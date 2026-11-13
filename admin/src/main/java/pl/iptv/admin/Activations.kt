package pl.iptv.admin

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.iptv.shared.ActivationFormat
import pl.iptv.shared.ActivationFormat.Entry
import java.io.IOException

/**
 * Aktywacje wersji zabezpieczonej: klucz podpisu (tylko na tym telefonie), lista urządzeń w repozytorium GitHub,
 * zgłoszenia urządzeń przez przekaźnik (kod, instalacja, wersja, model, czas).
 */
object Activations {
    private lateinit var sp: SharedPreferences
    var api = "https://api.github.com"
    var relay = "https://ntfy.sh"
    const val FILE = "activation.json"

    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("activations", Context.MODE_PRIVATE) }

    var privateKey: String get() = sp.getString("priv", "").orEmpty(); private set(v) = sp.edit().putString("priv", v).apply()
    var publicKey: String get() = sp.getString("pub", "").orEmpty(); private set(v) = sp.edit().putString("pub", v).apply()
    var repo: String get() = sp.getString("repo", "daniel01l/dplayer").orEmpty(); set(v) = sp.edit().putString("repo", v.trim().removePrefix("https://github.com/").trim('/')).apply()
    var token: String get() = sp.getString("token", "").orEmpty(); set(v) = sp.edit().putString("token", v.trim()).apply()
    val hasKey get() = privateKey.isNotBlank() && publicKey.isNotBlank()

    fun generateKey() { val (priv, pub) = ActivationFormat.newKeyPair(); privateKey = priv; publicKey = pub }

    /** Kopia klucza (tekst do zapisania w bezpiecznym miejscu). */
    fun exportKey(): String = JSONObject().put("type", "dplayer-key").put("priv", privateKey).put("pub", publicKey).toString()

    fun importKey(text: String) {
        val o = JSONObject(text.trim())
        require(o.optString("type") == "dplayer-key") { "To nie jest kopia klucza D-iptv" }
        val priv = o.getString("priv"); val pub = o.getString("pub")
        require(ActivationFormat.publicFromPrivateCheck(priv, pub)) { "Uszkodzona kopia klucza" }
        privateKey = priv; publicKey = pub
    }

    // ---------------- lista w repozytorium ----------------

    class Remote(val entries: Map<String, Entry>, val sha: String?, val ts: Long, val validSignature: Boolean)

    private fun gh(path: String) = Request.Builder().url("$api/repos/$repo/contents/$path")
        .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")

    /** Bieżąca lista (pusta, gdy pliku jeszcze nie ma). Poza wątkiem głównym. */
    fun load(): Remote {
        require(token.isNotBlank()) { "Najpierw wpisz token GitHub" }
        Admin.http.newCall(gh("$FILE?ref=main").get().build()).execute().use { r ->
            if (r.code == 404) return Remote(emptyMap(), null, 0, true)
            if (!r.isSuccessful) throw IOException(httpError(r.code))
            val o = JSONObject(r.body!!.string())
            val text = String(Base64.decode(o.getString("content"), Base64.DEFAULT))
            val (payload, sig) = ActivationFormat.unwrap(text)
            return Remote(ActivationFormat.entries(payload), o.getString("sha"), ActivationFormat.timestamp(payload),
                ActivationFormat.verify(payload, sig, publicKey))
        }
    }

    /** Zapisuje podpisaną listę (nowszy znacznik czasu niż poprzedni – urządzenia odrzucają starsze listy). */
    fun save(entries: Map<String, Entry>, prev: Remote, message: String): Remote {
        require(hasKey) { "Najpierw wygeneruj klucz" }
        val ts = maxOf(System.currentTimeMillis(), prev.ts + 1)
        val payload = ActivationFormat.payload(entries, ts)
        val file = ActivationFormat.wrap(payload, ActivationFormat.sign(payload, privateKey))
        val body = JSONObject().put("message", message).put("branch", "main")
            .put("content", Base64.encodeToString(file.toByteArray(), Base64.NO_WRAP))
        prev.sha?.let { body.put("sha", it) }
        Admin.http.newCall(gh(FILE).put(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(httpError(r.code))
            // nowy stan z zapisu (bez ponownego pobierania – GitHub przez chwilę zwraca jeszcze starą wersję pliku,
            // przez co zmiana, np. „Dodaj do Moich urządzeń”, nie była widoczna od razu po potwierdzeniu)
            val sha = runCatching { JSONObject(r.body!!.string()).getJSONObject("content").getString("sha") }.getOrNull()
            return Remote(entries, sha ?: prev.sha, ts, true)
        }
    }

    /** Zmiana jednego wpisu: pobierz → zmień → zapisz (zawsze na aktualnej wersji pliku). Zwraca zapisany stan. */
    fun update(message: String, change: (MutableMap<String, Entry>) -> Unit): Remote {
        val cur = load()
        val m = cur.entries.toMutableMap(); change(m)
        return save(m, cur, message)
    }

    private fun httpError(code: Int) = when (code) {
        401 -> "Token GitHub jest nieprawidłowy lub wygasł"
        403, 404 -> "Brak dostępu do repozytorium $repo (sprawdź nazwę i uprawnienia tokenu: Contents – Read and write)"
        409 -> "Plik zmienił się w międzyczasie – spróbuj ponownie"
        else -> "GitHub: HTTP $code"
    }

    // ---------------- zgłoszenia urządzeń ----------------

    /** [edition] – "full" (pełna) lub "test". */
    data class Seen(val code: String, val install: String, val ts: Long, val version: String, val model: String, val edition: String = "test", val deviceKey: String = "")

    /** Zapamiętane zgłoszenia (przekaźnik trzyma wiadomości tylko kilkanaście godzin). */
    fun seen(): List<Seen> = runCatching {
        val a = JSONArray(sp.getString("seen", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Seen(o.getString("c"), o.getString("i"), o.getLong("t"), o.optString("v"), o.optString("m"), o.optString("e", "test"), o.optString("k")) } }
    }.getOrDefault(emptyList())

    private fun saveSeen(l: List<Seen>) {
        // na każdą instalację tylko ostatnie zgłoszenie
        val latest = l.groupBy { it.code + "|" + it.install }.map { (_, v) -> v.maxBy { it.ts } }.sortedByDescending { it.ts }.take(500)
        sp.edit().putString("seen", JSONArray(latest.map { JSONObject().put("c", it.code).put("i", it.install).put("t", it.ts).put("v", it.version).put("m", it.model).put("e", it.edition).put("k", it.deviceKey) }).toString()).apply()
    }

    /** Pobiera nowe zgłoszenia; zwraca ich liczbę. Poza wątkiem głównym. */
    fun pollCheckins(): Int {
        if (publicKey.isBlank()) return 0
        val since = sp.getString("since", "").orEmpty().ifBlank { "24h" }
        val url = "$relay/${ActivationFormat.checkinTopic(publicKey)}/json?poll=1&since=$since"
        val body = Admin.http.newCall(Request.Builder().url(url).build()).execute().use { if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}"); it.body!!.string() }
        val add = ArrayList<Seen>()
        for (line in body.lines()) {
            val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (m.optString("event") != "message") continue
            sp.edit().putString("since", m.optString("id")).apply()
            val j = runCatching { JSONObject(m.optString("message")) }.getOrNull() ?: continue
            val code = ActivationFormat.normalize(j.optString("code")); if (code.length != 9) continue
            add += Seen(code, j.optString("inst").take(16), j.optLong("ts").takeIf { it > 0 } ?: m.optLong("time") * 1000, j.optString("ver").take(20), j.optString("model").take(40),
                if (j.optString("ed") == "full") "full" else "test", j.optString("dk").take(200))
        }
        if (add.isNotEmpty()) saveSeen(seen() + add)
        return add.size
    }

    /** Ten sam kod z różnych instalacji zgłoszony w ciągu godziny = prawdopodobnie skopiowane urządzenie. */
    fun duplicates(code: String, edition: String? = null): Int {
        // ta sama aplikacja (pełna lub testowa) – obie wersje na jednym urządzeniu to nie duplikat
        if (edition == null) return maxOf(duplicates(code, "full"), duplicates(code, "test"))
        val l = seen().filter { it.code == code && it.edition == edition }.sortedByDescending { it.ts }
        val newest = l.firstOrNull()?.ts ?: return 0
        return l.filter { newest - it.ts < 3_600_000L }.map { it.install }.distinct().size
    }

    /** Kod administratora zaszyfrowany dla urządzenia (do pola „k” listy); null – urządzenie jeszcze się nie zgłosiło z kluczem. */
    fun sealAdminCodeFor(code: String): String? {
        val dk = seen().filter { it.code == code && it.edition == "full" && it.deviceKey.isNotBlank() }.maxByOrNull { it.ts }?.deviceKey ?: return null
        return ActivationFormat.seal(Admin.code, ActivationFormat.sharedKey(privateKey, dk))
    }

    fun forget(code: String) = saveSeen(seen().filter { it.code != code })
}

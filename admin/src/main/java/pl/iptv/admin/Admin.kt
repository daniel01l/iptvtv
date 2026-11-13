package pl.iptv.admin

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.iptv.shared.RelayCrypto
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Urządzenie IptvTv widziane przez administratora (ostatni raport). */
data class Device(val j: JSONObject) {
    val id: String get() = j.optString("id")
    val name: String get() = j.optString("alias").ifBlank { j.optString("name") }
    val code: String get() = j.optString("code")
    val pin: String get() = j.optString("pin")
    val ts: Long get() = j.optLong("ts")
    val days: Int get() = j.optInt("days", -999)
    /** Token – tylko notatka administratora (nie jest wysyłany do urządzenia). */
    val token: String get() = j.optString("token")
    /** Kod aktywacji (XXXX-XXXX) – pusty w starszych wersjach aplikacji. */
    val act: String get() = j.optString("act")
    /** Dodane do „Moich urządzeń”, ale bez pierwszego raportu (urządzenie odbierze zmianę w ciągu kilku minut). */
    val pending: Boolean get() = j.optBoolean("pending")
    /** Kod pokazywany przy nazwie: kod aktywacji, a gdy go brak – kod przekaźnika. */
    val displayCode: String get() = act.ifBlank { code }
    /** Listy na urządzeniu (z ostatniego raportu). */
    val playlists: List<RemoteList> get() = j.optJSONArray("playlists")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { RemoteList(it.optString("id"), it.optString("name"), it.optBoolean("active"), it.optString("expiry")) }
    }.orEmpty()
}

/** Lista kanałów zapisana na urządzeniu. */
data class RemoteList(val id: String, val name: String, val active: Boolean, val expiry: String)

/** Lista zapisana w panelu administratora (do wysyłania na urządzenia). */
data class SavedList(val name: String, val url: String, val epg: String = "", val expiry: String = "")

/** Opis urządzenia w formacie „[Etykieta]: [Kod urządzenia]”. */
fun deviceLabel(label: String, code: String): String = "${label.trim().ifBlank { "Urządzenie" }}: ${code.ifBlank { "—" }}"

/** Skrzynka administratora i wysyłanie poleceń przez przekaźnik ntfy (szyfrowanie wspólne z IptvTv). */
object Admin {
    private lateinit var sp: SharedPreferences
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS).build()

    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("admin", Context.MODE_PRIVATE) }

    var code: String
        get() = sp.getString("code", "").orEmpty().ifBlank { RelayCrypto.newCode().also { code = it } }
        set(v) { sp.edit().putString("code", v).putString("since", "").apply() }
    var server: String
        get() = sp.getString("server", "").orEmpty().trim().trimEnd('/').ifBlank { RelayCrypto.DEFAULT_SERVER }
        set(v) = sp.edit().putString("server", v).apply()
    private var since: String
        get() = sp.getString("since", "").orEmpty()
        set(v) = sp.edit().putString("since", v).apply()

    fun devices(): List<Device> = runCatching {
        val o = JSONObject(sp.getString("devices", "{}")!!)
        o.keys().asSequence().map { Device(o.getJSONObject(it)) }.sortedBy { it.name.lowercase() }.toList()
    }.getOrDefault(emptyList())

    private fun saveDevice(j: JSONObject) {
        val o = JSONObject(sp.getString("devices", "{}")!!)
        // pierwszy raport urządzenia dodanego do „Moich urządzeń” zastępuje wpis oczekujący (nazwa z panelu zostaje)
        j.optString("act").takeIf { it.isNotBlank() && !j.optBoolean("pending") }?.let { act ->
            o.optJSONObject(pendingId(act))?.let { p ->
                if (j.optString("alias").isBlank()) p.optString("alias").takeIf { it.isNotBlank() }?.let { j.put("alias", it) }
                o.remove(pendingId(act))
            }
        }
        val old = o.optJSONObject(j.optString("id"))
        if (old != null && old.optLong("ts") > j.optLong("ts")) return
        old?.optString("alias")?.takeIf { it.isNotBlank() }?.let { j.put("alias", it) }
        old?.optString("token")?.takeIf { it.isNotBlank() && j.optString("token").isBlank() }?.let { j.put("token", it) }
        o.put(j.optString("id"), j); sp.edit().putString("devices", o.toString()).apply()
    }

    fun setAlias(d: Device, alias: String) { d.j.put("alias", alias); saveDevice(d.j) }
    fun setToken(d: Device, token: String) {
        val o = JSONObject(sp.getString("devices", "{}")!!)
        o.optJSONObject(d.id)?.let { it.put("token", token); sp.edit().putString("devices", o.toString()).apply() }
    }

    fun remove(d: Device) {
        val o = JSONObject(sp.getString("devices", "{}")!!); o.remove(d.id); sp.edit().putString("devices", o.toString()).apply()
    }

    // ---------------- „Moje urządzenia”: wpis od razu po dodaniu ----------------
    private fun pendingId(act: String) = "act:$act"

    /** Urządzenie dodane do „Moich urządzeń” pojawia się na liście od razu (do czasu pierwszego raportu – jako oczekujące). */
    fun addPending(act: String, label: String) {
        val o = JSONObject(sp.getString("devices", "{}")!!)
        if (o.keys().asSequence().any { o.optJSONObject(it)?.optString("act") == act && !o.optJSONObject(it)!!.optBoolean("pending") }) return
        o.put(pendingId(act), JSONObject().put("id", pendingId(act)).put("act", act).put("pending", true)
            .put("name", label.ifBlank { "Urządzenie" }).put("ts", System.currentTimeMillis()))
        sp.edit().putString("devices", o.toString()).apply()
    }

    /** Usunięcie z „Moich urządzeń” – znika też wpis oczekujący. */
    fun removePending(act: String) {
        val o = JSONObject(sp.getString("devices", "{}")!!); o.remove(pendingId(act)); sp.edit().putString("devices", o.toString()).apply()
    }

    // ---------------- zapisane listy (wybór przy wysyłaniu) ----------------
    fun savedLists(): List<SavedList> = runCatching {
        val a = org.json.JSONArray(sp.getString("lists", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SavedList(o.optString("name"), o.optString("url"), o.optString("epg"), o.optString("expiry")) } }
    }.getOrDefault(emptyList())

    private fun saveLists(l: List<SavedList>) = sp.edit().putString("lists", org.json.JSONArray(l.map {
        JSONObject().put("name", it.name).put("url", it.url).put("epg", it.epg).put("expiry", it.expiry) }).toString()).apply()

    /** Dodaje lub zastępuje (ta sama nazwa) listę. */
    fun putList(l: SavedList) = saveLists(savedLists().filter { it.name != l.name } + l)
    fun removeList(name: String) = saveLists(savedLists().filter { it.name != name })

    /** Ustawia aktywną listę na urządzeniu (spośród list zapisanych na nim). */
    fun useList(d: Device, id: String) = command(d, "use_list", mapOf("id" to id))

    /** Pobiera nowe raporty urządzeń; zwraca ich liczbę. Poza wątkiem głównym. */
    fun poll(): Int {
        val url = "$server/${RelayCrypto.adminTopic(code)}/json?poll=1&since=${since.ifBlank { "all" }}"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}"); it.body!!.string()
        }
        var n = 0
        for (line in body.lines()) {
            val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (m.optString("event") != "message") continue
            since = m.optString("id")
            val blob = runCatching {
                m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotBlank() }
                    ?.let { u -> http.newCall(Request.Builder().url(u).build()).execute().use { r -> r.body!!.bytes() } }
                    ?: Base64.decode(m.optString("message"), Base64.DEFAULT)
            }.getOrNull() ?: continue
            val j = runCatching { JSONObject(String(RelayCrypto.decrypt(code, blob))) }.getOrNull() ?: continue
            if (j.optString("type") == "status" && j.optString("id").isNotBlank()) { saveDevice(j); n++ }
        }
        return n
    }

    private fun send(d: Device, payload: JSONObject) {
        val blob = RelayCrypto.encrypt(d.code, payload.put("pin", d.pin).toString().toByteArray())
        val req = Request.Builder().url("$server/${RelayCrypto.topic(d.code)}").header("Filename", "dane.bin").header("Title", "IptvTv")
            .put(blob.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}") }
    }

    fun command(d: Device, cmd: String, extra: Map<String, String> = emptyMap()) =
        send(d, JSONObject().put("type", "cmd").put("cmd", cmd).apply { extra.forEach { (k, v) -> put(k, v) } })

    /** Lista od administratora od razu staje się aktywna na urządzeniu (EPG puste = zostaje dotychczasowe). */
    fun sendList(d: Device, name: String, url: String, file: ByteArray?, epg: String, expiry: String) = send(d, JSONObject()
        .put("name", name).put("url", url).put("epg", epg).put("expiry", expiry).put("activate", true)
        .put("file", if (file != null && file.isNotEmpty()) Base64.encodeToString(file, Base64.NO_WRAP) else ""))

    fun sendApk(d: Device, apk: ByteArray) = send(d, JSONObject().put("type", "apk").put("apk", Base64.encodeToString(apk, Base64.NO_WRAP)))
}

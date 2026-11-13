package pl.iptv.tv.remote

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.iptv.shared.RelayCrypto
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dodawanie listy przez Internet (z dowolnej sieci) przez przekaźnik ntfy (domyślnie ntfy.sh).
 * Telewizor odpytuje swój temat co 30 s; treść jest szyfrowana AES-256-GCM kluczem z kodu urządzenia.
 */
object RemoteRelay {
    const val DEFAULT_SERVER = RelayCrypto.DEFAULT_SERVER
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val MAGIC = "IPTV1"
    private const val POLL_MS = 30_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    val server get() = Prefs.relayServer.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }

    fun ensureCode(): String {
        if (normalize(Prefs.relayCode).length != 12) { Prefs.relayCode = RelayCrypto.newCode(); Prefs.relaySince = "" }
        return Prefs.relayCode
    }

    fun normalize(code: String) = RelayCrypto.normalize(code)
    fun topic(code: String) = RelayCrypto.topic(code)
    fun encrypt(code: String, plain: ByteArray) = RelayCrypto.encrypt(code, plain)
    fun decrypt(code: String, blob: ByteArray) = RelayCrypto.decrypt(code, blob)

    // ---------------- wysyłanie (telefon) ----------------

    /** Wysyła listę na urządzenie o kodzie [code]. Wywoływać poza wątkiem głównym. */
    fun send(code: String, pin: String, name: String, url: String, file: ByteArray?, epg: String, expiry: String, srv: String = server) {
        require(normalize(code).length == 12) { "Kod urządzenia ma 12 znaków (XXXX-XXXX-XXXX)" }
        val payload = JSONObject().put("pin", pin).put("name", name).put("url", url).put("epg", epg).put("expiry", expiry)
            .put("file", if (file != null && file.isNotEmpty()) Base64.encodeToString(file, Base64.NO_WRAP) else "")
        val blob = encrypt(code, payload.toString().toByteArray())
        val req = Request.Builder().url("${srv.trimEnd('/')}/${topic(code)}")
            .header("Filename", "lista.bin").header("Title", "IptvTv")
            .put(blob.toRequestBody("application/octet-stream".toMediaType())).build()
        Repository.http.newCall(req).execute().use { if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}") }
    }

    /** Wysyła plik aktualizacji (APK) przez przekaźnik. */
    fun sendApk(code: String, pin: String, apk: ByteArray, srv: String = server) {
        require(normalize(code).length == 12) { "Kod urządzenia ma 12 znaków (XXXX-XXXX-XXXX)" }
        val payload = JSONObject().put("pin", pin).put("type", "apk").put("apk", Base64.encodeToString(apk, Base64.NO_WRAP))
        val req = Request.Builder().url("${srv.trimEnd('/')}/${topic(code)}").header("Filename", "aktualizacja.bin").header("Title", "IptvTv")
            .put(encrypt(code, payload.toString().toByteArray()).toRequestBody("application/octet-stream".toMediaType())).build()
        Repository.http.newBuilder().writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute()
            .use { if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}") }
    }

    // ---------------- zdalne zarządzanie: raport do administratora ----------------
    /** Czy dzisiaj wysłano już raport (raport raz dziennie – przy pierwszym włączeniu aplikacji). */
    fun reportedToday(now: Long = System.currentTimeMillis()): Boolean {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return Prefs.adminLastReport > 0 && f.format(java.util.Date(Prefs.adminLastReport)) == f.format(java.util.Date(now))
    }

    fun deviceId(): String = Prefs.deviceId.ifBlank { java.util.UUID.randomUUID().toString().also { Prefs.deviceId = it } }

    fun status(msg: String): JSONObject {
        val act = pl.iptv.tv.data.Playlists.active()
        val c = RemoteServer.appContext
        return JSONObject().put("type", "status").put("id", deviceId())
            .put("name", Prefs.deviceLabel.ifBlank { RemoteServer.deviceName }).put("model", RemoteServer.deviceName)
            .put("code", ensureCode()).put("pin", RemoteServer.ensurePin())
            .put("ip", RemoteServer.localIps().joinToString(", ")).put("ver", pl.iptv.tv.update.Updater.currentName(c))
            .put("list", act?.name ?: "").put("days", pl.iptv.tv.data.Playlists.daysLeft() ?: -999)
            .put("expiry", pl.iptv.tv.data.Playlists.formatDate(act?.expiry ?: 0)).put("channels", Repository.allChannels.size)
            .put("epg", Repository.allChannels.count { it.epgKey != null }).put("lists", pl.iptv.tv.data.Playlists.all().size)
            .put("watching", Repository.byKey(Prefs.lastChannel)?.name ?: "").put("msg", msg).put("ts", System.currentTimeMillis())
            // kod aktywacji (XXXX-XXXX) – administrator łączy raport z wpisem w „Moich urządzeniach”
            .put("act", runCatching { pl.iptv.tv.data.Activation.code }.getOrDefault(""))
            // listy na urządzeniu – wybór aktywnej listy z panelu administratora
            .put("playlists", org.json.JSONArray().apply {
                pl.iptv.tv.data.Playlists.all().forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("active", p.id == Prefs.activePlaylist)
                        .put("expiry", pl.iptv.tv.data.Playlists.formatDate(p.expiry)))
                }
            })
    }

    /** Wysyła stan urządzenia do skrzynki administratora (wiadomość tekstowa, szyfrowana kodem administratora). */
    fun report(msg: String = "") {
        val admin = Prefs.adminCode
        if (RelayCrypto.normalize(admin).length != 12) return
        val body = Base64.encodeToString(encrypt(admin, status(msg).toString().toByteArray()), Base64.NO_WRAP)
        val req = Request.Builder().url("$server/${RelayCrypto.adminTopic(admin)}").post(body.toRequestBody("text/plain".toMediaType())).build()
        Repository.http.newCall(req).execute().use { if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}") }
        Prefs.adminLastReport = System.currentTimeMillis()
    }

    /** Polecenie od administratora; zwraca opis wyniku. */
    private fun command(json: JSONObject): String {
        val c = RemoteServer.appContext
        return when (json.optString("cmd")) {
            "refresh" -> kotlinx.coroutines.runBlocking {
                val n = runCatching { Repository.loadPlaylist(Prefs.playlistSource) }.getOrNull()
                val e = if (Prefs.effectiveEpgUrl.isNotBlank()) runCatching { Repository.loadEpg() }.getOrNull() else null
                RemoteServer.notifyChanged()
                "Odświeżono: ${n ?: "błąd listy"} kanałów, EPG: ${e ?: "bez zmian"}"
            }
            "update" -> kotlinx.coroutines.runBlocking { runCatching { pl.iptv.tv.update.Updater.updateFromUrl(c) }.getOrElse { "Błąd: ${it.message}" } }
            "set_update_url" -> { Prefs.updateUrl = json.optString("url"); "Ustawiono adres aktualizacji" }
            "set_epg" -> kotlinx.coroutines.runBlocking {
                Prefs.epgUrl = json.optString("url"); pl.iptv.tv.data.Playlists.captureActive()
                val n = runCatching { Repository.loadEpg() }
                RemoteServer.notifyChanged()
                n.fold({ "EPG ustawione: $it programów" }, { "EPG ustawione, błąd pobierania: ${it.message}" })
            }
            // token jest tylko informacją w aplikacji administratora – nie zmienia listy ani EPG
            "set_token" -> "Token zapisany w panelu administratora"
            // wybór aktywnej listy z panelu administratora
            "use_list" -> {
                val p = pl.iptv.tv.data.Playlists.all().firstOrNull { it.id == json.optString("id") } ?: return "Brak listy na urządzeniu"
                kotlinx.coroutines.runBlocking {
                    val n = runCatching { Repository.switchPlaylist(p.id) }
                    RemoteServer.notifyChanged()
                    n.fold({ "Aktywna lista: ${p.name} ($it kanałów)" }, { "Lista ${p.name}: błąd wczytywania – ${it.message}" })
                }
            }
            "set_expiry" -> pl.iptv.tv.data.Playlists.active()?.let { a ->
                val d = pl.iptv.tv.data.Playlists.parseDate(json.optString("date")) ?: return "Błędna data"
                pl.iptv.tv.data.Playlists.update(a.copy(expiry = d)); "Data ważności: ${json.optString("date")}"
            } ?: "Brak aktywnej listy"
            else -> "Raport"
        }
    }

    // ---------------- odbiór (telewizor) ----------------

    /** Jednorazowe sprawdzenie skrzynki; zwraca liczbę odebranych list. Poza wątkiem głównym. */
    fun pollOnce(): Int {
        val code = ensureCode()
        val since = Prefs.relaySince.ifBlank { "all" }
        val req = Request.Builder().url("$server/${topic(code)}/json?poll=1&since=$since").build()
        val body = Repository.http.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("Przekaźnik: HTTP ${it.code}"); it.body!!.string()
        }
        var n = 0
        for (line in body.lines()) {
            if (line.isBlank()) continue
            val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (m.optString("event") != "message") continue
            Prefs.relaySince = m.optString("id").ifBlank { Prefs.relaySince }
            val blob = runCatching {
                m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotBlank() }?.let { u ->
                    Repository.http.newCall(Request.Builder().url(u).build()).execute().use { r -> r.body!!.bytes() }
                } ?: Base64.decode(m.optString("message"), Base64.DEFAULT)
            }.getOrNull() ?: continue
            val json = runCatching { JSONObject(String(decrypt(code, blob))) }.getOrNull() ?: continue   // nie nasz / zły kod
            // wersja zabezpieczona: bez zdalnych poleceń i instalacji (administrator tylko aktywuje / blokuje)
            if (pl.iptv.tv.BuildConfig.SECURE && json.optString("type") in setOf("cmd", "apk")) continue
            if (json.optString("type") == "cmd") {
                if (json.optString("pin") == Prefs.remotePin) { val m = command(json); runCatching { report(m) }; n++ }
                continue
            }
            if (json.optString("type") == "apk") {
                val res = RemoteServer.receiveApk(json.optString("pin"), Base64.decode(json.optString("apk"), Base64.DEFAULT))
                if (res.first) n++
                continue
            }
            val file = json.optString("file").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) } ?: ByteArray(0)
            val res = RemoteServer.receive(json.optString("pin"), json.optString("name"), json.optString("url"), file,
                json.optString("epg"), json.optString("expiry"), json.optBoolean("activate", false))
            if (res.first) n++
        }
        return n
    }

    fun start(c: Context) {
        RemoteServer.init(c)
        if (thread != null) return
        ensureCode(); RemoteServer.ensurePin()
        val t = HandlerThread("RemoteRelay", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        thread = t
        val h = Handler(t.looper); handler = h
        val loop = object : Runnable {
            override fun run() {
                runCatching { if (pollOnce() > 0) report("Odebrano dane") }
                if (Prefs.adminCode.isNotBlank() && !reportedToday()) runCatching { report("Pierwsze włączenie dzisiaj") }
                h.postDelayed(this, POLL_MS)
            }
        }
        h.post(loop)
    }

    fun stop() { thread?.quitSafely(); thread = null; handler = null }
    val running get() = thread != null
}

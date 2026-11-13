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
}

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

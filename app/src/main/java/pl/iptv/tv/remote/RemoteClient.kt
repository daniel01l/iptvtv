package pl.iptv.tv.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.iptv.tv.data.Repository
import java.net.URLEncoder

/** Wysyłanie listy na inne urządzenie z IptvTv (wyszukiwanie w sieci przez NSD/mDNS). */
object RemoteClient {
    data class Device(val name: String, val host: String, val port: Int)

    /** Szuka urządzeń przez [ms] i zwraca wynik na wątku głównym. */
    fun discover(c: Context, ms: Long, done: (List<Device>) -> Unit) {
        val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = LinkedHashMap<String, Device>()
        val queue = ArrayDeque<NsdServiceInfo>(); var resolving = false
        val main = Handler(Looper.getMainLooper())
        val lock = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("iptvtv")?.apply { runCatching { acquire() } }
        fun next() {
            if (resolving) return
            val s = queue.removeFirstOrNull() ?: return
            resolving = true
            runCatching {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { main.post { resolving = false; next() } }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val h = i.host?.hostAddress
                        main.post {
                            if (h != null) found[h] = Device(i.serviceName.removePrefix("IptvTv-"), h, i.port)
                            resolving = false; next()
                        }
                    }
                })
            }.onFailure { resolving = false }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { main.post { queue.add(s); next() } }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
        main.postDelayed({
            runCatching { nsd.stopServiceDiscovery(l) }; runCatching { lock?.release() }
            done(found.values.toList())
        }, ms)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Wysyła plik aktualizacji (APK). Poza wątkiem głównym. */
    fun sendApk(host: String, port: Int, pin: String, apk: ByteArray): String {
        val req = Request.Builder().url("http://$host:$port/api/apk").header("X-Pin", pin)
            .post(apk.toRequestBody("application/vnd.android.package-archive".toMediaType())).build()
        Repository.http.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            val j = runCatching { JSONObject(r.body!!.string()) }.getOrNull()
            if (j?.optBoolean("ok") == true) return j.optString("message")
            throw java.io.IOException(j?.optString("message") ?: "HTTP ${r.code}")
        }
    }

    /** Wysyła listę (plik lub link). Wywoływać poza wątkiem głównym. */
    fun send(host: String, port: Int, pin: String, name: String, url: String, bytes: ByteArray?, epg: String, expiry: String, activate: Boolean): String {
        val req = Request.Builder().url("http://$host:$port/api/playlist")
            .header("X-Pin", pin).header("X-Name", enc(name)).header("X-Url", enc(url)).header("X-Epg", enc(epg))
            .header("X-Expiry", enc(expiry)).header("X-Activate", if (activate) "1" else "0")
            .post((bytes ?: ByteArray(0)).toRequestBody("application/octet-stream".toMediaType())).build()
        Repository.http.newCall(req).execute().use { r ->
            val j = runCatching { JSONObject(r.body!!.string()) }.getOrNull()
            if (j?.optBoolean("ok") == true) return j.optString("message")
            throw java.io.IOException(j?.optString("message") ?: "HTTP ${r.code}")
        }
    }
}

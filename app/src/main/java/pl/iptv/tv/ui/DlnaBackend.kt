package pl.iptv.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * (7.28) Przesyłanie DLNA / UPnP (telewizory Samsung, LG, Sony, Philips, boxy z odbiornikiem DLNA): wyszukiwanie SSDP
 * (`urn:schemas-upnp-org:service:AVTransport:1`, co [SEARCH_MS]), opis urządzenia (friendlyName, controlURL usług AVTransport
 * i RenderingControl), sterowanie SOAP. Start: `Stop` → `SetAVTransportURI` (adres z [CastProxy], metadane DIDL-Lite) → [PLAY_DELAY_MS]
 * → `Play` (ponowienie po [PLAY_RETRY_MS]). Pozycja / stan z `GetPositionInfo` / `GetTransportInfo` co [POLL_MS] w tle
 * (wątek główny nigdy nie czeka na sieć). Wszystko na jednym wątku tła („Dlna”).
 */
class DlnaBackend : CastHub.Backend {
    companion object {
        const val PREFIX = "dlna:"
        const val SEARCH_MS = 8_000L
        const val PLAY_DELAY_MS = 300L
        const val PLAY_RETRY_MS = 500L
        const val POLL_MS = 1_000L
        const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Metadane DIDL-Lite (tytuł, klasa, protokół http-get z typem MIME). */
        fun didl(m: CastHub.Media, url: String, mime: String): String {
            val cls = if (mime.startsWith("audio")) "object.item.audioItem.musicTrack" else "object.item.videoItem"
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${esc(m.title)}</dc:title><upnp:class>$cls</upnp:class>" +
                (if (m.image.startsWith("http")) "<upnp:albumArtURI>${esc(m.image)}</upnp:albumArtURI>" else "") +
                "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
        }

        fun ms(t: String?): Long {
            val p = t?.trim()?.split(':') ?: return 0
            if (p.size != 3) return 0
            return ((p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60 + (p[2].substringBefore('.').toLongOrNull() ?: 0)) * 1000
        }
        fun hms(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
        fun tag(xml: String, name: String): String? = Regex("<(?:\\w+:)?$name[^>]*>([\\s\\S]*?)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()
    }

    private class Renderer(val id: String, val name: String, val avControl: String, val rcControl: String?)

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Dlna").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private val found = java.util.concurrent.ConcurrentHashMap<String, Renderer>()
    private var onDevices: ((List<CastHub.Device>) -> Unit)? = null
    private var searching = false
    private var lock: android.net.wifi.WifiManager.MulticastLock? = null
    private var app: Context? = null

    @Volatile private var current: Renderer? = null
    @Volatile private var cb: ((CastHub.Result) -> Unit)? = null
    @Volatile private var lastPos = 0L
    @Volatile private var lastDur = 0L
    @Volatile private var paused = false
    @Volatile private var sawPlaying = false
    @Volatile private var volume = 50
    @Volatile private var muted = false
    @Volatile private var generation = 0

    override fun init(c: Context): Boolean { app = c.applicationContext; return true }

    override fun startDiscovery(onDevices: (List<CastHub.Device>) -> Unit) {
        this.onDevices = onDevices
        if (searching) { publish(); return }
        searching = true
        lock = runCatching {
            (app?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.createMulticastLock("dlna")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        io.execute(::search)
    }

    override fun stopDiscovery() {
        searching = false; onDevices = null
        runCatching { lock?.release() }; lock = null
    }

    private fun publish() {
        val list = found.values.map { CastHub.Device(PREFIX + it.id, it.name, "DLNA") }
        main.post { onDevices?.invoke(list) }
    }

    private fun search() {
        if (!searching) return
        runCatching {
            MulticastSocket().use { s ->
                s.soTimeout = 2500
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AV\r\n\r\n".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048); val seen = HashSet<String>()
                val end = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: Exception) { break }
                    val txt = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(txt)?.groupValues?.get(1) ?: continue
                    if (seen.add(loc)) runCatching { describe(loc) }?.getOrNull()?.let { r -> found[r.id] = r }
                }
            }
        }
        publish()
        if (searching) io.schedule(::search, SEARCH_MS, TimeUnit.MILLISECONDS)
    }

    private fun describe(location: String): Renderer? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val name = tag(xml, "friendlyName") ?: return null
        val udn = tag(xml, "UDN") ?: location
        fun control(type: String): String? {
            val svc = Regex("<(?:\\w+:)?service>([\\s\\S]*?)</(?:\\w+:)?service>").findAll(xml).map { it.groupValues[1] }
                .firstOrNull { tag(it, "serviceType")?.startsWith(type.substringBeforeLast(':')) == true } ?: return null
            val c = tag(svc, "controlURL") ?: return null
            return URI(location).resolve(c).toString()
        }
        val av = control(AV) ?: return null
        return Renderer(udn, name, av, control(RC))
    }

    private fun soap(url: String, service: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(url).header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("$action: HTTP ${r.code}")
            t
        }
    }

    private fun av(action: String, args: String = "") = soap(current!!.avControl, AV, action, "<InstanceID>0</InstanceID>$args")

    override fun cast(device: CastHub.Device, media: CastHub.Media, mime: String, cb: (CastHub.Result) -> Unit) {
        val r = found[device.id.removePrefix(PREFIX)] ?: run { cb(CastHub.Result.Failed("Urządzenie „${device.name}” jest już niedostępne")); return }
        this.cb = cb
        val gen = ++generation
        io.execute {
            try {
                current = r; sawPlaying = false; paused = false; lastPos = media.positionMs
                runCatching { av("Stop") }
                val meta = esc(didl(media, media.url, mime))
                av("SetAVTransportURI", "<CurrentURI>${esc(media.url)}</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>")
                Thread.sleep(PLAY_DELAY_MS)
                try { av("Play", "<Speed>1</Speed>") } catch (_: Exception) { Thread.sleep(PLAY_RETRY_MS); av("Play", "<Speed>1</Speed>") }
                if (!media.live && media.positionMs > 0) runCatching { av("Seek", "<Unit>REL_TIME</Unit><Target>${hms(media.positionMs)}</Target>") }
                if (gen == generation) { cb(CastHub.Result.Playing(device)); poll(gen) }
            } catch (e: Exception) {
                if (gen == generation) cb(CastHub.Result.Failed("„${device.name}” nie przyjął strumienia"))
            }
        }
    }

    private fun poll(gen: Int) {
        if (gen != generation || current == null) return
        runCatching {
            val pos = av("GetPositionInfo")
            lastPos = ms(tag(pos, "RelTime")); ms(tag(pos, "TrackDuration")).takeIf { it > 0 }?.let { lastDur = it }
            val state = tag(av("GetTransportInfo"), "CurrentTransportState").orEmpty()
            paused = state == "PAUSED_PLAYBACK"
            if (state == "PLAYING") sawPlaying = true
            if (state == "STOPPED" && sawPlaying && gen == generation) {
                val near = lastDur > 0 && lastPos >= lastDur - 5_000
                generation++; cb?.invoke(CastHub.Result.Ended(lastPos, finished = near)); return
            }
        }
        io.schedule({ poll(gen) }, POLL_MS, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        generation++
        val r = current ?: return
        io.execute { runCatching { soap(r.avControl, AV, "Stop", "<InstanceID>0</InstanceID>") } }
        current = null
    }

    override fun positionMs(): Long = lastPos
    override fun durationMs(): Long = lastDur
    override fun isPaused(): Boolean = paused
    override fun play() { paused = false; io.execute { runCatching { av("Play", "<Speed>1</Speed>") } } }
    override fun pause() { paused = true; io.execute { runCatching { av("Pause") } } }
    override fun seekTo(ms: Long) { lastPos = ms; io.execute { runCatching { av("Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>") } } }
    override fun volume(): Float = volume / 100f
    override fun isMuted(): Boolean = muted
    override fun setVolume(v: Float) {
        volume = (v * 100).toInt().coerceIn(0, 100)
        val rc = current?.rcControl ?: return
        io.execute { runCatching { soap(rc, RC, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$volume</DesiredVolume>") } }
    }
    override fun setMuted(m: Boolean) {
        muted = m
        val rc = current?.rcControl ?: return
        io.execute { runCatching { soap(rc, RC, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (m) 1 else 0}</DesiredMute>") } }
    }
}

/** (7.28) Google Cast + DLNA jako jeden backend [CastHub] – urządzenia obu rodzajów na jednej liście, polecenia do właściwego. */
class MultiCastBackend(private val parts: List<CastHub.Backend>) : CastHub.Backend {
    private val lists = HashMap<Int, List<CastHub.Device>>()
    @Volatile private var active: CastHub.Backend? = null
    private var live: List<CastHub.Backend> = emptyList()

    override fun init(c: Context): Boolean { live = parts.filter { runCatching { it.init(c) }.getOrDefault(false) }; return live.isNotEmpty() }
    override fun startDiscovery(onDevices: (List<CastHub.Device>) -> Unit) {
        live.forEachIndexed { i, b -> b.startDiscovery { l -> lists[i] = l; onDevices(lists.toSortedMap().values.flatten()) } }
    }
    override fun stopDiscovery() = live.forEach { runCatching { it.stopDiscovery() } }
    private fun owner(d: CastHub.Device) = if (d.id.startsWith(DlnaBackend.PREFIX)) live.firstOrNull { it is DlnaBackend } else live.firstOrNull { it !is DlnaBackend }
    override fun cast(device: CastHub.Device, media: CastHub.Media, mime: String, cb: (CastHub.Result) -> Unit) {
        val b = owner(device) ?: run { cb(CastHub.Result.Failed("Przesyłanie niedostępne")); return }
        if (active != null && active !== b) runCatching { active?.stop() }
        active = b; b.cast(device, media, mime, cb)
    }
    override fun stop() { runCatching { active?.stop() } }
    override fun positionMs() = active?.positionMs() ?: 0L
    override fun durationMs() = active?.durationMs() ?: 0L
    override fun isPaused() = active?.isPaused() ?: false
    override fun play() { active?.play() }
    override fun pause() { active?.pause() }
    override fun seekTo(ms: Long) { active?.seekTo(ms) }
    override fun volume() = active?.volume() ?: 0.5f
    override fun setVolume(v: Float) { active?.setVolume(v) }
    override fun isMuted() = active?.isMuted() ?: false
    override fun setMuted(m: Boolean) { active?.setMuted(m) }
    override fun tracks(type: Int) = active?.tracks(type) ?: emptyList()
    override fun selectTrack(type: Int, id: Long?) { active?.selectTrack(type, id) }
}

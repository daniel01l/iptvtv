package pl.iptv.tv.ui

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * (7.28) Lokalny, strumieniowy serwer proxy HTTP dla przesyłania (Google Cast – Default Media Receiver, DLNA / UPnP).
 *
 * Odbiornik pobiera strumień z telefonu / TV (`http://<IP Wi-Fi>:<port>/s/<id>`), a proxy:
 * - pobiera źródło przez OkHttp z przekierowaniami 301/302 i nagłówkami listy ([CastHub.Media.headers] – User-Agent, Referer, Origin),
 * - odpowiada z pełnym CORS (`Access-Control-Allow-Origin: *`, metody GET/HEAD/OPTIONS, `Expose-Headers: *`), obsługuje `OPTIONS`,
 * - przekazuje `Range` → `206 Partial Content` (`Content-Range`, `Accept-Ranges`),
 * - surowy MPEG-TS (.ts / Live bez rozszerzenia) z `Content-Type: video/mp2t`,
 * - manifesty HLS (.m3u8) przepisuje: adresy segmentów / list przez proxy, wariant z dźwiękiem AAC (`mp4a.40.2`) pierwszy,
 *   ścieżka audio AAC jako DEFAULT (AC3 / E-AC3 / MP2 często bez dźwięku na odbiornikach).
 * Dane płyną blokami ([BUF] 64 KB) prosto z gniazda do gniazda – bez buforowania całości w RAM; wątki z puli ([MAX_THREADS]),
 * priorytet tła. Serwer działa tylko w trakcie sesji ([start] / [stop] z [CastHub]).
 */
object CastProxy {
    const val BUF = 64 * 1024
    const val MAX_THREADS = 12
    const val HEADER_TIMEOUT_MS = 8_000
    const val CORS_HEADERS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
        "Access-Control-Allow-Headers: *\r\nAccess-Control-Expose-Headers: *\r\n"
    /** Kodek AAC LC w atrybucie CODECS manifestu HLS. */
    const val AAC = "mp4a.40.2"

    /** Robolectric / testy: bez proxy (adresy przekazywane bez zmian). */
    @Volatile var enabled = android.os.Build.FINGERPRINT != "robolectric"

    private class Entry(val url: String, val headers: Map<String, String>, val live: Boolean)
    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ThreadPoolExecutor? = null
    private var counter = 0

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    val running get() = server != null
    val port get() = server?.localPort ?: 0

    /** Adres dla odbiornika: [m] przez proxy (http/https), inne bez zmian; null – proxy niedostępne (adres oryginalny). */
    @Synchronized
    fun wrap(m: CastHub.Media): CastHub.Media {
        if (!enabled) return m
        val scheme = m.url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return m
        if (!start()) return m
        val ip = runCatching { pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull() }.getOrNull() ?: return m
        val id = "c${++counter}"
        entries[id] = Entry(m.url, m.headers, m.live)
        if (entries.size > 8) entries.keys.sortedBy { it.drop(1).toIntOrNull() ?: 0 }.take(entries.size - 8).forEach { entries.remove(it) }
        val ext = when {
            isHls(m.url) -> ".m3u8"
            CastHub.castMime(m) == "video/mp2t" -> ".ts"
            else -> m.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..4 }?.let { ".$it" }.orEmpty()
        }
        return m.copy(url = "http://$ip:$port/s/$id$ext", headers = emptyMap())
    }

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val s = ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"))
            val p = ThreadPoolExecutor(2, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }; r.run() }, "CastProxy").apply { isDaemon = true }
            }
            server = s; pool = p
            Thread({
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    try { p.execute { serve(c) } } catch (_: Exception) { runCatching { c.close() } }
                }
            }, "CastProxyAccept").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }; server = null
        pool?.shutdownNow(); pool = null
        entries.clear()
    }

    fun isHls(url: String): Boolean { val u = url.lowercase().substringBefore('?'); return u.endsWith(".m3u8") || u.endsWith(".m3u") }

    private fun serve(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = HEADER_TIMEOUT_MS
                val inp = BufferedInputStream(sock.getInputStream())
                val line = readLine(inp) ?: return
                val parts = line.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase(); val path = parts[1]
                val reqHeaders = HashMap<String, String>()
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':'); if (i > 0) reqHeaders[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                sock.soTimeout = 30_000
                val out = sock.getOutputStream()
                if (method == "OPTIONS") { write(out, "HTTP/1.1 204 No Content\r\n${CORS_HEADERS}Content-Length: 0\r\nConnection: close\r\n\r\n"); return }
                if (method != "GET" && method != "HEAD") { write(out, "HTTP/1.1 405 Method Not Allowed\r\n${CORS_HEADERS}Content-Length: 0\r\nConnection: close\r\n\r\n"); return }
                val (entry, target) = resolve(path) ?: run { write(out, "HTTP/1.1 404 Not Found\r\n${CORS_HEADERS}Content-Length: 0\r\nConnection: close\r\n\r\n"); return }
                proxy(out, method == "HEAD", entry, target, reqHeaders["range"], path.substringAfter("/s/").substringBefore('.').substringBefore('/'))
            } catch (_: Exception) { }
        }
    }

    /** `/s/<id>[.ext]` – adres główny; `/u/<id>?u=<adres>` – segment / lista z przepisanego manifestu. */
    private fun resolve(path: String): Pair<Entry, String>? {
        return when {
            path.startsWith("/s/") -> { val id = path.removePrefix("/s/").substringBefore('.').substringBefore('?'); entries[id]?.let { it to it.url } }
            path.startsWith("/u/") -> {
                val id = path.removePrefix("/u/").substringBefore('?')
                val u = path.substringAfter("?u=", "").let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: return null
                entries[id]?.let { it to u }
            }
            else -> null
        }
    }

    private fun proxy(out: OutputStream, head: Boolean, e: Entry, url: String, range: String?, id: String) {
        val rb = Request.Builder().url(url)
        e.headers.forEach { (k, v) -> runCatching { rb.header(k, v) } }
        if (range != null) rb.header("Range", range)
        http.newCall(rb.build()).execute().use { r ->
            val body = r.body
            val upstreamType = r.header("Content-Type").orEmpty()
            val finalUrl = r.request.url.toString()
            val hls = isHls(finalUrl) || isHls(url) || upstreamType.contains("mpegurl", true)
            if (hls && r.isSuccessful && body != null) {
                val text = rewriteHls(body.string(), finalUrl, id)
                val bytes = text.toByteArray(Charsets.UTF_8)
                write(out, "HTTP/1.1 200 OK\r\n${CORS_HEADERS}Content-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                if (!head) out.write(bytes)
                out.flush(); return
            }
            val type = when {
                url.lowercase().substringBefore('?').endsWith(".ts") || (e.live && (upstreamType.isBlank() || upstreamType.startsWith("application/octet", true) ||
                    upstreamType.startsWith("text/", true))) -> "video/mp2t"
                upstreamType.isNotBlank() -> upstreamType
                else -> "application/octet-stream"
            }
            val sb = StringBuilder("HTTP/1.1 ${r.code} ${if (r.code == 206) "Partial Content" else r.message.ifBlank { "OK" }}\r\n")
            sb.append(CORS_HEADERS).append("Content-Type: ").append(type).append("\r\n")
            r.header("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
            r.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
            sb.append("Accept-Ranges: ").append(r.header("Accept-Ranges") ?: if (e.live) "none" else "bytes").append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            write(out, sb.toString())
            if (head || body == null) { out.flush(); return }
            body.byteStream().use { src -> copy(src, out) }
        }
    }

    private fun copy(src: InputStream, out: OutputStream) {
        val buf = ByteArray(BUF)
        while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        out.flush()
    }

    /**
     * Manifest HLS: każdy adres (segment, lista, klucz, `URI="…"`) – przez proxy (nagłówki źródła), względne – wg [base];
     * lista wariantów: najpierw warianty z AAC (`mp4a.40.2`), ścieżka `EXT-X-MEDIA TYPE=AUDIO` z AAC – DEFAULT=YES (inne NO).
     */
    fun rewriteHls(text: String, base: String, id: String): String {
        val lines = text.lines()
        fun prox(u: String): String {
            val abs = runCatching { URI(base).resolve(u.trim()).toString() }.getOrDefault(u)
            return "/u/$id?u=" + URLEncoder.encode(abs, "UTF-8")
        }
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        val mapped = lines.map { l ->
            when {
                l.isBlank() -> l
                l.startsWith("#") -> if (l.contains("URI=\"")) l.replace(uriAttr) { m -> "URI=\"${prox(m.groupValues[1])}\"" } else l
                else -> prox(l)
            }
        }
        if (mapped.none { it.startsWith("#EXT-X-STREAM-INF") }) return mapped.joinToString("\n")
        // lista wariantów: preferencja AAC
        val header = ArrayList<String>(); val variants = ArrayList<Pair<String, String>>(); val audio = ArrayList<String>()
        var i = 0
        while (i < mapped.size) {
            val l = mapped[i]
            when {
                l.startsWith("#EXT-X-STREAM-INF") && i + 1 < mapped.size -> { variants += l to mapped[i + 1]; i += 2; continue }
                l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO") -> audio += l
                else -> header += l
            }
            i++
        }
        val hasAacAudio = audio.any { it.contains(AAC, true) || it.contains("aac", true) }
        val audioFixed = if (!hasAacAudio) audio else audio.map { a ->
            val aac = a.contains(AAC, true) || a.contains("aac", true)
            val noDef = a.replace(Regex("DEFAULT=(YES|NO)"), "").replace(",,", ",").trimEnd(',')
            "$noDef,DEFAULT=${if (aac) "YES" else "NO"}"
        }
        val sorted = variants.sortedBy { (inf, _) -> if (inf.contains(AAC, true)) 0 else if (inf.contains("CODECS=", true)) 1 else 0 }
        return (header.filter { it.isNotBlank() } + audioFixed + sorted.flatMap { listOf(it.first, it.second) }).joinToString("\n") + "\n"
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16_384) return null
            sb.append(b.toChar())
        }
    }

    private fun write(out: OutputStream, s: String) { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
}

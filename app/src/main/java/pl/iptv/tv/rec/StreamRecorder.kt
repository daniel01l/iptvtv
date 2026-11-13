package pl.iptv.tv.rec

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Zapis strumienia do pliku .ts: bezpośredni strumień (MPEG-TS) lub HLS (segmenty TS doklejane po kolei).
 * Nie obsługuje strumieni szyfrowanych (DRM / #EXT-X-KEY).
 */
class StreamRecorder(private val http: OkHttpClient, private val ua: String, private val referrer: String) {

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", ua)
        .apply { if (referrer.isNotBlank()) header("Referer", referrer) }.build()

    private fun resolve(base: String, rel: String) = if (rel.startsWith("http")) rel else URI(base).resolve(rel).toString()

    private fun fetchText(url: String): String = http.newCall(req(url)).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}"); it.body!!.string()
    }

    suspend fun record(url: String, out: File, until: Long) {
        out.parentFile?.mkdirs()
        http.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val input = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
            input.mark(16)
            val head = ByteArray(7); val n = input.read(head)
            input.reset()
            if (n == 7 && String(head) == "#EXTM3U") {
                val text = input.readBytes().toString(Charsets.UTF_8)
                out.outputStream().use { hls(resp.request.url.toString(), text, it, until) }
            } else {
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (coroutineContext.isActive && System.currentTimeMillis() < until) {
                        val r = input.read(buf); if (r < 0) break
                        o.write(buf, 0, r)
                    }
                }
            }
        }
    }

    private suspend fun hls(firstUrl: String, firstText: String, o: OutputStream, until: Long) {
        var url = firstUrl; var text = firstText
        if (text.contains("#EXT-X-STREAM-INF")) {          // lista wariantów: najwyższa jakość
            val lines = text.lines()
            var best = -1L; var bestUrl: String? = null
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0
                    val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (u != null && bw >= best) { best = bw; bestUrl = resolve(url, u.trim()) }
                }
            }
            url = bestUrl ?: throw IOException("Brak wariantu HLS"); text = fetchText(url)
        }
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) throw IOException("Strumień szyfrowany – nagrywanie niemożliwe")
        var lastSeq = -1L
        while (coroutineContext.isActive && System.currentTimeMillis() < until) {
            val lines = text.lines()
            var seq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.trim()?.toDoubleOrNull() ?: 6.0
            for (l in lines) {
                if (l.isBlank() || l.startsWith("#")) continue
                if (seq > lastSeq) {
                    http.newCall(req(resolve(url, l.trim()))).execute().use { r -> if (r.isSuccessful) r.body!!.byteStream().copyTo(o) }
                    lastSeq = seq
                    if (System.currentTimeMillis() >= until) return
                }
                seq++
            }
            if (text.contains("#EXT-X-ENDLIST")) return
            delay((target * 500).toLong().coerceAtLeast(1000))
            text = fetchText(url)
        }
    }
}

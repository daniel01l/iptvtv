package pl.iptv.tv.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Źródła Media3 dla strumieni YouTube. Serwery wideo YouTube oczekują zapytań jak z przeglądarki/aplikacji:
 * zakres bajtów w parametrze `range` (nie w nagłówku), licznik `rn`, metoda POST z treścią „x\0” oraz nagłówki
 * zależne od klienta (web: Origin/Referer, visionOS: jego User-Agent) – inaczej odpowiedź 403.
 */
object YtMedia {
    /** Przepisuje zapytania /videoplayback na postać oczekiwaną przez YouTube; odpowiedź 200 z zakresem → 206 dla Media3. */
    class VideoPlaybackInterceptor : Interceptor {
        private val rn = AtomicLong(0)
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            if (!req.url.encodedPath.startsWith("/videoplayback")) return chain.proceed(req)
            val range = parseRange(req.header("Range"))
            val ub = req.url.newBuilder()
            if (req.url.queryParameter("rn") == null) ub.addQueryParameter("rn", rn.getAndIncrement().toString())
            if (range != null && !(range.first == 0L && range.second < 0)) ub.addQueryParameter("range", "${range.first}-${if (range.second >= 0) range.second else ""}")
            val original = req.url.toString()
            val b = req.newBuilder().url(ub.build()).removeHeader("Range")
                .post(byteArrayOf(0x78, 0).toRequestBody())
            if (runCatching { YoutubeParsingHelper.isWebStreamingUrl(original) }.getOrDefault(false)) {
                b.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
                    .header("Sec-Fetch-Dest", "empty").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "cross-site")
            }
            val ua = if (runCatching { YoutubeParsingHelper.isVisionOsStreamingUrl(original) }.getOrDefault(false))
                runCatching { YoutubeParsingHelper.getVisionOsUserAgent(null) }.getOrNull() ?: YtStreams.USER_AGENT else YtStreams.USER_AGENT
            b.header("User-Agent", ua)
            val resp = chain.proceed(b.build())
            // serwer zwraca fragment z kodem 200 – dla Media3 to 206 (inaczej pominąłby [start] bajtów drugi raz)
            if (resp.code == 200 && range != null && range.first > 0) {
                val len = resp.body?.contentLength() ?: -1L
                val end = if (range.second >= 0) range.second else if (len >= 0) range.first + len - 1 else -1L
                return resp.newBuilder().code(206).apply { if (end >= 0) header("Content-Range", "bytes ${range.first}-$end/*") }.build()
            }
            return resp
        }

        /** "bytes=a-b" / "bytes=a-" → (a, b lub -1). */
        fun parseRange(h: String?): Pair<Long, Long>? {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(h ?: return null) ?: return null
            return m.groupValues[1].toLong() to (m.groupValues[2].toLongOrNull() ?: -1L)
        }
    }

    /** Klient dla filmów (z przepisywaniem zapytań); transmisje na żywo (HLS) – bez. */
    val vodHttp: okhttp3.OkHttpClient by lazy {
        Repository.http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(VideoPlaybackInterceptor()).build()
    }
    val liveHttp: okhttp3.OkHttpClient by lazy {
        Repository.http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    fun source(c: Context, p: YtPlayable): MediaSource = when (p) {
        is YtPlayable.Split -> MergingMediaSource(true, false, stream(p.video, p.durationS), stream(p.audio, p.durationS))
        is YtPlayable.Muxed -> progressive(p.stream.url)
        is YtPlayable.Live -> {
            val ds = OkHttpDataSource.Factory(liveHttp).setUserAgent(YtStreams.USER_AGENT)
            if (p.hlsUrl != null) HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(p.hlsUrl))
            else DashMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(p.dashUrl!!))
        }
    }

    private fun vodFactory() = OkHttpDataSource.Factory(vodHttp)

    private fun progressive(url: String): MediaSource = ProgressiveMediaSource.Factory(vodFactory()).createMediaSource(MediaItem.fromUri(url))

    /** Strumień bez dźwięku / sam dźwięk: manifest DASH z danych itag (szybkie przewijanie po indeksie), zapasowo – progresywnie. */
    private fun stream(c: YtCandidate, durationS: Long): MediaSource {
        val itag = c.itag ?: return progressive(c.url)
        return runCatching {
            val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(c.url, itag, durationS)
            val manifest = DashManifestParser().parse(Uri.parse(c.url), mpd.byteInputStream())
            DashMediaSource.Factory(vodFactory()).createMediaSource(manifest, MediaItem.Builder().setUri(c.url).setMimeType("application/dash+xml").build())
        }.getOrElse {
            android.util.Log.w("YtMedia", "manifest DASH niedostępny – odtwarzanie progresywne", it)
            progressive(c.url)
        }
    }
}

package pl.iptv.tv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Repository

/**
 * Jeden odtwarzacz na całą aplikację: kanał gra dalej przy przejściu do menu/ustawień,
 * a ponowne wejście w ten sam kanał nie restartuje strumienia.
 */
@OptIn(UnstableApi::class)
object Playback {
    var player: ExoPlayer? = null; private set
    var url: String? = null; private set
    var channelKey: String? = null; private set
    private var target: PlayerView? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Ponowienie po błędzie – anulowane przy zmianie kanału (wcześniej mogło uruchomić się już na nowym kanale i go zaciąć). */
    private val retry = Runnable { player?.let { it.prepare(); it.playWhenReady = true } }
    /** Poprzedni kanał zakończył się błędem – następny startuje na czysto (nowe dekodery i powierzchnia obrazu). */
    @Volatile private var hadError = false
    /** Bieżący kanał choć raz wystartował (STATE_READY). */
    @Volatile private var reachedReady = true
    val retryPending get() = android.os.Build.VERSION.SDK_INT >= 29 && main.hasCallbacks(retry)
    private var retries = 0
    private var bufferingSince = 0L
    private var lastFrames = -1; private var lastPos = -1L; private var stuck = 0
    /** Po wyczerpaniu prób – komunikat w odtwarzaczu. */
    var onFatal: ((androidx.media3.common.PlaybackException) -> Unit)? = null

    /** Czy coś jest teraz odtwarzane (np. pobieranie EPG zwalnia, by nie przycinać obrazu). */
    @Volatile var isPlayingNow = false; private set

    /** Tylko testy: symulacja błędu odtwarzania. */
    fun simulateError(code: Int) = recovery.onPlayerError(androidx.media3.common.PlaybackException("test", null, code))

    private val recovery = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlayingNow = playing }
        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
            val p = player ?: return
            // adres bez rozszerzenia, a serwer podaje listę HLS (np. Xtream „…/123”) – druga próba jako HLS (jak zewnętrzne odtwarzacze)
            if (e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && guessMime(url ?: "") == null && url !in hlsUrls) {
                val c = lastCtx; val ch = lastChannel; val u = url
                if (c != null && ch != null && u != null) { hlsUrls += u; main.post { if (url == u) play(c, ch, u, force = true) }; return }
            }
            // wypadnięcie poza okno transmisji na żywo – powrót do bieżącego miejsca (bez liczenia jako błąd)
            if (e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
            hadError = true
            // błąd dostawcy (np. 4xx/5xx, brak strumienia) – najwyżej 2 krótkie próby; błąd dekodera – od razu zwolnienie dekodera
            val decoder = e.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
            val source = e.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
            val max = if (decoder) 0 else if (source) 2 else 5
            if (retries < max) { retries++; main.removeCallbacks(retry); main.postDelayed(retry, if (source) 1500L * retries else 1000L shl minOf(retries, 4)) }
            else { p.stop(); onFatal?.invoke(e) }        // stop = zwolnienie dekoderów (słabsze urządzenia nie zacinają kolejnych kanałów)
        }
        override fun onPlaybackStateChanged(s: Int) {
            if (s == androidx.media3.common.Player.STATE_READY) { retries = 0; reachedReady = true }
            bufferingSince = if (s == androidx.media3.common.Player.STATE_BUFFERING) android.os.SystemClock.uptimeMillis() else 0
        }
    }

    /** Strażnik (co 2 s): zbyt długie buforowanie, zamrożony obraz przy działającym dźwięku – działa też w tle menu. */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = android.os.SystemClock.uptimeMillis()
            if (bufferingSince > 0 && now - bufferingSince > 15_000) {
                bufferingSince = now
                if (p.isCurrentMediaItemLive) p.seekToDefaultPosition()
                p.prepare(); p.playWhenReady = true
            }
            if (p.isPlaying && p.videoFormat != null) {
                val frames = p.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                val pos = p.currentPosition
                stuck = if (frames == lastFrames && pos != lastPos) stuck + 1 else 0
                lastFrames = frames; lastPos = pos
                if (stuck == 2) target?.let { v -> v.player = null; v.player = p }        // ok. 4 s – nowa powierzchnia obrazu
                if (stuck >= 4) { stuck = 0; val at = pos; p.stop(); p.prepare(); if (!p.isCurrentMediaItemLive) p.seekTo(at); p.playWhenReady = true }
            } else stuck = 0
            main.postDelayed(this, 2000)
        }
    }

    const val ENGINE_HARDWARE = 0
    const val ENGINE_SOFTWARE = 1
    const val ENGINE_TUNNELING = 2
    val ENGINES = listOf("Media3 – dekoder sprzętowy (zalecany)", "Media3 – dekoder programowy (zgodność)", "Media3 – tunelowanie (Android TV)")

    /** Dekoder programowy: silnik „programowy” albo wyłączona akceleracja sprzętowa (Ustawienia lub przytrzymanie OK). */
    val softwareDecoding get() = pl.iptv.tv.data.Prefs.playerEngine == ENGINE_SOFTWARE || !pl.iptv.tv.data.Prefs.hwAccel

    /** Kolejność dekoderów: sprzętowe (akceleracja) przed programowymi – albo odwrotnie w trybie zgodności. */
    fun codecSelector(software: Boolean) = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
        val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        all.sortedBy { if (it.softwareOnly == software) 0 else 1 }       // sortowanie stabilne – kolejność systemu w grupie zostaje
    }

    /** Opis bieżącego dekodera obrazu (np. „c2.mtk.avc.decoder – sprzętowy”). */
    fun decoderInfo(): String {
        val p = player ?: return "nic nie jest odtwarzane"
        val f = p.videoFormat ?: return "brak obrazu"
        val list = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(f.sampleMimeType ?: "", false, false) }.getOrDefault(emptyList())
        val hw = list.count { it.hardwareAccelerated }
        return "${f.sampleMimeType} ${f.width}×${f.height} • dekodery: $hw sprzętowe, ${list.size - hw} programowe • silnik: ${ENGINES.getOrNull(pl.iptv.tv.data.Prefs.playerEngine)} • akceleracja: ${if (pl.iptv.tv.data.Prefs.hwAccel) "wł." else "wył."}"
    }

    fun get(c: Context): ExoPlayer {
        player?.let { p -> if (p.applicationLooper == android.os.Looper.myLooper()) return p; runCatching { p.release() }; player = null; url = null; target = null }
        return create(c)
    }

    private fun create(c: Context): ExoPlayer {
        // zapasowy dekoder; bez wymuszonej asynchronicznej kolejki (na części TV zamrażała obraz przy działającym dźwięku)
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(c.applicationContext) {
            // zegar odtwarzania przesunięty o ustawienie synchronizacji – obraz dopasowuje się do dźwięku (zmiana działa od razu)
            override fun buildAudioSink(ctx: Context, enableFloat: Boolean, enableParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? {
                val sink = super.buildAudioSink(ctx, enableFloat, enableParams) ?: return null
                return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
                    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                        val p = super.getCurrentPositionUs(sourceEnded)
                        return if (p == androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET) p
                            else (p + pl.iptv.tv.data.Prefs.audioDelayMs * 1000L).coerceAtLeast(0)
                    }
                }
            }
        }
        val P = pl.iptv.tv.data.Prefs
        val engine = P.playerEngine
        // wybór dekodera: sprzętowy (akceleracja) przed programowym albo odwrotnie (tryb zgodności)
        renderers.setMediaCodecSelector(codecSelector(software = softwareDecoding))
        renderers.setEnableDecoderFallback(P.decoderFallback || softwareDecoding)
        when (P.asyncDecoder) {
            1 -> renderers.forceEnableMediaCodecAsynchronousQueueing()
            2 -> renderers.forceDisableMediaCodecAsynchronousQueueing()
        }
        val low = P.lowPerf
        // słabsze urządzenia: ograniczona rozdzielczość (domyślnie 1080p – bez 4K, którego dekoder nie nadąża) i mniejszy bufor w pamięci
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(c.applicationContext).apply {
            val b = buildUponParameters()
            if (low && P.lowMaxHeight > 0) b.setMaxVideoSize(P.lowMaxHeight * 16 / 9, P.lowMaxHeight).setExceedRendererCapabilitiesIfNecessary(false)
            // tunelowanie: obraz i dźwięk dekodowane i synchronizowane sprzętowo (Android TV) – najmniejsze obciążenie procesora
            if (engine == ENGINE_TUNNELING && P.hwAccel) b.setTunnelingEnabled(true)
            setParameters(b)
        }
        val start = if (P.lowLatency) 800 else START_BUFFER_MS
        val load = if (low) {
            val min = P.lowBufMinS.coerceIn(2, 60) * 1000; val max = (P.lowBufMaxS.coerceIn(2, 120) * 1000).coerceAtLeast(min)
            DefaultLoadControl.Builder().setBufferDurationsMs(min, max, start, REBUFFER_MS)
                .setTargetBufferBytes(P.lowBufMb.coerceIn(8, 256) * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        }
        // start po 2 s zapasu (1 s dawał przycięcia zaraz po przełączeniu na niestabilnych strumieniach),
        // po przycięciu 5 s zapasu (bez serii krótkich zatrzymań), bufor 30–60 s
        else DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, start, REBUFFER_MS).setPrioritizeTimeOverSizeThresholds(true).build()
        return ExoPlayer.Builder(c.applicationContext, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)          // Wi-Fi nie usypia podczas oglądania
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { player = it; it.addListener(recovery); main.removeCallbacks(watchdog); main.postDelayed(watchdog, 3000) }
    }

    val active get() = player != null && url != null

    /** Zapas przed startem i po przycięciu (ms). */
    const val START_BUFFER_MS = 2_000
    const val REBUFFER_MS = 5_000

    /**
     * Strumienie TS jak w zewnętrznych odtwarzaczach: klatki kluczowe inne niż IDR (częste w IPTV – bez tego obraz
     * nie startuje), wykrywanie jednostek dostępu bez znaczników AUD, ścieżki DTS z nadajników HDMV.
     */
    val TS_FLAGS = androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
    val extractors: androidx.media3.extractor.DefaultExtractorsFactory get() =
        androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS).setConstantBitrateSeekingEnabled(true)

    /**
     * Klient HTTP strumieni: krótszy limit odczytu (zawieszone połączenie wykryte po 20 s, nie 60 s), bez limitu całego
     * żądania (strumień na żywo trwa godzinami), wspólna pula połączeń utrzymywanych 5 min (segmenty HLS bez nowych połączeń).
     */
    val streamHttp: okhttp3.OkHttpClient by lazy {
        Repository.http.newBuilder()
            .connectTimeout(STREAM_CONNECT_S, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(STREAM_READ_S, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }
    const val STREAM_CONNECT_S = 12L
    const val STREAM_READ_S = 20L

    /** Typ strumienia z adresu (null = rozpoznanie po treści). */
    fun guessMime(streamUrl: String): String? {
        val lower = streamUrl.lowercase().substringBefore('?').substringBefore('#')
        return when {
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.contains("/hls/") || lower.contains("output=hls") ||
                streamUrl.lowercase().contains("output=m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private var lastCtx: Context? = null
    private var lastChannel: Channel? = null
    /** Adresy bez rozszerzenia rozpoznane jako HLS (po nierozpoznanym formacie) – kolejne włączenia od razu jako HLS. */
    val hlsUrls: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Odtwarza [streamUrl]; ten sam adres nie jest uruchamiany ponownie. Zwraca true, gdy rozpoczęto nowe odtwarzanie. */
    fun play(c: Context, ch: Channel, streamUrl: String, force: Boolean = false, mime: String? = null): Boolean {
        val p = get(c)
        main.removeCallbacks(retry)
        lastCtx = c.applicationContext; lastChannel = ch
        if (!force && streamUrl == url && p.playbackState != androidx.media3.common.Player.STATE_IDLE && p.playerError == null) { p.playWhenReady = true; return false }
        // czysty start (zwolnione dekodery + nowa powierzchnia obrazu): po błędzie, po kanale, który nie zdążył ruszyć
        // (np. wadliwy strumień), i zawsze na słabszych urządzeniach – inaczej następny kanał potrafi zaciąć obraz
        val clean = hadError || p.playerError != null || !reachedReady || (pl.iptv.tv.data.Prefs.lowPerf && pl.iptv.tv.data.Prefs.lowCleanDecoder)
        if (clean) { p.stop(); p.clearMediaItems() }
        val headers = HashMap<String, String>()
        if (ch.referrer.isNotBlank()) headers["Referer"] = ch.referrer
        val http = OkHttpDataSource.Factory(streamHttp)
            .setUserAgent(ch.userAgent.ifBlank { Repository.userAgent }).setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(streamUrl)
            // stała prędkość 1.0 – bez przyspieszania/zwalniania obrazu tuż po starcie kanału na żywo
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
                // niskie opóźnienie: bliżej krawędzi transmisji na żywo (gdy strumień na to pozwala)
                .apply { if (pl.iptv.tv.data.Prefs.lowLatency) setTargetOffsetMs(4_000) }.build()).apply {
            (mime ?: guessMime(streamUrl) ?: if (streamUrl in hlsUrls) MimeTypes.APPLICATION_M3U8 else null)?.let { setMimeType(it) }
        }.build()
        val ds = DefaultDataSource.Factory(c.applicationContext, http)
        val source = if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8)
            // HLS: segmenty TS czytane z tymi samymi, tolerancyjnymi ustawieniami co strumienie TS; szybki start bez pobierania segmentu
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds)
                .setExtractorFactory(androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(TS_FLAGS, true))
                .setAllowChunklessPreparation(true).createMediaSource(item)
        else DefaultMediaSourceFactory(ds, extractors).createMediaSource(item)
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        url = streamUrl; channelKey = ch.key
        retries = 0; stuck = 0; bufferingSince = 0; hadError = false; reachedReady = false
        // po błędzie: świeża powierzchnia obrazu (to samo, co wcześniej dawało dopiero otwarcie menu)
        if (clean) main.post { target?.let { v -> if (v.player === p) { v.player = null; v.player = p } } }
        return true
    }

    /** Przenosi obraz do [view] (bez przerywania odtwarzania). */
    fun attach(view: PlayerView) {
        val p = player ?: return
        if (target === view && view.player === p) return
        PlayerView.switchTargetView(p, target, view)
        target = view
    }

    /** Ponowne podłączenie powierzchni obrazu (po powrocie z innego ekranu – bez czarnego obrazu). */
    fun reattach(view: PlayerView) {
        val p = player ?: return
        if (target !== view) PlayerView.switchTargetView(p, target, view)
        view.player = null; view.player = p
        target = view
    }

    fun detach(view: PlayerView) { if (target === view) { view.player = null; target = null } }

    fun stop() { isPlayingNow = false; hadError = false; main.removeCallbacks(retry); main.removeCallbacks(watchdog); runCatching { player?.release() }; player = null; url = null; channelKey = null; target = null }
}

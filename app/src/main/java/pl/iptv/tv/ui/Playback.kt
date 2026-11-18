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
import pl.iptv.tv.data.ChannelEngines
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

    private const val TAG = "Playback"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Ponowienie po błędzie – anulowane przy zmianie kanału (wcześniej mogło uruchomić się już na nowym kanale i go zaciąć). */
    private val retry = Runnable { player?.let { it.prepare(); it.playWhenReady = true } }

    /**
     * Połączenia HTTP jednego strumienia. Przy zmianie kanału / odświeżeniu / zwolnieniu odtwarzacza przerywane od razu –
     * zawieszone połączenie nie blokuje już do 20 s miejsca u dostawcy (limit połączeń konta), przez co powrót
     * na ten sam kanał nie działał aż do ręcznej zmiany silnika.
     */
    class StreamCalls(private val client: okhttp3.OkHttpClient) : okhttp3.Call.Factory {
        private val calls = java.util.Collections.newSetFromMap(java.util.WeakHashMap<okhttp3.Call, Boolean>())
        @Volatile var closed = false; private set
        override fun newCall(request: okhttp3.Request): okhttp3.Call {
            val c = client.newCall(request)
            synchronized(calls) { if (closed) c.cancel() else calls += c }
            return c
        }
        /** Liczba żądań, które jeszcze nie zakończyły się (tylko diagnostyka i testy). */
        val open: Int get() = synchronized(calls) { calls.count { !it.isCanceled() } }
        /** Przerywa bieżące żądania; nowe (np. ponowne otwarcie tego samego strumienia) dalej działają. */
        fun cancelActive() = synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
        /** Koniec strumienia: przerywa wszystko i odrzuca kolejne żądania. */
        fun close() = synchronized(calls) { closed = true; calls.forEach { it.cancel() }; calls.clear() }
    }
    /** Połączenia bieżącego strumienia. */
    private var calls: StreamCalls? = null
    private var lastMime: String? = null

    /** Słuchacze ekranów – przenoszeni na nowy odtwarzacz po automatycznym odbudowaniu (bez gubienia stanu ekranu). */
    private val listeners = java.util.LinkedHashSet<androidx.media3.common.Player.Listener>()
    fun addListener(l: androidx.media3.common.Player.Listener) { if (listeners.add(l)) player?.addListener(l) }
    fun removeListener(l: androidx.media3.common.Player.Listener) { listeners.remove(l); player?.removeListener(l) }
    /** Wywoływane po zastąpieniu odtwarzacza nowym (automatyczne odwieszenie). */
    var onPlayerChanged: ((ExoPlayer) -> Unit)? = null

    /** Odwieszenia bieżącego kanału: dwa razy ponowne otwarcie strumienia, za trzecim – nowy odtwarzacz (jak ręczna zmiana silnika). */
    private var recoveries = 0
    private var recoveryWindow = 0L
    /** Ile razy odtwarzacz został odbudowany (diagnostyka i testy). */
    var rebuilds = 0; private set
    /** Buforowanie dłuższe niż tyle ms = zawieszony strumień. */
    const val STALL_MS = 12_000L
    /** Ten sam kanał wybrany ponownie, a od tylu ms buforuje – startuje od nowa zamiast czekać. */
    const val SAME_URL_STALL_MS = 6_000L
    const val RECOVERY_WINDOW_MS = 90_000L
    const val REBUILD_AFTER = 3
    /** Poprzedni kanał zakończył się błędem – następny startuje na czysto (nowe dekodery i powierzchnia obrazu). */
    @Volatile private var hadError = false
    /** Kanał grał bez powierzchni obrazu ([attach]) – następne play() tego kanału czystym startem. */
    @Volatile var headless = false; private set
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
                if (c != null && ch != null && u != null) { rememberHls(u); main.post { if (url == u) play(c, ch, u, force = true) }; return }
            }
            // wypadnięcie poza okno transmisji na żywo – powrót do bieżącego miejsca (bez liczenia jako błąd)
            if (e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                android.util.Log.w(TAG, "poza oknem na żywo – powrót na bieżące miejsce • ${diagnostics()}")
                p.seekToDefaultPosition(); p.prepare(); return
            }
            hadError = true
            android.util.Log.w(TAG, "błąd ${e.errorCodeName}, próba ${retries + 1}")
            // zawieszone połączenia poprzedniej próby zamknięte od razu (zwalniają miejsce u dostawcy przed ponowieniem)
            calls?.cancelActive()
            // błąd dostawcy (np. 4xx/5xx, brak strumienia) – kilka prób z rosnącą przerwą (dostawca zwalnia miejsce
            // po poprzednim połączeniu z opóźnieniem); błąd dekodera – od razu zwolnienie dekodera
            val decoder = e.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
            val source = e.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
            val max = if (decoder) 0 else if (source) SOURCE_RETRIES else 5
            // kanał na żywo, który nie ruszył: błąd dekodera albo nierozpoznany / wadliwy format – dobór silnika w tle
            // (błędy HTTP i sieci – zwykłe ponowienia: inny dekoder nie pomoże na niedziałający serwer)
            val engineIssue = decoder || e.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
            val ch = lastChannel; val c = lastCtx; val u = url
            if (engineIssue && optimizeOnError(decoder, retries) && !reachedReady && ch != null && c != null && u != null && u == ch.url &&
                ChannelEngineOptimizer.onStartFailed(c, ch, u, e.errorCodeName, decoderError = decoder)) return
            if (retries < max) {
                retries++; main.removeCallbacks(retry)
                // (7.3) pierwszy błąd formatu (np. wejście w środek strumienia na żywo) – szybkie ponowienie zamiast 2 s
                main.postDelayed(retry, if (engineIssue && !decoder && retries == 1) FORMAT_RETRY_MS else retryDelayMs(source, retries))
            }
            else { p.stop(); calls?.cancelActive(); onFatal?.invoke(e) }   // stop = zwolnienie dekoderów (słabsze urządzenia nie zacinają kolejnych kanałów)
        }
        override fun onPlaybackStateChanged(s: Int) {
            if (s == androidx.media3.common.Player.STATE_READY) { retries = 0; reachedReady = true }
            // początek buforowania liczony tylko przy przejściu do niego (READY→BUFFERING→BUFFERING nie zeruje licznika)
            if (s == androidx.media3.common.Player.STATE_BUFFERING) { if (bufferingSince == 0L) bufferingSince = android.os.SystemClock.uptimeMillis() }
            else bufferingSince = 0
            android.util.Log.i(TAG, "stan ${listOf("?", "IDLE", "BUFFERING", "READY", "ENDED").getOrElse(s) { "$s" }}")
        }
    }

    /** Strażnik (co 2 s): zbyt długie buforowanie, zamrożony obraz przy działającym dźwięku – działa też w tle menu. */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = android.os.SystemClock.uptimeMillis()
            // zbyt długie buforowanie: dawniej samo prepare() (bez działania – odtwarzacz nie był w stanie IDLE)
            // i przeskok na krawędź na żywo, co co 15 s zrzucało bufor i zacinało obraz w kółko
            if (bufferingSince > 0 && now - bufferingSince > STALL_MS && p.playWhenReady && url != null) {
                bufferingSince = now
                recover("buforowanie ${(p.totalBufferedDuration / 1000)} s zapasu")
            }
            // tunelowanie: klatki wyświetla sprzęt poza licznikiem odtwarzacza – bez sprawdzania (inaczej fałszywe alarmy)
            val view = target
            if (p.isPlaying && p.videoFormat != null && canJudgeFreeze(view != null && view.player === p, view?.isAttachedToWindow == true, view?.isShown == true) &&
                (p.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)?.parameters?.tunnelingEnabled != true) {
                val frames = p.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                val pos = p.currentPosition
                stuck = if (frames == lastFrames && pos != lastPos) stuck + 1 else 0     // obraz stoi, a czas (dźwięk) płynie
                lastFrames = frames; lastPos = pos
                if (stuck.toLong() * WATCHDOG_MS == FREEZE_SURFACE_MS) target?.let { v -> v.player = null; v.player = p }
                if (stuck.toLong() * WATCHDOG_MS >= FREEZE_SWITCH_MS) { stuck = 0; onFrozen() }
            } else stuck = 0
            // obraz płynie od dłuższej chwili – licznik odwieszeń od zera
            if (p.isPlaying && now - recoveryWindow > RECOVERY_WINDOW_MS) recoveries = 0
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    /**
     * Obraz zamrożony przy płynącym dźwięku (dane są – to dekoder): na dekoderze sprzętowym przejście na programowy
     * (nowy odtwarzacz, ten sam kanał); na programowym – zwykłe odwieszenie strumienia.
     */
    private fun onFrozen() {
        val c = lastCtx
        if (!softwareDecoding && pl.iptv.tv.data.Prefs.freezeFallback && c != null) {
            android.util.Log.w(TAG, "zamrożony obraz na dekoderze sprzętowym – przejście na programowy • ${diagnostics()}")
            sessionSoftware = true; softwareChannel = channelKey
            rebuild(c)
            onDecoderFallback?.invoke()
        } else recover("zamrożony obraz")
    }

    /** Tylko testy: zgłoszenie zamrożonego obrazu jak przez strażnika. */
    fun simulateFreeze() = onFrozen()

    /**
     * (7.3) Dobór silnika po błędzie: dekoder – od razu; błąd formatu – dopiero gdy powtórzył się po zwykłym ponowieniu.
     * W 7.2 jednorazowy błąd formatu na starcie (częsty przy wejściu w strumień na żywo) zatrzymywał kanał na testy
     * (4–5 s), a dobrany „na zapas” profil zostawał zapisany – każde przełączenie na ten kanał przebudowywało odtwarzacz.
     */
    fun optimizeOnError(decoder: Boolean, retriesSoFar: Int): Boolean = decoder || retriesSoFar >= 1
    /** Pierwsze ponowienie po błędzie formatu (zamiast 2 s). */
    const val FORMAT_RETRY_MS = 300L

    /** Ten sam odtwarzacz (dekodery / kolejka); sam rodzaj strumienia (HLS / TS) zmienia tylko źródło. */
    fun sameEngine(a: pl.iptv.tv.data.EngineProfile?, b: pl.iptv.tv.data.EngineProfile?): Boolean {
        val none = pl.iptv.tv.data.EngineProfile()
        return (a?.copy(container = 0) ?: none) == (b?.copy(container = 0) ?: none)
    }

    /** Opóźnienie kolejnej próby po błędzie: dostawca – 2, 4, 6, 8 s; sieć – 2, 4, 8, 16 s. */
    fun retryDelayMs(source: Boolean, attempt: Int): Long = if (source) 2000L * attempt else 1000L shl minOf(attempt, 4)
    const val SOURCE_RETRIES = 4

    /**
     * Odwieszenie zawieszonego strumienia: najpierw ponowne otwarcie (nowe połączenie, zwolnione dekodery),
     * a gdy w krótkim czasie nie pomaga – całkiem nowy odtwarzacz z tymi samymi ustawieniami.
     */
    private fun recover(reason: String) {
        val p = player ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - recoveryWindow > RECOVERY_WINDOW_MS) { recoveryWindow = now; recoveries = 0 }
        recoveries++
        android.util.Log.w(TAG, "odwieszenie: $reason (próba $recoveries) • ${diagnostics()}")
        val c = lastCtx
        if (recoveries >= REBUILD_AFTER && c != null) { recoveries = 0; recoveryWindow = now; rebuild(c); return }
        val at = p.currentPosition; val live = p.isCurrentMediaItemLive
        p.stop()
        calls?.cancelActive()                 // po stop(): przerwane połączenie nie zgłosi już błędu do starego źródła
        if (live) p.seekToDefaultPosition() else if (at > 0) p.seekTo(at)
        p.prepare(); p.playWhenReady = true
    }

    /**
     * Nowy odtwarzacz w miejsce starego (ten sam kanał, ten sam ekran): stary całkowicie zwolniony – dekodery,
     * wątki ładowania, połączenia. To samo, co dotąd dawała dopiero ręczna zmiana silnika.
     */
    fun rebuild(c: Context) {
        val ch = lastChannel; val u = url; val mime = lastMime; val view = target
        val at = player?.takeIf { !it.isCurrentMediaItemLive }?.currentPosition ?: 0L
        releasePlayer()
        val p = create(c)
        rebuilds++
        view?.let { it.player = p; target = it }
        onPlayerChanged?.invoke(p)
        if (ch != null && u != null) { play(c, ch, u, force = true, mime = mime); if (at > 0) p.seekTo(at) }
    }

    /** Pełne zwolnienie odtwarzacza i jego połączeń (bez zmiany zapamiętanego kanału). */
    private fun releasePlayer() {
        main.removeCallbacks(retry); main.removeCallbacks(watchdog); main.removeCallbacks(startCheck)
        val p = player
        target?.let { if (it.player === p) it.player = null }   // widok nie trzyma zwolnionego odtwarzacza
        if (p != null) { listeners.forEach { p.removeListener(it) }; p.removeListener(recovery); runCatching { p.release() } }
        player = null
        // aktywne żądania przerwane; bezczynne połączenia z puli zostają – nowy odtwarzacz użyje ich od razu
        // (bez ponownego łączenia, które mogłoby trafić na martwy adres serwera)
        calls?.close(); calls = null
        isPlayingNow = false; bufferingSince = 0; stuck = 0; lastFrames = -1; lastPos = -1; headless = false
    }

    const val ENGINE_HARDWARE = 0
    const val ENGINE_SOFTWARE = 1
    const val ENGINE_TUNNELING = 2
    val ENGINES = listOf("Media3 – dekoder sprzętowy (zalecany)", "Media3 – dekoder programowy (zgodność)", "Media3 – tunelowanie (Android TV)")

    /** Dekoder programowy: silnik „programowy” albo wyłączona akceleracja sprzętowa (Ustawienia lub przytrzymanie OK). */
    val softwareDecoding get() = pl.iptv.tv.data.Prefs.playerEngine == ENGINE_SOFTWARE || !pl.iptv.tv.data.Prefs.hwAccel || sessionSoftware

    /**
     * Dekoder sprzętowy zamroził obraz (dźwięk grał dalej) – dekoder programowy do końca sesji. Nie zmienia ustawień:
     * zerowane przez [stop] (zamknięcie aplikacji, ręczna zmiana silnika lub akceleracji).
     */
    @Volatile var sessionSoftware = false; private set
    /**
     * (7.3) Kanał, na którym obraz zamarzł – dekoder programowy tylko dla niego. Dawniej do końca sesji dla wszystkich
     * kanałów: fałszywy alarm (np. obraz bez powierzchni przy przebudowie ekranów po zmianie motywu albo zaraz po zimnym
     * starcie) zostawiał klatkujący dekoder programowy, aż aplikacja została zamknięta.
     */
    @Volatile var softwareChannel: String? = null; private set

    /** Strażnik liczy zamrożenie tylko, gdy obraz ma widoczną powierzchnię (bez niej dekoder nie wyświetla klatek). */
    fun canJudgeFreeze(hasTarget: Boolean, attached: Boolean, shown: Boolean) = hasTarget && attached && shown
    /** Po automatycznym przejściu na dekoder programowy (komunikat na ekranie). */
    var onDecoderFallback: (() -> Unit)? = null
    /** Co ile ms strażnik sprawdza obraz. */
    const val WATCHDOG_MS = 2_000L
    /** Po tylu ms zamrożonego obrazu – nowa powierzchnia obrazu (często wystarcza). */
    const val FREEZE_SURFACE_MS = 4_000L
    /** Po tylu ms zamrożonego obrazu – przejście na dekoder programowy (lub odświeżenie strumienia, gdy już programowy). */
    const val FREEZE_SWITCH_MS = 8_000L

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
        return "${f.sampleMimeType} ${f.width}×${f.height} • dekodery: $hw sprzętowe, ${list.size - hw} programowe • silnik: ${ENGINES.getOrNull(pl.iptv.tv.data.Prefs.playerEngine)} • akceleracja: ${if (pl.iptv.tv.data.Prefs.hwAccel) "wł." else "wył."}" +
            if (sessionSoftware) " • awaryjnie dekoder programowy (obraz zamarzał)" else ""
    }

    fun get(c: Context): ExoPlayer {
        player?.let { p -> if (p.applicationLooper == android.os.Looper.myLooper()) return p; releasePlayer(); url = null; target = null }
        return create(c)
    }

    private fun create(c: Context): ExoPlayer = buildPlayer(c, profile).also { p ->
        player = p; p.addListener(recovery); listeners.forEach { p.addListener(it) }
        if (pl.iptv.tv.BuildConfig.DEBUG) p.addAnalyticsListener(loadLog)
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 3000)
    }

    /**
     * Ustawienia silnika dobrane dla bieżącego kanału ([ChannelEngineOptimizer], zapisane w Room); null – ustawienia użytkownika.
     * Profil: dekoder programowy / dźwięk FFmpeg / kolejka dekodera / rodzaj strumienia, bez tunelowania.
     */
    @Volatile var profile: pl.iptv.tv.data.EngineProfile? = null; private set

    /**
     * Odtwarzacz z ustawieniami użytkownika i ewentualnym profilem kanału. Bez skutków ubocznych – używany też przez testy
     * silnika w tle ([looper] – wątek testów; [probe] – bez strażnika i słuchaczy ekranu).
     */
    fun buildPlayer(c: Context, profile: pl.iptv.tv.data.EngineProfile?, looper: android.os.Looper? = null): ExoPlayer {
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
        val software = softwareDecoding || profile?.software == true
        // wybór dekodera: sprzętowy (akceleracja) przed programowym albo odwrotnie (tryb zgodności)
        renderers.setMediaCodecSelector(codecSelector(software = software))
        renderers.setEnableDecoderFallback(P.decoderFallback || software || profile != null)
        when (profile?.async?.takeIf { it != 0 } ?: P.asyncDecoder) {
            1 -> renderers.forceEnableMediaCodecAsynchronousQueueing()
            2 -> renderers.forceDisableMediaCodecAsynchronousQueueing()
        }
        // profil kanału: dekoder dźwięku FFmpeg przed systemowym (formaty, których dekoder urządzenia nie obsługuje)
        if (profile?.ffmpeg == true) renderers.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val low = P.lowPerf
        // słabsze urządzenia: ograniczona rozdzielczość (domyślnie 1080p – bez 4K, którego dekoder nie nadąża) i mniejszy bufor w pamięci
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(c.applicationContext).apply {
            val b = buildUponParameters()
            if (low && P.lowMaxHeight > 0) b.setMaxVideoSize(P.lowMaxHeight * 16 / 9, P.lowMaxHeight).setExceedRendererCapabilitiesIfNecessary(false)
            // tunelowanie: obraz i dźwięk dekodowane i synchronizowane sprzętowo (Android TV) – najmniejsze obciążenie procesora
            if (engine == ENGINE_TUNNELING && P.hwAccel && !sessionSoftware && profile == null) b.setTunnelingEnabled(true)
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
        val probe = looper != null
        return ExoPlayer.Builder(c.applicationContext, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .apply { if (looper != null) setLooper(looper) }
            .apply {
                // odtwarzacz testowy: bez blokady Wi-Fi i bez przejmowania dźwięku (nie przerywa niczego, co gra)
                if (!probe) setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)          // Wi-Fi nie usypia podczas oglądania
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), !probe)
                setHandleAudioBecomingNoisy(!probe)
            }
            .build()
    }

    /** Typ strumienia wg profilu kanału (HLS / TS wymuszony), inaczej z adresu albo wcześniejszego rozpoznania. */
    fun mimeFor(streamUrl: String, mime: String?, profile: pl.iptv.tv.data.EngineProfile?): String? = when (profile?.container) {
        1 -> MimeTypes.APPLICATION_M3U8
        2 -> MimeTypes.VIDEO_MP2T
        else -> mime ?: guessMime(streamUrl) ?: if (streamUrl in hlsUrls) MimeTypes.APPLICATION_M3U8 else null
    }

    /** Źródło strumienia kanału (wspólne dla odtwarzacza i testów silnika): nagłówki kanału, tolerancyjne TS / HLS. */
    fun mediaSource(c: Context, ch: Channel, streamUrl: String, mime: String?, calls: okhttp3.Call.Factory): androidx.media3.exoplayer.source.MediaSource {
        val headers = HashMap<String, String>()
        if (ch.referrer.isNotBlank()) headers["Referer"] = ch.referrer
        val http = OkHttpDataSource.Factory(calls)
            .setUserAgent(ch.userAgent.ifBlank { Repository.userAgent }).setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(streamUrl)
            // stała prędkość 1.0 – bez przyspieszania/zwalniania obrazu tuż po starcie kanału na żywo
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
                // niskie opóźnienie: bliżej krawędzi transmisji na żywo (gdy strumień na to pozwala)
                .apply { if (pl.iptv.tv.data.Prefs.lowLatency) setTargetOffsetMs(4_000) }.build()).apply {
            mime?.let { setMimeType(it) }
        }.build()
        val ds = DefaultDataSource.Factory(c.applicationContext, http)
        return if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8)
            // HLS: segmenty TS czytane z tymi samymi, tolerancyjnymi ustawieniami co strumienie TS; szybki start bez pobierania segmentu
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds)
                .setExtractorFactory(androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(TS_FLAGS, true))
                .setAllowChunklessPreparation(true).createMediaSource(item)
        else DefaultMediaSourceFactory(ds, extractors).createMediaSource(item)
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
            .dispatcher(okhttp3.Dispatcher())        // własny – strumień nie dzieli kolejki z EPG, logo i aktualizacjami
            .retryOnConnectionFailure(true)
            .build()
    }
    /**
     * Limit nawiązania połączenia z jednym adresem serwera. Serwery IPTV mają wiele adresów (DNS z rotacją), a część z nich
     * bywa martwa – połączenie wisi. Przy 12 s każde trafienie na taki adres zatrzymywało strumień na 12 s (zamrożony obraz,
     * wypadnięcie z okna na żywo); przy 4 s klient od razu próbuje następnego adresu, a martwy odkłada na koniec kolejki.
     */
    const val STREAM_CONNECT_S = 4L
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
    val hlsUrls: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    const val HLS_REMEMBER = 400

    /**
     * (7.3) Rozpoznany HLS zapamiętany także po ponownym uruchomieniu (aktualizacja, wymuszone zatrzymanie): dawniej po
     * każdym zimnym starcie pierwsze włączenie takiego kanału kończyło się błędem formatu i drugim startem.
     */
    fun rememberHls(u: String) {
        hlsUrls += u
        val list = synchronized(hlsUrls) { hlsUrls.toList() }.takeLast(HLS_REMEMBER)
        runCatching { pl.iptv.tv.data.Prefs.hlsStreams = list.joinToString("\n") }
    }

    fun loadRememberedHls() {
        runCatching { pl.iptv.tv.data.Prefs.hlsStreams.split('\n').filter { it.isNotBlank() } }.getOrNull()?.let { hlsUrls.addAll(it) }
    }

    /** Typy strumieni na żywo, których dekodery są sprawdzane przy rozgrzewaniu. */
    val WARM_MIMES = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MPEG2, MimeTypes.AUDIO_AAC,
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2)
    @Volatile var warmed = false; private set

    /**
     * (7.3) Rozgrzanie po zimnym starcie (aktualizacja, wymuszone zatrzymanie, nowy proces): lista dekoderów urządzenia
     * (pierwsze zapytanie trwa na TV nawet sekundę i dawniej przypadało na start pierwszego kanału) i jednorazowe
     * zbudowanie / zwolnienie odtwarzacza na wątku w tle (wczytanie klas silnika, FFmpeg) – bez dźwięku, bez dekoderów,
     * bez połączeń. Kanały od razu przełączają się płynnie, zamiast „budzić się” po dłuższej chwili.
     */
    fun warmUp(c: Context) {
        val app = c.applicationContext
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            loadRememberedHls()
            WARM_MIMES.forEach { m -> runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(m, false, false) } }
            runCatching {
                android.os.Looper.prepare()
                val looper = android.os.Looper.myLooper()!!
                val p = buildPlayer(app, null, looper)
                p.release()
                streamHttp               // klient strumieni (pula połączeń, dyspozytor) gotowy przed pierwszym kanałem
                looper.quitSafely()
            }
            warmed = true
        }, "PlaybackWarmUp").start()
    }

    /** Odtwarza [streamUrl]; ten sam adres nie jest uruchamiany ponownie. Zwraca true, gdy rozpoczęto nowe odtwarzanie. */
    fun play(c: Context, ch: Channel, streamUrl: String, force: Boolean = false, mime: String? = null): Boolean {
        // inny kanał niż dobierany w tle – dobór przerwany (odtwarzacz testowy zwolniony)
        ChannelEngineOptimizer.cancelUnless(streamUrl)
        // zapamiętany profil silnika kanału (Room, odczyt z pamięci); rodzaj strumienia tylko dla adresu, dla którego go dobrano
        val want = ChannelEngines.get(ch.key)?.let { e -> e.profile?.let { if (e.streamUrl == streamUrl) it else it.copy(container = 0) } }
        // (7.3) awaryjny dekoder programowy tylko dla kanału, na którym obraz zamarzł – inny kanał znów na sprzętowym
        val leaveSoftware = sessionSoftware && softwareChannel != null && softwareChannel != ch.key
        if (leaveSoftware) { sessionSoftware = false; softwareChannel = null }
        if (want != profile || (leaveSoftware && player != null)) {
            // inne ustawienia silnika – nowy odtwarzacz (stary całkowicie zwolniony), obraz na tym samym widoku
            // (7.3) sam rodzaj strumienia (HLS / TS) – ten sam odtwarzacz, bez przebudowy (płynne przełączanie)
            val view = target
            val rebuild = !sameEngine(want, profile) || leaveSoftware
            profile = want
            if (player != null && rebuild) {
                releasePlayer(); url = null
                val np = create(c)
                view?.let { it.player = np; target = it }
                onPlayerChanged?.invoke(np)
            }
        }
        val p = get(c)
        main.removeCallbacks(retry)
        lastCtx = c.applicationContext; lastChannel = ch
        // ten sam kanał gra dalej bez restartu – chyba że od dłuższej chwili tylko buforuje (zawieszony strumień
        // z podglądu lub sprzed przełączenia: dawniej zostawał zawieszony aż do zmiany ustawień silnika)
        if (!force && streamUrl == url && p.playbackState != androidx.media3.common.Player.STATE_IDLE && p.playerError == null &&
            !stalledFor(SAME_URL_STALL_MS) && !headless) { p.playWhenReady = true; return false }
        val wasHeadless = headless; headless = false
        // czysty start (zwolnione dekodery + nowa powierzchnia obrazu): po błędzie, po kanale, który nie zdążył ruszyć
        // (np. wadliwy strumień), i zawsze na słabszych urządzeniach – inaczej następny kanał potrafi zaciąć obraz
        val clean = hadError || wasHeadless || p.playerError != null || !reachedReady || (pl.iptv.tv.data.Prefs.lowPerf && pl.iptv.tv.data.Prefs.lowCleanDecoder)
        if (clean) { p.stop(); p.clearMediaItems() }
        // osobna grupa połączeń dla nowego strumienia; poprzednia zamykana po podmianie źródła (niżej)
        val previousCalls = calls
        val streamCalls = StreamCalls(streamHttp); calls = streamCalls
        p.setMediaSource(mediaSource(c, ch, streamUrl, mimeFor(streamUrl, mime, profile), streamCalls))
        p.playWhenReady = true
        p.prepare()
        // po podmianie źródła: stare połączenia przerwane od razu (stare źródło jest już zwolnione, więc przerwanie
        // nie zgłosi błędu) – nowy kanał nie czeka, aż dostawca zwolni miejsce po poprzednim
        previousCalls?.close()
        android.util.Log.i(TAG, "start ${ch.name}${if (clean) " (czysty start)" else ""}")
        if (streamUrl != url) { recoveries = 0; recoveryWindow = 0 }          // nowy kanał – licznik odwieszeń od zera
        url = streamUrl; channelKey = ch.key; lastMime = mime
        retries = 0; stuck = 0; bufferingSince = 0; hadError = false; reachedReady = false
        // po błędzie: świeża powierzchnia obrazu (to samo, co wcześniej dawało dopiero otwarcie menu)
        if (clean) main.post { target?.let { v -> if (v.player === p) { v.player = null; v.player = p } } }
        // kanał na żywo: kontrola startu (pierwsze klatki w [START_CHECK_MS]) – inaczej dobór silnika w tle
        startedAt = android.os.SystemClock.uptimeMillis()
        main.removeCallbacks(startCheck)
        if (streamUrl == ch.url) main.postDelayed(startCheck, START_CHECK_MS)
        return true
    }

    /** Pierwsze klatki kanału na żywo powinny pojawić się w tym czasie; potem – dobór silnika ([ChannelEngineOptimizer]). */
    const val START_CHECK_MS = 5_000L
    /**
     * Strumień jeszcze się wczytuje (wolne łącze, zapas rośnie): kontrola przesuwana, najwyżej do tego czasu od startu –
     * wolny, ale działający kanał nie jest przerywany testami.
     */
    const val START_GIVE_UP_MS = 15_000L
    private var startedAt = 0L

    private val startCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            val ch = lastChannel ?: return; val u = url ?: return; val c = lastCtx ?: return
            if (reachedReady || !p.playWhenReady || p.playerError != null || u != ch.url) return
            val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
            when (startDecision(elapsed, p.videoFormat != null, p.totalBufferedDuration, isPlayingNow)) {
                StartDecision.WAIT -> main.postDelayed(this, 2_000)
                StartDecision.NONE -> {}
                StartDecision.OPTIMIZE -> {
                    android.util.Log.w(TAG, "dekoder nie ruszył w ${elapsed / 1000} s mimo danych • ${diagnostics()}")
                    ChannelEngineOptimizer.onStartFailed(c, ch, u, "brak obrazu po ${elapsed / 1000} s", decoderError = false)
                }
            }
        }
    }

    enum class StartDecision { WAIT, NONE, OPTIMIZE }

    /**
     * (7.2) Kontrola startu bez fałszywych alarmów. W 7.0 kanał bez obrazu po 5 s był od razu „niedziałający” – wolniej
     * startujące, ale dobre kanały były zatrzymywane na czas testów, a dekoder programowy zapisywany na stałe (zacinanie
     * we wszystkich motywach). Teraz dobór tylko, gdy **dekoder stoi mimo danych**: znany format obrazu, pełny zapas startowy
     * i nadal brak obrazu po [START_STUCK_MS]. Brak danych / formatu to łącze lub serwer – zwykłe ponowienia, bez doboru.
     */
    fun startDecision(elapsedMs: Long, hasVideoFormat: Boolean, bufferedMs: Long, playing: Boolean): StartDecision = when {
        playing -> StartDecision.NONE
        !hasVideoFormat -> if (elapsedMs < START_GIVE_UP_MS) StartDecision.WAIT else StartDecision.NONE
        bufferedMs < START_BUFFER_MS || elapsedMs < START_STUCK_MS -> if (elapsedMs < START_GIVE_UP_MS) StartDecision.WAIT else StartDecision.NONE
        else -> StartDecision.OPTIMIZE
    }

    /** Dekoder uznany za zawieszony dopiero po tym czasie (dane i format są, obrazu nie ma). */
    const val START_STUCK_MS = 10_000L

    /**
     * Dobór silnika: zatrzymanie odtwarzania kanału na czas testów (dekodery i połączenia zwolnione – testowy odtwarzacz nie
     * konkuruje o dekoder ani o połączenie u dostawcy). Kanał, adres i obraz zostają.
     */
    fun holdForProbe() {
        main.removeCallbacks(retry); main.removeCallbacks(startCheck)
        player?.stop(); calls?.cancelActive()
        bufferingSince = 0; stuck = 0
    }

    /** Wynik doboru (albo jego brak): kanał od nowa – z zapisanym profilem, a bez niego z ustawieniami użytkownika. */
    fun restartAfterProbe(c: Context, ch: Channel, streamUrl: String) {
        if (url != streamUrl) return                          // w międzyczasie inny kanał
        play(c, ch, streamUrl, force = true, mime = lastMime)
    }

    /** Przenosi obraz do [view] (bez przerywania odtwarzania). */
    fun attach(view: PlayerView) {
        val p = player ?: return
        if (target === view && view.player === p) return
        // kanał grał bez żadnej powierzchni obrazu (np. za ekranem głównym) – dekoder pracował „na ślepo”; przy jego przejęciu
        // część TV zacinała obraz aż do ręcznej zmiany silnika. Następne play() tego kanału robi czysty start (świeży dekoder).
        if (target == null && p.playbackState != androidx.media3.common.Player.STATE_IDLE && p.videoFormat != null) headless = true
        PlayerView.switchTargetView(p, target, view)
        target = view
        stuck = 0; lastFrames = -1; lastPos = -1          // nowa powierzchnia – licznik zamrożenia od zera
    }

    /** Ponowne podłączenie powierzchni obrazu (po powrocie z innego ekranu – bez czarnego obrazu). */
    fun reattach(view: PlayerView) {
        val p = player ?: return
        if (target !== view) PlayerView.switchTargetView(p, target, view)
        view.player = null; view.player = p
        target = view
    }

    fun detach(view: PlayerView) { if (target === view) { view.player = null; target = null } }

    /** Wersja testowa: czas i rozmiar każdego pobrania (bez adresu – tylko rodzaj danych) i przekroczenie okna na żywo. */
    private val loadLog = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onLoadCompleted(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, m: androidx.media3.exoplayer.source.MediaLoadData) {
            val kind = when (m.dataType) { androidx.media3.common.C.DATA_TYPE_MANIFEST -> "lista"; androidx.media3.common.C.DATA_TYPE_MEDIA -> "segment"; else -> "dane${m.dataType}" }
            android.util.Log.d(TAG, "pobrano $kind ${l.bytesLoaded / 1024} KB w ${l.loadDurationMs} ms (${l.uri.host} ${l.uri.lastPathSegment?.substringAfterLast('.', "")?.take(5)})")
        }
        override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, wasCanceled: Boolean) {
            android.util.Log.d(TAG, "błąd pobierania po ${l.loadDurationMs} ms: ${e.javaClass.simpleName}${if (wasCanceled) " (przerwane)" else ""}")
        }
    }

    /** Stan dekodera i bufora do dziennika (format, klatki wyświetlone/pominięte, zapas, przepływność łącza). */
    fun diagnostics(): String {
        val p = player ?: return "brak odtwarzacza"
        val f = p.videoFormat; val d = p.videoDecoderCounters
        return "obraz=${f?.let { "${it.sampleMimeType} ${it.width}x${it.height}@${it.frameRate}" } ?: "-"}" +
            " klatki=${d?.renderedOutputBufferCount ?: -1}/pominięte=${d?.droppedBufferCount ?: -1}/opuszczone=${d?.skippedOutputBufferCount ?: -1}" +
            " zapas=${p.totalBufferedDuration}ms łącze=${lastCtx?.let { androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(it).bitrateEstimate / 1000 } ?: -1}kb/s"
    }

    /** Czy odtwarzacz buforuje bez przerwy dłużej niż [ms]. */
    fun stalledFor(ms: Long): Boolean {
        val p = player ?: return false
        return p.playbackState == androidx.media3.common.Player.STATE_BUFFERING && bufferingSince > 0 &&
            android.os.SystemClock.uptimeMillis() - bufferingSince > ms
    }

    /** Liczba otwartych połączeń bieżącego strumienia (diagnostyka i testy). */
    val openCalls: Int get() = calls?.open ?: 0

    /** Kanał wstrzymany na czas STube / Stremio (kanał, adres, typ) – wznawiany po powrocie do pozostałych ekranów. */
    var suspended: Triple<Channel, String, String?>? = null; private set

    /**
     * Wejście do STube / Stremio: telewizja na żywo całkowicie zatrzymana (dekodery i połączenia zwolnione – nie obciąża
     * urządzenia), z zapamiętanym kanałem do [resumeAfterApp].
     */
    fun suspendForApp() {
        val ch = lastChannel; val u = url
        if (player != null && ch != null && u != null) suspended = Triple(ch, u, lastMime)
        if (player != null) stop()
    }

    /**
     * Powrót z STube / Stremio: wstrzymany kanał gra dalej (w tle menu / podglądzie). Nie, gdy gra film STube w tle
     * (pasek „Teraz odtwarzane”) – wtedy kanał czeka na następny powrót. Zwraca true, gdy wznowiono.
     */
    fun resumeAfterApp(c: Context, ytPlaying: Boolean = runCatching { YtEngine.player?.let { it.playWhenReady && it.playbackState != androidx.media3.common.Player.STATE_IDLE && it.playbackState != androidx.media3.common.Player.STATE_ENDED } == true }.getOrDefault(false)): Boolean {
        val s = suspended ?: return false
        if (ytPlaying) return false
        suspended = null
        if (active) return false                                 // w międzyczasie uruchomiono inny kanał
        play(c, s.first, s.second, mime = s.third)
        return true
    }

    /** Uruchomiony inny kanał (odtwarzacz TV) – wstrzymany nie jest już wznawiany. */
    fun forgetSuspended() { suspended = null }

    /** Całkowite zatrzymanie: odtwarzacz, dekodery, wątki ładowania i połączenia zwolnione (zmiana silnika, wyjście). */
    fun stop() {
        ChannelEngineOptimizer.cancel()
        main.removeCallbacks(startCheck)
        releasePlayer()
        profile = null
        hadError = false; retries = 0; recoveries = 0; recoveryWindow = 0
        sessionSoftware = false; softwareChannel = null          // ręczna zmiana silnika / wyjście – znowu ustawienia użytkownika
        url = null; channelKey = null; target = null; lastMime = null
    }
}

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
    /**
     * Widok, na którym jest obraz (podgląd w menu / przewodniku, tło ekranów, pełny ekran). Słaba referencja: singleton nie
     * trzyma zniszczonego ekranu (PlayerView → Activity), gdy ekran nie odpiął obrazu ([detach]).
     */
    private var targetRef: java.lang.ref.WeakReference<PlayerView>? = null
    private var target: PlayerView?
        get() = targetRef?.get()
        set(v) { targetRef = v?.let { java.lang.ref.WeakReference(it) } }

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
    var lastMime: String? = null; private set

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
    /** Tylko testy: dowolny błąd odtwarzania (np. błąd renderera dźwięku). */
    fun simulateError(e: androidx.media3.common.PlaybackException) = recovery.onPlayerError(e)

    /**
     * (7.4) Pomiar płynności kanału: czas od przełączenia do pierwszej klatki, zgubione klatki, przycięcia (buforowanie po
     * starcie). W logu „Playback” przy każdym przełączeniu i w oknie „Bieżący dekoder”.
     */
    class ChannelStats(val name: String, val engine: String) {
        var firstFrameMs = -1L; var dataMs = -1L; var dropped = 0; var rebuffers = 0; var startedAt = android.os.SystemClock.uptimeMillis()
        /** Wyświetlone klatki (próbki strażnika) – średnia liczba klatek na sekundę od pierwszej klatki. */
        var frames = 0; var framesAt = 0L
        val fps: Float get() = if (firstFrameMs < 0 || framesAt <= startedAt + firstFrameMs) 0f
            else frames * 1000f / (framesAt - startedAt - firstFrameMs)
        fun summary(now: Long = android.os.SystemClock.uptimeMillis()) =
            "$name [$engine]: start ${if (firstFrameMs >= 0) "$firstFrameMs ms" else "bez obrazu"}, ${(now - startedAt) / 1000} s, " +
                "${"%.1f".format(java.util.Locale.ROOT, fps)} kl/s, zgubione klatki $dropped, przycięcia $rebuffers"
    }
    @Volatile var stats: ChannelStats? = null; private set
    private var lastState = androidx.media3.common.Player.STATE_IDLE

    private fun closeStats() { stats?.let { android.util.Log.i(TAG, "pomiar • ${it.summary()}") } }

    private val measure = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            stats?.let { it.dropped += count }
            // (7.20) klatkowanie przy płynnym dźwięku → tryb zgodności kanału (FrameDropWatchdog)
            // (7.22) próba trybu zgodności – zrzuty liczone do oceny po 5 s (poprawa albo cofnięcie)
            // (7.22) „Automatyczna korekta przy zacinaniu obrazu” wyłączona – strażnik nic nie zmienia ani nie zapisuje
            if (!EngineConfigRepository.current.autoStutterCorrectionEnabled) return
            trial?.let { tr -> if (tr.readyAt > 0) tr.drops += count }
            if (dropWatch.onDropped(count, elapsedMs, android.os.SystemClock.uptimeMillis()))
                main.post { onFrameDrops(dropWatch.baselineDropRate) }
        }
        override fun onDownstreamFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                               data: androidx.media3.exoplayer.source.MediaLoadData) {
            if (data.trackType != androidx.media3.common.C.TRACK_TYPE_TEXT) dataSeen = true
            val s = stats ?: return
            if (s.dataMs < 0 && data.trackType != androidx.media3.common.C.TRACK_TYPE_TEXT) {
                s.dataMs = android.os.SystemClock.uptimeMillis() - s.startedAt
                android.util.Log.i(TAG, "pierwsze dane • ${s.name} po ${s.dataMs} ms")
            }
        }
        override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String,
                                               initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
            android.util.Log.i(TAG, "dekoder obrazu $decoderName (inicjalizacja $initializationDurationMs ms)")
            if (announcePending) main.post { announce(decoderName) }       // (7.20) po zmianie silnika – który dekoder przejął obraz
        }
        // (7.22) znana rozdzielczość / liczba klatek – profil programowy na strumieniu HD wraca na sprzętowy
        override fun onVideoInputFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format,
                                               decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            main.post { onVideoFormat(format) }
        }
        override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String,
                                               initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
        // (7.24) błąd ładowania – Media3 sam ponawia (polityka błędów ładowania); kontrolowane ponowienie startu tylko dla „cichych” połączeń
        override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo,
                                 m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, wasCanceled: Boolean) { if (!wasCanceled) loadFailed = true }
        // (7.20) nowa sesja dźwięku (np. po zmianie ścieżki / tunelowaniu) – efekt wyrównywania głośności na nowej sesji
        override fun onAudioSessionIdChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSessionId: Int) {
            ChannelLoudness.attach(audioSessionId)
        }
    }

    /** (7.20) Po zmianie przełącznika „Wyrównywanie głośności kanałów” – od razu na bieżącej sesji. */
    fun applyLoudness() { player?.let { ChannelLoudness.attach(it.audioSessionId) } ?: ChannelLoudness.release() }

    private val recovery = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlayingNow = playing }
        override fun onRenderedFirstFrame() {
            val s = stats ?: return
            if (s.firstFrameMs < 0) {
                s.firstFrameMs = android.os.SystemClock.uptimeMillis() - s.startedAt
                android.util.Log.i(TAG, "pierwsza klatka • ${s.name} po ${s.firstFrameMs} ms [${s.engine}]")
            }
        }
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
            // (7.20) błąd dekodera obrazu (inicjalizacja / dekodowanie – CodecException) na kanale na żywo: od razu dekoder
            // programowy dla tego kanału (zapamiętany), kanał od nowa w tle – bez komunikatu błędu i bez prób innych ustawień
            // (7.22) najpierw: przeciążony dekoder programowy → sprzętowy
            // (7.22) błąd dekodera w trakcie próby trybu zgodności – natychmiastowe cofnięcie
            if (trial?.key == channelKey && e.errorCode in HARDWARE_RETURN_ERRORS + SOFTWARE_FALLBACK_ERRORS && revertTrial("błąd ${e.errorCodeName}")) return
            if (hardwareReturnFor(e)) return
            if (decoderFallbackFor(e)) return
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
            // przycięcie = buforowanie prosto z odtwarzania (nie stop → przygotowanie przy przełączeniu)
            if (s == androidx.media3.common.Player.STATE_BUFFERING && lastState == androidx.media3.common.Player.STATE_READY && reachedReady) {
                stats?.let { it.rebuffers++ }
                // (7.17) kolejne przycięcia przy niskim buforze – próg wznowienia podniesiony do końca sesji
                liveLoad?.takeIf { it.onRebuffer(android.os.SystemClock.uptimeMillis()) }?.let { sessionRebufferBoostMs = it.boostMs; android.util.Log.i("Playback", "bufor po przycięciu: ${it.rebufferMs} ms (adaptacja)") }
            }
            lastState = s
            dropWatch.onState(s, android.os.SystemClock.uptimeMillis())
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
            // (7.22) okna strażnika klatkowania domykane także bez nowych zgłoszeń; ocena próby trybu zgodności
            if (EngineConfigRepository.current.autoStutterCorrectionEnabled && dropWatch.tick(now)) main.post { onFrameDrops(dropWatch.baselineDropRate) }
            checkTrial(now)
            // pomiar: wyświetlone klatki bieżącego kanału (licznik dekodera od jego włączenia)
            p.videoDecoderCounters?.let { dc -> stats?.let { s -> if (s.firstFrameMs >= 0) { s.frames = dc.renderedOutputBufferCount; s.framesAt = now } } }
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
                // (7.20) obraz stoi ≥ 3 s, a dźwięk gra (READY + isPlaying): najpierw tryb zgodności kanału (programowy + TextureView,
                // zapamiętany); dawne kroki (nowa powierzchnia po 4 s, programowy na sesję po 8 s) – gdy już w trybie zgodności
                val fps = p.videoFormat?.frameRate ?: -1f
                if (stuck > 0 && p.playbackState == androidx.media3.common.Player.STATE_READY && (fps <= 0f || fps >= FREEZE_MIN_FPS) &&
                    FrameDropWatchdog.frozenLongEnough(stuck.toLong() * WATCHDOG_MS) &&
                    enterCompatibility("obraz stoi ${stuck.toLong() * WATCHDOG_MS} ms przy płynącym dźwięku")) {
                    // nowy odtwarzacz ma już własny cykl strażnika (create); ten sam – cykl dalej
                    if (player === p) { main.removeCallbacks(this); main.postDelayed(this, WATCHDOG_MS) }
                    return
                }
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
        // (7.22) HD / FHD / ≥ 48 kl/s – nigdy dekoder programowy (procesor TV nie nadąża: ERROR_CODE_DECODING_FAILED po ~30 s)
        if (currentHeavy) { recover("zamrożony obraz (HD – bez dekodera programowego)"); return }
        if (!softwareDecoding && pl.iptv.tv.data.Prefs.freezeFallback && c != null) {
            val now = android.os.SystemClock.uptimeMillis()
            channelKey?.let { frozenChannels += it; synchronized(frozenAt) { frozenAt[it] = now } }
            // (7.4) drugi kanał z zamrożonym obrazem – dekoder sprzętowy urządzenia zawodny: programowy dla całej sesji (jak 6.5);
            // pierwszy – tylko ten kanał (fałszywy alarm nie psuje pozostałych)
            // (7.24) tylko dwa zamrożenia w [SESSION_SOFTWARE_WINDOW_MS] – dawniej dwa pojedyncze fałszywe alarmy w odstępie
            // godzin przełączały resztę długiego oglądania na klatkujący dekoder programowy
            val session = recentFreezes(now) >= SESSION_SOFTWARE_AFTER
            if (session) sessionSoftwareSince = now
            android.util.Log.w(TAG, "zamrożony obraz na dekoderze sprzętowym – przejście na programowy" +
                (if (session) " (cała sesja)" else "") + " • ${diagnostics()}")
            sessionSoftware = true; softwareChannel = if (session) null else channelKey
            rebuild(c)
            onDecoderFallback?.invoke()
        } else recover("zamrożony obraz")
    }

    /** Kanały, na których w tej sesji zamarzł obraz dekodera sprzętowego – kolejne włączenie od razu na programowym. */
    val frozenChannels: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    /** Po zamrożeniu obrazu na tylu różnych kanałach – dekoder programowy dla całej sesji. */
    const val SESSION_SOFTWARE_AFTER = 2
    /** (7.24) Zamrożenia liczone do [SESSION_SOFTWARE_AFTER] tylko z tego okna czasu. */
    const val SESSION_SOFTWARE_WINDOW_MS = 20 * 60_000L
    /** (7.24) Programowy dla całej sesji najwyżej tyle – potem kolejny kanał (nie zamrożony wcześniej) znowu na sprzętowym. */
    const val SESSION_SOFTWARE_TTL_MS = 30 * 60_000L
    /** Chwila ostatniego zamrożenia kanału (klucz) – okno [SESSION_SOFTWARE_WINDOW_MS]. */
    private val frozenAt = HashMap<String, Long>()
    /** Początek programowego dla całej sesji (0 – brak). */
    @Volatile private var sessionSoftwareSince: Long? = null
    private fun recentFreezes(now: Long) = synchronized(frozenAt) { frozenAt.values.count { now - it <= SESSION_SOFTWARE_WINDOW_MS } }
    private fun clearFrozen() { frozenChannels.clear(); synchronized(frozenAt) { frozenAt.clear() }; sessionSoftwareSince = null }
    /** Tylko testy: zamrożenia i programowy dla sesji „starsze” o [ms]. */
    fun ageFreezesForTest(ms: Long) {
        synchronized(frozenAt) { frozenAt.replaceAll { _, t -> t - ms } }
        sessionSoftwareSince = sessionSoftwareSince?.minus(ms)
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
        // (7.20) sama powierzchnia obrazu (TextureView / SurfaceView) – ten sam odtwarzacz, podmieniany tylko widok
        return (a?.copy(container = 0, surface = 0) ?: none) == (b?.copy(container = 0, surface = 0) ?: none)
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
    /** (v2) Liczba zwolnionych odtwarzaczy w procesie (testy – brak wycieków przy przełączaniu profili). */
    @Volatile var releases = 0; private set

    private fun releasePlayer() {
        main.removeCallbacks(retry); main.removeCallbacks(watchdog); main.removeCallbacks(startCheck); main.removeCallbacks(startRetry)
        val p = player
        target?.let { if (it.player === p) it.player = null }   // widok nie trzyma zwolnionego odtwarzacza
        if (p != null) {
            listeners.forEach { p.removeListener(it) }; p.removeListener(recovery)
            // (v2) każdy profil silnika (także własny / zmodyfikowany): najpierw zatrzymanie i odpięcie źródła i powierzchni –
            // bufory źródła, kodeki i AudioTrack zwalniane od razu, Surface / TextureView nie trzyma odwołania do starego odtwarzacza
            runCatching { p.stop(); p.clearMediaItems(); p.clearVideoSurface() }
            runCatching { p.release() }
            releases++
        }
        player = null
        // aktywne żądania przerwane; bezczynne połączenia z puli zostają – nowy odtwarzacz użyje ich od razu
        // (bez ponownego łączenia, które mogłoby trafić na martwy adres serwera)
        calls?.close(); calls = null
        ChannelLoudness.release()                       // (7.20) efekt zwolniony razem z sesją
        isPlayingNow = false; bufferingSince = 0; stuck = 0; lastFrames = -1; lastPos = -1; headless = false
    }

    const val ENGINE_HARDWARE = 0
    const val ENGINE_SOFTWARE = 1
    const val ENGINE_TUNNELING = 2
    val ENGINES = listOf("Media3 – dekoder sprzętowy (zalecany)", "Media3 – dekoder programowy (zgodność)", "Media3 – tunelowanie (Android TV)")

    /** Dekoder programowy: silnik „programowy” albo wyłączona akceleracja sprzętowa (Ustawienia lub przytrzymanie OK). */
    val softwareDecoding get() = pl.iptv.tv.data.EngineEff.playerEngine == ENGINE_SOFTWARE || !pl.iptv.tv.data.EngineEff.hwAccel || sessionSoftware

    /**
     * Dekoder sprzętowy zamroził obraz (dźwięk grał dalej) – dekoder programowy do końca sesji. Nie zmienia ustawień:
     * zerowane przez [stop] (zamknięcie aplikacji, ręczna zmiana silnika lub akceleracji).
     */
    @Volatile var sessionSoftware = false; private set
    /** (7.17) Podniesienie progu wznowienia po przycięciach w tej sesji (ms) i kontrola ładowania bieżącego odtwarzacza. */
    @Volatile var sessionRebufferBoostMs = 0; private set
    @Volatile var liveLoad: AdaptiveLiveLoadControl? = null; private set
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
    /** Co ile ms strażnik sprawdza obraz. (7.20) 1 s (dawniej 2 s) – zamrożenie 3 s wykrywane na czas ([FrameDropWatchdog.FREEZE_COMPAT_MS]). */
    const val WATCHDOG_MS = 1_000L
    /** (7.20) Kanały z obrazem poniżej tylu kl/s (np. stała plansza radia) – bez trybu zgodności z powodu „stojącego” obrazu. */
    const val FREEZE_MIN_FPS = 5f
    /** Po tylu ms zamrożonego obrazu – nowa powierzchnia obrazu (często wystarcza). */
    const val FREEZE_SURFACE_MS = 4_000L
    /** Po tylu ms zamrożonego obrazu – przejście na dekoder programowy (lub odświeżenie strumienia, gdy już programowy). */
    const val FREEZE_SWITCH_MS = 8_000L

    /** (7.20) Renderer obrazu zgłasza zrzucone klatki co tyle (Media3 domyślnie 50). */
    const val DROP_NOTIFY_FRAMES = 10
    /** (7.20) Maksymalny czas dołączania obrazu do zegara dźwięku (korekta dryfu A/V po starcie i przełączeniu). */
    const val VIDEO_JOINING_MS = 5_000L

    /** (7.20) Strategia zmiany odświeżania przez Media3: przy wyłączonym AFR – OFF (bez zmiany trybu matrycy). */
    fun frameRateStrategy(autoFrameRate: Boolean): Int =
        if (autoFrameRate) androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        else androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF

    /** (7.20) Obraz na TextureView wg [RenderSurfaceMode] (AUTO – tylko kanał z profilem trybu zgodności). */
    val wantsTextureView: Boolean get() = RenderSurfaceMode.current.texture(profile?.surface == 1)
    /** (7.20) Zmieniła się wymagana powierzchnia obrazu (TextureView / SurfaceView) – ekran odtwarzacza podmienia PlayerView. */
    var onSurfaceTypeChanged: (() -> Unit)? = null

    /** (7.20) Strażnik zrzucanych klatek bieżącego kanału. */
    val dropWatch = FrameDropWatchdog()
    /** (7.20) Po automatycznym przejściu klatkującego kanału na tryb zgodności (komunikat na ekranie). */
    var onFrameDropFallback: ((pl.iptv.tv.data.EngineProfile, String) -> Unit)? = null

    /**
     * (7.20) Tryb zgodności dla kanału z klatkującym obrazem: dekoder programowy obrazu + TextureView (profil kanału wyłącza
     * tunelowanie), zapisany w „Zapamiętanych silnikach kanałów” i od razu zastosowany. Null – nic do zmiany (już zgodność,
     * wyłączone w Ustawieniach, nie kanał na żywo).
     */
    fun compatibilityProfileFor(current: pl.iptv.tv.data.EngineProfile?, softwareAlready: Boolean, heavy: Boolean = false): pl.iptv.tv.data.EngineProfile? {
        val base = current ?: pl.iptv.tv.data.EngineProfile()
        // (7.22) HD / FHD / ≥ 48 kl/s: dekoder sprzętowy zostaje, tylko TextureView (profil wyłącza tunelowanie) – MediaTek na TCL
        // gubi klatki 1080i50 wyłącznie na SurfaceView; programowy przeciąża procesor (ERROR_CODE_DECODING_FAILED po ~30 s)
        if (heavy) return if (!base.software && base.surface == 1) null else base.copy(software = false, surface = 1)
        if ((base.software || softwareAlready) && (base.surface == 1 || RenderSurfaceMode.current == RenderSurfaceMode.TEXTURE_VIEW)) return null
        return base.copy(software = true, surface = 1)
    }

    /**
     * (7.20) Przejście bieżącego kanału na żywo na tryb zgodności (zrzucane klatki albo stojący obraz przy płynącym dźwięku):
     * profil zapisany w „Zapamiętanych silnikach kanałów”, kanał od nowa (nowy odtwarzacz z dekoderem programowym, PlayerView
     * na TextureView). False – nic do zrobienia (wyłączone, nie kanał na żywo, już tryb zgodności).
     */
    private fun enterCompatibility(reason: String, baseline: Float = Float.POSITIVE_INFINITY): Boolean {
        val c = lastCtx ?: return false; val ch = lastChannel ?: return false; val u = url ?: return false
        if (!EngineConfigRepository.current.autoStutterCorrectionEnabled || u != ch.url || channelKey != ch.key) return false
        if (ch.key in noIntervention) return false                     // (7.22) zmiana już nie pomogła w tej sesji
        val heavy = currentHeavy
        val prevProfile = profile
        val fps = player?.videoFormat?.frameRate ?: -1f
        val next = compatibilityProfileFor(profile, softwareDecoding, heavy) ?: return false
        android.util.Log.w(TAG, "$reason – tryb zgodności dla ${ch.name}: ${next.label} • ${diagnostics()}")
        ChannelEngines.save(ch.key, u, next)
        // (7.22) kanał 25 / 50 kl/s (albo HD bez znanej liczby klatek – zwykle 1080i50): odświeżanie TV 50 Hz na tę sesję
        if (heavy && (fps <= 0f || is25or50(fps))) sessionAfrFps = if (fps > 0f) fps else 50f
        play(c, ch, u, force = true, mime = lastMime)
        // (7.22) ocena po 5 s – bez poprawy cofnięcie ([checkTrial])
        // stojący obraz (bez pomiaru zrzutów) – baseline ∞: cofnięcie tylko po błędzie dekodera
        trial = Trial(ch.key, u, prevProfile, baseline.coerceAtLeast(0.1f))
        onFrameDropFallback?.invoke(next, FALLBACK_STUTTER)
        return true
    }

    /** Tempo zrzucania, które wywołało tryb zgodności (stan sprzed zmiany). */
    @Volatile private var lastDropRate = 0f

    /** (7.22) Strumień HD / FHD (≥ 720 linii) lub ≥ 48 kl/s – za ciężki dla dekodera programowego procesora TV. */
    fun isHeavy(height: Int, fps: Float): Boolean = height >= 720 || fps >= 48f
    private val currentHeavy: Boolean get() = player?.videoFormat?.let { isHeavy(it.height, it.frameRate) } == true
    fun is25or50(fps: Float): Boolean = kotlin.math.abs(fps - 25f) < 0.6f || kotlin.math.abs(fps - 50f) < 1.2f
    /** (7.22) Odświeżanie ekranu dopasowane po klatkowaniu w tej sesji (kl/s; null – zwykłe ustawienie AFR). Zerowane w [stop]. */
    @Volatile var sessionAfrFps: Float? = null; private set

    /** (7.22) Błędy, po których dekoder programowy wraca na sprzętowy (przeciążony procesor). */
    val HARDWARE_RETURN_ERRORS = setOf(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)

    /**
     * (7.22) Dekoder programowy obrazu (profil kanału albo awaryjny na sesję) zgłosił błąd dekodowania – procesor nie nadąża:
     * od razu dekoder sprzętowy, wpis kanału bez dekodera programowego (TextureView zostaje), kanał od nowa w tle.
     * Globalnie wybrany silnik programowy (Ustawienia) – bez zmian.
     */
    private fun hardwareReturnFor(e: androidx.media3.common.PlaybackException): Boolean {
        if (e.errorCode !in HARDWARE_RETURN_ERRORS) return false
        val c = lastCtx ?: return false; val ch = lastChannel ?: return false; val u = url ?: return false
        val fromProfile = profile?.software == true
        if (!fromProfile && !sessionSoftware) return false
        if (pl.iptv.tv.data.EngineEff.playerEngine == ENGINE_SOFTWARE || !pl.iptv.tv.data.EngineEff.hwAccel) return false
        android.util.Log.w(TAG, "błąd ${e.errorCodeName} na dekoderze programowym – powrót na sprzętowy dla ${ch.name} • ${diagnostics()}")
        dropSoftwareProfile(ch.key, u)
        calls?.cancelActive()
        main.post { if (url == u) hotReload(c, announce = false) }
        return true
    }

    /** (7.22) Profil kanału bez dekodera programowego (reszta zostaje; pusty – wpis usunięty). */
    private fun dropSoftwareProfile(key: String, u: String) {
        val p = ChannelEngines.get(key)?.profile ?: return
        if (!p.software) return
        val hw = p.copy(software = false)
        if (hw == pl.iptv.tv.data.EngineProfile()) ChannelEngines.forget(key) else ChannelEngines.save(key, ChannelEngines.get(key)?.streamUrl ?: u, hw)
    }

    /** (7.22) Zapisany profil programowy na strumieniu HD (np. z 7.20) – od razu sprzętowy + TextureView. */
    /**
     * (7.22) Próba trybu zgodności: stan przed zmianą ([prev] – profil kanału, [baseline] – zrzucane kl/s), zrzuty po starcie
     * nowego silnika ([drops] od [readyAt]). Po [TRIAL_MS] bez poprawy (≥ [TRIAL_KEEP_FRACTION] × baseline) albo po błędzie
     * dekodera – cofnięcie i kanał bez ingerencji do końca sesji ([noIntervention]).
     */
    class Trial(val key: String, val url: String, val prev: pl.iptv.tv.data.EngineProfile?, val baseline: Float) { var readyAt = 0L; var drops = 0 }
    @Volatile var trial: Trial? = null; private set
    const val TRIAL_MS = 5_000L
    const val TRIAL_KEEP_FRACTION = 0.75f
    /** Kanały, dla których zmiana nie pomogła – w tej sesji strażnik ich nie zmienia. */
    val noIntervention: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun checkTrial(now: Long) {
        val tr = trial ?: return
        if (channelKey != tr.key) { trial = null; return }
        val p = player ?: return
        if (tr.readyAt == 0L) { if (p.playbackState == androidx.media3.common.Player.STATE_READY) tr.readyAt = now; return }
        if (now - tr.readyAt < TRIAL_MS) return
        val rate = tr.drops / (TRIAL_MS / 1000f)
        if (rate >= tr.baseline * TRIAL_KEEP_FRACTION) revertTrial("bez poprawy (${"%.1f".format(java.util.Locale.ROOT, rate)} kl/s, przed zmianą ${"%.1f".format(java.util.Locale.ROOT, tr.baseline)})")
        else { android.util.Log.i(TAG, "tryb zgodności pomógł: ${"%.1f".format(java.util.Locale.ROOT, rate)} kl/s (przed: ${tr.baseline})"); trial = null }
    }

    /** Cofnięcie próby: poprzedni profil kanału (albo brak), dekoder sprzętowy, kanał bez ingerencji w tej sesji. */
    private fun revertTrial(why: String): Boolean {
        val tr = trial ?: return false
        trial = null
        val c = lastCtx ?: return false
        android.util.Log.w(TAG, "tryb zgodności cofnięty – $why")
        noIntervention += tr.key
        val back = tr.prev?.copy(software = false)?.takeIf { it != pl.iptv.tv.data.EngineProfile() }
        if (back == null) ChannelEngines.forget(tr.key) else ChannelEngines.save(tr.key, tr.url, back)
        sessionAfrFps = null
        main.post { if (url == tr.url) hotReload(c, announce = false) }
        return true
    }

    /** Tylko testy: próba z podaną liczbą zrzutów po starcie (ocena jak po 5 s). */
    fun simulateTrialResult(dropsIn5s: Int) { trial?.let { it.readyAt = 1; it.drops = dropsIn5s; checkTrial(1 + TRIAL_MS) } }

    private fun onVideoFormat(f: androidx.media3.common.Format) {
        if (f.frameRate > 0f) dropWatch.fps = f.frameRate              // (7.22) próg 40 % klatkażu strumienia
        lastChannel?.let { ch -> if (isHeavy(f.height, f.frameRate)) pl.iptv.tv.data.ChannelEngines.markHd(ch.key) }
        val ch = lastChannel ?: return; val u = url ?: return; val c = lastCtx ?: return
        if (!isHeavy(f.height, f.frameRate) || profile?.software != true || u != ch.url || !EngineConfigRepository.current.autoStutterCorrectionEnabled) return
        android.util.Log.w(TAG, "profil programowy na strumieniu ${f.height}p – sprzętowy + TextureView dla ${ch.name}")
        val p = ChannelEngines.get(ch.key)?.profile ?: return
        ChannelEngines.save(ch.key, u, p.copy(software = false, surface = 1))
        main.post { if (url == u) hotReload(c, announce = false) }
    }

    /** (7.20) Kody błędów dekodera, po których kanał od razu przechodzi na dekoder programowy. */
    val SOFTWARE_FALLBACK_ERRORS = setOf(androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)

    /**
     * (7.20) Błąd dekodera obrazu na kanale na żywo ([SOFTWARE_FALLBACK_ERRORS]; błąd dekodera dźwięku – zwykła obsługa):
     * profil kanału + dekoder programowy, zapis w „Zapamiętanych silnikach kanałów”, ponowny start w tle. False – już programowy,
     * dobór silnika wyłączony albo nie kanał na żywo (dalej dawna obsługa: dobór / komunikat).
     */
    private fun decoderFallbackFor(e: androidx.media3.common.PlaybackException): Boolean {
        if (e.errorCode !in SOFTWARE_FALLBACK_ERRORS) return false
        val format = (e as? androidx.media3.exoplayer.ExoPlaybackException)?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        if (format?.sampleMimeType?.let { MimeTypes.isVideo(it) } == false) return false
        // (7.22) HD / FHD / ≥ 48 kl/s – bez przejścia na programowy (procesor TV nie nadąża)
        val vf = format ?: player?.videoFormat
        if (vf != null && isHeavy(vf.height, vf.frameRate)) return false
        val c = lastCtx ?: return false; val ch = lastChannel ?: return false; val u = url ?: return false
        if (!pl.iptv.tv.data.Prefs.engineOptimizer || u != ch.url || channelKey != ch.key || softwareDecoding || profile?.software == true) return false
        val next = (profile ?: pl.iptv.tv.data.EngineProfile()).copy(software = true)
        android.util.Log.w(TAG, "błąd dekodera ${e.errorCodeName} – dekoder programowy dla ${ch.name} • ${diagnostics()}")
        ChannelEngines.save(ch.key, u, next)
        calls?.cancelActive()
        // nowy odtwarzacz po wyjściu z obsługi błędu (nie w jego wywołaniu zwrotnym); idempotentne z obserwatorem ustawień
        main.post { if (url == u) { reloadIfNeeded(); onFrameDropFallback?.invoke(next, FALLBACK_DECODER) } }
        return true
    }

    /** (7.20) Powody przejścia kanału na inne ustawienia silnika (komunikat na ekranie). */
    const val FALLBACK_STUTTER = "stutter"
    const val FALLBACK_DECODER = "decoder"

    private fun onFrameDrops(rate: Float): Boolean {
        lastDropRate = rate
        return enterCompatibility("klatkowanie obrazu (${"%.1f".format(java.util.Locale.ROOT, rate)} kl/s zrzuconych, dźwięk płynny)", baseline = rate)
    }

    /** Tylko testy: zgłoszenie klatkowania jak przez strażnika. */
    fun simulateFrameDrops(rate: Float = 20f) = onFrameDrops(rate)
    /** Tylko testy: obraz stoi przy płynącym dźwięku od [ms] – jak strażnik (true – przejście na tryb zgodności). */
    fun simulateStalledFrames(ms: Long = FrameDropWatchdog.FREEZE_COMPAT_MS): Boolean =
        FrameDropWatchdog.frozenLongEnough(ms) && enterCompatibility("obraz stoi ${ms} ms przy płynącym dźwięku")

    /** Kolejność dekoderów: sprzętowe (akceleracja) przed programowymi – albo odwrotnie w trybie zgodności. */
    fun codecSelector(software: Boolean) = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
        val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        // (7.20) tryb programowy obrazu: kodeki SoC (OMX.MTK / c2.mtk / amlogic / realtek / qcom …) i sprzętowe odrzucone –
        // samo sortowanie zostawiało je na liście i Media3 (zapasowy dekoder) potrafił wrócić na klatkujący kodek TCL
        if (software && androidx.media3.common.MimeTypes.isVideo(mime))
            SoftwareCodecs.pick(all, { it.name }, { it.hardwareAccelerated }, { it.softwareOnly })
        else all.sortedBy { if (it.softwareOnly == software) 0 else 1 }       // sortowanie stabilne – kolejność systemu w grupie zostaje
    }

    /** Opis bieżącego dekodera obrazu (np. „c2.mtk.avc.decoder – sprzętowy”). */
    fun decoderInfo(): String {
        val p = player ?: return "nic nie jest odtwarzane"
        val f = p.videoFormat ?: return "brak obrazu"
        val list = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(f.sampleMimeType ?: "", false, false) }.getOrDefault(emptyList())
        val hw = list.count { it.hardwareAccelerated }
        return "${f.sampleMimeType} ${f.width}×${f.height} • dekodery: $hw sprzętowe, ${list.size - hw} programowe • silnik: ${ENGINES.getOrNull(pl.iptv.tv.data.EngineEff.playerEngine)} • akceleracja: ${if (pl.iptv.tv.data.EngineEff.hwAccel) "wł." else "wył."}" +
            (if (sessionSoftware) " • awaryjnie dekoder programowy (obraz zamarzał)" else "") +
            (stats?.let { "\n\nDekoder: ${videoDecoder ?: "?"}\nPomiar: ${it.summary()}" } ?: "")
    }

    /** Nazwa dekodera obrazu bieżącego kanału (np. c2.android.avc.decoder). */
    @Volatile var videoDecoder: String? = null; private set

    /** Krótki opis silnika (pomiar w logu). */
    fun engineLabel(): String = listOfNotNull(
        when { softwareDecoding -> "programowy"; pl.iptv.tv.data.EngineEff.playerEngine == ENGINE_TUNNELING -> "tunelowanie"; else -> "sprzętowy" },
        profile?.label, if (pl.iptv.tv.data.Prefs.lowPerf) "słabsze urządzenia" else null).joinToString(", ")

    fun get(c: Context): ExoPlayer {
        player?.let { p -> if (p.applicationLooper == android.os.Looper.myLooper()) return p; releasePlayer(); url = null; target = null }
        return create(c)
    }

    // ---- (7.20) jedno źródło ustawień silnika i przebudowa w locie ----

    /** Ustawienia, z którymi zbudowano bieżący odtwarzacz ([PlayerEngineConfig.playerKey]); null – brak odtwarzacza. */
    @Volatile private var appliedKey: List<Any?>? = null
    private var lastConfig: PlayerEngineConfig? = null
    /** Po przebudowie w locie: pigułka „Zastosowano: …” po zainicjowaniu dekodera obrazu (albo po [ANNOUNCE_FALLBACK_MS]). */
    @Volatile private var announcePending = false
    /** Pigułka statusu (ekran na wierzchu – [BaseActivity]); tekst np. „Zastosowano: Dekoder programowy (c2.android.avc)”. */
    var onEngineApplied: ((String) -> Unit)? = null
    /** Licznik przebudów w locie (testy). */
    @Volatile var hotReloads = 0; private set
    const val ANNOUNCE_FALLBACK_MS = 1_500L

    init {
        // zmiana w Ustawieniach, w oknie silnika odtwarzacza, profilu kanału (strażnik, dobór) – od razu w odtwarzaczu.
        // Obserwacja [EngineConfigRepository.effectiveConfig] przez [EngineConfigRepository.observe]: każda zmiana to osobne
        // zadanie na głównym wątku (po bieżącym kodzie – np. play() po zapisie profilu kończy się przed oceną), a wyjątek
        // czy zgubione zadanie nie kończy obserwacji (długo żyjący kolektor korutyny po zgubionym wznowieniu stawał na zawsze).
        EngineConfigRepository.observe { c ->
            main.post { runCatching { onConfig(c) }.onFailure { e -> android.util.Log.w(TAG, "zmiana ustawień silnika: ${e.javaClass.simpleName}: ${e.message}") } }
        }
        lastConfig = EngineConfigRepository.current
    }

    private fun onConfig(c: PlayerEngineConfig) {
        val prev = lastConfig; lastConfig = c
        if (prev == null) return
        if (c.loudness != prev.loudness) applyLoudness()
        if (c.maxVideoResolution != prev.maxVideoResolution) applyVideoLimit()
        if (c.autoFrameRate != prev.autoFrameRate) player?.let { runCatching { it.setVideoChangeFrameRateStrategy(frameRateStrategy(c.autoFrameRate)) } }
        if (c.surfaceMode != prev.surfaceMode || c.textureView != prev.textureView) onSurfaceTypeChanged?.invoke()
        reloadIfNeeded(announce = true)
    }

    /**
     * Przebudowa odtwarzacza, gdy zmieniły się parametry budowy ([PlayerEngineConfig.playerKey]) bieżącego kanału: nowe
     * RenderersFactory / MediaCodecSelector / LoadControl, ten sam adres (VOD / archiwum – ta sama pozycja), widok bez zmian.
     * Wywoływana z obserwatora [EngineConfigRepository.effectiveConfig]; idempotentna (drugie wywołanie – nic).
     */
    fun reloadIfNeeded(announce: Boolean = false): Boolean {
        val c = lastCtx ?: return false; val ch = lastChannel ?: return false
        if (player == null || url == null || appliedKey == null) return false
        val cfg = EngineConfigRepository.current
        if (cfg.channelKey != ch.key || cfg.playerKey == appliedKey) return false
        hotReload(c, announce)
        return true
    }

    private fun hotReload(c: Context, announce: Boolean) {
        val ch = lastChannel ?: return; val u = url ?: return
        android.util.Log.i(TAG, "ustawienia silnika zmienione – przebudowa odtwarzacza w locie (${ch.name})")
        // ręczna zmiana – znowu ustawienia użytkownika (bez awaryjnego programowego z tej sesji)
        sessionSoftware = false; softwareChannel = null; clearFrozen()
        ChannelEngineOptimizer.cancel()
        profile = ChannelEngines.get(ch.key)?.let { e -> e.profile?.let { if (e.streamUrl == u) it else it.copy(container = 0) } }
        hotReloads++
        announcePending = announce
        if (announce) main.postDelayed(announceFallback, ANNOUNCE_FALLBACK_MS)
        rebuild(c)
        onSurfaceTypeChanged?.invoke()
    }

    private val announceFallback = Runnable { if (announcePending) announce(videoDecoder) }

    private fun announce(decoder: String?) {
        announcePending = false; main.removeCallbacks(announceFallback)
        val text = appliedText(EngineConfigRepository.current, decoder)
        android.util.Log.i(TAG, text)
        onEngineApplied?.invoke(text)
    }

    /** Treść pigułki po zmianie silnika, np. „Zastosowano: Dekoder programowy (c2.android.avc)”. */
    fun appliedText(cfg: PlayerEngineConfig, decoder: String?): String {
        val kind = when { cfg.tunneling -> "Tunelowanie"; cfg.software || sessionSoftware -> "Dekoder programowy"; else -> "Dekoder sprzętowy" }
        val short = decoder?.removeSuffix(".decoder")?.takeIf { it.isNotBlank() }
        return "Zastosowano: $kind" + (short?.let { " ($it)" } ?: "") + (if (cfg.textureView) " • TextureView" else "")
    }

    /** Dekoder dźwięku bieżącego kanału (np. c2.android.aac.decoder, ffmpegLib). */
    @Volatile var audioDecoder: String? = null; private set

    /** Kodeki na żywo dla okna silnika: „Wideo: c2.android.avc.decoder • 1080p50 | Audio: FFmpeg AC3”. */
    fun codecSummary(): String {
        val p = player ?: return "Wideo: — | Audio: —"
        val vf = p.videoFormat; val af = p.audioFormat
        val video = if (vf == null) "brak obrazu" else listOfNotNull(videoDecoder ?: vf.sampleMimeType,
            if (vf.height > 0) "${vf.height}p" + (vf.frameRate.takeIf { it > 0f }?.let { kotlin.math.round(it).toInt().toString() } ?: "") else null).joinToString(" • ")
        val codec = af?.sampleMimeType?.let { audioCodecName(it) }
        val audio = when {
            af == null -> "brak"
            audioDecoder?.contains("ffmpeg", ignoreCase = true) == true -> "FFmpeg" + (codec?.let { " $it" } ?: "")
            else -> listOfNotNull(audioDecoder, codec).joinToString(" ").ifBlank { "?" }
        }
        return "Wideo: $video | Audio: $audio"
    }

    /** Krótka nazwa formatu dźwięku (AC3, E-AC3, AAC, MP2…). */
    fun audioCodecName(mime: String): String = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC3"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"; MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"; MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_AC4 -> "AC4"; else -> mime.substringAfter('/').uppercase(java.util.Locale.ROOT)
    }

    private fun create(c: Context): ExoPlayer = buildPlayer(c, profile).also { p ->
        appliedKey = EngineConfigRepository.current.playerKey       // (7.20) z tymi ustawieniami zbudowano odtwarzacz
        player = p; p.addListener(recovery); listeners.forEach { p.addListener(it) }; p.addAnalyticsListener(measure)
        if (pl.iptv.tv.BuildConfig.DEBUG) p.addAnalyticsListener(loadLog)
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 3000)
        ChannelLoudness.attach(p.audioSessionId)        // (7.20) wyrównywanie głośności (sesja dźwięku tego odtwarzacza)
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
            // (7.17) FFmpeg tylko dla dźwięku: obraz zawsze na MediaCodec (bez programowego dekodera obrazu z rozszerzenia –
            // kłopot z samym dźwiękiem AC3 / E-AC3 / DTS / MP2 nie przenosi całego obrazu na procesor)
            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                                             enableDecoderFallback: Boolean, eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                                             allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>) {
                // (7.20) jak DefaultRenderersFactory przy OFF (sam MediaCodecVideoRenderer), ale zrzucone klatki zgłaszane co
                // DROP_NOTIFY_FRAMES (domyślnie co 50) – strażnik klatkowania widzi problem po 2–3 s, nie po kilkunastu
                out.add(androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context, getCodecAdapterFactory(), mediaCodecSelector,
                    allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, DROP_NOTIFY_FRAMES))
            }
        }
        // (7.20) dłuższe dołączanie obrazu do zegara dźwięku – po opróżnieniu startowego bufora dekoder nie wpada w pętlę
        // agresywnego odrzucania klatek (dryf A/V korygowany łagodnie)
        renderers.setAllowedVideoJoiningTimeMs(VIDEO_JOINING_MS)
        // (7.22) tunelowanie: tempo klatek z zegara sprzętowego TV (Media3 w trybie tunelowania otwiera AudioTrack z
        // AudioAttributes.FLAG_HW_AV_SYNC); bez programowego rozciągania czasu przez AudioTrack.PlaybackParams
        if (pl.iptv.tv.data.EngineEff.playerEngine == ENGINE_TUNNELING && pl.iptv.tv.data.EngineEff.hwAccel && profile == null) renderers.setEnableAudioTrackPlaybackParams(false)
        val P = pl.iptv.tv.data.EngineEff        // (7.28) nadpisanie kanału albo Ustawienia globalne
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
        // (7.17) inaczej FFmpeg za dekoderami systemu (ON) – formaty dźwięku, których urządzenie nie dekoduje, grają bez zmiany silnika obrazu
        renderers.setExtensionRendererMode(when {
            profile?.ffmpeg == true -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            StremioDecoders.ffmpegAvailable -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        })
        val low = P.lowPerf
        // słabsze urządzenia: ograniczona rozdzielczość (domyślnie 1080p – bez 4K, którego dekoder nie nadąża) i mniejszy bufor w pamięci
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(c.applicationContext).apply {
            val b = buildUponParameters()
            if (low && P.lowMaxHeight > 0) b.setMaxVideoSize(P.lowMaxHeight * 16 / 9, P.lowMaxHeight).setExceedRendererCapabilitiesIfNecessary(false)
            // tunelowanie: obraz i dźwięk dekodowane i synchronizowane sprzętowo (Android TV) – najmniejsze obciążenie procesora
            if (engine == ENGINE_TUNNELING && P.hwAccel && !sessionSoftware && profile == null) b.setTunnelingEnabled(true)
            setParameters(b)
        }
        val start = if (P.lowLatency) 800 else LIVE_START_BUFFER_MS
        var rebuffer = LIVE_REBUFFER_MS
        val load = if (profile?.buffer == 1) {
            // (7.5) profil kanału „duży bufor” (ręczne dopasowanie zacinającego się kanału): więcej zapasu przed startem i po przycięciu
            rebuffer = BIG_REBUFFER_MS
            DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, BIG_START_BUFFER_MS, BIG_REBUFFER_MS)
                .apply { if (low) setTargetBufferBytes(P.lowBufMb.coerceIn(8, 256) * 1024 * 1024) }
                .setPrioritizeTimeOverSizeThresholds(!low).build()
        } else if (low) {
            // (7.17) bezpieczne proporcje (LiveBuffer.safe): „Bufor – minimum = 2 s” przy progu po przycięciu 5 s łamało wymagania
            // DefaultLoadControl (próg ≤ minimum) – budowa odtwarzacza kończyła się błędem i Live TV się wyłączało
            val d = LiveBuffer.safe(P.lowBufMinS.coerceIn(1, 60) * 1000, P.lowBufMaxS.coerceIn(2, 120) * 1000)
            rebuffer = d.rebufferMs
            DefaultLoadControl.Builder().setBufferDurationsMs(d.minMs, d.maxMs, minOf(start, d.playMs).takeIf { P.lowLatency } ?: d.playMs, d.rebufferMs)
                .setTargetBufferBytes(P.lowBufMb.coerceIn(8, 256) * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        }
        // (7.17) kanały na żywo: start po 1 s zapasu, po przycięciu 2 s (szybkie przełączanie), bufor 30–60 s ograniczony
        // pamięcią urządzenia ([liveBufferBytes]); na urządzeniach do ~2 GB RAM limit bajtów ma pierwszeństwo przed czasem.
        // Zacinający się kanał dostaje profil „duży bufor” (ręczne dopasowanie silnika – 3 s / 8 s).
        else if (P.bufMinS > 0) {
            // (7.17) „Bufor – minimum” dla każdego urządzenia (profile Szybkie przełączanie / Maksymalna stabilność) – bezpieczne proporcje
            val total = totalMemBytes(c)
            val d = LiveBuffer.safe(P.bufMinS * 1000, 60_000)
            rebuffer = d.rebufferMs
            DefaultLoadControl.Builder().setBufferDurationsMs(d.minMs, d.maxMs, if (P.lowLatency) minOf(800, d.playMs) else d.playMs, d.rebufferMs)
                .setTargetBufferBytes(liveBufferBytes(total)).setPrioritizeTimeOverSizeThresholds(!isLowRam(total)).build()
        }
        else {
            val total = totalMemBytes(c)
            DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, start, LIVE_REBUFFER_MS)
                .setTargetBufferBytes(liveBufferBytes(total))
                .setPrioritizeTimeOverSizeThresholds(!isLowRam(total)).build()
        }
        val probe = looper != null
        // (7.17) adaptacja w trakcie sesji: częste przycięcia przy niskim progu → większy zapas wznowienia (bez błędu i bez restartu)
        val adaptive = AdaptiveLiveLoadControl(load, rebuffer, if (probe) 0 else sessionRebufferBoostMs)
        if (!probe) liveLoad = adaptive
        return ExoPlayer.Builder(c.applicationContext, renderers)
            .setTrackSelector(selector)
            .setLoadControl(adaptive)
            .apply { if (looper != null) setLooper(looper) }
            // (7.20) AFR wyłączone → Media3 nie zmienia odświeżania matrycy (Surface.setFrameRate) – na TCL przy 50i/50p
            // wymuszany niekompatybilny tryb dawał klatkowanie; z AFR – domyślnie tylko bezszwowe przełączanie
            .setVideoChangeFrameRateStrategy(frameRateStrategy(P.autoFrameRate))
            .apply {
                // odtwarzacz testowy: bez blokady Wi-Fi i bez przejmowania dźwięku (nie przerywa niczego, co gra)
                if (!probe) setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)          // Wi-Fi nie usypia podczas oglądania
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), !probe)
                setHandleAudioBecomingNoisy(!probe)
            }
            .build()
            .also { p -> if (!probe) applyVideoLimit(p) }
    }

    /**
     * (7.22) Parametry wyboru ścieżek z limitem [VideoResolutionLimit]: AUTO – `clearVideoSizeConstraints()` (bez limitu, jak
     * dotąd), a w trybie słabszych urządzeń zostaje jego limit ([Prefs.lowMaxHeight]); inne progi – `setMaxVideoSize` (przy
     * obu limitach – mniejszy). Strumień bez pasującej jakości gra dalej najniższą (domyślne `exceedVideoConstraintsIfNecessary`).
     */
    fun videoLimitParams(base: androidx.media3.common.TrackSelectionParameters, limit: VideoResolutionLimit, lowMaxHeight: Int): androidx.media3.common.TrackSelectionParameters {
        val b = base.buildUpon()
        val lowW = if (lowMaxHeight > 0) lowMaxHeight * 16 / 9 else Int.MAX_VALUE
        val lowH = if (lowMaxHeight > 0) lowMaxHeight else Int.MAX_VALUE
        if (!limit.limited && lowMaxHeight <= 0) b.clearVideoSizeConstraints()
        else b.setMaxVideoSize(minOf(limit.maxWidth, lowW), minOf(limit.maxHeight, lowH))
        return b.build()
    }

    /** Ostatnio zastosowany limit (testy, okno „Bieżący dekoder”). */
    @Volatile var appliedVideoLimit: VideoResolutionLimit = VideoResolutionLimit.AUTO; private set

    /** (7.22) Limit rozdzielczości obrazu na odtwarzaczu [p] (w locie – bez przebudowy i bez przerwy w obrazie). */
    fun applyVideoLimit(p: androidx.media3.common.Player? = player) {
        p ?: return
        runCatching {
            val limit = EngineConfigRepository.current.maxVideoResolution
            val low = if (pl.iptv.tv.data.Prefs.lowPerf) pl.iptv.tv.data.Prefs.lowMaxHeight else 0
            p.trackSelectionParameters = videoLimitParams(p.trackSelectionParameters, limit, low)
            appliedVideoLimit = limit
        }.onFailure { e -> android.util.Log.w(TAG, "limit rozdzielczości: ${e.javaClass.simpleName}: ${e.message}") }
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
                .apply { if (pl.iptv.tv.data.EngineEff.lowLatency) setTargetOffsetMs(4_000) }.build()).apply {
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

    /** (7.17) Liczba rzeczywistych startów strumienia (nowe źródło + prepare) – diagnostyka i testy płynnego przejścia PiP → pełny ekran. */
    @Volatile var starts = 0; private set

    /**
     * Zapas (ms), od którego kontrola startu ([startDecision]) uznaje dekoder za zawieszony; także bufor po przycięciu
     * w trybie słabszych urządzeń (ustawienia użytkownika).
     */
    const val START_BUFFER_MS = 2_000
    const val REBUFFER_MS = 5_000
    /** (7.17) Kanały na żywo: zapas przed startem i po przycięciu (ms) – `bufferForPlaybackMs` / `…AfterRebufferMs`. */
    const val LIVE_START_BUFFER_MS = 1_000
    const val LIVE_REBUFFER_MS = 2_000
    /** Urządzenie „2 GB” (z zapasem na pamięć zarezerwowaną przez system – raportowane ~1,8–2,0 GB). */
    const val LOW_RAM_BYTES = 2_600L * 1024 * 1024
    /** Limit bufora strumienia w pamięci: urządzenia do ~2 GB RAM / pozostałe. */
    const val LIVE_BUFFER_LOW_BYTES = 24 * 1024 * 1024
    const val LIVE_BUFFER_BYTES = 64 * 1024 * 1024

    fun isLowRam(totalMem: Long) = totalMem in 1..LOW_RAM_BYTES
    /** Limit bajtów bufora dla urządzenia z [totalMem] bajtów RAM (0 – nieznane: limit dla słabszych). */
    fun liveBufferBytes(totalMem: Long) = if (totalMem <= 0 || isLowRam(totalMem)) LIVE_BUFFER_LOW_BYTES else LIVE_BUFFER_BYTES

    @Volatile private var totalMem = 0L
    private fun totalMemBytes(c: Context): Long {
        if (totalMem == 0L) totalMem = runCatching {
            val am = c.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
        return totalMem
    }
    /** Profil „duży bufor” ([pl.iptv.tv.data.EngineProfile.buffer]). */
    const val BIG_START_BUFFER_MS = 3_000
    const val BIG_REBUFFER_MS = 8_000

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
            // (7.17) pula dla IPTV: do 5 bezczynnych połączeń przez 30 s (segmenty HLS na tym samym połączeniu, martwe szybko zamykane)
            .connectionPool(okhttp3.ConnectionPool(STREAM_POOL_IDLE, STREAM_POOL_KEEP_S, java.util.concurrent.TimeUnit.SECONDS))
            .dispatcher(okhttp3.Dispatcher())        // własny – strumień nie dzieli kolejki z EPG, logo i aktualizacjami
            .retryOnConnectionFailure(true)
            .addInterceptor(StreamRetry)
            .build()
    }

    /**
     * (7.17) Ponowienie odpowiedzi serwera IPTV [RETRY_CODES] (przeciążenie, limit połączeń, brama) po [RETRY_DELAY_MS] –
     * do [RETRY_MAX] razy; strumień startuje zamiast błędu odtwarzacza i doboru silnika. Przerwane wywołanie nie czeka.
     */
    object StreamRetry : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            var r = chain.proceed(chain.request())
            var n = 0
            while (r.code in RETRY_CODES && n < RETRY_MAX && !chain.call().isCanceled()) {
                r.close(); n++
                try { Thread.sleep(RETRY_DELAY_MS) } catch (e: InterruptedException) { throw java.io.InterruptedIOException("przerwane") }
                r = chain.proceed(chain.request())
            }
            return r
        }
    }
    val RETRY_CODES = setOf(403, 429, 502, 503, 504)
    const val RETRY_DELAY_MS = 400L
    const val RETRY_MAX = 2
    const val STREAM_POOL_IDLE = 5
    const val STREAM_POOL_KEEP_S = 30L
    /**
     * Limit nawiązania połączenia z jednym adresem serwera. Serwery IPTV mają wiele adresów (DNS z rotacją), a część z nich
     * bywa martwa – połączenie wisi. Przy 12 s każde trafienie na taki adres zatrzymywało strumień na 12 s (zamrożony obraz,
     * wypadnięcie z okna na żywo); przy 4 s klient od razu próbuje następnego adresu, a martwy odkłada na koniec kolejki.
     */
    const val STREAM_CONNECT_S = 4L
    /** (7.17) Odczyt: zawieszony strumień wykryty po 10 s (wcześniej 20 s) – szybsze odnowienie połączenia. */
    const val STREAM_READ_S = 10L

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
        val warmStart = android.os.SystemClock.uptimeMillis()
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
            android.util.Log.i(TAG, "rozgrzewanie gotowe po ${android.os.SystemClock.uptimeMillis() - warmStart} ms")
        }, "PlaybackWarmUp").start()
    }

    /** Odtwarza [streamUrl]; ten sam adres nie jest uruchamiany ponownie. Zwraca true, gdy rozpoczęto nowe odtwarzanie. */
    fun play(c: Context, ch: Channel, streamUrl: String, force: Boolean = false, mime: String? = null): Boolean {
        // inny kanał niż dobierany w tle – dobór przerwany (odtwarzacz testowy zwolniony)
        ChannelEngineOptimizer.cancelUnless(streamUrl)
        // (7.20) profil tego kanału w jednym źródle ustawień (okno silnika, Ustawienia, przebudowa w locie)
        // (7.28) ręczne ustawienia silnika tego kanału (odczyt z pamięci) – przed związaniem konfiguracji z kanałem
        pl.iptv.tv.data.ChannelEngineOverrides.bind(ch)
        EngineConfigRepository.bindChannel(ch.key)
        // zapamiętany profil silnika kanału (Room, odczyt z pamięci); rodzaj strumienia tylko dla adresu, dla którego go dobrano
        val want = ChannelEngines.get(ch.key)?.let { e -> e.profile?.let { if (e.streamUrl == streamUrl) it else it.copy(container = 0) } }
        // (7.3) awaryjny dekoder programowy tylko dla kanału, na którym obraz zamarzł – inny kanał znów na sprzętowym
        // (7.24) programowy dla całej sesji wygasa po [SESSION_SOFTWARE_TTL_MS] – kanał, który nie zamarzł, znowu na sprzętowym
        val since = sessionSoftwareSince
        val sessionExpired = sessionSoftware && softwareChannel == null && since != null && ch.key !in frozenChannels &&
            android.os.SystemClock.uptimeMillis() - since > SESSION_SOFTWARE_TTL_MS
        val leaveSoftware = (sessionSoftware && softwareChannel != null && softwareChannel != ch.key) || sessionExpired
        if (leaveSoftware) { sessionSoftware = false; softwareChannel = null; if (sessionExpired) sessionSoftwareSince = null }
        // (7.4) kanał, na którym w tej sesji już zamarzł obraz – od razu programowy (bez kolejnych 8 s zamrożenia)
        val enterSoftware = !sessionSoftware && ch.key in frozenChannels && pl.iptv.tv.data.Prefs.freezeFallback
        if (enterSoftware) { sessionSoftware = true; softwareChannel = ch.key }
        // (7.28) inne efektywne parametry budowy (nadpisanie kanału / powrót do globalnych) – nowy odtwarzacz od razu, przed startem
        // strumienia (bez podwójnego startu); kanały z tymi samymi parametrami – bez przebudowy (płynne przełączanie)
        val overrideSwitch = player != null && appliedKey != null && EngineConfigRepository.current.playerKey != appliedKey
        val switchEngine = ((leaveSoftware != enterSoftware) || overrideSwitch) && player != null
        if (want != profile || switchEngine) {
            // inne ustawienia silnika – nowy odtwarzacz (stary całkowicie zwolniony), obraz na tym samym widoku
            // (7.3) sam rodzaj strumienia (HLS / TS) – ten sam odtwarzacz, bez przebudowy (płynne przełączanie)
            val view = target
            val rebuild = !sameEngine(want, profile) || switchEngine
            val texBefore = wantsTextureView
            profile = want
            if (player != null && rebuild) {
                releasePlayer(); url = null
                val np = create(c)
                view?.let { it.player = np; target = it }
                onPlayerChanged?.invoke(np)
            }
            // (7.20) po ewentualnej przebudowie: ekran podmienia PlayerView (TextureView ↔ SurfaceView) i podłącza odtwarzacz
            if (texBefore != wantsTextureView) onSurfaceTypeChanged?.invoke()
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
        val clean = hadError || wasHeadless || p.playerError != null || !reachedReady || pl.iptv.tv.data.Prefs.cleanDecoderOnZap   // (7.17) opcja dla każdego urządzenia
        // (7.27) nowa powierzchnia obrazu tylko po faktycznym błędzie – przy zwykłej zmianie kanału ta sama powierzchnia (bez rekonfiguracji wyjścia obrazu boxa)
        val freshSurface = hadError || p.playerError != null
        if (clean) { p.stop(); p.clearMediaItems() }
        // osobna grupa połączeń dla nowego strumienia; poprzednia zamykana po podmianie źródła (niżej)
        val previousCalls = calls
        val streamCalls = StreamCalls(streamHttp); calls = streamCalls
        lastState = androidx.media3.common.Player.STATE_IDLE          // buforowanie po przełączeniu to nie przycięcie
        p.setMediaSource(mediaSource(c, ch, streamUrl, mimeFor(streamUrl, mime, profile), streamCalls))
        p.playWhenReady = true
        p.prepare()
        // po podmianie źródła: stare połączenia przerwane od razu (stare źródło jest już zwolnione, więc przerwanie
        // nie zgłosi błędu) – nowy kanał nie czeka, aż dostawca zwolni miejsce po poprzednim
        previousCalls?.close()
        android.util.Log.i(TAG, "start ${ch.name}${if (clean) " (czysty start)" else ""}")
        starts++
        if (streamUrl != url) {
            recoveries = 0; recoveryWindow = 0                                // nowy kanał – licznik odwieszeń od zera
            closeStats(); stats = ChannelStats(ch.name, engineLabel())
            // (7.24) podniesiony po przycięciach próg wznowienia dotyczy tylko kanału, na którym się przycinało
            sessionRebufferBoostMs = 0; liveLoad?.reset()
        }
        url = streamUrl; channelKey = ch.key; lastMime = mime
        dropWatch.reset()
        retries = 0; stuck = 0; bufferingSince = 0; hadError = false; reachedReady = false
        // (7.24) kontrolowane ponowienie, gdy strumień nie dał żadnych danych (martwe połączenie z puli, zawieszony adres)
        dataSeen = false; loadFailed = false; startRetries = 0
        main.removeCallbacks(startRetry)
        if (streamUrl == ch.url) main.postDelayed(startRetry, START_RETRY_MS)
        // po błędzie: świeża powierzchnia obrazu (to samo, co wcześniej dawało dopiero otwarcie menu)
        if (freshSurface) main.post { target?.let { v -> if (v.player === p) { v.player = null; v.player = p } } }
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

    /**
     * (7.24) Kontrolowane ponowienie startu kanału na żywo: po [START_RETRY_MS] bez żadnych danych strumienia (ani formatu,
     * ani zapasu) – połączenia przerwane, bezczynne połączenia z puli usunięte (w tle) i ponowne otwarcie tego samego źródła
     * na tym samym odtwarzaczu, najwyżej [START_RETRY_MAX] razy co [START_RETRY_MS]. Dawniej taki kanał czekał na odczyt
     * martwego połączenia (10 s) albo strażnika buforowania ([STALL_MS] 12 s) – „co któryś kanał długo się ładuje” po
     * dłuższym oglądaniu (połączenia w puli wygasłe po stronie dostawcy / sieci). Szybkie przełączanie bez zmian: każde
     * [play] anuluje zaplanowane ponowienie, więc dotyczy tylko kanału, na którym użytkownik został.
     */
    const val START_RETRY_MS = 3_500L
    const val START_RETRY_MAX = 2
    /** Ponowienia startu bieżącego kanału / wszystkie w procesie (diagnostyka i testy). */
    @Volatile var startRetries = 0; private set
    @Volatile var startRetriesTotal = 0; private set
    /** Bieżący start dostał dane strumienia (format obrazu / dźwięku). */
    @Volatile private var dataSeen = false
    /** Bieżący start miał błąd ładowania (Media3 ponawia sam). */
    @Volatile private var loadFailed = false

    /** Czy ponowić start: brak danych i zapasu, bez trwającej obsługi błędu, w limicie prób. */
    fun startRetryDue(hasData: Boolean, bufferedMs: Long, ready: Boolean, errorHandling: Boolean, done: Int): Boolean =
        !ready && !hasData && bufferedMs < START_RETRY_BUFFERED_MS && !errorHandling && done < START_RETRY_MAX
    const val START_RETRY_BUFFERED_MS = 300L

    private val startRetry = object : Runnable {
        override fun run() {
            val p = player ?: return; val u = url ?: return
            if (!p.playWhenReady || p.playerError != null) return
            if (!startRetryDue(dataSeen, p.totalBufferedDuration, reachedReady, retries > 0 || hadError || loadFailed, startRetries)) return
            startRetries++; startRetriesTotal++
            android.util.Log.w(TAG, "start bez danych po ${(android.os.SystemClock.uptimeMillis() - startedAt) / 1000} s – " +
                "ponowne połączenie (próba $startRetries) • ${diagnostics()}")
            // najpierw stop (ładowanie anulowane – bez fałszywego błędu ładowania), potem przerwanie połączeń tej grupy
            p.stop(); calls?.cancelActive()
            val pool = streamHttp.connectionPool
            // zamykanie gniazd (TLS) poza wątkiem UI; ponowne otwarcie po usunięciu – nowe połączenie nie trafi na martwe z puli
            Thread({
                runCatching { pool.evictAll() }
                main.post {
                    if (player !== p || url != u || reachedReady || dataSeen) return@post
                    loadFailed = false; bufferingSince = 0
                    p.prepare(); p.playWhenReady = true
                    if (startRetries < START_RETRY_MAX) { main.removeCallbacks(this); main.postDelayed(this, START_RETRY_MS) }
                }
            }, "StreamReconnect").start()
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
        main.removeCallbacks(retry); main.removeCallbacks(startCheck); main.removeCallbacks(startRetry)
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
        closeStats(); stats = null
        releasePlayer()
        profile = null
        hadError = false; retries = 0; recoveries = 0; recoveryWindow = 0
        sessionSoftware = false; softwareChannel = null; clearFrozen()          // ręczna zmiana silnika / wyjście – znowu ustawienia użytkownika
        sessionAfrFps = null                                                                // (7.22) dopasowanie 50 Hz tylko na sesję
        trial = null; noIntervention.clear()
        sessionRebufferBoostMs = 0; liveLoad = null                                         // (7.17) adaptacja bufora – od nowa
        url = null; channelKey = null; target = null; lastMime = null
        appliedKey = null; announcePending = false; main.removeCallbacks(announceFallback)
    }
}

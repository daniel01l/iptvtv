package pl.iptv.tv.ui

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs

/**
 * Inteligentny dobór silnika dla kanału, który nie otwiera się na domyślnych ustawieniach (błąd dekodera, nierozpoznany
 * format, brak obrazu po [Playback.START_CHECK_MS]).
 *
 * Bezpiecznie dla słabszych urządzeń:
 * - kanał zatrzymany na czas testów ([Playback.holdForProbe]) – testowy odtwarzacz nie konkuruje o dekoder ani połączenie;
 * - próby ściśle po kolei ([candidates]), każda najwyżej [PROBE_MS], na osobnym wątku ([thread]) – interfejs (60 FPS) bez obciążenia;
 * - po każdej próbie odtwarzacz testowy całkowicie zwalniany (`release()`) i połączenia przerwane, zanim ruszy następna;
 * - test bez dźwięku i bez blokady Wi-Fi, obraz do niewidocznej powierzchni (tylko sprawdzenie pierwszej klatki).
 *
 * Wynik w Room ([ChannelEngines]): udany profil – kolejne włączenia od razu z nim, bez testów; brak udanego –
 * `testedFailed` (bez testów przez [ChannelEngines.RETEST_MS]) i zwykłe odtwarzanie z ponowieniami. Najwyżej jeden dobór
 * na kanał w sesji ([triedThisSession]) – bez pętli testów.
 */
object ChannelEngineOptimizer {
    /** Limit jednej próby. */
    const val PROBE_MS = 3_500L

    /** Stan doboru dla ekranu odtwarzacza (komunikat). */
    sealed class Status {
        data class Testing(val channel: String, val step: Int, val of: Int, val label: String) : Status()
        data class Found(val channel: String, val label: String) : Status()
        data class Failed(val channel: String) : Status()
    }

    /** Słuchacz ekranu (wątek główny). */
    var onStatus: ((Status) -> Unit)? = null

    /** Testy: podmiana próby (bez prawdziwego odtwarzacza): profil → czy otwiera strumień. */
    @Volatile var probeOverride: (suspend (EngineProfile) -> Boolean)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(android.os.Looper.getMainLooper())
    private var job: Job? = null
    @Volatile var runningUrl: String? = null; private set
    /** Profile sprawdzone w ostatnim doborze (testy, diagnostyka). */
    @Volatile var lastTried: List<EngineProfile> = emptyList(); private set
    private val triedThisSession = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Osobny wątek odtwarzaczy testowych (tworzony raz, przy pierwszym doborze). */
    private val thread: HandlerThread by lazy { HandlerThread("EngineProbe", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() } }

    val running get() = job?.isActive == true

    /**
     * Ustawienia do sprawdzenia, po kolei – od najczęściej pomagających, bez profilu, który właśnie zawiódł ([failed]).
     * Wymuszenie HLS / TS tylko, gdy adres nie mówi jednoznacznie, czym jest strumień.
     */
    fun candidates(url: String, failed: EngineProfile?, allowSoftware: Boolean = true): List<EngineProfile> {
        val mime = Playback.guessMime(url)
        val ts = url.lowercase().substringBefore('?').substringBefore('#').endsWith(".ts")
        val unknown = mime == null && !ts
        val hls = mime == androidx.media3.common.MimeTypes.APPLICATION_M3U8
        // (7.2) najpierw ustawienia, które nie pogarszają obrazu (dekoder sprzętowy zostaje); dekoder programowy – na końcu
        // i tylko przy błędzie dekodera: na wielu TV klatkuje obraz HD, a w 7.0 bywał zapisywany dla dobrych kanałów
        return buildList {
            if (unknown) add(EngineProfile(container = 1))                    // adres bez rozszerzenia, a serwer podaje HLS
            add(EngineProfile(ffmpeg = true))                                 // dźwięk AC-3 / E-AC-3 / DTS / MP2 bez dekodera systemu
            add(EngineProfile(async = 2))                                     // kolejka asynchroniczna zawiesza dekoder części TV
            if (hls || unknown) add(EngineProfile(container = 2))             // „.m3u8”, który jest zwykłym strumieniem TS
            if (allowSoftware) {
                add(EngineProfile(software = true))                           // dekoder sprzętowy nie obsługuje profilu obrazu
                add(EngineProfile(software = true, ffmpeg = true))
                if (unknown) add(EngineProfile(software = true, container = 1))
            }
        }.distinct().filter { it != (failed ?: EngineProfile()) }
    }

    /**
     * Kanał nie ruszył. true – dobór uruchomiony (albo już trwa) i kanał czeka na wynik; false – zwykła obsługa błędu
     * (dobór wyłączony, kanał niedawno oznaczony jako niedziałający, dobór dla niego był już w tej sesji).
     */
    fun onStartFailed(c: Context, ch: Channel, url: String, reason: String, decoderError: Boolean = false): Boolean {
        if (runningUrl == url && running) return true
        if (!runCatching { Prefs.engineOptimizer }.getOrDefault(true)) return false
        if (ChannelEngines.blocked(ch.key)) return false
        if (!triedThisSession.add(ch.key + "|" + url)) return false
        val failed = Playback.profile
        // zapamiętany profil przestał działać – zapomniany, dobór od nowa
        if (failed != null) ChannelEngines.forget(ch.key)
        android.util.Log.w("EngineOptimizer", "dobór silnika: ${ch.name} ($reason)")
        start(c.applicationContext, ch, url, candidates(url, failed, allowSoftware = decoderError))
        return true
    }

    private fun start(c: Context, ch: Channel, url: String, list: List<EngineProfile>) {
        cancel()
        Playback.holdForProbe()
        runningUrl = url; lastTried = emptyList()
        job = scope.launch {
            val tried = mutableListOf<EngineProfile>()
            var found: EngineProfile? = null
            for ((i, p) in list.withIndex()) {
                if (!isActive) return@launch
                post { onStatus?.invoke(Status.Testing(ch.name, i + 1, list.size, p.label)) }
                tried += p; lastTried = tried.toList()
                if (probe(c, ch, url, p)) { found = p; break }
            }
            if (!isActive) return@launch
            if (found != null) ChannelEngines.save(ch.key, url, found) else ChannelEngines.markFailed(ch.key, url)
            post {
                if (runningUrl != url) return@post
                runningUrl = null
                onStatus?.invoke(if (found != null) Status.Found(ch.name, found.label) else Status.Failed(ch.name))
                // kanał od nowa: z dobranym profilem albo (brak) ze zwykłymi ustawieniami i ponowieniami
                Playback.restartAfterProbe(c, ch, url)
            }
        }
    }

    private fun post(r: () -> Unit) { main.post(r) }

    // ================= (7.5) ręczne dopasowanie – kanał działa, ale się zacina =================

    /** Najwyżej tyle prób ręcznego dopasowania. */
    const val MANUAL_MAX = 8
    /** Odtwarzanie każdej próby (pomiar płynności po pierwszej klatce). */
    const val MANUAL_PLAY_MS = 2_500L
    /** Limit na pojawienie się pierwszej klatki w próbie. */
    const val MANUAL_START_MS = 2_500L
    /** Całe dopasowanie najwyżej tyle (kolejna próba nie rusza po przekroczeniu). */
    const val MANUAL_BUDGET_MS = 18_000L
    /** Ręczne dopasowanie jednego kanału najwyżej tyle razy w sesji (bez pętli prób na słabszych TV). */
    const val MANUAL_PER_SESSION = 2

    /** Wynik pomiaru próby: płynność wg wyświetlonych / zgubionych klatek i przycięć. */
    data class ProbeResult(val started: Boolean, val firstFrameMs: Long = -1, val frames: Int = 0, val dropped: Int = 0,
                           val rebuffers: Int = 0, val playedMs: Long = 0, val frameRate: Float = -1f) {
        private val expected: Float get() = if (frameRate > 0f && playedMs > 0) frameRate * playedMs / 1000f else 0f
        /** Bez przycięć, prawie bez zgubionych klatek i (gdy znana liczba klatek na sekundę) co najmniej 85 % klatek. */
        val smooth: Boolean get() = started && rebuffers == 0 && dropped <= 2 && (expected <= 0f || frames >= expected * 0.85f)
        val score: Double get() = if (!started) -1e9 else 1000.0 - rebuffers * 200.0 - dropped * 3.0 - firstFrameMs / 100.0 +
            (if (expected > 0f) 100.0 * minOf(1f, frames / expected) else 0.0)
    }

    enum class ManualStart { STARTED, RUNNING, LIMIT, CRASHED }

    /** Testy: podmiana pomiaru (bez prawdziwego odtwarzacza). */
    @Volatile var measureOverride: (suspend (EngineProfile) -> ProbeResult)? = null
    private val manualRuns = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Kandydaci ręcznego dopasowania – od najmniej inwazyjnych (większy zapas, kolejka dekodera, dźwięk FFmpeg) po dekoder
     * programowy (na końcu: na wielu TV klatkuje obraz HD). Bez ustawień, które właśnie się zacinają ([current]).
     */
    fun manualCandidates(url: String, current: EngineProfile?): List<EngineProfile> {
        val mime = Playback.guessMime(url)
        val ts = url.lowercase().substringBefore('?').substringBefore('#').endsWith(".ts")
        val unknown = mime == null && !ts
        val hls = mime == androidx.media3.common.MimeTypes.APPLICATION_M3U8
        val now = current ?: EngineProfile()
        return buildList {
            if (current != null) add(EngineProfile())                         // zapamiętany profil się zacina – najpierw ustawienia domyślne
            add(EngineProfile(buffer = 1))                                     // strumień przycina się z braku zapasu
            add(EngineProfile(async = 2))                                      // kolejka asynchroniczna gubi klatki na części TV
            add(EngineProfile(ffmpeg = true))                                  // dźwięk, którego dekoder systemu nie nadąża
            add(EngineProfile(async = 2, buffer = 1))
            if (unknown) add(EngineProfile(container = 1, buffer = 1))
            if (hls) add(EngineProfile(container = 2, buffer = 1))
            add(EngineProfile(software = true, buffer = 1))
            add(EngineProfile(software = true))
        }.distinct().filter { it != now }.take(MANUAL_MAX)
    }

    /** Ręczne dopasowanie dla bieżącego kanału (przytrzymanie OK → ustawienia silnika). */
    fun startManual(c: Context, ch: Channel, url: String): ManualStart {
        if (running) return ManualStart.RUNNING
        // poprzednie dopasowanie tego kanału przerwało działanie aplikacji (np. awaria dekodera słabszego TV) – bez powtórki
        val crashed = runCatching { Prefs.engineManualRunning }.getOrDefault("")
        if (crashed.isNotEmpty()) { runCatching { Prefs.engineManualRunning = "" }; if (crashed == ch.key) return ManualStart.CRASHED }
        val n = manualRuns.merge(ch.key, 1, Int::plus) ?: 1
        if (n > MANUAL_PER_SESSION) return ManualStart.LIMIT
        val current = Playback.profile
        val list = manualCandidates(url, current)
        cancel()
        Playback.holdForProbe()
        runCatching { Prefs.engineManualRunning = ch.key }
        runningUrl = url; lastTried = emptyList()
        val app = c.applicationContext
        job = scope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            val tried = mutableListOf<EngineProfile>()
            var best: Pair<EngineProfile, ProbeResult>? = null
            try {
                for ((i, p) in list.withIndex()) {
                    if (!isActive) return@launch
                    if (i > 0 && android.os.SystemClock.uptimeMillis() - t0 > MANUAL_BUDGET_MS) break
                    post { onStatus?.invoke(Status.Testing(ch.name, i + 1, list.size, p.label)) }
                    tried += p; lastTried = tried.toList()
                    val r = runCatching { measure(app, ch, url, p) }.getOrDefault(ProbeResult(false))
                    if (r.started && (best == null || r.score > best!!.second.score)) best = p to r
                    if (r.smooth) break                                    // płynne – dalej nie trzeba szukać
                }
            } finally { runCatching { Prefs.engineManualRunning = "" } }
            if (!isActive) return@launch
            val found = best?.first
            when {
                found == null -> {}                                            // nic nie ruszyło – ustawienia bez zmian
                found == EngineProfile() -> ChannelEngines.forget(ch.key)      // domyślne są najlepsze – bez zapisanego profilu
                else -> ChannelEngines.save(ch.key, url, found)
            }
            post {
                if (runningUrl != url) return@post
                runningUrl = null
                onStatus?.invoke(if (found != null) Status.Found(ch.name, found.label) else Status.Failed(ch.name))
                Playback.restartAfterProbe(app, ch, url)
            }
        }
        return ManualStart.STARTED
    }

    /** Usunięcie dopasowanych ustawień kanału – znowu ustawienia domyślne (i możliwy ponowny dobór). */
    fun resetChannel(key: String, url: String) {
        ChannelEngines.forget(key)
        manualRuns.remove(key); triedThisSession.remove("$key|$url")
    }

    /**
     * Pomiar jednej próby: odtwarzacz z profilem na wątku testów, bez dźwięku, obraz do niewidocznej powierzchni
     * (ImageReader – klatki odbierane i od razu zwalniane, dekoder pracuje jak przy oglądaniu). Po pierwszej klatce
     * [MANUAL_PLAY_MS] odtwarzania: wyświetlone i zgubione klatki, przycięcia. Zawsze kończy się `release()`.
     */
    suspend fun measure(c: Context, ch: Channel, url: String, profile: EngineProfile): ProbeResult {
        measureOverride?.let { return it(profile) }
        val h = Handler(thread.looper)
        val first = CompletableDeferred<Long>()
        val calls = Playback.StreamCalls(Playback.streamHttp)
        var player: ExoPlayer? = null
        var reader: android.media.ImageReader? = null
        var dropped = 0; var rebuffers = 0; var ready = false
        val t0 = android.os.SystemClock.uptimeMillis()
        h.post {
            runCatching {
                val p = Playback.buildPlayer(c, profile, thread.looper)
                player = p
                p.volume = 0f
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    reader = android.media.ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.PRIVATE, 4).also { r ->
                        r.setOnImageAvailableListener({ rr -> runCatching { rr.acquireLatestImage()?.close() } }, h)
                        p.setVideoSurface(r.surface)
                    }
                }
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { first.complete(android.os.SystemClock.uptimeMillis() - t0) }
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == Player.STATE_READY) {
                            ready = true
                            if (p.currentTracks.groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } || reader == null)
                                first.complete(android.os.SystemClock.uptimeMillis() - t0)
                        } else if (s == Player.STATE_BUFFERING && ready) rebuffers++
                    }
                    override fun onPlayerError(error: PlaybackException) { first.complete(-1) }
                })
                p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, elapsedMs: Long) { dropped += count }
                })
                p.setMediaSource(Playback.mediaSource(c, ch, url, Playback.mimeFor(url, null, profile), calls))
                p.playWhenReady = true
                p.prepare()
            }.onFailure { first.complete(-1) }
        }
        try {
            val ff = withTimeoutOrNull(MANUAL_START_MS) { first.await() } ?: -1L
            if (ff < 0) return ProbeResult(false)
            kotlinx.coroutines.delay(MANUAL_PLAY_MS)
            val res = CompletableDeferred<ProbeResult>()
            h.post {
                val p = player
                res.complete(ProbeResult(true, ff, p?.videoDecoderCounters?.renderedOutputBufferCount ?: 0, dropped, rebuffers,
                    MANUAL_PLAY_MS, p?.videoFormat?.frameRate ?: -1f))
            }
            return withTimeoutOrNull(1_000) { res.await() } ?: ProbeResult(true, ff)
        } finally {
            val released = CompletableDeferred<Unit>()
            h.post {
                runCatching { player?.release() }; calls.close(); runCatching { reader?.close() }
                released.complete(Unit)
            }
            withContext(kotlinx.coroutines.NonCancellable) { withTimeoutOrNull(3_000) { released.await() } }
        }
    }

    /** Przerwanie doboru (inny kanał, zamknięcie odtwarzacza); bieżąca próba kończy się zwolnieniem odtwarzacza testowego. */
    fun cancel() { job?.cancel(); job = null; runningUrl = null }

    fun cancelUnless(url: String) { if (runningUrl != null && runningUrl != url) cancel() }

    /** Testy: nowa sesja (dobór możliwy ponownie). */
    fun resetSession() { cancel(); triedThisSession.clear(); manualRuns.clear(); lastTried = emptyList() }

    /**
     * Jedna próba: odtwarzacz z profilem na wątku testów, bez dźwięku, obraz do niewidocznej powierzchni. Sukces – pierwsza
     * wyświetlona klatka (albo gotowość strumienia bez obrazu, np. radio). Zawsze kończy się `release()` (także po przerwaniu).
     */
    suspend fun probe(c: Context, ch: Channel, url: String, profile: EngineProfile): Boolean {
        probeOverride?.let { return it(profile) }
        val h = Handler(thread.looper)
        val result = CompletableDeferred<Boolean>()
        val calls = Playback.StreamCalls(Playback.streamHttp)
        var player: ExoPlayer? = null
        var texture: android.graphics.SurfaceTexture? = null
        var surface: android.view.Surface? = null
        h.post {
            runCatching {
                val p = Playback.buildPlayer(c, profile, thread.looper)
                player = p
                p.volume = 0f
                texture = android.graphics.SurfaceTexture(0).also { t -> surface = android.view.Surface(t); p.setVideoSurface(surface) }
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { result.complete(true) }
                    override fun onPlaybackStateChanged(s: Int) {
                        // strumień bez obrazu (radio): gotowość odtwarzania wystarcza
                        if (s == Player.STATE_READY && p.currentTracks.groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }) result.complete(true)
                    }
                    override fun onPlayerError(error: PlaybackException) { result.complete(false) }
                })
                p.setMediaSource(Playback.mediaSource(c, ch, url, Playback.mimeFor(url, null, profile), calls))
                p.playWhenReady = false           // pierwsza klatka jest wyświetlana także w pauzie – bez dźwięku i bez zegara
                p.prepare()
            }.onFailure { result.complete(false) }
        }
        val ok = try { withTimeoutOrNull(PROBE_MS) { result.await() } ?: false } finally {
            // zwolnienie na wątku odtwarzacza i oczekiwanie na nie – następna próba dopiero po zwolnieniu dekoderów i połączeń
            val released = CompletableDeferred<Unit>()
            h.post {
                runCatching { player?.release() }; calls.close()
                runCatching { surface?.release() }; runCatching { texture?.release() }
                released.complete(Unit)
            }
            withContext(kotlinx.coroutines.NonCancellable) { withTimeoutOrNull(3_000) { released.await() } }
        }
        return ok
    }
}

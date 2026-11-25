package pl.iptv.tv.ui

import kotlinx.coroutines.launch

/**
 * (7.20) Wybór dekodera programowego „naprawdę”: w trybie zgodności lista dekoderów obrazu zawiera wyłącznie systemowe
 * dekodery programowe (c2.android.*, OMX.google.*, isSoftwareOnly). Dawniej lista była tylko sortowana – na części TV
 * (np. TCL z Google TV, SoC MediaTek) Media3 i tak trafiał na kodek sprzętowy producenta, który klatkuje strumienie 50i/50p.
 */
object SoftwareCodecs {
    /** Prefiksy kodeków sprzętowych producentów SoC (MediaTek, Amlogic, Realtek, Qualcomm, Broadcom, HiSilicon, Novatek…). */
    val VENDOR_PREFIXES = listOf(
        "omx.mtk.", "c2.mtk.", "omx.amlogic.", "c2.amlogic.", "omx.realtek.", "c2.realtek.", "omx.qcom.", "c2.qti.",
        "omx.brcm.", "c2.brcm.", "omx.hisi.", "c2.hisi.", "omx.nvt.", "c2.nvt.", "omx.exynos.", "c2.exynos.", "omx.sec.",
    )
    /** Prefiksy systemowych dekoderów programowych Androida. */
    val SYSTEM_SOFTWARE_PREFIXES = listOf("c2.android.", "omx.google.")

    fun isVendorHardware(name: String): Boolean = name.lowercase(java.util.Locale.ROOT).let { n -> VENDOR_PREFIXES.any { n.startsWith(it) } }

    /** Systemowy dekoder programowy: nie kodek producenta SoC, bez akceleracji sprzętowej, z nazwy albo [softwareOnly]. */
    fun isSystemSoftware(name: String, hardwareAccelerated: Boolean, softwareOnly: Boolean): Boolean {
        if (isVendorHardware(name) || hardwareAccelerated) return false
        val n = name.lowercase(java.util.Locale.ROOT)
        return softwareOnly || SYSTEM_SOFTWARE_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * Dekodery obrazu dla trybu programowego: tylko systemowe programowe (kolejność systemu zostaje). Gdy dla formatu nie ma
     * żadnego (rzadki kodek) – pełna lista z programowymi na początku, żeby obraz w ogóle ruszył.
     */
    fun <T> pick(all: List<T>, name: (T) -> String, hw: (T) -> Boolean, swOnly: (T) -> Boolean): List<T> {
        val sw = all.filter { isSystemSoftware(name(it), hw(it), swOnly(it)) }
        return sw.ifEmpty { all.sortedBy { if (swOnly(it) && !isVendorHardware(name(it))) 0 else 1 } }
    }
}

/**
 * (7.20) Powierzchnia obrazu Live TV ([pl.iptv.tv.data.Prefs.surfaceMode]): [AUTO] – wydajny SurfaceView, a TextureView dla
 * kanałów w trybie zgodności (profil kanału `surface = 1` – zapisany przez strażnika płynności albo dobór); [SURFACE_VIEW] /
 * [TEXTURE_VIEW] – zawsze wybrana.
 */
enum class RenderSurfaceMode(val label: String, val description: String) {
    AUTO("Automatycznie (zalecane)", "Zawsze sprzętowy SurfaceView – najdokładniejsze tempo klatek (TextureView tylko z ręcznego wyboru)"),
    SURFACE_VIEW("SurfaceView", "Najwydajniejsza powierzchnia – zawsze"),
    TEXTURE_VIEW("TextureView (zgodność)", "Zawsze TextureView – błędy usuwania przeplotu 1080i50 na części TV z Google TV");

    /** Czy obraz ma być na TextureView dla kanału z profilem wymagającym ([channelWantsTexture]) albo bez. */
    /** (7.22) AUTO – zawsze SurfaceView (TextureView przez kompozytor SurfaceFlinger dawał wrażenie spowolnienia ruchu); TextureView tylko z ręcznego wyboru. */
    fun texture(@Suppress("UNUSED_PARAMETER") channelWantsTexture: Boolean): Boolean = this == TEXTURE_VIEW

    companion object {
        val current: RenderSurfaceMode get() = entries.getOrElse(pl.iptv.tv.data.Prefs.surfaceMode) { AUTO }
    }
}

/**
 * (7.20) Wyrównywanie głośności kanałów: [android.media.audiofx.LoudnessEnhancer] na sesji dźwięku odtwarzacza Live TV
 * (łagodne podbicie [GAIN_MB] – ciche ścieżki AC-3 / E-AC-3 bliżej głośnych AAC). Część TV boxów nie obsługuje efektów
 * na sesji (np. tunelowanej) – każdy krok w try-catch, bez wpływu na odtwarzanie.
 */
object ChannelLoudness {
    const val GAIN_MB = 300

    /** Efekt na sesji (podmieniany w testach). */
    interface Effect { fun setGain(mB: Int); fun setEnabled(on: Boolean); fun release() }
    var factory: (Int) -> Effect = { session ->
        val e = android.media.audiofx.LoudnessEnhancer(session)
        object : Effect {
            override fun setGain(mB: Int) = e.setTargetGain(mB)
            override fun setEnabled(on: Boolean) { e.enabled = on }
            override fun release() = e.release()
        }
    }

    @Volatile private var effect: Effect? = null
    @Volatile var session: Int = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET; private set
    /** Czy efekt działa (testy, okno „Bieżący dekoder”). */
    val active: Boolean get() = effect != null
    /** Efekt tworzony / zakładany w tej chwili w tle (ta sama sesja nie zleca drugiego). */
    @Volatile private var pending = false

    /**
     * (7.22) Tworzenie efektu poza wątkiem UI (Dispatchers.IO): `LoudnessEnhancer` łączy się z usługą audio systemu i na
     * części urządzeń blokuje albo rzuca wyjątek – nie może zatrzymać ani zakończyć startu odtwarzania. W Robolectric
     * synchronicznie (testy).
     */
    @Volatile var async: Boolean = android.os.Build.FINGERPRINT != "robolectric"
    private val scope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO) }
    /** Pokolenie podpięcia – efekt utworzony w tle dla starszego podpięcia jest od razu zwalniany. */
    private var generation = 0

    /** Podpięcie do sesji [sessionId] wg ustawienia (wyłączone – zwolnienie); błąd urządzenia – bez efektu. */
    fun attach(sessionId: Int) {
        val wanted = runCatching { pl.iptv.tv.data.Prefs.loudnessLeveling }.getOrDefault(false)
        val gen: Int
        synchronized(this) {
            if (sessionId == session && (effect != null || pending) == wanted) return
            releaseLocked()
            session = sessionId
            if (!wanted || sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
            gen = generation
            pending = true
        }
        if (!async) { install(sessionId, gen); return }
        try { scope.launch { install(sessionId, gen) } } catch (t: Throwable) { synchronized(this) { if (gen == generation) pending = false } }
    }

    private fun install(sessionId: Int, gen: Int) {
        val e = try {
            factory(sessionId).also { it.setGain(GAIN_MB); it.setEnabled(true) }
        } catch (t: Throwable) {
            runCatching { android.util.Log.w("Playback", "wyrównywanie głośności niedostępne na sesji $sessionId: ${t.javaClass.simpleName}") }; null
        }
        val stale = synchronized(this) {
            if (gen == generation && session == sessionId) { effect = e; pending = false; false } else true
        }
        if (stale) e?.let { runCatching { it.setEnabled(false); it.release() } }
    }

    fun release() { synchronized(this) { releaseLocked() } }

    private fun releaseLocked() {
        generation++; pending = false
        val e = effect
        effect = null; session = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
        // zwolnienie efektu (wywołanie usługi audio) też poza wątkiem UI, gdy działa w tle
        e?.let { if (async) runCatching { scope.launch { runCatching { it.setEnabled(false); it.release() } } } else runCatching { it.setEnabled(false); it.release() } }
    }
}

/**
 * (7.20) Strażnik zrzucanych klatek: obraz klatkuje (dekoder zrzuca klatki), a dźwięk gra płynnie – typowe dla kodeków
 * sprzętowych części TV przy kanałach 50 kl/s / 1080i50 (np. Canal+ Extra 2 i 3 na TCL z Google TV), objaw po 2–3 s,
 * gdy skończy się początkowy zapas bufora.
 *
 * Działa w oknie startowym i przez całe oglądanie (okno [WINDOW_MS] przesuwa się; alarm raz na start kanału – po nim
 * kanał jest już w trybie zgodności). Drugi objaw – obraz stoi przy płynącym dźwięku – liczy strażnik w [Playback]
 * (licznik `renderedOutputBufferCount`, [frozenLongEnough]).
 *
 * Zdarzenia [onDropped] pochodzą z `AnalyticsListener.onDroppedVideoFrames` (renderer zgłasza co [Playback.DROP_NOTIFY_FRAMES]
 * klatek). Alarm, gdy: od początku odtwarzania (READY) minęło ponad [MIN_PLAY_MS], odtwarzacz jest w READY (dźwięk bez
 * buforowania), tempo zdarzenia > [MAX_DROP_FPS] kl/s i w oknie [WINDOW_MS] zrzucono ≥ [MIN_DROPPED] klatek. Jeden alarm na start kanału.
 */
class FrameDropWatchdog {
    companion object {
        /** (7.22) Karencja od początku odtwarzania (READY): zrzuty przy stabilizacji bufora i zegara są pomijane. */
        const val GRACE_MS = 4_500L
        /** Długość okna pomiaru zrzuconych klatek. */
        const val WINDOW_MS = 3_000L
        /** Ile kolejnych okien z rzędu musi przekroczyć próg (2 × 3 s = 6 s ciągłego problemu). */
        const val WINDOWS_IN_ROW = 2
        /** Próg: zrzucone klatki > tyle × klatkaż strumienia (40 % – np. > 20 kl/s przy 50 kl/s, > 10 kl/s przy 25 kl/s). */
        const val DROP_FRACTION = 0.4f
        /** Klatkaż przyjmowany, gdy strumień go nie podaje. */
        const val DEFAULT_FPS = 25f
        /** Zgodność wstecz (opisy, ustawienia). */
        const val MIN_PLAY_MS = GRACE_MS
        /**
         * Zamrożenie (licznik wyświetlonych klatek stoi, a czas odtwarzania – dźwięk – płynie) przez tyle ms przy READY i
         * isPlaying → tryb zgodności kanału (przed dawnym odświeżeniem powierzchni po 4 s i programowym na sesję po 8 s).
         */
        const val FREEZE_COMPAT_MS = 3_000L
        /** Czy obraz stoi wystarczająco długo, by przełączyć kanał na tryb zgodności. */
        fun frozenLongEnough(frozenMs: Long): Boolean = frozenMs >= FREEZE_COMPAT_MS
        /** Tempo zrzucania (kl/s) ze zdarzenia Media3; [elapsedMs] ≤ 0 → 0. */
        fun rate(droppedFrames: Int, elapsedMs: Long): Float = if (elapsedMs <= 0) 0f else droppedFrames / (elapsedMs / 1000f)
        /** Próg kl/s dla klatkażu [fps] (nieznany – [DEFAULT_FPS]). */
        fun threshold(fps: Float): Float = (if (fps > 0f) fps else DEFAULT_FPS) * DROP_FRACTION
    }

    private var readyAt = -1L
    private var ready = false
    private var fired = false
    /** Klatkaż strumienia (z formatu obrazu) – próg 40 %. */
    var fps = 0f
    /** Początek bieżącego okna i zrzucone w nim klatki; liczba kolejnych okien ponad progiem. */
    private var windowStart = -1L
    private var windowDrops = 0
    private var badInRow = 0
    private val badRates = ArrayList<Float>()
    /** Tempo zrzucania (kl/s) w oknach, które potwierdziły klatkowanie – stan sprzed zmiany silnika. */
    var baselineDropRate = 0f; private set

    /** Nowy kanał / nowy odtwarzacz – liczenie od zera. */
    fun reset() { readyAt = -1L; ready = false; fired = false; fps = 0f; resetWindows(); baselineDropRate = 0f }
    private fun resetWindows() { windowStart = -1L; windowDrops = 0; badInRow = 0; badRates.clear() }

    /** Stan odtwarzacza (Player.STATE_*). Pierwsze READY = początek odtwarzania. */
    fun onState(state: Int, now: Long) {
        ready = state == androidx.media3.common.Player.STATE_READY
        if (ready && readyAt < 0) readyAt = now
        if (!ready) resetWindows()             // buforowanie (łącze) – to nie klatkowanie dekodera
    }

    /** Zamknięcie okien, które minęły do [now] (puste okna – bez problemu, seria od nowa). */
    private fun closeWindows(now: Long) {
        while (windowStart >= 0 && now - windowStart >= WINDOW_MS) {
            val r = windowDrops / (WINDOW_MS / 1000f)
            if (r > threshold(fps)) { badInRow++; badRates += r } else { badInRow = 0; badRates.clear() }
            windowStart += WINDOW_MS; windowDrops = 0
        }
    }

    /**
     * Zrzucone klatki; true – klatkowanie przy płynnym dźwięku (raz na start): po karencji [GRACE_MS] w [WINDOWS_IN_ROW]
     * kolejnych oknach [WINDOW_MS] tempo zrzucania > 40 % klatkażu. Pojedyncze przycięcia i konwersja 50/60 Hz – nic.
     */
    fun onDropped(droppedFrames: Int, elapsedMs: Long, now: Long): Boolean {
        if (fired || !ready || readyAt < 0) return false
        if (now - readyAt < GRACE_MS) return false
        if (windowStart < 0) windowStart = readyAt + GRACE_MS
        closeWindows(now)
        windowDrops += droppedFrames
        if (badInRow >= WINDOWS_IN_ROW) {
            fired = true; baselineDropRate = badRates.takeLast(WINDOWS_IN_ROW).average().toFloat()
            return true
        }
        return false
    }

    /** Domknięcie okien bez nowych zgłoszeń (np. strażnik co 1 s) – true, gdy klatkowanie potwierdzone. */
    fun tick(now: Long): Boolean {
        if (fired || !ready || readyAt < 0 || windowStart < 0) return false
        closeWindows(now)
        if (badInRow >= WINDOWS_IN_ROW) { fired = true; baselineDropRate = badRates.takeLast(WINDOWS_IN_ROW).average().toFloat(); return true }
        return false
    }
}

package pl.iptv.tv.ui

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
    AUTO("Automatycznie (zalecane)", "SurfaceView, a TextureView dla kanałów, które klatkowały (tryb zgodności)"),
    SURFACE_VIEW("SurfaceView", "Najwydajniejsza powierzchnia – zawsze, także dla kanałów w trybie zgodności"),
    TEXTURE_VIEW("TextureView (zgodność)", "Zawsze TextureView – błędy usuwania przeplotu 1080i50 na części TV z Google TV");

    /** Czy obraz ma być na TextureView dla kanału z profilem wymagającym ([channelWantsTexture]) albo bez. */
    fun texture(channelWantsTexture: Boolean): Boolean = when (this) { AUTO -> channelWantsTexture; SURFACE_VIEW -> false; TEXTURE_VIEW -> true }

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

    private var effect: Effect? = null
    @Volatile var session: Int = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET; private set
    /** Czy efekt działa (testy, okno „Bieżący dekoder”). */
    val active: Boolean get() = effect != null

    /** Podpięcie do sesji [sessionId] wg ustawienia (wyłączone – zwolnienie); błąd urządzenia – bez efektu. */
    fun attach(sessionId: Int) {
        if (sessionId == session && (effect != null) == pl.iptv.tv.data.Prefs.loudnessLeveling) return
        release()
        session = sessionId
        if (!pl.iptv.tv.data.Prefs.loudnessLeveling || sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        effect = try {
            factory(sessionId).also { it.setGain(GAIN_MB); it.setEnabled(true) }
        } catch (t: Throwable) {
            android.util.Log.w("Playback", "wyrównywanie głośności niedostępne na sesji $sessionId: ${t.javaClass.simpleName}"); null
        }
    }

    fun release() {
        effect?.let { e -> try { e.setEnabled(false); e.release() } catch (_: Throwable) {} }
        effect = null; session = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
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
        const val MIN_PLAY_MS = 2_500L
        const val MAX_DROP_FPS = 8f
        const val MIN_DROPPED = 20
        const val WINDOW_MS = 3_000L
        /**
         * Zamrożenie (licznik wyświetlonych klatek stoi, a czas odtwarzania – dźwięk – płynie) przez tyle ms przy READY i
         * isPlaying → tryb zgodności kanału (przed dawnym odświeżeniem powierzchni po 4 s i programowym na sesję po 8 s).
         */
        const val FREEZE_COMPAT_MS = 3_000L
        /** Czy obraz stoi wystarczająco długo, by przełączyć kanał na tryb zgodności. */
        fun frozenLongEnough(frozenMs: Long): Boolean = frozenMs >= FREEZE_COMPAT_MS
        /** Tempo zrzucania (kl/s) ze zdarzenia Media3; [elapsedMs] ≤ 0 → 0. */
        fun rate(droppedFrames: Int, elapsedMs: Long): Float = if (elapsedMs <= 0) 0f else droppedFrames / (elapsedMs / 1000f)
    }

    private var readyAt = -1L
    private var ready = false
    private var fired = false
    private val window = ArrayDeque<Pair<Long, Int>>()

    /** Nowy kanał / nowy odtwarzacz – liczenie od zera. */
    fun reset() { readyAt = -1L; ready = false; fired = false; window.clear() }

    /** Stan odtwarzacza (Player.STATE_*). Pierwsze READY = początek odtwarzania. */
    fun onState(state: Int, now: Long) {
        ready = state == androidx.media3.common.Player.STATE_READY
        if (ready && readyAt < 0) readyAt = now
        if (!ready) window.clear()             // buforowanie (łącze) – to nie klatkowanie dekodera
    }

    /** Zrzucone klatki; true – klatkowanie przy płynnym dźwięku (raz na start). */
    fun onDropped(droppedFrames: Int, elapsedMs: Long, now: Long): Boolean {
        if (fired || !ready || readyAt < 0) return false
        window.addLast(now to droppedFrames)
        while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) window.removeFirst()
        if (now - readyAt <= MIN_PLAY_MS) return false
        val inWindow = window.sumOf { it.second }
        if (rate(droppedFrames, elapsedMs) > MAX_DROP_FPS && inWindow >= MIN_DROPPED) { fired = true; return true }
        return false
    }
}

package pl.iptv.tv.ui

import android.app.Activity
import android.os.Build
import android.view.Surface
import android.view.SurfaceView

/**
 * (7.17) Dopasowanie odświeżania ekranu do klatek kanału (AFR, opcja [pl.iptv.tv.data.Prefs.autoFrameRate], domyślnie wył.):
 * po odczytaniu `Format.frameRate` strumienia wybierany jest tryb ekranu w tej samej rozdzielczości, którego odświeżanie jest
 * całkowitą wielokrotnością klatek (25 → 50 Hz, 23,976 → 23,976 / 47,95 Hz, 29,97 → 59,94 Hz) – `preferredDisplayModeId`
 * (API 23+), na API 30+ dodatkowo `Surface.setFrameRate`. Przy wyjściu z odtwarzacza – tryb domyślny ([reset]).
 */
object AutoFrameRate {
    data class Mode(val id: Int, val width: Int, val height: Int, val refresh: Float)

    /** Najlepszy tryb dla [fps]: ta sama rozdzielczość co [current], odświeżanie = k × fps (±0,1 %), najniższe k; null – brak. */
    /** (7.22) Klatkaż europejskiej telewizji (25 / 50 kl/s) – tryb 50 Hz (49,9–50,1) tej samej rozdzielczości. */
    fun is50Family(fps: Float) = kotlin.math.abs(fps - 25f) < 0.6f || kotlin.math.abs(fps - 50f) < 1.2f
    /** Live TV bez podanego klatkażu – przyjmowane 50 Hz. */
    const val LIVE_DEFAULT_FPS = 50f

    fun pick(modes: List<Mode>, current: Mode, fps: Float): Mode? {
        if (is50Family(fps)) modes.filter { it.width == current.width && it.height == current.height && it.refresh in 49.9f..50.1f }
            .minByOrNull { kotlin.math.abs(it.refresh - 50f) }?.let { return it }
        if (fps < 10f || fps > 120f) return null
        return modes.filter { it.width == current.width && it.height == current.height }
            .mapNotNull { m -> val k = Math.round(m.refresh / fps); if (k >= 1 && kotlin.math.abs(m.refresh - k * fps) <= m.refresh * 0.001f) m to k else null }
            .minWithOrNull(compareBy({ it.second }, { -it.first.refresh }))?.first
    }

    /** Ostatnio ustawione klatki (bez ponownego przełączania przy tym samym kanale). */
    private var appliedFps = 0f
    /**
     * (7.27) Tryb ekranu wybrany przez AFR w tej sesji aplikacji – trzymany także w oknach Ustawień, pulpitu i list ([keep]),
     * żeby przejście między ekranami nie wymuszało powrotu do trybu domyślnego (ponowna negocjacja HDMI, czarny ekran).
     */
    var sessionModeId = 0; private set
    /** Liczba zmian trybu ekranu zleconych przez aplikację (testy). */
    var modeChanges = 0; private set

    /**
     * Dopasowanie trybu ekranu – **wyłącznie** przy włączonej opcji AFR (Ustawienia → Odtwarzanie, domyślnie wył.).
     * (7.27) [force] nie omija już wyłączonej opcji: wymuszane od 7.22 „50 Hz na sesję” po klatkowaniu kanału HD przełączało
     * tryb HDMI przy zmianie kanału, a powrót do trybu domyślnego w onStop odtwarzacza – przy wejściu w Ustawienia / Pulpit.
     */
    @Suppress("UNUSED_PARAMETER")
    fun apply(a: Activity, fps: Float, surface: SurfaceView?, force: Boolean = false) {
        if (!pl.iptv.tv.data.Prefs.autoFrameRate || fps <= 0f || kotlin.math.abs(fps - appliedFps) < 0.01f) return
        // (7.22) Display.Mode / preferredDisplayModeId od API 23 (minSdk 21) – starsze urządzenia bez AFR; każde wywołanie
        // systemowe w try-catch (telefon, ekran bez trybów, okno już zamknięte) – nigdy wyjątek w odtwarzaczu
        if (Build.VERSION.SDK_INT < 23) return
        try { applyModes(a, fps, surface) } catch (t: Throwable) {
            runCatching { android.util.Log.w("AutoFrameRate", "AFR pominięte: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun applyModes(a: Activity, fps: Float, surface: SurfaceView?) {
        val display = (if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay) ?: return
        val cur = display.mode?.let { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) } ?: return
        val target = pick(display.supportedModes.orEmpty().map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }, cur, fps) ?: return
        appliedFps = fps
        val w = a.window ?: return
        sessionModeId = target.id
        if (target.id != cur.id || w.attributes.preferredDisplayModeId != target.id) {
            w.attributes = w.attributes.also { it.preferredDisplayModeId = target.id }; modeChanges++
        }
        // (7.22) 25 / 50 kl/s – powierzchnia zgłasza 50 Hz; API 31+ – zmiana zawsze (CHANGE_FRAME_RATE_ALWAYS, także gdy niebezszwowa),
        // żeby TV (np. TCL) fizycznie przeszedł w 50 Hz zamiast pulldownu 50 → 60 Hz
        val rate = if (is50Family(fps)) target.refresh else fps
        if (Build.VERSION.SDK_INT >= 30) surface?.holder?.surface?.takeIf { it.isValid }?.let { s ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                else s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    /**
     * (7.27) Każde okno aplikacji (wznowienie ekranu): przy włączonym AFR – ten sam tryb co w odtwarzaczu (Ustawienia, pulpit,
     * lista kanałów nie wracają do trybu domyślnego); przy wyłączonym AFR – okno nie żąda żadnego trybu (zostaje tryb systemu).
     * Zapis atrybutów okna tylko przy faktycznej różnicy – bez zbędnej przebudowy okna.
     */
    fun keep(a: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val w = a.window ?: return
            val want = if (pl.iptv.tv.data.Prefs.autoFrameRate) sessionModeId else 0
            if (want == 0 && !pl.iptv.tv.data.Prefs.autoFrameRate) { sessionModeId = 0; appliedFps = 0f }
            if (w.attributes.preferredDisplayModeId != want && (want != 0 || w.attributes.preferredDisplayModeId != 0))
                w.attributes = w.attributes.also { it.preferredDisplayModeId = want }
        } catch (t: Throwable) { runCatching { android.util.Log.w("AutoFrameRate", "keep: ${t.javaClass.simpleName}") } }
    }

    /**
     * (7.27) Dawny powrót do trybu domyślnego przy wyjściu z odtwarzacza – teraz bez zmiany trybu ekranu (przejście do
     * Ustawień / pulpitu nie wywołuje ponownej negocjacji HDMI). Tryb domyślny wraca tylko po wyłączeniu AFR ([keep]).
     */
    fun reset(a: Activity) { keep(a) }
}

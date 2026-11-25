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

    /** [force] – (7.22) dopasowanie na bieżącą sesję po klatkowaniu kanału 25/50 kl/s, także przy wyłączonym AFR. */
    fun apply(a: Activity, fps: Float, surface: SurfaceView?, force: Boolean = false) {
        if (!(pl.iptv.tv.data.Prefs.autoFrameRate || force) || fps <= 0f || kotlin.math.abs(fps - appliedFps) < 0.01f) return
        val display = (if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay) ?: return
        val cur = display.mode.let { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = pick(display.supportedModes.map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }, cur, fps) ?: return
        appliedFps = fps
        if (target.id != cur.id || a.window.attributes.preferredDisplayModeId != target.id)
            a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = target.id }
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

    /** Powrót do domyślnego trybu ekranu (wyjście z odtwarzacza, wyłączona opcja). */
    fun reset(a: Activity) {
        appliedFps = 0f
        if (a.window.attributes.preferredDisplayModeId != 0) a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = 0 }
    }
}

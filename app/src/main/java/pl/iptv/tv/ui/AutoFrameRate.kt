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
    fun pick(modes: List<Mode>, current: Mode, fps: Float): Mode? {
        if (fps < 10f || fps > 120f) return null
        return modes.filter { it.width == current.width && it.height == current.height }
            .mapNotNull { m -> val k = Math.round(m.refresh / fps); if (k >= 1 && kotlin.math.abs(m.refresh - k * fps) <= m.refresh * 0.001f) m to k else null }
            .minWithOrNull(compareBy({ it.second }, { -it.first.refresh }))?.first
    }

    /** Ostatnio ustawione klatki (bez ponownego przełączania przy tym samym kanale). */
    private var appliedFps = 0f

    fun apply(a: Activity, fps: Float, surface: SurfaceView?) {
        if (!pl.iptv.tv.data.Prefs.autoFrameRate || fps <= 0f || kotlin.math.abs(fps - appliedFps) < 0.01f) return
        val display = (if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay) ?: return
        val cur = display.mode.let { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = pick(display.supportedModes.map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }, cur, fps) ?: return
        appliedFps = fps
        if (target.id != cur.id || a.window.attributes.preferredDisplayModeId != target.id)
            a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = target.id }
        if (Build.VERSION.SDK_INT >= 30) surface?.holder?.surface?.takeIf { it.isValid }?.let { s ->
            runCatching { s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
    }

    /** Powrót do domyślnego trybu ekranu (wyjście z odtwarzacza, wyłączona opcja). */
    fun reset(a: Activity) {
        appliedFps = 0f
        if (a.window.attributes.preferredDisplayModeId != 0) a.window.attributes = a.window.attributes.also { it.preferredDisplayModeId = 0 }
    }
}

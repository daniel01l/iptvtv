package pl.iptv.tv.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import pl.iptv.tv.data.Prefs

/**
 * (7.23) Korekta obrazu odtwarzacza Stremio: profil kolorów ([Profile] – Standardowy, Kino, Sport, Żywy) oraz jasność, kontrast
 * i nasycenie użytkownika. Wynik to jedna macierz kolorów ([matrix]) nakładana na warstwę TextureView obrazu ([apply] →
 * `TextureView.setLayerPaint`) – działa na żywo, bez przebudowy odtwarzacza, bez ponownego buforowania i bez przerwy w dźwięku.
 * Wartości domyślne ([DEFAULT]) = macierz jednostkowa (warstwa bez filtra, zero kosztu). Zapis w [Prefs] (wspólny dla tytułów).
 */
object PictureAdjust {
    /** Profil: nasycenie, kontrast (mnożniki), jasność (przesunięcie −1…1 zakresu) i ciepło barw (+ cieplej / − chłodniej). */
    enum class Profile(val label: String, val saturation: Float, val contrast: Float, val brightness: Float, val warmth: Float) {
        STANDARD("Standardowy", 1f, 1f, 0f, 0f),
        CINEMA("Kino", 0.92f, 1.06f, -0.03f, 0.05f),
        SPORT("Sport", 1.18f, 1.10f, 0.03f, 0f),
        VIVID("Żywy", 1.38f, 1.16f, 0.02f, -0.02f),
    }

    const val BRIGHTNESS_MIN = -50; const val BRIGHTNESS_MAX = 50
    const val CONTRAST_MIN = 50; const val CONTRAST_MAX = 150
    const val SATURATION_MIN = 0; const val SATURATION_MAX = 200

    /** Ustawienia: [brightness] −50…50 (0 – bez zmian), [contrast] i [saturation] w % (100 – bez zmian). */
    data class Values(val profile: Profile = Profile.STANDARD, val brightness: Int = 0, val contrast: Int = 100, val saturation: Int = 100) {
        val isDefault get() = this == DEFAULT
    }
    val DEFAULT = Values()

    /** Zapisane ustawienia (poza zakresem – przycięte). */
    fun current(): Values = runCatching {
        Values(Profile.entries.getOrElse(Prefs.stremioPicProfile) { Profile.STANDARD },
            Prefs.stremioBrightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX), Prefs.stremioContrast.coerceIn(CONTRAST_MIN, CONTRAST_MAX),
            Prefs.stremioSaturation.coerceIn(SATURATION_MIN, SATURATION_MAX))
    }.getOrDefault(DEFAULT)

    fun save(v: Values) {
        Prefs.stremioPicProfile = v.profile.ordinal; Prefs.stremioBrightness = v.brightness
        Prefs.stremioContrast = v.contrast; Prefs.stremioSaturation = v.saturation
    }

    /** Macierz: nasycenie → kontrast (wokół środka szarości) → jasność i ciepło barw. */
    fun matrix(v: Values): ColorMatrix {
        val p = v.profile
        val sat = p.saturation * v.saturation / 100f
        val con = p.contrast * v.contrast / 100f
        val bri = (p.brightness + v.brightness / 100f * 0.5f) * 255f
        val warm = p.warmth * 255f
        val m = ColorMatrix().apply { setSaturation(sat) }
        val t = 128f * (1f - con)
        m.postConcat(ColorMatrix(floatArrayOf(
            con, 0f, 0f, 0f, t,
            0f, con, 0f, 0f, t,
            0f, 0f, con, 0f, t,
            0f, 0f, 0f, 1f, 0f)))
        m.postConcat(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, bri + warm,
            0f, 1f, 0f, 0f, bri,
            0f, 0f, 1f, 0f, bri - warm,
            0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** Ostatnio nałożone ustawienia (testy). */
    var applied: Values? = null; private set

    /**
     * Nakłada [v] na powierzchnię obrazu [surface] (TextureView – na żywo; inna powierzchnia – false, bez zmian). Domyślne
     * ustawienia zdejmują filtr.
     */
    fun apply(surface: View?, v: Values): Boolean {
        val tv = surface as? TextureView ?: return false
        tv.setLayerPaint(if (v.isDefault) null else Paint().apply { colorFilter = ColorMatrixColorFilter(matrix(v)) })
        applied = v
        return true
    }
}

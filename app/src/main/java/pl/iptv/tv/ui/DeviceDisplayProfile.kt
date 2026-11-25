package pl.iptv.tv.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * (7.22) Jedno, bezpieczne źródło prawdy o rodzaju ekranu – wspólne dla trybu TV, poziomego i pionowego (9:16) telefonu.
 *
 * - [isTvDevice] – Android TV / Google TV ([detectTv]: tryb interfejsu „telewizja”, `UiModeManager` albo Leanback /
 *   `FEATURE_TELEVISION` bez ekranu dotykowego); każde wywołanie systemowe w `try-catch` – na telefonie nigdy nie rzuca.
 * - [isPortraitPhone] – telefon / tablet trzymany pionowo: interfejs zawsze w natywnej gęstości systemu (nigdy siatka
 *   960 dp z [TvResolution] – na wąskim ekranie telefonu wszystko byłoby mikroskopijne).
 * - [isLandscapeOrTv] – TV albo telefon poziomo (układy szerokie).
 *
 * Widoki: [of] (kontekst aktywności). Compose: [LocalDeviceDisplayProfile] (ustawiany przez [TvResolution1080pProvider]).
 */
@Immutable
data class DeviceDisplayProfile(val isTvDevice: Boolean, val orientation: Int) {
    val isPortraitPhone: Boolean get() = !isTvDevice && orientation == Configuration.ORIENTATION_PORTRAIT
    val isLandscapeOrTv: Boolean get() = isTvDevice || orientation == Configuration.ORIENTATION_LANDSCAPE

    companion object {
        /** Domyślny profil (poza drzewem z dostawcą): telefon poziomo – bez siatki TV, bez założeń o pionie. */
        val DEFAULT = DeviceDisplayProfile(false, Configuration.ORIENTATION_LANDSCAPE)

        /** Testy: wymuszona orientacja (null – z konfiguracji kontekstu). */
        @Volatile var forceOrientation: Int? = null

        /** Profil ekranu dla [c]; nigdy nie rzuca (błąd – [DEFAULT] z orientacją, jeśli dała się odczytać). */
        fun of(c: Context): DeviceDisplayProfile {
            val o = forceOrientation ?: runCatching { c.resources.configuration.orientation }.getOrDefault(Configuration.ORIENTATION_UNDEFINED)
            return DeviceDisplayProfile(detectTv(c), o)
        }

        /** Profil z gotowej konfiguracji (Compose – `LocalConfiguration`). */
        fun of(c: Context, cfg: Configuration): DeviceDisplayProfile =
            DeviceDisplayProfile(detectTv(c), forceOrientation ?: cfg.orientation)

        /**
         * Android TV / Google TV. Kolejno (każdy krok w `try-catch`): konfiguracja `uiMode` = TELEVISION, `UiModeManager.currentModeType`
         * = TELEVISION, cecha `FEATURE_LEANBACK` / `FEATURE_TELEVISION` bez ekranu dotykowego (część telefonów deklaruje Leanback –
         * ekran dotykowy wyklucza TV). Wymuszenie w testach: [TvResolution.forceTv].
         */
        fun detectTv(c: Context): Boolean {
            TvResolution.forceTv?.let { return it }
            try {
                if ((c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) return true
            } catch (_: Throwable) {}
            try {
                val mode = (c.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType
                if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true
            } catch (_: Throwable) {}
            return try {
                val pm = c.packageManager
                @Suppress("DEPRECATION")
                val tvFeature = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)
                tvFeature && !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
            } catch (_: Throwable) { false }
        }
    }
}

/** (7.22) Profil ekranu w Compose (ustawia [TvResolution1080pProvider] na szczycie każdego drzewa). */
val LocalDeviceDisplayProfile = staticCompositionLocalOf { DeviceDisplayProfile.DEFAULT }

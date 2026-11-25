package pl.iptv.tv.ui

/**
 * (7.22) Rozdzielczość interfejsu (Ustawienia → Wygląd → „Rozdzielczość interfejsu”, [pl.iptv.tv.data.Prefs.uiResolution]):
 * siatka projektowa w dp, w której rysowany jest cały interfejs ([TvResolution]). Obraz wideo nie jest skalowany –
 * dotyczy tylko menu, okien, kafelków i OSD.
 *
 * - [AUTO] (domyślna, identyczna z dotychczasowym działaniem): Android TV – siatka 1920 × 1080 (960 × 540 dp), telefon /
 *   tablet – gęstość systemu.
 * - [FHD_1080P] / [HD_720P] – siatka 960 × 540 dp / 640 × 360 dp (720p – te same proporcje, elementy ok. 1,5 × większe)
 *   na TV i na telefonie trzymanym poziomo.
 * - [NATIVE] – zawsze gęstość systemu (także na TV).
 *
 * Zasada ochronna: telefon / tablet w pionie ([DeviceDisplayProfile.isPortraitPhone]) zawsze w gęstości systemu,
 * niezależnie od wyboru (siatka pozioma na wąskim ekranie byłaby nieczytelna).
 */
enum class UiResolutionMode(val label: String, val description: String, private val gridW: Float?, private val gridH: Float?) {
    AUTO("Automatyczna (Zalecana)", "Telewizor – układ 1920×1080p, telefon i tablet – gęstość systemu", null, null),
    FHD_1080P("1920×1080p (Full HD)", "Stały układ Full HD – jak na telewizorze, także na telefonie poziomo", TvResolution.DESIGN_W_DP, TvResolution.DESIGN_H_DP),
    HD_720P("1280×720p (HD – większe elementy)", "Większe napisy i kafelki – czytelniej z daleka", 640f, 360f),
    NATIVE("Natywna urządzenia (Systemowa)", "Gęstość zgłaszana przez system urządzenia – bez skalowania", null, null);

    /** Siatka w dp (szerokość × wysokość) dla urządzenia ([isTv]); null – gęstość systemu. */
    fun grid(isTv: Boolean): Pair<Float, Float>? = when (this) {
        AUTO -> if (isTv) TvResolution.DESIGN_W_DP to TvResolution.DESIGN_H_DP else null
        NATIVE -> null
        else -> gridW!! to gridH!!
    }

    companion object {
        /** Testy: wymuszony tryb (null – z Prefs). */
        @Volatile var forced: UiResolutionMode? = null

        /** Bieżący wybór; Prefs przed `init` (np. `Application.attachBaseContext`) – [AUTO]. */
        fun current(): UiResolutionMode = forced ?: runCatching { entries.getOrElse(pl.iptv.tv.data.Prefs.uiResolution) { AUTO } }.getOrDefault(AUTO)
    }
}

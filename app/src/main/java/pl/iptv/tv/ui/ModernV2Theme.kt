package pl.iptv.tv.ui

import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.theme.ObsidianGlass

/**
 * Motyw **Nowoczesny v2** – „Luksusowy, ciemny styl Obsidian Glass z gradientami” (tokeny: [ObsidianGlass]).
 *
 * Drugi preset obok „Nowoczesny” w [Ui.PRESETS] z tą samą fakturą [Ui.S_MODERN] – wszystkie funkcje motywu Nowoczesny
 * (ekran główny START [ModernHomeActivity], panel nakładkowy menu, „Obsidian Stream” na ekranach z widokami, OSD, okna kart)
 * działają bez zmian ([ModernTheme.active] = true dla obu), a v2 podmienia tylko oprawę: tło [pl.iptv.tv.ui.theme.GlassBackdrop],
 * szklane panele / okna z kryciem wg [opacity], niebieską ramkę i poświatę fokusu, pigułki kroków kreatora, kartę kodu
 * na ekranie aktywacji, pływający dolny pasek na telefonie w pionie i modal platform Top 10 z „ZAPISZ” / „ANULUJ”.
 *
 * Ustawienia v2 (`Prefs.mv2Animations`, `Prefs.mv2Opacity`) są poza [Ui.PRESET_KEYS] – zmiana motywu i aktualizacja aplikacji
 * ich nie zerują; zapisany motyw użytkownika (`Prefs.theme`) też zostaje ([Ui.migrateRemovedThemes] pomija istniejące presety).
 */
object ModernV2 {
    const val ID = "modern_v2"
    const val NAME = "Nowoczesny v2"

    /** Motyw v2 wybrany (bezpiecznie przed `Prefs.init` – false). */
    val active: Boolean get() = runCatching { Prefs.theme == ID }.getOrDefault(false)

    /** Oba warianty Nowoczesnego (ekran główny, ustawienia ekranu startowego). */
    fun isModern(id: String) = id == ModernTheme.ID || id == ID

    /** Animacje przejść i fokusu: v2 z włączonymi animacjami i poza trybem słabszych urządzeń. */
    val animations: Boolean get() = runCatching { Prefs.mv2Animations && !pl.iptv.tv.ui.DevicePerformanceProfile.uiLow }.getOrDefault(true)

    /** v2 z wyłączonymi animacjami – przejścia ekranów / okien i animacje Compose natychmiastowe. */
    val animationsOff: Boolean get() = active && !runCatching { Prefs.mv2Animations }.getOrDefault(true)

    val opacity: ObsidianGlass.Opacity get() = ObsidianGlass.Opacity.current()

    val ANIMATION_OPTIONS = listOf("Włączone", "Wyłączone")
    val OPACITY_OPTIONS = ObsidianGlass.Opacity.entries.map { it.label }
    const val ANIMATIONS_TITLE = "Animacje przejść"
    const val OPACITY_TITLE = "Przezroczystość UI"
    const val SECTION_TITLE = NAME
    const val NOTE = "Nowoczesny v2: luksusowy, ciemny styl „Obsidian Glass” – granatowo-czarne tło z niebiesko-fioletową poświatą, " +
        "półprzezroczyste karty i okna z delikatną krawędzią, wyraźna niebieska ramka i poświata zaznaczenia. Ekran główny, menu i wszystkie " +
        "funkcje jak w motywie Nowoczesny."
    const val TOP10_SAVE = "ZAPISZ"
    const val TOP10_CANCEL = "ANULUJ"

    /** Włącza motyw v2 (Ustawienia, kreator). */
    fun enable() {
        if (active) return
        Ui.applyPreset(ID); Ui.changed()
    }
}

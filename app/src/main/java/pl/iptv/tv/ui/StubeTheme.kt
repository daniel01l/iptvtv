package pl.iptv.tv.ui

import android.graphics.Color
import pl.iptv.tv.data.Prefs

/**
 * Motywy STube: własne kolory ekranu STube i odtwarzacza filmów (niezależne od motywu telewizji) oraz układ kafelków.
 * Kolory działają przez [Ui.scoped] – cały mechanizm stylu (fokus, ramki, panele, okna) używa ich bez zmian w kodzie ekranów.
 * „Jak aplikacja” – bez nadpisania. Ekrany włączają motyw w onCreate/onResume ([enter]) i wyłączają w onPause ([exit]).
 */
object StubeTheme {
    data class Palette(val name: String, val bg: Int, val panel: Int, val accent: Int, val focus: Int, val focusText: Int, val text: Int, val text2: Int)
    data class Layout(val name: String, val tileW: Int, val thumbH: Int, val titleLines: Int, val showSub: Boolean, val gap: Int)

    private fun c(s: String) = Color.parseColor(s)

    /** Indeks 0 = motyw aplikacji (bez palety). */
    val THEMES: List<Palette?> = listOf(
        null,
        Palette("YouTube – ciemny", c("#0F0F0F"), c("#212121"), c("#FF0033"), c("#F1F1F1"), c("#0F0F0F"), c("#F1F1F1"), c("#AAAAAA")),
        Palette("YouTube – jasny", c("#F9F9F9"), c("#FFFFFF"), c("#CC0000"), c("#0F0F0F"), c("#FFFFFF"), c("#0F0F0F"), c("#606060")),
        Palette("Kino", c("#000000"), c("#141414"), c("#F5C518"), c("#F5C518"), c("#000000"), c("#EDEDED"), c("#9A9A9A")),
        Palette("Ocean", c("#06141F"), c("#0E2A3B"), c("#29B6F6"), c("#29B6F6"), c("#001018"), c("#E6F4FA"), c("#8FB3C4")),
        Palette("Neon", c("#0B0716"), c("#1A1030"), c("#FF3CAC"), c("#7B5CFF"), c("#FFFFFF"), c("#F3EEFF"), c("#A99BC9")),
        Palette("Las", c("#0C1410"), c("#16241C"), c("#5BD68A"), c("#5BD68A"), c("#06140C"), c("#E8F5EC"), c("#93B39F")),
    )
    val THEME_NAMES get() = listOf("Jak aplikacja") + THEMES.drop(1).map { it!!.name }

    val LAYOUTS = listOf(
        Layout("Standardowy", 236, 126, 2, true, 14),
        Layout("Kompaktowy (więcej filmów)", 196, 104, 1, true, 10),
        Layout("Duże kafelki", 300, 160, 2, true, 18),
        Layout("Minimalny (miniatury i tytuły)", 236, 126, 1, false, 14),
    )

    /** Paleta STube (null – kolory motywu aplikacji). (7.27) Dawne wyjątki X25 / PS5 usunięte razem z motywami. */
    val palette: Palette? get() = THEMES.getOrNull(Prefs.stubeTheme)
    val layout: Layout get() = LAYOUTS.getOrElse(Prefs.stubeLayout) { LAYOUTS[0] }

    /** Kolory palety w kluczach [Ui.color]. */
    fun colors(p: Palette): Map<String, Int> = mapOf("bg" to p.bg, "panel" to p.panel, "accent" to p.accent, "focus" to p.focus,
        "focusText" to p.focusText, "text" to p.text, "text2" to p.text2)

    /** Klucz wyglądu (motyw + układ) – ekran przebudowuje się, gdy zmieniono go w Ustawieniach. */
    val key get() = "${Prefs.stubeTheme}|${Prefs.stubeLayout}|${Ui.themeEpoch}"

    fun enter() { Ui.scoped = palette?.let(::colors) }
    fun exit() { Ui.scoped = null }
}

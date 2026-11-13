package pl.iptv.tv.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs

enum class FocusStyle { FILL, GRADIENT, OUTLINE }

data class ThemePreset(
    val id: String, val name: String,
    val bg: Int, val panel: Int, val accent: Int, val focus: Int, val focusText: Int,
    val text: Int, val text2: Int,
    val radius: Int, val font: Int, val focusStyle: FocusStyle,
    val anim: Int, val progress: Int, val infoPos: Int, val zoom: Boolean,
)

/** Czcionka systemowa ([family]) albo dołączona do aplikacji ([res]) – te działają na każdym urządzeniu. */
data class FontOption(val label: String, val family: String, val bold: Boolean = false, val res: Int = 0)

/**
 * Centralny styl aplikacji: motyw (preset) + nadpisania użytkownika z [Prefs].
 * Widoki oznaczane są w layoutach tagami: panel, item, field, text2, accent.
 */
object Ui {
    private fun c(s: String) = Color.parseColor(s)

    val PRESETS = listOf(
        ThemePreset("modern", "Nowoczesny", c("#0F1218"), c("#171C25"), c("#2F80ED"), c("#2F80ED"), c("#FFFFFF"),
            c("#EFEFEF"), c("#9AA3B2"), 10, 0, FocusStyle.FILL, 1, 2, 0, true),
        ThemePreset("classic", "Klasyczny dekoder", c("#06134A"), c("#0D2470"), c("#FFC400"), c("#FFB300"), c("#000000"),
            c("#FFFFFF"), c("#B8C7F0"), 0, 2, FocusStyle.GRADIENT, 0, 3, 0, false),
        ThemePreset("amoled", "AMOLED czerń", c("#000000"), c("#0D0D0D"), c("#00BFA5"), c("#00BFA5"), c("#000000"),
            c("#FFFFFF"), c("#8A8A8A"), 6, 3, FocusStyle.FILL, 1, 0, 0, false),
        ThemePreset("light", "Jasny", c("#E9EDF2"), c("#FFFFFF"), c("#1565C0"), c("#1565C0"), c("#FFFFFF"),
            c("#1A1A1A"), c("#5F6B7A"), 8, 0, FocusStyle.FILL, 2, 2, 0, true),
        ThemePreset("neon", "Neon retro", c("#12051F"), c("#1E0A33"), c("#FF2BD6"), c("#FF2BD6"), c("#FFFFFF"),
            c("#F5E6FF"), c("#B48CD9"), 4, 12, FocusStyle.OUTLINE, 3, 3, 1, false),
        ThemePreset("cinema", "Kino", c("#141010"), c("#1F1717"), c("#E50914"), c("#E50914"), c("#FFFFFF"),
            c("#FFFFFF"), c("#A89A9A"), 4, 8, FocusStyle.FILL, 3, 1, 0, true),
        ThemePreset("forest", "Leśny", c("#0E1A12"), c("#15261B"), c("#66BB6A"), c("#2E7D32"), c("#FFFFFF"),
            c("#EAF5EA"), c("#9DB8A0"), 14, 10, FocusStyle.FILL, 2, 2, 0, true),
    )

    val FONTS = listOf(
        FontOption("Systemowa", "sans-serif"),
        FontOption("Systemowa pogrubiona", "sans-serif", true),
        FontOption("Wąska (condensed)", "sans-serif-condensed"),
        FontOption("Cienka (light)", "sans-serif-light"),
        FontOption("Średnia (medium)", "sans-serif-medium"),
        FontOption("Szeryfowa", "serif"),
        FontOption("Maszynowa (mono)", "monospace"),
        FontOption("Odręczna (systemowa)", "casual"),
        FontOption("Lato", "", res = R.font.lato),
        FontOption("Ubuntu", "", res = R.font.ubuntu),
        FontOption("PT Serif (szeryfowa)", "", res = R.font.pt_serif),
        FontOption("Bebas Neue (nagłówkowa)", "", res = R.font.bebas_neue),
        FontOption("VT323 (dekoder retro)", "", res = R.font.vt323),
        FontOption("Press Start (pikselowa)", "", res = R.font.press_start),
        FontOption("Lobster (ozdobna)", "", res = R.font.lobster),
        FontOption("Pacifico (odręczna)", "", res = R.font.pacifico),
    )

    private lateinit var app: android.content.Context
    fun init(c: android.content.Context) { app = c.applicationContext }
    private val fontCache = HashMap<Int, Typeface?>()
    val FONT_SCALES = listOf(0.85f to "Mała", 1f to "Normalna", 1.15f to "Duża", 1.3f to "Bardzo duża")
    val ANIMS = listOf("Brak", "Zanikanie", "Przesunięcie", "Powiększenie")
    val ANIM_SPEEDS = listOf(150L to "Szybko", 250L to "Normalnie", 450L to "Wolno")
    val PROGRESS = listOf("Cienka linia", "Gruby pasek", "Zaokrąglony", "Segmenty (klasyczny)", "Ukryty")
    val INFO_POS = listOf("Na dole", "Na górze")
    val INFO_TIMEOUTS = listOf(3, 5, 8, 12)
    val INFO_STYLES = listOf("Gradient (nowoczesny)", "Pływający panel", "Szkło (przezroczyste)", "Klasyczny dekoder", "Minimalny (sam tekst)")
    val INFO_ALPHAS = listOf(100, 85, 70, 55, 40)
    val INFO_SIZES = listOf(0.85f to "Mały", 1f to "Normalny", 1.2f to "Duży")
    val ARCHIVE_TIMEOUTS = listOf(1, 2, 3, 5)
    val DIMS = listOf(0, 20, 40, 60, 80)
    val PANEL_ALPHAS = listOf(100, 85, 70, 50)
    val COLOR_KEYS = listOf(
        "bg" to "Tło", "panel" to "Panele", "accent" to "Akcent", "focus" to "Zaznaczenie",
        "focusText" to "Tekst zaznaczenia", "text" to "Tekst", "text2" to "Tekst dodatkowy",
    )
    /** Kolory, które mogą być gradientem (drugi kolor w "c2_<klucz>"). */
    val GRADIENT_KEYS = setOf("bg", "panel", "accent", "focus")
    val GRAD_DIRS = listOf("Pionowy ↓", "Poziomy →", "Ukośny ↘", "Ukośny ↗")
    val GRADIENT_PRESETS = listOf(
        Triple("Ocean", "#0F2027", "#2C5364"), Triple("Zachód słońca", "#FF512F", "#DD2476"),
        Triple("Fiolet nocy", "#41295A", "#2F0743"), Triple("Las", "#134E5E", "#71B280"),
        Triple("Ogień", "#F12711", "#F5AF19"), Triple("Lód", "#83A4D4", "#B6FBFF"),
        Triple("Grafit", "#232526", "#414345"), Triple("Neon", "#FC466B", "#3F5EFB"),
        Triple("Złoto", "#B8860B", "#FFD700"), Triple("Morski", "#1A2980", "#26D0CE"),
    )

    /** Ustawienia, które motyw ustawia na nowo przy wyborze. */
    private val PRESET_KEYS = arrayOf("font", "anim", "progress", "info_pos", "info_style", "zoom", "grad_dir") +
        COLOR_KEYS.map { "c_${it.first}" } + GRADIENT_KEYS.map { "c2_$it" }

    /** Zwiększane po każdej zmianie wyglądu – ekrany odświeżają styl przy powrocie. */
    var version = 0; private set
    fun changed() { version++; tfCache.clear() }

    val preset: ThemePreset get() = PRESETS.firstOrNull { it.id == Prefs.theme } ?: PRESETS[0]

    fun applyPreset(id: String) { Prefs.theme = id; Prefs.remove(*PRESET_KEYS) }
    fun resetColors() = Prefs.remove(*(COLOR_KEYS.map { "c_${it.first}" } + GRADIENT_KEYS.map { "c2_$it" }).toTypedArray())

    /** Drugi kolor gradientu albo null (kolor jednolity). */
    fun color2(key: String): Int? = if (key in GRADIENT_KEYS) Prefs.optInt("c2_$key") else null
    fun setGradient(key: String, c1: Int, c2: Int?) { setColor(key, c1); Prefs.setOptInt("c2_$key", c2) }
    var gradDir: Int get() = Prefs.optInt("grad_dir") ?: 0; set(v) = Prefs.setOptInt("grad_dir", v)
    private val orientation get() = when (gradDir) {
        1 -> GradientDrawable.Orientation.LEFT_RIGHT; 2 -> GradientDrawable.Orientation.TL_BR
        3 -> GradientDrawable.Orientation.BL_TR; else -> GradientDrawable.Orientation.TOP_BOTTOM
    }

    /** Kolor lub gradient jako drawable (alpha 0..255). */
    fun fill(key: String, alpha: Int = 255, radius: Boolean = true): GradientDrawable {
        val c1 = withAlpha(color(key), alpha * Color.alpha(color(key)) / 255)
        val c2 = color2(key)
        val g = if (c2 == null) GradientDrawable().apply { setColor(c1) }
        else GradientDrawable(orientation, intArrayOf(c1, withAlpha(c2, alpha)))
        if (radius) g.cornerRadius = dp(preset.radius).toFloat()
        return g
    }

    fun bgDrawable(): Drawable = fill("bg", radius = false)

    /** Opis koloru do ustawień: "#RRGGBB" lub "#RRGGBB → #RRGGBB". */
    fun colorLabel(key: String): String {
        val a = String.format("#%06X", color(key) and 0xFFFFFF)
        return color2(key)?.let { "$a → " + String.format("#%06X", it and 0xFFFFFF) } ?: a
    }

    fun color(key: String): Int = Prefs.optInt("c_$key") ?: when (key) {
        "bg" -> preset.bg; "panel" -> preset.panel; "accent" -> preset.accent; "focus" -> preset.focus
        "focusText" -> preset.focusText; "text" -> preset.text; else -> preset.text2
    }
    fun setColor(key: String, v: Int) = Prefs.setOptInt("c_$key", v)

    val bg get() = color("bg")
    val panel get() = color("panel")
    val accent get() = color("accent")
    val focus get() = color("focus")
    val focusText get() = color("focusText")
    val text get() = color("text")
    val text2 get() = color("text2")
    val live = c("#E53935")
    val archive = c("#43A047")

    var fontIndex: Int get() = (Prefs.optInt("font") ?: preset.font).coerceIn(FONTS.indices); set(v) = Prefs.setOptInt("font", v)
    var fontScaleIndex: Int get() = Prefs.optInt("font_scale") ?: 1; set(v) = Prefs.setOptInt("font_scale", v)
    var anim: Int get() = Prefs.optInt("anim") ?: preset.anim; set(v) = Prefs.setOptInt("anim", v)
    var animSpeedIndex: Int get() = Prefs.optInt("anim_speed") ?: 1; set(v) = Prefs.setOptInt("anim_speed", v)
    var progressStyle: Int get() = Prefs.optInt("progress") ?: preset.progress; set(v) = Prefs.setOptInt("progress", v)
    var progressRemaining: Boolean get() = Prefs.optInt("progress_rem") == 1; set(v) = Prefs.setOptInt("progress_rem", if (v) 1 else 0)
    var infoPos: Int get() = Prefs.optInt("info_pos") ?: preset.infoPos; set(v) = Prefs.setOptInt("info_pos", v)
    var infoTimeoutIndex: Int get() = Prefs.optInt("info_timeout") ?: 1; set(v) = Prefs.setOptInt("info_timeout", v)
    var zoom: Boolean get() = (Prefs.optInt("zoom") ?: if (preset.zoom) 1 else 0) == 1; set(v) = Prefs.setOptInt("zoom", if (v) 1 else 0)
    var infoStyle: Int get() = (Prefs.optInt("info_style") ?: if (preset.focusStyle == FocusStyle.GRADIENT) 3 else 0).coerceIn(INFO_STYLES.indices); set(v) = Prefs.setOptInt("info_style", v)
    var infoAlphaIndex: Int get() = (Prefs.optInt("info_alpha") ?: 0).coerceIn(INFO_ALPHAS.indices); set(v) = Prefs.setOptInt("info_alpha", v)
    var infoSizeIndex: Int get() = (Prefs.optInt("info_size") ?: 1).coerceIn(INFO_SIZES.indices); set(v) = Prefs.setOptInt("info_size", v)
    var infoPicon: Boolean get() = Prefs.optInt("info_picon") != 0; set(v) = Prefs.setOptInt("info_picon", if (v) 1 else 0)
    var infoNext: Boolean get() = Prefs.optInt("info_next") != 0; set(v) = Prefs.setOptInt("info_next", if (v) 1 else 0)
    var infoClock: Boolean get() = Prefs.optInt("info_clock") != 0; set(v) = Prefs.setOptInt("info_clock", if (v) 1 else 0)
    var archiveInfoIndex: Int get() = (Prefs.optInt("info_archive") ?: 1).coerceIn(ARCHIVE_TIMEOUTS.indices); set(v) = Prefs.setOptInt("info_archive", v)
    val archiveInfoMs get() = ARCHIVE_TIMEOUTS[archiveInfoIndex] * 1000L
    val infoScale get() = INFO_SIZES[infoSizeIndex].first
    val infoFloating get() = infoStyle == 1 || infoStyle == 2

    /** Tło paska informacji wg stylu i przezroczystości (null = bez tła). */
    fun infoBarBg(top: Boolean): Drawable? {
        val d: Drawable = when (infoStyle) {
            1 -> panelBg(0xF0)
            2 -> glassBg()
            3 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(panel, 0.15f), panel, shade(panel, -0.3f)))
                .apply { setStroke(dp(2), withAlpha(accent, 0xD0)) }
            4 -> return null
            else -> GradientDrawable(if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(withAlpha(shade(bg, -0.5f), 0xF0), withAlpha(bg, 0xB0), Color.TRANSPARENT))
        }
        return d.mutate().apply { alpha = INFO_ALPHAS[infoAlphaIndex] * 255 / 100 }
    }

    var dimIndex: Int get() = Prefs.optInt("bg_dim") ?: 2; set(v) = Prefs.setOptInt("bg_dim", v)
    var panelAlphaIndex: Int get() = Prefs.optInt("panel_alpha") ?: 1; set(v) = Prefs.setOptInt("panel_alpha", v)

    val animDuration get() = ANIM_SPEEDS[animSpeedIndex.coerceIn(ANIM_SPEEDS.indices)].first
    val infoTimeoutMs get() = INFO_TIMEOUTS[infoTimeoutIndex.coerceIn(INFO_TIMEOUTS.indices)] * 1000L
    val fontScale get() = FONT_SCALES[fontScaleIndex.coerceIn(FONT_SCALES.indices)].first

    fun dp(v: Number): Int = (v.toFloat() * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun shade(c: Int, f: Float): Int {
        fun ch(v: Int) = (if (f > 0) v + (255 - v) * f else v * (1 + f)).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    // ------------- drawable -------------

    private fun shape(fill: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(preset.radius).toFloat(); setColor(fill)
        if (strokeW > 0) setStroke(dp(strokeW), stroke)
    }

    /** Wygląd zaznaczonego elementu (np. do podglądu). */
    fun focusedBg(): Drawable = focusDrawable()

    private fun focusDrawable(): Drawable = when (preset.focusStyle) {
        FocusStyle.FILL -> fill("focus").apply { setStroke(dp(2), shade(color("focus"), 0.45f)) }
        FocusStyle.OUTLINE -> shape(withAlpha(focus, 0x40), 2, focus)
        FocusStyle.GRADIENT -> GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(focus, 0.35f), focus, shade(focus, -0.25f)),
        ).apply { cornerRadius = dp(preset.radius).toFloat(); setStroke(dp(1), shade(focus, 0.5f)) }
    }

    fun itemBg(border: Boolean = true): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusDrawable())
        addState(intArrayOf(android.R.attr.state_pressed), focusDrawable())
        addState(intArrayOf(android.R.attr.state_selected), focusDrawable())
        addState(intArrayOf(android.R.attr.state_activated), shape(withAlpha(accent, 0x45), if (border) 1 else 0, withAlpha(accent, 0x90)))
        addState(intArrayOf(), if (border) shape(Color.TRANSPARENT, 1, withAlpha(text, 0x1C)) else shape(Color.TRANSPARENT))
    }

    /** Tło komórki przewodnika: 0 kafelki, 1 linie, 2 kompakt, 3 klasyczny. */
    fun cellBg(style: Int): Drawable {
        if (style == 0 || style == 2) return itemBg()
        fun sq(fill: Int, sw: Int, sc: Int) = GradientDrawable().apply { setColor(fill); if (sw > 0) setStroke(dp(sw), sc) }
        val normal = if (style == 1) sq(Color.TRANSPARENT, 1, withAlpha(text, 0x30))
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(panel, 0.18f), shade(panel, 0.05f))).apply { setStroke(dp(1), withAlpha(text, 0x50)) }
        val now = if (style == 1) sq(withAlpha(accent, 0x40), 1, withAlpha(text, 0x30)) else sq(withAlpha(accent, 0x55), 1, withAlpha(accent, 0xC0))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusDrawable())
            addState(intArrayOf(android.R.attr.state_pressed), focusDrawable())
            addState(intArrayOf(android.R.attr.state_selected), focusDrawable())
            addState(intArrayOf(android.R.attr.state_activated), now)
            addState(intArrayOf(), normal)
        }
    }

    /** Kafelek nad obrazem (lista kanałów po OK): ciemny jak pasek informacji, zaznaczenie w kolorze motywu. */
    fun tileBg(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusDrawable())
        addState(intArrayOf(android.R.attr.state_pressed), focusDrawable())
        addState(intArrayOf(android.R.attr.state_activated), surfaceBg(0xF0).apply { setStroke(dp(2), withAlpha(accent, 0xC0)) })
        addState(intArrayOf(), surfaceBg(0xE0).apply { setStroke(dp(1), withAlpha(text, 0x22)) })
    }

    /** Kolor ikon: akcent, a na zaznaczonym elemencie kolor tekstu zaznaczenia. */
    fun iconTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed),
        intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(focusText, focusText, focusText, accent))

    /** Ikona motywu nad tekstem przycisku. */
    fun iconOnTop(tv: TextView, res: Int, sizeDp: Int = 26) {
        val d = androidx.core.content.ContextCompat.getDrawable(tv.context, res)!!.mutate().apply { setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }
        tv.setCompoundDrawables(null, d, null, null); tv.compoundDrawablePadding = dp(4)
        tv.compoundDrawableTintList = iconTint()
    }

    /** Półprzezroczyste okno nad obrazem (panele w odtwarzaczu). */
    fun glassBg(): Drawable = surfaceBg(0xE6)

    /** Tło w stylu paska informacji: ciemny gradient z koloru tła motywu, cienka ramka w kolorze akcentu. */
    fun surfaceBg(alpha: Int = 0xF0, radius: Int = maxOf(preset.radius, 12)): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(shade(bg, -0.25f), alpha), withAlpha(shade(bg, -0.55f), alpha))).apply {
            cornerRadius = dp(radius).toFloat(); setStroke(dp(1), withAlpha(accent, 0x66))
        }

    fun fieldBg(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(shade(panel, 0.06f), 2, focus))
        addState(intArrayOf(), shape(shade(panel, 0.06f), 1, withAlpha(text2, 0x80)))
    }

    val panelAlpha get() = PANEL_ALPHAS[panelAlphaIndex.coerceIn(PANEL_ALPHAS.indices)] * 255 / 100

    fun panelBg(alpha: Int = panelAlpha): Drawable = if (color2("panel") != null) fill("panel", alpha)
    else if (preset.focusStyle == FocusStyle.GRADIENT)
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(shade(panel, 0.12f), alpha), withAlpha(panel, alpha), withAlpha(shade(panel, -0.3f), alpha)))
            .apply { cornerRadius = dp(preset.radius).toFloat(); setStroke(dp(1), withAlpha(shade(panel, 0.4f), 0xC0)) }
    else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(withAlpha(shade(panel, 0.06f), alpha), withAlpha(shade(panel, -0.06f), alpha)))
        .apply { cornerRadius = dp(preset.radius).toFloat(); setStroke(dp(1), withAlpha(text, 0x14)) }

    /** Tło paska informacji w odtwarzaczu. */
    fun infoBg(top: Boolean): Drawable = if (preset.focusStyle == FocusStyle.GRADIENT || preset.id == "light")
        panelBg(0xF0)
    else GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(withAlpha(shade(bg, -0.5f), 0xF0), withAlpha(bg, 0xB0), Color.TRANSPARENT),
    )

    fun textColors(base: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(focusText, focusText, base),
    )

    private val tfCache = HashMap<Long, Typeface>()

    /** Krój dla bieżącej czcionki (buforowany – ta sama instancja nie wymusza ponownego układu widoku). */
    fun typeface(style: Int): Typeface = tfCache.getOrPut(fontIndex.toLong() * 16 + style) { createTypeface(style) }

    private fun createTypeface(style: Int): Typeface {
        val f = FONTS[fontIndex]
        val st = if (f.bold) style or Typeface.BOLD else style
        if (f.res != 0 && ::app.isInitialized) {
            val base = fontCache.getOrPut(f.res) { runCatching { androidx.core.content.res.ResourcesCompat.getFont(app, f.res) }.getOrNull() }
            if (base != null) return Typeface.create(base, st)
        }
        return Typeface.create(f.family.ifEmpty { "sans-serif" }, st)
    }

    /**
     * Powiększenie zaznaczonego elementu: najwyżej ok. 6 dp na stronę (szerokie wiersze rosną minimalnie,
     * małe kafelki do 3,5%), uniesione nad sąsiadów – nic nie wychodzi poza obramowanie paneli.
     */
    private fun zoomAnimator(v: View, w: Int, h: Int): StateListAnimator {
        val sx = 1f + minOf(0.035f, dp(12).toFloat() / maxOf(w, 1)); val sy = 1f + minOf(0.035f, dp(8).toFloat() / maxOf(h, 1))
        val s = minOf(sx, sy)
        fun a(sc: Float, z: Float) = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, sc),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, sc), PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, z)).setDuration(110)
        return StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_focused), a(s, dp(4).toFloat()))
            addState(intArrayOf(), a(1f, 0f))
        }
    }

    private fun applyZoom(v: View) {
        v.stateListAnimator = null
        if (!zoom) return
        if (v.width > 0) v.stateListAnimator = zoomAnimator(v, v.width, v.height)
        if (v.getTag(R.id.zoom_listener) == null) {
            v.setTag(R.id.zoom_listener, true)
            v.addOnLayoutChangeListener { x, l, t, r, b, ol, ot, or2, ob ->
                if (zoom && (r - l != or2 - ol || b - t != ob - ot)) x.stateListAnimator = zoomAnimator(x, r - l, b - t)
            }
        }
    }

    // ------------- stylowanie drzewa widoków -------------

    private fun isScroller(v: View) = v is RecyclerView || v is android.widget.ScrollView || v is android.widget.HorizontalScrollView ||
        v is androidx.core.widget.NestedScrollView || v is android.widget.AbsListView

    fun style(v: View, inItem: Boolean = false) {
        val tags = v.tag?.toString()?.split(' ') ?: emptyList()
        var item = inItem
        if ("panel" in tags) v.background = panelBg()
        if ("glass" in tags) v.background = glassBg()
        if ("item" in tags) {
            v.background = itemBg(border = "noborder" !in tags)
            if ("nozoom" !in tags) applyZoom(v) else v.stateListAnimator = null
            // powiększony element nie może być przycinany przez rodzica
            // (lista przycina się na swoich granicach – przewinięte elementy nie nachodzą na nagłówki obok)
            if (zoom) (v.parent as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false
                val gp = it.parent as? ViewGroup
                // rodzic przewijanej listy zostaje przycinający (lista nie może wychodzić na przyciski obok)
                if (it !is RecyclerView && gp != null && !isScroller(gp) && (0 until gp.childCount).none { i -> isScroller(gp.getChildAt(i)) }) gp.clipChildren = false }
            item = true
        } else if (inItem) v.isDuplicateParentStateEnabled = true
        if ("field" in tags) v.background = fieldBg()
        if (v is TextView) styleText(v, tags, item)
        // listy: miejsce na powiększenie zaznaczonego elementu (bez przycinania na brzegach)
        // przewijane kontenery przycinają treść na swojej krawędzi (nie wchodzi pod przyciski i nagłówki obok)
        if ((v is android.widget.ScrollView || v is androidx.core.widget.NestedScrollView || v is android.widget.HorizontalScrollView))
            (v.parent as? ViewGroup)?.clipChildren = true
        if (v is RecyclerView) { v.clipToPadding = false; v.clipChildren = false
            // granica listy: elementy znikają na jej krawędzi (nie wchodzą pod nagłówki i strzałki obok)
            if (v.getTag(R.id.ui_clip) == null) { v.setTag(R.id.ui_clip, true)
                v.addOnLayoutChangeListener { x, _, _, _, _, _, _, _, _ -> x.clipBounds = android.graphics.Rect(0, 0, x.width, x.height) } }
            if (v.paddingLeft < dp(4)) v.setPadding(dp(5), maxOf(v.paddingTop, dp(4)), dp(5), maxOf(v.paddingBottom, dp(4)))
            v.setItemViewCacheSize(12) }
        if (v is ViewGroup && v !is RecyclerView) for (i in 0 until v.childCount) style(v.getChildAt(i), item)
    }

    private fun styleText(v: TextView, tags: List<String>, inItem: Boolean) {
        val base = when {
            "text2" in tags -> text2
            "accent" in tags -> accent
            else -> text
        }
        if ("keepcolor" !in tags) {
            if (inItem) v.setTextColor(textColors(base)) else v.setTextColor(base)
        }
        if (v is EditText) v.setHintTextColor(withAlpha(text2, 0xA0))
        val origStyle = (v.getTag(R.id.ui_style) as? Int) ?: (v.typeface?.style ?: 0).also { v.setTag(R.id.ui_style, it) }
        v.typeface = typeface(origStyle)
        val origSize = (v.getTag(R.id.ui_size) as? Float) ?: v.textSize.also { v.setTag(R.id.ui_size, it) }
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, origSize * fontScale)
    }

    // ------------- okna dialogowe -------------

    /** Builder okien w stylu motywu – używany zamiast AlertDialog.Builder w całej aplikacji. */
    fun dialog(c: android.content.Context): androidx.appcompat.app.AlertDialog.Builder =
        object : androidx.appcompat.app.AlertDialog.Builder(c) {
            override fun create(): androidx.appcompat.app.AlertDialog {
                val d = super.create()
                d.setOnShowListener { styleDialog(d) }
                return d
            }
        }

    fun dialogBg(): Drawable = (if (color2("panel") != null) fill("panel").apply { setStroke(dp(1), withAlpha(accent, 0x90)) }
        else surfaceBg(0xF6, maxOf(preset.radius, 14))).apply { cornerRadius = dp(maxOf(preset.radius, 14)).toFloat() }

    private fun pillBg(primary: Boolean): Drawable = StateListDrawable().apply {
        val r = dp(22).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = r; setColor(focus) })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = r; setColor(focus) })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = r; setColor(if (primary) withAlpha(accent, 0x40) else Color.TRANSPARENT); setStroke(dp(1), withAlpha(if (primary) accent else text2, 0xA0))
        })
    }

    fun styleDialog(d: androidx.appcompat.app.AlertDialog) {
        val w = d.window ?: return
        val dm = d.context.resources.displayMetrics
        val wide = dm.widthPixels / dm.density >= 900
        w.setBackgroundDrawable(dialogBg())
        w.setLayout((dm.widthPixels * if (wide) 0.46f else 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setDimAmount(0.7f)
        // treść odsunięta od zaokrąglonej ramki – zaznaczenia i przyciski nie są ucinane na rogach
        w.decorView.setPadding(dp(10), dp(8), dp(10), dp(10))
        styleDialogTree(w.decorView)
        d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            setTextColor(Ui.accent); typeface = Ui.typeface(Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * fontScale); maxLines = 3
        }
        d.findViewById<TextView>(android.R.id.message)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale); setLineSpacing(0f, 1.15f)
        }
        for (which in intArrayOf(android.content.DialogInterface.BUTTON_POSITIVE,
            android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL)) {
            d.getButton(which)?.apply {
                val primary = which == android.content.DialogInterface.BUTTON_POSITIVE
                isAllCaps = false
                setTextColor(textColors(if (primary) Ui.accent else Ui.text))
                typeface = Ui.typeface(Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
                background = pillBg(primary)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = dp(8); layoutParams = it }
            }
        }
        d.listView?.let { lv -> styleList(lv) }
        d.findViewById<View>(androidx.appcompat.R.id.buttonPanel)?.setPadding(dp(8), dp(6), dp(8), dp(6))
        // początkowy kursor: pole tekstowe → lista (pierwsza pozycja) → „OK”
        w.decorView.post {
            val edit = findFirst(w.decorView) { it is EditText && it.isShown } as EditText?
            val list = (d.listView?.takeIf { it.isShown && it.count > 0 }
                ?: findFirst(w.decorView) { it is android.widget.ListView && it.isShown && (it as android.widget.ListView).count > 0 }) as android.widget.ListView?
            when {
                edit != null -> {
                    edit.requestFocus(); edit.setSelection(edit.text.length)
                    // telefon: klawiatura od razu; TV: klawiatura po OK na polu
                    if (d.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN))
                        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
                list != null -> if (!list.hasFocus()) { list.requestFocus(); if (list.checkedItemPosition < 0) list.setSelection(0) }
                else -> {
                    val f = w.decorView.findFocus()
                    val custom = f != null && f !is android.widget.Button && f.isFocusable && f !is ViewGroup && f !is android.widget.ScrollView
                    if (!custom) (d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.takeIf { it.isShown }
                        ?: d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.takeIf { it.isShown })?.requestFocus()
                }
            }
            // okno nie wyższe niż ekran – przyciski na dole zawsze widoczne (treść przewija się)
            val maxH = (dm.heightPixels * 0.9f).toInt()
            if (w.decorView.height > maxH) w.setLayout(w.attributes.width, maxH)
        }
    }

    private fun findFirst(v: View, test: (View) -> Boolean): View? {
        if (test(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findFirst(v.getChildAt(i), test)?.let { return it }
        return null
    }

    private fun styleDialogTree(v: View) {
        when (v) {
            is android.widget.Button -> {}
            is EditText -> { v.setTextColor(text); v.setHintTextColor(withAlpha(text2, 0xA0)); v.background = fieldBg()
                v.setPadding(dp(10), dp(8), dp(10), dp(8)); v.typeface = typeface(0) }
            is TextView -> { if (v.tag?.toString()?.contains("keepcolor") != true) v.setTextColor(text); v.typeface = typeface(v.typeface?.style ?: 0) }
            is android.widget.ListView -> styleList(v)
        }
        if (v is ViewGroup && v !is android.widget.ListView) for (i in 0 until v.childCount) styleDialogTree(v.getChildAt(i))
    }

    /** Lista w oknie: zaznaczenie w kolorze motywu, tekst zaznaczonego w kolorze „tekst zaznaczenia”. */
    private fun styleList(lv: android.widget.ListView) {
        if (lv.getTag(R.id.ui_style) == "styled") return
        lv.setTag(R.id.ui_style, "styled")
        lv.selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusDrawable())
            addState(intArrayOf(android.R.attr.state_pressed), focusDrawable())
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        lv.divider = android.graphics.drawable.ColorDrawable(withAlpha(text, 0x14)); lv.dividerHeight = 1
        lv.setPadding(dp(6), dp(4), dp(6), dp(4)); lv.clipToPadding = false
        // wiersze dostają docelową wysokość już przy pierwszym pomiarze – okno nie ucina ostatniej pozycji
        (lv.adapter as? android.widget.ListAdapter)?.takeIf { it !is SizedAdapter }?.let { base ->
            val checked = lv.checkedItemPositions?.clone()
            lv.adapter = SizedAdapter(base)
            if (checked != null) for (i in 0 until checked.size()) lv.setItemChecked(checked.keyAt(i), checked.valueAt(i))
        }
        // zmienia widok tylko, gdy zmienił się jego stan – inaczej każda zmiana wywołałaby kolejny układ (pętla)
        fun restyle() {
            var resized = false
            for (i in 0 until lv.childCount) {
                val tv = lv.getChildAt(i) as? TextView ?: continue
                if (tv.getTag(R.id.ui_size) != "dlg") {         // jednorazowo: wysokość, odstępy, wielkość tekstu
                    tv.setTag(R.id.ui_size, "dlg"); resized = true
                    tv.minHeight = dp(52); tv.setPadding(dp(22), dp(10), dp(22), dp(10))
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
                }
                val selected = lv.hasFocus() && lv.firstVisiblePosition + i == lv.selectedItemPosition
                // zaznaczona (wybrana) opcja wyróżniona akcentem, gdy nie ma na niej kursora
                val checked = (tv as? android.widget.Checkable)?.isChecked == true
                val color = if (selected) focusText else if (checked) accent else text
                val tf = typeface(0)
                if (tv.currentTextColor != color) tv.setTextColor(color)
                if (tv.typeface !== tf) tv.typeface = tf
                if (tv is android.widget.CheckedTextView) {
                    val tint = if (selected) focusText else accent
                    if (tv.checkMarkTintList?.defaultColor != tint) tv.checkMarkTintList = ColorStateList.valueOf(tint)
                }
            }
            // wyższe wiersze: okno mierzy listę jeszcze raz (inaczej ostatnia pozycja jest ucięta)
            if (resized) lv.post { lv.requestLayout(); (lv.rootView as? View)?.requestLayout() }
        }
        lv.viewTreeObserver.addOnGlobalLayoutListener { restyle() }
        lv.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = restyle()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = restyle()
        }
        lv.setOnFocusChangeListener { _, _ -> restyle() }
    }

    /** Adapter list w oknach: stała wysokość, odstępy i wielkość tekstu wiersza od pierwszego pomiaru. */
    private class SizedAdapter(val base: android.widget.ListAdapter) : android.widget.BaseAdapter() {
        override fun getCount() = base.count
        override fun getItem(p: Int): Any? = base.getItem(p)
        override fun getItemId(p: Int) = base.getItemId(p)
        override fun hasStableIds() = base.hasStableIds()
        override fun getViewTypeCount() = base.viewTypeCount
        override fun getItemViewType(p: Int) = base.getItemViewType(p)
        override fun isEnabled(p: Int) = base.isEnabled(p)
        override fun areAllItemsEnabled() = base.areAllItemsEnabled()
        override fun registerDataSetObserver(o: android.database.DataSetObserver?) { super.registerDataSetObserver(o); base.registerDataSetObserver(o) }
        override fun unregisterDataSetObserver(o: android.database.DataSetObserver?) { super.unregisterDataSetObserver(o); base.unregisterDataSetObserver(o) }
        override fun getView(p: Int, v: View?, parent: ViewGroup): View = base.getView(p, v, parent).also { tv ->
            if (tv is TextView && tv.getTag(R.id.ui_size) != "dlg") {
                tv.setTag(R.id.ui_size, "dlg")
                tv.minHeight = dp(52); tv.setPadding(dp(22), dp(10), dp(22), dp(10))
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
            }
        }
    }

    // ------------- animacje -------------

    fun openTransition(a: Activity) {
        @Suppress("DEPRECATION")
        when (anim) {
            1 -> a.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            2 -> a.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            3 -> a.overridePendingTransition(R.anim.zoom_in, R.anim.fade_out)
            else -> a.overridePendingTransition(0, 0)
        }
    }

    fun closeTransition(a: Activity) {
        @Suppress("DEPRECATION")
        when (anim) {
            1 -> a.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            2 -> a.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            3 -> a.overridePendingTransition(R.anim.fade_in, R.anim.zoom_out)
            else -> a.overridePendingTransition(0, 0)
        }
    }

    /** Wysuwa panel z boku (fromLeft) zgodnie z wybraną animacją. */
    /** Droga przesunięcia panelu: jego szerokość (min. 160 dp), najwyżej 60% ekranu. */
    private fun slideDistance(v: View): Float {
        val w = v.resources.displayMetrics.widthPixels.toFloat()
        val own = (if (v.width > 0) v.width else v.layoutParams?.width?.takeIf { it > 0 } ?: dp(320)).toFloat()
        return own.coerceIn(dp(160).toFloat(), w * 0.6f)
    }

    fun slideIn(v: View, fromLeft: Boolean) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (anim == 0) { v.translationX = 0f; v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        val d = slideDistance(v)
        v.translationX = if (fromLeft) -d else d
        v.alpha = if (anim == 2) 1f else 0f
        // krótsza droga i hamowanie na końcu – płynniej na słabszych urządzeniach
        v.animate().translationX(0f).alpha(1f).setDuration(animDuration).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
    }

    fun slideOut(v: View, toLeft: Boolean, end: () -> Unit = {}) {
        v.animate().cancel()
        if (anim == 0 || v.visibility != View.VISIBLE) { v.visibility = View.GONE; end(); return }
        val d = slideDistance(v)
        v.animate().translationX(if (toLeft) -d else d).alpha(if (anim == 2) 1f else 0f)
            .setDuration(animDuration * 3 / 4).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withLayer()
            .withEndAction { v.visibility = View.GONE; v.translationX = 0f; v.alpha = 1f; end() }.start()
    }

    fun fadeIn(v: View) {
        if (v.visibility == View.VISIBLE && v.alpha == 1f) return
        v.animate().cancel(); v.visibility = View.VISIBLE
        if (anim == 0) { v.alpha = 1f; return }
        v.alpha = 0f; v.animate().alpha(1f).setDuration(animDuration).start()
    }

    fun fadeOut(v: View) {
        v.animate().cancel()
        if (anim == 0) { v.visibility = View.GONE; return }
        v.animate().alpha(0f).setDuration(animDuration).withEndAction { v.visibility = View.GONE }.start()
    }
}

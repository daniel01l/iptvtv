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
    /** Faktura powierzchni: [Ui.S_FLAT], [Ui.S_GLASS] (szkło), [Ui.S_XBOX] (konsola). */
    val surface: Int = 0,
    /** Układ menu głównego narzucany przez motyw (-1 = bez zmiany, [Ui.LAYOUT_CARDS] = karty poziome). */
    val layout: Int = -1,
)

/**
 * Tło z fakturą: gradient w zaokrąglonym prostokącie, delikatny szum (szkło – bez pasm na gradientach),
 * podświetlenie górnej krawędzi i cienka ramka. Szum to jeden mały kafelek wspólny dla całej aplikacji.
 */
class TexturedDrawable(
    private val c1: Int, private val c2: Int, private val radius: Float,
    private val stroke: Int = 0, private val strokeW: Float = 0f,
    private val noiseAlpha: Int = 0, private val highlight: Int = 0,
    private val diagonal: Boolean = false,
    /** Pasek akcentu u góry (ramka okna: Xbox); 0 = brak. */
    private val topBar: Int = 0,
) : Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val grain = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val rect = android.graphics.RectF()
    private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private var alphaMul = 255

    override fun onBoundsChange(b: android.graphics.Rect) {
        rect.set(b)
        fill.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), if (diagonal) b.right.toFloat() else b.left.toFloat(), b.bottom.toFloat(),
            c1, c2, android.graphics.Shader.TileMode.CLAMP)
        if (noiseAlpha > 0) grain.shader = android.graphics.BitmapShader(noise, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }

    override fun draw(c: android.graphics.Canvas) {
        if (rect.isEmpty) return
        fill.alpha = alphaMul
        if (radius > 0) c.drawRoundRect(rect, radius, radius, fill) else c.drawRect(rect, fill)
        if (noiseAlpha > 0) { grain.alpha = noiseAlpha * alphaMul / 255
            if (radius > 0) c.drawRoundRect(rect, radius, radius, grain) else c.drawRect(rect, grain) }
        if (topBar != 0) {
            bar.color = topBar; bar.alpha = Color.alpha(topBar) * alphaMul / 255
            clip.reset(); clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            c.save(); c.clipPath(clip); c.drawRect(rect.left, rect.top, rect.right, rect.top + Ui.dp(3), bar); c.restore()
        }
        val half = strokeW / 2
        if (highlight != 0 && strokeW > 0) {
            // jaśniejsza górna krawędź (światło z góry – szkło, karty)
            line.strokeWidth = strokeW; line.color = highlight; line.alpha = Color.alpha(highlight) * alphaMul / 255
            c.save(); c.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() / 3)
            c.drawRoundRect(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half, radius, radius, line); c.restore()
        }
        if (stroke != 0 && strokeW > 0) {
            line.strokeWidth = strokeW; line.color = stroke; line.alpha = Color.alpha(stroke) * alphaMul / 255
            c.drawRoundRect(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half, radius, radius, line)
        }
    }

    override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, radius) }
    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** Kafelek szumu 96×96 (szary, losowy, stały wzór) – faktura miki i matowego szkła. */
        val noise: android.graphics.Bitmap by lazy {
            val n = 96; val r = java.util.Random(7)
            val px = IntArray(n * n) { val v = 96 + r.nextInt(128); Color.argb(255, v, v, v) }
            android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
}

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
        // inspirowane systemami (nie kopie): szkło iOS i konsola Xbox
        ThemePreset("ios", "iOS – szkło", c("#05060A"), c("#1C1C1E"), c("#0A84FF"), c("#F2F2F7"), c("#000000"),
            c("#FFFFFF"), c("#8E8E93"), 22, 8, FocusStyle.FILL, 5, 2, 0, true, S_GLASS),
        ThemePreset("xbox", "Xbox Series X – konsola", c("#0B0B0B"), c("#1C1C1C"), c("#107C10"), c("#107C10"), c("#FFFFFF"),
            c("#F2F2F2"), c("#9A9A9A"), 2, 2, FocusStyle.OUTLINE, 8, 1, 0, true, S_XBOX, LAYOUT_CARDS),
    )

    const val S_FLAT = 0
    const val S_GLASS = 2
    const val S_XBOX = 5
    /** Układ menu głównego „karty poziome” (konsole). */
    const val LAYOUT_CARDS = 4
    val surface get() = preset.surface

    /**
     * Geometria motywu (poza kolorami): powiększenie zaznaczenia, wielkość nagłówków, czas i sprężystość animacji,
     * ramka okien (tytuł, pasek akcentu, poświata), kształt kafelków ikon, rozmycie pod oknami.
     */
    data class Geometry(
        val zoomMax: Float = 0.035f, val headerScale: Float = 1f, val zoomMs: Long = 110, val overshoot: Float = 0f,
        val titleCenter: Boolean = false, val topBar: Boolean = false, val glow: Boolean = false,
        val iconRound: Boolean = false, val blur: Int = 0, val focusStroke: Int = 2,
    )

    val geo: Geometry get() = when (surface) {
        S_GLASS -> Geometry(zoomMax = 0.05f, headerScale = 1.35f, zoomMs = 170, overshoot = 1.4f, titleCenter = true, iconRound = true, blur = 28)
        S_XBOX -> Geometry(zoomMax = 0.05f, headerScale = 1.1f, zoomMs = 80, topBar = true, focusStroke = 3)
        else -> Geometry()
    }

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
    val ANIMS = listOf("Brak", "Zanikanie", "Przesunięcie", "Powiększenie", "Unoszenie", "Sprężyna (iOS)", "Przyciąganie",
        "Wejście w kartę", "Konsola – szybkie (Xbox)")
    val ANIM_SPEEDS = listOf(150L to "Szybko", 250L to "Normalnie", 450L to "Wolno")
    val PROGRESS = listOf("Cienka linia", "Gruby pasek", "Zaokrąglony", "Segmenty (klasyczny)", "Ukryty")
    val INFO_POS = listOf("Na dole", "Na górze")
    val INFO_TIMEOUTS = listOf(3, 5, 8, 12)
    val INFO_STYLES = listOf("Gradient (nowoczesny)", "Pływający panel", "Szkło (przezroczyste)", "Klasyczny dekoder", "Minimalny (sam tekst)",
        "Pływająca karta", "iOS – szklana kapsuła", "Panel z paskiem akcentu", "Pasek z poświatą", "Xbox – ostry pasek")
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

    /** Usunięte motywy (6.1) → najbliższy pozostały: PS5 → Xbox, MagicOS → iOS, Windows 11 → Nowoczesny. */
    val REMOVED_THEMES = mapOf("ps5" to "xbox", "magic" to "ios", "win11" to "modern")

    val preset: ThemePreset get() {
        val id = Prefs.theme.let { REMOVED_THEMES[it] ?: it }
        return PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]
    }

    /**
     * Zmienia się przy każdej zmianie motywu: otwarte ekrany (menu, ustawienia, przewodnik…) budują się od nowa przy powrocie,
     * a ekran ustawień od razu – motyw działa wszędzie bez ponownego uruchamiania aplikacji.
     */
    var themeEpoch = 0; private set

    fun applyPreset(id: String) {
        themeEpoch++
        val old = preset
        Prefs.theme = id; Prefs.remove(*PRESET_KEYS)
        // motywy konsolowe ustawiają też układ menu (karty poziome); powrót do zwykłego motywu przywraca układ klasyczny
        val p = preset
        if (p.layout >= 0) Prefs.mainLayout = p.layout
        else if (old.layout >= 0 && Prefs.mainLayout == old.layout) Prefs.mainLayout = 0
    }
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

    /** Tło ekranu; motywy z fakturą: szkło (poświata akcentu), konsola Xbox (grafit z zielenią). */
    fun bgDrawable(): Drawable {
        if (color2("bg") != null || surface == S_FLAT) return fill("bg", radius = false).apply { setDither(true) }
        val b = bg
        return when (surface) {
            S_GLASS -> TexturedDrawable(blend(b, accent, 0.16f), b, 0f, noiseAlpha = 0x08, diagonal = true)
            // Xbox: grafit z delikatną zielenią
            S_XBOX -> TexturedDrawable(blend(b, accent, 0.07f), shade(b, -0.1f), 0f)
            else -> TexturedDrawable(shade(b, 0.03f), shade(b, -0.08f), 0f)
        }
    }

    fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(a), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** Powierzchnia (panel, okno) w fakturze motywu; null = motyw bez faktury (zwykłe tła). */
    private fun texturedSurface(base: Int, alpha: Int, radiusDp: Int, frame: Boolean = false): Drawable? {
        val tb = if (frame && geo.topBar) accent else 0
        val r = dp(radiusDp).toFloat()
        return when (surface) {
            S_GLASS -> TexturedDrawable(withAlpha(shade(base, 0.10f), minOf(alpha, 0xD8)), withAlpha(base, minOf(alpha, 0xC4)), r,
                stroke = withAlpha(Color.WHITE, 0x1E), strokeW = dp(1).toFloat(), noiseAlpha = 0x0A, highlight = withAlpha(Color.WHITE, 0x40))
            // Xbox: jednolita, ostra powierzchnia
            S_XBOX -> TexturedDrawable(withAlpha(base, alpha), withAlpha(shade(base, -0.05f), alpha), r,
                stroke = withAlpha(Color.WHITE, 0x14), strokeW = dp(1).toFloat(), topBar = tb)
            else -> null
        }
    }

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
    /** Styl paska informacji; domyślnie wg motywu (motywy systemowe i konsolowe mają własny pasek). */
    var infoStyle: Int get() = (Prefs.optInt("info_style") ?: when (surface) {
        S_GLASS -> 6; S_XBOX -> 9
        else -> if (preset.focusStyle == FocusStyle.GRADIENT) 3 else 0 }).coerceIn(INFO_STYLES.indices); set(v) = Prefs.setOptInt("info_style", v)
    var infoAlphaIndex: Int get() = (Prefs.optInt("info_alpha") ?: 0).coerceIn(INFO_ALPHAS.indices); set(v) = Prefs.setOptInt("info_alpha", v)
    var infoSizeIndex: Int get() = (Prefs.optInt("info_size") ?: 1).coerceIn(INFO_SIZES.indices); set(v) = Prefs.setOptInt("info_size", v)
    var infoPicon: Boolean get() = Prefs.optInt("info_picon") != 0; set(v) = Prefs.setOptInt("info_picon", if (v) 1 else 0)
    var infoNext: Boolean get() = Prefs.optInt("info_next") != 0; set(v) = Prefs.setOptInt("info_next", if (v) 1 else 0)
    var infoClock: Boolean get() = Prefs.optInt("info_clock") != 0; set(v) = Prefs.setOptInt("info_clock", if (v) 1 else 0)
    var archiveInfoIndex: Int get() = (Prefs.optInt("info_archive") ?: 1).coerceIn(ARCHIVE_TIMEOUTS.indices); set(v) = Prefs.setOptInt("info_archive", v)
    val archiveInfoMs get() = ARCHIVE_TIMEOUTS[archiveInfoIndex] * 1000L
    val infoScale get() = INFO_SIZES[infoSizeIndex].first
    val infoFloating get() = infoStyle in intArrayOf(1, 2, 5, 6, 7)

    /** Tło paska informacji wg stylu i przezroczystości (null = bez tła). */
    fun infoBarBg(top: Boolean): Drawable? {
        val d: Drawable = when (infoStyle) {
            1 -> panelBg(0xF0)
            2 -> glassBg()
            3 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(panel, 0.15f), panel, shade(panel, -0.3f)))
                .apply { setStroke(dp(2), withAlpha(accent, 0xD0)) }
            4 -> return null
            // paski: karta, kapsuła ze szkła (iOS), panel z paskiem akcentu,
            // szeroki pasek z poświatą u góry, ostry pasek z linią limonki (Xbox)
            5 -> TexturedDrawable(withAlpha(shade(panel, 0.05f), 0xF4), withAlpha(panel, 0xF4), dp(24).toFloat(), stroke = withAlpha(text, 0x14), strokeW = dp(1).toFloat())
            6 -> TexturedDrawable(withAlpha(shade(panel, 0.12f), 0xD8), withAlpha(panel, 0xC8), dp(30).toFloat(), stroke = withAlpha(Color.WHITE, 0x22),
                strokeW = dp(1).toFloat(), noiseAlpha = 0x0A, highlight = withAlpha(Color.WHITE, 0x50))
            7 -> TexturedDrawable(withAlpha(blend(panel, accent, 0.05f), 0xEE), withAlpha(shade(panel, -0.06f), 0xEE), dp(8).toFloat(),
                stroke = withAlpha(Color.WHITE, 0x18), strokeW = dp(1).toFloat(), noiseAlpha = 0x0C, topBar = accent)
            8 -> TexturedDrawable(if (top) withAlpha(shade(bg, -0.3f), 0xF4) else withAlpha(blend(bg, accent, 0.28f), 0x70),
                if (top) withAlpha(blend(bg, accent, 0.28f), 0x70) else withAlpha(shade(bg, -0.3f), 0xF4), 0f, noiseAlpha = 0x06,
                topBar = if (top) 0 else withAlpha(accent, 0xC0))
            9 -> TexturedDrawable(withAlpha(shade(panel, -0.1f), 0xF0), withAlpha(shade(panel, -0.1f), 0xF0), 0f, topBar = focus)
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

    /** Ikony (emoji i symbole spoza ASCII) z początku tytułu ustawień. Znaki ASCII ([, |, #, +, * …) i litery zostają –
     *  wcześniej obcinany był każdy początkowy znak niebędący literą, np. „[PL] Sport” → „PL] Sport”. */
    private val iconPrefix = Regex("^(?:[\\p{So}\\p{Sk}\\p{Sm}\\p{Cn}\\p{Co}&&[^\\x00-\\x7F]]|[\\uFE0F\\u200D\\u20E3]|\\s)+")
    fun stripIcon(t: String): String = t.replace(iconPrefix, "").ifEmpty { t }

    fun dp(v: Number): Int =(v.toFloat() * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
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

    private fun focusDrawable(): Drawable = when {
        // iOS: jasna karta z miękkim, świecącym pierścieniem
        preset.focusStyle == FocusStyle.FILL && surface == S_GLASS && color2("focus") == null -> glowFocus(Color.WHITE)
        surface == S_XBOX -> xboxFocus()
        else -> focusDrawableClassic()
    }

    /** Zaznaczenie z poświatą: dwa półprzezroczyste pierścienie w kolorze akcentu wokół jasnej karty. */
    private fun glowFocus(ring: Int = accent): Drawable {
        val r = dp(preset.radius).toFloat()
        val accent = ring
        fun ring(rad: Float, w: Int, c: Int) = GradientDrawable().apply { cornerRadius = rad; setColor(Color.TRANSPARENT); setStroke(dp(w), c) }
        val inner = GradientDrawable().apply { cornerRadius = maxOf(r - dp(4), 0f); setColor(focus); setStroke(dp(1), withAlpha(Color.WHITE, 0xFF)) }
        return android.graphics.drawable.LayerDrawable(arrayOf(ring(r, 3, withAlpha(accent, 0x40)), ring(maxOf(r - dp(2), 0f), 2, withAlpha(accent, 0xA0)), inner)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2)); setLayerInset(2, dp(4), dp(4), dp(4), dp(4))
        }
    }

    /** Xbox: ostra ramka limonki (#107C10) o dużym kontraście – wewnętrzna jasna linia oddziela ją od ciemnego tła. */
    private fun xboxFocus(): Drawable {
        val outer = shape(withAlpha(focus, 0x30), geo.focusStroke, focus)
        val inner = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), withAlpha(Color.WHITE, 0xD0)) }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
            val i = dp(geo.focusStroke); setLayerInset(1, i, i, i, i)
        }
    }

    private fun focusDrawableClassic(): Drawable = when (preset.focusStyle) {
        FocusStyle.FILL -> fill("focus").apply { setStroke(dp(2), shade(color("focus"), 0.45f)) }
        FocusStyle.OUTLINE -> shape(withAlpha(focus, 0x40), 2, focus)
        FocusStyle.GRADIENT -> GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(focus, 0.35f), focus, shade(focus, -0.25f)),
        ).apply { cornerRadius = dp(preset.radius).toFloat(); setStroke(dp(1), shade(focus, 0.5f)) }
    }

    /** Tła elementów list – tworzone raz na wersję wyglądu, każdy widok dostaje kopię (szybsze budowanie ekranów, np. ustawień). */
    private val itemBgCache = HashMap<Boolean, Pair<Int, Drawable.ConstantState?>>()

    fun itemBg(border: Boolean = true): Drawable {
        val key = java.util.Objects.hash(version, Prefs.theme, focus, text, accent, color2("focus"), anim)
        itemBgCache[border]?.let { (v, st) -> if (v == key && st != null) return st.newDrawable() }
        val d = createItemBg(border)
        itemBgCache[border] = key to d.constantState
        return d
    }

    private fun createItemBg(border: Boolean): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusDrawable())
        addState(intArrayOf(android.R.attr.state_pressed), focusDrawable())
        addState(intArrayOf(android.R.attr.state_selected), focusDrawable())
        addState(intArrayOf(android.R.attr.state_activated), shape(withAlpha(accent, 0x45), if (border) 1 else 0, withAlpha(accent, 0x90)))
        addState(intArrayOf(), when (surface) {
            S_GLASS -> shape(withAlpha(Color.WHITE, 0x0E), if (border) 1 else 0, withAlpha(Color.WHITE, 0x14))
            S_XBOX -> shape(withAlpha(Color.WHITE, 0x0A))
            else -> if (border) shape(Color.TRANSPARENT, 1, withAlpha(text, 0x1C)) else shape(Color.TRANSPARENT)
        })
        setEnterFadeDuration(if (anim == 0) 0 else 90); setExitFadeDuration(if (anim == 0) 0 else 90)
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
        addState(intArrayOf(android.R.attr.state_activated), surfaceClassic(0xF0, maxOf(preset.radius, 12)).apply { setStroke(dp(2), withAlpha(accent, 0xC0)) })
        addState(intArrayOf(), (texturedSurface(shade(bg, -0.1f), 0xE0, maxOf(preset.radius, 12))
            ?: surfaceClassic(0xE0, maxOf(preset.radius, 12)).apply { setStroke(dp(1), withAlpha(text, 0x22)) }))
    }

    /** Przycina widok do zaokrąglonego prostokąta (logo, podgląd, kafelki) – obraz nie wychodzi poza ramkę. */
    fun roundClip(v: View, radiusPx: Float) {
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, radiusPx) }
        }
        v.clipToOutline = true
    }

    /** Promień zaokrąglenia logo kanałów wg motywu (ostre w Xbox, łagodne w pozostałych). */
    val logoRadius get() = dp(if (surface == S_XBOX) 1 else minOf(maxOf(preset.radius, 4), 10)).toFloat()

    /** Tło kafelka logo w pasku informacji wg stylu paska (null = logo bez kafelka). */
    fun infoLogoBg(): Drawable? {
        fun tile(r: Int, fill: Int, sw: Int = 0, sc: Int = 0) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(fill); if (sw > 0) setStroke(dp(sw), sc) }
        return when (infoStyle) {
            5 -> tile(16, withAlpha(text, 0x10)); 6 -> tile(18, withAlpha(Color.WHITE, 0x1A), 1, withAlpha(Color.WHITE, 0x22))
            7 -> tile(6, withAlpha(Color.WHITE, 0x0C), 1, withAlpha(Color.WHITE, 0x14)); 8 -> tile(12, withAlpha(Color.WHITE, 0x14), 1, withAlpha(accent, 0x90))
            9 -> tile(0, withAlpha(Color.WHITE, 0x0A), 2, withAlpha(focus, 0xC0)); else -> null
        }
    }

    /** Tło kafelka ikony (szybkie menu): koło w motywach z okrągłymi ikonami, w pozostałych tło elementu listy. */
    fun iconBoxBg(): Drawable {
        if (!geo.iconRound) return itemBg()
        fun oval(fill: Int, sw: Int = 0, sc: Int = 0) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); if (sw > 0) setStroke(dp(sw), sc) }
        val f = oval(focus, if (geo.glow) 3 else 0, withAlpha(accent, 0xB0))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), f); addState(intArrayOf(android.R.attr.state_pressed), f)
            addState(intArrayOf(), oval(withAlpha(Color.WHITE, 0x12), 1, withAlpha(Color.WHITE, 0x1C)))
        }
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
    fun surfaceBg(alpha: Int = 0xF0, radius: Int = maxOf(preset.radius, 12)): Drawable =
        texturedSurface(shade(bg, -0.1f), alpha, radius) ?: surfaceClassic(alpha, radius)

    private fun surfaceClassic(alpha: Int, radius: Int): GradientDrawable =
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
    else texturedSurface(panel, alpha, preset.radius) ?: if (preset.focusStyle == FocusStyle.GRADIENT)
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
        val g = geo; val room = if (g.zoomMax > 0.04f) 26 else 12
        val sx = 1f + minOf(g.zoomMax, dp(room).toFloat() / maxOf(w, 1)); val sy = 1f + minOf(g.zoomMax, dp(room * 2 / 3).toFloat() / maxOf(h, 1))
        val s = minOf(sx, sy)
        fun a(sc: Float, z: Float) = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, sc),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, sc), PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, z)).setDuration(g.zoomMs)
            .apply { if (g.overshoot > 0f && sc > 1f) interpolator = android.view.animation.OvershootInterpolator(g.overshoot) }
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
        // panele przycinają treść do własnej (zaokrąglonej) ramki – powiększone elementy, logo i obraz nie wychodzą poza panel
        if ("panel" in tags) { v.background = panelBg(); v.clipToOutline = true }
        if ("glass" in tags) { v.background = glassBg(); v.clipToOutline = true }
        if ("item" in tags) {
            v.background = itemBg(border = "noborder" !in tags)
            // treść elementu (logo, miniatury, tła) przycięta do jego zaokrąglonego kształtu – nic nie wychodzi poza kafelek
            if ("noclip" !in tags) roundClip(v, dp(preset.radius).toFloat())
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
    /** Otwarte okna (ekran + tytuł) – to samo okno nie otwiera się drugi raz jednocześnie
     *  (np. podwójne zdarzenie OK/dotknięcia uruchamiało dwa identyczne okna jedno na drugim). */
    private val openDialogs = java.util.WeakHashMap<androidx.appcompat.app.AlertDialog, Pair<String, Long>>()
    /** Duplikat = to samo okno otwarte w krótkim odstępie (podwójne zdarzenie). Okna otwierane kolejno przez użytkownika nie są blokowane. */
    private const val DUP_MS = 1000L

    private fun duplicateOf(key: String, except: androidx.appcompat.app.AlertDialog? = null): androidx.appcompat.app.AlertDialog? {
        val now = android.os.SystemClock.uptimeMillis()
        return openDialogs.entries.firstOrNull { (o, v) -> o !== except && v.first == key && o.isShowing && now - v.second < DUP_MS }?.key
    }

    /** Zamyka otwarte okna ekranu [c] (np. przed pokazaniem menu ikon z menu odtwarzania). */
    fun dismissOpenDialogs(c: android.content.Context) {
        val prefix = System.identityHashCode(c).toString() + "|"
        openDialogs.entries.filter { (d, v) -> v.first.startsWith(prefix) && d.isShowing }.map { it.key }.forEach { runCatching { it.dismiss() } }
    }

    private fun dialogKey(c: android.content.Context, title: CharSequence?) = System.identityHashCode(c).toString() + "|" + (title?.toString() ?: "")

    fun dialog(c: android.content.Context): androidx.appcompat.app.AlertDialog.Builder =
        object : androidx.appcompat.app.AlertDialog.Builder(c) {
            private var title: CharSequence? = null
            override fun setTitle(t: CharSequence?): androidx.appcompat.app.AlertDialog.Builder { title = t; return super.setTitle(t) }
            override fun setTitle(id: Int): androidx.appcompat.app.AlertDialog.Builder { title = c.getString(id); return super.setTitle(id) }
            override fun create(): androidx.appcompat.app.AlertDialog {
                val d = super.create()
                val key = dialogKey(c, title)
                d.setOnShowListener {
                    // drugie takie samo okno – zamykane od razu (zostaje pierwsze)
                    if (duplicateOf(key, d)?.let { o -> openDialogs[o]!!.second <= (openDialogs[d]?.second ?: Long.MAX_VALUE) } == true) { d.dismiss(); return@setOnShowListener }
                    if (d !in openDialogs) openDialogs[d] = key to android.os.SystemClock.uptimeMillis()
                    styleDialog(d)
                }
                return d
            }
            override fun show(): androidx.appcompat.app.AlertDialog {
                // to samo okno właśnie otwarte: nie tworzymy drugiego (bez mignięcia)
                val key = dialogKey(c, title)
                duplicateOf(key)?.let { return it }
                return super.show().also { openDialogs[it] = key to android.os.SystemClock.uptimeMillis() }
            }
        }

    fun dialogBg(): Drawable {
        // ramka okna wg motywu: ostra (Xbox), duże zaokrąglenia (iOS)
        val r = when (surface) { S_XBOX -> 2; S_GLASS -> 22; else -> maxOf(preset.radius, 14) }
        if (color2("panel") != null) return fill("panel").apply { setStroke(dp(1), withAlpha(accent, 0x90)); cornerRadius = dp(r).toFloat() }
        // szkło: okno półprzezroczyste (rozmycie tła pod oknem, gdy system je obsługuje)
        return texturedSurface(panel, if (geo.blur > 0 && surface != S_XBOX) 0xE4 else 0xFA, r, frame = true)
            ?: surfaceClassic(0xF6, r).apply { cornerRadius = dp(r).toFloat() }
    }

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
        // TV: 46% szerokości; telefon/tablet: do 92%, ale nie szerzej niż 560 dp (w poziomie okno nie rozciąga się na cały ekran)
        w.setLayout(if (wide) (dm.widthPixels * 0.46f).toInt() else minOf((dm.widthPixels * 0.92f).toInt(), dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setDimAmount(0.7f)
        if (anim != 0) w.setWindowAnimations(R.style.DialogAnim)
        // szkło: rozmyte tło pod oknem (Android 12+, gdy urządzenie obsługuje rozmycie między oknami)
        if (geo.blur > 0 && android.os.Build.VERSION.SDK_INT >= 31 &&
            (d.context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.isCrossWindowBlurEnabled == true) {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = dp(geo.blur) }
            w.setDimAmount(0.4f)
        }
        // okno nad odtwarzaczem pełnoekranowym nie wywołuje pasków systemu (obraz nie „skacze”)
        (d.ownerActivity ?: (d.context as? android.content.ContextWrapper)?.baseContext as? Activity)?.window?.let { aw ->
            if (aw.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // treść odsunięta od zaokrąglonej ramki – zaznaczenia i przyciski nie są ucinane na rogach
        w.decorView.setPadding(dp(10), dp(8), dp(10), dp(10))
        styleDialogTree(w.decorView)
        d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            setTextColor(Ui.accent); typeface = Ui.typeface(Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * fontScale * geo.headerScale); maxLines = 3
            // iOS: duży, wyśrodkowany tytuł; pozostałe – do lewej
            gravity = if (geo.titleCenter) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
            (parent as? android.widget.LinearLayout)?.gravity = if (geo.titleCenter) android.view.Gravity.CENTER else android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            if (geo.titleCenter) layoutParams = (layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply { width = 0; weight = 1f } ?: layoutParams
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
        d.findViewById<View>(androidx.appcompat.R.id.buttonPanel)?.let { bp ->
            bp.setPadding(dp(8), dp(6), dp(8), dp(6))
            // pasek przycisków (w AppCompat to ScrollView) bez paska przewijania i bez przycinania powiększonego przycisku
            bp.isVerticalScrollBarEnabled = false; bp.isHorizontalScrollBarEnabled = false; bp.overScrollMode = View.OVER_SCROLL_NEVER
            (bp as? ViewGroup)?.let { g -> g.clipToPadding = false; g.clipChildren = false
                (g.getChildAt(0) as? ViewGroup)?.let { bar -> bar.clipChildren = false; bar.clipToPadding = false } }
        }
        fixDialogScrollers(w.decorView)
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
                        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
                list != null -> if (!list.hasFocus()) { list.requestFocus(); if (list.checkedItemPosition < 0) list.setSelection(0) }
                else -> {
                    val f = w.decorView.findFocus()
                    val custom = f != null && f !is android.widget.Button && f.isFocusable && f !is ViewGroup && f !is android.widget.ScrollView
                    if (!custom) (d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.takeIf { it.isShown }
                        ?: d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.takeIf { it.isShown })?.requestFocus()
                }
            }
        }
        // okno nie wyższe niż ekran – przyciski na dole zawsze widoczne (treść przewija się);
        // sprawdzane przy każdej zmianie rozmiaru (np. lista odświeżona w oknie „na żywo”), nie tylko raz po otwarciu
        if (w.decorView.getTag(R.id.ui_clip) == null) {
            w.decorView.setTag(R.id.ui_clip, true)
            w.decorView.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
                val maxH = (v.resources.displayMetrics.heightPixels * 0.9f).toInt()
                if (b - t > maxH && w.attributes.height != maxH) v.post { w.setLayout(w.attributes.width, maxH) }
            }
        }
    }

    /**
     * Przewijana treść okna: pasek przewijania rysowany poza treścią (w zarezerwowanym odstępie), a nie na niej –
     * nie zasłania przycisków ani zaznaczeń; zaznaczony element przewijany do widoku z marginesem (pilot).
     */
    private fun fixDialogScrollers(v: View) {
        val scroller = v is android.widget.ScrollView || v is androidx.core.widget.NestedScrollView || v is android.widget.AbsListView
        if (scroller && v.id != androidx.appcompat.R.id.buttonPanel && v.getTag(R.id.zoom_listener) != "dlgscroll") {
            v.setTag(R.id.zoom_listener, "dlgscroll")
            v.scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
            v.isScrollbarFadingEnabled = true
            v.isVerticalFadingEdgeEnabled = true; v.setFadingEdgeLength(dp(16))
            if (v is android.widget.ScrollView) v.isSmoothScrollingEnabled = true
            if (v !is android.widget.AbsListView) (v as ViewGroup).let { it.clipToPadding = false
                // przycisk w przewijanej treści: przewinięcie tak, by był cały widoczny (z zapasem na powiększenie)
                v.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
                    if (now != null && now !== v && generateSequence(now.parent) { it.parent }.any { it === v }) {
                        val r = android.graphics.Rect(); now.getDrawingRect(r); it.offsetDescendantRectToMyCoords(now, r)
                        val gap = dp(12); val top = v.scrollY + v.paddingTop; val bottom = v.scrollY + v.height - v.paddingBottom
                        val dy = when { r.bottom + gap > bottom -> r.bottom + gap - bottom; r.top - gap < top -> r.top - gap - top; else -> 0 }
                        if (dy != 0) v.scrollBy(0, dy)
                    }
                } }
        }
        if (v is ViewGroup && v !is android.widget.AbsListView) for (i in 0 until v.childCount) fixDialogScrollers(v.getChildAt(i))
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

    /**
     * Przejścia między ekranami. Ekran pod spodem stoi nieruchomo ([R.anim.hold]) – wcześniej oba ekrany jednocześnie
     * zanikały/pojawiały się i w połowie animacji przez oba prześwitywała czerń (czarny ekran przy wejściu/wyjściu z ustawień i menu).
     */
    fun openTransition(a: Activity) {
        @Suppress("DEPRECATION")
        when (anim) {
            1 -> a.overridePendingTransition(R.anim.fade_in, R.anim.hold)
            2 -> a.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            3 -> a.overridePendingTransition(R.anim.zoom_in, R.anim.hold)
            4 -> a.overridePendingTransition(R.anim.float_in, R.anim.hold)
            5 -> a.overridePendingTransition(R.anim.spring_in_right, R.anim.parallax_out_left)
            6 -> a.overridePendingTransition(R.anim.snap_in, R.anim.hold)
            7 -> a.overridePendingTransition(R.anim.console_in, R.anim.hold)
            8 -> a.overridePendingTransition(R.anim.xbox_in, R.anim.hold)
            else -> a.overridePendingTransition(0, 0)
        }
    }

    fun closeTransition(a: Activity) {
        @Suppress("DEPRECATION")
        when (anim) {
            1 -> a.overridePendingTransition(R.anim.hold, R.anim.fade_out)
            2 -> a.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            3 -> a.overridePendingTransition(R.anim.hold, R.anim.zoom_out)
            4 -> a.overridePendingTransition(R.anim.hold, R.anim.float_out)
            5 -> a.overridePendingTransition(R.anim.parallax_in_left, R.anim.spring_out_right)
            6 -> a.overridePendingTransition(R.anim.hold, R.anim.snap_out)
            7 -> a.overridePendingTransition(R.anim.hold, R.anim.console_out)
            8 -> a.overridePendingTransition(R.anim.hold, R.anim.xbox_out)
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
        // szkło (iOS): sprężyste dojście do miejsca; pozostałe – hamowanie na końcu
        val interp = if (geo.overshoot > 0f) android.view.animation.OvershootInterpolator(geo.overshoot * 0.5f) else android.view.animation.DecelerateInterpolator(if (surface == S_XBOX) 2.4f else 1.6f)
        v.animate().translationX(0f).alpha(1f).setDuration(animDuration).setInterpolator(interp).withLayer().start()
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

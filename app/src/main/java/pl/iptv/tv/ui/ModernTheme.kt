package pl.iptv.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AlertDialog
import pl.iptv.tv.data.Prefs

/**
 * Motyw „Nowoczesny” (7.0 – zastępuje dawny „Nowoczesny”, to samo id „modern”: dotychczasowi użytkownicy dostają nowy wygląd).
 *
 * Styl serwisu VOD na telewizorze: głęboka czerń, pełnoekranowe tło zaznaczonego materiału wygaszane w lewo, pionowy pasek
 * ikon przy lewej krawędzi, nagłówki rzędów z czerwoną kropką, kafelki 16:9 / plakaty / kanały z paskiem postępu,
 * jasnoniebieska obwódka zaznaczenia, przyciski-kapsuły (niebieska „Odtwórz”, szare pozostałe).
 *
 * Tak jak X25 / PS5 to zwykły preset [Ui.PRESETS] (faktura [Ui.S_MODERN]) – ekrany na widokach (lista kanałów, odtwarzacz,
 * EPG, STube, Stremio, ustawienia, okna) dostają jego kolory, panele, zaznaczenie i tło [ModernBackdrop]. Dodatkowo ekran
 * główny [ModernHomeActivity] (Compose, [ModernThemeProvider]) i menu jako półprzezroczysty panel nakładkowy z prawej strony.
 * Logika aplikacji (TV, STube, Stremio, odtwarzacze, Room) bez zmian – funkcje uruchamiane przez istniejące ekrany.
 */
object ModernTheme {
    const val ID = "modern"
    /** Niebieski akcent (przycisk „Odtwórz”, zaznaczenie list). */
    val ACCENT = Color.parseColor("#2F6BFF")
    /** Jasnoniebieska obwódka zaznaczonego kafelka. */
    val RING = Color.parseColor("#D8E8FF")
    /** Czerwona kropka nagłówków i znacznik „na żywo”. */
    val LIVE = Color.parseColor("#E5233A")

    val active: Boolean get() = runCatching { Ui.surface == Ui.S_MODERN }.getOrDefault(false)

    /** Ekran główny jako ekran startowy (przy motywie Nowoczesny; w trybie launchera – zawsze przy tym motywie). */
    val homeOn: Boolean get() = active && (Prefs.modernHome || X25Theme.launcherHome)

    /** Włącza motyw (np. z ekranów głównych X25 / PS5). */
    fun enable() {
        if (active) return
        Ui.applyPreset(ID); Ui.changed()
    }

    fun openHome(a: Activity) {
        a.startActivity(Intent(a, ModernHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---------------- okna: panel nakładkowy ----------------

    /** Okno-menu (lista pozycji) jako panel nakładkowy z prawej; komunikaty i potwierdzenia – okna na środku. */
    fun isOverlay(d: AlertDialog): Boolean {
        if (!active || !Prefs.modernOverlay) return false
        val lv = d.listView
        return lv != null && (lv.adapter?.count ?: 0) > 0
    }

    /** Półprzezroczysta czerń (obraz pod spodem lekko prześwituje przy lewej krawędzi panelu), cienka jasna krawędź. */
    fun overlayBg(): Drawable = TexturedDrawable(Ui.withAlpha(Ui.shade(Ui.bg, -0.2f), 0xE8), Ui.withAlpha(Ui.shade(Ui.bg, -0.5f), 0xF4), 0f,
        stroke = Ui.withAlpha(Color.WHITE, 0x12), strokeW = Ui.dp(1).toFloat())

    /** Panel przy prawej krawędzi: TV – 36% szerokości, telefon – do 88% (najwyżej 440 dp). */
    fun layoutOverlay(w: Window, wide: Boolean) {
        val dm = w.context.resources.displayMetrics
        w.setGravity(Gravity.END or Gravity.TOP)
        w.setLayout(if (wide) (dm.widthPixels * 0.36f).toInt() else minOf((dm.widthPixels * 0.88f).toInt(), Ui.dp(440)), ViewGroup.LayoutParams.MATCH_PARENT)
        w.attributes = w.attributes.apply { x = 0; y = 0 }
    }

    /** Przyciski okien jak kapsuły ekranu głównego: szare, zaznaczony – niebieski z białym tekstem. */
    fun pill(focusedFill: Int): Drawable = android.graphics.drawable.StateListDrawable().apply {
        val r = Ui.dp(22).toFloat()
        fun pill(c: Int, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(c); if (stroke != 0) setStroke(Ui.dp(2), stroke) }
        addState(intArrayOf(android.R.attr.state_focused), pill(focusedFill, Ui.withAlpha(RING, 0xC0)))
        addState(intArrayOf(android.R.attr.state_pressed), pill(focusedFill))
        addState(intArrayOf(), pill(Ui.withAlpha(Color.WHITE, 0x22)))
    }

    fun textColors() = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(Color.WHITE, Color.WHITE, Ui.withAlpha(Color.WHITE, 0xE0)))

    fun navSound(c: Context) = X25Theme.navSound(c)
}

/**
 * Tło motywu Nowoczesny na ekranach z widokami: czerń z lekko granatową górą i delikatną niebieską poświatą w prawym górnym rogu.
 * Bez bitmap – dwa cieniowania (gradient liniowy + radialny) tworzone raz przy zmianie rozmiaru; rysowanie to dwa prostokąty.
 */
class ModernBackdrop(private val base: Int, private val accent: Int) : Drawable() {
    private val lin = Paint(Paint.DITHER_FLAG)
    private val rad = Paint(Paint.DITHER_FLAG)
    /** (7.1) druga, chłodna poświata przy lewym dolnym rogu i winieta – głębia jak w serwisach VOD, nadal bez bitmapy. */
    private val rad2 = Paint(Paint.DITHER_FLAG)
    private val vig = Paint(Paint.DITHER_FLAG)
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat()
        lin.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(Ui.blend(base, accent, 0.08f), base, Ui.shade(base, -0.55f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rad.shader = RadialGradient(b.left + w * 0.85f, b.top + h * 0.05f, maxOf(w, h) * 0.6f,
            intArrayOf(Ui.withAlpha(accent, 0x30), Ui.withAlpha(accent, 0x0C), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rad2.shader = RadialGradient(b.left + w * 0.05f, b.bottom - h * 0.05f, maxOf(w, h) * 0.45f,
            intArrayOf(Ui.withAlpha(Color.parseColor("#5B3BD6"), 0x1C), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        vig.shader = RadialGradient(b.exactCenterX(), b.exactCenterY(), maxOf(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Ui.withAlpha(Color.BLACK, 0x60)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        lin.alpha = alphaMul; rad.alpha = alphaMul; rad2.alpha = alphaMul; vig.alpha = alphaMul
        c.drawRect(bounds, lin); c.drawRect(bounds, rad); c.drawRect(bounds, rad2); c.drawRect(bounds, vig)
    }

    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { lin.colorFilter = cf; rad.colorFilter = cf; rad2.colorFilter = cf; vig.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

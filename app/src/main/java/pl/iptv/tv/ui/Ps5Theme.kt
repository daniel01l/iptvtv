package pl.iptv.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import pl.iptv.tv.data.Prefs

/**
 * Przełącznik motywu „PS5” (styl ekranu głównego konsoli: głęboki granat, czerń, świetliste błękity, tło „blueprint”).
 *
 * Tak jak X25 to zwykły preset [Ui.PRESETS] (faktura [Ui.S_PS5]) – wszystkie ekrany na widokach dostają jego kolory, panele,
 * ramkę zaznaczenia (biało-błękitna obwódka + powiększenie) i tło [Ps5Backdrop]. Dodatkowo: ekran główny [Ps5HomeActivity]
 * (Compose, [PS5ThemeProvider]), menu jako panel ustawień PS5 (pionowa lista przy lewej krawędzi), przełączniki „switch”
 * i podpowiedzi przycisków na dole ([Ps5Hints]). Logika aplikacji (TV, STube, Stremio, odtwarzacze, Room) bez zmian.
 */
object Ps5Theme {
    /** Inne niż usunięty w 6.1 „ps5” (stare ustawienie nadal prowadzi do Xbox – [Ui.REMOVED_THEMES]). */
    const val ID = "ps5dash"
    /** Świetlisty błękit (akcent, włączone przełączniki). */
    val GLOW = Color.parseColor("#3D8BFF")
    /** Biało-błękitna obwódka zaznaczenia. */
    val RING = Color.parseColor("#EAF4FF")

    val active: Boolean get() = runCatching { Ui.surface == Ui.S_PS5 }.getOrDefault(false)
    /**
     * Ekran główny PS5 jako ekran startowy (nad listą kanałów). W trybie launchera ([X25Theme.launcherHome]) – zawsze,
     * także przy motywach bez własnego pulpitu (Klasyczny, Xbox – w ich kolorach); przy X25 – pulpit X25, przy Nowoczesnym
     * z włączonym ekranem głównym – jego ekran ([ModernTheme.homeOn]).
     */
    val homeOn: Boolean get() = !X25Theme.active && !ModernTheme.homeOn && ((active && Prefs.ps5Home) || X25Theme.launcherHome)

    /** Włącza / wyłącza motyw; wyłączenie wraca do motywu sprzed włączenia (np. X25). */
    fun setEnabled(on: Boolean) {
        if (on == active) return
        if (on) Ui.applyPreset(ID)
        else Ui.applyPreset(Prefs.ps5PrevTheme.takeIf { it.isNotBlank() && it != ID } ?: "modern", restoring = true)
        Ui.changed()
    }

    /** Otwiera ekran główny (istniejący wraca na wierzch). */
    fun openHome(a: Activity) {
        a.startActivity(Intent(a, Ps5HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---------------- okna: panel ustawień ----------------

    /** Okno-menu (lista pozycji) jako panel ustawień PS5; komunikaty i potwierdzenia – okna na środku. */
    fun isPanel(d: AlertDialog): Boolean {
        if (!active || !Prefs.ps5Panel) return false
        val lv = d.listView
        return lv != null && (lv.adapter?.count ?: 0) > 0
    }

    /** Panel: nieprzezroczysty granat przechodzący w czerń (ekran pod spodem nie prześwituje), cienka jasna krawędź. */
    fun panelBg(): Drawable = TexturedDrawable(Ui.withAlpha(Ui.blend(Ui.panel, GLOW, 0.06f), 0xFF), Ui.withAlpha(Ui.shade(Ui.bg, -0.4f), 0xFF), 0f,
        stroke = Ui.withAlpha(Color.WHITE, 0x14), strokeW = Ui.dp(1).toFloat())

    /** Panel przy lewej krawędzi: TV – 40% szerokości, telefon – do 88% (najwyżej 460 dp). */
    fun layoutPanel(w: Window, wide: Boolean) {
        val dm = w.context.resources.displayMetrics
        w.setGravity(Gravity.START or Gravity.TOP)
        w.setLayout(if (wide) (dm.widthPixels * 0.40f).toInt() else minOf((dm.widthPixels * 0.88f).toInt(), Ui.dp(460)), ViewGroup.LayoutParams.MATCH_PARENT)
        w.attributes = w.attributes.apply { x = 0; y = 0 }
    }

    /** Przyciski okien: zaznaczony – granatowy tekst na białej kapsule (jak na konsoli). */
    fun buttonText(primary: Boolean) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(INK, INK, if (primary) Color.WHITE else Ui.text2))
    val INK = Color.parseColor("#0A1230")

    /** Pasek podpowiedzi na dole okna (raz na okno). */
    fun addDialogHints(d: AlertDialog) {
        // parentPanel (AlertDialogLayout) układa tylko własne panele – owinięty w pionowy układ: okno (waga 1, kurczy się
        // w panelu na całą wysokość) + pasek podpowiedzi pod nim; przyciski i lista zostają widoczne
        val panel = d.findViewById<ViewGroup>(androidx.appcompat.R.id.parentPanel) ?: return
        val host = panel.parent as? ViewGroup ?: return
        if (host.findViewWithTag<View>(HINT_TAG) != null) return
        val list = d.listView?.let { (it.adapter?.count ?: 0) > 0 } == true
        val hints = Ps5Hints(d.context, if (list) Ps5Hints.LIST else Ps5Hints.DIALOG).apply { tag = HINT_TAG; isFocusable = false }
        val idx = host.indexOfChild(panel); val lp = panel.layoutParams
        // panel na całą wysokość: okno wypełnia panel (przyciski przy dolnej krawędzi), podpowiedzi na samym dole
        val full = isPanel(d)
        host.removeView(panel)
        val col = LinearLayout(d.context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(panel, LinearLayout.LayoutParams(-1, if (full) 0 else -2, 1f))
        col.addView(hints, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(4) })
        host.addView(col, idx, if (full) ViewGroup.LayoutParams(-1, -1) else lp)
    }
    const val HINT_TAG = "ps5hints"

    /** Ekrany ustawień (Ustawienia, wtyczki Stremio): pasek podpowiedzi w prawym dolnym rogu. */
    fun attachScreenHints(root: FrameLayout, items: List<Pair<String, String>> = Ps5Hints.SCREEN) {
        root.findViewWithTag<View>(HINT_TAG)?.let { root.removeView(it) }
        if (!active) return
        root.addView(Ps5Hints(root.context, items).apply { tag = HINT_TAG; isFocusable = false },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.dp(28), Ui.dp(10)) })
    }

    fun navSound(c: Context) = X25Theme.navSound(c)
}

/**
 * Urządzenie sterujące, do którego dopasowane są podpowiedzi przycisków ([Ps5Hints]): pilot TV (OK, WSTECZ, strzałki, MENU),
 * pad (A, B, krzyżak, MENU) albo ekran dotykowy (podpowiedzi ukryte). Wykrywane z konfiguracji urządzenia, potem z ostatniego
 * użytego wejścia ([onKey] / [onTouch] z [BaseActivity]) – podłączenie pada i naciśnięcie przycisku od razu zmienia symbole.
 */
object Ps5Input {
    enum class Mode { REMOTE, GAMEPAD, TOUCH }

    @Volatile private var used: Mode? = null
    private val views = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Ps5Hints, Boolean>())

    fun mode(c: Context): Mode = used ?: detect(c)

    /** Telewizor / urządzenie z D-padem – pilot; z ekranem dotykowym – dotyk; inaczej pilot. */
    fun detect(c: Context): Mode {
        val cfg = c.resources.configuration
        val tv = (cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (tv || cfg.navigation == android.content.res.Configuration.NAVIGATION_DPAD) return Mode.REMOTE
        return if (c.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) Mode.TOUCH else Mode.REMOTE
    }

    /** Klawisz: z pada (źródło gamepad / joystick albo przycisk pada) – symbole pada, inaczej pilota. */
    fun onKey(e: android.view.KeyEvent) {
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return
        val pad = e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) || e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) ||
            android.view.KeyEvent.isGamepadButton(e.keyCode)
        set(if (pad) Mode.GAMEPAD else Mode.REMOTE)
    }

    fun onTouch() = set(Mode.TOUCH)

    fun set(m: Mode?) {
        if (m == used) return
        used = m
        synchronized(views) { views.toList() }.forEach { v -> v.post { v.requestLayout(); v.invalidate() } }
    }

    internal fun register(v: Ps5Hints) = synchronized(views) { views += v }
    internal fun unregister(v: Ps5Hints) = synchronized(views) { views -= v }
}

/**
 * Podpowiedzi przycisków (jak na dole menu konsoli), zgodne z urządzeniem sterującym ([Ps5Input]): symbol rysowany na płótnie
 * (bez znaków spoza czcionki) i opis. Rodzaje: [X] wybór (pilot „OK”, pad „A”), [O] wstecz (pilot – strzałka powrotu,
 * pad „B”), [NAV] / [LR] strzałki, [MENU] opcje. Przy ekranie dotykowym ukryte.
 */
class Ps5Hints(c: Context, val items: List<Pair<String, String>>) : View(c) {
    companion object {
        const val X = "x"; const val O = "o"; const val NAV = "nav"; const val MENU = "menu"; const val LR = "lr"
        val LIST = listOf(X to "Wybierz", O to "Wstecz", NAV to "Nawiguj")
        val DIALOG = listOf(X to "Wybierz", O to "Wstecz")
        val SCREEN = listOf(X to "Wybierz", O to "Wstecz", NAV to "Nawiguj")
        val HOME = listOf(X to "Otwórz", O to "Wyjście", LR to "Przeglądaj", MENU to "Opcje")
    }

    val mode get() = Ps5Input.mode(context)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Ps5Input.register(this) }
    override fun onDetachedFromWindow() { Ps5Input.unregister(this); super.onDetachedFromWindow() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = Ui.dp(9).toFloat()
        typeface = android.graphics.Typeface.DEFAULT_BOLD }

    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = Ui.dp(14).toFloat(); typeface = Ui.typeface(android.graphics.Typeface.NORMAL) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(1.6f).toFloat().coerceAtLeast(1.5f); color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val icon = Ui.dp(20).toFloat()
    private val gap = Ui.dp(8).toFloat()
    private val sep = Ui.dp(26).toFloat()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        if (mode == Ps5Input.Mode.TOUCH) { setMeasuredDimension(0, 0); return }
        var w = paddingLeft + paddingRight.toFloat()
        items.forEachIndexed { i, (_, l) -> w += icon + gap + txt.measureText(l) + if (i < items.lastIndex) sep else 0f }
        val h = (paddingTop + paddingBottom + maxOf(icon, txt.textSize * 1.4f)).toInt() + Ui.dp(8)
        setMeasuredDimension(resolveSize(w.toInt() + Ui.dp(16), wSpec), resolveSize(h, hSpec))
    }

    override fun onDraw(c: Canvas) {
        if (mode == Ps5Input.Mode.TOUCH) return
        // do prawej (jak na konsoli), w wąskim widoku od lewej
        var total = 0f
        items.forEachIndexed { i, (_, l) -> total += icon + gap + txt.measureText(l) + if (i < items.lastIndex) sep else 0f }
        var x = maxOf(paddingLeft.toFloat() + Ui.dp(8), width - paddingRight - total - Ui.dp(8))
        val cy = height / 2f
        for ((i, it) in items.withIndex()) {
            symbol(c, it.first, x + icon / 2, cy)
            x += icon + gap
            c.drawText(it.second, x, cy - (txt.descent() + txt.ascent()) / 2, txt)
            x += txt.measureText(it.second) + if (i < items.lastIndex) sep else 0f
        }
    }

    private fun symbol(c: Canvas, kind: String, cx: Float, cy: Float) {
        val r = icon / 2 - ring.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        val k = r * 0.42f
        val pad = mode == Ps5Input.Mode.GAMEPAD
        fun letter(s: String) = c.drawText(s, cx, cy - (label.descent() + label.ascent()) / 2, label)
        when (kind) {
            // pilot: środkowy przycisk „OK”; pad: przycisk A (wybór w Androidzie)
            Ps5Hints.X -> letter(if (pad) "A" else "OK")
            // pilot: strzałka powrotu (WSTECZ); pad: przycisk B
            Ps5Hints.O -> if (pad) letter("B") else backArrow(c, cx, cy, k)
            Ps5Hints.NAV -> { tri(c, cx, cy - k * 1.1f, k * 0.8f, true); tri(c, cx, cy + k * 1.1f, k * 0.8f, false) }
            Ps5Hints.LR -> { triH(c, cx - k * 1.1f, cy, k * 0.8f, true); triH(c, cx + k * 1.1f, cy, k * 0.8f, false) }
            Ps5Hints.MENU -> for (d in -1..1) c.drawLine(cx - k, cy + d * k * 0.75f, cx + k, cy + d * k * 0.75f, ring)
        }
    }

    /** Zakręcona strzałka powrotu (jak na przycisku WSTECZ pilota). */
    private fun backArrow(c: Canvas, cx: Float, cy: Float, k: Float) {
        val oval = RectF(cx - k * 0.6f, cy - k * 0.9f, cx + k * 1.2f, cy + k * 0.9f)
        c.drawArc(oval, -90f, 180f, false, ring)
        c.drawLine(cx - k * 1.1f, cy - k * 0.9f, cx + k * 0.3f, cy - k * 0.9f, ring)
        c.drawPath(android.graphics.Path().apply {
            moveTo(cx - k * 1.3f, cy - k * 0.9f); lineTo(cx - k * 0.6f, cy - k * 1.45f); lineTo(cx - k * 0.6f, cy - k * 0.35f); close()
        }, fill)
        c.drawLine(cx + k * 0.3f, cy + k * 0.9f, cx - k * 0.4f, cy + k * 0.9f, ring)
    }

    private fun tri(c: Canvas, cx: Float, cy: Float, s: Float, up: Boolean) = c.drawPath(android.graphics.Path().apply {
        val d = if (up) -1 else 1
        moveTo(cx, cy + d * s * 0.6f); lineTo(cx - s * 0.75f, cy - d * s * 0.35f); lineTo(cx + s * 0.75f, cy - d * s * 0.35f); close()
    }, fill)

    private fun triH(c: Canvas, cx: Float, cy: Float, s: Float, left: Boolean) = c.drawPath(android.graphics.Path().apply {
        val d = if (left) -1 else 1
        moveTo(cx + d * s * 0.6f, cy); lineTo(cx - d * s * 0.35f, cy - s * 0.75f); lineTo(cx - d * s * 0.35f, cy + s * 0.75f); close()
    }, fill)
}

/** Przełącznik „switch” (tor w kształcie kapsuły z białą gałką) – włączony: świetlisty błękit, wyłączony: szary. */
class Ps5Switch(var on: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun getIntrinsicWidth() = Ui.dp(46)
    override fun getIntrinsicHeight() = Ui.dp(26)
    override fun draw(c: Canvas) {
        val b = bounds
        r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val rad = r.height() / 2
        p.style = Paint.Style.FILL
        p.color = if (on) Ps5Theme.GLOW else Ui.withAlpha(Color.WHITE, 0x38)
        c.drawRoundRect(r, rad, rad, p)
        p.color = if (on) Color.WHITE else Ui.withAlpha(Color.WHITE, 0xC8)
        val k = rad - Ui.dp(3)
        c.drawCircle(if (on) r.right - rad else r.left + rad, r.centerY(), k, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Tło PS5 „blueprint”: głęboki granat z błękitną poświatą, siatka kreślarska (drobna i główna), znaczniki „+” na przecięciach,
 * linie wymiarowe i abstrakcyjne rysunki techniczne (okręgi z podziałką, obrys urządzenia) – wszystko cienkimi liniami.
 *
 * Wydajność jak [X25Backdrop]: warstwy rysowane raz do bitmapy w połowie rozdzielczości (wspólny [cache] wg rozmiaru
 * i kolorów), każda klatka okna to jedno kopiowanie bitmapy – zero pracy przy przejściach i fokusie.
 */
class Ps5Backdrop(private val base: Int, private val accent: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var bmp: android.graphics.Bitmap? = null
    private val dst = Rect()
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        dst.set(b)
        bmp = if (b.isEmpty) null else render(b.width(), b.height(), base, accent)
    }

    override fun draw(c: Canvas) {
        val b = bmp ?: return
        paint.alpha = alphaMul
        c.drawBitmap(b, null, dst, paint)
    }

    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private data class Key(val w: Int, val h: Int, val base: Int, val accent: Int)
        private var cacheKey: Key? = null
        private var cache: android.graphics.Bitmap? = null
        /** Liczba renderów (testy: kolejne ekrany używają tej samej bitmapy). */
        @Volatile var renders = 0; private set

        @Synchronized fun render(w: Int, h: Int, base: Int, accent: Int): android.graphics.Bitmap {
            val k = Key(w, h, base, accent)
            cache?.takeIf { cacheKey == k && !it.isRecycled }?.let { return it }
            val bw = maxOf(1, w / 2); val bh = maxOf(1, h / 2)
            val out = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            draw(Canvas(out), bw.toFloat(), bh.toFloat(), base, accent)
            renders++
            cacheKey = k; cache = out
            return out
        }

        private fun draw(c: Canvas, w: Float, h: Float, base: Int, accent: Int) {
            val p = Paint(Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)
            // granat: jaśniejszy u góry, czerń przy dolnej krawędzi
            val top = Ui.blend(base, accent, 0.22f)
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(top, Ui.blend(base, accent, 0.08f), Ui.shade(base, -0.6f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            // świetlista poświata (prawa górna część) i chłodny błysk z lewej
            p.shader = RadialGradient(w * 0.72f, h * 0.30f, maxOf(w, h) * 0.6f,
                intArrayOf(Ui.withAlpha(accent, 0x5A), Ui.withAlpha(accent, 0x18), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w * 0.05f, h * 0.55f, maxOf(w, h) * 0.35f,
                intArrayOf(Ui.withAlpha(Ui.shade(accent, 0.2f), 0x2C), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null

            val line = Ui.shade(accent, 0.45f)
            val cell = maxOf(8f, w / 64f)
            p.style = Paint.Style.STROKE
            // siatka drobna
            p.strokeWidth = 1f; p.color = Ui.withAlpha(line, 0x0E)
            var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += cell }
            var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, p); y += cell }
            // siatka główna (co 6 pól)
            p.color = Ui.withAlpha(line, 0x22)
            x = 0f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += cell * 6 }
            y = 0f; while (y < h) { c.drawLine(0f, y, w, y, p); y += cell * 6 }
            // znaczniki „+” na wybranych przecięciach
            p.color = Ui.withAlpha(Color.WHITE, 0x40)
            val k = cell * 0.6f
            var i = 0
            x = cell * 6; while (x < w) {
                y = cell * 6; while (y < h) {
                    if ((i++ % 3) == 0) { c.drawLine(x - k, y, x + k, y, p); c.drawLine(x, y - k, x, y + k, p) }
                    y += cell * 6
                }
                x += cell * 6
            }
            // rysunki techniczne – cienkie, półprzezroczyste
            p.color = Ui.withAlpha(line, 0x48); p.strokeWidth = maxOf(1f, w / 900f)
            dial(c, p, w * 0.80f, h * 0.30f, h * 0.16f)
            dial(c, p, w * 0.62f, h * 0.34f, h * 0.09f)
            // obrys „urządzenia” z przyciskami (abstrakcyjny, bez znaków towarowych)
            val dev = RectF(w * 0.55f, h * 0.12f, w * 0.96f, h * 0.52f)
            c.drawRoundRect(dev, dev.height() * 0.35f, dev.height() * 0.35f, p)
            c.drawRoundRect(RectF(dev.left + dev.width() * 0.32f, dev.top + dev.height() * 0.12f, dev.right - dev.width() * 0.32f, dev.top + dev.height() * 0.42f),
                dev.height() * 0.06f, dev.height() * 0.06f, p)
            // linie wymiarowe z kreskami na końcach
            p.color = Ui.withAlpha(line, 0x38)
            dimension(c, p, dev.left, dev.top - cell * 1.5f, dev.right, dev.top - cell * 1.5f, cell * 0.5f)
            dimension(c, p, dev.right + cell * 1.2f, dev.top, dev.right + cell * 1.2f, dev.bottom, cell * 0.5f)
            dimension(c, p, w * 0.06f, h * 0.70f, w * 0.40f, h * 0.70f, cell * 0.5f)
            // przerywane linie pomocnicze
            p.pathEffect = DashPathEffect(floatArrayOf(cell * 0.6f, cell * 0.5f), 0f)
            p.color = Ui.withAlpha(line, 0x26)
            c.drawLine(dev.centerX(), dev.top - cell * 3, dev.centerX(), h * 0.62f, p)
            c.drawLine(w * 0.45f, dev.centerY(), w, dev.centerY(), p)
            p.pathEffect = null
            // przyciemnienie dolnej części (czytelność kafelków)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, h * 0.55f, 0f, h, intArrayOf(Color.TRANSPARENT, Ui.withAlpha(Color.BLACK, 0x70)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.55f, w, h, p)
            p.shader = null
        }

        /** Okrąg z podziałką (jak tarcza na rysunku technicznym). */
        private fun dial(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
            c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * 0.72f, p)
            for (t in 0 until 24) {
                val a = Math.toRadians(t * 15.0)
                val l = if (t % 6 == 0) 0.82f else 0.9f
                c.drawLine(cx + (r * l * Math.cos(a)).toFloat(), cy + (r * l * Math.sin(a)).toFloat(),
                    cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(), p)
            }
        }

        private fun dimension(c: Canvas, p: Paint, x1: Float, y1: Float, x2: Float, y2: Float, tick: Float) {
            c.drawLine(x1, y1, x2, y2, p)
            if (y1 == y2) { c.drawLine(x1, y1 - tick, x1, y1 + tick, p); c.drawLine(x2, y2 - tick, x2, y2 + tick, p) }
            else { c.drawLine(x1 - tick, y1, x1 + tick, y1, p); c.drawLine(x2 - tick, y2, x2 + tick, y2, p) }
        }
    }
}

package pl.iptv.tv.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import pl.iptv.tv.data.Prefs

/**
 * Przełącznik motywu „X25 Dashboard” (styl rocznicowej konsoli: grafit, czerń, neonowa zieleń).
 *
 * Motyw to zwykły preset [Ui.PRESETS] o fakturze [Ui.S_X25] – wszystkie ekrany na widokach (menu, STube, odtwarzacz, EPG,
 * wyszukiwarka, ustawienia, okna) dostają jego kolory, akryl, ramkę zaznaczenia i tło z [Ui]. Dodatkowo:
 * pulpit z kafelkami ([X25DashboardActivity], Jetpack Compose), menu jako panel boczny „Guide”, dźwięki nawigacji.
 * Wyłączenie przywraca poprzedni motyw – logika aplikacji się nie zmienia.
 */
object X25Theme {
    const val ID = "x25"
    /** Żółtozielony pierścień przycisku Ustawień na pulpicie. */
    val RING = Color.parseColor("#D4FF3A")

    val active: Boolean get() = runCatching { Ui.surface == Ui.S_X25 }.getOrDefault(false)
    /** Pulpit X25 jako ekran startowy (nad listą kanałów); w trybie launchera – zawsze przy motywie X25. */
    val ownDashboard: Boolean get() = active && (Prefs.x25Dashboard || launcherHome)
    /**
     * Ekran startowy w stylu konsoli: pulpit X25 albo ekran główny PS5 ([Ps5Theme.homeOn]; w trybie launchera także przy
     * pozostałych motywach). Lista kanałów, WSTECZ z ekranów głównych i HOME otwierają wtedy ten ekran ([openDashboard]).
     */
    val dashboardOn: Boolean get() = ownDashboard || ModernTheme.homeOn || Ps5Theme.homeOn

    /**
     * Tryb launchera: aplikacja jest domyślnym ekranem głównym urządzenia. Wtedy HOME zawsze otwiera pulpit (pełnoprawny
     * launcher: aplikacje, ustawienia systemu, telewizja), niezależnie od opcji „pulpit jako ekran startowy”.
     * Odświeżane przy starcie i powrocie na ekrany ([refreshLauncher]) – zapytania do PackageManagera nie przy każdym odczycie.
     */
    @Volatile var launcherHome = false
    /** Testy: wymuszony tryb launchera (null – stan z systemu). */
    @Volatile var launcherForTest: Boolean? = null
    fun refreshLauncher(c: Context) {
        launcherHome = launcherForTest ?: runCatching { SystemTools.isLauncherEnabled(c) && SystemTools.isDefaultHome(c) }.getOrDefault(false)
    }

    /** Włącza / wyłącza motyw; wyłączenie wraca do motywu sprzed włączenia. */
    fun setEnabled(on: Boolean) {
        if (on == active) return
        if (on) Ui.applyPreset(ID)
        else Ui.applyPreset(Prefs.x25PrevTheme.takeIf { it.isNotBlank() && it != ID } ?: "modern", restoring = true)
        Ui.changed()
    }

    /** Motyw właśnie włączony (lista kanałów otwiera wtedy pulpit przy powrocie z Ustawień). */
    @Volatile var justEnabled = false
    fun consumeJustEnabled(): Boolean = justEnabled.also { justEnabled = false }

    /** Otwiera pulpit motywu (X25 albo ekran główny PS5; istniejący wraca na wierzch – bez budowania od nowa). */
    fun openDashboard(a: Activity) { a.startActivity(dashboardIntent(a)) }

    /** Ekran pulpitu: X25 przy motywie X25, ekran główny Nowoczesny przy motywie Nowoczesny, w pozostałych przypadkach PS5. */
    fun dashboardIntent(c: Context): android.content.Intent =
        android.content.Intent(c, when { active -> X25DashboardActivity::class.java; ModernTheme.homeOn -> ModernHomeActivity::class.java
            else -> Ps5HomeActivity::class.java })
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // ---------------- okna: panel boczny „Guide” ----------------

    /** Okno-menu (lista pozycji) wysuwane jako panel boczny; komunikaty i potwierdzenia zostają prostokątnymi oknami na środku. */
    fun isGuide(d: AlertDialog): Boolean {
        if (!active || !Prefs.x25Guide) return false
        val lv = d.listView ?: d.window?.decorView?.let { findList(it) }
        return lv != null && (lv.adapter?.count ?: 0) > 0
    }

    private fun findList(v: View): ListView? {
        if (v is ListView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findList(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Ciemny, półprzezroczysty panel na całą wysokość z zieloną linią u góry i cienką krawędzią. */
    fun guideBg(): Drawable = TexturedDrawable(Ui.withAlpha(Ui.blend(Ui.panel, Ui.accent, 0.04f), 0xF2), Ui.withAlpha(Ui.shade(Ui.bg, -0.3f), 0xF6), 0f,
        stroke = Ui.withAlpha(Color.WHITE, 0x16), strokeW = Ui.dp(1).toFloat(), topBar = Ui.accent)

    /** Panel przy lewej krawędzi: TV – 34% szerokości, telefon – do 86% (najwyżej 420 dp). */
    fun layoutGuide(w: Window, wide: Boolean) {
        val dm = w.context.resources.displayMetrics
        w.setGravity(Gravity.START or Gravity.TOP)
        w.setLayout(if (wide) (dm.widthPixels * 0.34f).toInt() else minOf((dm.widthPixels * 0.86f).toInt(), Ui.dp(420)), ViewGroup.LayoutParams.MATCH_PARENT)
        w.attributes = w.attributes.apply { x = 0; y = 0 }
    }

    /** Przyciski okien: zaznaczony – ciemny tekst na neonowej zieleni (czytelny kontrast). */
    fun buttonText(primary: Boolean) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(DARK_ON_GREEN, DARK_ON_GREEN, if (primary) Ui.accent else Ui.text))
    val DARK_ON_GREEN = Color.parseColor("#04140A")

    // ---------------- dźwięki nawigacji ----------------

    /** Krótki dźwięk systemowy przy przejściu fokusu pilotem (bez własnych plików – niska latencja, zero pamięci). */
    fun attachSounds(a: Activity) {
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            if (old != null && now != null && !now.isInTouchMode) navSound(a)
        }
    }

    private var lastSound = 0L
    fun navSound(c: Context, fx: Int = AudioManager.FX_FOCUS_NAVIGATION_RIGHT) {
        val on = (active && Prefs.x25Sounds) || (Ps5Theme.active && Prefs.ps5Sounds)
        if (!on || mediaPlaying()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSound < 45) return              // szybkie przewijanie – bez „trzeszczenia”
        lastSound = now
        runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(fx, 0.3f) }
    }

    fun selectSound(c: Context) = navSound(c, AudioManager.FX_KEY_CLICK)

    /**
     * Gra kanał TV lub film STube: bez dźwięków nawigacji – dźwięk systemowy otwiera osobny strumień audio, a na wielu
     * dekoderach TV (wyjście HDMI) przełącza to mikser i szarpie odtwarzany dźwięk / obraz.
     */
    private fun mediaPlaying(): Boolean = runCatching {
        Playback.player?.let { it.isPlaying || it.playWhenReady && Playback.active } == true || YtEngine.player?.isPlaying == true
    }.getOrDefault(false)
}

/**
 * Tło X25: warstwowy, ciemnozielony gradient, dwie poświaty (radialne) i ukośna smuga światła.
 *
 * Wydajność: warstwy rysowane są raz do bitmapy w połowie rozdzielczości (gładkie gradienty – skalowanie niewidoczne),
 * wspólnej dla wszystkich ekranów ([cache] wg rozmiaru i kolorów). Każda klatka okna to jedno kopiowanie bitmapy
 * zamiast czterech pełnoekranowych cieniowań – mniej pracy GPU przy przejściach i podczas odtwarzania w tle.
 */
class X25Backdrop(private val base: Int, private val accent: Int) : Drawable() {
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
            val top = Ui.blend(Ui.shade(base, 0.02f), accent, 0.09f)
            val p = Paint(Paint.DITHER_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(top, base, Ui.shade(base, -0.5f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w * 0.66f, h * 0.34f, maxOf(w, h) * 0.55f,
                intArrayOf(Ui.withAlpha(accent, 0x46), Ui.withAlpha(accent, 0x14), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w * 0.08f, h * 0.95f, maxOf(w, h) * 0.45f,
                intArrayOf(Ui.withAlpha(Ui.shade(accent, -0.45f), 0x50), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            // ukośna smuga światła (jak promień zza okna) – gradient w poprzek pasa
            val path = android.graphics.Path().apply {
                moveTo(w * 0.78f, 0f); lineTo(w * 0.93f, 0f); lineTo(w * 0.70f, h); lineTo(w * 0.48f, h); close()
            }
            p.isAntiAlias = true
            p.shader = LinearGradient(w * 0.6f, 0f, w * 0.9f, h * 0.3f,
                intArrayOf(Color.TRANSPARENT, Ui.withAlpha(Color.WHITE, 0x0C), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
    }
}

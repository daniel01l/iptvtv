package pl.iptv.tv.ui.theme

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import pl.iptv.tv.ui.ModernTheme
import pl.iptv.tv.ui.Ui

/**
 * (7.17) „Obsidian Stream” dla ekranów na widokach (Kanały/EPG, STube, Ustawienia): włączony razem z motywem Nowoczesny
 * ([active]) – pozostałe motywy wyglądają jak dotąd. Kolory jako ARGB i gotowe tła (pigułki, szkło, panele).
 */
object ObsidianViews {
    /** Obsidian działa w motywie Nowoczesny (paleta presetu = tokeny Obsidian). */
    val active: Boolean get() = runCatching { ModernTheme.active }.getOrDefault(false)

    /**
     * (v2) Kolory zależne od wariantu: Nowoczesny – stałe „Obsidian Stream” (7.17), Nowoczesny v2 – tokeny [ObsidianGlass]
     * (krycie powierzchni wg „Przezroczystość UI”). Dawniej `const val` – teraz odczyt przy każdym użyciu (wszystkie ekrany
     * na widokach: Live TV, odtwarzacz, EPG, wyszukiwarki, Edytor, STube, Stremio, Ustawienia dostają paletę v2 bez zmian w kodzie).
     */
    private val v2: Boolean get() = pl.iptv.tv.ui.ModernV2.active
    private val G = ObsidianGlass
    private fun glassAlpha(rgb: Int, panel: Boolean): Int {
        val o = ObsidianGlass.Opacity.current(); return G.withAlpha(rgb, if (panel) o.panelAlpha else o.surfaceAlpha)
    }

    val BG: Int get() = if (v2) G.BG_TOP else 0xFF080A0F.toInt()
    val SURFACE_GLASS: Int get() = if (v2) glassAlpha(G.SURFACE_TOP, false) else 0xD9121722.toInt()
    val SURFACE_ELEVATED: Int get() = if (v2) glassAlpha(G.SURFACE_BOTTOM, true) else 0xEB1A2233.toInt()
    const val ACCENT = 0xFF3B82F6.toInt()
    val ACCENT_GLOW: Int get() = if (v2) G.FOCUS_GLOW else 0x5960A5FA
    const val LIVE = 0xFFEF4444.toInt()
    val TEXT: Int get() = if (v2) G.TEXT else 0xFFF8FAFC.toInt()
    const val TEXT2 = 0xFF94A3B8.toInt()
    /** Panele Ustawień: `#0F141F` 90 % (v2 – szkło wg „Przezroczystość UI”). */
    val PANEL_90: Int get() = if (v2) glassAlpha(G.SURFACE_BOTTOM, true) else 0xE60F141F.toInt()
    /** Okna dialogowe i panele nakładkowe: `#0F141F` 92 % (v2 – szkło wg „Przezroczystość UI”). */
    val PANEL_92: Int get() = if (v2) glassAlpha(G.SURFACE_BOTTOM, true) else 0xEB0F141F.toInt()
    /** Przyciemnienie ekranu pod oknem dialogowym (scrim 75 %; v2 – 72 %). */
    val DIALOG_DIM: Float get() = if (v2) G.DIALOG_DIM else 0.75f
    /** Szklane powierzchnie (panele list): `SurfaceGlass` → `SurfaceElevated`. */
    val GLASS_TOP: Int get() = if (v2) glassAlpha(G.SURFACE_TOP, false) else 0xD9121722.toInt()
    val GLASS_BOTTOM: Int get() = if (v2) glassAlpha(G.SURFACE_BOTTOM, true) else 0xEB1A2233.toInt()

    /** Tło nakładki nad obrazem (kapsuły i panele w odtwarzaczach): Obsidian – panel 92 %, inne motywy – [fallback]. */
    fun overlay(fallback: Int): Int = if (active) (if (v2) G.OVERLAY_FILL else PANEL_92) else fallback   // (v2) V2GlassDialog 72 %
    /** Przyciemnienie treści pod panelem: Obsidian – scrim 75 % (`#080A0F`; v2 – granat `#070B14`), inne motywy – [fallback]. */
    fun scrim(fallback: Int): Int = if (active) (if (v2) G.withAlpha(G.BG_TOP, 0xB8) else 0xBF080A0F.toInt()) else fallback

    /** Okno dialogowe: panel 92 % z krawędzią specular, zaokrąglenie 16 dp. */
    fun dialogPanel(): Drawable = if (pl.iptv.tv.ui.ModernV2.active) ObsidianGlass.dialogPanel() else round(PANEL_92, Ui.dp(RADIUS_DP).toFloat(), Ui.dp(1), SPECULAR)

    /**
     * Przełącznik w kolorze akcentu (włączony – tor `AccentPrimary`, gałka biała po prawej; wyłączony – szary tor, gałka po lewej).
     * Rysowany (bez bitmap), rozmiar 44×24 dp.
     */
    class Switch(var on: Boolean) : Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        override fun getIntrinsicWidth() = Ui.dp(44)
        override fun getIntrinsicHeight() = Ui.dp(24)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val h = b.height().toFloat(); val rad = h / 2
            r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            if (v2) { G.drawSwitch(c, b, on, p, r); return }      // (v2) niebieska kapsuła #2F80ED → #38BDF8 / ciemnoszara
            p.color = if (on) ACCENT else 0x3394A3B8; c.drawRoundRect(r, rad, rad, p)
            p.color = if (on) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt()
            val cx = if (on) b.right - rad else b.left + rad
            c.drawCircle(cx, b.top + rad, rad - Ui.dp(3), p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    /** Przyciemnienie kanału grającego w tle Ustawień: 65 %. */
    const val VIDEO_DIM_65 = 0xA6000000.toInt()
    val SPECULAR: Int get() = if (v2) G.BORDER else 0x29FFFFFF
    const val RADIUS_DP = 16

    private val FOCUSED = intArrayOf(android.R.attr.state_focused)
    private val PRESSED = intArrayOf(android.R.attr.state_pressed)
    private val SELECTED = intArrayOf(android.R.attr.state_selected)

    private fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Pigułka z fokusem: zaznaczona – akcent, wybrana – akcent 18 % z obwódką, zwykła – [rest]. */
    fun pill(radius: Float = Ui.dp(40).toFloat(), rest: Int = 0): Drawable =
        // (v2) zakładki / filtry / kategorie: aktywna – gradient #2F80ED → #1D4ED8, fokus – tint z błękitną ramką i poświatą,
        // zwykła – półprzezroczyste szkło z obramowaniem 1 dp ([ObsidianGlass.pillStates])
        if (v2) G.pillStates(maxOf(1, Math.round(radius / Ui.density)), runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)) else StateListDrawable().apply {
        val on = round(ACCENT, radius)
        addState(FOCUSED, on); addState(PRESSED, on)
        val chosen = round(0x2E3B82F6, radius, Ui.dp(1), ACCENT)
        addState(SELECTED, chosen); addState(ACTIVATED, chosen)
        addState(intArrayOf(), if (rest == 0) ColorDrawable(0) else round(rest, radius))
    }
    private val ACTIVATED = intArrayOf(android.R.attr.state_activated)

    /**
     * (7.17) Pozycja paska nawigacji (zaokrąglenie 14 dp): fokus – wypełnienie akcentem; wybrana sekcja – akcent 22 % z pełną
     * obwódką 1,5 dp (GradientDrawable rysuje obrys wewnątrz granic – nic nie jest ucinane przy krawędzi ekranu).
     */
    fun railPill(): Drawable = if (v2) railPillGlass() else StateListDrawable().apply {
        val r = Ui.dp(AppNavRail.ITEM_RADIUS.value.toInt()).toFloat()
        val on = round(ACCENT, r)
        addState(FOCUSED, on); addState(PRESSED, on)
        val stroke = Math.round(AppNavRail.SELECTED_STROKE.value * Ui.density).coerceAtLeast(1)
        val chosen = round((ACCENT and 0x00FFFFFF) or (Math.round(AppNavRail.SELECTED_FILL_ALPHA * 255) shl 24), r, stroke, ACCENT)
        addState(SELECTED, chosen); addState(ACTIVATED, chosen)
        addState(intArrayOf(), ColorDrawable(0))
    }

    /**
     * (v2) Pozycja paska nawigacji „Obsidian Glass”: fokus – niebieski tint z błękitną ramką 2 dp i poświatą (bez pełnego
     * wypełnienia jak w Nowoczesnym), bieżąca sekcja – gradient przycisku głównego, spoczynek – bez tła.
     */
    private fun railPillGlass(): Drawable {
        // (v2, ujednolicenie) V2PillTab: pełna kapsuła – fokus / bieżąca sekcja: tint 22 % + ramka 2 dp #38BDF8 + poświata,
        // spoczynek – ciemne szkło #0E1629 65 % z ramką 1 dp
        val low = runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)
        return G.pillTab(AppNavRail.ITEM_RADIUS.value.toInt(), low)      // geometria jak w v1 (ikona na środku pozycji), kolory v2
    }

    /** Kolory tekstu pigułki: biały przy fokusie / wyborze, inaczej drugorzędny. */
    fun pillText(): ColorStateList = ColorStateList(arrayOf(FOCUSED, PRESSED, SELECTED, ACTIVATED, intArrayOf()), intArrayOf(TEXT, TEXT, TEXT, TEXT, TEXT2))

    /** Szkło bez rozmycia: pionowy gradient powierzchni + krawędź specular 1 dp. */
    fun glass(radiusDp: Int = RADIUS_DP): Drawable = if (pl.iptv.tv.ui.ModernV2.active) ObsidianGlass.glass(radiusDp) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SURFACE_GLASS, SURFACE_ELEVATED)).apply {
        cornerRadius = Ui.dp(radiusDp).toFloat(); setStroke(Ui.dp(1), SPECULAR)
    }

    /** Ramka okna podglądu (PiP): zaokrąglenie 16 dp, subtelna obwódka. */
    fun previewFrame(): Drawable = round(0xFF000000.toInt(), Ui.dp(RADIUS_DP).toFloat(), Ui.dp(1), 0x33FFFFFF)

    /** Panel 90 % (`#0F141F`) – Ustawienia. */
    fun panel90(radiusDp: Int = RADIUS_DP): Drawable = if (pl.iptv.tv.ui.ModernV2.active) ObsidianGlass.glassOverlay(ObsidianGlass.DIALOG_RADIUS_DP) else round(PANEL_90, Ui.dp(radiusDp).toFloat())

    /** Czerwona pigułka „NA ŻYWO” (tekst biały). */
    fun livePill(): Drawable = round(LIVE, Ui.dp(10).toFloat())
    /** Plakietka czasu trwania na miniaturze. */
    fun durationBadge(): Drawable = round(0xD9080A0F.toInt(), Ui.dp(6).toFloat())
}

/**
 * Pasek nawigacji „Obsidian Stream” na widokach – zachowanie jak [AppNavigationRail]: zwinięty [AppNavRail.COLLAPSED],
 * z fokusem w środku rozwija się w [AppNavRail.ANIM_MS] (FastOutSlowIn) do [AppNavRail.EXPANDED], podpisy tylko rozwinięty,
 * a [scrim] (nad treścią, pod paskiem) przyciemnia treść. Pasek musi leżeć nad treścią (FrameLayout) – nie przesuwa jej.
 *
 * [rail] – widok, którego szerokość się zmienia; [items] – pozycje (TextView z ikoną po lewej); [label] – podpis pozycji.
 */
class AppNavigationRailViews(
    private val rail: View,
    private val scrim: View,
    private val items: () -> List<TextView>,
    private val label: (TextView) -> String,
    private val lowPerf: () -> Boolean = { false },
) {
    var expanded = false; private set
    private var anim: ValueAnimator? = null
    private val collapsedPx = Ui.dp(AppNavRail.COLLAPSED.value.toInt())
    private val expandedPx = Ui.dp(AppNavRail.EXPANDED.value.toInt())
    private val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, new -> set(new != null && inside(new)) }

    fun attach() {
        rail.layoutParams = rail.layoutParams?.apply { width = collapsedPx }
        scrim.setBackgroundColor(AppNavRail.SCRIM_ARGB); scrim.alpha = 0f; scrim.isClickable = false; scrim.isFocusable = false
        scrim.visibility = View.GONE
        styleItems()
        rail.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        applyLabels()
    }

    fun detach() { runCatching { rail.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener) }; anim?.cancel() }

    /** (7.17) Pozycje paska: kontener 44 dp na środku zwiniętego paska (14 dp z obu stron), ikona w stałym miejscu, podpis obok. */
    fun styleItems() {
        // (7.17) kontener 44 dp dokładnie na środku zwiniętego paska: margines 14 dp z obu stron pozycji, rodzice bez
        // poziomych odstępów (dawniej ikona liczona od odstępu paska, a pozycje siedziały w kontenerze z własnym odstępem –
        // zaznaczenie wychodziło niesymetryczne i ucięte przy lewej krawędzi); ikona na środku kontenera
        val side = Ui.dp(AppNavRail.PAD.value.toInt())
        val box = collapsedPx - side * 2
        val icon = Ui.dp(AppNavRail.ICON.value.toInt())
        val iconPad = ((box - icon) / 2).coerceAtLeast(0)
        items().forEach { b ->
            var p = b.parent as? View
            while (p != null) { if (p.paddingLeft != 0 || p.paddingRight != 0) p.setPadding(0, p.paddingTop, 0, p.paddingBottom); if (p === rail) break; p = p.parent as? View }
            // podpis zapamiętany raz (tekst przycisku zmienia się z rozwinięciem paska)
            if (b.contentDescription.isNullOrEmpty() && b.text.isNotEmpty()) b.contentDescription = b.text
            b.tag = "keepcolor nozoom"
            b.background = ObsidianViews.railPill()
            b.setTextColor(ObsidianViews.pillText())
            // ikona nad tekstem (dawny układ przycisków) → po lewej, 24 dp, w kolorach pigułki
            val d = b.compoundDrawablesRelative.let { it[0] ?: it[1] }
            if (d != null) {
                val m = d.mutate(); m.setBounds(0, 0, icon, icon)
                // ikony rysowane / wektorowe w kolorach pigułki; ikony aplikacji (skróty) zostają w swoich kolorach
                if (m !is pl.iptv.tv.ui.PlayerIcon && b.getTag(pl.iptv.tv.R.id.row_key) != "app") runCatching { androidx.core.graphics.drawable.DrawableCompat.setTintList(m, ObsidianViews.pillText()) }
                b.setCompoundDrawablesRelative(m, null, null, null)
            }
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.isSingleLine = true; b.ellipsize = null; b.textSize = 15f
            b.compoundDrawablePadding = Ui.dp(16)
            b.setPadding(iconPad, 0, Ui.dp(12), 0)
            b.minHeight = 0; b.minimumHeight = 0
            (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = box
                lp.topMargin = Ui.dp(3); lp.bottomMargin = Ui.dp(3); lp.marginStart = side; lp.marginEnd = side; lp.leftMargin = side; lp.rightMargin = side
                b.layoutParams = lp
            }
            if (b.contentDescription.isNullOrEmpty()) b.contentDescription = label(b)
        }
    }

    private fun inside(v: View): Boolean { var x: View? = v; while (x != null) { if (x === rail) return true; x = x.parent as? View }; return false }

    /** Podpisy wg bieżącego stanu (np. po zmianie nazwy konta). */
    fun refreshLabels() = applyLabels()

    private fun applyLabels() = items().forEach { b -> val t = if (expanded) label(b) else ""; if (b.text.toString() != t) b.text = t }

    fun set(e: Boolean) {
        if (e == expanded) return
        expanded = e
        applyLabels()
        anim?.cancel()
        val from = rail.layoutParams.width.takeIf { it > 0 } ?: collapsedPx
        val to = if (e) expandedPx else collapsedPx
        val a0 = scrim.alpha; val a1 = if (e) 1f else 0f
        scrim.visibility = View.VISIBLE
        val ms = if (lowPerf() || Ui.anim == 0) 0L else AppNavRail.ANIM_MS.toLong()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)            // FastOutSlowIn
            addUpdateListener { va ->
                val f = va.animatedFraction
                rail.layoutParams.width = (from + (to - from) * f).toInt(); rail.requestLayout()
                scrim.alpha = a0 + (a1 - a0) * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!expanded) scrim.visibility = View.GONE }
            })
            start()
        }
        if (ms == 0L) { rail.layoutParams.width = to; rail.requestLayout(); scrim.alpha = a1; if (!e) scrim.visibility = View.GONE }
    }
}

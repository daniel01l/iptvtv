package pl.iptv.tv.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.iptv.tv.ui.Ui
import androidx.compose.ui.graphics.Color as CColor

/**
 * Tokeny wizualne motywu **Nowoczesny v2** – „Obsidian Glass” (luksusowy, ciemny styl z gradientami).
 *
 * - tło: głęboki granat / czerń `#070B14` → `#0E1525` z subtelną niebiesko-fioletową poświatą ([GlassBackdrop]),
 * - powierzchnie: półprzezroczyste karty i panele (16–24 dp) z delikatną jasną krawędzią ([BORDER]) i połyskiem u góry,
 * - fokus / element aktywny: wyraźna niebieska ramka [FOCUS_RING] i poświata `#2F80ED` / `#3B82F6`.
 *
 * Krycie powierzchni zależy od ustawienia „Przezroczystość UI” ([Opacity], `Prefs.mv2Opacity`). Gotowe tła dla widoków
 * (Drawable – cieniowania tworzone raz przy zmianie rozmiaru, rysowanie bez alokacji) i modyfikatory Compose
 * ([glassSurface], [glassFocusGlow] – obiekty w bloku pamięci `drawWithCache`, w `onDraw` tylko rysowanie).
 */
object ObsidianGlass {
    // ---------------- kolory (ARGB) ----------------
    // (v2, poprawka widoczności) tokeny wyraźnie inne niż w motywie Nowoczesny (#080A0F, pełne niebieskie wypełnienie fokusu):
    // granatowy gradient z dwiema mocnymi poświatami, szkło z bieli 8 % → 2 %, fokus = niebieski tint + błękitna ramka + poświata
    const val BG_TOP = 0xFF050811.toInt()
    const val BG_MID = 0xFF070C18.toInt()
    const val BG_BOTTOM = 0xFF090F1E.toInt()      // (v2, ujednolicenie) #050811 → #090F1E
    /** Poświata tła: elektryzujący błękit (prawy górny róg) i głęboki indygo / fiolet (lewy dolny). */
    const val GLOW_BLUE = 0xFF2F80ED.toInt()
    const val GLOW_VIOLET = 0xFF5B21B6.toInt()
    /** (v2) Indygo poświaty u góry ekranu. */
    const val GLOW_INDIGO = 0xFF4338CA.toInt()
    const val ACCENT = 0xFF2F80ED.toInt()
    /** Kolor zaznaczenia (tint i poświata). */
    const val FOCUS = 0xFF2F80ED.toInt()
    /** Drugi kolor gradientu przycisków głównych (`#2F80ED` → `#1D4ED8`). */
    const val PRIMARY_END = 0xFF1D4ED8.toInt()
    /** Wyraźna ramka fokusu 2 dp (błękit `#38BDF8`). */
    const val FOCUS_RING = 0xFF38BDF8.toInt()
    /** Wypełnienie zaznaczenia: `#2F80ED` 18 %. */
    const val FOCUS_TINT = 0x382F80ED      // (v2, ujednolicenie) 22 %
    /** Poświata wokół ramki fokusu (`#2F80ED`). */
    const val FOCUS_GLOW = 0x802F80ED.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()
    const val TEXT2 = 0xFF9FB0C8.toInt()
    const val LIVE = 0xFFEF4444.toInt()
    const val SUCCESS = 0xFF22C55E.toInt()
    const val WARNING = 0xFFF59E0B.toInt()
    /** Kolor bazowy szkła (granat `#0B1222`; krycie wg [Opacity]). */
    const val SURFACE_TOP = 0x10192E
    const val SURFACE_BOTTOM = 0x10192E
    /** Szklane obramowanie 1 dp: biel 14 %. */
    const val BORDER = 0x29FFFFFF
    const val SPECULAR = 0x38FFFFFF
    /** Spoczynkowe tło wierszy / kart w listach: biel 6 % (szkło na szkle). */
    const val ITEM_REST = 0x0FFFFFFF
    /** Połysk szkła: biel 8 % u góry → 2 % u dołu. */
    const val SHEEN_TOP = 0.08f
    const val SHEEN_BOTTOM = 0.02f
    /** Przyciemnienie pod oknami. */
    const val DIALOG_DIM = 0.72f
    /** Najmniejsze krycie okien / modali i paneli nakładkowych z listą (≈ 98 %) – niezależnie od „Przezroczystość UI”. */
    const val MODAL_MIN_ALPHA = 0xFA

    // ---------------- geometria ----------------
    const val RADIUS_CARD_DP = 16
    const val RADIUS_PANEL_DP = 20
    const val RADIUS_SHEET_DP = 24
    const val FOCUS_STROKE_DP = 2
    const val GLOW_DP = 3
    const val BORDER_DP = 1
    /** (v2) Szklana krawędź kart i paneli: 1,5 dp (V2GlassSurface). */
    const val BORDER_W = 1.5f
    fun borderPx(): Int = Math.round(BORDER_W * Ui.density).coerceAtLeast(1)

    /** „Przezroczystość UI”: wysoka – mocniej prześwitujące panele, niska – prawie kryjące (czytelność nad obrazem). */
    // (v2) krycie szkła: Wysoka 0,45 / Średnia 0,65 / Niska 0,82 (panele z listami nad obrazem – o ok. 8 % więcej)
    enum class Opacity(val label: String, val surfaceAlpha: Int, val panelAlpha: Int) {
        HIGH("Wysoka", 0x73, 0x8C),
        MEDIUM("Średnia", 0x94, 0xAD),
        LOW("Niska", 0xD1, 0xE0);
        companion object { fun current(): Opacity = entries[runCatching { pl.iptv.tv.data.Prefs.mv2Opacity }.getOrDefault(1).coerceIn(0, 2)] }
    }

    fun withAlpha(rgb: Int, a: Int) = (rgb and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    // ---------------- widoki (Drawable) ----------------

    /** Szklana karta / panel: gradient powierzchni z kryciem z [Opacity], krawędź 1 dp. */
    fun glass(radiusDp: Int = RADIUS_CARD_DP, panel: Boolean = false, opacity: Opacity = Opacity.current(), minAlpha: Int = 0): Drawable {
        val a = maxOf(minAlpha, if (panel) opacity.panelAlpha else opacity.surfaceAlpha)
        // granat z kryciem [a] rozjaśniony bielą 8 % u góry → 2 % u dołu (połysk szkła) + szklane obramowanie 1 dp (biel 14 %)
        val base = SURFACE_TOP or (0xFF shl 24)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            withAlpha(Ui.blend(base, Color.WHITE, SHEEN_TOP), a), withAlpha(Ui.blend(base, Color.WHITE, SHEEN_BOTTOM), a))).apply {
            cornerRadius = Ui.dp(radiusDp).toFloat(); setStroke(borderPx(), BORDER)
        }
    }

    /**
     * Przycisk główny / aktywna zakładka: żywy gradient `#2F80ED` → `#1D4ED8` (biały tekst); [ring] – z ramką fokusu i poświatą.
     */
    fun primary(radiusDp: Int = 40, ring: Boolean = false, lowPerf: Boolean = false): Drawable {
        val r = Ui.dp(radiusDp).toFloat(); val g = Ui.dp(GLOW_DP)
        val core = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(FOCUS, PRIMARY_END)).apply {
            cornerRadius = r; if (ring) setStroke(Ui.dp(FOCUS_STROKE_DP), FOCUS_RING)
        }
        if (!ring || lowPerf) return core
        val halo = GradientDrawable().apply { cornerRadius = r + g; setColor(Color.TRANSPARENT); setStroke(g, FOCUS_GLOW) }
        return LayerDrawable(arrayOf(halo, core)).apply { setLayerInset(1, g, g, g, g) }
    }

    // ---------------- (v2, ujednolicenie) V2GlassDialog, V2PillTab, V2GlowPillButton, V2OptionRow ----------------

    /** Fale świetlne tła ([V2WaveBackground]): błękit `#2F80ED` → fiolet `#6D28D9`. */
    const val WAVE_VIOLET = 0xFF6D28D9.toInt()
    /** `V2PillTab` w spoczynku: szkło `#0E1629` 65 % z ramką 1 dp biel 14 %. */
    const val PILL_REST = 0xA60E1629.toInt()
    const val PILL_BORDER = 0x24FFFFFF
    /** Fokus / stan aktywny pigułek, kart i wierszy: tint `#2F80ED` 22 % (ramka 2 dp [FOCUS_RING] + poświata). */
    const val PILL_TINT = 0x382F80ED
    /** `V2GlowPillButton` potwierdzający: gradient `#2F80ED` → `#38BDF8`; drugorzędny – ciemne szkło z ramką 1 dp biel 18 %. */
    const val CONFIRM_END = 0xFF38BDF8.toInt()
    const val SECONDARY_BORDER = 0x2EFFFFFF
    /** `V2GlassDialog`: `#0C1426` 78 % (okna), 72 % (nakładki odtwarzacza Live TV), zaokrąglenie 24 dp, krawędź 1,5 dp `#38BDF8` 45 % → biel 12 %. */
    const val DIALOG_FILL = 0xC70C1426.toInt()
    const val OVERLAY_FILL = 0xB80C1426.toInt()
    const val DIALOG_BORDER_TOP = 0x7338BDF8
    const val DIALOG_BORDER_BOTTOM = 0x1FFFFFFF
    const val DIALOG_RADIUS_DP = 24
    /** Wybrana opcja w oknie: ramka 1,5 dp `#38BDF8` i znacznik ✓ po prawej. */
    const val CHOSEN_STROKE_DP = 1.5f
    /** Odsunięcie wysuwanych paneli od krawędzi ekranu (pływające szkło z zaokrągleniem 24 dp). */
    const val EDGE_INSET_DP = 10
    /** `Switch` v2: tor `#2F80ED` → `#38BDF8` (wł.), ciemnoszary `#334155` (wył.). */
    const val SWITCH_OFF = 0xFF334155.toInt()

    /** `V2GlassDialog` – szkło okien / nakładek: [fill] z gradientową świetlistą krawędzią 1,5 dp. */
    fun glassDialog(radiusDp: Int = DIALOG_RADIUS_DP, fill: Int = DIALOG_FILL): Drawable =
        V2PanelDrawable(Ui.dp(radiusDp).toFloat(), fill)

    /** Nakładki pełnoekranowego odtwarzacza Live TV (panele boczne, menu ikon, szybkie menu): `V2GlassDialog` 72 %. */
    fun glassOverlay(radiusDp: Int = DIALOG_RADIUS_DP): Drawable = glassDialog(radiusDp, OVERLAY_FILL)

    /** Okno dialogowe / modal: `V2GlassDialog` (78 %, 24 dp). */
    fun dialogPanel(): Drawable = glassDialog()

    /** Panel wysuwany z prawej / lewej krawędzi: pływające szkło `V2GlassDialog` odsunięte o [EDGE_INSET_DP]. */
    fun edgePanel(): Drawable = android.graphics.drawable.InsetDrawable(glassDialog(), Ui.dp(EDGE_INSET_DP))

    /** Tint + ramka 2 dp `#38BDF8` + zewnętrzna poświata (fokus / stan aktywny `V2PillTab`, kart, wierszy). */
    fun tintRing(radiusDp: Int, lowPerf: Boolean = false, strokeDp: Float = FOCUS_STROKE_DP.toFloat(), fill: Int = PILL_TINT): Drawable {
        val r = Ui.dp(radiusDp).toFloat(); val g = Ui.dp(GLOW_DP)
        val core = GradientDrawable().apply { setColor(fill); cornerRadius = maxOf(r - g, 0f); setStroke(Math.round(strokeDp * Ui.density).coerceAtLeast(1), FOCUS_RING) }
        if (lowPerf) return core
        val outer = GradientDrawable().apply { cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(g, withAlpha(FOCUS, 0x40)) }
        val inner = GradientDrawable().apply { cornerRadius = maxOf(r - g / 2f, 0f); setColor(Color.TRANSPARENT); setStroke(maxOf(1, g / 2), FOCUS_GLOW) }
        return LayerDrawable(arrayOf(outer, inner, core)).apply { setLayerInset(1, g / 2, g / 2, g / 2, g / 2); setLayerInset(2, g, g, g, g) }
    }

    /** `V2PillTab` w spoczynku: ciemne szkło `#0E1629` 65 % z ramką 1 dp biel 14 %. */
    fun pillRest(radiusDp: Int = 40): Drawable = round(PILL_REST, radiusDp, Ui.dp(1), PILL_BORDER)

    /**
     * `V2PillTab` (zakładki kategorii, filtry, pigułki akcji, kafelki ikon): pełna kapsuła; spoczynek – [pillRest], fokus / wybrana /
     * aktywna – tint 22 % + ramka 2 dp `#38BDF8` + poświata ([tintRing]).
     */
    fun pillTab(radiusDp: Int = 40, lowPerf: Boolean = false): Drawable = StateListDrawable().apply {
        val on = tintRing(radiusDp, lowPerf)
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(android.R.attr.state_pressed), tintRing(radiusDp, true))
        addState(intArrayOf(android.R.attr.state_selected), on); addState(intArrayOf(android.R.attr.state_activated), on)
        addState(intArrayOf(), pillRest(radiusDp))
    }

    /** Przycisk potwierdzający (ZAPISZ, ZASTOSUJ, DODAJ, OK): gradient `#2F80ED` → `#38BDF8` z poświatą; fokus – biała ramka 2 dp. */
    fun confirm(radiusDp: Int = 40, focused: Boolean = false, lowPerf: Boolean = false): Drawable {
        val r = Ui.dp(radiusDp).toFloat(); val g = Ui.dp(GLOW_DP)
        val core = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(FOCUS, CONFIRM_END)).apply {
            cornerRadius = maxOf(r - g, 0f); if (focused) setStroke(Ui.dp(FOCUS_STROKE_DP), Color.WHITE)
        }
        if (lowPerf) return core
        val halo = GradientDrawable().apply { cornerRadius = r; setColor(Color.TRANSPARENT); setStroke(g, withAlpha(CONFIRM_END, if (focused) 0x80 else 0x40)) }
        return LayerDrawable(arrayOf(halo, core)).apply { setLayerInset(1, g, g, g, g) }
    }

    /**
     * `V2GlowPillButton`: [primary] – gradient z poświatą (fokus – biała ramka), drugorzędny (ANULUJ, ZAMKNIJ) – ciemne szkło z ramką
     * 1 dp biel 18 % (fokus – tint z ramką `#38BDF8` i poświatą).
     */
    fun button(primary: Boolean, radiusDp: Int = 40, lowPerf: Boolean = false): Drawable = StateListDrawable().apply {
        if (primary) {
            addState(intArrayOf(android.R.attr.state_focused), confirm(radiusDp, true, lowPerf))
            addState(intArrayOf(android.R.attr.state_pressed), confirm(radiusDp, true, true))
            addState(intArrayOf(), confirm(radiusDp, false, lowPerf))
        } else {
            addState(intArrayOf(android.R.attr.state_focused), tintRing(radiusDp, lowPerf))
            addState(intArrayOf(android.R.attr.state_pressed), tintRing(radiusDp, true))
            addState(intArrayOf(), round(PILL_REST, radiusDp, Ui.dp(1), SECONDARY_BORDER))
        }
    }

    /**
     * `V2OptionRow` / karta wyboru w oknie (16 dp): fokus – tint + ramka 2 dp + poświata, wybrana – ramka 1,5 dp `#38BDF8`
     * (znacznik ✓ rysuje [drawCheck]), spoczynek – ciemne szkło z ramką 1 dp biel 14 %.
     */
    fun optionCard(radiusDp: Int = RADIUS_CARD_DP, lowPerf: Boolean = false): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), tintRing(radiusDp, lowPerf))
        addState(intArrayOf(android.R.attr.state_pressed), tintRing(radiusDp, true))
        val chosen = GradientDrawable().apply { setColor(withAlpha(FOCUS, 0x1F)); cornerRadius = Ui.dp(radiusDp).toFloat()
            setStroke(Math.round(CHOSEN_STROKE_DP * Ui.density).coerceAtLeast(1), FOCUS_RING) }
        addState(intArrayOf(android.R.attr.state_activated), chosen); addState(intArrayOf(android.R.attr.state_selected), chosen)
        addState(intArrayOf(), round(PILL_REST, radiusDp, Ui.dp(1), PILL_BORDER))
    }

    /** Znacznik ✓ (`#38BDF8`, gruba zaokrąglona linia) w prostokącie [r] – wybrana opcja okna. */
    fun drawCheck(c: Canvas, r: android.graphics.RectF, p: Paint, color: Int = FOCUS_RING) {
        val s = minOf(r.width(), r.height()); val x = r.centerX() - s / 2; val y = r.centerY() - s / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.14f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = color; p.shader = null
        val path = android.graphics.Path().apply { moveTo(x + s * 0.18f, y + s * 0.52f); lineTo(x + s * 0.42f, y + s * 0.74f); lineTo(x + s * 0.84f, y + s * 0.28f) }
        c.drawPath(path, p)
    }

    /** Przełącznik v2: tor-kapsuła `#2F80ED` → `#38BDF8` (wł.) / `#334155` (wył.), gałka biała (wył. – szara po lewej). */
    fun drawSwitch(c: Canvas, b: Rect, on: Boolean, p: Paint, rect: android.graphics.RectF) {
        val h = b.height().toFloat(); val rad = h / 2
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        p.style = Paint.Style.FILL
        p.shader = if (on) LinearGradient(rect.left, 0f, rect.right, 0f, FOCUS, CONFIRM_END, Shader.TileMode.CLAMP) else null
        p.color = if (on) Color.WHITE else SWITCH_OFF
        c.drawRoundRect(rect, rad, rad, p)
        p.shader = null
        p.color = if (on) Color.WHITE else 0xFF94A3B8.toInt()
        c.drawCircle(if (on) b.right - rad else b.left + rad, b.top + rad, rad - Ui.dp(3), p)
    }

    /**
     * Zaznaczenie: niebieskie wypełnienie z wyraźną ramką [FOCUS_RING], na zewnątrz poświata [FOCUS_GLOW];
     * tryb słabszych urządzeń – bez poświaty.
     */
    fun focus(radiusDp: Int = RADIUS_CARD_DP, lowPerf: Boolean = false): Drawable {
        // (v2, ujednolicenie) tint #2F80ED 22 % + ramka 2 dp #38BDF8 + poświata – wspólne z V2PillTab / V2OptionRow
        return tintRing(radiusDp, lowPerf)
    }

    /**
     * Komórka siatki Programu TV: bez zewnętrznej poświaty (wąskie komórki nie tracą miejsca na tekst), fokus – tint #2F80ED 34 %
     * z ramką 2 dp `#38BDF8`, trwający program – lekki błękit z cienką ramką 1 dp (wyraźnie inny niż kursor; dawniej taki sam pierścień
     * jak fokus – cała kolumna „teraz” wyglądała na zaznaczoną), spoczynek – ciemne szkło z krawędzią biel 8 %.
     */
    fun epgCell(radiusDp: Int = 8): Drawable = StateListDrawable().apply {
        val r = Ui.dp(radiusDp)
        val focused = round(withAlpha(FOCUS, 0x57), radiusDp, Ui.dp(FOCUS_STROKE_DP), FOCUS_RING)
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(android.R.attr.state_selected), focused)
        addState(intArrayOf(android.R.attr.state_activated), GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(FOCUS, 0x33), withAlpha(FOCUS, 0x1A))).apply {
            cornerRadius = r.toFloat(); setStroke(Ui.dp(1), withAlpha(FOCUS_RING, 0x66)) })
        // spoczynek: jasne szkło (biel 7 % → 3 %) z krawędzią biel 12 % – widoczne na granatowym panelu (dawny granat ginął w tle)
        addState(intArrayOf(), GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x12FFFFFF, 0x08FFFFFF)).apply {
            cornerRadius = r.toFloat(); setStroke(Ui.dp(1), 0x1FFFFFFF) })
        setEnterFadeDuration(0); setExitFadeDuration(0)
    }

    /** Wiersz / karta listy: fokus – [focus], wybrana – akcent 22 % z ramką, spoczynek – [ITEM_REST] z krawędzią. */
    fun item(radiusDp: Int = RADIUS_CARD_DP, lowPerf: Boolean = false): Drawable = StateListDrawable().apply {
        val f = focus(radiusDp, lowPerf)
        addState(intArrayOf(android.R.attr.state_focused), f)
        addState(intArrayOf(android.R.attr.state_pressed), focus(radiusDp, true))
        // (v2) aktywny (bieżący kanał, wybrana kategoria): tint z wyraźną ramką 2 dp #38BDF8
        val chosen = round(withAlpha(ACCENT, 0x40), radiusDp, Ui.dp(FOCUS_STROKE_DP), FOCUS_RING)
        addState(intArrayOf(android.R.attr.state_selected), chosen)
        addState(intArrayOf(android.R.attr.state_activated), chosen)
        // (v2) spoczynek: karta V2GlassSurface – #10192E z kryciem wg „Przezroczystość UI” (Średnia 0,58) i szklaną krawędzią 1,5 dp
        addState(intArrayOf(), glass(radiusDp))
    }

    /**
     * Kafelek nad obrazem (wyśrodkowana lista kanałów po OK, nagłówek kategorii): spoczynek – szkło wg [Opacity], aktywny
     * (bieżący kanał) – szkło z niebieską ramką, fokus – [focus].
     */
    fun tile(lowPerf: Boolean = false): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focus(RADIUS_CARD_DP, lowPerf))
        addState(intArrayOf(android.R.attr.state_pressed), focus(RADIUS_CARD_DP, true))
        addState(intArrayOf(android.R.attr.state_activated), (glass(RADIUS_CARD_DP, panel = true) as GradientDrawable).apply { setStroke(Ui.dp(FOCUS_STROKE_DP), withAlpha(FOCUS_RING, 0xC0)) })
        addState(intArrayOf(), glass(RADIUS_CARD_DP, panel = true))
    }

    /** Pigułka (kroki kreatora, przyciski): wybrana – tint z ramką `#38BDF8` i poświatą, zwykła – [pillRest] (V2PillTab). */
    fun pill(selected: Boolean): Drawable = if (selected) tintRing(40) else pillRest(40)

    /**
     * Pigułka z fokusem (zakładki, filtry, kategorie, pigułki akcji) – (v2, ujednolicenie) `V2PillTab` ([pillTab]): fokus / aktywna –
     * tint 22 % + ramka 2 dp `#38BDF8` + poświata, spoczynek – szkło `#0E1629` 65 % z ramką 1 dp biel 14 %.
     */
    fun pillStates(radiusDp: Int = 40, lowPerf: Boolean = false): Drawable = pillTab(radiusDp, lowPerf)

    /** Plakietka stanu (np. „Sprawdzono …” na ekranie aktywacji) w kolorze [color]. */
    fun statusChip(color: Int): Drawable = round(withAlpha(color, 0x2A), 40, Ui.dp(1), withAlpha(color, 0x99))

    /** Kropka stanu (np. trwa sprawdzanie). */
    fun dot(color: Int): Drawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(Ui.dp(8), Ui.dp(8)) }

    private fun round(fill: Int, radiusDp: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = Ui.dp(radiusDp).toFloat(); if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun clear(): Drawable = ColorDrawable(Color.TRANSPARENT)

    // ---------------- (v2) pływająca wyspa i HUD ----------------

    /** Tło pływającej wyspy nawigacji / HUD: `#0D1426` 72 %. */
    const val ISLAND_FILL = 0xB80D1426.toInt()
    /** Gradientowe obramowanie wyspy 1,5 dp: błękit `#38BDF8` 35 % → biel 6 %. */
    const val ISLAND_BORDER_TOP = 0x5938BDF8
    const val ISLAND_BORDER_BOTTOM = 0x0FFFFFFF
    const val ISLAND_RADIUS_DP = 24
    /** Odsunięcie wyspy nawigacji od krawędzi ekranu. */
    const val ISLAND_MARGIN_DP = 12
    /** Odsunięcie HUD odtwarzacza od lewej i dolnej krawędzi; szerokość na TV – najwyżej 65 % ekranu. */
    const val HUD_MARGIN_DP = 20
    const val HUD_MAX_WIDTH = 0.65f
    /** Szuflada listy kanałów w odtwarzaczu: szkło 68 % (widać obraz pod spodem). */
    const val DRAWER_ALPHA = 0xAD

    /** Pływająca szklana wyspa (pasek nawigacji, HUD odtwarzacza) z gradientowym obramowaniem 1,5 dp. */
    fun island(radiusDp: Int = ISLAND_RADIUS_DP, fill: Int = ISLAND_FILL): Drawable =
        GlassIslandDrawable(Ui.dp(radiusDp).toFloat(), fill, ISLAND_BORDER_TOP, ISLAND_BORDER_BOTTOM, borderPx().toFloat())

    // ---------------- Compose ----------------

    /** Kolory dla Compose (ekran główny START i inne ekrany Compose motywu v2). */
    @Immutable
    object Tokens {
        val BgTop = CColor(BG_TOP)
        val BgBottom = CColor(BG_BOTTOM)
        val Accent = CColor(ACCENT)
        val Focus = CColor(FOCUS)
        val FocusRing = CColor(FOCUS_RING)
        val FocusGlow = CColor(FOCUS_GLOW)
        val Text = CColor(TEXT)
        val Text2 = CColor(TEXT2)
        val Border = CColor(BORDER)
        val Specular = CColor(SPECULAR)
        val Violet = CColor(GLOW_VIOLET)
        val FocusTint = CColor(FOCUS_TINT)
        val PrimaryEnd = CColor(PRIMARY_END)
    }
}


// ======================================================================================================================
// (v2) Compose: stan motywu na szczycie drzewa, tło, szklana karta, fokus
// ======================================================================================================================

/**
 * Aktywny motyw i jego ustawienia dla Compose ([LocalObsidianGlass]) – dostarczany na szczycie każdego drzewa `setContent`
 * ([pl.iptv.tv.ui.TvResolution1080pProvider]); ekrany są przebudowywane po zmianie motywu ([pl.iptv.tv.ui.Ui.themeEpoch]).
 */
@Immutable
data class ObsidianGlassSpec(val active: Boolean, val opacity: ObsidianGlass.Opacity, val animations: Boolean, val lowPerf: Boolean) {
    companion object {
        fun current() = ObsidianGlassSpec(pl.iptv.tv.ui.ModernV2.active, ObsidianGlass.Opacity.current(), pl.iptv.tv.ui.ModernV2.animations,
            runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false))
        val OFF = ObsidianGlassSpec(false, ObsidianGlass.Opacity.MEDIUM, true, false)
    }
}

val LocalObsidianGlass = androidx.compose.runtime.staticCompositionLocalOf { ObsidianGlassSpec.OFF }

/** Dostawca stanu motywu v2 (wersja wyglądu [pl.iptv.tv.ui.Ui.version] – nowe ustawienia po każdej zmianie). */
@androidx.compose.runtime.Composable
fun ObsidianGlassProvider(content: @androidx.compose.runtime.Composable () -> Unit) {
    val v = pl.iptv.tv.ui.Ui.version
    val spec = androidx.compose.runtime.remember(v) { ObsidianGlassSpec.current() }
    androidx.compose.runtime.CompositionLocalProvider(LocalObsidianGlass provides spec, content = content)
}

/**
 * Szklana powierzchnia w Compose: granat z kryciem wg „Przezroczystość UI” (0,45 / 0,65 / 0,82), połysk szkła (biel 8 % → 2 %)
 * i szklane obramowanie 1 dp (biel 14 %). Pędzle w bloku pamięci `drawWithCache` – rysowanie bez alokacji.
 */
fun Modifier.glassSurface(radius: Dp = ObsidianGlass.RADIUS_CARD_DP.dp, panel: Boolean = false,
                          opacity: ObsidianGlass.Opacity = ObsidianGlass.Opacity.current()): Modifier = drawWithCache {
    val a = (if (panel) opacity.panelAlpha else opacity.surfaceAlpha) / 255f
    val base = CColor(ObsidianGlass.SURFACE_TOP or (0xFF shl 24)).copy(alpha = a)
    val sheen = Brush.verticalGradient(listOf(CColor.White.copy(alpha = ObsidianGlass.SHEEN_TOP), CColor.White.copy(alpha = ObsidianGlass.SHEEN_BOTTOM)))
    val r = CornerRadius(radius.toPx()); val sw = ObsidianGlass.BORDER_W.dp.toPx()
    val border = Stroke(sw); val inset = Offset(sw / 2, sw / 2); val inner = Size(size.width - sw, size.height - sw)
    onDrawBehind {
        drawRoundRect(base, cornerRadius = r)
        drawRoundRect(sheen, cornerRadius = r)
        drawRoundRect(ObsidianGlass.Tokens.Border, inset, inner, r, style = border)
    }
}

/** Szklana karta / panel „Obsidian Glass” (Compose). */
@androidx.compose.runtime.Composable
fun ObsidianGlassCard(modifier: Modifier = Modifier, radius: Dp = ObsidianGlass.RADIUS_CARD_DP.dp, panel: Boolean = false,
                      content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val spec = LocalObsidianGlass.current
    androidx.compose.foundation.layout.Box(modifier.glassSurface(radius, panel, spec.opacity), content = content)
}

/**
 * Fokus / stan aktywny v2: niebieski tint `#2F80ED` 18 % pod treścią, wyraźna błękitna ramka 2 dp (`#38BDF8`) i dwustopniowa
 * poświata `#2F80ED` poza elementem (słabsze urządzenia – bez poświaty). Bez fokusu nic nie rysuje; obiekty w `drawWithCache`.
 */
fun Modifier.glassFocusGlow(focused: Boolean, radius: Dp, lowPerf: Boolean = false, tint: Boolean = true): Modifier = drawWithCache {
    val sw = ObsidianGlass.FOCUS_STROKE_DP.dp.toPx(); val gap = 2.dp.toPx(); val gw = ObsidianGlass.GLOW_DP.dp.toPx()
    val ring = Stroke(sw); val glow1 = Stroke(gw); val glow2 = Stroke(gw * 2)
    val o = gap + sw / 2; val ringTopLeft = Offset(-o, -o); val ringSize = Size(size.width + 2 * o, size.height + 2 * o)
    val g1 = gap + sw + gw / 2; val g1TopLeft = Offset(-g1, -g1); val g1Size = Size(size.width + 2 * g1, size.height + 2 * g1)
    val g2 = gap + sw + gw * 2; val g2TopLeft = Offset(-g2, -g2); val g2Size = Size(size.width + 2 * g2, size.height + 2 * g2)
    val base = radius.toPx()
    val rr = CornerRadius(base + o); val gr1 = CornerRadius(base + g1); val gr2 = CornerRadius(base + g2); val fr = CornerRadius(base)
    val glowNear = ObsidianGlass.Tokens.Focus.copy(alpha = 0.55f); val glowFar = ObsidianGlass.Tokens.Focus.copy(alpha = 0.22f)
    val fill = ObsidianGlass.Tokens.FocusTint
    onDrawWithContent {
        if (focused && tint) drawRoundRect(fill, cornerRadius = fr)
        drawContent()
        if (!focused) return@onDrawWithContent
        if (!lowPerf) { drawRoundRect(glowFar, g2TopLeft, g2Size, gr2, style = glow2); drawRoundRect(glowNear, g1TopLeft, g1Size, gr1, style = glow1) }
        drawRoundRect(ObsidianGlass.Tokens.FocusRing, ringTopLeft, ringSize, rr, style = ring)
    }
}

/**
 * Pełny fokus v2 dla elementu Compose: [glassFocusGlow] + płynne powiększenie 1,03 w warstwie GPU (`graphicsLayer`), gdy włączone są
 * „Animacje przejść” (wyłączone – bez powiększenia).
 */
@androidx.compose.runtime.Composable
fun Modifier.obsidianFocus(focused: Boolean, radius: Dp, scale: Float = 1.03f): Modifier {
    val spec = LocalObsidianGlass.current
    val target = if (focused && spec.animations && !spec.lowPerf) scale else 1f
    val s = androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.spring(1f, 500f), label = "obsidianFocus")
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }.glassFocusGlow(focused, radius, spec.lowPerf)
}

/** Przycisk główny / aktywna zakładka (Compose): gradient `#2F80ED` → `#1D4ED8`. */
fun Modifier.obsidianPrimary(radius: Dp): Modifier = drawWithCache {
    val brush = Brush.linearGradient(listOf(ObsidianGlass.Tokens.Focus, ObsidianGlass.Tokens.PrimaryEnd), Offset.Zero, Offset(size.width, size.height))
    val r = CornerRadius(radius.toPx())
    onDrawBehind { drawRoundRect(brush, cornerRadius = r) }
}

/**
 * Tło ekranu Compose v2 (`ObsidianBackground`): gradient `#060913` → `#0B1222`, wyraźne miękkie poświaty – błękit `#2F80ED`
 * (prawy górny róg) i indygo `#5B21B6` (lewy dolny) – oraz winieta; wyrenderowane raz do bitmapy ([gradientLayer]).
 */
fun Modifier.obsidianGlassBackdrop(): Modifier = drawWithCache {
    val img = gradientLayer {
        val w = size.width; val h = size.height; val r = maxOf(w, h)
        drawRect(Brush.verticalGradient(listOf(ObsidianGlass.Tokens.BgTop, CColor(ObsidianGlass.BG_MID), ObsidianGlass.Tokens.BgBottom)))
        drawRect(Brush.radialGradient(listOf(ObsidianGlass.Tokens.Focus.copy(alpha = 0.42f), ObsidianGlass.Tokens.Focus.copy(alpha = 0.12f), CColor.Transparent), Offset(w * 0.92f, 0f), r * 0.6f))
        drawRect(Brush.radialGradient(listOf(ObsidianGlass.Tokens.Violet.copy(alpha = 0.45f), ObsidianGlass.Tokens.Violet.copy(alpha = 0.12f), CColor.Transparent), Offset(w * 0.04f, h), r * 0.6f))
        // (v2) mocna niebiesko-indygo poświata u góry ekranu (V2GlassSurface)
        drawRect(Brush.radialGradient(listOf(CColor(ObsidianGlass.GLOW_INDIGO).copy(alpha = 0.40f), CColor(ObsidianGlass.GLOW_INDIGO).copy(alpha = 0.10f), CColor.Transparent), Offset(w * 0.45f, -h * 0.05f), r * 0.55f))
        // (v2, ujednolicenie) fale świetlne w prawej części ekranu (V2WaveBackground) – w tej samej bitmapie, raz na rozmiar
        val waveBrush = Brush.linearGradient(listOf(ObsidianGlass.Tokens.Focus.copy(alpha = 0.55f), CColor(ObsidianGlass.WAVE_VIOLET).copy(alpha = 0.55f)),
            Offset(w * 0.5f, h), Offset(w, 0f))
        val u = minOf(w, h) / 540f
        for (i in 0 until V2Waves.COUNT) {
            val f = V2Waves.points(i)
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * f[0], h * f[1]); cubicTo(w * f[2], h * f[3], w * f[4], h * f[5], w * f[6], h * f[7]) }
            val a = V2Waves.alpha(i)
            drawPath(path, waveBrush, alpha = a * 0.18f, style = Stroke(10f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(path, waveBrush, alpha = a, style = Stroke(1.6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        drawRect(Brush.radialGradient(0f to CColor.Transparent, 0.65f to CColor.Transparent, 1f to CColor.Black.copy(alpha = 0.30f), center = Offset(w / 2, h / 2), radius = r * 0.8f))
    }
    onDrawBehind { img() }
}

/** (v2, ujednolicenie) `V2WaveBackground` w Compose – tło z falami świetlnymi ([obsidianGlassBackdrop]). */
fun Modifier.v2WaveBackground(): Modifier = obsidianGlassBackdrop()

/** Tło aplikacji v2 w Compose (`ObsidianBackground`, alias [obsidianGlassBackdrop]). */
fun Modifier.obsidianBackground(): Modifier = obsidianGlassBackdrop()

/**
 * Tło ekranów na widokach (`ObsidianBackground`): gradient `#060913` → `#0B1222`, poświata błękitna (prawy górny róg) i indygo
 * (lewy dolny) – wyraźne, miękkie – oraz winieta. Same cieniowania (bez bitmap), tworzone raz przy zmianie rozmiaru.
 */
/**
 * (v2, ujednolicenie) Geometria fal [V2WaveBackground] / [v2WaveBackground] (wspólna dla widoków i Compose): krzywe Béziera
 * w ułamkach ekranu (x0, y0, c1x, c1y, c2x, c2y, x1, y1) – od dołu środka ekranu łukiem ku prawej krawędzi.
 */
object V2Waves {
    const val COUNT = 5
    fun points(i: Int): FloatArray {
        val d = i * 0.07f
        return floatArrayOf(0.50f + d * 0.6f, 1.02f, 0.70f + d * 0.3f, 0.86f - d, 0.86f, 0.42f - d * 0.8f, 1.03f, 0.18f - d * 0.9f)
    }
    /** Krycie fali [i] (0–1): najbliższa najjaśniejsza. */
    fun alpha(i: Int): Float = 0.55f - i * 0.09f
}

/** (v2, ujednolicenie) Dawna nazwa tła ekranów – teraz [V2WaveBackground] (z falami świetlnymi). */
typealias GlassBackdrop = V2WaveBackground

/**
 * (v2, ujednolicenie) `V2WaveBackground` – tło ekranów na widokach (Ustawienia, Live TV, Program TV, Edytor, Stremio, STube):
 * granat `#050811` → `#090F1E`, poświaty, winieta i abstrakcyjne fale / łuki świetlne w prawej części ekranu (błękit `#2F80ED`
 * → fiolet `#6D28D9`). Ścieżki i cieniowania tworzone raz przy zmianie rozmiaru; [draw] – samo rysowanie (bez alokacji).
 */
class V2WaveBackground : Drawable() {
    private val waves = ArrayList<android.graphics.Path>()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val waveGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val lin = Paint(Paint.DITHER_FLAG)
    private val indigo = Paint(Paint.DITHER_FLAG)
    private val blue = Paint(Paint.DITHER_FLAG)
    private val violet = Paint(Paint.DITHER_FLAG)
    private val vig = Paint(Paint.DITHER_FLAG)
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat(); val m = maxOf(w, h)
        lin.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(ObsidianGlass.BG_TOP, ObsidianGlass.BG_MID, ObsidianGlass.BG_BOTTOM), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        blue.shader = RadialGradient(b.left + w * 0.92f, b.top.toFloat(), m * 0.6f,
            intArrayOf(ObsidianGlass.withAlpha(ObsidianGlass.GLOW_BLUE, 0x6B), ObsidianGlass.withAlpha(ObsidianGlass.GLOW_BLUE, 0x1F), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        // (v2) mocna niebiesko-indygo poświata u góry ekranu
        indigo.shader = RadialGradient(b.left + w * 0.45f, b.top - h * 0.05f, m * 0.55f,
            intArrayOf(ObsidianGlass.withAlpha(ObsidianGlass.GLOW_INDIGO, 0x66), ObsidianGlass.withAlpha(ObsidianGlass.GLOW_INDIGO, 0x1A), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        violet.shader = RadialGradient(b.left + w * 0.04f, b.bottom.toFloat(), m * 0.6f,
            intArrayOf(ObsidianGlass.withAlpha(ObsidianGlass.GLOW_VIOLET, 0x73), ObsidianGlass.withAlpha(ObsidianGlass.GLOW_VIOLET, 0x1F), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        vig.shader = RadialGradient(b.exactCenterX(), b.exactCenterY(), m * 0.8f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, ObsidianGlass.withAlpha(Color.BLACK, 0x4D)), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        // fale: kilka łuków od dołu ekranu ku prawej krawędzi (prawa ~45 % ekranu), coraz wyżej i słabiej
        waves.clear()
        for (i in 0 until V2Waves.COUNT) {
            val f = V2Waves.points(i)
            waves += android.graphics.Path().apply {
                moveTo(b.left + w * f[0], b.top + h * f[1])
                cubicTo(b.left + w * f[2], b.top + h * f[3], b.left + w * f[4], b.top + h * f[5], b.left + w * f[6], b.top + h * f[7])
            }
        }
        val waveShader = LinearGradient(b.left + w * 0.5f, b.bottom.toFloat(), b.right.toFloat(), b.top.toFloat(),
            intArrayOf(ObsidianGlass.withAlpha(ObsidianGlass.GLOW_BLUE, 0x8C), ObsidianGlass.withAlpha(ObsidianGlass.WAVE_VIOLET, 0x8C)), null, Shader.TileMode.CLAMP)
        wavePaint.shader = waveShader; waveGlow.shader = waveShader
        val u = minOf(w, h) / 540f
        wavePaint.strokeWidth = 1.6f * u; waveGlow.strokeWidth = 10f * u
    }

    override fun draw(c: Canvas) {
        lin.alpha = alphaMul; blue.alpha = alphaMul; indigo.alpha = alphaMul; violet.alpha = alphaMul; vig.alpha = alphaMul
        c.drawRect(bounds, lin); c.drawRect(bounds, indigo); c.drawRect(bounds, blue); c.drawRect(bounds, violet)
        for (i in waves.indices) {
            val a = V2Waves.alpha(i)
            waveGlow.alpha = (a * 0.18f * alphaMul).toInt(); c.drawPath(waves[i], waveGlow)
            wavePaint.alpha = (a * alphaMul).toInt(); c.drawPath(waves[i], wavePaint)
        }
        c.drawRect(bounds, vig)
    }

    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { lin.colorFilter = cf; blue.colorFilter = cf; indigo.colorFilter = cf; violet.colorFilter = cf; vig.colorFilter = cf
        wavePaint.colorFilter = cf; waveGlow.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * (v2) Szkło okien i paneli Nowoczesnego v2 (`V2GlassDialog`) – wyraźnie inne niż płaskie panele v1: pionowy gradient granatu
 * (jaśniejszy u góry), połysk górnej krawędzi, wewnętrzna błękitna poświata przy brzegu i świetlista krawędź 1,5 dp z gradientem
 * `#38BDF8` → fiolet `#6D28D9`. Cieniowania tworzone przy zmianie rozmiaru, [draw] bez alokacji; obrys (outline) do przycinania.
 */
class V2PanelDrawable(private val radius: Float, private val fill: Int) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = android.graphics.RectF(); private val inRect = android.graphics.RectF(); private val glowRect = android.graphics.RectF()
    private var alphaMul = 255
    private val bw = ObsidianGlass.borderPx().toFloat()

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        val a = Color.alpha(fill)
        val top = ObsidianGlass.withAlpha(Ui.blend(fill or (0xFF shl 24), 0xFF1A2B55.toInt(), 0.55f), a)
        fillPaint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, fill, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + Ui.dp(36), ObsidianGlass.withAlpha(Color.WHITE, 0x1C), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val g = Ui.dp(6).toFloat(); inner.strokeWidth = g
        glowRect.set(b.left + g / 2, b.top + g / 2, b.right - g / 2, b.bottom - g / 2)
        inner.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, ObsidianGlass.withAlpha(ObsidianGlass.FOCUS_RING, 0x22), ObsidianGlass.withAlpha(ObsidianGlass.WAVE_VIOLET, 0x14), Shader.TileMode.CLAMP)
        border.strokeWidth = bw
        inRect.set(b.left + bw / 2, b.top + bw / 2, b.right - bw / 2, b.bottom - bw / 2)
        border.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(ObsidianGlass.withAlpha(ObsidianGlass.FOCUS_RING, 0xB3), ObsidianGlass.withAlpha(Color.WHITE, 0x26), ObsidianGlass.withAlpha(ObsidianGlass.WAVE_VIOLET, 0x80)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        fillPaint.alpha = alphaMul; sheen.alpha = alphaMul; inner.alpha = alphaMul; border.alpha = alphaMul
        c.drawRoundRect(rect, radius, radius, fillPaint)
        c.drawRoundRect(rect, radius, radius, sheen)
        val gr = maxOf(0f, radius - inner.strokeWidth / 2)
        c.drawRoundRect(glowRect, gr, gr, inner)
        val br = maxOf(0f, radius - bw / 2)
        c.drawRoundRect(inRect, br, br, border)
    }

    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radius); outline.alpha = 0f }
    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fillPaint.colorFilter = cf; border.colorFilter = cf; inner.colorFilter = cf; sheen.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * (v2) Pływająca szklana wyspa na widokach: zaokrąglony prostokąt wypełniony [fill] i obramowanie z gradientem pionowym
 * ([borderTop] → [borderBottom]). Cieniowanie obramowania tworzone raz przy zmianie rozmiaru; rysowanie bez alokacji.
 */
class GlassIslandDrawable(private val radius: Float, private val fill: Int, private val borderTop: Int, private val borderBottom: Int,
                          private val borderW: Float) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = borderW }
    private val rect = android.graphics.RectF()
    private val inner = android.graphics.RectF()
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        val h = borderW / 2; inner.set(b.left + h, b.top + h, b.right - h, b.bottom - h)
        strokePaint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), borderTop, borderBottom, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        fillPaint.alpha = Color.alpha(fill) * alphaMul / 255; strokePaint.alpha = alphaMul
        c.drawRoundRect(rect, radius, radius, fillPaint)
        c.drawRoundRect(inner, radius - borderW / 2, radius - borderW / 2, strokePaint)
    }

    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radius); outline.alpha = 0f }
    override fun setAlpha(a: Int) { alphaMul = a; invalidateSelf() }
    override fun getAlpha() = alphaMul
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fillPaint.colorFilter = cf; strokePaint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * (v2) Pływająca szklana wyspa w Compose (pasek nawigacji START): tło `#0D1426` 72 %, obramowanie 1,5 dp z gradientem
 * `#38BDF8` 35 % → biel 6 %, zaokrąglenie [radius]. Pędzle w `drawWithCache`.
 */
fun Modifier.glassIsland(radius: Dp = ObsidianGlass.ISLAND_RADIUS_DP.dp): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx()); val sw = ObsidianGlass.BORDER_W.dp.toPx()
    val fill = CColor(ObsidianGlass.ISLAND_FILL)
    val border = Brush.verticalGradient(listOf(CColor(ObsidianGlass.ISLAND_BORDER_TOP), CColor(ObsidianGlass.ISLAND_BORDER_BOTTOM)))
    val stroke = Stroke(sw); val inset = Offset(sw / 2, sw / 2); val inner = Size(size.width - sw, size.height - sw)
    val ir = CornerRadius(radius.toPx() - sw / 2)
    onDrawBehind {
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(border, inset, inner, ir, style = stroke)
    }
}

/**
 * (v2, ujednolicenie) `V2GlassDialog` w Compose: `#0C1426` 78 % ([fill]; nakładki odtwarzacza – 72 %), zaokrąglenie 24 dp,
 * świetlista krawędź 1,5 dp `#38BDF8` 45 % → biel 12 %. Pędzle w `drawWithCache`.
 */
fun Modifier.v2GlassDialog(radius: Dp = ObsidianGlass.DIALOG_RADIUS_DP.dp, fill: Int = ObsidianGlass.DIALOG_FILL): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx()); val sw = ObsidianGlass.BORDER_W.dp.toPx()
    val f = CColor(fill)
    val border = Brush.verticalGradient(listOf(CColor(ObsidianGlass.DIALOG_BORDER_TOP), CColor(ObsidianGlass.DIALOG_BORDER_BOTTOM)))
    val stroke = Stroke(sw); val inset = Offset(sw / 2, sw / 2); val inner = Size(size.width - sw, size.height - sw)
    val ir = CornerRadius(maxOf(0f, radius.toPx() - sw / 2))
    onDrawBehind { drawRoundRect(f, cornerRadius = r); drawRoundRect(border, inset, inner, ir, style = stroke) }
}

/**
 * (v2, ujednolicenie) `V2PillTab` w Compose: spoczynek – szkło `#0E1629` 65 % z ramką 1 dp biel 14 %; fokus lub [active] –
 * tint `#2F80ED` 22 % + ramka 2 dp `#38BDF8` + poświata ([glassFocusGlow]).
 */
fun Modifier.v2PillTab(focused: Boolean, active: Boolean, radius: Dp, lowPerf: Boolean = false): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx()); val sw = 1.dp.toPx()
    val rest = CColor(ObsidianGlass.PILL_REST); val border = CColor(ObsidianGlass.PILL_BORDER)
    val stroke = Stroke(sw); val inset = Offset(sw / 2, sw / 2); val inner = Size(size.width - sw, size.height - sw)
    onDrawBehind { if (!focused && !active) { drawRoundRect(rest, cornerRadius = r); drawRoundRect(border, inset, inner, r, style = stroke) } }
}.glassFocusGlow(focused || active, radius, lowPerf)

/**
 * (v2, ujednolicenie) `V2GlowPillButton` w Compose: [primary] – gradient `#2F80ED` → `#38BDF8` z poświatą (fokus – biała ramka),
 * drugorzędny – ciemne szkło z ramką 1 dp biel 18 % (fokus – [glassFocusGlow]).
 */
fun Modifier.v2GlowPillButton(primary: Boolean, focused: Boolean, radius: Dp, lowPerf: Boolean = false): Modifier =
    if (!primary) drawWithCache {
        val r = CornerRadius(radius.toPx()); val sw = 1.dp.toPx(); val stroke = Stroke(sw)
        val inset = Offset(sw / 2, sw / 2); val inner = Size(size.width - sw, size.height - sw)
        val rest = CColor(ObsidianGlass.PILL_REST); val border = CColor(ObsidianGlass.SECONDARY_BORDER)
        onDrawBehind { drawRoundRect(rest, cornerRadius = r); if (!focused) drawRoundRect(border, inset, inner, r, style = stroke) }
    }.glassFocusGlow(focused, radius, lowPerf)
    else drawWithCache {
        val r = CornerRadius(radius.toPx())
        val brush = Brush.horizontalGradient(listOf(ObsidianGlass.Tokens.Focus, CColor(ObsidianGlass.CONFIRM_END)))
        val g = ObsidianGlass.GLOW_DP.dp.toPx(); val glow = Stroke(g * 2)
        val gTopLeft = Offset(-g, -g); val gSize = Size(size.width + 2 * g, size.height + 2 * g); val gr = CornerRadius(radius.toPx() + g)
        val ringW = 2.dp.toPx(); val ring = Stroke(ringW); val ri = Offset(ringW / 2, ringW / 2); val rs = Size(size.width - ringW, size.height - ringW)
        val glowColor = CColor(ObsidianGlass.CONFIRM_END).copy(alpha = if (focused) 0.45f else 0.22f)
        onDrawBehind {
            if (!lowPerf) drawRoundRect(glowColor, gTopLeft, gSize, gr, style = glow)
            drawRoundRect(brush, cornerRadius = r)
            if (focused) drawRoundRect(CColor.White, ri, rs, r, style = ring)
        }
    }

/**
 * (v2) `V2GlassSurface` – kontener karty „Obsidian Glass” w Compose: `#10192E` z kryciem wg „Przezroczystość UI” (Średnia 0,58),
 * połysk szkła, krawędź 1,5 dp; przy fokusie powiększenie 1,04 (`graphicsLayer`, gdy włączone animacje), niebieski tint i ramka
 * 2 dp `#38BDF8` z poświatą.
 */
@androidx.compose.runtime.Composable
fun V2GlassSurface(focused: Boolean, modifier: Modifier = Modifier, radius: Dp = ObsidianGlass.RADIUS_CARD_DP.dp,
                   content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val spec = LocalObsidianGlass.current
    androidx.compose.foundation.layout.Box(modifier.obsidianFocus(focused, radius, 1.04f).glassSurface(radius, false, spec.opacity), content = content)
}

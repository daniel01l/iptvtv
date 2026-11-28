package pl.iptv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.theme.ObsidianGlass

/**
 * (v2) Live TV w motywie Nowoczesny v2: szklane szuflady paneli bocznych ([edge]), szkło menu ikon ([MENU_FILL]), pierścień logo
 * w HUD ([Ring]) i ramka przełączania kanałów ▲▼ ([ZapOsd] – poprzedni / bieżący / następny: numer, nazwa, logo jako znak wodny;
 * bez programu EPG i bez parametrów obrazu).
 */
object LiveV2 {
    /** HUD wyśrodkowany u dołu: szerokość na TV, odstęp od dolnej krawędzi. */
    const val HUD_WIDTH = 0.70f
    const val HUD_BOTTOM_DP = 20
    /** Menu ikon po przytrzymaniu OK: `#0C1426` 76 %. */
    const val MENU_FILL = 0xC20C1426.toInt()
    /** Ramka ▲▼: czas widoczności, krycie sąsiednich wierszy i znaków wodnych logo. */
    const val ZAP_OSD_MS = 2_500L
    const val SIDE_ALPHA = 0.40f
    const val WATERMARK_CURRENT = 0.16f
    const val WATERMARK_SIDE = 0.08f
    const val ZAP_TAG = "live_v2_zap"

    /** Szuflada panelu bocznego: `#0C1426` 72 %, zaokrąglenie 24 dp tylko od strony ekranu, świetlista krawędź 1,5 dp. */
    fun edge(leftSide: Boolean): Drawable {
        // szkło V2PanelDrawable wysunięte poza krawędź ekranu (ujemny odstęp) – zaokrąglenie 24 dp tylko od strony obrazu
        val out = -Ui.dp(32)
        return NoPadInset(pl.iptv.tv.ui.theme.V2PanelDrawable(Ui.dp(24).toFloat(), ObsidianGlass.OVERLAY_FILL),
            if (leftSide) out else 0, 0, if (leftSide) 0 else out, 0)
    }

    /**
     * Wysunięte tło bez wpływu na odstępy panelu: zwykły InsetDrawable z ujemnym odstępem zgłaszał UJEMNY padding – widok przejmował
     * go jako własny i treść (numery kanałów, „Menu”, strzałki ◄) wyjeżdżała poza ekran. Tu padding widoku zostaje bez zmian.
     */
    class NoPadInset(d: Drawable, l: Int, t: Int, r: Int, b: Int) : android.graphics.drawable.InsetDrawable(d, l, t, r, b) {
        override fun getPadding(padding: Rect): Boolean { padding.set(0, 0, 0, 0); return false }
    }

    /** Okrągły świecący pierścień logo kanału: wypełnienie szkła, obwódka `#2F80ED` → `#38BDF8` (gradient kołowy) i poświata. */
    class Ring : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ObsidianGlass.withAlpha(ObsidianGlass.SURFACE_TOP, 0xCC) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var cx = 0f; private var cy = 0f; private var rad = 0f
        override fun onBoundsChange(b: Rect) {
            cx = b.exactCenterX(); cy = b.exactCenterY()
            val w = Ui.dp(3).toFloat(); ring.strokeWidth = w; glow.strokeWidth = w * 3
            rad = minOf(b.width(), b.height()) / 2f - w * 1.5f
            val sh = SweepGradient(cx, cy, intArrayOf(ObsidianGlass.FOCUS, ObsidianGlass.FOCUS_RING, ObsidianGlass.FOCUS), null)
            ring.shader = sh; glow.shader = sh
        }
        override fun draw(c: Canvas) {
            c.drawCircle(cx, cy, rad, fill)
            if (!pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) { glow.alpha = 0x40; c.drawCircle(cx, cy, rad, glow) }
            c.drawCircle(cx, cy, rad, ring)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ring.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf; ring.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Ramka przełączania kanałów ▲▼: szkło 20 dp z krawędzią 1,5 dp, trzy wiersze (poprzedni, bieżący, następny) – numer po lewej,
     * nazwa obok, w tle przycięte logo kanału jako znak wodny (16 % / 8 %). Bieżący – ramka 2 dp `#38BDF8` z poświatą, sąsiednie –
     * krycie 40 %. Bez fokusu (pilot steruje jak dotąd), chowana po [ZAP_OSD_MS].
     */
    class ZapOsd(c: Context) : LinearLayout(c) {
        private class Row(val box: FrameLayout, val logo: ImageView, val num: TextView, val name: TextView)
        private val rows: List<Row>
        private val hide = Runnable { visibility = View.GONE }
        /** Klucze kanałów w wierszach (testy). */
        var shownKeys: List<String> = emptyList(); private set

        init {
            orientation = VERTICAL; tag = ZAP_TAG; visibility = View.GONE; isFocusable = false; clipChildren = false; clipToPadding = false
            background = ObsidianGlass.glassDialog(20, ObsidianGlass.OVERLAY_FILL)
            val p = Ui.dp(10); setPadding(p, p, p, p)
            rows = (0..2).map { i ->
                val current = i == 1
                val box = FrameLayout(c).apply { clipToOutline = true
                    background = if (current) ObsidianGlass.tintRing(16, pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) else ObsidianGlass.pillRest(16) }
                val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if (current) WATERMARK_CURRENT else WATERMARK_SIDE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                box.addView(logo, FrameLayout.LayoutParams(Ui.dp(160), -1, Gravity.END or Gravity.CENTER_VERTICAL))
                val line = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(18), 0, Ui.dp(18), 0) }
                val num = TextView(c).apply { textSize = if (current) 20f else 17f; setTextColor(ObsidianGlass.FOCUS_RING); typeface = Ui.typeface(android.graphics.Typeface.BOLD); minWidth = Ui.dp(40); tag = "keepcolor" }
                val name = TextView(c).apply { textSize = if (current) 20f else 17f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "keepcolor"
                    if (current) typeface = Ui.typeface(android.graphics.Typeface.BOLD) }
                line.addView(num); line.addView(name, LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(12) })
                box.addView(line, FrameLayout.LayoutParams(-1, -1))
                if (!current) box.alpha = SIDE_ALPHA
                addView(box, LayoutParams(-1, Ui.dp(if (current) 60 else 50)).apply { if (i < 2) bottomMargin = Ui.dp(8) })
                Row(box, logo, num, name)
            }
        }

        /** Pokazuje [prev] / [cur] / [next] (bez programu i jakości obrazu); [numbers] – numery kanałów (Ustawienia). */
        fun show(prev: Channel?, cur: Channel, next: Channel?, numbers: Boolean) {
            val list = listOf(prev, cur, next)
            shownKeys = list.map { it?.key.orEmpty() }
            rows.forEachIndexed { i, r ->
                val ch = list[i]
                r.box.visibility = if (ch == null) View.INVISIBLE else View.VISIBLE
                if (ch == null) return@forEachIndexed
                r.num.text = if (numbers) ch.number.toString() else ""; r.num.visibility = if (numbers) View.VISIBLE else View.GONE
                r.name.text = ch.name
                if (r.logo.tag != ch.key) { r.logo.tag = ch.key; r.logo.loadPicon(ch) }
            }
            visibility = View.VISIBLE
            removeCallbacks(hide); postDelayed(hide, ZAP_OSD_MS)
        }

        fun dismiss() { removeCallbacks(hide); visibility = View.GONE }
    }
}

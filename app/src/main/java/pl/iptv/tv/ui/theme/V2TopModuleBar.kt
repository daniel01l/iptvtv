package pl.iptv.tv.ui.theme

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.iptv.tv.ui.Ui

/**
 * (v2, ujednolicenie) `V2TopModuleBar` – czysta górna belka ekranów modułowych motywu Nowoczesny v2 (Ustawienia, Live TV,
 * Program TV, Edytor listy, Stremio, STube): po lewej logo i „D-iptv Player” • nazwa sekcji, po prawej zegar (HH:mm, odświeżany
 * co [CLOCK_MS] tylko, gdy belka jest na ekranie) i ikona profilu. Belka nie przejmuje fokusu pilota (nawigacja ekranów bez
 * zmian); ikona profilu działa dotykiem. Odstęp na pasek stanu (telefon) z wstawek okna.
 */
object V2TopModuleBar {
    const val TAG = "v2_top_bar"
    const val SECTION_TAG = "v2_top_section"
    const val CLOCK_TAG = "v2_top_clock"
    const val PROFILE_TAG = "v2_top_profile"
    const val APP_NAME = "D-iptv Player"
    const val PROFILE_LABEL = "Profil"
    const val CLOCK_MS = 20_000L

    fun create(a: Activity, section: String, onProfile: () -> Unit): LinearLayout {
        val compact = a.resources.configuration.screenHeightDp < 520 || a.resources.configuration.screenWidthDp < 600
        val bar = LinearLayout(a).apply {
            tag = TAG; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val side = Ui.dp(if (compact) 14 else 28)
            setPadding(side, Ui.dp(if (compact) 6 else 10), side, Ui.dp(if (compact) 4 else 6))
        }
        val logo = Ui.dp(if (compact) 24 else 28)
        bar.addView(ImageView(a).apply { setImageDrawable(LogoDrawable()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(logo, logo))
        bar.addView(TextView(a).apply {
            text = APP_NAME; textSize = if (compact) 15f else 17f; setTextColor(Color.WHITE); tag = "keepcolor"
            typeface = Ui.typeface(android.graphics.Typeface.BOLD); maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
        bar.addView(TextView(a).apply {
            text = "•"; textSize = if (compact) 14f else 16f; setTextColor(ObsidianGlass.FOCUS_RING); tag = "keepcolor"
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10); marginEnd = Ui.dp(10) })
        bar.addView(TextView(a).apply {
            text = section; textSize = if (compact) 14f else 16f; setTextColor(ObsidianGlass.TEXT2); tag = "keepcolor $SECTION_TAG"; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val clock = TextView(a).apply {
            textSize = if (compact) 14f else 17f; setTextColor(Color.WHITE); tag = "keepcolor $CLOCK_TAG"
            typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        }
        bar.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(14) })
        val p = Ui.dp(if (compact) 30 else 34)
        bar.addView(ImageView(a).apply {
            tag = PROFILE_TAG; contentDescription = PROFILE_LABEL; setImageDrawable(ProfileDrawable())
            isFocusable = false; isClickable = true; setOnClickListener { onProfile() }
        }, LinearLayout.LayoutParams(p, p))
        // zegar: odświeżany tylko, gdy belka jest przypięta do okna
        val tick = object : Runnable {
            override fun run() { clock.text = pl.iptv.tv.ui.Fmt.time(System.currentTimeMillis()); clock.postDelayed(this, CLOCK_MS) }
        }
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { clock.removeCallbacks(tick); tick.run() }
            override fun onViewDetachedFromWindow(v: View) { clock.removeCallbacks(tick) }
        })
        clock.text = pl.iptv.tv.ui.Fmt.time(System.currentTimeMillis())
        // telefon: belka pod paskiem stanu (TV / ukryte paski – odstęp 0)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { v, ins ->
            val top = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
            val base = Ui.dp(if (compact) 6 else 10)
            if (v.paddingTop != base + top) v.setPadding(v.paddingLeft, base + top, v.paddingRight, v.paddingBottom)
            ins
        }
        return bar
    }

    /** Logo: zaokrąglony kwadrat z gradientem `#2F80ED` → `#6D28D9` i białym trójkątem „odtwórz”. */
    class LogoDrawable : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF(); private val tri = Path()
        override fun onBoundsChange(b: Rect) {
            r.set(b); fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, ObsidianGlass.FOCUS, ObsidianGlass.WAVE_VIOLET, Shader.TileMode.CLAMP)
            val w = r.width(); val h = r.height()
            tri.reset(); tri.moveTo(r.left + w * 0.38f, r.top + h * 0.28f); tri.lineTo(r.left + w * 0.38f, r.top + h * 0.72f); tri.lineTo(r.left + w * 0.74f, r.top + h * 0.5f); tri.close()
        }
        override fun draw(c: Canvas) { val rad = r.width() * 0.27f; c.drawRoundRect(r, rad, rad, fill); c.drawPath(tri, ink) }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ink.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf; ink.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Ikona profilu: koło z gradientem i krawędzią `#38BDF8`, biała sylwetka (głowa + ramiona). */
    class ProfileDrawable : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ObsidianGlass.withAlpha(ObsidianGlass.FOCUS_RING, 0xB0) }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF(); private val body = RectF()
        override fun onBoundsChange(b: Rect) {
            r.set(b); fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, ObsidianGlass.FOCUS, ObsidianGlass.WAVE_VIOLET, Shader.TileMode.CLAMP)
            ring.strokeWidth = maxOf(1f, r.width() * 0.05f)
            body.set(r.left + r.width() * 0.24f, r.top + r.height() * 0.58f, r.right - r.width() * 0.24f, r.bottom + r.height() * 0.2f)
        }
        override fun draw(c: Canvas) {
            val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2
            c.drawCircle(cx, cy, rad, fill)
            c.save(); c.clipRect(r)
            c.drawCircle(cx, r.top + r.height() * 0.4f, r.width() * 0.16f, ink)
            c.drawArc(body, 180f, 180f, true, ink)
            c.restore()
            c.drawCircle(cx, cy, rad - ring.strokeWidth / 2, ring)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ink.alpha = alpha; ring.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf; ink.colorFilter = cf; ring.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

package pl.iptv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import pl.iptv.tv.data.Programme

/** Pasek postępu programu rysowany w stylu wybranym w ustawieniach. */
class ProgView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun show(p: Programme?, now: Long) {
        if (p == null || Ui.progressStyle == 4) { visibility = GONE; return }
        visibility = VISIBLE
        fraction = (now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)
    }

    /** Wypełnienie kolorem albo gradientem akcentu (na całej szerokości paska). */
    private fun fillPaint(c1: Int, c2: Int?, w: Float) {
        paint.color = c1
        paint.shader = if (c2 == null) null else android.graphics.LinearGradient(0f, 0f, w, 0f, c1, c2, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val track = Ui.withAlpha(Ui.text2, 0x50)
        val fill = Ui.accent
        val fill2 = Ui.color2("accent")
        paint.shader = null
        when (Ui.progressStyle) {
            0 -> { // cienka linia
                val lh = Ui.dp(3).toFloat().coerceAtMost(h); val top = (h - lh) / 2
                paint.color = track; canvas.drawRect(0f, top, w, top + lh, paint)
                fillPaint(fill, fill2, w); canvas.drawRect(0f, top, w * fraction, top + lh, paint); paint.shader = null
            }
            1 -> { // gruby
                paint.color = track; canvas.drawRect(0f, 0f, w, h, paint)
                fillPaint(fill, fill2, w); canvas.drawRect(0f, 0f, w * fraction, h, paint); paint.shader = null
            }
            2 -> { // zaokrąglony
                val rad = h / 2
                paint.color = track; r.set(0f, 0f, w, h); canvas.drawRoundRect(r, rad, rad, paint)
                if (fraction > 0) { fillPaint(fill, fill2, w); r.set(0f, 0f, (w * fraction).coerceAtLeast(h), h); canvas.drawRoundRect(r, rad, rad, paint); paint.shader = null }
            }
            3 -> { // segmenty jak w starych dekoderach
                val n = 12; val gap = Ui.dp(2).toFloat()
                val sw = (w - gap * (n - 1)) / n
                val filled = fraction * n
                for (i in 0 until n) {
                    val x = i * (sw + gap)
                    paint.color = track; canvas.drawRect(x, 0f, x + sw, h, paint)
                    val part = (filled - i).coerceIn(0f, 1f)
                    if (part > 0) { fillPaint(fill, fill2, w); canvas.drawRect(x, 0f, x + sw * part, h, paint); paint.shader = null }
                }
            }
        }
    }
}

package pl.iptv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

/**
 * (7.23) Pigułka „Proponowane / Co następuje” w odtwarzaczu STube: pojawia się dopiero przy [YtPlayerActivity.UP_NEXT_FRACTION]
 * długości utworu, w dolnej części obrazu po stronie następnego utworu (prawa), na [durationMs]. Obramowanie w kolorze akcentu
 * „ładuje się” płynnie wokół pigułki przez cały ten czas ([progress] 0 → 1, od środka górnej krawędzi zgodnie z ruchem wskazówek),
 * po czym pigułka sama znika. Nie zależy od widoczności kontrolek.
 */
class UpNextPill(c: Context) : TextView(c) {
    companion object { const val TAG = "yt_up_next_pill"; const val RADIUS_DP = 22; const val STROKE_DP = 3
        /** Krok rysowania obramowania (ok. 30 kl/s – płynnie, niski koszt). */
        const val FRAME_MS = 33L }

    private val stroke = Ui.dp(STROKE_DP).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Ui.withAlpha(0xFFFFFFFF.toInt(), 0x33) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = Ui.accent or 0xFF000000.toInt() }
    private val outline = Path(); private val part = Path(); private val measure = PathMeasure()
    private var outlineLen = 0f
    /** Początek wyświetlania (uptime) – postęp liczony z zegara, niezależnie od skali animacji systemu. */
    private var startedAt = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE) return
            progress = ((android.os.SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
            invalidate()
            if (progress >= 1f) hide() else postDelayed(this, FRAME_MS)
        }
    }
    private val hideLater = Runnable { hide() }
    /** Postęp obramowania 0–1 (testy). */
    var progress = 0f; private set
    /** Pigułka na ekranie. */
    val shown get() = visibility == View.VISIBLE
    var durationMs = YtPlayerActivity.UP_NEXT_MS

    init {
        tag = TAG; visibility = View.GONE; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); isClickable = true
        setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10))
        background = GradientDrawable().apply { setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xD8101010.toInt())); cornerRadius = Ui.dp(RADIUS_DP).toFloat() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val half = stroke / 2
        val r = RectF(half, half, w - half, h - half)
        val rad = minOf(Ui.dp(RADIUS_DP).toFloat(), r.height() / 2)
        outline.reset()
        // od środka górnej krawędzi, zgodnie z ruchem wskazówek zegara
        outline.moveTo(r.centerX(), r.top)
        outline.lineTo(r.right - rad, r.top); outline.arcTo(RectF(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad), -90f, 90f)
        outline.lineTo(r.right, r.bottom - rad); outline.arcTo(RectF(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom), 0f, 90f)
        outline.lineTo(r.left + rad, r.bottom); outline.arcTo(RectF(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom), 90f, 90f)
        outline.lineTo(r.left, r.top + rad); outline.arcTo(RectF(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad), 180f, 90f)
        outline.lineTo(r.centerX(), r.top)
        measure.setPath(outline, false); outlineLen = measure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (outlineLen <= 0f) return
        canvas.drawPath(outline, track)
        part.reset()
        if (measure.getSegment(0f, outlineLen * progress, part, true)) canvas.drawPath(part, fill)
    }

    /**
     * Pokazuje pigułkę z tekstem [text] na [durationMs]: obramowanie ładuje się płynnie (klatka po klatce, z zegara) od 0 do pełna,
     * potem [hide] – także gdy systemowa skala animacji jest wyłączona (zapasowe ukrycie po czasie).
     */
    fun start(text: CharSequence) {
        this.text = text
        removeCallbacks(frame); removeCallbacks(hideLater)
        progress = 0f; visibility = View.VISIBLE; alpha = 1f
        startedAt = android.os.SystemClock.uptimeMillis()
        post(frame)
        postDelayed(hideLater, durationMs + 50)
    }

    fun hide() {
        removeCallbacks(frame); removeCallbacks(hideLater)
        visibility = View.GONE; progress = 0f
    }

    override fun onDetachedFromWindow() { removeCallbacks(frame); removeCallbacks(hideLater); super.onDetachedFromWindow() }
}

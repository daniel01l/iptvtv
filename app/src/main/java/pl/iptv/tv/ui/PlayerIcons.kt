package pl.iptv.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Minimalistyczne ikony odtwarzaczy (STube w stylu YouTube na TV, Stremio w stylu jego odtwarzacza): cienkie, zaokrąglone linie
 * rysowane na płótnie – ostre w każdym rozmiarze, bez emoji (różnie wyglądających na różnych TV) i bez bitmap.
 * Kolor z [ColorStateList] (stan widoku) – na zaznaczonej kapsule ikona ciemna, na zwykłej biała.
 */
class PlayerIcon(private val kind: String, private val tint: ColorStateList = ColorStateList.valueOf(Color.WHITE)) : Drawable() {
    companion object {
        const val SPEED = "speed"; const val QUALITY = "quality"; const val AUDIO = "audio"; const val CC = "cc"; const val EQ = "eq"
        const val CHAPTERS = "chapters"; const val QUEUE = "queue"; const val PIP = "pip"; const val AUTOPLAY = "autoplay"; const val NEXT = "next"
        const val DELAY = "delay"; const val STYLE = "style"; const val ASPECT = "aspect"; const val INFO = "info"; const val RELATED = "related"
        // (7.5) sterowanie odtwarzaczem Stremio (dolny pasek) i tryb samego dźwięku STube
        const val PLAY = "play"; const val PAUSE = "pause"; const val PREV = "prev"; const val REW = "rew"; const val FWD = "fwd"; const val AUDIO_ONLY = "audio_only"
        // (7.9) widok muzyczny STube: losowo, powtarzanie, tekst piosenki, teledysk
        const val SHUFFLE = "shuffle"; const val REPEAT = "repeat"; const val REPEAT_ONE = "repeat_one"; const val LYRICS = "lyrics"; const val VIDEO = "video"; const val MUSIC = "music"; const val STOP = "stop"
        // (7.10) pasek ikon STube
        const val SEARCH = "search"; const val HOME = "home"; const val PHONE = "phone"; const val REFRESH = "refresh"; const val CLOSE = "close"; const val USER = "user"
        val ALL = listOf(SPEED, QUALITY, AUDIO, CC, EQ, CHAPTERS, QUEUE, PIP, AUTOPLAY, NEXT, DELAY, STYLE, ASPECT, INFO, RELATED,
            PLAY, PAUSE, PREV, REW, FWD, AUDIO_ONLY, SHUFFLE, REPEAT, REPEAT_ONE, LYRICS, VIDEO, MUSIC, STOP, SEARCH, HOME, PHONE, REFRESH, CLOSE, USER)
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path(); private val r = RectF()
    private var color = tint.defaultColor

    override fun isStateful() = tint.isStateful
    override fun onStateChange(state: IntArray): Boolean {
        val c = tint.getColorForState(state, tint.defaultColor)
        if (c == color) return false
        color = c; return true
    }
    override fun getIntrinsicWidth() = Ui.dp(22)
    override fun getIntrinsicHeight() = Ui.dp(22)

    override fun draw(c: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()).toFloat()
        val ox = b.left + (b.width() - s) / 2; val oy = b.top + (b.height() - s) / 2
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        p.color = color; f.color = color; p.strokeWidth = s * 0.085f
        fun line(a: Float, b2: Float, cc: Float, d: Float) = c.drawLine(x(a), y(b2), x(cc), y(d), p)
        fun rect(l: Float, t: Float, rr: Float, bb: Float, rad: Float) { r.set(x(l), y(t), x(rr), y(bb)); c.drawRoundRect(r, rad * s, rad * s, p) }
        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
            path.reset(); path.moveTo(x(ax), y(ay)); path.lineTo(x(bx), y(by)); path.lineTo(x(cx), y(cy)); path.close(); c.drawPath(path, f)
        }
        when (kind) {
            SPEED -> { r.set(x(0.1f), y(0.18f), x(0.9f), y(0.98f)); c.drawArc(r, 160f, 220f, false, p); line(0.5f, 0.58f, 0.7f, 0.36f); c.drawCircle(x(0.5f), y(0.58f), s * 0.06f, f) }
            QUALITY -> { rect(0.08f, 0.22f, 0.92f, 0.78f, 0.1f); f.textSize = s * 0.34f; f.isFakeBoldText = true; f.textAlign = Paint.Align.CENTER
                c.drawText("HD", x(0.5f), y(0.62f), f) }
            AUDIO -> { path.reset(); path.moveTo(x(0.12f), y(0.38f)); path.lineTo(x(0.3f), y(0.38f)); path.lineTo(x(0.5f), y(0.18f)); path.lineTo(x(0.5f), y(0.82f))
                path.lineTo(x(0.3f), y(0.62f)); path.lineTo(x(0.12f), y(0.62f)); path.close(); c.drawPath(path, p)
                r.set(x(0.5f), y(0.3f), x(0.78f), y(0.7f)); c.drawArc(r, -60f, 120f, false, p); r.set(x(0.5f), y(0.16f), x(0.94f), y(0.84f)); c.drawArc(r, -55f, 110f, false, p) }
            CC -> { rect(0.08f, 0.2f, 0.92f, 0.8f, 0.12f); f.textSize = s * 0.32f; f.isFakeBoldText = true; f.textAlign = Paint.Align.CENTER; c.drawText("CC", x(0.5f), y(0.61f), f) }
            EQ -> { for ((i, k) in listOf(0.35f, 0.65f, 0.45f).withIndex()) { val xx = 0.22f + i * 0.28f; line(xx, 0.14f, xx, 0.86f); c.drawCircle(x(xx), y(k), s * 0.09f, f) } }
            CHAPTERS -> { for (i in 0..2) { line(0.32f, 0.26f + i * 0.24f, 0.88f, 0.26f + i * 0.24f); c.drawCircle(x(0.15f), y(0.26f + i * 0.24f), s * 0.05f, f) } }
            QUEUE, RELATED -> { line(0.1f, 0.24f, 0.62f, 0.24f); line(0.1f, 0.5f, 0.62f, 0.5f); line(0.1f, 0.76f, 0.46f, 0.76f); tri(0.62f, 0.6f, 0.62f, 0.92f, 0.92f, 0.76f) }
            PIP -> { rect(0.06f, 0.16f, 0.94f, 0.84f, 0.08f); r.set(x(0.5f), y(0.5f), x(0.84f), y(0.74f)); c.drawRect(r, f) }
            AUTOPLAY -> { r.set(x(0.1f), y(0.1f), x(0.9f), y(0.9f)); c.drawArc(r, -60f, 300f, false, p); tri(0.62f, 0.02f, 0.62f, 0.3f, 0.86f, 0.16f); tri(0.4f, 0.32f, 0.4f, 0.68f, 0.68f, 0.5f) }
            NEXT -> { tri(0.12f, 0.18f, 0.12f, 0.82f, 0.62f, 0.5f); line(0.8f, 0.2f, 0.8f, 0.8f) }
            DELAY -> { c.drawCircle(x(0.5f), y(0.54f), s * 0.38f, p); line(0.5f, 0.54f, 0.5f, 0.32f); line(0.5f, 0.54f, 0.66f, 0.62f); line(0.4f, 0.06f, 0.6f, 0.06f) }
            STYLE -> { f.textSize = s * 0.62f; f.isFakeBoldText = false; f.textAlign = Paint.Align.CENTER; c.drawText("Aa", x(0.5f), y(0.74f), f) }
            ASPECT -> { rect(0.06f, 0.2f, 0.94f, 0.8f, 0.08f); line(0.2f, 0.36f, 0.2f, 0.5f); line(0.2f, 0.36f, 0.34f, 0.36f); line(0.8f, 0.64f, 0.8f, 0.5f); line(0.8f, 0.64f, 0.66f, 0.64f) }
            INFO -> { c.drawCircle(x(0.5f), y(0.5f), s * 0.4f, p); line(0.5f, 0.46f, 0.5f, 0.72f); c.drawCircle(x(0.5f), y(0.3f), s * 0.055f, f) }
            PLAY -> tri(0.26f, 0.14f, 0.26f, 0.86f, 0.86f, 0.5f)
            PAUSE -> { r.set(x(0.22f), y(0.16f), x(0.42f), y(0.84f)); c.drawRoundRect(r, s * 0.04f, s * 0.04f, f); r.set(x(0.58f), y(0.16f), x(0.78f), y(0.84f)); c.drawRoundRect(r, s * 0.04f, s * 0.04f, f) }
            PREV -> { tri(0.88f, 0.18f, 0.88f, 0.82f, 0.38f, 0.5f); line(0.2f, 0.2f, 0.2f, 0.8f) }
            REW -> { tri(0.52f, 0.2f, 0.52f, 0.8f, 0.1f, 0.5f); tri(0.92f, 0.2f, 0.92f, 0.8f, 0.5f, 0.5f) }
            FWD -> { tri(0.08f, 0.2f, 0.08f, 0.8f, 0.5f, 0.5f); tri(0.48f, 0.2f, 0.48f, 0.8f, 0.9f, 0.5f) }
            AUDIO_ONLY -> { r.set(x(0.14f), y(0.14f), x(0.86f), y(0.86f)); c.drawArc(r, 180f, 180f, false, p); rect(0.1f, 0.5f, 0.3f, 0.86f, 0.06f); rect(0.7f, 0.5f, 0.9f, 0.86f, 0.06f) }
            SHUFFLE -> { line(0.08f, 0.28f, 0.34f, 0.28f); line(0.34f, 0.28f, 0.62f, 0.72f); line(0.62f, 0.72f, 0.78f, 0.72f); line(0.08f, 0.72f, 0.34f, 0.72f); line(0.34f, 0.72f, 0.62f, 0.28f); line(0.62f, 0.28f, 0.78f, 0.28f)
                tri(0.78f, 0.14f, 0.78f, 0.42f, 0.95f, 0.28f); tri(0.78f, 0.58f, 0.78f, 0.86f, 0.95f, 0.72f) }
            REPEAT, REPEAT_ONE -> { rect(0.1f, 0.26f, 0.9f, 0.74f, 0.2f); tri(0.62f, 0.1f, 0.62f, 0.42f, 0.84f, 0.26f); tri(0.38f, 0.58f, 0.38f, 0.9f, 0.16f, 0.74f)
                if (kind == REPEAT_ONE) { f.textSize = s * 0.34f; f.isFakeBoldText = true; f.textAlign = Paint.Align.CENTER; c.drawText("1", x(0.5f), y(0.62f), f) } }
            LYRICS -> { line(0.1f, 0.2f, 0.9f, 0.2f); line(0.1f, 0.42f, 0.9f, 0.42f); line(0.1f, 0.64f, 0.66f, 0.64f); line(0.1f, 0.86f, 0.48f, 0.86f) }
            VIDEO -> { rect(0.06f, 0.2f, 0.94f, 0.8f, 0.1f); tri(0.42f, 0.34f, 0.42f, 0.66f, 0.7f, 0.5f) }
            SEARCH -> { c.drawCircle(x(0.42f), y(0.42f), s * 0.28f, p); line(0.63f, 0.63f, 0.9f, 0.9f) }
            HOME -> { line(0.08f, 0.5f, 0.5f, 0.12f); line(0.5f, 0.12f, 0.92f, 0.5f); line(0.2f, 0.42f, 0.2f, 0.88f); line(0.8f, 0.42f, 0.8f, 0.88f); line(0.2f, 0.88f, 0.8f, 0.88f); line(0.42f, 0.88f, 0.42f, 0.62f); line(0.42f, 0.62f, 0.58f, 0.62f); line(0.58f, 0.62f, 0.58f, 0.88f) }
            PHONE -> { rect(0.28f, 0.06f, 0.72f, 0.94f, 0.1f); line(0.44f, 0.82f, 0.56f, 0.82f) }
            REFRESH -> { r.set(x(0.14f), y(0.14f), x(0.86f), y(0.86f)); c.drawArc(r, 40f, 290f, false, p); tri(0.78f, 0.04f, 0.78f, 0.36f, 0.5f, 0.2f) }
            CLOSE -> { line(0.2f, 0.2f, 0.8f, 0.8f); line(0.8f, 0.2f, 0.2f, 0.8f) }
            USER -> { c.drawCircle(x(0.5f), y(0.33f), s * 0.18f, p); r.set(x(0.16f), y(0.58f), x(0.84f), y(1.2f)); c.drawArc(r, 180f, 180f, false, p) }
            STOP -> { r.set(x(0.2f), y(0.2f), x(0.8f), y(0.8f)); c.drawRoundRect(r, s * 0.08f, s * 0.08f, f) }
            MUSIC -> { line(0.38f, 0.2f, 0.38f, 0.72f); line(0.38f, 0.2f, 0.84f, 0.1f); line(0.84f, 0.1f, 0.84f, 0.6f); c.drawCircle(x(0.28f), y(0.76f), s * 0.12f, f); c.drawCircle(x(0.74f), y(0.66f), s * 0.12f, f) }
            else -> c.drawCircle(x(0.5f), y(0.5f), s * 0.3f, p)
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a; f.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Kapsuły przycisków w nakładkach odtwarzaczy: półprzezroczyste, zaznaczona – biała z ciemną ikoną i tekstem (jak YouTube na TV). */
object PlayerChrome {
    val INK = Color.parseColor("#0F0F0F")
    /** Czerwień paska postępu (styl YouTube). */
    val YT_RED = Color.parseColor("#FF0033")
    /** Fiolet Stremio (pasek postępu, akcent). */
    val STREMIO = Color.parseColor("#7B5BF5")

    fun colors() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(INK, INK, Color.WHITE))

    fun pillBg(): Drawable = StateListDrawable().apply {
        val rad = Ui.dp(24).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = rad; setColor(Color.WHITE) })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = rad; setColor(Color.WHITE) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = rad; setColor(0x33FFFFFF) })
        setEnterFadeDuration(90); setExitFadeDuration(90)
    }

    /**
     * Przycisk-kapsuła z ikoną i podpisem. Zaznaczony – lekko powiększony (animacja w warstwie, bez nowego układu).
     * [label] puste – sama okrągła ikona.
     */
    fun button(c: Context, icon: String, label: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label; textSize = 15f; isFocusable = true; isClickable = true; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
        setTextColor(colors()); typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        background = pillBg()
        val d = PlayerIcon(icon, colors()).apply { setBounds(0, 0, Ui.dp(20), Ui.dp(20)) }
        setCompoundDrawables(d, null, null, null); compoundDrawablePadding = if (label.isEmpty()) 0 else Ui.dp(9)
        setPadding(Ui.dp(if (label.isEmpty()) 12 else 16), Ui.dp(9), Ui.dp(if (label.isEmpty()) 12 else 18), Ui.dp(9))
        tag = "keepcolor nozoom"
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(110).start() }
    }

    /**
     * (7.4) Okrągła ikona, która po zaznaczeniu wysuwa swój podpis (np. „Napisy”), a po przejściu na inną ikonę go chowa –
     * zawsze widać nazwę tylko zaznaczonej funkcji. Płynna zmiana szerokości: [LayoutTransition] rodzica ([expandingRow]).
     */
    fun expandingButton(c: Context, icon: String, label: String, onClick: () -> Unit): TextView = button(c, icon, "", onClick).apply {
        contentDescription = label
        val base = onFocusChangeListener
        setOnFocusChangeListener { v, has ->
            base?.onFocusChange(v, has)
            text = if (has) (contentDescription?.toString() ?: label) else ""      // podpis z opisu – zmienny (np. ⏯ Pauza / Odtwórz)
            compoundDrawablePadding = if (has) Ui.dp(9) else 0
            setPadding(Ui.dp(if (has) 16 else 12), Ui.dp(9), Ui.dp(if (has) 18 else 12), Ui.dp(9))
        }
    }

    /** Rząd na [expandingButton]: animowana zmiana szerokości przycisków (wysuwanie / chowanie podpisu). */
    fun expandingRow(row: android.view.ViewGroup) {
        row.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(android.animation.LayoutTransition.CHANGING, 160)
        }
    }

    /** Kolory paska postępu PlayerView (DefaultTimeBar) i krok przewijania pilotem na pasku. */
    fun styleTimeBar(root: View, played: Int, stepMs: Long = 10_000) {
        (root.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.DefaultTimeBar)?.apply {
            setPlayedColor(played); setScrubberColor(played); setBufferedColor(0x66FFFFFF); setUnplayedColor(0x33FFFFFF)
            setKeyTimeIncrement(stepMs)
        }
    }
}

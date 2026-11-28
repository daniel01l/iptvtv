package pl.iptv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.theme.ObsidianGlass

/**
 * (v2) Zwykły odtwarzacz STube w motywie Nowoczesny v2: dolny pływający szklany panel – po lewej czas „01:33 / 04:28” i pod nim
 * **interaktywny** pasek postępu ([timeBar] – fokus D-pad, ◄ ► przewijają, dotyk), dalej ⏮, okrągły niebieski ⏯, ⏭ i pigułki
 * funkcji ([pills] – istniejące przyciski odtwarzacza). Obramowanie panelu to **ozdobny** wskaźnik postępu ([ProgressBorder],
 * `#38BDF8` → `#2F80ED`; tło widoku – bez fokusu). Pigułka samej jakości obrazu ([quality], np. „1080p60”, „Auto 1080p”).
 * W pionie telefonu [timeBox] i [controls] trafiają pod wideo ([placePortrait]); w poziomie / na TV – do panelu ([placeLandscape]).
 */
class StubeV2Controls(c: Context, private val playerOf: () -> Player?, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    private val G = ObsidianGlass
    private fun dp(v: Int) = Ui.dp(v)

    val border = ProgressBorder()
    val panel = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        background = border; setPadding(dp(20), dp(12), dp(16), dp(12)); tag = PANEL_TAG; isFocusable = false
    }
    val timeText = TextView(c).apply { textSize = 15f; setTextColor(G.TEXT); tag = "keepcolor"; typeface = Ui.typeface(android.graphics.Typeface.BOLD) }
    val timeBar = DefaultTimeBar(c).apply {
        setPlayedColor(G.FOCUS_RING); setScrubberColor(G.FOCUS_RING); setUnplayedColor(Ui.withAlpha(Color.WHITE, 0x30)); setBufferedColor(Ui.withAlpha(Color.WHITE, 0x50))
        setKeyTimeIncrement(SEEK_STEP_MS); isFocusable = true; contentDescription = SEEK_LABEL
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), G.focus(12, pl.iptv.tv.ui.DevicePerformanceProfile.uiLow)); addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0)) }
    }
    val timeBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    val play: TextView; val prev: TextView; val next: TextView
    /** Pigułki funkcji (tu odtwarzacz przenosi swój pasek przycisków). */
    val pills = FrameLayout(c).apply { clipChildren = false; clipToPadding = false }
    val controls = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    /** ⏮ ⏯ ⏭ (poziom – na początku panelu, pion – na środku obrazu). */
    val transport = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    /** (v2, pion) Dolny pas obrazu: czas + pasek postępu na ciemnym przejściu (jak w zwykłym odtwarzaczu). */
    private val overlayBottom = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(18), dp(12), dp(2))
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
    }
    val quality = TextView(c).apply {
        textSize = 13f; setTextColor(Color.WHITE); tag = "keepcolor"; typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(5), dp(12), dp(5)); background = G.glassDialog(16, G.OVERLAY_FILL); visibility = View.GONE; isFocusable = false
    }
    private var scrubbing = false

    init {
        timeBox.addView(timeText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        timeBox.addView(timeBar, LinearLayout.LayoutParams(-1, dp(28)))
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(t: TimeBar, p: Long) { scrubbing = true }
            override fun onScrubMove(t: TimeBar, p: Long) { timeText.text = label(p, playerOf()?.duration ?: 0L) }
            override fun onScrubStop(t: TimeBar, p: Long, canceled: Boolean) {
                scrubbing = false; if (!canceled) playerOf()?.let { if (!it.isCurrentMediaItemLive) it.seekTo(p) }
            }
        })
        fun round(icon: String, desc: String, size: Int, primary: Boolean, onClick: () -> Unit) = TextView(c).apply {
            contentDescription = desc; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; tag = "keepcolor nozoom"
            val i = if (primary) 26 else 20; val p = (dp(size) - dp(i)) / 2; setPadding(p, p, p, p)
            background = if (primary) G.button(true, 60, pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) else G.pillTab(60, pl.iptv.tv.ui.DevicePerformanceProfile.uiLow)
            setCompoundDrawables(PlayerIcon(icon, android.content.res.ColorStateList.valueOf(Color.WHITE)).apply { setBounds(0, 0, dp(i), dp(i)) }, null, null, null)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> Ui.focusZoom(v, f, 1.08f) }
        }
        prev = round(PlayerIcon.PREV, "Poprzedni", 40, false, onPrev)
        play = round(PlayerIcon.PAUSE, "Odtwórz / pauza", 52, true, onPlayPause)
        next = round(PlayerIcon.NEXT, "Następny", 40, false, onNext)
        for ((b, s) in listOf(prev to 40, play to 52, next to 40)) transport.addView(b, LinearLayout.LayoutParams(dp(s), dp(s)).apply { marginEnd = dp(10) })
        controls.addView(transport, LinearLayout.LayoutParams(-2, -2))
        controls.addView(pills, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
    }

    /** Transport z powrotem w panelu (przed pigułkami). */
    private fun transportToControls() {
        if (transport.parent === controls) return
        detach(transport)
        for ((b, s) in listOf(prev to 40, play to 52, next to 40)) (b.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = dp(s); it.height = dp(s); it.marginStart = 0; it.marginEnd = dp(10) }
        controls.addView(transport, 0, LinearLayout.LayoutParams(-2, -2))
    }

    /**
     * (v2) Pion telefonu: ⏮ ⏯ ⏭ na środku obrazu, czas i interaktywny pasek postępu na dole obrazu – w kontrolerze odtwarzacza [host]
     * (pokazywane i chowane razem z nim, jak w zwykłym odtwarzaczu). Pigułki ([pills]) – poza obrazem (odtwarzacz daje ikony pod tytułem).
     */
    fun placePortraitOverlay(host: ViewGroup) {
        detach(timeBox); detach(controls); detach(panel); detach(transport); detach(overlayBottom)
        for ((b, s) in listOf(prev to 44, play to 60, next to 44)) (b.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = dp(s); it.height = dp(s); it.marginStart = dp(14); it.marginEnd = dp(14) }
        host.addView(transport, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        timeText.textSize = 13f; timeText.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        overlayBottom.removeAllViews()
        overlayBottom.addView(timeBox, LinearLayout.LayoutParams(-1, -2))
        (timeBar.layoutParams as? LinearLayout.LayoutParams)?.height = dp(24)
        host.addView(overlayBottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** Poziom / TV: jeden pływający panel u dołu obrazu (w kontrolerze odtwarzacza – pokazywany i chowany razem z nim). */
    fun placeLandscape(host: ViewGroup) {
        detach(timeBox); detach(controls); detach(panel); detach(overlayBottom)
        transportToControls()
        timeText.textSize = 15f; timeText.setShadowLayer(0f, 0f, 0f, 0); (timeBar.layoutParams as? LinearLayout.LayoutParams)?.height = dp(28)
        panel.removeAllViews()
        panel.addView(timeBox, LinearLayout.LayoutParams(dp(230), -2).apply { marginEnd = dp(14) })
        panel.addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
        panel.background = border
        host.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(20)) })
    }

    /** Pion telefonu: czas i pasek postępu pod wideo ([top]), ⏮ ⏯ ⏭ i pigułki pod tytułem ([below]). */
    fun placePortrait(top: ViewGroup, below: ViewGroup) {
        detach(timeBox); detach(controls); detach(panel); detach(overlayBottom)
        transportToControls()
        top.addView(timeBox, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(4), dp(8), 0) })
        below.addView(android.widget.HorizontalScrollView(top.context).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; addView(controls) },
            FrameLayout.LayoutParams(-1, -2))
    }

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.let { p -> p.removeView(v); if (p is android.widget.HorizontalScrollView) (p.parent as? ViewGroup)?.removeView(p) }
    }

    /** Odświeżenie (co [TICK_MS] przy widocznych kontrolkach): czas, pasek, obramowanie postępu, ikona ⏯, pigułka jakości. */
    fun update() {
        val p = playerOf() ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition
        timeBar.setDuration(dur); timeBar.setBufferedPosition(p.bufferedPosition)
        if (!scrubbing) { timeBar.setPosition(pos); timeText.text = if (p.isCurrentMediaItemLive) "NA ŻYWO" else label(pos, dur) }
        border.progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        if (playing != lastPlaying) {
            lastPlaying = playing
            play.setCompoundDrawables(PlayerIcon(if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY, android.content.res.ColorStateList.valueOf(Color.WHITE))
                .apply { setBounds(0, 0, dp(26), dp(26)) }, null, null, null)
            play.contentDescription = if (playing) "Pauza" else "Odtwórz"
        }
        val fmt = (YtEngine.player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat      // kontroler (MediaController) nie ma formatu – odtwarzacz silnika
        val q = qualityText?.invoke(fmt?.frameRate ?: 0f) ?: qualityLabel(fmt?.height ?: p.videoSize.height, fmt?.frameRate ?: 0f, Prefs.ytQuality <= 0)
        if (quality.text.toString() != q) quality.text = q
        quality.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
    }
    private var lastPlaying: Boolean? = null
    /** Tekst pigułki jakości od odtwarzacza (zgodny z oknem „Jakość obrazu”); null – z formatu obrazu. */
    var qualityText: ((Float) -> String)? = null

    private fun label(pos: Long, dur: Long) = clock(pos) + " / " + clock(dur)

    companion object {
        const val PANEL_TAG = "stube_v2_panel"
        const val SEEK_LABEL = "Pasek postępu – przewijanie"
        const val SEEK_STEP_MS = 10_000L
        const val TICK_MS = 500L

        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600
            return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        }

        /** Sama jakość obrazu: „1080p60”, przy automatycznym wyborze „Auto 1080p” (bez nazwy silnika); brak obrazu – pusto. */
        fun qualityLabel(height: Int, fps: Float, auto: Boolean): String {
            if (height <= 0) return ""
            return (if (auto) "Auto " else "") + VideoQuality.label(VideoQuality.classOf(0, height), fps)
        }
    }

    /**
     * Obramowanie panelu jako ozdobny wskaźnik postępu: szkło `V2GlassDialog` (72 %), cienka szklana krawędź i jej część
     * [progress] (od środka górnej krawędzi, zgodnie ze wskazówkami zegara) w gradiencie `#38BDF8` → `#2F80ED`. Ścieżka i jej
     * długość liczone przy zmianie rozmiaru; [draw] – tylko `getSegment` do stałej ścieżki (bez alokacji).
     */
    class ProgressBorder : Drawable() {
        private val glass = ObsidianGlass.glassDialog(24, ObsidianGlass.OVERLAY_FILL)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val path = Path(); private val seg = Path(); private val measure = PathMeasure(); private var len = 0f
        private val r = RectF()
        var progress = 0f
            set(v) { if (Math.abs(v - field) > 0.0005f) { field = v; invalidateSelf() } }

        override fun onBoundsChange(b: Rect) {
            glass.bounds = b
            val w = Ui.dp(2).toFloat(); stroke.strokeWidth = w; glow.strokeWidth = w * 4
            r.set(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2)
            val rad = Ui.dp(24).toFloat() - w / 2
            path.reset()
            // od środka górnej krawędzi w prawo (zgodnie ze wskazówkami zegara)
            path.moveTo(r.centerX(), r.top); path.lineTo(r.right - rad, r.top); path.quadTo(r.right, r.top, r.right, r.top + rad)
            path.lineTo(r.right, r.bottom - rad); path.quadTo(r.right, r.bottom, r.right - rad, r.bottom)
            path.lineTo(r.left + rad, r.bottom); path.quadTo(r.left, r.bottom, r.left, r.bottom - rad)
            path.lineTo(r.left, r.top + rad); path.quadTo(r.left, r.top, r.left + rad, r.top); path.lineTo(r.centerX(), r.top)
            measure.setPath(path, false); len = measure.length
            val sh = LinearGradient(r.left, r.top, r.right, r.bottom, ObsidianGlass.FOCUS_RING, ObsidianGlass.FOCUS, Shader.TileMode.CLAMP)
            stroke.shader = sh; glow.shader = sh
        }

        override fun draw(c: Canvas) {
            glass.draw(c)
            if (progress <= 0f || len <= 0f) return
            seg.reset(); measure.getSegment(0f, len * progress, seg, true)
            if (!pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) { glow.alpha = 0x40; c.drawPath(seg, glow) }
            c.drawPath(seg, stroke)
        }

        override fun setAlpha(alpha: Int) { glass.alpha = alpha; stroke.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf; glow.colorFilter = cf; glass.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

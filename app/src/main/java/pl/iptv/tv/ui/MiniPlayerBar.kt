package pl.iptv.tv.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import coil.load
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtVideo

/**
 * Pasek „Teraz odtwarzane” (mini-player) na dole STube i menu głównego: film STube grający w tle – miniatura, tytuł, kanał, postęp,
 * ⏯ (pauza / wznowienie), ⛶ (pełny ekran) i ✖ (zatrzymanie). Sterowanie przez własny [YtClient] (MediaController usługi).
 * Pilot: ▼ z najniższego elementu ekranu → pasek, ◄► między przyciskami, ▲ – z powrotem do ekranu.
 */
class MiniPlayerBar(private val a: Activity) : FrameLayout(a) {
    private val thumb = ImageView(a)
    private val title = TextView(a)
    private val sub = TextView(a)
    private val bar = WatchBar(a)
    val playBtn: TextView
    val openBtn: TextView
    val stopBtn: TextView
    private var client: YtClient? = null
    private var started = false

    init {
        tag = "panel"; visibility = View.GONE; isClickable = true
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(10)) }
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x30)); cornerRadius = Ui.dp(6).toFloat() }
        Ui.roundClip(thumb, Ui.dp(6).toFloat())
        row.addView(thumb, LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(54)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(12), 0, Ui.dp(8), 0) }
        title.apply { textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        sub.apply { tag = "text2"; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(TextView(a).apply { tag = "accent"; textSize = 12f; text = "▶ TERAZ ODTWARZANE" })
        col.addView(title); col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        fun btn(t: String, desc: String, click: () -> Unit) = TextView(a).apply {
            text = t; tag = "item"; textSize = 18f; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; contentDescription = desc
            setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8)); setOnClickListener { click() }
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6) }) }
        playBtn = btn("⏸", "Pauza / wznowienie") { toggle() }
        val narrow = a.resources.displayMetrics.widthPixels < Ui.dp(560)
        openBtn = btn(if (narrow) "⛶" else "⛶ Odtwarzacz", "Otwórz odtwarzacz") { open() }
        stopBtn = btn("✖", "Zatrzymaj odtwarzanie") { (client?.stop() ?: YtEngine.stop()); refresh() }
        addView(row, LayoutParams(-1, -2))
        bar.fraction = 0f
        addView(bar, LayoutParams(-1, Ui.dp(3), Gravity.BOTTOM))
        Ui.style(this)
        // równe granice w każdym motywie (pasek postępu i miniatura nie wychodzą poza zaokrąglenie), cień; dotknięcie paska = pełny odtwarzacz
        Ui.roundClip(this, Ui.dp(16).toFloat()); elevation = Ui.dp(8).toFloat(); clipChildren = true
        setOnClickListener { open() }
    }

    /** Pełny widok odtwarzacza (muzyczny / wideo) bez przewijania ekranu – też skrót „Teraz odtwarzane” z paska ikon STube. */
    fun open() { a.startActivity(YtPlayerActivity.resumeIntent(a)) }

    /** Dodanie na dół ekranu [root] (nad treścią, przy prawej krawędzi). */
    fun attachTo(root: FrameLayout) {
        val w = minOf(Ui.dp(620), (a.resources.displayMetrics.widthPixels * 0.94).toInt())
        root.addView(this, FrameLayout.LayoutParams(w, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(Ui.dp(12), 0, Ui.dp(16), Ui.dp(12)) })
    }

    val shown get() = visibility == View.VISIBLE

    fun start() {
        started = true
        YtEngine.listeners += listener
        refresh()
        if (YtEngine.active) connect()
        removeCallbacks(tick); post(tick)
    }

    fun stop() {
        started = false
        YtEngine.listeners -= listener
        removeCallbacks(tick)
        client?.let { it.player.removeListener(playerListener); it.release() }
        client = null
    }

    private fun connect() {
        if (client != null) return
        YtClients.connect(a) { c ->
            if (!started) { c.release(); return@connect }
            client = c; c.player.addListener(playerListener); refresh()
        }
    }

    private fun toggle() {
        val p = client?.player ?: YtEngine.player ?: return
        p.playWhenReady = !p.playWhenReady
        refresh()
    }

    /** Widoczność i treść wg silnika (pokazany tylko, gdy coś gra lub jest wstrzymane w tle). */
    fun refresh() {
        val v: YtVideo? = YtEngine.video
        val show = started && YtEngine.active && v != null
        if (!show) {
            // fokus był na pasku – wraca na element, z którego zjechano (inaczej najbliższy powyżej)
            if (findFocus() != null) returnFocus?.get()?.takeIf { it.isShown }?.requestFocus() ?: focusSearch(this, View.FOCUS_UP)?.requestFocus()
            visibility = View.GONE; onVisibilityChanged?.invoke(false); return
        }
        val p: YtPlayable? = YtEngine.playable
        title.text = p?.title?.ifBlank { null } ?: v!!.title.ifBlank { "Film STube" }
        sub.text = p?.channel?.ifBlank { null } ?: v!!.channel
        if (thumb.tag != v!!.thumb) { thumb.tag = v.thumb; if (v.thumb.isNotBlank()) thumb.load(v.thumb) { crossfade(false); fitTo(thumb, Ui.dp(96), Ui.dp(54)) } else thumb.setImageDrawable(null) }
        playBtn.text = if (YtEngine.player?.playWhenReady == true) "⏸" else "▶"
        val pl = YtEngine.player
        bar.fraction = pl?.let { if (it.duration > 0) it.currentPosition.toFloat() / it.duration else 0f } ?: 0f
        if (visibility != View.VISIBLE) { visibility = View.VISIBLE; onVisibilityChanged?.invoke(true) }
        if (client == null) connect()
    }

    /** Zmiana widoczności – ekran dodaje / zdejmuje miejsce na pasek pod treścią. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** ▼ z elementu, pod którym nic już nie ma → pasek (wywoływane przez ekran przed własną obsługą klawiszy). */
    fun handleKey(e: KeyEvent): Boolean {
        if (!shown || e.action != KeyEvent.ACTION_DOWN) return false
        val f = a.window.decorView.findFocus() ?: return false
        // ▲ z paska – z powrotem na element, z którego zjechano (o ile nadal widoczny)
        if (isInside(f)) {
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) returnFocus?.get()?.takeIf { it.isShown && it.isFocusable }?.let { it.requestFocus(); return true }
            return false
        }
        if (e.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val next = f.focusSearch(View.FOCUS_DOWN)
        // pod spodem jest jeszcze element ekranu (faktycznie niżej) – zwykła nawigacja
        if (next != null && !isInside(next) && next.isShown && screenTop(next) >= screenBottom(f) - Ui.dp(4)) return false
        focusBar(); return true
    }

    /** Element, z którego zjechano do paska (powrót ▲). */
    private var returnFocus: java.lang.ref.WeakReference<View>? = null

    fun focusBar() {
        a.window.decorView.findFocus()?.takeIf { !isInside(it) }?.let { returnFocus = java.lang.ref.WeakReference(it) }
        playBtn.requestFocus()
    }

    private fun screenTop(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[1]
    private fun screenBottom(v: View) = screenTop(v) + v.height

    fun isInside(v: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === this) return true; x = x.parent as? View }
        return false
    }

    private val listener = object : YtEngine.Listener {
        override fun onLoading(v: YtVideo) = refresh()
        override fun onLoaded(p: YtPlayable) = refresh()
        override fun onStopped() = refresh()
    }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
    }
    private val tick = object : Runnable {
        override fun run() { if (started) { refresh(); postDelayed(this, 1000) } }
    }
}

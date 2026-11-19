package pl.iptv.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.YtVideo

/**
 * Widok muzyczny odtwarzacza STube (utwór / playlista): rozmyte tło w kolorach okładki zmieszanych z motywem aplikacji i dwie strefy.
 * Lewa (TV, tablet poziomo) albo góra (telefon w pionie): kwadratowa okładka, tytuł, wykonawca, pasek postępu z czasem i
 * przyciski (losowo, poprzedni, odtwórz / pauza, następny, powtarzanie, teledysk, opcje). Prawa / dół: zakładki „Następne
 * w kolejce” (lista, wybór pilotem lub dotykiem) i „Tekst” (słowa piosenki, gdy dostępne).
 * Wszystko rysowane z kolorów motywu ([Ui]); tło i okładka przycięte do własnych granic (nic nie wychodzi poza ekran).
 */
class YtMusicStage(c: Context, private val host: Host) : FrameLayout(c) {

    /** Pozycja listy „Następne”: film STube albo kanał TV z kolejki. */
    data class Entry(val title: String, val artist: String, val thumb: String, val video: YtVideo?, val queue: QueueItem? = null, val current: Boolean = false)

    interface Host {
        val player: Player?
        fun playPause()
        fun previous()
        fun next()
        fun shuffle(): Boolean
        fun repeat(): Boolean
        fun toVideo()
        fun options()
        fun pick(e: Entry)
        fun entries(): List<Entry>
        fun wantLyrics()
    }

    companion object {
        const val TICK_MS = 500L
        const val TAB_QUEUE = 0; const val TAB_LYRICS = 1
    }

    /** Elementy dostępne w testach. */
    val cover = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.withAlpha(Ui.text2, 0x40)) }
    val titleView = TextView(c)
    val artistView = TextView(c)
    val timeBar = DefaultTimeBar(c)
    private val posView = TextView(c); private val durView = TextView(c)
    private val back = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val tintView = View(c)
    val playBtn: TextView; val prevBtn: TextView; val nextBtn: TextView; val shuffleBtn: TextView; val repeatBtn: TextView
    val videoBtn: TextView; val optionsBtn: TextView
    val queueTab: TextView; val lyricsTab: TextView
    private val list = RecyclerView(c)
    private val lyricsScroll = ScrollView(c)
    private val lyricsText = TextView(c)
    private val adapter = Adapter()
    private var items: List<Entry> = emptyList()
    var tab = TAB_QUEUE; private set
    private var boundId: String? = null
    private var scrubbing = false
    private var lastPlaying: Boolean? = null
    private var lyricsState = 0       // 0 – nie pobierano, 1 – pobieranie, 2 – jest, 3 – brak
    /** Pierwsza pozycja listy – cel fokusu przy wejściu do prawej strefy (testy). */
    val listView: RecyclerView get() = list
    val lyricsLabel: String get() = lyricsText.text.toString()

    private fun dp(v: Int) = Ui.dp(v)

    private val wide: Boolean
    private val tick = object : Runnable { override fun run() { updateProgress(); if (isAttachedToWindow) postDelayed(this, TICK_MS) } }

    init {
        isClickable = true; clipChildren = true; clipToPadding = true
        setBackgroundColor(Ui.bg or 0xFF000000.toInt())
        val dm = resources.displayMetrics
        wide = dm.widthPixels >= dm.heightPixels * 1.15f
        // tło: miniatura okładki (mała, powiększona z filtrowaniem = rozmycie bez kosztu przetwarzania) + ściemnienie motywem
        addView(back, LayoutParams(-1, -1))
        addView(tintView, LayoutParams(-1, -1))
        tintView.background = scrim(Ui.bg)

        val coverSide = if (wide) minOf((dm.heightPixels * 0.46f).toInt(), (dm.widthPixels * 0.30f).toInt()) else minOf((dm.widthPixels * 0.56f).toInt(), (dm.heightPixels * 0.30f).toInt())
        Ui.roundClip(cover, dp(14).toFloat())

        titleView.apply { setTextColor(Ui.text); textSize = if (wide) 28f else 22f; typeface = Ui.typeface(android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL }
        artistView.apply { setTextColor(Ui.text2); textSize = if (wide) 19f else 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL }
        for (t in listOf(posView, durView)) t.apply { setTextColor(Ui.text2); textSize = 14f; minWidth = dp(44) }
        posView.gravity = Gravity.END or Gravity.CENTER_VERTICAL; durView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        timeBar.apply {
            setPlayedColor(Ui.accent or 0xFF000000.toInt()); setScrubberColor(Ui.accent or 0xFF000000.toInt())
            setBufferedColor(Ui.withAlpha(Ui.text, 0x55)); setUnplayedColor(Ui.withAlpha(Ui.text, 0x30))
            setKeyTimeIncrement(10_000L); isFocusable = true
            addListener(object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true }
                override fun onScrubMove(timeBar: TimeBar, position: Long) { posView.text = clock(position) }
                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    scrubbing = false; if (!canceled) host.player?.let { if (!it.isCurrentMediaItemLive) it.seekTo(position) }
                }
            })
        }
        val bar = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        bar.addView(posView, LinearLayout.LayoutParams(-2, -2)); bar.addView(timeBar, LinearLayout.LayoutParams(0, dp(40), 1f)); bar.addView(durView, LinearLayout.LayoutParams(-2, -2))

        shuffleBtn = round(PlayerIcon.SHUFFLE, "Losowo", 44) { shuffleBtn.isSelected = host.shuffle(); tintToggle(shuffleBtn) }
        prevBtn = round(PlayerIcon.PREV, "Poprzedni", 48) { host.previous() }
        playBtn = round(PlayerIcon.PLAY, "Odtwórz / pauza", 64, primary = true) { host.playPause() }
        nextBtn = round(PlayerIcon.NEXT, "Następny", 48) { host.next() }
        repeatBtn = round(PlayerIcon.REPEAT, "Powtarzanie", 44) { val on = host.repeat(); repeatBtn.isSelected = on; setIcon(repeatBtn, if (on) PlayerIcon.REPEAT_ONE else PlayerIcon.REPEAT); tintToggle(repeatBtn) }
        videoBtn = round(PlayerIcon.VIDEO, "Teledysk (wideo)", 44) { host.toVideo() }
        optionsBtn = round(PlayerIcon.CHAPTERS, "Opcje", 44) { host.options() }
        val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (b in listOf(shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn)) buttons.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) })
        val extras = LinearLayout(c).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (b in listOf(videoBtn, optionsBtn)) extras.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) })

        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        left.addView(cover, LinearLayout.LayoutParams(coverSide, coverSide))
        left.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        left.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        left.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        left.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        left.addView(extras, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // prawa strefa: zakładki + lista / tekst
        queueTab = pill("Następne w kolejce") { selectTab(TAB_QUEUE) }
        lyricsTab = pill("Tekst") { selectTab(TAB_LYRICS) }
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(0, 0, 0, dp(8)) }
        tabs.addView(queueTab, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }); tabs.addView(lyricsTab)
        list.apply { layoutManager = LinearLayoutManager(c); adapter = this@YtMusicStage.adapter; itemAnimator = null; clipToPadding = false
            setPadding(0, dp(2), dp(4), dp(24)); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; overScrollMode = OVER_SCROLL_NEVER }
        lyricsText.apply { setTextColor(Ui.text); textSize = if (wide) 20f else 17f; setLineSpacing(0f, 1.25f); setPadding(dp(6), dp(6), dp(10), dp(30)) }
        lyricsScroll.apply { addView(lyricsText); visibility = View.GONE; isFocusable = true; isFocusableInTouchMode = false; isVerticalScrollBarEnabled = false
            background = focusFrame() }
        val rightBody = FrameLayout(c).apply { clipChildren = true }
        rightBody.addView(list, LayoutParams(-1, -1)); rightBody.addView(lyricsScroll, LayoutParams(-1, -1))
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
        right.addView(tabs, LinearLayout.LayoutParams(-1, -2)); right.addView(rightBody, LinearLayout.LayoutParams(-1, 0, 1f))

        val body = LinearLayout(c).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; clipChildren = true
            setPadding(dp(if (wide) 48 else 18), dp(if (wide) 36 else 20), dp(if (wide) 48 else 18), dp(if (wide) 28 else 12)) }
        if (wide) {
            body.addView(left, LinearLayout.LayoutParams(0, -1, 5f).apply { marginEnd = dp(40) })
            left.gravity = Gravity.CENTER
            body.addView(right, LinearLayout.LayoutParams(0, -1, 4f))
        } else {
            body.addView(left, LinearLayout.LayoutParams(-1, -2))
            body.addView(right, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        addView(body, LayoutParams(-1, -1))
        selectTab(TAB_QUEUE)
    }

    // ---------------- kolory i kształty z motywu ----------------

    private fun scrim(base: Int, tint: Int = 0): GradientDrawable {
        val top = blend(base or 0xFF000000.toInt(), tint, if (tint == 0) 0f else 0.35f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.withAlpha(top, 0xB8), Ui.withAlpha(base, 0xF0)))
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun tintFrom(bm: Bitmap?) {
        val px = runCatching { Bitmap.createScaledBitmap(bm ?: return, 1, 1, true).getPixel(0, 0) }.getOrNull() ?: return
        tintView.background = scrim(Ui.bg, px)
    }

    private fun focusFrame(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(2), Ui.focus or 0xFF000000.toInt()) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.TRANSPARENT) })
    }

    private fun states(focus: Int, normal: Int, selected: Int = normal) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(focus, focus, selected, normal))

    private fun shape(color: Int, r: Int = 60) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun fillStates(onFocus: Int, normal: Int, selected: Int = normal) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(onFocus)); addState(intArrayOf(android.R.attr.state_pressed), shape(onFocus))
        addState(intArrayOf(android.R.attr.state_selected), shape(selected)); addState(intArrayOf(), shape(normal))
    }

    private fun setIcon(b: TextView, kind: String, primary: Boolean = false) {
        val ink = states(Ui.focusText, if (primary) Ui.focusText else Ui.text, if (primary) Ui.focusText else Ui.accent)
        val d = PlayerIcon(kind, ink).apply { setBounds(0, 0, dp(if (primary) 30 else 22), dp(if (primary) 30 else 22)) }
        b.setCompoundDrawables(d, null, null, null)
    }

    private fun tintToggle(b: TextView) { b.refreshDrawableState(); b.compoundDrawables[0]?.state = b.drawableState }

    /** Okrągły przycisk ikony: tło z palety motywu, zaznaczony – kolor zaznaczenia motywu, powiększony (animacja w warstwie). */
    private fun round(kind: String, desc: String, size: Int, primary: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        contentDescription = desc; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; tag = "keepcolor nozoom"
        val pad = (dp(size) - dp(if (primary) 30 else 22)) / 2
        setPadding(pad, pad, pad, pad)
        minWidth = dp(size); minHeight = dp(size)
        background = if (primary) fillStates(Ui.focus or 0xFF000000.toInt(), Ui.accent or 0xFF000000.toInt()) else fillStates(Ui.focus or 0xFF000000.toInt(), Ui.withAlpha(Ui.text, 0x26), Ui.withAlpha(Ui.accent, 0x40))
        setIcon(this, kind, primary)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(110).start() }
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text; textSize = 16f; maxLines = 1; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; tag = "keepcolor nozoom"
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(states(Ui.focusText, Ui.text, Ui.accent))
        background = fillStates(Ui.focus or 0xFF000000.toInt(), Ui.withAlpha(Ui.text2, 0x30), Ui.withAlpha(Ui.accent, 0x33))
        setOnClickListener { onClick() }
    }

    // ---------------- dane ----------------

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Utwór: tytuł, wykonawca i okładka (ta sama miniatura – rozmyte tło w jej kolorach). */
    fun bind(id: String, title: String, artist: String, thumb: String) {
        titleView.text = title; artistView.text = artist; artistView.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
        if (boundId == id) return
        boundId = id
        cover.setImageDrawable(null); back.setImageDrawable(null); tintView.background = scrim(Ui.bg)
        if (thumb.isNotBlank()) {
            cover.load(thumb) { crossfade(false); size(512, 512); allowHardware(false) }
            back.load(thumb) { crossfade(true); size(48, 48); allowHardware(false); listener(onSuccess = { _, r -> tintFrom((r.drawable as? BitmapDrawable)?.bitmap) }) }
        }
        lyricsState = 0; lyricsText.text = ""
        if (tab == TAB_LYRICS) { lyricsState = 1; lyricsText.text = "Szukanie tekstu…"; host.wantLyrics() }
        scrollToCurrent()
    }

    /** Odświeża listę „Następne” (kolejka / propozycje) i stan przycisków. */
    fun refresh() {
        items = host.entries()
        adapter.notifyDataSetChanged()
        queueTab.text = "Następne w kolejce" + (items.count { !it.current }.takeIf { it > 0 }?.let { " ($it)" } ?: "")
        updatePlay()
    }

    private fun scrollToCurrent() { list.scrollToPosition(0) }

    fun setLyrics(text: String?) {
        lyricsState = if (text.isNullOrBlank()) 3 else 2
        lyricsText.text = if (text.isNullOrBlank()) "Brak tekstu dla tego utworu" else text
        lyricsTab.alpha = if (lyricsState == 3) 0.6f else 1f
    }

    fun selectTab(t: Int) {
        tab = t
        queueTab.isSelected = t == TAB_QUEUE; lyricsTab.isSelected = t == TAB_LYRICS
        list.visibility = if (t == TAB_QUEUE) View.VISIBLE else View.GONE
        lyricsScroll.visibility = if (t == TAB_LYRICS) View.VISIBLE else View.GONE
        if (t == TAB_LYRICS && lyricsState == 0) { lyricsState = 1; lyricsText.text = "Szukanie tekstu…"; host.wantLyrics() }
    }

    /** Ikona ⏯ wg stanu odtwarzacza. */
    fun updatePlay() {
        val playing = host.player?.let { it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) } == true
        if (lastPlaying != playing) { lastPlaying = playing; setIcon(playBtn, if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY, primary = true) }
        playBtn.contentDescription = if (playing) "Pauza" else "Odtwórz"
    }

    private fun updateProgress() {
        val p = host.player ?: return
        updatePlay()
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        timeBar.setDuration(dur); timeBar.setBufferedPosition(p.bufferedPosition)
        if (!scrubbing) { timeBar.setPosition(p.currentPosition); posView.text = clock(p.currentPosition) }
        durView.text = if (p.isCurrentMediaItemLive) "NA ŻYWO" else clock(dur)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    /** Fokus startowy: ⏯. */
    fun focusDefault() { playBtn.requestFocus() }

    // ---------------- lista „Następne” ----------------

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView, val mark: TextView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"
                setPadding(dp(8), dp(6), dp(12), dp(6))
                background = fillStates(Ui.focus or 0xFF000000.toInt(), Color.TRANSPARENT, Ui.withAlpha(Ui.accent, 0x22), 14)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            }
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.withAlpha(Ui.text2, 0x40)) }
            Ui.roundClip(img, dp(8).toFloat())
            root.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(c).apply { textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Ui.typeface(android.graphics.Typeface.BOLD) }
            val sub = TextView(c).apply { textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            col.addView(title); col.addView(sub)
            root.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            val mark = TextView(c).apply { textSize = 18f; text = "♪"; visibility = View.GONE }
            root.addView(mark)
            val vh = VH(root, img, title, sub, mark)
            root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.takeIf { !it.current }?.let(host::pick) }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.root.isSelected = e.current
            val focusText = Ui.focusText
            val tc = states(focusText, Ui.text, Ui.accent); val sc = states(focusText, Ui.text2, Ui.text2)
            h.title.setTextColor(tc); h.sub.setTextColor(sc); h.mark.setTextColor(tc)
            h.title.text = e.title; h.sub.text = e.artist.ifBlank { if (e.queue?.isVideo == false) "Kanał TV" else "" }
            h.mark.visibility = if (e.current) View.VISIBLE else View.GONE
            h.root.contentDescription = e.title
            coil.util.CoilUtils.dispose(h.img); h.img.setImageDrawable(null)
            if (e.thumb.isNotBlank()) h.img.load(e.thumb) { crossfade(false); size(112, 112) }
        }
    }

    private fun fillStates(onFocus: Int, normal: Int, selected: Int, r: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(onFocus, r)); addState(intArrayOf(android.R.attr.state_pressed), shape(onFocus, r))
        addState(intArrayOf(android.R.attr.state_selected), shape(selected, r)); addState(intArrayOf(), shape(normal, r))
    }
}

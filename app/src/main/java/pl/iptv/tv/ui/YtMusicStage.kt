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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        /** (7.14) ► na pozycji listy: utwór usunięty z kolejki / propozycji (odrzucony – gust i kolejka dopasowują się w tle). */
        fun remove(e: Entry)
        fun entries(): List<Entry>
        fun wantLyrics()
        /** (7.16) Ręczna synchronizacja tekstu zmieniona (ms; dodatnie – tekst opóźniony) – do zapamiętania dla utworu. */
        fun lyricDelayChanged(ms: Long) {}
        /** (7.16) Łączna długość (ms) fragmentów pominiętych przez SponsorBlock przed pozycją [posMs] (bez nakładania się) – tekst z płyty ich nie zawiera. */
        fun skippedBefore(posMs: Long): Long = 0L
        // (v2) tryb muzyczny w pionie: wstecz, Cast, wyciszenie (true – wyciszone), ♡ (Do obejrzenia później; true – dodano), udostępnij
        fun back() {}
        fun cast() {}
        fun toggleMute(): Boolean = false
        fun like(): Boolean = false
        fun share() {}
        // (v2) plakat / teledysk w karcie (Prefs.ytStagePoster zmieniony), wyszukiwanie i dodawanie do kolejki
        fun posterChanged() {}
        suspend fun search(q: String): List<pl.iptv.tv.data.YtVideo> = emptyList()
        fun addToQueue(v: pl.iptv.tv.data.YtVideo, next: Boolean) {}
    }

    companion object {
        const val TICK_MS = 250L
        const val TAB_QUEUE = 0; const val TAB_LYRICS = 1
        /** (v2) Napisy panelu tekstu i przycisków. */
        const val LYRICS_TITLE = "Synchronizowany tekst piosenki"
        /** (v2) Krycie okna „Opcje odtwarzania” (0–255). */
        const val OPTIONS_ALPHA = 0xF2
        const val QUEUE_ICON_LABEL = "Kolejka"
        const val SYNC_LABEL = "Synchronizuj tekst"
        const val SYNC_ACTIVE = "◄ ► przesuń tekst · OK – gotowe"
        const val BACK_LABEL = "Wstecz"
        /** (v2) Powiększenie bieżącego wiersza tekstu (w warstwie – bez przeliczania układu). */
        const val LINE_SCALE = 1.0f
        const val POSTER_ON = "Włącz plakat"; const val POSTER_OFF = "Włącz wideo"
        const val QUEUE_ADD_LABEL = "Dodaj do kolejki"; const val QUEUE_BACK_LABEL = "Kolejka"; const val SEARCH_HINT = "Szukaj utworu lub teledysku…"
        const val SEARCH_DEBOUNCE_MS = 350L
        /** (v2) Kolumny siatki kolejki (TV / poziomo). */
        const val QUEUE_SPAN = 2
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
    private val lyricsBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private var lyricLines: List<TextView> = emptyList()
    private var lyricTimes = LongArray(0)
    private var lyricIdx = -1
    /** (7.16) Ręczne przesunięcie tekstu względem utworu (ms; dodatnie – tekst opóźniony). */
    var lyricDelayMs = 0L; private set
    /** Tryb ręcznej synchronizacji: OK na tekście włącza, ◄ ► przesuwają tekst, OK kończy. */
    var syncMode = false; private set
    private val syncHint = TextView(c).apply {
        textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(14), dp(7), dp(14), dp(7)); visibility = View.GONE; isClickable = true
        background = GradientDrawable().apply { setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xB0000000.toInt())); cornerRadius = dp(18).toFloat() }
        setOnClickListener { toggleSync() }
    }
    val hasSyncedLyrics get() = lyricLines.isNotEmpty()
    private var lyricLenSec = 0.0

    /**
     * Czas wiersza w czasie filmu: tekst pochodzi zwykle z płyty, a film ma dodatkowe fragmenty (wstęp, rozmowa), które SponsorBlock
     * pomija – pozycja filmu jest wtedy większa o ich długość. Korekta tylko, gdy przybliża długość filmu do długości tekstu
     * (albo długość tekstu nieznana).
     */
    private fun skipped(posMs: Long): Long {
        val total = host.skippedBefore(Long.MAX_VALUE)
        if (total <= 0) return 0
        val dur = host.player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val len = (lyricLenSec * 1000).toLong()
        val aware = len <= 0 || dur <= 0 || Math.abs(dur - total - len) < Math.abs(dur - len)
        return if (aware) host.skippedBefore(posMs) else 0
    }

    /** Pozycja filmu (ms) odpowiadająca czasowi wiersza [songMs] (odwrotność [skipped], kilka przybliżeń). */
    private fun videoPos(songMs: Long): Long { var p = songMs + lyricDelayMs; repeat(3) { p = songMs + lyricDelayMs + skipped(p) }; return p.coerceAtLeast(0) }
    private var holdScrollUntil = 0L
    private var lyricsPlain = ""
    private val adapter = Adapter()
    private var items: List<Entry> = emptyList()
    var tab = TAB_QUEUE; private set
    private var boundId: String? = null
    private var scrubbing = false
    private var lastPlaying: Boolean? = null
    private var bodyRef: View? = null
    private var buttonsRow: View? = null
    private var extrasRow: View? = null
    private var lyricsState = 0       // 0 – nie pobierano, 1 – pobieranie, 2 – jest, 3 – brak
    /** Pierwsza pozycja listy – cel fokusu przy wejściu do prawej strefy (testy). */
    val listView: RecyclerView get() = list
    val lyricsLabel: String get() = lyricsPlain
    /** Indeks wiersza tekstu śpiewanego teraz (karaoke; -1 – brak synchronizacji) – testy. */
    val currentLyricLine: Int get() = lyricIdx

    private fun dp(v: Int) = Ui.dp(v)

    /** (v2) Nowoczesny v2 – układ „Obsidian Glass” ([buildV2]); Nowoczesny (v1) i inne – jak dotąd ([buildClassic]). */
    val v2Layout = ModernV2.active
    // (v2) pola układu v2 – przed blokiem init (inicjalizacja w kolejności deklaracji)
    private val G = pl.iptv.tv.ui.theme.ObsidianGlass
    /** Podgląd teledysku w karcie (TextureView – zaokrąglone rogi); obraz podpinany przez [attachVideo]. */
    private var videoView: androidx.media3.ui.PlayerView? = null
    /** Wysuwana siatka kolejki (TV / poziomo) albo rozwijana karta „Następne w kolejce” (pion). */
    private var queuePanel: View? = null
    /** (v2, pion) Ikona kolejki w rzędzie przycisków (zaznaczona przy otwartej liście). */
    private var portraitQueueIcon: TextView? = null
    var queueOpen = false; private set
    var syncBtn: TextView? = null; private set
    private var muteBtn: TextView? = null
    private var likeBtn: TextView? = null
    private var portraitTime: TextView? = null
    // (v2) karta teledysku z postępem na obramowaniu, plakat / wideo, rząd ⏮ ⏯ ⏭, okno kolejki z wyszukiwaniem
    private var mediaBorder: StubeV2Controls.ProgressBorder? = null
    private var mediaCard: FrameLayout? = null
    private var posterHint: TextView? = null
    private var transportRow: View? = null
    private var queueAddBtn: TextView? = null
    private var searchField: android.widget.EditText? = null
    var searchMode = false; private set
    private var found: List<pl.iptv.tv.data.YtVideo> = emptyList()
    private var searchJob: kotlinx.coroutines.Job? = null
    private val results = RecyclerView(c)
    private val resultsAdapter = ResultsAdapter()
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

        val coverSide = if (wide) minOf((dm.heightPixels * 0.40f).toInt(), (dm.widthPixels * 0.30f).toInt()) else minOf((dm.widthPixels * 0.56f).toInt(), (dm.heightPixels * 0.30f).toInt())
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
        // (v2) Nowoczesny v2 – osobny układ (TV / poziomo: wideo + pigułki | panel tekstu; pion: wideo, tekst, kolejka)
        queueTab = pill("Następne w kolejce") { selectTab(TAB_QUEUE) }
        lyricsTab = pill("Tekst") { selectTab(TAB_LYRICS) }
        if (v2Layout) buildV2(c, bar) else buildClassic(c, bar, coverSide)
        selectTab(if (v2Layout) TAB_LYRICS else TAB_QUEUE)
    }

    private fun buildClassic(c: Context, bar: LinearLayout, coverSide: Int) {
        val buttons = LinearLayout(c).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (b in if (wide) listOf(shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn, videoBtn, optionsBtn) else listOf(shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn)) buttons.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) })
        val extras = LinearLayout(c).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (b in if (wide) emptyList() else listOf(videoBtn, optionsBtn)) extras.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) })

        buttonsRow = buttons; extrasRow = extras
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        left.addView(cover, LinearLayout.LayoutParams(coverSide, coverSide))
        left.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        left.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        left.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        left.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        left.addView(extras, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // prawa strefa: zakładki + lista / tekst
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(0, 0, 0, dp(8)) }
        tabs.addView(queueTab, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }); tabs.addView(lyricsTab)
        list.apply { layoutManager = LinearLayoutManager(c); adapter = this@YtMusicStage.adapter; itemAnimator = null; clipToPadding = false
            setPadding(0, dp(2), dp(4), dp(24)); isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; overScrollMode = OVER_SCROLL_NEVER }
        lyricsText.apply { isFocusable = false; setTextColor(Ui.text); textSize = if (wide) 20f else 17f; setLineSpacing(0f, 1.25f); setPadding(dp(6), dp(6), dp(10), dp(30)) }
        lyricsBox.addView(lyricsText)
        lyricsScroll.apply { addView(lyricsBox); visibility = View.GONE; setOnKeyListener { _, _, e -> if (e.action == android.view.KeyEvent.ACTION_DOWN) holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; false }
            setOnTouchListener { _, _ -> holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; false }; isFocusable = true; isFocusableInTouchMode = false; isVerticalScrollBarEnabled = false
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> refreshSyncHint() }   // (7.17) podpowiedź OK tylko w tekście
            background = focusFrame(); isClickable = true; setOnClickListener { toggleSync() } }
        val rightBody = FrameLayout(c).apply { clipChildren = true }
        rightBody.addView(list, LayoutParams(-1, -1)); rightBody.addView(lyricsScroll, LayoutParams(-1, -1))
        rightBody.addView(syncHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(10), dp(10)) })
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
        bodyRef = body
        addView(body, LayoutParams(-1, -1))
    }

    // ---------------- (v2) Nowoczesny v2: wideo w szklanej karcie, pigułki, kolejka, panel tekstu ----------------


    /** Teledysk w karcie trybu muzycznego (testy). */
    val showsVideo: Boolean get() = videoView?.player != null

    /**
     * (v2) Obraz teledysku w szklanej karcie: [on] – ten sam odtwarzacz co w zwykłym widoku (bez nowego strumienia), false – odpięty
     * (okładka). Wywoływane przez odtwarzacz przy każdej zmianie widoku; inny obiekt odtwarzacza (ponowne połączenie) – podpięcie od nowa.
     */
    fun attachVideo(on: Boolean) {
        val v = videoView ?: return
        val p = if (on) host.player else null
        if (v.player !== p) v.player = p
        v.visibility = if (p != null) View.VISIBLE else View.INVISIBLE
        cover.visibility = if (p != null) View.INVISIBLE else View.VISIBLE
    }

    private fun queueLabel(): String = (if (v2Layout) (if (wide) "Kolejka" else "Następne w kolejce") else "Następne w kolejce") +
        (items.count { !it.current }.takeIf { it > 0 }?.let { " ($it)" } ?: "")

    /** Pigułka V2PillTab z ikoną (przycisk istniejącej funkcji – ta sama akcja, nowy wygląd). */
    private fun v2Pill(b: TextView, label: String, icon: String?) {
        b.text = label; b.textSize = 15f; b.minWidth = 0; b.minHeight = 0; b.compoundDrawablePadding = dp(8)
        b.setPadding(dp(16), dp(9), dp(18), dp(9)); b.typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        b.background = G.pillTab(40, Prefs.lowPerf); b.setTextColor(pl.iptv.tv.ui.theme.ObsidianViews.pillText())
        if (icon != null) setIcon(b, icon) else b.setCompoundDrawables(null, null, null, null)
        b.setOnFocusChangeListener { v, f -> Ui.focusZoom(v, f, 1.04f) }
    }

    /** Okrągły przycisk transportu v2: ⏯ – niebieski gradient z poświatą, pozostałe – ciemne szkło (fokus – ramka `#38BDF8`). */
    private fun v2Round(b: TextView, primary: Boolean) { b.background = if (primary) G.button(true, 60, Prefs.lowPerf) else G.pillTab(60, Prefs.lowPerf) }

    private fun v2Icon(kind: String, desc: String, onClick: () -> Unit) = round(kind, desc, 40) { onClick() }.also { v2Round(it, false) }

    /** Przycisk ikony w kwadracie [s] dp – ikona wyśrodkowana (odstęp z obu stron). */
    private fun fit(b: TextView, s: Int): LinearLayout.LayoutParams {
        val icon = if (b === playBtn) 30 else 22
        val p = maxOf(0, (dp(s) - dp(icon)) / 2); b.minWidth = 0; b.minHeight = 0; b.setPadding(p, p, p, p)
        return LinearLayout.LayoutParams(dp(s), dp(s))
    }

    private fun buildV2(c: Context, bar: LinearLayout) {
        setBackgroundColor(G.BG_TOP)
        tintView.background = scrim(G.BG_TOP)
        // tekst: wiersze wyśrodkowane, bieżący – #38BDF8 z poświatą (skala w warstwie, bez przeliczania układu)
        lyricsText.apply { isFocusable = false; setTextColor(G.TEXT2); textSize = if (wide) 18f else 16f; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(30)) }
        lyricsBox.addView(lyricsText, LinearLayout.LayoutParams(-1, -2))
        lyricsBox.setPadding(0, dp(if (wide) 48 else 16), 0, 0)      // podświetlony wers nie chowa się pod wygaszoną krawędzią panelu
        lyricsScroll.apply { addView(lyricsBox); setOnKeyListener { _, _, e -> if (e.action == android.view.KeyEvent.ACTION_DOWN) holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; false }
            setOnTouchListener { _, _ -> holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; false }; isFocusable = true; isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48))
            // tekst przycięty do własnego obszaru także przy clipChildren = false rodziców (w pionie wiersze nachodziły na wideo, tytuł i pigułki)
            clipChildren = true; clipToPadding = true; outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> refreshSyncHint() }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), G.focus(20, Prefs.lowPerf)); addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0)) }
            isClickable = true; setOnClickListener { toggleSync() } }
        list.apply { adapter = this@YtMusicStage.adapter; itemAnimator = null; clipToPadding = false; overScrollMode = OVER_SCROLL_NEVER
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutManager = if (wide) androidx.recyclerview.widget.GridLayoutManager(c, QUEUE_SPAN) else LinearLayoutManager(c)
            setPadding(dp(4), dp(4), dp(4), dp(12)) }
        // pigułki akcji (te same przyciski i funkcje co dotąd)
        v2Pill(videoBtn, "Teledysk", PlayerIcon.VIDEO)
        v2Pill(optionsBtn, "Opcje wideo", PlayerIcon.CHAPTERS)
        v2Pill(queueTab, queueLabel(), PlayerIcon.QUEUE)
        queueTab.setOnClickListener { toggleQueue() }
        lyricsTab.visibility = View.GONE
        for (b in listOf(prevBtn, nextBtn, shuffleBtn, repeatBtn)) v2Round(b, false)
        v2Round(playBtn, true)
        titleView.apply { gravity = Gravity.START; textSize = if (wide) 24f else 21f; maxLines = if (wide) 1 else 2; setTextColor(G.TEXT) }
        artistView.apply { gravity = Gravity.START; textSize = 15f; setTextColor(G.TEXT2) }
        timeBar.apply { setPlayedColor(G.FOCUS_RING); setScrubberColor(G.FOCUS_RING); setUnplayedColor(Ui.withAlpha(Color.WHITE, 0x30)); setBufferedColor(Ui.withAlpha(Color.WHITE, 0x50)) }
        val sync = TextView(c).apply {
            text = SYNC_LABEL; textSize = 15f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"
            setPadding(dp(20), dp(9), dp(20), dp(9)); background = G.button(false, 40, Prefs.lowPerf); setTextColor(pl.iptv.tv.ui.theme.ObsidianViews.pillText())
            setCompoundDrawables(PlayerIcon(PlayerIcon.REPEAT, ColorStateList.valueOf(G.FOCUS_RING)).apply { setBounds(0, 0, dp(18), dp(18)) }, null, null, null); compoundDrawablePadding = dp(8)
            setOnClickListener { if (lyricLines.isEmpty()) { lyricsState = 1; showLyricsStatus("Szukanie tekstu…"); host.wantLyrics() } else { toggleSync(); lyricsScroll.requestFocus() } }
            setOnFocusChangeListener { v, f -> Ui.focusZoom(v, f, 1.04f) }
        }
        syncBtn = sync
        // karta wideo: okładka (tylko dźwięk / przed podpięciem) i teledysk na tym samym odtwarzaczu
        val media = FrameLayout(c).apply { background = G.glassDialog(); clipToOutline = true; val p = dp(3); setPadding(p, p, p, p) }
        val inner = FrameLayout(c); Ui.roundClip(inner, dp(21).toFloat())
        inner.setBackgroundColor(Color.BLACK)
        inner.addView(cover, LayoutParams(-1, -1))
        videoView = (android.view.LayoutInflater.from(c).inflate(pl.iptv.tv.R.layout.stremio_player_texture, inner, false) as androidx.media3.ui.PlayerView).apply {
            useController = false; isFocusable = false; visibility = View.INVISIBLE; setShutterBackgroundColor(Color.TRANSPARENT)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        inner.addView(videoView, LayoutParams(-1, -1))
        media.addView(inner, LayoutParams(-1, -1))
        posView.setTextColor(G.TEXT); durView.setTextColor(G.TEXT2)
        if (wide) buildV2Wide(c, bar, media, sync) else buildV2Portrait(c, bar, media, inner, sync)
    }

    /** TV / poziomo: lewa – tytuł, wykonawca, karta wideo z paskiem czasu, pigułki; prawa – panel „Synchronizowany tekst piosenki”. */
    private fun buildV2Wide(c: Context, bar: LinearLayout, media: FrameLayout, sync: TextView) {
        val dm = resources.displayMetrics
        // karta teledysku – mniejsza (ok. 80 % kolumny), obramowanie = postęp utworu ([mediaBorder]); fokus – „Włącz plakat / wideo”
        val leftW = (dm.widthPixels - dp(64) - dp(24)) * 5 / 8
        val maxH = dm.heightPixels - dp(44) - dp(64) - dp(64) - dp(60) - dp(40)
        val cardW = minOf((leftW * 0.82f).toInt(), maxH * 16 / 9).coerceAtLeast(dp(200))
        setupMediaCard(c, media)
        // pod kartą: ⏮ ⏯ ⏭ i czas (bez paska – postęp na obramowaniu karty)
        timeBar.visibility = View.GONE
        bar.addView(TextView(c).apply { text = " / "; textSize = 14f; setTextColor(G.TEXT2); tag = "keepcolor" }, 1)
        val transport = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for ((b, s) in listOf(prevBtn to 44, playBtn to 56, nextBtn to 44)) transport.addView(b, fit(b, s).apply { marginEnd = dp(12) })
        transport.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        transportRow = transport
        val stack = FrameLayout(c).apply { clipChildren = false; clipToPadding = false }
        // ⏮ ⏯ ⏭ i czas tuż pod kartą, wyśrodkowane (dawniej na dole kolumny – daleko od karty)
        val cardCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
        cardCol.addView(media, LinearLayout.LayoutParams(cardW, cardW * 9 / 16))
        cardCol.addView(transport, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })
        stack.addView(cardCol, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        // wysuwana siatka kolejki nad kartą: nagłówek z „Dodaj do kolejki” (wyszukiwanie jak w STube), siatka utworów / wyniki
        val q = buildQueuePanel(c, grid = true)
        stack.addView(q, LayoutParams(-1, -1))
        val pills = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for (b in listOf(videoBtn, optionsBtn, queueTab)) pills.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        pills.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        for (b in listOf(shuffleBtn, repeatBtn)) pills.addView(b, fit(b, 40).apply { marginStart = dp(8) })
        buttonsRow = pills
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        left.addView(titleView, LinearLayout.LayoutParams(-1, -2)); left.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        left.addView(stack, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        left.addView(pills, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        // prawy panel: sam tekst (bez nagłówka „Synchronizowany tekst piosenki”) i „Synchronizuj tekst”
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = G.glassDialog(); setPadding(dp(14), dp(12), dp(14), dp(16)) }
        val rb = FrameLayout(c); rb.addView(lyricsScroll, LayoutParams(-1, -1))
        right.addView(rb, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(sync, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(32), dp(24), dp(32), dp(20)); clipChildren = false }
        body.addView(left, LinearLayout.LayoutParams(0, -1, 5f).apply { marginEnd = dp(24) })
        body.addView(right, LinearLayout.LayoutParams(0, -1, 3f))
        bodyRef = body
        addView(body, LayoutParams(-1, -1))
    }

    /**
     * Karta teledysku: tło – szkło z ozdobnym postępem na obramowaniu ([mediaBorder]); fokus (pilot) – ramka i podpowiedź
     * „Włącz plakat” / „Włącz wideo”, OK / dotknięcie – przełączenie (zapamiętane dla kolejnych utworów), kursor wraca na ⏯.
     */
    private fun setupMediaCard(c: Context, media: FrameLayout) {
        media.background = StubeV2Controls.ProgressBorder().also { mediaBorder = it }
        val p = dp(4); media.setPadding(p, p, p, p)
        media.isFocusable = true; media.isClickable = true; media.contentDescription = POSTER_ON
        if (android.os.Build.VERSION.SDK_INT >= 23) media.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), G.focus(24, Prefs.lowPerf)); addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0)) }
        val hint = TextView(c).apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = Ui.typeface(android.graphics.Typeface.BOLD); tag = "keepcolor"; visibility = View.GONE
            setPadding(dp(18), dp(9), dp(18), dp(9)); background = G.glassDialog(20, G.OVERLAY_FILL); gravity = Gravity.CENTER
        }
        (media.getChildAt(0) as FrameLayout).addView(hint, LayoutParams(-2, -2, Gravity.CENTER))
        posterHint = hint
        fun label() { hint.text = if (Prefs.ytStagePoster) POSTER_OFF else POSTER_ON; media.contentDescription = hint.text }
        media.setOnFocusChangeListener { _, f -> label(); hint.visibility = if (f) View.VISIBLE else View.GONE }
        media.setOnClickListener { Prefs.ytStagePoster = !Prefs.ytStagePoster; label(); host.posterChanged(); playBtn.requestFocus() }
        mediaCard = media
    }

    /** Okno kolejki: nagłówek (tytuł, „Dodaj do kolejki”), lista / siatka utworów albo pole wyszukiwania z wynikami. */
    private fun buildQueuePanel(c: Context, grid: Boolean): LinearLayout {
        val qTitle = TextView(c).apply { textSize = 17f; setTextColor(G.TEXT); typeface = Ui.typeface(android.graphics.Typeface.BOLD); tag = "keepcolor" }
        val add = TextView(c).apply {
            text = QUEUE_ADD_LABEL; textSize = 14f; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"; gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7)); background = G.pillTab(40, Prefs.lowPerf); setTextColor(pl.iptv.tv.ui.theme.ObsidianViews.pillText())
            setCompoundDrawables(PlayerIcon(PlayerIcon.PLUS, ColorStateList.valueOf(G.FOCUS_RING)).apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null); compoundDrawablePadding = dp(6)
            setOnClickListener { setSearchMode(!searchMode) }
        }
        queueAddBtn = add
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, 0, dp(8)) }
        head.addView(qTitle, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(add)
        val field = android.widget.EditText(c).apply {
            hint = SEARCH_HINT; textSize = 16f; setSingleLine(); setTextColor(G.TEXT); setHintTextColor(G.TEXT2); tag = "keepcolor"; visibility = View.GONE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = G.pillTab(40, Prefs.lowPerf); setPadding(dp(18), dp(10), dp(18), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { scheduleSearch(SEARCH_DEBOUNCE_MS) }
            })
            setOnEditorActionListener { _, _, _ -> scheduleSearch(0); true }
        }
        searchField = field
        results.apply { layoutManager = LinearLayoutManager(c); adapter = resultsAdapter; itemAnimator = null; visibility = View.GONE; clipToPadding = false; setPadding(0, dp(6), 0, dp(12)) }
        val q = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = G.glassDialog(24, 0xF20C1426.toInt()); setPadding(dp(14), dp(14), dp(14), dp(10))
            visibility = if (grid) View.GONE else View.VISIBLE; clipChildren = false; clipToPadding = false }
        q.addView(head, LinearLayout.LayoutParams(-1, -2))
        q.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        q.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        q.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        q.tag = qTitle
        queuePanel = q
        return q
    }

    /** Tryb wyszukiwania w oknie kolejki: pole i wyniki zamiast utworów kolejki (fokus na polu); wyjście – z powrotem lista. */
    fun setSearchMode(on: Boolean) {
        searchMode = on
        queueAddBtn?.text = if (on) QUEUE_BACK_LABEL else QUEUE_ADD_LABEL
        searchField?.visibility = if (on) View.VISIBLE else View.GONE
        results.visibility = if (on) View.VISIBLE else View.GONE
        list.visibility = if (on) View.GONE else View.VISIBLE
        if (on) searchField?.requestFocus() else { searchField?.setText(""); found = emptyList(); resultsAdapter.notifyDataSetChanged(); focusListPos(0) }
    }

    private val searchRun = Runnable { runSearch() }
    private fun scheduleSearch(delay: Long) { removeCallbacks(searchRun); postDelayed(searchRun, delay) }
    private fun runSearch() {
        val qText = searchField?.text?.toString()?.trim().orEmpty()
        if (qText.length < 2) { found = emptyList(); resultsAdapter.notifyDataSetChanged(); return }
        val owner = context as? androidx.lifecycle.LifecycleOwner ?: return
        searchJob?.cancel()
        searchJob = owner.lifecycleScope.launch {
            val r = runCatching { host.search(qText) }.getOrDefault(emptyList())
            found = r.take(30); resultsAdapter.notifyDataSetChanged()
        }
    }

    /** Wynik wyszukiwania: „Dodaj” / „Dodaj i odtwórz następnie” (okno w stylu widoku), po dodaniu – z powrotem lista kolejki. */
    private fun pickResult(v: pl.iptv.tv.data.YtVideo) {
        showOptions(v.title.ifBlank { QUEUE_ADD_LABEL }, listOf(
            Opt(PlayerIcon.QUEUE, "Dodaj") { host.addToQueue(v, false); setSearchMode(false) },
            Opt(PlayerIcon.NEXT, "Dodaj i odtwórz następnie") { host.addToQueue(v, true); setSearchMode(false) },
        ), PlayerIcon.QUEUE)
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.VH>() {
        inner class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)
        override fun getItemCount() = found.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val root = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"
                setPadding(dp(8), dp(6), dp(12), dp(6)); background = G.optionCard(14, Prefs.lowPerf); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; Ui.roundClip(img, dp(8).toFloat())
            root.addView(img, LinearLayout.LayoutParams(dp(80), dp(45)))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(c).apply { textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(G.TEXT); typeface = Ui.typeface(android.graphics.Typeface.BOLD); tag = "keepcolor" }
            val sub = TextView(c).apply { textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(G.TEXT2); tag = "keepcolor" }
            col.addView(title); col.addView(sub); root.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            val vh = VH(root, img, title, sub)
            root.setOnClickListener { found.getOrNull(vh.bindingAdapterPosition)?.let { pickResult(it) } }
            return vh
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val v = found[position]
            h.title.text = v.title; h.sub.text = v.channel; h.root.contentDescription = v.title
            coil.util.CoilUtils.dispose(h.img); h.img.setImageDrawable(null)
            if (v.thumb.isNotBlank()) h.img.load(v.thumb) { crossfade(false); size(240, 135) }
        }
    }

    /**
     * Pion telefonu: karta wideo na górze (◄ wstecz, ⏮ ⏯ ⏭ na środku, czas, Cast i wyciszenie), interaktywny pasek postępu, tytuł
     * z wykonawcą, ♡ i udostępnianie, pigułki, zsynchronizowany tekst, „Synchronizuj tekst”, rozwijana karta „Następne w kolejce”.
     */
    private fun buildV2Portrait(c: Context, bar: LinearLayout, media: FrameLayout, inner: FrameLayout, sync: TextView) {
        val backBtn = v2Icon(PlayerIcon.BACK, BACK_LABEL) { host.back() }
        inner.addView(backBtn, LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
        val center = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        for ((b, s) in listOf(prevBtn to 44, playBtn to 58, nextBtn to 44)) center.addView(b, fit(b, s).apply { marginStart = dp(10); marginEnd = dp(10) })
        inner.addView(center, LayoutParams(-2, -2, Gravity.CENTER))
        val corner = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val castB = v2Icon(PlayerIcon.CAST, "Przesyłaj") { host.cast() }
        corner.addView(castB, fit(castB, 36).apply { marginEnd = dp(6) })
        muteBtn = v2Icon(PlayerIcon.AUDIO, "Wycisz") { val m = host.toggleMute(); muteBtn?.let { b -> b.isSelected = m; b.contentDescription = if (m) "Włącz dźwięk" else "Wycisz"; setIcon(b, if (m) PlayerIcon.MUTE else PlayerIcon.AUDIO) } }
        corner.addView(muteBtn, fit(muteBtn!!, 36))
        inner.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0)) }, LayoutParams(-1, dp(96), Gravity.BOTTOM))
        inner.addView(corner, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(28)) })
        val time = TextView(c).apply { textSize = 13f; setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 1f, Color.BLACK); tag = "keepcolor" }
        inner.addView(time, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(34)) })
        portraitTime = time
        posView.visibility = View.GONE; durView.visibility = View.GONE
        // tytuł, wykonawca, ♡, udostępnij
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleView); col.addView(artistView)
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        likeBtn = v2Icon(PlayerIcon.BOOKMARK_ADD, "Do obejrzenia później") { if (host.like()) setIcon(likeBtn!!, PlayerIcon.BOOKMARK_ADDED) }
        head.addView(likeBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        head.addView(v2Icon(PlayerIcon.SHARE, "Udostępnij") { host.share() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        val pills = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(4), dp(2), dp(4)) }
        // (v2, pion) wszystkie przyciski jako okrągłe ikony jednej wielkości – mieszczą się w rzędzie (kolejka nie ucieka poza ekran)
        for (b in listOf(videoBtn, optionsBtn)) { b.contentDescription = b.text; b.text = ""; b.compoundDrawablePadding = 0; v2Round(b, false); pills.addView(b, fit(b, 38).apply { marginEnd = dp(8) }) }
        for (b in listOf(shuffleBtn, repeatBtn)) pills.addView(b, fit(b, 38).apply { marginEnd = dp(8) })
        extrasRow = pills
        // (v2) kolejka – okrągła ikona w rzędzie przycisków (bez karty „Następne w kolejce” na dole ekranu); lista w miejscu tekstu
        val qIcon = v2Icon(PlayerIcon.QUEUE, QUEUE_ICON_LABEL) { toggleQueue() }
        pills.addView(qIcon, fit(qIcon, 38).apply { marginEnd = dp(8) })
        portraitQueueIcon = qIcon
        list.visibility = View.GONE
        val area = FrameLayout(c)
        area.addView(lyricsScroll, LayoutParams(-1, -1))
        area.addView(list, LayoutParams(-1, -1))
        queuePanel = null
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val dm = resources.displayMetrics
        body.addView(media, LinearLayout.LayoutParams(-1, ((dm.widthPixels - dp(28)) * 9 / 16)))
        // (v2) pasek postępu na dole karty wideo (jak w zwykłym odtwarzaczu), czas i ikony nad nim
        inner.addView(bar, LayoutParams(-1, dp(26), Gravity.BOTTOM).apply { setMargins(dp(6), 0, dp(6), dp(2)) })
        body.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        body.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(pills) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(area, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        body.addView(sync, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(8) })
        // bezpieczne odstępy: pasek stanu i nawigacji systemu
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(body) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(14), dp(10) + b.top, dp(14), dp(10) + b.bottom); ins
        }
        bodyRef = body
        addView(body, LayoutParams(-1, -1))
    }


    /** Kolejka v2: TV – siatka miniatur nad kartą wideo (fokus na pierwszej pozycji); pion – rozwinięcie listy w karcie. */
    fun toggleQueue() {
        queueOpen = !queueOpen
        if (wide) {
            if (!queueOpen && searchMode) setSearchMode(false)
            queuePanel?.visibility = if (queueOpen) View.VISIBLE else View.GONE
            ((queuePanel?.tag) as? TextView)?.text = queueLabel()
            if (queueOpen) { selectTab(TAB_QUEUE); list.visibility = View.VISIBLE; focusListPos(0, 25) } else { selectTab(TAB_LYRICS); queueTab.requestFocus() }
        } else {
            // (v2, pion) kolejka w miejscu tekstu (ikona w rzędzie przycisków zaznaczona), ponowne dotknięcie – z powrotem tekst
            list.visibility = if (queueOpen) View.VISIBLE else View.GONE
            lyricsScroll.visibility = if (queueOpen) View.INVISIBLE else View.VISIBLE
            syncBtn?.visibility = if (queueOpen) View.INVISIBLE else View.VISIBLE
            portraitQueueIcon?.isSelected = queueOpen
            tab = if (queueOpen) TAB_QUEUE else TAB_LYRICS
            if (queueOpen) list.scrollToPosition(0)
        }
    }

    private fun inside(v: View?, parent: View?): Boolean { var x = v; while (x != null) { if (x === parent) return true; x = x.parent as? View }; return false }

    /**
     * (v2) Nawigacja D-pad: karta teledysku ▼ → ⏯; rząd ⏮ ⏯ ⏭ ▲ → karta, ▼ → „Teledysk”; pigułki ▲ → ⏯, ► z „Powtarzaj” → tekst
     * (◄ / WSTECZ z tekstu – z powrotem „Powtarzaj”); „Synchronizuj tekst” ▲ → tekst, ◄ → „Powtarzaj”. Otwarta kolejka: siatka –
     * zwykły ruch po pozycjach, ▲ z pierwszego rzędu → „Dodaj do kolejki”; wyszukiwanie – pole ↕ wyniki.
     */
    private fun handleKeyV2(e: android.view.KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == android.view.KeyEvent.ACTION_DOWN
        fun act(block: () -> Unit): Boolean { if (down) block(); return true }
        val left = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT; val right = k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val up = k == android.view.KeyEvent.KEYCODE_DPAD_UP; val dn = k == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        if (!left && !right && !up && !dn) return false
        // kursor zgubiony (widok z fokusem zniknął / fokus uciekł pod widok muzyczny) – strzałka przywraca go na ⏯ zamiast szukać pod spodem
        val f = findFocus() ?: return act { focusContentDefault() }
        val handled = if (!wide) handleKeyV2Portrait(f, left, right, up, dn, ::act) else handleKeyV2Wide(f, left, right, up, dn, ::act)
        if (handled || !down) return handled
        // nieobsłużony ruch, po którym kursor wyszedłby poza widok muzyczny (pod spód – na ukryte przyciski odtwarzacza) – zostaje na miejscu
        val dir = when { left -> View.FOCUS_LEFT; right -> View.FOCUS_RIGHT; up -> View.FOCUS_UP; else -> View.FOCUS_DOWN }
        return runCatching { android.view.FocusFinder.getInstance().findNextFocus(this, f, dir) }.getOrNull() == null
    }

    private fun handleKeyV2Wide(f: View, left: Boolean, right: Boolean, up: Boolean, dn: Boolean, act: (() -> Unit) -> Boolean): Boolean {
        val pos = focusedListPos()
        val span = QUEUE_SPAN
        return when {
            f === lyricsScroll && syncMode && (left || right) -> act { nudgeLyrics(if (right) 250 else -250) }
            // okno kolejki
            pos >= 0 -> when {
                up && pos < span -> act { listMemory = pos; (queueAddBtn ?: queueTab).requestFocus() }
                up -> act { focusListPos(pos - span) }
                dn -> act { if (pos + span < items.size) focusListPos(pos + span) else queueTab.requestFocus() }
                left -> act { if (pos % span > 0) focusListPos(pos - 1) }
                right -> act { if (pos % span < span - 1 && pos + 1 < items.size) focusListPos(pos + 1) }
                else -> false
            }
            f === queueAddBtn -> when {
                dn -> act { if (searchMode) searchField?.requestFocus() else focusListPos(if (listMemory >= 0) listMemory else 0, 25) }
                else -> true
            }
            f === searchField -> when {
                dn -> act { results.getChildAt(0)?.requestFocus() ?: queueTab.requestFocus() }
                up -> act { queueAddBtn?.requestFocus() }
                else -> false
            }
            inside(f, results) -> {
                val i = results.findContainingViewHolder(f)?.bindingAdapterPosition ?: -1
                when {
                    up && i <= 0 -> act { searchField?.requestFocus() }
                    up -> act { results.layoutManager?.findViewByPosition(i - 1)?.requestFocus() }
                    dn -> act { results.layoutManager?.findViewByPosition(i + 1)?.requestFocus() ?: results.scrollToPosition(i + 1) }
                    else -> true
                }
            }
            // tekst piosenki: ▲▼ przewijanie; ◄ – „Powtarzaj”; ▼ na dole – „Synchronizuj tekst”
            f === lyricsScroll -> when {
                left -> act { repeatBtn.requestFocus() }
                right -> true
                dn && lyricsScroll.scrollY + lyricsScroll.height >= lyricsBox.height - dp(4) -> act { syncBtn?.requestFocus() }
                up && lyricsScroll.scrollY <= 0 -> true
                else -> act { holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; lyricsScroll.smoothScrollBy(0, (if (up) -1 else 1) * maxOf(dp(80), lyricsScroll.height / 3)) }
            }
            f === syncBtn -> when {
                up -> act { lyricsScroll.requestFocus() }
                left -> act { repeatBtn.requestFocus() }
                else -> true
            }
            // karta teledysku
            f === mediaCard -> when { dn -> act { playBtn.requestFocus() }; else -> true }
            // rząd ⏮ ⏯ ⏭
            inside(f, transportRow) -> when {
                up -> act { (if (queueOpen) queueAddBtn else mediaCard)?.requestFocus() }
                dn -> act { videoBtn.requestFocus() }
                left && f === prevBtn -> true
                right && f === nextBtn -> true
                else -> false
            }
            // pigułki funkcji: ▲ – ⏯, ► z „Powtarzaj” – tekst, ◄ z „Teledysk” – zostaje
            inside(f, buttonsRow) -> when {
                up -> act { playBtn.requestFocus() }
                dn -> true
                right && f === repeatBtn -> act { lyricsScroll.requestFocus() }
                left && f === videoBtn -> true
                else -> false
            }
            else -> false
        }
    }

    /** (v2) Pion telefonu (głównie dotyk): dotychczasowe zasady – kolejka i tekst w jednej kolumnie. */
    private fun handleKeyV2Portrait(f: View, left: Boolean, right: Boolean, up: Boolean, dn: Boolean, act: (() -> Unit) -> Boolean): Boolean {
        val pos = focusedListPos()
        return when {
            f === lyricsScroll && syncMode && (left || right) -> act { nudgeLyrics(if (right) 250 else -250) }
            pos >= 0 -> when {
                up && pos == 0 -> act { queueTab.requestFocus() }
                up -> act { focusListPos(pos - 1) }
                dn -> act { if (pos + 1 < items.size) focusListPos(pos + 1) }
                right -> act { removeAt(pos) }
                else -> true
            }
            f === queueTab && dn && queueOpen -> act { focusListPos(0) }
            f === syncBtn && up -> act { lyricsScroll.requestFocus() }
            f === syncBtn && dn -> act { queueTab.requestFocus() }
            else -> false
        }
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

    /** (v2) Nowoczesny v2: zaznaczenie z wyraźną niebieską ramką Obsidian Glass; inne motywy – samo wypełnienie. */
    private fun focusShape(color: Int, r: Int = 60) = shape(if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.withAlpha(pl.iptv.tv.ui.theme.ObsidianGlass.FOCUS, 0x4D) else color, r).apply {   // (v2) tint zamiast pełnego wypełnienia
        if (ModernV2.active) setStroke(dp(pl.iptv.tv.ui.theme.ObsidianGlass.FOCUS_STROKE_DP), pl.iptv.tv.ui.theme.ObsidianGlass.FOCUS_RING)
    }

    /** (v2) Spoczynek przycisku (okrągłe przyciski sterowania, zakładki): szkło #10192E z krawędzią 1,5 dp; v1 – dawny kolor. */
    private fun restShape(color: Int, r: Int = 60): GradientDrawable = if (!ModernV2.active) shape(color, r) else GradientDrawable().apply {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        setColor(G.withAlpha(G.SURFACE_TOP, pl.iptv.tv.ui.theme.ObsidianGlass.Opacity.current().surfaceAlpha)); cornerRadius = dp(r).toFloat(); setStroke(G.borderPx(), G.BORDER)
    }

    private fun fillStates(onFocus: Int, normal: Int, selected: Int = normal) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusShape(onFocus)); addState(intArrayOf(android.R.attr.state_pressed), shape(onFocus))
        addState(intArrayOf(android.R.attr.state_selected), shape(selected)); addState(intArrayOf(), restShape(normal))
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
        setOnFocusChangeListener { v, f -> Ui.focusZoom(v, f, 1.1f) }
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text; textSize = 16f; maxLines = 1; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; tag = "keepcolor nozoom"
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(states(Ui.focusText, Ui.text, Color.WHITE))
        background = fillStates(Ui.focus or 0xFF000000.toInt(), Ui.withAlpha(Ui.text2, 0x30), Ui.withAlpha(Ui.accent, 0xCC))
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
            val hi = hires(thumb)
            // okładka w pełnej rozdzielczości (1280×720 z kadrowaniem do kwadratu); brak dużej wersji – miniatura z listy
            cover.load(hi) { crossfade(false); size(1024, 1024); allowHardware(false); listener(onError = { _, _ -> cover.load(thumb) { size(1024, 1024); allowHardware(false) } }) }
            // tło: większa miniatura rozmyta sprzętowo (Android 12+), starsze – powiększona mała (gładkie przejścia)
            val blur = android.os.Build.VERSION.SDK_INT >= 31
            if (blur) back.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(40f, 40f, android.graphics.Shader.TileMode.CLAMP))
            back.scaleX = 1.25f; back.scaleY = 1.25f
            val bs = if (blur) 480 else 96
            back.load(hi) { crossfade(true); size(bs, bs); allowHardware(false); listener(onError = { _, _ -> back.load(thumb) { size(bs, bs); allowHardware(false) } }, onSuccess = { _, r -> tintFrom((r.drawable as? BitmapDrawable)?.bitmap) }) }
        }
        lyricsState = 0; showLyricsStatus("")
        if (tab == TAB_LYRICS || v2Layout) { lyricsState = 1; showLyricsStatus("Szukanie tekstu…"); host.wantLyrics() }
        scrollToCurrent()
        ensureFocus()
    }

    /**
     * Odświeża listę „Następne” (kolejka / propozycje) i stan przycisków. Nowy utwór: fokus (jeśli był na liście albo zniknął)
     * wraca na pierwszą pozycję, pamięć pozycji jest zerowana; ta sama lista – fokus zostaje na tej samej pozycji.
     */
    fun refresh() {
        val pos = focusedListPos()
        val trackChanged = refreshedId != boundId
        items = host.entries()
        adapter.notifyDataSetChanged()
        queueTab.text = queueLabel(); ((queuePanel?.tag) as? TextView)?.text = queueLabel()
        updatePlay()
        if (trackChanged) {
            refreshedId = boundId; listMemory = -1; scrollToCurrent()
            if (pos >= 0) post { focusListPos(0) }
        } else if (pos >= 0) post { focusListPos(pos) }
        ensureFocus()
    }

    private var refreshedId: String? = null
    /** Pozycja listy, na której był fokus przed przejściem na ikony nawigacji (powrót na ten sam utwór); -1 – brak. */
    private var listMemory = -1
    val rememberedListPos get() = listMemory

    /** Odświeżenie listy / zmiana utworu potrafi zabrać zaznaczenie – wraca na pierwszą pozycję (nawigacja pilotem nigdy nie zostaje bez fokusu). */
    fun ensureFocus() { post { if (isShown && findFocus() == null && !optionsOpen) focusContentDefault() } }

    // ---------------- fokus pilotem (D-pad) ----------------

    /** Indeks pozycji listy, w której jest fokus (-1 – fokus poza listą). */
    fun focusedListPos(): Int {
        var v: View? = findFocus()
        while (v != null && v.parent !== list) v = v.parent as? View
        return if (v == null) -1 else list.getChildAdapterPosition(v)
    }

    /** Fokus na pozycji [pos] listy (przewija i ponawia, aż wiersz powstanie); lista pusta – na zakładkę. */
    fun focusListPos(pos: Int, tries: Int = 10) {
        if (items.isEmpty()) { currentPill().requestFocus(); return }
        val p = pos.coerceIn(0, items.size - 1)
        val v = list.layoutManager?.findViewByPosition(p)
        if (v != null && v.isShown && v.isFocusable) { v.requestFocus(); return }
        list.scrollToPosition(p)
        if (tries > 0) list.postDelayed({ focusListPos(p, tries - 1) }, 40) else playBtn.requestFocus()
    }

    private fun currentPill() = if (v2Layout) queueTab else if (tab == TAB_LYRICS) lyricsTab else queueTab

    /** Wejście do prawej strefy z ikon nawigacji: zakładka Tekst – okno tekstu; lista – zapamiętana pozycja albo pierwsza (bieżący utwór). */
    private fun enterContent() {
        if (tab == TAB_LYRICS) lyricsTab.requestFocus() else focusListPos(if (listMemory >= 0) listMemory else 0)   // (7.14) z ikon – na kafelek „Tekst”, nie w pole przewijania
    }

    /** ▼ z zakładki: do treści (okno tekstu / pierwsza pozycja listy). */
    private fun descendIntoContent() { if (tab == TAB_LYRICS) lyricsScroll.requestFocus() else focusListPos(if (listMemory >= 0) listMemory else 0) }

    /** ► na pozycji listy: usuwa ją od razu (lokalnie), kursor zostaje na tej pozycji (następny utwór), a na ostatniej – na poprzednim. */
    private fun removeAt(pos: Int) {
        val e = items.getOrNull(pos)?.takeIf { !it.current } ?: return
        items = items.toMutableList().also { it.removeAt(pos) }
        adapter.notifyItemRemoved(pos)
        queueTab.text = queueLabel(); ((queuePanel?.tag) as? TextView)?.text = queueLabel()
        val to = pos.coerceAtMost(items.size - 1); listMemory = to
        host.remove(e)
        post { focusListPos(to) }
    }

    private fun focusContentDefault() { if (v2Layout) { if (queueOpen) focusListPos(0) else playBtn.requestFocus(); return }; if (tab == TAB_LYRICS) lyricsScroll.requestFocus() else focusListPos(0) }

    /**
     * Własna nawigacja D-pad widoku muzycznego (fokus nigdy nie ucieka ani nie ginie): lista – ▲▼ po pozycjach (na pierwszej ▲ →
     * zakładka), ◄ → ostatnia ikona z prawej (opcje), pozycja zapamiętana do powrotu; tekst – ▲▼ przewijają okno i nie opuszczają go
     * (tylko ▲ na samej górze → zakładka); zakładki ◄► i ▼ do treści; z ikon ► (z opcji) do listy / tekstu. Zwraca true, gdy klawisz obsłużono.
     */
    fun handleKey(e: android.view.KeyEvent): Boolean {
        val k = e.keyCode
        if (k != android.view.KeyEvent.KEYCODE_DPAD_LEFT && k != android.view.KeyEvent.KEYCODE_DPAD_RIGHT && k != android.view.KeyEvent.KEYCODE_DPAD_UP && k != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false
        if (optionsOpen) return false
        if (v2Layout) return handleKeyV2(e)      // (v2) układ „Obsidian Glass”
        val f = findFocus() ?: return false
        val down = e.action == android.view.KeyEvent.ACTION_DOWN
        fun act(block: () -> Unit): Boolean { if (down) block(); return true }
        val left = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT; val right = k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val up = k == android.view.KeyEvent.KEYCODE_DPAD_UP
        val pos = focusedListPos()
        when {
            // (7.16) tryb ręcznej synchronizacji tekstu: ◄ ► przesuwają tekst o 0,25 s (tekst wcześniej / później)
            f === lyricsScroll && syncMode && (left || right) -> return act { nudgeLyrics(if (right) 250 else -250) }
            pos >= 0 -> return when {
                left && wide -> act { listMemory = pos; optionsBtn.requestFocus() }
                right -> act { removeAt(pos) }
                left -> true
                up && pos == 0 -> act { listMemory = 0; currentPill().requestFocus() }
                up -> act { focusListPos(pos - 1) }
                else -> act { if (pos < items.size - 1) focusListPos(pos + 1) }
            }
            f === lyricsScroll -> return when {
                left && wide -> act { optionsBtn.requestFocus() }
                left || right -> true
                up && lyricsScroll.scrollY <= 0 -> act { currentPill().requestFocus() }
                else -> act { holdScrollUntil = android.os.SystemClock.uptimeMillis() + 4000; lyricsScroll.smoothScrollBy(0, (if (up) -1 else 1) * maxOf(dp(80), lyricsScroll.height / 3)) }
            }
            f === queueTab || f === lyricsTab -> return when {
                left && f === lyricsTab -> act { queueTab.requestFocus() }
                left -> if (wide) act { optionsBtn.requestFocus() } else true
                right -> if (f === queueTab) act { lyricsTab.requestFocus() } else true
                up -> if (wide) true else act { optionsBtn.requestFocus() }
                else -> act { descendIntoContent() }
            }
        }
        // ikony nawigacji i pasek postępu
        val bottomRow = if (wide) buttonsRow else extrasRow
        val inBottom = bottomRow != null && f.parent === bottomRow
        return when {
            wide && right && f === optionsBtn -> act { enterContent() }
            !wide && right && f === optionsBtn -> true
            left && f === shuffleBtn && wide -> true
            left && !wide && f === videoBtn -> true
            up && f === timeBar -> true
            !wide && !up && !left && !right && inBottom -> act { enterContent() }
            wide && !up && !left && !right && inBottom -> true
            else -> false
        }
    }


    /** Okładka w największym dostępnym rozmiarze (miniatury YouTube: …/vi/ID/mqdefault.jpg → maxresdefault.jpg). */
    private fun hires(url: String) = url.replace(Regex("""/(default|mqdefault|hqdefault|sddefault)(\.jpg|\.webp)"""), "/maxresdefault.jpg")

    private fun scrollToCurrent() { list.scrollToPosition(0) }

    /** Komunikat w zakładce „Tekst” (pobieranie / brak tekstu) zamiast wierszy. */
    /** Przesunięcie tekstu w sekundach z jednym miejscem po przecinku, ze znakiem. */
    fun delayLabel(ms: Long = lyricDelayMs) = "%+.2f".format(java.util.Locale.US, ms / 1000.0).replace('.', ',') + " s"

    private fun refreshSyncHint() {
        // (7.17) podpowiedź tylko z kursorem w samym tekście (nie na kafelku „Tekst”)
        // (v2) zamiast podpowiedzi – przycisk „Synchronizuj tekst” pod panelem (w trybie synchronizacji: przesunięcie i „OK – gotowe”)
        syncBtn?.text = if (syncMode) "$SYNC_ACTIVE (${delayLabel()})" else if (lyricDelayMs != 0L) "$SYNC_LABEL (${delayLabel()})" else SYNC_LABEL
        syncBtn?.isActivated = syncMode
        val on = !v2Layout && tab == TAB_LYRICS && lyricLines.isNotEmpty() && lyricsScroll.hasFocus()
        syncHint.visibility = if (on) View.VISIBLE else View.GONE
        syncHint.text = if (syncMode) "◄ ► przesuń tekst (${delayLabel()}) · OK – gotowe"
            else if (lyricDelayMs != 0L) "OK – synchronizacja tekstu (${delayLabel()})" else "OK – synchronizacja tekstu"
    }

    /** Podpowiedź „OK – synchronizacja tekstu” widoczna (testy). */
    val syncHintShown: Boolean get() = syncHint.visibility == View.VISIBLE

    /** WSTECZ wciśnięty w polu tekstu – zwolnienie klawisza też należy do widoku (nie zamyka odtwarzacza). */
    private var backInLyrics = false

    /**
     * (7.17) WSTECZ z pola tekstu (niebieska ramka): kończy ręczną synchronizację i wraca na kafelek „Tekst” – stamtąd dalej
     * ◄ ► ▲ ▼ po widoku muzycznym. Kolejne WSTECZ (już poza tekstem) – jak dotąd. Zwraca true, gdy klawisz obsłużono.
     */
    fun handleBack(e: android.view.KeyEvent): Boolean {
        if (e.action == android.view.KeyEvent.ACTION_UP) { val was = backInLyrics; backInLyrics = false; return was }
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return false
        // (v2) okno kolejki: WSTECZ – najpierw wyjście z wyszukiwania, potem zamknięcie kolejki (kursor na „Kolejka”)
        if (v2Layout && wide && queueOpen && inside(findFocus(), queuePanel)) { backInLyrics = true; if (e.repeatCount == 0) { if (searchMode) setSearchMode(false) else toggleQueue() }; return true }
        if (findFocus() !== lyricsScroll) return false
        backInLyrics = true
        if (e.repeatCount == 0) { if (syncMode) { syncMode = false; refreshSyncHint() }; (if (v2Layout) (if (wide) repeatBtn else (syncBtn ?: playBtn)) else lyricsTab).requestFocus() }
        return true
    }

    fun toggleSync() {
        if (lyricLines.isEmpty()) return
        syncMode = !syncMode; refreshSyncHint()
    }

    fun setLyricDelay(ms: Long) {
        lyricDelayMs = ms.coerceIn(-30_000L, 30_000L); refreshSyncHint(); updateLyricLine(force = true)
    }

    /** Przesuwa tekst o [deltaMs] (dodatnie – później) i zapamiętuje dla utworu. */
    fun nudgeLyrics(deltaMs: Long) { setLyricDelay(lyricDelayMs + deltaMs); host.lyricDelayChanged(lyricDelayMs) }

    /**
     * Szybkie dopasowanie: „teraz śpiewany jest [next] ? następny : poprzedni wiersz” – przesunięcie tak, by ten wiersz był podświetlony
     * w tej chwili. Zwraca false, gdy tekst nie jest zsynchronizowany.
     */
    fun snapLyrics(next: Boolean): Boolean {
        if (lyricTimes.isEmpty()) return false
        val t = (lyricIdx + if (next) 1 else -1).coerceIn(0, lyricTimes.size - 1)
        val vp = host.player?.currentPosition ?: 0L; val raw = vp + 250 - skipped(vp)
        setLyricDelay(raw - lyricTimes[t]); host.lyricDelayChanged(lyricDelayMs)
        return true
    }

    private fun showLyricsStatus(msg: String) {
        syncMode = false
        lyricsScroll.scrollTo(0, 0)
        lyricsPlain = msg; lyricIdx = -1; lyricLines = emptyList(); lyricTimes = LongArray(0)
        while (lyricsBox.childCount > 1) lyricsBox.removeViewAt(1)
        refreshSyncHint()
        lyricsText.text = msg; lyricsText.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Tekst piosenki. Z znacznikami czasu – wiersze zsynchronizowane z odtwarzaniem (karaoke: śpiewany wiersz podświetlony i
     * wyśrodkowany, stuknięcie w wiersz przewija do niego); bez – zwykły tekst. null – brak tekstu.
     */
    fun setLyrics(r: pl.iptv.tv.data.YtLyrics.Result?) {
        if (r == null) { lyricsState = 3; showLyricsStatus("Brak tekstu dla tego utworu"); lyricsTab.alpha = 0.6f; return }
        lyricsState = 2; lyricsTab.alpha = 1f; lyricLenSec = r.duration
        if (!r.isSynced) { showLyricsStatus(r.plain); return }
        showLyricsStatus("")
        lyricsPlain = r.synced.joinToString("\n") { it.text }
        lyricTimes = LongArray(r.synced.size) { r.synced[it].ms }
        lyricLines = r.synced.map { l ->
            TextView(context).apply {
                text = l.text.ifBlank { "♪" }; textSize = if (v2Layout) (if (wide) 24f else 20f) else if (wide) 22f else 18f; setLineSpacing(0f, 1.15f)
                if (v2Layout) { gravity = Gravity.CENTER; typeface = Ui.typeface(android.graphics.Typeface.BOLD); setPadding(dp(16), dp(10), dp(16), dp(10)) }
                setTextColor(Ui.withAlpha(Ui.text, 0x88)); setPadding(dp(6), dp(9), dp(10), dp(9)); isClickable = true; isFocusable = false   // wiersz nie przejmuje fokusu D-pad (dotyk działa)
                setOnClickListener { host.player?.seekTo(videoPos(l.ms)) }
            }.also { lyricsBox.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        lyricsBox.addView(View(context), LinearLayout.LayoutParams(-1, dp(120)))     // miejsce na wyśrodkowanie ostatnich wierszy
        refreshSyncHint()
        updateLyricLine(force = true)
    }

    /** Wiersz śpiewany teraz wg pozycji odtwarzacza: podświetlenie i płynne wyśrodkowanie (chyba że użytkownik przewija ręcznie). */
    private fun updateLyricLine(force: Boolean = false) {
        if (lyricLines.isEmpty()) return
        val vpos = host.player?.currentPosition ?: 0L
        val pos = vpos + 250 - lyricDelayMs - skipped(vpos)   // lekkie wyprzedzenie – wiersz podświetla się tuż przed śpiewem
        var i = -1
        for (k in lyricTimes.indices) { if (lyricTimes[k] <= pos) i = k else break }
        if (i == lyricIdx && !force) return
        val old = lyricIdx; lyricIdx = i
        if (v2Layout) { v2Line(old, false); v2Line(i, true); if (i in lyricLines.indices) scrollToLine(lyricLines[i]); return }
        if (old in lyricLines.indices) lyricLines[old].apply { setTextColor(Ui.withAlpha(Ui.text, 0x88)); typeface = Ui.typeface(android.graphics.Typeface.NORMAL); textSize = if (wide) 22f else 18f }
        if (i !in lyricLines.indices) return
        val cur = lyricLines[i]
        cur.setTextColor(Ui.text); cur.typeface = Ui.typeface(android.graphics.Typeface.BOLD); cur.textSize = if (wide) 28f else 22f
        if (tab == TAB_LYRICS) scrollToLine(cur)
    }

    private fun scrollToLine(cur: View) {
        if (android.os.SystemClock.uptimeMillis() >= holdScrollUntil)
            lyricsScroll.post { lyricsScroll.smoothScrollTo(0, (cur.top - (lyricsScroll.height - cur.height) / 2).coerceAtLeast(0)) }
    }

    /**
     * (v2) Wiersz tekstu: bieżący – `#38BDF8` z niebieską poświatą i powiększeniem [LINE_SCALE] (animacja w warstwie RenderThread –
     * bez zmiany rozmiaru czcionki i przeliczania układu), pozostałe – przygaszone; tylko dwa wiersze zmieniane na wers.
     */
    private fun v2Line(i: Int, on: Boolean) {
        val t = lyricLines.getOrNull(i) ?: return
        t.setTextColor(if (on) G.FOCUS_RING else Ui.withAlpha(G.TEXT, 0x70))
        if (on) t.setShadowLayer(dp(8).toFloat(), 0f, 0f, G.withAlpha(G.FOCUS_RING, 0xB0)) else t.setShadowLayer(0f, 0f, 0f, 0)
        val s = if (on) LINE_SCALE else 1f
        if (Prefs.lowPerf || ModernV2.animationsOff) { t.scaleX = s; t.scaleY = s } else t.animate().scaleX(s).scaleY(s).setDuration(220).start()
    }

    fun selectTab(t: Int) {
        if (v2Layout) {
            // (v2) tekst zawsze widoczny (panel / środek ekranu); kolejka – osobna siatka / karta ([toggleQueue])
            tab = t; queueTab.isSelected = queueOpen
            if (wide) list.visibility = if (t == TAB_QUEUE) View.VISIBLE else View.GONE
            lyricsScroll.visibility = View.VISIBLE
            refreshSyncHint()
            if (lyricsState == 0 && boundId != null) { lyricsState = 1; showLyricsStatus("Szukanie tekstu…"); host.wantLyrics() }
            holdScrollUntil = 0; updateLyricLine(force = true)
            return
        }
        tab = t
        queueTab.isSelected = t == TAB_QUEUE; lyricsTab.isSelected = t == TAB_LYRICS
        list.visibility = if (t == TAB_QUEUE) View.VISIBLE else View.GONE
        lyricsScroll.visibility = if (t == TAB_LYRICS) View.VISIBLE else View.GONE
        if (t != TAB_LYRICS) syncMode = false
        refreshSyncHint()
        if (t == TAB_LYRICS && lyricsState == 0) { lyricsState = 1; showLyricsStatus("Szukanie tekstu…"); host.wantLyrics() }
        if (t == TAB_LYRICS) { holdScrollUntil = 0; updateLyricLine(force = true) }
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
        updateLyricLine()
        durView.text = if (p.isCurrentMediaItemLive) "NA ŻYWO" else clock(dur)
        portraitTime?.text = posView.text.toString() + " / " + durView.text   // (v2) pion – czas na karcie wideo
        mediaBorder?.progress = if (dur > 0) (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f      // (v2) postęp na obramowaniu karty
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    /** Pobierz tekst od nowa (przełącznik „Teksty piosenek”). */
    fun resetLyrics() { lyricsState = 0; showLyricsStatus(""); if (tab == TAB_LYRICS) selectTab(TAB_LYRICS) }

    /** Fokus startowy (wejście do widoku muzycznego): pierwsza pozycja listy (bieżący utwór) albo okno tekstu. */
    fun focusDefault() { listMemory = -1; focusContentDefault() }


    // ---------------- okno opcji w stylu widoku muzycznego ----------------

    /** Pozycja okna opcji: ikona ([PlayerIcon], puste – bez ikony), nazwa, wartość po prawej i akcja. */
    data class Opt(val icon: String, val label: String, val value: String = "", val action: () -> Unit)

    private var sheet: FrameLayout? = null
    private var sheetOpener: View? = null
    val optionsOpen get() = sheet != null
    /** Wiersze okna opcji (testy). */
    val optionRows: List<View> get() = (sheet?.findViewWithTag<LinearLayout>("rows")?.let { r -> (0 until r.childCount).map(r::getChildAt) }) ?: emptyList()

    /**
     * Okno opcji (zamiast systemowego okna): panel z prawej (telefon w pionie – od dołu), zaokrąglony, w kolorach motywu z cienką
     * obwódką akcentu, tło ściemnione; wiersze z ikonami, zaznaczony – kolor zaznaczenia motywu. WSTECZ zamyka ([closeOptions]).
     */
    /** [into] (7.23) – kontener spoza widoku muzycznego (zwykły odtwarzacz wideo, przycisk „Opcje odtwarzania”): to samo okno nad obrazem. */
    fun showOptions(title: String, opts: List<Opt>, headerIcon: String = PlayerIcon.MUSIC, into: ViewGroup? = null) {
        closeOptions(refocus = false)
        if (opts.isEmpty()) return
        sheetOpener = findFocus()
        val c = context
        val dim = FrameLayout(c).apply {
            isClickable = true; setOnClickListener { closeOptions() }
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.withAlpha(Ui.bg, 0x40), Ui.withAlpha(Ui.bg, 0xE0)))
        }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; clipChildren = true; clipToPadding = true
            setPadding(dp(12), dp(12), dp(12), dp(12)); elevation = dp(12).toFloat()
            // (v2) „Opcje odtwarzania” na szkle Obsidian Glass (24 dp, krycie wg „Przezroczystość UI”)
            // (v2) okno prawie kryjące (95 %) – tekst opcji czytelny nad wideo / listą (dawniej 78 % – prześwitywały napisy spod spodu)
            background = if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.glassDialog(24, pl.iptv.tv.ui.theme.ObsidianGlass.withAlpha(pl.iptv.tv.ui.theme.ObsidianGlass.DIALOG_FILL, OPTIONS_ALPHA))
            else GradientDrawable().apply {
                setColor(blend(Ui.panel or 0xFF000000.toInt(), Ui.bg or 0xFF000000.toInt(), 0.35f))
                cornerRadius = dp(24).toFloat(); setStroke(dp(1), Ui.withAlpha(Ui.accent, 0x70))
            }
            Ui.roundClip(this, dp(24).toFloat())
        }
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(10)) }
        head.addView(TextView(c).apply {
            setCompoundDrawables(PlayerIcon(headerIcon, ColorStateList.valueOf(Ui.accent or 0xFF000000.toInt())).apply { setBounds(0, 0, dp(26), dp(26)) }, null, null, null)
            compoundDrawablePadding = dp(12); text = title; textSize = 22f; setTextColor(Ui.text); typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        })
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(View(c).apply { setBackgroundColor(Ui.withAlpha(Ui.text, 0x22)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), 0, dp(10), dp(6)) })
        val rows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; tag = "rows"; clipChildren = false }
        val ink = states(Ui.focusText, Ui.text)
        opts.forEach { o ->
            rows.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"
                setPadding(dp(14), dp(11), dp(16), dp(11)); minimumHeight = dp(52)
                background = fillStates(Ui.focus or 0xFF000000.toInt(), Color.TRANSPARENT, Ui.withAlpha(Ui.accent, 0x26), 16)
                if (o.icon.isNotEmpty()) addView(ImageView(c).apply { setImageDrawable(PlayerIcon(o.icon, ink).apply { setBounds(0, 0, dp(24), dp(24)) }); isDuplicateParentStateEnabled = true },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
                val sel = o.label.startsWith("✔")
                addView(TextView(c).apply { text = o.label.removePrefix("✔").trim(); textSize = 18f; maxLines = 2; setTextColor(ink); isDuplicateParentStateEnabled = true
                    typeface = Ui.typeface(if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }, LinearLayout.LayoutParams(0, -2, 1f))
                val value = if (sel) "✔" else o.value
                if (value.isNotEmpty()) addView(TextView(c).apply { text = value; textSize = 16f; setTextColor(states(Ui.focusText, Ui.accent or 0xFF000000.toInt())); isDuplicateParentStateEnabled = true },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                setOnClickListener { closeOptions(); o.action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
        card.addView(ScrollView(c).apply { addView(rows); isVerticalScrollBarEnabled = false; isFocusable = false; clipChildren = false }, LinearLayout.LayoutParams(-1, -2))      // okno tak wysokie jak jego treść (dawniej zawsze do górnej krawędzi)
        val dm = resources.displayMetrics
        val lp = if (wide) LayoutParams(minOf(dp(440), (dm.widthPixels * 0.44f).toInt()), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(dp(24), dp(24), dp(32), dp(24)) }
        else LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), dp(60), dp(10), dp(10)) }
        card.layoutParams = lp
        dim.addView(card)
        (card.layoutParams as LayoutParams).height = if (wide) (dm.heightPixels - dp(48)).coerceAtMost(dp(76) * opts.size + dp(110)) else (dm.heightPixels * 0.62f).toInt().coerceAtMost(dp(60) * opts.size + dp(110))
        (into ?: this).addView(dim, LayoutParams(-1, -1))
        sheet = dim
        bodyRef?.let { (it as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        card.alpha = 0f; card.translationX = if (wide) dp(40).toFloat() else 0f; card.translationY = if (wide) 0f else dp(60).toFloat()
        card.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(180).start()
        rows.getChildAt(opts.indexOfFirst { it.label.startsWith("✔") }.coerceAtLeast(0))?.requestFocus()
    }

    /** Zamyka okno opcji; fokus wraca na przycisk, z którego je otwarto. */
    fun closeOptions(refocus: Boolean = true) {
        val s = sheet ?: return
        sheet = null
        (s.parent as? ViewGroup)?.removeView(s)
        bodyRef?.let { (it as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        if (refocus) { val o = sheetOpener; if (o != null && o.isAttachedToWindow) o.requestFocus() else if (isShown) playBtn.requestFocus() }
        sheetOpener = null
    }

    // ---------------- lista „Następne” ----------------

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView, val mark: TextView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; tag = "keepcolor nozoom"
                setPadding(dp(8), dp(6), dp(12), dp(6))
                background = fillStates(Ui.focus or 0xFF000000.toInt(), Color.TRANSPARENT, Ui.withAlpha(Ui.accent, 0x55), 14)
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
            val mark = TextView(c).apply { textSize = 20f; text = "♪"; visibility = View.GONE; setTextColor(Ui.accent or 0xFF000000.toInt()) }
            root.addView(mark)
            val vh = VH(root, img, title, sub, mark)
            root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.takeIf { !it.current }?.let(host::pick) }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.root.isSelected = e.current
            val focusText = Ui.focusText
            val tc = states(focusText, Ui.text, Ui.text); val sc = states(focusText, Ui.text2, Ui.text)
            h.title.setTextColor(tc); h.sub.setTextColor(sc);             h.title.text = e.title; h.sub.text = e.artist.ifBlank { if (e.queue?.isVideo == false) "Kanał TV" else "" }
            h.mark.visibility = if (e.current) View.VISIBLE else View.GONE
            h.root.contentDescription = e.title
            coil.util.CoilUtils.dispose(h.img); h.img.setImageDrawable(null)
            if (e.thumb.isNotBlank()) h.img.load(e.thumb) { crossfade(false); size(224, 224) }
        }
    }

    private fun fillStates(onFocus: Int, normal: Int, selected: Int, r: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusShape(onFocus, r)); addState(intArrayOf(android.R.attr.state_pressed), shape(onFocus, r))
        addState(intArrayOf(android.R.attr.state_selected), shape(selected, r)); addState(intArrayOf(), shape(normal, r))
    }
}

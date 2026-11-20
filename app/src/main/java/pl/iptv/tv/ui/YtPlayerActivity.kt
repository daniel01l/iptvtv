package pl.iptv.tv.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtChapter
import pl.iptv.tv.data.YtChapters
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtLyrics
import coil.load

/**
 * Odtwarzacz STube – ekran-klient: obraz i sterowanie przez [MediaController] połączony z [YtPlaybackService] ([YtEngine]).
 * Ekran nie posiada odtwarzacza: przy WSTECZ / HOME odpina tylko powierzchnię wideo i zwalnia kontroler – dźwięk gra dalej
 * (mini-player w STube i menu). Nakładka (10-foot UI): tytuł i kanał, rozdziały, „Co następuje”, przyciski: prędkość, jakość,
 * audio, napisy, dźwięk (korektor), rozdziały, kolejka, obraz w obrazie (PiP).
 * Pilot: OK przy ukrytym pasku – tylko pokazuje pasek (pauza: przycisk ⏯ na pasku lub Play/Pause na pilocie), ◄► 10 s,
 * ▲▼ rozdziały, MENU – opcje. Błąd strumienia – okno: SmartTube / YouTube / Zamknij.
 */
class YtPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "id"; const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"; const val EXTRA_THUMB = "thumb"; const val EXTRA_FROM_START = "from_start"
        /** Otwarcie bieżącego filmu silnika (mini-player, powiadomienie) – bez wczytywania od nowa. */
        const val EXTRA_RESUME_CURRENT = "resume_current"
        const val SEEK_STEP_MS = 10_000L
        const val SAVE_EVERY_MS = YtEngine.SAVE_EVERY_MS
        const val START_TIMEOUT_MS = YtEngine.START_TIMEOUT_MS
        const val TAB_RELATED = 0; const val TAB_QUEUE = 1; const val TAB_CHANNEL = 2; const val TAB_SIMILAR = 3
        /** Najwięcej kafelków na półce muzycznej; odliczanie autoodtwarzania po końcu filmu. */
        const val SHELF_MAX = 12; const val AUTOPLAY_MS = 8_000L
        const val AMBIENT_TICK_MS = 15_000L
        /** Bieżący ekran odtwarzacza (polecenia z telefonu). */
        @Volatile var active: java.lang.ref.WeakReference<YtPlayerActivity>? = null

        fun intent(c: Context, id: String, title: String = "") =
            Intent(c, YtPlayerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)

        /** Film z kafelka STube: dane do rzędu „Kontynuuj oglądanie”; [fromStart] – bez wznawiania od zapisanej pozycji. */
        fun intent(c: Context, v: YtVideo, fromStart: Boolean = false) = intent(c, v.id, v.title)
            .putExtra(EXTRA_CHANNEL, v.channel).putExtra(EXTRA_THUMB, v.thumb).putExtra(EXTRA_FROM_START, fromStart)

        /** Powrót do filmu grającego w tle (pełny ekran z mini-playera). */
        fun resumeIntent(c: Context) = Intent(c, YtPlayerActivity::class.java).putExtra(EXTRA_RESUME_CURRENT, true)
    }

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var skipLabel: TextView
    private lateinit var infoBox: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var channelView: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var upNextLabel: TextView
    private lateinit var optionsBar: LinearLayout
    private lateinit var optionsScroll: android.widget.HorizontalScrollView
    private lateinit var speedBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var audioBtn: TextView
    private lateinit var subsBtn: TextView
    private lateinit var eqBtn: TextView
    private lateinit var chaptersBtn: TextView
    private lateinit var queueBtn: TextView
    private lateinit var pipBtn: TextView
    private lateinit var audioOnlyBtn: TextView
    private lateinit var musicBtn: TextView
    /** (7.9) Widok muzyczny: okładka, sterowanie, kolejka i tekst – zamiast paska wideo przy utworach. */
    lateinit var stage: YtMusicStage; private set
    private var stageSig = ""
    private var stageBackground: Boolean? = null

    /** Klient usługi (MediaController); null – rozłączony (ekran schowany). */
    var client: YtClient? = null; private set
    private var connecting: com.google.common.util.concurrent.ListenableFuture<*>? = null
    val player: Player? get() = client?.player
    /** Okno awaryjne (testy). */
    var fallbackDialog: AlertDialog? = null; private set
    val segments: List<SbSegment> get() = if (YtEngine.video?.id == videoId) YtEngine.segments else emptyList()
    val lastSkipped: SbSegment? get() = YtEngine.lastSkipped
    val eq: YtEqSession? get() = YtEngine.eq
    val chapters: List<YtChapter> get() = current?.chapters.orEmpty()
    val related: List<YtVideo> get() = current?.related.orEmpty()
    /** Propozycje po utworze: bez składanek i wideo z kilkunastoma piosenkami, bez ostatnio słuchanych, wielu wykonawców ([pl.iptv.tv.data.StubeMixes.suggest]). */
    val suggestions: List<YtVideo> get() = pl.iptv.tv.data.StubeMixes.suggest(related, videoId, titleView.text.toString(), current?.channel?.ifBlank { null } ?: video.channel, YtEngine.recentIds, pl.iptv.tv.data.StubeTaste.current())
    /** Dane bieżącego filmu (gdy silnik gra film tego ekranu). */
    private val current: YtPlayable? get() = YtEngine.playable?.takeIf { YtEngine.video?.id == videoId }
    private val live get() = current is YtPlayable.Live

    private val videoId get() = (if (intent.getBooleanExtra(EXTRA_RESUME_CURRENT, false)) YtEngine.video?.id else null) ?: intent.getStringExtra(EXTRA_ID).orEmpty()
    private val videoUrl get() = "https://www.youtube.com/watch?v=$videoId"
    private val video get() = (if (intent.getBooleanExtra(EXTRA_RESUME_CURRENT, false)) YtEngine.video else null)
        ?: YtVideo(videoId, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_CHANNEL).orEmpty(), intent.getStringExtra(EXTRA_THUMB).orEmpty())
    /** „Od początku” – tylko pierwsze wczytanie tego ekranu. */
    private var fromStartPending = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Przycisk paska opcji w stylu YouTube na TV: kapsuła z cienką ikoną ([PlayerIcon]) i krótkim podpisem,
     * zaznaczona – biała z ciemną ikoną, lekko powiększona ([PlayerChrome.button]).
     */
    private fun barButton(icon: String, text: String, onClick: () -> Unit) = PlayerChrome.button(this, icon, text, onClick).also {
        optionsBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
    }

    private lateinit var autoplayBtn: TextView
    /** Półka „Następne utwory” (muzyka): autoodtwarzanie i kafelki propozycji – nad paskiem postępu. */
    private lateinit var shelf: LinearLayout
    private lateinit var shelfRow: LinearLayout
    private lateinit var shelfAutoplay: TextView
    private var shelfFor: String? = null
    /** Uchwyt „▲” pod paskiem postępu: wysuwa półkę propozycji od dołu (OK / ▼ na uchwycie). */
    private lateinit var shelfHandle: TextView
    private var shelfOpen = false
    val shelfHandleShown get() = ::shelfHandle.isInitialized && shelfHandle.visibility == View.VISIBLE
    /** Ostatnio zaznaczony kafelek półki (powrót ▼ na to samo miejsce). */
    private var shelfFocus = 0
    val shelfShown get() = ::shelf.isInitialized && shelf.visibility == View.VISIBLE
    /** Kafelki półki (testy). */
    val shelfTiles: List<View> get() = if (::shelfRow.isInitialized) (0 until shelfRow.childCount).map(shelfRow::getChildAt) else emptyList()
    /** Bieżący film to muzyka (oznaczenia w tytule, kanał wykonawcy). */
    val isMusic: Boolean get() {
        val title = titleView.text.toString(); val ch = current?.channel?.ifBlank { null } ?: video.channel
        // znaczniki muzyki w tytule / kanale albo „Wykonawca – Tytuł” krótkiego filmu (utwory z miksów często nie mają „official video”)
        return pl.iptv.tv.data.StubeMixes.isMusicVideo(title, ch) ||
            pl.iptv.tv.data.StubeMixes.isTrack(YtVideo(videoId, title, ch, duration = YtKeyless.isoDuration(current?.durationS ?: 0)))
    }

    override fun onResume() { super.onResume(); StubeTheme.enter() }
    override fun onPause() { super.onPause(); StubeTheme.exit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // kolory motywu STube (znaczniki rozdziałów, przyciski, okna)
        StubeTheme.enter()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // kanał IPTV (podgląd / tło menu) zatrzymany – dwa odtwarzacze naraz to brak dekoderów na wielu TV
        Playback.suspendForApp()          // kanał wróci po wyjściu z odtwarzacza (resumeAfterApp w ekranach menu)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 4000; setShowNextButton(false); setShowPreviousButton(false)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(16))
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // widok muzyczny nad obrazem i komunikatem wczytywania, pod nakładkami (SponsorBlock, odliczanie autoodtwarzania)
        stage = YtMusicStage(this, stageHost).apply { visibility = View.GONE }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        // komunikat SponsorBlock – duży, w rogu, czytelny z kanapy
        skipLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE; setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10))
            background = GradientDrawable().apply { setColor(0xD0101010.toInt()); cornerRadius = Ui.dp(22).toFloat(); setStroke(Ui.dp(2), Ui.accent) }
        }
        root.addView(skipLabel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(40), Ui.dp(150)) })

        // u góry (z paskiem odtwarzania): tytuł i kanał (czytelne z ok. 3 m), rozdział, przyciski opcji
        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(Ui.dp(32), Ui.dp(20), Ui.dp(32), Ui.dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        channelView = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 17f; maxLines = 1; setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        chapterLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; visibility = View.GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6))
            background = GradientDrawable().apply { setColor(0xC0101010.toInt()); cornerRadius = Ui.dp(16).toFloat() }
        }
        optionsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6)); clipChildren = false; clipToPadding = false }
        speedBtn = barButton(PlayerIcon.SPEED, "1.0×") { speedMenu() }
        qualityBtn = barButton(PlayerIcon.QUALITY, "Auto") { qualityMenu() }
        audioBtn = barButton(PlayerIcon.AUDIO, "Audio") { audioMenu() }
        subsBtn = barButton(PlayerIcon.CC, "Napisy") { subtitlesMenu() }
        eqBtn = barButton(PlayerIcon.EQ, "Dźwięk") { eqPanel() }
        chaptersBtn = barButton(PlayerIcon.CHAPTERS, "Rozdziały") { chaptersMenu() }.apply { visibility = View.GONE }
        queueBtn = barButton(PlayerIcon.QUEUE, "Następne") { openPanel(TAB_RELATED) }
        autoplayBtn = barButton(PlayerIcon.AUTOPLAY, "") { toggleAutoplay() }
        pipBtn = barButton(PlayerIcon.PIP, "Okno") { enterPip() }
        // (7.5) telefon: sam dźwięk – obraz odpięty (bez dekodowania), słuchanie przy wyłączonym / zablokowanym ekranie
        audioOnlyBtn = barButton(PlayerIcon.AUDIO_ONLY, "Tylko dźwięk") { toggleAudioOnly() }.apply { visibility = if (Tutorial.isTv(this@YtPlayerActivity)) View.GONE else View.VISIBLE }
        musicBtn = barButton(PlayerIcon.MUSIC, "Widok muzyczny") { setMusicView(true) }.apply { visibility = View.GONE }
        optionsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; addView(optionsBar)
            clipChildren = false; clipToPadding = false }
        infoBox.addView(titleView); infoBox.addView(channelView)
        infoBox.addView(chapterLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(8) })
        infoBox.addView(optionsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(12) })
        root.addView(infoBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // „Co następuje” – następna pozycja kolejki (film STube lub kanał TV), nad paskiem postępu
        upNextLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; visibility = View.GONE; maxLines = 2; maxWidth = Ui.dp(460)
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10))
            background = GradientDrawable().apply { setColor(0xD0101010.toInt()); cornerRadius = Ui.dp(14).toFloat(); setStroke(Ui.dp(1), Ui.withAlpha(Ui.accent, 0xA0)) }
        }
        root.addView(upNextLabel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(32), Ui.dp(110)) })
        root.addView(buildShelf(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = Ui.dp(96) })
        shelfHandle = TextView(this).apply {
            text = "⌃"; textSize = 20f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; visibility = View.GONE; contentDescription = "Następne utwory"
            setPadding(Ui.dp(34), 0, Ui.dp(34), 0); tag = "keepcolor nozoom"
            setTextColor(PlayerChrome.colors())
            background = PlayerChrome.pillBg()
            setOnClickListener { openShelf() }
        }
        root.addView(shelfHandle, FrameLayout.LayoutParams(-2, Ui.dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(6) })
        // przewijanie ◄► przy ukrytym pasku: tylko cienki pasek postępu z przesunięciem (pełne menu – po OK)
        seekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
            setPadding(Ui.dp(40), Ui.dp(24), Ui.dp(40), Ui.dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        seekBadge = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(6))
            background = GradientDrawable().apply { setColor(0xC0101010.toInt()); cornerRadius = Ui.dp(18).toFloat() }
        }
        seekProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent or 0xFF000000.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        }
        seekBox.addView(seekBadge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dp(10) })
        seekBox.addView(seekProgress, LinearLayout.LayoutParams(-1, Ui.dp(6)))
        root.addView(seekBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControllerVisibility(v) })
        setContentView(root)
        // pasek postępu jak w YouTube: czerwony, ◄► na pasku przewija o 10 s
        PlayerChrome.styleTimeBar(playerView, PlayerChrome.YT_RED)
        fromStartPending = intent.getBooleanExtra(EXTRA_FROM_START, false) && savedInstanceState == null
        if (videoId.isBlank()) { finish(); return }
        showTitle()
        handler.postDelayed(idleTick, AMBIENT_TICK_MS)
        lifecycleScope.launch { StubeQueue.items(); updateButtons() }
    }

    override fun onStart() {
        super.onStart()
        active = java.lang.ref.WeakReference(this)
        uiStarted = true
        YtEngine.listeners += engineListener
        connect()
    }

    /** Połączenie z usługą; film tego ekranu wczytywany tylko, gdy silnik gra coś innego (powrót z tła – bez przerwy). */
    private fun connect() {
        if (client != null || connecting != null) return
        connecting = YtClients.connect(this) { c ->
            connecting = null
            if (!uiStarted) { c.release(); return@connect }
            client = c
            c.player.addListener(playerListener)
            playerView.player = c.player
            c.setBackground(YtEngine.audioOnly)
            applyAudioOnlyView()
            stageBackground = null; updateStage()
            val same = YtEngine.video?.id == videoId
            when {
                same && YtEngine.error != null -> showFallback(YtEngine.error!!)
                same -> syncUi()
                else -> { status.visibility = View.VISIBLE; status.text = video.title.ifBlank { "Wczytywanie…" } + "\n\nWczytywanie…"
                    c.load(video, fromStartPending); fromStartPending = false }
            }
        }
    }

    /**
     * Ekran znika (WSTECZ, HOME, inna aplikacja): odpięcie powierzchni wideo i zwolnienie kontrolera – BEZ pauzy i zwalniania
     * odtwarzacza (dźwięk gra dalej w usłudze, obraz wyłączony dla oszczędności dekodera).
     */
    override fun onStop() {
        super.onStop()
        uiStarted = false
        YtEngine.listeners -= engineListener
        disconnect()
    }

    /** Ekran widoczny (między onStart i onStop) – odpowiedź kontrolera po onStop jest odrzucana. */
    private var uiStarted = false

    private fun disconnect() {
        (connecting as? java.util.concurrent.Future<*>)?.cancel(true); connecting = null
        val c = client ?: return
        if (YtEngine.active) c.setBackground(true)
        c.player.removeListener(playerListener)
        playerView.player = null          // clearVideoSurface – obraz odpięty od odtwarzacza
        c.release()                       // tylko kontroler; odtwarzacz w usłudze gra dalej
        client = null
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (!i.getBooleanExtra(EXTRA_RESUME_CURRENT, false) && i.getStringExtra(EXTRA_ID) != YtEngine.video?.id) stageSticky = false   // film wybrany z zewnątrz – znowu decydują jego znaczniki
        if (i.getBooleanExtra(EXTRA_RESUME_CURRENT, false) || i.getStringExtra(EXTRA_ID) == YtEngine.video?.id) { syncUi(); return }
        // inny film (STube, telefon) w tym samym oknie – także w trybie PiP
        fromStartPending = i.getBooleanExtra(EXTRA_FROM_START, false)
        showTitle()
        client?.let { it.load(video, fromStartPending); fromStartPending = false }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------- zdarzenia silnika i odtwarzacza ----------------

    private val engineListener = object : YtEngine.Listener {
        override fun onLoading(v: YtVideo) {
            if (v.id != videoId) { setIntent(intent(this@YtPlayerActivity, v)) }   // następny z kolejki – ten sam ekran
            showTitle()
            status.visibility = View.VISIBLE; status.text = v.title.ifBlank { "Wczytywanie…" } + "\n\nWczytywanie…"
        }
        override fun onLoaded(p: YtPlayable) {
            syncUi()
            if (player?.playbackState != Player.STATE_READY) {
                status.visibility = View.VISIBLE
                status.text = p.title.ifBlank { "Wczytywanie…" } + "\n\n" + (YtEngine.resumedFrom.takeIf { it > 0 }?.let { "Wznawianie od " + clock(it) } ?: "Wczytywanie…")
            }
        }
        override fun onError(reason: String): Boolean { showFallback(reason); return true }
        override fun onSkipped(seg: SbSegment) {
            skipLabel.text = "⏭  Pominięto: ${SponsorBlock.label(seg.category)}"
            skipLabel.visibility = View.VISIBLE
            handler.removeCallbacks(hideSkip); handler.postDelayed(hideSkip, 3000)
        }
        override fun onEnded(): Boolean {
            if (stageShown || suggestions.isEmpty() || isFinishing || isInPip) return false   // widok muzyczny: bez okna z teledyskiem – silnik gra następną propozycję
            endedPanel = true; openPanel(TAB_RELATED); startAutoplay(); return true
        }
        override fun onQueueChannel(q: QueueItem): Boolean { QueueUi.play(this@YtPlayerActivity, q); finish(); return true }
        override fun onStopped() { if (!isFinishing) finish() }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) status.visibility = View.GONE }
        override fun onIsPlayingChanged(isPlaying: Boolean) { keepScreen(); updateAutoPip(); if (isPlaying) hideAmbient(); if (::stage.isInitialized) stage.updatePlay() }
        override fun onTracksChanged(tracks: Tracks) = updateButtons()
    }

    /** Tytuł, kanał, rozdziały, przyciski i „Co następuje” wg bieżącego filmu. */
    private fun syncUi() {
        showTitle()
        showChapters(chapters)
        updateButtons()
        keepScreen()
        if (player?.playbackState == Player.STATE_READY) status.visibility = View.GONE
    }

    private fun showTitle() {
        val p = current
        titleView.text = p?.title?.ifBlank { null } ?: video.title.ifBlank { "STube" }
        val ch = p?.channel?.ifBlank { null } ?: video.channel
        channelView.text = listOfNotNull(ch.ifBlank { null }?.let { "📺 $it" }, if (live) "● NA ŻYWO" else null).joinToString("  •  ")
        channelView.visibility = if (channelView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateStage()
    }

    // ---------------- rozdziały ----------------

    /** Rozdziały na osi czasu: znaczniki na pasku postępu (kolor akcentu motywu) i przycisk listy. */
    private fun showChapters(list: List<YtChapter>) {
        val marks = list.drop(1).map { it.startMs }.toLongArray()
        playerView.setExtraAdGroupMarkers(marks, BooleanArray(marks.size))
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setAdMarkerColor(Ui.accent or 0xFF000000.toInt()); setPlayedAdMarkerColor(Ui.withAlpha(Ui.accent, 0xB0))
        }
        chaptersBtn.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        updateChapterLabel()
    }

    /** Indeks bieżącego rozdziału (-1 – brak rozdziałów). */
    val chapterIndex get() = YtChapters.indexAt(chapters, player?.currentPosition ?: 0)

    private fun updateChapterLabel() {
        val i = chapterIndex
        chapterLabel.text = if (i < 0) "" else "☰ ${i + 1}/${chapters.size}  ·  ${chapters[i].title}"
        chapterLabel.visibility = if (i >= 0) View.VISIBLE else View.GONE
    }

    /** ▼ następny / ▲ poprzedni (albo początek bieżącego) rozdział; false – brak celu. */
    fun jumpChapter(next: Boolean): Boolean {
        val p = player ?: return false
        val pos = p.currentPosition
        val to = (if (next) YtChapters.nextStart(chapters, pos) else YtChapters.prevStart(chapters, pos)) ?: return false
        p.seekTo(to)
        playerView.showController()
        updateChapterLabel()
        return true
    }

    private fun chaptersMenu() {
        if (chapters.isEmpty()) { toast("Ten film nie ma rozdziałów"); return }
        val cur = chapterIndex
        playerMenu("Rozdziały", chapters.mapIndexed { i, c ->
            ((if (i == cur) "▶  " else "     ") + clock(c.startMs) + "   " + c.title) to { player?.seekTo(c.startMs); playerView.showController(); updateChapterLabel() }
        })
    }

    // ---------------- prędkość ----------------

    /** Dostępne prędkości (procent). */
    val SPEEDS = YtEngine.SPEEDS
    private fun speedLabel(pct: Int) = (if (pct % 10 == 0) "%.1f×" else "%.2f×").format(java.util.Locale.US, pct / 100f)

    fun setSpeed(pct: Int) {
        Prefs.ytSpeedPct = pct
        player?.setPlaybackSpeed(if (live) 1f else pct / 100f)
        updateButtons()
    }

    private fun speedMenu() {
        if (live) { toast("Transmisja na żywo – prędkość 1.0×"); return }
        val cur = Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100
        playerMenu("Prędkość odtwarzania", SPEEDS.map { pct ->
            ((if (pct == cur) "✔  " else "     ") + speedLabel(pct) + if (pct == 100) "  (normalna)" else "") to { setSpeed(pct) }
        })
    }

    // ---------------- jakość, ścieżki dźwięku, napisy (TrackSelectionParameters) ----------------

    /** Grupy ścieżek danego typu z bieżących ścieżek odtwarzacza. */
    private fun groups(type: Int): List<Tracks.Group> = player?.currentTracks?.groups.orEmpty().filter { it.type == type && it.length > 0 }

    /** Dostępne wysokości obrazu (od najwyższej) – menu jakości. */
    fun qualities(): List<Int> = groups(C.TRACK_TYPE_VIDEO).flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
        .filter { it > 0 }.distinct().sortedDescending()

    /** Bieżąca wysokość obrazu (wybrana ścieżka). */
    private fun playingHeight(): Int = groups(C.TRACK_TYPE_VIDEO).firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).height } } ?: 0

    /** Jakość: 0 = automatycznie (do limitu z Ustawień), inaczej maks. wysokość – odtwarzacz wybiera najlepszą ścieżkę ≤ [h]. */
    fun setQuality(h: Int) {
        Prefs.ytQuality = h
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (h > 0) h else YtStreams.maxHeight).build()
        updateButtons()
    }

    private fun qualityMenu() {
        val qs = qualities()
        if (qs.isEmpty()) { toast("Jakość niedostępna dla tego filmu"); return }
        val cur = Prefs.ytQuality
        playerMenu("Jakość obrazu", listOf((if (cur == 0) "✔  " else "     ") + "Automatycznie" + (playingHeight().takeIf { it > 0 }?.let { " (teraz ${it}p)" } ?: "") to { setQuality(0) }) +
            qs.map { h -> ((if (cur == h) "✔  " else "     ") + "${h}p" + if (h >= 2160) " 4K" else if (h >= 1080) " HD" else "") to { setQuality(h) } })
    }

    /** Opis ścieżki: nazwa z manifestu, język albo numer. */
    private fun label(f: androidx.media3.common.Format, i: Int): String =
        f.label?.ifBlank { null } ?: f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale("pl")) } ?: "Ścieżka ${i + 1}"

    /** Wybór ścieżki [type] z grupy [g] (null – wyłączenie, tylko napisy). */
    fun selectTrack(type: Int, g: Tracks.Group?, index: Int = 0) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (g == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, index))
        if (type == C.TRACK_TYPE_TEXT) Prefs.ytSubLang = g?.getTrackFormat(index)?.language.orEmpty()
        p.trackSelectionParameters = b.build()
        updateButtons()
    }

    private fun audioMenu() {
        val gs = groups(C.TRACK_TYPE_AUDIO)
        if (gs.size < 2) { toast("Ten film ma jedną ścieżkę dźwięku" + (gs.firstOrNull()?.let { " (${label(it.getTrackFormat(0), 0)})" } ?: "")); return }
        playerMenu("Ścieżka dźwięku", gs.mapIndexed { i, g ->
            ((if (g.isSelected) "✔  " else "     ") + label(g.getTrackFormat(0), i)) to { selectTrack(C.TRACK_TYPE_AUDIO, g) }
        })
    }

    private fun subtitlesMenu() {
        val gs = groups(C.TRACK_TYPE_TEXT)
        if (gs.isEmpty()) { toast("Brak napisów dla tego filmu"); return }
        val anyOn = gs.any { it.isSelected }
        playerMenu("Napisy", listOf((if (!anyOn) "✔  " else "     ") + "Wyłączone" to { selectTrack(C.TRACK_TYPE_TEXT, null) }) +
            gs.mapIndexed { i, g -> ((if (g.isSelected) "✔  " else "     ") + label(g.getTrackFormat(0), i)) to { selectTrack(C.TRACK_TYPE_TEXT, g) } })
    }

    private fun updateButtons() {
        speedBtn.text = speedLabel(if (live) 100 else Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100)
        val h = playingHeight()
        qualityBtn.text = if (Prefs.ytQuality > 0) "${Prefs.ytQuality}p" else "Auto" + (h.takeIf { it > 0 }?.let { " ${it}p" } ?: "")
        audioBtn.visibility = if (groups(C.TRACK_TYPE_AUDIO).size > 1) View.VISIBLE else View.GONE
        val subs = groups(C.TRACK_TYPE_TEXT)
        subsBtn.visibility = if (subs.isEmpty()) View.GONE else View.VISIBLE
        subsBtn.text = subs.firstOrNull { it.isSelected }?.let { label(it.getTrackFormat(0), 0) } ?: "Napisy"
        eqBtn.text = if (Prefs.ytEqPreset == YtEq.FLAT) "Dźwięk" else YtEq.PRESETS.getOrElse(Prefs.ytEqPreset) { "Dźwięk" }
        queueBtn.text = "Następne" + StubeQueue.cached.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
        autoplayBtn.text = autoplayLabel()
        pipBtn.visibility = if (pipSupported && !YtEngine.audioOnly) View.VISIBLE else View.GONE
        audioOnlyBtn.text = if (YtEngine.audioOnly) "Obraz" else "Tylko dźwięk"
        updateUpNext()
        updateShelf()
        updateStage()
    }

    // ---------------- autoodtwarzanie i półka muzyczna ----------------

    private fun autoplayLabel() = "Autoodtwarzanie: " + if (Prefs.ytAutoplay) "wł." else "wył."

    /** Przełącznik autoodtwarzania (pasek opcji, półka muzyczna, MENU) – działa od razu, także dla gry w tle. */
    fun toggleAutoplay() {
        Prefs.ytAutoplay = !Prefs.ytAutoplay
        if (!Prefs.ytAutoplay) cancelAutoplay()
        autoplayBtn.text = autoplayLabel(); shelfAutoplay.text = autoplayLabel()
        playerView.showController()
    }

    private fun buildShelf(): View {
        shelf = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(Ui.dp(40), Ui.dp(28), Ui.dp(40), 0)
            // ciemny gradient od góry: kontrolki środka paska nie prześwitują spod półki
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt(), 0xF0000000.toInt()))
            clipChildren = false; clipToPadding = false }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        head.addView(TextView(this).apply { text = "Następne utwory"; setTextColor(Color.WHITE); textSize = 17f; typeface = Ui.typeface(android.graphics.Typeface.BOLD)
            setShadowLayer(6f, 0f, 1f, Color.BLACK) }, LinearLayout.LayoutParams(0, -2, 1f))
        shelfAutoplay = PlayerChrome.button(this, PlayerIcon.AUTOPLAY, autoplayLabel()) { toggleAutoplay() }
        head.addView(shelfAutoplay)
        shelf.addView(head, LinearLayout.LayoutParams(-1, -2))
        shelfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(8)) }
        shelf.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
            isSmoothScrollingEnabled = true; addView(shelfRow) }, LinearLayout.LayoutParams(-1, -2))
        return shelf
    }

    /** Kafelek propozycji: miniatura 16:9, tytuł i wykonawca; OK – od razu ten utwór. */
    private fun shelfTile(v: YtVideo): View {
        val w = Ui.dp(196); val h = Ui.dp(110)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(6))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = Ui.dp(12).toFloat(); setColor(0x26FFFFFF); setStroke(Ui.dp(3), Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = Ui.dp(12).toFloat(); setColor(Color.TRANSPARENT) })
            }
            setOnClickListener { cancelAutoplay(); playNow(v) }
            setOnFocusChangeListener { x, has -> x.animate().scaleX(if (has) 1.07f else 1f).scaleY(if (has) 1.07f else 1f).setDuration(120).start() }
            contentDescription = v.title
        }
        val img = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x33FFFFFF) }
        Ui.roundClip(img, Ui.dp(8).toFloat())
        if (v.thumb.isNotBlank()) img.load(v.thumb) { crossfade(false); size(w / 2, h / 2) }
        box.addView(img, LinearLayout.LayoutParams(w, h))
        box.addView(TextView(this).apply { text = v.title; setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(2), Ui.dp(6), 0, 0) }, LinearLayout.LayoutParams(w, -2))
        box.addView(TextView(this).apply { text = v.channel; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(2), 0, 0, 0) }, LinearLayout.LayoutParams(w, -2))
        return box
    }

    /**
     * Półka propozycji (muzyka, zwykły odtwarzacz): domyślnie schowana; pod paskiem postępu jest uchwyt „⌃” (▼ z kontrolek),
     * a OK / ▼ na nim wysuwa półkę od dołu. Znika razem z paskiem. W widoku muzycznym propozycje są po prawej stronie ekranu.
     */
    private fun updateShelf() {
        if (!::shelf.isInitialized) return
        val avail = isMusic && suggestions.isNotEmpty() && playerView.isControllerFullyVisible && !isInPip && !stageShown
        if (!avail) shelfOpen = false
        val show = avail && shelfOpen
        if (show && shelfFor != videoId) {
            shelfFor = videoId
            shelfRow.removeAllViews()
            suggestions.take(SHELF_MAX).forEach { shelfRow.addView(shelfTile(it), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(14) }) }
        }
        if (show && shelf.visibility != View.VISIBLE) {
            shelf.visibility = View.VISIBLE
            shelf.translationY = Ui.dp(220).toFloat(); shelf.alpha = 0f
            shelf.animate().translationY(0f).alpha(1f).setDuration(220).start()
        } else if (!show) { shelf.animate().cancel(); shelf.translationY = 0f; shelf.alpha = 1f; shelf.visibility = View.GONE }
        shelfHandle.visibility = if (avail && !shelfOpen) View.VISIBLE else View.GONE
        if (show) upNextLabel.visibility = View.GONE
    }

    /** Wysuwa półkę od dołu i zaznacza ostatnio wybrany kafelek. */
    fun openShelf() {
        shelfOpen = true; shelfFor = null; updateShelf()
        playerView.showController()
        (shelfRow.getChildAt(shelfFocus.coerceIn(0, maxOf(0, shelfRow.childCount - 1))) ?: shelfAutoplay).requestFocus()
    }

    /** Chowa półkę; fokus wraca na uchwyt. */
    fun closeShelf() {
        shelfOpen = false; updateShelf()
        if (shelfHandle.visibility == View.VISIBLE) shelfHandle.requestFocus()
    }

    /** Odliczanie autoodtwarzania po końcu filmu (panel propozycji otwarty; dowolny klawisz – anulowanie). */
    private var autoplayAt = 0L
    val autoplayPending get() = autoplayAt > 0
    private val autoplayTick = object : Runnable {
        override fun run() {
            val next = suggestions.firstOrNull() ?: run { cancelAutoplay(); return }
            val left = ((autoplayAt - android.os.SystemClock.uptimeMillis()) / 1000).toInt()
            if (left <= 0) { autoplayAt = 0; skipLabel.visibility = View.GONE; playNow(next); return }
            skipLabel.text = "▶  Za $left s: ${next.title}\nWSTECZ lub dowolny klawisz – anuluj"
            skipLabel.visibility = View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }

    private fun startAutoplay() {
        if (!Prefs.ytAutoplay || suggestions.isEmpty()) return
        autoplayAt = android.os.SystemClock.uptimeMillis() + AUTOPLAY_MS
        handler.removeCallbacks(autoplayTick); handler.post(autoplayTick)
    }

    private fun cancelAutoplay() {
        if (autoplayAt == 0L) return
        autoplayAt = 0; handler.removeCallbacks(autoplayTick); skipLabel.visibility = View.GONE
    }

    /** „Co następuje”: pierwsza pozycja kolejki (film / kanał TV), a bez kolejki – pierwsza propozycja. */
    private fun updateUpNext() {
        val q = StubeQueue.cached.firstOrNull { !(it.isVideo && it.ref == videoId) }
        val text = when {
            q != null && q.isVideo -> "⏭ Co następuje:  ▶ ${q.title}" + q.subtitle.ifBlank { null }?.let { "\n$it" }.orEmpty()
            q != null -> {
                val prog = pl.iptv.tv.data.Repository.byKey(q.ref)?.let { pl.iptv.tv.data.Repository.current(it) }
                "⏭ Co następuje:  📺 ${q.title}" + (prog?.let { "\n${it.title} • do ${Fmt.time(it.end)}" } ?: "")
            }
            suggestions.isNotEmpty() -> "Proponowane:  ▶ ${suggestions.first().title}"
            else -> ""
        }
        upNextLabel.text = text
        upNextLabel.visibility = if (text.isNotEmpty() && playerView.isControllerFullyVisible && !isInPip && !shelfShown) View.VISIBLE else View.GONE
    }

    // ---------------- widok muzyczny (7.9) ----------------

    /** Widok muzyczny na ekranie: utwór (nie transmisja), włączony ([Prefs.ytMusicView]), poza trybem PiP i oknem awaryjnym. */
    val stageWanted: Boolean get() = Prefs.ytMusicView && !live && (isMusic || stageSticky) && !isInPip && fallbackDialog?.isShowing != true
    /** Widok muzyczny już był na ekranie w tym oknie – zostaje dla kolejnych utworów (autoodtwarzanie, kolejka, poprzedni), także gdy tytuł nie ma znaczników muzyki. */
    private var stageSticky = false
    val stageShown get() = ::stage.isInitialized && stage.visibility == View.VISIBLE

    private fun stageArtist() = (current?.channel?.ifBlank { null } ?: video.channel).removeSuffix("- Topic").trim()
    private fun stageThumb() = video.thumb.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

    /** Pokazuje / chowa widok muzyczny wg [stageWanted]; odświeża tytuł, okładkę i listę „Następne”. */
    private fun updateStage() {
        if (!::stage.isInitialized || !::musicBtn.isInitialized) return
        val want = stageWanted
        val was = stage.visibility == View.VISIBLE
        if (want) {
            stageSticky = true
            stage.bind(videoId, titleView.text.toString(), stageArtist(), stageThumb())
            val sig = "$videoId|${StubeQueue.version}|${suggestions.size}"
            if (sig != stageSig) { stageSig = sig; stage.refresh() } else stage.updatePlay()
        }
        if (want != was) {
            stage.visibility = if (want) View.VISIBLE else View.GONE
            playerView.useController = !want && !isInPip
            if (want) {
                playerView.hideController(); infoBox.visibility = View.GONE; upNextLabel.visibility = View.GONE; shelf.visibility = View.GONE
                if (panel?.isOpen != true) stage.focusDefault()
            }
        }
        // widok muzyczny zasłania obraz – dekoder obrazu niepotrzebny (oszczędność); teledysk / zwykły widok włącza go z powrotem
        client?.let { if (stageBackground != want) { stageBackground = want; it.setBackground(want || YtEngine.audioOnly) } }
        // przełącznik widoku muzycznego zawsze pod ręką (nie tylko przy tytułach z oznaczeniami muzyki)
        musicBtn.visibility = if (!want && !live && !isInPip) View.VISIBLE else View.GONE
        if (want && was && stage.findFocus() == null && panel?.isOpen != true && fallbackDialog?.isShowing != true) stage.focusDefault()
    }

    /** Przełącznik widoku: muzyka (okładka, kolejka, tekst) ↔ teledysk (zwykły odtwarzacz wideo z paskiem). */
    fun setMusicView(on: Boolean) {
        Prefs.ytMusicView = on
        if (on) stageSticky = true
        updateStage()
        if (on) playerView.hideController() else playerView.showController()
    }

    private val stageHost = object : YtMusicStage.Host {
        override val player: Player? get() = this@YtPlayerActivity.player
        override fun playPause() { player?.let { if (it.isPlaying) it.pause() else it.play() }; stage.updatePlay() }
        override fun previous() { YtEngine.previous(this@YtPlayerActivity) }
        override fun next() { YtEngine.next() }
        override fun shuffle(): Boolean {
            YtEngine.shuffle = !YtEngine.shuffle
            if (YtEngine.shuffle) lifecycleScope.launch { StubeQueue.shuffle(); stageSig = ""; updateStage() }
            toast(if (YtEngine.shuffle) "Losowa kolejność włączona" else "Losowa kolejność wyłączona")
            return YtEngine.shuffle
        }
        override fun repeat(): Boolean {
            YtEngine.repeatOne = !YtEngine.repeatOne
            toast(if (YtEngine.repeatOne) "Powtarzanie utworu" else "Powtarzanie wyłączone")
            return YtEngine.repeatOne
        }
        override fun toVideo() = setMusicView(false)
        override fun options() = optionsMenu()
        override fun pick(e: YtMusicStage.Entry) {
            val q = e.queue
            if (q != null) lifecycleScope.launch {
                StubeQueue.remove(q.id)
                if (q.isVideo) playNow(q.toVideo()) else { QueueUi.play(this@YtPlayerActivity, q); YtEngine.stop(); finish() }
            } else e.video?.let { playNow(it) }
        }
        override fun remove(e: YtMusicStage.Entry) {
            val q = e.queue; val v = e.video
            lifecycleScope.launch {
                if (q != null) StubeQueue.remove(q.id)
                if (v != null) {
                    // odrzucony utwór: kara dla wykonawcy / gatunku, kolejka dopasowuje się w tle (podobne znikają), propozycje go nie pokażą
                    pl.iptv.tv.data.StubeTaste.recordDislike(v.id, v.title, v.channel)
                    runCatching { YtEngine.adaptQueue(v) }
                }
                stageSig = ""; updateStage()
            }
        }
        /** Bieżący utwór, potem kolejka STube / TV; pusta kolejka – propozycje (podobne utwory). */
        override fun entries(): List<YtMusicStage.Entry> {
            val cur = YtMusicStage.Entry(titleView.text.toString(), stageArtist(), stageThumb(), video, current = true)
            val queued = StubeQueue.cached.filter { !(it.isVideo && it.ref == videoId) }.map { YtMusicStage.Entry(it.title, it.subtitle, it.thumb, if (it.isVideo) it.toVideo() else null, it) }
            val rest = queued.ifEmpty { suggestions.take(25).map { YtMusicStage.Entry(it.title, it.channel, it.thumb, it) } }
            return listOf(cur) + rest
        }
        override fun wantLyrics() {
            val id = videoId; val title = titleView.text.toString(); val ch = stageArtist()
            val sec = (player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L) / 1000
            lifecycleScope.launch { val t = YtLyrics.find(title, ch, sec); if (videoId == id && ::stage.isInitialized) stage.setLyrics(t) }
        }
    }

    // ---------------- obraz w obrazie (PiP) ----------------

    val pipSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private val isInPip get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /** Pływające okno w rogu: film gra dalej, a menu IPTV / STube można przeglądać pod spodem. */
    fun enterPip(): Boolean {
        if (!pipSupported) { toast("Obraz w obrazie niedostępny na tym urządzeniu"); return false }
        playerView.hideController()
        val ratio = player?.videoSize?.let { v -> if (v.width > 0 && v.height > 0) Rational(v.width, v.height) else null } ?: Rational(16, 9)
        val safe = ratio.toFloat().let { if (it > 2.39f) Rational(239, 100) else if (it < 1 / 2.39f) Rational(100, 239) else ratio }
        return runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build()) }.getOrDefault(false)
    }

    /**
     * (7.5) Telefon / tablet: wyjście z aplikacji (HOME, gest) w trakcie filmu – od razu pływające okno (jak YouTube).
     * Nie na TV, nie w trybie samego dźwięku (wtedy gra w tle z powiadomieniem) i nie w pauzie.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoPipAllowed()) enterPip()
    }

    private fun autoPipAllowed() = pipSupported && !Tutorial.isTv(this) && !YtEngine.audioOnly && !isInPip && player?.isPlaying == true

    /** Android 12+: płynne przejście w okno przy wyjściu z aplikacji (system animuje przejście zamiast przełączenia). */
    private fun updateAutoPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !pipSupported || Tutorial.isTv(this)) return
        runCatching { setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(autoPipAllowed()).build()) }
    }

    /**
     * (7.5) Tylko dźwięk (telefon): ścieżka obrazu wyłączona w silniku (dekoder obrazu zwolniony – mniej baterii, bez błędów
     * dekodera przy wyłączonym ekranie), powierzchnia obrazu odpięta; ekran może zgasnąć – dźwięk gra dalej w usłudze
     * z powiadomieniem (odtwórz / pauza, poprzedni / następny, zatrzymaj).
     */
    fun toggleAudioOnly() {
        YtEngine.audioOnly = !YtEngine.audioOnly
        client?.setBackground(YtEngine.audioOnly)
        applyAudioOnlyView()
        updateButtons(); updateAutoPip(); keepScreen()
        toast(if (YtEngine.audioOnly) "Tylko dźwięk – możesz wyłączyć ekran, film gra dalej" else "Obraz włączony")
    }

    /** Powierzchnia obrazu: w trybie samego dźwięku schowana (zwolniona), status z tytułem zamiast obrazu. */
    private fun applyAudioOnlyView() {
        val on = YtEngine.audioOnly
        playerView.videoSurfaceView?.visibility = if (on) View.INVISIBLE else View.VISIBLE
        if (on) { status.visibility = View.VISIBLE; status.text = "♪ " + video.title.ifBlank { "STube" } + "\n\nTylko dźwięk" }
        else if (status.text.endsWith("Tylko dźwięk")) status.visibility = View.GONE
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        // w oknie PiP: sam obraz (bez paska i nakładek); powrót – pełny odtwarzacz
        playerView.useController = !inPip && !stageShown
        updateStage()
        if (inPip) { infoBox.visibility = View.GONE; upNextLabel.visibility = View.GONE; panel?.close(); hideAmbient() }
        // okno PiP zamknięte (✕) – koniec odtwarzania; rozwinięte – ekran wraca do zwykłego trybu
        else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { YtEngine.stop(); finish() }
    }

    // ---------------- korektor ----------------

    /**
     * Panel dźwięku: presety (rząd przycisków) i suwaki pasm – ▲▼ między elementami, ◄► zmienia pasmo o 1 dB
     * (zmiana pasma = preset „Własny”). Dźwięk zmienia się od razu, film gra dalej.
     */
    fun eqPanel(): AlertDialog? {
        val s = eq
        if (s == null || !s.available || s.bands == 0) { toast("Korektor dźwięku niedostępny na tym urządzeniu"); return null }
        val presetViews = mutableListOf<TextView>()
        val bars = mutableListOf<android.widget.SeekBar>(); val values = mutableListOf<TextView>()
        var syncing = false
        fun refresh() {
            syncing = true
            presetViews.forEachIndexed { i, v -> v.text = (if (i == Prefs.ytEqPreset) "✔ " else "") + YtEq.PRESETS[i] }
            s.current().forEachIndexed { b, mb -> bars.getOrNull(b)?.progress = (mb - s.min) / YtEq.STEP_MB; values.getOrNull(b)?.text = YtEq.dbLabel(mb) }
            updateButtons()
            syncing = false
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(4)) }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        YtEq.PRESETS.indices.forEach { i ->
            presets.addView(TextView(this).apply {
                tag = "item"; textSize = 16f; isFocusable = true; isClickable = true; setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
                setOnClickListener {
                    if (i == YtEq.CUSTOM && Prefs.ytEqCustom.isEmpty()) Prefs.ytEqCustom = s.current()
                    Prefs.ytEqPreset = i; s.apply(i); refresh()
                }
            }.also { presetViews += it }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) })
        box.addView(TextView(this).apply { tag = "text2"; textSize = 14f; text = "Pasma: ▲▼ wybór, ◄► ±1 dB"; setPadding(0, Ui.dp(12), 0, Ui.dp(4)) })
        val focusBg = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Ui.withAlpha(Ui.focus, 0x60)); cornerRadius = Ui.dp(10).toFloat() })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        s.centersHz.forEachIndexed { b, hz ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(2), 0, Ui.dp(2)) }
            row.addView(TextView(this).apply { text = YtEq.hzLabel(hz); textSize = 16f }, LinearLayout.LayoutParams(Ui.dp(88), -2))
            val value = TextView(this).apply { textSize = 16f; gravity = Gravity.END }
            val bar = android.widget.SeekBar(this).apply {
                max = (s.max - s.min) / YtEq.STEP_MB; keyProgressIncrement = 1; isFocusable = true
                background = focusBg.constantState?.newDrawable()?.mutate()
                setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14))
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent or 0xFF000000.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(Ui.accent or 0xFF000000.toInt())
                contentDescription = YtEq.hzLabel(hz)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                        if (syncing) return
                        s.setBand(b, s.min + progress * YtEq.STEP_MB); refresh()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            }
            row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(value, LinearLayout.LayoutParams(Ui.dp(72), -2))
            bars += bar; values += value
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        Ui.style(box)
        refresh()
        val d = Ui.dialog(this).setTitle("Dźwięk – korektor")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNeutralButton("Płaski") { _, _ -> Prefs.ytEqPreset = YtEq.FLAT; s.apply(YtEq.FLAT); updateButtons() }
            .setPositiveButton("Zamknij", null)
            .create()
        d.show()
        // fokus na wybranym presecie – od razu ◄► między presetami, ▼ do suwaków
        presetViews.getOrNull(Prefs.ytEqPreset)?.let { v -> v.requestFocus(); d.window?.decorView?.post { v.requestFocus() } }
        eqDialog = d
        return d
    }

    /** Okno korektora (testy). */
    var eqDialog: AlertDialog? = null; private set

    /** Menu wyboru: w widoku muzycznym – okno w stylu widoku ([YtMusicStage.showOptions]), inaczej zwykłe okno systemowe z motywem. */
    private fun playerMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        if (stageShown) stage.showOptions(title, items.map { (l, a) -> YtMusicStage.Opt("", l, action = a) })
        else Dialogs.menu(this, title, items)
    }

    /** Opcje widoku muzycznego: ikony, wartości po prawej, wszystko w jednym oknie ze stylem motywu. */
    private fun showMusicOptions() {
        val opts = buildList {
            add(YtMusicStage.Opt(PlayerIcon.SPEED, "Prędkość", speedLabel(if (live) 100 else Prefs.ytSpeedPct)) { speedMenu() })
            add(YtMusicStage.Opt(PlayerIcon.QUALITY, "Jakość", if (Prefs.ytQuality > 0) "${Prefs.ytQuality}p" else "Auto") { qualityMenu() })
            if (groups(C.TRACK_TYPE_AUDIO).size > 1) add(YtMusicStage.Opt(PlayerIcon.AUDIO, "Ścieżka dźwięku") { audioMenu() })
            if (groups(C.TRACK_TYPE_TEXT).isNotEmpty()) add(YtMusicStage.Opt(PlayerIcon.CC, "Napisy") { subtitlesMenu() })
            add(YtMusicStage.Opt(PlayerIcon.EQ, "Dźwięk – korektor", YtEq.PRESETS.getOrElse(Prefs.ytEqPreset) { "Płaski" }) { eqPanel() })
            if (chapters.isNotEmpty()) add(YtMusicStage.Opt(PlayerIcon.CHAPTERS, "Rozdziały", "${chapters.size}") { chaptersMenu() })
            add(YtMusicStage.Opt(PlayerIcon.AUTOPLAY, "Autoodtwarzanie", if (Prefs.ytAutoplay) "wł." else "wył.") { toggleAutoplay() })
            add(YtMusicStage.Opt(PlayerIcon.LYRICS, "Teksty piosenek", if (Prefs.ytLyrics) "wł." else "wył.") {
                Prefs.ytLyrics = !Prefs.ytLyrics; stage.resetLyrics(); toast("Teksty piosenek " + if (Prefs.ytLyrics) "włączone" else "wyłączone")
            })
            add(YtMusicStage.Opt(PlayerIcon.VIDEO, "Teledysk (wideo)") { setMusicView(false) })
            if (!Tutorial.isTv(this@YtPlayerActivity)) add(YtMusicStage.Opt(PlayerIcon.AUDIO_ONLY, "Tylko dźwięk", if (YtEngine.audioOnly) "wł." else "wył.") { toggleAudioOnly() })
            if (pipSupported && !YtEngine.audioOnly) add(YtMusicStage.Opt(PlayerIcon.PIP, "Obraz w obrazie") { enterPip() })
            add(YtMusicStage.Opt(PlayerIcon.QUEUE, "Propozycje i kolejka") { openPanel(TAB_RELATED) })
            add(YtMusicStage.Opt(PlayerIcon.INFO, "SponsorBlock", if (Prefs.sbEnabled) "wł." else "wył.") {
                Prefs.sbEnabled = !Prefs.sbEnabled
                toast("SponsorBlock " + if (Prefs.sbEnabled) "włączony (od następnego filmu)" else "wyłączony (od następnego filmu)")
            })
            add(YtMusicStage.Opt(PlayerIcon.INFO, "Wygaszacz teraz") { showAmbient() })
            add(YtMusicStage.Opt(PlayerIcon.STOP, "Zatrzymaj odtwarzanie") { YtEngine.stop(); finish() })
        }
        stage.showOptions("Opcje odtwarzania", opts)
    }

    /** Opcje odtwarzania (MENU na pilocie). */
    private fun optionsMenu() {
        if (stageShown) { showMusicOptions(); return }
        Dialogs.menu(this, "Opcje odtwarzania", buildList {
            add("⏩ Prędkość: " + speedLabel(if (live) 100 else Prefs.ytSpeedPct) to { speedMenu() })
            add("🎞 Jakość: " + (if (Prefs.ytQuality > 0) "${Prefs.ytQuality}p" else "automatycznie") to { qualityMenu() })
            if (groups(C.TRACK_TYPE_AUDIO).size > 1) add("🔊 Ścieżka dźwięku" to { audioMenu() })
            if (groups(C.TRACK_TYPE_TEXT).isNotEmpty()) add("💬 Napisy" to { subtitlesMenu() })
            add("🎚 Dźwięk: " + YtEq.PRESETS.getOrElse(Prefs.ytEqPreset) { "Płaski" } to { eqPanel(); Unit })
            if (chapters.isNotEmpty()) add("☰ Rozdziały (${chapters.size})" to { chaptersMenu() })
            if (!live) add(if (stageShown) "🎬 Teledysk (wideo)" to { setMusicView(false) } else "🎵 Widok muzyczny" to { setMusicView(true) })
            add("📋 Propozycje i kolejka" to { openPanel(TAB_RELATED) })
            add("🔁 " + autoplayLabel() to { toggleAutoplay() })
            if (pipSupported) add("⧉ Obraz w obrazie (PiP)" to { enterPip(); Unit })
            add("📺 Wygaszacz teraz" to { showAmbient() })
            add("⏹ Zatrzymaj odtwarzanie" to { YtEngine.stop(); finish() })
            add("⏭ SponsorBlock: " + (if (Prefs.sbEnabled) "włączony" else "wyłączony") to {
                Prefs.sbEnabled = !Prefs.sbEnabled
                toast("SponsorBlock " + if (Prefs.sbEnabled) "włączony (od następnego filmu)" else "wyłączony (od następnego filmu)")
            })
        })
    }

    // ---------------- koniec filmu, kolejka, panel boczny ----------------

    /** Panel otwarty po końcu filmu – jego zamknięcie zamyka odtwarzacz. */
    private var endedPanel = false
    var panel: SidePanel? = null; private set

    /** Panel boczny: „Propozycje” (podobne filmy) i „Kolejka” (filmy STube i kanały TV). */
    fun openPanel(tab: Int = TAB_RELATED) {
        val p = panel ?: SidePanel(this, listOf(
            SidePanel.Tab("▶ Propozycje", "Brak propozycji dla tego filmu") { suggestions.map { v -> QueueUi.videoEntry(this, v, { panel?.reload(); updateButtons() }) { playNow(it) } } },
            SidePanel.Tab("☰ Kolejka", "Kolejka jest pusta – długie OK na filmie (STube, propozycje) lub kanale (TV) → „Dodaj do kolejki”") {
                QueueUi.entries(this, { panel?.reload(); updateButtons() }) { q ->
                    lifecycleScope.launch {
                        StubeQueue.remove(q.id)
                        if (q.isVideo) { panel?.close(); playNow(q.toVideo()) } else { QueueUi.play(this@YtPlayerActivity, q); YtEngine.stop(); finish() }
                    }
                }
            },
            // bogatsze propozycje: więcej od tego twórcy i z powiązanej kategorii (gatunek muzyczny / słowa tytułu)
            SidePanel.Tab("▶ Z tego kanału", "Brak innych filmów tego kanału") { moreLike(channelQuery()) },
            SidePanel.Tab("▶ Podobne", "Brak podobnych filmów") { moreLike(categoryQuery()) },
        )) { cancelAutoplay(); if (endedPanel) { endedPanel = false; YtEngine.stop(); finish() } }.also { panel = it }
        playerView.hideController()
        p.open(tab)
    }

    private fun channelQuery() = (current?.channel?.ifBlank { null } ?: video.channel).removeSuffix(" - Topic").trim()

    /** Zapytanie „podobnych”: gatunek muzyczny filmu albo najważniejsze słowa tytułu. */
    fun categoryQuery(): String {
        val t = titleView.text.toString()
        pl.iptv.tv.data.StubeMixes.GENRES.firstOrNull { it.pattern.containsMatchIn(t + " " + channelQuery()) }?.let { return it.queries.first() }
        return pl.iptv.tv.data.StubeMixes.tokens(t).take(3).joinToString(" ").ifBlank { channelQuery() } + if (isMusic) " music" else ""
    }

    private suspend fun moreLike(q: String): List<SidePanel.Entry> {
        if (q.isBlank()) return emptyList()
        val list = runCatching { YtKeyless.search(q) }.getOrDefault(emptyList()).filter { it.id != videoId && it.duration != "P0D" }.take(25)
        return list.map { v -> QueueUi.videoEntry(this, v, { panel?.reload(); updateButtons() }) { playNow(it) } }
    }

    /** Film z propozycji / kolejki / telefonu teraz – w tym samym oknie (pozycja bieżącego zapisana w historii). */
    fun playNow(v: YtVideo) {
        cancelAutoplay()
        endedPanel = false
        panel?.close()
        lifecycleScope.launch { StubeQueue.removeRef(QueueItem.KIND_YT, v.id) }
        setIntent(intent(this, v))
        showTitle()
        client?.load(v, false) ?: YtEngine.load(this, v)
    }

    // ---------------- wygaszacz (tryb ambient) ----------------

    private var lastInput = android.os.SystemClock.uptimeMillis()
    private var ambient: View? = null
    val ambientShown get() = ambient != null

    /** Ekran nie gaśnie tylko w trakcie odtwarzania – w pauzie system może włączyć wygaszacz / uśpić TV (oszczędzanie energii). */
    private fun keepScreen() {
        // (7.5) tylko dźwięk – ekran może zgasnąć (oszczędzanie baterii), dźwięk gra dalej w usłudze
        if (player?.playWhenReady == true && !YtEngine.audioOnly) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Po [Prefs.ytAmbientMin] min pauzy bez pilota: czarny ekran z zegarem, tytułem i podpowiedzią (przyciemnione, przesuwane
     * co minutę – bez wypalania matrycy OLED). Dowolny klawisz wraca do odtwarzacza.
     */
    fun showAmbient() {
        if (ambient != null || isFinishing || isInPip) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true; setOnClickListener { hideAmbient() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0.55f; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 64f; tag = "clock" })
        col.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; text = titleView.text.ifBlank { "STube" }; maxLines = 2; gravity = Gravity.CENTER; maxWidth = Ui.dp(640) })
        col.addView(TextView(this).apply { setTextColor(0xFFAAAAAA.toInt()); textSize = 15f; text = "⏸ Wstrzymano  •  dowolny klawisz – powrót"; setPadding(0, Ui.dp(10), 0, 0) })
        box.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        ambient = box
        playerView.hideController()
        updateAmbient()
    }

    fun hideAmbient() {
        val v = ambient ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        ambient = null
        lastInput = android.os.SystemClock.uptimeMillis()
    }

    private fun updateAmbient() {
        val box = ambient as? FrameLayout ?: return
        val col = box.getChildAt(0)
        (col as? LinearLayout)?.findViewWithTag<TextView>("clock")?.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        // co minutę inne miejsce (ochrona przed wypaleniem)
        val m = (System.currentTimeMillis() / 60_000L).toInt()
        col.translationX = ((m * 37) % 200 - 100) * resources.displayMetrics.density
        col.translationY = ((m * 23) % 120 - 60) * resources.displayMetrics.density
    }

    private val idleTick = object : Runnable {
        override fun run() {
            val p = player
            val min = Prefs.ytAmbientMin
            if (ambient != null) updateAmbient()
            else if (p != null && min > 0 && !p.playWhenReady && panel?.isOpen != true && fallbackDialog?.isShowing != true &&
                android.os.SystemClock.uptimeMillis() - lastInput >= min * 60_000L) showAmbient()
            if (playerView.isControllerFullyVisible) { updateChapterLabel(); updateUpNext() }
            handler.postDelayed(this, AMBIENT_TICK_MS)
        }
    }

    // ---------------- sterowanie z telefonu ([pl.iptv.tv.remote.StubeRemote]) ----------------

    /** Polecenie z telefonu: toggle, play, pause, seek (ms), chapter_next/prev, next (kolejka), stop. */
    fun remote(cmd: String, arg: Long = 0): Boolean {
        val p = player
        lastInput = android.os.SystemClock.uptimeMillis(); hideAmbient()
        when (cmd) {
            "toggle" -> p?.let { it.playWhenReady = !it.playWhenReady } ?: return false
            "play" -> p?.playWhenReady = true
            "pause" -> p?.playWhenReady = false
            "seek" -> p?.let { it.seekTo((it.currentPosition + arg).coerceAtLeast(0)) } ?: return false
            "chapter_next" -> return jumpChapter(true)
            "chapter_prev" -> return jumpChapter(false)
            "next" -> YtEngine.next()
            "stop" -> { YtEngine.stop(); finish() }
            else -> return false
        }
        playerView.showController()
        return true
    }

    /** Stan dla telefonu. */
    fun remoteStatus(): org.json.JSONObject = org.json.JSONObject().apply {
        val p = player
        put("id", videoId); put("title", titleView.text.toString())
        put("playing", p?.isPlaying == true); put("position", p?.currentPosition ?: 0)
        put("duration", p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0); put("live", live)
        put("chapter", chapterIndex.takeIf { it >= 0 }?.let { chapters[it].title } ?: "")
    }

    private fun onControllerVisibility(v: Int) {
        infoBox.visibility = if (isInPip) View.GONE else v
        updateChapterLabel(); updateUpNext(); updateShelf()
    }

    private fun toast(s: String) = I18n.makeToast(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** Pozycja do historii oglądania (pasek na kafelku STube, wznawianie) – zapis w silniku. */
    fun saveProgress() = YtEngine.saveProgress()

    /** Pominięcie segmentu SponsorBlock (silnik; testy). */
    fun skipIfNeeded(posMs: Long) = YtEngine.skipIfNeeded(posMs)
    private val hideSkip = Runnable { skipLabel.visibility = View.GONE }

    fun errorMessage(e: androidx.media3.common.PlaybackException, http: Int = 0) = YtEngine.errorMessage(e, http)

    /** Okno awaryjne – czytelne na TV, fokus od razu na „Odtwórz w SmartTube”. */
    fun showFallback(reason: String) {
        if (isFinishing || isDestroyed || fallbackDialog?.isShowing == true) return
        status.visibility = View.GONE
        // kolejność przycisków na TV (układ pionowy): SmartTube → YouTube → Zamknij
        val url = videoUrl
        val d = Ui.dialog(this).setTitle("Nie można odtworzyć filmu")
            .setMessage("$reason\n\nWybierz inny sposób odtwarzania:")
            .setPositiveButton("Odtwórz w SmartTube") { _, _ ->
                YtEngine.stop()
                if (SmartTubeIntegrationHelper.play(this, url) { finish() }) finish()
            }
            .setNegativeButton("Odtwórz w YouTube") { _, _ -> YtEngine.stop(); SmartTubeIntegrationHelper.playInYouTube(this, url); finish() }
            .setNeutralButton("Zamknij") { _, _ -> YtEngine.stop(); finish() }
            .setOnCancelListener { YtEngine.stop(); finish() }
            .create()
        fallbackDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------------- pilot ----------------

    /**
     * Klawisze (odpowiednik onPreviewKeyEvent): OK przy ukrytym pasku tylko pokazuje pasek (z fokusem na ⏯) – nie pauzuje;
     * pauza: przycisk ⏯ na pasku albo Play/Pause na pilocie. ◄► 10 s, ▲▼ rozdziały, MENU – opcje.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (event.action == KeyEvent.ACTION_DOWN) lastInput = android.os.SystemClock.uptimeMillis()
        // wygaszacz: dowolny klawisz wraca do odtwarzacza (bez wykonania akcji)
        if (ambient != null) { if (event.action == KeyEvent.ACTION_UP) hideAmbient(); return true }
        // odliczanie autoodtwarzania: dowolny klawisz je przerywa (sam klawisz działa dalej – np. WSTECZ zamyka ekran końcowy)
        if (event.action == KeyEvent.ACTION_DOWN) cancelAutoplay()
        // panel boczny prowadzi nawigację sam (fokus nie wychodzi do kontrolek pod spodem)
        if (panel?.onKey(event) == true) return true
        if (panel?.isOpen == true) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) optionsMenu(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_WINDOW) { if (event.action == KeyEvent.ACTION_DOWN) enterPip(); return true }
        // widok muzyczny prowadzi fokus sam (przyciski, pasek, lista, tekst); klawisze multimedialne działają jak w odtwarzaczu
        if (stageShown) {
            // okno opcji w widoku muzycznym: WSTECZ zamyka je (nie cały odtwarzacz)
            if (stage.optionsOpen && event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_DOWN) stage.closeOptions(); return true }
            if (stage.optionsOpen) return super.dispatchKeyEvent(event)
            if (stage.handleKey(event)) return true
            val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) stageHost.playPause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) p?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) p?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down) YtEngine.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down) YtEngine.previous(this); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down && p != null) p.seekTo(p.currentPosition + SEEK_STEP_MS); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down && p != null) p.seekTo((p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)); return true }
            }
            return super.dispatchKeyEvent(event)
        }
        val hidden = !playerView.isControllerFullyVisible
        if (hidden && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            // OK: wyłącznie pokazanie paska (oba zdarzenia pochłonięte – puszczenie OK nie „klika” przycisku ⏯)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showControllerOk = true
            if (event.action == KeyEvent.ACTION_UP && showControllerOk) { showControllerOk = false; playerView.showController() }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && showControllerOk && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            showControllerOk = false; return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && p != null && hidden) {
            when (event.keyCode) {
                // pasek ukryty: ◄► przewija o 10 s (jak w aplikacjach YouTube na TV)
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekHidden(p, -1, event.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!p.isCurrentMediaItemLive) seekHidden(p, 1, event.repeatCount); return true }
                // ▲▼ przy ukrytym pasku: poprzedni / następny rozdział (bez rozdziałów – pokazanie paska)
                KeyEvent.KEYCODE_DPAD_UP -> if (chapters.isNotEmpty()) { jumpChapter(next = false); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (chapters.isNotEmpty()) { if (!jumpChapter(next = true)) playerView.showController(); return true }
            }
        }
        // pasek widoczny: ▲ z kontrolek odtwarzacza → przyciski opcji u góry, ▼ z przycisków → ⏯ (PlayerView sam tam nie prowadzi)
        if (event.action == KeyEvent.ACTION_DOWN && !hidden && !isInPip) {
            val f = window.decorView.findFocus()
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && f != null && f.id in centerControls) {
                (0 until optionsBar.childCount).map(optionsBar::getChildAt).firstOrNull { it.visibility == View.VISIBLE }?.let { it.requestFocus(); playerView.showController(); return true }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && isInside(f, optionsBar)) {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.let { it.requestFocus(); playerView.showController(); return true }
            }
            // uchwyt pod paskiem postępu: ▼ z kontrolek → uchwyt; OK / ▼ na uchwycie wysuwa półkę od dołu, ▲ wraca na ⏯
            if (shelfHandleShown && f != null) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !isInside(f, shelfHandle) && isInside(f, playerView)) { shelfHandle.requestFocus(); playerView.showController(); return true }
                if (isInside(f, shelfHandle)) {
                    playerView.showController()
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { openShelf(); return true }
                        KeyEvent.KEYCODE_DPAD_UP -> { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus(); return true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    }
                }
            }
            // półka wysunięta: w niej pasek nie znika; ▲ z kafelków – schowanie półki (fokus na uchwycie), ▼ z kontrolek – kafelki
            if (shelfShown && f != null) {
                val inShelf = isInside(f, shelf)
                if (inShelf) playerView.showController()
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !inShelf && isInside(f, playerView)) {
                    (shelfRow.getChildAt(shelfFocus.coerceIn(0, maxOf(0, shelfRow.childCount - 1))) ?: shelfAutoplay).requestFocus()
                    playerView.showController(); return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && inShelf && isInside(f, shelfRow)) {
                    shelfFocus = shelfRow.indexOfChild(f).coerceAtLeast(0)
                    closeShelf(); return true
                }
                // na skraju półki ◄► nie wychodzą poza nią (fokus nie „przeskakuje” na kontrolki obok)
                if (inShelf && isInside(f, shelfRow) && ((event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && shelfRow.indexOfChild(f) == 0) ||
                        (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && shelfRow.indexOfChild(f) == shelfRow.childCount - 1))) return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible && p != null) {
            playerView.hideController(); return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** Kontrolki środkowe paska (⏪ ⏯ ⏩) – z nich ▲ przechodzi do przycisków opcji (z osi czasu ▲ idzie do ⏯). */
    private val centerControls = setOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_rew_with_amount,
        androidx.media3.ui.R.id.exo_ffwd_with_amount, androidx.media3.ui.R.id.exo_prev, androidx.media3.ui.R.id.exo_next)

    private fun isInside(v: View, parent: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === parent) return true; x = x.parent as? View }
        return false
    }

    /** OK wciśnięte przy ukrytym pasku – pokazanie paska po puszczeniu. */
    private var showControllerOk = false

    private lateinit var seekBox: LinearLayout
    private lateinit var seekBadge: TextView
    private lateinit var seekProgress: android.widget.ProgressBar
    private var seekAccum = 0L
    private val hideSeek = Runnable { seekAccum = 0; if (::seekBox.isInitialized) seekBox.visibility = View.GONE }
    /** Widoczny pasek przewijania ◄► (testy). */
    val seekBarShown get() = ::seekBox.isInitialized && seekBox.visibility == View.VISIBLE

    /** ◄► przy ukrytym pasku: przewinięcie ([SEEK_STEP_MS], przy przytrzymaniu większe) i cienki pasek z przesunięciem – bez pełnego menu. */
    private fun seekHidden(p: Player, dir: Int, repeat: Int) {
        val step = if (repeat >= 6) SEEK_STEP_MS * 6 else if (repeat >= 2) SEEK_STEP_MS * 3 else SEEK_STEP_MS
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + dir * step).coerceIn(0, dur)
        seekAccum += target - p.currentPosition
        p.seekTo(target)
        fun fmt(ms: Long) = (ms / 1000).let { s -> if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
        val sign = if (seekAccum >= 0) "+" else "−"
        seekBadge.text = "$sign${Math.abs(seekAccum) / 1000} s   ${fmt(target)}" + if (p.duration > 0) " / ${fmt(p.duration)}" else ""
        seekProgress.progress = if (p.duration > 0) (target * 1000 / p.duration).toInt() else 0
        seekBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek); handler.postDelayed(hideSeek, 1500)
    }

    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null)
        panel?.close()
        YtEngine.listeners -= engineListener
        disconnect()
        if (active?.get() === this) active = null
    }
}

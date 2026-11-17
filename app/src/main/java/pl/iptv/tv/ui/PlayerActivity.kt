package pl.iptv.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import coil.load
import pl.iptv.tv.data.Scores.State as MState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pl.iptv.tv.R
import pl.iptv.tv.data.CatchupUrlBuilder
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.KeyMap.Action as A
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Repository
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {
    override val x25Root = true
    override val isPlayerScreen = true
    companion object {
        fun delayLabel(ms: Int) = when { ms == 0 -> "bez przesunięcia"; ms < 0 -> "dźwięk wcześniej o ${-ms} ms"; else -> "dźwięk później o $ms ms" }
        /** Odtwarzacz w okienku PiP – zamykany, gdy użytkownik wraca do aplikacji innym ekranem. */
        var pipActivity: java.lang.ref.WeakReference<PlayerActivity>? = null
        fun closePip() { pipActivity?.get()?.let { if (!it.isFinishing) it.finish() }; pipActivity = null }
        const val EXTRA_CHANNEL = "ch"
        const val EXTRA_GROUP = "group"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        private const val RECENT_AFTER_MS = 60_000L
        private const val PANEL_TIMEOUT_MS = 30_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var touchBar: View
    private lateinit var errorTv: TextView
    private lateinit var numberTv: TextView
    private lateinit var scrim: View
    private lateinit var leftPanel: View
    private lateinit var rightPanel: View
    private lateinit var lpChannels: RecyclerView
    private lateinit var lpProgrammes: RecyclerView
    private lateinit var lpGroup: TextView
    private lateinit var lpEpgTitle: TextView
    private lateinit var lpDesc: TextView
    private lateinit var rpList: RecyclerView
    private lateinit var rpEmpty: TextView
    private lateinit var rightColumn: View
    private lateinit var quickBar: View
    private lateinit var quickList: RecyclerView
    private lateinit var infoTitle: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoProgress: ProgView
    private lateinit var infoDesc: TextView
    private lateinit var infoProgrammes: RecyclerView

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var group = Repository.ALL
    private lateinit var list: List<Channel>
    private var pos = 0
    private var catchStart = 0L
    private var catchEnd = 0L
    private val isCatchup get() = catchStart > 0
    private var retries = 0
    private var numberInput = ""
    private var recentScheduled = false

    private var panelGroup = Repository.ALL
    private var panelChannel: Channel? = null
    private val leftOpen get() = leftPanel.visibility == View.VISIBLE
    private val rightOpen get() = rightPanel.visibility == View.VISIBLE
    private val quickOpen get() = quickBar.visibility == View.VISIBLE

    private val lpChannelAdapter = ChannelAdapter(
        onFocus = { showPanelEpg(it) },
        onClick = { ch, v ->
            when {
                v.isInTouchMode && panelChannel?.key != ch.key -> { showPanelEpg(ch); lpChannelAdapterSelect(ch) }
                // OK na oglądanym kanale = wejście w jego program (archiwum ⟲, przypomnienia)
                !v.isInTouchMode && ch.key == list.getOrNull(pos)?.key && !isCatchup && lpProgAdapter.itemCount > 0 -> {
                    val now = System.currentTimeMillis()
                    lpProgrammes.focusPosition(lpProgAdapter.items.indexOfFirst { it.isNow(now) }.coerceAtLeast(0), R.id.row)
                }
                else -> zapTo(ch, panelGroup)
            }
        },
        onLongClick = { ch -> ChannelActions.menu(this, ch) { refreshPanel() } },
    )
    private val lpProgAdapter: ProgrammeAdapter = ProgrammeAdapter(onClick = ::onPanelProgramme, onFocus = { lpDesc.text = describe(it) },
        onLongClick = { p -> panelChannel?.let { ch -> ProgrammeActions.menu(this, ch, p, ::panelPlay) { lpProgAdapter.notifyDataSetChanged() } } })

    private fun panelPlay(ch: Channel, p: Programme?) { if (p == null) zapTo(ch, panelGroup) else playArchive(ch, p) }
    private val recentAdapter = RecentAdapter { ch -> zapTo(ch, if (ch in list) group else Repository.ALL) }
    private val infoProgAdapter: ProgrammeAdapter = ProgrammeAdapter(onClick = ::onInfoProgramme, onFocus = ::showInfoDesc,
        onLongClick = { p -> ProgrammeActions.menu(this, list[pos], p, ::panelPlay) { infoProgAdapter.notifyDataSetChanged() } })
    private val quickAdapter = QuickAdapter()

    private val hideOverlay = Runnable { Ui.fadeOut(overlay); Ui.fadeOut(touchBar) }
    private val closePanelsRunnable = Runnable { closePanels() }
    private val addRecent = Runnable { list.getOrNull(pos)?.let { Repository.addRecent(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // film STube grający w tle kończy się przy przejściu do telewizji
        YtEngine.stop()
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        touchBar = findViewById(R.id.touchBar)
        errorTv = findViewById(R.id.error)
        numberTv = findViewById(R.id.numberInput)
        scrim = findViewById(R.id.scrim)
        leftPanel = findViewById(R.id.leftPanel)
        rightPanel = findViewById(R.id.rightPanel)
        lpChannels = findViewById(R.id.lpChannels)
        lpProgrammes = findViewById(R.id.lpProgrammes)
        lpGroup = findViewById(R.id.lpGroup)
        lpEpgTitle = findViewById(R.id.lpEpgTitle)
        lpDesc = findViewById(R.id.lpDesc)
        rpList = findViewById(R.id.rpList)
        rpEmpty = findViewById(R.id.rpEmpty)
        rightColumn = findViewById(R.id.rightColumn)
        quickBar = findViewById(R.id.quickBar)
        quickList = findViewById(R.id.quickList)
        infoTitle = findViewById(R.id.infoTitle)
        infoTime = findViewById(R.id.infoTime)
        infoProgress = findViewById(R.id.infoProgress)
        infoDesc = findViewById(R.id.infoDesc)
        infoProgrammes = findViewById(R.id.infoProgrammes)

        Ui.style(findViewById(R.id.playerRoot))
        applyInfoBarPosition()
        fitPanelsToScreen()

        lpChannels.layoutManager = LinearLayoutManager(this); lpChannels.adapter = lpChannelAdapter; lpChannels.setHasFixedSize(true)
        lpProgrammes.layoutManager = LinearLayoutManager(this); lpProgrammes.adapter = lpProgAdapter
        rpList.layoutManager = LinearLayoutManager(this); rpList.adapter = recentAdapter
        infoProgrammes.layoutManager = LinearLayoutManager(this); infoProgrammes.adapter = infoProgAdapter
        quickList.layoutManager = LinearLayoutManager(this); quickList.adapter = quickAdapter
        findViewById<View>(R.id.lpMenu).setOnClickListener { if (quickOpen) closeQuickBar() else openQuickBar() }
        findViewById<View>(R.id.tMenu).setOnClickListener { openQuickBar() }

        group = intent.getStringExtra(EXTRA_GROUP) ?: Repository.ALL
        list = Repository.channelsFor(group).ifEmpty { group = Repository.ALL; Repository.channels }
        if (list.isEmpty()) { finish(); return }
        val key = intent.getStringExtra(EXTRA_CHANNEL)
        pos = list.indexOfFirst { it.key == key }.coerceAtLeast(0)
        catchStart = intent.getLongExtra(EXTRA_START, 0)
        catchEnd = intent.getLongExtra(EXTRA_END, 0)

        scrim.setOnClickListener { closePanels() }
        findViewById<View>(R.id.rpScores).apply {
            visibility = if (Prefs.scoresEnabled) View.VISIBLE else View.GONE
            setOnClickListener { if (scoresOpen) closeScores() else openScores() }
        }
        findViewById<View>(R.id.lpPrev).setOnClickListener { cycleGroup(-1) }
        findViewById<View>(R.id.lpGroup).setOnClickListener { chooseGroup() }
        findViewById<View>(R.id.lpNext).setOnClickListener { cycleGroup(1) }
        findViewById<View>(R.id.tChannels).setOnClickListener { openLeft() }
        findViewById<View>(R.id.tRecent).setOnClickListener { openRight() }
        findViewById<View>(R.id.tPrev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.tNext).setOnClickListener { zap(1) }
        findViewById<View>(R.id.tClose).setOnClickListener { leaveScreen() }
        findViewById<View>(R.id.tFav).setOnClickListener {
            val added = Repository.toggleFav(list[pos])
            Toast.makeText(this, if (added) "Dodano do ulubionych" else "Usunięto z ulubionych", Toast.LENGTH_SHORT).show()
            updateFavButton(); showOverlay(touch = true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    miniOpen -> closeMini.run()
                    zapOpen -> { zapHide.run(); hideOverlay.run() }
                    leftOpen && quickOpen -> closeQuickBar()
                    leftOpen || rightOpen -> closePanels()
                    isCatchup && playerView.isControllerFullyVisible -> playerView.hideController()
                    overlay.visibility == View.VISIBLE && touchBar.visibility == View.VISIBLE -> hideOverlay.run()
                    else -> leaveScreen()
                }
            }
        })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            if (isCatchup && vis == View.VISIBLE) {
                fillCatchupBar(); hideOverlay.run()
                // pełny panel: zaznaczony ▶‖; sam pasek postępu: bez zaznaczenia (◄ ► dalej przewijają)
                if (barFull) playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.let { b -> b.post { b.requestFocus() } }
            }
            if (isCatchup && vis != View.VISIBLE) setBarFull(true)
        })
        setupCatchupBar()
    }

    override fun applyTheme() {} // odtwarzacz nie używa tła menu

    override fun onStart() {
        super.onStart(); applyInfoBarPosition(); initPlayer(); playCurrent()
        // pierwszy kanał na żywo: instrukcja obsługi (raz)
        if (!Prefs.tutorialShown && !isCatchup) handler.postDelayed({ if (!isFinishing) Tutorial.showOnce(this) }, 1500)
    }

    override fun onResume() {
        super.onResume()
        // ekran w pełni widoczny – obraz zawsze z powrotem na tę powierzchnię
        playerView.post { if (player != null) { Playback.reattach(playerView); if (isCatchup) playerView.showController() } }
        handler.removeCallbacks(scoresTick); handler.postDelayed(scoresTick, 2000)
        showGoals(pl.iptv.tv.data.Scores.takeGoals())          // gole wykryte, gdy odtwarzacz był w tle (menu, przewodnik)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        recentScheduled = false
        // odtwarzacz zostaje (kanał gra dalej w menu/ustawieniach); odłączamy tylko obraz i słuchacza
        Playback.removeListener(listener)
        Playback.onFatal = null; Playback.onPlayerChanged = null; Playback.onDecoderFallback = null
        Playback.detach(playerView)
        player = null
    }

    /** Na telefonach panele zajmują część ekranu zamiast stałej szerokości z TV. */
    private fun fitPanelsToScreen() {
        val wDp = resources.configuration.screenWidthDp
        val d = resources.displayMetrics.density
        fun px(dp: Int) = (dp * d + 0.5f).toInt()
        // telefon w pionie: kanały u góry, program kanału pod nimi (pełna szerokość); poziomo – obok siebie
        val portrait = wDp < 600 && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val lp = findViewById<LinearLayout>(R.id.leftPanel)
        val c1 = findViewById<View>(R.id.lpCol1); val c2 = findViewById<View>(R.id.lpCol2)
        if (portrait) {
            lp.orientation = LinearLayout.VERTICAL
            lp.layoutParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            c1.layoutParams = LinearLayout.LayoutParams(-1, 0, 0.58f)
            c2.layoutParams = LinearLayout.LayoutParams(-1, 0, 0.42f).apply { topMargin = px(8) }
            quickBar.layoutParams = LinearLayout.LayoutParams(-1, 0, 0.5f).apply { bottomMargin = px(8) }
        } else {
            lp.orientation = LinearLayout.HORIZONTAL
            lp.layoutParams.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            c1.layoutParams = LinearLayout.LayoutParams(px(minOf(340, (wDp * 0.42).toInt())), -1)
            c2.layoutParams = LinearLayout.LayoutParams(px(minOf(360, (wDp * 0.46).toInt())), -1).apply { marginStart = px(10) }
            quickBar.layoutParams = LinearLayout.LayoutParams(px(if (wDp < 700) 150 else 190), -1).apply { marginEnd = px(8) }
        }
        // ostatnio oglądane + opis: 1/4 ekranu (min. 260dp); w pionie prawie cały ekran
        rightColumn.layoutParams.width = px(if (wDp < 600) minOf(wDp - 30, 340) else maxOf(260, wDp / 4))
        lp.requestLayout()
        // przyciski dotykowe: w pionie same ikony (cały pasek mieści się na ekranie)
        val narrow = wDp < 600
        for (i in 0 until (touchBar as android.view.ViewGroup).childCount) (touchBar as android.view.ViewGroup).getChildAt(i).let { b ->
            val tv = b as? TextView ?: return@let
            if (tv.getTag(R.id.row_key) == null) tv.setTag(R.id.row_key, tv.text.toString())
            val full = tv.getTag(R.id.row_key) as String
            tv.text = if (narrow) full.substringBefore(' ') else full
            tv.setPadding(px(if (narrow) 10 else 14), px(if (narrow) 8 else 12), px(if (narrow) 10 else 14), px(if (narrow) 8 else 12))
        }
    }

    /** Położenie, styl, przezroczystość, wielkość i elementy paska informacji. */
    private fun applyInfoBarPosition() {
        val top = Ui.infoPos == 1
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (top) Gravity.TOP else Gravity.BOTTOM
        val m = if (Ui.infoFloating) Ui.dp(28) else 0
        lp.setMargins(m, if (top) m / 2 else 0, m, if (top) 0 else m / 2)
        overlay.background = Ui.infoBarBg(top)
        val edge = if (Ui.infoStyle == 0) Ui.dp(36) else Ui.dp(16)
        // telefon w pionie: węższe marginesy i mniejsze logo – nazwa kanału i program mieszczą się w pasku
        val narrow = resources.configuration.screenWidthDp < 600
        val side = if (narrow) 14 else if (Ui.infoFloating) 24 else 40
        overlay.setPadding(Ui.dp(side), if (top) Ui.dp(16) else edge, Ui.dp(side), if (top) edge else Ui.dp(16))
        val sc = Ui.infoScale * if (narrow) 0.78f else 1f
        fun size(id: Int, sp: Float) = findViewById<TextView>(id).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp * sc * Ui.fontScale)
        size(R.id.oName, 24f); size(R.id.oClock, 22f); size(R.id.oNow, 18f); size(R.id.oNext, 14f)
        findViewById<View>(R.id.oPicon).apply {
            visibility = if (Ui.infoPicon) View.VISIBLE else View.GONE
            layoutParams.width = (Ui.dp(if (narrow) 84 else 120) * sc).toInt(); layoutParams.height = (Ui.dp(if (narrow) 50 else 72) * sc).toInt()
        }
        ((findViewById<View>(R.id.oPicon).parent as View).let { (it as android.view.ViewGroup).getChildAt(1).layoutParams as LinearLayout.LayoutParams }).marginStart = Ui.dp(if (narrow) 12 else 20)
        findViewById<View>(R.id.oClock).visibility = if (Ui.infoClock) View.VISIBLE else View.GONE
        // logo w kafelku w języku motywu, przycięte do jego kształtu
        findViewById<ImageView>(R.id.oPicon).apply {
            background = Ui.infoLogoBg(); val pad = if (background != null) Ui.dp(6) else 0; setPadding(pad, pad, pad, pad)
            Ui.roundClip(this, if (background != null) Ui.dp(if (Ui.infoStyle == 9) 0 else 12).toFloat() else Ui.logoRadius)
        }
        // iOS i konsole: większa, pogrubiona nazwa kanału
        if (Ui.infoStyle in 6..9) size(R.id.oName, 24f * Ui.geo.headerScale.coerceAtMost(1.25f))
        // styl minimalny: cień pod tekstem zamiast tła
        listOf(R.id.oName, R.id.oClock, R.id.oNow, R.id.oNext).forEach { id ->
            findViewById<TextView>(id).setShadowLayer(if (Ui.infoStyle == 4) 8f else 0f, 0f, 2f, android.graphics.Color.BLACK)
        }
        (touchBar.layoutParams as FrameLayout.LayoutParams).gravity = (if (top) Gravity.BOTTOM else Gravity.TOP) or Gravity.END
        overlay.requestLayout()
    }

    // ---------------- odtwarzanie ----------------

    private val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // archiwum: serwer zrywa strumień przy końcu programu – to koniec, nie błąd
                val p = player
                if (isCatchup && p != null && archiveLengthMs - p.currentPosition < 60_000) archiveEnded()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    errorTv.visibility = View.GONE; retries = 0
                    if (!recentScheduled) { recentScheduled = true; handler.postDelayed(addRecent, RECENT_AFTER_MS) }
                }
                if (state == Player.STATE_ENDED && isCatchup) archiveEnded()
            }
        }


    // ---- koniec programu z archiwum: wybór przed końcem, wykonanie po czasie programu z EPG ----
    /** null = domyślnie (ustawienie), true = następny program, false = na żywo */
    private var endChoice: Boolean? = null
    private var endPrompt: androidx.appcompat.app.AlertDialog? = null
    private var promptShown = false

    private fun nextArchived(): Programme? {
        val ch = list[pos]; val now = System.currentTimeMillis()
        return Repository.programmes(ch).firstOrNull { it.start >= catchEnd - 60_000 && it.start > catchStart }?.takeIf { it.end <= now }
    }

    /** Czas trwania odtwarzanego materiału wg EPG (z marginesami). */
    private val archiveLengthMs get() = (catchEnd + Prefs.cuPadAfter * 60_000L) - (catchStart - Prefs.cuPadBefore * 60_000L)

    private val archiveMonitor = object : Runnable {
        override fun run() {
            val p = player
            if (isCatchup && p != null && p.currentMediaItem != null) {
                val left = archiveLengthMs - p.currentPosition
                if (left <= 30_000 && !promptShown && Prefs.cuAsk) showEndPrompt()
                if (left <= 0) { archiveEnded(); return }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun showEndPrompt() {
        promptShown = true
        val next = nextArchived()
        val items = mutableListOf("Oglądaj kanał na żywo")
        if (next != null) items += "Odtwórz następny program: ${next.title} (${Fmt.time(next.start)})"
        val def = if (next != null && Prefs.cuAutoNext) 1 else 0
        endPrompt = Ui.dialog(this).setTitle("Po zakończeniu programu")
            .setSingleChoiceItems(items.toTypedArray(), def) { d, w -> endChoice = w == 1; d.dismiss()
                toast(if (w == 1) "Po zakończeniu: następny program" else "Po zakończeniu: na żywo") }
            .setNegativeButton("Zamknij", null).create().also { it.show() }
        handler.postDelayed({ endPrompt?.takeIf { it.isShowing }?.dismiss() }, 25_000)      // brak wyboru = domyślnie
    }

    /** Koniec materiału z archiwum: następny program (jeśli też w archiwum) albo kanał na żywo. */
    private fun archiveEnded() {
        endPrompt?.takeIf { it.isShowing }?.dismiss()
        val ch = list[pos]
        val next = nextArchived()
        val wantNext = endChoice ?: Prefs.cuAutoNext
        endChoice = null; promptShown = false
        if (wantNext && next != null) playArchive(ch, next) else { catchStart = 0; catchEnd = 0; playCurrent() }
    }

    // przewijanie: kolejne naciśnięcia sumują się i wykonują jednym skokiem (bez zacinania przy serii ⏩)
    private var seekAcc = 0L
    private val applySeek = Runnable {
        val p = player
        if (p != null && seekAcc != 0L) p.seekTo((p.currentPosition + seekAcc).coerceAtLeast(0))
        seekAcc = 0; seekHint()
    }

    private val HINT = "◄ ► przewijanie (przytrzymaj – szybciej)  •  2× ► pomiń reklamę  •  ▲ oś czasu  •  OK menu"

    private fun seekHint() {
        val t = playerView.findViewById<TextView>(R.id.ctlHint) ?: return
        if (seekAcc == 0L) { t.text = HINT; return }
        val sec = kotlin.math.abs(seekAcc) / 1000
        // przesunięcie i godzina, w którą trafi przewijanie (np. „⏩ +1:30 → 20:47”)
        val target = catchStart - Prefs.cuPadBefore * 60_000L + ((player?.currentPosition ?: 0L) + seekAcc).coerceAtLeast(0)
        t.text = (if (seekAcc > 0) "⏩ +" else "⏪ −") + "%d:%02d".format(sec / 60, sec % 60) + "  →  " + Fmt.time(target)
    }

    /** Przytrzymany ◄ ►: coraz większy krok (1× → 2× → 4× → 8×) – szybkie przewijanie długiej audycji pilotem. */
    private fun seekAccel(repeat: Int) = when { repeat < 3 -> 1; repeat < 10 -> 2; repeat < 20 -> 4; else -> 8 }

    private fun seekBy(ms: Long) {
        if (player == null) return
        seekAcc += ms
        handler.removeCallbacks(applySeek); handler.postDelayed(applySeek, 500)
        if (!playerView.isControllerFullyVisible) setBarFull(false)       // sam pasek postępu
        playerView.showController(); seekHint()
    }

    /** Pełny panel (informacje + przyciski) albo sam pasek postępu przy przewijaniu. */
    private var barFull = true
    private fun setBarFull(full: Boolean) {
        barFull = full
        val vis = if (full) View.VISIBLE else View.GONE
        listOf(R.id.ctlHead, R.id.ctlRow1, R.id.ctlRow2).forEach { playerView.findViewById<View>(it)?.visibility = vis }
        if (isCatchup) { handler.removeCallbacks(catchupClock); handler.post(catchupClock) }
    }

    // ► w archiwum: pojedyncze = przewinięcie, podwójne (do 400 ms) = pominięcie reklamy
    private var lastRight = 0L
    private val singleRight = Runnable { seekBy(Prefs.cuFwd * 1000L) }
    private fun rightPressed() {
        val now = android.os.SystemClock.uptimeMillis()
        handler.removeCallbacks(singleRight)
        if (now - lastRight < 400) { lastRight = 0; skipAd() } else { lastRight = now; handler.postDelayed(singleRight, 400) }
    }

    /** Dolny pasek archiwum: informacje o programie, przyciski. */
    private fun setupCatchupBar() {
        playerView.controllerShowTimeoutMs = maxOf(Ui.archiveInfoMs.toInt(), 2000)
        playerView.findViewById<View>(R.id.ctlBack)?.setOnClickListener { seekBy(-Prefs.cuBack * 1000L) }
        playerView.findViewById<View>(R.id.ctlFwd)?.setOnClickListener { seekBy(Prefs.cuFwd * 1000L) }
        playerView.findViewById<View>(R.id.ctlSkip)?.setOnClickListener { skipAd() }
        playerView.findViewById<View>(R.id.ctlLive)?.setOnClickListener { catchStart = 0; catchEnd = 0; playerView.hideController(); playCurrent() }
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)?.apply {
            setKeyTimeIncrement(Prefs.cuFwd * 1000L); setPlayedColor(Ui.accent); setScrubberColor(Ui.accent)
            // oś czasu osiągalna pilotem: ▲ z przycisków – oś (◄ ► przewijają), ▼ z osi – ▶‖
            isFocusable = true; nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
            setScrubberColor(Ui.accent); setAdMarkerColor(Ui.withAlpha(Ui.accent, 0x80))
        }
        listOf(R.id.ctlBack, R.id.ctlFwd, androidx.media3.ui.R.id.exo_play_pause).forEach { playerView.findViewById<View>(it)?.nextFocusUpId = androidx.media3.ui.R.id.exo_progress }
        listOf(R.id.ctlBack, R.id.ctlFwd, androidx.media3.ui.R.id.exo_play_pause, R.id.ctlSkip, R.id.ctlLive).forEach { id ->
            playerView.findViewById<View>(id)?.let { v ->
                // przyciski w kształcie motywu: kapsuła (iOS i pozostałe), ostry z limonkową ramką (Xbox)
                val r = Ui.dp(if (Ui.surface == Ui.S_XBOX) 2 else 22).toFloat()
                v.background = android.graphics.drawable.StateListDrawable().apply {
                    fun g(c: Int, sw: Int = 0, sc: Int = 0) = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = r; if (sw > 0) setStroke(Ui.dp(sw), sc) }
                    val focused = when (Ui.surface) {
                        Ui.S_XBOX -> g(Ui.withAlpha(Ui.focus, 0x30), 3, Ui.focus)
                        else -> g(Ui.focus)
                    }
                    addState(intArrayOf(android.R.attr.state_focused), focused); addState(intArrayOf(android.R.attr.state_pressed), focused)
                    addState(intArrayOf(), g(Ui.withAlpha(Ui.text, 0x26)))
                    setEnterFadeDuration(if (Ui.anim == 0) 0 else 80)
                }
                (v as? android.widget.ImageView)?.imageTintList = Ui.textColors(Ui.text)
                (v as? TextView)?.setTextColor(Ui.textColors(Ui.text))
                // zaznaczony przycisk wyraźnie powiększony (czytelny fokus z daleka)
                v.setOnFocusChangeListener { _, f ->
                    (v as? android.widget.ImageView)?.refreshDrawableState()
                    val s = if (f) 1.12f else 1f
                    if (Ui.anim == 0) { v.scaleX = s; v.scaleY = s } else v.animate().scaleX(s).scaleY(s).setDuration(Ui.geo.zoomMs.coerceAtLeast(90)).start()
                }
            }
        }
    }

    private fun fillCatchupBar() {
        val ch = list[pos]
        val p = Repository.programmes(ch).firstOrNull { it.start == catchStart }
        styleCatchupBar()
        playerView.findViewById<ImageView>(R.id.ctlLogo)?.let { Ui.roundClip(it, Ui.logoRadius); it.loadPicon(ch) }
        playerView.findViewById<TextView>(R.id.ctlTitle)?.text = "${ch.name}  •  ${p?.fullTitle ?: "Archiwum"}"
        handler.removeCallbacks(catchupClock); handler.post(catchupClock)
        playerView.findViewById<TextView>(R.id.ctlInfo)?.text = "⟲ ${Fmt.day(catchStart)}  ${Fmt.time(catchStart)}–${Fmt.time(catchEnd)}" +
            (p?.desc?.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: "")
        playerView.findViewById<TextView>(R.id.ctlSkip)?.text = "Pomiń reklamę +${if (Prefs.adSkip % 60 == 0) "${Prefs.adSkip / 60} min" else "${Prefs.adSkip} s"}"
    }

    /** Pasek archiwum w stylu paska informacji i kolorach motywu. */
    private var catchupStyled = -1
    private fun styleCatchupBar() {
        if (catchupStyled == Ui.version) return
        catchupStyled = Ui.version
        playerView.findViewById<View>(R.id.ctlBar)?.background = Ui.infoBarBg(false)
            ?: android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF0101318.toInt(), 0x00101318))
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)?.apply {
            setPlayedColor(Ui.accent); setScrubberColor(Ui.accent); setBufferedColor(0x66FFFFFF); setUnplayedColor(0x33FFFFFF)
        }
        playerView.findViewById<TextView>(R.id.ctlBadge)?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(10).toFloat(); setColor(Ui.archive)
        }
        playerView.findViewById<TextView>(R.id.ctlClock)?.setTextColor(Ui.accent)
        // style pływające (karta, kapsuła, mika): pasek archiwum odsunięty od krawędzi i przycięty do swojej ramki
        playerView.findViewById<View>(R.id.ctlBar)?.let { bar ->
            val m = if (Ui.infoFloating) Ui.dp(24) else 0
            (bar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { it.setMargins(m, 0, m, m / 2); bar.layoutParams = it }
            bar.clipToOutline = Ui.infoFloating
        }
    }

    /** Godzina oglądanego momentu audycji (np. „20:47”) – czytelniejsza niż sam czas od początku. */
    private val catchupClock: Runnable = object : Runnable {
        override fun run() {
            if (!isCatchup) return
            val pos = player?.currentPosition ?: 0
            playerView.findViewById<TextView>(R.id.ctlClock)?.text = "⏱ " + Fmt.time(catchStart - Prefs.cuPadBefore * 60_000L + pos)
            if (playerView.isControllerFullyVisible) handler.postDelayed(this, 1000)
        }
    }

    private fun skipAd() { if (isCatchup) { seekBy(Prefs.adSkip * 1000L); toast("⏭ +${Prefs.adSkip} s") } else toast("Pomijanie działa w odtwarzaniu z archiwum") }

    private fun initPlayer() {
        val p = Playback.get(this)
        Playback.addListener(listener)           // słuchacz przechodzi też na odtwarzacz odbudowany po zawieszeniu
        player = p
        Playback.onPlayerChanged = { np -> player = np; Playback.attach(playerView) }
        Playback.onDecoderFallback = { toast("Obraz zamarzał – przełączono na dekoder programowy (do zmiany silnika lub zamknięcia aplikacji)") }
        playerView.setKeepContentOnPlayerReset(true)      // ostatnia klatka zamiast czarnego ekranu przy zmianie kanału
        Playback.attach(playerView)
        if (p.playbackState == Player.STATE_READY) { errorTv.visibility = View.GONE }
        Playback.onFatal = { e ->
            if (!(isCatchup && archiveLengthMs - (player?.currentPosition ?: 0) < 60_000)) {
                errorTv.text = "Nie można odtworzyć: ${e.errorCodeName}\n(▲▼ – zmiana kanału)"; errorTv.visibility = View.VISIBLE
            }
        }
    }

    /** Pozycja ostatnio odtworzonego kanału – do cofnięcia, gdy PIN nie zostanie podany. */
    private var lastOkPos = -1

    private fun playCurrent(keepRetries: Boolean = false) {
        val p = player ?: return
        if (pl.iptv.tv.data.Parental.needsPin(list[pos])) {
            p.pause()
            ParentalUi.require(this, "🔒 ${list[pos].name} – podaj PIN", onCancel = {
                if (lastOkPos >= 0 && lastOkPos != pos) { pos = lastOkPos; catchStart = 0; catchEnd = 0; playCurrent() } else finish()
            }) { playCurrent(keepRetries) }
            return
        }
        if (lastOkPos >= 0 && lastOkPos != pos) prevKey = list.getOrNull(lastOkPos)?.key
        lastOkPos = pos
        if (!keepRetries) {
            retries = 0
            handler.removeCallbacks(addRecent); recentScheduled = false
        }
        val ch = list[pos]
        Prefs.lastChannel = ch.key
        errorTv.visibility = View.GONE
        // marginesy archiwum (EPG bywa niedokładne)
        val url = if (isCatchup) CatchupUrlBuilder.build(ch, catchStart - Prefs.cuPadBefore * 60_000L, catchEnd + Prefs.cuPadAfter * 60_000L) else ch.url
        if (url == null) {
            Toast.makeText(this, "Kanał nie obsługuje catch-up", Toast.LENGTH_LONG).show()
            catchStart = 0; catchEnd = 0
            return playCurrent()
        }
        // ten sam strumień (np. powrót z ustawień lub z podglądu w menu) gra dalej bez restartu
        Playback.play(this, ch, url, force = keepRetries)
        // logo sąsiednich kanałów do pamięci – przy kolejnym przełączeniu pokaże się od razu
        listOf(-1, 1, 2).forEach { d -> list.getOrNull((pos + d + list.size) % list.size)?.let { prefetchPicon(this, it) } }
        applyAspect()
        handler.removeCallbacks(archiveMonitor)
        if (isCatchup) { if (!keepRetries) { promptShown = false; endChoice = null }; handler.postDelayed(archiveMonitor, 1000) }
        playerView.useController = isCatchup
        showOverlay()
    }

    /** ▲▼: nazwa kanału od razu, odtwarzanie po krótkiej chwili – szybkie przełączanie pomija kanały pośrednie
     *  (bez uruchamiania kilku strumieni naraz, które na słabszych urządzeniach zacinały obraz). */
    private val zapStart = Runnable { playCurrent() }
    private fun zap(delta: Int) {
        catchStart = 0; catchEnd = 0
        pos = (pos + delta + list.size) % list.size
        handler.removeCallbacks(zapStart)
        if (pl.iptv.tv.data.Parental.needsPin(list[pos])) { playCurrent(); return }
        showOverlay()
        handler.postDelayed(zapStart, if (Prefs.lowPerf) Prefs.lowZapDelay.coerceIn(100, 2000).toLong() else 250L)
    }

    private fun zapTo(ch: Channel, inGroup: String) {
        val l = Repository.channelsFor(inGroup)
        if (ch in l) { group = inGroup; list = l } else if (ch !in list) { group = Repository.ALL; list = Repository.channels }
        pos = list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        closePanels()
        catchStart = 0; catchEnd = 0
        playCurrent()
    }

    private fun playArchive(ch: Channel, p: Programme) {
        if (ch !in list) { group = Repository.ALL; list = Repository.channels }
        pos = list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        closePanels()
        catchStart = p.start; catchEnd = p.end
        playCurrent()
    }

    // ---------------- pasek informacji ----------------

    private fun updateFavButton() {
        findViewById<TextView>(R.id.tFav).text = if (Repository.isFav(list[pos])) "★" else "☆"
    }

    private fun showOverlay(touch: Boolean = false) {
        if (isCatchup) { setBarFull(true); fillCatchupBar(); playerView.showController(); if (touch) Ui.fadeIn(touchBar); return }
        val ch = list[pos]
        val now = System.currentTimeMillis()
        findViewById<ImageView>(R.id.oPicon).loadPicon(ch)
        findViewById<TextView>(R.id.oClock).text = Fmt.time(now)
        val num = if (Prefs.showNumbers) "${ch.number}. " else ""
        findViewById<TextView>(R.id.oName).text = num + ch.name +
            (if (Repository.isFav(ch)) "  ★" else "") + (if (isCatchup) "  ⟲ ARCHIWUM" else "")
        val nowTv = findViewById<TextView>(R.id.oNow)
        val nextTv = findViewById<TextView>(R.id.oNext)
        val prog = findViewById<ProgView>(R.id.oProgress)
        if (isCatchup) {
            val p = Repository.programmes(ch).firstOrNull { it.start == catchStart }
            nowTv.text = "${Fmt.day(catchStart)}  ${Fmt.time(catchStart)}–${Fmt.time(catchEnd)}  ${p?.title ?: ""}"
            nextTv.text = if (Ui.infoNext) p?.desc ?: "" else ""
            prog.visibility = View.GONE
        } else {
            val cur = Repository.current(ch, now)
            val next = Repository.next(ch, now)
            nowTv.text = cur?.let { "${Fmt.time(it.start)}–${Fmt.time(it.end)}  ${it.title}" +
                if (Ui.progressRemaining) "  (jeszcze ${(it.end - now) / 60_000} min)" else "" } ?: "Brak informacji o programie"
            nextTv.text = if (!Ui.infoNext) "" else next?.let { "Następnie: ${Fmt.time(it.start)}  ${it.title}" } ?: ""
            prog.show(cur, now)
        }
        updateFavButton()
        nextTv.visibility = if (nextTv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        Ui.fadeIn(overlay)
        if (touch) Ui.fadeIn(touchBar)
        handler.removeCallbacks(hideOverlay)
        val timeout = if (isCatchup) Ui.archiveInfoMs else Ui.infoTimeoutMs        // archiwum: krótko (domyślnie 2 s)
        handler.postDelayed(hideOverlay, if (touch) maxOf(timeout, 6000) else timeout)
    }

    private fun describe(p: Programme): String {
        val next = panelChannel?.let { Repository.nextEpisodeIfReady(it, p) }
        return "${Fmt.time(p.start)}–${Fmt.time(p.end)}  ${p.fullTitle}" +
            (if (next != null) "\n⏭ Następny: ${ProgrammeActions.whenText(next.channel, next.programme)}" else "") +
            (if (p.desc.isNotBlank()) "\n${p.desc}" else "")
    }

    // ---------------- panele ----------------

    private fun lpChannelAdapterSelect(ch: Channel) { lpChannelAdapter.selectedKey = ch.key }

    private fun openLeft() {
        if (!Prefs.channelPanel) return
        groupBrowsing = false
        closeRight(animate = false)
        hideOverlay.run()
        panelGroup = group
        refreshPanel()
        scrim.visibility = View.VISIBLE
        Ui.slideIn(leftPanel, fromLeft = true)
        val i = lpChannelAdapter.indexOfKey(list[pos].key).coerceAtLeast(0)
        (lpChannels.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(120))
        lpChannels.focusPosition(i)
        val cur = list[pos]
        lpChannels.post { if (panelChannel?.key != cur.key || lpProgAdapter.itemCount == 0) showPanelEpg(cur) }
        resetPanelTimer()
    }

    private var panelItemsFor: Pair<String, List<Channel>>? = null

    /** Lista panelu przebudowywana tylko po zmianie grupy/listy – otwieranie ◄ bez przycięć. */
    private fun refreshPanel() {
        val groups = Repository.groups()
        // ukryta kategoria znika z panelu od razu
        if (panelGroup !in groups) panelGroup = if (Repository.ALL in groups) Repository.ALL else groups.first()
        lpGroup.text = "$panelGroup  ${groups.indexOf(panelGroup) + 1}/${groups.size}"
        val items = Repository.channelsFor(panelGroup)
        if (panelItemsFor?.first != panelGroup || panelItemsFor?.second != items) { lpChannelAdapter.items = items; panelItemsFor = panelGroup to items }
        else lpChannelAdapter.notifyItemRangeChanged(0, lpChannelAdapter.itemCount, ChannelAdapter.EPG)
        lpChannelAdapter.selectedKey = list.getOrNull(pos)?.key
    }

    private fun cycleGroup(d: Int, keepHeader: Boolean = false) {
        val groups = Repository.groups()
        val i = groups.indexOf(panelGroup).coerceAtLeast(0)
        setPanelGroup(groups[(i + d + groups.size) % groups.size], d, keepHeader)
    }

    /** Zmiana kategorii w panelu ◄: lista kanałów wsuwa się z kierunku zmiany. */
    private fun setPanelGroup(g: String, dir: Int = 1, keepHeader: Boolean = false) {
        panelGroup = g
        refreshPanel()
        lpChannels.scrollToPosition(0)
        if (Ui.anim != 0) {
            lpChannels.translationX = Ui.dp(if (dir >= 0) 40 else -40).toFloat(); lpChannels.alpha = 0f
            lpChannels.animate().translationX(0f).alpha(1f).setDuration(Ui.animDuration).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        if (keepHeader) lpGroup.requestFocus() else lpChannels.focusPosition(0)
        lpChannelAdapter.items.firstOrNull()?.let { showPanelEpg(it) }
        resetPanelTimer()
    }

    /** OK na nagłówku: lista wszystkich kategorii z liczbą kanałów. */
    private fun chooseGroup() {
        val groups = Repository.groups()
        Dialogs.choice(this, "Kategoria", groups.map { "$it  (${Repository.channelsFor(it).size})" }, groups.indexOf(panelGroup)) { setPanelGroup(groups[it]) }
    }

    /** Program od początku archiwum (lub 3 godz. wstecz) do [Prefs.panelHours] naprzód. */
    private fun channelSchedule(ch: Channel, now: Long): Pair<List<Programme>, Long> {
        val catchupFrom = if (ch.hasCatchup) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
        val from = if (ch.hasCatchup) catchupFrom else now - 3 * 3_600_000L
        val until = now + Prefs.panelHours * 3_600_000L
        return Repository.programmesIn(ch, from, until) to catchupFrom
    }

    private fun showPanelEpg(ch: Channel) {
        panelChannel = ch
        val now = System.currentTimeMillis()
        val (progs, catchupFrom) = channelSchedule(ch, now)
        lpEpgTitle.text = ch.name
        lpProgAdapter.submit(progs, catchupFrom, ch)
        val cur = progs.indexOfFirst { it.isNow(now) }
        lpDesc.text = if (progs.isEmpty()) "Brak programu TV dla tego kanału" else describe(progs.getOrElse(cur) { progs.first() })
        (lpProgrammes.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(cur.coerceAtLeast(0), 0)
    }

    /** Wspólna obsługa kliknięcia programu w panelach. */
    private fun programmeClicked(ch: Channel, p: Programme, refresh: () -> Unit) {
        val now = System.currentTimeMillis()
        when {
            p.start > now -> ProgrammeActions.menu(this, ch, p, ::panelPlay, onChanged = refresh)
            p.end <= now && Repository.isInArchive(ch, p, now) -> playArchive(ch, p)
            p.end <= now -> Toast.makeText(this, "Ten program nie jest dostępny w archiwum", Toast.LENGTH_SHORT).show()
            Repository.isInArchive(ch, p, now) -> Ui.dialog(this).setTitle(p.title)
                .setItems(arrayOf("Oglądaj na żywo", "Oglądaj od początku")) { _, w ->
                    if (w == 0) zapTo(ch, if (ch in list) group else Repository.ALL) else playArchive(ch, p)
                }.show()
            else -> zapTo(ch, if (ch in list) group else Repository.ALL)
        }
    }

    private fun onPanelProgramme(p: Programme) {
        val ch = panelChannel ?: return
        programmeClicked(ch, p) { lpProgAdapter.notifyDataSetChanged() }
    }

    private fun openRight() {
        if (!Prefs.recentPanel) return
        closeLeft(animate = false)
        hideOverlay.run()
        val items = Repository.recent()
        recentAdapter.items = items
        rpEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        showInfo(list[pos])
        scrim.visibility = View.VISIBLE
        Ui.slideIn(rightPanel, fromLeft = false)
        if (items.isNotEmpty()) rpList.focusPosition(0) else infoProgrammes.focusPosition(0, R.id.row, fallback = rightPanel)
        resetPanelTimer()
    }

    // ----- panel „Teraz w TV / Kolejka” (wspólna kolejka z STube) -----

    private var sidePanel: SidePanel? = null

    /**
     * Panel boczny: „Teraz w TV” – kanały bieżącej kategorii od oglądanego, z aktualnym programem i postępem (OK = przełącz,
     * długie OK = do kolejki); „Kolejka” – filmy STube i kanały (OK = odtwórz, długie OK = kolejność / usuń).
     */
    fun openQueuePanel(tab: Int = 0) {
        hideOverlay.run()
        val scope = lifecycleScope
        fun channelMenu(ch: Channel): () -> Unit = {
            Dialogs.menu(this, ch.name, listOf(
                "▶ Przełącz teraz" to { sidePanel?.close(); zapTo(ch, if (ch in list) group else Repository.ALL) },
                "＋ Dodaj do kolejki" to { scope.launch { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(ch)); toast("Dodano do kolejki: ${ch.name}"); sidePanel?.reload() }; Unit },
                "⏭ Odtwórz jako następny" to { scope.launch { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(ch), next = true); toast("Następny w kolejce: ${ch.name}") }; Unit },
            ))
        }
        val p = sidePanel ?: SidePanel(this, listOf(
            SidePanel.Tab("📺 Teraz w TV", "Brak kanałów w tej kategorii") {
                // kanały od oglądanego (najbliższe w kolejności listy), bez przeliczania całej listy
                val from = pos.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                (list.drop(from) + list.take(from)).take(60).map { ch -> QueueUi.channelEntry(ch, channelMenu(ch)) { sidePanel?.close(); zapTo(it, group) } }
            },
            SidePanel.Tab("☰ Kolejka", "Kolejka jest pusta – dodaj kanał (długie OK w „Teraz w TV”) lub film w STube") {
                val cur = list.getOrNull(pos)
                listOfNotNull(cur?.let { ch ->
                    SidePanel.Entry("add", "＋ Dodaj bieżący kanał do kolejki", ch.name, icon = "＋") {
                        scope.launch { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(ch)); toast("Dodano do kolejki: ${ch.name}"); sidePanel?.reload() }
                    }
                }) + QueueUi.entries(this, { sidePanel?.reload() }) { q ->
                    scope.launch {
                        pl.iptv.tv.data.StubeQueue.remove(q.id)
                        val ch = if (q.isVideo) null else Repository.byKey(q.ref)
                        sidePanel?.close()
                        when {
                            ch != null -> zapTo(ch, if (ch in list) group else Repository.ALL)
                            q.isVideo -> { QueueUi.play(this@PlayerActivity, q); finish() }
                            else -> toast("Kanału nie ma na bieżącej liście")
                        }
                    }
                }
            },
        )).also { sidePanel = it }
        p.open(tab)
    }

    // ----- opis oglądanego programu + program kanału (pod ostatnio oglądanymi) -----

    private fun showInfo(ch: Channel) {
        val now = System.currentTimeMillis()
        val (progs, catchupFrom) = channelSchedule(ch, now)
        infoProgAdapter.submit(progs, catchupFrom, ch)
        val watched = if (isCatchup) progs.firstOrNull { it.start == catchStart } else progs.firstOrNull { it.isNow(now) }
        showInfoDesc(watched)
        val i = progs.indexOf(watched).coerceAtLeast(0)
        (infoProgrammes.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
    }

    private fun showInfoDesc(p: Programme?) {
        val ch = list[pos]
        val now = System.currentTimeMillis()
        if (p == null) {
            infoTitle.text = ch.name; infoTime.text = "Brak informacji o programie"
            infoDesc.text = ""; infoProgress.visibility = View.GONE; return
        }
        infoTitle.text = p.fullTitle
        infoTime.text = "${Fmt.shortDay(p.start)} ${Fmt.time(p.start)}–${Fmt.time(p.end)} • ${ch.name}" +
            (if (p.category.isNotBlank()) " • ${p.category}" else "")
        infoProgress.show(if (p.isNow(now)) p else null, now)
        val next = Repository.nextEpisodeIfReady(ch, p)
        infoDesc.text = p.desc.ifBlank { "Brak opisu." } +
            (if (next != null) "\n⏭ Następny odcinek: ${ProgrammeActions.whenText(next.channel, next.programme)}" else "")
    }

    private fun onInfoProgramme(p: Programme) = programmeClicked(list[pos], p) { infoProgAdapter.notifyDataSetChanged() }

    // ----- pasek menu (druga strzałka ►) -----

    private class QuickItem(val section: String?, val label: String, val icon: android.graphics.drawable.Drawable? = null,
                            val onLong: (() -> Unit)? = null, val action: () -> Unit)

    private fun quickItems(): List<QuickItem> {
        val ch = list[pos]
        val l = mutableListOf<QuickItem>()
        fun add(label: String, action: () -> Unit) { l += QuickItem(null, label, action = action) }
        for (id in pl.iptv.tv.data.QuickMenu.visible()) when (id) {
            "settings" -> add("⚙ Ustawienia") { startActivity(Intent(this, SettingsActivity::class.java)) }
            "sys_settings" -> add("🛠 Ustawienia urządzenia") { SystemTools.openSystemSettings(this) }
            "editor" -> add("✎ Edytor listy") { startActivity(Intent(this, EditorActivity::class.java)) }
            "epg_edit" -> add("📺 Edytor EPG") { ChannelActions.epg(this, ch) { showInfo(list[pos]) } }
            "channel" -> add("☰ Opcje kanału") { ChannelActions.menu(this, ch) { buildQuick() } }
            "fav" -> add(if (Repository.isFav(ch)) "☆ Usuń z ulubionych" else "★ Do ulubionych") { Repository.toggleFav(ch); buildQuick() }
            "guide" -> add("📅 Program TV") { startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, ch.key)) }
            "search" -> if (Prefs.epgSearchEnabled) add("🔎 Szukaj w programie") { startActivity(Intent(this, EpgSearchActivity::class.java)) }
            "reminders" -> if (Prefs.remindersEnabled) add("🔔 Przypomnienia") {
                startActivity(Intent(this, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_TAB, EpgSearchActivity.TAB_REMINDERS))
            }
            "audio" -> add("🔊 Ścieżka audio") { trackMenu(androidx.media3.common.C.TRACK_TYPE_AUDIO) }
            "subs" -> add("💬 Napisy") { trackMenu(androidx.media3.common.C.TRACK_TYPE_TEXT) }
            "aspect" -> add("📐 ${ASPECTS[Prefs.aspectMode.coerceIn(ASPECTS.indices)]}") {
                Dialogs.choice(this, "Proporcje obrazu", ASPECTS, Prefs.aspectMode) { Prefs.aspectMode = it; applyAspect(); buildQuick() }
            }
            "record" -> if (Prefs.recEnabled) {
                val rec = pl.iptv.tv.data.Recordings.activeFor(ch.key)
                if (rec != null) add("⏹ Zatrzymaj nagrywanie") { pl.iptv.tv.rec.RecordingService.stop(this, rec.id); toast("Zatrzymano nagrywanie"); buildQuick() }
                else add("⏺ Nagrywaj") {
                    val cur = Repository.current(ch)
                    pl.iptv.tv.rec.RecordingService.recordProgramme(this, ch, cur)
                    toast("⏺ Nagrywanie: ${ch.name}" + (cur?.let { " do ${Fmt.time(it.end)}" } ?: "")); buildQuick()
                }
            }
            "skip_ad" -> if (isCatchup) add("⏭ Pomiń reklamę (+${Prefs.adSkip} s)") { closePanels(); skipAd() }
            "queue" -> add("📋 Kolejka i propozycje") { closePanels(); openQueuePanel() }
            "refresh" -> add("🔄 Odśwież listę i EPG") { refreshSources() }
            "apps_all" -> add("▦ Wszystkie aplikacje") { SystemTools.showApps(this) }
            "apps" -> {
                var sec: String? = "APLIKACJE"
                SystemTools.quickApps(this).forEach { app ->
                    l += QuickItem(sec, app.label, app.icon, onLong = { SystemTools.removeQuickApp(this, app) { buildQuick() } }) { SystemTools.launch(this, app) }
                    sec = null
                }
                l += QuickItem(sec, "➕ Dodaj aplikację…") { SystemTools.addQuickApp(this) { buildQuick() } }
            }
        }
        // zawsze na końcu: widoczność i kolejność pozycji tego menu
        l += QuickItem("EDYCJA", "✎ Edytuj to menu…") { editQuickMenu() }
        return l
    }

    /** Okno edycji menu funkcji ◄◄ (w stylu motywu); panel nie znika w trakcie edycji. */
    private fun editQuickMenu() {
        handler.removeCallbacks(closePanelsRunnable)
        val Q = pl.iptv.tv.data.QuickMenu
        Dialogs.orderEditor(this, "Menu boczne – widoczność i kolejność",
            ids = { Q.order() }, label = { Q.ITEMS[it] ?: it }, hidden = { Q.isHidden(it) },
            toggle = { Q.toggle(it) }, move = { id, d -> Q.move(id, d) }, reset = { Q.reset() },
            onDone = { lastQuickPos = Int.MAX_VALUE; buildQuick(); resetPanelTimer() })
    }

    /** Przebudowa menu z zachowaniem pozycji zaznaczenia (np. po dodaniu skrótu aplikacji). */
    private fun buildQuick() {
        val f = window.decorView.findFocus()
        val pos = f?.let { quickList.findContainingViewHolder(it)?.bindingAdapterPosition } ?: lastQuickPos
        quickAdapter.items = quickItems()
        if (quickOpen && pos >= 0) { lastQuickPos = pos.coerceAtMost(quickAdapter.itemCount - 1); quickList.focusPosition(lastQuickPos, R.id.row) }
    }

    private var lastQuickPos = 0

    /** Po zamknięciu okna (dodanie skrótu, opcje kanału…) zaznaczenie wraca do otwartego panelu. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val f = window.decorView.findFocus()
        val lost = f == null || !f.isAttachedToWindow || f === playerView
        if (!lost) return
        when {
            quickOpen -> quickList.focusPosition(lastQuickPos.coerceIn(0, maxOf(0, quickAdapter.itemCount - 1)), R.id.row)
            leftOpen -> lpChannels.focusPosition(lpChannelAdapter.indexOfKey(list[pos].key).coerceAtLeast(0))
            rightOpen -> if (recentAdapter.itemCount > 0) rpList.focusPosition(0) else infoProgrammes.focusPosition(0, R.id.row)
        }
    }

    private fun openQuickBar() {
        if (!leftOpen) openLeft()
        if (!leftOpen) return
        buildQuick()
        // menu boczne wysuwa się od lewej, a kolumny kanałów płynnie przesuwają się w prawo
        if ((leftPanel as android.view.ViewGroup).layoutTransition == null && Ui.anim != 0) (leftPanel as android.view.ViewGroup).layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING); setDuration(Ui.animDuration)
            disableTransitionType(android.animation.LayoutTransition.APPEARING); disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
        }
        Ui.slideIn(quickBar, fromLeft = true)
        quickList.focusPosition(0, R.id.row)
        resetPanelTimer()
    }

    private fun closeQuickBar() {
        Ui.slideOut(quickBar, toLeft = true)
        lpChannels.focusPosition(lpChannelAdapter.indexOfKey(list[pos].key).coerceAtLeast(0))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val ASPECTS = listOf("Dopasuj", "Rozciągnij", "Powiększ (przytnij)", "16:9", "4:3")

    /** Tryb obrazu; 16:9 i 4:3 wymuszają proporcje ramki (po każdej zmianie rozmiaru wideo). */
    private fun applyAspect() {
        val m = Prefs.aspectMode
        playerView.resizeMode = when (m) {
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        if (m >= 3) playerView.post {
            playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                ?.setAspectRatio(if (m == 3) 16f / 9f else 4f / 3f)
        }
    }

    private fun trackMenu(type: Int) {
        val text = type == androidx.media3.common.C.TRACK_TYPE_TEXT
        val items = trackItems(type)
        if (items.size <= (if (text) 1 else 0)) toast(if (text) "Brak napisów w tym strumieniu" else "Brak dodatkowych ścieżek audio")
        else Dialogs.menu(this, if (text) "Napisy" else "Ścieżka audio", items)
    }

    private fun trackItems(type: Int): List<Pair<String, () -> Unit>> {
        val p = player ?: return emptyList()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val text = type == androidx.media3.common.C.TRACK_TYPE_TEXT
        if (text) items += "✕ Wyłączone" + (if (p.trackSelectionParameters.disabledTrackTypes.contains(type)) " ✔" else "") to {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
        }
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage }
                val label = listOfNotNull(f.label, lang, f.codecs?.substringBefore('.'),
                    if (!text && f.channelCount > 0) "${f.channelCount} kan." else null).distinct().joinToString(" • ").ifEmpty { "Ścieżka ${items.size + 1}" }
                items += label + (if (g.isTrackSelected(i)) " ✔" else "") to {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        return if (text && items.size == 1) emptyList() else items
    }

    // ----- obraz w obrazie -----
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Prefs.pipAuto && android.os.Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) }
        }
    }

    /** Obrót telefonu (odtwarzacz się nie odtwarza na nowo) – panele dopasowane do nowej szerokości. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        fitPanelsToScreen(); applyInfoBarPosition()
        if (::playerView.isInitialized && scoresColumn.visibility == View.VISIBLE) scoresColumn.layoutParams.width = rightColumn.layoutParams.width
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, cfg: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(pip, cfg)
        if (pip) { closePanels(); hideOverlay.run(); zapHide.run(); playerView.useController = false; pipActivity = java.lang.ref.WeakReference(this) }
        else {
            pipActivity = null
            playerView.useController = isCatchup
            // okienko zamknięte (✕) – koniec odtwarzacza; powiększone – obraz od nowa na pełny ekran
            if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) finish()
            else playerView.post { if (player != null) Playback.reattach(playerView) }
        }
    }


    private fun refreshSources() {
        Toast.makeText(this, "Odświeżanie listy i EPG…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val src = Prefs.playlistSource
            val pl = if (src.isNotBlank()) runCatching { Repository.loadPlaylist(src) } else null
            val epg = if (Prefs.effectiveEpgUrl.isNotBlank()) runCatching { Repository.loadEpg() } else null
            val msg = buildString {
                append(if (pl?.isSuccess == true) "Lista: ${pl.getOrNull()} kanałów" else "Lista: bez zmian")
                append(" • ")
                append(if (epg?.isSuccess == true) "EPG: ${epg.getOrNull()} programów" else "EPG: bez zmian")
            }
            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
            if (rightOpen) showInfo(list[pos])
        }
    }

    private inner class QuickAdapter : RecyclerView.Adapter<QuickAdapter.VH>() {
        var items: List<QuickItem> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val section: TextView = v.findViewById(R.id.section)
            val row: View = v.findViewById(R.id.row)
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_quick, p, false); Ui.style(v); return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val it = items[i]
            h.section.visibility = if (it.section != null) View.VISIBLE else View.GONE
            h.section.text = it.section
            h.label.text = it.label
            h.icon.visibility = if (it.icon != null) View.VISIBLE else View.GONE
            h.icon.setImageDrawable(it.icon)
            h.row.setOnClickListener { _ -> lastQuickPos = h.bindingAdapterPosition; it.action() }
            h.row.setOnLongClickListener(it.onLong?.let { l -> View.OnLongClickListener { l(); true } })
        }
    }

    private fun closeLeft(animate: Boolean = true) {
        if (!leftOpen) return
        quickBar.visibility = View.GONE
        if (animate) Ui.slideOut(leftPanel, toLeft = true) else leftPanel.visibility = View.GONE
    }

    private fun closeRight(animate: Boolean = true) {
        if (!rightOpen) return
        scoresColumn.visibility = View.GONE; rightColumn.visibility = View.VISIBLE
        if (animate) Ui.slideOut(rightPanel, toLeft = false) else rightPanel.visibility = View.GONE
    }

    private fun closePanels() {
        groupBrowsing = false
        handler.removeCallbacks(closePanelsRunnable)
        closeLeft(); closeRight()
        scrim.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun resetPanelTimer() {
        handler.removeCallbacks(closePanelsRunnable)
        if (leftOpen || rightOpen) handler.postDelayed(closePanelsRunnable, PANEL_TIMEOUT_MS)
    }

    // ---------------- pilot ----------------

    private val numberBox by lazy { findViewById<View>(R.id.numberBox) }

    /** Cyfry na środku ekranu z logo i nazwą kanału o tym numerze. */
    private fun showNumber() {
        numberTv.text = numberInput
        val ch = numberInput.toIntOrNull()?.let { n -> Repository.channels.firstOrNull { it.number == n } }
        val logo = findViewById<android.widget.ImageView>(R.id.numberLogo)
        val name = findViewById<TextView>(R.id.numberName)
        if (ch != null) { logo.visibility = View.VISIBLE; logo.loadPicon(ch); name.text = ch.name }
        else { logo.visibility = View.GONE; name.text = "Brak kanału" }
        if (numberBox.visibility != View.VISIBLE) {
            numberBox.background = Ui.glassBg(); Ui.style(numberBox); numberBox.visibility = View.VISIBLE
        }
    }

    private val applyNumber = Runnable {
        val n = numberInput.toIntOrNull(); numberInput = ""
        numberBox.visibility = View.GONE
        val ch = Repository.channels.firstOrNull { it.number == n } ?: return@Runnable
        zapTo(ch, group)
    }

    private fun inside(v: View, parent: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === parent) return true; x = x.parent as? View }
        return false
    }

    /** Poprzednio oglądany kanał (akcja „powrót”). */
    private var prevKey: String? = null

    private fun perform(a: pl.iptv.tv.data.KeyMap.Action): Boolean {
        when (a) {
            A.CH_PREV -> zap(-1)
            A.CH_NEXT -> zap(1)
            A.PANEL_CHANNELS -> if (Prefs.channelPanel) openLeft() else return false
            A.PANEL_RECENT -> if (Prefs.recentPanel) openRight() else return false
            A.QUICK_MENU -> openQuickBar()
            A.INFO -> { showOverlay(); openZap() }
            A.GUIDE -> openGuide(list[pos].key)
            A.LAST -> prevKey?.let { k -> Repository.byKey(k) }?.let { zapTo(it, if (it in list) group else Repository.ALL) }
            A.FAV -> { val added = Repository.toggleFav(list[pos]); toast(if (added) "★ Dodano do ulubionych" else "Usunięto z ulubionych"); showOverlay() }
            A.RECORD -> if (Prefs.recEnabled) {
                val ch = list[pos]; val r = pl.iptv.tv.data.Recordings.activeFor(ch.key)
                if (r != null) { pl.iptv.tv.rec.RecordingService.stop(this, r.id); toast("⏹ Zatrzymano nagrywanie") }
                else { pl.iptv.tv.rec.RecordingService.recordProgramme(this, ch, Repository.current(ch)); toast("⏺ Nagrywanie: ${ch.name}") }
            }
            A.AUDIO -> trackMenu(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            A.SUBS -> trackMenu(androidx.media3.common.C.TRACK_TYPE_TEXT)
            A.SKIP_AD -> skipAd()
            A.ASPECT -> { Prefs.aspectMode = (Prefs.aspectMode + 1) % ASPECTS.size; applyAspect(); toast("📐 ${ASPECTS[Prefs.aspectMode]}") }
        }
        return true
    }

    /** Przyciski multimedialne pilota: ▶‖ zawsze, ⏪ ⏩ w archiwum. */
    private fun mediaKey(code: Int): Boolean {
        val p = player ?: return false
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { p.playWhenReady = !p.playWhenReady; showOverlay() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> if (isCatchup) seekBy(Prefs.cuFwd * 1000L) else return false
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> if (isCatchup) seekBy(-Prefs.cuBack * 1000L) else return false
            else -> return false
        }
        return true
    }

    private var okPending = false; private var okLong = false; private var okReplay = false
    /** Ostatnio zmieniono kategorię ◄ ► z nagłówka panelu (fokus na pierwszym kanale). */
    private var groupBrowsing = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // panel „Teraz w TV / Kolejka” prowadzi nawigację sam (fokus nie wychodzi do paneli i kontrolek pod spodem)
        sidePanel?.let { if (it.onKey(event)) return true; if (it.isOpen) return super.dispatchKeyEvent(event) }
        if (event.action == KeyEvent.ACTION_UP && okPending && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            okPending = false
            if (!okLong) { okReplay = true; try { dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, event.keyCode)) } finally { okReplay = false } }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        resetPanelTimer()
        val focus = window.decorView.findFocus()
        // przeglądanie kategorii ◄ ► kończy każdy inny klawisz (▲▼ – ruch po kanałach, ◄ ► wracają do zwykłego działania)
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) groupBrowsing = false

        if (leftOpen) {
            val inQuick = focus != null && quickOpen && inside(focus, quickBar)
            when {
                inQuick && code == KeyEvent.KEYCODE_DPAD_RIGHT -> { closeQuickBar(); return true }
                inQuick && code == KeyEvent.KEYCODE_DPAD_LEFT -> return true
                // nagłówek kategorii: ◄ ► zmiana kategorii, ▼ kanały, OK – lista kategorii
                // ◄ ► na nagłówku: zmiana kategorii i od razu zaznaczony pierwszy kanał (wystarczy OK);
                // kolejne ◄ ► (dopóki zaznaczenie stoi na tym pierwszym kanale) dalej zmieniają kategorię
                (focus === lpGroup || (groupBrowsing && focus != null && inside(focus, lpChannels) &&
                    lpChannels.findContainingViewHolder(focus)?.bindingAdapterPosition == 0)) &&
                    (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                    cycleGroup(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); groupBrowsing = true; return true
                }
                focus === lpGroup && code == KeyEvent.KEYCODE_DPAD_DOWN -> { lpChannels.focusPosition(0); return true }
                code == KeyEvent.KEYCODE_DPAD_UP && focus != null && inside(focus, lpChannels) &&
                    lpChannels.findContainingViewHolder(focus)?.bindingAdapterPosition == 0 -> { lpGroup.requestFocus(); return true }
                // CH± / strona – poprzednia / następna kategoria
                code in intArrayOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP) -> { cycleGroup(-1); return true }
                code in intArrayOf(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN) -> { cycleGroup(1); return true }
                // drugie ◄ (na liście kanałów) = menu funkcji
                code == KeyEvent.KEYCODE_DPAD_LEFT && focus != null && inside(focus, lpChannels) -> { openQuickBar(); return true }
                // ► z listy kanałów -> program TV kanału; kolejne ► (z programu) chowa panel
                code == KeyEvent.KEYCODE_DPAD_RIGHT && focus != null && inside(focus, lpChannels) -> {
                    if (lpProgAdapter.itemCount == 0) { closePanels(); return true }
                    val now = System.currentTimeMillis()
                    lpProgrammes.focusPosition(lpProgAdapter.items.indexOfFirst { it.isNow(now) }.coerceAtLeast(0), R.id.row); return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT && focus != null && inside(focus, lpProgrammes) -> { closePanels(); return true }
                // ◄ z programu -> kanał, z którego weszliśmy (►)
                code == KeyEvent.KEYCODE_DPAD_LEFT && focus != null && inside(focus, lpProgrammes) -> {
                    lpChannels.focusPosition(lpChannelAdapter.indexOfKey(panelChannel?.key).coerceAtLeast(0)); return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (rightOpen) {
            // drugie ► = zakładka wyników, ◄ z wyników wraca do panelu
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (!scoresOpen) openScores(); return true }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && scoresOpen && focus != null && inside(focus, scoresColumn)) { closeScores(); return true }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { closePanels(); return true }
            return super.dispatchKeyEvent(event)
        }

        if (miniOpen && code in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return if (event.repeatCount > 0) true else miniKey(code)
        // OK: krótko = jak dotąd (po puszczeniu), przytrzymanie = mini menu
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val ctl = isCatchup && playerView.isControllerFullyVisible
        if (ok && !ctl && !okReplay && holdOkEnabled) {
            if (event.repeatCount == 0) { okPending = true; okLong = false }
            else if (!okLong && okPending) { okLong = true; onHoldOk() }
            return true
        }
        if (zapOpen) when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { zapMove(if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val ch = zapAdapter.items.getOrNull(zapFocused()) ?: return true
                zapHide.run(); if (ch.key != list[pos].key) zapTo(ch, zapGroupName) else showOverlay(); return true
            }
            // ◄ ► – poprzednia / następna kategoria; od razu zaznaczony pierwszy kanał (wystarczy OK)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { zapCycleGroup(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); return true }
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numberInput = (numberInput + (code - KeyEvent.KEYCODE_0)).take(4)
            showNumber()
            handler.removeCallbacks(applyNumber); handler.postDelayed(applyNumber, 1500)
            return true
        }
        if (mediaKey(code)) return true
        val controllerShown = isCatchup && playerView.isControllerFullyVisible
        if (controllerShown && !barFull && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            setBarFull(true); playerView.showController()
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus(); return true
        }
        if (controllerShown) {
            // pasek archiwum: na ▶‖ (lub pasku postępu) ◄ ► przewijają, podwójne ► pomija reklamę; ▼ – drugi rząd przycisków
            val f = window.decorView.findFocus()
            val onMain = f == null || f.id in intArrayOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_progress, R.id.ctlBack, R.id.ctlFwd)
            if (onMain && code == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-Prefs.cuBack * 1000L * seekAccel(event.repeatCount)); return true }
            if (onMain && code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (event.repeatCount > 0) seekBy(Prefs.cuFwd * 1000L * seekAccel(event.repeatCount)) else rightPressed(); return true }
            return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }
        // archiwum: ◄ ► przewijają (ustawienie w karcie Catch-up)
        if (isCatchup && Prefs.cuArrows == 0 && code == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-Prefs.cuBack * 1000L * seekAccel(event.repeatCount)); return true }
        if (isCatchup && Prefs.cuArrows == 0 && code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (event.repeatCount > 0) seekBy(Prefs.cuFwd * 1000L * seekAccel(event.repeatCount)) else rightPressed(); return true }
        val action = pl.iptv.tv.data.KeyMap.actionFor(code)
        val dpad = code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        // archiwum: strzałki/OK bez przypisanych paneli/menu pokazują pasek przewijania
        if (isCatchup && dpad && action !in listOf(pl.iptv.tv.data.KeyMap.Action.PANEL_CHANNELS, pl.iptv.tv.data.KeyMap.Action.PANEL_RECENT,
                pl.iptv.tv.data.KeyMap.Action.QUICK_MENU)) { showOverlay(); return true }
        if (action != null && perform(action)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------- wyniki (drugie ► w panelu ostatnio oglądanych) ----------------

    private val scoresColumn by lazy { findViewById<View>(R.id.scoresColumn) }
    private val scoresList by lazy { findViewById<RecyclerView>(R.id.scoresList).also { it.layoutManager = LinearLayoutManager(this); it.adapter = scoresAdapter } }
    private val scoresOpen get() = scoresColumn.visibility == View.VISIBLE
    private var scoresJob: kotlinx.coroutines.Job? = null

    /** Wiersz meczu: liga i status, herby, nazwy i wynik w ramce w kolorze motywu. */
    private inner class MatchVH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val head = TextView(this@PlayerActivity).apply { textSize = 11f; tag = "text2"; singleLine() }
        val homeLogo = android.widget.ImageView(this@PlayerActivity); val awayLogo = android.widget.ImageView(this@PlayerActivity)
        val home = TextView(this@PlayerActivity).apply { textSize = 14f; gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL; singleLine() }
        val away = TextView(this@PlayerActivity).apply { textSize = 14f; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; singleLine() }
        val score = TextView(this@PlayerActivity).apply { textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2)); tag = "keepcolor" }
        val scorers = TextView(this@PlayerActivity).apply { textSize = 11f; tag = "text2"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        private fun TextView.singleLine() { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        init {
            root.orientation = LinearLayout.VERTICAL; root.tag = "item"; root.isFocusable = true; root.isClickable = true
            root.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6))
            root.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(5) }
            root.addView(head)
            val row = LinearLayout(this@PlayerActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(3), 0, Ui.dp(2)) }
            row.addView(home, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(homeLogo, LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)).apply { marginStart = Ui.dp(6) })
            row.addView(score, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6); marginEnd = Ui.dp(6) })
            row.addView(awayLogo, LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)).apply { marginEnd = Ui.dp(6) })
            row.addView(away, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(row); root.addView(scorers)
            Ui.style(root)
        }
        fun bind(m: pl.iptv.tv.data.Scores.Match) {
            val live = m.state == MState.LIVE
            val today = Fmt.shortDay(System.currentTimeMillis()) == Fmt.shortDay(m.start)
            val status = when (m.state) {
                MState.LIVE -> "● ${m.clock}"; MState.POST -> "Koniec"
                // dart: turniej bez godziny – sama data
                MState.PRE -> if (m.league.startsWith("tsdb:")) Fmt.shortDay(m.start) else (if (today) "" else Fmt.shortDay(m.start) + " ") + Fmt.time(m.start)
            }
            head.text = android.text.SpannableStringBuilder(m.leagueName.substringAfter(' ') + "   ").apply {
                append(status, android.text.style.ForegroundColorSpan(if (live) Ui.live else Ui.accent), 0)
            }
            val single = m.away.isBlank()                       // zapowiedź turnieju (dart)
            home.text = m.home; away.text = m.away; score.text = m.shownScore
            home.gravity = if (single) android.view.Gravity.CENTER else android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            score.visibility = if (single) View.GONE else View.VISIBLE
            away.visibility = if (single) View.GONE else View.VISIBLE
            score.setTextColor(if (live) android.graphics.Color.WHITE else Ui.text)
            score.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(6).toFloat()
                if (live) setColor(Ui.live) else { setColor(android.graphics.Color.argb(60, 0, 0, 0)); setStroke(Ui.dp(1), Ui.accent) }
            }
            listOf(homeLogo to m.homeLogo, awayLogo to m.awayLogo).forEach { (iv, url) ->
                if (url.isBlank()) iv.visibility = View.GONE else { iv.visibility = View.VISIBLE; iv.load(url) { crossfade(false) } }
            }
            val goals = if (Prefs.scoreHideResults) emptyList() else m.events.filter { "⚽" in it }
            scorers.visibility = if (goals.isEmpty()) View.GONE else View.VISIBLE
            scorers.text = goals.joinToString("  ") { it.substringBefore(" (") }
            root.setOnClickListener { matchDetails(m) }
        }
    }

    private val scoresAdapter = object : RecyclerView.Adapter<MatchVH>() {
        var items: List<pl.iptv.tv.data.Scores.Match> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) = MatchVH(LinearLayout(p.context))
        override fun onBindViewHolder(h: MatchVH, i: Int) = h.bind(items[i])
    }

    private fun openScores() {
        if (!Prefs.scoresEnabled) return
        scoresColumn.layoutParams.width = rightColumn.layoutParams.width.takeIf { it > 0 } ?: Ui.dp(320)
        // telefon w pionie: wyniki zamiast ostatnio oglądanych (obie kolumny się nie mieszczą)
        if (resources.configuration.screenWidthDp < 600) rightColumn.visibility = View.GONE
        Ui.style(scoresColumn)
        Ui.slideIn(scoresColumn, fromLeft = false)          // wysuwa się od prawej krawędzi
        showScores(pl.iptv.tv.data.Scores.last)
        if (System.currentTimeMillis() - pl.iptv.tv.data.Scores.lastAt > 20_000) refreshScores()
        scoresList.post { if (scoresAdapter.itemCount > 0) scoresList.focusPosition(0) }
    }

    private fun closeScores() {
        scoresColumn.visibility = View.GONE; rightColumn.visibility = View.VISIBLE
        if (recentAdapter.itemCount > 0) rpList.focusPosition(0) else infoProgrammes.focusPosition(0, R.id.row, fallback = rightPanel)
    }

    private fun showScores(list: List<pl.iptv.tv.data.Scores.Match>) {
        val empty = findViewById<TextView>(R.id.scoresEmpty)
        val S = pl.iptv.tv.data.Scores
        empty.text = when {
            S.follows.isEmpty() -> "Brak obserwowanych lig i drużyn.\nDodaj je w Ustawienia → Wyniki."
            list.isEmpty() -> "Dziś brak meczów obserwowanych drużyn i lig."
            else -> ""
        }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val hadFocus = scoresList.hasFocus()
        scoresAdapter.items = list; scoresAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.scoresTitle).text = "⚽ Wyniki" + (if (S.lastAt > 0) "  •  ${Fmt.time(S.lastAt)}" else "")
        if (hadFocus) scoresList.post { scoresList.focusPosition(0) }
    }

    private fun refreshScores() {
        if (scoresJob?.isActive == true) return
        scoresJob = lifecycleScope.launch {
            val r = runCatching { pl.iptv.tv.data.Scores.fetch() }
            r.onSuccess { now ->
                // wszystkie nowe gole (także wykryte przez inny ekran), po kolei
                showGoals(pl.iptv.tv.data.Scores.takeGoals())
                if (scoresOpen) showScores(now)
                // wynik przy kanale z meczem: panel kanałów i lista po OK
                lpChannelAdapter.notifyItemRangeChanged(0, lpChannelAdapter.itemCount, ChannelAdapter.EPG)
                if (zapOpen) zapAdapter.notifyDataSetChanged()
            }.onFailure { if (scoresOpen) { val e = findViewById<TextView>(R.id.scoresEmpty); e.text = "Błąd pobierania wyników: ${it.message}"; e.visibility = View.VISIBLE } }
        }
    }

    /** Odświeżanie: przy otwartej zakładce lub meczu na żywo co [Prefs.scoreRefresh] s, przed meczem co minutę, poza tym co 10 min. */
    private val scoresTick: Runnable = object : Runnable {
        override fun run() {
            if (!Prefs.scoresEnabled || pl.iptv.tv.data.Scores.follows.isEmpty()) return
            refreshScores()
            val now = System.currentTimeMillis(); val S = pl.iptv.tv.data.Scores
            val live = S.last.any { it.state == MState.LIVE }
            val soon = S.last.any { it.state == MState.PRE && it.start - now < 15 * 60_000 }
            handler.postDelayed(this, when { scoresOpen || live -> Prefs.scoreRefresh * 1000L; soon -> 60_000L; else -> 600_000L })
        }
    }

    /** Gole czekające na pokazanie – kilka bramek naraz wyświetla się po kolei, nie tylko pierwsza. */
    private val pendingGoals = ArrayDeque<pl.iptv.tv.data.Scores.Match>()
    private val goalBox by lazy { findViewById<View>(R.id.goalBox) }
    /** Jeden stały Runnable: kolejny gol przedłuża wyświetlanie (wcześniej ukrycie pierwszego chowało też następny po chwili). */
    private val goalHide = Runnable { hideGoal() }
    private val goalShowing get() = goalBox.visibility == View.VISIBLE

    private fun showGoals(ms: List<pl.iptv.tv.data.Scores.Match>) {
        if (!Prefs.scoreGoalAlerts || !Prefs.scoresEnabled || ms.isEmpty()) return
        // ten sam mecz w kolejce – zostaje najnowszy stan
        ms.forEach { m -> pendingGoals.removeAll { it.id == m.id }; pendingGoals.addLast(m) }
        if (!goalShowing) nextGoal()
    }

    private fun nextGoal() { pendingGoals.removeFirstOrNull()?.let { goalToast(it) } }

    /** „GOL!” z herbami obu drużyn, w ramce w stylu paska informacji; wjeżdża z góry, znika po 6 s. */
    private fun goalToast(m: pl.iptv.tv.data.Scores.Match) {
        val box = goalBox
        val t = findViewById<TextView>(R.id.goalToast)
        t.text = android.text.SpannableStringBuilder("⚽ GOL!  ").apply {
            setSpan(android.text.style.ForegroundColorSpan(Ui.accent), 0, length, 0)
            // bez spoilerów: drużyny bez wyniku
            append(if (Prefs.scoreHideResults) "${m.home}  –  ${m.away}" else "${m.home}  ${m.hs}:${m.aws}  ${m.away}")
        }
        t.setTextColor(Ui.text); t.typeface = Ui.typeface(android.graphics.Typeface.BOLD)
        listOf(findViewById<ImageView>(R.id.goalHome) to m.homeLogo, findViewById<ImageView>(R.id.goalAway) to m.awayLogo).forEach { (iv, url) ->
            if (url.isBlank()) { iv.setImageDrawable(null); iv.visibility = View.GONE } else { iv.visibility = View.VISIBLE; iv.load(url) { crossfade(false) } }
        }
        box.background = Ui.surfaceBg(0xF0)
        box.elevation = Ui.dp(12).toFloat()                 // nad panelami i listą kanałów
        handler.removeCallbacks(goalHide)
        box.animate().cancel()
        box.visibility = View.VISIBLE
        if (Ui.anim == 0) { box.alpha = 1f; box.translationY = 0f; box.scaleX = 1f; box.scaleY = 1f }
        else {
            box.alpha = 0f; box.translationY = -Ui.dp(48).toFloat(); box.scaleX = 0.9f; box.scaleY = 0.9f
            box.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).withLayer().start()
        }
        handler.postDelayed(goalHide, 6000)
    }

    private fun hideGoal() {
        val box = goalBox
        handler.removeCallbacks(goalHide)
        if (box.visibility != View.VISIBLE) { nextGoal(); return }
        box.animate().cancel()
        if (Ui.anim == 0) { box.visibility = View.GONE; nextGoal(); return }
        box.animate().alpha(0f).translationY(-Ui.dp(32).toFloat()).setDuration(220).withLayer()
            .withEndAction { box.visibility = View.GONE; box.alpha = 1f; box.translationY = 0f; nextGoal() }.start()
    }

    private fun matchDetails(m: pl.iptv.tv.data.Scores.Match, reveal: Boolean = false) {
        val S = pl.iptv.tv.data.Scores
        // kanał z meczem: automatycznie z programu TV (nazwy drużyn w tytule/opisie), do tego kanały przypisane ręcznie
        val found = S.broadcasts(m)
        val assigned = S.assignedFor(m).filter { a -> found.none { it.channel.key == a.key } }
        val options = found.map { b -> "📺 ${b.channel.name}  •  ${Fmt.time(b.programme.start)} ${b.programme.title}" + (if (b.sure) "" else "  (?)") to b.channel } +
            assigned.map { "📌 ${it.name}" to it }
        val hidden = Prefs.scoreHideResults && !reveal && m.state != MState.PRE
        val msg = m.leagueName + "  •  " + (if (m.state == MState.PRE) "start ${Fmt.shortDay(m.start)} ${Fmt.time(m.start)}" else m.clock) +
            (if (m.events.isNotEmpty() && !hidden) "\n\n" + m.events.joinToString("\n") else "") +
            (if (hidden) "\n\nWynik ukryty (bez spoilerów) – „👁 Pokaż wynik”." else "") +
            (if (options.isNotEmpty()) "\n\nGdzie oglądać:\n" + options.joinToString("\n") { it.first }
             else "\n\nNie znaleziono meczu w programie TV. Przypisz kanały („➕ Kanały”) albo poszukaj w programie.")
        val d = Ui.dialog(this).setTitle("${m.home}  ${if (hidden) m.shownScore else m.score}  ${m.away}").setMessage(msg)
            .setNegativeButton("Zamknij", null)
        // ukryty wynik: odsłonięcie tylko dla tego meczu, na życzenie
        if (hidden) d.setNeutralButton("👁 Pokaż wynik") { _, _ -> matchDetails(m, reveal = true) }
        else d.setNeutralButton("➕ Kanały") { _, _ -> assignChannels(m) }
        if (options.isNotEmpty()) d.setPositiveButton("📺 Oglądaj") { _, _ ->
            if (options.size == 1) { closePanels(); zapTo(options[0].second, Repository.ALL) }
            else Dialogs.menu(this, "Kanał z meczem", options.map { (label, ch) -> label to { closePanels(); zapTo(ch, Repository.ALL) } })
        } else d.setPositiveButton("🔎 Szukaj w programie") { _, _ ->
            closePanels()
            startActivity(Intent(this, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_QUERY, m.homeFull.ifBlank { m.home }))
        }
        d.show()
    }

    /** Przypisanie kanałów z listy do drużyny gospodarzy, gości albo całej ligi. */
    private fun assignChannels(m: pl.iptv.tv.data.Scores.Match) {
        val S = pl.iptv.tv.data.Scores
        val opts = listOf("👕 ${m.home}" to S.teamKey(m.league, m.homeId), "👕 ${m.away}" to S.teamKey(m.league, m.awayId),
            "🏆 ${m.leagueName.substringAfter(' ')} (wszystkie mecze)" to S.leagueKey(m.league))
        Dialogs.menu(this, "Kanały dla…", opts.map { (label, key) -> label to {
            val chs = Repository.channels
            val cur = S.assigned(key).toSet()
            Dialogs.multi(this, label, chs.map { it.name }, BooleanArray(chs.size) { chs[it].key in cur }) { sel ->
                S.assign(key, chs.filterIndexed { i, _ -> sel[i] }.map { it.key })
                toast("Zapisano: ${sel.count { it }} kan."); matchDetails(m)
            }
        } })
    }

    // ---------------- lista kanałów po OK (nad paskiem informacji) ----------------

    private val zapPanel by lazy { findViewById<View>(R.id.zapPanel) }
    private val zapStride get() = zapRowH + Ui.dp(4)
    /** Pierwszy widoczny wiersz – lista zawsze pokazuje pełne kafelki (przewijanie o całe wiersze). */
    private var zapTop = 0
    private var zapShown = 8
    private var zapRowsMax = 8

    private val zapList by lazy { findViewById<RecyclerView>(R.id.zapList).also {
        it.layoutManager = object : LinearLayoutManager(this) {
            // przewija tylko nasz kod (płynnie, o pełny wiersz) – bez systemowego „dociągania” do zaznaczonego elementu
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean) = false
            // sąsiednie wiersze są gotowe przed przewinięciem (fokus od razu, bez czekania)
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extra: IntArray) { extra[0] = zapStride * 2; extra[1] = zapStride * 2 }
        }
        it.adapter = zapAdapter; it.itemAnimator = null } }
    private val zapOpen get() = ::playerView.isInitialized && zapPanel.visibility == View.VISIBLE
    private val zapRowH get() = Ui.dp(36)

    private inner class ZapVH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val num = TextView(this@PlayerActivity).apply { textSize = 13f; tag = "text2"; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val logo = ImageView(this@PlayerActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = TextView(this@PlayerActivity).apply { textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val now = TextView(this@PlayerActivity).apply { textSize = 13f; tag = "text2"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        init {
            root.tag = "item nozoom"; root.isFocusable = true; root.isClickable = true; root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(Ui.dp(8), 0, Ui.dp(10), 0)
            root.layoutParams = RecyclerView.LayoutParams(-1, zapRowH).apply { bottomMargin = Ui.dp(4) }
            root.addView(num, LinearLayout.LayoutParams(Ui.dp(32), -2))
            root.addView(logo, LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(24)).apply { marginStart = Ui.dp(8); marginEnd = Ui.dp(8) })
            root.addView(name, LinearLayout.LayoutParams(0, -2, 0.42f))
            root.addView(now, LinearLayout.LayoutParams(0, -2, 0.58f).apply { marginStart = Ui.dp(8) })
            Ui.style(root)
            root.background = Ui.tileBg()
        }
        fun bind(ch: Channel, playing: Boolean) {
            num.text = ch.number.toString(); num.visibility = if (Prefs.showNumbers) View.VISIBLE else View.GONE
            name.text = (if (playing) "▶ " else "") + ch.name; logo.loadPicon(ch)
            val live = pl.iptv.tv.data.Scores.liveFor(ch)
            now.text = live ?: Repository.current(ch)?.let { "${Fmt.time(it.start)}  ${it.title}" } ?: ""
            // kolor zależny od stanu: na zaznaczonym kafelku – kolor tekstu zaznaczenia (szary był nieczytelny na akcencie)
            now.setTextColor(if (live != null) android.content.res.ColorStateList.valueOf(Ui.live) else Ui.textColors(Ui.text2))
            root.isActivated = playing
            root.setOnClickListener { zapHide.run(); if (ch.key != list[pos].key) zapTo(ch, zapGroupName) }
        }
    }

    private val zapAdapter = object : RecyclerView.Adapter<ZapVH>() {
        var items: List<Channel> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) = ZapVH(LinearLayout(p.context))
        override fun onBindViewHolder(h: ZapVH, i: Int) = h.bind(items[i], items[i].key == list.getOrNull(pos)?.key)
    }

    private fun zapFocused(): Int = window.decorView.findFocus()?.let { zapList.findContainingViewHolder(it)?.bindingAdapterPosition }
        ?: if (zapGroupName == group) pos else 0

    /** Kategoria pokazywana na liście po OK (może być inna niż kategoria oglądanego kanału). */
    private var zapGroupName = Repository.ALL
    private val zapGroupView by lazy { findViewById<TextView>(R.id.zapGroup).also { v ->
        v.setOnClickListener { zapCycleGroup(1) } } }

    private fun zapShowGroup() {
        val groups = Repository.groups()
        zapGroupView.text = "◄  $zapGroupName  ${groups.indexOf(zapGroupName) + 1}/${groups.size}  ►"
    }

    /** ◄ ► na liście po OK: zmiana kategorii, fokus od razu na pierwszym kanale nowej kategorii. */
    private fun zapCycleGroup(d: Int) {
        val groups = Repository.groups().filter { Repository.channelsFor(it).isNotEmpty() }
        if (groups.isEmpty()) return
        val i = groups.indexOf(zapGroupName)
        zapGroupName = groups[if (i < 0) 0 else (i + d + groups.size) % groups.size]
        zapAdapter.items = Repository.channelsFor(zapGroupName); zapAdapter.notifyDataSetChanged()
        zapShowGroup()
        val shown = minOf(zapRowsMax, zapAdapter.itemCount).coerceAtLeast(1)
        zapShown = shown
        zapList.layoutParams.height = shown * zapStride + Ui.dp(8); zapList.requestLayout()
        zapTop = 0
        zapList.stopScroll()
        (zapList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
        if (Ui.anim != 0) {
            zapList.translationX = Ui.dp(if (d >= 0) 40 else -40).toFloat(); zapList.alpha = 0f
            zapList.animate().translationX(0f).alpha(1f).setDuration(Ui.animDuration).start()
        }
        zapList.focusPosition(0)
        zapTimer()
    }

    private fun zapMove(d: Int) {
        val n = zapAdapter.itemCount; if (n == 0) return
        val i = ((zapFocused() + d) % n + n) % n                  // z końca na początek i odwrotnie
        val shown = minOf(zapShown, n)
        val wrap = (d > 0 && i == 0) || (d < 0 && i == n - 1)
        val top = when { i < zapTop -> i; i >= zapTop + shown -> i - shown + 1; else -> zapTop }.coerceIn(0, maxOf(0, n - shown))
        if (wrap) { zapTop = top; zapList.stopScroll(); (zapList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(top, 0) }
        else if (top != zapTop) {
            zapTop = top
            // płynnie dokładnie do granicy wiersza (pierwszy i ostatni kafelek zawsze w całości)
            val v = zapList.findViewHolderForAdapterPosition(top)?.itemView
            val dy = if (v != null) v.top - zapList.paddingTop else top * zapStride - zapList.computeVerticalScrollOffset()
            zapList.smoothScrollBy(0, dy, android.view.animation.DecelerateInterpolator(), 140)
        }
        zapList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: zapList.post { zapList.focusPosition(i) }
        zapTimer()
    }

    private val zapHide = Runnable { if (::playerView.isInitialized) zapPanel.visibility = View.GONE }
    private fun zapTimer() { handler.removeCallbacks(zapHide); handler.postDelayed(zapHide, 3000) }

    /**
     * Niski ekran (telefon w poziomie): pasek informacji i górny pasek dotykowy zajmują dużą część wysokości – lista po OK
     * dostaje tyle pełnych wierszy, ile mieści się między nimi (bez nachodzenia warstw).
     */
    private fun fitZapBetweenBars() {
        val infoTop = Ui.infoPos == 1
        val upper = maxOf(if (touchBar.visibility == View.VISIBLE) touchBar.bottom else 0, if (infoTop) overlay.bottom else 0)
        val lower = if (infoTop) (zapPanel.parent as View).height else overlay.top
        val free = lower - upper - Ui.dp(16)
        val extra = zapPanel.height - zapList.layoutParams.height           // nagłówek kategorii
        val stride = zapRowH + Ui.dp(4)
        val rows = ((free - extra - Ui.dp(8)) / stride).coerceAtLeast(1)
        if (rows >= zapShown) return
        zapRowsMax = rows; zapShown = rows
        zapList.layoutParams.height = rows * stride + Ui.dp(8); zapList.requestLayout()
        // środek wolnego pasa
        zapPanel.translationY = 0f
        zapPanel.post { zapPanel.translationY = (upper + Ui.dp(8) + (free - zapPanel.height) / 2f - zapPanel.top).coerceAtLeast(-zapPanel.top.toFloat()) }
    }

    /** Lista kanałów z bieżącym programem na środku ekranu – same kafelki (bez ramki), znika po 3 s bez ruchu. */
    private fun openZap() {
        if (!Prefs.zapListEnabled || isCatchup || list.isEmpty()) return
        val rows = Prefs.zapRows.coerceIn(4, 14)
        zapGroupName = group
        zapAdapter.items = list; zapAdapter.notifyDataSetChanged()
        zapShowGroup(); zapGroupView.background = Ui.tileBg(); zapList.translationX = 0f; zapList.alpha = 1f
        (zapGroupView.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.dp(4)
        val dm = resources.displayMetrics
        val narrow = dm.widthPixels / dm.density < 600
        (zapPanel.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(Ui.dp(560), (dm.widthPixels * if (narrow) 0.94f else 0.5f).toInt())
            gravity = Gravity.CENTER; setMargins(0, 0, 0, 0)
        }
        // wiersze nie mogą zasłonić paska informacji: na niskim ekranie mniej wierszy
        val maxRows = ((dm.heightPixels * 0.62f) / (zapRowH + Ui.dp(4))).toInt().coerceAtLeast(3)
        zapRowsMax = minOf(rows, maxRows)
        val shown = minOf(rows, maxRows, list.size)
        zapList.layoutParams.height = shown * (zapRowH + Ui.dp(4)) + Ui.dp(8)
        zapList.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        zapPanel.background = null; zapPanel.setPadding(0, 0, 0, 0)
        Ui.style(zapList)
        zapList.clipToPadding = true                          // bez „wystających” fragmentów sąsiednich kafelków
        zapPanel.translationY = 0f
        zapPanel.visibility = View.VISIBLE
        // na środku, ale nie pod paskiem informacji
        zapPanel.post {
            if (overlay.visibility != View.VISIBLE || overlay.height == 0) return@post
            fitZapBetweenBars()
            val top = Ui.infoPos == 1
            if (!top) { val over = zapPanel.bottom - (overlay.top - Ui.dp(8)); if (over > 0) zapPanel.translationY = -over.toFloat().coerceAtMost(zapPanel.top.toFloat()) }
            else { val over = overlay.bottom + Ui.dp(8) - zapPanel.top; if (over > 0) zapPanel.translationY = over.toFloat() }
        }
        zapShown = shown
        zapTop = (pos - shown / 2).coerceIn(0, maxOf(0, list.size - shown))
        zapList.stopScroll()
        (zapList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(zapTop, 0)
        zapList.focusPosition(pos)
        zapTimer()
    }

    // ---------------- mini menu (długie OK / przytrzymanie ekranu) ----------------

    private val miniMenu by lazy { findViewById<android.widget.LinearLayout>(R.id.miniMenu) }
    private val miniOpen get() = ::playerView.isInitialized && miniMenu.visibility == View.VISIBLE
    private val closeMini = Runnable { miniMenu.visibility = View.GONE }
    private fun miniTimer() { handler.removeCallbacks(closeMini); handler.postDelayed(closeMini, 3000) }

    private fun miniEntries(): List<Pair<String, () -> Unit>> {
        val ch = list[pos]
        return pl.iptv.tv.data.MiniMenu.visible().mapNotNull { id -> when (id) {
            "settings" -> "⚙ Ustawienia" to { startActivity(Intent(this, SettingsActivity::class.java)) }
            "sys_settings" -> "🛠 Ustawienia urządzenia" to { SystemTools.openSystemSettings(this) }
            "guide" -> "📅 Program TV" to { openGuide(ch.key) }
            "desc" -> "ℹ Opis programu" to {
                val t = if (isCatchup) catchStart else System.currentTimeMillis()
                val p = Repository.programmes(ch).firstOrNull { t >= it.start && t < it.end }
                if (p != null) ProgrammeActions.details(this, ch, p) else toast("Brak opisu programu")
            }
            "fav" -> (if (Repository.isFav(ch)) "☆ Usuń z ulubionych" else "★ Dodaj do ulubionych") to {
                val added = Repository.toggleFav(ch); toast(if (added) "★ Dodano do ulubionych" else "Usunięto z ulubionych"); updateFavButton()
            }
            "record" -> if (!Prefs.recEnabled) null else if (pl.iptv.tv.data.Recordings.activeFor(ch.key) != null) "⏹ Zatrzymaj nagrywanie" to { perform(A.RECORD) }
                else "⏺ Zacznij nagrywanie" to { perform(A.RECORD) }
            "tracks" -> "🎧 Audio i napisy" to { tracksMenu() }
            "aspect" -> "📐 Proporcje obrazu" to { perform(A.ASPECT) }
            "channels" -> "☰ Lista kanałów" to { openLeft() }
            "queue" -> "📋 Kolejka i propozycje" to { openQueuePanel() }
            "engine" -> "⚡ Silnik i akceleracja" to { playbackMenu() }
            else -> if (id.startsWith(pl.iptv.tv.data.MiniMenu.APP)) {
                val cn = android.content.ComponentName.unflattenFromString(id.removePrefix(pl.iptv.tv.data.MiniMenu.APP))
                val label = pl.iptv.tv.data.MiniMenu.ITEMS[id] ?: id
                if (cn == null || runCatching { packageManager.getActivityInfo(cn, 0) }.isFailure) null
                else label to { runCatching { startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { toast("Nie można uruchomić") } }
            } else null
        } }
    }

    /** Przytrzymanie OK / ekranu: menu odtwarzania (domyślnie) albo szybkie menu ikon (Ustawienia → Odtwarzanie). */
    private val holdOkEnabled get() = Prefs.okLongAction == 0 || Prefs.miniMenuEnabled
    private fun onHoldOk() { if (Prefs.okLongAction == 0) playbackMenu() else openMini() }

    /**
     * Menu odtwarzania (przytrzymanie OK): silnik odtwarzania i akceleracja sprzętowa przełączane na żywo –
     * kanał startuje od razu z nowymi ustawieniami (archiwum wraca w to samo miejsce).
     */
    private fun playbackMenu() {
        closePanels(); hideOverlay.run()
        Dialogs.menuLive(this, "Odtwarzanie") {
            val eng = Prefs.playerEngine.coerceIn(Playback.ENGINES.indices)
            listOfNotNull(
                "▶ Silnik: ${Playback.ENGINES[eng].substringAfter("– ").substringBefore(" (")}" to {
                    Prefs.playerEngine = (eng + 1) % Playback.ENGINES.size; restartEngine()
                },
                (if (Prefs.hwAccel) "⚡ Akceleracja sprzętowa: ✔ włączona" else "⚡ Akceleracja sprzętowa: ✖ wyłączona") to {
                    Prefs.hwAccel = !Prefs.hwAccel; restartEngine()
                },
                "⇄ Kolejka dekodera: " + listOf("automatycznie", "asynchroniczna", "synchroniczna")[Prefs.asyncDecoder.coerceIn(0, 2)] to {
                    Prefs.asyncDecoder = (Prefs.asyncDecoder.coerceIn(0, 2) + 1) % 3; restartEngine()
                },
                "ℹ Bieżący dekoder" to { Dialogs.confirm(this, "Bieżący dekoder", Playback.decoderInfo()) {} },
                if (Prefs.miniMenuEnabled && Prefs.okLongAction == 0) "▦ Szybkie menu ikon" to {
                    Ui.dismissOpenDialogs(this); openMini()
                } else null,
            )
        }
    }

    /** Nowy odtwarzacz z bieżącymi ustawieniami silnika (ten sam kanał; archiwum – ta sama pozycja). */
    private fun restartEngine() {
        val at = if (isCatchup) player?.currentPosition ?: 0L else 0L
        Playback.removeListener(listener)
        Playback.stop(); player = null           // stary odtwarzacz, dekodery i połączenia całkowicie zwolnione
        initPlayer(); playCurrent()
        if (isCatchup && at > 0) player?.seekTo(at)
        toast("Silnik: ${Playback.ENGINES[Prefs.playerEngine.coerceIn(Playback.ENGINES.indices)].substringAfter("– ")} • akceleracja ${if (Prefs.hwAccel) "wł." else "wył."}")
    }

    private fun openMini() {
        val e = miniEntries(); if (e.isEmpty()) return
        closePanels(); hideOverlay.run()
        val icons = findViewById<android.widget.LinearLayout>(R.id.miniIcons); icons.removeAllViews()
        miniBoxes.clear()
        val label = findViewById<TextView>(R.id.miniLabel)
        e.forEach { (text, action) ->
            val icon = text.substringBefore(' '); val name = text.substringAfter(' ')
            val appId = pl.iptv.tv.data.MiniMenu.ITEMS.entries.firstOrNull { it.value == text && it.key.startsWith(pl.iptv.tv.data.MiniMenu.APP) }?.key
            val appIcon = appId?.let { id -> android.content.ComponentName.unflattenFromString(id.removePrefix(pl.iptv.tv.data.MiniMenu.APP))
                ?.let { cn -> runCatching { packageManager.getActivityIcon(cn) }.getOrNull() } }
            // każda pozycja: kwadrat 64 dp z ikoną na środku (ikony motywu lub ikona aplikacji – zawsze w tym samym miejscu)
            val res = MINI_ICONS[icon]
            val img = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; isDuplicateParentStateEnabled = true
                if (appIcon != null) setImageDrawable(appIcon) else if (res != null) { setImageResource(res); imageTintList = Ui.iconTint() }
            }
            val box = android.widget.FrameLayout(this).apply {
                tag = "item"; isFocusable = true; isClickable = true; contentDescription = name
                if (appIcon == null && res == null) addView(TextView(this@PlayerActivity).apply { this.text = icon; textSize = 26f; gravity = android.view.Gravity.CENTER },
                    android.widget.FrameLayout.LayoutParams(-1, -1))
                else addView(img, android.widget.FrameLayout.LayoutParams(Ui.dp(if (appIcon != null) 40 else 32), Ui.dp(if (appIcon != null) 40 else 32), android.view.Gravity.CENTER))
                setOnFocusChangeListener { _, f -> if (f) label.text = name }
                setOnClickListener { closeMini.run(); action() }
            }
            miniBoxes += box
        }
        // tyle ikon w rzędzie, ile mieści się na ekranie (telefon w pionie: kilka rzędów)
        val dm = resources.displayMetrics
        miniPerRow = ((dm.widthPixels - Ui.dp(64)) / Ui.dp(74)).coerceAtLeast(1)
        fun lp() = android.widget.LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)).apply { setMargins(Ui.dp(5), Ui.dp(5), Ui.dp(5), Ui.dp(5)) }
        if (miniBoxes.size <= miniPerRow) { icons.orientation = android.widget.LinearLayout.HORIZONTAL; miniBoxes.forEach { icons.addView(it, lp()) } }
        else {
            icons.orientation = android.widget.LinearLayout.VERTICAL
            miniBoxes.chunked(miniPerRow).forEach { row ->
                icons.addView(android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER; clipChildren = false; clipToPadding = false
                    row.forEach { addView(it, lp()) } }, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
        }
        // powiększona (zaznaczona) ikona nie jest ucinana na brzegach
        icons.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(4)); icons.clipChildren = false; icons.clipToPadding = false
        miniMenu.clipChildren = false; miniMenu.clipToPadding = false
        miniMenu.background = Ui.glassBg(); miniMenu.clipToOutline = true        // kafelki nie wychodzą poza zaokrąglony panel
        Ui.style(miniMenu)
        // kształt kafelków ikon wg motywu: okrągłe (iOS), kwadratowe (Xbox), zaokrąglone (pozostałe)
        miniBoxes.forEach { it.background = Ui.iconBoxBg() }
        miniMenu.visibility = View.VISIBLE
        label.text = e[0].first.substringAfter(' '); miniBoxes[0].requestFocus(); icons.post { if (!miniBoxes[0].isFocused) miniBoxes[0].requestFocus() }
        miniTimer()
    }

    /** Program TV z odtwarzacza: płynne przejście (krótkie zanikanie), obraz kanału zostaje w tle przewodnika. */
    private fun openGuide(key: String) {
        startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, key).putExtra(GuideActivity.EXTRA_FROM_PLAYER, true))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.fade_in, R.anim.hold)
    }

    private val MINI_ICONS = mapOf("⚙" to R.drawable.ic_n_settings, "🛠" to R.drawable.ic_n_tools, "📅" to R.drawable.ic_n_epg, "ℹ" to R.drawable.ic_n_info,
        "★" to R.drawable.ic_n_star, "☆" to R.drawable.ic_n_star, "⏺" to R.drawable.ic_n_rec, "⏹" to R.drawable.ic_n_rec,
        "🎧" to R.drawable.ic_n_tracks, "📐" to R.drawable.ic_n_aspect, "☰" to R.drawable.ic_n_list, "⚡" to R.drawable.ic_n_engine)

    private val miniBoxes = ArrayList<View>()
    private var miniPerRow = 8

    private fun miniKey(code: Int): Boolean {
        miniTimer()
        if (miniBoxes.isEmpty()) return true
        val i = miniBoxes.indexOfFirst { it.isFocused }.coerceAtLeast(0)
        val last = miniBoxes.lastIndex
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> miniBoxes[(i - 1).coerceAtLeast(0)].requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> miniBoxes[(i + 1).coerceAtMost(last)].requestFocus()
            KeyEvent.KEYCODE_DPAD_UP -> if (i - miniPerRow >= 0) miniBoxes[i - miniPerRow].requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (i + miniPerRow <= last) miniBoxes[i + miniPerRow].requestFocus() else if (i / miniPerRow < last / miniPerRow) miniBoxes[last].requestFocus()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { okPending = false; miniBoxes[i].performClick() }
            else -> {}
        }
        return true
    }

    /** Ścieżki audio i napisy w jednym oknie. */
    private fun tracksMenu() {
        val items = trackItems(androidx.media3.common.C.TRACK_TYPE_AUDIO).map { "🔊 " + it.first to it.second } +
            trackItems(androidx.media3.common.C.TRACK_TYPE_TEXT).map { "💬 " + it.first to it.second } +
            ("⏱ Synchronizacja dźwięku: ${delayLabel(Prefs.audioDelayMs)}" to { audioDelayMenu() })
        Dialogs.menu(this, "Audio i napisy", items)
    }

    /** Przesunięcie dźwięku – zmiana słyszalna od razu (okno zostaje otwarte). */
    private fun audioDelayMenu() {
        val values = (-1000..1000 step 100).toList()
        Dialogs.choiceLive(this, "Synchronizacja dźwięku", values.map { delayLabel(it) }, values.indexOf(Prefs.audioDelayMs).coerceAtLeast(10)) { i ->
            Prefs.audioDelayMs = values[i]
        }
    }

    // ---------------- dotyk ----------------

    private val gestures by lazy { GestureDetector(this@PlayerActivity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) { if (holdOkEnabled) onHoldOk() }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (overlay.visibility == View.VISIBLE && touchBar.visibility == View.VISIBLE && !isCatchup) hideOverlay.run()
            else { showOverlay(touch = true); openZap() }        // telefon: także lista kanałów (dotknięcie kafelka = przełącz)
            return false
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) * 1.3f && abs(vx) > 800) {
                if (vx > 0) openLeft() else openRight(); return true
            }
            if (!isCatchup && abs(vy) > abs(vx) * 1.3f && abs(vy) > 800) {
                if (vy < 0) zap(1) else zap(-1); return true
            }
            return false
        }
    }) }

    /** Telefon: w otwartym panelu ► przesunięcie w lewo = zakładka wyników, w prawo = cofnij. */
    private val panelGestures by lazy { GestureDetector(this@PlayerActivity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) * 1.3f || abs(vx) < 800) return false
            if (vx < 0) { if (!scoresOpen) openScores() } else if (scoresOpen) closeScores() else closePanels()
            return true
        }
    }) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetPanelTimer()
        if (!leftOpen && !rightOpen) {
            // przyciski i lista kanałów obsługują dotyk same (bez gestów przełączania / chowania paska)
            val onZap = zapOpen && hit(zapPanel, ev)
            if (onZap) zapTimer()
            val onButtons = (touchBar.visibility == View.VISIBLE && hit(touchBar, ev)) || onZap
            if (!onButtons) gestures.onTouchEvent(ev)
        } else if (rightOpen) panelGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun hit(v: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }
}

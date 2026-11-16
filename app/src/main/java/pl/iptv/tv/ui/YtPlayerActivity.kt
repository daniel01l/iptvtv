package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreamException
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.data.YtChapter
import pl.iptv.tv.data.YtChapters

/**
 * Wbudowany odtwarzacz STube (Media3): strumienie YouTube bez reklam ([YtStreams]), automatyczne pomijanie
 * segmentów SponsorBlock, sterowanie pilotem. Gdy film nie da się odtworzyć (blokada, błąd strumienia, brak odpowiedzi) –
 * od razu okno: „Odtwórz w SmartTube”, „Odtwórz w YouTube”, „Zamknij”.
 */
class YtPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "id"; const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"; const val EXTRA_THUMB = "thumb"; const val EXTRA_FROM_START = "from_start"
        /** Bez gotowości odtwarzania po tylu ms od startu – strumień uznany za zablokowany. */
        const val START_TIMEOUT_MS = 25_000L
        const val SEEK_STEP_MS = 10_000L
        /** Co ile ms zapisywać pozycję w historii oglądania ([StubeHistory]) – także przy nagłym wyłączeniu TV. */
        const val SAVE_EVERY_MS = 10_000L
        const val TAB_RELATED = 0; const val TAB_QUEUE = 1
        const val AMBIENT_TICK_MS = 15_000L
        /** Bieżący ekran odtwarzacza (polecenia z telefonu). */
        @Volatile var active: java.lang.ref.WeakReference<YtPlayerActivity>? = null

        fun intent(c: Context, id: String, title: String = "") =
            Intent(c, YtPlayerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)

        /** Film z kafelka STube: dane do rzędu „Kontynuuj oglądanie”; [fromStart] – bez wznawiania od zapisanej pozycji. */
        fun intent(c: Context, v: YtVideo, fromStart: Boolean = false) = intent(c, v.id, v.title)
            .putExtra(EXTRA_CHANNEL, v.channel).putExtra(EXTRA_THUMB, v.thumb).putExtra(EXTRA_FROM_START, fromStart)
    }

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var skipLabel: TextView
    var player: ExoPlayer? = null; private set
    /** Okno awaryjne (testy). */
    var fallbackDialog: AlertDialog? = null; private set
    var segments: List<SbSegment> = emptyList(); private set
    /** Ostatnio pominięty segment (testy, komunikat). */
    var lastSkipped: SbSegment? = null; private set

    private val videoId get() = intent.getStringExtra(EXTRA_ID).orEmpty()
    private val videoUrl get() = "https://www.youtube.com/watch?v=$videoId"
    private var loadJob: Job? = null
    private var reExtracted = false
    private var resumeAt = 0L
    /** Zapisana pozycja sprawdzona (raz na otwarcie – ponowna ekstrakcja / powrót z HOME używa [resumeAt]). */
    private var resumeChecked = false
    private var live = false
    /** Koniec filmu (także pominięty segment końcowy SponsorBlock) – w historii jako obejrzany. */
    private var ended = false
    private var title = ""
    private val video get() = YtVideo(videoId, title.ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() },
        intent.getStringExtra(EXTRA_CHANNEL).orEmpty(), intent.getStringExtra(EXTRA_THUMB).orEmpty())
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var chapterLabel: TextView
    private lateinit var optionsBar: LinearLayout
    private lateinit var speedBtn: TextView
    private lateinit var eqBtn: TextView
    private lateinit var chaptersBtn: TextView
    private lateinit var queueBtn: TextView
    /** Rozdziały bieżącego filmu (oś czasu, ▲▼, lista). */
    var chapters: List<YtChapter> = emptyList(); private set
    /** Korektor na sesji audio odtwarzacza (null – brak odtwarzacza). */
    var eq: YtEqSession? = null; private set

    /** Przycisk paska opcji: duży, fokusowalny, zaznaczenie wg motywu ([Ui.style], tag „item”). */
    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; tag = "item"; textSize = 18f; isFocusable = true; isClickable = true
        setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10)); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        optionsBar.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
    }

    override fun onResume() { super.onResume(); StubeTheme.enter() }
    override fun onPause() { super.onPause(); StubeTheme.exit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // kolory motywu STube (znaczniki rozdziałów, przyciski, okna)
        StubeTheme.enter()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // kanał IPTV (podgląd / tło menu) zatrzymany – dwa odtwarzacze naraz to brak dekoderów na wielu TV
        Playback.stop()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 4000; setShowNextButton(false); setShowPreviousButton(false)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(16))
            text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { "$it\n\nWczytywanie…" } ?: "Wczytywanie…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // komunikat SponsorBlock – duży, w rogu, czytelny z kanapy
        skipLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE; setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10))
            background = GradientDrawable().apply { setColor(0xD0101010.toInt()); cornerRadius = Ui.dp(22).toFloat(); setStroke(Ui.dp(2), Ui.accent) }
        }
        root.addView(skipLabel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(40), Ui.dp(96)) })
        // bieżący rozdział (lewy górny róg) i pasek opcji (prawy górny róg) – widoczne razem z paskiem odtwarzacza
        chapterLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8)); maxWidth = Ui.dp(560)
            background = GradientDrawable().apply { setColor(0xC0101010.toInt()); cornerRadius = Ui.dp(18).toFloat() }
        }
        root.addView(chapterLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(Ui.dp(32), Ui.dp(24), 0, 0) })
        optionsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL }
        speedBtn = barButton("⏩ 1.0×") { speedMenu() }
        eqBtn = barButton("🎚 Dźwięk") { eqPanel() }
        chaptersBtn = barButton("☰ Rozdziały") { chaptersMenu() }.apply { visibility = View.GONE }
        queueBtn = barButton("📋 Następne") { openPanel(TAB_RELATED) }
        root.addView(optionsBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(20), Ui.dp(32), 0) })
        Ui.style(optionsBar)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControllerVisibility(v) })
        setContentView(root)
        resumeAt = savedInstanceState?.getLong("pos") ?: 0L
        resumeChecked = savedInstanceState != null || intent.getBooleanExtra(EXTRA_FROM_START, false)
        if (videoId.isBlank()) { finish(); return }
        handler.postDelayed(idleTick, AMBIENT_TICK_MS)
        lifecycleScope.launch { pl.iptv.tv.data.StubeQueue.items(); updateButtons() }
    }

    override fun onStart() {
        super.onStart()
        active = java.lang.ref.WeakReference(this)
        if (player != null || fallbackDialog != null) return
        // powrót z gry w tle (ten sam film) – odbiór odtwarzacza bez przerwy w dźwięku; inny film – tło zatrzymane
        if (YtBackground.holds(videoId)) YtBackground.take(this)?.let { reclaim(it); return }
        YtBackground.stop(this)
        start()
    }

    override fun onStop() {
        super.onStop()
        val p = player
        // HOME / inna aplikacja: gra w tle (Ustawienia) albo zwolnienie odtwarzacza i dekoderów (powrót – ta sama chwila)
        if (p != null && Prefs.ytBackground && !isFinishing && !isChangingConfigurations && YtBackground.canHandOff(p)) {
            handOffToBackground(p); return
        }
        p?.let { resumeAt = it.currentPosition }
        release()
    }

    /** Odtwarzacz przechodzi do usługi w tle; ekran nie trzyma już do niego odwołań. */
    private fun handOffToBackground(p: ExoPlayer) {
        saveProgress()
        resumeAt = p.currentPosition
        handler.removeCallbacks(startTimeout); handler.removeCallbacks(sbTick); handler.removeCallbacks(saveTick)
        p.removeListener(listener)
        playerView.player = null
        YtBackground.handOff(applicationContext, p, video, eq, segments, lastPlayable)
        player = null; eq = null
    }

    /** Powrót do ekranu z gry w tle: ten sam odtwarzacz, korektor, rozdziały i propozycje. */
    private fun reclaim(t: YtBackground.Taken) {
        val exo = t.player
        player = exo; eq = t.eq; segments = t.segments
        t.playable?.let { lastPlayable = it; live = it is YtPlayable.Live; related = it.related; showChapters(it.chapters); if (it.title.isNotBlank()) title = it.title }
        playerView.player = exo
        exo.addListener(listener)
        status.visibility = View.GONE
        updateButtons(); keepScreen()
        handler.removeCallbacks(sbTick); handler.postDelayed(sbTick, 500)
        handler.removeCallbacks(saveTick); if (!live) handler.postDelayed(saveTick, SAVE_EVERY_MS)
    }

    /** Ostatnio odtwarzany film (rozdziały, propozycje – także po powrocie z tła). */
    private var lastPlayable: YtPlayable? = null
    /** Propozycje bieżącego filmu (panel boczny). */
    var related: List<pl.iptv.tv.data.YtVideo> = emptyList(); private set

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putLong("pos", player?.currentPosition ?: resumeAt) }

    private fun start() {
        loadJob?.cancel()
        status.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            val sb = if (Prefs.sbEnabled) async { SponsorBlock.segments(videoId) } else null
            val playable = try { YtStreams.resolve(videoId) } catch (e: YtStreamException) { showFallback(e.message ?: "Film niedostępny"); return@launch }
            catch (e: Exception) { showFallback(YtStreams.message(e)); return@launch }
            if (isFinishing || isDestroyed) return@launch
            live = playable is YtPlayable.Live
            if (!live) segments = sb?.await().orEmpty()
            // wznowienie od miejsca, w którym oglądanie zostało przerwane (jak w YouTube; menu kafelka: „od początku”)
            if (!resumeChecked) {
                resumeChecked = true
                if (!live && resumeAt == 0L && Prefs.stubeResume) resumeAt = StubeHistory.resumeFrom(StubeHistory.progress(videoId))
                if (resumeAt > 0) resumedLabel = "Wznawianie od " + clock(resumeAt)
            }
            play(playable)
        }
    }

    private fun play(p: YtPlayable) {
        // ekran zakryty w trakcie pobierania adresów (HOME) – bez dźwięku w tle; onStart uruchomi film od nowa
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        release()
        val exo = ExoPlayer.Builder(this, renderers()).build()
        player = exo
        playerView.player = exo
        exo.addListener(listener)
        exo.setMediaSource(YtMedia.source(this, p))
        if (resumeAt > 0 && p !is YtPlayable.Live) exo.seekTo(resumeAt)
        // prędkość zapamiętana (jak w SmartTube); transmisja na żywo – zawsze 1.0×
        exo.setPlaybackSpeed(if (live) 1f else speed)
        exo.playWhenReady = true
        exo.prepare()
        // korektor na sesji audio tego odtwarzacza (preset z Ustawień; nowa sesja → nowy korektor)
        attachEq(exo.audioSessionId)
        showChapters(p.chapters)
        lastPlayable = p; related = p.related
        updateButtons()
        if (p.title.isNotBlank()) title = p.title
        status.text = p.title.ifBlank { "Wczytywanie…" } + "\n\n" + (resumedLabel ?: "Wczytywanie…")
        resumedLabel = null
        handler.removeCallbacks(startTimeout); handler.postDelayed(startTimeout, START_TIMEOUT_MS)
        handler.removeCallbacks(sbTick); handler.postDelayed(sbTick, 500)
        handler.removeCallbacks(saveTick); if (!live) handler.postDelayed(saveTick, SAVE_EVERY_MS)
    }

    private var resumedLabel: String? = null

    private fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------- rozdziały ----------------

    /** Rozdziały na osi czasu: znaczniki na pasku postępu (kolor akcentu motywu) i przycisk listy. */
    private fun showChapters(list: List<YtChapter>) {
        chapters = list
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
        chapterLabel.visibility = if (i >= 0 && playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
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
        Dialogs.menu(this, "Rozdziały", chapters.mapIndexed { i, c ->
            ((if (i == cur) "▶  " else "     ") + clock(c.startMs) + "   " + c.title) to { player?.seekTo(c.startMs); playerView.showController(); updateChapterLabel() }
        })
    }

    // ---------------- prędkość ----------------

    /** Dostępne prędkości (procent). */
    val SPEEDS = listOf(50, 100, 125, 150, 200)
    private val speed get() = (Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100) / 100f
    private fun speedLabel(pct: Int) = (if (pct % 100 == 0) "%.1f×" else if (pct % 10 == 0) "%.1f×" else "%.2f×").format(java.util.Locale.US, pct / 100f)

    fun setSpeed(pct: Int) {
        Prefs.ytSpeedPct = pct
        player?.setPlaybackSpeed(if (live) 1f else pct / 100f)
        updateButtons()
    }

    private fun speedMenu() {
        if (live) { toast("Transmisja na żywo – prędkość 1.0×"); return }
        val cur = Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100
        Dialogs.menu(this, "Prędkość odtwarzania", SPEEDS.map { pct ->
            ((if (pct == cur) "✔  " else "     ") + speedLabel(pct) + if (pct == 100) "  (normalna)" else "") to { setSpeed(pct) }
        })
    }

    private fun updateButtons() {
        speedBtn.text = "⏩ " + speedLabel(if (live) 100 else Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100)
        eqBtn.text = "🎚 " + if (Prefs.ytEqPreset == YtEq.FLAT) "Dźwięk" else YtEq.PRESETS.getOrElse(Prefs.ytEqPreset) { "Dźwięk" }
        queueBtn.text = "📋 Następne" + pl.iptv.tv.data.StubeQueue.cached.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
    }

    // ---------------- korektor ----------------

    private fun attachEq(sessionId: Int) {
        eq?.release()
        eq = YtEqSession.create(sessionId).also { it.apply() }
    }

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

    /** Opcje odtwarzania (MENU na pilocie): prędkość, dźwięk, rozdziały, SponsorBlock. */
    private fun optionsMenu() {
        Dialogs.menu(this, "Opcje odtwarzania", buildList {
            add("⏩ Prędkość: " + speedLabel(if (live) 100 else Prefs.ytSpeedPct) to { speedMenu() })
            add("🎚 Dźwięk: " + YtEq.PRESETS.getOrElse(Prefs.ytEqPreset) { "Płaski" } to { eqPanel(); Unit })
            if (chapters.isNotEmpty()) add("☰ Rozdziały (${chapters.size})" to { chaptersMenu() })
            add("📋 Propozycje i kolejka" to { openPanel(TAB_RELATED) })
            add("📺 Wygaszacz teraz" to { showAmbient() })
            add("⏭ SponsorBlock: " + (if (Prefs.sbEnabled) "włączony" else "wyłączony") to {
                Prefs.sbEnabled = !Prefs.sbEnabled
                if (!Prefs.sbEnabled) segments = emptyList()
                toast("SponsorBlock " + if (Prefs.sbEnabled) "włączony (od następnego filmu)" else "wyłączony")
            })
        })
    }

    // ---------------- koniec filmu, kolejka, panel boczny ----------------

    /**
     * Koniec filmu: następna pozycja kolejki (film lub kanał TV – Ustawienia: autoodtwarzanie), a bez kolejki – panel
     * „Propozycje” (jak ekran końcowy YouTube); bez propozycji – zamknięcie.
     */
    private fun onEnded() {
        lifecycleScope.launch {
            val next = if (Prefs.queueAutoplay) pl.iptv.tv.data.StubeQueue.pop(skipRef = videoId) else null
            if (next != null) { QueueUi.play(this@YtPlayerActivity, next); finish(); return@launch }
            if (related.isNotEmpty() && !isFinishing) { endedPanel = true; openPanel(TAB_RELATED) } else finish()
        }
    }

    /** Panel otwarty po końcu filmu – jego zamknięcie zamyka odtwarzacz. */
    private var endedPanel = false
    var panel: SidePanel? = null; private set

    /** Panel boczny: „Propozycje” (podobne filmy) i „Kolejka” (filmy STube i kanały TV). */
    fun openPanel(tab: Int = TAB_RELATED) {
        val p = panel ?: SidePanel(this, listOf(
            SidePanel.Tab("▶ Propozycje", "Brak propozycji dla tego filmu") { related.map { v -> QueueUi.videoEntry(this, v, { panel?.reload(); updateButtons() }) { playNow(it) } } },
            SidePanel.Tab("☰ Kolejka", "Kolejka jest pusta – długie OK na filmie (STube, propozycje) lub kanale (TV) → „Dodaj do kolejki”") {
                QueueUi.entries(this, { panel?.reload(); updateButtons() }) { q -> lifecycleScope.launch { pl.iptv.tv.data.StubeQueue.remove(q.id); QueueUi.play(this@YtPlayerActivity, q); finish() } }
            },
        )) { if (endedPanel) finish() }.also { panel = it }
        playerView.hideController()
        p.open(tab)
    }

    /** Film z propozycji / pilota teraz – w miejsce bieżącego (pozycja zapisana w historii). */
    fun playNow(v: pl.iptv.tv.data.YtVideo) {
        saveProgress()
        lifecycleScope.launch { pl.iptv.tv.data.StubeQueue.removeRef(pl.iptv.tv.data.QueueItem.KIND_YT, v.id) }
        startActivity(intent(this, v)); finish()
    }

    // ---------------- wygaszacz (tryb ambient) ----------------

    private var lastInput = android.os.SystemClock.uptimeMillis()
    private var ambient: View? = null
    val ambientShown get() = ambient != null

    /** Ekran nie gaśnie tylko w trakcie odtwarzania – w pauzie system może włączyć wygaszacz / uśpić TV (oszczędzanie energii). */
    private fun keepScreen() {
        if (player?.playWhenReady == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Po [Prefs.ytAmbientMin] min pauzy bez pilota: czarny ekran z zegarem, tytułem i podpowiedzią (przyciemnione, przesuwane
     * co minutę – bez wypalania matrycy OLED). Dowolny klawisz wraca do odtwarzacza.
     */
    fun showAmbient() {
        if (ambient != null || isFinishing) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true; setOnClickListener { hideAmbient() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0.55f; gravity = Gravity.CENTER_HORIZONTAL }
        val clockView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 64f; tag = "clock" }
        col.addView(clockView)
        col.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; text = title.ifBlank { "STube" }; maxLines = 2; gravity = Gravity.CENTER; maxWidth = Ui.dp(640) })
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
            "next" -> { ended = false; saveProgress(); onEnded() }
            "stop" -> finish()
            else -> return false
        }
        playerView.showController()
        return true
    }

    /** Stan dla telefonu. */
    fun remoteStatus(): org.json.JSONObject = org.json.JSONObject().apply {
        val p = player
        put("id", videoId); put("title", title.ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() })
        put("playing", p?.isPlaying == true); put("position", p?.currentPosition ?: 0)
        put("duration", p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0); put("live", live)
        put("chapter", chapterIndex.takeIf { it >= 0 }?.let { chapters[it].title } ?: "")
    }

    private fun onControllerVisibility(v: Int) {
        optionsBar.visibility = v
        updateChapterLabel()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** Pozycja do historii oglądania (pasek na kafelku STube, wznawianie); transmisje na żywo pomijane. */
    fun saveProgress(): pl.iptv.tv.data.WatchProgress? {
        val p = player ?: return null
        if (live || p.isCurrentMediaItemLive) return null
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return StubeHistory.savePosition(video, if (ended) dur else p.currentPosition, dur)
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) saveProgress()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    /** Dekodery jak w odtwarzaczu kanałów (ustawienia silnika, zapasowy dekoder, tryb zgodności). */
    private fun renderers() = androidx.media3.exoplayer.DefaultRenderersFactory(applicationContext).apply {
        setMediaCodecSelector(Playback.codecSelector(software = Playback.softwareDecoding))
        setEnableDecoderFallback(Prefs.decoderFallback || Playback.softwareDecoding)
        setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { handler.removeCallbacks(startTimeout); status.visibility = View.GONE }
            if (state == Player.STATE_ENDED) { ended = true; saveProgress(); onEnded() }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keepScreen()
            if (isPlaying) hideAmbient()
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEq(audioSessionId) }
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("YtPlayer", "błąd ${error.errorCodeName}", error)
            val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            // adres strumienia wygasł lub został odrzucony (403/410) – raz ponowna ekstrakcja od tej samej chwili
            if (!reExtracted && (http == 403 || http == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
                reExtracted = true; resumeAt = player?.currentPosition ?: resumeAt
                release(); start(); return
            }
            showFallback(errorMessage(error, http))
        }
    }

    private val startTimeout = Runnable {
        if (player?.playbackState != Player.STATE_READY) showFallback("Strumień nie odpowiada (YouTube mógł zablokować odtwarzanie w tej aplikacji)")
    }

    /** SponsorBlock: segment pod bieżącą pozycją → przeskok na jego koniec i komunikat. */
    private val sbTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying || p.playbackState == Player.STATE_BUFFERING) skipIfNeeded(p.currentPosition)
            if (playerView.isControllerFullyVisible) updateChapterLabel()
            handler.postDelayed(this, 500)
        }
    }

    /** Pominięcie segmentu w [posMs] (wywoływane co 0,5 s i w testach). */
    fun skipIfNeeded(posMs: Long): Boolean {
        val p = player ?: return false
        val seg = SponsorBlock.at(segments, posMs) ?: return false
        val dur = p.duration
        // segment do samego końca filmu – koniec odtwarzania zamiast przeskoku w pustkę
        if (dur != C.TIME_UNSET && seg.endMs >= dur - 1000) { lastSkipped = seg; ended = true; saveProgress(); p.playWhenReady = false; onEnded(); return true }
        p.seekTo(seg.endMs)
        lastSkipped = seg
        skipLabel.text = "⏭  Pominięto: ${SponsorBlock.label(seg.category)}"
        skipLabel.visibility = View.VISIBLE
        handler.removeCallbacks(hideSkip); handler.postDelayed(hideSkip, 3000)
        return true
    }
    private val hideSkip = Runnable { skipLabel.visibility = View.GONE }

    fun errorMessage(e: PlaybackException, http: Int = 0): String = when {
        http == 403 || http == 410 -> "YouTube odrzucił strumień (HTTP $http)"
        http == 429 -> "YouTube ograniczył odtwarzanie (zbyt wiele zapytań)"
        e.errorCode in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) ->
            "Urządzenie nie obsługuje formatu tego filmu"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Brak połączenia z serwerem wideo YouTube"
        else -> "Błąd odtwarzania (${e.errorCodeName})"
    }

    /** Okno awaryjne – czytelne na TV, fokus od razu na „Odtwórz w SmartTube”. */
    fun showFallback(reason: String) {
        if (isFinishing || isDestroyed || fallbackDialog?.isShowing == true) return
        handler.removeCallbacks(startTimeout)
        resumeAt = player?.currentPosition?.takeIf { it > 0 } ?: resumeAt
        release()
        status.visibility = View.GONE
        // kolejność przycisków na TV (układ pionowy): SmartTube → YouTube → Zamknij
        val d = Ui.dialog(this).setTitle("Nie można odtworzyć filmu")
            .setMessage("$reason\n\nWybierz inny sposób odtwarzania:")
            .setPositiveButton("Odtwórz w SmartTube") { _, _ ->
                if (SmartTubeIntegrationHelper.play(this, videoUrl) { finish() }) finish()
            }
            .setNegativeButton("Odtwórz w YouTube") { _, _ -> SmartTubeIntegrationHelper.playInYouTube(this, videoUrl); finish() }
            .setNeutralButton("Zamknij") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        fallbackDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------------- pilot ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (event.action == KeyEvent.ACTION_DOWN) lastInput = android.os.SystemClock.uptimeMillis()
        // wygaszacz: dowolny klawisz wraca do odtwarzacza (bez wykonania akcji)
        if (ambient != null) { if (event.action == KeyEvent.ACTION_UP) hideAmbient(); return true }
        // panel boczny prowadzi nawigację sam (fokus nie wychodzi do kontrolek pod spodem)
        if (panel?.onKey(event) == true) return true
        if (panel?.isOpen == true) return super.dispatchKeyEvent(event)
        // MENU na pilocie – opcje odtwarzania (prędkość, dźwięk, rozdziały, SponsorBlock); bez MENU: ▲ z paska do przycisków u góry
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) optionsMenu(); return true }
        if (event.action == KeyEvent.ACTION_DOWN && p != null && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                // pasek ukryty: ◄► przewija o 10 s (jak w aplikacjach YouTube na TV), OK – pauza/wznowienie
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!p.isCurrentMediaItemLive) p.seekTo(p.currentPosition + SEEK_STEP_MS); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { p.playWhenReady = !p.playWhenReady; playerView.showController(); return true }
                // ▲▼ przy ukrytym pasku: poprzedni / następny rozdział (bez rozdziałów – pokazanie paska jak dotąd)
                KeyEvent.KEYCODE_DPAD_UP -> if (chapters.isNotEmpty()) { jumpChapter(next = false); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (chapters.isNotEmpty()) { if (!jumpChapter(next = true)) playerView.showController(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible && p != null) {
            playerView.hideController(); return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun release() {
        // HOME, okno awaryjne, ponowna ekstrakcja, zamknięcie – ostatnia pozycja do historii (tylko gdy film już grał)
        if (player?.playbackState == Player.STATE_READY || player?.playbackState == Player.STATE_BUFFERING || ended) saveProgress()
        handler.removeCallbacks(startTimeout); handler.removeCallbacks(sbTick); handler.removeCallbacks(saveTick)
        eq?.release(); eq = null
        player?.let { it.removeListener(listener); runCatching { it.release() } }
        player = null
        playerView.player = null
    }

    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null); loadJob?.cancel()
        panel?.close()
        if (active?.get() === this) active = null
    }
}

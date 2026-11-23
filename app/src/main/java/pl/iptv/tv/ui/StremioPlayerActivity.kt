package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioResume
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StremioSubs
import pl.iptv.tv.data.StremioSubtitle
import pl.iptv.tv.data.StubeHistory

/**
 * Odtwarzacz Stremio (Media3): napisy z wtyczek (OpenSubtitles…) i wbudowane, opóźnienie napisów ±, rozmiar i kolor napisów,
 * ścieżki dźwięku, dopasowanie obrazu (Dopasuj / Wypełnij / Powiększ / Do szerokości), „Następny odcinek” (źródło z tej samej
 * wtyczki – grupa bingeGroup jak w Stremio), wznawianie i zapis postępu (Room + biblioteka konta Stremio).
 *
 * Pilot: OK – pasek, ◄ ► – przewijanie 10 s, ▲ – przyciski u góry, MENU – opcje, WSTECZ – ukrycie paska / wyjście.
 */
@OptIn(UnstableApi::class)
class StremioPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TYPE = "type"; const val EXTRA_META = "meta"; const val EXTRA_NAME = "name"; const val EXTRA_POSTER = "poster"
        const val EXTRA_VIDEO = "video"; const val EXTRA_LABEL = "label"; const val EXTRA_STREAM = "stream"
        val SUB_SIZES = listOf(0.75f to "Mały", 1f to "Normalny", 1.25f to "Duży", 1.5f to "Bardzo duży", 2f to "Ogromny")
        val SUB_COLORS = listOf(Color.WHITE to "Biały", Color.parseColor("#FFEB3B") to "Żółty", Color.parseColor("#69F0AE") to "Zielony",
            Color.parseColor("#80DEEA") to "Błękitny", Color.parseColor("#FFAB91") to "Brzoskwiniowy")
        val ASPECTS = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Dopasuj (oryginalne proporcje)", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Wypełnij ekran (rozciągnij)",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Powiększ (przytnij krawędzie)", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Dopasuj do szerokości")
        /** Krok opóźnienia napisów. */
        const val DELAY_STEP = 250
        /** (7.15) Próg procentowy (było: 4 min przed końcem) albo początek napisów końcowych z rozdziałów – [StremioResume.nextDue]. */
        fun nextDue(durationMs: Long, positionMs: Long, creditsStartMs: Long = -1): Boolean {
            if (durationMs == C.TIME_UNSET) return false
            return StremioResume.nextDue(durationMs, positionMs, creditsStartMs)
        }

        /** (7.8) Limit czasu wczytywania źródeł przy wznawianiu / „Oglądaj dalej”. */
        const val RESOLVE_TIMEOUT_MS = 25_000L

        /**
         * (7.8) „Wznów” / „Oglądaj dalej”: bez ekranu pośredniego – odtwarzacz sam wybiera odcinek ([StremioResume.target]: następny,
         * gdy dla zapisanego odcinka pojawiło się już okienko „Następny odcinek”, inaczej ten sam od zapisanej pozycji) i źródło
         * (to samo co poprzednio, polska wersja na górze).
         */
        fun resumeIntent(c: Context, p: StremioProgress) =
            Intent(c, StremioPlayerActivity::class.java).putExtra(EXTRA_TYPE, if (p.type == "series") "series" else p.type).putExtra(EXTRA_META, p.metaId)
                .putExtra(EXTRA_NAME, p.name).putExtra(EXTRA_POSTER, p.poster)

        fun intent(c: Context, type: String, metaId: String, name: String, poster: String, videoId: String, label: String, s: StremioStream) =
            Intent(c, StremioPlayerActivity::class.java).putExtra(EXTRA_TYPE, type).putExtra(EXTRA_META, metaId).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_POSTER, poster).putExtra(EXTRA_VIDEO, videoId).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_STREAM, s.toJson().toString())
    }

    private val handler = Handler(Looper.getMainLooper())
    var player: ExoPlayer? = null; private set
    lateinit var playerView: PlayerView; private set
    lateinit var subView: SubtitleView; private set
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var nextCard: TextView
    private lateinit var nextBtn: TextView

    private val type get() = intent.getStringExtra(EXTRA_TYPE).orEmpty()
    private val metaId get() = intent.getStringExtra(EXTRA_META).orEmpty()
    var videoId = ""; private set
    var label = ""; private set
    lateinit var stream: StremioStream; private set

    /** Napisy z wtyczek dla bieżącego wideo. */
    var addonSubs: List<StremioSubtitle> = emptyList(); private set
    /** Wybrane napisy zewnętrzne (wczytane linie) – null: wbudowane albo brak. */
    var externalSub: StremioSubtitle? = null; private set
    private var lines: List<StremioSubs.Line> = emptyList()
    var subDelayMs: Int = Prefs.stremioSubDelay; private set
    var subsOff = false; private set
    private var resumeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // jedno odtwarzanie naraz: telewizja i film STube w tle zatrzymane
        Playback.suspendForApp(); runCatching { YtEngine.stop() }
        val json = intent.getStringExtra(EXTRA_STREAM)
        videoId = intent.getStringExtra(EXTRA_VIDEO).orEmpty(); label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        setContentView(build())
        if (json.isNullOrBlank()) resolveAndStart()
        else { stream = StremioStream.fromJson(JSONObject(json)); startChecked(resume = true) }
    }

    // ---------------- (7.8) wznowienie bez ekranu źródeł ----------------

    private lateinit var loadingView: TextView

    /**
     * Odcinek ([StremioResume.target]) i źródło ([StremioStreams.pickResume]) wybrane tutaj – zapytania do wtyczek na wątkach roboczych
     * z limitem czasu; bez pasującego źródła – ekran szczegółów tego odcinka (wybór ręczny).
     */
    private fun resolveAndStart() {
        loadingView.visibility = View.VISIBLE
        lifecycleScope.launch {
            val p = StremioStore.progressOf(metaId)
            val explicit = intent.getStringExtra(EXTRA_VIDEO).orEmpty()
            val meta = if (type == "series") StremioMetas.get(type, metaId) else null
            val target = if (explicit.isNotBlank()) StremioResume.Target(explicit, label, false)
                else if (p != null) StremioResume.target(p, meta) else StremioResume.Target(metaId, "", false)
            val vid = target.videoId.ifBlank { metaId }
            val streamType = if (type == "series") "series" else type
            val addons = StremioStore.enabledAddons().filter { it.supports("stream", streamType, vid) }
            var all = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { StremioApi.collectStreams(addons, streamType, vid) }.orEmpty()
            var pick = StremioStreams.pickResume(p?.addonUrl.orEmpty(), p?.bingeGroup.orEmpty(), p?.quality.orEmpty(), all)
            if (pick == null && all.any { it.kind == StremioStream.Kind.TORRENT }) {
                val server = pl.iptv.tv.data.TorrentStreams.find()
                if (server != null) { all = all.map { if (it.kind == StremioStream.Kind.TORRENT) pl.iptv.tv.data.TorrentStreams.asHttp(it, server) else it }
                    pick = StremioStreams.pickResume(p?.addonUrl.orEmpty(), p?.bingeGroup.orEmpty(), p?.quality.orEmpty(), all) }
            }
            if (isFinishing) return@launch
            if (pick == null) {
                I18n.makeToast(this@StremioPlayerActivity, "Nie znaleziono źródła do odtworzenia – wybierz je ręcznie", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@StremioPlayerActivity, StremioDetailActivity::class.java).putExtra(StremioDetailActivity.EXTRA_TYPE, type)
                    .putExtra(StremioDetailActivity.EXTRA_ID, metaId).putExtra(StremioDetailActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME))
                    .putExtra(StremioDetailActivity.EXTRA_POSTER, intent.getStringExtra(EXTRA_POSTER)).putExtra(StremioDetailActivity.EXTRA_VIDEO, vid))
                finish(); return@launch
            }
            stream = pick; videoId = vid; label = target.label
            intent.putExtra(EXTRA_STREAM, pick.toJson().toString()).putExtra(EXTRA_VIDEO, vid).putExtra(EXTRA_LABEL, target.label)
            loadingView.visibility = View.GONE
            startChecked(resume = true)
        }
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // (7.5) własny pasek sterowania ([controls]) zamiast kontrolera PlayerView: przewijanie ◄► bez menu, OK – menu z fokusem na ⏯
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = ASPECTS.getOrElse(Prefs.stremioAspect) { ASPECTS[0] }.first
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            // dotknięcie obrazu (telefon): pasek sterowania pokaż / schowaj
            setOnClickListener { setBar(if (bar == Bar.FULL) Bar.HIDDEN else Bar.FULL) }
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        // fokus „spoczynkowy” przy schowanym pasku – klawisze nie trafiają do niewidocznych przycisków
        root.isFocusable = true; root.isFocusableInTouchMode = false; rootView = root
        // napisy rysowane przez nas (opóźnienie, rozmiar, kolor – także dla napisów z wtyczek)
        playerView.subtitleView?.visibility = View.GONE
        subView = SubtitleView(this).apply { setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false) }
        root.addView(subView, FrameLayout.LayoutParams(-1, -1))
        applySubStyle()
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setShadowLayer(6f, 0f, 1f, Color.BLACK)
            // (7.8) tytuł nie wchodzi pod kartę „Następny odcinek” (prawy górny róg)
            setPadding(Ui.dp(40), Ui.dp(22), Ui.dp(if (resources.configuration.screenWidthDp < 600) 40 else 420), 0); maxLines = 2; visibility = View.GONE
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(buildControls(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // (7.8) karta „Następny odcinek” w prawym górnym rogu – nie zasłania obrazu ani sterowania na dole
        val narrow = resources.configuration.screenWidthDp < 600
        nextCard = TextView(this).apply {
            tag = "item"; isFocusable = true; isClickable = true; textSize = if (narrow) 14f else 16f; setTextColor(Color.WHITE); visibility = View.GONE
            maxWidth = Ui.dp(if (narrow) 240 else 360); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12))
            background = GradientDrawable().apply { setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xE0101010.toInt())); cornerRadius = Ui.dp(10).toFloat(); setStroke(Ui.dp(2), Ui.accent) }
            setOnClickListener { playNext() }
        }
        root.addView(nextCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(if (narrow) 56 else 24), Ui.dp(if (narrow) 16 else 40), 0) })
        // (7.8) wczytywanie źródła przy wznawianiu bez ekranu źródeł
        loadingView = TextView(this).apply {
            text = "Wczytywanie źródła…"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; visibility = View.GONE
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        root.addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    // ---------------- (7.5) pasek sterowania ----------------

    /** Pasek: schowany, sam postęp z przesunięciem (◄► bez menu), pełny (przyciski, fokus na ⏯). */
    enum class Bar { HIDDEN, SEEK, FULL }
    var bar = Bar.HIDDEN; private set
    private lateinit var rootView: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var buttonsRow: LinearLayout
    private lateinit var timeBar: androidx.media3.ui.DefaultTimeBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    lateinit var seekBadge: TextView; private set
    lateinit var playBtn: TextView; private set
    private lateinit var pipBtn: TextView
    /** Przesunięcie z bieżącej serii ◄► (wskaźnik „+30 s”). */
    var seekAccum = 0L; private set
    private val hideBar = Runnable { setBar(Bar.HIDDEN) }

    private fun buildControls(): View {
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(Ui.dp(36), Ui.dp(28), Ui.dp(36), Ui.dp(18)); clipChildren = false; clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
        }
        // wskaźnik przesunięcia (◄► bez menu)
        seekBadge = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); visibility = View.GONE
            setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(6))
            background = GradientDrawable().apply { cornerRadius = Ui.dp(18).toFloat(); setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xCC000000.toInt())); setStroke(Ui.dp(2), PlayerChrome.STREMIO) }
        }
        controls.addView(seekBadge, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(10) })
        // pasek postępu z czasem
        val progressRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun timeText() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        posText = timeText(); durText = timeText()
        timeBar = androidx.media3.ui.DefaultTimeBar(this).apply {
            setPlayedColor(PlayerChrome.STREMIO); setScrubberColor(PlayerChrome.STREMIO); setBufferedColor(0x66FFFFFF); setUnplayedColor(0x33FFFFFF)
            setKeyTimeIncrement(TIMEBAR_STEP_MS); isFocusable = true   // (7.11) ◄► na samym pasku postępu: 1,5 min
            addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                override fun onScrubStart(t: androidx.media3.ui.TimeBar, position: Long) { handler.removeCallbacks(hideBar) }
                override fun onScrubMove(t: androidx.media3.ui.TimeBar, position: Long) { posText.text = StremioUi.fmt(position) }
                override fun onScrubStop(t: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) { if (!canceled) player?.seekTo(position); scheduleHide() }
            })
        }
        progressRow.addView(posText)
        progressRow.addView(timeBar, LinearLayout.LayoutParams(0, Ui.dp(26), 1f).apply { marginStart = Ui.dp(12); marginEnd = Ui.dp(12) })
        progressRow.addView(durText)
        controls.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        // przyciski: w lewym dolnym rogu ⏪ ⏯ ⏩ (i „Następny odcinek” pod koniec), z prawej – ikony funkcji
        buttonsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        fun left(icon: String, t: String, a: () -> Unit) = iconButton(icon, t) { a(); scheduleHide() }
            .also { buttonsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) }) }
        left(PlayerIcon.REW, "Do tyłu 10 s") { seekBy(-SEEK_STEP_MS, badge = false) }
        playBtn = left(PlayerIcon.PAUSE, "Pauza") { togglePlay() }
        left(PlayerIcon.FWD, "Do przodu 10 s") { seekBy(SEEK_STEP_MS, badge = false) }
        nextBtn = PlayerChrome.button(this, PlayerIcon.NEXT, "Następny odcinek") { playNext() }.apply { visibility = View.GONE }
        buttonsRow.addView(nextBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6) })
        buttonsRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        // ikony funkcji (7.4): zaznaczona wysuwa swój podpis
        fun btn(icon: String, t: String, a: () -> Unit) = iconButton(icon, t) { a(); scheduleHide() }
            .also { topBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) }) }
        btn(PlayerIcon.QUEUE, if (type == "series") "Odcinki serialu" else "Inne części filmu") { openEpisodesPanel() }
        btn(PlayerIcon.CC, "Napisy") { subtitlesMenu() }
        btn(PlayerIcon.DELAY, "Opóźnienie napisów") { delayMenu() }
        btn(PlayerIcon.STYLE, "Styl napisów") { styleMenu() }
        btn(PlayerIcon.AUDIO, "Ścieżka dźwięku") { audioMenu() }
        btn(PlayerIcon.ASPECT, "Dopasowanie obrazu") { aspectMenu() }
        pipBtn = btn(PlayerIcon.PIP, "Okno") { enterPip() }.apply { visibility = if (pipSupported && !Tutorial.isTv(this@StremioPlayerActivity)) View.VISIBLE else View.GONE }
        buttonsRow.addView(topBar)
        // (7.7) bez zmiany szerokości ikon (podpis nad ikoną) – rząd nie wypycha ikon poza ekran
        controls.addView(captionBox, LinearLayout.LayoutParams(-1, Ui.dp(30)).apply { topMargin = Ui.dp(6) })
        controls.addView(buttonsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
        return controls
    }

    /** (7.7) OK wciśnięte przy schowanym pasku; [okLong] – przytrzymanie już obsłużone (menu). */
    private var okDown = false
    private var okLong = false
    private val holdOk = Runnable { if (okDown && !okLong && !isFinishing) { okLong = true; optionsMenu() } }
    /** Testy: menu opcji po przytrzymaniu OK. */
    val holdOkHandled get() = okLong

    // ---------------- (7.7) podpis zaznaczonej ikony ----------------

    /** Pas nad ikonami z podpisem zaznaczonej ikony (ikony mają stałą szerokość – nic się nie przesuwa). */
    private val captionBox by lazy { FrameLayout(this).apply { clipChildren = false; clipToPadding = false; addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)) } }
    /** Podpis zaznaczonej ikony (testy). */
    val caption: TextView by lazy {
        TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; alpha = 0f
            setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
            background = GradientDrawable().apply { cornerRadius = Ui.dp(12).toFloat(); setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xE6000000.toInt())); setStroke(Ui.dp(1), PlayerChrome.STREMIO) }
        }
    }

    /** Okrągła ikona (stała szerokość); zaznaczenie pokazuje nad nią podpis [label]. */
    private val ICON_TAG = "icon"
    private fun iconButton(icon: String, label: String, onClick: () -> Unit) = PlayerChrome.button(this, icon, "", onClick).apply {
        setTag(R.id.row_key, ICON_TAG)
        contentDescription = label
        val base = onFocusChangeListener
        setOnFocusChangeListener { v, has ->
            base?.onFocusChange(v, has)
            // (7.7) podpis tylko nad ikoną z fokusem (fokus na innym elemencie – np. „Następny odcinek” – chowa go)
            if (has) showCaption(v) else v.post { if (controls.findFocus()?.getTag(R.id.row_key) != ICON_TAG) hideCaption() }
        }
    }

    private fun showCaption(v: View) {
        caption.text = v.contentDescription
        caption.post {
            val box = IntArray(2).also { captionBox.getLocationInWindow(it) }
            val at = IntArray(2).also { v.getLocationInWindow(it) }
            // szerokość z pomiaru nowego tekstu (caption.width bywa jeszcze sprzed zmiany napisu)
            caption.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); val cw = caption.measuredWidth
            val x = at[0] - box[0] + v.width / 2 - cw / 2
            caption.translationX = x.coerceIn(0, (captionBox.width - cw).coerceAtLeast(0)).toFloat()
            caption.animate().alpha(1f).setDuration(110).start()
        }
    }

    private fun hideCaption() { caption.animate().alpha(0f).setDuration(110).start() }

    /** Krok przewijania ◄► i ⏪ ⏩. */
    val SEEK_STEP_MS = 10_000L
    /** (7.11) Krok ◄► po zaznaczeniu paska postępu filmu (przyciski i ◄► bez paska – [SEEK_STEP_MS]). */
    val TIMEBAR_STEP_MS = 90_000L

    fun setBar(m: Bar) {
        handler.removeCallbacks(hideBar)
        val was = bar; bar = m
        if (m == Bar.HIDDEN) {
            seekAccum = 0
            // płynne zniknięcie; fokus wraca na tło (OK znowu otwiera menu z fokusem na ⏯)
            if (controls.findFocus() != null) rootView.requestFocus()
            controls.animate().alpha(0f).setDuration(220).withEndAction { if (bar == Bar.HIDDEN) controls.visibility = View.GONE }.start()
            titleView.visibility = View.GONE
            return
        }
        controls.animate().cancel(); controls.alpha = 1f; controls.visibility = View.VISIBLE
        buttonsRow.visibility = if (m == Bar.FULL) View.VISIBLE else View.GONE
        captionBox.visibility = buttonsRow.visibility                // (7.7) bez pustego pasa nad paskiem postępu przy przewijaniu
        seekBadge.visibility = if (m == Bar.SEEK) View.VISIBLE else View.GONE
        titleView.visibility = if (m == Bar.FULL) View.VISIBLE else View.GONE
        if (m == Bar.SEEK && controls.findFocus() != null) rootView.requestFocus()
        if (m == Bar.FULL) { updateControls(); updateNextButtonVisibility(); if (was != Bar.FULL) playBtn.requestFocus() }
        scheduleHide()
    }

    /** Pasek chowa się sam: po serii ◄► po 1,5 s, pełny – po 5 s (w pauzie zostaje). */
    private fun scheduleHide() {
        handler.removeCallbacks(hideBar)
        when (bar) {
            Bar.SEEK -> handler.postDelayed(hideBar, SEEK_BAR_MS)
            Bar.FULL -> if (player?.isPlaying == true) handler.postDelayed(hideBar, FULL_BAR_MS)
            Bar.HIDDEN -> {}
        }
    }
    val SEEK_BAR_MS = 1_500L
    val FULL_BAR_MS = 5_000L

    /** Przewinięcie o [delta]; [badge] – wskaźnik serii (◄► bez menu) z łączną wartością. */
    fun seekBy(delta: Long, badge: Boolean = true) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val to = (p.currentPosition + delta).coerceAtLeast(0).let { if (dur != null) it.coerceAtMost(dur - 1000) else it }
        p.seekTo(to)
        if (badge) {
            seekAccum += delta
            seekBadge.text = (if (seekAccum < 0) "⏪  −" else "⏩  +") + seekLabel(kotlin.math.abs(seekAccum))
            if (bar != Bar.FULL) setBar(Bar.SEEK) else scheduleHide()
        }
        updateControls(to)
    }

    private fun seekLabel(ms: Long) = if (ms >= 60_000) "${ms / 60_000} min ${(ms % 60_000) / 1000} s".replace(" 0 s", "") else "${ms / 1000} s"

    fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
        updateControls(); scheduleHide()
    }

    /** Postęp, czasy i ikona ⏯ (co 100 ms przy widocznym pasku). */
    private fun updateControls(position: Long = -1) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val pos = if (position >= 0) position else p.currentPosition
        timeBar.setDuration(dur); timeBar.setPosition(pos); timeBar.setBufferedPosition(p.bufferedPosition)
        posText.text = StremioUi.fmt(pos); durText.text = if (dur > 0) StremioUi.fmt(dur) else "--:--"
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        val icon = if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY
        if (playBtn.tag != icon) {
            playBtn.tag = icon
            playBtn.setCompoundDrawables(PlayerIcon(icon, PlayerChrome.colors()).apply { setBounds(0, 0, Ui.dp(20), Ui.dp(20)) }, null, null, null)
            playBtn.contentDescription = if (playing) "Pauza" else "Odtwórz"
            if (playBtn.isFocused) showCaption(playBtn)
        }
    }

    // ---------------- (7.5) obraz w obrazie (telefon / tablet) ----------------

    val pipSupported: Boolean get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private val isInPip get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode

    fun enterPip(): Boolean {
        if (!pipSupported) { I18n.makeToast(this, "Obraz w obrazie niedostępny na tym urządzeniu", Toast.LENGTH_SHORT).show(); return false }
        setBar(Bar.HIDDEN)
        val ratio = player?.videoSize?.let { v -> if (v.width > 0 && v.height > 0) android.util.Rational(v.width, v.height) else null } ?: android.util.Rational(16, 9)
        val safe = ratio.toFloat().let { if (it > 2.39f) android.util.Rational(239, 100) else if (it < 1 / 2.39f) android.util.Rational(100, 239) else ratio }
        return runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(safe).build()) }.getOrDefault(false)
    }

    /** Wyjście z aplikacji w trakcie filmu (telefon) – od razu pływające okno. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipSupported && !Tutorial.isTv(this) && !isInPip && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) { setBar(Bar.HIDDEN); nextCard.visibility = View.GONE }
        // okno zamknięte (✕) – koniec odtwarzania (pozycja zapisana); rozwinięte – zwykły ekran
        else if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { saveProgress(push = true); finish() }
    }

    // ---------------- odtwarzanie ----------------

    /** Etap dekodowania ([StremioDecoders]): po błędzie dekodera jedna próba w trybie programowym, potem okno z wyborem. */
    var decoderStage = StremioDecoders.Stage.NORMAL; private set
    /** Opis błędu pokazany w oknie awaryjnym (testy); null – brak. */
    var failure: String? = null; private set
    private var failureDialog: androidx.appcompat.app.AlertDialog? = null
    private var audioWarned = false

    // ---------------- (7.18) źródła pobierane przez debrid ----------------

    private var debridJob: kotlinx.coroutines.Job? = null
    private var debridView: TextView? = null
    /** Odtwarzacz czeka, aż debrid pobierze plik (testy). */
    var debridWaiting = false; private set

    /**
     * Start źródła z kontrolą debrid: adres rozwiązywany przez wtyczkę ([DebridResolve.needsCheck]) jest najpierw sprawdzany –
     * gdy prowadzi do planszy „pobieranie”, odtwarzacz pokazuje komunikat i sprawdza co [DebridResolve.POLL_MS], a gdy plik jest
     * gotowy – startuje sam. Debrid (np. RealDebrid) udostępnia plik dopiero po pobraniu całości, więc nie da się ruszyć od części.
     */
    private fun startChecked(resume: Boolean) {
        debridJob?.cancel()
        if (!pl.iptv.tv.data.DebridResolve.needsCheck(stream.url)) { hideDebrid(); start(resume); return }
        val s = stream
        debridJob = lifecycleScope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            var first = true
            while (true) {
                val st = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pl.iptv.tv.data.DebridResolve.check(s.url, s.headers) }
                if (stream !== s) return@launch
                val waited = android.os.SystemClock.uptimeMillis() - t0
                when (st) {
                    pl.iptv.tv.data.DebridResolve.State.READY -> {
                        hideDebrid()
                        if (!first) I18n.makeToast(this@StremioPlayerActivity, "Plik gotowy – start odtwarzania", Toast.LENGTH_SHORT).show()
                        start(resume); return@launch
                    }
                    pl.iptv.tv.data.DebridResolve.State.FAILED -> { hideDebrid(); showFailure("Serwis debrid nie mógł pobrać tego źródła."); return@launch }
                    pl.iptv.tv.data.DebridResolve.State.DOWNLOADING -> {
                        if (waited > pl.iptv.tv.data.DebridResolve.MAX_WAIT_MS) { hideDebrid(); showFailure("Debrid wciąż pobiera to źródło – spróbuj innego albo wróć później."); return@launch }
                        showDebrid(waited)
                    }
                }
                first = false
                kotlinx.coroutines.delay(pl.iptv.tv.data.DebridResolve.POLL_MS)
            }
        }
    }

    private fun showDebrid(waitedMs: Long) {
        debridWaiting = true
        val v = debridView ?: TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER; setLineSpacing(0f, 1.25f)
            background = pl.iptv.tv.ui.theme.ObsidianViews.dialogPanel(); setPadding(Ui.dp(32), Ui.dp(24), Ui.dp(32), Ui.dp(24))
            (window.decorView as? android.view.ViewGroup)?.addView(this, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        }.also { debridView = it }
        val min = waitedMs / 60_000
        v.text = "⬇ Debrid pobiera ten tytuł na swój serwer\n" +
            "Odtwarzanie ruszy samo, gdy plik będzie gotowy (sprawdzam co ${pl.iptv.tv.data.DebridResolve.POLL_MS / 1000} s" +
            (if (min > 0) ", czekasz $min min" else "") + ").\nWSTECZ – wybierz inne źródło"
        v.visibility = View.VISIBLE
    }

    private fun hideDebrid() { debridWaiting = false; debridView?.visibility = View.GONE }

    /** [atMs] ≥ 0 – start od tej pozycji (ponowienie po błędzie dekodera), bez wznawiania z historii. */
    private fun start(resume: Boolean, atMs: Long = -1) {
        releasePlayer()
        titleView.text = intent.getStringExtra(EXTRA_NAME).orEmpty() + (if (label.isNotBlank()) "  ·  $label" else "")
        externalSub = null; lines = emptyList(); addonSubs = stream.subtitles; subView.setCues(emptyList())
        audioWarned = false; langApplied = false; langManual = false; polishAudio = null; subsOff = false
        langPlan = null; addonSubsLoaded = false; englishDone = false
        val http = OkHttpDataSource.Factory(Repository.http).setDefaultRequestProperties(stream.headers)
        val p = StremioDecoders.player(this, decoderStage, DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)), forceVideo)
        player = p
        playerView.player = p
        val item = MediaItem.Builder().setUri(Uri.parse(stream.url)).apply {
            val u = stream.url.substringBefore('?').lowercase()
            when { u.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8); u.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD) }
        }.build()
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { if (externalSub == null) showEmbeddedCues(cueGroup.cues) }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded() }
            override fun onPlayerError(error: PlaybackException) {
                // poza wywołaniem słuchacza – odtwarzacz jest zwalniany i budowany od nowa
                val pos = p.currentPosition
                handler.post { if (player === p) onError(error, pos) }
            }
            override fun onMetadata(metadata: androidx.media3.common.Metadata) = onChapterMetadata(metadata)
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { if (checkVideo(tracks)) return; warnUnsupportedAudio(tracks); autoLanguage(p, tracks) }
        })
        if (atMs > 0) p.seekTo(atMs)
        p.prepare(); p.playWhenReady = true
        lifecycleScope.launch {
            val saved = StremioStore.progressOf(metaId)?.takeIf { it.videoId == videoId }
            if (saved?.pastNextThreshold == true) nextReached = true         // (7.8) okienko dla tego odcinka już się pojawiło (trwale w Room)
            if (resume && atMs < 0) saved?.takeIf { it.positionMs > 10_000 && !it.finished }?.let {
                resumeMs = it.positionMs; p.seekTo(it.positionMs)
                I18n.makeToast(this@StremioPlayerActivity, "Wznowiono od ${StremioUi.fmt(it.positionMs)}", Toast.LENGTH_SHORT).show()
            }
            loadAddonSubs()
            updateNextButton()
        }
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private var saveCounter = 0
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (externalSub != null && !subsOff) subView.setCues(StremioSubs.at(lines, p.currentPosition, subDelayMs.toLong())?.let { listOf(Cue.Builder().setText(it).build()) } ?: emptyList())
            if (++saveCounter % 100 == 0) saveProgress()        // co 10 s
            if (p.isPlaying) { playedMs += 100; if (playedMs >= StremioStore.WATCHED_AFTER_MS) markCurrentWatched() }   // (7.11) 3 min oglądania = obejrzany
            if (bar != Bar.HIDDEN) updateControls()
            updateNextCard()
            handler.postDelayed(this, 100)
        }
    }

    /** Napisy wbudowane z opóźnieniem: dodatnie – wyświetlane później (ujemne: od razu – wbudowanych nie da się pokazać wcześniej). */
    private fun showEmbeddedCues(cues: List<Cue>) {
        if (subsOff) { subView.setCues(emptyList()); return }
        if (subDelayMs > 0) handler.postDelayed({ if (externalSub == null) subView.setCues(cues) }, subDelayMs.toLong()) else subView.setCues(cues)
    }

    // ---------------- obraz 4K / HDR: wymuszenie ścieżki obrazu ----------------

    /** Obraz wymuszony ponad deklarowane możliwości dekodera ([StremioDecoders.trackSelector]). */
    var forceVideo = false; private set

    /**
     * Film ma obraz, ale odtwarzacz wybrał tylko dźwięk (telewizor zaniża możliwości dekodera dla 4K HEVC 10-bit / DV / HDR):
     * ponowny start od tej samej pozycji z wymuszonym obrazem. Gdy i wtedy obrazu nie ma – okno z wyborem (inne źródło,
     * zewnętrzny odtwarzacz). true – obsłużone (start od nowa).
     */
    private fun checkVideo(tracks: androidx.media3.common.Tracks): Boolean {
        if (!StremioDecoders.needsForcedVideo(tracks)) return false
        val pos = player?.currentPosition ?: 0L
        if (!forceVideo) {
            forceVideo = true
            I18n.makeToast(this, "Telewizor zgłasza brak obsługi tego obrazu (np. 4K HDR) – próbuję mimo to", Toast.LENGTH_LONG).show()
            handler.post { start(resume = false, atMs = pos.coerceAtLeast(resumeMs)) }
            return true
        }
        val fmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.mediaTrackGroup?.getFormat(0)
        handler.post { showFailure("Telewizor nie potrafi wyświetlić obrazu tego źródła" + (fmt?.let { " (${StremioDecoders.codecName(it.sampleMimeType ?: "")} ${it.width}×${it.height})" } ?: "") +
            ". Wybierz źródło w niższej rozdzielczości albo zewnętrzny odtwarzacz.") }
        return true
    }

    // ---------------- błędy dekodera: tryb programowy, potem wybór ----------------

    /** Błąd odtwarzania: dekoder – jedna próba z dekodowaniem programowym od tej samej pozycji; inaczej okno z wyborem. */
    fun onError(e: PlaybackException, positionMs: Long) {
        val next = StremioDecoders.nextStage(decoderStage, e.errorCode)
        if (next != null) {
            decoderStage = next
            I18n.makeToast(this, "Dekoder sprzętowy odrzucił strumień – przełączam na dekodowanie programowe", Toast.LENGTH_LONG).show()
            start(resume = false, atMs = positionMs.coerceAtLeast(resumeMs))
            return
        }
        showFailure(StremioDecoders.describe(e))
    }

    /** Okno na TV: przerwane odtwarzanie, inne źródło (lista źródeł wtyczek) albo zewnętrzny odtwarzacz. */
    private fun showFailure(reason: String) {
        val pos = player?.currentPosition?.coerceAtLeast(resumeMs) ?: resumeMs
        saveProgress(push = true)
        player?.stop()
        setBar(Bar.HIDDEN)
        failure = reason
        failureDialog?.dismiss()
        failureDialog = Ui.dialog(this).setTitle("Nie można odtworzyć tego źródła")
            .setMessage("$reason\n\nWybierz inne źródło (np. z innym dźwiękiem lub w 1080p) albo otwórz je w zewnętrznym odtwarzaczu (VLC, Just Player).")
            .setPositiveButton("Wybierz inne źródło") { _, _ -> finish() }
            .setNegativeButton("Odtwórz w zewnętrznym odtwarzaczu") { _, _ -> openExternal(pos) }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Ten sam strumień w zewnętrznej aplikacji (okno wyboru); brak aplikacji – komunikat i okno zostaje. */
    fun openExternal(positionMs: Long) {
        val view = StremioDecoders.externalView(stream.url, intent.getStringExtra(EXTRA_NAME).orEmpty() +
            (if (label.isNotBlank()) " · $label" else ""), positionMs, stream.headers)
        if (packageManager.queryIntentActivities(view, 0).isEmpty()) {
            I18n.makeToast(this, "Brak zewnętrznego odtwarzacza – zainstaluj np. VLC lub Just Player", Toast.LENGTH_LONG).show()
            failure?.let { showFailure(it) }
            return
        }
        startActivity(Intent.createChooser(view, "Odtwórz w…"))
        finish()
    }

    /** Ścieżki dźwięku są, ale żadnej nie da się odtworzyć (np. tylko DTS-HD bez dekodera i passthrough) – obraz gra bez dźwięku. */
    private fun warnUnsupportedAudio(tracks: androidx.media3.common.Tracks) {
        if (audioWarned) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { it.isSelected } || audio.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return
        audioWarned = true
        val codec = audio.firstNotNullOfOrNull { it.mediaTrackGroup.getFormat(0).sampleMimeType }?.let { StremioDecoders.codecName(it) } ?: "tego formatu"
        I18n.makeToast(this, "Telewizor nie obsługuje dźwięku $codec – odtwarzanie bez dźwięku. Wybierz inne źródło, aby mieć dźwięk.", Toast.LENGTH_LONG).show()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tick)
        player?.let { saveProgress(); it.release() }
        player = null
    }

    // (7.5) w oknie PiP onPause nie zatrzymuje filmu (gra dalej w pływającym oknie)
    override fun onPause() { super.onPause(); saveProgress(push = true); if (!isInPip) player?.pause() }
    override fun onResume() { super.onResume(); player?.let { if (it.playbackState != Player.STATE_ENDED) it.play() } }
    override fun onDestroy() { failureDialog?.dismiss(); releasePlayer(); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    /** (7.18) Źródło, za które wtyczka dostała już punkt w tym seansie. */
    private var creditedKey: String? = null

    fun saveProgress(push: Boolean = false) {
        val p = player ?: return
        val pos = p.currentPosition; val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        if (pos < 5_000) return
        if (!::stream.isInitialized) return
        // (7.18) obejrzane ≥ 10 % – źródło działa: wtyczka trafia wyżej na liście źródeł (raz na źródło w tym seansie)
        if (creditedKey != stream.key && pl.iptv.tv.data.StremioAddonScores.watched(pos, dur)) {
            creditedKey = stream.key; pl.iptv.tv.data.StremioAddonScores.credit(stream.addonUrl)
        }
        val row = StremioProgress(metaId, type, intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_POSTER).orEmpty(),
            videoId, label.substringBefore(" – "), pos, dur, System.currentTimeMillis(),
            nextShown = nextReached, addonUrl = stream.addonUrl, bingeGroup = stream.bingeGroup, quality = stream.quality)
        StubeHistory.scope.launch { runCatching { StremioStore.saveProgress(row, push) } }
    }

    // ---------------- napisy ----------------

    /** Plan języka zastosowany dla bieżącego źródła / użytkownik sam wybrał napisy (bez automatu) / jest polski dźwięk. */
    private var langApplied = false
    private var langManual = false
    var polishAudio: Boolean? = null; private set

    /**
     * Automatycznie (raz na źródło, do pierwszego ręcznego wyboru napisów): jest polska ścieżka dźwiękowa – włączona i napisy
     * wyłączone; brak – polskie napisy (wbudowane w plik albo z wtyczek, np. OpenSubtitles).
     */
    private fun autoLanguage(p: androidx.media3.common.Player, tracks: androidx.media3.common.Tracks) {
        if (langApplied || langManual || player !== p) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        fun info(g: androidx.media3.common.Tracks.Group) = g.mediaTrackGroup.getFormat(0).let { it.language to it.label }
        val plan = StremioDecoders.languagePlan(audio.map(::info), text.map(::info), stream.claimsPolishAudio)
        langApplied = true; polishAudio = plan.polishAudio
        // źródło obiecywało polskiego lektora, a ścieżki są w innych językach – zapamiętane (lista źródeł bez fałszywego „Lektor PL”)
        if (plan.noPolishAudio) StremioStreams.markNoPolishAudio(stream)
        val b = p.trackSelectionParameters.buildUpon()
        plan.audio?.let { i -> audio[i].takeIf { !it.isSelected && it.isTrackSupported(0) }?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) } }
        if (plan.subsOff) {
            subsOff = true; externalSub = null; lines = emptyList(); subView.setCues(emptyList())
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else plan.embeddedText?.let { i ->
            subsOff = false; externalSub = null; lines = emptyList()
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(text[i].mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
        if (plan.addonSubs && externalSub == null) addonSubs.firstOrNull { StremioDecoders.isPolishSubtitle(it.lang) }?.let { selectExternal(it, quiet = true) }
        langPlan = plan; englishFallback()
    }

    // ---------------- (7.8) angielski, gdy nie ma polskiego dźwięku ani polskich napisów ----------------

    private var langPlan: StremioDecoders.LangPlan? = null
    private var addonSubsLoaded = false
    private var englishDone = false

    /**
     * Kolejność: polski dźwięk → polskie napisy (wbudowane, z wtyczek) → angielski dźwięk → angielskie napisy (wbudowane, z wtyczek).
     * Wywoływane, gdy znane są i ścieżki pliku, i napisy z wtyczek; po ręcznym wyborze napisów nie zmienia nic.
     */
    private fun englishFallback() {
        val plan = langPlan ?: return
        val p = player ?: return
        if (englishDone || langManual || !addonSubsLoaded || !plan.addonSubs) return
        if (addonSubs.any { StremioDecoders.isPolishSubtitle(it.lang) }) return
        englishDone = true
        val audio = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val b = p.trackSelectionParameters.buildUpon()
        val ea = plan.englishAudio; val et = plan.englishText
        when {
            ea != null -> {
                audio.getOrNull(ea)?.takeIf { !it.isSelected && it.isTrackSupported(0) }?.let { b.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
                subsOff = true; externalSub = null; lines = emptyList(); subView.setCues(emptyList()); b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            et != null && text.getOrNull(et) != null -> {
                subsOff = false; externalSub = null; lines = emptyList()
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(text[et].mediaTrackGroup, 0))
            }
            else -> addonSubs.firstOrNull { StremioDecoders.isEnglishSubtitle(it.lang) }?.let { selectExternal(it, quiet = true) }
        }
        p.trackSelectionParameters = b.build()
    }

    private suspend fun loadAddonSubs() {
        val addons = StremioStore.enabledAddons().filter { it.supports("subtitles", type, videoId) }
        // (7.8) wtyczki napisów równolegle, z limitem czasu (jedna wolna nie wstrzymuje reszty)
        val vid = videoId; val fn = stream.filename; val vs = stream.videoSize
        val got = kotlinx.coroutines.coroutineScope {
            addons.map { a -> async(Dispatchers.Default) {
                try { kotlinx.coroutines.withTimeoutOrNull(StremioApi.STREAMS_TIMEOUT_MS) { StremioApi.subtitles(a, type, vid, fn, vs) }.orEmpty() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            } }.map { it.await() }.flatten()
        }
        addonSubs = (stream.subtitles + got).distinctBy { it.url }
        // (7.4) zawsze polskie (wytyczne): od razu, gdy plik nie ma własnych polskich napisów ani polskiego dźwięku. Dawniej prefiks
        // „po” z zapamiętanego języka trafiał też w portugalskie („por”), a wbudowane „pl” nie były rozpoznawane
        if (externalSub == null && !subsOff && polishAudio != true && !langManual) addonSubs.firstOrNull { StremioDecoders.isPolishSubtitle(it.lang) }?.let { s ->
            val embedded = player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.mediaTrackGroup.getFormat(0).let { f -> StremioDecoders.isPolish(f.language, f.label) } } == true
            if (!embedded) selectExternal(s, quiet = true)
        }
        addonSubsLoaded = true; englishFallback()
    }

    fun selectExternal(s: StremioSubtitle, quiet: Boolean = false) {
        lifecycleScope.launch {
            runCatching { StremioSubs.parse(StremioApi.subtitleText(s.url)) }.onSuccess { l ->
                lines = l; externalSub = s; subsOff = false
                player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                // ręczny wybór zapamiętany tylko do kolejności w menu; automat i tak zawsze szuka polskich
                if (!quiet) Prefs.stremioSubLang = s.lang.ifBlank { Prefs.stremioSubLang }
                if (!quiet) I18n.makeToast(this@StremioPlayerActivity, "Napisy: ${StremioSubs.langName(s.lang)} (${s.addon})", Toast.LENGTH_SHORT).show()
            }.onFailure { if (!quiet) I18n.makeToast(this@StremioPlayerActivity, "Nie udało się wczytać napisów: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun subtitlesMenu() {
        val p = player ?: return
        val embedded = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (subsOff) "✔ " else "") + "Wyłączone" to { langManual = true; subsOff = true; externalSub = null; subView.setCues(emptyList())
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        embedded.forEachIndexed { i, g ->
            val f = g.mediaTrackGroup.getFormat(0)
            val sel = externalSub == null && !subsOff && g.isSelected
            items += (if (sel) "✔ " else "") + "Wbudowane: " + (f.label ?: f.language?.let { StremioSubs.langName(it) } ?: "ścieżka ${i + 1}") to {
                langManual = true; subsOff = false; externalSub = null; lines = emptyList()
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            }
        }
        addonSubs.sortedWith(compareBy({ !StremioDecoders.isPolishSubtitle(it.lang) }, { it.lang != Prefs.stremioSubLang }, { it.lang })).forEach { s ->
            items += (if (externalSub?.url == s.url) "✔ " else "") + "${StremioSubs.langName(s.lang)}  ·  ${s.addon}" to { langManual = true; selectExternal(s) }
        }
        if (addonSubs.isEmpty() && embedded.isEmpty()) items += "Brak napisów (włącz wtyczkę napisów, np. OpenSubtitles)" to {}
        Dialogs.menu(this, "Napisy", items)
    }

    /** Przesuwa napisy o [deltaMs] (dodatnie – później). */
    fun changeDelay(deltaMs: Int) {
        subDelayMs = (subDelayMs + deltaMs).coerceIn(-30_000, 30_000); Prefs.stremioSubDelay = subDelayMs
    }

    private fun delayLabel() = String.format(java.util.Locale.US, "%+.2f s", subDelayMs / 1000f)

    private fun delayMenu() = Dialogs.menuLive(this, "Opóźnienie napisów") {
        listOf("Teraz: ${delayLabel()}  (+ napisy później, − wcześniej)" to {},
            "+ ${DELAY_STEP} ms" to { changeDelay(DELAY_STEP) }, "− ${DELAY_STEP} ms" to { changeDelay(-DELAY_STEP) },
            "+ 1 s" to { changeDelay(1000) }, "− 1 s" to { changeDelay(-1000) }, "Wyzeruj" to { changeDelay(-subDelayMs) })
    }

    fun applySubStyle() {
        val color = SUB_COLORS.getOrElse(Prefs.stremioSubColor) { SUB_COLORS[0] }.first
        subView.setStyle(CaptionStyleCompat(color, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        subView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUB_SIZES.getOrElse(Prefs.stremioSubSize) { SUB_SIZES[1] }.first)
    }

    private fun styleMenu() = Dialogs.menu(this, "Styl napisów", listOf(
        "Rozmiar: ${SUB_SIZES.getOrElse(Prefs.stremioSubSize) { SUB_SIZES[1] }.second}" to {
            Dialogs.choiceLive(this, "Rozmiar napisów", SUB_SIZES.map { it.second }, Prefs.stremioSubSize) { Prefs.stremioSubSize = it; applySubStyle() }; Unit },
        "Kolor: ${SUB_COLORS.getOrElse(Prefs.stremioSubColor) { SUB_COLORS[0] }.second}" to {
            Dialogs.choiceLive(this, "Kolor napisów", SUB_COLORS.map { it.second }, Prefs.stremioSubColor) { Prefs.stremioSubColor = it; applySubStyle() }; Unit },
    ))

    // ---------------- dźwięk i obraz ----------------

    private fun audioMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) { I18n.makeToast(this, "Brak informacji o ścieżkach dźwięku", Toast.LENGTH_SHORT).show(); return }
        Dialogs.menu(this, "Ścieżka dźwięku", groups.mapIndexed { i, g ->
            val f = g.mediaTrackGroup.getFormat(0)
            val name = listOfNotNull(f.label ?: f.language?.let { StremioSubs.langName(it) } ?: "Ścieżka ${i + 1}",
                f.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "stereo" else "$it kan." },
                f.sampleMimeType?.substringAfter('/')?.uppercase()).joinToString(" · ")
            (if (g.isSelected) "✔ " else "") + name to {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            }
        })
    }

    fun setAspect(i: Int) { Prefs.stremioAspect = i; playerView.resizeMode = ASPECTS[i].first }

    private fun aspectMenu() = Dialogs.choiceLive(this, "Dopasowanie obrazu", ASPECTS.map { it.second }, Prefs.stremioAspect.coerceIn(ASPECTS.indices)) { setAspect(it) }

    // ---------------- następny odcinek ----------------

    private var nextVideo: pl.iptv.tv.data.StremioVideo? = null

    private suspend fun updateNextButton() {
        if (type != "series") return
        nextVideo = StremioMetas.get(type, metaId)?.next(videoId)
        nextBtn.text = nextVideo?.let { "Następny odcinek · ${it.label.substringBefore(" – ")}" } ?: "Następny odcinek"
        nextCard.text = nextVideo?.let { "⏭  Następny odcinek: ${it.label}   (OK)" }.orEmpty()
        updateNextButtonVisibility()
    }

    /** (7.5) „Następny odcinek” dopiero pod koniec: znana długość, kolejny odcinek i ≤ [NEXT_EPISODE_WINDOW_MS] do końca. */
    val nextEpisodeDue: Boolean get() {
        val p = player ?: return false
        return nextVideo != null && nextDue(p.duration, p.currentPosition, creditsStartMs)
    }

    /** (7.15) Początek napisów końcowych z rozdziałów / metadanych (−1 – nieznany → próg procentowy). Zerowany z nowym odcinkiem. */
    var creditsStartMs = -1L; private set

    /** Rozdział o tytule napisów końcowych (ID3 CHAP i podobne metadane) ustawia [creditsStartMs]; uruchamiane na wątku odtwarzacza. */
    internal fun onChapterMetadata(m: androidx.media3.common.Metadata) {
        for (i in 0 until m.length()) {
            val e = m[i] as? androidx.media3.extractor.metadata.id3.ChapterFrame ?: continue
            val title = e.chapterId + " " + (0 until e.subFrameCount).joinToString(" ") { e.getSubFrame(it).toString() }
            if (StremioResume.isCreditsTitle(title) && e.startTimeMs > 0) creditsStartMs = e.startTimeMs.toLong()
        }
    }

    private fun updateNextButtonVisibility() {
        nextBtn.visibility = if (nextEpisodeDue && bar == Bar.FULL) View.VISIBLE else View.GONE
    }

    /** (7.8) Próg okienka „Następny odcinek” osiągnięty dla tego odcinka – zapisane trwale w Room ([StremioProgress.nextShown]). */
    var nextReached = false; private set
    /** Okienko zamknięte (WSTECZ) – nie pojawia się ponownie w tym odcinku. */
    private var nextDismissed = false

    private fun updateNextCard() {
        val due = nextEpisodeDue
        if (due && !nextReached) { nextReached = true; saveProgress() }
        val show = due && !nextDismissed
        updateNextButtonVisibility()
        if (show && nextCard.visibility != View.VISIBLE && !isInPip) { nextCard.visibility = View.VISIBLE; Ui.style(nextCard); if (bar != Bar.FULL) nextCard.requestFocus() }
        else if (!show && nextCard.visibility == View.VISIBLE) { if (nextCard.isFocused) rootView.requestFocus(); nextCard.visibility = View.GONE }
    }

    /** (7.8) Zamknięcie okienka bez przechodzenia do następnego odcinka (WSTECZ / ▲▼◄► z karty). */
    fun dismissNextCard() {
        nextDismissed = true
        if (nextCard.isFocused) rootView.requestFocus()
        nextCard.visibility = View.GONE
    }

    private fun onEnded() { saveProgress(push = true); if (nextVideo != null) playNext() else finish() }

    /** Następny odcinek: źródło z tej samej wtyczki ([StremioStreams.pickNext]); brak – ekran źródeł tego odcinka. */
    private var nextLoading = false

    fun playNext() {
        val n = nextVideo ?: return
        markCurrentWatched()                          // (7.11) przejście do następnego odcinka zalicza bieżący
        playEpisode(n)
    }

    /** Odcinek [n] (następny z okienka / wybrany z panelu odcinków): źródło z tej samej wtyczki, brak – ekran źródeł. */
    fun playEpisode(n: pl.iptv.tv.data.StremioVideo) {
        if (nextLoading) return                      // (7.8) wielokrotne OK nie uruchamia kilku zapytań
        nextLoading = true
        saveProgress(push = true)
        nextCard.text = "⏭  Wczytywanie: ${n.label}…"
        lifecycleScope.launch {
            val prev = stream
            // bieżące źródło P2P (przez serwer torrent): następny odcinek też z P2P przez ten sam serwer
            val server = if (prev.infoHash.isNotBlank()) pl.iptv.tv.data.TorrentStreams.find() else null
            // (7.8) najpierw ta sama wtyczka (jak w Stremio – grupa bingeGroup), dalej inne włączone wtyczki; poza głównym wątkiem, z limitem czasu
            val addons = StremioStore.enabledAddons().filter { it.supports("stream", type, n.id) }.sortedBy { it.transportUrl != prev.addonUrl }
            val all = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { StremioApi.collectStreams(addons, type, n.id) }.orEmpty()
                .map { if (server != null && it.kind == pl.iptv.tv.data.StremioStream.Kind.TORRENT) pl.iptv.tv.data.TorrentStreams.asHttp(it, server) else it }
            val pick = StremioStreams.pickResume(prev.addonUrl, prev.bingeGroup, prev.quality, all)
            nextLoading = false
            if (isFinishing) return@launch
            if (pick == null) {
                startActivity(Intent(this@StremioPlayerActivity, StremioDetailActivity::class.java).putExtra(StremioDetailActivity.EXTRA_TYPE, type)
                    .putExtra(StremioDetailActivity.EXTRA_ID, metaId).putExtra(StremioDetailActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME))
                    .putExtra(StremioDetailActivity.EXTRA_POSTER, intent.getStringExtra(EXTRA_POSTER)).putExtra(StremioDetailActivity.EXTRA_VIDEO, n.id))
                finish(); return@launch
            }
            stream = pick; videoId = n.id; label = n.label; nextCard.visibility = View.GONE
            nextReached = false; nextDismissed = false; creditsStartMs = -1      // nowy odcinek – próg okienka liczony od nowa
            playedMs = 0; watchedMarked = false; sidePanel?.close()
            decoderStage = StremioDecoders.Stage.NORMAL; failure = null; forceVideo = false   // nowe źródło – znowu od dekoderów sprzętowych
            intent.putExtra(EXTRA_STREAM, pick.toJson().toString()).putExtra(EXTRA_VIDEO, n.id).putExtra(EXTRA_LABEL, n.label)
            I18n.makeToast(this@StremioPlayerActivity, "Następny odcinek: ${n.label}", Toast.LENGTH_SHORT).show()
            startChecked(resume = false)
        }
    }


    // ---------------- (7.11) obejrzane i panel „Odcinki” / „Inne części” ----------------

    /** Czas faktycznego oglądania bieżącego odcinka (po [StremioStore.WATCHED_AFTER_MS] odcinek zalicza się jako obejrzany). */
    var playedMs = 0L; private set
    var watchedMarked = false; private set

    fun markCurrentWatched() {
        if (type != "series" || watchedMarked) return
        val v = StremioMetas.cached(type, metaId)?.videos?.firstOrNull { it.id == videoId } ?: return
        watchedMarked = true
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty(); val poster = intent.getStringExtra(EXTRA_POSTER).orEmpty()
        StubeHistory.scope.launch { runCatching { StremioStore.setWatched(metaId, v, type, name, poster, true) } }
    }

    var sidePanel: SidePanel? = null; private set

    /**
     * Panel boczny (jak kolejka w STube): serial – odcinki bieżącego sezonu ze znaczkiem „Obejrzany”, film – inne części serii
     * (sequele / prequele), jeśli istnieją. Wybór odcinka od razu go uruchamia, wybór części otwiera jej szczegóły.
     */
    fun openEpisodesPanel() {
        val series = type == "series"
        val tab = SidePanel.Tab(if (series) "☰ Odcinki" else "☰ Inne części", if (series) "Brak listy odcinków" else "Brak innych części tego filmu") {
            if (series) {
                val m = StremioMetas.get(type, metaId) ?: return@Tab emptyList()
                val cur = m.videos.firstOrNull { it.id == videoId }
                val done = StremioStore.watchedIds(metaId)
                m.episodes(cur?.season ?: m.seasons.firstOrNull() ?: 1).map { e ->
                    SidePanel.Entry(e.id, "${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }, listOfNotNull(e.released.ifBlank { null }, if (e.id == videoId) "▶ teraz" else null).joinToString(" · "),
                        e.thumbnail, badge = if (e.id in done) "✓ Obejrzany" else "") { if (e.id != videoId) playEpisode(e) else sidePanel?.close() }
                }
            } else {
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                StremioMetas.parts(name, metaId).map { p ->
                    SidePanel.Entry(p.id, p.name, p.releaseInfo, p.poster) { startActivity(StremioDetailActivity.intent(this@StremioPlayerActivity, p, null)); finish() }
                }
            }
        }
        val p = sidePanel ?: SidePanel(this, listOf(tab)).also { sidePanel = it }
        setBar(Bar.HIDDEN)
        p.open(0)
    }
    // ---------------- pilot ----------------

    private fun optionsMenu() = Dialogs.menu(this, "Opcje", buildList {
        add("💬 Napisy" to { subtitlesMenu() }); add("⏱ Opóźnienie napisów (${delayLabel()})" to { delayMenu(); Unit })
        add("Aa Styl napisów" to { styleMenu() }); add("🔊 Ścieżka dźwięku" to { audioMenu() }); add("⛶ Dopasowanie obrazu" to { aspectMenu(); Unit })
        if (nextVideo != null) add("⏭ Następny odcinek" to { playNext() })
    })

    /** WSTECZ schował pasek – zwolnienie klawisza nie zamyka odtwarzacza. */
    private var backHid = false

    /**
     * (7.5) Pilot:
     * - pasek schowany / sam postęp: ◄► – od razu przewinięcie o 10 s (sam pasek postępu ze wskaźnikiem łącznej wartości),
     *   OK – menu z fokusem na ⏯, ▲ – menu z fokusem na ikonach funkcji, ▼ – menu;
     * - menu: OK na ⏯ – pauza / wznowienie, ◄► – kolejne ikony paska, WSTECZ – chowa pasek;
     * - „następny” pilota – od razu następny odcinek; przyciski odtwarzania / przewijania pilota działają zawsze.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (sidePanel?.onKey(e) == true) return true
        if (sidePanel?.isOpen == true) return super.dispatchKeyEvent(e)
        val full = bar == Bar.FULL
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && e.repeatCount == 0 && nextVideo != null) { playNext(); return true }
            if (full) scheduleHide()
            // (7.8) kursor na karcie „Następny odcinek”: OK – odtwarza, WSTECZ – zamyka kartę, ◄► – przewijanie, ▼▲ – pasek sterowania
            if (nextCard.isFocused && !full) when (e.keyCode) {
                KeyEvent.KEYCODE_BACK -> { dismissNextCard(); backHid = true; return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    rootView.requestFocus(); seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_STEP_MS else SEEK_STEP_MS); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> { rootView.requestFocus(); setBar(Bar.FULL); return true }
            }
            when (e.keyCode) {
                KeyEvent.KEYCODE_MENU -> { optionsMenu(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); if (!full) setBar(Bar.SEEK); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_STEP_MS); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_STEP_MS); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!full && !nextCard.isFocused) {
                    seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_STEP_MS else SEEK_STEP_MS); return true
                }
                // (7.7) krótkie OK (po puszczeniu) – pasek z fokusem na ⏯; przytrzymanie – menu opcji (napisy, dźwięk, obraz…)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!full && !nextCard.isFocused) {
                    if (e.repeatCount == 0) { okDown = true; okLong = false; handler.removeCallbacks(holdOk); handler.postDelayed(holdOk, PlayerActivity.HOLD_OK_MS) }
                    else if (okDown && !okLong) { handler.removeCallbacks(holdOk); holdOk.run() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (!full && !nextCard.isFocused) { setBar(Bar.FULL); topBar.getChildAt(0)?.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!full && !nextCard.isFocused) { setBar(Bar.FULL); return true }
                KeyEvent.KEYCODE_BACK -> if (bar != Bar.HIDDEN) { setBar(Bar.HIDDEN); backHid = true; return true }
            }
        }
        if (e.action == KeyEvent.ACTION_UP && okDown && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            okDown = false; handler.removeCallbacks(holdOk)
            if (!okLong) setBar(Bar.FULL)
            return true
        }
        // zwolnienie OK, które otworzyło menu, nie „klika” ⏯ (przycisk nie dostał wciśnięcia)
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && backHid) { backHid = false; return true }
        return super.dispatchKeyEvent(e)
    }
}

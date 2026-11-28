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
import pl.iptv.tv.data.StremioMetaPreview
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
    /** (7.20) Siatka interfejsu TV 960 × 540 dp (1920 × 1080) niezależnie od DPI systemu – [pl.iptv.tv.ui.TvResolution]. */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(pl.iptv.tv.ui.TvResolution.wrap(newBase)) }
    override fun applyOverrideConfiguration(o: android.content.res.Configuration?) { super.applyOverrideConfiguration(pl.iptv.tv.ui.TvResolution.adjust(this, o)) }

    companion object {
        const val EXTRA_TYPE = "type"; const val EXTRA_META = "meta"; const val EXTRA_NAME = "name"; const val EXTRA_POSTER = "poster"
        const val EXTRA_VIDEO = "video"; const val EXTRA_LABEL = "label"; const val EXTRA_STREAM = "stream"
        /** (v2) Odstęp pływających pigułek i panelu od krawędzi; tag pigułki jakości (testy). */
        const val V2_MARGIN_DP = 20; const val V2_QUALITY_TAG = "stremio_v2_quality"
        /** (v2) Szkło pigułek tytułu i jakości: `#0C1426` 55 %. */
        const val TITLE_FILL = 0x8C0C1426.toInt()
        val SUB_SIZES = listOf(0.75f to "Mały", 1f to "Normalny", 1.25f to "Duży", 1.5f to "Bardzo duży", 2f to "Ogromny")
        val SUB_COLORS = listOf(Color.WHITE to "Biały", Color.parseColor("#FFEB3B") to "Żółty", Color.parseColor("#69F0AE") to "Zielony",
            Color.parseColor("#80DEEA") to "Błękitny", Color.parseColor("#FFAB91") to "Brzoskwiniowy")
        val ASPECTS = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Dopasuj (oryginalne proporcje)", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Wypełnij ekran (rozciągnij)",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Powiększ (przytnij krawędzie)", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Dopasuj do szerokości")
        /** Krok opóźnienia napisów. */
        const val DELAY_STEP = 250
        /** (7.23) Położenie napisów: odstęp od dołu obrazu (ułamek wysokości – SubtitleView.setBottomPaddingFraction). */
        val SUB_POSITIONS = listOf(0.03f to "Nisko", 0.08f to "Standardowo", 0.18f to "Wyżej", 0.32f to "Wysoko")
        /** (7.23) Proporcje w oknie „Obraz”: tryb dopasowania PlayerView i wymuszony stosunek (null – z materiału). */
        val PIC_ASPECTS = listOf(Triple("Dopasuj", AspectRatioFrameLayout.RESIZE_MODE_FIT, null as Float?),
            Triple("Wypełnij / Przytnij", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, null as Float?),
            Triple("Rozciągnij", AspectRatioFrameLayout.RESIZE_MODE_FILL, null as Float?),
            Triple("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f), Triple("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f))
        /** (7.23) Okno „Obraz” – nazwy sekcji (testy). */
        const val PIC_PROFILE = "Profil obrazu"; const val PIC_ASPECT = "Proporcje obrazu"
        const val PIC_BRIGHTNESS = "Jasność"; const val PIC_CONTRAST = "Kontrast"; const val PIC_SATURATION = "Nasycenie"; const val PIC_RESET = "Resetuj"
        const val SUB_DELAY = "Przesunięcie napisów"; const val SUB_COLOR = "Kolor napisów"; const val SUB_SIZE = "Rozmiar napisów"; const val SUB_POSITION = "Położenie napisów"
        /** (7.23) Podwójne stuknięcie: lewy / prawy pas tej szerokości obrazu przewija o [SEEK_STEP_MS]. */
        const val DOUBLE_TAP_SIDE = 0.4f
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
    private val TITLE_TAG = "stremio_title"; private val CENTER_TAG = "stremio_center_transport"

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
        pl.iptv.tv.ui.TvResolution.applyMetrics(resources); pl.iptv.tv.ui.TvResolution.applyMetrics(applicationContext.resources)   // (7.20) siatka TV 960 × 540 dp
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // jedno odtwarzanie naraz: telewizja i film STube w tle zatrzymane
        Playback.suspendForApp(); runCatching { YtEngine.stop() }
        val json = intent.getStringExtra(EXTRA_STREAM)
        videoId = intent.getStringExtra(EXTRA_VIDEO).orEmpty(); label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        setContentView(build())
        setupPortrait()
        loadRelated()
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
            // (7.23) film / odcinek z listy M3U – adres z katalogu listy (bez wtyczek i bez konta Stremio)
            if (pl.iptv.tv.data.M3uVod.isM3uId(metaId)) {
                val cat = pl.iptv.tv.data.VodLibrary.current()
                val vid = explicit.ifBlank { p?.videoId.orEmpty() }.ifBlank { cat.show(metaId)?.episodes?.firstOrNull()?.id ?: metaId }
                val ch = cat.channelFor(vid)
                if (isFinishing) return@launch
                if (ch == null) { I18n.makeToast(this@StremioPlayerActivity, "Tej pozycji nie ma już na liście", Toast.LENGTH_LONG).show(); finish(); return@launch }
                val ep = cat.episode(vid)
                stream = pl.iptv.tv.data.M3uVod.stream(ch); videoId = vid; label = ep?.let { pl.iptv.tv.data.M3uVod.episodeLabel(it) }.orEmpty()
                intent.putExtra(EXTRA_STREAM, stream.toJson().toString()).putExtra(EXTRA_VIDEO, vid).putExtra(EXTRA_LABEL, label)
                loadingView.visibility = View.GONE
                startChecked(resume = true)
                return@launch
            }
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
        // (7.23) telefon: TextureView – profil / jasność / kontrast / nasycenie nakładane na żywo ([PictureAdjust]); TV – SurfaceView (4K/HDR)
        // jak dotąd, chyba że zapisano korektę obrazu
        val texture = !Tutorial.isTv(this) || !PictureAdjust.current().isDefault
        playerView = (if (texture) layoutInflater.inflate(R.layout.stremio_player_texture, root, false) as PlayerView else PlayerView(this)).apply {
            useController = false
            resizeMode = ASPECTS.getOrElse(Prefs.stremioAspect) { ASPECTS[0] }.first
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            // dotknięcie obrazu (telefon): pasek sterowania pokaż / schowaj
            setOnClickListener { setBar(if (bar == Bar.FULL) Bar.HIDDEN else Bar.FULL) }
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        forcedRatio = PIC_ASPECTS.getOrNull(Prefs.stremioAspectRatio + 2)?.third?.takeIf { Prefs.stremioAspectRatio > 0 }
        applyPicture()
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
        titleView.gravity = Gravity.START; titleView.tag = TITLE_TAG
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        root.addView(buildControls(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { if (ModernV2.active) setMargins(Ui.dp(V2_MARGIN_DP), 0, Ui.dp(V2_MARGIN_DP), Ui.dp(V2_MARGIN_DP)) })
        if (ModernV2.active) setupV2Top(root)
        // (7.23) pion: ⏪ ⏯ ⏩ wyśrodkowane na ekranie (przenoszone z dolnego paska w [placeTransport])
        centerRow = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false; visibility = View.GONE; tag = CENTER_TAG }
        root.addView(centerRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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
    /** (7.20) „Inne odcinki / części” – widoczna tylko, gdy są inne odcinki serialu albo co najmniej 2 części filmu. */
    lateinit var partsBtn: TextView; private set
    /** (7.20) Części serii filmu / bajki (z bieżącym), chronologicznie; seriale – pusta. */
    var relatedParts: List<StremioMetaPreview> = emptyList(); private set
    /** Pierwsza widoczna ikona funkcji (ukryta „Inne części” – „Audio”). */
    fun firstIcon(): View? = (0 until topBar.childCount).map { topBar.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }
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
        if (ModernV2.active) {
            // (v2) czas „00:24:15 / 01:52:40”, bezpośrednio pod nim interaktywny pasek postępu (fokus D-pad, dotyk)
            val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            timeRow.addView(posText); timeRow.addView(TextView(this).apply { text = " / "; textSize = 14f; setTextColor(pl.iptv.tv.ui.theme.ObsidianGlass.TEXT2) }); timeRow.addView(durText)
            controls.addView(timeRow, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(4) })
            controls.addView(timeBar, LinearLayout.LayoutParams(-1, Ui.dp(28)))
        } else {
        progressRow.addView(posText)
        progressRow.addView(timeBar, LinearLayout.LayoutParams(0, Ui.dp(26), 1f).apply { marginStart = Ui.dp(12); marginEnd = Ui.dp(12) })
        progressRow.addView(durText)
        controls.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        }
        // przyciski: w lewym dolnym rogu ⏪ ⏯ ⏩ (i „Następny odcinek” pod koniec), z prawej – ikony funkcji
        buttonsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        fun left(icon: String, t: String, a: () -> Unit) = iconButton(icon, t) { a(); scheduleHide() }
            .also { buttonsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) }) }
        rewBtn = left(PlayerIcon.REW, "Do tyłu 10 s") { seekBy(-SEEK_STEP_MS, badge = false) }
        playBtn = left(PlayerIcon.PAUSE, "Pauza") { togglePlay() }
        fwdBtn = left(PlayerIcon.FWD, "Do przodu 10 s") { seekBy(SEEK_STEP_MS, badge = false) }
        nextBtn = PlayerChrome.button(this, PlayerIcon.NEXT, "Następny odcinek") { playNext() }.apply { visibility = View.GONE }
        buttonsRow.addView(nextBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6) })
        rowSpacer = View(this); buttonsRow.addView(rowSpacer, LinearLayout.LayoutParams(0, 1, 1f))
        topBar = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        // ikony funkcji (7.4): zaznaczona wysuwa swój podpis
        fun btn(icon: String, t: String, a: () -> Unit) = iconButton(icon, t) { a(); scheduleHide() }
            .also { topBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) }) }
        // (7.20) kolejność od lewej: Inne odcinki / części (tylko gdy są), Audio, Napisy, dalej pozostałe ikony.
        // Ukryta pierwsza ikona (GONE) nie zostawia dziury – fokus zaczyna się od „Audio” ([firstIcon]).
        partsBtn = btn(PlayerIcon.QUEUE, if (type == "series") "Inne odcinki" else "Inne części") { openEpisodesPanel() }.apply { visibility = View.GONE }
        btn(PlayerIcon.AUDIO, "Ścieżka dźwięku") { audioMenu() }
        btn(PlayerIcon.CC, "Napisy") { subtitlesMenu() }
        btn(PlayerIcon.DELAY, "Opóźnienie napisów") { delayMenu() }
        btn(PlayerIcon.STYLE, "Styl napisów") { styleMenu() }
        // (7.23) telefon (także poziomo) – okno „Obraz” (profil, proporcje, jasność, kontrast, nasycenie); TV – dawne okno proporcji
        btn(PlayerIcon.ASPECT, "Dopasowanie obrazu") { if (Tutorial.isTv(this@StremioPlayerActivity)) aspectMenu() else lastSheet = pictureSheet() }
        // (7.25) przesyłanie na urządzenie w pobliżu – ikona tylko, gdy są urządzenia
        CastHub.bind(btn(PlayerIcon.CAST, "Przesyłaj na urządzenie") { castClick() })
        pipBtn = btn(PlayerIcon.PIP, "Okno") { enterPip() }.apply { visibility = if (pipSupported && !Tutorial.isTv(this@StremioPlayerActivity)) View.VISIBLE else View.GONE }
        buttonsRow.addView(topBar)
        // (7.7) bez zmiany szerokości ikon (podpis nad ikoną) – rząd nie wypycha ikon poza ekran
        controls.addView(captionBox, LinearLayout.LayoutParams(-1, Ui.dp(30)).apply { topMargin = Ui.dp(6) })
        controls.addView(buttonsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
        if (ModernV2.active) styleV2Controls()
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
    // ---------------- (v2) Nowoczesny v2: HUD „Obsidian Glass” ----------------

    /** Ozdobna ramka postępu dolnego panelu (tło – bez fokusu) i pigułka jakości obrazu (null – inne motywy). */
    private var v2Border: StubeV2Controls.ProgressBorder? = null
    private var v2Quality: TextView? = null
    private val v2DialogHook: (androidx.appcompat.app.AlertDialog) -> Unit = { StremioV2Hud.decorate(it) }

    /**
     * Dolny pływający panel (szkło 24 dp, obramowanie wypełniane postępem), przyciski w tej samej kolejności co dotąd: ⏪ ⏯ ⏩,
     * „Następny odcinek”, Inne odcinki / części, Audio, Napisy, Opóźnienie, Styl, Obraz, Przesyłaj, PiP. ⏯ – niebieskie kółko,
     * pozostałe – kapsuły V2PillTab (fokus: ramka 2 dp `#38BDF8` + poświata). Telefon w pionie: rząd przycisków przewijany w poziomie.
     */
    private fun styleV2Controls() {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass; val low = pl.iptv.tv.ui.DevicePerformanceProfile.uiLow
        controls.background = StubeV2Controls.ProgressBorder().also { v2Border = it }
        controls.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8))      // smukły panel
        (timeBar.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = Ui.dp(20); timeBar.layoutParams = it }
        (captionBox.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = Ui.dp(24); it.topMargin = Ui.dp(2); captionBox.layoutParams = it }
        caption.textSize = 13f; caption.setPadding(Ui.dp(10), Ui.dp(2), Ui.dp(10), Ui.dp(2))
        for (b in listOf(rewBtn, playBtn, fwdBtn) + (0 until topBar.childCount).map { topBar.getChildAt(it) }) (b as? TextView)?.let { t -> val pd = Ui.dp(if (t === playBtn) 10 else 8); t.setPadding(pd, pd, pd, pd); t.minWidth = 0; t.minHeight = 0 }
        (buttonsRow.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = 0; buttonsRow.layoutParams = it }
        timeBar.setPlayedColor(G.FOCUS_RING); timeBar.setScrubberColor(G.FOCUS_RING)
        timeBar.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), G.focus(12, low)); addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0)) }
        for (b in listOf(rewBtn, fwdBtn) + (0 until topBar.childCount).map { topBar.getChildAt(it) }) b.background = G.pillTab(60, low)
        playBtn.background = G.button(true, 60, low)
        nextBtn.background = G.pillTab(40, low)
        val pill = G.glassDialog(16, G.OVERLAY_FILL)
        seekBadge.background = pill; caption.background = G.glassDialog(12, G.OVERLAY_FILL)
        // pion telefonu: cały rząd przycisków przewijany w poziomie (kolejność bez zmian)
        if (resources.configuration.screenWidthDp < 600) {
            val idx = controls.indexOfChild(buttonsRow)
            controls.removeView(buttonsRow)
            rowSpacer.visibility = View.GONE
            controls.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; addView(buttonsRow) },
                idx, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
        }
        // bezpieczne odstępy: pasek nawigacji systemu pod panelem
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(controls) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val m = Ui.dp(V2_MARGIN_DP) + b; if (lp.bottomMargin != m) { lp.bottomMargin = m; v.layoutParams = lp } }
            ins
        }
    }

    /** Górne pigułki: z lewej tytuł (z „S01E02”) w szkle, z prawej sama jakość obrazu; pod paskiem stanu (telefon). */
    private fun setupV2Top(root: FrameLayout) {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        titleView.apply {
            background = G.glassDialog(16, TITLE_FILL); setShadowLayer(0f, 0f, 0f, 0); textSize = 15f      // mniejsza, bardziej przezroczysta pigułka dopasowana do tytułu
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(Ui.dp(V2_MARGIN_DP), Ui.dp(V2_MARGIN_DP), 0, 0) }
            // telefon: przycisk powrotu w pigułce tytułu (dotyk; pilot – WSTECZ jak dotąd)
            if (!Tutorial.isTv(this@StremioPlayerActivity)) {
                setCompoundDrawables(PlayerIcon(PlayerIcon.BACK, android.content.res.ColorStateList.valueOf(Color.WHITE)).apply { setBounds(0, 0, Ui.dp(18), Ui.dp(18)) }, null, null, null)
                compoundDrawablePadding = Ui.dp(10); isClickable = true; setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
        }
        v2Quality = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); visibility = View.GONE; isFocusable = false
            setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7)); background = G.glassDialog(18, G.OVERLAY_FILL); tag = V2_QUALITY_TAG
        }
        root.addView(v2Quality, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(V2_MARGIN_DP), Ui.dp(V2_MARGIN_DP), 0) })
        v2Quality?.apply { textSize = 13f; setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5)); background = G.glassDialog(16, TITLE_FILL) }
        // wskaźnik przewijania nad panelem (nie powiększa panelu): szklana pigułka z ikoną ⏪ / ⏩ v2
        (seekBadge.parent as? android.view.ViewGroup)?.removeView(seekBadge)
        seekBadge.textSize = 15f; seekBadge.setPadding(Ui.dp(14), Ui.dp(5), Ui.dp(16), Ui.dp(5)); seekBadge.compoundDrawablePadding = Ui.dp(8)
        root.addView(seekBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(V2_MARGIN_DP + 72) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(titleView) { v, ins ->
            val t = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
            for (x in listOfNotNull(v, v2Quality)) (x.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val m = Ui.dp(V2_MARGIN_DP) + t; if (lp.topMargin != m) { lp.topMargin = m; x.layoutParams = lp } }
            ins
        }
    }

    /** Pigułka jakości razem z tytułem (pełny pasek); ukryta przy karcie „Następny odcinek” (ten sam róg). */
    private fun syncV2Top() {
        val q = v2Quality ?: return
        q.visibility = if (titleView.visibility == View.VISIBLE && q.text.isNotEmpty() && !(::nextCard.isInitialized && nextCard.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    }

    private fun iconButton(icon: String, label: String, onClick: () -> Unit) =PlayerChrome.button(this, icon, "", onClick).apply {
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
            // (v2) pigułka przewijania leży poza panelem (w root) – chowana osobno (dawniej zostawała na obrazie)
            seekBadge.visibility = View.GONE
            // płynne zniknięcie; fokus wraca na tło (OK znowu otwiera menu z fokusem na ⏯)
            if (controls.findFocus() != null) rootView.requestFocus()
            controls.animate().alpha(0f).setDuration(220).withEndAction { if (bar == Bar.HIDDEN) controls.visibility = View.GONE }.start()
            titleView.visibility = View.GONE; syncV2Top()
            if (::centerRow.isInitialized) centerRow.visibility = View.GONE
            return
        }
        controls.animate().cancel(); controls.alpha = 1f; controls.visibility = View.VISIBLE
        buttonsRow.visibility = if (m == Bar.FULL) View.VISIBLE else View.GONE
        captionBox.visibility = buttonsRow.visibility                // (7.7) bez pustego pasa nad paskiem postępu przy przewijaniu
        seekBadge.visibility = if (m == Bar.SEEK) View.VISIBLE else View.GONE
        titleView.visibility = if (m == Bar.FULL) View.VISIBLE else View.GONE; syncV2Top()
        if (::centerRow.isInitialized) centerRow.visibility = if (m == Bar.FULL && transportCentered) View.VISIBLE else View.GONE
        if (m == Bar.SEEK && controls.findFocus() != null) rootView.requestFocus()
        if (m == Bar.FULL && was != Bar.FULL) placeTitle(transportCentered)     // (7.23) odstęp paska stanu znany dopiero po wstawkach okna
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
            if (v2Border != null) {      // (v2) ikona ⏪ / ⏩ rysowana (bez emoji)
                seekBadge.text = (if (seekAccum < 0) "−" else "+") + seekLabel(kotlin.math.abs(seekAccum))
                seekBadge.setCompoundDrawables(PlayerIcon(if (seekAccum < 0) PlayerIcon.REW else PlayerIcon.FWD, android.content.res.ColorStateList.valueOf(pl.iptv.tv.ui.theme.ObsidianGlass.FOCUS_RING)).apply { setBounds(0, 0, Ui.dp(18), Ui.dp(18)) }, null, null, null)
            } else seekBadge.text = (if (seekAccum < 0) "⏪  −" else "⏩  +") + seekLabel(kotlin.math.abs(seekAccum))
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
        v2Border?.progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f      // (v2) ozdobna ramka postępu
        v2Quality?.let { q -> val t = StremioV2Hud.quality(p.videoFormat); if (q.text.toString() != t) { q.text = t; syncV2Top() } }
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
        updatePortraitBar()          // (7.22) PiP – bez paska ikon
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
        externalSub = null; lines = emptyList(); addonSubs = stream.subtitles.filter(::usableSub); subView.setCues(emptyList())
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
            // (7.20) interfejs w siatce 1080p, obraz 4K w pełnej rozdzielczości (bufor powierzchni wideo = rozmiar materiału)
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                TvResolution.nativeVideoBuffer(playerView.videoSurfaceView as? android.view.SurfaceView, v.width, v.height, TvResolution.displayHeightPx(this@StremioPlayerActivity), limited = false)
                // (7.23) PlayerView ustawia proporcje z materiału – wymuszone 16:9 / 4:3 z okna „Obraz” zostają
                if (forcedRatio != null) playerView.post { applyForcedRatio() }
            }
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
        // (7.28) trwa przesyłanie: nowy odcinek / źródło od razu na odbiorniku (ta sama sesja), lokalnie bez pobierania drugiego strumienia
        if (CastHub.isCasting(CastHub.Kind.STREMIO)) { p.playWhenReady = false; castMedia()?.let { CastHub.switchMedia(it) } }
        else { p.prepare(); p.playWhenReady = true }
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
    override fun onPause() { super.onPause(); if (Ui.dialogHook === v2DialogHook) Ui.dialogHook = null; saveProgress(push = true); if (!isInPip) player?.pause(); CastHub.detach(this) }
    override fun onResume() {
        super.onResume()
        if (ModernV2.active) Ui.dialogHook = v2DialogHook      // (v2) okna wyboru odtwarzacza: karty, ✓ przy aktywnej pozycji
        CastHub.attach(this, CastHub.Kind.STREMIO, castLocal)          // (7.25) urządzenia w pobliżu; trwa przesyłanie – lokalnie cisza
        player?.let { if (it.playbackState != Player.STATE_ENDED && !CastHub.isCasting(CastHub.Kind.STREMIO)) it.play() }
    }

    // ---------------- (7.25) przesyłanie (Cast) ----------------

    val castLocal = object : CastHub.Local {
        // (7.28) przesyłanie: lokalny odtwarzacz zatrzymany (bez pobierania strumienia w tle), wznowienie – prepare od pozycji z odbiornika
        override fun pause() { player?.let { p -> p.pause(); p.stop() } }
        override fun resume(positionMs: Long) { player?.let { p -> if (positionMs > 0) p.seekTo(positionMs); if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) p.play() } }
        // (7.28) pilot Cast: następny odcinek i odcinki sezonu w tej samej sesji
        override fun next(): Boolean { if (nextVideo == null) return false; playNext(); return true }
        override val itemsTitle: String get() = if (type == "series") "Odcinki" else "Inne części"
        override fun items(): List<CastHub.Item> {
            if (type != "series") return emptyList()
            val m = StremioMetas.cached(type, metaId) ?: return emptyList()
            val cur = m.videos.firstOrNull { it.id == videoId }
            return m.episodes(cur?.season ?: m.seasons.firstOrNull() ?: 1).map { e ->
                CastHub.Item("${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }, e.id == videoId) { if (e.id != videoId) playEpisode(e) }
            }
        }
    }

    /** Bieżące źródło: adres, nagłówki wtyczki, tytuł z etykietą odcinka, plakat, pozycja. */
    fun castMedia(): CastHub.Media? {
        if (!::stream.isInitialized || stream.url.isBlank()) return null
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        return CastHub.Media(stream.url, name.ifBlank { stream.title }, label, live = false, image = intent.getStringExtra(EXTRA_POSTER).orEmpty(),
            positionMs = player?.currentPosition ?: 0, headers = stream.headers)
    }

    private fun castClick() { CastHub.click(this, CastHub.Kind.STREMIO, castLocal) { castMedia() } }
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
        addonSubs = (stream.subtitles + got).distinctBy { it.url }.filter(::usableSub)
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

    /** (v2) Napisy z wtyczki nadające się do wyboru – bez komunikatów błędów wtyczek udających język („[❌] … Failed to …”). */
    fun usableSub(s: pl.iptv.tv.data.StremioSubtitle): Boolean {
        val l = s.lang.trim()
        return s.url.isNotBlank() && l.length <= SUB_LANG_MAX && "❌" !in l && !l.contains("fail", true) && !l.contains("error", true)
    }
    val SUB_LANG_MAX = 32

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
        subView.setBottomPaddingFraction(SUB_POSITIONS.getOrElse(Prefs.stremioSubPos) { SUB_POSITIONS[1] }.first)   // (7.23) położenie
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

    // ---------------- (7.23) okno „Obraz”: profil, proporcje, jasność, kontrast, nasycenie – na żywo ----------------

    /** Nakłada zapisaną korektę obrazu na powierzchnię (TextureView; bez restartu strumienia). */
    fun applyPicture(v: PictureAdjust.Values = PictureAdjust.current()) = PictureAdjust.apply(playerView.videoSurfaceView, v)

    private fun contentFrame() = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)

    private fun applyForcedRatio() {
        val r = forcedRatio ?: return
        contentFrame()?.setAspectRatio(r)
    }

    /** Bieżąca pozycja [PIC_ASPECTS]. */
    fun pictureAspectIndex(): Int = when {
        Prefs.stremioAspectRatio == 1 -> 3
        Prefs.stremioAspectRatio == 2 -> 4
        playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> 1
        playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL -> 2
        else -> 0
    }

    /** Proporcje [i] z [PIC_ASPECTS]: tryb dopasowania i wymuszony stosunek (16:9 / 4:3) – od razu, bez restartu. */
    fun setPictureAspect(i: Int) {
        val (_, mode, ratio) = PIC_ASPECTS[i.coerceIn(PIC_ASPECTS.indices)]
        Prefs.stremioAspect = ASPECTS.indexOfFirst { it.first == mode }.coerceAtLeast(0)
        Prefs.stremioAspectRatio = when (i) { 3 -> 1; 4 -> 2; else -> 0 }
        forcedRatio = ratio
        playerView.resizeMode = mode
        val f = contentFrame()
        if (ratio != null) f?.setAspectRatio(ratio)
        else player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { v -> f?.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height) }
    }

    /**
     * Okno „Obraz”: profil kolorów (Standardowy / Kino / Sport / Żywy), proporcje (Dopasuj, Wypełnij / Przytnij, Rozciągnij, 16:9,
     * 4:3), suwaki Jasności, Kontrastu i Nasycenia, „Resetuj” (wartości domyślne) oraz prędkość. Każda zmiana działa od razu.
     */
    fun pictureSheet(): PortraitSheet {
        val s = PortraitSheet(this, "Obraz")
        fun build() {
            s.clear()
            val v = PictureAdjust.current()
            fun set(n: PictureAdjust.Values) { PictureAdjust.save(n); applyPicture(n) }
            s.section(PIC_PROFILE)
            s.tiles(PictureAdjust.Profile.entries.map { it.label }, v.profile.ordinal, perRow = 2) { set(PictureAdjust.current().copy(profile = PictureAdjust.Profile.entries[it])); build() }
            s.section(PIC_ASPECT)
            s.tiles(PIC_ASPECTS.map { it.first }, pictureAspectIndex()) { setPictureAspect(it); build() }
            s.section(PIC_BRIGHTNESS)
            s.slider(PictureAdjust.BRIGHTNESS_MIN, PictureAdjust.BRIGHTNESS_MAX, 1, v.brightness, { b -> (if (b > 0) "+" else "") + b }) { set(PictureAdjust.current().copy(brightness = it)) }
            s.section(PIC_CONTRAST)
            s.slider(PictureAdjust.CONTRAST_MIN, PictureAdjust.CONTRAST_MAX, 1, v.contrast, { c -> "$c%" }) { set(PictureAdjust.current().copy(contrast = it)) }
            s.section(PIC_SATURATION)
            s.slider(PictureAdjust.SATURATION_MIN, PictureAdjust.SATURATION_MAX, 1, v.saturation, { c -> "$c%" }) { set(PictureAdjust.current().copy(saturation = it)) }
            s.action(PIC_RESET, close = false) { set(PictureAdjust.DEFAULT); setPictureAspect(0); build() }
            if (player != null) {
                s.section("Prędkość odtwarzania")
                val sp = player?.playbackParameters?.speed ?: 1f
                s.tiles(PortraitPlayerUi.SPEEDS.map { PortraitPlayerUi.speedLabel(it) }, PortraitPlayerUi.SPEEDS.indexOfFirst { kotlin.math.abs(it - sp) < 0.01f }) { player?.setPlaybackSpeed(PortraitPlayerUi.SPEEDS[it]); build() }
            }
        }
        build(); s.show(); return s
    }

    // ---------------- (7.23) okna „Audio” i „Napisy” ----------------

    /** Ścieżki dźwięku (karty, wybór od razu). */
    fun audioSheet(): PortraitSheet {
        val s = PortraitSheet(this, "Audio")
        fun build() {
            s.clear()
            val p = player
            val audio = p?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_AUDIO }
            s.section("Ścieżka dźwięku")
            if (audio.isEmpty()) s.info("Brak informacji o ścieżkach dźwięku")
            else s.choices(audio.mapIndexed { i, g -> PortraitPlayerUi.trackName(g.mediaTrackGroup.getFormat(0), i) }, audio.indexOfFirst { it.isSelected }) { i ->
                p?.let { PortraitPlayerUi.selectTrack(it, C.TRACK_TYPE_AUDIO, audio[i]) }; s.root.postDelayed({ if (s.isShowing) build() }, 300)
            }
        }
        build(); s.show(); return s
    }

    /** Napisy: wyłączone / wbudowane / z wtyczek, przesunięcie (synchronizacja), kolor, rozmiar i położenie – wszystko od razu. */
    fun subtitlesSheet(): PortraitSheet {
        val s = PortraitSheet(this, "Napisy")
        fun build() {
            s.clear()
            val p = player
            val text = p?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_TEXT }
            val ext = addonSubs.sortedWith(compareBy({ !StremioDecoders.isPolishSubtitle(it.lang) }, { it.lang }))
            s.section("Napisy")
            val off = subsOff || (externalSub == null && text.none { it.isSelected })
            val labels = listOf("Wyłączone") + text.mapIndexed { i, g -> "Wbudowane: " + PortraitPlayerUi.trackName(g.mediaTrackGroup.getFormat(0), i) } + ext.map { "${StremioSubs.langName(it.lang)}  ·  ${it.addon}" }
            val sel = when {
                off -> 0
                externalSub != null -> ext.indexOfFirst { it.url == externalSub?.url }.takeIf { it >= 0 }?.plus(1 + text.size) ?: 0
                else -> text.indexOfFirst { it.isSelected }.plus(1)
            }
            s.choices(labels, sel) { i ->
                langManual = true
                when {
                    i == 0 -> { subsOff = true; externalSub = null; subView.setCues(emptyList())
                        p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() } }
                    i <= text.size -> { subsOff = false; externalSub = null; lines = emptyList(); p?.let { PortraitPlayerUi.selectTrack(it, C.TRACK_TYPE_TEXT, text[i - 1]) } }
                    else -> selectExternal(ext[i - 1 - text.size])
                }
                s.root.postDelayed({ if (s.isShowing) build() }, 300)
            }
            s.section(SUB_DELAY)
            s.slider(-PortraitPlayerUi.DELAY_RANGE_MS, PortraitPlayerUi.DELAY_RANGE_MS, 100, subDelayMs.coerceIn(-PortraitPlayerUi.DELAY_RANGE_MS, PortraitPlayerUi.DELAY_RANGE_MS),
                { v -> String.format(java.util.Locale.US, "%+.1f s", v / 1000f).replace('.', ',') }) { changeDelay(it - subDelayMs) }
            s.section(SUB_COLOR)
            s.tiles(SUB_COLORS.map { it.second }, Prefs.stremioSubColor.coerceIn(SUB_COLORS.indices)) { Prefs.stremioSubColor = it; applySubStyle(); build() }
            s.section(SUB_SIZE)
            s.tiles(SUB_SIZES.map { it.second }, Prefs.stremioSubSize.coerceIn(SUB_SIZES.indices)) { Prefs.stremioSubSize = it; applySubStyle(); build() }
            s.section(SUB_POSITION)
            s.tiles(SUB_POSITIONS.map { it.second }, Prefs.stremioSubPos.coerceIn(SUB_POSITIONS.indices), perRow = 2) { Prefs.stremioSubPos = it; applySubStyle(); build() }
        }
        build(); s.show(); return s
    }

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

    /** (7.20) Bieżący tytuł jako pozycja katalogu (rok z metadanych – kolejność części). */
    private fun currentPreview(): StremioMetaPreview {
        val m = StremioMetas.cached(type, metaId)
        return StremioMetaPreview(metaId, type, m?.name ?: intent.getStringExtra(EXTRA_NAME).orEmpty(), m?.poster ?: intent.getStringExtra(EXTRA_POSTER).orEmpty(),
            releaseInfo = m?.releaseInfo.orEmpty())
    }

    /**
     * (7.20) Czy pokazać ikonę „Inne odcinki / części”: serial – metadane z więcej niż jednym odcinkiem, film / bajka – co najmniej
     * 2 części serii (kolekcja TMDB albo wyszukiwanie po tytule, razem z bieżącym). Pojedynczy film – ikona całkowicie ukryta.
     */
    private fun loadRelated() = lifecycleScope.launch {
        val series = type == "series"
        val episodes = if (series) (StremioMetas.get(type, metaId)?.videos?.size ?: 0) else 0
        if (!series) { StremioMetas.get(type, metaId); relatedParts = runCatching { StremioMetas.relatedParts(currentPreview()) }.getOrDefault(emptyList()) }
        if (isFinishing || isDestroyed) return@launch
        val show = StremioMetas.hasOtherParts(series, episodes, relatedParts.size)
        val had = partsBtn.isFocused
        partsBtn.visibility = if (show) View.VISIBLE else View.GONE
        if (had && !show) firstIcon()?.requestFocus()
        updateEpisodesIcon()
    }

    /** (7.20) Pozycja panelu obejrzana (✓ „Obejrzano”) i postęp rozpoczętej (pasek) – z Biblioteki (obejrzane + postęp). */
    data class WatchState(val watched: Boolean, val progress: Float?)

    /** Stan obejrzenia odcinka [videoIdOf] / części filmu: zapisane obejrzane, postęp z ≥ progu „Następny” = obejrzane, rozpoczęte – pasek. */
    fun watchState(id: String, done: Set<String>, p: StremioProgress?): WatchState {
        val started = p?.takeIf { it.videoId == id || (it.videoId.isBlank() && it.metaId == id) || it.metaId == id && type != "series" }
        val watched = id in done || started?.pastNextThreshold == true
        return WatchState(watched, started?.fraction?.takeIf { !watched && it > 0f })
    }

    /**
     * Panel boczny (jak kolejka w STube): serial – odcinki bieżącego sezonu, film / bajka – wszystkie części serii od najstarszej.
     * (7.20) Przy każdej pozycji znacznik „Obejrzano” (✓ w kolorze akcentu motywu) i pasek postępu rozpoczętej. Wybór odcinka od
     * razu go uruchamia, wybór innej części otwiera jej szczegóły; bieżąca pozycja – „▶ teraz”.
     */
    // ---------------- (7.22) telefon pionowo: pasek ikon w strefie kciuka i dolne arkusze ----------------

    /** Pasek ikon w pionie (null – TV / poziomo). */
    var portraitBar: LinearLayout? = null; private set
    var lastSheet: PortraitSheet? = null; private set
    private var portraitBarH = 0

    /** Telefon (nie TV): obrót także do pionu przy orientacji pionowej / automatycznej w Ustawieniach; domyślnie – jak dotąd poziomo. */
    private fun setupPortrait() {
        OrientationPolicy.apply(this)   // (7.23) wspólna polityka – także przed onCreate (IptvApp), manifest „behind”
        updatePortraitBar()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePortraitBar()
    }

    /**
     * (7.23) Dolny pasek w pionie – dokładnie: Źródła, Odcinki / Części (serial – zawsze; film – tylko gdy są inne części, [updateEpisodesIcon]),
     * Audio, Napisy, Obraz, Obróć. Pozycje równej szerokości (min. 48 dp) – sześć mieści się bez przycinania także przy 360 dp.
     */
    private fun portraitIcons() = buildList {
        add(PortraitPlayerUi.Icon("sources", PlayerIcon.PLUG, "Źródła") { lastSheet = sourcesSheet() })
        add(PortraitPlayerUi.Icon("episodes", PlayerIcon.QUEUE, if (type == "series") "Odcinki" else "Części") { episodesSheet() })
        add(PortraitPlayerUi.Icon("audio", PlayerIcon.AUDIO, "Audio") { lastSheet = audioSheet() })
        add(PortraitPlayerUi.Icon("tracks", PlayerIcon.CC, "Napisy") { lastSheet = subtitlesSheet() })
        add(PortraitPlayerUi.Icon("aspect", PlayerIcon.ASPECT, "Obraz") { lastSheet = pictureSheet() })
        add(PortraitPlayerUi.Icon("cast", PlayerIcon.CAST, "Przesyłaj") { castClick() })
        add(PortraitPlayerUi.Icon("rotate", PlayerIcon.ROTATE, "Obróć") { PortraitPlayerUi.rotateToLandscape(this@StremioPlayerActivity) })
    }

    /** Ikona „Odcinki / Części” na dolnym pasku: serial zawsze, film – tylko z innymi częściami ([relatedParts]). */
    private fun updateEpisodesIcon() {
        val v = portraitBar?.findViewWithTag<View>(PortraitPlayerUi.ICON_TAG + "episodes") ?: return
        v.visibility = if (type == "series" || relatedParts.size >= 2) View.VISIBLE else View.GONE
    }

    // ---------------- (7.23) pion: ⏪ ⏯ ⏩ na środku, tytuł w lewym górnym rogu ----------------

    private lateinit var centerRow: LinearLayout
    private lateinit var rewBtn: TextView
    private lateinit var fwdBtn: TextView
    private lateinit var rowSpacer: View
    /** Przyciski transportu na środku ekranu (pion) – testy. */
    var transportCentered = false; private set

    /** Pion: ⏪ ⏯ ⏩ przeniesione na środek ekranu (większe odstępy); poziom / TV – z powrotem w lewym dolnym rogu paska. */
    private fun placeTransport(center: Boolean) {
        if (!::centerRow.isInitialized || center == transportCentered) return
        val focused = listOf(rewBtn, playBtn, fwdBtn).firstOrNull { it.isFocused }
        listOf(rewBtn, playBtn, fwdBtn).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        if (center) {
            listOf(rewBtn, playBtn, fwdBtn).forEach { b -> centerRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(14); marginEnd = Ui.dp(14) }) }
            playBtn.scaleX = 1.25f; playBtn.scaleY = 1.25f
        } else {
            listOf(rewBtn, playBtn, fwdBtn).forEachIndexed { i, b -> buttonsRow.addView(b, i, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) }) }
            playBtn.scaleX = 1f; playBtn.scaleY = 1f
        }
        transportCentered = center
        centerRow.visibility = if (center && bar == Bar.FULL) View.VISIBLE else View.GONE
        focused?.requestFocus()
    }

    /** Tytuł w lewym górnym rogu: w pionie pod paskiem stanu, z odstępem od karty „Następny odcinek”. */
    private fun placeTitle(portraitNow: Boolean) {
        val top = if (portraitNow) (androidx.core.view.ViewCompat.getRootWindowInsets(rootView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0) else 0
        if (v2Border != null) return      // (v2) pigułka tytułu – rozmiar i odstępy z [setupV2Top]
        titleView.textSize = if (portraitNow) 18f else 22f
        titleView.setPadding(Ui.dp(if (portraitNow) 16 else 40), top + Ui.dp(if (portraitNow) 12 else 22), Ui.dp(if (resources.configuration.screenWidthDp < 600) 40 else 420), 0)
    }

    // ---------------- (7.23) podwójne stuknięcie po bokach ----------------

    private val doubleTap by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean = doubleTapAt(e.x, e.y)
        })
    }

    private fun hit(v: View?, x: Float, y: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || !v.isShown) return false
        val l = IntArray(2); v.getLocationInWindow(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    /** Podwójne stuknięcie w punkcie okna: lewy pas – do tyłu, prawy – do przodu o [SEEK_STEP_MS] (wskaźnik przewijania); true – przewinięto. */
    fun doubleTapAt(x: Float, y: Float): Boolean {
        if (player == null || (android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) return false
        // przyciski paska, środkowy rząd i dolny pasek ikon – zwykłe kliknięcia
        if ((bar == Bar.FULL && hit(buttonsRow, x, y)) || hit(centerRow, x, y) || hit(portraitBar, x, y) || hit(nextCard, x, y)) return false
        val l = IntArray(2); playerView.getLocationInWindow(l)
        val w = playerView.width; if (w <= 0) return false
        val lx = x - l[0]; val ly = y - l[1]
        if (ly < 0 || ly > playerView.height) return false
        val dir = when { lx < w * DOUBLE_TAP_SIDE -> -1; lx > w * (1 - DOUBLE_TAP_SIDE) -> 1; else -> return false }
        seekBy(dir * SEEK_STEP_MS, badge = true)
        return true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!Tutorial.isTv(this)) doubleTap.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Pion: pasek przy dolnej krawędzi, pasek sterowania (przewijanie) nad nim, ikony górne ukryte (te same funkcje w pasku). */
    private fun updatePortraitBar() {
        val show = PortraitPlayerUi.active(this) && !(android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        if (show && portraitBar == null) portraitBar = PortraitPlayerUi.attachBar(this, rootView, portraitIcons()) { h -> portraitBarH = h; liftControls() }
        PortraitPlayerUi.bindCast(portraitBar)
        portraitBar?.visibility = if (show) View.VISIBLE else View.GONE
        if (::topBar.isInitialized) topBar.visibility = if (show) View.GONE else View.VISIBLE
        if (::rowSpacer.isInitialized) rowSpacer.visibility = if (show) View.GONE else View.VISIBLE
        placeTransport(show); placeTitle(show); updateEpisodesIcon()
        liftControls()
    }

    private fun liftControls() {
        if (!::controls.isInitialized) return
        val m = if (portraitBar?.visibility == View.VISIBLE) portraitBarH else 0
        (controls.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> if (lp.bottomMargin != m) { lp.bottomMargin = m; controls.layoutParams = lp } }
    }

    /** Źródła / jakość: ścieżki obrazu strumienia (gdy jest kilka) i powrót do listy źródeł tytułu. */
    private fun sourcesSheet(): PortraitSheet {
        val s = PortraitSheet(this, "Źródła i jakość")
        val p = player
        val video = p?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_VIDEO }
        val formats = video.flatMap { g -> (0 until g.length).map { i -> g to i } }
        s.section("Jakość obrazu")
        if (formats.size <= 1) s.info(PortraitPlayerUi.diagnostics(p).lineSequence().drop(1).firstOrNull()?.let { "Strumień ma jedną jakość ($it)" } ?: "Strumień ma jedną jakość obrazu")
        else s.choices(formats.map { (g, i) -> g.getTrackFormat(i).let { f -> if (f.height > 0) VideoQuality.label(f.width, f.height) + (if (f.bitrate > 0) " · ${f.bitrate / 1000} kb/s" else "") else "Jakość ${i + 1}" } },
            formats.indexOfFirst { (g, i) -> g.isTrackSelected(i) }) { k ->
            val (g, i) = formats[k]
            p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build() }
            s.dismiss()
        }
        s.section("Źródło")
        s.info(label.ifBlank { if (::stream.isInitialized) listOf(stream.addonName, stream.name).filter { it.isNotBlank() }.joinToString(" · ") else "" })
        s.action("Wybierz inne źródło") { saveProgress(push = true); finish() }
        s.show(); return s
    }

    /** Wymuszone proporcje 16:9 / 4:3 z okna „Obraz” (null – z materiału; [Prefs.stremioAspectRatio]). */
    private var forcedRatio: Float? = null

    /** Odcinki serialu (bieżący sezon) albo części filmu – lista w arkuszu, bieżący oznaczony, wybór odtwarza i zamyka arkusz. */
    private fun episodesSheet() = lifecycleScope.launch {
        val series = type == "series"
        val entries = if (series) {
            val m = StremioMetas.get(type, metaId)
            val cur = m?.videos?.firstOrNull { it.id == videoId }
            m?.episodes(cur?.season ?: m.seasons.firstOrNull() ?: 1).orEmpty().map { e ->
                PortraitPlayerUi.Entry("${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }, e.released, e.id == videoId) { if (e.id != videoId) playEpisode(e) }
            }
        } else {
            relatedParts.ifEmpty { StremioMetas.relatedParts(currentPreview()).also { relatedParts = it } }.map { p ->
                PortraitPlayerUi.Entry(p.name, p.releaseInfo, p.id == metaId) { if (p.id != metaId) { startActivity(StremioDetailActivity.intent(this@StremioPlayerActivity, p, null)); finish() } }
            }
        }
        if (isFinishing || isDestroyed) return@launch
        lastSheet = PortraitPlayerUi.listSheet(this@StremioPlayerActivity, if (series) "Odcinki" else "Inne części", entries, if (series) "Brak listy odcinków" else "Brak innych części tego filmu")
    }

    fun openEpisodesPanel() {
        val series = type == "series"
        val tab = SidePanel.Tab(if (series) "☰ Odcinki" else "☰ Inne części", if (series) "Brak listy odcinków" else "Brak innych części tego filmu") {
            if (series) {
                val m = StremioMetas.get(type, metaId) ?: return@Tab emptyList()
                val cur = m.videos.firstOrNull { it.id == videoId }
                val done = StremioStore.watchedIds(metaId)
                val prog = StremioStore.progressOf(metaId)
                m.episodes(cur?.season ?: m.seasons.firstOrNull() ?: 1).map { e ->
                    val w = watchState(e.id, done, prog)
                    SidePanel.Entry(e.id, "${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }, listOfNotNull(e.released.ifBlank { null }, if (e.id == videoId) "▶ teraz" else null).joinToString(" · "),
                        e.thumbnail, progress = w.progress, watched = w.watched) { if (e.id != videoId) playEpisode(e) else sidePanel?.close() }
                }
            } else {
                val parts = relatedParts.ifEmpty { StremioMetas.relatedParts(currentPreview()).also { relatedParts = it } }
                parts.map { p ->
                    val w = if (p.id == metaId) watchState(videoId.ifBlank { metaId }, StremioStore.watchedIds(metaId), StremioStore.progressOf(metaId))
                        else watchState(p.id, StremioStore.watchedIds(p.id), StremioStore.progressOf(p.id))
                    SidePanel.Entry(p.id, p.name, listOfNotNull(p.releaseInfo.ifBlank { null }, if (p.id == metaId) "▶ teraz" else null).joinToString(" · "),
                        p.poster, progress = w.progress, watched = w.watched) {
                        if (p.id == metaId) sidePanel?.close() else { startActivity(StremioDetailActivity.intent(this@StremioPlayerActivity, p, null)); finish() }
                    }
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
                KeyEvent.KEYCODE_DPAD_UP -> if (!full && !nextCard.isFocused) { setBar(Bar.FULL); firstIcon()?.requestFocus(); return true }
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

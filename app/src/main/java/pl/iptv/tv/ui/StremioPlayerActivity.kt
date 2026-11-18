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
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioProgress
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
        stream = StremioStream.fromJson(JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}"))
        videoId = intent.getStringExtra(EXTRA_VIDEO).orEmpty(); label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        setContentView(build())
        start(resume = true)
    }

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 4000; setShowNextButton(false); setShowPreviousButton(false)
            resizeMode = ASPECTS.getOrElse(Prefs.stremioAspect) { ASPECTS[0] }.first
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                topBar.visibility = v; titleView.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
                // „Następny odcinek” razem z paskiem (serial z kolejnym odcinkiem)
                nextBtn.visibility = if (v == View.VISIBLE && nextVideo != null) View.VISIBLE else View.GONE
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        // napisy rysowane przez nas (opóźnienie, rozmiar, kolor – także dla napisów z wtyczek)
        playerView.subtitleView?.visibility = View.GONE
        subView = SubtitleView(this).apply { setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false) }
        root.addView(subView, FrameLayout.LayoutParams(-1, -1))
        applySubStyle()
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding(Ui.dp(40), Ui.dp(22), Ui.dp(40), 0); visibility = View.GONE
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        topBar = LinearLayout(this).apply { gravity = Gravity.END; setPadding(Ui.dp(30), Ui.dp(18), Ui.dp(30), 0); visibility = View.GONE }
        // przyciski jak w odtwarzaczu Stremio: okrągłe ikony (podpis tylko przy napisach), zaznaczona – biała
        fun btn(icon: String, t: String, a: () -> Unit) = PlayerChrome.button(this, icon, t) { a(); playerView.showController() }
            .also { it.contentDescription = t.ifBlank { icon }; topBar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) }) }
        btn(PlayerIcon.CC, "Napisy") { subtitlesMenu() }.contentDescription = "Napisy"
        btn(PlayerIcon.DELAY, "") { delayMenu() }.contentDescription = "Opóźnienie napisów"
        btn(PlayerIcon.STYLE, "") { styleMenu() }.contentDescription = "Styl napisów"
        btn(PlayerIcon.AUDIO, "") { audioMenu() }.contentDescription = "Ścieżka dźwięku"
        btn(PlayerIcon.ASPECT, "") { aspectMenu() }.contentDescription = "Dopasowanie obrazu"
        topBar.clipChildren = false; topBar.clipToPadding = false
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = Ui.dp(52) })
        // „Następny odcinek” – wyraźna kapsuła nad paskiem postępu (▼ z kontrolek, przycisk „następny” pilota)
        nextBtn = PlayerChrome.button(this, PlayerIcon.NEXT, "Następny odcinek") { playNext() }.apply { visibility = View.GONE; textSize = 16f }
        root.addView(nextBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.dp(40), Ui.dp(100)) })
        // karta „Następny odcinek” przy końcu odcinka
        nextCard = TextView(this).apply {
            tag = "item"; isFocusable = true; isClickable = true; textSize = 16f; setTextColor(Color.WHITE); visibility = View.GONE
            setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12))
            background = GradientDrawable().apply { setColor(0xE0101010.toInt()); cornerRadius = Ui.dp(10).toFloat(); setStroke(Ui.dp(2), Ui.accent) }
            setOnClickListener { playNext() }
        }
        root.addView(nextCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.dp(40), Ui.dp(110)) })
        // pasek postępu w kolorze Stremio, ◄► na pasku – 10 s
        root.post { PlayerChrome.styleTimeBar(playerView, PlayerChrome.STREMIO) }
        return root
    }

    // ---------------- odtwarzanie ----------------

    /** Etap dekodowania ([StremioDecoders]): po błędzie dekodera jedna próba w trybie programowym, potem okno z wyborem. */
    var decoderStage = StremioDecoders.Stage.NORMAL; private set
    /** Opis błędu pokazany w oknie awaryjnym (testy); null – brak. */
    var failure: String? = null; private set
    private var failureDialog: androidx.appcompat.app.AlertDialog? = null
    private var audioWarned = false

    /** [atMs] ≥ 0 – start od tej pozycji (ponowienie po błędzie dekodera), bez wznawiania z historii. */
    private fun start(resume: Boolean, atMs: Long = -1) {
        releasePlayer()
        titleView.text = intent.getStringExtra(EXTRA_NAME).orEmpty() + (if (label.isNotBlank()) "  ·  $label" else "")
        externalSub = null; lines = emptyList(); addonSubs = stream.subtitles; subView.setCues(emptyList())
        audioWarned = false; langApplied = false; langManual = false; polishAudio = null; subsOff = false
        val http = OkHttpDataSource.Factory(Repository.http).setDefaultRequestProperties(stream.headers)
        val p = StremioDecoders.player(this, decoderStage, DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
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
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { warnUnsupportedAudio(tracks); autoLanguage(p, tracks) }
        })
        if (atMs > 0) p.seekTo(atMs)
        p.prepare(); p.playWhenReady = true
        lifecycleScope.launch {
            if (resume && atMs < 0) StremioStore.progressOf(metaId)?.takeIf { it.videoId == videoId && it.positionMs > 10_000 && !it.finished }?.let {
                resumeMs = it.positionMs; p.seekTo(it.positionMs)
                Toast.makeText(this@StremioPlayerActivity, "Wznowiono od ${StremioUi.fmt(it.positionMs)}", Toast.LENGTH_SHORT).show()
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
            updateNextCard()
            handler.postDelayed(this, 100)
        }
    }

    /** Napisy wbudowane z opóźnieniem: dodatnie – wyświetlane później (ujemne: od razu – wbudowanych nie da się pokazać wcześniej). */
    private fun showEmbeddedCues(cues: List<Cue>) {
        if (subsOff) { subView.setCues(emptyList()); return }
        if (subDelayMs > 0) handler.postDelayed({ if (externalSub == null) subView.setCues(cues) }, subDelayMs.toLong()) else subView.setCues(cues)
    }

    // ---------------- błędy dekodera: tryb programowy, potem wybór ----------------

    /** Błąd odtwarzania: dekoder – jedna próba z dekodowaniem programowym od tej samej pozycji; inaczej okno z wyborem. */
    fun onError(e: PlaybackException, positionMs: Long) {
        val next = StremioDecoders.nextStage(decoderStage, e.errorCode)
        if (next != null) {
            decoderStage = next
            Toast.makeText(this, "Dekoder sprzętowy odrzucił strumień – przełączam na dekodowanie programowe", Toast.LENGTH_LONG).show()
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
        playerView.hideController()
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
            Toast.makeText(this, "Brak zewnętrznego odtwarzacza – zainstaluj np. VLC lub Just Player", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "Telewizor nie obsługuje dźwięku $codec – odtwarzanie bez dźwięku. Wybierz inne źródło, aby mieć dźwięk.", Toast.LENGTH_LONG).show()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tick)
        player?.let { saveProgress(); it.release() }
        player = null
    }

    override fun onPause() { super.onPause(); saveProgress(push = true); player?.pause() }
    override fun onResume() { super.onResume(); player?.let { if (it.playbackState != Player.STATE_ENDED) it.play() } }
    override fun onDestroy() { failureDialog?.dismiss(); releasePlayer(); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    fun saveProgress(push: Boolean = false) {
        val p = player ?: return
        val pos = p.currentPosition; val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        if (pos < 5_000) return
        val row = StremioProgress(metaId, type, intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_POSTER).orEmpty(),
            videoId, label.substringBefore(" – "), pos, dur, System.currentTimeMillis())
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
        val plan = StremioDecoders.languagePlan(audio.map(::info), text.map(::info))
        langApplied = true; polishAudio = plan.audio != null
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
        if (plan.addonSubs && externalSub == null) addonSubs.firstOrNull { StremioDecoders.isPolish(it.lang, null) }?.let { selectExternal(it, quiet = true) }
    }

    private suspend fun loadAddonSubs() {
        val addons = StremioStore.enabledAddons().filter { it.supports("subtitles", type, videoId) }
        val got = addons.flatMap { a -> runCatching { StremioApi.subtitles(a, type, videoId, stream.filename, stream.videoSize) }.getOrDefault(emptyList()) }
        addonSubs = (stream.subtitles + got).distinctBy { it.url }
        // preferowany język (domyślnie polski) – od razu, gdy plik nie ma własnych napisów w tym języku ani polskiego dźwięku
        if (externalSub == null && !subsOff && polishAudio != true && !langManual) addonSubs.firstOrNull { it.lang.lowercase().startsWith(Prefs.stremioSubLang.take(2)) }?.let { s ->
            val embedded = player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && (it.mediaTrackGroup.getFormat(0).language ?: "").startsWith(Prefs.stremioSubLang.take(2)) } == true
            if (!embedded) selectExternal(s, quiet = true)
        }
    }

    fun selectExternal(s: StremioSubtitle, quiet: Boolean = false) {
        lifecycleScope.launch {
            runCatching { StremioSubs.parse(StremioApi.subtitleText(s.url)) }.onSuccess { l ->
                lines = l; externalSub = s; subsOff = false
                player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                Prefs.stremioSubLang = s.lang.ifBlank { Prefs.stremioSubLang }
                if (!quiet) Toast.makeText(this@StremioPlayerActivity, "Napisy: ${StremioSubs.langName(s.lang)} (${s.addon})", Toast.LENGTH_SHORT).show()
            }.onFailure { if (!quiet) Toast.makeText(this@StremioPlayerActivity, "Nie udało się wczytać napisów: ${it.message}", Toast.LENGTH_LONG).show() }
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
        addonSubs.sortedWith(compareBy({ !it.lang.startsWith(Prefs.stremioSubLang.take(2)) }, { it.lang })).forEach { s ->
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
        if (groups.isEmpty()) { Toast.makeText(this, "Brak informacji o ścieżkach dźwięku", Toast.LENGTH_SHORT).show(); return }
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
        nextBtn.visibility = if (nextVideo != null && playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        nextBtn.text = nextVideo?.let { "Następny odcinek · ${it.label.substringBefore(" – ")}" } ?: "Następny odcinek"
        nextCard.text = nextVideo?.let { "⏭  Następny odcinek: ${it.label}   (OK)" }.orEmpty()
    }

    private fun updateNextCard() {
        val p = player ?: return
        val dur = p.duration; val n = nextVideo
        val show = n != null && dur > 0 && dur != C.TIME_UNSET && p.currentPosition >= maxOf(dur - 45_000, (dur * 0.95).toLong())
        if (show && nextCard.visibility != View.VISIBLE) { nextCard.visibility = View.VISIBLE; Ui.style(nextCard); if (!playerView.isControllerFullyVisible) nextCard.requestFocus() }
        else if (!show && nextCard.visibility == View.VISIBLE) nextCard.visibility = View.GONE
    }

    private fun onEnded() { saveProgress(push = true); if (nextVideo != null) playNext() else finish() }

    /** Następny odcinek: źródło z tej samej wtyczki ([StremioStreams.pickNext]); brak – ekran źródeł tego odcinka. */
    fun playNext() {
        val n = nextVideo ?: return
        saveProgress(push = true)
        lifecycleScope.launch {
            val addon = StremioStore.enabledAddons().firstOrNull { it.transportUrl == stream.addonUrl }
            val pick = addon?.let { a -> runCatching { StremioApi.streams(a, type, n.id) }.getOrNull() }?.let { StremioStreams.pickNext(stream, it) }
            if (pick == null) {
                startActivity(Intent(this@StremioPlayerActivity, StremioDetailActivity::class.java).putExtra(StremioDetailActivity.EXTRA_TYPE, type)
                    .putExtra(StremioDetailActivity.EXTRA_ID, metaId).putExtra(StremioDetailActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME))
                    .putExtra(StremioDetailActivity.EXTRA_POSTER, intent.getStringExtra(EXTRA_POSTER)).putExtra(StremioDetailActivity.EXTRA_VIDEO, n.id))
                finish(); return@launch
            }
            stream = pick; videoId = n.id; label = n.label; nextCard.visibility = View.GONE
            decoderStage = StremioDecoders.Stage.NORMAL; failure = null   // nowe źródło – znowu od dekoderów sprzętowych
            intent.putExtra(EXTRA_STREAM, pick.toJson().toString()).putExtra(EXTRA_VIDEO, n.id).putExtra(EXTRA_LABEL, n.label)
            Toast.makeText(this@StremioPlayerActivity, "Następny odcinek: ${n.label}", Toast.LENGTH_SHORT).show()
            start(resume = false)
        }
    }

    // ---------------- pilot ----------------

    private fun optionsMenu() = Dialogs.menu(this, "Opcje", buildList {
        add("💬 Napisy" to { subtitlesMenu() }); add("⏱ Opóźnienie napisów (${delayLabel()})" to { delayMenu(); Unit })
        add("Aa Styl napisów" to { styleMenu() }); add("🔊 Ścieżka dźwięku" to { audioMenu() }); add("⛶ Dopasowanie obrazu" to { aspectMenu(); Unit })
        if (nextVideo != null) add("⏭ Następny odcinek" to { playNext() })
    })

    /** WSTECZ schował pasek – zwolnienie klawisza nie zamyka odtwarzacza. */
    private var backHid = false

    /** Kontrolki środkowe paska (⏪ ⏯ ⏩) – z nich ▼ przechodzi do „Następny odcinek”. */
    private val centerControls = setOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_rew_with_amount,
        androidx.media3.ui.R.id.exo_ffwd_with_amount, androidx.media3.ui.R.id.exo_progress)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val visible = playerView.isControllerFullyVisible
        // „Następny odcinek” zawsze pod ręką: przycisk „następny” / CH+ pilota, ▼ z kontrolek – kapsuła, ▲ z niej – ⏯
        if (e.action == KeyEvent.ACTION_DOWN && nextVideo != null) {
            if (e.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && e.repeatCount == 0) { playNext(); return true }
            val f = window.decorView.findFocus()
            if (visible && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && f.id in centerControls && nextBtn.visibility == View.VISIBLE) {
                nextBtn.requestFocus(); playerView.showController(); return true
            }
            if (visible && f === nextBtn) {
                playerView.showController()
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> return true      // bez „ucieczki” fokusu poza ekran
                }
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_MENU -> { optionsMenu(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!visible && nextCard.visibility != View.VISIBLE) { playerView.showController(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!visible) {
                player?.let { it.seekTo((it.currentPosition + if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000).coerceAtLeast(0)) }
                playerView.showController(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (!visible) { playerView.showController(); topBar.post { topBar.getChildAt(0)?.requestFocus() }; return true }
            KeyEvent.KEYCODE_BACK -> if (visible) { playerView.hideController(); backHid = true; return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && backHid) { backHid = false; return true }
        return super.dispatchKeyEvent(e)
    }
}

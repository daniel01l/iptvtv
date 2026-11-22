package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMeta
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioResume
import pl.iptv.tv.data.StremioSourceGroup
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StremioVideo

/**
 * Pozycja listy źródeł: zwijana grupa rozdzielczości (4K, 1080p…), źródło (z nazwą wtyczki), komunikat (wczytywanie, błąd wtyczki).
 */
sealed class SourceItem {
    data class Quality(val q: String, val count: Int, val polish: Int, val open: Boolean) : SourceItem()
    data class Source(val s: StremioStream) : SourceItem()
    data class Note(val t: String) : SourceItem()
    /** (7.7) Jednolity wiersz „Ładowanie źródeł” (zaznaczalny – OK / ► z odcinka od razu przenosi tu kursor). */
    object Loading : SourceItem()
}

/** (7.8) Gotowy model listy źródeł (grupy rozdzielczości, wszystkie źródła, komunikaty) – liczony poza głównym wątkiem. */
class SourcePrepared(val byQuality: List<Pair<String, List<StremioStream>>>, val sourceItems: List<StremioStream>, val notes: List<SourceItem>)

/**
 * Szczegóły filmu / serialu jak w Stremio: plakat, opis, sezony i odcinki, a po prawej źródła – zapytanie wyłącznie do włączonych
 * wtyczek ze źródłami, najwyżej [StremioStreams.PER_ADDON] źródeł na wtyczkę z każdej rozdzielczości (polska wersja na górze), pogrupowane według nazwy wtyczki
 * (jakość, HDR, język, rozmiar).
 */
class StremioDetailActivity : BaseActivity() {
    override val pausesLiveTv = true
    companion object {
        const val EXTRA_TYPE = "type"; const val EXTRA_ID = "id"; const val EXTRA_NAME = "name"; const val EXTRA_POSTER = "poster"; const val EXTRA_VIDEO = "video"
        fun intent(c: Context, m: StremioMetaPreview, videoId: String? = null) = Intent(c, StremioDetailActivity::class.java)
            .putExtra(EXTRA_TYPE, m.type).putExtra(EXTRA_ID, m.id).putExtra(EXTRA_NAME, m.name).putExtra(EXTRA_POSTER, m.poster).putExtra(EXTRA_VIDEO, videoId)
        /** (7.4) Źródła odcinka zaznaczonego pilotem – dopiero po tej chwili bez ruchu (szybkie przewijanie bez zapytań). */
        const val EPISODE_SOURCES_DELAY_MS = 350L
        /** (7.7) Jedyny komunikat w trakcie wczytywania źródeł. */
        const val LOADING_TEXT = "Ładowanie źródeł"
    }

    private val type get() = intent.getStringExtra(EXTRA_TYPE).orEmpty()
    private val metaId get() = intent.getStringExtra(EXTRA_ID).orEmpty()
    var meta: StremioMeta? = null; private set
    var progress: StremioProgress? = null; private set
    /** Wybrany odcinek (serial) albo id filmu. */
    var videoId: String = ""; private set
    var groups: List<StremioSourceGroup> = emptyList(); private set

    private lateinit var poster: ImageView
    private lateinit var bg: ImageView
    private lateinit var title: TextView
    private lateinit var metaLine: TextView
    private lateinit var desc: TextView
    private lateinit var resume: TextView
    private lateinit var episodesCol: LinearLayout
    private lateinit var seasonsBar: LinearLayout
    private lateinit var episodes: RecyclerView
    private lateinit var sourcesTitle: TextView
    private lateinit var sources: RecyclerView
    private val episodeAdapter = EpisodeAdapter()
    val sourceAdapter = SourceAdapter()
    private var sourcesJob: Job? = null
    /** Wczytywanie źródeł czeka na zatrzymanie kursora na odcinku ([EPISODE_SOURCES_DELAY_MS]). */
    var sourcesPending = false; private set
    private var season = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(build())
        title.text = intent.getStringExtra(EXTRA_NAME)
        intent.getStringExtra(EXTRA_POSTER)?.takeIf { it.isNotBlank() }?.let { poster.load(it) { crossfade(false); fitTo(poster, Ui.dp(170), Ui.dp(252)) } }
        lifecycleScope.launch {
            progress = StremioStore.progressOf(metaId)
            val m = StremioMetas.get(type, metaId)
            meta = m
            if (m == null) { desc.text = "Nie udało się wczytać informacji – źródła poniżej."; videoId = metaId; loadSources(); return@launch }
            bind(m)
        }
    }

    /**
     * (7.7) ► na odcinku – od razu do listy źródeł (dawniej, gdy źródła się wczytywały, lista nie miała żadnego zaznaczalnego
     * elementu i ► nic nie robiło); kursor na pierwszym źródle albo na „Ładowanie źródeł”.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && ::episodes.isInitialized) {
            val f = window.decorView.findFocus()
            if (f != null && episodes.findContainingItemView(f) != null) {
                KeyActivity.lastUserKeyAt = event.eventTime          // odwołuje wcześniejsze ustawianie fokusu na liście odcinków
                focusSourcesWhenReady()
                return true
            }
        }
        // (7.11) ◄ z listy źródeł – dokładnie na odcinek, z którego przeszliśmy do źródeł (z zachowaniem przewinięcia listy)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && ::sources.isInitialized && episodesCol.visibility == View.VISIBLE) {
            val f = window.decorView.findFocus()
            if (f != null && sources.findContainingItemView(f) != null) { KeyActivity.lastUserKeyAt = event.eventTime; backToEpisodes(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // odświeżenie postępu bez przebudowy listy (te same widoki – fokus zostaje na odcinku)
        if (meta != null) lifecycleScope.launch { progress = StremioStore.progressOf(metaId); updateResume(); episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount); refreshWatched() }
    }

    // ---------------- (7.17) „Obsidian Stream” – kinowy widok szczegółów ----------------

    private val obsidian by lazy { pl.iptv.tv.ui.theme.ObsidianViews.active }
    private var genreBar: LinearLayout? = null
    /** Przyciski CTA: Oglądaj (akcent), Zwiastun, Dodaj do biblioteki (testy). */
    var watchBtn: TextView? = null; private set
    var trailerBtn: TextView? = null; private set
    var libraryBtn: TextView? = null; private set

    /** Pigułka CTA: główna – akcent `#3B82F6`, pozostałe – szkło; zaznaczona – biała z ciemnym tekstem. */
    private fun ctaPill(text: String, icon: String, primary: Boolean, onClick: () -> Unit): TextView {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val foc = intArrayOf(android.R.attr.state_focused)
        val colors = android.content.res.ColorStateList(arrayOf(foc, intArrayOf()), intArrayOf(O.BG, O.TEXT))
        return TextView(this).apply {
            this.text = text.uppercase(); tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER_VERTICAL; letterSpacing = 0.04f
            setTextColor(colors); setPadding(Ui.dp(22), Ui.dp(11), Ui.dp(24), Ui.dp(11))
            setCompoundDrawables(PlayerIcon(icon, colors).apply { setBounds(0, 0, Ui.dp(18), Ui.dp(18)) }, null, null, null); compoundDrawablePadding = Ui.dp(10)
            fun pill(c: Int, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(26).toFloat(); setColor(c); if (stroke != 0) setStroke(Ui.dp(1), stroke) }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(foc, pill(0xFFFFFFFF.toInt()))
                addState(intArrayOf(), if (primary) pill(O.ACCENT) else pill(O.SURFACE_GLASS, O.SPECULAR))
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(if (pl.iptv.tv.data.Prefs.lowPerf) 0 else 140).start() }
        }
    }

    /** OGLĄDAJ: wznowienie (gdy jest postęp), inaczej źródła wybranego odcinka / filmu (kursor na pierwszym źródle). */
    private fun watch() { if (resume.visibility == View.VISIBLE) resumePlayback() else focusSourcesWhenReady() }

    /** Zwiastun: z metadanych (YouTube) w odtwarzaczu STube, bez niego – wyszukiwanie zwiastuna w STube. */
    private fun trailer() {
        val m = meta ?: return
        val id = m.trailers.firstOrNull()
        when {
            id != null -> X25Actions.playVideo(this, pl.iptv.tv.data.YtVideo(id, "Zwiastun – ${m.name}", m.name))
            pl.iptv.tv.BuildConfig.FULL_EDITION -> startActivity(Intent(this, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_QUERY, "${m.name} ${m.releaseInfo.take(4)} zwiastun".trim()))
        }
    }

    private fun preview(): StremioMetaPreview = meta?.let { StremioMetaPreview(it.id, it.type, it.name, it.poster, releaseInfo = it.releaseInfo, imdbRating = it.imdbRating) }
        ?: StremioMetaPreview(metaId, type, intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_POSTER).orEmpty())

    private fun toggleLibrary() {
        val now = pl.iptv.tv.data.StremioLibrary.toggle(preview())
        updateLibraryButton()
        I18n.makeToast(this, if (now) "Dodano do biblioteki" else "Usunięto z biblioteki", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateLibraryButton() {
        val b = libraryBtn ?: return
        val inLib = pl.iptv.tv.data.StremioLibrary.contains(metaId)
        b.text = (if (inLib) "W bibliotece" else "Dodaj do biblioteki").uppercase()
        (b.compoundDrawables[0] as? PlayerIcon)?.let { b.setCompoundDrawables(PlayerIcon(if (inLib) PlayerIcon.CHECK else PlayerIcon.PLUS, b.textColors).apply { setBounds(0, 0, Ui.dp(18), Ui.dp(18)) }, null, null, null) }
    }

    /**
     * Kinowy układ: tło (backdrop) na cały ekran z winietą `#080A0F` od lewej i od dołu, duży tytuł, rok · czas · ocena,
     * gatunki w pigułkach, opis (do 4 linii), przyciski OGLĄDAJ / Zwiastun / Dodaj do biblioteki, a pod nimi odcinki i źródła
     * (te same listy i ta sama nawigacja pilotem co w zwykłym układzie).
     */
    private fun buildObsidian(): View {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val frame = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(bg, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2080A0F.toInt(), 0xB3080A0F.toInt(), 0x1A080A0F)),
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF080A0F.toInt(), 0xCC080A0F.toInt(), 0x00080A0F))))
        }, FrameLayout.LayoutParams(-1, -1))
        poster = ImageView(this).apply { visibility = View.GONE }          // plakat jako zapasowe tło (bez osobnej kolumny)
        frame.addView(poster, FrameLayout.LayoutParams(1, 1))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(56), Ui.dp(30), Ui.dp(40), Ui.dp(16)) }
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { textSize = 34f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(O.TEXT); tag = "keepcolor"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        metaLine = TextView(this).apply { textSize = 15f; setTextColor(O.TEXT); alpha = 0.9f; tag = "keepcolor"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, Ui.dp(6), 0, 0) }
        genreBar = LinearLayout(this).apply { setPadding(0, Ui.dp(8), 0, 0) }
        desc = TextView(this).apply { textSize = 15f; setTextColor(O.TEXT); alpha = 0.88f; tag = "keepcolor"; maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(Ui.dp(3).toFloat(), 1f); setPadding(0, Ui.dp(10), 0, 0) }
        resume = TextView(this).apply { visibility = View.GONE }            // stan „Wznów” – przycisk OGLĄDAJ pokazuje go w napisie
        hero.addView(title); hero.addView(metaLine); hero.addView(genreBar); hero.addView(desc)
        val cta = LinearLayout(this).apply { setPadding(0, Ui.dp(16), 0, Ui.dp(4)); clipChildren = false; clipToPadding = false }
        watchBtn = ctaPill("Oglądaj", PlayerIcon.PLAY, primary = true) { watch() }
        trailerBtn = ctaPill("Zwiastun", PlayerIcon.FILM, primary = false) { trailer() }.apply { visibility = if (pl.iptv.tv.BuildConfig.FULL_EDITION) View.VISIBLE else View.GONE }
        libraryBtn = ctaPill("Dodaj do biblioteki", PlayerIcon.PLUS, primary = false) { toggleLibrary() }
        listOf(watchBtn!!, trailerBtn!!, libraryBtn!!).forEach { cta.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(12) }) }
        hero.addView(cta); hero.addView(resume)
        root.addView(hero, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.62f).toInt(), -2))
        // pod spodem: odcinki (seriale) i źródła – szklane panele
        val lists = LinearLayout(this).apply { setPadding(0, Ui.dp(14), 0, 0) }
        episodesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = O.glass(); setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10)) }
        seasonsBar = LinearLayout(this)
        episodesCol.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seasonsBar) })
        episodes = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StremioDetailActivity); adapter = episodeAdapter; itemAnimator = null }
        episodesCol.addView(episodes, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(6) })
        lists.addView(episodesCol, LinearLayout.LayoutParams(Ui.dp(320), -1).apply { marginEnd = Ui.dp(16) })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = O.glass(); setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10)) }
        sourcesTitle = TextView(this).apply { text = "Źródła"; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(O.TEXT); tag = "keepcolor"; setPadding(Ui.dp(6), 0, 0, Ui.dp(6)) }
        right.addView(sourcesTitle)
        sources = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StremioDetailActivity); adapter = sourceAdapter; itemAnimator = null }
        right.addView(sources, LinearLayout.LayoutParams(-1, 0, 1f))
        lists.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(lists, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        updateLibraryButton()
        return frame
    }

    /** Metadane i gatunki w układzie „Obsidian Stream”: rok · czas trwania · ocena, gatunki jako pigułki. */
    private fun bindObsidian(m: StremioMeta) {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        metaLine.text = listOfNotNull(StremioUi.typeName(m.type), m.releaseInfo.ifBlank { null }, m.runtime.ifBlank { null },
            m.imdbRating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it IMDb" }).joinToString("   ·   ")
        genreBar?.let { bar ->
            bar.removeAllViews()
            m.genres.take(4).forEach { g ->
                bar.addView(TextView(this).apply {
                    text = g; textSize = 13f; tag = "keepcolor"; setTextColor(O.TEXT); setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(14).toFloat(); setColor(0x1FFFFFFF); setStroke(Ui.dp(1), O.SPECULAR) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) })
            }
            bar.visibility = if (m.genres.isEmpty()) View.GONE else View.VISIBLE
        }
        // tło: backdrop, a bez niego plakat (rozmiar ekranu – bez pełnej rozdzielczości w pamięci)
        val url = m.background.ifBlank { m.poster }
        if (url.isNotBlank()) bg.load(url) { crossfade(false); backdropSize(this@StremioDetailActivity).let { (w, h) -> size(w, h) } }
        updateLibraryButton()
    }

    private fun build(): View {
        if (obsidian) return buildObsidian()
        val frame = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.22f }
        frame.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply { setPadding(Ui.dp(36), Ui.dp(22), Ui.dp(28), Ui.dp(14)) }
        // lewa kolumna: plakat i opis
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Ui.roundClip(poster, Ui.dp(10).toFloat())
        left.addView(poster, LinearLayout.LayoutParams(Ui.dp(170), Ui.dp(252)))
        title = StremioUi.text(this, "", 22f, bold = true, lines = 2).apply { setPadding(0, Ui.dp(10), 0, 0) }
        metaLine = StremioUi.text(this, "", 13f, "accent", lines = 2)
        resume = StremioUi.button(this, "Wznów", PlayerIcon.PLAY) { resumePlayback() }.apply { visibility = View.GONE }
        desc = StremioUi.text(this, "", 14f, "text2")
        val descScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(desc) }
        left.addView(title); left.addView(metaLine)
        left.addView(resume, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        left.addView(descScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(8) })
        root.addView(left, LinearLayout.LayoutParams(Ui.dp(290), -1))
        // środek: sezony i odcinki (tylko seriale)
        episodesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "panel"; setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8)) }
        seasonsBar = LinearLayout(this)
        episodesCol.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seasonsBar) })
        episodes = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StremioDetailActivity); adapter = episodeAdapter; itemAnimator = null }
        episodesCol.addView(episodes, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(6) })
        root.addView(episodesCol, LinearLayout.LayoutParams(Ui.dp(300), -1).apply { marginStart = Ui.dp(18) })
        // prawa kolumna: źródła
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "panel"; setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10)) }
        sourcesTitle = StremioUi.text(this, "Źródła", 18f, "accent", bold = true, lines = 1).apply { setPadding(Ui.dp(6), 0, 0, Ui.dp(6)) }
        right.addView(sourcesTitle)
        sources = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StremioDetailActivity); adapter = sourceAdapter; itemAnimator = null }
        right.addView(sources, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(18) })
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun bind(m: StremioMeta) {
        title.text = m.name
        metaLine.text = StremioUi.metaLine(m.type, m.releaseInfo, m.imdbRating, m.genres) + (if (m.runtime.isNotBlank()) "   ·   ${m.runtime}" else "")
        desc.text = m.description
        if (m.poster.isNotBlank()) poster.load(m.poster) { crossfade(false); fitTo(poster, Ui.dp(170), Ui.dp(252)) }
        // tło przyciemnione (alpha 0,22) – wystarczy rozdzielczość ekranu ograniczona do 1280 px
        if (m.background.isNotBlank()) bg.load(m.background) { crossfade(false); backdropSize(this@StremioDetailActivity).let { (w, h) -> size(w, h) } }
        if (obsidian) bindObsidian(m)
        updateResume()
        if (m.isSeries && m.videos.isNotEmpty()) {
            episodesCol.visibility = View.VISIBLE
            val want = intent.getStringExtra(EXTRA_VIDEO)?.takeIf { it.isNotBlank() } ?: progress?.videoId
            val start = m.videos.firstOrNull { it.id == want }
            season = start?.season ?: m.seasons.first()
            seasonsBar.removeAllViews()
            m.seasons.forEach { s ->
                seasonsBar.addView(StremioUi.button(this, if (s == 0) "Dodatki" else "Sezon $s") { selectSeason(s, null) }.apply {
                    textSize = 14f; setOnLongClickListener { seasonMenu(s); true }   // (7.12) przytrzymanie – cały sezon jako obejrzany
                },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(6) })
            }
            Ui.style(seasonsBar)
            selectSeason(season, start)
            refreshWatched()
        } else {
            videoId = m.videos.singleOrNull()?.id?.takeIf { it.isNotBlank() } ?: m.id
            loadSources()
        }
    }

    private fun updateResume() {
        val p = progress?.takeIf { it.positionMs > 10_000 }
        // (7.8) okienko „Następny odcinek” już było – przycisk prowadzi do następnego odcinka, inaczej wznawia bieżący
        val next = p?.let { StremioResume.target(it, meta) }?.takeIf { it.next }
        val show = p != null && (!p.finished || next != null)
        resume.visibility = if (show) View.VISIBLE else View.GONE
        if (p != null && next != null) resume.text = "▶ Następny odcinek " + next.label.substringBefore(" – ")
        else if (p != null) resume.text = "▶ Wznów " + listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" · ")
        // (7.17) OGLĄDAJ pokazuje, co uruchomi: wznowienie / następny odcinek albo zwykłe „Oglądaj”
        watchBtn?.text = (if (!show) "Oglądaj" else resume.text.toString().removePrefix("▶ ")).uppercase()
    }

    /**
     * (7.8) „Wznów” od razu uruchamia odtwarzanie (dawniej tylko przenosiło kursor na listę źródeł i nic się nie działo):
     * odtwarzacz wybiera odcinek ([StremioResume.target]) i źródło (to samo co poprzednio, polska wersja na górze).
     */
    private fun resumePlayback() {
        val p = progress ?: return
        startActivity(StremioPlayerActivity.resumeIntent(this, p))
    }

    /**
     * (7.4) Fokus na pierwszym źródle – od razu, gdy źródła już są, inaczej po wczytaniu pierwszych wyników. Dawniej OK na
     * odcinku próbował przenieść kursor na listę, która jeszcze się wczytywała – nic się nie działo. Ruch pilotem w międzyczasie
     * odwołuje przeniesienie (kursor nie „ucieka”).
     */
    private var sourceFocusAt = 0L

    /** (7.7) Kursor na wybranym odcinku (serial) – gdy lista źródeł nie ma nic do zaznaczenia. */
    private fun backToEpisodes() {
        sourceFocusAt = 0
        if (episodesCol.visibility != View.VISIBLE) return
        val i = episodeAdapter.items.indexOfFirst { it.id == episodeAdapter.selected }.coerceAtLeast(0)
        episodes.post { episodes.focusPosition(i) }
    }

    fun focusSourcesWhenReady() {
        // (7.7) źródła czekały na zatrzymanie kursora – wczytywanie od razu
        if (sourcesPending) loadSources()
        sourceFocusAt = android.os.SystemClock.uptimeMillis()
        // kursor od razu na liście źródeł: na pierwszym źródle albo (jeszcze się wczytują) na „Ładowanie źródeł”
        if (sourceAdapter.items.none { it is SourceItem.Source || it is SourceItem.Quality }) {
            val i = sourceAdapter.items.indexOf(SourceItem.Loading)
            if (i >= 0) { val at = sourceFocusAt; sources.post { sources.focusPosition(i, since = at) } }
        }
        applySourceFocus()
    }

    private fun applySourceFocus() {
        val at = sourceFocusAt
        if (at == 0L) return
        if (KeyActivity.lastUserKeyAt.let { it > at && it <= android.os.SystemClock.uptimeMillis() }) { sourceFocusAt = 0; return }
        if (sourceAdapter.items.none { it is SourceItem.Source || it is SourceItem.Quality }) return
        sourceFocusAt = 0
        sources.post { sources.focusPosition(sourceAdapter.firstSource(), since = at) }
    }

    fun selectSeason(s: Int, ep: StremioVideo?) {
        val m = meta ?: return
        season = s
        for (i in 0 until seasonsBar.childCount) (seasonsBar.getChildAt(i) as TextView).isActivated = m.seasons.getOrNull(i) == s
        episodeAdapter.items = m.episodes(s)
        episodeAdapter.notifyDataSetChanged()
        val e = ep ?: episodeAdapter.items.firstOrNull() ?: return
        selectEpisode(e)
        episodes.post { episodes.focusPosition(episodeAdapter.items.indexOf(e).coerceAtLeast(0)) }
    }

    /**
     * Wybór odcinka. (7.4) Tylko poprzedni i nowy wiersz odświeżane (dawniej cała lista – przy przewijaniu kursor skakał
     * na inny odcinek); [debounce] – zaznaczenie pilotem: źródła wczytywane dopiero, gdy kursor zatrzyma się na odcinku
     * (szybkie przewijanie nie uruchamia zapytań do wtyczek dla każdego mijanego odcinka).
     */
    fun selectEpisode(e: StremioVideo, debounce: Boolean = false) {
        if (videoId == e.id) return
        videoId = e.id
        val old = episodeAdapter.items.indexOfFirst { it.id == episodeAdapter.selected }
        episodeAdapter.selected = e.id
        if (old >= 0) episodeAdapter.notifyItemChanged(old)
        episodeAdapter.items.indexOf(e).takeIf { it >= 0 }?.let { episodeAdapter.notifyItemChanged(it) }
        loadSources(if (debounce) EPISODE_SOURCES_DELAY_MS else 0)
    }

    /** Źródła z włączonych wtyczek (równolegle), po [StremioStreams.PER_ADDON] na wtyczkę z każdej rozdzielczości. */
    fun loadSources(delayMs: Long = 0) {
        sourcesJob?.cancel()
        val vid = videoId
        val streamType = if (meta?.isSeries == true) "series" else type
        val label = meta?.videos?.firstOrNull { it.id == vid }?.label?.takeIf { meta?.isSeries == true }
        sourcesTitle.text = "Źródła" + (label?.let { " – $it" } ?: "")
        // (7.7) od razu „Ładowanie źródeł” (lista poprzedniego odcinka znika – OK nie trafi w jego źródła)
        groups = emptyList(); sourceAdapter.showLoading()
        sourcesPending = delayMs > 0
        sourcesJob = lifecycleScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            sourcesPending = false
            val addons = StremioStore.enabledAddons().filter { it.supports("stream", streamType, vid) }
            if (addons.isEmpty()) {
                groups = emptyList(); sourceAdapter.set(emptyList(), "Brak włączonych wtyczek ze źródłami dla tego tytułu. Włącz je w Stremio → 🧩 Wtyczki."); return@launch
            }
            groups = addons.map { StremioSourceGroup(it, emptyList(), loading = true) }
            sourceAdapter.set(groups, null)
            // (7.8) sieć, parsowanie, ograniczanie i sortowanie na wątkach roboczych (limit czasu na wtyczkę, anulowanie z zadaniem);
            // na głównym wątku tylko podmiana gotowej listy – bez zawieszania interfejsu przy setkach źródeł
            var seq = 0
            addons.mapIndexed { i, a -> async {
                val g = withContext(Dispatchers.Default) {
                    try {
                        withTimeoutOrNull(StremioApi.STREAMS_TIMEOUT_MS) {
                            val all = StremioApi.streams(a, streamType, vid)
                            StremioSourceGroup(a, StremioStreams.limit(all), total = all.size)
                        } ?: StremioSourceGroup(a, emptyList(), "przekroczono czas oczekiwania")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { StremioSourceGroup(a, emptyList(), e.message ?: "błąd") }
                }
                groups = groups.toMutableList().also { it[i] = g }
                val snapshot = groups; val mine = ++seq
                val ready = withContext(Dispatchers.Default) { sourceAdapter.prepare(snapshot) }
                if (videoId == vid && mine == seq) { sourceAdapter.show(ready); applySourceFocus() }
            } }.forEach { it.await() }
            sourceFocusAt = 0                         // wszystkie wtyczki odpowiedziały (także bez źródeł) – bez czekania na fokus
        }
    }

    fun play(s: StremioStream) {
        val m = meta
        val vid = videoId
        val label = m?.videos?.firstOrNull { it.id == vid }?.label?.takeIf { m.isSeries }.orEmpty()
        when (s.kind) {
            StremioStream.Kind.HTTP -> startActivity(StremioPlayerActivity.intent(this, if (m?.isSeries == true) "series" else type, metaId,
                m?.name ?: title.text.toString(), m?.poster ?: intent.getStringExtra(EXTRA_POSTER).orEmpty(), vid, label, s))
            StremioStream.Kind.YOUTUBE -> startActivity(YtPlayerActivity.intent(this, s.ytId, s.headline))
            StremioStream.Kind.EXTERNAL -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.externalUrl))) }
            // P2P: przez serwer strumieniujący (TorrServer / serwer Stremio) – zwykłe źródło HTTP we wbudowanym odtwarzaczu
            StremioStream.Kind.TORRENT -> lifecycleScope.launch {
                I18n.makeToast(this@StremioDetailActivity, "Źródło P2P – łączenie z serwerem torrent…", android.widget.Toast.LENGTH_SHORT).show()
                val server = pl.iptv.tv.data.TorrentStreams.find()
                if (server != null) play(pl.iptv.tv.data.TorrentStreams.asHttp(s, server))
                else torrentHelp(s)
            }
            StremioStream.Kind.NONE -> {}
        }
    }

    /** Brak serwera torrent: adres serwera w sieci, zewnętrzna aplikacja (link magnet) albo inne źródło. */
    fun torrentHelp(s: StremioStream) {
        Ui.dialog(this).setTitle("Źródło P2P (torrent)")
            .setMessage("Do odtwarzania źródeł P2P we wbudowanym odtwarzaczu potrzebny jest serwer strumieniujący torrenty:\n\n" +
                "• TorrServer – aplikacja na ten telewizor (działa sama w tle) albo na komputerze,\n" +
                "• Stremio na komputerze w tej samej sieci (serwer Stremio, port 11470).\n\n" +
                "Nie znaleziono serwera" + pl.iptv.tv.data.Prefs.torrentServer.ifBlank { "" }.let { if (it.isBlank()) " na tym urządzeniu." else " pod adresem $it." } +
                " Podaj adres serwera albo otwórz źródło w zewnętrznej aplikacji.")
            .setPositiveButton("Adres serwera…") { _, _ -> askTorrentServer { play(s) } }
            .setNeutralButton("Zewnętrzna aplikacja") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:${s.infoHash}&dn=" + Uri.encode(s.headline)))) }
                    .onFailure { I18n.makeToast(this, "Brak aplikacji obsługującej linki magnet", android.widget.Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Anuluj", null).show()
    }

    /** Adres serwera torrent (np. 192.168.1.10:11470); pusty – szukanie na tym urządzeniu. Sprawdzany od razu. */
    fun askTorrentServer(onOk: () -> Unit = {}) {
        Dialogs.input(this, "Serwer torrent (P2P)", pl.iptv.tv.data.Prefs.torrentServer, "np. 192.168.1.10:11470 albo 127.0.0.1:8090") { v ->
            pl.iptv.tv.data.Prefs.torrentServer = v.trim()
            lifecycleScope.launch {
                val found = pl.iptv.tv.data.TorrentStreams.find()
                I18n.makeToast(this@StremioDetailActivity, found?.let { "Połączono: ${it.label}" } ?: "Serwer nie odpowiada", android.widget.Toast.LENGTH_LONG).show()
                if (found != null) onOk()
            }
        }
    }

    // ---------------- listy ----------------

    inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        // (7.4) stałe identyfikatory: odświeżenie listy zachowuje widoki, więc i zaznaczony odcinek
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        var items: List<StremioVideo> = emptyList()
        var selected = ""
        inner class VH(val root: LinearLayout, val t: TextView, val s: TextView, val badge: TextView) : RecyclerView.ViewHolder(root)
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8)); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) } }
            val t = StremioUi.text(parent.context, "", 15f, lines = 2); val s = StremioUi.text(parent.context, "", 12f, "text2", lines = 1)
            // (7.11) znaczek „Obejrzany”: ciemna kapsuła z obwódką i tekstem w kolorze akcentu motywu (czytelny także na zaznaczeniu)
            val badge = TextView(parent.context).apply {
                text = "✓ Obejrzany"; textSize = 11f; tag = "keepcolor"; maxLines = 1; visibility = View.GONE
                setTextColor(Ui.accent or 0xFF000000.toInt()); setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2))
                background = GradientDrawable().apply { cornerRadius = Ui.dp(10).toFloat(); setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xCC000000.toInt())); setStroke(Ui.dp(1), Ui.accent or 0xFF000000.toInt()) }
            }
            val sub = LinearLayout(parent.context).apply { gravity = Gravity.CENTER_VERTICAL }
            sub.addView(s, LinearLayout.LayoutParams(0, -2, 1f)); sub.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6) })
            root.addView(t); root.addView(sub)
            val vh = VH(root, t, s, badge)
            root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let {
                // OK: źródła od razu (bez opóźnienia przewijania), kursor na pierwsze źródło, gdy się wczyta
                if (videoId == it.id && sourcesPending) loadSources()      // czekało na zatrzymanie kursora – od razu
                else selectEpisode(it)
                focusSourcesWhenReady()
            } }
            // (7.11) długie OK: menu z ręcznym zaznaczeniem / odznaczeniem „obejrzany”
            root.setOnLongClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { episodeMenu(it) }; true }
            // zaznaczenie odcinka pilotem wczytuje jego źródła (bez OK) – jak w Stremio; z krótkim opóźnieniem przy przewijaniu
            root.setOnFocusChangeListener { _, f -> if (f) items.getOrNull(vh.bindingAdapterPosition)?.let { selectEpisode(it, debounce = true) } }
            Ui.style(root)
            return vh
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.t.text = "${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }
            val p = progress?.takeIf { it.videoId == e.id }
            h.s.text = listOfNotNull(e.released.ifBlank { null }, p?.let { "▶ " + StremioUi.fmt(it.positionMs) }).joinToString("  ·  ")
            h.badge.visibility = if (e.id in watched) View.VISIBLE else View.GONE
            h.root.isActivated = e.id == selected
        }
    }

    // ---------------- (7.11) obejrzane odcinki ----------------

    /** Odcinki zaliczone jako obejrzane (Room); testy. */
    var watched: Set<String> = emptySet(); private set

    private fun refreshWatched() {
        lifecycleScope.launch {
            watched = StremioStore.watchedIds(metaId)
            if (::episodes.isInitialized) episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount)
            updateSeasonLabels()
        }
    }

    /** Podpis przycisków sezonów z procentem obejrzanego sezonu (zaliczone odcinki / odcinki sezonu). */
    private fun updateSeasonLabels() {
        val m = meta ?: return
        for (i in 0 until seasonsBar.childCount) {
            val s = m.seasons.getOrNull(i) ?: continue
            val pct = StremioStore.seasonPercent(m, s, watched)
            (seasonsBar.getChildAt(i) as? TextView)?.text = (if (s == 0) "Dodatki" else "Sezon $s") + if (pct > 0) " · $pct%" else ""
        }
    }

    private fun episodeMenu(e: StremioVideo) {
        val on = e.id in watched
        Dialogs.menu(this, "${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }, listOf(
            (if (on) "↺ Odznacz jako obejrzany" else "✔ Oznacz jako obejrzany") to { setWatched(e, !on) },
        ))
    }

    private fun seasonMenu(s: Int) {
        val m = meta ?: return
        val name = if (s == 0) "Dodatki" else "Sezon $s"
        val all = StremioStore.seasonPercent(m, s, watched) == 100
        Dialogs.menu(this, name, listOf(
            (if (all) "↺ Odznacz cały sezon jako obejrzany" else "✔ Oznacz cały sezon jako obejrzany") to { setSeasonWatched(s, !all) },
        ))
    }

    /** (7.12) Cały sezon obejrzany / nieobejrzany: od razu na ekranie (ptaszki odcinków, procent), zapis w tle. */
    fun setSeasonWatched(s: Int, on: Boolean) {
        val m = meta ?: return
        val ids = m.episodes(s).map { it.id }
        watched = if (on) watched + ids else watched - ids.toSet()
        if (::episodes.isInitialized) episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount)
        updateSeasonLabels()
        lifecycleScope.launch { StremioStore.setSeasonWatched(m, s, m.poster.ifBlank { intent.getStringExtra(EXTRA_POSTER).orEmpty() }, on) }
    }

    fun setWatched(e: StremioVideo, on: Boolean) {
        watched = if (on) watched + e.id else watched - e.id        // od razu na ekranie, zapis w tle
        val i = episodeAdapter.items.indexOf(e).takeIf { it >= 0 }
        if (i != null) episodeAdapter.notifyItemChanged(i)
        updateSeasonLabels()
        val m = meta
        lifecycleScope.launch { StremioStore.setWatched(metaId, e, "series", m?.name ?: title.text.toString(), m?.poster ?: intent.getStringExtra(EXTRA_POSTER).orEmpty(), on) }
    }

    /**
     * Źródła pogrupowane według rozdzielczości w zwijanych grupach (4K, 1440p, 1080p, 720p, 480p, inna, CAM): OK na grupie
     * rozwija / zwija jej źródła (z nazwą wtyczki), na starcie rozwinięta najwyższa dostępna. W grupie najpierw polska wersja.
     * Wtyczki wczytują się równolegle – kolejne wyniki dopisywane bez utraty zaznaczenia (fokus wraca na ten sam element).
     */
    inner class SourceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        // (7.4) stałe identyfikatory: wyniki kolejnych wtyczek dopisywane bez utraty zaznaczenia (te same widoki)
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = keyOf(items[position]).hashCode().toLong()
        var items: List<SourceItem> = emptyList(); private set
        /** Wszystkie źródła w kolejności wyświetlania (także w zwiniętych grupach). */
        var sourceItems: List<StremioStream> = emptyList(); private set
        private var byQuality: List<Pair<String, List<StremioStream>>> = emptyList()
        private var notes: List<SourceItem> = emptyList()
        /** Rozwinięte grupy; null – jeszcze bez wyboru użytkownika ((7.7) wszystkie zwinięte, także 4K – OK rozwija). */
        private var expanded: MutableSet<String>? = null
        val expandedNow: Set<String> get() = expanded ?: emptySet()

        /** Czysta funkcja (bez dostępu do widoków): grupy rozdzielczości z polską wersją na górze każdej grupy, komunikaty. */
        fun prepare(gs: List<StremioSourceGroup>): SourcePrepared {
            val all = gs.flatMap { it.streams }
            val groups = all.groupBy { it.quality }
            val order = StremioStreams.QUALITY_ORDER + (groups.keys - StremioStreams.QUALITY_ORDER.toSet())
            // sortowanie stabilne: polska wersja (dubbing / lektor, potem napisy) na górze rozdzielczości, dalej kolejność wtyczki
            val by = order.mapNotNull { q -> groups[q]?.let { l -> q to l.map { it to StremioStreams.polishRank(it) }.sortedBy { it.second }.map { it.first } } }
            val notes = buildList<SourceItem> {
                // (7.11) błędy i przekroczenia czasu wtyczek są pomijane po cichu (bez komunikatów w trakcie wyszukiwania)
                // (7.7) jeden, jednolity wiersz zamiast listy wczytywanych wtyczek
                if (gs.any { it.loading }) add(SourceItem.Loading)
                if (all.isEmpty() && gs.none { it.loading }) add(SourceItem.Note("Brak źródeł dla tego tytułu we włączonych wtyczkach."))
            }
            return SourcePrepared(by, by.flatMap { it.second }, notes)
        }

        fun show(p: SourcePrepared) { byQuality = p.byQuality; sourceItems = p.sourceItems; notes = p.notes; rebuild() }

        fun set(gs: List<StremioSourceGroup>, empty: String?) {
            if (empty != null) { byQuality = emptyList(); sourceItems = emptyList(); notes = listOf(SourceItem.Note(empty)); rebuild(); return }
            show(prepare(gs))
        }

        private fun build(): List<SourceItem> = byQuality.flatMap { (q, l) ->
            val open = q in expandedNow
            listOf(SourceItem.Quality(q, l.size, l.count { StremioStreams.polishRank(it) < 2 }, open)) + if (open) l.map { SourceItem.Source(it) } else emptyList()
        } + notes

        /** Nowa lista z zachowaniem zaznaczenia (grupa / źródło o tym samym kluczu) – fokus nie przeskakuje przy dopisywaniu wyników. */
        private fun rebuild() {
            val focusedKey = sources.findFocus()?.let { f -> sources.findContainingViewHolder(f)?.bindingAdapterPosition }?.let { items.getOrNull(it)?.let(::keyOf) }
            items = build()
            notifyDataSetChanged()
            if (focusedKey != null) {
                val p = items.indexOfFirst { keyOf(it) == focusedKey }
                // (7.7) kursor był na „Ładowanie źródeł”, a wiersz zniknął (wszystkie wtyczki odpowiedziały) – pierwsze źródło / grupa
                val hasPick = items.any { it is SourceItem.Source || it is SourceItem.Quality }
                val target = if (p >= 0) p else if (focusedKey == "loading" && hasPick) firstSource() else -1
                if (target >= 0) sources.post { sources.focusPosition(target) }
                // wtyczki nic nie zwróciły (same komunikaty – nic do zaznaczenia): kursor wraca na wybrany odcinek / listę
                else if (focusedKey == "loading") backToEpisodes()
            }
        }

        /** (7.7) Tylko „Ładowanie źródeł” (nowy odcinek / film – przed odpowiedzią wtyczek). */
        fun showLoading() { byQuality = emptyList(); sourceItems = emptyList(); notes = listOf(SourceItem.Loading); rebuild() }

        private fun keyOf(i: SourceItem) = when (i) { is SourceItem.Quality -> "q:" + i.q; is SourceItem.Source -> "s:" + i.s.url + i.s.infoHash + i.s.name; is SourceItem.Note -> "n:" + i.t; SourceItem.Loading -> "loading" }

        /** OK na grupie: rozwinięcie / zwinięcie bez przebudowy listy (wstawienie / usunięcie pozycji) – zaznaczenie zostaje na grupie. */
        fun toggle(q: String) {
            val set = expanded ?: expandedNow.toMutableSet().also { expanded = it }
            val p = items.indexOfFirst { it is SourceItem.Quality && it.q == q }.takeIf { it >= 0 } ?: return
            val n = byQuality.firstOrNull { it.first == q }?.second?.size ?: 0
            if (q in set) set -= q else set += q
            items = build()
            notifyItemChanged(p)
            if (q in set) notifyItemRangeInserted(p + 1, n) else notifyItemRangeRemoved(p + 1, n)
        }

        fun firstSource() = items.indexOfFirst { it is SourceItem.Source }.takeIf { it >= 0 } ?: items.indexOfFirst { it is SourceItem.Quality }.takeIf { it >= 0 }
            ?: items.indexOf(SourceItem.Loading).coerceAtLeast(0)
        fun qualityName(q: String) = when (q) { "4K" -> "4K UHD"; "" -> "Inna jakość"; "CAM" -> "CAM (nagranie z kina)"; else -> q }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) { is SourceItem.Quality -> 0; is SourceItem.Source -> 1; is SourceItem.Note -> 2; SourceItem.Loading -> 3 }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (viewType) {
                0 -> LinearLayout(c).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                    setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(14), Ui.dp(10))
                    addView(StremioUi.text(c, "", 17f, bold = true, lines = 1), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(StremioUi.text(c, "", 13f, "text2", lines = 1))
                }
                1 -> LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                    setPadding(Ui.dp(26), Ui.dp(9), Ui.dp(14), Ui.dp(9))
                    addView(StremioUi.text(c, "", 15f, bold = true, lines = 1)); addView(StremioUi.text(c, "", 13f, "text2", lines = 2))
                }
                // (7.7) „Ładowanie źródeł” – kółko postępu w kolorze akcentu motywu i jeden napis, zaznaczalne
                3 -> LinearLayout(c).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                    setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12))
                    addView(android.widget.ProgressBar(c).apply {
                        // kolor tekstu zaznaczenia / zwykłego (na zaznaczeniu w kolorze akcentu kółko nie znika)
                        isIndeterminate = true; isDuplicateParentStateEnabled = true; indeterminateTintList = Ui.textColors(Ui.text)
                    }, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)))
                    addView(StremioUi.text(c, LOADING_TEXT, 16f, "accent", bold = true, lines = 1).apply { setPadding(Ui.dp(12), 0, 0, 0) })
                    contentDescription = LOADING_TEXT
                }
                else -> StremioUi.text(c, "", 14f, "text2").apply { setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(6), Ui.dp(6)) }
            }
            v.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) }
            val vh = object : RecyclerView.ViewHolder(v) {}
            if (viewType == 1) v.setOnClickListener { (items.getOrNull(vh.bindingAdapterPosition) as? SourceItem.Source)?.let { play(it.s) } }
            if (viewType == 0) v.setOnClickListener { (items.getOrNull(vh.bindingAdapterPosition) as? SourceItem.Quality)?.let { toggle(it.q) } }
            Ui.style(v)
            return vh
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is SourceItem.Quality -> (h.itemView as LinearLayout).let { box ->
                    (box.getChildAt(0) as TextView).text = (if (it.open) "▾  " else "▸  ") + qualityName(it.q)
                    (box.getChildAt(1) as TextView).text = listOfNotNull("${it.count} " + StremioUi.plural(it.count, "źródło", "źródła", "źródeł"),
                        it.polish.takeIf { n -> n > 0 }?.let { n -> "🇵🇱 $n" }).joinToString("   ·   ")
                    h.itemView.isActivated = it.open
                }
                is SourceItem.Note -> (h.itemView as TextView).text = it.t
                SourceItem.Loading -> {}
                is SourceItem.Source -> (h.itemView as LinearLayout).let { box ->
                    val s = it.s
                    (box.getChildAt(0) as TextView).text = s.badges.ifBlank { s.name.replace('\n', ' ') }
                    (box.getChildAt(1) as TextView).text = "🧩 ${s.addonName}  ·  ${s.headline}"
                }
            }
        }
    }
}

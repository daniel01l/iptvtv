package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMeta
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioSourceGroup
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StremioVideo

/** Pozycja listy źródeł: nagłówek wtyczki, źródło, komunikat. */
sealed class SourceItem { data class Header(val g: StremioSourceGroup) : SourceItem(); data class Source(val s: StremioStream) : SourceItem(); data class Note(val t: String) : SourceItem() }

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
    private var season = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(build())
        title.text = intent.getStringExtra(EXTRA_NAME)
        intent.getStringExtra(EXTRA_POSTER)?.takeIf { it.isNotBlank() }?.let { poster.load(it) { crossfade(false) } }
        lifecycleScope.launch {
            progress = StremioStore.progressOf(metaId)
            val m = StremioMetas.get(type, metaId)
            meta = m
            if (m == null) { desc.text = "Nie udało się wczytać informacji – źródła poniżej."; videoId = metaId; loadSources(); return@launch }
            bind(m)
        }
    }

    override fun onResume() {
        super.onResume()
        if (meta != null) lifecycleScope.launch { progress = StremioStore.progressOf(metaId); updateResume(); episodeAdapter.notifyDataSetChanged() }
    }

    private fun build(): View {
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
        resume = StremioUi.button(this, "▶ Wznów") { resumePlayback() }.apply { visibility = View.GONE }
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
        if (m.poster.isNotBlank()) poster.load(m.poster) { crossfade(false) }
        if (m.background.isNotBlank()) bg.load(m.background) { crossfade(false) }
        updateResume()
        if (m.isSeries && m.videos.isNotEmpty()) {
            episodesCol.visibility = View.VISIBLE
            val want = intent.getStringExtra(EXTRA_VIDEO)?.takeIf { it.isNotBlank() } ?: progress?.videoId
            val start = m.videos.firstOrNull { it.id == want }
            season = start?.season ?: m.seasons.first()
            seasonsBar.removeAllViews()
            m.seasons.forEach { s ->
                seasonsBar.addView(StremioUi.button(this, if (s == 0) "Dodatki" else "Sezon $s") { selectSeason(s, null) }.apply { textSize = 14f },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(6) })
            }
            Ui.style(seasonsBar)
            selectSeason(season, start)
        } else {
            videoId = m.videos.singleOrNull()?.id?.takeIf { it.isNotBlank() } ?: m.id
            loadSources()
        }
    }

    private fun updateResume() {
        val p = progress?.takeIf { it.positionMs > 10_000 && !it.finished }
        resume.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) resume.text = "▶ Wznów " + listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" · ")
    }

    private fun resumePlayback() {
        val p = progress ?: return
        val m = meta
        if (m?.isSeries == true) m.videos.firstOrNull { it.id == p.videoId }?.let { selectSeason(it.season, it) }
        // źródła wczytane – fokus na pierwszym (wznowienie od zapisanej pozycji w odtwarzaczu)
        sources.post { sources.focusPosition(sourceAdapter.firstSource()) }
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

    fun selectEpisode(e: StremioVideo) {
        if (videoId == e.id) return
        videoId = e.id
        episodeAdapter.selected = e.id; episodeAdapter.notifyDataSetChanged()
        loadSources()
    }

    /** Źródła z włączonych wtyczek (równolegle), po [StremioStreams.PER_ADDON] na wtyczkę z każdej rozdzielczości. */
    fun loadSources() {
        sourcesJob?.cancel()
        val vid = videoId
        val streamType = if (meta?.isSeries == true) "series" else type
        val label = meta?.videos?.firstOrNull { it.id == vid }?.label?.takeIf { meta?.isSeries == true }
        sourcesTitle.text = "Źródła" + (label?.let { " – $it" } ?: "")
        sourcesJob = lifecycleScope.launch {
            val addons = StremioStore.enabledAddons().filter { it.supports("stream", streamType, vid) }
            if (addons.isEmpty()) {
                groups = emptyList(); sourceAdapter.set(emptyList(), "Brak włączonych wtyczek ze źródłami dla tego tytułu. Włącz je w Stremio → 🧩 Wtyczki."); return@launch
            }
            groups = addons.map { StremioSourceGroup(it, emptyList(), loading = true) }
            sourceAdapter.set(groups, null)
            addons.mapIndexed { i, a -> async {
                val r = runCatching { StremioApi.streams(a, streamType, vid) }
                groups = groups.toMutableList().also { g -> g[i] = r.fold({ StremioSourceGroup(a, StremioStreams.limit(it), total = it.size) }, { StremioSourceGroup(a, emptyList(), it.message ?: "błąd") }) }
                if (videoId == vid) sourceAdapter.set(groups, null)
            } }.forEach { it.await() }
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
            StremioStream.Kind.TORRENT -> Ui.dialog(this).setTitle("Źródło P2P (torrent)")
                .setMessage("To źródło wymaga silnika torrent (serwera strumieniowego Stremio), którego nie ma we wbudowanym odtwarzaczu. " +
                    "Wybierz źródło z linkiem HTTP (np. wtyczka z usługą Debrid) albo otwórz je w zewnętrznej aplikacji.")
                .setPositiveButton("Otwórz w aplikacji") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:${s.infoHash}&dn=" + Uri.encode(s.headline)))) }
                        .onFailure { android.widget.Toast.makeText(this, "Brak aplikacji obsługującej linki magnet", android.widget.Toast.LENGTH_SHORT).show() }
                }.setNegativeButton("Anuluj", null).show()
            StremioStream.Kind.NONE -> {}
        }
    }

    // ---------------- listy ----------------

    inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        var items: List<StremioVideo> = emptyList()
        var selected = ""
        inner class VH(val root: LinearLayout, val t: TextView, val s: TextView) : RecyclerView.ViewHolder(root)
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8)); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) } }
            val t = StremioUi.text(parent.context, "", 15f, lines = 2); val s = StremioUi.text(parent.context, "", 12f, "text2", lines = 1)
            root.addView(t); root.addView(s)
            val vh = VH(root, t, s)
            root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { selectEpisode(it); sources.post { sources.focusPosition(sourceAdapter.firstSource()) } } }
            // zaznaczenie odcinka pilotem od razu wczytuje jego źródła (bez OK) – jak w Stremio
            root.setOnFocusChangeListener { _, f -> if (f) items.getOrNull(vh.bindingAdapterPosition)?.let { selectEpisode(it) } }
            Ui.style(root)
            return vh
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.t.text = "${e.episode}. " + e.title.ifBlank { "Odcinek ${e.episode}" }
            val p = progress?.takeIf { it.videoId == e.id }
            h.s.text = listOfNotNull(e.released.ifBlank { null }, p?.let { "▶ " + StremioUi.fmt(it.positionMs) }).joinToString("  ·  ")
            h.root.isActivated = e.id == selected
        }
    }

    /** Źródła: nagłówek wtyczki, jej źródła (≤ 5) albo komunikat (wczytywanie, brak, błąd). */
    inner class SourceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<SourceItem> = emptyList(); private set

        fun set(gs: List<StremioSourceGroup>, empty: String?) {
            items = if (empty != null) listOf(SourceItem.Note(empty)) else gs.flatMap { g ->
                listOf(SourceItem.Header(g)) + when {
                    g.loading -> listOf(SourceItem.Note("Wczytywanie…"))
                    g.error != null -> listOf(SourceItem.Note("Błąd: ${g.error}"))
                    g.streams.isEmpty() -> listOf(SourceItem.Note("Brak źródeł"))
                    else -> g.streams.map { SourceItem.Source(it) }
                }
            }
            notifyDataSetChanged()
        }
        fun firstSource() = items.indexOfFirst { it is SourceItem.Source }.coerceAtLeast(0)
        val sourceItems get() = items.filterIsInstance<SourceItem.Source>().map { it.s }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) { is SourceItem.Header -> 0; is SourceItem.Source -> 1; is SourceItem.Note -> 2 }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (viewType) {
                0 -> StremioUi.text(c, "", 16f, "accent", bold = true, lines = 1).apply { setPadding(Ui.dp(6), Ui.dp(12), Ui.dp(6), Ui.dp(4)) }
                1 -> LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                    setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9))
                    addView(StremioUi.text(c, "", 15f, bold = true, lines = 1)); addView(StremioUi.text(c, "", 13f, "text2", lines = 2))
                }
                else -> StremioUi.text(c, "", 14f, "text2").apply { setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(6), Ui.dp(6)) }
            }
            v.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) }
            val vh = object : RecyclerView.ViewHolder(v) {}
            if (viewType == 1) v.setOnClickListener { (items.getOrNull(vh.bindingAdapterPosition) as? SourceItem.Source)?.let { play(it.s) } }
            Ui.style(v)
            return vh
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is SourceItem.Header -> (h.itemView as TextView).text = "🧩 " + it.g.addon.name + (if (it.g.total > it.g.streams.size) "   (${it.g.streams.size} z ${it.g.total})" else "")
                is SourceItem.Note -> (h.itemView as TextView).text = it.t
                is SourceItem.Source -> (h.itemView as LinearLayout).let { box ->
                    val s = it.s
                    (box.getChildAt(0) as TextView).text = s.badges.ifBlank { s.name.replace('\n', ' ') }
                    (box.getChildAt(1) as TextView).text = s.headline
                }
            }
        }
    }
}

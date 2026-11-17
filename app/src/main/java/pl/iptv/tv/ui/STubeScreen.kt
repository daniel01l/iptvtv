package pl.iptv.tv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtChannel
import pl.iptv.tv.data.YtPlaylist
import pl.iptv.tv.data.YtSource
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtException
import pl.iptv.tv.data.StubeHistory

/** Kafelek rzędu STube. */
sealed class STubeTile {
    data class Video(val v: YtVideo) : STubeTile()
    data class Channel(val c: YtChannel) : STubeTile()
    /** Kafelek akcji (zaloguj, szukaj w SmartTube, ponów) – tej samej wielkości co film. */
    data class Action(val id: String, val icon: String, val label: String) : STubeTile()
    /** Playlista Biblioteki (lokalna, Polubione, konta) albo kanał – OK otwiera jej filmy w rzędzie poniżej. */
    data class Playlist(val p: YtPlaylist) : STubeTile()
}

/** Strona danych rzędu: kafelki i token następnej strony. */
data class STubePage(val tiles: List<STubeTile>, val next: String? = null)

/** Rząd: tytuł + loader stron (YouTube Data API v3 przez [YouTubeRepository]). */
class STubeRow(val id: String, val title: String, val load: suspend (page: String?) -> STubePage) {
    val tiles = mutableListOf<STubeTile>()
    var next: String? = null
    var loading = false
    var error: String? = null
    /** Ostatnio zaznaczony kafelek (powrót ▲▼ na tę samą pozycję – jak w Leanback). */
    var lastFocus = 0
    /** Rząd playlisty: otwarta playlista ([STubeScreen.playlistRow]). */
    var source: YtPlaylist? = null
    val adapter = STubeTileAdapter(this)
}

/**
 * Ekran STube – układ rzędów i kafelków (10-foot UI): duże kafelki 16:9, wyraźny fokus wg motywu
 * (ramka/poświata i powiększenie z [Ui.style]), brak drobnych elementów – cały kafelek jest jednym celem OK.
 */
object STubeScreen {
    const val ROW_SEARCH = "search"; const val ROW_FOR_YOU = "for_you"; const val ROW_TRENDING = "trending"
    const val ROW_MUSIC = "music"; const val ROW_SUBS = "subs"; const val ROW_LOGIN = "login"
    const val ACT_LOGIN = "login"; const val ACT_SMARTTUBE_SEARCH = "st_search"; const val ACT_RETRY = "retry"

    /** Wymiary kafelków (dp). */
    const val TILE_W = 236; const val THUMB_H = 126; const val CHANNEL_W = 150; const val AVATAR = 96

    /**
     * Kolejność rzędów: [wyniki], Dla Ciebie (konto, źródło „Moje”) albo zachęta do logowania, Na czasie, Muzyka, Subskrypcje (konto).
     * Czysta funkcja – testowana bez sieci.
     */
    const val ROW_LIVE = "live"
    /** „Kontynuuj oglądanie” – rozpoczęte filmy z lokalnej bazy ([StubeHistory]). */
    const val ROW_CONTINUE = "continue"
    /** Payload odświeżenia samego paska obejrzenia (bez ponownego wiązania kafelka – fokus zostaje). */
    const val PAYLOAD_PROGRESS = "progress"
    /** Wysokość paska obejrzenia na miniaturze (dp) – jak w YouTube: cienki pasek przy dolnej krawędzi. */
    const val PROGRESS_H = 4
    /** Biblioteka (playlisty), kanały z subskrypcji i rząd otwartej playlisty / kanału (wstawiany pod rząd, z którego otwarto). */
    const val ROW_LIBRARY = "library"; const val ROW_CHANNELS = "channels"; const val ROW_PLAYLIST = "playlist"
    /** „Twoje miksy” – gotowe playlisty z historii ([StubeMixes]); id playlisty miksu: [PL_MIX] + id. */
    const val ROW_MIXES = "mixes"; const val PL_MIX = "mix:"
    /** Identyfikatory playlist specjalnych (nie są playlistami YouTube). */
    const val PL_LATER = "local:later"; const val PL_HISTORY = "local:history"; const val PL_LIKED = "liked"; const val PL_CHANNEL = "channel:"

    /** Kanał jako „playlista” (jego najnowsze filmy). */
    fun channelPlaylist(c: YtChannel) = YtPlaylist(PL_CHANNEL + c.id, c.title, c.thumb)

    /** Ikona kafelka playlisty bez miniatury. */
    fun playlistIcon(id: String) = when {
        id == PL_LATER -> "🕘"; id == PL_HISTORY -> "📜"; id.startsWith(PL_MIX) -> "🎵"; id == PL_LIKED -> "👍"; id.startsWith(PL_CHANNEL) -> "📺"; else -> "☰"
    }

    /** Kafelki Biblioteki: listy lokalne zawsze, z kontem – Polubione i playlisty konta (błąd API nie ukrywa list lokalnych). */
    suspend fun libraryPage(loggedIn: Boolean, page: String?): STubePage {
        val local = if (page == null) listOf(
            YtPlaylist(PL_LATER, "Do obejrzenia później", count = StubeHistory.watchLaterCount),
            YtPlaylist(PL_HISTORY, "Historia oglądania"),
        ) + (if (loggedIn) listOf(YtPlaylist(PL_LIKED, "Polubione filmy")) else emptyList()) else emptyList()
        if (!loggedIn) return STubePage(local.map(STubeTile::Playlist))
        val (mine, next) = try { YouTubeRepository.myPlaylists(page) } catch (e: YtException) {
            android.util.Log.w("STube", "playlisty: ${e.message}"); emptyList<YtPlaylist>() to null
        }
        return STubePage((local + mine).map(STubeTile::Playlist), next)
    }

    /** Rząd z filmami playlisty / kanału [p]. */
    fun playlistRow(p: YtPlaylist): STubeRow = STubeRow(ROW_PLAYLIST, playlistIcon(p.id) + "  " + p.title) { page ->
        when {
            p.id == PL_LATER -> STubePage(StubeHistory.watchLater().map { STubeTile.Video(it.toVideo()) })
            p.id == PL_HISTORY -> STubePage(StubeHistory.watched().map { STubeTile.Video(it.toVideo()) })
            p.id.startsWith(PL_MIX) -> STubePage((pl.iptv.tv.data.StubeMixes.byId(p.id.removePrefix(PL_MIX))?.let { pl.iptv.tv.data.StubeMixes.videos(it) }.orEmpty()).map(STubeTile::Video))
            p.id == PL_LIKED -> YouTubeRepository.liked(page).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
            p.id.startsWith(PL_CHANNEL) -> YouTubeRepository.playlistVideos(YouTubeRepository.uploadsPlaylist(p.id.removePrefix(PL_CHANNEL)), page)
                .let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
            else -> YouTubeRepository.playlistVideos(p.id, page).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
        }
    }.also { it.source = p }

    fun rowIds(loggedIn: Boolean, personal: Boolean, query: String?, hasContinue: Boolean = false, hasMixes: Boolean = false): List<String> = buildList {
        if (!query.isNullOrBlank()) add(ROW_SEARCH)
        if (hasContinue) add(ROW_CONTINUE)
        if (hasMixes) add(ROW_MIXES)
        if (loggedIn && personal) add(ROW_FOR_YOU) else if (!loggedIn) add(ROW_LOGIN)
        // najnowsze z subskrypcji zaraz pod „Dla Ciebie” (jak karta Subskrypcje w YouTube)
        if (loggedIn) add(ROW_SUBS)
        add(ROW_TRENDING)
        add(ROW_MUSIC)
        add(ROW_LIVE)
        // Biblioteka zawsze (listy lokalne: Do obejrzenia później, Historia); z kontem – Polubione i playlisty konta
        add(ROW_LIBRARY)
        if (loggedIn) add(ROW_CHANNELS)
    }

    /**
     * Dane z YouTube Data API (klucz lub konto), a bez dostępu do API albo przy jego błędzie (limit, wyłączone API) –
     * listy bez klucza ([YtKeyless]). STube pokazuje filmy także bez żadnej konfiguracji Google.
     */
    private suspend fun apiOr(api: suspend () -> STubePage, keyless: suspend () -> List<YtVideo>): STubePage {
        if (YouTubeRepository.publicAvailable) {
            try { return api() } catch (e: YtException) { android.util.Log.w("STube", "API: ${e.message} – listy bez klucza") }
        }
        return STubePage(keyless().map(STubeTile::Video))
    }

    /** Rzędy z loaderami (repozytorium konta YouTube + listy bez klucza). */
    fun rows(query: String?): List<STubeRow> {
        val logged = YouTubeTokens.loggedIn
        val personal = YouTubeRepository.source == YtSource.PERSONAL
        return rowIds(logged, personal, query, StubeHistory.hasUnfinished, StubeHistory.historyCount >= pl.iptv.tv.data.StubeMixes.MIN_HISTORY).map { id ->
            when (id) {
                // gotowe miksy z historii oglądania (Room) – filmy z YouTube po otwarciu
                ROW_MIXES -> STubeRow(id, "🎵 Twoje miksy / gotowe playlisty") { _ ->
                    STubePage(pl.iptv.tv.data.StubeMixes.current().map { STubeTile.Playlist(YtPlaylist(PL_MIX + it.id, it.title, it.thumb)) })
                }
                ROW_CONTINUE -> STubeRow(id, "Kontynuuj oglądanie") { _ -> STubePage(StubeHistory.unfinished().map { STubeTile.Video(it.toVideo()) }) }
                // wyszukiwanie bez klucza (nie zużywa limitu API: 100 jednostek za zapytanie); API – zapasowo
                ROW_SEARCH -> STubeRow(id, "Wyniki: ${query!!.trim()}") { _ ->
                    val found = try { YtKeyless.search(query) } catch (e: YtException) {
                        if (YouTubeRepository.publicAvailable) YouTubeRepository.search(query) else throw e
                    }
                    STubePage(found.map { STubeTile.Video(it) } + STubeTile.Action(ACT_SMARTTUBE_SEARCH, "🔎", "Szukaj „${query.trim()}” w SmartTube"))
                }
                ROW_FOR_YOU -> STubeRow(id, "Dla Ciebie") { p -> YouTubeRepository.feed(YtSource.PERSONAL, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }
                ROW_LOGIN -> STubeRow(id, "Dla Ciebie") { _ ->
                    STubePage(listOf(STubeTile.Action(ACT_LOGIN, "👤", "Zaloguj się do YouTube, aby zobaczyć polecane i subskrypcje")))
                }
                ROW_TRENDING -> STubeRow(id, "Na czasie") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(null, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.PODCASTS) }
                }
                ROW_MUSIC -> STubeRow(id, "Muzyka") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(YouTubeRepository.CAT_MUSIC, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.MUSIC) }
                }
                ROW_LIVE -> STubeRow(id, "Na żywo") { _ -> STubePage(YtKeyless.kiosk(YtKeyless.LIVE).map(STubeTile::Video)) }
                // najnowsze filmy z subskrybowanych kanałów (playlisty „przesłane”, scalone po dacie)
                ROW_SUBS -> STubeRow(id, "Subskrypcje") { _ -> STubePage(YouTubeRepository.subscriptionFeed().map(STubeTile::Video)) }
                ROW_LIBRARY -> STubeRow(id, "Biblioteka / Playlisty") { p -> libraryPage(logged, p) }
                else -> STubeRow(id, "Kanały") { _ -> STubePage(YouTubeRepository.subscriptions().map(STubeTile::Channel)) }
            }
        }
    }

    /** Wiek filmu z daty publikacji ISO 8601 („3 godz. temu”, „2 dni temu”) – podpis kafelków subskrypcji; null przy braku daty. */
    fun ago(iso: String, now: Long = System.currentTimeMillis()): String? {
        if (iso.isBlank()) return null
        val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return null
        val min = ((now - t) / 60_000L).coerceAtLeast(0)
        return when {
            min < 60 -> "${min.coerceAtLeast(1)} min temu"
            min < 24 * 60 -> "${min / 60} godz. temu"
            min < 2 * 24 * 60 -> "wczoraj"
            min < 30L * 24 * 60 -> "${min / (24 * 60)} dni temu"
            min < 365L * 24 * 60 -> "${min / (30L * 24 * 60)} mies. temu"
            else -> (min / (365L * 24 * 60)).let { y -> if (y == 1L) "rok temu" else if (y % 10 in 2..4 && y % 100 !in 12..14) "$y lata temu" else "$y lat temu" }
        }
    }

    /** Czas z ISO 8601 (PT1H2M3S) → „1:02:03”; puste dla transmisji na żywo. */
    fun duration(iso: String): String {
        val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").find(iso) ?: return ""
        val (d, h, min, s) = m.destructured
        val hours = (d.toIntOrNull() ?: 0) * 24 + (h.toIntOrNull() ?: 0)
        val mm = min.toIntOrNull() ?: 0; val ss = s.toIntOrNull() ?: 0
        if (hours == 0 && mm == 0 && ss == 0) return ""
        return if (hours > 0) "%d:%02d:%02d".format(hours, mm, ss) else "%d:%02d".format(mm, ss)
    }
}

/** Kafelki jednego rzędu (poziomy RecyclerView). */
class STubeTileAdapter(private val row: STubeRow) : RecyclerView.Adapter<STubeTileAdapter.VH>() {
    var onClick: (STubeTile) -> Unit = {}
    var onLongClick: (STubeTile) -> Unit = {}
    var onFocus: (Int) -> Unit = {}

    class VH(val root: LinearLayout, val thumb: ImageView, val badge: TextView, val icon: TextView, val title: TextView, val sub: TextView,
             val progress: WatchBar?) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = row.tiles.size
    override fun getItemViewType(position: Int) = if (row.tiles[position] is STubeTile.Channel) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val channel = viewType == 1
        val lay = StubeTheme.layout
        val w = Ui.dp(if (channel) STubeScreen.CHANNEL_W else lay.tileW)
        val imgW = w - Ui.dp(12); val imgH = Ui.dp(if (channel) STubeScreen.AVATAR else lay.thumbH)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
            setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8))
            gravity = if (channel) Gravity.CENTER_HORIZONTAL else Gravity.START
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(lay.gap) }
        }
        val frame = FrameLayout(c)
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x30)); cornerRadius = Ui.dp(8).toFloat() }
        }
        frame.addView(thumb, FrameLayout.LayoutParams(if (channel) imgH else imgW, imgH))
        Ui.roundClip(thumb, if (channel) imgH / 2f else Ui.dp(maxOf(Ui.preset.radius - 4, 6)).toFloat())
        // ikona kafelka akcji (zamiast miniatury)
        val icon = TextView(c).apply { textSize = 44f; gravity = Gravity.CENTER; visibility = View.GONE }
        frame.addView(icon, FrameLayout.LayoutParams(imgW, imgH))
        // czas filmu w rogu miniatury (stałe kolory – czytelny na każdym obrazie)
        val badge = TextView(c).apply {
            tag = "keepcolor"; setTextColor(Color.WHITE); textSize = 13f; setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = Ui.dp(4).toFloat() }
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(6), Ui.dp(6) + Ui.dp(STubeScreen.PROGRESS_H)) })
        // pasek obejrzenia (jak w YouTube): na dole miniatury, na całą szerokość; ramka przycięta do zaokrąglenia miniatury
        val progress = if (channel) null else WatchBar(c).also { bar ->
            bar.visibility = View.GONE
            frame.addView(bar, FrameLayout.LayoutParams(imgW, Ui.dp(STubeScreen.PROGRESS_H), Gravity.BOTTOM))
            Ui.roundClip(frame, Ui.dp(maxOf(Ui.preset.radius - 4, 6)).toFloat())
        }
        root.addView(frame, LinearLayout.LayoutParams(-2, -2))
        val title = TextView(c).apply {
            textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0)
            if (channel) gravity = Gravity.CENTER_HORIZONTAL
            // stała wysokość (2 linie) – rzędy nie skaczą przy przewijaniu
            setLines(lay.titleLines)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val sub = TextView(c).apply { tag = "text2"; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), 0) }
        root.addView(sub, LinearLayout.LayoutParams(-1, -2))
        val vh = VH(root, thumb, badge, icon, title, sub, progress)
        root.setOnClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnLongClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onLongClick); true }
        root.setOnFocusChangeListener { _, f -> if (f && vh.bindingAdapterPosition >= 0) { row.lastFocus = vh.bindingAdapterPosition; onFocus(vh.bindingAdapterPosition) } }
        // styl motywu (tło kafelka ze stanem fokusu, powiększenie, przycięcie) – raz, przy tworzeniu widoku
        Ui.style(root)
        return vh
    }

    /** Payload [STubeScreen.PAYLOAD_PROGRESS] – tylko pasek obejrzenia (powrót z odtwarzacza bez migania miniatur). */
    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == STubeScreen.PAYLOAD_PROGRESS }) bindProgress(h, row.tiles.getOrNull(position))
        else onBindViewHolder(h, position)
    }

    private fun bindProgress(h: VH, t: STubeTile?) {
        val bar = h.progress ?: return
        val f = (t as? STubeTile.Video)?.v?.id?.let(StubeHistory::fractionOf)
        bar.fraction = f ?: 0f
        bar.visibility = if (f != null) View.VISIBLE else View.GONE
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        bindProgress(h, row.tiles[position])
        coil.util.CoilUtils.dispose(h.thumb)
        h.thumb.setImageDrawable(null)
        when (val t = row.tiles[position]) {
            is STubeTile.Video -> {
                h.icon.visibility = View.GONE; h.thumb.visibility = View.VISIBLE
                if (t.v.thumb.isNotBlank()) h.thumb.load(t.v.thumb) { crossfade(false) }
                val d = STubeScreen.duration(t.v.duration)
                h.badge.text = d.ifBlank { if (t.v.duration == "P0D") "● NA ŻYWO" else "" }
                h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
                h.title.text = t.v.title; h.sub.visibility = if (StubeTheme.layout.showSub) View.VISIBLE else View.GONE
                h.sub.text = t.v.channel + STubeScreen.ago(t.v.publishedAt)?.let { " · $it" }.orEmpty()
                h.root.contentDescription = t.v.title
            }
            is STubeTile.Channel -> {
                h.icon.visibility = View.GONE; h.badge.visibility = View.GONE
                if (t.c.thumb.isNotBlank()) h.thumb.load(t.c.thumb) { crossfade(false) }
                h.title.text = t.c.title; h.sub.visibility = View.GONE
                h.root.contentDescription = t.c.title
            }
            is STubeTile.Playlist -> {
                // miniatura playlisty (konto/kanał) albo duża ikona (listy lokalne); liczba filmów w rogu
                val hasThumb = t.p.thumb.isNotBlank()
                h.icon.visibility = if (hasThumb) View.GONE else View.VISIBLE; h.icon.text = STubeScreen.playlistIcon(t.p.id)
                if (hasThumb) h.thumb.load(t.p.thumb) { crossfade(false) }
                val count = if (t.p.id == STubeScreen.PL_LATER) StubeHistory.watchLaterCount else t.p.count
                h.badge.text = if (count >= 0) "☰ $count" else ""
                h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
                h.title.text = t.p.title
                h.sub.text = when {
                    t.p.id.startsWith("local:") -> "Na tym urządzeniu"
                    t.p.id.startsWith(STubeScreen.PL_MIX) -> pl.iptv.tv.data.StubeMixes.byId(t.p.id.removePrefix(STubeScreen.PL_MIX))?.subtitle ?: "Gotowy miks"
                    t.p.id == STubeScreen.PL_LIKED -> "Konto YouTube"
                    else -> "Playlista"
                }
                h.sub.visibility = View.VISIBLE
                h.root.contentDescription = t.p.title
            }
            is STubeTile.Action -> {
                h.icon.visibility = View.VISIBLE; h.icon.text = t.icon; h.badge.visibility = View.GONE
                h.title.text = t.label; h.sub.visibility = View.GONE
                h.root.contentDescription = t.label
            }
        }
    }
}

/**
 * Pasek obejrzenia na miniaturze (jak w YouTube): półprzezroczysty tor na całą szerokość i wypełnienie w kolorze akcentu motywu.
 * [fraction] 0..1 – część obejrzana ([StubeHistory.fractionOf]).
 */
class WatchBar(c: android.content.Context) : View(c) {
    var fraction = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private val track = android.graphics.Paint().apply { color = 0x66FFFFFF }
    private val fill = android.graphics.Paint().apply { color = Ui.accent or 0xFF000000.toInt() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, track)
        if (fraction > 0f) canvas.drawRect(0f, 0f, w * fraction, h, fill)
    }
}

/**
 * Podpowiedzi pola wyszukiwania STube (historia z [StubeHistory]): duży tekst, wyraźne zaznaczenie ▲▼ wg motywu (10-foot UI).
 * Lista ustawiana z zewnątrz ([set]); filtr nie zawęża – dopasowanie robi zapytanie do bazy.
 */
class STubeSuggestAdapter(private val c: android.content.Context) : android.widget.BaseAdapter(), android.widget.Filterable {
    var items: List<String> = emptyList(); private set

    fun set(list: List<String>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(c).apply {
            textSize = 18f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12)); minHeight = Ui.dp(52); gravity = Gravity.CENTER_VERTICAL
            val sel = intArrayOf(android.R.attr.state_selected); val foc = intArrayOf(android.R.attr.state_focused); val pr = intArrayOf(android.R.attr.state_pressed)
            setTextColor(android.content.res.ColorStateList(arrayOf(sel, foc, pr, intArrayOf()), intArrayOf(Ui.focusText, Ui.focusText, Ui.focusText, Ui.text)))
            background = android.graphics.drawable.StateListDrawable().apply {
                val on = GradientDrawable().apply { setColor(Ui.focus); cornerRadius = Ui.dp(maxOf(Ui.preset.radius - 6, 4)).toFloat() }
                addState(sel, on); addState(foc, on); addState(pr, on)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
        }
        tv.text = "🕘  " + items[position]
        return tv
    }

    private val noFilter = object : android.widget.Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply { values = items; count = items.size }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        override fun convertResultToString(resultValue: Any?) = resultValue as? String ?: ""
    }
    override fun getFilter(): android.widget.Filter = noFilter
}

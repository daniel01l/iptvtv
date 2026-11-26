package pl.iptv.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * (7.23) Filmy i seriale z list M3U (VOD dostawcy): rozpoznanie pozycji ([classify] – `tvg-type`, adresy Xtream `/movie/` i
 * `/series/`, rozszerzenia plików, nazwy grup, wzorce odcinków), grupowanie odcinków seriali po tytule, sezonie i odcinku
 * ([episodeOf]), normalizacja gatunków ([normalizeGenres] – bez „VOD”, „Filmy”, „Seriale”, „PL”…, jednolita wielkość liter,
 * „Pozostałe” dla pozycji bez gatunku). Wynik – [VodCatalog]; budowa wyłącznie poza głównym wątkiem ([VodLibrary]).
 * Pozycje widoczne w module VOD (Stremio) niezależnie od konta Stremio. Lista kanałów Live TV bez zmian.
 */
object M3uVod {
    enum class Kind { LIVE, MOVIE, SERIES }

    /** Przedrostek identyfikatorów pozycji z listy (odróżnia je od tytułów Stremio „tt…”). */
    const val PREFIX = "m3u:"
    const val MOVIE_PREFIX = "m3u:m:"; const val SERIES_PREFIX = "m3u:s:"; const val EPISODE_PREFIX = "m3u:e:"
    const val ALL = "Wszystkie"
    const val OTHER = "Pozostałe"

    fun isM3uId(id: String) = id.startsWith(PREFIX)

    data class EpisodeInfo(val show: String, val season: Int, val episode: Int, val title: String = "")

    data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val channel: Channel)
    data class Movie(val id: String, val title: String, val year: String, val genres: List<String>, val poster: String, val channel: Channel)
    data class Show(val id: String, val title: String, val genres: List<String>, val poster: String, val episodes: List<Episode>) {
        val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
        fun season(s: Int) = episodes.filter { it.season == s }
    }

    // ---------------- rozpoznawanie ----------------

    private val VIDEO_EXT = Regex("""\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv|mpg|mpeg|divx|xvid)(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val XC_MOVIE = Regex("""/movie/[^/]+/[^/]+/[^/]+$""", RegexOption.IGNORE_CASE)
    private val XC_SERIES = Regex("""/series/[^/]+/[^/]+/[^/]+$""", RegexOption.IGNORE_CASE)
    private val SERIES_GROUP = Regex("""\b(seriale?|series|tv\s*shows?|shows?|sezon)\b""", RegexOption.IGNORE_CASE)
    private val MOVIE_GROUP = Regex("""\b(vod|filmy?|movies?|kino|cinema)\b""", RegexOption.IGNORE_CASE)

    private val EP_PATTERNS = listOf(
        // „Tytuł S01E02 …”, „Tytuł.S1.E2”, „Tytuł - s01 e02”
        Regex("""^(.*?)[\s._\-:|]*\b[Ss](\d{1,2})[\s._]*[Ee](\d{1,3})\b[\s._\-:|]*(.*)$"""),
        // „Tytuł 1x02”
        Regex("""^(.*?)[\s._\-:|]+(\d{1,2})x(\d{1,3})\b[\s._\-:|]*(.*)$""", RegexOption.IGNORE_CASE),
        // „Tytuł Sezon 1 Odcinek 2”, „Tytuł Season 1 Episode 2”, „Tytuł sezon 1 odc. 2”
        Regex("""^(.*?)[\s._\-:|]*\b(?:sezon|season|seria)\s*(\d{1,2})[\s,._\-:|]*(?:odcinek|odc\.?|episode|ep\.?|e)\s*(\d{1,3})\b[\s._\-:|]*(.*)$""", RegexOption.IGNORE_CASE),
    )
    /** Sam numer odcinka („Tytuł odc. 5”, „Tytuł Episode 5”) – sezon 1. */
    private val EP_ONLY = Regex("""^(.*?)[\s._\-:|]*\b(?:odcinek|odc\.?|episode|ep\.?)\s*(\d{1,3})\b[\s._\-:|]*(.*)$""", RegexOption.IGNORE_CASE)

    /** Sezon i odcinek z nazwy pozycji; null – nazwa bez wzorca odcinka. */
    fun episodeOf(name: String): EpisodeInfo? {
        val n = name.trim()
        for (r in EP_PATTERNS) r.find(n)?.let { m ->
            val show = cleanTitle(m.groupValues[1]); if (show.isBlank()) return null
            return EpisodeInfo(show, m.groupValues[2].toInt(), m.groupValues[3].toInt(), cleanTitle(m.groupValues[4]))
        }
        EP_ONLY.find(n)?.let { m ->
            val show = cleanTitle(m.groupValues[1]); if (show.isBlank()) return null
            return EpisodeInfo(show, 1, m.groupValues[2].toInt(), cleanTitle(m.groupValues[3]))
        }
        return null
    }

    /** Rodzaj pozycji listy: kanał na żywo (domyślnie), film albo odcinek serialu. */
    fun classify(c: Channel): Kind {
        val type = c.tvgType.lowercase()
        val url = c.url.substringBefore('|')
        val ep = episodeOf(c.name) != null
        when {
            type in setOf("movie", "movies", "vod", "film") -> return if (ep) Kind.SERIES else Kind.MOVIE
            type in setOf("series", "serial", "episode", "show", "tvshow") -> return Kind.SERIES
            type in setOf("live", "stream", "channel") -> return Kind.LIVE
        }
        if (XC_SERIES.containsMatchIn(url)) return Kind.SERIES
        if (XC_MOVIE.containsMatchIn(url)) return Kind.MOVIE
        val file = VIDEO_EXT.containsMatchIn(url)
        val g = c.group
        if (SERIES_GROUP.containsMatchIn(g) && (file || ep)) return Kind.SERIES
        if (file) return if (ep) Kind.SERIES else Kind.MOVIE
        if (MOVIE_GROUP.containsMatchIn(g) && !url.contains(".m3u8", true) && !url.endsWith(".ts", true)) return if (ep) Kind.SERIES else Kind.MOVIE
        return Kind.LIVE
    }

    // ---------------- tytuły i gatunki ----------------

    private val YEAR = Regex("""[(\[]?\b(19[0-9]{2}|20[0-9]{2})\b[)\]]?""")
    private val QUALITY = Regex("""[(\[]?\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hevc|x264|x265|h264|h265|multi|lektor|napisy|dubbing|pl|eng|vod)\b[)\]]?""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s{2,}""")

    /** Tytuł bez znaczników jakości, roku i zbędnych separatorów („Film.2021.1080p.PL” → „Film”). */
    fun cleanTitle(t: String): String {
        var s = t.replace('_', ' ').let { if (!it.contains(' ') && it.count { ch -> ch == '.' } >= 2) it.replace('.', ' ') else it }
        s = QUALITY.replace(s, " ")
        s = YEAR.replace(s, " ")
        s = s.replace(Regex("""[(\[]\s*[)\]]"""), " ")
        return SPACES.replace(s, " ").trim().trim('-', ':', '|', '.', ',', '–', ' ')
    }

    fun yearOf(t: String): String = YEAR.find(t)?.groupValues?.get(1).orEmpty()

    /** Słowa usuwane z nazw gatunków (grupy w stylu „VOD | Filmy PL – Akcja”). */
    private val NOISE = setOf("vod", "film", "filmy", "movie", "movies", "serial", "seriale", "series", "tv", "show", "shows", "pl", "pol",
        "polska", "polskie", "polski", "eng", "en", "hd", "fhd", "uhd", "4k", "sd", "lektor", "napisy", "dubbing", "kino", "cinema", "nowości", "new", "all",
        "inne", "other", "others", "pozostałe", "różne", "misc")
    private val SPLIT = Regex("""\s*(?:[|/;,»>•]|\s[-–]\s|&)\s*""")
    private val SYNONYMS = mapOf(
        "action" to "Akcja", "akcji" to "Akcja", "comedy" to "Komedia", "komedie" to "Komedia", "drama" to "Dramat", "dramaty" to "Dramat",
        "horrory" to "Horror", "thrillery" to "Thriller", "animation" to "Animacja", "animowane" to "Animacja", "animacje" to "Animacja",
        "bajki" to "Dla dzieci", "kids" to "Dla dzieci", "dzieci" to "Dla dzieci", "family" to "Familijny", "familijne" to "Familijny",
        "documentary" to "Dokumentalny", "dokumentalne" to "Dokumentalny", "dokument" to "Dokumentalny", "sci-fi" to "Sci-fi", "science fiction" to "Sci-fi",
        "fantasy" to "Fantasy", "romance" to "Romans", "romanse" to "Romans", "crime" to "Kryminał", "kryminalne" to "Kryminał", "kryminały" to "Kryminał",
        "adventure" to "Przygodowy", "przygodowe" to "Przygodowy", "war" to "Wojenny", "wojenne" to "Wojenny", "western" to "Western", "westerny" to "Western",
        "musical" to "Musical", "music" to "Muzyczny", "muzyczne" to "Muzyczny", "history" to "Historyczny", "historyczne" to "Historyczny", "sport" to "Sport",
        "mystery" to "Tajemnica", "biography" to "Biograficzny", "biograficzne" to "Biograficzny",
    )

    /** Gatunki z atrybutu (`tvg-genre`) albo nazwy grupy: znormalizowane, bez powtórzeń; brak – [OTHER]. */
    fun normalizeGenres(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (part in SPLIT.split(raw)) {
            val words = part.trim().split(Regex("""\s+""")).filter { it.isNotBlank() && it.trim('(', ')', '[', ']', ':', '.').lowercase() !in NOISE }
            val g = words.joinToString(" ").trim('(', ')', '[', ']', ':', '.', '-', '–', ' ')
            if (g.isBlank() || g.all { it.isDigit() }) continue
            val low = g.lowercase()
            out += SYNONYMS[low] ?: (low.replaceFirstChar { it.titlecase() })
        }
        return out.toList().ifEmpty { listOf(OTHER) }
    }

    private fun genresOf(c: Channel) = normalizeGenres(c.genre.ifBlank { c.group })

    /** Źródło odtwarzacza Stremio dla pozycji z listy (adres i nagłówki User-Agent / Referer kanału). */
    fun stream(c: Channel): StremioStream = StremioStream("Lista M3U", "m3u", name = "Lista M3U", title = c.name, url = c.url.substringBefore('|'),
        headers = buildMap { c.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }; c.referrer.takeIf { it.isNotBlank() }?.let { put("Referer", it) } })

    /** „S1E2 · Tytuł odcinka”. */
    fun episodeLabel(e: Episode) = "S${e.season}E${e.episode}" + (e.title.takeIf { it.isNotBlank() && it != "Odcinek ${e.episode}" }?.let { " · $it" } ?: "")

    private fun hash(s: String) = Integer.toHexString(s.hashCode()) + s.length.toString(36)
    private fun showKey(t: String) = t.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    // ---------------- katalog ----------------

    /** Buduje katalog z [channels] (czyste obliczenie – wywoływać poza głównym wątkiem). */
    fun build(channels: List<Channel>, version: Int = 0): VodCatalog {
        val movies = ArrayList<Movie>()
        val shows = LinkedHashMap<String, MutableList<Pair<Episode, Channel>>>()
        val showTitle = HashMap<String, String>()
        for (c in channels) {
            if (c.hidden) continue
            when (classify(c)) {
                Kind.LIVE -> {}
                Kind.MOVIE -> movies += Movie(MOVIE_PREFIX + hash(c.key), cleanTitle(c.name).ifBlank { c.name }, yearOf(c.name), genresOf(c), c.logo, c)
                Kind.SERIES -> {
                    val e = episodeOf(c.name) ?: EpisodeInfo(cleanTitle(c.name).ifBlank { c.name }, 1, (shows[showKey(cleanTitle(c.name))]?.size ?: 0) + 1)
                    val k = showKey(e.show)
                    showTitle.putIfAbsent(k, e.show)
                    shows.getOrPut(k) { ArrayList() } += Episode(EPISODE_PREFIX + hash(c.key), e.season, e.episode, e.title.ifBlank { "Odcinek ${e.episode}" }, c) to c
                }
            }
        }
        val series = shows.map { (k, list) ->
            val eps = list.map { it.first }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
            val chans = list.map { it.second }
            Show(SERIES_PREFIX + k, showTitle[k] ?: k, chans.flatMap(::genresOf).distinct().let { g -> if (g.size > 1) g - OTHER else g },
                chans.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty(), eps)
        }
        return VodCatalog(movies, series, version)
    }
}

/** Filmy i seriale z listy M3U; [genres] – „Wszystkie”, gatunki alfabetycznie, „Pozostałe” na końcu. */
data class VodCatalog(val movies: List<M3uVod.Movie> = emptyList(), val series: List<M3uVod.Show> = emptyList(), val version: Int = -1) {
    val isEmpty get() = movies.isEmpty() && series.isEmpty()
    private val byId: Map<String, Any> by lazy { HashMap<String, Any>().apply { movies.forEach { put(it.id, it) }; series.forEach { s -> put(s.id, s); s.episodes.forEach { put(it.id, it) } } } }

    /** Gatunki filtra dla [type] („movie” / „series” / null – oba). */
    fun genres(type: String? = null): List<String> {
        val all = (if (type != "series") movies.flatMap { it.genres } else emptyList()) + (if (type != "movie") series.flatMap { it.genres } else emptyList())
        val list = all.distinct().filter { it != M3uVod.OTHER }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return listOf(M3uVod.ALL) + list + (if (M3uVod.OTHER in all) listOf(M3uVod.OTHER) else emptyList())
    }
    fun movies(genre: String = M3uVod.ALL) = if (genre == M3uVod.ALL) movies else movies.filter { genre in it.genres }
    fun series(genre: String = M3uVod.ALL) = if (genre == M3uVod.ALL) series else series.filter { genre in it.genres }
    fun movie(id: String) = byId[id] as? M3uVod.Movie
    fun show(id: String) = byId[id] as? M3uVod.Show
    fun episode(id: String) = byId[id] as? M3uVod.Episode
    fun showOf(episodeId: String) = series.firstOrNull { s -> s.episodes.any { it.id == episodeId } }

    /** Kanał (adres + nagłówki) filmu albo odcinka [id]. */
    fun channelFor(id: String): Channel? = movie(id)?.channel ?: episode(id)?.channel
}

/**
 * (7.23) Stan modułu VOD z list M3U: [catalog] (StateFlow) przebudowywany w tle po każdej zmianie listy ([invalidate] z
 * [Repository], na Dispatchers.Default – parsowanie wzorców i gatunki nie blokują UI), [current] – katalog aktualny dla bieżącej
 * listy (czeka na budowę). Niezależne od konta Stremio.
 */
object VodLibrary {
    private val _catalog = MutableStateFlow(VodCatalog())
    val catalog: StateFlow<VodCatalog> = _catalog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var pending: Job? = null
    @Volatile private var wanted = -1
    @Volatile private var source: List<Channel> = emptyList()

    /** Nowa lista kanałów (wersja [version]) – katalog budowany w tle. */
    fun invalidate(version: Int, channels: List<Channel>) {
        wanted = version; source = channels
        pending?.cancel()
        pending = scope.launch { rebuild() }
    }

    private suspend fun rebuild(): VodCatalog = mutex.withLock {
        val v = wanted; val ch = source
        val cur = _catalog.value
        if (cur.version == v) return cur
        val built = withContext(Dispatchers.Default) { runCatching { M3uVod.build(ch, v) }.getOrElse { VodCatalog(version = v) } }
        if (wanted == v) _catalog.value = built
        built
    }

    /** Katalog dla bieżącej listy (budowa w tle, gdy jeszcze nieaktualny). */
    suspend fun current(): VodCatalog = if (_catalog.value.version == wanted) _catalog.value else rebuild()

    /** Ma filmy lub seriale (wartość już zbudowana – bez czekania). */
    val hasContent: Boolean get() = !_catalog.value.isEmpty

    /** Testy. */
    fun resetForTest() { pending?.cancel(); wanted = -1; source = emptyList(); _catalog.value = VodCatalog() }
}

package pl.iptv.tv.data

import kotlinx.coroutines.async
import kotlin.math.exp

/**
 * Gotowe miksy STube („Twoje playlisty”): analiza lokalnej historii oglądania (Room – [WatchProgress]) i dobór filmów z YouTube.
 *
 * Preferencje: kanał / wykonawca i słowa kluczowe z tytułów, z wagą = 1 + część obejrzana (obejrzane do końca liczą się więcej)
 * + świeżość (połowa wagi po ok. 3 tygodniach). Słowa szumu YouTube („official”, „teledysk”, „4K”…) i krótkie / liczby pomijane.
 * Gdy w historii przeważa muzyka (znaczniki w tytułach) – miksy muzyczne. Filmy już obejrzane są w miksach pomijane
 * (poza „Wróć do ulubionych”). Wyszukiwanie: bez klucza API (bez limitu), zapasowo YouTube Data API.
 */
object StubeMixes {
    data class Mix(val id: String, val title: String, val subtitle: String, val queries: List<String>, val thumb: String = "",
                   /** Lista bez sieci – filmy z historii. */
                   val local: List<YtVideo> = emptyList(),
                   /**
                    * Filmy-zalążki rekomendacji społecznościowych: propozycje YouTube po każdym z nich (kolejność z zachowań innych
                    * widzów); kilka zalążków – wyżej filmy polecane po wielu z nich naraz ([rankCrowd]).
                    */
                   val seeds: List<String> = emptyList(),
                   /**
                    * Zapytania uzupełniające (gatunki / klimat z historii): gdy lista ma mniej niż [MIN_PLAYLIST] filmów,
                    * dobierane są kolejne – z wielu wykonawców tego samego stylu.
                    */
                   val fill: List<String> = emptyList(),
                   /** Muzyka: wykonawcy układani rundami ([diversify]) – jeden artysta nie zamyka miksu. */
                   val diverse: Boolean = false)

    /** Gatunek: nazwa, słowa w tytule / nazwie kanału i zapytania miksu. */
    data class Genre(val id: String, val name: String, val pattern: Regex, val queries: List<String>)

    val GENRES = listOf(
        Genre("rock", "Rock", Regex("(?i)\\brock\\b|grunge|punk|\\bindie\\b|alternative|alternatyw"), listOf("rock mix", "best rock songs")),
        Genre("metal", "Metal", Regex("(?i)metal|metallica|slayer|sabaton|\\bdeath\\b|thrash"), listOf("metal mix", "heavy metal playlist")),
        Genre("hiphop", "Hip-hop i rap", Regex("(?i)hip ?hop|\\brap\\b|raper|trap\\b|drill\\b|freestyle|\\bdrake\\b|eminem|quebonafide|taco hemingway|mata\\b"), listOf("hip hop mix", "polski rap mix")),
        Genre("pop", "Pop", Regex("(?i)\\bpop\\b|taylor swift|dua lipa|ed sheeran|the weeknd|billie eilish|ariana|sanah|podsiadło"), listOf("pop hits mix", "polski pop mix")),
        Genre("electronic", "Elektronika", Regex("(?i)\\bedm\\b|house|techno|trance|dubstep|drum ?(and|&|n) ?bass|\\bdnb\\b|electro|synthwave|avicii|tiesto|\\bdj\\b"), listOf("electronic music mix", "house music mix")),
        Genre("discopolo", "Disco polo", Regex("(?i)disco ?polo|\\bdisco\\b|zenek|martyniuk|akcent|weekend\\b|boys\\b"), listOf("disco polo mix", "disco polo hity")),
        Genre("jazz", "Jazz i blues", Regex("(?i)jazz|blues|\\bswing\\b|bossa"), listOf("jazz mix", "smooth jazz playlist")),
        Genre("classical", "Klasyczna", Regex("(?i)classical|klasyczn|symphony|symfoni|chopin|mozart|beethoven|bach\\b|vivaldi|piano sonata|koncert fortepian"), listOf("classical music mix", "muzyka klasyczna")),
        Genre("chill", "Chill i lo-fi", Regex("(?i)lo-?fi|chill|relax|relaks|ambient|sleep|study|deep focus"), listOf("lofi chill mix", "relaxing music")),
        Genre("reggae", "Reggae", Regex("(?i)reggae|dancehall|ska\\b|bob marley"), listOf("reggae mix", "best reggae songs")),
        Genre("kpop", "K-pop", Regex("(?i)k-?pop|bts\\b|blackpink|twice\\b|stray kids|newjeans"), listOf("kpop mix", "kpop hits")),
        Genre("latin", "Latino", Regex("(?i)latin|reggaeton|bachata|salsa|bad bunny|shakira"), listOf("latin hits mix", "reggaeton mix")),
        Genre("country", "Country i folk", Regex("(?i)country|\\bfolk\\b|bluegrass|ludow"), listOf("country music mix", "folk music playlist")),
    )

    const val MIN_HISTORY = 3
    const val PER_QUERY = 12
    /** Minimalna długość playlisty miksu (utwory) i górny limit. */
    const val MIN_PLAYLIST = 100
    const val MAX_VIDEOS = 150
    /** Ile utworów jednego wykonawcy w jednej „rundzie” miksu (kolejne dopiero, gdy brakuje do [MIN_PLAYLIST]). */
    const val MAX_PER_ARTIST = 2
    /** Zapytań uruchamianych równolegle przy uzupełnianiu playlisty. */
    const val BATCH = 6
    private val VARIANTS = listOf("hits", "new songs", "2020s", "2010s", "2000s", "90s", "official video", "songs")

    private val STOP = setOf(
        // PL
        "się", "jest", "jak", "dla", "nie", "czy", "tak", "pod", "nad", "oraz", "albo", "też", "już", "ale", "przez", "tylko", "przy",
        "ten", "ta", "to", "tym", "tego", "tej", "jego", "jej", "ich", "mój", "moja", "twój", "nasz", "być", "było", "będzie",
        "odc", "odcinek", "część", "cz", "nowy", "nowa", "nowe", "najlepsze", "całe", "cały", "film", "filmy", "wideo", "teledysk",
        "oficjalny", "piosenka", "piosenki", "muzyka", "muzyki", "napisy", "pełny", "pełna", "zwiastun",
        // EN / YouTube
        "the", "and", "for", "with", "from", "you", "your", "are", "was", "this", "that", "official", "video", "music", "audio",
        "lyrics", "lyric", "full", "new", "live", "remix", "feat", "ft", "hd", "4k", "hq", "mv", "trailer", "episode", "part",
        "vs", "how", "what", "why", "best", "top", "shorts", "version", "remastered", "visualizer", "clip",
    )
    private val MUSIC = Regex("(?i)official (music )?video|teledysk|lyrics?|\\bft\\.?\\b|\\bfeat\\.?\\b|remix|\\(audio\\)|\\bmv\\b|cover|piosenk|official audio|album|\\blive\\b")

    /** Słowa kluczowe tytułu (małe litery, bez szumu). */
    fun tokens(title: String): List<String> = title.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) && !it.all(Char::isDigit) }

    /** Waga pozycji historii: obejrzenie + świeżość. */
    fun weight(p: WatchProgress, now: Long): Double {
        val days = ((now - p.updatedAt).coerceAtLeast(0)) / 86_400_000.0
        return 1.0 + (if (p.finished) 1.0 else p.fraction.toDouble()) + exp(-days / 30.0)
    }

    /** Czy historia jest głównie muzyczna. */
    fun isMusic(history: List<WatchProgress>) = history.isNotEmpty() && history.count { MUSIC.containsMatchIn(it.title) } * 10 >= history.size * 3

    /** Miksy z historii (kolejność = ważność). Pusta lista – za mało historii. */
    fun build(history: List<WatchProgress>, now: Long = System.currentTimeMillis()): List<Mix> {
        val h = history.filter { it.title.isNotBlank() }
        if (h.size < MIN_HISTORY) return emptyList()
        val channels = HashMap<String, Double>(); val words = HashMap<String, Double>()
        h.forEach { p ->
            val w = weight(p, now)
            if (p.channel.isNotBlank()) channels.merge(p.channel.trim(), w, Double::plus)
            // słowa tytułu bez nazwy kanału (nie dublują miksu kanału)
            val chTokens = tokens(p.channel).toSet()
            tokens(p.title).distinct().filter { it !in chTokens }.forEach { words.merge(it, w, Double::plus) }
        }
        val topCh = channels.entries.sortedByDescending { it.value }.map { it.key }
        val topWords = words.entries.filter { it.value >= 2.0 }.sortedByDescending { it.value }.map { it.key }
        val music = isMusic(h)
        val byWeight = h.sortedByDescending { weight(it, now) }
        val topGenres = genreScores(h, now).filter { it.value >= GENRE_MIN }.entries.sortedByDescending { it.value }.map { it.key }
        val moodList = moodWords(h, topWords).takeIf { it.size >= 2 }
        // uzupełnianie playlist: style z historii (kilka gatunków naraz → wielu wykonawców), nie jeden artysta
        val fill = fillQueries(topGenres, moodList, music)
        // zalążki o różnych wykonawcach (propozycje po nich obejmują innych artystów)
        val seedsMixed = byWeight.distinctBy { artistKey(it.channel) }.take(CROWD_SEEDS).map { it.videoId }
        val out = mutableListOf<Mix>()
        if (topCh.isNotEmpty()) out += Mix("fav", if (music) "Muzyczny miks pod Ciebie" else "Miks pod Twoje ulubione",
            "Na podstawie: " + topCh.take(3).joinToString(", "), topCh.take(3).map { if (music) "$it music" else it }, byWeight.first().thumb,
            seeds = if (music) seedsMixed else emptyList(), fill = fill, diverse = music)
        moodList?.let { mood ->
            val kw = mood.joinToString(" ")
            out += Mix("mood", "Więcej w tym klimacie", "„$kw”", listOf(if (music) "$kw music" else kw, mood.first()).distinct(),
                byWeight.firstOrNull { p -> mood.first() in tokens(p.title) }?.thumb ?: byWeight.first().thumb, fill = fill, diverse = music)
        }
        topCh.filter { (channels[it] ?: 0.0) >= 4.0 }.take(2).forEach { ch ->
            val chTop = h.filter { it.channel.trim() == ch }.maxByOrNull { weight(it, now) }
            out += Mix("ch:" + ch.lowercase().hashCode().toUInt().toString(16), "Miks: $ch", "Więcej od tego twórcy i podobnych",
                listOf(ch, if (music) "$ch mix" else "$ch najnowsze"), chTop?.thumb.orEmpty(),
                // muzyka: jeden wykonawca nie zamyka miksu – podobni artyści (propozycje po jego utworze) i styl
                seeds = if (music) listOfNotNull(chTop?.videoId) else emptyList(), fill = fill, diverse = music)
        }
        // miksy gatunkowe (do dwóch najmocniejszych gatunków w historii)
        topGenres.take(2).forEach { g ->
            out += Mix("g:" + g.id, "Miks: ${g.name}", "Gatunek z Twojej historii", g.queries,
                h.filter { g.pattern.containsMatchIn(it.title + " " + it.channel) }.maxByOrNull { weight(it, now) }?.thumb.orEmpty(),
                fill = expand(g.queries), diverse = true)
        }
        // „Dalej po…”: co oglądano / słuchano po ostatnim filmie – u Ciebie (przejścia w historii) i u innych widzów (propozycje YouTube)
        val latest = h.maxByOrNull { it.updatedAt }!!
        val tr = transitions(h)
        val after = followers(tr, latest.videoId).mapNotNull { id -> h.firstOrNull { it.videoId == id }?.toVideo() }
        out += Mix("next:" + latest.videoId, "Dalej po: " + latest.title.take(48), "Co słuchają dalej osoby o podobnym guście",
            emptyList(), latest.thumb, local = after, seeds = listOf(latest.videoId), fill = fill, diverse = music)
        // rekomendacje społecznościowe: filmy polecane po kilku Twoich ulubionych naraz (wspólne gusta)
        val favs = byWeight.map { it.videoId }.distinct().take(CROWD_SEEDS)
        if (favs.size >= 2) out += Mix("crowd", "Słuchacze podobni do Ciebie wybierają", "Wspólne propozycje po Twoich ulubionych",
            emptyList(), byWeight.getOrNull(1)?.thumb ?: byWeight.first().thumb, seeds = favs, fill = fill, diverse = music)
        out += Mix("again", "Wróć do ulubionych", "Najczęściej oglądane", emptyList(), byWeight.first().thumb,
            local = byWeight.take(MAX_VIDEOS).map { it.toVideo() }, fill = fill, diverse = music)
        // różne okładki miksów (ten sam film nie powtarza się na kilku kafelkach)
        val used = HashSet<String>(); val pool = byWeight.map { it.thumb }.filter { it.isNotBlank() }.distinct()
        return out.map { m ->
            if (m.thumb.isNotBlank() && used.add(m.thumb)) m
            else pool.firstOrNull { it !in used }?.let { t -> used.add(t); m.copy(thumb = t) } ?: m
        }
    }

    /** Najdłuższy film uznawany za pojedynczy utwór (s); dłuższe to składanki, mixy, albumy. */
    const val MAX_TRACK_SEC = 11 * 60
    private val COMPILATION = Regex("""(?i)mega ?mix|non-?stop|skladanka|składanka|best of|\btop ?\d+|full album|compilation|\bplaylist|mixtape|\b(dj|hits?|hity|music|muzyka) mix|\d+ ?(hours?|godz\w*|songs|piosenek|hit\w*|utwor\w*)|\b(hity|hits)\b[^|]*\b20\d\d\b|\b20\d\d\b[^|]*\b(hity|hits)\b|najlepsze|największe hity|nowe hity|\blive stream|\bradio\b.*24/7""")

    /** Czas ISO 8601 (PT3M20S) → sekundy; 0 – nieznany / transmisja. */
    fun durationSec(iso: String): Int {
        val m = Regex("""^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""").find(iso) ?: return 0
        val (d, h, min, s) = m.destructured
        return (d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    /** Składanka / mix / album w jednym wideo („Hity 2026”, „Best of”, kilkanaście piosenek) – nie jest propozycją utworu. */
    fun isCompilation(v: YtVideo): Boolean = durationSec(v.duration) > MAX_TRACK_SEC || COMPILATION.containsMatchIn(v.title)

    /** Tytuły typowe dla vlogów, filmów, odcinków, gier, wywiadów – nie piosenek. */
    private val NON_MUSIC = Regex("""(?i)\bvlog|\bvlogi|#\d+|\bodc(inek)?\b|\bepisode|\bep\.? ?\d+|trailer|zwiastun|\breview|recenzj|reaction|reakcj|gameplay|let'?s play|unboxing|tutorial|poradnik|\bchallenge|wyzwanie|podcast|wywiad|interview|\bnews\b|wiadomo[sś]ci|q ?& ?a\b|full movie|cały film|\bstream\b|asmr|storytime|\bprank|\bshorts?\b|\bfail|\bfilm\b|\bserial|\bkurs\b|lekcj|\bunboxing|\bgra\b""")
    private val ARTIST_TITLE = Regex("""^.{2,45}\s[-–—]\s.{2,}""")

    /** Nie nadaje się na utwór w playliście: składanka, vlog / film / odcinek albo za długie (> [MAX_TRACK_SEC] / 9 min). */
    fun isNonTrack(v: YtVideo): Boolean = isCompilation(v) || NON_MUSIC.containsMatchIn(v.title) || durationSec(v.duration) > 9 * 60

    /**
     * Pewny utwór (propozycje po piosence): nie [isNonTrack] i wyraźny znak muzyki – oznaczenia w tytule, kanał „- Topic” / VEVO,
     * styl „Wykonawca – Tytuł” albo słowa gatunku. Zwykłe filmy i vlogi YouTuberów odpadają.
     */
    fun isTrack(v: YtVideo): Boolean = !isNonTrack(v) && (isMusicVideo(v.title, v.channel) || ARTIST_TITLE.containsMatchIn(v.title))

    /**
     * Propozycje po utworze: bez składanek, bez bieżącego i ostatnio słuchanych, gatunek bieżącego utworu wyżej, wykonawcy przeplatani
     * (nie więcej niż [MAX_PER_ARTIST] z jednego, o ile jest z czego wybierać). Nie-muzyka – lista bez zmian. Czysta funkcja – testy.
     */
    fun suggest(related: List<YtVideo>, currentId: String, currentTitle: String, currentChannel: String, recent: Set<String> = emptySet()): List<YtVideo> {
        if (!isMusicVideo(currentTitle, currentChannel) && related.none { isMusicVideo(it.title, it.channel) }) return related.filter { it.id != currentId }
        val g = GENRES.firstOrNull { it.pattern.containsMatchIn(currentTitle + " " + currentChannel) }
        val base = related.filter { it.id != currentId && it.id !in recent && it.duration != "P0D" && isTrack(it) }
        val ranked = if (g == null) base else base.sortedByDescending { if (g.pattern.containsMatchIn(it.title + " " + it.channel)) 1 else 0 }
        return diversify(ranked, MAX_PER_ARTIST, SUGGEST_MIN)
    }
    const val SUGGEST_MIN = 12

    /** Klucz wykonawcy filmu (kanał bez „- Topic” / VEVO). */
    fun artistKey(channel: String): String = channel.trim().removeSuffix("- Topic").removeSuffix("VEVO").trim().lowercase()

    /** Zapytania wariantowe („rock mix” → „rock mix hits”, „… 2010s”…) – kolejne partie wyników tego samego stylu. */
    fun expand(base: List<String>): List<String> = (base + base.flatMap { b -> VARIANTS.map { "$b $it" } }).distinct()

    /**
     * Zapytania uzupełniające: do trzech najmocniejszych gatunków z historii (przeplatane – wielu wykonawców różnych stylów
     * słuchacza), klimat z tytułów; bez gatunków – ogólne hity.
     */
    fun fillQueries(genres: List<Genre>, mood: List<String>?, music: Boolean): List<String> {
        val out = interleave(genres.take(3).map { expand(it.queries) }).toMutableList()
        mood?.joinToString(" ")?.let { kw -> out += expand(listOf(if (music) "$kw music" else kw)) }
        if (out.isEmpty() && music) out += expand(listOf("top hits mix", "polskie hity mix"))
        return out.distinct()
    }

    /**
     * Różnorodność wykonawców: filmy układane „rundami” – po jednym utworze każdego wykonawcy, potem po drugim… Kolejne rundy
     * ponad [cap] dochodzą tylko, gdy do [target] jeszcze brakuje. Czysta funkcja – testy.
     */
    fun diversify(list: List<YtVideo>, cap: Int = MAX_PER_ARTIST, target: Int = MIN_PLAYLIST): List<YtVideo> {
        val buckets = LinkedHashMap<String, MutableList<YtVideo>>()
        list.forEach { buckets.getOrPut(artistKey(it.channel).ifEmpty { it.id }) { mutableListOf() }.add(it) }
        val out = ArrayList<YtVideo>(list.size); var round = 0
        while (true) {
            var any = false
            for (b in buckets.values) b.getOrNull(round)?.let { out += it; any = true }
            if (!any) break
            round++
            if (round >= cap && out.size >= target) break
        }
        return out
    }

    const val GENRE_MIN = 2.5
    const val CROWD_SEEDS = 3
    /** Dwa filmy obejrzane w odstępie nie większym niż ta przerwa – jedno posiedzenie (przejście „po tym – tamto”). */
    const val SESSION_GAP_MS = 45 * 60_000L

    /** Gatunki w historii: suma wag pozycji, których tytuł / kanał pasuje do gatunku. Czysta funkcja – testy. */
    fun genreScores(h: List<WatchProgress>, now: Long = System.currentTimeMillis()): Map<Genre, Double> {
        val out = HashMap<Genre, Double>()
        h.forEach { p -> val t = p.title + " " + p.channel; GENRES.filter { it.pattern.containsMatchIn(t) }.forEach { g -> out.merge(g, weight(p, now), Double::plus) } }
        return out
    }

    /**
     * Przejścia między filmami w historii (Room zapisuje czas ostatniego oglądania): kolejne pozycje w jednym posiedzeniu
     * (przerwa ≤ [SESSION_GAP_MS]) → liczba przejść a → b. Czysta funkcja – testy.
     */
    fun transitions(h: List<WatchProgress>): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        h.sortedBy { it.updatedAt }.zipWithNext().forEach { (a, b) ->
            if (a.videoId != b.videoId && b.updatedAt - a.updatedAt <= SESSION_GAP_MS) out.getOrPut(a.videoId) { HashMap() }.merge(b.videoId, 1, Int::plus)
        }
        return out
    }

    /** Filmy oglądane po [id] – od najczęstszego. */
    fun followers(tr: Map<String, Map<String, Int>>, id: String): List<String> = tr[id].orEmpty().entries.sortedByDescending { it.value }.map { it.key }

    /**
     * Ranking społecznościowy: filmy z kilku list propozycji – najpierw polecane po większej liczbie zalążków, w razie remisu
     * wyżej na listach (suma pozycji). Czysta funkcja – testy.
     */
    fun rankCrowd(lists: List<List<YtVideo>>): List<YtVideo> {
        val count = HashMap<String, Int>(); val pos = HashMap<String, Int>(); val first = LinkedHashMap<String, YtVideo>()
        lists.forEach { l -> l.forEachIndexed { i, v -> count.merge(v.id, 1, Int::plus); pos.merge(v.id, i, Int::plus); first.putIfAbsent(v.id, v) } }
        return first.values.sortedWith(compareByDescending<YtVideo> { count[it.id] ?: 0 }.thenBy { (pos[it.id] ?: 0) / (count[it.id] ?: 1) })
    }

    /** Czy film to muzyka (tytuł z oznaczeniami muzycznymi, kanał wykonawcy „– Topic” / VEVO). */
    fun isMusicVideo(title: String, channel: String = ""): Boolean =
        MUSIC.containsMatchIn(title) || channel.endsWith(" - Topic") || channel.contains("VEVO", true) || GENRES.any { it.pattern.containsMatchIn(title) && it.id != "chill" }

    /**
     * Słowa „klimatu”: najmocniejsze słowo i najczęstsze słowo, które występuje RAZEM z nim w tych samych tytułach
     * (spójne zapytanie, np. „jazz relaks”, zamiast dwóch niezwiązanych słów z różnych filmów).
     */
    fun moodWords(h: List<WatchProgress>, topWords: List<String>): List<String> {
        val first = topWords.firstOrNull() ?: return emptyList()
        val co = HashMap<String, Int>()
        h.map { tokens(it.title).toSet() }.filter { first in it }.forEach { t -> t.filter { it != first }.forEach { co.merge(it, 1, Int::plus) } }
        val partner = co.entries.filter { it.value >= 2 }.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { topWords.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } })?.key
        return listOfNotNull(first, partner)
    }

    /** Miksy z bieżącej historii (pamięć [StubeHistory]); ostatnio zbudowane – do otwarcia po id. */
    @Volatile var last: List<Mix> = emptyList(); private set
    suspend fun current(): List<Mix> = build(StubeHistory.watched(200)).also { last = it }
    fun byId(id: String) = last.firstOrNull { it.id == id }

    /** Filmy miksu: wyniki zapytań przeplatane, bez powtórzeń i bez filmów już obejrzanych. */
    suspend fun videos(m: Mix, watchedIds: Set<String>? = null, early: (suspend (List<YtVideo>) -> Unit)? = null): List<YtVideo> {
        val watched = watchedIds ?: StubeHistory.watched(500).map { it.videoId }.toSet()
        fun ok(v: YtVideo) = v.id !in watched && v.id !in m.seeds && v.duration != "P0D" && (!m.diverse || !isNonTrack(v))
        // propozycje po zalążkach i wyszukiwanie – równolegle (wcześniej jedno po drugim)
        val (crowd, queried) = kotlinx.coroutines.coroutineScope {
            val c = async {
                if (m.seeds.isEmpty()) emptyList() else {
                    val lists = m.seeds.map { s -> async { runCatching { YtKeyless.related(s) }.getOrDefault(emptyList()).filter { it.id !in m.seeds } } }.map { it.await() }
                    // jeden zalążek: najpierw Twoje przejścia (z historii), potem propozycje innych widzów; kilka – ranking wspólnych
                    if (lists.size > 1) rankCrowd(lists) else lists.firstOrNull().orEmpty()
                }
            }
            val q = async { if (m.queries.isEmpty()) emptyList() else search(m.queries, PER_QUERY) }
            c.await() to q.await()
        }
        val primary = when {
            m.seeds.isEmpty() && m.queries.isEmpty() -> m.local
            m.queries.isEmpty() -> m.local + crowd
            crowd.isEmpty() -> m.local + queried
            else -> m.local + interleave(listOf(crowd, queried))
        }.filter { m.local.contains(it) || ok(it) }.distinctBy { it.id }
        // pierwsza partia od razu (odtwarzanie rusza, zanim uzupełnienie do 100+ się wczyta)
        if (early != null && primary.isNotEmpty() && primary.size < MIN_PLAYLIST && m.fill.isNotEmpty())
            early((if (m.diverse) diversify(primary) else primary).take(MAX_VIDEOS))
        val list = if (primary.size >= MIN_PLAYLIST || m.fill.isEmpty()) primary else {
            val seen = primary.mapTo(HashSet()) { it.id }
            val extra = search(m.fill, PER_QUERY * 2) { acc -> primary.size + acc.count { ok(it) && it.id !in seen } >= MIN_PLAYLIST * 2 }
            primary + extra.filter { ok(it) && seen.add(it.id) }
        }
        return (if (m.diverse) diversify(list) else list).take(MAX_VIDEOS)
    }

    /** Wyniki zapytań [queries] (równolegle, partiami po [BATCH]) – listy przeplatane; kończy, gdy [enough] zwróci true. */
    private suspend fun search(queries: List<String>, per: Int, enough: (List<YtVideo>) -> Boolean = { false }): List<YtVideo> {
        val acc = mutableListOf<YtVideo>()
        for (batch in queries.chunked(BATCH)) {
            val lists = kotlinx.coroutines.coroutineScope {
                batch.map { q ->
                    async {
                        runCatching {
                            try { YtKeyless.search(q) } catch (e: YtException) { if (YouTubeRepository.publicAvailable) YouTubeRepository.search(q) else throw e }
                        }.getOrDefault(emptyList()).take(per)
                    }
                }.map { it.await() }
            }
            acc += interleave(lists)
            if (enough(acc)) break
        }
        return acc
    }

    /** Przeplatanie list (po jednym z każdej) – miks z kilku źródeł zamiast bloków. Czysta funkcja – testy. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>(); var i = 0
        while (lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let(out::add) }; i++ }
        return out
    }
}

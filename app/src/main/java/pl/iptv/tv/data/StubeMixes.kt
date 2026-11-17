package pl.iptv.tv.data

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
                   val local: List<YtVideo> = emptyList())

    const val MIN_HISTORY = 3
    const val PER_QUERY = 12
    const val MAX_VIDEOS = 40

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
        val out = mutableListOf<Mix>()
        if (topCh.isNotEmpty()) out += Mix("fav", if (music) "Muzyczny miks pod Ciebie" else "Miks pod Twoje ulubione",
            "Na podstawie: " + topCh.take(3).joinToString(", "), topCh.take(3).map { if (music) "$it music" else it }, byWeight.first().thumb)
        moodWords(h, topWords).takeIf { it.size >= 2 }?.let { mood ->
            val kw = mood.joinToString(" ")
            out += Mix("mood", "Więcej w tym klimacie", "„$kw”", listOf(if (music) "$kw music" else kw, mood.first()).distinct(),
                byWeight.firstOrNull { p -> mood.first() in tokens(p.title) }?.thumb ?: byWeight.first().thumb)
        }
        topCh.filter { (channels[it] ?: 0.0) >= 4.0 }.take(2).forEach { ch ->
            out += Mix("ch:" + ch.lowercase().hashCode().toUInt().toString(16), "Miks: $ch", "Więcej od tego twórcy i podobnych",
                listOf(ch, if (music) "$ch mix" else "$ch najnowsze"), h.firstOrNull { it.channel.trim() == ch }?.thumb.orEmpty())
        }
        out += Mix("again", "Wróć do ulubionych", "Najczęściej oglądane", emptyList(), byWeight.first().thumb,
            local = byWeight.take(MAX_VIDEOS).map { it.toVideo() })
        return out
    }

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
    suspend fun videos(m: Mix, watchedIds: Set<String>? = null): List<YtVideo> {
        val watched = watchedIds ?: StubeHistory.watched(500).map { it.videoId }.toSet()
        if (m.queries.isEmpty()) return m.local
        val lists = m.queries.map { q ->
            runCatching {
                try { YtKeyless.search(q) } catch (e: YtException) { if (YouTubeRepository.publicAvailable) YouTubeRepository.search(q) else throw e }
            }.getOrDefault(emptyList()).take(PER_QUERY)
        }
        return interleave(lists).filter { it.id !in watched && it.duration != "P0D" }.distinctBy { it.id }.take(MAX_VIDEOS)
    }

    /** Przeplatanie list (po jednym z każdej) – miks z kilku źródeł zamiast bloków. Czysta funkcja – testy. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>(); var i = 0
        while (lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let(out::add) }; i++ }
        return out
    }
}

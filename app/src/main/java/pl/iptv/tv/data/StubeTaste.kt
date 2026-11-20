package pl.iptv.tv.data

/**
 * (7.14) Gust słuchacza dla STube: co lubi (gatunki i wykonawcy z historii – waga z [StubeMixes.weight]) oraz co odrzucił
 * (pominięte i usunięte z kolejki utwory – licznik wykonawców i gatunków w [Prefs.ytDislikes], identyfikatory pominiętych).
 * Wszystko poza zapisem odrzuceń to czyste funkcje (testy): ocena propozycji ([score]) i dopasowanie kolejki po pominięciu ([afterSkip]).
 */
object StubeTaste {
    class Profile(
        val artistLikes: Map<String, Double> = emptyMap(), val genreLikes: Map<String, Double> = emptyMap(),
        val artistDislikes: Map<String, Int> = emptyMap(), val genreDislikes: Map<String, Int> = emptyMap(),
        val skippedIds: Set<String> = emptySet(),
    )
    val EMPTY = Profile()

    /** Ile razy ten sam wykonawca / gatunek może być „odrzucony” (dalej już bez wzrostu kary). */
    const val DISLIKE_CAP = 4
    /** Maks. liczba zapamiętanych odrzuceń (najstarsze wypadają). */
    const val MAX_DISLIKES = 80
    /** Poniżej tej oceny propozycja odpada (np. wykonawca pominięty co najmniej dwa razy). */
    const val DROP_BELOW = -3.5
    /** Utwór pominięty w pierwszej części (ułamek długości) liczy się jako odrzucony. */
    const val SKIP_FRACTION = 0.5f

    fun genresOf(title: String, channel: String): Set<String> =
        StubeMixes.GENRES.filter { it.pattern.containsMatchIn("$title $channel") }.mapTo(HashSet()) { it.id }

    private fun artistOf(channel: String) = StubeMixes.artistKey(channel)

    // ---------- odrzucenia (Prefs) ----------

    private fun parse(s: String): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        s.split('\n').forEach { line -> val i = line.lastIndexOf('='); if (i > 0) line.substring(i + 1).toIntOrNull()?.let { out[line.substring(0, i)] = it } }
        return out
    }
    private fun format(m: Map<String, Int>) = m.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /** Zapamiętuje odrzucenie utworu: wykonawca i gatunki (kara przy kolejnych propozycjach) + identyfikator (nigdy więcej w propozycjach). */
    fun recordDislike(id: String, title: String, channel: String) {
        val m = parse(Prefs.ytDislikes)
        fun bump(k: String) { val v = (m.remove(k) ?: 0) + 1; m[k] = v.coerceAtMost(DISLIKE_CAP) }   // remove + put = najświeższe na końcu
        artistOf(channel).takeIf { it.isNotEmpty() }?.let { bump("a:$it") }
        genresOf(title, channel).forEach { bump("g:$it") }
        m.remove("v:$id"); m["v:$id"] = 1
        while (m.size > MAX_DISLIKES) m.remove(m.keys.first())
        Prefs.ytDislikes = format(m)
    }

    /** Cofnięcie kary (utwór wysłuchany do końca albo świadomie wybrany): wykonawca i gatunki łagodnieją. */
    fun forgive(title: String, channel: String) {
        val m = parse(Prefs.ytDislikes); if (m.isEmpty()) return
        fun ease(k: String) { val v = m[k] ?: return; if (v <= 1) m.remove(k) else m[k] = v - 1 }
        artistOf(channel).takeIf { it.isNotEmpty() }?.let { ease("a:$it") }
        genresOf(title, channel).forEach { ease("g:$it") }
        Prefs.ytDislikes = format(m)
    }

    fun clearDislikes() { Prefs.ytDislikes = "" }

    // ---------- profil ----------

    /** Profil z historii (pamięć [StubeHistory]) i zapisanych odrzuceń. */
    fun profile(history: Collection<WatchProgress> = StubeHistory.allCached() + StubeImport.asHistory(), now: Long = System.currentTimeMillis()): Profile {
        val dis = parse(Prefs.ytDislikes)
        val al = HashMap<String, Double>(); val gl = HashMap<String, Double>()
        history.forEach { p ->
            if (p.title.isBlank()) return@forEach
            // słuchane krótko (pominięte) nie świadczy o guście
            val w = if (!p.finished && p.fraction < SKIP_FRACTION) 0.25 * StubeMixes.weight(p, now) else StubeMixes.weight(p, now)
            artistOf(p.channel).takeIf { it.isNotEmpty() }?.let { al.merge(it, w, Double::plus) }
            genresOf(p.title, p.channel).forEach { gl.merge(it, w, Double::plus) }
        }
        return Profile(al, gl,
            dis.filterKeys { it.startsWith("a:") }.mapKeys { it.key.substring(2) },
            dis.filterKeys { it.startsWith("g:") }.mapKeys { it.key.substring(2) },
            dis.keys.filter { it.startsWith("v:") }.mapTo(HashSet()) { it.substring(2) })
    }

    private var memoKey = ""; private var memo = EMPTY

    /** Profil bieżący – przeliczany dopiero po zmianie historii lub odrzuceń (wywoływany przy każdym odświeżeniu listy). */
    @Synchronized fun current(): Profile {
        val h = StubeHistory.allCached() + StubeImport.asHistory()
        val key = "${h.size}|${h.maxOfOrNull { it.updatedAt } ?: 0}|${Prefs.ytDislikes.hashCode()}"
        if (key != memoKey) { memo = if (h.isEmpty() && Prefs.ytDislikes.isEmpty()) EMPTY else profile(h); memoKey = key }
        return memo
    }

    // ---------- ocena i dopasowanie ----------

    /**
     * Ocena propozycji [v] względem gustu [p] i bieżącego utworu ([curTitle]/[curChannel]): gatunki i wykonawcy z historii,
     * zgodność gatunku z bieżącym utworem; kary za odrzuconego wykonawcę / gatunek, pominięty utwór ma ocenę bardzo niską.
     */
    fun score(v: YtVideo, p: Profile, curTitle: String = "", curChannel: String = ""): Double {
        if (v.id in p.skippedIds) return -100.0
        val gs = genresOf(v.title, v.channel); val artist = artistOf(v.channel)
        val maxG = p.genreLikes.values.maxOrNull() ?: 0.0; val maxA = p.artistLikes.values.maxOrNull() ?: 0.0
        var s = 0.0
        if (maxG > 0) s += gs.sumOf { (p.genreLikes[it] ?: 0.0) / maxG }.coerceAtMost(2.0) * 1.5
        if (maxA > 0 && artist.isNotEmpty()) s += (p.artistLikes[artist] ?: 0.0) / maxA * 2.5
        if (curTitle.isNotBlank()) {
            val cg = genresOf(curTitle, curChannel)
            if (gs.any { it in cg }) s += 2.0
            if (artist.isNotEmpty() && artist == artistOf(curChannel)) s += 0.5
        }
        s -= 2.0 * (p.artistDislikes[artist] ?: 0)
        s -= gs.sumOf { 1.0 * (p.genreDislikes[it] ?: 0) }.coerceAtMost(4.0)
        return s
    }

    /**
     * Sortowanie propozycji wg [score] (stabilne – przy równej ocenie zostaje kolejność YouTube), bez odrzuconych. Gdy jest dość
     * trafnych (ocena > 0), pozycje bez żadnego związku z gustem ani bieżącym utworem odpadają („propozycje od czapy”).
     */
    fun rank(list: List<YtVideo>, p: Profile, curTitle: String = "", curChannel: String = "", enough: Int = StubeMixes.SUGGEST_MIN): List<YtVideo> {
        if (p === EMPTY) return list
        val scored = list.map { it to score(it, p, curTitle, curChannel) }.filter { it.second > DROP_BELOW }
        val sorted = scored.sortedByDescending { it.second }
        val relevant = sorted.filter { it.second > 0.0 }
        return (if (relevant.size >= enough) relevant else sorted).map { it.first }
    }

    /** Podobieństwo dwóch utworów: 2 – ten sam wykonawca, 1 – wspólny gatunek lub co najmniej dwa wspólne słowa tytułu, 0 – inne. */
    fun similarity(aTitle: String, aChannel: String, bTitle: String, bChannel: String): Int {
        val a = artistOf(aChannel)
        if (a.isNotEmpty() && a == artistOf(bChannel)) return 2
        if (genresOf(aTitle, aChannel).intersect(genresOf(bTitle, bChannel)).isNotEmpty()) return 1
        val ta = StubeMixes.tokens(aTitle).toSet()
        return if (StubeMixes.tokens(bTitle).count { it in ta } >= 2) 1 else 0
    }

    /**
     * Kolejka po pominięciu [skipped]: utwory tego samego wykonawcy znikają, o podobnym gatunku / klimacie – co drugi odpada, a
     * pozostałe lądują na końcu; reszta (inne utwory i kanały TV) zostaje na swoich miejscach i dostępna. Nigdy nie opróżnia kolejki.
     */
    fun afterSkip(queue: List<QueueItem>, skipped: YtVideo): List<QueueItem> {
        val keep = ArrayList<QueueItem>(); val soft = ArrayList<QueueItem>()
        queue.forEach { q ->
            val sim = if (!q.isVideo) 0 else if (q.ref == skipped.id) 2 else similarity(skipped.title, skipped.channel, q.title, q.subtitle)
            when (sim) {
                2 -> {}                                                    // ten sam wykonawca (albo ten sam utwór) – znika
                1 -> soft += q
                else -> keep += q
            }
        }
        val out = keep + soft.filterIndexed { i, _ -> i % 2 == 0 }        // co drugi podobny zostaje (na końcu), reszta znika
        return if (out.isEmpty()) queue.filter { it.ref != skipped.id }.ifEmpty { queue } else out
    }

    /** Czy pominięcie w chwili [posMs] z [durMs] liczy się jako odrzucenie (przed połową utworu; nieznana długość – przed 90 s). */
    fun isSkip(posMs: Long, durMs: Long): Boolean =
        if (durMs > 0) posMs < durMs * SKIP_FRACTION else posMs < 90_000
}

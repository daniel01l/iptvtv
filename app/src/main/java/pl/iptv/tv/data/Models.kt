package pl.iptv.tv.data

data class Channel(
    /** Stabilny identyfikator (URL + nr wystąpienia) – klucz edycji, ulubionych, historii. */
    val key: String,
    val name: String,
    val url: String,
    val group: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val catchupType: String = "",
    val catchupSource: String = "",
    val catchupDays: Int = 0,
    val userAgent: String = "",
    val referrer: String = "",
    val origName: String = name,
    val origGroup: String = group,
    val hidden: Boolean = false,
    /** null = automatyczne dopasowanie EPG, "" = brak EPG, inna wartość = id kanału XMLTV. */
    val epgOverride: String? = null,
    /** Numer na liście (1..n, 0 dla ukrytych). */
    val number: Int = 0,
) {
    /** Klucz kanału w EPG (id z XMLTV) – ustawiany po wczytaniu EPG. */
    @Volatile var epgKey: String? = null
    /** Ikona z XMLTV, używana gdy brak tvg-logo. */
    @Volatile var epgIcon: String? = null

    val hasCatchup: Boolean
        get() = catchupType.isNotBlank() || catchupSource.isNotBlank()
}

data class Programme(
    val start: Long,   // ms UTC
    val end: Long,     // ms UTC
    val title: String,
    val desc: String = "",
    val category: String = "",
    /** Tytuł odcinka (<sub-title>). */
    val subTitle: String = "",
    /** Numer odcinka, np. "S01E12" albo tekst z <episode-num system="onscreen">. */
    val episode: String = "",
    /** (7.17) Jawny znacznik transmisji na żywo w XMLTV (`<live />`). */
    val live: Boolean = false,
) {
    fun isNow(now: Long) = now in start until end
    /** Tytuł z informacją o odcinku – do wyświetlania. */
    val fullTitle: String
        get() = title + (if (episode.isNotBlank()) "  $episode" else "") + (if (subTitle.isNotBlank()) " – $subTitle" else "")
}

data class Playlist(
    val channels: List<Channel>,
    val epgUrls: List<String>,
)

/**
 * (7.17) Czy audycja to prawdziwa transmisja na żywo (czerwona pigułka „NA ŻYWO” – odtwarzacz, podgląd EPG, wyszukiwarka).
 * Kanał IPTV gra zawsze „na żywo”, ale serial czy film z odtworzenia nim nie jest.
 *
 * Tak, gdy audycja ma jawny znacznik XMLTV `<live />` albo słowo transmisji na żywo ([LIVE_WORDS]) w tytule, podtytule
 * lub kategorii (w opisie – tylko wyraźne „na żywo” / „live” / „transmisja”: opis filmu o sporcie nie robi z niego
 * transmisji) – ORAZ nie jest odcinkiem ([EPISODE]: S1E24, „odc.”, „sezon”, numer odcinka z EPG). Kategoria fabularna
 * ([SCRIPTED]: film, serial, bajka, dokument) wyklucza słowa kluczowe, ale nie jawny znacznik `<live />`.
 */
object LiveBroadcast {
    val LIVE_WORDS = listOf("na żywo", "live", "transmisja", "mecz", "studio przedmeczowe", "wydanie specjalne", "wiadomości", "fakty", "wydarzenia", "sport")
    private val DESC_WORDS = listOf("na żywo", "live", "transmisja")
    val SCRIPTED = listOf("film", "serial", "bajka", "dokument")
    val EPISODE = Regex("""\bS\d+\s*E\d+\b|\bodc\.|\bodcinek\b|\bsezon\b""", RegexOption.IGNORE_CASE)

    /** Słowo od początku wyrazu (np. „sport” w „Sport.pl”, ale nie w „Eurosport”; „mecz” też w „meczu”). */
    private fun has(text: String, words: List<String>): Boolean {
        val t = text.lowercase()
        return words.any { w ->
            var i = t.indexOf(w)
            while (i >= 0) { if (i == 0 || !t[i - 1].isLetterOrDigit()) return@any true; i = t.indexOf(w, i + 1) }
            false
        }
    }

    fun isEpisode(p: Programme) = p.episode.isNotBlank() || EPISODE.containsMatchIn(p.title) || EPISODE.containsMatchIn(p.subTitle)

    fun isActualLiveBroadcast(p: Programme?): Boolean {
        if (p == null || isEpisode(p)) return false
        if (p.live) return true
        if (has(p.category, SCRIPTED)) return false
        return has(p.title, LIVE_WORDS) || has(p.subTitle, LIVE_WORDS) || has(p.category, LIVE_WORDS) || has(p.desc, DESC_WORDS)
    }
}

/** Skrót: [LiveBroadcast.isActualLiveBroadcast]. */
fun isActualLiveBroadcast(program: Programme?): Boolean = LiveBroadcast.isActualLiveBroadcast(program)

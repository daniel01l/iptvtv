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

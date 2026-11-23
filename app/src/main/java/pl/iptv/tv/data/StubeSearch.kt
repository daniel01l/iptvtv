package pl.iptv.tv.data

/**
 * (7.17) Panel wyszukiwania STube: filtry, wyniki z rodzajem (film / kanał / playlista) i historia wyszukiwania
 * (Room `search_history` – [StubeHistory]; w panelu [HISTORY_MAX] ostatnich haseł).
 */
object StubeSearch {
    /** Filtry wyników (pigułki nad klawiaturą). */
    enum class Filter(val label: String) {
        ALL("Wszystko"), VIDEOS("Wideo"), CHANNELS("Kanały"), PLAYLISTS("Playlisty"), MUSIC("Muzyka"), LIVE("Na żywo")
    }

    /** Wynik: stały klucz ([key]) dla siatki, tytuł i podpis karty, miniatura. */
    sealed class Item {
        abstract val key: String
        abstract val title: String
        abstract val subtitle: String
        abstract val thumb: String

        data class Video(val video: YtVideo, val live: Boolean = false) : Item() {
            override val key get() = "v:" + video.id
            override val title get() = video.title
            override val subtitle get() = video.channel
            override val thumb get() = video.thumb
        }
        data class Channel(val id: String, val name: String, val avatar: String = "", val subscribers: Long = -1) : Item() {
            override val key get() = "c:$id"
            override val title get() = name
            override val subtitle get() = if (subscribers > 0) "Kanał · ${subscribersText(subscribers)}" else "Kanał"
            override val thumb get() = avatar
        }
        data class Playlist(val playlist: YtPlaylist, val owner: String = "") : Item() {
            override val key get() = "p:" + playlist.id
            override val title get() = playlist.title
            override val subtitle get() = listOfNotNull("Playlista", owner.ifBlank { null }, playlist.count.takeIf { it > 0 }?.let { "$it filmów" }).joinToString(" · ")
            override val thumb get() = playlist.thumb
        }
    }

    const val HISTORY_MAX = 15

    /** Liczba subskrybentów skrótem: 1,2 tys. / 3,4 mln. */
    fun subscribersText(n: Long): String = when {
        n >= 1_000_000 -> "%.1f mln".format(java.util.Locale("pl"), n / 1_000_000.0).replace(",0 ", " ")
        n >= 1_000 -> "%.1f tys.".format(java.util.Locale("pl"), n / 1_000.0).replace(",0 ", " ")
        else -> "$n"
    }

    /** Historia: ostatnie hasła (najnowsze pierwsze). */
    suspend fun history(): List<String> = StubeHistory.suggestions("", HISTORY_MAX)
    suspend fun remember(q: String) { if (q.isNotBlank()) StubeHistory.addSearch(q) }
    suspend fun forget(q: String) = StubeHistory.removeSearch(q)
}

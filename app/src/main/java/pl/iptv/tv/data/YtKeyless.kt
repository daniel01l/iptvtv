package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Listy i wyszukiwanie YouTube bez klucza API i bez konta (strony YouTube przez NewPipeExtractor).
 * STube działa dzięki temu od razu po instalacji; z kluczem/kontem rzędy biorą dane z YouTube Data API v3,
 * a te listy są zapasem przy błędzie API (np. wyczerpany limit).
 */
object YtKeyless {
    const val MUSIC = "trending_music"; const val LIVE = "live"; const val GAMING = "trending_gaming"
    const val MOVIES = "trending_movies_and_shows"; const val PODCASTS = "trending_podcasts_episodes"
    const val TRENDING = "Trending"

    /** Źródło danych (testy podmieniają na wersję bez sieci). */
    interface Source {
        fun kiosk(id: String): List<YtVideo>
        fun search(q: String): List<YtVideo>
        /** Propozycje YouTube po filmie (lista „Następne” – kolejność z zachowań innych widzów). */
        fun related(id: String): List<YtVideo> = emptyList()
        /** (7.14) Playlista YouTube: nazwa i utwory (do [MAX] pozycji) – przesyłanie linków do STube. */
        fun playlist(id: String): Pair<String, List<YtVideo>> = "" to emptyList()
        /**
         * (7.17) Wyszukiwanie z rodzajem wyników ([StubeSearch.Filter]): filmy, kanały, playlisty, muzyka, na żywo.
         * Domyślnie (źródła testowe) – same filmy z [search]; „Na żywo” – filmy o czasie „P0D”.
         */
        fun searchItems(q: String, filter: StubeSearch.Filter): List<StubeSearch.Item> {
            val v = search(q).map { StubeSearch.Item.Video(it, it.duration == "P0D") }
            return when (filter) {
                StubeSearch.Filter.CHANNELS, StubeSearch.Filter.PLAYLISTS -> emptyList()
                StubeSearch.Filter.LIVE -> v.filter { it.live }
                else -> v
            }
        }
    }

    object NewPipeSource : Source {
        override fun kiosk(id: String): List<YtVideo> {
            YtStreams.ensureInit()
            val e = ServiceList.YouTube.kioskList.getExtractorById(id, null)
            e.fetchPage()
            return KioskInfo.getInfo(e).relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::video)
        }
        override fun search(q: String): List<YtVideo> {
            YtStreams.ensureInit()
            val yt = ServiceList.YouTube
            return SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(q.trim(), listOf("videos"), ""))
                .relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::video)
        }
        override fun related(id: String): List<YtVideo> {
            YtStreams.ensureInit()
            return org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
                .relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::video)
        }
        override fun playlist(id: String): Pair<String, List<YtVideo>> {
            YtStreams.ensureInit()
            val yt = ServiceList.YouTube; val url = "https://www.youtube.com/playlist?list=$id"
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(yt, url)
            val out = ArrayList<YtVideo>()
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNullTo(out, ::video)
            var next = info.nextPage
            while (next != null && out.size < StubeImport.MAX_ITEMS) {
                val page = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(yt, url, next)
                page.items.filterIsInstance<StreamInfoItem>().mapNotNullTo(out, ::video); next = page.nextPage
            }
            return info.name.orEmpty() to out
        }
        override fun searchItems(q: String, filter: StubeSearch.Filter): List<StubeSearch.Item> {
            YtStreams.ensureInit()
            val yt = ServiceList.YouTube
            // filtry treści NewPipe: wszystko (pusta lista), filmy, kanały, playlisty, utwory YouTube Music
            val content = when (filter) {
                StubeSearch.Filter.ALL -> emptyList()
                StubeSearch.Filter.VIDEOS, StubeSearch.Filter.LIVE -> listOf("videos")
                StubeSearch.Filter.CHANNELS -> listOf("channels")
                StubeSearch.Filter.PLAYLISTS -> listOf("playlists")
                StubeSearch.Filter.MUSIC -> listOf("music_songs")
            }
            val items = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(q.trim(), content, "")).relatedItems.mapNotNull { i ->
                when (i) {
                    is StreamInfoItem -> video(i)?.let { StubeSearch.Item.Video(it, it.duration == "P0D") }
                    is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> channelId(i.url)?.let { id ->
                        StubeSearch.Item.Channel(id, i.name.orEmpty(), i.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), i.subscriberCount) }
                    is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> playlistId(i.url)?.let { id ->
                        StubeSearch.Item.Playlist(YtPlaylist(id, i.name.orEmpty(), i.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), i.streamCount.toInt()), i.uploaderName.orEmpty()) }
                    else -> null
                }
            }
            return if (filter == StubeSearch.Filter.LIVE) items.filter { it is StubeSearch.Item.Video && it.live } else items
        }
    }

    @Volatile var source: Source = NewPipeSource

    private const val TTL = 10 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<YtVideo>>>()
    fun clearCache() = synchronized(cache) { cache.clear() }

    suspend fun kiosk(id: String, force: Boolean = false): List<YtVideo> = withContext(Dispatchers.IO) {
        if (!force) synchronized(cache) { cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return@withContext it.second } }
        val l = try { source.kiosk(id) } catch (e: Exception) { throw YtException(YtStreams.message(e)) }
        synchronized(cache) { cache[id] = System.currentTimeMillis() to l }
        l
    }

    suspend fun search(q: String): List<YtVideo> = withContext(Dispatchers.IO) {
        try { source.search(q) } catch (e: Exception) { throw YtException(YtStreams.message(e)) }
    }

    /** Propozycje po filmie [id] (pamięć podręczna jak listy – miksy nie pytają YouTube przy każdym otwarciu). */
    suspend fun related(id: String): List<YtVideo> = withContext(Dispatchers.IO) {
        val k = "rel:$id"
        synchronized(cache) { cache[k]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return@withContext it.second } }
        val l = try { source.related(id) } catch (e: Exception) { throw YtException(YtStreams.message(e)) }
        synchronized(cache) { cache[k] = System.currentTimeMillis() to l }
        l
    }

    /** Playlista YouTube [id] (przesyłanie linków): nazwa i utwory; błąd → [YtException]. */
    suspend fun playlist(id: String): Pair<String, List<YtVideo>> = withContext(Dispatchers.IO) {
        try { source.playlist(id) } catch (e: Exception) { throw YtException(YtStreams.message(e)) }
    }

    /** (7.17) Wyszukiwanie z rodzajem wyników (panel wyszukiwania STube); błąd → [YtException]. */
    suspend fun searchItems(q: String, filter: StubeSearch.Filter): List<StubeSearch.Item> = withContext(Dispatchers.IO) {
        try { source.searchItems(q, filter) } catch (e: Exception) { throw YtException(YtStreams.message(e)) }
    }

    /** Kanał z adresu (/channel/UC…). */
    fun channelId(url: String): String? = Regex("/channel/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    /** Playlista z adresu (list=…). */
    fun playlistId(url: String): String? = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

    /** Identyfikator z adresu filmu (watch?v=…, youtu.be/…, /shorts/…). */
    fun idOf(url: String): String? =
        Regex("(?:v=|youtu\\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    /** Czas w sekundach → ISO 8601 (jak w YouTube Data API); transmisja na żywo → „P0D”. */
    fun isoDuration(seconds: Long, live: Boolean = false): String {
        if (live || seconds < 0) return "P0D"
        if (seconds == 0L) return ""
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (s > 0) "${s}S" else "")
    }

    fun video(i: StreamInfoItem): YtVideo? {
        val id = idOf(i.url) ?: return null
        val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
        // miniatura: największa z listy (YouTube podaje kilka rozmiarów)
        val thumb = i.thumbnails.maxByOrNull { it.height }?.url.orEmpty()
        return YtVideo(id, i.name, i.uploaderName.orEmpty(), thumb, "", isoDuration(i.duration, live))
    }
}

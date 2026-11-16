package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import pl.iptv.tv.BuildConfig

/** Film YouTube (odtwarzany w SmartTube: [url]). */
data class YtVideo(
    val id: String, val title: String, val channel: String,
    val thumb: String = "", val publishedAt: String = "", val duration: String = "",
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
}

/** Playlista konta (Biblioteka); [count] – liczba filmów (-1 = nieznana). */
data class YtPlaylist(val id: String, val title: String, val thumb: String = "", val count: Int = -1)

/** Kanał (subskrypcja). */
data class YtChannel(val id: String, val title: String, val thumb: String = "")

/** Źródło filmów; kolejność = wartości [Prefs.ytSource]. */
enum class YtSource(val label: String) {
    PERSONAL("Moje (polubione, konto)"), TRENDING("Na czasie (publiczne)");
    companion object { fun of(i: Int) = entries.getOrElse(i) { PERSONAL } }
}

/** Wynik: faktyczne źródło ([fellBack] – spersonalizowane niedostępne, pokazano trendy). */
data class YtFeed(val source: YtSource, val videos: List<YtVideo>, val nextPage: String? = null, val fellBack: Boolean = false)

class YtException(message: String, val http: Int = 0) : Exception(message)

/**
 * YouTube Data API v3. Zapytania z kontem – nagłówek Bearer z [YouTubeAuth.accessToken] (401 → odświeżenie i ponowienie),
 * publiczne – klucz API, a bez klucza token konta (gdy zalogowano).
 * Przełączanie: [Prefs.ytSource] – spersonalizowane (polubione, subskrypcje) albo trendy regionu [Prefs.ytRegion].
 */
object YouTubeRepository {
    var apiBase = "https://www.googleapis.com/youtube/v3"
    private const val TTL = 10 * 60_000L
    private const val PAGE = 25

    val apiKey: String get() = Prefs.ytApiKey.trim().ifBlank { BuildConfig.YT_API_KEY }

    var source: YtSource get() = YtSource.of(Prefs.ytSource); set(v) { Prefs.ytSource = v.ordinal; clearCache() }

    /** Źródło po uwzględnieniu logowania (spersonalizowane tylko z kontem). */
    val effectiveSource: YtSource get() = if (source == YtSource.PERSONAL && !YouTubeTokens.loggedIn) YtSource.TRENDING else source

    /** Czy da się pobrać trendy (klucz API albo konto). */
    val publicAvailable: Boolean get() = apiKey.isNotBlank() || YouTubeTokens.loggedIn

    private data class Cached(val at: Long, val feed: YtFeed)
    private val cache = HashMap<String, Cached>()
    fun clearCache() = synchronized(cache) { cache.clear() }

    /** Lista filmów dla wybranego źródła; spersonalizowane bez konta / po odwołaniu dostępu → trendy. */
    suspend fun feed(src: YtSource = source, pageToken: String? = null, force: Boolean = false): YtFeed {
        val key = "${src.name}|${Prefs.ytRegion}|${pageToken.orEmpty()}|${YouTubeTokens.loggedIn}"
        if (!force) synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.feed } }
        val f = if (src == YtSource.PERSONAL) {
            if (!YouTubeTokens.loggedIn) trending(pageToken).copy(fellBack = true)
            else try { liked(pageToken) } catch (e: YtException) {
                if (e.http == 401 && !YouTubeTokens.loggedIn) trending(pageToken).copy(fellBack = true) else throw e
            }
        } else trending(pageToken)
        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), f) }
        return f
    }

    /** Kategorie wideo YouTube (videoCategoryId) używane w rzędach STube. */
    const val CAT_MUSIC = "10"

    /** Trendy regionu; [categoryId] – tylko z kategorii (np. [CAT_MUSIC]). */
    suspend fun trending(pageToken: String? = null, categoryId: String? = null): YtFeed {
        val j = get("videos", mapOf("part" to "snippet,contentDetails", "chart" to "mostPopular", "videoCategoryId" to categoryId,
            "regionCode" to Prefs.ytRegion.ifBlank { "PL" }, "maxResults" to "$PAGE", "pageToken" to pageToken), auth = false)
        return YtFeed(YtSource.TRENDING, videos(j), j.optString("nextPageToken").ifBlank { null })
    }

    /** Wynik z pamięci (10 min) – rzędy STube nie zużywają limitu API przy każdym otwarciu ekranu. */
    suspend fun cachedTrending(categoryId: String? = null, pageToken: String? = null, force: Boolean = false): YtFeed {
        val key = "T|${categoryId.orEmpty()}|${Prefs.ytRegion}|${pageToken.orEmpty()}"
        if (!force) synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.feed } }
        return trending(pageToken, categoryId).also { f -> synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), f) } }
    }

    /** Polubione filmy konta (playlista „LL” zastąpiona przez myRating=like). */
    suspend fun liked(pageToken: String? = null): YtFeed {
        val j = get("videos", mapOf("part" to "snippet,contentDetails", "myRating" to "like", "maxResults" to "$PAGE", "pageToken" to pageToken), auth = true)
        return YtFeed(YtSource.PERSONAL, videos(j), j.optString("nextPageToken").ifBlank { null })
    }

    suspend fun subscriptions(pageToken: String? = null): List<YtChannel> {
        val j = get("subscriptions", mapOf("part" to "snippet", "mine" to "true", "maxResults" to "50", "order" to "alphabetical", "pageToken" to pageToken), auth = true)
        return items(j).mapNotNull { o ->
            val s = o.optJSONObject("snippet") ?: return@mapNotNull null
            val id = s.optJSONObject("resourceId")?.optString("channelId").orEmpty().ifBlank { return@mapNotNull null }
            YtChannel(id, s.optString("title"), thumb(s))
        }
    }

    /** Wyszukiwanie (z kontem – wyniki spersonalizowane przez Google). */
    suspend fun search(q: String, pageToken: String? = null): List<YtVideo> {
        val j = get("search", mapOf("part" to "snippet", "type" to "video", "q" to q.trim(), "maxResults" to "$PAGE",
            "regionCode" to Prefs.ytRegion.ifBlank { "PL" }, "pageToken" to pageToken), auth = YouTubeTokens.loggedIn && apiKey.isBlank())
        return items(j).mapNotNull { o ->
            val id = o.optJSONObject("id")?.optString("videoId").orEmpty().ifBlank { return@mapNotNull null }
            val s = o.optJSONObject("snippet") ?: JSONObject()
            YtVideo(id, s.optString("title"), s.optString("channelTitle"), thumb(s), s.optString("publishedAt"))
        }
    }

    /** Nazwa kanału zalogowanego konta (do opisu w Ustawieniach). */
    suspend fun myChannelTitle(): String? = runCatching {
        items(get("channels", mapOf("part" to "snippet", "mine" to "true"), auth = true)).firstOrNull()
            ?.optJSONObject("snippet")?.optString("title")?.ifBlank { null }
    }.getOrNull()


    // ---------------- Subskrypcje i biblioteka ----------------

    /** Ile kanałów z subskrypcji brać do rzędu „Subskrypcje” (każdy = 1 jednostka limitu API) i ile filmów z kanału. */
    const val SUB_CHANNELS = 30
    const val SUB_PER_CHANNEL = 5
    const val SUB_FEED_MAX = 50

    /** Playlista „przesłane” kanału (UC… → UU…) – najnowsze filmy kanału za 1 jednostkę limitu (search kosztuje 100). */
    fun uploadsPlaylist(channelId: String): String = if (channelId.startsWith("UC")) "UU" + channelId.substring(2) else channelId

    /**
     * Najnowsze filmy z subskrybowanych kanałów (jak karta „Subskrypcje” w YouTube): [SUB_CHANNELS] kanałów (kolejność trafności Google),
     * z każdego [SUB_PER_CHANNEL] ostatnich filmów z playlisty „przesłane”, scalone malejąco po dacie publikacji; czas filmów – jedno
     * zapytanie videos. Pamięć 10 min. Kanał bez filmów / z błędem jest pomijany (rząd nie znika przez jeden kanał).
     */
    suspend fun subscriptionFeed(force: Boolean = false): List<YtVideo> {
        val key = "SUBS|${YouTubeTokens.loggedIn}"
        if (!force) synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.feed.videos } }
        val j = get("subscriptions", mapOf("part" to "snippet", "mine" to "true", "maxResults" to "$SUB_CHANNELS", "order" to "relevance"), auth = true)
        val channels = items(j).mapNotNull { it.optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("channelId")?.ifBlank { null } }
        val latest = kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            channels.map { ch ->
                async { gate.withPermit { runCatching { playlistEntries(uploadsPlaylist(ch), SUB_PER_CHANNEL, null).first }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten()
        }
        val newest = mergeNewest(latest, SUB_FEED_MAX)
        val videos = withDetails(newest)
        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), YtFeed(YtSource.PERSONAL, videos)) }
        return videos
    }

    /** Scalenie filmów z wielu kanałów: bez powtórzeń, najnowsze pierwsze (ISO 8601 – porównanie tekstowe). Czysta funkcja (testy). */
    fun mergeNewest(videos: List<YtVideo>, max: Int): List<YtVideo> =
        videos.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(max)

    /** Playlisty konta (Biblioteka). */
    suspend fun myPlaylists(pageToken: String? = null): Pair<List<YtPlaylist>, String?> {
        val j = get("playlists", mapOf("part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "$PAGE", "pageToken" to pageToken), auth = true)
        return items(j).mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val s = o.optJSONObject("snippet") ?: JSONObject()
            YtPlaylist(id, s.optString("title"), thumb(s), o.optJSONObject("contentDetails")?.optInt("itemCount", -1) ?: -1)
        } to j.optString("nextPageToken").ifBlank { null }
    }

    /** Filmy playlisty (konta lub kanału) z czasem trwania; usunięte i prywatne pominięte. */
    suspend fun playlistVideos(playlistId: String, pageToken: String? = null): YtFeed {
        val (entries, next) = playlistEntries(playlistId, PAGE, pageToken)
        return YtFeed(YtSource.PERSONAL, withDetails(entries), next)
    }

    /** Pozycje playlisty bez czasu filmu (playlistItems: 1 jednostka). Z kontem – także playlisty prywatne. */
    private suspend fun playlistEntries(playlistId: String, max: Int, pageToken: String?): Pair<List<YtVideo>, String?> {
        val j = get("playlistItems", mapOf("part" to "snippet,contentDetails", "playlistId" to playlistId, "maxResults" to "$max", "pageToken" to pageToken),
            auth = YouTubeTokens.loggedIn)
        return items(j).mapNotNull { o ->
            val s = o.optJSONObject("snippet") ?: return@mapNotNull null
            val cd = o.optJSONObject("contentDetails")
            val id = cd?.optString("videoId")?.ifBlank { null } ?: s.optJSONObject("resourceId")?.optString("videoId")?.ifBlank { null } ?: return@mapNotNull null
            val title = s.optString("title")
            if (title == "Private video" || title == "Deleted video") return@mapNotNull null
            YtVideo(id, title, s.optString("videoOwnerChannelTitle").ifBlank { s.optString("channelTitle") }, thumb(s),
                cd?.optString("videoPublishedAt")?.ifBlank { null } ?: s.optString("publishedAt"))
        } to j.optString("nextPageToken").ifBlank { null }
    }

    /** Czas trwania (contentDetails) dla listy filmów – jedno zapytanie na 50 filmów; przy błędzie – filmy bez czasu. */
    private suspend fun withDetails(list: List<YtVideo>): List<YtVideo> {
        if (list.isEmpty()) return list
        val details = HashMap<String, String>()
        list.chunked(50).forEach { part ->
            runCatching {
                items(get("videos", mapOf("part" to "contentDetails", "id" to part.joinToString(",") { it.id }), auth = YouTubeTokens.loggedIn && apiKey.isBlank()))
                    .forEach { o -> details[o.optString("id")] = o.optJSONObject("contentDetails")?.optString("duration").orEmpty() }
            }
        }
        return list.map { v -> details[v.id]?.let { v.copy(duration = it) } ?: v }
    }
    // ---------------- HTTP ----------------

    private suspend fun get(path: String, params: Map<String, String?>, auth: Boolean): JSONObject {
        val useToken = auth || apiKey.isBlank()
        val token = if (useToken) YouTubeAuth.accessToken() else null
        if (useToken && token == null) throw YtException(if (auth) "Zaloguj się do konta YouTube" else "Brak klucza API – zaloguj się lub dodaj klucz w Ustawieniach", 401)
        val first = call(path, params, token)
        if (first.first != 401 || token == null) return check(first)
        // token odrzucony (odwołany / wygasł wcześniej) – odświeżenie i jedna ponowna próba
        if (!YouTubeAuth.refresh(force = true)) throw YtException("Sesja wygasła – zaloguj się ponownie", 401)
        return check(call(path, params, YouTubeTokens.accessToken))
    }

    private suspend fun call(path: String, params: Map<String, String?>, token: String?): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
        val url = "$apiBase/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
            if (token == null) addQueryParameter("key", apiKey)
        }.build()
        val rq = Request.Builder().url(url).apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        try {
            Repository.http.newCall(rq).execute().use { r ->
                r.code to (runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: JSONObject())
            }
        } catch (e: java.io.IOException) { throw YtException("Brak połączenia z YouTube") }
    }

    private fun check(res: Pair<Int, JSONObject>): JSONObject {
        val (code, j) = res
        if (code in 200..299) return j
        val err = j.optJSONObject("error")
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        throw YtException(when {
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> "Wyczerpany dzienny limit YouTube API"
            reason == "accessNotConfigured" -> "YouTube Data API v3 nie jest włączone dla tego klienta"
            code == 401 -> "Sesja wygasła – zaloguj się ponownie"
            code == 403 -> "Brak dostępu (HTTP 403)"
            else -> err?.optString("message")?.ifBlank { null } ?: "Błąd YouTube (HTTP $code)"
        }, code)
    }

    private fun items(j: JSONObject): List<JSONObject> = j.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private fun thumb(s: JSONObject): String = s.optJSONObject("thumbnails")?.let { t ->
        listOf("high", "medium", "default").firstNotNullOfOrNull { t.optJSONObject(it)?.optString("url")?.ifBlank { null } }
    }.orEmpty()

    private fun videos(j: JSONObject): List<YtVideo> = items(j).mapNotNull { o ->
        val id = o.optString("id").ifBlank { return@mapNotNull null }
        val s = o.optJSONObject("snippet") ?: JSONObject()
        YtVideo(id, s.optString("title"), s.optString("channelTitle"), thumb(s), s.optString("publishedAt"),
            o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
    }
}

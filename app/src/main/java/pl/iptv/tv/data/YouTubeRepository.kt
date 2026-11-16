package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
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

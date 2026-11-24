package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * (7.20) Metadane TMDB dla Stremio: polskie tytuły, opisy i gatunki (`language=pl-PL`) oraz części kolekcji filmów
 * (`belongs_to_collection` → `/collection/{id}`). Działa z kluczem API ([apiKey]: `keystore/tmdb.properties` →
 * `BuildConfig.TMDB_API_KEY`); bez klucza zwraca null – moduł Stremio zostaje przy danych wtyczek (Cinemeta, wyszukiwanie).
 * Wyniki w pamięci podręcznej; błąd sieci / brak tłumaczenia – null (bezpieczny powrót do danych angielskich).
 */
object StremioTmdb {
    var apiKey: () -> String = { runCatching { pl.iptv.tv.BuildConfig.TMDB_API_KEY }.getOrDefault("") }
    var base = "https://api.themoviedb.org/3"
    const val TIMEOUT_MS = 6_000L

    val available: Boolean get() = apiKey().isNotBlank()

    /** Język metadanych wg języka aplikacji: polski → `pl-PL`, inaczej `en-US`. */
    fun language(): String = if (polishWanted) "pl-PL" else "en-US"
    /** Aplikacja po polsku – opisy, tytuły i gatunki mają być polskie. */
    val polishWanted: Boolean get() = runCatching { Prefs.language }.getOrDefault("pl").startsWith("pl")

    data class Localized(val title: String, val overview: String, val genres: List<String>)

    private val cache = HashMap<String, Any>()
    private val NONE = Any()

    private suspend fun get(path: String, params: String = ""): JSONObject? {
        val key = apiKey().ifBlank { return null }
        val url = "$base$path?api_key=$key" + (if (params.isNotEmpty()) "&$params" else "")
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    StremioApi.client().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
                    }
                }.getOrNull()
            }
        }
    }

    /** Identyfikator TMDB i rodzaj (movie / tv) dla identyfikatora IMDb (tt…). */
    private suspend fun find(imdbId: String): Pair<String, Long>? {
        if (!imdbId.startsWith("tt")) return null
        synchronized(cache) { cache["find/$imdbId"] }?.let { @Suppress("UNCHECKED_CAST") return if (it === NONE) null else it as Pair<String, Long> }
        val o = get("/find/$imdbId", "external_source=imdb_id")
        val r = o?.let { j ->
            j.optJSONArray("movie_results")?.optJSONObject(0)?.let { "movie" to it.optLong("id") }
                ?: j.optJSONArray("tv_results")?.optJSONObject(0)?.let { "tv" to it.optLong("id") }
        }?.takeIf { it.second > 0 }
        synchronized(cache) { cache["find/$imdbId"] = r ?: NONE }
        return r
    }

    /** Polskie (wg [language]) tytuł, opis i gatunki; null – brak klucza, brak pozycji albo brak tłumaczenia opisu i tytułu. */
    suspend fun localized(imdbId: String): Localized? {
        if (!available) return null
        val lang = language()
        synchronized(cache) { cache["loc/$lang/$imdbId"] }?.let { return if (it === NONE) null else it as Localized }
        val (kind, id) = find(imdbId) ?: return null.also { synchronized(cache) { cache["loc/$lang/$imdbId"] = NONE } }
        val o = get("/$kind/$id", "language=$lang")
        val l = o?.let { j ->
            Localized(j.optString(if (kind == "tv") "name" else "title"), j.optString("overview"),
                (0 until (j.optJSONArray("genres")?.length() ?: 0)).mapNotNull { j.optJSONArray("genres")?.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() } })
        }?.takeIf { it.overview.isNotBlank() || it.title.isNotBlank() }
        synchronized(cache) { cache["loc/$lang/$imdbId"] = l ?: NONE }
        return l
    }

    /** Metadane z polskim opisem / tytułem / gatunkami; brak tłumaczenia danego pola – zostaje oryginał (angielski). */
    suspend fun localize(m: StremioMeta): StremioMeta {
        if (!polishWanted || !available) return m
        val l = localized(m.id.substringBefore(':')) ?: return m
        return m.copy(name = l.title.ifBlank { m.name }, description = l.overview.ifBlank { m.description }, genres = l.genres.ifEmpty { m.genres })
    }

    /**
     * Części kolekcji filmu (razem z bieżącym), od najstarszej premiery: TMDB `belongs_to_collection` → `/collection/{id}`,
     * każda część dopasowana do identyfikatora IMDb (`/movie/{id}/external_ids`) – zgodnego z wtyczkami Stremio. Null – brak
     * klucza albo film poza kolekcją (wtedy wyszukiwanie po tytule w [pl.iptv.tv.ui.StremioMetas.relatedParts]).
     */
    suspend fun collectionParts(imdbId: String): List<StremioMetaPreview>? {
        if (!available) return null
        val lang = language()
        val k = "col/$lang/$imdbId"
        synchronized(cache) { cache[k] }?.let { @Suppress("UNCHECKED_CAST") return if (it === NONE) null else it as List<StremioMetaPreview> }
        val (kind, id) = find(imdbId) ?: return null
        val result = if (kind != "movie") null else run {
            val colId = get("/movie/$id", "language=$lang")?.optJSONObject("belongs_to_collection")?.optLong("id")?.takeIf { it > 0 } ?: return@run null
            val parts = get("/collection/$colId", "language=$lang")?.optJSONArray("parts") ?: return@run null
            coroutineScope {
                (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.map { p ->
                    async {
                        val imdb = get("/movie/${p.optLong("id")}/external_ids")?.optString("imdb_id").orEmpty()
                        if (!imdb.startsWith("tt")) null else StremioMetaPreview(imdb, "movie", p.optString("title"),
                            p.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty(),
                            description = p.optString("overview"), releaseInfo = p.optString("release_date").take(4), released = p.optString("release_date"))
                    }
                }.mapNotNull { it.await() }
            }.sortedWith(compareBy({ it.released.ifBlank { "9999" } }, { it.name }))
        }
        synchronized(cache) { cache[k] = result ?: NONE }
        return result
    }

    /**
     * (7.20) Ranking popularności platformy: `/discover/{movie|tv}` z `with_watch_providers` = [providerId],
     * `watch_region` = [region], `sort_by=popularity.desc`, język aplikacji ([language] – po polsku `pl-PL`). Do [limit] pozycji
     * z identyfikatorem IMDb (`external_ids` – zgodny z wtyczkami Stremio), w kolejności rankingu. Null – brak klucza / błąd sieci.
     */
    suspend fun discover(type: String, providerId: Int, region: String = "PL", limit: Int = 10): List<StremioMetaPreview>? {
        if (!available) return null
        val kind = if (type == "series") "tv" else "movie"
        val o = get("/discover/$kind", "with_watch_providers=$providerId&watch_region=$region&sort_by=popularity.desc&language=${language()}&include_adult=false&page=1")
            ?: return null
        val results = o.optJSONArray("results") ?: return emptyList()
        val list = (0 until results.length()).mapNotNull { results.optJSONObject(it) }.take(limit * 2)
        val mapped = coroutineScope {
            list.map { r ->
                async {
                    val imdb = get("/$kind/${r.optLong("id")}/external_ids")?.optString("imdb_id").orEmpty()
                    if (!imdb.startsWith("tt")) null else {
                        val date = r.optString(if (kind == "tv") "first_air_date" else "release_date")
                        StremioMetaPreview(imdb, type, r.optString(if (kind == "tv") "name" else "title"),
                            r.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty(),
                            description = r.optString("overview"), releaseInfo = date.take(4), released = date,
                            imdbRating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty())
                    }
                }
            }.mapNotNull { it.await() }
        }
        return mapped.distinctBy { it.id }.take(limit)
    }

    /** Testy. */
    fun clearCache() = synchronized(cache) { cache.clear() }
}

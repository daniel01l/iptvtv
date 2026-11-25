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
    /** (7.21) Klucz użytkownika z Ustawień Stremio ([Prefs.tmdbApiKey]); pusty – funkcje TMDB wyłączone (jak w 7.19). */
    var apiKey: () -> String = { runCatching { Prefs.tmdbApiKey }.getOrDefault("") }
    var base = "https://api.themoviedb.org/3"
    const val TIMEOUT_MS = 6_000L
    const val RETRY_429_MAX_MS = 2_500L

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
                // (7.22) HTTP 429 (limit zapytań TMDB) – jedno ponowienie po `Retry-After` (najwyżej [RETRY_429_MAX_MS])
                var attempt = 0
                while (true) {
                    var wait = 0L
                    val res = runCatching {
                        StremioApi.client().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                            if (r.code == 429 && attempt == 0) { wait = (r.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 1000L).coerceIn(200L, RETRY_429_MAX_MS); null }
                            else if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
                        }
                    }.getOrNull()
                    if (wait == 0L) return@withTimeoutOrNull res
                    attempt++; kotlinx.coroutines.delay(wait)
                }
                @Suppress("UNREACHABLE_CODE") null
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
    suspend fun discover(type: String, providers: String, region: String = "PL", limit: Int = 10): List<StremioMetaPreview>? {
        if (!available) return null
        val kind = if (type == "series") "tv" else "movie"
        // (7.22) kilka identyfikatorów platformy naraz („1899|384” = LUB w TMDB) – jedno zapytanie zamiast kolejnych prób
        val o = get("/discover/$kind", "with_watch_providers=$providers&watch_region=$region&sort_by=popularity.desc&language=${language()}&include_adult=false&page=1")
            ?: return null
        val results = o.optJSONArray("results") ?: return emptyList()
        // (7.22) bez `external_ids` dla każdej pozycji (dawniej ~20 zapytań na rząd – HTTP 429 na TV): identyfikator TMDB,
        // a IMDb (tt…) dopiero po kliknięciu ([imdbFor])
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { preview(it, kind, type) }.distinctBy { it.id }.take(limit)
    }

    /** (7.22) Identyfikator zastępczy pozycji TMDB („tmdb:movie:123” / „tmdb:tv:456”) – rozwiązywany do IMDb przy otwarciu. */
    const val ID_PREFIX = "tmdb:"
    fun tmdbId(kind: String, id: Long) = "$ID_PREFIX$kind:$id"
    fun isTmdbId(id: String) = id.startsWith(ID_PREFIX)
    /** „tmdb:movie:123” → („movie”, 123); inny identyfikator – null. */
    fun parseTmdbId(id: String): Pair<String, Long>? {
        if (!isTmdbId(id)) return null
        val p = id.removePrefix(ID_PREFIX).split(':')
        val kind = p.getOrNull(0)?.takeIf { it == "movie" || it == "tv" } ?: return null
        return p.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { kind to it }
    }

    /** Rozmiar plakatów TMDB (342 px szerokości – kafelek ma ok. 150–300 px; nigdy „original”). */
    const val POSTER_SIZE = "w342"
    fun posterUrl(path: String?): String = path?.takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$POSTER_SIZE$it" }.orEmpty()

    private fun preview(r: JSONObject, kind: String, type: String): StremioMetaPreview? {
        val id = r.optLong("id").takeIf { it > 0 } ?: return null
        val title = r.optString(if (kind == "tv") "name" else "title").ifBlank { return null }
        val date = r.optString(if (kind == "tv") "first_air_date" else "release_date").takeIf { it != "null" }.orEmpty()
        return StremioMetaPreview(tmdbId(kind, id), type, title, posterUrl(r.optString("poster_path")),
            description = r.optString("overview").takeIf { it != "null" }.orEmpty(), releaseInfo = date.take(4), released = date,
            imdbRating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty())
    }

    /**
     * (7.22) Identyfikator IMDb (tt…) pozycji: „tt…” bez zmian, „tmdb:…” – `/movie|tv/{id}/external_ids` (pamięć podręczna).
     * Null – brak klucza, brak sieci albo tytuł bez IMDb (niedostępny we wtyczkach Stremio).
     */
    suspend fun imdbFor(id: String): String? {
        if (id.startsWith("tt")) return id
        val (kind, n) = parseTmdbId(id) ?: return null
        val k = "ext/$kind/$n"
        synchronized(cache) { cache[k] }?.let { return if (it === NONE) null else it as String }
        val o = get("/$kind/$n/external_ids") ?: return null          // błąd sieci – bez zapisu (spróbuje ponownie)
        val imdb = o.optString("imdb_id").takeIf { it.startsWith("tt") }
        synchronized(cache) { cache[k] = imdb ?: NONE }
        return imdb
    }

    /** Pozycja z identyfikatorem IMDb (rozwiązana przy otwarciu); null – brak odpowiednika w Stremio. */
    suspend fun resolve(m: StremioMetaPreview): StremioMetaPreview? = if (!isTmdbId(m.id)) m else imdbFor(m.id)?.let { m.copy(id = it) }

    /** Ile dni wstecz liczy się premiera kinowa w „Obecnie w kinach”. */
    const val CINEMA_DAYS = 45L

    /**
     * (7.22) „Obecnie w kinach” – polski repertuar: `/movie/now_playing` (region PL) + `/discover/movie` z premierą kinową
     * (`with_release_type=3|2`) w ostatnich [CINEMA_DAYS] dniach, popularność malejąco, `pl-PL`; połączone bez powtórzeń
     * (po identyfikatorze TMDB). Null – brak klucza albo oba zapytania nieudane (wtedy Cinemeta).
     */
    suspend fun nowInCinemas(today: java.time.LocalDate = java.time.LocalDate.now(), limit: Int = 30): List<StremioMetaPreview>? {
        if (!available) return null
        val lang = language()
        val (now, disc) = coroutineScope {
            val a = async { get("/movie/now_playing", "language=$lang&region=PL&page=1") }
            val b = async {
                get("/discover/movie", "language=$lang&region=PL&with_release_type=3|2&release_date.gte=${today.minusDays(CINEMA_DAYS)}" +
                    "&release_date.lte=$today&sort_by=popularity.desc&include_adult=false&page=1")
            }
            a.await() to b.await()
        }
        if (now == null && disc == null) return null
        fun list(o: JSONObject?) = o?.optJSONArray("results")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it) } }.orEmpty()
        return (list(now) + list(disc)).mapNotNull { preview(it, "movie", "movie") }.distinctBy { it.id }.take(limit)
    }

    /** (7.21) Sprawdzenie klucza jednym zapytaniem: true – działa, false – odrzucony (401), null – brak sieci. */
    suspend fun validate(key: String): Boolean? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                StremioApi.client().newCall(okhttp3.Request.Builder().url("$base/configuration?api_key=${key.trim()}").build()).execute().use { r ->
                    when { r.isSuccessful -> true; r.code == 401 || r.code == 403 -> false; else -> null }
                }
            }.getOrNull()
        }
    }

    /** Informacja wymagana przez TMDB przy korzystaniu z API. */
    const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

    /** Testy. */
    fun clearCache() = synchronized(cache) { cache.clear() }
}

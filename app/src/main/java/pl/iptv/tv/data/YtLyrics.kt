package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tekst piosenki w widoku muzycznym STube. Źródło: otwarta baza tekstów LRCLIB (bez klucza): wysyłany jest wyłącznie tytuł
 * utworu, wykonawca i czas trwania bieżącego utworu (żadnych danych konta ani historii). Brak tekstu / błąd sieci = null
 * (zakładka pokazuje „Brak tekstu dla tego utworu”). Wyniki w pamięci (ostatnie [CACHE_MAX] utworów). Wyłączane:
 * [Prefs.ytLyrics].
 */
object YtLyrics {
    var apiBase = "https://lrclib.net/api"
    const val CACHE_MAX = 30
    private val cache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String?>) = size > CACHE_MAX
    }

    /** Tytuł bez dopisków typu „(Official Video)”, „[HD]”, „ft. …”, „- Topic”, „| kanał”. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("(?i)[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|teledysk|clip|hd|4k|remaster|visuali[sz]er|mv)[^\\)\\]]*[\\)\\]]"), " ")
        .replace(Regex("(?i)\\b(ft|feat)\\.?\\s.*$"), " ")
        .replace(Regex("\\s*[|｜].*$"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—')

    /** Wykonawca i tytuł z nazwy kanału i tytułu filmu („Artysta – Utwór” w tytule ma pierwszeństwo przed nazwą kanału). */
    fun artistAndTrack(title: String, channel: String): Pair<String, String> {
        val c = channel.trim().removeSuffix("- Topic").removeSuffix("VEVO").trim()
        val parts = title.split(Regex("\\s[-–—]\\s"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) cleanTitle(parts[0]) to cleanTitle(parts[1])
        else c to cleanTitle(title)
    }

    /** Tekst (zwykły, bez znaczników czasu) albo null. Wywołanie w tle (Dispatchers.IO). */
    suspend fun find(title: String, channel: String, durationSec: Long = 0): String? {
        if (!Prefs.ytLyrics) return null
        val (artist, track) = artistAndTrack(title, channel)
        if (track.isBlank()) return null
        val key = "$artist|$track".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val text = withContext(Dispatchers.IO) { runCatching { fetch(artist, track, durationSec) }.getOrNull() }
        synchronized(cache) { cache[key] = text }
        return text
    }

    private fun fetch(artist: String, track: String, durationSec: Long): String? {
        fun get(url: okhttp3.HttpUrl): String? = Repository.http.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
        // dokładne dopasowanie (wykonawca + tytuł + czas ±2 s); bez wyniku – wyszukiwanie
        val exact = "$apiBase/get".toHttpUrl().newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", track)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }.build()
        get(exact)?.let { parse(JSONObject(it))?.let { t -> return t } }
        val search = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("track_name", track)
            .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build()
        val arr = get(search)?.let { JSONArray(it) } ?: return null
        for (i in 0 until arr.length()) parse(arr.optJSONObject(i) ?: continue)?.let { return it }
        return null
    }

    /** Tekst z odpowiedzi: zwykły albo (gdy brak) zsynchronizowany bez znaczników czasu; utwór instrumentalny – null. */
    fun parse(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return null
        val plain = o.optString("plainLyrics").trim()
        if (plain.isNotEmpty()) return plain
        val synced = o.optString("syncedLyrics")
        return synced.lines().map { it.replace(Regex("^\\[[^\\]]*]\\s*"), "") }.joinToString("\n").trim().ifEmpty { null }
    }
}

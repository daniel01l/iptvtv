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

    /** Wiersz tekstu z czasem (ms) rozpoczęcia – karaoke. */
    data class Line(val ms: Long, val text: String)

    /** Tekst: zwykły ([plain]) i – gdy baza ma znaczniki czasu – wiersze zsynchronizowane z utworem ([synced]). */
    class Result(val plain: String, val synced: List<Line>) {
        val isSynced get() = synced.size >= 2
    }

    private val cache = object : LinkedHashMap<String, Result?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Result?>) = size > CACHE_MAX
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
    suspend fun find(title: String, channel: String, durationSec: Long = 0): Result? {
        if (!Prefs.ytLyrics) return null
        val (artist, track) = artistAndTrack(title, channel)
        if (track.isBlank()) return null
        val key = "$artist|$track".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val text = withContext(Dispatchers.IO) { runCatching { fetch(artist, track, durationSec) }.getOrNull() }
        synchronized(cache) { cache[key] = text }
        return text
    }

    private fun fetch(artist: String, track: String, durationSec: Long): Result? {
        fun get(url: okhttp3.HttpUrl): String? = Repository.http.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
        // dokładne dopasowanie (wykonawca + tytuł + czas ±2 s); bez wyniku – wyszukiwanie
        val exact = "$apiBase/get".toHttpUrl().newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", track)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }.build()
        val first = get(exact)?.let { parse(JSONObject(it)) }
        if (first?.isSynced == true) return first
        val search = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("track_name", track)
            .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build()
        val arr = runCatching { get(search)?.let { JSONArray(it) } }.getOrNull()
        val found = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parse) }
        return found.firstOrNull { it.isSynced } ?: first ?: found.firstOrNull()
    }

    /** Tekst z odpowiedzi (zwykły + zsynchronizowany); utwór instrumentalny albo brak tekstu – null. */
    fun parse(o: JSONObject): Result? {
        if (o.optBoolean("instrumental")) return null
        val synced = parseSynced(o.optString("syncedLyrics"))
        var plain = o.optString("plainLyrics").trim()
        if (plain.isEmpty() && synced.isNotEmpty()) plain = synced.joinToString("\n") { it.text }
        if (plain.isEmpty()) return null
        return Result(plain, synced)
    }

    /** Wiersze LRC: „[mm:ss.xx] tekst” (także kilka znaczników na wiersz), posortowane po czasie. */
    fun parseSynced(lrc: String): List<Line> {
        val tag = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
        val out = ArrayList<Line>()
        for (raw in lrc.lines()) {
            val stamps = tag.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (s in stamps) {
                val sec = s.groupValues[2].replace(':', '.').toDoubleOrNull() ?: continue
                out += Line((s.groupValues[1].toLong() * 60_000 + sec * 1000).toLong(), text)
            }
        }
        return out.sortedBy { it.ms }
    }
}

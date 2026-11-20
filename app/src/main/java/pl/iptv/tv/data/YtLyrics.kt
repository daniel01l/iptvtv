package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tekst piosenki w widoku muzycznym STube. Źródła (kaskada, krótki limit czasu na zapytanie, wszystko w tle): otwarta baza
 * LRCLIB (dokładne dopasowanie, wyszukiwanie dla tytułu oczyszczonego, bez dopisków o remiksie i zapytanie ogólne), potem
 * zapasowe źródło zwykłego tekstu ([fallbackBase]). Wysyłany jest wyłącznie tytuł, wykonawca i czas utworu. Wyniki: pamięć
 * ([CACHE_MAX] utworów) i Room (tabela lyrics_cache; brak tekstu pamiętany [MISSING_TTL_MS]). Wyłączane: [Prefs.ytLyrics].
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

    /** Czas, po którym ponawiane jest zapytanie o utwór bez tekstu. */
    const val MISSING_TTL_MS = 6 * 3600_000L
    const val FOUND_TTL_MS = 90L * 24 * 3600_000L
    const val CALL_TIMEOUT_S = 6L
    /** Zapasowe źródło zwykłego tekstu (bez znaczników czasu); pusty – wyłączone (testy). */
    var fallbackBase = "https://api.lyrics.ovh/v1"

    /** Tytuł bez dopisków typu „(Official Video)”, „[Lyrics]”, „(Live)”, „ft. …”, „- Topic”, „| kanał” i nadmiarowej interpunkcji. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("(?i)[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|teledysk|clip|hd|4k|remaster|visuali[sz]er|mv|live|na żywo|explicit)[^\\)\\]]*[\\)\\]]"), " ")
        .replace(Regex("(?i)\\b(ft|feat)\\.?\\s.*$"), " ")
        .replace(Regex("\\s*[|｜].*$"), " ")
        .replace(Regex("[\"“”„*#~_]+"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.', ',')

    /** Tytuł bez dopisków o remiksach / wersjach specjalnych („(Radio Edit)”, „- Remastered 2011”) – zapytanie zapasowe. */
    fun baseTitle(t: String): String = t
        .replace(Regex("(?i)[\\(\\[][^\\)\\]]*(remix|mix|edit|version|wersja|acoustic|akustycz|cover|radio|extended|remaster|deluxe|session)[^\\)\\]]*[\\)\\]]"), " ")
        .replace(Regex("(?i)\\s[-–—]\\s.*(remix|mix|edit|version|wersja|acoustic|remaster|live|radio|extended).*$"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—')

    /** Wykonawca i tytuł z nazwy kanału i tytułu filmu („Artysta – Utwór” w tytule ma pierwszeństwo przed nazwą kanału). */
    fun artistAndTrack(title: String, channel: String): Pair<String, String> {
        val c = channel.trim().removeSuffix("- Topic").removeSuffix("VEVO").trim()
        val parts = title.split(Regex("\\s[-–—]\\s"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) cleanTitle(parts[0]) to cleanTitle(parts[1])
        else c to cleanTitle(title)
    }

    /** Tekst albo null. Pamięć → Room (znaleziony bez TTL, brak – [MISSING_TTL_MS]) → źródła. Wywołanie w tle (Dispatchers.IO). */
    suspend fun find(title: String, channel: String, durationSec: Long = 0): Result? {
        if (!Prefs.ytLyrics) return null
        val (artist, track) = artistAndTrack(title, channel)
        if (track.isBlank()) return null
        val key = "$artist|$track".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val text = withContext(Dispatchers.IO) {
            val dao = runCatching { StubeHistory.db()?.lyrics() }.getOrNull()
            val now = System.currentTimeMillis()
            val saved = runCatching { dao?.get(key) }.getOrNull()
            if (saved != null) {
                if (saved.missing && now - saved.savedAt < MISSING_TTL_MS) return@withContext null
                if (!saved.missing) fromEntry(saved)?.let { return@withContext it }
            }
            val net = runCatching { fetch(artist, track, durationSec) }.getOrNull()
            runCatching {
                dao?.put(LyricsCacheEntry(key, net?.plain.orEmpty(), net?.synced?.joinToString("\n") { stamp(it) }.orEmpty(), net == null, now))
                dao?.prune(now - FOUND_TTL_MS)
            }
            net
        }
        synchronized(cache) { cache[key] = text }
        return text
    }

    private fun stamp(l: Line) = "[%d:%02d.%02d]%s".format(l.ms / 60000, (l.ms % 60000) / 1000, (l.ms % 1000) / 10, l.text)

    private fun fromEntry(e: LyricsCacheEntry): Result? {
        if (e.plain.isBlank() && e.synced.isBlank()) return null
        val synced = parseSynced(e.synced)
        return Result(e.plain.ifBlank { synced.joinToString("\n") { it.text } }, synced)
    }

    private val fast by lazy { Repository.http.newBuilder().callTimeout(CALL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build() }

    private fun get(url: okhttp3.HttpUrl): String? = runCatching {
        fast.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() }
    }.getOrNull()

    /** Kaskada: LRCLIB dokładne → wyszukiwanie (tytuł oczyszczony, potem bez dopisków o remiksie) → zapytanie ogólne → zwykły tekst z zapasowego źródła. */
    private fun fetch(artist: String, track: String, durationSec: Long): Result? {
        val titles = listOf(track, baseTitle(track)).filter { it.isNotBlank() }.distinct()
        var best: Result? = null
        for (t in titles) {
            val exact = "$apiBase/get".toHttpUrl().newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", t)
                .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }.build()
            val first = get(exact)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            if (first?.isSynced == true) return first
            if (best == null) best = first
            val search = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("track_name", t)
                .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build()
            val found = searchList(search)
            found.firstOrNull { it.isSynced }?.let { return it }
            if (best == null) best = found.firstOrNull()
        }
        if (best == null) {   // zapytanie ogólne „wykonawca tytuł” (np. odwrócona kolejność w tytule)
            val q = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("q", "$artist ${titles.last()}".trim()).build()
            val found = searchList(q)
            found.firstOrNull { it.isSynced }?.let { return it }
            best = found.firstOrNull()
        }
        if (best != null) return best
        if (fallbackBase.isNotBlank() && artist.isNotBlank()) {
            for (t in titles) {
                val url = runCatching { "$fallbackBase/".toHttpUrl().newBuilder().addPathSegment(artist).addPathSegment(t).build() }.getOrNull() ?: continue
                val plain = get(url)?.let { runCatching { JSONObject(it).optString("lyrics").trim() }.getOrDefault("") }.orEmpty()
                if (plain.length > 20) return Result(plain, emptyList())
            }
        }
        return null
    }

    private fun searchList(url: okhttp3.HttpUrl): List<Result> {
        val arr = runCatching { get(url)?.let { JSONArray(it) } }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { parse(it) }.getOrNull() } }
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

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
    class Result(val plain: String, val synced: List<Line>, val duration: Double = 0.0) {
        val isSynced get() = synced.size >= 2
    }

    private val cache = object : LinkedHashMap<String, Result?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Result?>) = size > CACHE_MAX
    }

    /** Czas, po którym ponawiane jest zapytanie o utwór bez tekstu. */
    const val MISSING_TTL_MS = 20 * 60_000L
    const val FOUND_TTL_MS = 90L * 24 * 3600_000L
    const val CALL_TIMEOUT_S = 8L

    /** (7.16) Błąd sieci / serwera w bieżącym wyszukiwaniu – brak wyniku wtedy nie jest zapamiętywany (znana piosenka nie „znika” na czas awarii). */
    private val netError = ThreadLocal<Boolean>()

    private fun delays(): Map<String, Long> = Prefs.ytLyricDelays.split(',').mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: return@mapNotNull null) } }.toMap()

    /** Ręczne przesunięcie synchronizacji tekstu dla filmu (ms; dodatnie – tekst opóźniony). */
    fun delayFor(videoId: String?): Long = videoId?.let { delays()[it] } ?: 0L

    fun saveDelay(videoId: String?, ms: Long) {
        videoId ?: return
        val m = LinkedHashMap(delays()); m.remove(videoId)
        if (ms != 0L) m[videoId] = ms
        while (m.size > 200) m.remove(m.keys.first())
        Prefs.ytLyricDelays = m.entries.joinToString(",") { "${it.key}=${it.value}" }
    }
    /** Zapasowe źródło zwykłego tekstu (bez znaczników czasu); pusty – wyłączone (testy). */
    var fallbackBase = "https://api.lyrics.ovh/v1"

    /** Tytuł bez dopisków typu „(Official Video)”, „[Lyrics]”, „(Live)”, „ft. …”, „- Topic”, „| kanał” i nadmiarowej interpunkcji. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("(?i)[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|teledysk|clip|hd|4k|remaster|visuali[sz]er|mv|live|na żywo|explicit)[^\\)\\]]*[\\)\\]]"), " ")
        .replace(Regex("(?i)\\b(ft|feat)\\.?\\s.*$"), " ")
        .replace(Regex("(?i)\\b(official\\s+(music\\s+)?(video|audio|lyric\\s+video|visuali[sz]er)|lyric\\s+video|lyrics?\\s+video|oficjalny\\s+teledysk|teledysk\\s+oficjalny|official)\\b"), " ")
        .replace(Regex("^\\s*\\d{1,2}[.)]\\s+"), "")
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
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) return cleanTitle(parts[0]) to cleanTitle(parts[1])
        // „Artysta „Utwór” (Official Video)” – tytuł w cudzysłowie
        Regex("^(.+?)\\s*[\"„“](.+?)[\"”“]").find(title)?.let { m ->
            val a = cleanTitle(m.groupValues[1]); val t = cleanTitle(m.groupValues[2])
            if (a.isNotBlank() && t.isNotBlank()) return a to t
        }
        return c to cleanTitle(title)
    }

    /** Tekst albo null. Pamięć → Room (znaleziony bez TTL, brak – [MISSING_TTL_MS]) → źródła. Wywołanie w tle (Dispatchers.IO). */
    suspend fun find(title: String, channel: String, durationSec: Long = 0): Result? {
        if (!Prefs.ytLyrics) return null
        val (artist, track) = artistAndTrack(title, channel)
        if (track.isBlank()) return null
        val key = "$artist|$track".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        var transient = false
        val text = withContext(Dispatchers.IO) {
            val dao = runCatching { StubeHistory.db()?.lyrics() }.getOrNull()
            val now = System.currentTimeMillis()
            val saved = runCatching { dao?.get(key) }.getOrNull()
            if (saved != null) {
                if (saved.missing && now - saved.savedAt < MISSING_TTL_MS) return@withContext null
                if (!saved.missing) fromEntry(saved)?.let { return@withContext it }
            }
            netError.set(false)
            val net = runCatching { fetch(artist, track, durationSec, channel, cleanTitle(title)) }.getOrNull()
            if (net == null && netError.get() == true) { transient = true; return@withContext null }   // awaria sieci – bez zapamiętywania braku
            runCatching {
                dao?.put(LyricsCacheEntry(key, net?.plain.orEmpty(), net?.synced?.joinToString("\n") { stamp(it) }.orEmpty(), net == null, now))
                dao?.prune(now - FOUND_TTL_MS)
            }
            net
        }
        if (!transient) synchronized(cache) { cache[key] = text }
        return text
    }

    private fun stamp(l: Line) = "[%d:%02d.%02d]%s".format(l.ms / 60000, (l.ms % 60000) / 1000, (l.ms % 1000) / 10, l.text)

    private fun fromEntry(e: LyricsCacheEntry): Result? {
        if (e.plain.isBlank() && e.synced.isBlank()) return null
        val synced = parseSynced(e.synced)
        return Result(e.plain.ifBlank { synced.joinToString("\n") { it.text } }, synced)
    }

    private val fast by lazy { Repository.http.newBuilder().callTimeout(CALL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build() }

    private fun get(url: okhttp3.HttpUrl): String? = try {
        fast.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r ->
            if (!r.isSuccessful) { if (r.code != 404) netError.set(true); null } else r.body?.string()
        }
    } catch (e: Exception) { netError.set(true); null }

    /** Kaskada: LRCLIB dokładne → wyszukiwanie (tytuł oczyszczony, potem bez dopisków o remiksie) → zapytanie ogólne → zwykły tekst z zapasowego źródła. */
    private fun fetch(artist: String, track: String, durationSec: Long, channel: String = "", fullTitle: String = ""): Result? {
        val pairs = candidatePairs(artist, track, channel)
        var best: Result? = null
        for ((i, p) in pairs.withIndex()) {
            val r = if (i == 0) fetchLrclib(p.first, p.second, durationSec) else searchOnly(p.first, p.second, durationSec)
            if (r?.isSynced == true) return r
            if (best == null) best = r
        }
        // (7.16) wyszukiwanie ogólne po całym tytule filmu i po „wykonawca tytuł” – gdy nazwa kanału / format tytułu nie pasują do bazy
        for (s in listOf(fullTitle, "$artist $track", baseTitle(fullTitle)).map { it.trim() }.filter { it.length > 2 }.distinct().take(2)) {
            val u = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("q", s).build()
            val r = pick(searchList(u), durationSec)
            if (r?.isSynced == true) return r
            if (best == null) best = r
        }
        if (fallbackBase.isNotBlank()) {
            // dodatkowe źródło z tekstem zsynchronizowanym (gdy LRCLIB ma najwyżej zwykły tekst albo nic)
            for (p in pairs.take(2)) netease(p.first, p.second)?.let { return it }
        }
        if (best != null) return best
        return plainFallback(pairs)
    }

    /**
     * Warianty wykonawca / tytuł do wyszukania (maks. 4): z tytułu filmu, odwrócone („Tytuł – Wykonawca”), z nazwy kanału,
     * pierwszy z kilku wykonawców („A & B”, „A, B”, „A x B”).
     */
    fun candidatePairs(artist: String, track: String, channel: String = ""): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        out += artist to track
        val first = artist.split(Regex("(?i)\\s(&|x|i|and|feat\\.?|ft\\.?)\\s|,\\s*")).firstOrNull()?.trim().orEmpty()
        if (first.isNotBlank() && first != artist) out += first to track
        val ch = channel.trim().removeSuffix("- Topic").removeSuffix("VEVO").removeSuffix("Official").trim()
        if (ch.isNotBlank() && !ch.equals(artist, true) && ch.length < 40) out += ch to track
        if (artist.isNotBlank() && track.isNotBlank()) out += track to artist
        return out.take(4).toList()
    }

    private fun searchOnly(artist: String, track: String, durationSec: Long = 0): Result? {
        val u = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("track_name", track)
            .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build()
        return pick(searchList(u), durationSec)
    }

    /** Z wyników: zsynchronizowany o czasie najbliższym długości filmu (±10 s), inaczej pierwszy zsynchronizowany, inaczej pierwszy. */
    private fun pick(found: List<Result>, durationSec: Long): Result? {
        val synced = found.filter { it.isSynced }
        if (synced.isEmpty()) return found.firstOrNull()
        if (durationSec > 0) synced.filter { it.duration > 0 }.minByOrNull { Math.abs(it.duration - durationSec) }
            ?.takeIf { Math.abs(it.duration - durationSec) <= 10 }?.let { return it }
        return synced.first()
    }

    /** NetEase Music (nieoficjalne API bez klucza): wyszukanie utworu i tekst z znacznikami czasu. Wyłączone razem z [fallbackBase]. */
    var neteaseBase = "https://music.163.com"
    private fun netease(artist: String, track: String): Result? = runCatching {
        if (neteaseBase.isBlank()) return null
        val q = "$artist $track".trim()
        val s = "$neteaseBase/api/search/get/web".toHttpUrl().newBuilder().addQueryParameter("s", q).addQueryParameter("type", "1")
            .addQueryParameter("offset", "0").addQueryParameter("limit", "6").build()
        val songs = JSONObject(neteaseGet(s) ?: return null).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val want = track.lowercase()
        var id = 0L
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val n = o.optString("name").lowercase()
            val artists = o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(" ") { a.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty().lowercase()
            if ((n.contains(want) || want.contains(n)) && (artist.isBlank() || artists.contains(artist.lowercase().take(12)) || artist.lowercase().contains(artists.take(12)))) { id = o.optLong("id"); break }
        }
        if (id == 0L) return null
        val l = "$neteaseBase/api/song/lyric".toHttpUrl().newBuilder().addQueryParameter("id", id.toString()).addQueryParameter("lv", "1").build()
        val lrc = JSONObject(neteaseGet(l) ?: return null).optJSONObject("lrc")?.optString("lyric").orEmpty()
        val lines = parseSynced(lrc).filter { it.text.isNotBlank() && !Regex("^\\S{1,6}\\s?[:：]").containsMatchIn(it.text) }   // bez wierszy „作词 : …”
        if (lines.size < 4) null else Result(lines.joinToString("\n") { it.text }, lines)
    }.getOrNull()

    private fun neteaseGet(url: okhttp3.HttpUrl): String? = runCatching {
        fast.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.163.com/").build()).execute()
            .use { r -> if (!r.isSuccessful) null else r.body?.string() }
    }.getOrNull()

    /** Zwykły tekst: lyrics.ovh dla każdego wariantu, a potem jego wyszukiwarka (poprawna pisownia wykonawcy / tytułu). */
    private fun plainFallback(pairs: List<Pair<String, String>>): Result? {
        if (fallbackBase.isBlank()) return null
        fun direct(a: String, t: String): Result? {
            if (a.isBlank() || t.isBlank()) return null
            val url = runCatching { "$fallbackBase/".toHttpUrl().newBuilder().addPathSegment(a).addPathSegment(t).build() }.getOrNull() ?: return null
            val plain = get(url)?.let { runCatching { JSONObject(it).optString("lyrics").trim() }.getOrDefault("") }.orEmpty()
            return if (plain.length > 20) Result(plain, emptyList()) else null
        }
        for ((a, t) in pairs.take(3)) for (tt in listOf(t, baseTitle(t)).distinct()) direct(a, tt)?.let { return it }
        val (a0, t0) = pairs.firstOrNull() ?: return null
        val sug = runCatching { "${fallbackBase.removeSuffix("/v1").removeSuffix("/")}/suggest/".toHttpUrl().newBuilder().addPathSegment("$a0 $t0".trim()).build() }.getOrNull() ?: return null
        val arr = get(sug)?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() } ?: return null
        for (i in 0 until minOf(arr.length(), 3)) {
            val o = arr.optJSONObject(i) ?: continue
            direct(o.optJSONObject("artist")?.optString("name").orEmpty(), o.optString("title"))?.let { return it }
        }
        return null
    }

    private fun fetchLrclib(artist: String, track: String, durationSec: Long): Result? {
        val titles = listOf(track, baseTitle(track)).filter { it.isNotBlank() }.distinct()
        var best: Result? = null
        for (t in titles) {
            val exact = "$apiBase/get".toHttpUrl().newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", t)
                .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }.build()
            var first = get(exact)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            if (first == null && durationSec > 0) {   // wersja teledyskowa bywa dłuższa od albumowej – dokładne dopasowanie bez czasu
                val noDur = "$apiBase/get".toHttpUrl().newBuilder().addQueryParameter("artist_name", artist).addQueryParameter("track_name", t).build()
                first = get(noDur)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            }
            if (first?.isSynced == true) return first
            if (best == null) best = first
            val search = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("track_name", t)
                .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build()
            val found = pick(searchList(search), durationSec)
            if (found?.isSynced == true) return found
            if (best == null) best = found
        }
        if (best == null) {   // zapytanie ogólne „wykonawca tytuł” (np. odwrócona kolejność w tytule)
            val q = "$apiBase/search".toHttpUrl().newBuilder().addQueryParameter("q", "$artist ${titles.last()}".trim()).build()
            val found = pick(searchList(q), durationSec)
            if (found?.isSynced == true) return found
            best = found
        }
        return best
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
        return Result(plain, synced, o.optDouble("duration", 0.0))
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

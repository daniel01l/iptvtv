package pl.iptv.tv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * (7.14) Playlista przesłana do STube (link YouTube / Spotify albo lista „Wykonawca – Tytuł”) zapisana na stałe w Room
 * (utwory jako JSON – [items]). Źródło wskazuje, skąd pochodzi ([kind]: yt / spotify / text); [skipped] – pozycje bez dopasowania.
 */
@Entity(tableName = "imported_playlist")
data class ImportedPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val kind: String,
    val createdAt: Long,
    val items: String,
    val skipped: Int = 0,
) {
    fun videos(): List<YtVideo> = StubeImport.decode(items)
}

@Dao
interface ImportedPlaylistDao {
    @Query("SELECT * FROM imported_playlist ORDER BY createdAt DESC") suspend fun all(): List<ImportedPlaylist>
    @Query("SELECT * FROM imported_playlist WHERE id = :id") suspend fun get(id: Long): ImportedPlaylist?
    @Insert suspend fun insert(p: ImportedPlaylist): Long
    @Query("DELETE FROM imported_playlist WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM imported_playlist") suspend fun clear()
}

/**
 * Przesyłanie linków do playlist do STube – ten sam kanał co listy M3U ([pl.iptv.tv.remote.RemoteServer]: strona `/upload`,
 * `/api/playlist`, przekaźnik internetowy): link, który nie jest listą M3U, trafia tu ([classify] / [offer]) zamiast do listy kanałów.
 *
 * Przepływ: [resolve] (w tle: pobranie pozycji, dopasowanie do filmów YouTube) → podgląd ([Preview]) → [save] (Room) + start kolejki.
 * Wszystko w `Dispatchers.IO`, anulowanie korutyny (zamknięcie okna) kończy pobieranie.
 */
object StubeImport {
    const val KIND_YT = "yt"; const val KIND_SPOTIFY = "spotify"; const val KIND_TEXT = "text"
    /** Maks. liczba utworów jednej playlisty (limit kolejki STube). */
    const val MAX_ITEMS = StubeQueue.MAX
    /** Ile zapytań dopasowujących naraz. */
    const val PARALLEL = 6
    /** Ile utworów z jednej playlisty wchodzi do sygnału gustu (miksy, propozycje). */
    const val TASTE_ITEMS = 60

    /** Adresy źródeł (testy podmieniają). */
    @Volatile var spotifyEmbedBase = "https://open.spotify.com/embed"

    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }

    // ---------- rozpoznanie linku ----------

    class Link(val kind: String, val id: String, val url: String)

    private val URL = Regex("""https?://[^\s<>"']+""")
    private val YT_LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
    private val SPOTIFY = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(playlist|album|track)/([A-Za-z0-9]{10,})""")

    /** Pierwszy adres z wklejonego tekstu (udostępnianie z aplikacji dodaje opis przed linkiem). */
    fun urlIn(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')

    /** Rodzaj linku do playlisty muzycznej; null – nie jest takim linkiem (np. lista M3U albo zwykła strona). */
    fun classify(text: String): Link? {
        val url = urlIn(text) ?: return null
        SPOTIFY.find(url)?.let { return Link(KIND_SPOTIFY, it.groupValues[1] + "/" + it.groupValues[2], url) }
        if (Regex("""(youtube\.com|youtu\.be|music\.youtube\.com)""").containsMatchIn(url)) {
            YT_LIST.find(url)?.let { return Link(KIND_YT, it.groupValues[1], url) }
        }
        return null
    }

    /** Czy tekst to lista tytułów (kilka linii „Wykonawca – Tytuł”) – bez żadnego adresu. */
    fun looksLikeTrackList(text: String): Boolean =
        urlIn(text) == null && text.lines().count { it.trim().length >= 3 } >= 2

    // ---------- odbiór z zewnątrz ----------

    /** Link / tekst odebrany przez serwer (telefon, strona WWW) i jeszcze nieopracowany – okno „＋” pokazuje go od razu. */
    @Volatile var pending: String? = null; private set
    @Volatile var onPending: (() -> Unit)? = null

    /** Zapamiętuje odebrany link (wywoływane z wątku serwera); okno / ekran STube reaguje na wątku głównym. */
    fun offer(text: String) {
        pending = text.trim()
        android.os.Handler(android.os.Looper.getMainLooper()).post { onPending?.invoke() }
    }
    fun takePending(): String? = pending.also { pending = null }

    // ---------- pobranie i dopasowanie ----------

    /** Wynik podglądu: dopasowane utwory ([videos]), pominięte pozycje ([missed] – opisy) albo [error] (brak dostępu / pusta lista). */
    class Preview(val name: String, val source: String, val kind: String, val videos: List<YtVideo>, val missed: List<String>, val error: String? = null) {
        val ok get() = error == null && videos.isNotEmpty()
    }

    /** Pozycja źródła przed dopasowaniem. */
    data class Track(val artist: String, val title: String, val durationSec: Int = 0)

    /**
     * Link / lista tytułów → [Preview]. Playlista YouTube: utwory wprost; Spotify i tekst: każda pozycja dopasowana wyszukiwaniem YouTube
     * (partiami po [PARALLEL]); [onProgress] – postęp dopasowania (dla paska w oknie).
     */
    suspend fun resolve(input: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Preview = withContext(Dispatchers.IO) {
        val text = input.trim()
        val link = classify(text)
        try {
            when {
                link?.kind == KIND_YT -> {
                    val (name, vids) = YtKeyless.playlist(link.id)
                    val clean = vids.filter { it.duration != "P0D" }.distinctBy { it.id }.take(MAX_ITEMS)
                    if (clean.isEmpty()) Preview(name, link.url, KIND_YT, emptyList(), emptyList(), "Playlista jest pusta albo prywatna (YouTube nie udostępnia jej utworów)")
                    else Preview(name.ifBlank { "Playlista YouTube" }, link.url, KIND_YT, clean, emptyList())
                }
                link?.kind == KIND_SPOTIFY -> {
                    val (name, tracks) = spotifyTracks(link)
                    if (tracks.isEmpty()) Preview(name, link.url, KIND_SPOTIFY, emptyList(), emptyList(), "Nie udało się odczytać listy ze Spotify (playlista prywatna albo brak dostępu)")
                    else match(name.ifBlank { "Playlista Spotify" }, link.url, KIND_SPOTIFY, tracks.take(MAX_ITEMS), onProgress)
                }
                link == null && urlIn(text) != null -> Preview("", text, "", emptyList(), emptyList(), "To nie jest link do playlisty YouTube ani Spotify")
                looksLikeTrackList(text) -> match("Lista z telefonu", "", KIND_TEXT, parseTextList(text).take(MAX_ITEMS), onProgress)
                else -> Preview("", text, "", emptyList(), emptyList(), "Wklej link do playlisty (YouTube lub Spotify) albo listę „Wykonawca – Tytuł”")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Preview("", text, link?.kind.orEmpty(), emptyList(), emptyList(), "Brak dostępu do listy: ${e.message ?: "błąd sieci"}")
        }
    }

    /** Linie „Wykonawca – Tytuł” (myślnik, półpauza, „ - ”) albo sam tytuł. */
    fun parseTextList(text: String): List<Track> = text.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.length >= 3 }.map { l ->
        val i = Regex("""\s[-–—]\s""").find(l)
        if (i != null) Track(l.substring(0, i.range.first).trim(), l.substring(i.range.last + 1).trim()) else Track("", l)
    }

    private suspend fun match(name: String, source: String, kind: String, tracks: List<Track>, onProgress: (Int, Int) -> Unit): Preview = coroutineScope {
        val gate = Semaphore(PARALLEL); var done = 0
        val found = tracks.map { t ->
            async {
                gate.withPermit {
                    val v = runCatching { pick(t, YtKeyless.search("${t.artist} ${t.title}".trim())) }.getOrNull()
                    synchronized(this@StubeImport) { done++; onProgress(done, tracks.size) }
                    v
                }
            }
        }.map { it.await() }
        val vids = ArrayList<YtVideo>(); val missed = ArrayList<String>(); val seen = HashSet<String>()
        tracks.forEachIndexed { i, t -> val v = found[i]
            if (v != null && seen.add(v.id)) vids += v else if (v == null) missed += listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" – ") }
        Preview(name, source, kind, vids, missed, if (vids.isEmpty()) "Nie znaleziono żadnego utworu w STube" else null)
    }

    /**
     * Najlepszy film dla utworu: nie składanka / vlog, tytuł zawiera słowa utworu (a wykonawca – w tytule lub kanale), czas zbliżony
     * do oryginału (±40 s, o ile znany). Brak pewnego wyniku → null (pozycja pominięta, zamiast losowego filmu). Czysta funkcja – testy.
     */
    fun pick(t: Track, candidates: List<YtVideo>): YtVideo? {
        val words = StubeMixes.tokens(t.title).distinct()
        val artist = StubeMixes.tokens(t.artist).distinct()
        var best: YtVideo? = null; var bestScore = 0.0
        candidates.take(8).forEach { v ->
            if (v.duration == "P0D" || StubeMixes.isNonTrack(v)) return@forEach
            val hay = (v.title + " " + v.channel).lowercase()
            val titleHit = if (words.isEmpty()) 0.0 else words.count { hay.contains(it) }.toDouble() / words.size
            if (titleHit < 0.5) return@forEach
            val artistHit = if (artist.isEmpty()) 0.5 else artist.count { hay.contains(it) }.toDouble() / artist.size
            if (artist.isNotEmpty() && artistHit == 0.0) return@forEach
            val dur = StubeMixes.durationSec(v.duration)
            val durOk = t.durationSec <= 0 || dur <= 0 || kotlin.math.abs(dur - t.durationSec) <= 40
            if (!durOk) return@forEach
            val s = titleHit * 2 + artistHit + (if (StubeMixes.isMusicVideo(v.title, v.channel)) 0.5 else 0.0)
            if (s > bestScore) { bestScore = s; best = v }
        }
        return best
    }

    // ---------- Spotify (strona osadzana – bez konta i klucza) ----------

    private fun spotifyTracks(link: Link): Pair<String, List<Track>> {
        val req = okhttp3.Request.Builder().url("$spotifyEmbedBase/${link.id}").header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36").build()
        val html = http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("Spotify odpowiedział ${r.code}"); r.body?.string().orEmpty() }
        return parseSpotify(html)
    }

    /** Nazwa i utwory z JSON-u strony osadzanej Spotify (`__NEXT_DATA__`); odporne na zmiany układu (szuka `trackList`). Czysta funkcja. */
    fun parseSpotify(html: String): Pair<String, List<Track>> {
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return "" to emptyList()
        val tl = findKey(json, "trackList")
        val list = tl?.second as? JSONArray
        val name = tl?.first?.optString("name")?.ifBlank { null } ?: tl?.first?.optString("title").orEmpty()   // rodzaj trackList = obiekt „entity”
        val tracks = ArrayList<Track>()
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isBlank()) continue
            tracks += Track(o.optString("subtitle").ifBlank { o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty() }, title,
                (o.optLong("duration") / 1000).toInt().takeIf { it in 1..3600 } ?: 0)
        }
        return name to tracks
    }

    /** Obiekt (rodzic) i wartość pierwszego klucza [key] w drzewie JSON. */
    private fun findKey(n: Any?, key: String): Pair<JSONObject, Any?>? {
        when (n) {
            is JSONObject -> {
                if (n.has(key)) return n to n.get(key)
                for (k in n.keys()) findKey(n.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until n.length()) findKey(n.opt(i), key)?.let { return it }
        }
        return null
    }

    // ---------- zapis (Room) ----------

    @Volatile var cached: List<ImportedPlaylist> = emptyList(); private set
    fun reset() { cached = emptyList(); pending = null }
    @Volatile var version = 0; private set

    private fun dao() = StubeHistory.db()?.imported()

    fun encode(v: List<YtVideo>): String = JSONArray(v.map { JSONObject().put("i", it.id).put("t", it.title).put("c", it.channel).put("p", it.thumb).put("d", it.duration) }).toString()
    fun decode(s: String): List<YtVideo> = runCatching {
        val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it).let { o -> YtVideo(o.getString("i"), o.optString("t"), o.optString("c"), o.optString("p"), duration = o.optString("d")) } }
    }.getOrDefault(emptyList())

    suspend fun loadCache() {
        val d = dao() ?: return
        cached = withContext(Dispatchers.IO) { runCatching { d.all() }.getOrDefault(emptyList()) }
    }

    suspend fun all(): List<ImportedPlaylist> { loadCache(); return cached }

    /** Zapisuje podgląd na stałe; ta sama playlista (źródło) nie powstaje drugi raz – aktualizuje się. Zwraca zapisaną. */
    suspend fun save(p: Preview, now: Long = System.currentTimeMillis()): ImportedPlaylist? {
        val d = dao() ?: return null
        val saved = withContext(Dispatchers.IO) {
            val old = if (p.source.isNotBlank()) d.all().firstOrNull { it.source == p.source } else null
            old?.let { d.delete(it.id) }
            val e = ImportedPlaylist(name = p.name, source = p.source, kind = p.kind, createdAt = now, items = encode(p.videos), skipped = p.missed.size)
            e.copy(id = d.insert(e))
        }
        version++; loadCache(); return saved
    }

    suspend fun remove(id: Long) { dao()?.let { d -> withContext(Dispatchers.IO) { d.delete(id) } }; version++; loadCache() }

    // ---------- sygnał gustu ----------

    /**
     * Utwory przesłanych playlist jako „wysłuchane do końca” (ostatnie [TASTE_ITEMS] z każdej) – mocny sygnał preferencji:
     * wchodzą do profilu gustu ([StubeTaste]) i do generowania „Moich miksów” ([StubeMixes.build]). Z pamięci, bez dostępu do bazy.
     */
    fun asHistory(): List<WatchProgress> = cached.flatMap { p ->
        p.videos().take(TASTE_ITEMS).map { v ->
            val dur = (StubeMixes.durationSec(v.duration).takeIf { it > 0 } ?: 200) * 1000L
            WatchProgress(v.id, v.title, v.channel, v.thumb, dur, dur, p.createdAt)
        }
    }

    /** Identyfikatory utworów z przesłanych playlist (nie liczą się jako „ostatnio oglądane” w miksach). */
    fun importedIds(): Set<String> = cached.flatMapTo(HashSet()) { p -> p.videos().take(TASTE_ITEMS).map { it.id } }
}

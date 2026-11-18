package pl.iptv.tv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ================= encje =================

/** Fraza wyszukiwania STube. [key] – postać znormalizowana (małe litery, pojedyncze spacje): bez duplikatów „Kot”/„kot ”. */
@Entity(tableName = "search_history", indices = [Index("usedAt")])
data class SearchEntry(
    @PrimaryKey val key: String,
    val query: String,
    val usedAt: Long,
    val uses: Int = 1,
)

/** Postęp oglądania filmu YouTube (pozycja i długość w ms) z danymi kafelka („Kontynuuj oglądanie”). */
@Entity(tableName = "watch_progress", indices = [Index("updatedAt")])
data class WatchProgress(
    @PrimaryKey val videoId: String,
    val title: String = "",
    val channel: String = "",
    val thumb: String = "",
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    /** Część obejrzana 0..1 (pasek na kafelku). */
    val fraction get() = StubeHistory.fraction(positionMs, durationMs)
    val finished get() = StubeHistory.isFinished(positionMs, durationMs)
    fun toVideo() = YtVideo(videoId, title, channel, thumb, duration = StubeHistory.isoDuration(durationMs))
}

// ================= DAO =================

@Dao
interface SearchDao {
    /** Frazy zaczynające się od [prefix] lub zawierające słowo od niego (znaki % _ \ w [prefix] już zabezpieczone). */
    @Query("SELECT * FROM search_history WHERE `key` LIKE :prefix || '%' ESCAPE '\\' OR `key` LIKE '% ' || :prefix || '%' ESCAPE '\\' " +
        "ORDER BY (`key` LIKE :prefix || '%' ESCAPE '\\') DESC, usedAt DESC LIMIT :limit")
    suspend fun matching(prefix: String, limit: Int): List<SearchEntry>

    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchEntry>

    @Query("SELECT * FROM search_history WHERE `key` = :key")
    suspend fun get(key: String): SearchEntry?

    @Upsert suspend fun upsert(e: SearchEntry)

    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun delete(key: String)

    /** Zostawia [keep] najnowszych fraz. */
    @Query("DELETE FROM search_history WHERE `key` NOT IN (SELECT `key` FROM search_history ORDER BY usedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM search_history") suspend fun clear()
    @Query("SELECT COUNT(*) FROM search_history") suspend fun count(): Int
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM watch_progress WHERE videoId = :id")
    suspend fun get(id: String): WatchProgress?

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchProgress>

    /** Rozpoczęte i nieukończone (rząd „Kontynuuj oglądanie”); próg ukończenia jak w [StubeHistory.isFinished]. */
    @Query("SELECT * FROM watch_progress WHERE positionMs > 0 AND durationMs > 0 AND positionMs < durationMs * :doneRatio " +
        "AND durationMs - positionMs > :doneTailMs ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun unfinished(limit: Int, doneRatio: Double, doneTailMs: Long): List<WatchProgress>

    @Upsert suspend fun upsert(p: WatchProgress)

    @Query("DELETE FROM watch_progress WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM watch_progress WHERE videoId NOT IN (SELECT videoId FROM watch_progress ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM watch_progress") suspend fun clear()
    @Query("SELECT COUNT(*) FROM watch_progress") suspend fun count(): Int
}

/** Film na lokalnej liście „Do obejrzenia później” (YouTube Data API nie udostępnia listy WL konta). */
@Entity(tableName = "watch_later", indices = [Index("addedAt")])
data class WatchLater(
    @PrimaryKey val videoId: String,
    val title: String = "",
    val channel: String = "",
    val thumb: String = "",
    val duration: String = "",
    val addedAt: Long,
) {
    fun toVideo() = YtVideo(videoId, title, channel, thumb, duration = duration)
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC LIMIT :limit")
    suspend fun all(limit: Int): List<WatchLater>

    @Query("SELECT videoId FROM watch_later")
    suspend fun ids(): List<String>

    @Upsert suspend fun upsert(w: WatchLater)

    @Query("DELETE FROM watch_later WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM watch_later") suspend fun clear()
    @Query("SELECT COUNT(*) FROM watch_later") suspend fun count(): Int
}

// v2: lista „Do obejrzenia później”, v3: kolejka odtwarzania, v4: kafelki pulpitu X25, v5: Stremio (wtyczki, postęp) – migracje automatyczne (schematy w app/schemas), dane zachowane
// v6: dobrane ustawienia silnika kanałów TV (ChannelEngineOptimizer)
@Database(entities = [SearchEntry::class, WatchProgress::class, WatchLater::class, QueueItem::class, DashTile::class, StremioAddonState::class, StremioProgress::class,
    ChannelEngine::class], version = 6, exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3), androidx.room.AutoMigration(from = 3, to = 4), androidx.room.AutoMigration(from = 4, to = 5),
        androidx.room.AutoMigration(from = 5, to = 6)])
abstract class StubeDatabase : RoomDatabase() {
    abstract fun channelEngines(): ChannelEngineDao
    abstract fun search(): SearchDao
    abstract fun progress(): ProgressDao
    abstract fun watchLater(): WatchLaterDao
    abstract fun queue(): QueueDao
    abstract fun dash(): DashTileDao
    abstract fun stremio(): StremioDao

    companion object {
        const val NAME = "stube.db"
        fun build(c: Context): StubeDatabase = Room.databaseBuilder(c.applicationContext, StubeDatabase::class.java, NAME)
            // dane pomocnicze (historia) – przy nieznanej wersji schematu baza zakładana od nowa zamiast awarii
            .fallbackToDestructiveMigration()
            .build()
    }
}

// ================= fasada =================

/**
 * Lokalna historia STube (Room): frazy wyszukiwania (podpowiedzi w polu szukania) i postęp oglądania (pasek na kafelkach,
 * wznawianie, rząd „Kontynuuj oglądanie”). Postęp jest też trzymany w pamięci – kafelki odczytują go synchronicznie
 * przy wiązaniu widoku ([fractionOf]), zapis do bazy w tle. Wyłączenie [Prefs.stubeHistory] wstrzymuje zapis.
 */
object StubeHistory {
    const val MAX_SEARCHES = 50
    const val MAX_PROGRESS = 500
    const val SUGGESTIONS = 8
    /** Krótsze oglądanie nie jest zapisywane (przypadkowe OK na kafelku). */
    const val MIN_SAVE_MS = 5_000L
    /** Film uznany za obejrzany: ≥ 95 % albo mniej niż 20 s do końca (napisy końcowe) – jak w YouTube. */
    const val DONE_RATIO = 0.95
    const val DONE_TAIL_MS = 20_000L
    /** Wznawianie od zapisanej pozycji dopiero po tylu ms (wcześniej – od początku). */
    const val MIN_RESUME_MS = 10_000L
    /** Pasek obejrzenia przy filmie ukończonym nie znika (jak w YouTube – pełny pasek). */
    const val FINISHED_FRACTION = 1f

    private var app: Context? = null
    @Volatile private var db: StubeDatabase? = null
    private val cache = ConcurrentHashMap<String, WatchProgress>()
    @Volatile var cacheLoaded = false; private set
    /** Zapisy w tle niezależne od ekranu (zamknięcie odtwarzacza nie przerywa zapisu). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(c: Context) { app = c.applicationContext }

    /** Testy: baza w pamięci (null = domyślna). */
    fun useDatabase(d: StubeDatabase?) { synchronized(this) { db = d; cache.clear(); laterIds.clear(); cacheLoaded = false } }

    internal fun db(): StubeDatabase? = db ?: synchronized(this) {
        db ?: app?.let { runCatching { StubeDatabase.build(it) }.onFailure { e -> android.util.Log.w("StubeHistory", "baza: ${e.message}") }.getOrNull() }
            ?.also { db = it }
    }

    private val enabled get() = runCatching { Prefs.stubeHistory }.getOrDefault(true)

    // ---------- czyste funkcje (testy) ----------

    fun normalize(q: String): String = q.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Zabezpieczenie znaków specjalnych LIKE (ESCAPE '\'). */
    fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun fraction(posMs: Long, durMs: Long): Float =
        if (durMs <= 0 || posMs <= 0) 0f else if (isFinished(posMs, durMs)) FINISHED_FRACTION else (posMs.toFloat() / durMs).coerceIn(0f, 1f)

    fun isFinished(posMs: Long, durMs: Long): Boolean = durMs > 0 && (posMs >= durMs * DONE_RATIO || durMs - posMs <= DONE_TAIL_MS)

    /** Pozycja wznowienia: 0 gdy za wcześnie albo film obejrzany do końca. */
    fun resumeFrom(p: WatchProgress?): Long = when {
        p == null || p.durationMs <= 0 || p.positionMs < MIN_RESUME_MS || p.finished -> 0L
        else -> p.positionMs
    }

    /** ms → ISO 8601 (PT1H2M3S) – format czasu kafelków ([pl.iptv.tv.ui.STubeScreen.duration]). */
    fun isoDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        if (s == 0L) return ""
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (sec > 0) "${sec}S" else "")
    }

    // ---------- wyszukiwanie ----------

    suspend fun addSearch(q: String, now: Long = System.currentTimeMillis()) {
        val key = normalize(q)
        if (key.isEmpty() || !enabled) return
        val d = db() ?: return
        withContext(Dispatchers.IO) {
            val old = d.search().get(key)
            d.search().upsert(SearchEntry(key, q.trim().replace(Regex("\\s+"), " "), now, (old?.uses ?: 0) + 1))
            d.search().prune(MAX_SEARCHES)
        }
    }

    /** Podpowiedzi: puste pole – ostatnie frazy; inaczej pasujące od początku frazy lub słowa (najpierw od początku). */
    suspend fun suggestions(prefix: String, limit: Int = SUGGESTIONS): List<String> {
        val d = db() ?: return emptyList()
        val p = normalize(prefix)
        return withContext(Dispatchers.IO) {
            runCatching { if (p.isEmpty()) d.search().recent(limit) else d.search().matching(escapeLike(p), limit) }.getOrDefault(emptyList())
        }.map { it.query }.filter { normalize(it) != p }
    }

    suspend fun removeSearch(q: String) { db()?.let { withContext(Dispatchers.IO) { it.search().delete(normalize(q)) } } }
    suspend fun clearSearches() { db()?.let { withContext(Dispatchers.IO) { it.search().clear() } } }
    suspend fun searchCount(): Int = db()?.let { withContext(Dispatchers.IO) { it.search().count() } } ?: 0

    // ---------- postęp oglądania ----------

    /** Wczytanie postępu do pamięci (start ekranu STube); kolejne wywołania tylko odświeżają. */
    suspend fun loadCache() {
        val d = db() ?: return
        val all = withContext(Dispatchers.IO) { runCatching { d.progress().recent(MAX_PROGRESS) }.getOrDefault(emptyList()) }
        cache.clear(); all.forEach { cache[it.videoId] = it }
        loadWatchLater()
        cacheLoaded = true
    }

    /** Część obejrzana filmu (z pamięci; null = nie oglądany) – wiązanie kafelka bez dostępu do bazy. */
    fun fractionOf(videoId: String): Float? = cache[videoId]?.fraction?.takeIf { it > 0f }

    fun cached(videoId: String): WatchProgress? = cache[videoId]

    /** Czy jest rozpoczęty, nieukończony film (rząd „Kontynuuj oglądanie”) – z pamięci, bez dostępu do bazy. */
    val hasUnfinished get() = enabled && cache.values.any { it.positionMs > 0 && !it.finished }

    /** Liczba filmów w historii (w pamięci) – czy budować miksy. */
    val historyCount get() = cache.size

    suspend fun progress(videoId: String): WatchProgress? =
        cache[videoId] ?: db()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.progress().get(videoId) }.getOrNull() } }?.also { cache[videoId] = it }

    /**
     * Zapis pozycji: pamięć od razu, baza w tle. Pomijane: transmisje (brak długości), oglądanie krótsze niż [MIN_SAVE_MS]
     * nowego filmu. Pozycja przy końcu = film obejrzany (pełny pasek, bez wznawiania).
     */
    fun savePosition(v: YtVideo, posMs: Long, durMs: Long, now: Long = System.currentTimeMillis()): WatchProgress? {
        if (!enabled || v.id.isBlank() || durMs <= 0) return null
        val old = cache[v.id]
        if (old == null && posMs < MIN_SAVE_MS) return null
        val pos = if (isFinished(posMs, durMs)) durMs else posMs.coerceIn(0, durMs)
        val p = WatchProgress(v.id, v.title.ifBlank { old?.title.orEmpty() }, v.channel.ifBlank { old?.channel.orEmpty() },
            v.thumb.ifBlank { old?.thumb.orEmpty() }, pos, durMs, now)
        cache[v.id] = p
        val d = db() ?: return p
        scope.launch { runCatching { d.progress().upsert(p); if (cache.size > MAX_PROGRESS) d.progress().prune(MAX_PROGRESS) } }
        if (cache.size > MAX_PROGRESS) cache.values.sortedBy { it.updatedAt }.take(cache.size - MAX_PROGRESS).forEach { cache.remove(it.videoId) }
        return p
    }

    /** Zapis synchroniczny (testy, koniec procesu). */
    suspend fun flush(p: WatchProgress) { db()?.let { withContext(Dispatchers.IO) { it.progress().upsert(p) } } }

    suspend fun unfinished(limit: Int = 20): List<WatchProgress> =
        db()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.progress().unfinished(limit, DONE_RATIO, DONE_TAIL_MS) }.getOrDefault(emptyList()) } } ?: emptyList()

    suspend fun removeProgress(id: String) { cache.remove(id); db()?.let { withContext(Dispatchers.IO) { it.progress().delete(id) } } }
    suspend fun clearProgress() { cache.clear(); db()?.let { withContext(Dispatchers.IO) { it.progress().clear() } } }
    suspend fun progressCount(): Int = db()?.let { withContext(Dispatchers.IO) { it.progress().count() } } ?: 0

    // ---------- Do obejrzenia później (Biblioteka) ----------

    const val MAX_WATCH_LATER = 200
    private val laterIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Czy film jest na liście (z pamięci – menu kafelka bez dostępu do bazy). */
    fun isWatchLater(id: String) = id in laterIds
    val watchLaterCount get() = laterIds.size

    suspend fun loadWatchLater() {
        val d = db() ?: return
        val ids = withContext(Dispatchers.IO) { runCatching { d.watchLater().ids() }.getOrDefault(emptyList()) }
        laterIds.clear(); laterIds += ids
    }

    suspend fun watchLater(limit: Int = MAX_WATCH_LATER): List<WatchLater> =
        db()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.watchLater().all(limit) }.getOrDefault(emptyList()) } } ?: emptyList()

    /** Dodanie (nowe na początku listy); lista dobrowolna – zapis niezależny od [Prefs.stubeHistory]. */
    suspend fun addWatchLater(v: YtVideo, now: Long = System.currentTimeMillis()): Boolean {
        if (v.id.isBlank()) return false
        val d = db() ?: return false
        withContext(Dispatchers.IO) {
            d.watchLater().upsert(WatchLater(v.id, v.title, v.channel, v.thumb, v.duration, now))
            if (d.watchLater().count() > MAX_WATCH_LATER) d.watchLater().all(MAX_WATCH_LATER + 50).drop(MAX_WATCH_LATER).forEach { d.watchLater().delete(it.videoId); laterIds.remove(it.videoId) }
        }
        laterIds += v.id
        return true
    }

    suspend fun removeWatchLater(id: String) { laterIds.remove(id); db()?.let { withContext(Dispatchers.IO) { it.watchLater().delete(id) } } }
    suspend fun clearWatchLater() { laterIds.clear(); db()?.let { withContext(Dispatchers.IO) { it.watchLater().clear() } } }

    /** Historia oglądania (Biblioteka): ostatnio oglądane, także ukończone. */
    suspend fun watched(limit: Int = 100): List<WatchProgress> =
        db()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.progress().recent(limit) }.getOrDefault(emptyList()) } } ?: emptyList()
}

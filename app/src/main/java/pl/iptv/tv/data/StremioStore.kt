package pl.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ================= Room (baza aplikacji – StubeDatabase v5) =================

/** Wtyczka Stremio zapisana lokalnie: manifest, włączona w wyszukiwaniu / źródłach, kolejność, dodana ręcznie. */
@Entity(tableName = "stremio_addons")
data class StremioAddonState(
    @PrimaryKey val transportUrl: String,
    val manifest: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val custom: Boolean = false,
)

/** Postęp oglądania (jedna pozycja na film / serial – jak biblioteka Stremio: [videoId] = ostatni odcinek). */
@Entity(tableName = "stremio_progress")
data class StremioProgress(
    @PrimaryKey val metaId: String,
    val type: String,
    val name: String,
    val poster: String = "",
    val videoId: String = "",
    val videoLabel: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    val createdAt: Long = 0,
    /** Zapisane już na koncie Stremio. */
    val synced: Boolean = false,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.92
}

@Dao
interface StremioDao {
    @Query("SELECT * FROM stremio_addons ORDER BY position") suspend fun addons(): List<StremioAddonState>
    @Upsert suspend fun upsertAddons(a: List<StremioAddonState>)
    @Query("DELETE FROM stremio_addons WHERE transportUrl = :url") suspend fun deleteAddon(url: String)
    @Query("UPDATE stremio_addons SET enabled = :on WHERE transportUrl = :url") suspend fun setEnabled(url: String, on: Boolean)
    @Query("DELETE FROM stremio_addons WHERE custom = 0") suspend fun clearAccountAddons()

    @Query("SELECT * FROM stremio_progress ORDER BY updatedAt DESC LIMIT :limit") suspend fun progress(limit: Int): List<StremioProgress>
    @Query("SELECT * FROM stremio_progress WHERE metaId = :id") suspend fun progressOf(id: String): StremioProgress?
    @Query("SELECT * FROM stremio_progress WHERE synced = 0") suspend fun unsynced(): List<StremioProgress>
    @Upsert suspend fun upsertProgress(p: List<StremioProgress>)
    @Query("UPDATE stremio_progress SET synced = 1 WHERE metaId IN (:ids)") suspend fun markSynced(ids: List<String>)
    @Query("DELETE FROM stremio_progress") suspend fun clearProgress()
}

// ================= konto =================

/**
 * Sesja Stremio (klucz API „authKey” i e-mail) w EncryptedSharedPreferences – jak tokeny YouTube; hasło nie jest zapisywane.
 * Gdy Keystore jest niedostępny – tylko w pamięci.
 */
object StremioAccount {
    private const val FILE = "stremio_account"
    private var app: Context? = null
    @Volatile private var opened = false
    @Volatile private var sp: SharedPreferences? = null
    private val mem = HashMap<String, String>()

    fun init(c: Context) { app = c.applicationContext }
    /** Testy: własny magazyn (null = tylko pamięć). */
    fun useStorage(s: SharedPreferences?) { synchronized(this) { sp = s; opened = true; mem.clear() } }

    private fun store(): SharedPreferences? {
        if (opened) return sp
        synchronized(this) {
            if (opened) return sp
            sp = app?.let { c -> runCatching {
                val key = androidx.security.crypto.MasterKey.Builder(c).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
                androidx.security.crypto.EncryptedSharedPreferences.create(c, FILE, key,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }.onFailure { android.util.Log.w("StremioAccount", "Brak szyfrowanego magazynu – sesja tylko w pamięci") }.getOrNull() }
            opened = true
            return sp
        }
    }

    private fun get(k: String) = store()?.let { runCatching { it.getString(k, "") }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: synchronized(this) { mem[k] } ?: ""
    private fun set(k: String, v: String) {
        synchronized(this) { mem[k] = v }
        store()?.let { runCatching { it.edit().putString(k, v).apply() } }
    }

    val authKey: String get() = get("auth")
    val email: String get() = get("email")
    val loggedIn: Boolean get() = authKey.isNotBlank()

    fun save(s: StremioApi.Session) { set("auth", s.authKey); set("email", s.email) }
    fun clear() { synchronized(this) { mem.clear() }; store()?.let { runCatching { it.edit().clear().apply() } } }
}

// ================= fasada =================

/**
 * Stan Stremio w aplikacji: wtyczki (konto + ręcznie dodane, przełącznik włączona/wyłączona) i postęp oglądania
 * z synchronizacją z biblioteką konta ([Prefs.stremioSync]).
 */
object StremioStore {
    private fun dao() = StubeHistory.db()?.stremio()

    @Volatile var addonsCache: List<Pair<StremioAddon, Boolean>> = emptyList(); private set

    /** Wtyczki z bazy; pusta baza – domyślne (Cinemeta, OpenSubtitles), gdy nie ma konta. */
    suspend fun addons(): List<Pair<StremioAddon, Boolean>> {
        val d = dao() ?: return addonsCache
        var rows = withContext(Dispatchers.IO) { runCatching { d.addons() }.getOrDefault(emptyList()) }
        if (rows.isEmpty()) {
            val defaults = StremioApi.defaultAddons.mapIndexedNotNull { i, u -> runCatching { StremioApi.manifest(u) }.getOrNull()?.let { StremioAddonState(it.transportUrl, it.manifestJson, true, i) } }
            if (defaults.isNotEmpty()) { withContext(Dispatchers.IO) { d.upsertAddons(defaults) }; rows = defaults }
        }
        return rows.mapNotNull { r -> runCatching { StremioAddon.parse(r.transportUrl, org.json.JSONObject(r.manifest)) to r.enabled }.getOrNull() }.also { addonsCache = it }
    }

    suspend fun enabledAddons() = addons().filter { it.second }.map { it.first }

    suspend fun setEnabled(url: String, on: Boolean) {
        dao()?.let { d -> withContext(Dispatchers.IO) { d.setEnabled(url, on) } }
        addonsCache = addonsCache.map { if (it.first.transportUrl == url) it.first to on else it }
    }

    /**
     * Wtyczki z konta: zastępują poprzednie z konta (stan włącz/wyłącz zachowany dla tych samych adresów, nowe – włączone),
     * ręcznie dodane zostają na końcu.
     */
    suspend fun replaceFromAccount(list: List<StremioAddon>) {
        val d = dao() ?: return
        withContext(Dispatchers.IO) {
            val old = d.addons().associateBy { it.transportUrl }
            val custom = old.values.filter { it.custom && list.none { a -> a.transportUrl == it.transportUrl } }
            d.clearAccountAddons()
            d.upsertAddons(list.mapIndexed { i, a -> StremioAddonState(a.transportUrl, a.manifestJson, old[a.transportUrl]?.enabled ?: true, i) } +
                custom.mapIndexed { i, c -> c.copy(position = list.size + i) })
        }
        addons()
    }

    /** Wtyczka z adresu manifestu (jak „Zainstaluj z adresu” w Stremio). */
    suspend fun addCustom(url: String): StremioAddon {
        val a = StremioApi.manifest(url)
        val d = dao() ?: throw StremioException("Baza niedostępna")
        withContext(Dispatchers.IO) { val n = d.addons().size; d.upsertAddons(listOf(StremioAddonState(a.transportUrl, a.manifestJson, true, n, custom = true))) }
        addons(); return a
    }

    suspend fun remove(url: String) { dao()?.let { d -> withContext(Dispatchers.IO) { d.deleteAddon(url) } }; addons() }

    /** Wylogowanie: wtyczki konta usunięte (zostają domyślne / ręczne przy następnym otwarciu). */
    suspend fun forgetAccount() { dao()?.let { d -> withContext(Dispatchers.IO) { d.clearAccountAddons() } }; addonsCache = emptyList() }

    // ---------------- postęp ----------------

    suspend fun progress(limit: Int = 30): List<StremioProgress> = dao()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.progress(limit) }.getOrDefault(emptyList()) } }.orEmpty()
    suspend fun progressOf(metaId: String): StremioProgress? = dao()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.progressOf(metaId) }.getOrNull() } }

    /** Zapis postępu (krótsze niż 5 s – pomijane); [push] – od razu na konto (gdy synchronizacja włączona). */
    suspend fun saveProgress(p: StremioProgress, push: Boolean = false) {
        if (p.positionMs < 5_000) return
        val d = dao() ?: return
        val old = withContext(Dispatchers.IO) { d.progressOf(p.metaId) }
        val row = p.copy(createdAt = old?.createdAt?.takeIf { it > 0 } ?: p.updatedAt, synced = false)
        withContext(Dispatchers.IO) { d.upsertProgress(listOf(row)) }
        if (push) pushPending()
    }

    val syncOn: Boolean get() = runCatching { Prefs.stremioSync == 1 }.getOrDefault(false) && StremioAccount.loggedIn

    /** Wysyła niezsynchronizowany postęp do biblioteki konta. */
    suspend fun pushPending(): Int {
        if (!syncOn) return 0
        val d = dao() ?: return 0
        val pending = withContext(Dispatchers.IO) { d.unsynced() }
        if (pending.isEmpty()) return 0
        StremioApi.putLibrary(StremioAccount.authKey, pending.map { StremioApi.libraryJson(it) })
        withContext(Dispatchers.IO) { d.markSynced(pending.map { it.metaId }) }
        return pending.size
    }

    /**
     * Synchronizacja z kontem: pozycje biblioteki z postępem → baza (nowsze wygrywają), potem lokalne zmiany → konto.
     * Zwraca liczbę pozycji pobranych z konta.
     */
    suspend fun syncLibrary(): Int {
        if (!StremioAccount.loggedIn) return 0
        val d = dao() ?: return 0
        val remote = StremioApi.library(StremioAccount.authKey).filter { !it.removed && (it.timeOffset > 0 || it.videoId.isNotBlank()) }
        val local = withContext(Dispatchers.IO) { d.progress(10_000) }.associateBy { it.metaId }
        val newer = remote.filter { r -> (local[r.id]?.updatedAt ?: 0) < r.lastWatched }.map { r ->
            StremioProgress(r.id, r.type, r.name, r.poster, r.videoId, videoLabel(r.videoId), r.timeOffset, r.duration, r.lastWatched, r.lastWatched, synced = true)
        }
        withContext(Dispatchers.IO) { d.upsertProgress(newer) }
        pushPending()
        return newer.size
    }

    /** „tt0903747:2:5” → „S2E5”. */
    fun videoLabel(videoId: String): String {
        val p = videoId.split(':')
        return if (p.size >= 3 && p[p.size - 2].toIntOrNull() != null && p.last().toIntOrNull() != null) "S${p[p.size - 2]}E${p.last()}" else ""
    }
}

// ================= napisy =================

/** Napisy SRT / WebVTT – parsowanie do listy (czas w ms); wyświetlanie z opóźnieniem w odtwarzaczu. */
object StremioSubs {
    data class Line(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})|(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")

    private fun ms(s: String): Long? {
        val m = TIME.find(s.trim()) ?: return null
        return if (m.groupValues[1].isNotEmpty()) {
            (m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].padEnd(3, '0').toLong()
        } else (m.groupValues[5].toLong() * 60 + m.groupValues[6].toLong()) * 1000 + m.groupValues[7].padEnd(3, '0').toLong()
    }

    fun parse(text: String): List<Line> {
        val out = ArrayList<Line>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) continue
            val (a, z) = lines[ti].split("-->").let { it[0] to it.getOrElse(1) { "" } }
            val start = ms(a) ?: continue; val end = ms(z) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n") { it.replace(Regex("<[^>]+>|\\{\\\\[^}]*\\}"), "").trim() }.trim()
            if (body.isNotEmpty()) out += Line(start, end, body)
        }
        return out.sortedBy { it.startMs }
    }

    /** Tekst widoczny w chwili [posMs] przy opóźnieniu [delayMs] (dodatnie – napisy później). */
    fun at(lines: List<Line>, posMs: Long, delayMs: Long): String? {
        val t = posMs - delayMs
        var lo = 0; var hi = lines.size - 1; var idx = -1
        while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].startMs <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
        if (idx < 0) return null
        val active = (maxOf(0, idx - 3)..idx).map { lines[it] }.filter { t < it.endMs }
        return active.joinToString("\n") { it.text }.ifEmpty { null }
    }

    /** Nazwa języka z kodu ISO 639-2 (OpenSubtitles: „pol”, „eng”). */
    fun langName(code: String): String = when (code.lowercase().take(3)) {
        "pol", "pl" -> "Polski"; "eng", "en" -> "Angielski"; "ger", "deu", "de" -> "Niemiecki"; "fre", "fra", "fr" -> "Francuski"
        "spa", "es" -> "Hiszpański"; "ita", "it" -> "Włoski"; "rus", "ru" -> "Rosyjski"; "ukr", "uk" -> "Ukraiński"; "cze", "ces", "cs" -> "Czeski"
        "por", "pt" -> "Portugalski"; "dut", "nld", "nl" -> "Niderlandzki"; "swe", "sv" -> "Szwedzki"; "tur", "tr" -> "Turecki"
        else -> code
    }
}

package pl.iptv.tv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Ustawienia silnika odtwarzania dobrane dla kanału ([pl.iptv.tv.ui.ChannelEngineOptimizer]).
 *
 * [software] – dekoder programowy przed sprzętowym; [ffmpeg] – dekoder dźwięku FFmpeg (AC-3, E-AC-3, DTS, MP2 niewspierane
 * przez dekoder systemu); [async] – kolejka dekodera (0 – wg ustawień, 1 – asynchroniczna, 2 – synchroniczna);
 * [container] – rodzaj strumienia (0 – rozpoznanie automatyczne, 1 – HLS, 2 – zwykły strumień TS). Profil wyłącza tunelowanie.
 */
data class EngineProfile(val software: Boolean = false, val ffmpeg: Boolean = false, val async: Int = 0, val container: Int = 0) {
    fun encode() = "sw=${b(software)};ff=${b(ffmpeg)};async=$async;fmt=$container"
    private fun b(v: Boolean) = if (v) 1 else 0

    /** Opis dla użytkownika (komunikat na ekranie, ustawienia). */
    val label: String get() = listOfNotNull(
        if (software) "dekoder programowy" else "dekoder sprzętowy",
        if (ffmpeg) "dźwięk FFmpeg" else null,
        when (async) { 1 -> "kolejka asynchroniczna"; 2 -> "kolejka synchroniczna"; else -> null },
        when (container) { 1 -> "strumień HLS"; 2 -> "strumień TS"; else -> null },
    ).joinToString(", ")

    companion object {
        fun decode(s: String): EngineProfile? = runCatching {
            val m = s.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            EngineProfile(m["sw"] == "1", m["ff"] == "1", m["async"]?.toInt()?.coerceIn(0, 2) ?: 0, m["fmt"]?.toInt()?.coerceIn(0, 2) ?: 0)
        }.getOrNull()
    }
}

/**
 * Zapamiętany wynik doboru silnika: kanał ([channelKey]) i adres strumienia ([streamUrl]) → ustawienia ([config], [EngineProfile.encode]).
 * [testedFailed] – żadne ustawienie nie otworzyło strumienia (np. martwy serwer): bez ponownych testów do [ChannelEngines.RETEST_MS].
 */
@Entity(tableName = "channel_engine")
data class ChannelEngine(
    @PrimaryKey val channelKey: String,
    val streamUrl: String,
    val config: String = "",
    val testedFailed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val profile: EngineProfile? get() = if (testedFailed) null else EngineProfile.decode(config)
}

@Dao
interface ChannelEngineDao {
    @Query("SELECT * FROM channel_engine") suspend fun all(): List<ChannelEngine>
    @Upsert suspend fun upsert(e: ChannelEngine)
    @Query("DELETE FROM channel_engine WHERE channelKey = :key") suspend fun delete(key: String)
    @Query("DELETE FROM channel_engine") suspend fun clear()
}

/**
 * Fasada: wyniki w pamięci (odczyt synchroniczny przy starcie kanału – bez dostępu do bazy w wątku interfejsu),
 * zapis do Room w tle ([StubeHistory.scope], wątek I/O).
 */
object ChannelEngines {
    /** Po tym czasie kanał oznaczony jako niedziałający może być przetestowany ponownie (serwer mógł wrócić). */
    const val RETEST_MS = 24 * 3_600_000L
    /** Wersja zasad doboru – starsze zapisane profile są usuwane przy starcie ([load]). */
    const val PROFILES_VERSION = 2

    private val mem = ConcurrentHashMap<String, ChannelEngine>()
    @Volatile var loaded = false; private set

    private fun dao() = StubeHistory.db()?.channelEngines()

    /** Wczytanie zapisanych wyników (start aplikacji, w tle). */
    suspend fun load() {
        // (7.2) profile zapisane przez 7.0 / 7.1 bywały błędne (dobry, wolniej startujący kanał dostawał dekoder programowy –
        // zacinanie obrazu): jednorazowo usunięte, kanały dobierane od nowa wg nowych, bezpiecznych zasad
        if (runCatching { Prefs.engineProfilesVersion }.getOrDefault(PROFILES_VERSION) < PROFILES_VERSION) {
            // tylko stare wiersze bazy; wyniki zapisane już w tej sesji (pamięć – nowe zasady) zostają i wracają do bazy
            runCatching { dao()?.clear() }
            mem.values.forEach { e -> runCatching { dao()?.upsert(e) } }
            Prefs.engineProfilesVersion = PROFILES_VERSION
        }
        val l = runCatching { dao()?.all() }.getOrNull() ?: emptyList()
        // scalanie (bez czyszczenia): wynik zapisany tuż po starcie, zanim baza zdążyła się wczytać, zostaje
        l.forEach { mem.putIfAbsent(it.channelKey, it) }
        loaded = true
    }

    fun loadAsync() { StubeHistory.scope.launch { load() } }

    fun get(channelKey: String): ChannelEngine? = mem[channelKey]

    /** Zapamiętany profil kanału (null – brak albo kanał oznaczony jako niedziałający). */
    fun profileFor(channelKey: String): EngineProfile? = mem[channelKey]?.profile

    /** Czy testy tego kanału są wstrzymane (wszystkie ustawienia zawiodły niedawno). */
    fun blocked(channelKey: String, now: Long = System.currentTimeMillis()): Boolean =
        mem[channelKey]?.let { it.testedFailed && now - it.updatedAt < RETEST_MS } == true

    fun save(channelKey: String, url: String, p: EngineProfile) = put(ChannelEngine(channelKey, url, p.encode()))
    fun markFailed(channelKey: String, url: String) = put(ChannelEngine(channelKey, url, "", testedFailed = true))

    fun forget(channelKey: String) {
        mem.remove(channelKey)
        StubeHistory.scope.launch { runCatching { dao()?.delete(channelKey) } }
    }

    fun clear() {
        mem.clear()
        StubeHistory.scope.launch { runCatching { dao()?.clear() } }
    }

    val count: Int get() = mem.size
    val failedCount: Int get() = mem.values.count { it.testedFailed }

    private fun put(e: ChannelEngine) {
        mem[e.channelKey] = e
        StubeHistory.scope.launch { runCatching { dao()?.upsert(e) } }
    }

    /** Testy: tylko pamięć (bez czekania na zapis). */
    fun resetForTest() { mem.clear(); loaded = false }
}

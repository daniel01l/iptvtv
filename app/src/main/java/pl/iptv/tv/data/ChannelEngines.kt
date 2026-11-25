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
data class EngineProfile(val software: Boolean = false, val ffmpeg: Boolean = false, val async: Int = 0, val container: Int = 0,
                         val buffer: Int = 0, val surface: Int = 0) {
    /** (7.5) [buffer] – 0: zwykły zapas, 1: duży (start 3 s, po przycięciu 8 s, do 90 s) dla zacinających się kanałów.
     *  (7.20) [surface] – 0: SurfaceView, 1: TextureView (błędy sprzętowego usuwania przeplotu na części TV z Google TV). */
    fun encode() = "sw=${b(software)};ff=${b(ffmpeg)};async=$async;fmt=$container" + (if (buffer != 0) ";buf=$buffer" else "") +
        (if (surface != 0) ";surf=$surface" else "")
    private fun b(v: Boolean) = if (v) 1 else 0

    /** Opis dla użytkownika (komunikat na ekranie, ustawienia). */
    val label: String get() = listOfNotNull(
        if (software) "dekoder programowy" else "dekoder sprzętowy",
        if (ffmpeg) "dźwięk FFmpeg" else null,
        when (async) { 1 -> "kolejka asynchroniczna"; 2 -> "kolejka synchroniczna"; else -> null },
        when (container) { 1 -> "strumień HLS"; 2 -> "strumień TS"; else -> null },
        if (buffer == 1) "duży bufor" else null,
        if (surface == 1) "obraz TextureView" else null,
    ).joinToString(", ")

    companion object {
        fun decode(s: String): EngineProfile? = runCatching {
            val m = s.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            EngineProfile(m["sw"] == "1", m["ff"] == "1", m["async"]?.toInt()?.coerceIn(0, 2) ?: 0, m["fmt"]?.toInt()?.coerceIn(0, 2) ?: 0,
                m["buf"]?.toInt()?.coerceIn(0, 1) ?: 0, m["surf"]?.toInt()?.coerceIn(0, 1) ?: 0)
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
    const val PROFILES_VERSION = 3   // 3 (7.3): profile dobrane w 7.2 po jednorazowym błędzie formatu (zbędne, spowalniały przełączanie)

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
        // (7.22) jednorazowo: tryb zgodności z 7.20 (dekoder programowy + TextureView) przeciążał procesor TV na kanałach HD –
        // dekoder sprzętowy zostaje, TextureView tak (np. Canal+ Extra 2 od razu na GPU); pozostałe profile bez zmian
        if (runCatching { !Prefs.engineCompatHw }.getOrDefault(false)) {
            mem.values.toList().forEach { e -> e.profile?.takeIf { it.software && it.surface == 1 }?.let { put(e.copy(config = it.copy(software = false).encode(), updatedAt = System.currentTimeMillis())) } }
            Prefs.engineCompatHw = true
        }
        // (7.22) profile programowe kanałów HD (np. Canal+ Extra 2) – od razu z powrotem na sprzętowy
        runCatching { clearSoftwareForHd() }
        loaded = true; onChanged?.invoke()
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    fun loadAsync() { loadJob = StubeHistory.scope.launch { load() } }

    fun get(channelKey: String): ChannelEngine? = mem[channelKey]

    /** Zapamiętany profil kanału (null – brak albo kanał oznaczony jako niedziałający). */
    fun profileFor(channelKey: String): EngineProfile? = mem[channelKey]?.profile

    /** Czy testy tego kanału są wstrzymane (wszystkie ustawienia zawiodły niedawno). */
    fun blocked(channelKey: String, now: Long = System.currentTimeMillis()): Boolean =
        mem[channelKey]?.let { it.testedFailed && now - it.updatedAt < RETEST_MS } == true

    fun save(channelKey: String, url: String, p: EngineProfile) = put(ChannelEngine(channelKey, url, p.encode()))
    fun markFailed(channelKey: String, url: String) = put(ChannelEngine(channelKey, url, "", testedFailed = true))

    fun forget(channelKey: String) {
        mem.remove(channelKey); onChanged?.invoke()
        StubeHistory.scope.launch { runCatching { dao()?.delete(channelKey) } }
    }

    /** (7.22) Kanały, na których obraz miał ≥ 720 linii lub ≥ 48 kl/s ([Prefs.hdChannels]) – bez profili programowych. */
    fun markHd(key: String) {
        val s = Prefs.hdChannels
        if (key in s) return
        Prefs.hdChannels = (s + key).toList().takeLast(HD_MAX).toSet()
    }
    const val HD_MAX = 500

    /**
     * (7.22) Usunięcie programowego dekodera z profili kanałów HD (zapisanych automatycznie – tryb zgodności / błąd dekodera):
     * pozostałe ustawienia zostają, pusty profil – wpis usunięty. Zwraca liczbę zmienionych wpisów.
     */
    fun clearSoftwareForHd(): Int {
        val hd = Prefs.hdChannels
        var n = 0
        mem.values.toList().filter { it.channelKey in hd && it.profile?.software == true }.forEach { e ->
            val hw = e.profile!!.copy(software = false)
            if (hw == EngineProfile()) forget(e.channelKey) else put(e.copy(config = hw.encode(), updatedAt = System.currentTimeMillis()))
            n++
        }
        return n
    }

    /** (7.20) Zmiana zapamiętanych ustawień (zapis, reset kanału, wyczyszczenie) – [pl.iptv.tv.ui.EngineConfigRepository] odświeża stan. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun clear() {
        mem.clear(); onChanged?.invoke()
        StubeHistory.scope.launch { runCatching { dao()?.clear() } }
    }

    /** (7.20) Zapamiętane kanały (okno w Ustawieniach): dobrane najpierw, potem niedziałające; w grupie – najnowsze pierwsze. */
    fun entries(): List<ChannelEngine> = mem.values.sortedWith(compareBy<ChannelEngine> { it.testedFailed }.thenByDescending { it.updatedAt })

    val count: Int get() = mem.size
    val failedCount: Int get() = mem.values.count { it.testedFailed }

    private fun put(e: ChannelEngine) {
        mem[e.channelKey] = e; onChanged?.invoke()
        StubeHistory.scope.launch { runCatching { dao()?.upsert(e) } }
    }

    /** Testy: tylko pamięć (bez czekania na zapis). */
    /** Testy: czekanie na wczytywanie z [loadAsync] (start aplikacji) – inaczej mogło dopisać wiersze w trakcie testu. */
    fun resetForTest() { loadJob?.let { j -> kotlinx.coroutines.runBlocking { j.join() } }; loadJob = null; mem.clear(); loaded = false; onChanged?.invoke() }
}

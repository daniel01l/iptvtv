package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * (7.20) Rzędy „Top 10” platform streamingowych na tablicy Stremio: dla każdej widocznej platformy osobno filmy i seriale
 * (po najwyżej [SIZE] pozycji) – ranking popularności TMDB w regionie PL ([StremioTmdb.discover]). (7.22) 10 platform
 * ([PLATFORMS], wybór w oknie „Klucz TMDB”, domyślnie pięć pierwszych), identyfikatory kilku wersji platformy w jednym
 * zapytaniu („1899|384”), mniej niż [FILL_BELOW] wyników w PL – uzupełnienie z US, najwyżej [PARALLEL_ROWS] zapytania naraz,
 * w pamięci podręcznej tylko rzędy z ≥ [MIN_CACHE_ITEMS] pozycjami; pozycje z identyfikatorem TMDB (IMDb po kliknięciu).
 *
 * Pamięć podręczna: plik JSON `stremio_top10.json` w `cacheDir` (pozycje + `lastUpdatedTimestamp` każdego rzędu) – tablica
 * wczytuje rzędy od razu z pliku, a sieć tylko w tle i tylko gdy od ostatniej aktualizacji minął [intervalMs]
 * (domyślnie 96 h = 4 dni, `Prefs.stremioTop10Hours`) albo rząd jest pusty.
 */
object StremioTop10 {
    const val SIZE = 10
    const val DEFAULT_HOURS = 96
    /** Do wyboru w Ustawieniach (godziny). */
    val HOUR_CHOICES = listOf(24, 48, 72, 96, 168, 336)
    const val FILE = "stremio_top10.json"

    /**
     * (7.22) Platforma: [providers] – identyfikatory `with_watch_providers` TMDB (kilka rozdzielonych „|” = LUB, jedno
     * zapytanie), [defaultOn] – widoczna, dopóki użytkownik nie zmieni wyboru (Klucz TMDB → „Widoczne sekcje TOP 10 w Stremio”).
     */
    data class Platform(val key: String, val name: String, val providers: String, val defaultOn: Boolean)
    /** (7.22) 10 platform dostępnych w Polsce (region PL); domyślnie widocznych pięć pierwszych. */
    val PLATFORMS = listOf(
        Platform("netflix", "Netflix", "8", true),
        Platform("max", "Max (HBO Max)", "1899|384", true),
        Platform("disney", "Disney+", "337", true),
        Platform("prime", "Amazon Prime Video", "119", true),
        Platform("skyshowtime", "SkyShowtime (Paramount+)", "1773|531", true),
        Platform("appletv", "Apple TV+", "350", false),
        Platform("canalplus", "CANAL+ online", "188", false),
        Platform("player", "Player (TVN)", "505", false),
        Platform("polsatbox", "Polsat Box Go", "548", false),
        Platform("tvpvod", "TVP VOD", "502", false),
    )

    /** Klucze widocznych platform (wybór użytkownika albo domyślne). */
    fun enabledKeys(): Set<String> = runCatching { Prefs.stremioTop10Platforms }.getOrNull()
        ?: PLATFORMS.filter { it.defaultOn }.map { it.key }.toSet()
    fun isEnabled(key: String) = key in enabledKeys()
    fun setEnabled(key: String, on: Boolean) { Prefs.stremioTop10Platforms = enabledKeys().let { if (on) it + key else it - key }.filter { k -> PLATFORMS.any { it.key == k } }.toSet() }
    fun setAll(on: Boolean) { Prefs.stremioTop10Platforms = if (on) PLATFORMS.map { it.key }.toSet() else emptySet() }
    val enabledPlatforms: List<Platform> get() = enabledKeys().let { on -> PLATFORMS.filter { it.key in on } }

    /** Rząd: platforma + rodzaj („movie” / „series”). */
    data class RowSpec(val platform: Platform, val type: String) {
        val key: String get() = "${platform.key}/$type"
        val title: String get() = "Top 10 ${if (type == "series") "Seriale" else "Filmy"} – ${platform.name}"
    }

    /**
     * Rzędy dla zakładki (tylko widoczne platformy): Odkrywaj ([type] null) – filmy i seriale pogrupowane platformami;
     * Filmy – rzędy filmów; Seriale – rzędy seriali. [all] – także ukryte platformy.
     */
    fun rows(type: String?, all: Boolean = false): List<RowSpec> = (if (all) PLATFORMS else enabledPlatforms).flatMap { p ->
        listOf("movie", "series").filter { type == null || it == type }.map { RowSpec(p, it) }
    }

    /** (7.22) Zapisywane są tylko rzędy z co najmniej tyloma pozycjami (pusty / szczątkowy wynik – pobranie od nowa). */
    const val MIN_CACHE_ITEMS = 3
    /** (7.22) Mniej wyników w regionie PL – uzupełnienie rankingiem z regionu US (polskie tytuły, `pl-PL`). */
    const val FILL_BELOW = 5
    const val FILL_REGION = "US"

    val intervalMs: Long get() = runCatching { Prefs.stremioTop10Hours }.getOrDefault(DEFAULT_HOURS).coerceAtLeast(1) * 3_600_000L

    /** Zapisany rząd: pozycje w kolejności rankingu i czas ostatniej aktualizacji. */
    data class Cached(val items: List<StremioMetaPreview>, val lastUpdatedTimestamp: Long)

    @Volatile private var dir: File? = null
    private val mem = HashMap<String, Cached>()
    @Volatile private var loaded = false
    private val lock = Mutex()
    /** (7.22) Najwyżej tyle rzędów (zapytań HTTP do TMDB) naraz – ochrona przed HTTP 429 i przeciążeniem słabszych TV. */
    const val PARALLEL_ROWS = 3
    private val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL_ROWS)
    /** Zegar (testy). */
    var now: () -> Long = { System.currentTimeMillis() }
    /** Liczba pobrań z sieci (testy). */
    private val fetchCount = java.util.concurrent.atomic.AtomicInteger()
    val fetches: Int get() = fetchCount.get()

    fun init(c: android.content.Context) { if (dir == null) dir = c.applicationContext.cacheDir }
    private fun file() = dir?.let { File(it, FILE) }

    /** Wczytanie pliku (raz) – synchroniczne, mały plik; tablica pokazuje rzędy bez czekania na sieć. */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(mem) {
            if (loaded) return
            runCatching {
                val f = file() ?: return
                if (!f.exists()) { loaded = true; return }
                val o = JSONObject(f.readText())
                o.optJSONObject("rows")?.let { rows ->
                    rows.keys().forEach { k ->
                        val r = rows.getJSONObject(k)
                        val a = r.optJSONArray("items") ?: JSONArray()
                        mem[k] = Cached((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
                            StremioMetaPreview(j.optString("id"), j.optString("type"), j.optString("name"), j.optString("poster"),
                                description = j.optString("description"), releaseInfo = j.optString("year"), imdbRating = j.optString("rating"), released = j.optString("released"))
                        }.filter { it.id.isNotBlank() }, r.optLong("lastUpdatedTimestamp"))
                    }
                }
            }
            loaded = true
        }
    }

    private fun save() {
        val f = file() ?: return
        val o = JSONObject()
        val rows = JSONObject()
        val known = rows(null, all = true).map { it.key }.toSet()
        synchronized(mem) {
            // (7.22) tylko rzędy znanych platform z co najmniej [MIN_CACHE_ITEMS] pozycjami (dawne klucze, np. „paramount/…”, wypadają)
            mem.filter { (k, c) -> k in known && c.items.size >= MIN_CACHE_ITEMS }.forEach { (k, c) ->
                rows.put(k, JSONObject().put("lastUpdatedTimestamp", c.lastUpdatedTimestamp).put("items", JSONArray().apply {
                    c.items.forEach { m -> put(JSONObject().put("id", m.id).put("type", m.type).put("name", m.name).put("poster", m.poster)
                        .put("description", m.description).put("year", m.releaseInfo).put("rating", m.imdbRating).put("released", m.released)) }
                }))
            }
        }
        o.put("rows", rows)
        runCatching { val tmp = File(f.parentFile, "$FILE.tmp"); tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
    }

    /** Pozycje z pamięci podręcznej (od razu, bez sieci); pusta – brak zapisu. */
    fun cached(spec: RowSpec): Cached? { ensureLoaded(); return synchronized(mem) { mem[spec.key] } }

    /** Wymaga pobrania: brak zapisu, pusty rząd albo od ostatniej aktualizacji minęło co najmniej [intervalMs]. */
    fun isStale(spec: RowSpec, at: Long = now()): Boolean {
        val c = cached(spec) ?: return true
        return c.items.size < MIN_CACHE_ITEMS || at - c.lastUpdatedTimestamp >= intervalMs
    }

    /**
     * Pobranie rzędu z sieci (w tle; [force] – mimo świeżego zapisu, np. „Odśwież Top 10 teraz”). Kolejne identyfikatory
     * platformy, dopóki wynik jest pusty. Błąd sieci / brak klucza – zapis bez zmian (null).
     */
    suspend fun refresh(spec: RowSpec, force: Boolean = false): List<StremioMetaPreview>? = withContext(Dispatchers.IO) {
        if (!force && !isStale(spec)) return@withContext cached(spec)?.items
        if (!StremioTmdb.available) return@withContext null
        gate.acquire()
        // po czekaniu na kolejkę rząd mógł już zostać pobrany (np. tablica i Ustawienia naraz)
        try { if (!force && !isStale(spec)) cached(spec)?.items else fetch(spec) } finally { gate.release() }
    }

    private suspend fun fetch(spec: RowSpec): List<StremioMetaPreview>? {
        if (!StremioTmdb.available) return null
        val pl = StremioTmdb.discover(spec.type, spec.platform.providers, "PL", limit = SIZE) ?: return null
        fetchCount.incrementAndGet()
        // (7.22) mało wyników w PL (np. nowa platforma w TMDB) – uzupełnienie z regionu US, polskie tytuły (język aplikacji)
        val us = if (pl.size < FILL_BELOW) StremioTmdb.discover(spec.type, spec.platform.providers, FILL_REGION, limit = SIZE).orEmpty() else emptyList()
        val list = (pl + us).distinctBy { it.id }.take(SIZE)
        // (7.22) nigdy pusty / szczątkowy rząd w pamięci podręcznej – przy następnym otwarciu pobranie od nowa
        if (list.size >= MIN_CACHE_ITEMS) {
            synchronized(mem) { mem[spec.key] = Cached(list, now()) }
            lock.withLock { save() }
        }
        return list
    }

    /** Odświeżenie wszystkich nieaktualnych rzędów (równolegle po platformach); zwraca rzędy, które się zmieniły. */
    suspend fun refreshStale(specs: List<RowSpec>, force: Boolean = false): List<RowSpec> = coroutineScope {
        specs.filter { force || isStale(it) }.map { s -> async { val before = cached(s)?.items; val after = refresh(s, force); if (after != null && after != before) s else null } }
            .mapNotNull { it.await() }
    }

    /** Testy: pamięć i plik od nowa. */
    fun resetForTest(c: android.content.Context) {
        dir = c.applicationContext.cacheDir; synchronized(mem) { mem.clear() }; loaded = false; fetchCount.set(0); now = { System.currentTimeMillis() }
        file()?.delete()
    }
}

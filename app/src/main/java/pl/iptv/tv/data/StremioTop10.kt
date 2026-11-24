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
 * (7.20) Rzędy „Top 10” platform streamingowych na tablicy Stremio: dla Netflix, HBO Max, Prime Video, Disney+ i Paramount+
 * osobno filmy i seriale (10 rzędów po najwyżej [SIZE] pozycji) – ranking popularności TMDB w regionie PL
 * ([StremioTmdb.discover]). Platforma z kilkoma identyfikatorami ([Platform.providers]) – kolejny, gdy poprzedni zwraca pustą
 * listę (HBO Max 1899 → 384, Paramount+ 531 → SkyShowtime 1773).
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

    data class Platform(val key: String, val name: String, val providers: List<Int>)
    val PLATFORMS = listOf(
        Platform("netflix", "Netflix", listOf(8)),
        Platform("max", "HBO Max", listOf(1899, 384)),
        Platform("prime", "Prime Video", listOf(119)),
        Platform("disney", "Disney+", listOf(337)),
        Platform("paramount", "Paramount+", listOf(531, 1773)),
    )

    /** Rząd: platforma + rodzaj („movie” / „series”). */
    data class RowSpec(val platform: Platform, val type: String) {
        val key: String get() = "${platform.key}/$type"
        val title: String get() = "Top 10 ${if (type == "series") "Seriale" else "Filmy"} – ${platform.name}"
    }

    /**
     * Rzędy dla zakładki: Odkrywaj ([type] null) – wszystkie 10, pogrupowane platformami (filmy, potem seriale); Filmy – 5 rzędów
     * filmów; Seriale – 5 rzędów seriali.
     */
    fun rows(type: String?): List<RowSpec> = PLATFORMS.flatMap { p ->
        listOf("movie", "series").filter { type == null || it == type }.map { RowSpec(p, it) }
    }

    val intervalMs: Long get() = runCatching { Prefs.stremioTop10Hours }.getOrDefault(DEFAULT_HOURS).coerceAtLeast(1) * 3_600_000L

    /** Zapisany rząd: pozycje w kolejności rankingu i czas ostatniej aktualizacji. */
    data class Cached(val items: List<StremioMetaPreview>, val lastUpdatedTimestamp: Long)

    @Volatile private var dir: File? = null
    private val mem = HashMap<String, Cached>()
    @Volatile private var loaded = false
    private val lock = Mutex()
    /** Najwyżej tyle rzędów pobieranych naraz (każdy to ~20 zapytań TMDB) – bez przekraczania limitu czasu i limitów API. */
    const val PARALLEL_ROWS = 2
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
        synchronized(mem) {
            mem.forEach { (k, c) ->
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
        return c.items.isEmpty() || at - c.lastUpdatedTimestamp >= intervalMs
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
        var got: List<StremioMetaPreview>? = null
        for (id in spec.platform.providers) {
            val l = StremioTmdb.discover(spec.type, id, limit = SIZE) ?: return null
            got = l
            if (l.isNotEmpty()) break
        }
        fetchCount.incrementAndGet()
        val list = got.orEmpty().take(SIZE)
        synchronized(mem) { mem[spec.key] = Cached(list, now()) }
        lock.withLock { save() }
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

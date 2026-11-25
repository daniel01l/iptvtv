package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioTmdb
import pl.iptv.tv.data.StremioTop10
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration

/**
 * 7.20 – rzędy „Top 10” platform streamingowych w Stremio: 10 rzędów (Netflix, HBO Max, Prime Video, Disney+, Paramount+ ×
 * filmy / seriale) z TMDB discover (PL, popularność, pl-PL), identyfikatory zastępcze platform, pamięć podręczna w pliku
 * odświeżana co 96 h (do zmiany), rzędy od razu z pamięci, odznaka „#1”–„#10” w kolorach motywu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V720Top10Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    private fun url(p: String) = server.url(p).toString()
    private val discovers = mutableListOf<String>()
    private var clock = 1_000_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear(); StremioTmdb.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0; Prefs.stremioLibrary = ""
        Prefs.language = "pl"; Prefs.stremioTop10Hours = 96
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                fun results(base: Int, n: Int, tv: Boolean) = ok(JSONObject().put("results", JSONArray((1..n).map { i ->
                    JSONObject().put("id", base + i).put(if (tv) "name" else "title", "Tytuł ${base + i}").put("poster_path", "/p${base + i}.jpg")
                        .put(if (tv) "first_air_date" else "release_date", "2024-01-0${i % 9 + 1}").put("vote_average", 7.5).put("overview", "Polski opis ${base + i}")
                })))
                if (p.startsWith("/tmdb/discover/") && p.contains("with_watch_providers")) synchronized(discovers) { discovers += p }   // (7.22) bez zapytania kin
                val tv = p.startsWith("/tmdb/discover/tv")
                return when {
                    p == "/m/manifest.json" -> ok(JSONObject().put("id", "meta").put("name", "Meta").put("version", "1.0.0").put("types", JSONArray(listOf("movie", "series")))
                        .put("idPrefixes", JSONArray(listOf("tt"))).put("resources", JSONArray(listOf("catalog", "meta"))).put("catalogs", JSONArray()))
                    p.startsWith("/tmdb/discover/") && p.contains("with_watch_providers") -> {
                        // (7.22) kilka identyfikatorów w jednym zapytaniu („1899|384” = LUB): wyniki daje ten, który nie jest pusty
                        val provs = java.net.URLDecoder.decode(Regex("with_watch_providers=([^&]+)").find(p)!!.groupValues[1], "UTF-8").split('|').map { it.toInt() }
                        val prov = provs.firstOrNull { it != 1899 && it != 531 } ?: return ok(JSONObject().put("results", JSONArray()))
                        results(prov * 100 + if (tv) 50 else 0, 12, tv)
                    }
                    p.contains("/external_ids") -> {
                        val id = Regex("/(movie|tv)/(\\d+)/").find(p)!!.groupValues[2].toInt()
                        ok(JSONObject().put("imdb_id", if (id % 100 == 3) "" else "tt$id"))     // pozycja bez IMDb – pominięta
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioApi.defaultAddons = listOf(url("/m/manifest.json"))
        StremioApi.client = { okhttp3.OkHttpClient() }
        StremioTmdb.base = url("/tmdb").removeSuffix("/"); StremioTmdb.apiKey = { "test" }
        StremioTop10.resetForTest(ctx); StremioTop10.now = { clock }; Prefs.stremioTop10Platforms = null
    }

    @After fun tearDown() {
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES); StremioApi.client = { pl.iptv.tv.data.Repository.http }
        StremioTmdb.base = "https://api.themoviedb.org/3"; StremioTmdb.apiKey = { Prefs.tmdbApiKey }; StremioTmdb.clearCache()
        StremioTop10.resetForTest(ctx); Prefs.stremioTop10Hours = 96; Prefs.stremioTop10Platforms = null
        Prefs.stremioGuest = false; StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    @Test fun platformsRowsAndTitlesPerTab() {
        // (7.22) domyślnie widoczne pięć pierwszych z 10 platform
        val all = StremioTop10.rows(null)
        assertEquals(10, all.size)
        assertEquals(listOf("Top 10 Filmy – Netflix", "Top 10 Seriale – Netflix", "Top 10 Filmy – Max (HBO Max)", "Top 10 Seriale – Max (HBO Max)",
            "Top 10 Filmy – Disney+", "Top 10 Seriale – Disney+", "Top 10 Filmy – Amazon Prime Video", "Top 10 Seriale – Amazon Prime Video",
            "Top 10 Filmy – SkyShowtime (Paramount+)", "Top 10 Seriale – SkyShowtime (Paramount+)"), all.map { it.title })
        assertEquals(listOf("8", "1899|384", "337", "119", "1773|531", "350", "188", "505", "548", "502"), StremioTop10.PLATFORMS.map { it.providers })
        assertEquals(listOf("netflix", "max", "disney", "prime", "skyshowtime", "appletv", "canalplus", "player", "polsatbox", "tvpvod"), StremioTop10.PLATFORMS.map { it.key })
        assertEquals(20, StremioTop10.rows(null, all = true).size)
        assertTrue(StremioTop10.rows("movie").let { r -> r.size == 5 && r.all { it.title.startsWith("Top 10 Filmy – ") } })
        assertTrue(StremioTop10.rows("series").let { r -> r.size == 5 && r.all { it.title.startsWith("Top 10 Seriale – ") } })
    }

    @Test fun discoverFallbackProvidersAndFourDayCache() = runBlocking {
        val netflix = StremioTop10.rows("movie")[0]
        val list = StremioTop10.refresh(netflix)!!
        assertEquals(10, list.size)                                                         // dokładnie 10
        // (7.22) identyfikator TMDB (IMDb dopiero po kliknięciu) – bez zapytań external_ids przy pobieraniu rzędu
        assertEquals("tmdb:movie:801", list.first().id)
        assertEquals("Polski opis 801", list.first().description); assertEquals("https://image.tmdb.org/t/p/w342/p801.jpg", list.first().poster)
        val q = synchronized(discovers) { discovers.first() }
        listOf("with_watch_providers=8", "watch_region=PL", "sort_by=popularity.desc", "language=pl-PL").forEach { assertTrue(q, q.contains(it)) }
        // Max „1899|384” i SkyShowtime „1773|531” – jedno zapytanie z kilkoma identyfikatorami; seriale – /discover/tv
        assertEquals("tmdb:movie:38401", StremioTop10.refresh(StremioTop10.rows("movie")[1])!!.first().id)
        assertEquals("tmdb:movie:177301", StremioTop10.refresh(StremioTop10.rows("movie")[4])!!.first().id)
        assertTrue(synchronized(discovers) { discovers.any { java.net.URLDecoder.decode(it, "UTF-8").contains("with_watch_providers=1899|384") } })
        assertEquals("series", StremioTop10.refresh(StremioTop10.rows("series")[0])!!.first().type)
        assertTrue(synchronized(discovers) { discovers.any { it.startsWith("/tmdb/discover/tv") } })
        // plik w cacheDir z lastUpdatedTimestamp; świeży zapis – bez sieci
        val f = java.io.File(ctx.cacheDir, StremioTop10.FILE)
        assertTrue(f.exists()); assertTrue(f.readText().contains("lastUpdatedTimestamp"))
        val n = StremioTop10.fetches
        clock += 95 * 3_600_000L
        assertFalse(StremioTop10.isStale(netflix)); StremioTop10.refresh(netflix); assertEquals(n, StremioTop10.fetches)
        // dokładnie 4 dni (96 h) – pobranie w tle od nowa
        clock += 3_600_000L
        assertTrue(StremioTop10.isStale(netflix)); StremioTop10.refresh(netflix); assertEquals(n + 1, StremioTop10.fetches)
        // ręczna zmiana: co 24 h
        Prefs.stremioTop10Hours = 24
        clock += 24 * 3_600_000L; assertTrue(StremioTop10.isStale(netflix))
        assertEquals(96, StremioTop10.DEFAULT_HOURS); assertEquals(4 * 24 * 60 * 60 * 1000L, 96 * 3_600_000L)
        Unit
    }

    private fun board(): StremioActivity {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { all(a.rowsBox).filterIsInstance<RecyclerView>().any { (it.adapter?.itemCount ?: 0) > 0 } }
        return a
    }

    @Test fun boardShowsRowsInstantlyFromCacheWithRankBadgesPerTab() = runBlocking {
        // zapis z poprzedniego uruchomienia; bez klucza (brak sieci) – rzędy i tak od razu z pliku
        StremioTop10.refreshStale(StremioTop10.rows(null))
        // świeży zapis – bez zapytań do sieci (klucz jest, ale rzędy nie są przeterminowane)
        synchronized(discovers) { discovers.clear() }
        val a = board(); waitFor { a.rowTitles.count { it.startsWith("Top 10") } == 10 }
        assertEquals(StremioTop10.rows(null).map { it.title }, a.rowTitles.filter { it.startsWith("Top 10") })
        assertTrue(synchronized(discovers) { discovers.isEmpty() })
        // odznaki #1–#10 w kolejności rankingu, w kolorze akcentu motywu
        val rv = all(a.rowsBox).filterIsInstance<RecyclerView>().first { (it.adapter as? StremioPosterAdapter)?.items?.firstOrNull()?.rank == 1 }
        idle(300)
        val badges = all(rv).filterIsInstance<TextView>().filter { it.getTag(pl.iptv.tv.R.id.row_key) == StremioUi.RANK_TAG && it.visibility == View.VISIBLE }
        assertEquals("#1", badges.first().text.toString())
        assertEquals((1..10).toList(), (rv.adapter as StremioPosterAdapter).items.map { it.rank })
        assertEquals(CardSheet.palette().accent or 0xFF000000.toInt(), ((badges.first().background as android.graphics.drawable.GradientDrawable).color)?.defaultColor)
        // odznaka wewnątrz plakatu (przycinana razem z nim – nic nie wystaje przy powiększeniu fokusu)
        assertTrue((badges.first().parent as View).clipToOutline || (badges.first().parent as View).outlineProvider != null)
        // Filmy – 5 rzędów filmów, Seriale – 5 rzędów seriali
        a.filterViews[StremioActivity.FILTER_MOVIES].performClick(); waitFor { a.rowTitles.count { it.startsWith("Top 10") } == 5 }
        assertTrue(a.rowTitles.filter { it.startsWith("Top 10") }.let { t -> t.size == 5 && t.all { it.startsWith("Top 10 Filmy") } })
        a.filterViews[StremioActivity.FILTER_SERIES].performClick(); waitFor { a.rowTitles.count { it.startsWith("Top 10 Seriale") } == 5 }
        assertTrue(a.rowTitles.filter { it.startsWith("Top 10") }.let { t -> t.size == 5 && t.all { it.startsWith("Top 10 Seriale") } })
        // inny motyw – odznaka w kolorach tego motywu
        classicTheme { assertEquals(CardSheet.withAlpha(Ui.accent, 0xFF), (StremioUi.rankBadge(ctx).background as android.graphics.drawable.GradientDrawable).color?.defaultColor) }
        Unit
    }

    @Test fun staleOrEmptyRowsRefreshInBackgroundOnBoard() {
        // brak zapisu – rzędy z kluczem pobierane w tle i wypełniane
        val a = board(); waitFor { a.rowTitles.count { it.startsWith("Top 10") } == 10 && StremioTop10.fetches >= 10 }
        assertEquals(10, StremioTop10.fetches)
        waitFor { all(a.rowsBox).filterIsInstance<RecyclerView>().count { (it.adapter as? StremioPosterAdapter)?.items?.firstOrNull()?.rank == 1 } == 10 }
        assertEquals(10, all(a.rowsBox).filterIsInstance<RecyclerView>().count { (it.adapter as? StremioPosterAdapter)?.items?.firstOrNull()?.rank == 1 })
    }
}

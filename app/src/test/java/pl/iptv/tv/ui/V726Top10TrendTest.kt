package pl.iptv.tv.ui

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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioTmdb
import pl.iptv.tv.data.StremioTop10
import java.util.concurrent.atomic.AtomicInteger

/**
 * 7.26 – Top 10 platform z bieżących trendów tygodnia (TMDB `/trending/…/week`) zamiast samej popularności: tytuły platformy
 * w kolejności rankingu tygodnia, uzupełnione popularnością; ranking wspólny dla platform (jedno pobranie), ważność 6 h,
 * zapas przy błędzie / pustej odpowiedzi (zapisana lista), bez trendów – ranking popularności i ponowna próba po 30 min.
 */
@RunWith(AndroidJUnit4::class)
class V726Top10TrendTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val server = MockWebServer()
    private var clock = 1_000_000_000L
    /** Trendy tygodnia (filmy): identyfikatory TMDB w kolejności; null – HTTP 500. */
    @Volatile private var trend: List<Int>? = listOf(855, 803, 840, 99999, 812)
    @Volatile private var discoverFails = false
    @Volatile private var discoverEmpty = false
    private val trendingCalls = AtomicInteger()

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                return when {
                    p.startsWith("/tmdb/trending/movie/week") -> {
                        trendingCalls.incrementAndGet()
                        val t = trend ?: return MockResponse().setResponseCode(500)
                        val page = Regex("page=(\\d+)").find(p)?.groupValues?.get(1)?.toInt() ?: 1
                        ok(JSONObject().put("results", JSONArray(if (page == 1) t.map { JSONObject().put("id", it) } else emptyList())))
                    }
                    p.startsWith("/tmdb/discover/movie") -> {
                        if (discoverFails) return MockResponse().setResponseCode(500)
                        if (discoverEmpty) return ok(JSONObject().put("results", JSONArray()))
                        val prov = Regex("with_watch_providers=([0-9]+)").find(p)!!.groupValues[1].toInt()
                        val page = Regex("page=(\\d+)").find(p)!!.groupValues[1].toInt()
                        // 3 strony po 20: platforma 8 → 801…860 w kolejności popularności
                        ok(JSONObject().put("results", JSONArray((1..20).map { i -> val id = prov * 100 + (page - 1) * 20 + i
                            JSONObject().put("id", id).put("title", "Film $id").put("poster_path", "/p$id.jpg").put("release_date", "2026-11-0${i % 9 + 1}") })))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioTmdb.base = server.url("/tmdb").toString().removeSuffix("/"); StremioTmdb.apiKey = { "test" }; StremioTmdb.clearCache()
        StremioTop10.resetForTest(ctx); StremioTop10.now = { clock }; Prefs.stremioTop10Platforms = null
        Prefs.remove("stremio_top10_h"); Prefs.language = "pl"
    }

    @After fun tearDown() {
        StremioTmdb.base = "https://api.themoviedb.org/3"; StremioTmdb.apiKey = { Prefs.tmdbApiKey }; StremioTmdb.clearCache()
        StremioTop10.resetForTest(ctx); server.shutdown()
    }

    private val netflix get() = StremioTop10.rows("movie")[0]

    @Test fun top10FollowsWeeklyTrendsThenPopularity() = runBlocking {
        val list = StremioTop10.refresh(netflix)!!
        assertEquals(10, list.size)
        // trendy tygodnia na platformie (855 z 3. strony puli, 99999 – nie na tej platformie), dalej popularność
        assertEquals(listOf(855, 803, 840, 812, 801, 802, 804, 805, 806, 807).map { "tmdb:movie:$it" }, list.map { it.id })
        assertEquals(StremioTop10.SOURCE_TREND, StremioTop10.cached(netflix)!!.source)
        // ranking tygodnia wspólny – druga platforma bez ponownego pobierania trendów
        val calls = trendingCalls.get()
        StremioTop10.refresh(StremioTop10.rows("movie")[2])
        assertEquals(calls, trendingCalls.get())
        // źródło zapisane w pliku
        assertTrue(java.io.File(ctx.cacheDir, StremioTop10.FILE).readText().contains(StremioTop10.SOURCE_TREND))
        // ważność: domyślnie 6 h
        assertEquals(6, Prefs.stremioTop10Hours)
        clock += 5 * 3_600_000L; assertFalse(StremioTop10.isStale(netflix))
        clock += 3_600_000L; assertTrue(StremioTop10.isStale(netflix))
        Unit
    }

    @Test fun withoutTrendsFallsBackToPopularityAndRetriesSoon() = runBlocking {
        trend = null
        val list = StremioTop10.refresh(netflix)!!
        assertEquals((801..810).map { "tmdb:movie:$it" }, list.map { it.id })
        assertEquals(StremioTop10.SOURCE_POPULAR, StremioTop10.cached(netflix)!!.source)
        clock += 20 * 60_000L; assertFalse(StremioTop10.isStale(netflix))
        clock += 11 * 60_000L; assertTrue("trendy – ponowna próba po 30 min", StremioTop10.isStale(netflix))
        trend = listOf(809); StremioTmdb.clearTrending()
        assertEquals("tmdb:movie:809", StremioTop10.refresh(netflix)!!.first().id)
        assertEquals(StremioTop10.SOURCE_TREND, StremioTop10.cached(netflix)!!.source)
        Unit
    }

    @Test fun networkErrorOrEmptyAnswerKeepsSavedList() = runBlocking {
        val saved = StremioTop10.refresh(netflix)!!.map { it.id }
        clock += 7 * 3_600_000L
        discoverFails = true
        assertNull(StremioTop10.refresh(netflix))
        assertEquals("błąd sieci – zapisana lista na ekranie", saved, StremioTop10.cached(netflix)!!.items.map { it.id })
        discoverFails = false; discoverEmpty = true
        assertEquals("pusta odpowiedź – zapisana lista", saved, StremioTop10.refresh(netflix)!!.map { it.id })
        assertEquals(saved, StremioTop10.cached(netflix)!!.items.map { it.id })
        Unit
    }

    @Test fun rankByTrendKeepsOrderWithoutTrends() {
        val items = (1..5).map { pl.iptv.tv.data.StremioMetaPreview("tmdb:movie:$it", "movie", "F$it", "") }
        assertEquals(items, StremioTop10.rankByTrend(items, null))
        assertEquals(listOf(4, 2, 1, 3, 5), StremioTop10.rankByTrend(items, mapOf("tmdb:movie:4" to 0, "tmdb:movie:2" to 7)).map { it.id.substringAfterLast(':').toInt() })
    }
}

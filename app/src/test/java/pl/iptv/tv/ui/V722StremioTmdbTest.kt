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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioTmdb
import pl.iptv.tv.data.StremioTop10
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 7.22 – Stremio z kluczem TMDB: wybór 10 platform Top 10 (domyślnie pięć pierwszych; przełączniki, „Zaznacz / Odznacz
 * wszystkie”, odznaczenie od razu ukrywa rzędy), najwyżej 3 zapytania naraz, w pamięci podręcznej tylko rzędy z ≥ 3 pozycjami,
 * uzupełnianie z regionu US, „Obecnie w kinach” z polskiego repertuaru TMDB (odznaka „W KINACH”, ★), IMDb po kliknięciu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V722StremioTmdbTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    private fun url(p: String) = server.url(p).toString()
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val inFlight = AtomicInteger(); private val maxInFlight = AtomicInteger()
    @Volatile private var slowMs = 0L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear(); StremioTmdb.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0; Prefs.stremioLibrary = ""
        Prefs.language = "pl"; Prefs.stremioTop10Hours = 96; Prefs.stremioTop10Platforms = null; Prefs.tmdbApiKey = "test"
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = URLDecoder.decode(r.path!!, "UTF-8"); paths += p
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                fun results(base: Int, n: Int, tv: Boolean) = ok(JSONObject().put("results", JSONArray((1..n).map { i ->
                    JSONObject().put("id", base + i).put(if (tv) "name" else "title", "Polski tytuł ${base + i}").put("poster_path", "/p${base + i}.jpg")
                        .put(if (tv) "first_air_date" else "release_date", "2026-11-0${i % 9 + 1}").put("vote_average", 7.5).put("overview", "Opis ${base + i}")
                })))
                return when {
                    p == "/m/manifest.json" -> ok(JSONObject().put("id", "meta").put("name", "Meta").put("version", "1.0.0").put("types", JSONArray(listOf("movie", "series")))
                        .put("idPrefixes", JSONArray(listOf("tt"))).put("resources", JSONArray(listOf("catalog", "meta"))).put("catalogs", JSONArray()))
                    p.startsWith("/tmdb/movie/now_playing") -> results(9000, 3, false)                            // 9001–9003
                    p.startsWith("/tmdb/discover/movie") && p.contains("with_release_type") -> results(9002, 3, false)   // 9003–9005 (9003 powtórzony)
                    p.startsWith("/tmdb/discover/") -> {
                        val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                        try {
                            if (slowMs > 0) Thread.sleep(slowMs)
                            val tv = p.startsWith("/tmdb/discover/tv")
                            val prov = Regex("with_watch_providers=([^&]+)").find(p)!!.groupValues[1].split('|').first().toInt()
                            val us = p.contains("watch_region=US")
                            // TVP VOD: w PL 2 pozycje (uzupełnienie z US); Polsat Box Go: po 1 w PL i US (rząd bez zapisu)
                            val count = when (prov) { 502 -> if (us) 6 else 2; 548 -> 1; else -> 12 }
                            results(prov * 100 + (if (tv) 50 else 0) + (if (us) 30 else 0), count, tv)
                        } finally { inFlight.decrementAndGet() }
                    }
                    p.contains("/external_ids") -> ok(JSONObject().put("imdb_id", "tt" + Regex("/(movie|tv)/(\\d+)/").find(p)!!.groupValues[2]))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioApi.defaultAddons = listOf(url("/m/manifest.json"))
        StremioApi.client = { okhttp3.OkHttpClient() }
        StremioTmdb.base = url("/tmdb").removeSuffix("/"); StremioTmdb.apiKey = { "test" }
        StremioTop10.resetForTest(ctx)
    }

    @After fun tearDown() {
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES); StremioApi.client = { pl.iptv.tv.data.Repository.http }
        StremioTmdb.base = "https://api.themoviedb.org/3"; StremioTmdb.apiKey = { Prefs.tmdbApiKey }; StremioTmdb.clearCache()
        StremioTop10.resetForTest(ctx); Prefs.stremioTop10Platforms = null; Prefs.tmdbApiKey = ""
        Prefs.stremioGuest = false; StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    @Test fun firstFivePlatformsOnByDefaultAndSelectionPersists() {
        assertEquals(10, StremioTop10.PLATFORMS.size)
        assertEquals(listOf("netflix", "max", "disney", "prime", "skyshowtime"), StremioTop10.enabledPlatforms.map { it.key })
        StremioTop10.setEnabled("tvpvod", true); StremioTop10.setEnabled("netflix", false)
        assertEquals(setOf("max", "disney", "prime", "skyshowtime", "tvpvod"), Prefs.stremioTop10Platforms)
        assertTrue(StremioTop10.rows(null).map { it.title }.containsAll(listOf("Top 10 Filmy – TVP VOD", "Top 10 Seriale – TVP VOD")))
        assertTrue(StremioTop10.rows(null).none { it.platform.key == "netflix" })
        StremioTop10.setAll(false); assertTrue(StremioTop10.rows(null).isEmpty())
        StremioTop10.setAll(true); assertEquals(20, StremioTop10.rows(null).size); assertEquals(10, StremioTop10.rows("series").size)
    }

    @Test fun minThreeItemsCachedAndPolandFilledFromUs() = runBlocking {
        val tvp = StremioTop10.RowSpec(StremioTop10.PLATFORMS.first { it.key == "tvpvod" }, "movie")
        val l = StremioTop10.refresh(tvp)!!
        // 2 z PL + uzupełnienie z US (pl-PL), PL pierwsze
        assertEquals(8, l.size); assertEquals("tmdb:movie:50201", l.first().id); assertEquals("tmdb:movie:50231", l[2].id)
        assertTrue(paths.any { it.contains("with_watch_providers=502") && it.contains("watch_region=US") && it.contains("language=pl-PL") })
        assertNotNull(StremioTop10.cached(tvp)); assertFalse(StremioTop10.isStale(tvp))
        // 1 + 1 pozycja – pokazana, ale nigdy zapisana (przy następnym otwarciu pobranie od nowa)
        val polsat = StremioTop10.RowSpec(StremioTop10.PLATFORMS.first { it.key == "polsatbox" }, "movie")
        assertEquals(2, StremioTop10.refresh(polsat)!!.size)
        assertNull(StremioTop10.cached(polsat)); assertTrue(StremioTop10.isStale(polsat))
        assertFalse(java.io.File(ctx.cacheDir, StremioTop10.FILE).readText().contains("polsatbox"))
        Unit
    }

    @Test fun atMostThreeRequestsAtOnce() = runBlocking {
        StremioTop10.setAll(true); slowMs = 150
        StremioTop10.refreshStale(StremioTop10.rows(null))
        assertTrue("równolegle: ${maxInFlight.get()}", maxInFlight.get() in 2..StremioTop10.PARALLEL_ROWS)
        assertEquals(3, StremioTop10.PARALLEL_ROWS)
        Unit
    }

    @Test fun inCinemasFromPolishRepertoireDeduplicated() = runBlocking {
        val today = java.time.LocalDate.of(2026, 11, 25)
        val l = StremioTmdb.nowInCinemas(today)!!
        assertEquals(listOf("tmdb:movie:9001", "tmdb:movie:9002", "tmdb:movie:9003", "tmdb:movie:9004", "tmdb:movie:9005"), l.map { it.id })
        assertEquals("Polski tytuł 9001", l.first().name); assertEquals("7.5", l.first().imdbRating)
        assertTrue(paths.any { it.startsWith("/tmdb/movie/now_playing") && it.contains("language=pl-PL") && it.contains("region=PL") })
        val d = paths.first { it.startsWith("/tmdb/discover/movie") && it.contains("with_release_type") }
        listOf("with_release_type=3|2", "release_date.gte=2026-10-11", "release_date.lte=2026-11-25", "sort_by=popularity.desc", "region=PL").forEach { assertTrue(d, d.contains(it)) }
        // IMDb dopiero na żądanie; tt… bez zapytania
        assertEquals("tt9001", StremioTmdb.imdbFor("tmdb:movie:9001")); assertEquals("tt1", StremioTmdb.imdbFor("tt1"))
        assertEquals(1, paths.count { it.contains("/external_ids") })
        Unit
    }

    private fun board(): StremioActivity {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { all(a.rowsBox).filterIsInstance<RecyclerView>().any { (it.adapter?.itemCount ?: 0) > 0 } }
        return a
    }

    @Test fun boardCinemaBadgeRatingAndClickOpensDetailWithImdb() {
        val a = board(); waitFor { a.rowTitles.contains(StremioActivity.IN_CINEMAS) && a.rowTitles.count { it.startsWith("Top 10") } == 10 }
        assertEquals(StremioActivity.IN_CINEMAS, a.rowTitles.first())
        val rv = all(a.rowsBox).filterIsInstance<RecyclerView>().first { (it.adapter as? StremioPosterAdapter)?.items?.firstOrNull()?.badge == StremioUi.IN_CINEMAS_BADGE }
        waitFor { all(rv).filterIsInstance<TextView>().any { it.getTag(pl.iptv.tv.R.id.row_key) == StremioUi.BADGE_TAG && it.visibility == View.VISIBLE } }
        val texts = all(rv).filterIsInstance<TextView>()
        assertTrue(texts.any { it.getTag(pl.iptv.tv.R.id.row_key) == StremioUi.BADGE_TAG && it.text == "W KINACH" && it.visibility == View.VISIBLE })
        assertTrue(texts.any { it.getTag(pl.iptv.tv.R.id.row_key) == StremioUi.RATING_TAG && it.text == "★ 7.5" && it.visibility == View.VISIBLE })
        assertTrue(texts.any { it.text == "Polski tytuł 9001" })
        // odznaka w kolorach aktywnego motywu
        val badge = texts.first { it.getTag(pl.iptv.tv.R.id.row_key) == StremioUi.BADGE_TAG }
        assertEquals(CardSheet.palette().accent or 0xFF000000.toInt(), badge.currentTextColor)
        // OK na plakacie TMDB → external_ids → ekran szczegółów z tt…
        rv.layoutManager!!.findViewByPosition(0)!!.performClick()
        waitFor { shadowOf(a).peekNextStartedActivity() != null }
        val next = shadowOf(a).nextStartedActivity
        assertEquals(StremioDetailActivity::class.java.name, next.component!!.className)
        assertEquals("tt9001", next.getStringExtra(StremioDetailActivity.EXTRA_ID))
        assertTrue(paths.any { it.startsWith("/tmdb/movie/9001/external_ids") })
        // Top 10 – to samo (serial: /tv/{id}/external_ids)
        val series = all(a.rowsBox).filterIsInstance<RecyclerView>().first { (it.adapter as? StremioPosterAdapter)?.items?.firstOrNull()?.meta?.type == "series" && it.adapter!!.itemCount > 0 }
        series.layoutManager!!.findViewByPosition(0)!!.performClick()
        waitFor { shadowOf(a).peekNextStartedActivity() != null }
        assertTrue(shadowOf(a).nextStartedActivity.getStringExtra(StremioDetailActivity.EXTRA_ID)!!.startsWith("tt"))
        assertTrue(paths.any { Regex("^/tmdb/tv/\\d+/external_ids").containsMatchIn(it) })
    }

    @Test fun keyDialogListsPlatformsAndUncheckingHidesRowsAtOnce() {
        val a = board(); waitFor { a.rowTitles.count { it.startsWith("Top 10") } == 10 }
        TmdbKeyUi.show(a, onTop10Changed = { a.applyTop10Selection() }) {}; idle()
        val d = ShadowDialog.getLatestDialog()
        val root = d.window!!.decorView
        assertTrue(all(root).filterIsInstance<TextView>().any { it.text == TmdbKeyUi.PLATFORMS_TITLE })
        val cards = all(root).filter { (it.tag as? String)?.startsWith(TmdbKeyUi.PLATFORM_TAG) == true }
        assertEquals(10, cards.size)
        assertEquals("Netflix: włączone", cards.first().contentDescription); assertEquals("Apple TV+: wyłączone", cards[5].contentDescription)
        cards.first().performClick(); idle()
        assertEquals("Netflix: wyłączone", cards.first().contentDescription)
        assertTrue(a.rowTitles.none { it.endsWith("– Netflix") }); assertEquals(8, a.rowTitles.count { it.startsWith("Top 10") })
        all(root).first { it.tag == "keepcolor top10_none" }.performClick(); idle()
        assertTrue(a.rowTitles.none { it.startsWith("Top 10") }); assertTrue(Prefs.stremioTop10Platforms!!.isEmpty())
        all(root).first { it.tag == "keepcolor top10_all" }.performClick(); idle()
        assertEquals(10, Prefs.stremioTop10Platforms!!.size); assertTrue(cards.all { it.contentDescription.endsWith("włączone") })
        d.dismiss()
    }

    // ---------------- (7.22) telefon pionowo ----------------

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitBoardHeroRowsAndWatchlistTab() {
        Prefs.orientation = 2
        try {
            val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
            waitFor { a.heroMeta != null && a.rowTitles.count { it.startsWith("Top 10") } == 10 }
            // karta wyróżnionego tytułu 16:10 u góry (pierwszy rząd – Obecnie w kinach), przyciski Odtwórz / + Do obejrzenia
            val hero = all(a.rowsBox).first { it.tag == StremioActivity.HERO_TAG }
            assertSame(hero, a.rowsBox.getChildAt(0))
            val w = a.resources.displayMetrics.widthPixels - pl.iptv.tv.ui.Ui.dp(24)
            assertEquals(w * 10 / 16, hero.layoutParams.height)
            assertEquals("tmdb:movie:9001", a.heroMeta!!.id)
            val texts = all(hero).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("Polski tytuł 9001", StremioActivity.HERO_PLAY, StremioActivity.HERO_WATCHLIST)))
            assertTrue(texts.any { it.startsWith("★ 7.5") })
            // „+ Do obejrzenia” – IMDb rozwiązany, tytuł na liście
            all(hero).first { it.tag == "keepcolor hero_watchlist" }.performClick(); waitFor { pl.iptv.tv.data.StremioLibrary.contains("tt9001") }
            assertTrue(pl.iptv.tv.data.StremioLibrary.contains("tt9001"))
            // pionowa kolumna rzędów: najpierw kina, potem Top 10 włączonych platform
            assertEquals(StremioActivity.IN_CINEMAS, a.rowTitles.first())
            // dolny pasek: Stremio zaznaczone; „Do obejrzenia” – Biblioteka na tym samym ekranie, z dodanym tytułem
            val nav = a.portraitNav!!
            assertTrue(nav.getChildAt(1).isSelected)
            nav.getChildAt(3).performClick(); idle()
            assertEquals(StremioActivity.FILTER_LIBRARY, a.filter); assertTrue(nav.getChildAt(3).isSelected); assertFalse(nav.getChildAt(1).isSelected)
            waitFor { a.rowTitles.contains(StremioActivity.LIBRARY_ROW) }
            assertTrue(a.rowTitles.contains(StremioActivity.LIBRARY_ROW))
            // powrót na Odkrywaj – rząd „Do obejrzenia” także tutaj (pion)
            nav.getChildAt(1).performClick(); waitFor { a.rowTitles.contains(StremioActivity.LIBRARY_ROW) && a.rowTitles.contains(StremioActivity.IN_CINEMAS) }
            assertTrue(a.rowTitles.contains(StremioActivity.LIBRARY_ROW))
            assertEquals(4, a.filterViews.size)                                   // pigułki filtrów we wszystkich motywach
        } finally { Prefs.orientation = 0; Prefs.stremioLibrary = "" }
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitDetailBannerAndSourcesColumn() {
        Prefs.orientation = 2
        try {
            val i = StremioDetailActivity.intent(ctx, pl.iptv.tv.data.StremioMetaPreview("tt9001", "movie", "Polski tytuł 9001", ""), null)
            val a = Robolectric.buildActivity(StremioDetailActivity::class.java, i).setup().get(); idle(500)
            val root = a.window.decorView
            val banner = all(root).first { it.tag == "portrait_banner" }
            assertTrue(banner.layoutParams.height >= a.resources.displayMetrics.widthPixels * 9 / 16)
            // jedna przewijana kolumna: baner, tytuł, przyciski, źródła (bez kolumn TV)
            assertTrue(all(root).any { it is androidx.core.widget.NestedScrollView })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "Źródła" })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "Polski tytuł 9001" })
        } finally { Prefs.orientation = 0 }
    }

    @Test fun withoutKeyNoPlatformListAndCinemetaFallback() {
        Prefs.tmdbApiKey = ""; StremioTmdb.apiKey = { Prefs.tmdbApiKey }
        assertNull(runBlocking { StremioTmdb.nowInCinemas() })
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get(); idle(800)
        TmdbKeyUi.show(a) {}; idle()
        assertTrue(all(ShadowDialog.getLatestDialog().window!!.decorView).none { (it.tag as? String)?.startsWith(TmdbKeyUi.PLATFORM_TAG) == true })
        // bez klucza i bez katalogu „year” (Cinemeta) – brak rzędu kin, brak zapytań TMDB
        assertFalse(a.rowTitles.contains(StremioActivity.IN_CINEMAS)); assertTrue(paths.none { it.startsWith("/tmdb/") })
    }
}

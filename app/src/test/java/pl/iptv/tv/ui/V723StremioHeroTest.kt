package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
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
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration

/**
 * 7.23 – Stremio na telefonie w pionie: karta banerowa u góry Odkrywaj = „Kontynuuj oglądanie” (karuzela ‹ ›, „▶ Wznów” od razu
 * wznawia; pusta historia – bez karty), rząd „Kontynuuj oglądanie” nie pojawia się na Odkrywaj (pod „Obecnie w kinach”), w Filmach
 * ani w Serialach; Filmy / Seriale – w karcie propozycje danego typu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V723StremioHeroTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    private fun url(p: String) = server.url(p).toString()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0; Prefs.stremioLibrary = ""
        Prefs.language = "pl"; Prefs.tmdbApiKey = ""; Prefs.orientation = 2
        pl.iptv.tv.data.StremioTmdb.apiKey = { "" }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                fun metas(type: String, prefix: String) = ok(JSONObject().put("metas", JSONArray((1..6).map { i ->
                    JSONObject().put("id", "tt$prefix$i").put("type", type).put("name", "$prefix $i").put("poster", "").put("imdbRating", "7.$i").put("releaseInfo", "2025")
                })))
                return when {
                    p == "/m/manifest.json" -> ok(JSONObject().put("id", "meta").put("name", "Meta").put("version", "1.0.0").put("types", JSONArray(listOf("movie", "series")))
                        .put("idPrefixes", JSONArray(listOf("tt"))).put("resources", JSONArray(listOf("catalog", "meta")))
                        .put("catalogs", JSONArray(listOf(JSONObject().put("type", "movie").put("id", "top").put("name", "Popularne"),
                            JSONObject().put("type", "series").put("id", "top").put("name", "Popularne")))))
                    p.startsWith("/m/catalog/movie/") -> metas("movie", "Film")
                    p.startsWith("/m/catalog/series/") -> metas("series", "Serial")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioApi.defaultAddons = listOf(url("/m/manifest.json"))
        StremioApi.client = { okhttp3.OkHttpClient() }
    }

    @After fun tearDown() {
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES); StremioApi.client = { pl.iptv.tv.data.Repository.http }
        pl.iptv.tv.data.StremioTmdb.apiKey = { Prefs.tmdbApiKey }
        Prefs.orientation = 0; Prefs.stremioGuest = false; StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun hero(a: StremioActivity) = all(a.rowsBox).firstOrNull { it.tag == StremioActivity.HERO_TAG }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun discoverHeroIsContinueWatchingCarousel() {
        runBlocking {
            StremioStore.saveProgress(StremioProgress("ttA", "series", "Serial A", "", "ttA:1:2", "S1E2", 600_000, 2_400_000, updatedAt = 3))
            StremioStore.saveProgress(StremioProgress("ttB", "movie", "Film B", positionMs = 1_800_000, durationMs = 6_000_000, updatedAt = 2))
            StremioStore.saveProgress(StremioProgress("ttDone", "movie", "Obejrzany", positionMs = 5_900_000, durationMs = 6_000_000, updatedAt = 1))
        }
        val c = Robolectric.buildActivity(StremioActivity::class.java).setup(); val a = c.get()
        waitFor { a.heroMeta != null && a.rowTitles.isNotEmpty() }
        assertEquals(StremioActivity.FILTER_ALL, a.filter)
        // karta u góry: najnowsza rozpoczęta pozycja (bez obejrzanych do końca), wznowienie i postęp
        val h = hero(a)!!
        assertSame(h, a.rowsBox.getChildAt(0))
        assertEquals(listOf("ttA", "ttB"), a.heroItems.map { it.tile.meta.id })
        assertEquals("ttA", a.heroMeta!!.id)
        assertTrue(texts(h).containsAll(listOf("Serial A", StremioActivity.HERO_RESUME, StremioActivity.HERO_WATCHLIST, StremioActivity.HERO_CONTINUE_LABEL, "1 / 2")))
        assertTrue(texts(h).any { it.startsWith("S1E2") && it.endsWith("25%") })
        assertEquals(View.VISIBLE, all(h).first { it.tag == "hero_progress" }.visibility)
        // przełączanie ‹ ›
        all(h).first { it.tag == "keepcolor hero_next" }.performClick(); idle()
        assertEquals("ttB", a.heroMeta!!.id); assertTrue(texts(h).contains("2 / 2"))
        all(h).first { it.tag == "keepcolor hero_next" }.performClick(); idle()
        assertEquals("ttA", a.heroMeta!!.id)
        // „▶ Wznów” – od razu odtwarzacz (wznowienie), bez ekranu szczegółów
        generateSequence { shadowOf(a).nextStartedActivity }.toList()
        all(h).first { it.tag == "keepcolor hero_play" }.performClick(); idle()
        assertEquals(StremioPlayerActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        // bez rzędu „Kontynuuj oglądanie” (pod „Obecnie w kinach” ani gdziekolwiek na Odkrywaj)
        assertFalse(a.rowTitles.contains(StremioActivity.CONTINUE_ROW))
        // Filmy / Seriale – bez rzędu, w karcie propozycje danego typu
        a.setFilter(StremioActivity.FILTER_MOVIES); waitFor { a.heroMeta != null && a.rowTitles.isNotEmpty() }
        assertFalse(a.rowTitles.contains(StremioActivity.CONTINUE_ROW))
        assertTrue(a.heroItems.isNotEmpty()); assertTrue(a.heroItems.all { it.tile.meta.type == "movie" && it.progress == null })
        assertTrue(texts(hero(a)!!).containsAll(listOf(StremioActivity.HERO_SUGGEST_MOVIES, StremioActivity.HERO_PLAY)))
        a.setFilter(StremioActivity.FILTER_SERIES); waitFor { a.heroMeta?.type == "series" }
        assertFalse(a.rowTitles.contains(StremioActivity.CONTINUE_ROW))
        assertTrue(a.heroItems.all { it.tile.meta.type == "series" })
        assertTrue(texts(hero(a)!!).contains(StremioActivity.HERO_SUGGEST_SERIES))
        // Biblioteka – historia jak dotąd w rzędzie, bez karty
        a.setFilter(StremioActivity.FILTER_LIBRARY); waitFor { a.rowTitles.contains(StremioActivity.CONTINUE_ROW) }
        assertTrue(a.rowTitles.contains(StremioActivity.CONTINUE_ROW)); assertNull(hero(a))
        c.pause().stop().destroy()
    }

    @Test fun discoverWithoutHistoryHasNoHeroCard() {
        val c = Robolectric.buildActivity(StremioActivity::class.java).setup(); val a = c.get()
        waitFor { a.rowTitles.isNotEmpty() }; idle(500)
        assertNull(a.heroMeta); assertNull(hero(a))
        assertFalse(a.rowTitles.contains(StremioActivity.CONTINUE_ROW))
        c.pause().stop().destroy()
    }
}

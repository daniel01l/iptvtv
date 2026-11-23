package pl.iptv.tv.ui

import android.content.Context
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
import org.robolectric.annotation.Config
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMeta
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioVideo
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.LocalDate

/** 7.12: sezon / tytuł jako obejrzany, ukryte katalogi usługowych wtyczek, „Obecnie w kinach”. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V712StremioTest {
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return if (p.startsWith("/c/catalog/movie/year/genre=")) {
                    val today = LocalDate.now()
                    MockResponse().setBody(JSONObject().put("metas", JSONArray(listOf(
                        JSONObject().put("id", "tt10").put("type", "movie").put("name", "Nowy").put("releaseInfo", today.year.toString()).put("released", today.minusDays(20).toString() + "T00:00:00.000Z"),
                        JSONObject().put("id", "tt11").put("type", "movie").put("name", "Stary").put("releaseInfo", today.year.toString()).put("released", today.minusDays(300).toString() + "T00:00:00.000Z"),
                        JSONObject().put("id", "tt12").put("type", "movie").put("name", "Przyszły").put("releaseInfo", today.year.toString()).put("released", today.plusDays(60).toString() + "T00:00:00.000Z"),
                    ))).toString())
                } else MockResponse().setResponseCode(404)
            }
        } }
        server.start()
    }
    @After fun tearDown() { server.shutdown(); StubeHistory.useDatabase(null); db.close() }

    private val meta = StremioMeta("tt1", "series", "Serial", videos = (1..3).map { StremioVideo("tt1:1:$it", "Odc $it", 1, it) } + (1..2).map { StremioVideo("tt1:2:$it", "S2 $it", 2, it) } + StremioVideo("tt1:0:1", "Extra", 0, 1))

    @Test fun wholeSeasonAndWholeTitleCanBeMarkedWatchedAndUnmarked() = runBlocking {
        StremioStore.setSeasonWatched(meta, 1, "", true)
        var w = StremioStore.watchedIds("tt1")
        assertEquals(setOf("tt1:1:1", "tt1:1:2", "tt1:1:3"), w)
        assertEquals(100, StremioStore.seasonPercent(meta, 1, w)); assertEquals(0, StremioStore.seasonPercent(meta, 2, w))
        StremioStore.setSeasonWatched(meta, 1, "", false)
        assertTrue(StremioStore.watchedIds("tt1").isEmpty())
        // cały serial: wszystkie sezony poza dodatkami
        val s = StremioMetaPreview("tt1", "series", "Serial")
        assertFalse(StremioStore.isTitleWatched(s, meta))
        StremioStore.setTitleWatched(s, meta, true)
        assertEquals(5, StremioStore.watchedIds("tt1").size)
        assertTrue(StremioStore.isTitleWatched(s, meta))
        StremioStore.setTitleWatched(s, meta, false)
        assertTrue(StremioStore.watchedIds("tt1").isEmpty())
        // film: ukończony postęp, znika z „Kontynuuj”, wraca po odznaczeniu
        val f = StremioMetaPreview("tt9", "movie", "Film")
        StremioStore.setTitleWatched(f, null, true)
        assertTrue(StremioStore.isTitleWatched(f, null)); assertTrue(StremioStore.progressOf("tt9")!!.finished)
        StremioStore.setTitleWatched(f, null, false)
        assertFalse(StremioStore.isTitleWatched(f, null)); assertNull(StremioStore.progressOf("tt9"))
    }

    @Test fun serviceAddonCatalogsAreHidden() {
        val re = StremioActivity.HIDDEN_CATALOGS
        listOf("AIOStreams ElfHosted · Store RD", "ElfHosted Store PM", "AIOStreams", "Store RD", "store_pm", "Torrentio", "Torrentio RD", "RealDebrid", "real-debrid").forEach { assertTrue(it, re.containsMatchIn(it)) }
        // (7.17) Torrentio i debrid – też ukryte
        listOf("Cinemeta Popularne", "Cyberflix Filmy", "Nagrody Wordpress").forEach { assertFalse(it, re.containsMatchIn(it)) }
    }

    @Test fun inCinemasKeepsOnlyRecentReleases() = runBlocking {
        val a = StremioAddon(server.url("/c/manifest.json").toString(), "cin", "Cinemeta", catalogs = listOf(StremioAddon.Catalog("movie", "year", "Rok", listOf("genre"), listOf("genre"))))
        val l = StremioApi.inCinemas(a)
        assertEquals(listOf("Nowy"), l.map { it.name })
    }
}

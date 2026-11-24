package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
import pl.iptv.tv.data.StremioLibrary
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioTmdb
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration

/**
 * 7.20 – Stremio: inne części filmu (kolekcja TMDB) i ikona widoczna tylko, gdy są; kolejność ikon odtwarzacza (Inne części,
 * Audio, Napisy, …) bez dziury w fokusie; znaczniki „Obejrzano” i postęp w panelu; polskie opisy (pl-PL) z powrotem do
 * angielskiego; „Do obejrzenia” (szczegóły, menu po przytrzymaniu OK, rząd w Bibliotece); zwiastun zamykany całkowicie.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V720StremioTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    private fun url(p: String) = server.url(p).toString()
    private val tmdbCalls = mutableListOf<String>()

    private val manifest get() = JSONObject().put("id", "meta").put("name", "Meta").put("version", "1.0.0")
        .put("types", JSONArray(listOf("movie", "series"))).put("idPrefixes", JSONArray(listOf("tt"))).put("resources", JSONArray(listOf("catalog", "meta")))
        .put("catalogs", JSONArray().put(JSONObject().put("type", "movie").put("id", "top").put("name", "Popularne")))

    private fun movieMeta(id: String, name: String, year: String) = JSONObject().put("meta", JSONObject().put("id", id).put("type", "movie").put("name", name)
        .put("description", "English plot of $name").put("releaseInfo", year).put("genres", JSONArray(listOf("Animation")))
        .put("trailers", JSONArray().put(JSONObject().put("source", "trl$id").put("type", "Trailer"))))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear(); StremioTmdb.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0; Prefs.stremioLibrary = ""
        Prefs.language = "pl"
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                if (p.startsWith("/tmdb/")) synchronized(tmdbCalls) { tmdbCalls += p }
                return when {
                    p == "/m/manifest.json" -> ok(manifest)
                    p.startsWith("/m/catalog/movie/top") -> ok(JSONObject().put("metas", JSONArray(listOf("ttshrek1" to "Shrek", "ttsolo" to "Samotny film").map { (id, n) ->
                        JSONObject().put("id", id).put("type", "movie").put("name", n).put("poster", "").put("releaseInfo", "2001").put("description", "English plot of $n") })))
                    p.startsWith("/m/meta/movie/ttshrek1") -> ok(movieMeta("ttshrek1", "Shrek", "2001"))
                    p.startsWith("/m/meta/movie/ttshrek2") -> ok(movieMeta("ttshrek2", "Shrek 2", "2004"))
                    p.startsWith("/m/meta/movie/ttsolo") -> ok(movieMeta("ttsolo", "Samotny film", "2019"))
                    // TMDB (pl-PL): Shrek w kolekcji, „Samotny film” bez polskiego opisu i poza kolekcją
                    p.startsWith("/tmdb/find/ttshrek1") -> ok(JSONObject().put("movie_results", JSONArray().put(JSONObject().put("id", 808))))
                    p.startsWith("/tmdb/find/ttshrek2") -> ok(JSONObject().put("movie_results", JSONArray().put(JSONObject().put("id", 809))))
                    p.startsWith("/tmdb/find/ttsolo") -> ok(JSONObject().put("movie_results", JSONArray().put(JSONObject().put("id", 900))))
                    p.startsWith("/tmdb/movie/808/external_ids") -> ok(JSONObject().put("imdb_id", "ttshrek1"))
                    p.startsWith("/tmdb/movie/809/external_ids") -> ok(JSONObject().put("imdb_id", "ttshrek2"))
                    p.startsWith("/tmdb/movie/810/external_ids") -> ok(JSONObject().put("imdb_id", "ttshrek3"))
                    p.startsWith("/tmdb/movie/808") -> ok(JSONObject().put("title", "Shrek").put("overview", "Polski opis Shreka").put("genres", JSONArray().put(JSONObject().put("name", "Animacja")))
                        .put("belongs_to_collection", JSONObject().put("id", 2150)))
                    p.startsWith("/tmdb/movie/809") -> ok(JSONObject().put("title", "Shrek 2").put("overview", "Polski opis Shreka 2").put("belongs_to_collection", JSONObject().put("id", 2150)))
                    p.startsWith("/tmdb/movie/900") -> ok(JSONObject().put("title", "").put("overview", "").put("genres", JSONArray()))
                    p.startsWith("/tmdb/collection/2150") -> ok(JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("id", 810).put("title", "Shrek Trzeci").put("release_date", "2007-05-17"))
                        .put(JSONObject().put("id", 808).put("title", "Shrek").put("release_date", "2001-05-18"))
                        .put(JSONObject().put("id", 809).put("title", "Shrek 2").put("release_date", "2004-05-19"))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioApi.defaultAddons = listOf(url("/m/manifest.json"))
        StremioApi.client = { okhttp3.OkHttpClient() }
        StremioTmdb.base = url("/tmdb").removeSuffix("/"); StremioTmdb.apiKey = { "test" }
    }

    @After fun tearDown() {
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES); StremioApi.client = { pl.iptv.tv.data.Repository.http }
        StremioTmdb.base = "https://api.themoviedb.org/3"; StremioTmdb.apiKey = { pl.iptv.tv.BuildConfig.TMDB_API_KEY }; StremioTmdb.clearCache()
        Prefs.stremioGuest = false; Prefs.stremioLibrary = ""; Prefs.language = "pl"
        StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close(); StremioMetas.clear()
    }

    @Test fun polishMetadataWithEnglishFallbackAndCollectionPartsInOrder() = runBlocking {
        StremioStore.refreshAddons()
        // polski opis, tytuł i gatunki z TMDB pl-PL
        val shrek = StremioMetas.get("movie", "ttshrek1")!!
        assertEquals("Polski opis Shreka", shrek.description); assertEquals(listOf("Animacja"), shrek.genres)
        assertTrue(synchronized(tmdbCalls) { tmdbCalls.any { it.contains("language=pl-PL") } })
        // tytuł bez polskiego tłumaczenia – zostaje angielski
        val solo = StremioMetas.get("movie", "ttsolo")!!
        assertEquals("English plot of Samotny film", solo.description); assertEquals("Samotny film", solo.name)
        // kolekcja: wszystkie części z bieżącą, od najstarszej premiery
        val parts = StremioMetas.relatedParts(StremioMetaPreview("ttshrek1", "movie", "Shrek", releaseInfo = "2001"))
        assertEquals(listOf("ttshrek1", "ttshrek2", "ttshrek3"), parts.map { it.id })
        assertTrue(StremioMetas.hasOtherParts(false, 0, parts.size))
        // pojedynczy film (poza kolekcją, wtyczka bez wyszukiwania) – tylko on, ikona ukryta
        val single = StremioMetas.relatedParts(StremioMetaPreview("ttsolo", "movie", "Samotny film"))
        assertEquals(listOf("ttsolo"), single.map { it.id }); assertFalse(StremioMetas.hasOtherParts(false, 0, single.size))
        assertTrue(StremioMetas.hasOtherParts(true, 5, 0)); assertFalse(StremioMetas.hasOtherParts(true, 1, 0))
        // aplikacja po angielsku – bez TMDB pl-PL, dane wtyczki
        Prefs.language = "en"; StremioMetas.clear(); synchronized(tmdbCalls) { tmdbCalls.clear() }
        assertEquals("en-US", StremioTmdb.language())
        assertFalse(synchronized(tmdbCalls) { tmdbCalls.any { it.contains("pl-PL") } })
        Unit
    }

    private fun player(id: String, name: String) = Robolectric.buildActivity(StremioPlayerActivity::class.java,
        StremioPlayerActivity.intent(ctx, "movie", id, name, "", id, name, StremioStream("Meta", url("/m/manifest.json"), url = "http://127.0.0.1:9/$id.mp4"))).setup()

    private fun icons(p: StremioPlayerActivity) = all(p.window.decorView).filter { it.getTag(pl.iptv.tv.R.id.row_key) == "icon" && it.contentDescription != null }

    @Test fun playerIconOrderHidesOtherPartsForSingleFilmAndMarksWatchedParts() = runBlocking {
        StremioStore.refreshAddons()
        // film w kolekcji: ikona widoczna, kolejność od lewej: Inne części, Audio, Napisy, dalej pozostałe
        val c = player("ttshrek1", "Shrek"); val p = c.get()
        waitFor { p.partsBtn.visibility == View.VISIBLE }
        assertEquals(View.VISIBLE, p.partsBtn.visibility)
        val bar = p.partsBtn.parent as android.view.ViewGroup          // pasek ikon funkcji (położenie bez zmian)
        val visible = (0 until bar.childCount).map { bar.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.map { it.contentDescription.toString() }
        assertEquals(listOf("Inne części", "Ścieżka dźwięku", "Napisy"), visible.take(3))
        assertTrue(visible.indexOf("Opóźnienie napisów") > visible.indexOf("Napisy"))
        assertSame(p.partsBtn, p.firstIcon())
        // obejrzana część (postęp ≥ progu) – „Obejrzano”, rozpoczęta – pasek postępu
        StremioStore.saveProgress(StremioProgress("ttshrek2", "movie", "Shrek 2", positionMs = 95_000, durationMs = 100_000, updatedAt = 1))
        StremioStore.saveProgress(StremioProgress("ttshrek3", "movie", "Shrek Trzeci", positionMs = 30_000, durationMs = 100_000, updatedAt = 2))
        assertTrue(p.watchState("ttshrek2", emptySet(), StremioStore.progressOf("ttshrek2")).watched)
        p.watchState("ttshrek3", emptySet(), StremioStore.progressOf("ttshrek3")).let { assertFalse(it.watched); assertEquals(0.3f, it.progress!!, 0.01f) }
        p.partsBtn.performClick(); waitFor { all(p.window.decorView).any { it.contentDescription == "Shrek 2, obejrzano" } }
        val rows = all(p.window.decorView).mapNotNull { it.contentDescription?.toString() }
        assertTrue(rows.toString(), "Shrek 2, obejrzano" in rows && "Shrek Trzeci" in rows)
        val mark = all(p.window.decorView).filterIsInstance<android.widget.TextView>().first { it.getTag(pl.iptv.tv.R.id.row_key) == WatchedMark.TAG && it.visibility == View.VISIBLE }
        assertEquals("Obejrzano", mark.text.toString()); assertEquals(CardSheet.palette().accent, mark.textColors.defaultColor)
        c.pause().stop().destroy()
        // pojedynczy film: ikona całkowicie ukryta, fokus ▲ od razu na „Audio”
        val c2 = player("ttsolo", "Samotny film"); val p2 = c2.get()
        waitFor { synchronized(tmdbCalls) { tmdbCalls.any { it.startsWith("/tmdb/movie/900") } } }; idle(800)
        assertEquals(View.GONE, p2.partsBtn.visibility)
        assertEquals("Ścieżka dźwięku", p2.firstIcon()!!.contentDescription)
        p2.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)); idle()
        assertSame(p2.firstIcon(), p2.window.decorView.findFocus())
        c2.pause().stop().destroy(); Unit
    }

    private fun board(): StremioActivity {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { recyclers(a.rowsBox).any { (it.adapter?.itemCount ?: 0) > 0 } }
        return a
    }
    private fun recyclers(v: View): List<RecyclerView> = all(v).filterIsInstance<RecyclerView>()

    @Test fun watchlistFromDetailsHoldOkMenuAndLibrarySection() {
        val a = board(); idle(500)
        // przytrzymanie OK na plakacie – menu kart: „Do obejrzenia”, „Zwiastun” (metadane mają zwiastun), obejrzany
        val poster = recyclers(a.rowsBox).first { (it.adapter?.itemCount ?: 0) > 0 }.getChildAt(0)
        poster.performLongClick(); waitFor { CardSheet.shownActions != null }
        val menu = CardSheet.shownActions!!
        val keys = all(menu.window!!.decorView).mapNotNull { (it.tag as? String)?.takeIf { t -> t.startsWith("action:") } }
        assertEquals(listOf("action:watchlist", "action:trailer", "action:watched"), keys)
        assertEquals(StremioDetailActivity.WATCHLIST_ADD, all(menu.window!!.decorView).first { it.tag == "action:watchlist" }.contentDescription)
        all(menu.window!!.decorView).first { it.tag == "action:watchlist" }.performClick(); idle()
        assertTrue(StremioLibrary.contains("ttshrek1")); assertFalse(menu.isShowing)
        // ponownie – „Usuń z listy do obejrzenia”
        poster.performLongClick(); waitFor { CardSheet.shownActions != null }
        assertEquals(StremioActivity.WATCHLIST_REMOVE, all(CardSheet.shownActions!!.window!!.decorView).first { it.tag == "action:watchlist" }.contentDescription)
        CardSheet.shownActions!!.dismiss(); idle()
        // Biblioteka: rząd „Do obejrzenia”
        a.filterViews[StremioActivity.FILTER_LIBRARY].performClick()
        waitFor { StremioActivity.LIBRARY_ROW in a.rowTitles }
        assertEquals("Do obejrzenia", StremioActivity.LIBRARY_ROW); assertTrue(a.rowTitles.toString(), StremioActivity.LIBRARY_ROW in a.rowTitles)
        // szczegóły (każdy motyw): przycisk przełączający „Do obejrzenia” ↔ „✓ Na liście do obejrzenia”
        classicTheme {
            val d = Robolectric.buildActivity(StremioDetailActivity::class.java, StremioDetailActivity.intent(ctx, StremioMetaPreview("ttsolo", "movie", "Samotny film"))).setup().get()
            waitFor { d.libraryBtn != null }
            assertEquals(StremioDetailActivity.WATCHLIST_ADD, d.libraryBtn!!.text.toString())
            d.libraryBtn!!.performClick(); idle()
            assertTrue(StremioLibrary.contains("ttsolo")); assertEquals(StremioDetailActivity.WATCHLIST_ADDED, d.libraryBtn!!.text.toString())
        }
    }

    @Test fun stremioTrailerClosesCompletelyAndNeverGoesToPip() {
        Prefs.stubeInternal = true
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        StremioTrailer.play(act, "Shrek", "2001", "trlShrek")
        val i = shadowOf(act).nextStartedActivity
        assertEquals(YtPlayerActivity::class.java.name, i.component!!.className)
        assertTrue(i.getBooleanExtra(YtPlayerActivity.EXTRA_TRAILER, false))
        assertEquals("trlShrek", i.getStringExtra(YtPlayerActivity.EXTRA_ID))
        // zwykły film STube – bez trybu zwiastuna (mini-odtwarzacz / PiP jak dotąd)
        assertFalse(YtPlayerActivity.intent(ctx, pl.iptv.tv.data.YtVideo("x", "t", "c")).getBooleanExtra(YtPlayerActivity.EXTRA_TRAILER, false))
        // wyjście z odtwarzacza zwiastuna: silnik zatrzymany i zwolniony, ekran zamknięty, bez okna PiP
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, i).setup(); idle(300)
        val y = c.get()
        assertTrue(y.trailerMode); assertFalse(y.enterPip())
        c.pause().stop(); idle()
        assertTrue(y.isFinishing); assertFalse(YtEngine.active); assertNull(YtEngine.video)
        c.destroy()
    }
}

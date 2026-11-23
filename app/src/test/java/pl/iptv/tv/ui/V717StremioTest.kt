package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration

/**
 * (7.17) Stremio w stylu „Obsidian Stream”: pasek nawigacji, pigułki filtrów (Filmy / Seriale / Odkrywaj / Biblioteka),
 * plakaty 2:3 z oceną, tytułem i rokiem, kinowy ekran szczegółów z CTA i biblioteką – oraz nawigacja pilotem bez gubienia fokusu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V717StremioTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    /**
     * Klawisz pilota; strzałki nieobsłużone przez ekran – kierunkowe przejście fokusu jak w systemie (w Robolectric nie ma
     * `ViewRootImpl`, który robi to na urządzeniu).
     */
    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        val handled = a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); a.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
        val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> 0 }
        if (!handled && dir != 0) a.window.decorView.findFocus()?.focusSearch(dir)?.requestFocus(dir)
        idle(300)
    }
    private fun focus(a: android.app.Activity) = a.window.decorView.findFocus()
    private fun inside(v: View?, p: View): Boolean { var x = v; while (x != null) { if (x === p) return true; x = x.parent as? View }; return false }
    private fun recyclers(v: View, out: MutableList<RecyclerView> = mutableListOf()): List<RecyclerView> {
        if (v is RecyclerView) out += v
        if (v is android.view.ViewGroup) (0 until v.childCount).forEach { recyclers(v.getChildAt(it), out) }
        return out
    }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    private fun url(p: String) = server.url(p).toString()

    private val manifest get() = JSONObject().put("id", "meta").put("name", "Meta").put("version", "1.0.0")
        .put("types", JSONArray(listOf("movie", "series"))).put("idPrefixes", JSONArray(listOf("tt"))).put("resources", JSONArray(listOf("catalog", "meta")))
        .put("catalogs", JSONArray().put(JSONObject().put("type", "movie").put("id", "top").put("name", "Popularne"))
            .put(JSONObject().put("type", "series").put("id", "top").put("name", "Popularne")))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0; Prefs.stremioLibrary = ""
        Ui.applyPreset("classic"); Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                fun metas(type: String, n: Int) = ok(JSONObject().put("metas", JSONArray((1..n).map { i ->
                    JSONObject().put("id", "tt${type.take(1)}$i").put("type", type).put("name", "${if (type == "movie") "Film" else "Serial"} $i")
                        .put("poster", "").put("releaseInfo", "20${10 + i}").put("imdbRating", if (i == 1) "8.4" else "")
                })))
                return when {
                    p == "/m/manifest.json" -> ok(manifest)
                    p.startsWith("/m/catalog/movie/top") -> metas("movie", 6)
                    p.startsWith("/m/catalog/series/top") -> metas("series", 4)
                    p.startsWith("/m/meta/movie/ttm1") -> ok(JSONObject().put("meta", JSONObject().put("id", "ttm1").put("type", "movie").put("name", "Film 1")
                        .put("description", "Opis filmu ".repeat(40)).put("releaseInfo", "2011").put("runtime", "2h 5min").put("imdbRating", "8.4")
                        .put("genres", JSONArray(listOf("Dramat", "Thriller"))).put("trailers", JSONArray().put(JSONObject().put("source", "dQw4w9WgXcQ").put("type", "Trailer")))))
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
        Prefs.stremioGuest = false; Prefs.stremioLibrary = ""
        StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun board(): StremioActivity {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { a.rowTitles.any { it.contains("filmy") } && recyclers(a.rowsBox).any { (it.adapter?.itemCount ?: 0) > 0 } && focus(a) != null }
        return a
    }

    @Test fun filtersPostersAndRailNavigation() {
        val a = board()
        // (7.17) zakładki: Odkrywaj (pierwsza, wybrana, z kursorem startowym), Filmy, Seriale, Biblioteka
        assertEquals(listOf("Odkrywaj", "Filmy", "Seriale", "Biblioteka"), a.filterViews.map { it.text.toString() })
        assertEquals(0, StremioActivity.FILTER_ALL)
        assertTrue(a.filterViews[StremioActivity.FILTER_ALL].isSelected)
        idle(600)
        assertSame("kursor startowy na „Odkrywaj”", a.filterViews[StremioActivity.FILTER_ALL], focus(a))
        // historia i postęp tylko w Bibliotece – Odkrywaj zaczyna się od katalogów
        assertFalse(a.rowTitles.toString(), a.rowTitles.any { it == StremioActivity.CONTINUE_ROW || it == StremioActivity.WATCHED_ROW })
        // ▼ z zakładki – pierwszy plakat (pasek nawigacji zostaje zwinięty)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); idle(300)
        assertTrue("▼ – plakaty", inside(focus(a), a.rowsBox))
        // plakat 2:3, ocena w rogu (tylko gdy jest), pod plakatem tytuł i rok
        val rv = recyclers(a.rowsBox).first { (it.adapter?.itemCount ?: 0) > 0 }
        val tile = rv.getChildAt(0) as android.view.ViewGroup
        val frame = tile.getChildAt(0)
        assertEquals(frame.layoutParams.width * 3 / 2, frame.layoutParams.height)
        val tileTexts = mutableListOf<TextView>().also { l -> fun w(v: View) { if (v is TextView) l += v; if (v is android.view.ViewGroup) (0 until v.childCount).forEach { w(v.getChildAt(it)) } }; w(tile) }
        assertTrue(tileTexts.any { it.text == "★ 8.4" && it.visibility == View.VISIBLE })
        assertTrue(tileTexts.any { it.text == "Film 1" }); assertTrue(tileTexts.any { it.text == "2011" })
        assertTrue("stałe id pozycji", (rv.adapter as StremioPosterAdapter).hasStableIds())
        // fokus na plakacie: ► – następny, skala 1,07 (warstwa GPU)
        assertTrue(inside(focus(a), a.rowsBox))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); idle(300)
        val focused = focus(a)!!
        assertEquals(StremioUi.POSTER_FOCUS_SCALE, focused.scaleX, 0.01f)
        // ▲ z pierwszego (najwyższego) rzędu – pigułki filtrów / wyszukiwarka (katalogi wczytują się w dowolnej kolejności –
        // kursor startowy bywa w niższym rzędzie, z którego ▲ słusznie prowadzi rząd wyżej)
        recyclers(a.rowsBox).first { (it.adapter?.itemCount ?: 0) > 0 }.focusPosition(0); idle(400)
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertSame("▲ z pierwszego rzędu – wybrana zakładka", a.filterViews[StremioActivity.FILTER_ALL], focus(a))
        // ◄ z pierwszej pigułki – pasek nawigacji (rozwinięty), ► wraca na tę samą pigułkę
        a.filterViews[0].requestFocus(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val rail = focus(a)!!.parent as View
        assertEquals(Ui.dp(pl.iptv.tv.ui.theme.AppNavRail.EXPANDED.value.toInt()), rail.layoutParams.width)
        assertEquals("pierwsza pozycja paska – konto (gość: „Zaloguj”)", "Zaloguj", focus(a)!!.contentDescription.toString())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("Szukaj", focus(a)!!.contentDescription.toString())
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertSame("► z paska – z powrotem na pigułkę", a.filterViews[0], focus(a))
        assertEquals(Ui.dp(72), rail.layoutParams.width)
        // OK na „Seriale”: tylko katalogi seriali, wybrana pigułka
        a.filterViews[StremioActivity.FILTER_SERIES].performClick()
        waitFor { a.rowTitles.any { it.contains("seriale") } && recyclers(a.rowsBox).any { (it.adapter?.itemCount ?: 0) > 0 } }
        assertTrue(a.filterViews[StremioActivity.FILTER_SERIES].isSelected)
        assertFalse(a.rowTitles.toString(), a.rowTitles.any { it.contains("filmy") })
        // ▼ z „Seriale” do plakatów i ▲ – z powrotem dokładnie na „Seriale” (nie na „Odkrywaj”)
        a.filterViews[StremioActivity.FILTER_SERIES].requestFocus(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); idle(300)
        assertTrue(inside(focus(a), a.rowsBox))
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertSame(a.filterViews[StremioActivity.FILTER_SERIES], focus(a))
        // ◄ z pierwszego plakatu – pasek nawigacji
        recyclers(a.rowsBox).first { (it.adapter?.itemCount ?: 0) > 0 }.focusPosition(0); idle(400)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Zaloguj", focus(a)?.contentDescription?.toString())
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("► z paska – z powrotem na plakat", inside(focus(a), a.rowsBox))
    }

    @Test fun debridAndTorrentioCatalogsHiddenFromBoard() {
        fun a(name: String, id: String = "x.addon", url: String = "https://example.org/manifest.json") = pl.iptv.tv.data.StremioAddon(url, id, name)
        val c = pl.iptv.tv.data.StremioAddon.Catalog("movie", "top", "Popularne")
        assertTrue(StremioActivity.isHiddenCatalog(a("Torrentio RD"), c))
        assertTrue(StremioActivity.isHiddenCatalog(a("Moje źródła", url = "https://torrentio.strem.fun/realdebrid=abc/manifest.json"), c))
        assertTrue(StremioActivity.isHiddenCatalog(a("Debrid", id = "community.real-debrid"), c))
        assertTrue(StremioActivity.isHiddenCatalog(a("Wtyczka"), pl.iptv.tv.data.StremioAddon.Catalog("movie", "rd", "RealDebrid – pobrane")))
        // zwykłe katalogi zostają (Cinemeta, „rd” wewnątrz słowa)
        assertFalse(StremioActivity.isHiddenCatalog(a("Cinemeta", "com.linvo.cinemeta", StremioApi.CINEMETA), c))
        assertFalse(StremioActivity.isHiddenCatalog(a("Nagrody i nagrodzone", "org.awards"), pl.iptv.tv.data.StremioAddon.Catalog("movie", "oscars", "Oscary – Wordpress")))
    }

    @Test fun libraryFilterShowsSavedTitles() {
        StremioLibrary.add(StremioMetaPreview("ttm3", "movie", "Zapisany film", releaseInfo = "2020"))
        val a = board()
        a.filterViews[StremioActivity.FILTER_LIBRARY].performClick()
        waitFor { StremioActivity.LIBRARY_ROW in a.rowTitles }
        assertTrue(a.rowTitles.toString(), StremioActivity.LIBRARY_ROW in a.rowTitles)
        assertFalse("bez katalogów wtyczek", a.rowTitles.any { it.startsWith("Meta") || it.startsWith("Popularne") })
    }

    @Test fun cinematicDetailsWithCtaLibraryAndTrailer() {
        val a = Robolectric.buildActivity(StremioDetailActivity::class.java, StremioDetailActivity.intent(ctx, StremioMetaPreview("ttm1", "movie", "Film 1"))).setup().get()
        waitFor { a.meta != null }
        idle(500)
        val m = a.meta!!
        assertEquals(listOf("dQw4w9WgXcQ"), m.trailers)
        // przyciski CTA: OGLĄDAJ (akcent), ZWIASTUN, DODAJ DO BIBLIOTEKI
        assertEquals("OGLĄDAJ", a.watchBtn!!.text.toString())
        assertEquals("ZWIASTUN", a.trailerBtn!!.text.toString())
        assertEquals("DODAJ DO BIBLIOTEKI", a.libraryBtn!!.text.toString())
        // opis do 4 linii, gatunki jako pigułki
        val all = mutableListOf<TextView>().also { l -> fun w(v: View) { if (v is TextView) l += v; if (v is android.view.ViewGroup) (0 until v.childCount).forEach { w(v.getChildAt(it)) } }; w(a.window.decorView) }
        assertEquals(4, all.first { it.text.startsWith("Opis filmu") }.maxLines)
        assertTrue(all.any { it.text == "Dramat" }); assertTrue(all.any { it.text == "Thriller" })
        assertTrue(all.any { it.text.contains("2h 5min") && it.text.contains("★ 8.4") })
        // biblioteka: dodanie i usunięcie
        a.libraryBtn!!.performClick()
        assertTrue(StremioLibrary.contains("ttm1")); assertEquals("W BIBLIOTECE", a.libraryBtn!!.text.toString())
        a.libraryBtn!!.performClick()
        assertFalse(StremioLibrary.contains("ttm1"))
        // zwiastun z metadanych – odtwarzacz STube
        Prefs.stubeInternal = true
        a.trailerBtn!!.performClick()
        assertEquals(YtPlayerActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        // nawigacja: CTA ◄ ► w rzędzie, ▼ do źródeł, ▲ z powrotem do CTA
        a.watchBtn!!.requestFocus(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertSame(a.trailerBtn, focus(a))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertSame(a.libraryBtn, focus(a))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertSame(a.watchBtn, focus(a))
        assertNotNull(focus(a))
        // OGLĄDAJ bez postępu – kursor na liście źródeł (albo na wierszu wczytywania)
        a.watchBtn!!.performClick(); idle(600)
        assertNotNull("fokus nie ginie po OGLĄDAJ", focus(a))
    }

    @Test fun trailerIdsFromMetadata() {
        val m = JSONObject().put("trailers", JSONArray().put(JSONObject().put("source", "abcdefghijk").put("type", "Clip")).put(JSONObject().put("source", "ZZZZZZZZZZZ").put("type", "Trailer"))
            .put(JSONObject().put("source", "zly-id"))).put("trailerStreams", JSONArray().put(JSONObject().put("ytId", "abcdefghijk")))
        assertEquals(listOf("ZZZZZZZZZZZ", "abcdefghijk"), StremioApi.trailers(m))
        assertEquals(StremioUi.POSTER_W * 3 / 2, StremioUi.posterH)
    }
}

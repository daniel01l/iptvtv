package pl.iptv.tv.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/** Lokalna baza STube (Room): historia wyszukiwania z podpowiedziami, postęp oglądania (pasek na kafelkach, wznawianie). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class StubeHistoryTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var db: StubeDatabase
    private val H = StubeHistory

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }   // otwarta od razu – zamknięcie w tearDown nie trafia na otwieranie w tle (zakleszczenie Room)
        H.useDatabase(db)
        Prefs.stubeHistory = true; Prefs.stubeResume = true
        Prefs.ytApiKey = ""; Prefs.permissionsAsked = true; Prefs.setupDone = true
        YouTubeTokens.useStorage(ctx.getSharedPreferences("yt_hist_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        YouTubeRepository.clearCache(); YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..4).map { YtVideo("$id-$it", "$id $it", "Kanał", "", "", "PT4M") }
            override fun search(q: String) = listOf(YtVideo("kw1", "Wynik K1", "K", "", "", "PT2M"))
        }
    }

    @After fun tearDown() {
        H.useDatabase(null); db.close()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
    }

    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }

    @Test fun pureRules() {
        assertEquals("kot w butach", H.normalize("  Kot   W  BUTACH "))
        assertEquals("100\\%\\_a\\\\", H.escapeLike("100%_a\\"))
        // ukończony: ≥ 95 % albo < 20 s do końca → pełny pasek, bez wznawiania
        assertEquals(0.25f, H.fraction(60_000, 240_000), 0.001f)
        assertTrue(H.isFinished(229_000, 240_000)); assertTrue(H.isFinished(950_000, 1_000_000))
        assertFalse(H.isFinished(900_000, 1_000_000))
        assertEquals(1f, H.fraction(235_000, 240_000), 0f)
        assertEquals(0f, H.fraction(10, 0), 0f)
        fun wp(pos: Long, dur: Long) = WatchProgress("v", positionMs = pos, durationMs = dur, updatedAt = 0)
        assertEquals(60_000L, H.resumeFrom(wp(60_000, 240_000)))
        assertEquals(0L, H.resumeFrom(wp(5_000, 240_000)))      // za wcześnie
        assertEquals(0L, H.resumeFrom(wp(239_000, 240_000)))    // obejrzany
        assertEquals(0L, H.resumeFrom(null))
        // ms → ISO 8601 zgodny z czasem kafelka
        assertEquals("PT1H2M3S", H.isoDuration(3_723_000)); assertEquals("1:02:03", STubeScreen.duration(H.isoDuration(3_723_000)))
        assertEquals("4:00", STubeScreen.duration(H.isoDuration(240_000)))
        assertEquals("", H.isoDuration(0))
        assertEquals(listOf("search", "continue", "login", "trending", "music", "live", "library"), STubeScreen.rowIds(false, true, "kot", hasContinue = true))
    }

    @Test fun searchHistoryAndSuggestions() = runBlocking {
        H.addSearch("Kot w butach", now = 1000)
        H.addSearch("  kot   W butach ", now = 2000)   // ta sama fraza – bez duplikatu, licznik użyć
        H.addSearch("Muzyka relaks", now = 3000)
        H.addSearch("100% hity", now = 4000)
        assertEquals(3, H.searchCount())
        assertEquals(2, db.search().get("kot w butach")!!.uses)
        // puste pole – ostatnie frazy (najnowsze pierwsze)
        assertEquals(listOf("100% hity", "Muzyka relaks", "kot W butach"), H.suggestions(""))   // ostatnia pisownia
        // od początku frazy i od początku słowa, wielkość liter bez znaczenia
        assertEquals(listOf("kot W butach"), H.suggestions("KO"))
        assertEquals(listOf("kot W butach"), H.suggestions("but"))
        assertEquals(listOf("Muzyka relaks"), H.suggestions("rel"))
        // znaki LIKE w zapytaniu dosłownie („%” nie pasuje do wszystkiego)
        assertEquals(listOf("100% hity"), H.suggestions("100%"))
        assertTrue(H.suggestions("%").isEmpty())
        // wpisana w całości fraza nie podpowiada samej siebie
        assertTrue(H.suggestions("muzyka relaks").isEmpty())
        // limit historii
        repeat(StubeHistory.MAX_SEARCHES + 10) { H.addSearch("fraza $it", now = 10_000L + it) }
        assertEquals(StubeHistory.MAX_SEARCHES, H.searchCount())
        assertNull(db.search().get("kot w butach"))
        // wyłączona historia – bez zapisu
        Prefs.stubeHistory = false; H.addSearch("tajne", now = 99_999); Prefs.stubeHistory = true
        assertNull(db.search().get("tajne"))
        H.clearSearches(); assertEquals(0, H.searchCount())
    }

    @Test fun watchProgressSavedAndListed() = runBlocking {
        val a = YtVideo("vidA", "Film A", "Kanał A", "http://x/a.jpg")
        val b = YtVideo("vidB", "Film B", "Kanał B")
        // przypadkowe OK (2 s) – nic nie zapisane
        assertNull(H.savePosition(a, 2_000, 600_000))
        // transmisja (brak długości) – nic
        assertNull(H.savePosition(a, 60_000, 0))
        val p = H.savePosition(a, 150_000, 600_000, now = 10)!!
        assertEquals(0.25f, H.fractionOf("vidA")!!, 0.001f)
        H.flush(p)
        H.flush(H.savePosition(b, 595_000, 600_000, now = 20)!!)   // koniec → obejrzany
        assertEquals(600_000L, db.progress().get("vidB")!!.positionMs)
        assertEquals(1f, H.fractionOf("vidB")!!, 0f)
        // „Kontynuuj oglądanie”: tylko nieukończone, z danymi kafelka
        val cont = H.unfinished()
        assertEquals(listOf("vidA"), cont.map { it.videoId })
        assertEquals(YtVideo("vidA", "Film A", "Kanał A", "http://x/a.jpg", duration = "PT10M"), cont[0].toVideo())
        assertTrue(H.hasUnfinished)
        // pamięć wczytywana z bazy (nowy start ekranu)
        H.useDatabase(db); assertNull(H.fractionOf("vidA"))
        H.loadCache(); assertEquals(0.25f, H.fractionOf("vidA")!!, 0.001f)
        assertEquals(150_000L, H.resumeFrom(H.progress("vidA")))
        // ponowny zapis bez metadanych zachowuje tytuł
        H.flush(H.savePosition(YtVideo("vidA", "", ""), 300_000, 600_000, now = 30)!!)
        assertEquals("Film A", db.progress().get("vidA")!!.title)
        H.removeProgress("vidA"); assertNull(H.fractionOf("vidA")); assertFalse(H.hasUnfinished)
        H.clearProgress(); assertEquals(0, H.progressCount())
    }

    @Test fun stubeShowsContinueRowProgressBarAndSuggestions() {
        val id = "${YtKeyless.PODCASTS}-1"
        runBlocking {
            H.flush(WatchProgress(id, "Odcinek 1", "Kanał", "", positionMs = 60_000, durationMs = 240_000, updatedAt = 1))
            H.addSearch("kot w butach", now = 5)
        }
        H.useDatabase(db)   // pamięć pusta – ekran wczytuje postęp sam
        val c = Robolectric.buildActivity(STubeActivity::class.java).setup()
        val a = c.get()
        waitFor { a.rows.isNotEmpty() && a.rows.none { it.loading } && a.rows.all { it.tiles.isNotEmpty() } }
        assertEquals("Kontynuuj oglądanie", a.rows[0].title)
        assertEquals(id, (a.rows[0].tiles.single() as STubeTile.Video).v.id)
        idle(300)
        // pasek obejrzenia na kafelku w rzędzie „Na czasie” (ten sam film) – 25 %, w kolorze akcentu
        val trending = a.rows.indexOfFirst { it.id == STubeScreen.ROW_TRENDING }
        val rv = a.findViewById<ViewGroup>(R.id.stRows).getChildAt(trending).let { (it as ViewGroup).getChildAt(2) as RecyclerView }
        val tile = rv.findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup
        val bar = (tile.getChildAt(0) as FrameLayout).let { f -> (0 until f.childCount).map(f::getChildAt).filterIsInstance<WatchBar>().single() }
        assertEquals(View.VISIBLE, bar.visibility); assertEquals(0.25f, bar.fraction, 0.001f)
        assertEquals(Ui.dp(STubeScreen.PROGRESS_H), bar.layoutParams.height)
        // drugi kafelek – nieoglądany, bez paska
        val bar2 = ((rv.findViewHolderForAdapterPosition(1)!!.itemView as ViewGroup).getChildAt(0) as FrameLayout)
            .let { f -> (0 until f.childCount).map(f::getChildAt).filterIsInstance<WatchBar>().single() }
        assertEquals(View.GONE, bar2.visibility)

        // postęp z odtwarzacza → po powrocie pasek odświeżony bez przebudowy rzędów
        val rowsBefore = a.rows
        H.savePosition(YtVideo(id, "Odcinek 1", "Kanał"), 120_000, 240_000)
        c.pause().resume(); idle(300)
        assertSame(rowsBefore, a.rows)
        assertEquals(0.5f, bar.fraction, 0.001f)

        // fokus w polu wyszukiwania → ostatnie frazy; wyszukanie zapisuje nową frazę
        val q = a.findViewById<android.widget.AutoCompleteTextView>(R.id.stQuery)
        q.requestFocus()
        waitFor { a.suggestions.isNotEmpty() }
        assertEquals(listOf("kot w butach"), a.suggestions)
        q.setText("Pies"); a.findViewById<View>(R.id.stSearch).performClick()
        waitFor { runBlocking { db.search().get("pies") } != null }
        assertEquals("Pies", runBlocking { db.search().get("pies") }!!.query)
        c.pause().stop().destroy()
    }

    @Test fun playerIntentCarriesTileData() {
        val i = YtPlayerActivity.intent(ctx, YtVideo("abc", "Tytuł", "Kanał", "http://t"), fromStart = true)
        assertEquals("abc", i.getStringExtra(YtPlayerActivity.EXTRA_ID))
        assertEquals("Kanał", i.getStringExtra(YtPlayerActivity.EXTRA_CHANNEL))
        assertEquals("http://t", i.getStringExtra(YtPlayerActivity.EXTRA_THUMB))
        assertTrue(i.getBooleanExtra(YtPlayerActivity.EXTRA_FROM_START, false))
    }
}

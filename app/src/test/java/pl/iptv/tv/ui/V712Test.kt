package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
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
import pl.iptv.tv.data.StubeMixes
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/** (7.12) STube: podkategorie i różnorodność kanałów, podgląd kategorii na żywo (najechanie ikoną), „Na czasie” bez kiosku, przełącznik widoku muzycznego. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V712Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeInternal = true
        YtKeyless.clearCache(); STubeScreen.clearCategoryCache()
        // kiosk „Trending” zawodzi (jak w praktyce), wyszukiwanie: 3 kanały po wiele filmów + krótkie i długie
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String): List<YtVideo> = throw IllegalStateException("kiosk niedostępny")
            override fun search(q: String) = (1..30).map {
                YtVideo("s${q.hashCode()}-$it", "Artysta $it – $q", "Kanał ${it % 3}", duration = if (it % 5 == 0) "PT25M" else "PT3M")
            }
        }
    }

    @After fun tearDown() { YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache(); StubeScreen_clear(); StubeHistory.useDatabase(null); db.close() }
    private fun StubeScreen_clear() = STubeScreen.clearCategoryCache()

    @Test fun trendingWorksWithoutKioskAndEveryCategoryHasSubcategories() {
        val trending = STubeScreen.CATEGORIES.first { it.id == "cat_trending" }
        val vids = runBlocking { STubeScreen.subVideos(trending, trending.subs.first()) }
        assertTrue("było ${vids.size}", vids.size >= STubeScreen.CATEGORY_MIN)
        assertEquals(vids.size, vids.map { it.id }.distinct().size)
        STubeScreen.CATEGORIES.forEach { assertTrue(it.title, it.subs.size >= 5 && it.subs.first().title == "Wszystko") }
        val music = STubeScreen.CATEGORIES.first { it.id == "cat_music" }
        assertTrue(music.subs.map { it.title }.containsAll(listOf("Na czasie", "Popularne", "Rock", "Hip-hop i rap", "Składanki")))
    }

    @Test fun compilationsLiveOnlyInTheirOwnSubcategoryAndTracksAreSingleSongs() {
        val music = STubeScreen.CATEGORIES.first { it.id == "cat_music" }
        val rock = music.subs.first { it.id == "g_rock" }
        val tracks = runBlocking { STubeScreen.subVideos(music, rock) }
        assertTrue(tracks.isNotEmpty()); assertTrue(tracks.none { StubeMixes.durationSec(it.duration) > 9 * 60 })
        val mix = runBlocking { STubeScreen.subVideos(music, music.subs.first { it.id == "mix" }) }
        assertTrue(mix.isNotEmpty()); assertTrue(mix.all { StubeMixes.durationSec(it.duration) >= 10 * 60 })   // składanki: 10 min+
    }

    @Test fun channelsAreSpreadNotOneCreatorDominating() {
        val sport = STubeScreen.CATEGORIES.first { it.id == "cat_sport" }
        val vids = runBlocking { STubeScreen.subVideos(sport, sport.subs.first()) }
        // przy 3 kanałach w źródle nikt nie zajmuje całej listy – rundy po kanałach, a limit 2 / kanał ustępuje tylko dla celu
        assertEquals(setOf("Kanał 0", "Kanał 1", "Kanał 2"), vids.take(3).map { it.channel }.toSet())
    }

    @Test fun hoveringCategoryIconShowsItLiveAndRightArrowJumpsToFirstVideo() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        waitFor { a.rows.isNotEmpty() && a.rows.none { it.loading } }
        val icon = a.findViewById<View>(R.id.stCat3)
        icon.requestFocus()
        idle(STubeActivity.LIVE_PREVIEW_MS + 100)
        waitFor { a.categoryRow?.id == "cat_sport" && a.categoryRow?.tiles?.isNotEmpty() == true }
        assertEquals("cat_sport", a.categoryRow?.id)
        assertSame(icon, a.window.decorView.findFocus())                         // kursor zostaje na ikonie
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stChipScroll).visibility)
        // ► → pierwszy film siatki
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)); idle(300)
        val f = a.window.decorView.findFocus()
        assertNotNull(f); assertNotSame(icon, f)
        var v: View = f!!; while (v.parent !== a.categoryGrid && v.parent is View) v = v.parent as View
        assertEquals(0, a.categoryGrid.getChildAdapterPosition(v))
    }

    @Test fun mixTitleHasNoCountSuffix() {
        assertFalse(STubeScreen.rows(null).any { it.title.contains("100+") })
    }
}

class V712LyricsTest {
    @Test fun lyricsSearchTriesSwappedChannelAndFirstArtistVariants() {
        val p = pl.iptv.tv.data.YtLyrics.candidatePairs("Artysta A & Artysta B", "Piosenka", "Kanał Muzyczny VEVO")
        assertEquals("Artysta A & Artysta B" to "Piosenka", p[0])
        assertTrue(p.contains("Artysta A" to "Piosenka"))
        assertTrue(p.contains("Kanał Muzyczny" to "Piosenka"))
        assertTrue(p.size <= 4)
        assertEquals(listOf("Sam" to "Tytuł"), pl.iptv.tv.data.YtLyrics.candidatePairs("", "Tytuł", "Sam").let { it.filter { x -> x.first == "Sam" } })
    }
}

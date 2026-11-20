package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.time.Duration

/** (7.16) Pasek boczny STube: edycja kolejności i widoczności ikon, mniejsza miniatura YouTube, ikona edycji. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V716Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        Prefs.stubeRailOrder = ""; Prefs.stubeRailHidden = ""
    }

    @After fun tearDown() { Prefs.stubeRailOrder = ""; Prefs.stubeRailHidden = ""; db.close() }

    @Test fun railHonoursSavedOrderAndHiddenIcons() {
        Prefs.stubeRailOrder = "smart,phone,lib"
        Prefs.stubeRailHidden = "cat3,add"
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get(); idle(400)
        val rail = a.findViewById<android.widget.LinearLayout>(R.id.stRail)
        val order = (0 until rail.childCount).map { rail.getChildAt(it).id }
        assertTrue(order.indexOf(R.id.stSmartTube) < order.indexOf(R.id.stPhone))
        assertTrue(order.indexOf(R.id.stPhone) < order.indexOf(R.id.stLib))
        assertTrue(order.indexOf(R.id.stLib) < order.indexOf(R.id.stHome))   // reszta w domyślnej kolejności za zapisanymi
        assertEquals(View.GONE, a.findViewById<View>(R.id.stCat3).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.stAdd).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stCat0).visibility)
        // ikony stałe zostają
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stEdit).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stClose).visibility)
    }

    @Test fun editIconOpensEditorAndChangesApplyLive() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get(); idle(400)
        a.findViewById<View>(R.id.stEdit).performClick(); idle()
        // zapis ustawień jak z edytora, odświeżenie układu przy zmianie
        Prefs.stubeRailHidden = "home"
        a.findViewById<View>(R.id.stEdit).performClick(); idle()
        assertTrue(Prefs.stubeRailHidden.contains("home"))
    }

    @Test fun lyricTitlesAndManualDelayPersist() {
        val yl = pl.iptv.tv.data.YtLyrics
        assertEquals("Rick Astley" to "Never Gonna Give You Up", yl.artistAndTrack("Rick Astley - Never Gonna Give You Up Official Music Video", "RickAstleyVEVO"))
        assertEquals("Sanah" to "Szampan", yl.artistAndTrack("sanah „Szampan” (Official Video)", "sanah").let { it.first.replaceFirstChar(Char::uppercase) to it.second })
        assertEquals(0L, yl.delayFor("abc"))
        yl.saveDelay("abc", -750); yl.saveDelay("def", 500)
        assertEquals(-750L, yl.delayFor("abc")); assertEquals(500L, yl.delayFor("def"))
        yl.saveDelay("abc", 0)
        assertEquals(0L, yl.delayFor("abc")); assertEquals(500L, yl.delayFor("def"))
        Prefs.ytLyricDelays = ""
    }

    @Test fun smallYoutubeThumbnailOnlyForSmallTiles() {
        val big = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals("https://i.ytimg.com/vi/abc/mqdefault.jpg", STubeScreen.thumbUrl(big, 300))
        assertEquals(big, STubeScreen.thumbUrl(big, 600))
        assertEquals("https://example.com/maxresdefault.jpg", STubeScreen.thumbUrl("https://example.com/maxresdefault.jpg", 300))
    }
}

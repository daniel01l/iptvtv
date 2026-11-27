package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.VodLibrary
import pl.iptv.tv.data.X25Store
import java.io.File
import java.time.Duration

/**
 * (v2) Pulpit START motywu Nowoczesny v2: górny pasek (Telewizja / STube / Stremio / warunkowe VOD), hero ze slajdami,
 * rzędy Top 10 / kina / VOD z listy / kanały / skróty; stary Nowoczesny (v1) bez zmian.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V727ModernV2HomeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var frameView: android.view.View? = null
    private val frameCanvas by lazy { android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)) }
    private fun idle(ms: Long = 200) {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 3 + 1)); frameView?.let { v -> v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1920, 1080); v.draw(frameCanvas) } }
    }
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(5); if (cond()) return } }
    private lateinit var db: StubeDatabase

    private val VOD_M3U = """
        #EXTM3U
        #EXTINF:-1 group-title="Ogólne",TVP 1 HD
        http://srv/live/1.ts
        #EXTINF:-1 tvg-type="movie" group-title="VOD | Filmy",Szybki film (2021)
        http://srv/movie/u/p/101.mp4
        #EXTINF:-1 group-title="Seriale",Detektyw S01E01
        http://srv/series/u/p/201.mkv
        #EXTINF:-1 group-title="Seriale",Detektyw S02E01
        http://srv/series/u/p/203.mkv
    """.trimIndent()
    private val LIVE_M3U = "#EXTM3U\n#EXTINF:-1,TVN\nhttp://s/live.ts\n"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); X25Store.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.modernHome = true; Prefs.tmdbApiKey = ""
        StremioApi.defaultAddons = emptyList()                      // bez sieci: „Obecnie w kinach” z Cinemety pominięte
        ModernHomeActivity.cinemaCache = null
        VodLibrary.resetForTest()
        Ui.applyPreset(ModernV2.ID); Ui.changed(); X25Theme.justEnabled = false
    }

    private var ctrl: org.robolectric.android.controller.ActivityController<ModernHomeActivity>? = null

    @After fun tearDown() {
        frameView = null; runCatching { ctrl?.pause()?.stop()?.destroy() }; ctrl = null
        frameView = null
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES)
        ModernHomeActivity.cinemaCache = null; VodLibrary.resetForTest()
        Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false; StubeHistory.useDatabase(null); db.close()
    }

    private fun load(text: String) {
        val f = File(ctx.filesDir, "v2home.m3u").apply { writeText(text) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        runBlocking { VodLibrary.current() }
    }

    private fun home(): ModernHomeActivity {
        val c = Robolectric.buildActivity(ModernHomeActivity::class.java).setup().visible().also { ctrl = it }
        c.windowFocusChanged(true)
        frameView = c.get().window.decorView
        waitFor { c.get().focusedKey != null }
        return c.get()
    }

    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Test fun vodTabOnlyWithMoviesOrSeriesAndFocusNeverLost() {
        load(LIVE_M3U)
        val a = home()
        assertTrue(ModernV2.active)
        assertEquals(ModernHomeActivity.HERO_PLAY, a.focusedKey)
        assertFalse(a.vodTabShown)
        assertFalse(ModernHomeActivity.V2_VOD in a.v2RowIdsNow)
        // ▲ z hero – górny pasek (pierwsza zakładka „Telewizja”), ▼ – z powrotem „Oglądaj”
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(ModernHomeActivity.TOP_LIVE, a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(ModernHomeActivity.HERO_PLAY, a.focusedKey)
        // nowa lista z filmami i serialami – pigułka „VOD” i rząd „VOD z Twojej listy” od razu
        load(VOD_M3U)
        waitFor { a.vodTabShown }
        assertTrue(a.vodTabShown)
        assertTrue(a.v2RowIdsNow.toString(), ModernHomeActivity.V2_VOD in a.v2RowIdsNow)
        waitFor { ModernHomeActivity.TOP_VOD in a.attachedKeys }
        assertTrue("pigułka VOD w kompozycji: comps=${a.contentComps}", ModernHomeActivity.TOP_VOD in a.attachedKeys)
        // kursor na „VOD”, lista bez VOD – pigułka znika, kursor przechodzi na „Stremio” (nie ginie)
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        repeat(3) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(ModernHomeActivity.TOP_VOD, a.focusedKey)
        load(LIVE_M3U)
        waitFor { !a.vodTabShown && a.focusedKey == ModernHomeActivity.TOP_STREMIO }
        assertFalse(a.vodTabShown)
        assertEquals(ModernHomeActivity.TOP_STREMIO, a.focusedKey)
    }

    @Test fun heroSlidesTop10AndCinemaRows() {
        load(LIVE_M3U)
        val a = home()
        val top = (1..10).map { StremioMetaPreview("tt$it", "movie", "Film $it", "", imdbRating = "7.$it") }
        val cin = listOf(StremioMetaPreview("tt100", "movie", "Kino 1", ""))
        a.setV2ContentForTest(top, cin)
        idle(400)
        val rows = a.v2RowIdsNow
        assertEquals(listOf(ModernHomeActivity.V2_TOP10, ModernHomeActivity.V2_CINEMA), rows.take(2))
        assertEquals(ModernHomeActivity.V2_SHORTCUTS, rows.last())
        // slajdy: Top 10 (4) + kina, najwyżej HERO_SLIDES; ► na „Więcej informacji” – następny, ◄ na „Oglądaj” – poprzedni
        val slides = a.heroSlideKeys
        assertEquals("sm:tt1", slides.first()); assertTrue(slides.size <= ModernHomeActivity.HERO_SLIDES); assertTrue("sm:tt100" in slides)
        assertEquals(0, a.heroIndexNow)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(ModernHomeActivity.HERO_INFO, a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, a.heroIndexNow)
        assertEquals(ModernHomeActivity.HERO_INFO, a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, a.heroIndexNow)
        a.heroStep(-1)
        assertEquals(slides.size - 1, a.heroIndexNow)
    }

    @Test fun seasonLabels() {
        assertEquals("Sezon 2  ·  1 odcinek", M3uVodUi.seasonLabel(2, 1))
        assertEquals("Sezon 1  ·  5 odcinków", M3uVodUi.seasonLabel(1, 5))
    }

    @Test fun classicModernKeepsLeftRailAndOldShelves() {
        Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
        val a = home()
        assertFalse(ModernV2.active)
        assertFalse(a.focusedKey!!.startsWith(ModernHomeActivity.TOP_PREFIX))
        assertTrue(a.shelfIds.contains("shortcuts"))
        a.railTap(1); idle()
        assertEquals(1, a.sectionNow)                               // pasek boczny v1 działa jak dotąd
    }
}

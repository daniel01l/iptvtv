package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioStream
import java.io.File
import java.time.Duration

/**
 * 7.22 – odtwarzacze na telefonie w pionie: jednolity pasek ikon w strefie kciuka (pole dotyku ≥ 48 × 48 dp, mieści się
 * w 360 dp) i dolne arkusze (uchwyt, górne rogi 24 dp, tło motywu): silnik i jakość, ścieżki audio / napisy z suwakiem
 * opóźnienia ±5 s, proporcje i prędkość, lista kanałów / odcinków z bieżącą pozycją. TV i poziomo – bez paska i arkuszy.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V722PortraitSheetsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.orientation = 2; Prefs.aspectMode = 0; Prefs.maxVideoRes = 0
        val f = File(ctx.filesDir, "v722s.m3u").apply { writeText((1..5).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"s$it\" group-title=\"G\",Kanał $it\nhttp://127.0.0.1:9/live/$it.ts\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Prefs.orientation = 0; Prefs.aspectMode = 0; Prefs.maxVideoRes = 0; Playback.stop(); TvResolution.resetForTest() }

    private fun livePlayer(): org.robolectric.android.controller.ActivityController<PlayerActivity> {
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        return Robolectric.buildActivity(PlayerActivity::class.java, i).setup().also { idle(400) }
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun liveTvIconBarFitsNarrowPhoneWithBigTouchTargets() {
        val c = livePlayer(); val a = c.get()
        val bar = a.portraitBar!!
        assertEquals(View.VISIBLE, bar.visibility)
        val icons = (0 until bar.childCount).map { bar.getChildAt(it) }
        assertEquals(listOf("channels", "guide", "tracks", "aspect", "engine", "cast", "rotate").map { PortraitPlayerUi.ICON_TAG + it }, icons.map { it.tag })
        // 360 dp szerokości: wszystkie ikony w całości, każda ≥ 48 × 48 dp, podpis w jednej linii
        val w = a.resources.displayMetrics.widthPixels
        bar.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); bar.layout(0, 0, w, bar.measuredHeight)
        // (7.25) „cast” ukryta bez urządzeń w pobliżu – liczą się widoczne
        icons.filter { it.visibility == View.VISIBLE }.forEach { assertTrue(it.width >= Ui.dp(48)); assertTrue(it.height >= Ui.dp(48)) }
        assertTrue(icons.last().right <= w)
        all(bar).filterIsInstance<TextView>().forEach { assertEquals(1, it.maxLines) }
        // obraz i nakładki kończą się nad paskiem (bez nakładania na wideo)
        assertTrue(bar.parent is android.widget.FrameLayout)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvPlayerHasNoBarOrSheets() {
        val c = livePlayer(); val a = c.get()
        assertNull(a.portraitBar)
        assertTrue(all(a.window.decorView).none { it.tag == PortraitPlayerUi.BAR_TAG })
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun channelListSheetMarksCurrentAndClosesAfterPick() {
        val c = livePlayer(); val a = c.get()
        all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "channels" }.performClick(); idle()
        val s = a.lastSheet!!
        assertTrue(s.isShowing); assertEquals("Kanały", s.title)
        val rv = s.recycler!!
        rv.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)); rv.layout(0, 0, 800, rv.measuredHeight)
        val rows = (0 until rv.childCount).map { rv.getChildAt(it) }
        assertEquals(1, rows.count { it.tag == "sheet_current" })
        assertTrue(texts(rows.first { it.tag == "sheet_current" }).any { it.startsWith("▶ teraz") })
        rows.first { it.tag == "sheet_entry" }.performClick(); idle(600)
        assertFalse(s.isShowing)                                              // wybór zamyka arkusz
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun engineSheetChoicesApplyToSharedConfig() {
        val c = livePlayer(); val a = c.get()
        all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "engine" }.performClick(); idle()
        val s = a.lastSheet!!
        val t = texts(s.root)
        assertTrue(t.containsAll(PortraitPlayerUi.ENGINE_LABELS))
        assertTrue(t.containsAll(VideoResolutionLimit.entries.map { it.label }))
        assertTrue(t.containsAll(listOf("Automatyczny", "SurfaceView", "TextureView", SettingsActivity.AUTO_STUTTER_TITLE, "Wyrównywanie głośności kanałów", "Diagnostyka")))
        // arkusz: uchwyt, górne rogi 24 dp, tło panelu motywu
        assertTrue(all(s.root).any { it.tag == "sheet_handle" })
        val bg = s.root.background as android.graphics.drawable.GradientDrawable
        assertEquals(Ui.dp(24).toFloat(), bg.cornerRadii!![0], 0.5f); assertEquals(0f, bg.cornerRadii!![4], 0.5f)
        all(s.root).first { it is TextView && it.text == VideoResolutionLimit.HD_720P.label }.performClick(); idle()
        assertEquals(VideoResolutionLimit.HD_720P, EngineConfigRepository.current.maxVideoResolution)
        s.dismiss(); c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun aspectSheetTilesSetLiveAspect() {
        val c = livePlayer(); val a = c.get()
        all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "aspect" }.performClick(); idle()
        val s = a.lastSheet!!
        assertTrue(texts(s.root).containsAll(PortraitPlayerUi.Aspect.entries.map { it.label }))
        assertFalse(texts(s.root).contains("Prędkość odtwarzania"))         // na żywo – bez prędkości
        all(s.root).first { it is TextView && it.text == "4:3" }.performClick(); idle()
        assertEquals(4, Prefs.aspectMode)
        all(s.root).first { it is TextView && it.text == "Wypełnij" }.performClick(); idle()
        assertEquals(2, Prefs.aspectMode)
        s.dismiss(); c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun genericSheetsSpeedTilesAndSubtitleDelaySlider() {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var speed = 1f
        val s1 = PortraitPlayerUi.aspectSpeedSheet(a, PortraitPlayerUi.Aspect.FIT, {}, 1f, { speed = it }); idle()
        assertTrue(texts(s1.root).containsAll(listOf("0,5x", "1,0x", "1,25x", "1,5x", "2,0x")))
        all(s1.root).first { it is TextView && it.text == "1,5x" }.performClick(); assertEquals(1.5f, speed, 0f)
        s1.dismiss()
        var delay = 0
        val s2 = PortraitPlayerUi.tracksSheet(a, null, external = { listOf(PortraitPlayerUi.Choice("Polski · OpenSubtitles", true) {}) },
            delay = PortraitPlayerUi.Delay({ delay }) { delay = it }); idle()
        val t = texts(s2.root)
        assertTrue(t.containsAll(listOf("Ścieżka dźwięku", "Napisy", "Wyłączone", "Polski · OpenSubtitles", "Synchronizacja napisów")))
        val bar = all(s2.root).filterIsInstance<SeekBar>().single()
        assertEquals(100, bar.max)                                            // −5,0 s … +5,0 s co 0,1 s
        shadowOf(bar).onSeekBarChangeListener.onProgressChanged(bar, 75, true)
        assertEquals(2_500, delay)
        assertTrue(texts(s2.root).contains("+2,5 s"))
        s2.dismiss()
        // lista: bieżąca pozycja oznaczona, wybór uruchamia akcję i zamyka
        var picked = ""
        val s3 = PortraitPlayerUi.listSheet(a, "Odcinki", (1..4).map { n -> PortraitPlayerUi.Entry("$n. Odcinek", "", n == 3) { picked = "e$n" } }); idle()
        assertTrue(s3.isShowing)
        s3.recycler!!.let { rv -> rv.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)); rv.layout(0, 0, 800, rv.measuredHeight)
            (0 until rv.childCount).map { rv.getChildAt(it) }.first { texts(it).contains("1. Odcinek") }.performClick() }
        assertEquals("e1", picked); assertFalse(s3.isShowing)
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun stremioPlayerPortraitBarAndSheets() {
        val i = StremioPlayerActivity.intent(ctx, "series", "tt1", "Serial", "", "tt1:1:1", "1080p", StremioStream("Wtyczka", "http://127.0.0.1:9", url = "http://127.0.0.1:9/x.mp4"))
        val c = Robolectric.buildActivity(StremioPlayerActivity::class.java, i).setup(); idle(400)
        val a = c.get()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER, a.requestedOrientation)
        val bar = a.portraitBar!!
        // (7.23) Źródła, Odcinki / Części, Audio, Napisy, Obraz, Obróć (bez „Silnik”)
        assertEquals(listOf("sources", "episodes", "audio", "tracks", "aspect", "cast", "rotate").map { PortraitPlayerUi.ICON_TAG + it }, (0 until bar.childCount).map { bar.getChildAt(it).tag })
        assertEquals("Odcinki", (bar.getChildAt(1) as android.view.ViewGroup).getChildAt(1).let { (it as TextView).text.toString() })
        all(bar).first { it.tag == PortraitPlayerUi.ICON_TAG + "tracks" }.performClick(); idle()
        assertTrue(texts(a.lastSheet!!.root).contains(StremioPlayerActivity.SUB_DELAY))
        a.lastSheet!!.dismiss()
        all(bar).first { it.tag == PortraitPlayerUi.ICON_TAG + "sources" }.performClick(); idle()
        assertTrue(texts(a.lastSheet!!.root).contains("Wybierz inne źródło"))
        a.lastSheet!!.dismiss()
        all(bar).first { it.tag == PortraitPlayerUi.ICON_TAG + "aspect" }.performClick(); idle()
        assertTrue(texts(a.lastSheet!!.root).contains(StremioPlayerActivity.PIC_PROFILE))
        a.lastSheet!!.dismiss()
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun stremioPlayerOnTvUnchanged() {
        val i = StremioPlayerActivity.intent(ctx, "movie", "tt2", "Film", "", "tt2", "1080p", StremioStream("Wtyczka", "http://127.0.0.1:9", url = "http://127.0.0.1:9/x.mp4"))
        val c = Robolectric.buildActivity(StremioPlayerActivity::class.java, i).setup(); idle(400)
        assertNull(c.get().portraitBar)
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true || all(ShadowDialog.getLatestDialog().window!!.decorView).none { it.tag == PortraitPlayerUi.SHEET_TAG })
        c.pause().stop().destroy()
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.R
import pl.iptv.tv.data.MiniMenu
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.Scores
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V41Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.keyMapJson = ""; Prefs.zapListEnabled = true; Prefs.zapRows = 8; Prefs.miniMenuEnabled = true; Prefs.okLongAction = 1; MiniMenu.reset(); Prefs.miniApps = emptyList()
        val f = File(ctx.filesDir, "v41.m3u").apply { writeText((1..20).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\",K$it\nhttp://v41/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    private fun player() = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[3].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup().also { idle() }

    @Test fun appNameAndIcon() {
        assertEquals("D-iptv Player", ctx.getString(R.string.app_name))
        assertNotNull(androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_fg))
        assertNotNull(androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.banner))
    }

    @Test fun isoTimesAndDarts() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val exp = java.util.Calendar.getInstance(utc).apply { clear(); set(2026, 10, 7, 12, 30) }.timeInMillis
        assertEquals(exp, Scores.isoTime("2026-11-07T12:30Z"))
        assertEquals(exp, Scores.isoTime("2026-11-07T12:30:00Z"))
        assertEquals(exp, Scores.isoTime("2026-11-07T13:30:00+01:00"))
        val past = JSONObject().put("events", JSONArray().put(JSONObject().put("idEvent", "9").put("strEvent", "Players Championship 34")
            .put("strTimestamp", "2026-11-05T00:00:00").put("strResult", "Quarter-Finals\r\nA Aaa 6-3 B Bbb\r\n\r\nFinal\r\n1 A Aaa 8-5 C Ccc")))
        val next = JSONObject().put("events", JSONArray().put(JSONObject().put("idEvent", "10").put("strEvent", "Grand Slam Day 1")
            .put("strTimestamp", "2026-11-14T00:00:00").put("strCity", "Wolverhampton")))
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when {
                r.path!!.contains("eventspastleague") -> MockResponse().setBody(past.toString())
                r.path!!.contains("eventsnextleague") -> MockResponse().setBody(next.toString())
                else -> MockResponse().setResponseCode(404)
            }
        }
        srv.start()
        try {
            Scores.tsdbBase = srv.url("/t").toString()
            assertNotNull(Scores.available().firstOrNull { it.id == "tsdb:4554" })          // dart zawsze dostępny
            Scores.follows = listOf(Scores.Follow("tsdb:4554", null, "Dart"))
            val m = runBlocking { Scores.fetch() }
            assertEquals(3, m.size)
            assertEquals("Grand Slam Day 1", m[0].home); assertEquals("", m[0].away); assertEquals(Scores.State.PRE, m[0].state)
            assertEquals("A Aaa", m[1].home); assertEquals("C Ccc", m[1].away); assertEquals("8:5", m[1].score)    // finał najwyżej
            assertTrue(m[1].leagueName.endsWith("Finał"))
            assertTrue(m[2].leagueName.endsWith("Ćwierćfinały"))
            assertTrue(Scores.goalMatches(m.map { it.copy(hs = 0, aws = 0) }, m).isEmpty())   // dart – bez „GOL!”
            assertTrue(runCatching { runBlocking { Scores.teams(Scores.league("tsdb:4554")!!) } }.isFailure)
        } finally { srv.shutdown(); Scores.follows = emptyList() }
    }

    /** Dawny pasek informacji i lista kanałów pod OK (7.17: w Nowoczesnym – OSD „Obsidian Stream”, V717OsdTest). */
    @Test fun zapListCenteredTilesAndHidesAfter3s() = classicTheme {
        val c = player(); val a = c.get()
        idle(6000)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val zp = a.findViewById<View>(R.id.zapPanel)
        assertEquals(View.VISIBLE, zp.visibility)
        assertEquals(android.view.Gravity.CENTER, (zp.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
        assertNull(zp.background)                                                // bez ramki – same kafelki
        idle(2000); key(a, KeyEvent.KEYCODE_DPAD_DOWN)                           // ruch przedłuża
        idle(2500); assertEquals(View.VISIBLE, zp.visibility)
        idle(800); assertEquals(View.GONE, zp.visibility)
        c.pause().stop().destroy()
    }

    /** Dawny pasek informacji i lista kanałów pod OK (7.17: w Nowoczesnym – OSD „Obsidian Stream”, V717OsdTest). */
    @Test fun zapListScrollsByWholeRows() = classicTheme {
        val c = player(); val a = c.get()
        idle(6000)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.zapList)
        fun focusedIdx() = rv.findContainingViewHolder(a.window.decorView.findFocus()!!)!!.bindingAdapterPosition
        fun checkWhole(tag: String) {
            idle(400)
            for (i in 0 until rv.childCount) {
                val ch = rv.getChildAt(i)
                if (ch.bottom <= rv.paddingTop || ch.top >= rv.height - rv.paddingBottom) continue     // poza widokiem
                assertTrue("$tag: ucięty od góry ${ch.top}", ch.top >= rv.paddingTop)
                assertTrue("$tag: ucięty od dołu ${ch.bottom}", ch.bottom <= rv.height - rv.paddingBottom)
            }
            val f = a.window.decorView.findFocus()!!
            assertTrue(f.top >= rv.paddingTop && f.bottom <= rv.height - rv.paddingBottom)
        }
        checkWhole("start")
        repeat(12) { key(a, KeyEvent.KEYCODE_DPAD_DOWN); checkWhole("w dół $it") }
        assertEquals(15, focusedIdx())
        repeat(10) { key(a, KeyEvent.KEYCODE_DPAD_UP); checkWhole("w górę $it") }
        assertEquals(5, focusedIdx())
        // szybkie przytrzymanie
        repeat(9) { a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, it)); idle(50) }
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)); checkWhole("przytrzymanie")
        assertEquals(14, focusedIdx())
        // zawijanie: z ostatniego na pierwszy
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(0, focusedIdx()); checkWhole("zawinięcie")
        c.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-xhdpi") fun miniMenuFitsPortrait() {
        val c = player(); val a = c.get()
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        val menu = a.findViewById<View>(R.id.miniMenu)
        assertEquals(View.VISIBLE, menu.visibility)
        assertTrue("${menu.width}", menu.width <= a.resources.displayMetrics.widthPixels)
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(LinearLayout.VERTICAL, icons.orientation)                   // kilka rzędów
        val boxes = (0 until icons.childCount).flatMap { r -> (icons.getChildAt(r) as LinearLayout).let { row -> (0 until row.childCount).map { row.getChildAt(it) } } }
        assertEquals(8, boxes.size)
        boxes.forEach { b -> val loc = IntArray(2); b.getLocationInWindow(loc); assertTrue(loc[0] >= 0 && loc[0] + b.width <= a.resources.displayMetrics.widthPixels) }
        c.pause().stop().destroy()
    }
}

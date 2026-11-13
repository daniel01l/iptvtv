package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.Weather
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V35Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int, ms: Long = 300) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(ms) }
    private fun inside(a: android.app.Activity, id: Int) = generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.any { it.id == id }
    private val H = 3_600_000L
    private val base = System.currentTimeMillis() / (30 * 60_000) * (30 * 60_000)
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.mainLayout = 0; Prefs.weatherEnabled = false
        val f = File(ctx.filesDir, "v35.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v35/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val sb = StringBuilder("<tv>")
        for (c in 1..12) { var x = base - 6 * H; while (x < base + 6 * H) { sb.append("<programme start=\"${t(x)}\" stop=\"${t(x + H)}\" channel=\"c$c\"><title>P$c ${(x - base) / H}</title></programme>"); x += H } }
        val x = File(ctx.filesDir, "v35.xml").apply { writeText("$sb</tv>") }
        runBlocking { Repository.loadEpg(x.absolutePath) }
    }

    @Test fun mainSideButtonsDateNoPlayButton() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        assertEquals(0, a.resources.getIdentifier("btnPlay", "id", a.packageName).let { if (it == 0) 0 else if (a.findViewById<View>(it) == null) 0 else 1 })
        assertTrue(a.findViewById<TextView>(R.id.date).text.isNotBlank())
        assertEquals(android.view.Gravity.CENTER_HORIZONTAL, a.findViewById<TextView>(R.id.epgChannel).gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        assertTrue(inside(a, R.id.channels))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertTrue(inside(a, R.id.groups))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(R.id.btnEpgSearch, a.window.decorView.findFocus()?.id)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(R.id.btnSearch, a.window.decorView.findFocus()?.id)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(R.id.btnSettings, a.window.decorView.findFocus()?.id)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(R.id.btnSettings, a.window.decorView.findFocus()?.id)   // koniec kolumny
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(R.id.btnSettings, a.window.decorView.findFocus()?.id)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.groups))
        key(a, KeyEvent.KEYCODE_DPAD_UP); key(a, KeyEvent.KEYCODE_DPAD_UP); assertTrue(inside(a, R.id.groups))   // ▲ z 1. kategorii zostaje
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.channels))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.programmes))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertTrue(inside(a, R.id.channels))
        c.pause().stop().destroy()
    }

    @Test fun layoutsMovePanels() {
        for (m in 0..3) {
            Prefs.mainLayout = m
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
            val a = c.get()
            val content = a.findViewById<android.widget.LinearLayout>(R.id.content)
            val progParent = a.findViewById<View>(R.id.programmes).parent as View
            if (m == 1) { assertNotEquals(R.id.infoPanel, progParent.id); assertEquals(5, content.childCount) }
            else { assertEquals(R.id.infoPanel, progParent.id); assertEquals(4, content.childCount) }
            assertTrue(inside(a, R.id.channels))
            key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue("układ $m", inside(a, R.id.programmes))
            c.pause().stop().destroy()
        }
    }

    @Test fun previewNotStartedWhenMenuHidden() {
        Prefs.mainPreview = true
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(100)
        c.pause(); idle(2000)                    // np. akcja startowa uruchomiła odtwarzacz
        assertNull(Playback.channelKey)
        c.resume(); idle(2000)
        assertNotNull(Playback.channelKey)
        c.pause().stop().destroy(); Playback.stop()
    }

    @Test fun weatherFromOpenMeteo() {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setBody("""{"results":[{"name":"Kraków","admin1":"Małopolskie","latitude":50.06,"longitude":19.94}]}"""))
        srv.enqueue(MockResponse().setBody("""{"current":{"temperature_2m":7.6,"weather_code":3,"is_day":1}}"""))
        srv.start()
        Weather.geoBase = srv.url("/geo").toString(); Weather.forecastBase = srv.url("/fc").toString()
        assertEquals("Kraków, Małopolskie", runBlocking { Weather.setCity("krakow") })
        assertEquals("☁ 8°C", runBlocking { Weather.refresh() })
        assertTrue(srv.takeRequest().path!!.contains("name=krakow"))
        assertTrue(srv.takeRequest().path!!.contains("latitude=50.06"))
        assertEquals("☁ 8°C", runBlocking { Weather.refresh() })          // z pamięci (bez zapytania)
        assertEquals(2, srv.requestCount)
        Prefs.weatherEnabled = true
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val w = c.get().findViewById<TextView>(R.id.weather)
        assertEquals(View.VISIBLE, w.visibility); assertEquals("☁ 8°C", w.text.toString())
        c.pause().stop().destroy()
        val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 8)).setup(); idle()
        assertEquals("Wyniki i pogoda", SettingsActivity.TAB_NAMES[8])
        s.pause().stop().destroy()
        srv.shutdown()
    }

    @Test fun playerPanelLeftReturnsToChannel() {
        val ch = Repository.channels[4]
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        val c = Robolectric.buildActivity(PlayerActivity::class.java, i).setup(); idle()
        val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT, 1000)
        val lp = a.findViewById<RecyclerView>(R.id.lpChannels)
        fun focusedKey() = lp.findContainingViewHolder(a.window.decorView.findFocus()!!)?.bindingAdapterPosition?.let { (lp.adapter as ChannelAdapter).items[it].key }
        lp.focusPosition(6); idle()
        val entered = focusedKey()
        assertEquals(Repository.channels[6].key, entered)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.lpProgrammes))
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(inside(a, R.id.lpChannels)); assertEquals(entered, focusedKey())
        c.pause().stop().destroy()
    }

    @Test fun guideNeverFlashesTopButtons() {
        val c = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java)
            .putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[0].key)).setup(); idle(500)
        val a = c.get()
        val top = a.findViewById<View>(R.id.guideTop)
        var flashed = false; var step = ""
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> if (n != null && generateSequence(n) { it.parent as? View }.any { it === top }) flashed = true }
        repeat(14) { step = "D$it"; key(a, KeyEvent.KEYCODE_DPAD_DOWN, 60) }
        repeat(6) { step = "R$it"; key(a, KeyEvent.KEYCODE_DPAD_RIGHT, 60) }
        repeat(6) { step = "L$it"; key(a, KeyEvent.KEYCODE_DPAD_LEFT, 60) }
        repeat(8) { step = "U$it"; key(a, KeyEvent.KEYCODE_DPAD_UP, 60) }
        idle(500)
        assertFalse("fokus przeskoczył na górne przyciski", flashed)
        assertNotNull(a.window.decorView.findFocus()?.getTag(R.id.guide_cell))
        c.pause().stop().destroy()
    }
}

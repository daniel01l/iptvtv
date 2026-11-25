package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V46Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.zapListEnabled = true; Prefs.orientation = 0
        val f = File(ctx.filesDir, "v46.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v46/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    private fun player() = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[2].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup().also { idle() }

    @Test fun editorGroupOrderAndHideAll() {
        val c = Robolectric.buildActivity(EditorActivity::class.java).setup(); idle()
        val a = c.get()
        a.findViewById<View>(R.id.btnGroups).performClick(); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        assertTrue(lv.adapter.getItem(0).toString().contains(Repository.ALL))
        d.dismiss(); idle()
        // przytrzymanie „Wszystkie” – ukrycie w menu
        val gr = a.findViewById<RecyclerView>(R.id.groups)
        gr.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick(); idle()
        val m = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        m.listView.performItemClick(null, 0, 0); idle()
        assertTrue(Repository.ALL in Prefs.groupHidden)
        assertEquals(listOf("G1", "G2", "G0"), Repository.groups())
        assertEquals(12, Repository.channels.size)
        // kolejność z okna kategorii obowiązuje też w edytorze
        Repository.moveGroup("G0", -1000)
        c.pause().stop().destroy()
        val c2 = Robolectric.buildActivity(EditorActivity::class.java).setup(); idle()
        assertEquals(listOf("Wszystkie", "G0", "G1", "G2"), (c2.get().findViewById<RecyclerView>(R.id.groups).adapter as GroupAdapter).items.take(4).map { if (it.startsWith("Wszystkie")) "Wszystkie" else it })
        c2.pause().stop().destroy()
        Prefs.groupHidden = emptyList(); Prefs.groupOrder = emptyList()
    }

    @Test fun panelTitleOnlyChannelName() {
        val c = player(); val a = c.get()
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)); idle(1000)
        assertEquals("K3", a.findViewById<TextView>(R.id.lpEpgTitle).text.toString())
        c.pause().stop().destroy()
    }

    /** Dawny pasek informacji pod dotknięciem (7.17: w Nowoczesnym – OSD „Obsidian Stream”). */
    @Test @Config(qualifiers = "w400dp-h800dp-port-xhdpi") fun phoneTapShowsChannelListAndCompactButtons() = classicTheme {
        Prefs.orientation = 2
        val c = player(); val a = c.get()
        val root = a.window.decorView
        val t = SystemClock.uptimeMillis()
        a.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 300f, 900f, 0))
        a.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 300f, 900f, 0)); idle(600)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.overlay).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.zapPanel).visibility)
        val bar = a.findViewById<View>(R.id.touchBar)
        assertEquals("☰", a.findViewById<TextView>(R.id.tMenu).text.toString())
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        assertTrue("${bar.measuredWidth}", bar.measuredWidth + Ui.dp(32) <= root.width)
        c.pause().stop().destroy()
        // menu w pionie (7.22): kategorie z nazwami jako poziomy rząd pigułek, bez rozsuwania
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val g = m.get().findViewById<RecyclerView>(R.id.groups)
        assertEquals(androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, (g.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).orientation); assertTrue((g.adapter as GroupAdapter).chips)
        assertFalse((g.adapter as GroupAdapter).compact)
        g.findViewHolderForAdapterPosition(1)!!.itemView.performClick(); idle(400)
        assertEquals(androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, (g.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).orientation); assertTrue((g.adapter as GroupAdapter).chips)
        assertEquals("G1", (g.adapter as GroupAdapter).selected)
        m.pause().stop().destroy(); Prefs.orientation = 0
    }
}

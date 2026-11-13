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
class V45Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList()
        val f = File(ctx.filesDir, "v45.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v45/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun groupsOrderAndVisibility() {
        assertEquals(listOf(Repository.ALL, "G1", "G2", "G0"), Repository.groups())
        Repository.moveGroup("G0", -1000); Repository.toggleGroupVisible("G2")
        assertEquals(listOf("G0", Repository.ALL, "G1"), Repository.groups())
        assertEquals(12, Repository.channelsFor(Repository.ALL).size)            // kanały ukrytej kategorii zostają w „Wszystkie”
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        val gr = a.findViewById<RecyclerView>(R.id.groups)
        val ga = gr.adapter as GroupAdapter
        assertEquals(listOf("G0", Repository.ALL, "G1", MainActivity.EDIT_GROUPS), ga.items)
        // „Edytuj” otwiera okno; Przywróć domyślne
        gr.findViewHolderForAdapterPosition(3)!!.itemView.performClick(); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        assertEquals(4, lv.adapter.count)
        assertTrue(lv.adapter.getItem(3).toString().contains("✖") && lv.adapter.getItem(3).toString().contains("G2"))
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick(); idle()
        assertEquals(listOf(Repository.ALL, "G1", "G2", "G0", MainActivity.EDIT_GROUPS), ga.items)
        c.pause().stop().destroy()
    }

    @Test fun liveChoiceKeepsWindowSize() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val d = Dialogs.choiceLive(c.get(), "Wybór", listOf("A", "B", "C"), 0) {}; idle()
        val w0 = d.window!!.attributes.width
        d.listView.performItemClick(d.listView.getChildAt(1), 1, 1); idle()
        assertEquals(w0, d.window!!.attributes.width)
        assertEquals(1, d.listView.checkedItemPosition)
        d.dismiss(); c.pause().stop().destroy()
    }

    @Test fun guideBackGoesToTopAndFixedDescription() {
        val c = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java)
            .putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[6].key)).setup(); idle(600)
        val a = c.get()
        assertNotNull(a.window.decorView.findFocus()?.getTag(R.id.guide_cell) ?: a.window.decorView.findFocus()?.takeIf { it.id == R.id.chCell })
        val desc = a.findViewById<TextView>(R.id.dDesc)
        assertEquals(desc.minLines, desc.maxLines)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(R.id.btnGroup, a.window.decorView.findFocus()?.id)
        assertFalse(a.isFinishing)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.window.decorView.findFocus()?.getTag(R.id.guide_cell) != null)
        // cyfry: skok do kanału
        key(a, KeyEvent.KEYCODE_1); key(a, KeyEvent.KEYCODE_1); idle(1500)
        val rows = a.findViewById<RecyclerView>(R.id.rows)
        val row = generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.first { it.parent === rows }
        assertEquals(10, rows.getChildAdapterPosition(row))
        a.onBackPressedDispatcher.onBackPressed(); idle()
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(a.isFinishing)
        c.pause().stop().destroy()
    }
}

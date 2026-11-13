package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class UiFlowTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setup() {
        val m3u = buildString {
            append("#EXTM3U\n")
            for (i in 1..12) {
                val g = if (i <= 6) "Ogólne" else "Sport"
                append("#EXTINF:-1 tvg-id=\"ch$i\" group-title=\"$g\" catchup=\"shift\",Kanał $i\nhttp://test/$i.m3u8\n")
            }
        }
        val f = File(ctx.filesDir, "pl.m3u"); f.writeText(m3u)
        Prefs.recent = emptyList(); Prefs.favorites = emptyList()
        Repository.resetAllEdits()
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true
        runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun rv(a: android.app.Activity, id: Int) = a.findViewById<RecyclerView>(id).also {
        it.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        it.layout(0, 0, 1200, 700)
    }
    private fun key(a: android.app.Activity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle()
    }

    @Test fun mainShowsGroupsChannelsAndFavourites() {
        Repository.toggleFav(Repository.channels[3])
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val a = c.get()
        val groups = rv(a, R.id.groups).adapter!!
        assertEquals(5, groups.itemCount) // Ulubione, Wszystkie, Ogólne, Sport + „Edytuj”
        assertEquals(Repository.FAV, Repository.groups().first())
        assertEquals(12, rv(a, R.id.channels).adapter!!.itemCount)
        Prefs.showFavGroup = false
        assertEquals(3, Repository.groups().size)
        Prefs.showFavGroup = true
        assertEquals(4, Repository.search("Kanał 1").size) // 1, 10, 11, 12
        assertEquals(1, Repository.search("7").size)        // po numerze
        c.pause().stop().destroy()
    }

    @Test fun allPresetsStyleWithoutCrash() {
        for (p in Ui.PRESETS) {
            Ui.applyPreset(p.id); Ui.changed()
            for (st in 0..4) { Ui.progressStyle = st }
            val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
            val root = c.get().window.decorView
            // klik w zakładki
            for (tab in listOf("Listy", "Menu i kanały", "Odtwarzanie", "Program TV", "Archiwum (catch-up)", "System", "Wygląd")) {
                findText(root, tab)!!.performClick(); idle()
            }
            c.pause().stop().destroy()
        }
        Ui.applyPreset("modern")
    }

    @Test fun editorMoveModeWithRemote() {
        val c = Robolectric.buildActivity(EditorActivity::class.java).setup(); idle()
        val a = c.get()
        val list = rv(a, R.id.channels); idle()
        val first = Repository.allChannels[0]
        // wejście w tryb przenoszenia przez reflection-free API: wywołaj startMove poprzez menu nie da się w teście – symulujemy prywatne pole
        val m = EditorActivity::class.java.getDeclaredMethod("startMove", pl.iptv.tv.data.Channel::class.java)
        m.isAccessible = true; m.invoke(a, first); idle()
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(first.key, Repository.allChannels[2].key)
        assertEquals(3, Repository.byKey(first.key)!!.number)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(list.adapter!!.itemCount == 12)
        // zmiany zapisują się na dysku
        assertTrue(File(ctx.filesDir, "edits.json").readText().contains("1.m3u8"))
        c.pause().stop().destroy()
    }

    @Test fun playerPanelsOpenWithArrows() {
        val ch = Repository.channels[2]
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        val c = Robolectric.buildActivity(PlayerActivity::class.java, i).setup(); idle()
        val a = c.get()
        val left = a.findViewById<View>(R.id.leftPanel); val right = a.findViewById<View>(R.id.rightPanel)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.VISIBLE, left.visibility)
        assertEquals(12, a.findViewById<RecyclerView>(R.id.lpChannels).adapter!!.itemCount)
        a.onBackPressedDispatcher.onBackPressed(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.GONE, left.visibility)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.VISIBLE, right.visibility)
        a.onBackPressedDispatcher.onBackPressed(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.GONE, right.visibility)
        // zap
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(Repository.channels[3].key, Prefs.lastChannel)
        // panel wyłączony w ustawieniach
        Prefs.channelPanel = false
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(View.GONE, left.visibility)
        Prefs.channelPanel = true
        c.pause().stop().destroy()
    }

    @Test fun recentRespectsLimitAndOrder() {
        Prefs.recentLimit = 5
        Repository.channels.take(8).forEach { Repository.addRecent(it) }
        val r = Repository.recent()
        assertEquals(5, r.size)
        assertEquals(Repository.channels[7].key, r[0].key)
        Repository.addRecent(Repository.channels[0])
        assertEquals(Repository.channels[0].key, Repository.recent()[0].key)
    }

    @Test fun touchTwoTapPlaysAndSwipeOpensPanel() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val a = c.get()
        val list = rv(a, R.id.channels); idle()
        val item = list.findViewHolderForAdapterPosition(1)!!.itemView
        val ch2 = Repository.channels[1]
        val click = MainActivity::class.java.getDeclaredMethod("onChannelClick", pl.iptv.tv.data.Channel::class.java, View::class.java)
        click.isAccessible = true
        val touchView = object : View(a) { override fun isInTouchMode() = true }
        click.invoke(a, ch2, touchView); idle()
        assertNull(shadowOf(a).nextStartedActivity)           // dotyk: 1. dotknięcie – tylko zaznacza i pokazuje EPG
        assertEquals("Kanał 2", a.findViewById<TextView>(R.id.epgChannel).text.toString())
        click.invoke(a, ch2, touchView); idle()
        val started = shadowOf(a).nextStartedActivity          // dotyk: 2. dotknięcie – odtwarzanie
        assertEquals(PlayerActivity::class.java.name, started.component!!.className)
        item.performClick(); idle()                            // pilot: OK od razu odtwarza
        assertNotNull(shadowOf(a).nextStartedActivity)
        // menu kontekstowe (przytrzymanie) i dialogi edycji
        item.performLongClick(); idle()
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dlg.isShowing)
        dlg.listView.performItemClick(null, 0, 0); idle()       // dodaj do ulubionych
        assertTrue(Repository.isFav(Repository.channels[1]))
        c.pause().stop().destroy()

        val pc = Robolectric.buildActivity(PlayerActivity::class.java, started).setup(); idle()
        val p = pc.get()
        fun ev(act: Int, x: Float, t: Long) = android.view.MotionEvent.obtain(0, t, act, x, 300f, 0)
        p.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 100f, 0))
        p.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, 400f, 30))
        p.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 700f, 60))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.VISIBLE, p.findViewById<View>(R.id.leftPanel).visibility)
        p.findViewById<View>(R.id.scrim).performClick(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.GONE, p.findViewById<View>(R.id.leftPanel).visibility)
        // pojedyncze dotknięcie – pasek informacji z przyciskami
        p.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 600f, 1000))
        p.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 600f, 1050))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(View.VISIBLE, p.findViewById<View>(R.id.touchBar).visibility)
        p.findViewById<View>(R.id.tRecent).performClick(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.VISIBLE, p.findViewById<View>(R.id.rightPanel).visibility)
        pc.pause().stop().destroy()
    }

    @Test fun editorDialogsRenameAndGroup() {
        val c = Robolectric.buildActivity(EditorActivity::class.java).setup(); idle()
        val a = c.get()
        val ch = Repository.allChannels[0]
        ChannelActions.menu(a, ch) {}; idle()
        val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(menu.listView.adapter.count >= 7)
        Repository.edit(ch.key) { it.name = "Nowa nazwa"; it.group = "Moja" }
        assertEquals("Nowa nazwa", Repository.byKey(ch.key)!!.name)
        assertTrue("Moja" in Repository.realGroups)
        Repository.edit(ch.key) { it.name = "Kanał 1" }          // = oryginał -> nadpisanie usuwane
        assertEquals(null, Repository.override(ch.key).name)
        Repository.renameGroup("Sport", "Sporty")
        assertTrue("Sporty" in Repository.realGroups && "Sport" !in Repository.realGroups)
        Repository.setGroupHidden("Sporty", true)
        assertEquals(6, Repository.channels.size)
        c.pause().stop().destroy()
    }

    private fun findText(v: View, t: String): View? {
        if (v is TextView && v.text.toString() == t) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), t)?.let { return it }
        return null
    }
}

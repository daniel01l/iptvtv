package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
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
import pl.iptv.tv.data.KeyMap
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QuickMenu
import pl.iptv.tv.data.Repository
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class V19Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val H = 3_600_000L
    private val base = System.currentTimeMillis() / (30 * 60_000) * (30 * 60_000)
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).let { if (ms > 0) it.idleFor(Duration.ofMillis(ms)) else it.idle() }
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(500) }

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.batteryAsked = true; Prefs.setupDone = true; Prefs.startAction = 0
        Prefs.keyMapJson = ""; QuickMenu.reset(); Prefs.parentalOn = false; Prefs.playlistsJson = "[]"
        Prefs.guideRows = 6; Prefs.guideWindow = 120; Prefs.guideStyle = 0; Prefs.guideDesc = 1; Prefs.guideChannelCol = 0
        val m3u = File(ctx.filesDir, "v19.m3u")
        m3u.writeText((1..4).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\",K$it\nhttp://v/$it\n" })
        Repository.resetAllEdits()
        runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
        val sb = StringBuilder("<tv>")
        for (c in 1..4) sb.append("<channel id=\"c$c\"><display-name>K$c</display-name></channel>")
        var x = base - 24 * H
        while (x < base + 4 * 24 * H) { for (c in 1..4) sb.append("<programme start=\"${t(x)}\" stop=\"${t(x + H)}\" channel=\"c$c\"><title>Audycja $c ${(x - base) / H}</title><desc>Opis $c ${(x - base) / H}</desc></programme>"); x += H }
        val f = File(ctx.filesDir, "v19.xml"); f.writeText(sb.append("</tv>").toString())
        runBlocking { Repository.loadEpg(f.absolutePath) }
    }

    private fun player() = Robolectric.buildActivity(PlayerActivity::class.java,
        Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key)).setup().also { idle() }

    @Test fun leftPanelShows3DaysAhead() {
        assertEquals(72, Prefs.panelHours)
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val n = a.findViewById<RecyclerView>(R.id.lpProgrammes).adapter!!.itemCount
        assertTrue("programów: $n", n >= 72)
        assertFalse(a.findViewById<TextView>(R.id.lpEpgTitle).text.contains("najbliższe"))     // sama nazwa kanału
        c.pause().stop().destroy()
    }

    @Test fun keyMappingSidePresetAndLearnedKey() {
        KeyMap.applyPreset(1)                                           // ◄ ► przełączają kanały
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(Repository.channels[2].key, Prefs.lastChannel)
        assertEquals(View.GONE, a.findViewById<View>(R.id.rightPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(Repository.channels[1].key, Prefs.lastChannel)
        key(a, KeyEvent.KEYCODE_DPAD_UP)                                 // ▲ = panel kanałów
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.leftPanel).visibility)
        a.onBackPressedDispatcher.onBackPressed(); idle(500)
        // własny klawisz: czerwony = menu funkcji
        KeyMap.assign(KeyMap.Action.QUICK_MENU, KeyEvent.KEYCODE_PROG_RED)
        assertEquals(-1, KeyMap.presetIndex())
        key(a, KeyEvent.KEYCODE_PROG_RED)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.quickBar).visibility)
        a.onBackPressedDispatcher.onBackPressed(); idle(500)
        // klawisz „powrót” wraca do poprzedniego kanału
        KeyMap.assign(KeyMap.Action.LAST, KeyEvent.KEYCODE_PROG_GREEN)
        a.onBackPressedDispatcher.onBackPressed(); idle(500)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); val now = Prefs.lastChannel
        key(a, KeyEvent.KEYCODE_PROG_GREEN)
        assertNotEquals(now, Prefs.lastChannel)
        // przypisanie przenosi klawisz z innej akcji
        KeyMap.assign(KeyMap.Action.INFO, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(KeyEvent.KEYCODE_DPAD_RIGHT in KeyMap.map()[KeyMap.Action.CH_NEXT]!!)
        c.pause().stop().destroy()
        Prefs.keyMapJson = ""
    }

    @Test fun quickMenuOrderAndVisibility() {
        QuickMenu.toggle("settings"); QuickMenu.move("guide", -100)
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val q = a.findViewById<RecyclerView>(R.id.quickList)
        q.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 600, 4000)
        val labels = (0 until q.adapter!!.itemCount).map { q.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<TextView>(R.id.label).text.toString() }
        assertTrue(labels.toString(), labels.first().contains("Program TV"))
        assertFalse(labels.any { it.trim().endsWith("Ustawienia") })
        // ostatnia pozycja: edycja menu w oknie – przywrócenie widoczności „Ustawienia”
        assertTrue(labels.last().contains("Edytuj to menu"))
        q.findViewHolderForAdapterPosition(labels.lastIndex)!!.itemView.findViewById<View>(R.id.row).performClick(); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        assertEquals(QuickMenu.order().size, lv.adapter.count)
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick(); idle()
        q.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 600, 4000)
        val after = (0 until q.adapter!!.itemCount).mapNotNull { q.findViewHolderForAdapterPosition(it)?.itemView?.findViewById<TextView>(R.id.label)?.text?.toString() }
        assertTrue(after.toString(), after.any { it.contains("Ustawienia") })
        c.pause().stop().destroy()
        QuickMenu.reset()
    }

    @Test fun guideShowsDescriptionAndLookOptions() {
        val c = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java)
            .putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[0].key)).setup(); idle(600)
        val a = c.get()
        assertTrue(a.findViewById<TextView>(R.id.dTitle).text.startsWith("Audycja 1"))
        assertTrue(a.findViewById<TextView>(R.id.dDesc).text.startsWith("Opis 1"))
        // inny program: opis podąża za zaznaczeniem
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.findViewById<TextView>(R.id.dTitle).text.startsWith("Audycja 2"))
        // wiersze wg ustawienia „kanałów na ekranie”
        val rv = a.findViewById<RecyclerView>(R.id.rows)
        val h6 = rv.findViewHolderForAdapterPosition(0)!!.itemView.layoutParams.height
        Prefs.guideRows = 3; Prefs.guideStyle = 1; Prefs.guideChannelCol = 1; Prefs.guideDesc = 0
        val m = GuideActivity::class.java.getDeclaredMethod("applyLook"); m.isAccessible = true; m.invoke(a)
        rv.adapter!!.notifyDataSetChanged(); idle(300); rv.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)); rv.layout(0, 0, 1200, 500); idle(300)
        val h3 = rv.findViewHolderForAdapterPosition(0)!!.itemView.layoutParams.height
        assertTrue("$h6 -> $h3", h3 > h6)
        assertEquals(View.GONE, a.findViewById<View>(R.id.descPanel).visibility)
        assertEquals(View.GONE, rv.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.name).visibility)
        assertEquals(7, GuideLook.items(a, {}).size)
        c.pause().stop().destroy()
        Prefs.guideRows = 6; Prefs.guideStyle = 0; Prefs.guideChannelCol = 0; Prefs.guideDesc = 1
    }

    @Test fun settingsPilotTabAndQuickEditor() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 9)).setup(); idle()
        val a = c.get()
        fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        val texts = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.any { it.contains("Poprzedni kanał") })
        assertTrue(texts.any { it == "▲, CH+" })
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Odtwarzanie" }.performClick(); idle()
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Menu boczne (2× ◄)") }.performClick(); idle()
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("1. ⚙ Ustawienia") })
        c.pause().stop().destroy()
    }
}

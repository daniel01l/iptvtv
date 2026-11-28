package pl.iptv.tv.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
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
class RightPanelTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val H = 3_600_000L
    private val base = System.currentTimeMillis() / 60_000 * 60_000
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).let { if (ms > 0) it.idleFor(Duration.ofMillis(ms)) else it.idle() }

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.batteryAsked = true; Prefs.startAction = 0
        Prefs.recentPanel = true; Prefs.channelPanel = true; Prefs.quickApps = emptyList()
        Prefs.keyMapJson = ""; pl.iptv.tv.data.QuickMenu.reset()
        val m3u = File(ctx.filesDir, "pl.m3u")
        m3u.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" catchup=\"shift\" catchup-days=\"1\",Jedynka\nhttp://t/1\n" +
            "#EXTINF:-1 tvg-id=\"b\",Dwójka\nhttp://t/2\n")
        Repository.resetAllEdits()
        runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
        fun p(ch: String, f: Long, to: Long, title: String, desc: String = "") =
            "<programme start=\"${t(f)}\" stop=\"${t(to)}\" channel=\"$ch\"><title>$title</title><desc>$desc</desc></programme>\n"
        val xml = "<tv><channel id=\"a\"><display-name>Jedynka</display-name></channel>\n" +
            p("a", base - 3 * H, base - 2 * H, "Poranny serwis") + p("a", base - 2 * H, base - H, "Film archiwalny") +
            p("a", base - H, base + H, "Teraz na żywo", "Szczegółowy opis bieżącego programu") +
            p("a", base + H, base + 2 * H, "Wieczorny program") + "</tv>"
        val f = File(ctx.filesDir, "epg.xml"); f.writeText(xml)
        runBlocking { Repository.loadEpg(f.absolutePath) }
        Repository.clearRecent(); Repository.addRecent(Repository.channels[1]); Repository.addRecent(Repository.channels[0])
    }

    private fun key(a: android.app.Activity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(600)
    }

    private fun player(): org.robolectric.android.controller.ActivityController<PlayerActivity> {
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key)
        return Robolectric.buildActivity(PlayerActivity::class.java, i).setup().also { idle() }
    }

    @Test fun rightPanelQuarterWithInfoArchiveAndMenu() {
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val right = a.findViewById<View>(R.id.rightPanel)
        assertEquals(View.VISIBLE, right.visibility)
        assertEquals((320 * a.resources.displayMetrics.density + 0.5f).toInt(), a.findViewById<View>(R.id.rightColumn).layoutParams.width)   // 1280dp / 4
        assertEquals(2, a.findViewById<RecyclerView>(R.id.rpList).adapter!!.itemCount)
        assertEquals("Teraz na żywo", a.findViewById<TextView>(R.id.infoTitle).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.infoDesc).text.contains("Szczegółowy opis"))
        val infoList = a.findViewById<RecyclerView>(R.id.infoProgrammes)
        assertEquals(4, infoList.adapter!!.itemCount)                       // 2 wcześniejsze + bieżący + następny
        assertEquals(View.GONE, a.findViewById<View>(R.id.quickBar).visibility)
        // 2. strzałka w prawo nie otwiera już menu
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.GONE, a.findViewById<View>(R.id.quickBar).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(View.GONE, right.visibility)
        // ◄ ◄ = panel kanałów + menu funkcji po lewej
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.leftPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val qb = a.findViewById<View>(R.id.quickBar)
        assertEquals(View.VISIBLE, qb.visibility)
        assertTrue(qb.parent === a.findViewById<View>(R.id.leftPanel))
        assertTrue(generateSequence(a.window.decorView.findFocus() as View?) { it.parent as? View }.any { it.id == R.id.quickBar })
        val q = a.findViewById<RecyclerView>(R.id.quickList)
        q.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 600, 4000)
        val labels = (0 until q.adapter!!.itemCount).map { q.findViewHolderForAdapterPosition(it)?.itemView?.findViewById<TextView>(R.id.label)?.text.toString() }
        assertTrue(labels.toString(), labels.first { it != PlayerActivity.DASHBOARD_LABEL }.contains("Ustawienia")); assertTrue(labels.any { it.contains("Edytor EPG") })
        assertTrue(labels[labels.size - 2].contains("Dodaj aplikację")); assertTrue(labels.last().contains("Edytuj to menu"))
        // ► zamyka menu, fokus wraca do listy kanałów
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.GONE, qb.visibility)
        assertTrue(generateSequence(a.window.decorView.findFocus() as View?) { it.parent as? View }.any { it.id == R.id.lpChannels })
        // menu otwiera ustawienia
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        q.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 600, 4000)
        q.findViewHolderForAdapterPosition(if (labels.first() == PlayerActivity.DASHBOARD_LABEL) 1 else 0)!!.itemView.findViewById<View>(R.id.row).performClick(); idle()
        assertEquals(SettingsActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }

    @Test fun pastProgrammePlaysCatchupFromInfoAndLeftPanel() {
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val lp = a.findViewById<RecyclerView>(R.id.lpProgrammes)
        assertEquals(4, lp.adapter!!.itemCount)                            // lewy panel też pokazuje archiwum
        a.onBackPressedDispatcher.onBackPressed(); idle(600)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val info = a.findViewById<RecyclerView>(R.id.infoProgrammes)
        info.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)); info.layout(0, 0, 600, 2000)
        (info.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPosition(0); info.layout(0, 0, 600, 2000)
        info.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.row).performClick(); idle(600)
        // odtwarzanie z archiwum: panel zamknięty, nakładka „ARCHIWUM”
        assertEquals(View.GONE, a.findViewById<View>(R.id.rightPanel).visibility)
        assertTrue(a.findViewById<TextView>(R.id.ctlInfo).text.contains("⟲"))          // informacje na dolnym pasku archiwum
        c.pause().stop().destroy()
    }

    @Test fun addedAppIsLaunchedFromMenu() {
        val ai = ActivityInfo().apply {
            packageName = "com.example.yt"; name = "com.example.yt.Main"; nonLocalizedLabel = "Moja aplikacja"
            applicationInfo = ApplicationInfo().apply { packageName = "com.example.yt" }
        }
        shadowOf(ctx.packageManager).addOrUpdateActivity(ai)
        Prefs.quickApps = listOf(ComponentName("com.example.yt", "com.example.yt.Main").flattenToString())
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val q = a.findViewById<RecyclerView>(R.id.quickList)
        q.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY)); q.layout(0, 0, 600, 3000)
        val idx = (0 until q.adapter!!.itemCount).first {
            q.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<TextView>(R.id.label).text == "Moja aplikacja"
        }
        val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.example.yt", "com.example.yt.Main"))
        shadowOf(ctx.packageManager).addResolveInfoForIntent(intent, android.content.pm.ResolveInfo().apply { activityInfo = ai })
        q.findViewHolderForAdapterPosition(idx)!!.itemView.findViewById<View>(R.id.row).performClick(); idle()
        assertEquals("com.example.yt", shadowOf(a).nextStartedActivity.component!!.packageName)
        c.pause().stop().destroy()
    }
}

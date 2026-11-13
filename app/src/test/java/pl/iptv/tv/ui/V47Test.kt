package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
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
import pl.iptv.tv.data.Scores
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V47Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private val H = 3_600_000L

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.orientation = 2; Prefs.scoresEnabled = true
        val f = File(ctx.filesDir, "v47.m3u").apply { writeText((1..8).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 2}\",K$it\nhttp://v47/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val now = System.currentTimeMillis(); val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val xml = "<tv>" + (1..8).joinToString("") { c -> (-30..30).joinToString("") { h ->
            val title = if (c == 3 && h == 0) "Piłka nożna: Leeds United - Tottenham Hotspur" else "P$c $h"
            "<programme start=\"${fmt.format(Date(now / H * H + h * H))}\" stop=\"${fmt.format(Date(now / H * H + (h + 1) * H))}\" channel=\"c$c\"><title>$title</title><desc>Opis $c $h</desc></programme>" } } + "</tv>"
        val x = File(ctx.filesDir, "v47.xml").apply { writeText(xml) }
        runBlocking { Repository.loadEpg(x.absolutePath) }
    }

    @Test fun guideInPortraitIsList() {
        val g = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[2].key)).setup(); idle()
        val next = shadowOf(g.get()).nextStartedActivity
        assertEquals(GuideListActivity::class.java.name, next.component!!.className)
        assertTrue(g.get().isFinishing)
        val c = Robolectric.buildActivity(GuideListActivity::class.java, next).setup(); idle()
        val a = c.get()
        val progs = a.findViewById<RecyclerView>(R.id.progs)
        assertTrue(progs.adapter!!.itemCount in 20..26)                          // jeden dzień
        assertTrue(a.findViewById<TextView>(R.id.dTitle).text.contains("Leeds"))  // bieżący program kanału 3
        assertTrue(a.findViewById<TextView>(R.id.dDesc).maxLines == 3)
        a.findViewById<View>(R.id.btnDayNext).performClick(); idle()
        assertTrue(a.findViewById<TextView>(R.id.dayLabel).text.startsWith("Jutro"))
        val chips = a.findViewById<RecyclerView>(R.id.chips)
        chips.findViewHolderForAdapterPosition(0)!!.itemView.performClick(); idle()
        assertTrue(a.findViewById<TextView>(R.id.dTitle).text.contains("P1"))
        c.pause().stop().destroy()
    }

    @Test fun settingsTabsOnTopInPortrait() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 8)).setup(); idle()
        val a = c.get()
        val strips = all(a.window.decorView).filterIsInstance<android.widget.HorizontalScrollView>()
        val tabs = strips.map { it.getChildAt(0) }.filterIsInstance<android.widget.LinearLayout>().first { it.childCount == SettingsActivity.TAB_NAMES.size }
        assertEquals(android.widget.LinearLayout.HORIZONTAL, tabs.orientation)
        assertTrue((tabs.parent as View).let { it.top < a.resources.displayMetrics.heightPixels / 4 })
        val content = all(a.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
        assertTrue(content.width > a.resources.displayMetrics.widthPixels * 0.85)   // treść na pełną szerokość
        c.pause().stop().destroy()
    }

    @Test fun epgSearchFitsPortrait() {
        val c = Robolectric.buildActivity(EpgSearchActivity::class.java).setup(); idle()
        val a = c.get()
        val row = a.findViewById<View>(R.id.fAll).parent as View
        assertTrue(row.parent is android.widget.HorizontalScrollView)
        val close = a.findViewById<View>(R.id.btnClose)
        val loc = IntArray(2); close.getLocationInWindow(loc)
        assertTrue(loc[0] + close.width <= a.resources.displayMetrics.widthPixels)
        c.pause().stop().destroy()
    }

    @Test fun tutorialOnceOnFirstLiveChannel() {
        Tutorial.auto = true; Prefs.tutorialShown = false
        try {
            val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key)
            val c = Robolectric.buildActivity(PlayerActivity::class.java, i).setup(); idle(2000)
            val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(d.isShowing)
            val first = d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString(); assertTrue(first.startsWith("Jak korzystać"))
            assertTrue(Prefs.tutorialShown)
            d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick(); idle()
            val d2 = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val second = d2.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()
            assertTrue(second.startsWith("Jak korzystać") && second != first)          // druga wersja (pilot ↔ dotyk)
            d2.dismiss(); c.pause().stop().destroy()
            // drugie uruchomienie – bez instrukcji
            val c2 = Robolectric.buildActivity(PlayerActivity::class.java, i).setup(); idle(2000)
            assertSame(d2, org.robolectric.shadows.ShadowDialog.getLatestDialog())
            c2.pause().stop().destroy()
        } finally { Tutorial.auto = false }
    }

    @Test fun liveScoreShownOnChannelWithMatch() {
        val now = System.currentTimeMillis()
        val m = Scores.Match("1", "eng.1", "PL", "Leeds", "Spurs", "357", "367", 1, 0, Scores.State.LIVE, "30'", now - H / 2,
            homeFull = "Leeds United", awayFull = "Tottenham Hotspur")
        val live = Scores.buildLive(listOf(m))
        assertEquals("⚽ Leeds 1:0 Spurs  30'", live[Repository.channels[2].key])
        assertEquals(1, live.size)
        // lista kanałów w menu pokazuje wynik
        val f = Scores::class.java.getDeclaredField("liveByChannel").apply { isAccessible = true }
        f.set(Scores, live)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val rv = c.get().findViewById<RecyclerView>(R.id.channels)
        val t = rv.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<TextView>(R.id.now).text.toString()
        assertTrue(t, t.startsWith("⚽ Leeds 1:0 Spurs"))
        Prefs.scoresEnabled = false; rv.adapter!!.notifyDataSetChanged(); idle()
        assertFalse(rv.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<TextView>(R.id.now).text.startsWith("⚽"))
        c.pause().stop().destroy(); f.set(Scores, emptyMap<String, String>()); Prefs.scoresEnabled = true; Prefs.orientation = 0
    }
}

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
import pl.iptv.tv.R
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/** v5.6: nazwy list bez obcinania, lista po OK z kategoriami, OK na kategorii, parametry trybu słabszych urządzeń, silnik, podwójne okna. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V56Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0
        val f = File(ctx.filesDir, "v56.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v56/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun iconStrippingKeepsNamePrefixes() {
        assertEquals("[PL] Sport", Ui.stripIcon("[PL] Sport"))
        assertEquals("|PL| Filmy", Ui.stripIcon("|PL| Filmy"))
        assertEquals("+1 Kanał", Ui.stripIcon("+1 Kanał"))
        assertEquals("#Muzyka", Ui.stripIcon("#Muzyka"))
        assertEquals("„Cytat”", Ui.stripIcon("„Cytat”"))
        assertEquals("Adres listy", Ui.stripIcon("🔗 Adres listy"))
        assertEquals("Kategorie", Ui.stripIcon("⇅ Kategorie"))
        assertEquals("Przywróć", Ui.stripIcon("↺ Przywróć"))
    }

    @Test fun listNamesInSettingsNotTruncated() {
        Playlists.add("[PL] Sport", "/x.m3u"); Playlists.add("|VIP| Filmy", "/y.m3u")
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent(ctx, SettingsActivity::class.java).putExtra("tab", 0)).setup(); idle()
        val all = texts(c.get().window.decorView)
        assertTrue(all.toString(), all.any { it.startsWith("[PL] Sport") })
        assertTrue(all.toString(), all.any { it.startsWith("|VIP| Filmy") })
        c.pause().stop().destroy()
    }

    /** Dawny pasek informacji i lista kanałów pod OK (7.17: w Nowoczesnym – OSD „Obsidian Stream”, V717OsdTest). */
    @Test fun zapListSwitchesCategoryAndFocusesFirstChannel() = classicTheme {
        Prefs.zapListEnabled = true; Prefs.miniMenuEnabled = false; Prefs.channelPanel = true
        val g = Repository.groups()
        val start = Repository.channelsFor(g[0])[2]
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, start.key).putExtra(PlayerActivity.EXTRA_GROUP, g[0])).setup(); idle(600)
        val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val panel = a.findViewById<View>(R.id.zapPanel)
        assertEquals(View.VISIBLE, panel.visibility)
        val header = a.findViewById<TextView>(R.id.zapGroup)
        assertTrue(header.text.toString(), header.text.contains(g[0]))
        // ► – następna kategoria w ramce u góry, zaznaczony od razu jej pierwszy kanał
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue(header.text.toString(), header.text.contains(g[1]))
        val zl = a.findViewById<RecyclerView>(R.id.zapList)
        val first = Repository.channelsFor(g[1]).first()
        assertEquals(0, zl.findContainingViewHolder(a.window.decorView.findFocus()!!)?.bindingAdapterPosition)
        // samo OK włącza ten kanał
        key(a, KeyEvent.KEYCODE_DPAD_CENTER); idle(600)
        assertEquals(first.key, Playback.channelKey)
        c.pause().stop().destroy(); Playback.stop()
    }

    @Test fun okOnCategoryEntersIt() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        val gr = a.findViewById<RecyclerView>(R.id.groups)
        val ch = a.findViewById<RecyclerView>(R.id.channels)
        val ga = gr.adapter as GroupAdapter
        val target = ga.items.indexOf(Repository.groups()[2])
        gr.scrollToPosition(target); idle()
        gr.findViewHolderForAdapterPosition(target)!!.itemView.apply { requestFocus() }.performClick(); idle()
        val f = a.window.decorView.findFocus()!!
        // fokus na pierwszym kanale wybranej kategorii
        assertEquals(0, ch.findContainingViewHolder(f)?.bindingAdapterPosition)
        assertEquals(Repository.channelsFor(Repository.groups()[2]).first().key, (ch.adapter as ChannelAdapter).items.first().key)
        c.pause().stop().destroy()
    }

    @Test fun lowPerfParamsAndEngine() {
        Prefs.lowPerf = true; Prefs.resetLowPerfParams(); Prefs.playerEngine = 0
        Prefs.lowMaxHeight = 720
        var p = Playback.get(ctx)
        assertEquals(720, p.trackSelectionParameters.maxVideoHeight)
        Playback.stop()
        Prefs.lowMaxHeight = 0                                   // bez limitu
        p = Playback.get(ctx)
        assertEquals(Int.MAX_VALUE, p.trackSelectionParameters.maxVideoHeight)
        Playback.stop()
        Prefs.playerEngine = Playback.ENGINE_TUNNELING
        p = Playback.get(ctx)
        assertTrue((p.trackSelector as androidx.media3.exoplayer.trackselection.DefaultTrackSelector).parameters.tunnelingEnabled)
        Playback.stop()
        Prefs.resetLowPerfParams(); assertEquals(1080, Prefs.lowMaxHeight); assertEquals(450, Prefs.lowZapDelay)
        Prefs.playerEngine = 0; Prefs.lowPerf = false
    }

    @Test fun sameDialogNotShownTwice() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        val d1 = Ui.dialog(a).setTitle("Opcje kanału").setMessage("x").show()
        val d2 = Ui.dialog(a).setTitle("Opcje kanału").setMessage("x").show()     // podwójne zdarzenie
        idle()
        assertSame(d1, d2); assertTrue(d1.isShowing)
        // inne okno – otwiera się normalnie
        val d3 = Ui.dialog(a).setTitle("Inne").show(); idle()
        assertNotSame(d1, d3); assertTrue(d3.isShowing)
        d1.dismiss(); d3.dismiss(); idle()
        // po zamknięciu to samo okno można otworzyć ponownie
        val d4 = Ui.dialog(a).setTitle("Opcje kanału").show(); idle()
        assertNotSame(d1, d4); assertTrue(d4.isShowing)
        d4.dismiss(); c.pause().stop().destroy()
    }

    @Test fun settingsUseBehindOrientationInManifest() {
        val pm = ctx.packageManager
        val info = pm.getActivityInfo(android.content.ComponentName(ctx, SettingsActivity::class.java), 0)
        // bez wymuszonej orientacji przy starcie – brak chwilowego obrotu do poziomu na telefonie w pionie
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND, info.screenOrientation)
    }
}

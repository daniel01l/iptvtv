package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.R
import pl.iptv.tv.data.MiniMenu
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V40Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private val H = 3_600_000L
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private val base = System.currentTimeMillis() / H * H

    private fun xml(ch: IntRange, title: String) = "<tv>" + ch.joinToString("") { c -> (-2..3).joinToString("") { h ->
        "<programme start=\"${t(base + h * H)}\" stop=\"${t(base + (h + 1) * H)}\" channel=\"c$c\"><title>$title$c-$h</title><sub-title>Odc</sub-title><episode-num system=\"onscreen\">S01E0${h + 3}</episode-num></programme>" } } + "</tv>"

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.keyMapJson = ""; Prefs.zapListEnabled = true; Prefs.zapRows = 8; Prefs.miniMenuEnabled = true; Prefs.okLongAction = 1; MiniMenu.reset(); Prefs.epgUrl = ""
        val f = File(ctx.filesDir, "v40.m3u").apply { writeText((1..20).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\",K$it\nhttp://v40/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Playlists.syncActive(f.absolutePath)
    }

    private fun player(i: Int = 3) = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[i].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup().also { idle() }

    @Test fun multiEpgMergedAndKeptOnListChange() {
        val a = File(ctx.filesDir, "e1.xml").apply { writeText(xml(1..10, "A")) }
        val b = File(ctx.filesDir, "e2.xml").apply { writeText(xml(11..20, "B")) }
        Prefs.epgUrl = "${a.absolutePath} ${b.absolutePath}"
        assertEquals(listOf(a.absolutePath, b.absolutePath), Repository.epgUrls())
        assertEquals(120, runBlocking { Repository.loadEpg() })
        assertEquals("A1-0", Repository.current(Repository.channels[0])?.title)
        assertEquals("B20-0", Repository.current(Repository.channels[19])?.title)
        File(ctx.filesDir, "epg.bin").delete()
        assertTrue(runBlocking { Repository.loadEpgFromCache() })                // z dwóch plików w pamięci podręcznej
        assertEquals("B20-0", Repository.current(Repository.channels[19])?.title)
        // zmiana listy: lista bez EPG zachowuje adres
        Playlists.captureActive()
        val second = Playlists.add("Druga", File(ctx.filesDir, "v40.m3u").absolutePath)
        runBlocking { Repository.switchPlaylist(second.id) }
        assertEquals("${a.absolutePath} ${b.absolutePath}", Prefs.effectiveEpgUrl)
        assertEquals(Prefs.effectiveEpgUrl, Playlists.active()!!.epg)
        assertEquals(Prefs.DEFAULT_UPDATE_URL, Prefs.updateUrl)
        assertEquals("https://github.com/daniel01l/iptvtv/releases/latest/download/iptvtv.json", Prefs.updateUrl)
    }

    @Test fun mainMenuNoNextEpisodeAndDialogFocusOnOk() {
        Prefs.epgUrl = File(ctx.filesDir, "e3.xml").apply { writeText(xml(1..20, "S")) }.absolutePath
        runBlocking { Repository.loadEpg() }
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        val desc = a.findViewById<TextView>(R.id.epgDesc).text.toString()
        assertTrue(desc, desc.contains("S"))
        assertFalse(desc.contains("Następny odcinek"))
        // ikony motywu w kolumnie przycisków
        assertNotNull(a.findViewById<TextView>(R.id.btnSettings).compoundDrawables[1])
        Dialogs.confirm(a, "Pytanie", "Treść") {}; idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isFocused)
        d.dismiss(); idle()
        c.pause().stop().destroy()
    }

    @Test fun okShowsChannelListAboveInfoBar() {
        val c = player(3); val a = c.get()
        idle(6000)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val zp = a.findViewById<View>(R.id.zapPanel)
        assertEquals(View.VISIBLE, zp.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.overlay).visibility)
        val rv = a.findViewById<RecyclerView>(R.id.zapList)
        assertEquals(8 * (Ui.dp(36) + Ui.dp(4)) + Ui.dp(8), rv.layoutParams.height)
        fun focusedIdx() = rv.findContainingViewHolder(a.window.decorView.findFocus()!!)!!.bindingAdapterPosition
        assertEquals(3, focusedIdx())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(5, focusedIdx())
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals(4, focusedIdx())
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Repository.channels[4].key, Playback.channelKey)
        assertEquals(View.GONE, zp.visibility)
        // wyłączona w ustawieniach – sam pasek
        Prefs.zapListEnabled = false; idle(6000)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.GONE, zp.visibility)
        c.pause().stop().destroy()
    }

    @Test fun miniMenuIconsCentered() {
        val cn = android.content.ComponentName(ctx, SettingsActivity::class.java).flattenToString()
        MiniMenu.addApp(cn, "Apka")
        val c = player(); val a = c.get()
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(8, icons.childCount)                       // + kafelek „Silnik i akceleracja”
        for (i in 0 until icons.childCount) {
            val box = icons.getChildAt(i) as FrameLayout
            val child = box.getChildAt(0)
            assertEquals(android.view.Gravity.CENTER, (child.layoutParams as FrameLayout.LayoutParams).gravity)
            assertEquals(box.height / 2, child.top + child.height / 2)
        }
        Prefs.miniApps = emptyList()
        c.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-xhdpi") fun phonePortraitPanelsAndScores() {
        Prefs.recentPanel = true; Prefs.scoresEnabled = true
        val c = player(); val a = c.get()
        val w1 = a.findViewById<View>(R.id.lpCol1).layoutParams.width; val w2 = a.findViewById<View>(R.id.lpCol2).layoutParams.width
        assertTrue("${w1 + w2}", w1 + w2 + Ui.dp(30) <= a.resources.displayMetrics.widthPixels)
        a.findViewById<View>(R.id.tRecent).performClick(); idle(800)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rightPanel).visibility)
        a.findViewById<View>(R.id.rpScores).performClick(); idle(800)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.scoresColumn).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.rightColumn).visibility)      // w pionie zamiast ostatnich
        assertTrue(a.findViewById<View>(R.id.scoresColumn).layoutParams.width <= a.resources.displayMetrics.widthPixels)
        c.pause().stop().destroy()
    }

    @Test fun backToMenuKeepsWatchedChannel() {
        Prefs.lastChannel = Repository.channels[7].key
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        a.findViewById<View>(R.id.channels).requestFocus()
        c.pause(); idle(); Prefs.lastChannel = Repository.channels[9].key        // obejrzano inny kanał
        c.resume(); idle(1200)
        assertEquals("K10", a.findViewById<TextView>(R.id.epgChannel).text.toString())
        c.pause().stop().destroy()
    }

    @Test fun wizardRemoteOptionsDefaultEpgAndLook() {
        Prefs.setupDone = false
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        val a = c.get()
        fun texts() = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        fun click(t: String) { all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith(t) }.performClick(); idle() }
        click("Dalej ►")
        assertTrue(texts().any { it.endsWith("Zdalnie przez Wi-Fi (ta sama sieć)") })
        assertTrue(texts().any { it.endsWith("Zdalnie przez Internet (kod urządzenia)") })
        assertTrue(texts().any { it.contains("D-iptv Admin") })
        click("Pomiń krok")
        assertEquals(SetupActivity.DEFAULT_EPG, Prefs.epgUrl)
        assertTrue(texts().any { it.contains("https://epg.ovh/plar.xml") && it.contains("https://epg.ovh/pltv.xml") })
        click("Pomiń krok"); click("Pomiń krok")
        // krok wyglądu: lista motywów przewija się pod przyciskami (nie nachodzi na nie)
        val scroll = all(a.window.decorView).first { it is android.widget.ScrollView }
        assertTrue((scroll.parent as android.view.ViewGroup).clipChildren)
        c.pause().stop().destroy()
        Prefs.setupDone = true
    }

    @Test fun lowPerfLimitsVideoAndEpgWindow() {
        Prefs.lowPerf = true
        try {
            val p = Playback.get(ctx)
            assertEquals(1920, p.trackSelectionParameters.maxVideoWidth)
            Playback.stop()
            Prefs.epgUrl = File(ctx.filesDir, "e4.xml").apply {
                writeText("<tv>" + listOf(-5 * 24, -2, 0, 2, 4 * 24).joinToString("") { h -> "<programme start=\"${t(base + h * H)}\" stop=\"${t(base + (h + 1) * H)}\" channel=\"c1\"><title>P$h</title></programme>" } + "</tv>")
            }.absolutePath
            runBlocking { Repository.loadEpg() }
            assertEquals(listOf("P-2", "P0", "P2"), Repository.programmes(Repository.channels[0]).map { it.title })
        } finally { Prefs.lowPerf = false; Playback.stop() }
        assertEquals(Int.MAX_VALUE, Playback.get(ctx).trackSelectionParameters.maxVideoWidth)
        Playback.stop()
    }
}

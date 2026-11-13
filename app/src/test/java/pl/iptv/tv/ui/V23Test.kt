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
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V23Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun inside(a: android.app.Activity, id: Int) = generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.any { it.id == id }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = true
        Prefs.settingsOpen = emptyList(); Prefs.settingsClosed = emptyList()
        val f = File(ctx.filesDir, "v23.m3u").apply { writeText((1..9).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 group-title=\"Grupa ${it % 3}\",K$it\nhttp://v23/$it.m3u8\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Prefs.lastChannel = Repository.channels[0].key
    }

    @Test fun mainRemoteNavigationGroupsAndPreview() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        val groups = a.findViewById<RecyclerView>(R.id.groups)
        assertTrue(inside(a, R.id.channels))
        val compactW = groups.layoutParams.width
        // ◄ z kanałów -> wybrana kategoria (nie górny pasek), kolumna rozwija się w bok
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(inside(a, R.id.groups))
        assertTrue(groups.layoutParams.width > compactW)
        // ▼ w kategoriach zmienia kategorię
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        // ► wraca do kanałów, kolumna się zwija
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(inside(a, R.id.channels)); idle(400)
        assertEquals(compactW, groups.layoutParams.width)
        // ► z kanałów -> program, ◄ -> kanały
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(inside(a, R.id.programmes))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(inside(a, R.id.channels))
        // podgląd zaznaczonego kanału
        idle(1200)
        assertTrue(Playback.active)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.previewBox).visibility)
        c.pause().stop().destroy()
    }

    @Test fun channelKeepsPlayingThroughSettings() {
        val ch = Repository.channels[1]
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)).setup(); idle()
        assertEquals(ch.url, Playback.url)
        val player = Playback.player
        p.pause().stop()                                           // przejście do ustawień
        assertNotNull(Playback.player); assertSame(player, Playback.player)
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        assertTrue(Playback.active)                                   // kanał w tle ustawień
        s.pause().stop().destroy()
        p.start().resume(); idle()
        assertSame(player, Playback.player)
        assertFalse("bez restartu strumienia", Playback.play(ctx, ch, ch.url))
        p.pause().stop().destroy()
    }

    @Test fun settingsSectionsCollapseOnTabChangeAndStickyPreview() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        val a = c.get()
        fun hdr(t: String) = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith(t) }
        hdr("Obraz w obrazie (PiP)").performClick(); idle()
        assertTrue(hdr("Obraz w obrazie (PiP)").text.startsWith("▾"))
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Wygląd" }.performClick(); idle()
        // podgląd przewija się z treścią (nie zasłania opcji)
        val previewParent = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Pokaż animację paneli" }.parent as View
        assertTrue(generateSequence(previewParent) { it.parent as? View }.any { it is android.widget.ScrollView })
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Odtwarzanie" }.performClick(); idle()
        assertTrue(hdr("Obraz w obrazie (PiP)").text.startsWith("▸"))          // zwinięte po zmianie karty
        c.pause().stop().destroy()
    }

    @Test fun groupsDownDoesNotCrashAndChangesChannels() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        val chRv = a.findViewById<RecyclerView>(R.id.channels)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(inside(a, R.id.groups))
        val seen = mutableSetOf<Int>()
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_DOWN); seen += chRv.adapter!!.itemCount }
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue("kategorie zmieniają listę: $seen", seen.contains(3))       // „Grupa N” = 3 kanały
        assertTrue(inside(a, R.id.groups) || inside(a, R.id.btnSettings) || a.window.decorView.findFocus() != null)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(inside(a, R.id.channels))
        c.pause().stop().destroy()
    }

    @Test fun rightClosesChannelPanelAndOkOpensProgramme() {
        val now = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val ch = Repository.channels[0]
        val xml = "<tv><channel id=\"x\"><display-name>${ch.name}</display-name></channel>" +
            (-2..3).joinToString("") { "<programme start=\"${fmt.format(now + it * 3_600_000L)} +0000\" stop=\"${fmt.format(now + (it + 1) * 3_600_000L)} +0000\" channel=\"x\"><title>P$it</title></programme>" } + "</tv>"
        val f = File(ctx.filesDir, "v23.xml").apply { writeText(xml) }
        runBlocking { Repository.loadEpg(f.absolutePath) }
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)).setup(); idle()
        val a = p.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.leftPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); idle(500)                         // 1. ► = program TV
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.leftPanel).visibility)
        assertTrue(inside(a, R.id.lpProgrammes))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); idle(500)                         // 2. ► = zamknięcie
        assertEquals(View.GONE, a.findViewById<View>(R.id.leftPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)                                   // OK na oglądanym kanale
        assertTrue(inside(a, R.id.lpProgrammes))
        assertEquals(ch.url, Playback.url)                                       // bez przełączania
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); idle(500)
        assertEquals(View.GONE, a.findViewById<View>(R.id.leftPanel).visibility)
        p.pause().stop().destroy()
    }

    @Test fun focusReturnsAfterAddingAppShortcut() {
        val ai = android.content.pm.ActivityInfo().apply {
            packageName = "com.example.app"; name = "com.example.app.Main"; nonLocalizedLabel = "Przykład"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.example.app" }
        }
        shadowOf(ctx.packageManager).addOrUpdateActivity(ai)
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            android.content.pm.ResolveInfo().apply { activityInfo = ai })
        Prefs.quickApps = emptyList()
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key)).setup(); idle()
        val a = p.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val q = a.findViewById<RecyclerView>(R.id.quickList)
        val addIdx = q.adapter!!.itemCount - 2                     // ostatnia pozycja to „Edytuj menu”
        q.focusPosition(addIdx, R.id.row); idle()
        q.findViewHolderForAdapterPosition(addIdx)!!.itemView.findViewById<View>(R.id.row).performClick(); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        d.listView.performItemClick(null, 0, 0); idle()
        assertEquals(1, Prefs.quickApps.size)
        a.onWindowFocusChanged(true); idle()
        assertTrue("zaznaczenie w menu po dodaniu skrótu", inside(a, R.id.quickBar))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.quickBar).visibility)
        p.pause().stop().destroy()
        Prefs.quickApps = emptyList()
    }

    @Test fun settingsChoiceStaysOpenAndAppliesLive() {
        Ui.fontScaleIndex = 1
        Prefs.settingsOpen = listOf("1|Czcionka")
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        val a = c.get()
        fun row(t: String) = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == t }.parent as View
        row("Wielkość tekstu").apply { requestFocus(); performClick() }; idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        d.listView.performItemClick(d.listView.getChildAt(3), 3, 3); idle()
        assertEquals(3, Ui.fontScaleIndex)                                        // zmiana od razu
        assertTrue("okno zostaje otwarte", d.isShowing)
        d.listView.performItemClick(d.listView.getChildAt(2), 2, 2); idle()
        assertEquals(2, Ui.fontScaleIndex)
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(d.isShowing)
        a.onWindowFocusChanged(true); idle()
        val f = a.window.decorView.findFocus()
        assertEquals("row|Wielkość tekstu", generateSequence(f) { it.parent as? View }.firstNotNullOfOrNull { it.getTag(R.id.row_key) })
        // kolor: okno zostaje, zmiana na żywo
        Prefs.settingsOpen = listOf("1|Kolory"); c.pause().stop().destroy()
        val c2 = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        all(c2.get().window.decorView).filterIsInstance<TextView>().first { it.text == "Tekst" }.let { (it.parent as View).performClick() }; idle()
        val cd = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val sw = all(cd.window!!.decorView).first { it.javaClass == View::class.java && it.isClickable }
        val before = Ui.text
        sw.performClick(); idle()
        assertTrue(cd.isShowing)                                                   // okno kolorów zostaje
        assertNotEquals(before, Ui.text)                                           // kolor zmieniony od razu
        c2.pause().stop().destroy()
        Ui.fontScaleIndex = 1; Ui.resetColors(); Prefs.settingsOpen = emptyList()
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
class V31Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(a: android.app.Activity) = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"
        val f = File(ctx.filesDir, "v31.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" catchup=\"shift\" catchup-days=\"2\",A\nhttp://v31/a.m3u8\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun settingsNewOrderAndContent() {
        assertEquals(listOf("Listy", "Wygląd", "Menu i kanały", "Odtwarzanie", "Program TV", "Archiwum (catch-up)", "Kontrola rodzicielska", "Nagrywanie", "Wyniki i pogoda", "Pilot", "System", "Konto YouTube"), SettingsActivity.TAB_NAMES)
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val a = c.get()
        texts(a).let { t ->
            assertTrue(t.any { it.endsWith("Aktywna lista – Lista 1") })
            assertFalse(t.any { it.startsWith("Dodaj listę szybko") || it.startsWith("Dodaj listę zdalnie") || it.startsWith("Dodaj listę z linku") })
            listOf("Token listy", "Dodaj listę (kreator)…",
                "Wyślij w tej samej sieci Wi-Fi…", "Wyślij przez Internet (kod urządzenia)…").forEach { assertTrue(it, t.contains(it)) }
        }
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Program TV" }.performClick(); idle()
        texts(a).let { t -> listOf("Wczytaj EPG teraz", "Pobieraj nowe EPG co", "Kanałów na ekranie", "Zakres programu w panelu ◄",
            "Przypomnienia o programach", "Mapowanie EPG kanałów (edytor listy)").forEach { assertTrue(it, t.contains(it)) } }
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Archiwum (catch-up)" }.performClick(); idle()
        texts(a).let { t -> listOf("Przewijanie do tyłu (◄ / ⏪)", "Przewijanie do przodu (► / ⏩)", "Zacznij wcześniej",
            "Pytaj przed końcem programu (30 s)", "Domyślnie po zakończeniu programu", "Czas wyświetlania w archiwum", "„Pomiń reklamę” przeskakuje o").forEach { assertTrue(it, t.contains(it)) } }
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "System" }.performClick(); idle()
        assertTrue(texts(a).any { it.endsWith("Zdalne zarządzanie (administrator)") })
        c.pause().stop().destroy()
    }

    @Test fun tokenIsSubstitutedInPlaylistAddress() {
        File(ctx.filesDir, "lista-ABC.m3u").writeText("#EXTM3U\n#EXTINF:-1,X\nhttp://x/1\n#EXTINF:-1,Y\nhttp://x/2\n")
        pl.iptv.tv.data.Playlists.active()!!.let { pl.iptv.tv.data.Playlists.update(it.copy(token = "ABC")) }
        assertEquals(2, runBlocking { Repository.loadPlaylist(File(ctx.filesDir, "lista-{token}.m3u").absolutePath) })
        assertTrue(Prefs.playlistSource.contains("{token}"))                // zapisany szablon, nie token
    }

    @Test fun archivePlaybackSeekPadsSkipAndNext() {
        val ch = Repository.channels[0]; val now = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = "<tv><channel id=\"a\"><display-name>A</display-name></channel>" + (-4..1).joinToString("") {
            "<programme start=\"${fmt.format(now + it * 3_600_000L)} +0000\" stop=\"${fmt.format(now + (it + 1) * 3_600_000L)} +0000\" channel=\"a\"><title>P$it</title></programme>" } + "</tv>"
        runBlocking { Repository.loadEpg(File(ctx.filesDir, "v31.xml").apply { writeText(xml) }.absolutePath) }
        Prefs.cuPadBefore = 2; Prefs.cuBack = 10; Prefs.cuFwd = 30; Prefs.adSkip = 120; Prefs.cuArrows = 0
        assertFalse("domyślnie po archiwum: na żywo", Prefs.cuAutoNext); Prefs.cuAutoNext = true
        val p3 = Repository.programmes(ch).first { it.title == "P-3" }
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            .putExtra(PlayerActivity.EXTRA_START, p3.start).putExtra(PlayerActivity.EXTRA_END, p3.end)).setup(); idle()
        val a = c.get()
        assertTrue("margines 2 min", Playback.url!!.contains("utc=${(p3.start - 120_000) / 1000}"))
        val pp = Playback.player!!
        val before = pp.currentPosition
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)                                         // ► przewija, nie otwiera panelu
        assertEquals(View.GONE, a.findViewById<View>(R.id.rightPanel).visibility)
        // podwójne ► = pominięcie reklamy (+2 min), pojedyncze = +30 s
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); idle(100)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); idle(600)
        assertEquals("⏭ +120 s", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                key(a, KeyEvent.KEYCODE_PROG_BLUE)                                          // pomiń reklamę
        assertEquals(View.GONE, a.findViewById<View>(R.id.leftPanel).visibility)
        assertTrue(pp.currentPosition >= before)
        // okno wyboru przed końcem -> „na żywo” -> po końcu kanał na żywo
        PlayerActivity::class.java.getDeclaredMethod("showEndPrompt").apply { isAccessible = true }.invoke(a); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing); assertEquals(2, d.listView.adapter.count)
        d.listView.performItemClick(d.listView.getChildAt(0), 0, 0); idle()
        assertFalse(d.isShowing)
        PlayerActivity::class.java.getDeclaredMethod("archiveEnded").apply { isAccessible = true }.invoke(a); idle()
        assertEquals(ch.url, Playback.url)                                           // na żywo
        // domyślnie (bez wyboru) – następny program z archiwum
        a.findViewById<View>(R.id.leftPanel)
        PlayerActivity::class.java.getDeclaredMethod("playArchive", pl.iptv.tv.data.Channel::class.java, pl.iptv.tv.data.Programme::class.java)
            .apply { isAccessible = true }.invoke(a, ch, p3); idle()
        PlayerActivity::class.java.getDeclaredMethod("archiveEnded").apply { isAccessible = true }.invoke(a); idle()
        val p2 = Repository.programmes(ch).first { it.title == "P-2" }
        assertTrue(Playback.url!!.contains("utc=${(p2.start - 120_000) / 1000}"))
        c.pause().stop().destroy()
        Prefs.cuPadBefore = 0
    }

    @Test fun settingsNavigationCollapsedAndAddFromLink() {
        Prefs.settingsOpen = emptyList(); Prefs.settingsClosed = emptyList()
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val a = c.get()
        val headers = all(a.window.decorView).filterIsInstance<TextView>().filter { it.text.startsWith("▸") || it.text.startsWith("▾") }
        assertTrue(headers.isNotEmpty()); assertTrue("wszystkie zwinięte", headers.all { it.text.startsWith("▸") })
        // ► z karty -> treść, ◄ z treści -> ta sama karta (nie ta na wysokości kursora)
        val tabListy = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Listy" }
        tabListy.requestFocus(); key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val f1 = a.window.decorView.findFocus()
        assertNotSame(tabListy, f1)
        repeat(4) { key(a, KeyEvent.KEYCODE_DPAD_DOWN) }
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertSame(tabListy, a.window.decorView.findFocus())
        // najechanie na inną kartę przełącza ją
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Archiwum (catch-up)" }.requestFocus(); idle(400)
        assertTrue(texts(a).any { it.endsWith("Odtwarzanie archiwum") })
        // okno akcji pozycji menu zostaje otwarte, zaznaczenie zostaje w karcie Funkcje
        pl.iptv.tv.data.QuickMenu.reset()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Odtwarzanie" }.apply { requestFocus(); performClick() }; idle(400)
        val row = all(a.window.decorView).first { it.getTag(R.id.row_key) == "q|settings" }
        row.requestFocus(); row.performClick(); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        lv.performItemClick(lv.getChildAt(2), 2, 2); idle()                    // przesuń w dół
        assertTrue("okno zostaje", d.isShowing)
        assertEquals(1, pl.iptv.tv.data.QuickMenu.order().indexOf("settings"))
        lv.performItemClick(lv.getChildAt(2), 2, 2); idle()
        assertEquals(2, pl.iptv.tv.data.QuickMenu.order().indexOf("settings"))
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        a.onWindowFocusChanged(true); idle()
        assertEquals("q|settings", generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.firstNotNullOfOrNull { it.getTag(R.id.row_key) })
        assertTrue(texts(a).any { it.endsWith("Menu boczne (2× ◄)") })      // nadal karta Odtwarzanie
        pl.iptv.tv.data.QuickMenu.reset()
        c.pause().stop().destroy()
    }

    @Test fun choiceDialogStartsOnCurrentOption() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val d = Dialogs.choiceLive(c.get(), "Test", listOf("A", "B", "C", "D"), 2) {}
        idle()
        assertEquals(2, d.listView.checkedItemPosition)
        assertEquals(2, d.listView.selectedItemPosition)
        d.dismiss(); c.pause().stop().destroy()
    }

    @Test fun previewNoOverlapZoomLimitedAndVideoBack() {
        Ui.applyPreset("modern"); Ui.zoom = true
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        val a = c.get()
        val name = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Kanał zaznaczony" }
        val info = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("1. Przykładowy kanał HD") }
        val r1 = IntArray(2).also { name.getLocationOnScreen(it) }; val r2 = IntArray(2).also { info.getLocationOnScreen(it) }
        assertTrue("pasek pod listą", r2[1] >= r1[1] + name.height)
        c.pause().stop().destroy()
        // szerokie wiersze powiększają się minimalnie
        val v = View(ctx); v.layout(0, 0, 1500, 60); v.tag = "item"; Ui.style(v)
        v.layout(0, 0, 1500, 60)
        val anim = v.stateListAnimator
        assertNotNull(anim)
        // obraz wraca po powrocie z ustawień
        val ch = Repository.channels[0]
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)).setup(); idle()
        p.pause().stop()
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        s.pause()
        p.restart().start().resume(); idle()
        s.stop().destroy()
        assertSame(Playback.player, p.get().findViewById<androidx.media3.ui.PlayerView>(R.id.playerView).player)
        p.pause().stop().destroy()
    }

    @Test fun archiveSeekShowsOnlyProgressAndGuideNavigation() {
        val ch = Repository.channels[0]; val now = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = "<tv><channel id=\"a\"><display-name>A</display-name></channel>" + (-3..3).joinToString("") {
            "<programme start=\"${fmt.format(now + it * 3_600_000L)} +0000\" stop=\"${fmt.format(now + (it + 1) * 3_600_000L)} +0000\" channel=\"a\"><title>P$it</title></programme>" } + "</tv>"
        runBlocking { Repository.loadEpg(File(ctx.filesDir, "v31b.xml").apply { writeText(xml) }.absolutePath) }
        val p2 = Repository.programmes(ch).first { it.title == "P-2" }
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            .putExtra(PlayerActivity.EXTRA_START, p2.start).putExtra(PlayerActivity.EXTRA_END, p2.end)).setup(); idle()
        val a = c.get(); val pv = a.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)
        pv.hideController(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)                                       // przewijanie -> sam pasek postępu
        assertTrue(pv.isControllerFullyVisible)
        assertEquals(View.GONE, pv.findViewById<View>(R.id.ctlHead).visibility)
        assertEquals(View.GONE, pv.findViewById<View>(R.id.ctlRow2).visibility)
        assertEquals(View.VISIBLE, pv.findViewById<View>(androidx.media3.ui.R.id.exo_progress).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)                                     // OK -> pełny panel
        assertEquals(View.VISIBLE, pv.findViewById<View>(R.id.ctlHead).visibility)
        assertEquals(View.VISIBLE, pv.findViewById<View>(R.id.ctlRow2).visibility)
        assertTrue(pv.findViewById<TextView>(R.id.ctlHint).text.contains("2× ►"))
        c.pause().stop().destroy()
        // przewodnik
        val g = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, ch.key)).setup(); idle(600)
        val ga = g.get()
        fun focusId() = ga.window.decorView.findFocus()?.id
        assertNotNull(ga.window.decorView.findFocus()?.getTag(R.id.guide_cell))
        key(ga, KeyEvent.KEYCODE_DPAD_UP)                                        // jedyny wiersz -> ▲ = górne przyciski
        assertEquals(R.id.btnGroup, focusId())
        key(ga, KeyEvent.KEYCODE_DPAD_DOWN)                                      // ▼ z przycisków -> siatka
        idle(200); assertNotNull(ga.window.decorView.findFocus()?.getTag(R.id.guide_cell))
        val cellsRow = ga.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rows).findViewHolderForAdapterPosition(0)!!.itemView
        cellsRow.findViewById<View>(R.id.chCell).requestFocus()
        key(ga, KeyEvent.KEYCODE_DPAD_LEFT)                                      // ◄ z kanału -> górne przyciski
        assertEquals(R.id.btnGroup, focusId())
        // ponowne użycie komórek przy przesuwaniu czasu
        val cells = cellsRow.findViewById<android.widget.LinearLayout>(R.id.cells)
        val first = (0 until cells.childCount).map { cells.getChildAt(it) }.filterIsInstance<TextView>().toSet()
        ga.findViewById<View>(R.id.btnNext).performClick(); idle(400)
        val after = (0 until cells.childCount).map { cells.getChildAt(it) }.filterIsInstance<TextView>().toSet()
        assertTrue("komórki użyte ponownie", first.intersect(after).isNotEmpty())
        g.pause().stop().destroy()
    }
}

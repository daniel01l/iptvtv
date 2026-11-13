package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.XmltvParser
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h640dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V50Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test fun epgCrashLoopGuard() {
        Prefs.lowPerf = false; Prefs.epgCrashes = 0
        Prefs.epgParsing = true                               // symulacja: aplikacja padła w trakcie wczytywania
        assertTrue(Repository.epgCrashed())
        assertTrue(Prefs.lowPerf); assertFalse(Prefs.epgParsing); assertFalse(Repository.epgAutoBlocked)
        Prefs.epgParsing = true; Repository.epgCrashed()
        assertTrue(Repository.epgAutoBlocked)                 // druga awaria – bez automatycznego wczytywania
        // udane wczytanie zeruje licznik
        val f = File(ctx.filesDir, "v50.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://x/1\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        val x = File(ctx.filesDir, "v50.xml").apply { writeText("<tv><programme start=\"${fmt.format(java.util.Date(now))}\" stop=\"${fmt.format(java.util.Date(now + 3_600_000))}\" channel=\"a\"><title>T</title><desc>${"słowo ".repeat(200)}</desc></programme></tv>") }
        runBlocking { Repository.loadEpg(x.absolutePath) }
        assertEquals(0, Prefs.epgCrashes); assertFalse(Prefs.epgParsing)
        assertTrue(Repository.current(Repository.channels[0])!!.desc.length <= 301)      // słabsze urządzenie: krótszy opis
        Prefs.lowPerf = false
        assertEquals(1199, XmltvParser.parse(x.inputStream(), Repository.channels, 0, Long.MAX_VALUE).programmes["a"]!![0].desc.length)
    }

    @Test fun dialogsFitScreenAndFocusRules() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        // długa treść: okno nie wyższe niż 90% ekranu, przyciski widoczne
        Tutorial.show(a, false); idle(600)
        var d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.window!!.decorView.height <= a.resources.displayMetrics.heightPixels * 0.9 + 2)
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isShown)
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isFocused)
        d.dismiss(); idle()
        // pole tekstowe: kursor w polu, kod z myślnikami
        Dialogs.input(a, "Kod", "", code = true) {}; idle(400)
        d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val e = all(d.window!!.decorView).filterIsInstance<EditText>().first()
        assertTrue(e.isFocused)
        e.setText("abcd1234efgh9"); assertEquals("ABCD-1234-EFGH", e.text.toString())
        d.dismiss(); idle()
        // okno kolejności: pierwsza pozycja
        Dialogs.editGroups(a) {}; idle(400)
        d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val lv = all(d.window!!.decorView).filterIsInstance<ListView>().first()
        assertTrue(lv.hasFocus()); assertEquals(0, lv.selectedItemPosition.coerceAtLeast(0))
        d.dismiss(); c.pause().stop().destroy()
    }
}

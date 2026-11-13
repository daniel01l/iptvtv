package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class V21Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).let { if (ms > 0) it.idleFor(Duration.ofMillis(ms)) else it.idle() }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private lateinit var first: File

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"
        Prefs.catchupDefaultType = ""; Prefs.epgUrl = ""; Prefs.piconUrl = ""
        first = File(ctx.filesDir, "f1.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(first.absolutePath) }
    }

    @Test fun addListWizardStoresPerListSettings() {
        val second = File(ctx.filesDir, "f2.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,B\nhttp://b/1\n#EXTINF:-1,C\nhttp://b/2\n") }
        val s = Robolectric.buildActivity(SetupActivity::class.java, Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ADD_LIST, true)).setup(); idle()
        val a = s.get()
        fun texts() = all(a.window.decorView).filterIsInstance<TextView>()
        fun btn(t: String) = texts().first { it.text.toString().startsWith(t) }
        assertTrue(texts().any { it.text.startsWith("Nowa lista") && it.text.contains("1/3") })
        val f = all(a.window.decorView).filterIsInstance<EditText>()
        assertEquals("", f[0].text.toString())                        // nowa lista – puste pola
        f[0].setText(second.absolutePath); f[1].setText("Druga"); f[2].setText("10.10.2031")
        btn("Dalej").performClick()
        for (i in 0 until 50) { idle(100); if (Repository.allChannels.size == 2 && texts().any { it.text.contains("2/3") }) break; Thread.sleep(100) }
        assertEquals(2, Playlists.all().size)
        val p = Playlists.active()!!
        assertEquals("Druga", p.name); assertEquals("10.10.2031", Playlists.formatDate(p.expiry)); assertEquals(2, Repository.allChannels.size)
        assertTrue(texts().any { it.text.contains("2/3") })               // krok EPG
        all(a.window.decorView).filterIsInstance<EditText>()[0].setText("http://druga/epg.xml")
        btn("Pomiń").performClick(); idle()
        Prefs.catchupDefaultType = "shift"; Playlists.captureActive()     // jak wybór w kroku catch-up
        assertTrue(btn("✓ Zapisz listę").isShown)
        btn("✓ Zapisz listę").performClick(); idle()
        assertTrue(a.isFinishing)
        assertEquals("http://druga/epg.xml", Playlists.active()!!.epg); assertEquals("shift", Playlists.active()!!.cuType)
        s.pause().stop().destroy()
        // przełączenie: każda lista ma swoje ustawienia
        val main = Playlists.all().first { it.id != p.id }
        runBlocking { Repository.switchPlaylist(main.id) }
        assertEquals("http://druga/epg.xml", Prefs.epgUrl); assertEquals("", Prefs.catchupDefaultType); assertEquals(1, Repository.allChannels.size)   // lista bez EPG zachowuje adres
        runBlocking { Repository.switchPlaylist(p.id) }
        assertEquals("http://druga/epg.xml", Prefs.epgUrl); assertEquals("shift", Prefs.catchupDefaultType)
        assertTrue(Repository.channels.all { it.hasCatchup })
    }

    @Test fun themedDialogLayout() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        Dialogs.confirm(c.get(), "Tytuł", "Treść") {}; idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val w = d.window!!.attributes
        val dm = c.get().resources.displayMetrics
        assertEquals((dm.widthPixels * 0.46f).toInt(), w.width)          // ekran TV: ~połowa szerokości
        assertEquals(0.7f, w.dimAmount, 0.01f)
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).background is android.graphics.drawable.StateListDrawable)
        d.dismiss(); c.pause().stop().destroy()
    }

    @Test fun binarySearchHelpersMatchLinear() {
        val ch = Channel("k", "K", "u", "G")
        val list = (0 until 500).map { Programme(it * 1000L, it * 1000L + 1000, "P$it") }
        val m = Repository::class.java.getDeclaredMethod("firstEndingAfter", List::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        for (t in listOf(-5L, 0L, 999L, 1000L, 250_500L, 499_999L, 500_000L, 900_000L)) {
            val expected = list.indexOfFirst { it.end > t }.let { if (it < 0) list.size else it }
            assertEquals("t=$t", expected, m.invoke(Repository, list, t))
        }
        assertNull(Repository.current(ch))                                 // kanał bez EPG
    }
}

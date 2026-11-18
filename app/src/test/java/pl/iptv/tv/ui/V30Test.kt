package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
import pl.iptv.tv.data.EpgBinary
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V30Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun inside(a: android.app.Activity, id: Int) = generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.any { it.id == id }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        val f = File(ctx.filesDir, "v30.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"Grupa ${it % 4}\",K$it\nhttp://v30/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun epgBinaryCacheFastPathAndRefreshDays() {
        val now = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = "<tv>" + (1..12).joinToString("") { c -> "<channel id=\"c$c\"><display-name>K$c</display-name></channel>" + (0..5).joinToString("") { h ->
            "<programme start=\"${fmt.format(now + h * 3_600_000L)} +0000\" stop=\"${fmt.format(now + (h + 1) * 3_600_000L)} +0000\" channel=\"c$c\"><title>T$c-$h</title><desc>Opis ąę</desc><sub-title>S</sub-title></programme>" } } + "</tv>"
        val x = File(ctx.filesDir, "v30.xml").apply { writeText(xml) }
        runBlocking { Repository.loadEpg(x.absolutePath) }
        val bin = File(ctx.filesDir, "epg.bin")
        assertTrue(bin.exists())
        // odczyt z binarnej kopii daje to samo
        val sig = EpgBinary.signature(Repository.allChannels)
        val d = EpgBinary.read(bin, sig, 0, Long.MAX_VALUE)!!
        assertEquals(72, d.programmes.values.sumOf { it.size })
        assertEquals("Opis ąę", d.programmes["c1"]!!.first().desc)
        assertNull(EpgBinary.read(bin, "inny", 0, Long.MAX_VALUE))         // inna lista -> pełne przetworzenie XML
        File(ctx.filesDir, "epg.xml.dat").delete()                         // bez XML: start korzysta tylko z binarnej kopii
        assertTrue(runBlocking { Repository.loadEpgFromCache() })
        assertEquals("T1-0", Repository.programmes(Repository.channels[0]).first().title)
        assertEquals(3, Prefs.epgRefreshDays)
    }

    @Test fun mainFocusAfterReturnAndTopBarDownAndFastGroups() {
        Prefs.lastChannel = Repository.channels[5].key
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        val chRv = a.findViewById<RecyclerView>(R.id.channels)
        c.pause(); idle(); c.resume(); idle(500)                            // powrót z odtwarzacza
        assertTrue(inside(a, R.id.channels))
        assertEquals(Repository.channels[5].key, (chRv.findContainingViewHolder(a.window.decorView.findFocus()!!)?.bindingAdapterPosition)?.let { (chRv.adapter as ChannelAdapter).items[it].key })
        a.findViewById<View>(R.id.btnSettings).requestFocus(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.groups))   // przyciski po lewej -> kategorie
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(inside(a, R.id.channels))
        // szybkie przechodzenie po kategoriach: lista kanałów zmienia się dopiero po zatrzymaniu
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN); idle(400)
        val sel = (a.findViewById<RecyclerView>(R.id.groups).adapter as GroupAdapter).selected
        assertEquals(Repository.channelsFor(sel).size, chRv.adapter!!.itemCount)   // lista = wybrana kategoria (po zatrzymaniu)
        assertEquals(3, chRv.adapter!!.itemCount)
        c.pause().stop().destroy()
    }

    @Test fun focusedItemHasBorderAndIsNotClipped() {
        Ui.applyPreset("modern")
        val bg = Ui.itemBg() as android.graphics.drawable.StateListDrawable
        bg.state = intArrayOf(android.R.attr.state_focused)
        // Nowoczesny (7.0): zaznaczenie z poświatą – warstwy, ramka w ostatniej
        val g = bg.current.let { (it as? android.graphics.drawable.LayerDrawable)?.let { l -> l.getDrawable(l.numberOfLayers - 1) } ?: it } as android.graphics.drawable.GradientDrawable
        assertNotNull(g)
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val row = generateSequence<View>(s.get().window.decorView) { null }.let {
            fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(it.let { i -> v.getChildAt(i) }) } else emptyList()
            all(s.get().window.decorView).first { it.getTag(R.id.row_key)?.toString()?.startsWith("row|") == true }
        }
        assertFalse((row.parent as android.view.ViewGroup).clipChildren)
        s.pause().stop().destroy()
    }
}

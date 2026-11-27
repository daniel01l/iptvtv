package pl.iptv.tv.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
class GuideSetupTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val H = 3_600_000L
    private val base = System.currentTimeMillis() / (30 * 60_000) * (30 * 60_000)
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).let { if (ms > 0) it.idleFor(Duration.ofMillis(ms)) else it.idle() }
    private lateinit var m3u: File

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.batteryAsked = true; Prefs.startAction = 0; Prefs.setupDone = true
        Prefs.catchupDefaultType = ""; Prefs.catchupForce = false
        m3u = File(ctx.filesDir, "pl.m3u")
        m3u.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" catchup=\"shift\" catchup-days=\"7\",Jedynka\nhttp://t/1\n" +
            "#EXTINF:-1 tvg-id=\"b\",Dwójka\nhttp://t/2\n")
        Repository.resetAllEdits()
        runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
        val sb = StringBuilder("<tv><channel id=\"a\"><display-name>Jedynka</display-name></channel><channel id=\"b\"><display-name>Dwójka</display-name></channel>\n")
        // co godzinę program, od 7 dni wstecz do 7 dni naprzód
        var x = base - 7 * 24 * H
        while (x < base + 7 * 24 * H) {
            for (ch in listOf("a", "b")) sb.append("<programme start=\"${t(x)}\" stop=\"${t(x + H)}\" channel=\"$ch\"><title>P$ch ${(x - base) / H}</title></programme>\n")
            x += H
        }
        sb.append("</tv>")
        val f = File(ctx.filesDir, "epg.xml"); f.writeText(sb.toString())
        runBlocking { Repository.loadEpg(f.absolutePath) }
    }

    private fun key(a: android.app.Activity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(300)
    }
    private fun focused(a: android.app.Activity) = a.window.decorView.findFocus()
    private fun winStart(a: GuideActivity) = GuideActivity::class.java.getDeclaredField("winStart").let { it.isAccessible = true; it.getLong(a) }

    @Test fun guideGridNavigation7DaysAndActions() {
        assertTrue("EPG trzyma 7 dni wstecz", Repository.programmes(Repository.channels[0]).first().start <= base - 7 * 24 * H + H)
        val c = Robolectric.buildActivity(GuideActivity::class.java, Intent(ctx, GuideActivity::class.java)
            .putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[0].key)).setup(); idle(500)
        val a = c.get()
        val rows = a.findViewById<RecyclerView>(R.id.rows)
        assertEquals(2, rows.adapter!!.itemCount)
        val f0 = focused(a)!!
        assertNotNull("fokus na komórce programu", f0.getTag(R.id.guide_cell))
        assertTrue((f0 as TextView).text.startsWith("●"))         // bieżący program
        // pilot: ◄ na pierwszej komórce przewija czas wstecz
        val ws0 = winStart(a)
        var guard = 0
        while (winStart(a) == ws0 && guard++ < 5) {
            key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        }
        assertEquals(ws0 - 30 * 60_000L, winStart(a))
        // przycisk „◄ Dzień” 7 razy – nie dalej niż 7 dni wstecz
        repeat(9) { a.findViewById<View>(R.id.btnDayPrev).performClick(); idle(100) }
        assertTrue(winStart(a) >= System.currentTimeMillis() - 7 * 24 * H - 30 * 60_000L)
        assertTrue(winStart(a) < System.currentTimeMillis() - 6 * 24 * H)
        assertTrue(a.findViewById<TextView>(R.id.dayLabel).text.isNotEmpty())
        // wybór programu z archiwum -> menu -> „Oglądaj z archiwum”
        a.findViewById<View>(R.id.btnNow).performClick(); idle(500)
        a.findViewById<View>(R.id.btnPrev).performClick(); idle(500)
        val row0 = rows.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<LinearLayout>(R.id.cells)
        val pastCell = (0 until row0.childCount).map { row0.getChildAt(it) }.first { (it as? TextView)?.text?.startsWith("⟲") == true }
        pastCell.performClick(); idle()
        val dlg = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val labels = (0 until dlg.listView.adapter.count).map { dlg.listView.adapter.getItem(it).toString() }
        assertTrue(labels.toString(), labels.any { it.contains("Oglądaj z archiwum") })
        assertTrue(labels.any { it.contains("Oglądaj kanał") })
        assertTrue(labels.any { it.contains("Następny odcinek") || it.contains("emisje") })
        dlg.listView.performItemClick(null, labels.indexOfFirst { it.contains("archiwum") }, 0); idle()
        val play = shadowOf(a).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, play.component!!.className)
        assertTrue(play.getLongExtra(PlayerActivity.EXTRA_START, 0) > 0)
        // kanał w lewej kolumnie – oglądanie na żywo
        rows.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.chCell).performClick(); idle()
        val live = shadowOf(a).nextStartedActivity
        assertEquals(Repository.channels[1].key, live.getStringExtra(PlayerActivity.EXTRA_CHANNEL))
        assertEquals(0L, live.getLongExtra(PlayerActivity.EXTRA_START, 0))
        c.pause().stop().destroy()
    }

    @Test fun themedDialogsGradientsFonts() {
        applyClassic()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        Dialogs.menu(c.get(), "Test", listOf("A" to {}, "B" to {})); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.window!!.decorView.background is android.graphics.drawable.GradientDrawable)
        assertEquals(Ui.accent, d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.currentTextColor)
        d.dismiss(); c.pause().stop().destroy()
        // gradient tła
        Ui.setGradient("bg", 0xFF112233.toInt(), 0xFF445566.toInt()); Ui.changed()
        assertTrue(Ui.colorLabel("bg").contains("→"))
        val g = Ui.bgDrawable() as GradientDrawable
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()), g.colors)
        assertNull(Ui.color2("text"))                                  // tekst nie ma gradientu
        // dołączone czcionki ładują się z zasobów
        Ui.fontIndex = Ui.FONTS.indexOfFirst { it.label.startsWith("Lobster") }
        assertNotNull(androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.lobster))
        assertNotSame(android.graphics.Typeface.DEFAULT, Ui.typeface(0))
        Ui.applyPreset("modern")
        assertNull(Ui.color2("bg"))                                    // motyw czyści gradienty
    }

    @Test fun settingsSaveButtonLoadsNewList() {
        val other = File(ctx.filesDir, "inna.m3u"); other.writeText("#EXTM3U\n#EXTINF:-1,X\nhttp://x/1\n#EXTINF:-1,Y\nhttp://x/2\n#EXTINF:-1,Z\nhttp://x/3\n")
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 0)).setup(); idle()
        val a = c.get()
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        val field = all(a.window.decorView).filterIsInstance<EditText>().first()
        field.setText(other.absolutePath)
        val save = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.contains("Zapisz i zamknij") }
        save.performClick()
        Thread.sleep(300); idle(500)
        assertEquals(3, Repository.allChannels.size)
        assertTrue(a.isFinishing)
        c.pause().stop().destroy()
    }

    @Test fun settingsSaveButtonKeepsFocusOnDownAndRight() {
        // (7.17) ▼ / ► z „Zapisz i zamknij” nie przeskakują do kart po lewej (np. „Nagrywanie”, „Konto YouTube”)
        for (t in listOf(0, 7, 11)) {
            val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", t)).setup(); idle()
            val a = c.get()
            fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            val save = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.contains("Zapisz i zamknij") }
            assertTrue(save.requestFocus())
            for (k in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                assertTrue("tab $t key $k", a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k)); idle()
                assertSame("tab $t key $k", save, a.window.decorView.findFocus())
            }
            assertFalse(a.isFinishing)
            c.pause().stop().destroy()
        }
    }

    @Test fun catchupDefaultForChannelsWithoutInfo() {
        assertFalse(Repository.channels[1].hasCatchup)
        Prefs.catchupDefaultType = "shift"; Prefs.catchupDefaultDays = 3; Repository.reapply()
        assertTrue(Repository.channels[1].hasCatchup); assertEquals(3, Repository.channels[1].catchupDays)
        assertEquals(7, Repository.channels[0].catchupDays)            // kanał z listy bez zmian
        Prefs.catchupForce = true; Repository.reapply()
        assertEquals(3, Repository.channels[0].catchupDays)
        Prefs.catchupDefaultType = ""; Prefs.catchupForce = false; Repository.reapply()
        assertFalse(Repository.channels[1].hasCatchup)
    }

    @Test fun firstRunSetupWizardFlow() {
        // czysta instalacja: brak listy i konfiguracji
        File(ctx.filesDir, "playlist.m3u").delete()
        Prefs.setupDone = false; Prefs.playlistSource = ""
        Repository.init(ctx)
        val field = Repository::class.java.getDeclaredField("raw").apply { isAccessible = true }
        field.set(Repository, emptyList<pl.iptv.tv.data.Channel>()); Repository.reapply()
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val started = shadowOf(m.get()).nextStartedActivity
        assertEquals(SetupActivity::class.java.name, started.component!!.className)
        m.pause().stop().destroy()

        val s = Robolectric.buildActivity(SetupActivity::class.java, started).setup(); idle()
        val a = s.get()
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun btn(t: String) = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith(t) }
        fun header() = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("Konfiguracja") }.text.toString()
        assertTrue(header().contains("1/6"))
        btn("Dalej").performClick(); idle()
        assertTrue(header().contains("Lista kanałów"))
        all(a.window.decorView).filterIsInstance<EditText>().first().setText(m3u.absolutePath)
        btn("Dalej").performClick(); Thread.sleep(300); idle(500)          // Dalej wczytuje listę
        assertEquals(2, Repository.allChannels.size)
        assertTrue(header(), header().contains("EPG"))
        btn("Pomiń").performClick(); idle()
        assertTrue(header().contains("Catch-up"))
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.contains("1/2") })   // 1 z 2 kanałów z archiwum
        btn("Dalej").performClick(); idle()
        assertTrue(header().contains("Wygląd"))
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == ModernV2.NAME }.let { (it.parent as View).performClick() }; idle()
        assertEquals(ModernV2.ID, Ui.preset.id)
        btn("Dalej").performClick(); idle()
        assertTrue(header().contains("Uruchamianie"))
        btn("✓ Zakończ").performClick(); idle()
        assertTrue(Prefs.setupDone); assertTrue(a.isFinishing)
        s.pause().stop().destroy()
        Ui.applyPreset("modern")
    }
}

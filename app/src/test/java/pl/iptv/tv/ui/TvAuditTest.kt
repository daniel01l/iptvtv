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

/** Audyt pod Android TV: 960×540 dp, tryb telewizora, tylko pilot. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvAuditTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun focus(a: android.app.Activity): View? = a.window.decorView.findFocus()
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private val issues = mutableListOf<String>()

    /** Widoczne, klikalne elementy muszą być osiągalne pilotem (focusable) i mieścić się na ekranie. */
    private fun audit(name: String, a: android.app.Activity) {
        val root = a.window.decorView
        val w = root.width
        all(root).filter { it.isShown && it.isClickable && it.width > 0 }.forEach { v ->
            val label = (v as? TextView)?.text ?: v.javaClass.simpleName
            if (!v.isFocusable && v.id != R.id.scrim && v.id != R.id.previewBox && v !is androidx.media3.ui.PlayerView && v.parent !is androidx.media3.ui.PlayerView)
                issues += "$name: klikalny bez fokusu: $label"
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (v is TextView && loc[0] + v.width > w + 2) issues += "$name: poza ekranem: $label"
        }
        if (focus(a) == null) issues += "$name: brak zaznaczenia po otwarciu"
        if (focus(a) is android.widget.ScrollView) issues += "$name: zaznaczony kontener przewijania zamiast elementu"
        println("AUDIT $name w=$w klikalnych=${all(root).count { it.isShown && it.isClickable && it.width > 0 }} fokus=${(focus(a) as? TextView)?.text ?: focus(a)?.javaClass?.simpleName}")
    }

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.keyMapJson = ""
        val f = File(ctx.filesDir, "tv.m3u").apply { writeText((1..30).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 group-title=\"G${it % 3}\" catchup=\"shift\",Kanał numer $it HD\nhttp://tv/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun allScreensUsableWithRemote() {
        listOf(MainActivity::class.java, GuideActivity::class.java, EditorActivity::class.java, EpgSearchActivity::class.java, SetupActivity::class.java).forEach { cls ->
            val c = Robolectric.buildActivity(cls).setup(); idle(800)
            audit(cls.simpleName, c.get())
            c.pause().stop().destroy()
        }
        // ustawienia: każda karta, przejście ► z karty do treści
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val a = s.get()
        SettingsActivity.TAB_NAMES.forEach { t ->
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text == t }.apply { requestFocus(); performClick() }; idle()
            audit("Ustawienia/$t", a)
        }
        s.pause().stop().destroy()
        // odtwarzacz: panele i menu tylko pilotem
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key)).setup(); idle()
        val pa = p.get()
        key(pa, KeyEvent.KEYCODE_DPAD_LEFT); audit("Odtwarzacz/panel ◄", pa)
        key(pa, KeyEvent.KEYCODE_DPAD_LEFT); audit("Odtwarzacz/menu ◄◄", pa)
        pa.onBackPressedDispatcher.onBackPressed(); idle(); pa.onBackPressedDispatcher.onBackPressed(); idle()
        key(pa, KeyEvent.KEYCODE_DPAD_RIGHT); audit("Odtwarzacz/panel ►", pa)
        p.pause().stop().destroy()
        assertTrue(issues.joinToString("\n"), issues.isEmpty())
    }

    @Test fun archiveInfoBar2sMediaKeysAndStyles() {
        val now = System.currentTimeMillis()
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key)
            .putExtra(PlayerActivity.EXTRA_START, now - 3_600_000L).putExtra(PlayerActivity.EXTRA_END, now - 1_800_000L)).setup(); idle()
        val a = p.get(); val overlay = a.findViewById<View>(R.id.overlay)
        assertEquals(2000L, Ui.archiveInfoMs)
        val pv = a.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)
        idle(700)
        assertEquals(View.GONE, overlay.visibility)                            // archiwum: bez paska informacji
        assertTrue(pv.isControllerFullyVisible)                               // informacje na dolnym pasku
        assertTrue(pv.findViewById<TextView>(R.id.ctlTitle).text.isNotBlank())
        assertNotNull(pv.findViewById<View>(R.id.ctlSkip)); assertNotNull(pv.findViewById<View>(R.id.ctlLive))
        // ▶‖ pauzuje
        val pl = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(a) as androidx.media3.exoplayer.ExoPlayer
        assertTrue(pl.playWhenReady)
        key(a, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); assertFalse(pl.playWhenReady)
        key(a, KeyEvent.KEYCODE_MEDIA_PLAY); assertTrue(pl.playWhenReady)
        p.pause().stop().destroy()
        // style paska
        Ui.infoStyle = 4; assertNull(Ui.infoBarBg(false))
        Ui.infoStyle = 1; Ui.infoAlphaIndex = 2; assertEquals(70 * 255 / 100, Ui.infoBarBg(false)!!.alpha)
        assertTrue(Ui.infoFloating)
        Ui.applyPreset("classic"); assertEquals(3, Ui.infoStyle)               // motyw klasyczny = pasek dekodera
        Ui.applyPreset("modern"); assertEquals(0, Ui.infoStyle)
        Prefs.remove("info_alpha")
        // ustawienia: sekcja paska w Wyglądzie
        val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        val texts = all(s.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        listOf("Styl paska", "Krycie tła paska", "Wielkość paska", "Logo kanału").forEach { assertTrue(it, texts.contains(it)) }
        s.pause().stop().destroy()
        val s2 = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 5)).setup(); idle()
        assertTrue(all(s2.get().window.decorView).filterIsInstance<TextView>().any { it.text == "Czas wyświetlania w archiwum" })
        s2.pause().stop().destroy()
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
import pl.iptv.tv.R
import pl.iptv.tv.data.MiniMenu
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V36Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun down(a: android.app.Activity, code: Int, rep: Int = 0) = a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, rep))
    private fun up(a: android.app.Activity, code: Int) = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    private fun key(a: android.app.Activity, code: Int) { down(a, code); up(a, code); idle() }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.mainLayout = 0; Prefs.orientation = 0; Prefs.miniMenuEnabled = true; Prefs.okLongAction = 1; MiniMenu.reset(); Prefs.miniApps = emptyList(); Prefs.keyMapJson = ""
        val f = File(ctx.filesDir, "v36.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v36/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    private fun player(): org.robolectric.android.controller.ActivityController<PlayerActivity> {
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        return Robolectric.buildActivity(PlayerActivity::class.java, i).setup().also { idle() }
    }

    @Test fun numberZapShowsLogoAndName() {
        val c = player(); val a = c.get()
        key(a, KeyEvent.KEYCODE_5)
        val box = a.findViewById<View>(R.id.numberBox)
        assertEquals(View.VISIBLE, box.visibility)
        assertEquals("5", a.findViewById<TextView>(R.id.numberInput).text.toString())
        assertEquals(Repository.channels.first { it.number == 5 }.name, a.findViewById<TextView>(R.id.numberName).text.toString())
        key(a, KeyEvent.KEYCODE_9); key(a, KeyEvent.KEYCODE_9)
        assertEquals("Brak kanału", a.findViewById<TextView>(R.id.numberName).text.toString())
        idle(2000); assertEquals(View.GONE, box.visibility)
        c.pause().stop().destroy()
    }

    /** Dawny pasek informacji i lista kanałów pod OK (7.17: w Nowoczesnym – OSD „Obsidian Stream”, V717OsdTest). */
    @Test fun longOkOpensMiniMenuShortOkInfo() = classicTheme {
        val c = player(); val a = c.get()
        val mini = a.findViewById<LinearLayout>(R.id.miniMenu)
        idle(6000)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)                               // krótkie OK = pasek informacji
        assertEquals(View.GONE, mini.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.overlay).visibility)
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 2); up(a, KeyEvent.KEYCODE_DPAD_CENTER); idle()
        assertEquals(View.VISIBLE, mini.visibility)
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(7, icons.childCount)                                   // domyślnie: ustawienia, program, opis, ulubione, nagrywanie, audio, silnik
        assertEquals("Ustawienia", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); key(a, KeyEvent.KEYCODE_DPAD_RIGHT); key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Dodaj do ulubionych", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(Repository.isFav(Repository.channels[0]))
        assertEquals(View.GONE, mini.visibility)
        // znika po 3 s
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); up(a, KeyEvent.KEYCODE_DPAD_CENTER); idle()
        assertEquals(View.VISIBLE, mini.visibility)
        idle(3200); assertEquals(View.GONE, mini.visibility)
        // konfiguracja: ukrycie pozycji
        MiniMenu.toggle("settings"); MiniMenu.toggle("channels")
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); up(a, KeyEvent.KEYCODE_DPAD_CENTER); idle()
        assertEquals(7, icons.childCount); assertEquals("Program TV", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        Repository.toggleFav(Repository.channels[0])
        c.pause().stop().destroy()
    }

    @Test fun appShortcutInMiniMenu() {
        val cn = android.content.ComponentName(ctx, SettingsActivity::class.java).flattenToString()
        MiniMenu.addApp(cn, "Moja apka"); MiniMenu.addApp(cn, "Moja apka")
        assertEquals(1, Prefs.miniApps.size)
        assertEquals("📱 Moja apka", MiniMenu.ITEMS[MiniMenu.APP + cn])
        val c = player(); val a = c.get()
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); up(a, KeyEvent.KEYCODE_DPAD_CENTER); idle()
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(8, icons.childCount)
        repeat(7) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Moja apka", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(cn, shadowOf(a).nextStartedActivity.component!!.flattenToString())
        MiniMenu.removeApp(MiniMenu.APP + cn)
        assertTrue(Prefs.miniApps.isEmpty())
        c.pause().stop().destroy()
        val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        all(s.get().window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Szybkie menu ikon (przytrzymaj OK lub ekran)") }.performClick(); idle()
        assertTrue(all(s.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().endsWith("Dodaj skrót aplikacji…") })
        s.pause().stop().destroy()
    }

    @Test fun lookPreviewScrollsWithContentAndMiniSettings() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        val a = c.get()
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val anim = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Pokaż animację paneli" }
        assertTrue(generateSequence(anim as View) { it.parent as? View }.any { it is android.widget.ScrollView })
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("Orientacja ekranu") })
        c.pause().stop().destroy()
        val f = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        assertTrue(all(f.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Szybkie menu ikon w odtwarzaczu" })
        f.pause().stop().destroy()
    }

    @Test fun listsClipAtOwnEdge() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val rv = c.get().findViewById<RecyclerView>(R.id.channels)
        assertEquals(android.graphics.Rect(0, 0, rv.width, rv.height), rv.clipBounds)
        c.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-xhdpi") fun portraitMainLayout() {
        Prefs.orientation = 2
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, a.requestedOrientation)
        val content = a.findViewById<LinearLayout>(R.id.content)
        assertEquals(LinearLayout.VERTICAL, content.orientation)
        // (7.22) telefon pionowo: u góry „Szukaj” + pigułki kategorii, pod nimi podgląd z programem, dalej kanały
        assertSame(a.portraitChips, content.getChildAt(1)); assertEquals(R.id.infoPanel, content.getChildAt(2).id)
        assertTrue(a.findViewById<RecyclerView>(R.id.channels).adapter!!.itemCount > 0)
        c.pause().stop().destroy()
        val p = player()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER, p.get().requestedOrientation)
        p.pause().stop().destroy()
        Prefs.orientation = 0
    }
}

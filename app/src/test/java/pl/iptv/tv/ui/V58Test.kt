package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/** v5.8: motywy PS5 i Xbox, geometria motywów (karty poziome, ramki okien, ikony, przejścia), kafelek silnika, potok logo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V58Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun down(a: android.app.Activity, code: Int, repeat: Int = 0) = a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0; Prefs.mainLayout = 0
        Prefs.remove("ok_long2"); Prefs.miniMenuEnabled = true; MiniMenu.reset(); Prefs.miniApps = emptyList()
        Prefs.playerEngine = 0; Prefs.hwAccel = true; Prefs.piconUrl = ""
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v58.m3u").apply { writeText((1..8).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 2}\",Kanał $it HD\nhttp://v58/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed(); Prefs.mainLayout = 0; Prefs.remove("anim") }

    // (7.27) test „consoleThemesSetGeometryAndLayout” usunięty – dotyczył usuniętych motywów Klasyczny / Xbox / X25 / PS5 (kopia: docs/archiwum_motywy/)
    @Test fun themeTransitions() {
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        val sh = shadowOf(s.get())
        val expect = mapOf(4 to (R.anim.float_in to R.anim.hold), 5 to (R.anim.spring_in_right to R.anim.parallax_out_left),
            6 to (R.anim.snap_in to R.anim.hold), 7 to (R.anim.console_in to R.anim.hold), 8 to (R.anim.xbox_in to R.anim.hold))
        expect.forEach { (anim, pair) ->
            Ui.anim = anim; s.get().startActivity(Intent(s.get(), GuideActivity::class.java))
            assertEquals("anim $anim", pair.first, sh.pendingTransitionEnterAnimationResourceId)
            assertEquals("anim $anim", pair.second, sh.pendingTransitionExitAnimationResourceId)
        }
        Ui.anim = 5; s.get().finish()
        assertEquals(R.anim.parallax_in_left, sh.pendingTransitionEnterAnimationResourceId)
        assertEquals(R.anim.spring_out_right, sh.pendingTransitionExitAnimationResourceId)
        s.pause().stop().destroy()
    }

    // (7.27) test „dialogFramesPerTheme” usunięty – dotyczył usuniętych motywów Klasyczny / Xbox / X25 / PS5 (kopia: docs/archiwum_motywy/)
    // (7.27) test „horizontalCardsLayout” usunięty – układ kart narzucany przez motyw Xbox (kopia: docs/archiwum_motywy/)
    @Test fun holdOkShowsEngineTileOpeningPopup() {
        Prefs.okLongAction = 1
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(600)
        val a = c.get()
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.miniMenu).visibility)   // ustawienie: szybkie menu ikon (7.5: domyślnie menu silnika)
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(7, icons.childCount)
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Silnik i akceleracja", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        // (7.20) okno silnika z kartami (EngineSheet) – bez pozycji „Szybkie menu ikon” (otwarte z menu ikon)
        val d = EngineSheet.shown!!
        assertTrue(d.isShowing)
        val tags = all(d.window!!.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith("engine:") }
        assertTrue(tags.toString(), tags.take(2) == listOf("engine:engine", "engine:hw"))
        assertFalse(all(d.window!!.decorView).any { it.contentDescription == "Szybkie menu ikon" })
        all(d.window!!.decorView).first { it.tag == "engine:hw" }.performClick(); idle()
        assertFalse(Prefs.hwAccel); Prefs.hwAccel = true
        d.dismiss(); idle()
        c.pause().stop().destroy()
    }

    // (7.27) test „roundIconTilesInConsoleTheme” usunięty – dotyczył usuniętego motywu Xbox (kopia: docs/archiwum_motywy/)
    @Test fun logoFallbackChainAndMonogram() {
        val ch = Repository.channels[0]
        assertTrue(Repository.piconCandidates(ch).isEmpty())
        val iv = PiconView(ctx)
        iv.loadPicon(ch)
        assertTrue(iv.drawable is MonogramDrawable)                         // brak logo – inicjały, nie pusty kafelek
        ch.epgIcon = "http://epg.invalid/c1.png"
        val withLogo = ch.copy(logo = "http://logo.invalid/c1.png").also { it.epgIcon = "http://epg.invalid/c1.png" }
        assertEquals(listOf("http://logo.invalid/c1.png", "http://epg.invalid/c1.png"), Repository.piconCandidates(withLogo))
        ch.epgIcon = null
        // monogram rysuje się bez błędów
        val bmp = android.graphics.Bitmap.createBitmap(120, 72, android.graphics.Bitmap.Config.ARGB_8888)
        MonogramDrawable("Kanał Sport HD").apply { setBounds(0, 0, 120, 72) }.draw(android.graphics.Canvas(bmp))
        assertFalse(piconFailedRecently("http://nigdy.invalid/x.png"))
    }
}

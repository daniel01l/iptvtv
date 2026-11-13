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
        Prefs.remove("ok_long"); Prefs.miniMenuEnabled = true; MiniMenu.reset(); Prefs.miniApps = emptyList()
        Prefs.playerEngine = 0; Prefs.hwAccel = true; Prefs.piconUrl = ""
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v58.m3u").apply { writeText((1..8).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 2}\",Kanał $it HD\nhttp://v58/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed(); Prefs.mainLayout = 0; Prefs.remove("anim") }

    @Test fun consoleThemesSetGeometryAndLayout() {
        for (id in listOf("magic", "ios", "win11", "ps5", "xbox")) assertTrue(id, Ui.PRESETS.any { it.id == id })
        Ui.applyPreset("ps5"); Ui.changed()
        assertEquals(Ui.S_PS, Ui.surface)
        assertEquals(Ui.LAYOUT_CARDS, Prefs.mainLayout)                      // motyw konsolowy = karty poziome
        assertTrue(Ui.geo.glow); assertTrue(Ui.geo.zoomMax > 0.08f)
        assertTrue(Ui.panelBg() is TexturedDrawable)
        val focused = (Ui.itemBg() as android.graphics.drawable.StateListDrawable).apply { state = intArrayOf(android.R.attr.state_focused) }.current
        assertTrue(focused is android.graphics.drawable.LayerDrawable)        // poświata
        Ui.applyPreset("xbox"); Ui.changed()
        assertEquals(Ui.LAYOUT_CARDS, Prefs.mainLayout)
        assertEquals(2, Ui.preset.radius); assertEquals(3, Ui.geo.focusStroke); assertTrue(Ui.geo.topBar)
        Ui.applyPreset("ios"); Ui.changed()
        assertEquals(0, Prefs.mainLayout)                                    // zwykły motyw przywraca układ
        assertTrue(Ui.geo.titleCenter); assertTrue(Ui.geo.headerScale > 1.3f)
        // każdy motyw ma własne przejście ekranów
        assertEquals(listOf(4, 5, 6, 7, 8), listOf("magic", "ios", "win11", "ps5", "xbox").map { id -> Ui.PRESETS.first { it.id == id }.anim })
        assertEquals(9, Ui.ANIMS.size)
    }

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

    @Test fun dialogFramesPerTheme() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)
        Ui.applyPreset("ios"); Ui.changed()
        Dialogs.confirm(m.get(), "Tytuł", "Treść") {}; idle()
        var d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val t = d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!
        assertEquals(android.view.Gravity.CENTER_HORIZONTAL, t.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        assertTrue(t.textSize > Ui.dp(26))                                   // duży nagłówek
        d.dismiss(); idle()
        Ui.applyPreset("xbox"); Ui.changed()
        Dialogs.confirm(m.get(), "Tytuł 2", "Treść") {}; idle()
        d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.window!!.decorView.background is TexturedDrawable)
        assertEquals(android.view.Gravity.START, d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        d.dismiss(); idle()
        m.pause().stop().destroy()
    }

    @Test fun horizontalCardsLayout() {
        Ui.applyPreset("ps5"); Ui.changed()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(600)
        val a = c.get()
        val rv = a.findViewById<RecyclerView>(R.id.channels)
        assertEquals(LinearLayoutManager.HORIZONTAL, (rv.layoutManager as LinearLayoutManager).orientation)
        val first = rv.findViewHolderForAdapterPosition(0)!!.itemView as LinearLayout
        assertEquals(LinearLayout.VERTICAL, first.orientation)
        assertEquals(Ui.dp(196), first.layoutParams.width)
        assertTrue(all(first).filterIsInstance<PiconView>().single().layoutParams.width >= Ui.dp(120))
        rv.focusPosition(0); idle()
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, rv.findContainingViewHolder(a.window.decorView.findFocus()!!)!!.bindingAdapterPosition)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT)   // z pierwszej karty – kategorie
        assertTrue(generateSequence(a.window.decorView.findFocus() as View?) { it.parent as? View }.any { it.id == R.id.groups })
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertNotNull(rv.findContainingViewHolder(a.window.decorView.findFocus()!!))
        c.pause().stop().destroy()
    }

    @Test fun holdOkShowsEngineTileOpeningPopup() {
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(600)
        val a = c.get()
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.miniMenu).visibility)   // domyślnie: szybkie menu ikon
        val icons = a.findViewById<LinearLayout>(R.id.miniIcons)
        assertEquals(7, icons.childCount)
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Silnik i akceleracja", a.findViewById<TextView>(R.id.miniLabel).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        val labels = (0 until lv.adapter.count).map { lv.adapter.getItem(it).toString() }
        assertTrue(labels.toString(), labels[0].startsWith("▶ Silnik:"))
        assertTrue(labels.toString(), labels[1].startsWith("⚡ Akceleracja sprzętowa"))
        assertFalse(labels.any { it.contains("Szybkie menu ikon") })
        lv.performItemClick(lv.getChildAt(1), 1, 1); idle()
        assertFalse(Prefs.hwAccel); Prefs.hwAccel = true
        d.dismiss(); idle()
        c.pause().stop().destroy()
    }

    @Test fun roundIconTilesInConsoleTheme() {
        Ui.applyPreset("ps5"); Ui.changed()
        val bg = (Ui.iconBoxBg() as android.graphics.drawable.StateListDrawable).current as android.graphics.drawable.GradientDrawable
        assertEquals(android.graphics.drawable.GradientDrawable.OVAL, bg.shape)
        Ui.applyPreset("xbox"); Ui.changed()
        val sq = (Ui.iconBoxBg() as android.graphics.drawable.StateListDrawable).current as android.graphics.drawable.GradientDrawable
        assertEquals(android.graphics.drawable.GradientDrawable.RECTANGLE, sq.shape)
    }

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

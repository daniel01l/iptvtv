package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
import pl.iptv.tv.data.Scores
import java.io.File
import java.time.Duration

/** v5.7: motywy MagicOS/iOS/Windows 11, menu odtwarzania (przytrzymanie OK), wyniki bez spoilerów, gole, EPG, ustawienia, przejścia. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V57Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle() }
    private fun down(a: android.app.Activity, code: Int, repeat: Int = 0) = a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0
        Prefs.okLongAction = 0; Prefs.miniMenuEnabled = true; Prefs.playerEngine = 0; Prefs.hwAccel = true; Prefs.asyncDecoder = 0
        Prefs.scoreHideResults = false; Prefs.scoresEnabled = true; Prefs.scoreGoalAlerts = true
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v57.m3u").apply { writeText((1..8).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 2}\",K$it\nhttp://v57/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Scores.clearGoalsForTest(); Scores.setLastForTest(emptyList(), 0)
    }

    private fun match(hs: Int?, aws: Int?, state: Scores.State = Scores.State.LIVE, id: String = "m1") =
        Scores.Match(id, "eng.1", "🏴 Premier League", "Leeds", "Spurs", "1", "2", hs, aws, state, "45'", System.currentTimeMillis())

    @Test fun newThemesWithTextures() {
        for (id in listOf("magic", "ios", "win11")) assertTrue(id, Ui.PRESETS.any { it.id == id })
        Ui.applyPreset("win11"); Ui.changed()
        assertEquals(Ui.S_MICA, Ui.surface)
        assertTrue(Ui.bgDrawable() is TexturedDrawable)
        assertTrue(Ui.panelBg() is TexturedDrawable)
        assertTrue(Ui.dialogBg() is TexturedDrawable)
        Ui.applyPreset("ios"); Ui.changed()
        assertEquals(Ui.S_GLASS, Ui.surface)
        assertTrue(Ui.surfaceBg() is TexturedDrawable)
        Ui.applyPreset("magic"); Ui.changed()
        assertEquals(Ui.S_CARD, Ui.surface)
        assertEquals(18, Ui.preset.radius)
        // własny kolor tła (gradient) ma pierwszeństwo przed fakturą
        Ui.setGradient("bg", 0xFF000000.toInt(), 0xFF202020.toInt())
        assertTrue(Ui.bgDrawable() is android.graphics.drawable.GradientDrawable)
        Ui.applyPreset("modern"); Ui.changed()
        assertEquals(Ui.S_FLAT, Ui.surface)
        assertTrue(Ui.panelBg() is android.graphics.drawable.GradientDrawable)
        // ekrany z nowymi motywami otwierają się bez błędów
        for (id in listOf("magic", "ios", "win11")) {
            Ui.applyPreset(id); Ui.changed()
            val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
            val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)
            Dialogs.confirm(m.get(), "Test", "Okno") {}; idle()
            assertTrue(ShadowDialog.getLatestDialog().window!!.decorView.background is TexturedDrawable)
            ShadowDialog.getLatestDialog().dismiss(); idle()
            m.pause().stop().destroy(); s.pause().stop().destroy()
        }
        Ui.applyPreset("modern"); Ui.changed()
    }

    @Test fun itemBackgroundsCachedPerLook() {
        val a = Ui.itemBg(); val b = Ui.itemBg()
        assertNotSame(a, b)
        assertSame(a.constantState!!.javaClass, b.constantState!!.javaClass)
        Ui.applyPreset("magic"); Ui.changed()
        val c = Ui.itemBg() as android.graphics.drawable.StateListDrawable
        c.state = intArrayOf(android.R.attr.state_focused)
        assertEquals(Ui.focus, (c.current as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        Ui.applyPreset("modern"); Ui.changed()
    }

    @Test fun transitionsKeepScreenBelowOpaque() {
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup(); idle()
        // okno ma tło motywu od pierwszej klatki
        assertNotNull(s.get().window.decorView.background)
        Ui.anim = 1
        val sh = shadowOf(s.get())
        s.get().startActivity(Intent(s.get(), GuideActivity::class.java))
        assertEquals(R.anim.fade_in, sh.pendingTransitionEnterAnimationResourceId)
        assertEquals(R.anim.hold, sh.pendingTransitionExitAnimationResourceId)
        s.get().finish()
        assertEquals(R.anim.hold, sh.pendingTransitionEnterAnimationResourceId)
        assertEquals(R.anim.fade_out, sh.pendingTransitionExitAnimationResourceId)
        Ui.anim = 3; s.get().startActivity(Intent(s.get(), GuideActivity::class.java))
        assertEquals(R.anim.hold, sh.pendingTransitionExitAnimationResourceId)
        Prefs.remove("anim")
        s.pause().stop().destroy()
    }

    @Test fun settingsSectionTogglesWithoutRebuild() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        val a = c.get()
        val header = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Silnik odtwarzania i akceleracja") }
        assertTrue(header.text.startsWith("▸"))
        val row0 = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Akceleracja sprzętowa obrazu" }
        assertFalse(row0.isShown)
        header.performClick(); idle()
        val row1 = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Akceleracja sprzętowa obrazu" }
        assertSame(row0, row1)                                               // te same widoki – karta nie była budowana od nowa
        assertTrue(row1.isShown); assertTrue(header.text.startsWith("▾"))
        assertNotNull((row1.parent as View).background)                    // styl nałożony przy rozwinięciu
        (row1.parent as View).performClick(); idle()
        assertFalse(Prefs.hwAccel)
        header.performClick(); idle()
        Prefs.hwAccel = true
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Przytrzymanie OK / ekranu" })
        c.pause().stop().destroy()
    }

    @Test fun holdOkOpensPlaybackMenuAndSwitchesEngine() {
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(600)
        val a = c.get()
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        fun label(i: Int) = lv.adapter.getItem(i).toString()
        assertTrue(label(0), label(0).startsWith("▶ Silnik: dekoder sprzętowy"))
        assertTrue(label(1), label(1).contains("Akceleracja sprzętowa: ✔"))
        val before = Playback.player
        lv.performItemClick(lv.getChildAt(1), 1, 1); idle()
        assertFalse(Prefs.hwAccel)
        assertTrue(Playback.softwareDecoding)
        assertNotSame(before, Playback.player)                              // nowy odtwarzacz z nowymi ustawieniami
        assertTrue(Playback.active)
        assertTrue(label(1), label(1).contains("✖ wyłączona"))
        lv.performItemClick(lv.getChildAt(0), 0, 0); idle()
        assertEquals(1, Prefs.playerEngine)
        assertTrue(label(0).startsWith("▶ Silnik: dekoder programowy"))
        // do szybkiego menu ikon
        lv.performItemClick(lv.getChildAt(4), 4, 4); idle()
        assertFalse(d.isShowing)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.miniMenu).visibility)
        Prefs.playerEngine = 0; Prefs.hwAccel = true
        // ustawienie: przytrzymanie OK = szybkie menu ikon
        Prefs.okLongAction = 1; idle(3500)
        down(a, KeyEvent.KEYCODE_DPAD_CENTER); down(a, KeyEvent.KEYCODE_DPAD_CENTER, 1); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.miniMenu).visibility)
        c.pause().stop().destroy()
    }

    @Test fun hiddenResultsAndLiveLabels() {
        val m = match(1, 0)
        assertEquals("1:0", m.shownScore)
        Prefs.scoreHideResults = true
        assertEquals("?:?", m.shownScore)
        assertEquals("–:–", match(null, null, Scores.State.PRE).shownScore)
        Scores.assign(Scores.teamKey("eng.1", "1"), listOf(Repository.channels[0].key))
        Scores.setLastForTest(listOf(m))
        assertEquals("⚽ Leeds – Spurs  45'", Scores.liveFor(Repository.channels[0]))
        Prefs.scoreHideResults = false
        assertEquals("⚽ Leeds 1:0 Spurs  45'", Scores.liveFor(Repository.channels[0]))  // przeliczone po zmianie ustawienia
        Scores.assign(Scores.teamKey("eng.1", "1"), emptyList())
    }

    @Test fun goalsQueuedOnceAcrossScreens() {
        val f = Scores.Follow("eng.1", null, "PL")
        Prefs.scoreFollowJson = "[]"; Scores.follow(f)
        Scores.setLastForTest(listOf(match(0, 0)))
        // gol wykryty przy pobraniu (dowolny ekran) trafia do kolejki – jeden raz
        val g = Scores.goalMatches(Scores.last, listOf(match(1, 0)))
        assertEquals(1, g.size)
        val q = Scores::class.java.getDeclaredMethod("queueGoals", List::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        q.invoke(Scores, g, System.currentTimeMillis()); q.invoke(Scores, g, System.currentTimeMillis())
        assertEquals(1, Scores.takeGoals().size)
        assertTrue(Scores.takeGoals().isEmpty())
        // stare gole (sprzed > 5 min) nie są pokazywane
        q.invoke(Scores, listOf(match(2, 0)), System.currentTimeMillis() - 10 * 60_000)
        assertTrue(Scores.takeGoals().isEmpty())
        Scores.unfollow(f)
    }

    @Test fun goalToastShowsAllGoalsInOrder() {
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(600)
        val a = c.get()
        val show = PlayerActivity::class.java.getDeclaredMethod("showGoals", List::class.java).apply { isAccessible = true }
        show.invoke(a, listOf(match(1, 0, id = "a"), match(2, 2, id = "b")))
        idle(400)
        val box = a.findViewById<View>(R.id.goalBox)
        val t = a.findViewById<TextView>(R.id.goalToast)
        assertEquals(View.VISIBLE, box.visibility)
        assertTrue(t.text.toString(), t.text.contains("1:0"))
        idle(6500)                                                          // pierwszy znika, pokazuje się drugi
        assertEquals(View.VISIBLE, box.visibility)
        assertTrue(t.text.toString(), t.text.contains("2:2"))
        idle(6500)
        assertEquals(View.GONE, box.visibility)
        // bez spoilerów: bez wyniku
        Prefs.scoreHideResults = true
        show.invoke(a, listOf(match(3, 0, id = "c"))); idle(400)
        assertFalse(t.text.toString(), t.text.contains("3:0")); assertTrue(t.text.contains("Leeds  –  Spurs"))
        Prefs.scoreHideResults = false
        c.pause().stop().destroy()
    }

    @Test fun epgShowsLiveScoreForCurrentProgramme() {
        val ch = Repository.channels[0]
        val now = System.currentTimeMillis()
        val p = pl.iptv.tv.data.Programme(now - 600_000, now + 600_000, "Leeds – Spurs", "", "", "")
        Scores.assign(Scores.teamKey("eng.1", "1"), listOf(ch.key))
        Scores.setLastForTest(listOf(match(1, 0)))
        assertEquals("⚽ Leeds 1:0 Spurs  45'", Scores.liveForProgramme(ch, p, now))
        val past = pl.iptv.tv.data.Programme(now - 7_200_000, now - 3_600_000, "Wcześniej", "", "", "")
        assertNull(Scores.liveForProgramme(ch, past, now))
        Scores.assign(Scores.teamKey("eng.1", "1"), emptyList())
    }

    @Test fun piconViewDoesNotRelayoutOnImage() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val rv = m.get().findViewById<RecyclerView>(R.id.channels)
        val pv = all(rv).filterIsInstance<PiconView>().firstOrNull()
        assertNotNull(pv)
        pv!!
        assertFalse(pv.isLayoutRequested)
        pv.setImageDrawable(android.graphics.drawable.ColorDrawable(0xFF00FF00.toInt()))
        assertFalse(pv.isLayoutRequested)
        m.pause().stop().destroy()
    }
}

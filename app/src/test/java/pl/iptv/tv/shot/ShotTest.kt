package pl.iptv.tv.shot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.ui.*
import java.io.File
import java.time.Duration

/** Zrzuty ekranu do ręcznej oceny wyglądu (uruchamiane tylko z -Dshots=1). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun save(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File("/tmp/shots").mkdirs(); File("/tmp/shots/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        if (System.getProperty("shots") != "1") return
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false; Prefs.keyMapJson = ""
        val f = File(ctx.filesDir, "s.m3u").apply { writeText((1..20).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 catchup=\"default\" catchup-days=\"7\" catchup-source=\"?utc={utc}\" tvg-id=\"c$it\" group-title=\"G${it % 3}\",Kanał $it HD\nhttp://s/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        save(m.get().window.decorView, "main")
        Dialogs.confirm(m.get(), "Usunąć listę?", "Lista „Moja” zostanie usunięta z urządzenia.") {}; idle()
        save(ShadowDialog.getLatestDialog().window!!.decorView, "dlg_confirm")
        ShadowDialog.getLatestDialog().dismiss()
        Dialogs.menu(m.get(), "Opcje kanału", listOf("★ Do ulubionych" to {}, "✎ Zmień nazwę" to {}, "🔒 Zablokuj" to {})); idle()
        save(ShadowDialog.getLatestDialog().window!!.decorView, "dlg_menu")
        ShadowDialog.getLatestDialog().dismiss()
        Prefs.groupHidden = listOf("G2")
        m.get().findViewById<androidx.recyclerview.widget.RecyclerView>(pl.iptv.tv.R.id.groups).let { rv ->
            m.get().runOnUiThread { }; idle(); rv.findViewHolderForAdapterPosition((rv.adapter!!.itemCount) - 1)?.itemView?.performClick() }
        idle(600); save(ShadowDialog.getLatestDialog().window!!.decorView, "groups_editor")
        ShadowDialog.getLatestDialog().dismiss(); idle(); Prefs.groupHidden = emptyList()
        val cl = Dialogs.choiceLive(m.get(), "Motyw", listOf("Nowoczesny", "Klasyczny dekoder", "AMOLED czerń", "Jasny", "Neon retro", "Kino", "Leśny"), 1) {}; idle()
        save(cl.window!!.decorView, "choice1"); cl.listView.performItemClick(cl.listView.getChildAt(3), 3, 3); idle(); save(cl.window!!.decorView, "choice2"); cl.dismiss()
        m.pause().stop().destroy()
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[3].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(6000)
        val a = p.get()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle(600)
        save(a.window.decorView, "zap")
        idle(8000)
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)); a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle(600)
        save(a.window.decorView, "mini")
        p.pause().stop().destroy()
        val st = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle(800)
        save(st.get().window.decorView, "tv_settings"); st.pause().stop().destroy()
        val tu = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        Tutorial.show(tu.get(), true); idle(); save(ShadowDialog.getLatestDialog().window!!.decorView, "tutorial_tv"); ShadowDialog.getLatestDialog().dismiss()
        tu.pause().stop().destroy()
        val s3 = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(800)
        save(s3.get().window.decorView, "tv_settings_play"); s3.pause().stop().destroy()
        val now = System.currentTimeMillis()
        val cu = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[3].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
            .putExtra(PlayerActivity.EXTRA_START, now - 7_200_000).putExtra(PlayerActivity.EXTRA_END, now - 3_600_000)).setup(); idle(800)
        cu.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); cu.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle(800)
        save(cu.get().window.decorView, "catchup"); cu.pause().stop().destroy()
        Prefs.setupDone = false
        val w = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        fun click(t: String) { all(w.get().window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString().endsWith(t) }.performClick(); idle() }
        click("Dalej ►"); save(w.get().window.decorView, "wiz_list")
        click("Pomiń krok"); save(w.get().window.decorView, "wiz_epg")
        click("Pomiń krok"); click("Pomiń krok"); save(w.get().window.decorView, "wiz_look")
        w.get().window.decorView.findViewById<android.widget.ScrollView>(android.view.View.NO_ID)
        all(w.get().window.decorView).filterIsInstance<android.widget.ScrollView>().first().scrollTo(0, 120); idle()
        save(w.get().window.decorView, "wiz_look_scrolled")
        w.pause().stop().destroy(); Prefs.setupDone = true
    }

    @Test fun scores() {
        if (System.getProperty("shots") != "1") return
        Playback.stop(); Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.playlistsJson = "[]"; Prefs.keyMapJson = ""
        Prefs.scoresEnabled = true; Prefs.apiFootballKey = ""
        pl.iptv.tv.data.Scores.follows = listOf(pl.iptv.tv.data.Scores.Follow("eng.1", null, "PL"), pl.iptv.tv.data.Scores.Follow("tsdb:4554", null, "Dart"))
        val f = File(ctx.filesDir, "s.m3u").apply { writeText((1..5).joinToString("", "#EXTM3U\n") { "#EXTINF:-1,K$it\nhttp://s/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup()
        repeat(40) { Thread.sleep(100); idle(200) }
        println("DBGS " + pl.iptv.tv.data.Scores.last.take(3).map { it.home + "|" + it.homeLogo })
        val a = p.get()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); idle(800)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); idle(800)
        repeat(40) { Thread.sleep(100); idle(100) }
        save(a.window.decorView, "scores")
        val m0 = pl.iptv.tv.data.Scores.last.first { it.homeLogo.isNotBlank() }
        PlayerActivity::class.java.getDeclaredMethod("goalToast", pl.iptv.tv.data.Scores.Match::class.java).apply { isAccessible = true }.invoke(a, m0.copy(hs = 1, aws = 0))
        repeat(20) { Thread.sleep(100); idle(100) }
        save(a.window.decorView, "goal")
        p.pause().stop().destroy(); pl.iptv.tv.data.Scores.follows = emptyList()
    }

    @Test fun lock() {
        if (System.getProperty("shots") != "1") return
        pl.iptv.tv.data.Activation.forceForTest = true
        ctx.getSharedPreferences("activation", 0).edit().clear().commit()
        val l = Robolectric.buildActivity(LockActivity::class.java).setup(); idle(500); save(l.get().window.decorView, "lock_consent")
        pl.iptv.tv.data.Activation.consent = true; l.pause(); l.resume(); idle(500)
        l.get().recreate(); idle(800)
        save(l.get().window.decorView, "lock_code"); l.pause().stop().destroy()
        pl.iptv.tv.data.Activation.forceForTest = false
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test @Config(qualifiers = "w400dp-h800dp-port-mdpi") fun phone() {
        if (System.getProperty("shots") != "1") return
        Playback.stop(); Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.playlistsJson = "[]"; Prefs.keyMapJson = ""; Prefs.orientation = 2
        val f = File(ctx.filesDir, "s.m3u").apply { writeText((1..20).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 catchup=\"default\" catchup-days=\"7\" catchup-source=\"?utc={utc}\" tvg-id=\"c$it\" group-title=\"G${it % 3}\",Kanał $it HD\nhttp://s/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        save(m.get().window.decorView, "phone_main"); m.pause().stop().destroy()
        val p = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[3].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(1000)
        p.get().dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)); p.get().dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        p.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle(600)
        save(p.get().window.decorView, "phone_mini"); idle(4000)
        run { val t0 = android.os.SystemClock.uptimeMillis()
            p.get().dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0, 0, 200f, 400f, 0)); p.get().dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 40, 1, 200f, 400f, 0)); idle(700)
            save(p.get().window.decorView, "phone_tap"); idle(4000) }
        p.get().findViewById<View>(pl.iptv.tv.R.id.tChannels).performClick(); idle(1200)
        save(p.get().window.decorView, "phone_left")
        p.get().onBackPressedDispatcher.onBackPressed(); idle(1200)
        p.get().findViewById<View>(pl.iptv.tv.R.id.tRecent).performClick(); idle(1200)
        save(p.get().window.decorView, "phone_right")
        p.get().findViewById<View>(pl.iptv.tv.R.id.rpScores).performClick(); idle(1200)
        save(p.get().window.decorView, "phone_scores")
        p.pause().stop().destroy()
        val now = System.currentTimeMillis(); val H = 3_600_000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val xml = "<tv>" + (1..20).joinToString("") { c -> (-12..12).joinToString("") { h ->
            "<programme start=\"${fmt.format(java.util.Date(now / H * H + h * H))}\" stop=\"${fmt.format(java.util.Date(now / H * H + (h + 1) * H))}\" channel=\"c$c\"><title>Program $c-$h z dłuższym tytułem</title><desc>Opis programu numer $h na kanale $c. Dłuższy tekst opisu.</desc></programme>" } } + "</tv>"
        runBlocking { Repository.loadEpg(File(ctx.filesDir, "s.xml").apply { writeText(xml) }.absolutePath) }
        val gl = Robolectric.buildActivity(GuideListActivity::class.java, Intent(ctx, GuideListActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, Repository.channels[2].key)).setup(); idle(800)
        save(gl.get().window.decorView, "phone_guide"); gl.pause().stop().destroy()
        val st = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle(800)
        save(st.get().window.decorView, "phone_settings"); st.pause().stop().destroy()
        val es = Robolectric.buildActivity(EpgSearchActivity::class.java, Intent().putExtra(EpgSearchActivity.EXTRA_QUERY, "Program 3")).setup(); idle(1500)
        save(es.get().window.decorView, "phone_search"); es.pause().stop().destroy()
        Prefs.setupDone = false
        val wz = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle(500)
        all(wz.get().window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString().endsWith("Dalej ►") }.performClick(); idle(500)
        save(wz.get().window.decorView, "phone_wizard"); wz.pause().stop().destroy(); Prefs.setupDone = true
        val tm = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        Tutorial.show(tm.get(), false); idle(600); save(ShadowDialog.getLatestDialog().window!!.decorView, "phone_tutorial"); ShadowDialog.getLatestDialog().dismiss()
        Dialogs.input(tm.get(), "Kod z aplikacji D-iptv Admin (XXXX-XXXX-XXXX)", "", "ABCD-EFGH-JKMN", code = true) {}; idle(400)
        val ed = all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<android.widget.EditText>().first()
        println("DBGF focus=${ed.isFocused}"); ed.setText("abcdefgh2345"); println("DBGF text=${ed.text}")
        ShadowDialog.getLatestDialog().dismiss(); tm.pause().stop().destroy()
        Prefs.orientation = 0
    }
}

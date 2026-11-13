package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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
import pl.iptv.tv.data.Scores
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V38Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(600) }
    private val srv = MockWebServer()
    private val board = javaClass.classLoader!!.getResource("espn_scoreboard.json").readText()
    private val teams = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"363","displayName":"Chelsea"}},{"team":{"id":"360","displayName":"Manchester United"}}]}]}]}"""
    private val af = """{"errors":[],"response":[{"fixture":{"id":77,"timestamp":1700000000,"status":{"short":"2H","elapsed":67}},
        "league":{"id":106},"teams":{"home":{"id":339,"name":"Legia Warszawa"},"away":{"id":347,"name":"Lech Poznań"}},"goals":{"home":2,"away":1}},
        {"fixture":{"id":78,"timestamp":1700000000,"status":{"short":"NS","elapsed":null}},"league":{"id":999},"teams":{"home":{"id":1,"name":"X"},"away":{"id":2,"name":"Y"}},"goals":{"home":null,"away":null}}]}"""

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.keyMapJson = ""; Prefs.scoresEnabled = true; Prefs.apiFootballKey = ""; Scores.follows = emptyList()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when {
                r.path!!.contains("/scoreboard") -> MockResponse().setBody(board)
                r.path!!.contains("/teams") && r.path!!.startsWith("/espn") -> MockResponse().setBody(teams)
                r.path!!.startsWith("/af/fixtures") -> if (r.getHeader("x-apisports-key") == "K") MockResponse().setBody(af) else MockResponse().setResponseCode(401)
                else -> MockResponse().setResponseCode(404)
            }
        }
        srv.start()
        Scores.espnBase = srv.url("/espn").toString(); Scores.afBase = srv.url("/af").toString()
        val f = File(ctx.filesDir, "v38.m3u").apply { writeText((1..6).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\",K$it\nhttp://v38/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun down() { srv.shutdown(); Scores.follows = emptyList(); Prefs.apiFootballKey = "" }

    @Test fun espnFollowTeamParsesScoreAndEvents() {
        assertNull(Scores.available().firstOrNull { it.name.contains("Ekstraklasa") })      // ESPN nie ma polskich lig
        assertEquals(listOf("363" to "Chelsea", "360" to "Manchester United"), runBlocking { Scores.teams(Scores.league("eng.1")!!) })
        Scores.follow(Scores.Follow("eng.1", "363", "Chelsea")); Scores.follow(Scores.Follow("eng.1", "363", "Chelsea"))
        assertEquals(1, Scores.follows.size)
        val m = runBlocking { Scores.fetch() }
        assertEquals(1, m.size)
        assertEquals("Chelsea", m[0].home); assertEquals("Man Utd", m[0].away); assertEquals("2:0", m[0].score)
        assertEquals(Scores.State.POST, m[0].state); assertEquals("KONIEC", m[0].clock)
        assertTrue(m[0].events.contains("50' ⚽ Morgan Rogers (Chelsea)"))
        assertTrue(m[0].events.any { it.startsWith("63' 🟥") })
        assertTrue(m[0].events.any { it.contains("⚽ (k.) Cole Palmer") })
        assertTrue(m[0].homeLogo.endsWith("/363.png"))
        // cała liga -> wszystkie mecze dnia
        Scores.follow(Scores.Follow("eng.1", null, "Premier League"))
        assertTrue(runBlocking { Scores.fetch() }.size > 1)
    }

    @Test fun apiFootballAndGoalsAndChannel() {
        Prefs.apiFootballKey = "K"
        assertNotNull(Scores.available().firstOrNull { it.name.contains("Ekstraklasa") })
        Scores.follow(Scores.Follow("af:106", null, "Ekstraklasa"))
        val m = runBlocking { Scores.fetch() }
        assertEquals(1, m.size)
        assertEquals(Scores.State.LIVE, m[0].state); assertEquals("67'", m[0].clock); assertEquals("2:1", m[0].score)
        val before = listOf(m[0].copy(hs = 1))
        assertEquals(listOf("⚽ GOL!  Legia Warszawa 2:1 Lech Poznań"), Scores.goals(before, m))
        assertTrue(Scores.goals(m, m).isEmpty())
        Prefs.apiFootballKey = "zly"
        assertTrue(runCatching { runBlocking { Scores.fetch() } }.isFailure)
        // kanał z EPG
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val xml = "<tv><programme start=\"${fmt.format(Date(now - 3_600_000))}\" stop=\"${fmt.format(Date(now + 3_600_000))}\" channel=\"c3\"><title>Piłka nożna: Legia Warszawa - Lech Poznań</title></programme></tv>"
        val x = File(ctx.filesDir, "v38.xml").apply { writeText(xml) }
        runBlocking { Repository.loadEpg(x.absolutePath) }
        assertEquals("K3", Scores.channelFor(m[0])?.name)
        // kanały przypisane do drużyny i ligi
        Scores.assign(Scores.teamKey("af:106", "339"), listOf(Repository.channels[4].key, Repository.channels[1].key))
        Scores.assign(Scores.leagueKey("af:106"), listOf(Repository.channels[1].key))
        assertEquals(listOf("K5", "K2"), Scores.assignedFor(m[0]).map { it.name })
        Scores.assign(Scores.teamKey("af:106", "339"), emptyList()); Scores.assign(Scores.leagueKey("af:106"), emptyList())
        assertTrue(Scores.assignedFor(m[0]).isEmpty())
    }

    @Test fun broadcastFoundAutomaticallyInEpg() {
        val now = System.currentTimeMillis(); val H = 3_600_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun prog(ch: Int, from: Long, to: Long, title: String, desc: String = "") =
            "<programme start=\"${fmt.format(Date(from))}\" stop=\"${fmt.format(Date(to))}\" channel=\"c$ch\"><title>$title</title><desc>$desc</desc></programme>"
        val start = now + 3 * H
        val xml = "<tv>" + prog(2, now - H, now + 5 * H, "Piłka nożna: Liga angielska", "Mecz 12. kolejki: Tottenham Hotspur – Manchester United") +
            prog(4, start - 10 * 60_000, start + 2 * H, "Magazyn: przed meczem Barcelony", "Liga hiszpańska") +
            prog(5, now - H, now + 5 * H, "Skoki narciarskie", "Puchar Świata") +
            prog(6, now - H, now + 5 * H, "Teleexpress") +
            // prawdziwe tytuły z polskiego EPG (Canal+ Extra): inna drużyna z „United” nie może zostać uznana za pewną
            prog(1, now - H, now + 2 * H, "Fulham FC - Newcastle United - Fulham FC - Newcastle United", "G: Sport, Pilka nozna. Premier League") +
            prog(3, now - H, now + 2 * H, "Leeds United - Tottenham Hotspur - Leeds United - Tottenham Hotspur", "G: Sport, Pilka nozna. R: 2026") + "</tv>"
        val x = File(ctx.filesDir, "v38b.xml").apply { writeText(xml) }
        Prefs.epgUrl = x.absolutePath
        runBlocking { Repository.loadEpg(x.absolutePath) }
        val live = Scores.Match("1", "eng.1", "PL", "Spurs", "Man Utd", "367", "360", 1, 0, Scores.State.LIVE, "50'", now - H, homeFull = "Tottenham Hotspur", awayFull = "Manchester United")
        val b = Scores.broadcasts(live)
        assertEquals("K2", b.first().channel.name); assertTrue(b.first().sure)
        assertTrue(b.none { it.channel.name == "K5" || it.channel.name == "K6" })
        // przed meczem: audycja w czasie rozpoczęcia; odmiana nazwy („Barcelony”) + słowo sportowe = dopasowanie niepewne
        val pre = Scores.Match("2", "esp.1", "LaLiga", "Barcelona", "Real Madrid", "83", "86", null, null, Scores.State.PRE, "", start, homeFull = "Barcelona", awayFull = "Real Madrid")
        val bp = Scores.broadcasts(pre)
        assertEquals("K4", bp.single().channel.name); assertFalse(bp.single().sure)
        assertEquals("K2", Scores.channelFor(live)?.name)
        val leeds = Scores.Match("3", "eng.1", "PL", "Leeds", "Spurs", "357", "367", 0, 0, Scores.State.LIVE, "10'", now, homeFull = "Leeds United", awayFull = "Tottenham Hotspur")
        val bl = Scores.broadcasts(leeds)
        assertEquals("K3", bl.first().channel.name); assertTrue(bl.first().sure)
        assertTrue(bl.none { it.channel.name == "K1" })
    }

    @Test fun secondRightOpensScoresTab() {
        Scores.follow(Scores.Follow("eng.1", "363", "Chelsea"))
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        val c = Robolectric.buildActivity(PlayerActivity::class.java, i).setup(); idle()
        val a = c.get()
        repeat(20) { Thread.sleep(50); idle(200) }                          // pierwsze pobranie w tle
        assertEquals(1, Scores.last.size)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rightPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val col = a.findViewById<View>(R.id.scoresColumn)
        assertEquals(View.VISIBLE, col.visibility)
        assertEquals(1, a.findViewById<RecyclerView>(R.id.scoresList).adapter!!.itemCount)
        assertTrue(generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.any { it === col })
        val rp = a.findViewById<android.view.ViewGroup>(R.id.rightPanel)
        assertEquals(rp.childCount - 1, rp.indexOfChild(col))                   // po prawej stronie panelu
        // szczegóły meczu z przyciskiem przypisania kanałów
        a.window.decorView.findFocus()!!.performClick(); idle()
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("➕ Kanały", dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
        dlg.dismiss(); idle()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(View.GONE, col.visibility); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rightPanel).visibility)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(3) { idle(500) }
        assertEquals(View.GONE, a.findViewById<View>(R.id.rightPanel).visibility)
        c.pause().stop().destroy()
        val look = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        fun all0(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all0(g.getChildAt(it)) } } ?: emptyList())
        assertTrue(all0(look.get().window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.contains("uruchom aplikację ponownie") })
        look.pause().stop().destroy()
        val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 8)).setup(); idle()
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        all(s.get().window.decorView).filterIsInstance<android.widget.TextView>().filter { it.text.startsWith("▸") }.forEach { it.performClick(); idle() }
        val t = all(s.get().window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(t.any { it.contains("Chelsea") }); assertTrue(t.any { it.endsWith("Obserwuj drużynę…") })
        s.pause().stop().destroy()
    }
}

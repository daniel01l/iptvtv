package pl.iptv.tv.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Reminders
import pl.iptv.tv.data.Repository
import pl.iptv.tv.reminders.ReminderActivity
import pl.iptv.tv.reminders.ReminderReceiver
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class EpgRemindersTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val H = 3_600_000L
    private val base = System.currentTimeMillis() / 60_000 * 60_000
    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " +0000"
    private fun prog(ch: String, from: Long, to: Long, title: String, ep: String = "", sub: String = "", desc: String = "") =
        """<programme start="${t(from)}" stop="${t(to)}" channel="$ch"><title>$title</title>""" +
            (if (sub.isNotEmpty()) "<sub-title>$sub</sub-title>" else "") +
            (if (ep.isNotEmpty()) """<episode-num system="xmltv_ns">$ep</episode-num>""" else "") +
            (if (desc.isNotEmpty()) "<desc>$desc</desc>" else "") + "</programme>\n"
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setup() {
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true; Prefs.startAction = 0
        File(ctx.filesDir, "reminders.json").delete(); Reminders.init(File(ctx.filesDir, "reminders.json"))
        val m3u = File(ctx.filesDir, "pl.m3u")
        m3u.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" catchup=\"shift\" catchup-days=\"2\",Jedynka\nhttp://t/1\n" +
            "#EXTINF:-1 tvg-id=\"b\",Dwójka\nhttp://t/2\n")
        Repository.resetAllEdits()
        runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
        val xml = "<tv><channel id=\"a\"><display-name>Jedynka</display-name></channel><channel id=\"b\"><display-name>Dwójka</display-name></channel>\n" +
            prog("a", base - 5 * H, base - 4 * H, "Serial (11)", "0.10.", "Stary odcinek") +
            prog("a", base - H, base + H, "Serial (12)", "0.11.", "Bieżący", desc = "kryminał w Gdańsku") +
            prog("a", base + 2 * H, base + 3 * H, "Serial (12)", "0.11.", "Bieżący") +          // powtórka
            prog("b", base + 5 * H, base + 6 * H, "Serial (13)", "0.12.", "Wielki finał") +
            prog("b", base + 24 * H, base + 25 * H, "Serial (14)", "0.13.", "Nowy sezon") +
            prog("b", base - H, base + 2 * H, "Wiadomości") + "</tv>"
        val epg = File(ctx.filesDir, "epg.xml"); epg.writeText(xml)
        runBlocking { Repository.loadEpg(epg.absolutePath) }
    }

    @Test fun searchAndNextEpisode() {
        assertEquals(5, Repository.searchProgrammes("serial", false).size)
        assertEquals(1, Repository.searchProgrammes("finał", false).size)           // po tytule odcinka
        assertEquals(0, Repository.searchProgrammes("gdańsk", false).size)
        assertEquals(1, Repository.searchProgrammes("gdańsk", true).size)           // w opisach
        assertEquals(3, Repository.searchProgrammes("serial", false, Repository.When.UPCOMING).size)
        assertEquals(1, Repository.searchProgrammes("serial", false, Repository.When.ARCHIVE).size)  // tylko kanał z catch-up
        assertEquals(1, Repository.searchProgrammes("serial", false, Repository.When.NOW).size)
        val a = Repository.channels[0]
        val cur = Repository.current(a)!!
        assertEquals("S01E12", cur.episode)
        val next = Repository.nextEpisode(a, cur)!!                    // pomija powtórkę S01E12
        assertEquals("Wielki finał", next.programme.subTitle)
        assertEquals("Dwójka", next.channel.name)
        assertEquals("Nowy sezon", Repository.nextEpisode(next.channel, next.programme)!!.programme.subTitle)
        assertEquals(5, Repository.episodes(cur).size)
    }

    @Test fun remindersSingleAndSeriesWithAlarm() {
        val b = Repository.channels[1]
        val finale = Repository.programmes(b).first { it.subTitle == "Wielki finał" }
        Reminders.add(b, finale)
        pl.iptv.tv.reminders.ReminderScheduler.schedule(ctx)
        val am = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals(finale.start - Prefs.reminderLead * 60_000L, am.nextScheduledAlarm!!.triggerAtTime)

        // seria na wszystkich kanałach -> odcinki przyszłe (powtórka + finał + nowy sezon)
        Reminders.addRule(Repository.current(Repository.channels[0])!!, null)
        Reminders.expandRules(Repository.channels, Repository::programmes, System.currentTimeMillis())
        assertEquals(3, Reminders.all().size)
        // pierwszy alarm teraz = powtórka za 2h
        pl.iptv.tv.reminders.ReminderScheduler.schedule(ctx)
        assertEquals(base + 2 * H - Prefs.reminderLead * 60_000L, am.nextScheduledAlarm!!.triggerAtTime)

        // nadejście alarmu w czasie, gdy aplikacja jest widoczna -> okno przypomnienia
        val due = Reminders.takeDue(base + 2 * H, Prefs.reminderLead * 60_000L)
        assertEquals(1, due.size)
        assertTrue(Reminders.takeDue(base + 2 * H, 0).isEmpty())   // nie pokazuje drugi raz
        Reminders.removeRule(pl.iptv.tv.data.Series.key("Serial"))
        assertEquals(1, Reminders.all().count { !it.notified })     // zostaje pojedyncze przypomnienie o finale
    }

    @Test fun receiverShowsPopupAndPopupSwitchesChannel() {
        val b = Repository.channels[1]
        // przypomnienie o programie, który właśnie się zaczyna
        val now = Repository.programmes(b).first { it.title == "Wiadomości" }
        Reminders.add(b, now)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()   // aplikacja na pierwszym planie
        ReminderReceiver().onReceive(ctx, Intent(ReminderReceiver.ACTION))
        val popup = shadowOf(ctx).nextStartedActivity
        assertEquals(ReminderActivity::class.java.name, popup.component!!.className)
        val ra = Robolectric.buildActivity(ReminderActivity::class.java, popup).setup(); idle()
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        val player = shadowOf(ra.get()).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, player.component!!.className)
        assertEquals(b.key, player.getStringExtra(PlayerActivity.EXTRA_CHANNEL))
        ra.pause().stop().destroy(); main.pause().stop().destroy()
    }

    @Test fun searchScreenFiltersAndReminderTab() {
        val c = Robolectric.buildActivity(EpgSearchActivity::class.java,
            Intent(ctx, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_QUERY, "Serial")).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); Thread.sleep(200); idle()
        val a = c.get()
        val rv = a.findViewById<RecyclerView>(R.id.results)
        assertEquals(5, rv.adapter!!.itemCount)
        a.findViewById<View>(R.id.fUpcoming).performClick(); Thread.sleep(200); idle()
        assertEquals(3, rv.adapter!!.itemCount)
        a.findViewById<EditText>(R.id.query).setText("finał"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); Thread.sleep(200); idle()
        assertEquals(1, rv.adapter!!.itemCount)
        Reminders.add(Repository.channels[1], Repository.programmes(Repository.channels[1]).first { it.subTitle == "Wielki finał" })
        a.findViewById<View>(R.id.tabReminders).performClick(); idle()
        assertEquals(1, rv.adapter!!.itemCount)
        assertTrue(a.findViewById<TextView>(R.id.info).text.contains("Przypomnienia: 1"))
        c.pause().stop().destroy()
    }
}

package pl.iptv.tv.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Reminders
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.Series
import pl.iptv.tv.reminders.ReminderScheduler

/**
 * Menu programu: oglądanie (na żywo / od początku / archiwum), przypomnienia,
 * następny odcinek, wszystkie odcinki, szczegóły. [play]: p == null -> na żywo.
 */
object ProgrammeActions {

    fun menu(a: Activity, ch: Channel, p: Programme, play: (Channel, Programme?) -> Unit,
             channelItem: Boolean = false, onChanged: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (channelItem && !p.isNow(now)) items += "📺 Oglądaj kanał ${ch.name} na żywo" to { play(ch, null) }
        val archive = Repository.isInArchive(ch, p, now)
        when {
            p.isNow(now) -> {
                items += "▶ Oglądaj na żywo" to { play(ch, null) }
                if (archive) items += "⏮ Oglądaj od początku" to { play(ch, p) }
            }
            p.end <= now -> if (archive) items += "⟲ Oglądaj z archiwum" to { play(ch, p) }
            Prefs.remindersEnabled -> items += if (Reminders.isSet(ch, p))
                "🔕 Usuń przypomnienie" to { Reminders.remove(ch.key, p.start); ReminderScheduler.schedule(a); toast(a, "Usunięto przypomnienie"); onChanged() }
            else "🔔 Przypomnij (${Prefs.reminderLead} min przed)" to { remind(a, ch, p); onChanged() }
        }
        if (Prefs.recEnabled && p.end > now) {
            val job = pl.iptv.tv.data.Recordings.forProgramme(ch, p)
            items += if (job != null) "⏹ Anuluj nagrywanie" to { pl.iptv.tv.rec.RecordingService.stop(a, job.id); toast(a, "Anulowano nagrywanie"); onChanged() }
            else "⏺ Nagraj program" to {
                pl.iptv.tv.rec.RecordingService.recordProgramme(a, ch, p)
                toast(a, if (p.start > now) "⏺ Zaplanowano nagranie: ${whenText(ch, p)}" else "⏺ Nagrywanie: ${p.title}"); onChanged()
            }
        }
        if (Prefs.remindersEnabled) {
            val rule = Reminders.ruleFor(p)
            val base = Series.baseTitle(p.title)
            items += if (rule != null) "🔕 Nie przypominaj o serii „$base”" to {
                Reminders.removeRule(rule.key); ReminderScheduler.schedule(a); toast(a, "Wyłączono przypomnienia o serii"); onChanged()
            } else "🔁 Przypominaj o wszystkich odcinkach „$base”" to { seriesRule(a, ch, p, onChanged) }
        }
        val next = Repository.nextEpisode(ch, p)
        items += if (next != null) "⏭ Następny odcinek: ${whenText(next.channel, next.programme)}" to {
            menu(a, next.channel, next.programme, play, channelItem, onChanged)
        } else "⏭ Następny odcinek: brak w programie TV" to { toast(a, "W pobranym EPG nie ma kolejnego odcinka") }
        if (Prefs.epgSearchEnabled) items += "🔎 Wszystkie emisje / odcinki" to {
            a.startActivity(Intent(a, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_QUERY, Series.baseTitle(p.title)))
        }
        items += "ℹ Szczegóły" to { details(a, ch, p) }
        Dialogs.menu(a, p.fullTitle, items)
    }

    fun whenText(ch: Channel, p: Programme) = "${Fmt.shortDay(p.start)} ${Fmt.time(p.start)}, ${ch.name}"

    fun remind(a: Activity, ch: Channel, p: Programme) {
        Reminders.add(ch, p)
        ReminderScheduler.schedule(a)
        askNotificationPermission(a)
        toast(a, "🔔 Przypomnę: ${whenText(ch, p)}")
    }

    private fun seriesRule(a: Activity, ch: Channel, p: Programme, onChanged: () -> Unit) {
        Dialogs.menu(a, "Przypominaj o odcinkach „${Series.baseTitle(p.title)}”", listOf(
            "Tylko na kanale ${ch.name}" to { addRule(a, p, ch); onChanged() },
            "Na wszystkich kanałach" to { addRule(a, p, null); onChanged() },
        ))
    }

    private fun addRule(a: Activity, p: Programme, ch: Channel?) {
        Reminders.addRule(p, ch)
        Reminders.expandRules(Repository.channels, Repository::programmes, System.currentTimeMillis())
        ReminderScheduler.schedule(a)
        askNotificationPermission(a)
        val n = Reminders.all().count { it.fromSeries && Series.key(it.title) == Series.key(p.title) && !it.notified }
        toast(a, "🔁 Seria zapisana – zaplanowano $n przypomnień (kolejne dojdą po aktualizacji EPG)")
    }

    fun details(a: Activity, ch: Channel, p: Programme) {
        val sb = StringBuilder()
        sb.append("${ch.name}\n${Fmt.day(p.start)}, ${Fmt.time(p.start)}–${Fmt.time(p.end)}")
        if (p.episode.isNotBlank()) sb.append("\nOdcinek: ${p.episode}")
        if (p.subTitle.isNotBlank()) sb.append("\nTytuł odcinka: ${p.subTitle}")
        if (p.category.isNotBlank()) sb.append("\nKategoria: ${p.category}")
        Repository.nextEpisode(ch, p)?.let { sb.append("\n\n⏭ Następny odcinek: ${whenText(it.channel, it.programme)}") }
        if (p.desc.isNotBlank()) sb.append("\n\n${p.desc}")
        Ui.dialog(a).setTitle(p.title).setMessage(sb).setPositiveButton("OK", null).show()
    }

    /** Android 13+: zgoda na powiadomienia (pytamy raz, przy pierwszym przypomnieniu). */
    private fun askNotificationPermission(a: Activity) {
        if (Build.VERSION.SDK_INT < 33 || Prefs.notificationsAsked) return
        Prefs.notificationsAsked = true
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}

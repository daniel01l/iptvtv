package pl.iptv.tv.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Reminders

/** Jeden alarm na najbliższe przypomnienie; po jego obsłużeniu planowany jest kolejny. */
object ReminderScheduler {
    private const val REQ = 4242

    private fun pending(c: Context, flags: Int = 0): PendingIntent? = PendingIntent.getBroadcast(
        c, REQ, Intent(c, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    val leadMs get() = Prefs.reminderLead * 60_000L

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        am.cancel(pi)
        if (!Prefs.remindersEnabled) return
        val at = Reminders.nextTrigger(System.currentTimeMillis(), leadMs) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        when {
            exact && Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            exact -> am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            Build.VERSION.SDK_INT >= 23 -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else -> am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

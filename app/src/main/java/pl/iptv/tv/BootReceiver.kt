package pl.iptv.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.MainActivity

/**
 * Start po włączeniu urządzenia. Launchery boxów często przykrywają aplikację zaraz po starcie,
 * dlatego otwarcie jest ponawiane (0, 6, 15, 30 s) i dodatkowo zlecane alarmem systemowym.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        const val RETRY = "pl.iptv.tv.BOOT_RETRY"
        private val DELAYS = longArrayOf(6_000, 15_000, 30_000)

        /**
         * Czy otwierać aplikację po włączeniu: opcja „Uruchamiaj po włączeniu” albo włączony tryb launchera.
         * Gdy aplikacja jest już domyślnym ekranem głównym, system sam ją otwiera (HOME) – bez drugiego, konkurencyjnego startu.
         */
        fun shouldLaunch(c: Context): Boolean {
            val launcher = runCatching { pl.iptv.tv.ui.SystemTools.isLauncherEnabled(c) }.getOrDefault(false)
            if (!Prefs.bootStart && !launcher) return false
            return !(launcher && runCatching { pl.iptv.tv.ui.SystemTools.isDefaultHome(c) }.getOrDefault(false))
        }

        fun launch(c: Context) {
            if (IptvApp.isForeground) return
            // akcja startowa wykonana w tym rozruchu – późniejsze HOME (tryb launchera) jej nie powtarza
            runCatching { HomeActivity.firstSinceBoot(c) }
            runCatching {
                c.startActivity(Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_FROM_BOOT, true))
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        Prefs.init(context)
        if (a == RETRY) { if (shouldLaunch(context)) launch(context); return }
        if (a != Intent.ACTION_BOOT_COMPLETED && !a.endsWith("QUICKBOOT_POWERON")) return
        // alarmy są kasowane przy restarcie – zaplanuj przypomnienia i nagrania ponownie
        runCatching { pl.iptv.tv.reminders.ReminderScheduler.schedule(context) }
        runCatching { pl.iptv.tv.rec.RecordingService.rescheduleAll(context) }
        if (!shouldLaunch(context)) return
        launch(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        DELAYS.forEachIndexed { i, d ->
            val pi = PendingIntent.getBroadcast(context, 900 + i, Intent(context, BootReceiver::class.java).setAction(RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + d, pi) else am.set(AlarmManager.RTC_WAKEUP, now + d, pi)
        }
        // alarm „budzika” otwierający ekran – działa także bez uprawnienia nakładki na części urządzeń
        if (exact) runCatching {
            val show = PendingIntent.getActivity(context, 950, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_FROM_BOOT, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(now + 10_000, show), show)
        }
    }
}

package pl.iptv.tv.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import pl.iptv.tv.IptvApp
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Reminder
import pl.iptv.tv.data.Reminders
import pl.iptv.tv.data.Repository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "pl.iptv.tv.REMINDER"
        private const val CHANNEL_ID = "reminders"
    }

    override fun onReceive(c: Context, intent: Intent) {
        val due = if (Prefs.remindersEnabled) Reminders.takeDue(System.currentTimeMillis(), ReminderScheduler.leadMs) else emptyList()
        if (due.isNotEmpty()) show(c, due)
        ReminderScheduler.schedule(c)
    }

    private fun show(c: Context, due: List<Reminder>) {
        val popup = ReminderActivity.intent(c, due).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canPopup = IptvApp.isForeground || Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)
        if (canPopup) runCatching { c.startActivity(popup) }
        if (!IptvApp.isForeground) notify(c, due, popup)
    }

    /** Powiadomienie systemowe (telefon; na części telewizorów nie jest wyświetlane). */
    private fun notify(c: Context, due: List<Reminder>, popup: Intent) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Przypomnienia o programach", NotificationManager.IMPORTANCE_HIGH))
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        due.forEachIndexed { i, r ->
            val ch = Repository.byKey(r.channelKey)
            val pi = PendingIntent.getActivity(c, r.id.hashCode(), ReminderActivity.intent(c, listOf(r)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tv)
                .setContentTitle("🔔 ${r.title}")
                .setContentText("${tf.format(Date(r.start))} • ${ch?.name ?: ""}" + if (r.subTitle.isNotBlank()) " • ${r.subTitle}" else "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(r.id.hashCode() + i, n) }
        }
    }
}

package pl.iptv.tv.rec

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.RecJob
import pl.iptv.tv.data.Recordings
import pl.iptv.tv.data.Repository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Usługa nagrywania (na pierwszym planie, z powiadomieniem). */
class RecordingService : Service() {
    companion object {
        const val ACTION_START = "pl.iptv.tv.REC_START"
        const val ACTION_STOP = "pl.iptv.tv.REC_STOP"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "recordings"
        private const val NOTIF = 7001

        fun folder(c: Context): File = Prefs.recFolder.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: c.getExternalFilesDir("Recordings") ?: File(c.filesDir, "Recordings")

        private fun intent(c: Context, action: String, id: String) =
            Intent(c, RecordingService::class.java).setAction(action).putExtra(EXTRA_ID, id)

        /** Tworzy zadanie; startuje od razu albo planuje alarm. */
        fun recordProgramme(c: Context, ch: Channel, p: Programme?): RecJob {
            val now = System.currentTimeMillis()
            val start = if (p == null) now else maxOf(now, p.start - Prefs.recBefore * 60_000L)
            val end = if (p == null) Long.MAX_VALUE else p.end + Prefs.recAfter * 60_000L
            val job = RecJob("r" + now.toString(36), ch.key, ch.name, ch.url, p?.title ?: "Nagranie", start, end,
                userAgent = ch.userAgent, referrer = ch.referrer)
            Recordings.add(job)
            if (start <= now + 5_000) start(c, job.id) else schedule(c, job)
            return job
        }

        fun start(c: Context, id: String) {
            val i = intent(c, ACTION_START, id)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context, id: String) {
            val j = Recordings.get(id) ?: return
            if (j.state == RecJob.SCHEDULED) { cancelAlarm(c, j); Recordings.remove(id); return }
            c.startService(intent(c, ACTION_STOP, id))
        }

        private fun pending(c: Context, j: RecJob): PendingIntent {
            val i = intent(c, ACTION_START, j.id)
            val f = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(c, j.id.hashCode(), i, f)
            else PendingIntent.getService(c, j.id.hashCode(), i, f)
        }

        fun schedule(c: Context, j: RecJob) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(c, j)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, j.start, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, j.start, pi)
        }

        private fun cancelAlarm(c: Context, j: RecJob) = (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, j))

        /** Po restarcie urządzenia. */
        fun rescheduleAll(c: Context) {
            val now = System.currentTimeMillis()
            Recordings.all().filter { it.state == RecJob.SCHEDULED }.forEach { j ->
                if (j.end < now) Recordings.remove(j.id) else if (j.start <= now) runCatching { start(c, j.id) } else schedule(c, j)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<String, Job>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> id?.let { begin(it) }
            ACTION_STOP -> id?.let { running[it]?.cancel() }
        }
        if (running.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun begin(id: String) {
        val j = Recordings.get(id) ?: return
        if (running.containsKey(id) || j.state == RecJob.DONE) return
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IptvTv:rec").apply { acquire(12 * 3_600_000L) }
        val name = (j.channelName + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(j.start)) + "_" + j.title)
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(90)
        val out = File(folder(this), "$name.ts")
        j.file = out.path; j.state = RecJob.RECORDING; Recordings.update(j)
        running[id] = scope.launch {
            try {
                StreamRecorder(Repository.http, j.userAgent.ifBlank { Repository.userAgent }, j.referrer).record(j.url, out, j.end)
                j.state = if (out.length() > 0) RecJob.DONE else RecJob.FAILED
                if (out.length() == 0L) j.error = "Brak danych"
            } catch (e: Throwable) {
                j.state = if (out.length() > 0) RecJob.DONE else RecJob.FAILED
                if (e !is kotlinx.coroutines.CancellationException) j.error = e.message ?: e.javaClass.simpleName
            } finally {
                Recordings.update(j)
                launch(Dispatchers.Main) { running.remove(id); updateNotification(); finishIfIdle() }
            }
        }
        updateNotification()
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nagrywanie", NotificationManager.IMPORTANCE_LOW))
        val names = running.keys.mapNotNull { Recordings.get(it)?.let { j -> "${j.channelName}: ${j.title}" } }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tv)
            .setContentTitle("⏺ Nagrywanie (${names.size})").setContentText(names.joinToString(", ").ifEmpty { "Przygotowanie…" })
            .setOngoing(true).build()
    }

    private fun foreground() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF, notification())
    }

    private fun updateNotification() = runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF, notification()) }

    private fun finishIfIdle() {
        if (running.isNotEmpty()) return
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}

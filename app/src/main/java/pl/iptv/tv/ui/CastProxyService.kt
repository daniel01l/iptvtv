package pl.iptv.tv.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * (7.28) Lekka usługa pierwszoplanowa na czas sesji Cast / DLNA: proces (i serwer [CastProxy]) żyje po wygaszeniu ekranu telefonu,
 * więc transmisja na TV się nie urywa. Ciche powiadomienie z przyciskiem „Zatrzymaj przesyłanie”. Start przy
 * [CastHub.Result.Playing], koniec przy zakończeniu sesji ([stop]). Blokada Wi-Fi (bez usypiania karty sieciowej) tylko w trakcie.
 */
class CastProxyService : Service() {
    companion object {
        const val CHANNEL = "cast_proxy"
        const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "pl.iptv.tv.cast.STOP"
        const val STOP_LABEL = "Zatrzymaj przesyłanie"
        private var app: Context? = null

        /** (7.28) Prośba o POST_NOTIFICATIONS najwyżej raz na proces (bez nękania przy każdej sesji). */
        private var askedNotifications = false
        private const val REQ_NOTIFICATIONS = 4712

        fun start(c: Context) {
            if (!CastProxy.enabled) return
            app = c.applicationContext
            // (7.28) Android 13+: bez zgody na powiadomienia usługa działa, ale powiadomienia (i „Zatrzymaj przesyłanie”) nie widać –
            // prośba w tle (bez czekania na odpowiedź; przesyłanie startuje od razu)
            if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && c is android.app.Activity &&
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                askedNotifications = true
                runCatching { c.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS) }
            }
            val i = Intent(c, CastProxyService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                // Android 12+: ForegroundServiceStartNotAllowedException (aplikacja w tle / ograniczenia baterii), IllegalStateException
                // (starsze) – bez awarii; przesyłanie działa dalej, tylko bez ochrony przed uśpieniem
                android.util.Log.w("CastProxyService", "start: ${e.javaClass.simpleName}")
            }
        }

        fun stop() { app?.let { c -> runCatching { c.stopService(Intent(c, CastProxyService::class.java)) } } }
    }

    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            android.os.Handler(mainLooper).post { CastHub.stopCasting() }
            stopSelf(); return START_NOT_STICKY
        }
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (wifi == null) wifi = runCatching {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cast").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        if (CastHub.session == null && CastHub.pending == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Przesyłanie (Cast / DLNA)", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = PendingIntent.getService(this, 1, Intent(this, CastProxyService::class.java).setAction(ACTION_STOP), flags)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 2, it, flags) }
        val s = CastHub.session
        val text = s?.let { "„${it.device.name}”: ${it.media.title}" } ?: "Przesyłanie"
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Przesyłanie na telewizor").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(@Suppress("DEPRECATION") Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, STOP_LABEL, stop).build()).build()
    }

    override fun onDestroy() {
        runCatching { wifi?.release() }; wifi = null
        super.onDestroy()
    }
}

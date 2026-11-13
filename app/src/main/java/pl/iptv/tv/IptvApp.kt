package pl.iptv.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Repository
import pl.iptv.tv.reminders.ReminderScheduler

class IptvApp : Application() {
    companion object {
        private var started = 0
        /** Czy jakiś ekran aplikacji jest widoczny (przypomnienie jako okno, nie powiadomienie). */
        val isForeground get() = started > 0
    }

    private val stopHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Wersja zabezpieczona: sprawdzanie aktywacji co 15 min, gdy aplikacja jest widoczna; brak aktywacji = ekran blokady. */
    private fun activationLoop() {
        val A = pl.iptv.tv.data.Activation
        stopHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isForeground && A.needsCheck) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    if (A.check() != pl.iptv.tv.data.Activation.State.ACTIVE) lock()
                }
                stopHandler.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    fun lock() {
        pl.iptv.tv.ui.Playback.stop()
        startActivity(android.content.Intent(this, pl.iptv.tv.ui.LockActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate() {
        super.onCreate()
        started = 0
        Repository.init(this)
        if (Repository.epgCrashed()) android.util.Log.w("IptvApp", "Poprzednie wczytywanie EPG przerwane – tryb dla słabszych urządzeń")
        pl.iptv.tv.data.Activation.init(this)
        if (pl.iptv.tv.data.Activation.enabled) activationLoop()
        pl.iptv.tv.ui.Ui.init(this)
        ReminderScheduler.schedule(this)
        if (pl.iptv.tv.data.Prefs.remoteOn) pl.iptv.tv.remote.RemoteServer.start(this)
        if (pl.iptv.tv.data.Prefs.relayOn) pl.iptv.tv.remote.RemoteRelay.start(this)
        if (pl.iptv.tv.BuildConfig.SECURE) pl.iptv.tv.data.Prefs.adminCode = ""          // bez raportów do administratora
        pl.iptv.tv.update.Updater.autoCheck(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // aplikacja w tle (żaden ekran nie jest widoczny) – zatrzymaj odtwarzanie
                if (started == 0) stopHandler.postDelayed({ if (started == 0) pl.iptv.tv.ui.Playback.stop() }, 1500)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

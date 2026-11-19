package pl.iptv.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Repository
import pl.iptv.tv.reminders.ReminderScheduler

class IptvApp : Application(), coil.ImageLoaderFactory {
    /**
     * Klient HTTP dla logo: długie limity czasu (wolne serwery picon), ponowienia przy błędzie sieci, 5xx i 429
     * (2 próby, rosnąca przerwa), nagłówek User-Agent jak dla listy (część serwerów odrzuca żądania bez niego).
     */
    private fun piconHttp(): okhttp3.OkHttpClient = Repository.http.newBuilder()
        .connectTimeout(PICON_CONNECT_S, java.util.concurrent.TimeUnit.SECONDS).readTimeout(PICON_READ_S, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(PICON_CALL_S, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 12; maxRequestsPerHost = 6 })
        .addInterceptor { chain ->
            val req = chain.request().let { r -> if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", Repository.userAgent).build() else r }
            var attempt = 0
            while (true) {
                val res = try { chain.proceed(req) } catch (e: java.io.IOException) { if (attempt >= PICON_RETRIES) throw e; null }
                if (res != null && (res.isSuccessful || attempt >= PICON_RETRIES || (res.code < 500 && res.code != 429))) return@addInterceptor res
                res?.close(); attempt++
                Thread.sleep(700L * attempt)
            }
            @Suppress("UNREACHABLE_CODE") error("")
        }.build()

    /** Logo: pamięć RAM → dysk (160 MB, niezależnie od nagłówków serwera) → sieć z ponowieniami. */
    override fun newImageLoader(): coil.ImageLoader {
        val low = runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)
        return coil.ImageLoader.Builder(this)
            .okHttpClient { piconHttp() }
            .respectCacheHeaders(false)
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(if (low) 0.12 else 0.2).build() }
            .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("picons")).maxSizeBytes(160L * 1024 * 1024).build() }
            .crossfade(false)
            .allowRgb565(false)                // pełne kolory logo (RGB565 zmieniał barwy i gradienty) także na słabszych urządzeniach
            .build()
    }

    companion object {
        /** Limity czasu pobierania logo (s) i liczba ponowień jednego żądania – wolne serwery picon. */
        const val PICON_CONNECT_S = 40L
        const val PICON_READ_S = 75L
        const val PICON_CALL_S = 180L
        const val PICON_RETRIES = 3
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
        pl.iptv.tv.data.YouTubeTokens.init(this)
        pl.iptv.tv.data.StubeHistory.init(this)
        // dobrane silniki kanałów – do pamięci w tle (w testach Robolectric nie: wyścig z zamykaniem bazy testu – zakleszczenie Room)
        if (android.os.Build.FINGERPRINT != "robolectric") pl.iptv.tv.data.ChannelEngines.loadAsync()
        // odtwarzacz rozgrzany w tle (dekodery, klasy silnika, rozpoznane HLS) – płynne kanały od pierwszego przełączenia
        if (android.os.Build.FINGERPRINT != "robolectric") pl.iptv.tv.ui.Playback.warmUp(this)
        pl.iptv.tv.data.StremioAccount.init(this)
        pl.iptv.tv.remote.RemoteServer.init(this)
        if (Repository.epgCrashed()) android.util.Log.w("IptvApp", "Poprzednie wczytywanie EPG przerwane – tryb dla słabszych urządzeń")
        pl.iptv.tv.data.Activation.init(this)
        if (pl.iptv.tv.data.Activation.enabled) activationLoop()
        pl.iptv.tv.ui.Ui.init(this)
        pl.iptv.tv.ui.I18n.init(this)                       // (7.8) język interfejsu (słownik tylko dla języka innego niż polski)
        // świeża instalacja (nie aktualizacja): motyw PS5 jako domyślny; pomijane w testach Robolectric (motyw ustawiają same testy)
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pl.iptv.tv.ui.Ui.applyFirstRunTheme(pi.firstInstallTime == pi.lastUpdateTime)
        }
        pl.iptv.tv.ui.X25Theme.refreshLauncher(this)
        ReminderScheduler.schedule(this)
        if (pl.iptv.tv.data.Prefs.remoteOn || pl.iptv.tv.data.Prefs.stubeRemote) pl.iptv.tv.remote.RemoteServer.start(this)
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
            // (7.8) tłumaczenie interfejsu na każdym ekranie (bez narzutu przy języku polskim)
            override fun onActivityResumed(a: Activity) { pl.iptv.tv.ui.I18n.attach(a.window) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

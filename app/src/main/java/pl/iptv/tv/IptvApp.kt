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

    /**
     * Obrazy (logo, miniatury, plakaty): pamięć RAM (≤ 12 % pamięci aplikacji – na urządzeniach 2 GB poniżej ~25 MB) →
     * dysk (180 MB w `cacheDir/image_cache`, niezależnie od nagłówków serwera) → sieć z ponowieniami.
     * Bitmapy sprzętowe (HARDWARE – piksele w pamięci GPU, nie na stercie Javy); wyjątki z dostępem do pikseli same
     * wyłączają je przez `allowHardware(false)` (np. kolor tła z okładki w widoku muzycznym STube).
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient { piconHttp() }
        .respectCacheHeaders(false)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(IMAGE_MEMORY_PERCENT).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve(IMAGE_CACHE_DIR)).maxSizeBytes(IMAGE_DISK_BYTES).build() }
        .allowHardware(true)
        // HARDWARE od Androida 8 (API 26); starsze – ARGB_8888 (Coil i tak nie tworzy tam bitmap sprzętowych)
        .bitmapConfig(if (android.os.Build.VERSION.SDK_INT >= 26) android.graphics.Bitmap.Config.HARDWARE else android.graphics.Bitmap.Config.ARGB_8888)
        .crossfade(IMAGE_CROSSFADE_MS)
        .allowRgb565(false)                // pełne kolory logo (RGB565 zmieniał barwy i gradienty) także na słabszych urządzeniach
        .build()

    /** Dawny katalog pamięci obrazów (do 6.x / 7.16: „picons”, 160 MB) – usuwany raz w tle, żeby nie zajmował miejsca obok nowego. */
    private fun dropOldImageCache() {
        val old = cacheDir.resolve("picons")
        if (old.exists()) Thread({ runCatching { old.deleteRecursively() } }, "ImageCacheCleanup").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    companion object {
        /** Pamięć RAM obrazów: część pamięci aplikacji (Coil `maxSizePercent`). */
        const val IMAGE_MEMORY_PERCENT = 0.12
        /** Pamięć dyskowa obrazów: katalog w `cacheDir` i limit. */
        const val IMAGE_CACHE_DIR = "image_cache"
        const val IMAGE_DISK_BYTES = 180L * 1024 * 1024
        /** Domyślne przenikanie obrazu po wczytaniu (ms); listy przewijane pilotem wyłączają je same (`crossfade(false)`). */
        const val IMAGE_CROSSFADE_MS = 150
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

    /** (7.20) Kontekst aplikacji w tej samej siatce TV 960 × 540 dp co aktywności ([pl.iptv.tv.ui.TvResolution]). */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(pl.iptv.tv.ui.TvResolution.wrap(base))
        // (7.22) zapis ostatniej awarii (filesDir/crash_last.txt) jak najwcześniej – także awarii samego startu
        runCatching { CrashLog.install(this) }
    }

    override fun onCreate() {
        super.onCreate()
        val S = CrashLog
        S.safe("crashLog") { S.install(this) }
        S.safe("tvMetrics") { pl.iptv.tv.ui.TvResolution.applyMetrics(resources) }       // (7.20) kontekst aplikacji w siatce TV
        started = 0
        // (7.22) każdy krok startu osobno w try-catch (CrashLog.safe): błąd jednego (I/O, Keystore, usługa systemu) nie zamyka
        // aplikacji przy starcie – kroki ciężkie (baza silników, rozgrzewanie, migracje, stara pamięć obrazów) idą w tle
        if (android.os.Build.FINGERPRINT != "robolectric") S.safe("imageCache") { dropOldImageCache() }
        S.safe("repository") { Repository.init(this) }
        S.safe("youtubeTokens") { pl.iptv.tv.data.YouTubeTokens.init(this) }
        S.safe("stubeHistory") { pl.iptv.tv.data.StubeHistory.init(this) }
        // dobrane silniki kanałów – do pamięci w tle (w testach Robolectric nie: wyścig z zamykaniem bazy testu – zakleszczenie Room)
        if (android.os.Build.FINGERPRINT != "robolectric") S.safe("channelEngines") { pl.iptv.tv.data.ChannelEngines.loadAsync() }
        // odtwarzacz rozgrzany w tle (dekodery, klasy silnika, rozpoznane HLS) – płynne kanały od pierwszego przełączenia
        if (android.os.Build.FINGERPRINT != "robolectric") S.safe("warmUp") { pl.iptv.tv.ui.Playback.warmUp(this) }
        S.safe("stremioAccount") { pl.iptv.tv.data.StremioAccount.init(this) }
        S.safe("remoteInit") { pl.iptv.tv.remote.RemoteServer.init(this) }
        S.safe("epgCrashed") { if (Repository.epgCrashed()) android.util.Log.w("IptvApp", "Poprzednie wczytywanie EPG przerwane – tryb dla słabszych urządzeń") }
        S.safe("activation") { pl.iptv.tv.data.Activation.init(this); if (pl.iptv.tv.data.Activation.enabled) activationLoop() }
        S.safe("ui") { pl.iptv.tv.ui.Ui.init(this) }
        S.safe("i18n") { pl.iptv.tv.ui.I18n.init(this) }                       // (7.8) język interfejsu (słownik tylko dla języka innego niż polski)
        // świeża instalacja (nie aktualizacja): motyw PS5 jako domyślny; pomijane w testach Robolectric (motyw ustawiają same testy)
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pl.iptv.tv.ui.Ui.applyFirstRunTheme(pi.firstInstallTime == pi.lastUpdateTime)
        }
        S.safe("launcher") { pl.iptv.tv.ui.X25Theme.refreshLauncher(this) }
        S.safe("reminders") { ReminderScheduler.schedule(this) }
        S.safe("remoteServer") { if (pl.iptv.tv.remote.RemoteServer.wanted) pl.iptv.tv.remote.RemoteServer.start(this) }
        S.safe("relay") { if (pl.iptv.tv.data.Prefs.relayOn) pl.iptv.tv.remote.RemoteRelay.start(this) }
        S.safe("secure") { if (pl.iptv.tv.BuildConfig.SECURE) pl.iptv.tv.data.Prefs.adminCode = "" }          // bez raportów do administratora
        S.safe("updater") { pl.iptv.tv.update.Updater.autoCheck(this) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            // (7.23) jedna polityka orientacji dla wszystkich ekranów (także przyszłych) – przed utworzeniem okna, bez chwilowego poziomu
            override fun onActivityPreCreated(a: Activity, b: Bundle?) { pl.iptv.tv.ui.OrientationPolicy.apply(a) }
            override fun onActivityStopped(a: Activity) {
                pl.iptv.tv.ui.OrientationPolicy.onStopped(a)
                started = (started - 1).coerceAtLeast(0)
                // aplikacja w tle (żaden ekran nie jest widoczny) – zatrzymaj odtwarzanie
                if (started == 0) stopHandler.postDelayed({ if (started == 0) pl.iptv.tv.ui.Playback.stop() }, 1500)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) { pl.iptv.tv.ui.OrientationPolicy.apply(a) }   // API < 29 (bez onActivityPreCreated)
            // (7.8) tłumaczenie interfejsu na każdym ekranie (bez narzutu przy języku polskim)
            override fun onActivityResumed(a: Activity) { pl.iptv.tv.ui.OrientationPolicy.apply(a); pl.iptv.tv.ui.I18n.attach(a.window) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

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
    override fun newImageLoader(): coil.ImageLoader = run { pl.iptv.tv.ui.DevicePerformanceProfile.init(this); coil.ImageLoader.Builder(this) }
        // (7.28) tryb szybki: zapytania bez rozmiaru (Size.ORIGINAL) dekodowane najwyżej do LITE_MAX_BITMAP_PX – logo i miniatury
        // nie trafiają do pamięci w pełnej rozdzielczości serwera (pauzy GC w procesie 32-bit); zapytania z rozmiarem – bez zmian
        .components { add(LiteSizeInterceptor) }
        .okHttpClient { piconHttp() }
        .respectCacheHeaders(false)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(IMAGE_MEMORY_PERCENT).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve(IMAGE_CACHE_DIR)).maxSizeBytes(IMAGE_DISK_BYTES).build() }
        .allowHardware(true)
        // HARDWARE od Androida 8 (API 26); starsze – ARGB_8888 (Coil i tak nie tworzy tam bitmap sprzętowych)
        .bitmapConfig(if (android.os.Build.VERSION.SDK_INT >= 26) android.graphics.Bitmap.Config.HARDWARE else android.graphics.Bitmap.Config.ARGB_8888)
        .crossfade(if (pl.iptv.tv.ui.DevicePerformanceProfile.lite) 0 else IMAGE_CROSSFADE_MS)   // (7.28) tryb szybki – bez przenikania
        // (7.28) RGB565 dla nieprzezroczystych JPEG (plakaty, miniatury, tła EPG) – połowa pamięci, mniej GC w procesie 32-bit.
        // Coil stosuje go tylko do JPEG bez przezroczystości i tylko gdy konfiguracją nie jest HARDWARE (API < 26 / allowHardware(false));
        // logo kanałów (PNG z alfą, PiconView – allowRgb565(false)) bez zmian, więc wygląd motywów identyczny.
        .allowRgb565(true)
        .build()

    /** Dawny katalog pamięci obrazów (do 6.x / 7.16: „picons”, 160 MB) – usuwany raz w tle, żeby nie zajmował miejsca obok nowego. */
    private fun dropOldImageCache() {
        val old = cacheDir.resolve("picons")
        if (old.exists()) Thread({ runCatching { old.deleteRecursively() } }, "ImageCacheCleanup").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** (7.28) Ograniczenie rozmiaru dekodowania w trybie szybkim – tylko dla zapytań bez podanego rozmiaru. */
    private object LiteSizeInterceptor : coil.intercept.Interceptor {
        override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
            val lite = pl.iptv.tv.ui.DevicePerformanceProfile.lite
            return if (lite && chain.size == coil.size.Size.ORIGINAL)
                chain.withSize(coil.size.Size(LITE_MAX_BITMAP_PX, LITE_MAX_BITMAP_PX)).proceed(chain.request)
            else chain.proceed(chain.request)
        }
    }

    companion object {
        /** (7.28) Maks. bok dekodowanej bitmapy bez rozmiaru w trybie szybkim (logo, miniatury). */
        const val LITE_MAX_BITMAP_PX = 360
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
        S.safe("perfProfile") { pl.iptv.tv.ui.DevicePerformanceProfile.init(this) }   // (7.28) profil wydajności UI – raz na proces
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
        // (7.27) zapisany motyw usunięty z aplikacji (Klasyczny dekoder, Xbox, X25, PS5) → Nowoczesny; pozostałe ustawienia bez zmian
        S.safe("themeMigration") { pl.iptv.tv.ui.Ui.migrateRemovedThemes() }
        // świeża instalacja (nie aktualizacja): motyw Nowoczesny jako domyślny; pomijane w testach Robolectric (motyw ustawiają same testy)
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pl.iptv.tv.ui.Ui.applyFirstRunTheme(pi.firstInstallTime == pi.lastUpdateTime)
        }
        // (7.24) profil silnika „Szybkie przełączanie” tylko przy pierwszej instalacji; po aktualizacji ustawienia użytkownika bez zmian
        if (android.os.Build.FINGERPRINT != "robolectric") S.safe("engineProfile") {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pl.iptv.tv.ui.EngineProfiles.applyFirstRunDefault(pi.firstInstallTime == pi.lastUpdateTime)
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
            // (7.27) tryb ekranu okna zgodny z AFR (bez powrotu do trybu domyślnego przy przejściu między ekranami – brak ponownej negocjacji HDMI)
            override fun onActivityResumed(a: Activity) { pl.iptv.tv.ui.OrientationPolicy.apply(a); pl.iptv.tv.ui.I18n.attach(a.window); pl.iptv.tv.ui.AutoFrameRate.keep(a)
                runCatching { pl.iptv.tv.ui.CastHub.onActivityResumed(a) } }   // (7.28) pilot Cast na każdym ekranie w trakcie sesji
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

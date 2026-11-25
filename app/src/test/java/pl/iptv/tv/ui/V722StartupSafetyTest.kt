package pl.iptv.tv.ui

import android.content.res.Configuration
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.CrashLog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/**
 * 7.22 – bezpieczny start na telefonie i wspólny fundament ekranu (TV / poziomo / pionowo 9:16): [DeviceDisplayProfile]
 * i [LocalDeviceDisplayProfile], natywna gęstość w pionie telefonu, okno 0 × 0 przy zimnym starcie, wywołania okna i AFR
 * bez wyjątków, [CrashLog] (crash_last.txt), wyrównywanie głośności tworzone w tle.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V722StartupSafetyTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.orientation = 0
    }

    @After fun reset() {
        TvResolution.resetForTest(); ChannelLoudness.release(); ChannelLoudness.async = false
        Prefs.loudnessLeveling = false; Prefs.orientation = 0
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitPhoneProfileKeepsNativeDensity() {
        val p = DeviceDisplayProfile.of(ctx)
        assertFalse(p.isTvDevice); assertTrue(p.isPortraitPhone); assertFalse(p.isLandscapeOrTv)
        // siatka TV nigdy na telefonie: kontekst bez zmian, gęstość systemu, Compose – natywna (null = LocalDensity.current)
        assertSame(ctx, TvResolution.wrap(ctx)); assertNull(TvResolution.density)
        assertNull(TvResolution.composeDensityFor(p, 800, 1600, ctx.resources))
        // nawet przy pozostałej z TV gęstości (np. wymuszonej w innym miejscu) profil pionowy telefonu ma pierwszeństwo
        TvResolution.forceTv = true; TvResolution.init(ctx); TvResolution.forceTv = null
        assertNotNull(TvResolution.density)
        assertNull(TvResolution.composeDensityFor(DeviceDisplayProfile(false, Configuration.ORIENTATION_PORTRAIT), 800, 1600, ctx.resources))
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvProfileAndColdStartZeroWindow() {
        val p = DeviceDisplayProfile.of(ctx)
        assertTrue(p.isTvDevice); assertFalse(p.isPortraitPhone); assertTrue(p.isLandscapeOrTv)
        // okno 0 × 0 przy zimnym starcie – nigdy gęstość 0 / NaN
        val d0 = TvResolution.densityFor(0, 0)
        assertTrue(d0 > 0f && !d0.isNaN() && !d0.isInfinite())
        assertTrue(TvResolution.densityFor(-5, 1080) > 0f)
        // gęstość TV jeszcze nie wyliczona: szerokość 0 → metryki zasobów (≥ 1 px), bez zasobów – gęstość systemu
        TvResolution.resetForTest()                   // aplikacja wyliczyła gęstość przy starcie – symulacja chwili przed init
        assertNull(TvResolution.density)
        val fromMetrics = TvResolution.composeDensityFor(p, 0, 0, ctx.resources)
        assertNotNull(fromMetrics); assertTrue(fromMetrics!!.density > 0f); assertEquals(1f, fromMetrics.fontScale, 0f)
        assertNull(TvResolution.composeDensityFor(p, 0, 0, null))
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun landscapePhoneIsLandscapeButNotTv() {
        val p = DeviceDisplayProfile.of(ctx)
        assertFalse(p.isTvDevice); assertFalse(p.isPortraitPhone); assertTrue(p.isLandscapeOrTv)
        assertNull(TvResolution.composeDensityFor(p, 1600, 800, ctx.resources))
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun composeProviderGivesProfileAndNativeDensityInPortrait() {
        // bez uruchamiania Compose w Robolectric (niezamknięta kompozycja blokowała ekrany Compose w kolejnych klasach, np. X25Test):
        // ta sama logika, której używa TvResolution1080pProvider – profil z konfiguracji i gęstość dla profilu
        val p = DeviceDisplayProfile.of(ctx, ctx.resources.configuration)
        assertTrue(p.isPortraitPhone)
        assertNull(TvResolution.composeDensityFor(p, 800, 1600, ctx.resources))                // null = natywne LocalDensity.current
        assertEquals(DeviceDisplayProfile.DEFAULT, DeviceDisplayProfile(false, android.content.res.Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun mainScreenStartsInPortraitPhone() {
        Prefs.orientation = 2
        val f = File(ctx.filesDir, "v722.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",A\nhttp://v722/1\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        assertFalse(c.get().isFinishing)
        assertNull(TvResolution.density)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun windowAndDisplayModeCallsNeverThrow() {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        TvResolution.forceTv = true
        TvResolution.fullscreen(a, keepScreenOn = true)                        // WindowCompat / kontroler wstawek
        AutoFrameRate.apply(a, 25f, android.view.SurfaceView(a), force = true)  // Display.Mode, preferredDisplayModeId, Surface.setFrameRate
        AutoFrameRate.apply(a, 50f, null, force = true)
        AutoFrameRate.reset(a)
        assertTrue(TvResolution.displayHeightPx(a) >= 0)
        assertEquals(0, a.window.attributes.preferredDisplayModeId)
    }

    @Test @Config(sdk = [34])
    fun crashHandlerWritesLastStacktraceAndPassesOn() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passed: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> passed = e }
            CrashLog.resetForTest(); File(ctx.filesDir, CrashLog.FILE).delete()
            CrashLog.install(ctx); CrashLog.install(ctx)             // drugi raz – bez podwójnego łańcucha
            val boom = IllegalStateException("start telefonu v722")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertSame(boom, passed)                                  // przekazany do poprzedniego (systemowego) handlera
            val text = CrashLog.last(ctx)!!
            assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("start telefonu v722"))
            assertTrue(text.contains("V722StartupSafetyTest"))         // pełny stacktrace
            assertTrue(text.contains("wersja:"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original); CrashLog.resetForTest()
        }
    }

    @Test @Config(sdk = [34])
    fun startupStepFailureIsLoggedAndSkipped() {
        CrashLog.install(ctx)
        File(ctx.filesDir, CrashLog.STARTUP_FILE).delete()
        var after = false
        assertFalse(CrashLog.safe("v722") { throw java.io.IOException("dysk") })
        assertTrue(CrashLog.safe("v722ok") { after = true })
        assertTrue(after)
        assertTrue(File(ctx.filesDir, CrashLog.STARTUP_FILE).readText().contains("v722: java.io.IOException: dysk"))
    }

    @Test @Config(sdk = [34])
    fun loudnessEffectCreatedOffMainThread() {
        val real = ChannelLoudness.factory
        try {
            val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
            val released = java.util.concurrent.atomic.AtomicInteger()
            ChannelLoudness.factory = { _ ->
                threads += Thread.currentThread().name
                object : ChannelLoudness.Effect {
                    override fun setGain(mB: Int) {}
                    override fun setEnabled(on: Boolean) {}
                    override fun release() { released.incrementAndGet() }
                }
            }
            ChannelLoudness.async = true; Prefs.loudnessLeveling = true
            ChannelLoudness.attach(11)
            val until = System.currentTimeMillis() + 3000
            while (!ChannelLoudness.active && System.currentTimeMillis() < until) Thread.sleep(10)
            assertTrue(ChannelLoudness.active); assertEquals(11, ChannelLoudness.session)
            assertNotEquals(Thread.currentThread().name, threads.single())
            // błąd urządzenia przy tworzeniu efektu – bez efektu, bez wyjątku
            ChannelLoudness.factory = { throw RuntimeException("audiofx") }
            ChannelLoudness.attach(12)
            Thread.sleep(200)
            assertFalse(ChannelLoudness.active)
            val wait = System.currentTimeMillis() + 3000
            while (released.get() == 0 && System.currentTimeMillis() < wait) Thread.sleep(10)
            assertEquals(1, released.get())                            // poprzedni efekt zwolniony
        } finally { ChannelLoudness.factory = real }
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import java.time.Duration

/**
 * 7.20 – stała siatka interfejsu TV 960 × 540 dp (1920 × 1080) niezależnie od DPI systemu: TV zgłaszający 240 dpi
 * (dawniej 1280 × 720 dp – inne proporcje), TV 4K (3840 × 2160 px), telefon bez zmian; Compose w tej samej gęstości;
 * pełny bufor wideo 4K przy interfejsie 1080p.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V720TvResolutionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @After fun reset() { TvResolution.resetForTest() }

    private fun assertGrid(a: android.app.Activity, density: Float) {
        val r = a.resources
        assertEquals(density, r.displayMetrics.density, 0.01f)
        assertEquals(960, r.configuration.screenWidthDp); assertEquals(540, r.configuration.screenHeightDp)
        assertEquals(1f, r.configuration.fontScale, 0f)
        assertEquals(density, TvResolution.density!!, 0.01f)
        assertEquals(Math.round(10 * density), Ui.dp(10))                // widoki tworzone w kodzie – ta sama siatka
        assertEquals(density, ctx.resources.displayMetrics.density, 0.01f)   // kontekst aplikacji też
    }

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-television-hdpi")
    fun tvReportingHdpiGetsExact960x540Grid() {
        // 1920 × 1080 px przy 240 dpi: system – 1280 × 720 dp; aplikacja – 960 × 540 dp (gęstość 2,0)
        assertEquals(2f, TvResolution.densityFor(1920, 1080), 0.001f)
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(500)
        assertGrid(c.get(), 2f)
        // cały ekran mieści się w siatce – korzeń nie jest szerszy niż okno
        val root = c.get().window.decorView
        assertTrue(root.width <= 1920)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-television-xhdpi")
    fun tv4kWindowKeepsSameGridAndComposeDensity() {
        // okno 3840 × 2160 px – siatka nadal 960 × 540 dp (gęstość 4,0)
        assertEquals(4f, TvResolution.densityFor(3840, 2160), 0.001f)
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(500)
        assertGrid(c.get(), 4f)
        // Compose: LocalDensity na szczycie drzewa = gęstość siatki
        assertEquals(4f, TvResolution.composeDensity()!!.density, 0.01f); assertEquals(1f, TvResolution.composeDensity()!!.fontScale, 0f)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
    fun phoneKeepsSystemDensity() {
        TvResolution.forceTv = false
        val base = ctx
        assertSame(base, TvResolution.wrap(base))
        assertNull(TvResolution.density); assertNull(TvResolution.composeDensity())
        assertEquals(Math.round(10 * android.content.res.Resources.getSystem().displayMetrics.density), Ui.dp(10))
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun nativeVideoBufferFor4kWithUiIn1080p() {
        assertEquals(TvResolution.DESIGN_W_PX, (TvResolution.DESIGN_W_DP * 2).toInt()); assertEquals(TvResolution.DESIGN_H_PX, (TvResolution.DESIGN_H_DP * 2).toInt())
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val sv = android.view.SurfaceView(a)
        a.setContentView(sv, android.view.ViewGroup.LayoutParams(1920, 1080))
        sv.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); sv.layout(0, 0, 1920, 1080)
        // materiał 4K na matrycy 4K – bufor 3840 × 2160 (kompozytor skaluje do widoku, bez utraty szczegółów)
        assertTrue(TvResolution.nativeVideoBuffer(sv, 3840, 2160, displayH = 2160, limited = false))
        // matryca Full HD, materiał 1080p albo tryb słabszych urządzeń z limitem – rozmiar z układu
        assertFalse(TvResolution.nativeVideoBuffer(sv, 3840, 2160, displayH = 1080, limited = false))
        assertFalse(TvResolution.nativeVideoBuffer(sv, 1920, 1080, displayH = 2160, limited = false))
        assertFalse(TvResolution.nativeVideoBuffer(sv, 3840, 2160, displayH = 2160, limited = true))
    }
}

package pl.iptv.tv.ui

import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.IptvApp

/** (7.17) Wydajność: pamięć obrazów (Coil), bufor kanałów na żywo, wspólny zegar postępu EPG, słaba referencja widoku obrazu. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V717PerfTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun imageLoaderLimitsAndExactTargetSize() {
        assertEquals(0.12, IptvApp.IMAGE_MEMORY_PERCENT, 1e-9)
        assertEquals(180L * 1024 * 1024, IptvApp.IMAGE_DISK_BYTES)
        assertEquals("image_cache", IptvApp.IMAGE_CACHE_DIR)
        assertEquals(150, IptvApp.IMAGE_CROSSFADE_MS)
        val loader = coil.Coil.imageLoader(ctx)
        assertEquals(150, (loader.defaults.transitionFactory as? coil.transition.CrossfadeTransition.Factory)?.durationMillis)
        assertEquals(android.graphics.Bitmap.Config.HARDWARE, loader.defaults.bitmapConfig)
        assertTrue(loader.diskCache!!.directory.toFile().path.endsWith("image_cache"))
        assertEquals(180L * 1024 * 1024, loader.diskCache!!.maxSize)

        // stały rozmiar widoku → dokładny rozmiar dekodowania; bez niego – rozmiar zastępczy, nigdy oryginał
        val iv = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(120, 68); scaleType = ImageView.ScaleType.CENTER_CROP }
        val req = coil.request.ImageRequest.Builder(ctx).data("http://x/a.png").fitTo(iv, 10, 10).build()
        assertEquals(coil.size.Scale.FILL, req.scale)
        val free = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val req2 = coil.request.ImageRequest.Builder(ctx).data("http://x/a.png").fitTo(free, 96, 54).build()
        assertEquals(coil.size.Scale.FIT, req2.scale)
        kotlinx.coroutines.runBlocking {
            assertEquals(coil.size.Size(120, 68), req.sizeResolver.size())
            assertEquals(coil.size.Size(96, 54), req2.sizeResolver.size())
        }
    }

    @Test fun liveBufferStartsFastAndFitsDeviceMemory() {
        assertEquals(1_000, Playback.LIVE_START_BUFFER_MS)
        assertEquals(2_000, Playback.LIVE_REBUFFER_MS)
        val gb = 1024L * 1024 * 1024
        assertTrue(Playback.isLowRam(2 * gb))
        assertFalse(Playback.isLowRam(4 * gb))
        assertEquals(Playback.LIVE_BUFFER_LOW_BYTES, Playback.liveBufferBytes(2 * gb))
        assertEquals(Playback.LIVE_BUFFER_LOW_BYTES, Playback.liveBufferBytes(0))       // nieznana pamięć – ostrożnie
        assertEquals(Playback.LIVE_BUFFER_BYTES, Playback.liveBufferBytes(4 * gb))
        // kontrola startu (dobór silnika) nadal ocenia zawieszenie od 2 s zapasu
        assertEquals(Playback.StartDecision.WAIT, Playback.startDecision(12_000, true, 1_500, false))
    }

    @Test fun epgClockFractionAndProgViewFollowsSharedClock() {
        assertEquals(-1f, EpgClock.fraction(10, 10, 5), 0f)
        assertEquals(0f, EpgClock.fraction(100, 200, 50), 0f)
        assertEquals(0.5f, EpgClock.fraction(100, 200, 150), 1e-6f)
        assertEquals(1f, EpgClock.fraction(100, 200, 500), 0f)
        assertEquals(30_000L, EpgClock.TICK_MS)
        val now = System.currentTimeMillis()
        val pv = ProgView(ctx)
        pv.show(pl.iptv.tv.data.Programme(now - 30 * 60_000, now + 30 * 60_000, "Program"), now)
        assertEquals(0.5f, pv.fraction, 0.01f)
    }

    @Test fun sharedPlayerDoesNotPinDestroyedScreenView() {
        val f = Playback::class.java.getDeclaredField("targetRef")
        assertEquals(java.lang.ref.WeakReference::class.java, f.type)
    }
}

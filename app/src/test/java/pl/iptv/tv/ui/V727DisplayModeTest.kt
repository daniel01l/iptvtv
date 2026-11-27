package pl.iptv.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/**
 * 7.27 – bez ponownej negocjacji trybu HDMI na boxach TV: przy wyłączonym AFR (domyślnie) żadna ścieżka nie zmienia trybu ekranu
 * (także dawne wymuszanie 50 Hz „na sesję”), a przejście między ekranami ([AutoFrameRate.keep] / [AutoFrameRate.reset]) nie
 * ustawia trybu domyślnego w oknie, które go nie żądało. Odtwarzacze STube / Stremio bez zmiany odświeżania przy wyłączonym AFR.
 */
@RunWith(AndroidJUnit4::class)
class V727DisplayModeTest {
    @After fun reset() { Prefs.autoFrameRate = false }

    @Test @Config(sdk = [34])
    fun afrOffNeverRequestsDisplayModeEvenWhenForced() {
        Prefs.autoFrameRate = false
        assertFalse("AFR domyślnie wyłączone", Prefs.autoFrameRate)
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val before = AutoFrameRate.modeChanges
        AutoFrameRate.apply(a, 25f, android.view.SurfaceView(a), force = true)     // dawniej: 50 Hz mimo wyłączonego AFR
        AutoFrameRate.apply(a, 50f, null, force = true)
        AutoFrameRate.reset(a)                                                      // wyjście z odtwarzacza (Ustawienia / pulpit)
        AutoFrameRate.keep(a)                                                       // wznowienie innego ekranu
        assertEquals(before, AutoFrameRate.modeChanges)
        assertEquals(0, AutoFrameRate.sessionModeId)
        assertEquals(0, a.window.attributes.preferredDisplayModeId)
    }

    @Test fun frameRateStrategyOffUnlessAfr() {
        assertEquals(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, Playback.frameRateStrategy(false))
        assertEquals(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, Playback.frameRateStrategy(true))
    }
}

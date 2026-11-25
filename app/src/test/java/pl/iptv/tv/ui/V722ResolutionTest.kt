package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import java.time.Duration

/**
 * 7.22 – „Rozdzielczość interfejsu” ([UiResolutionMode]: Automatyczna = jak dotąd, 1080p, 720p, natywna; telefon pionowo
 * zawsze natywnie) i „Maksymalna rozdzielczość obrazu” ([VideoResolutionLimit]: Automatyczna = bez limitu, jak dotąd;
 * limit w locie na odtwarzaczu), wybór w Ustawieniach i w oknie silnika odtwarzacza.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V722ResolutionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun clickable(root: View, text: String) = all(root).first { v -> v.isClickable && all(v).filterIsInstance<android.widget.TextView>().any { it.text.toString() == text } }

    @After fun reset() {
        Prefs.uiResolution = 0; Prefs.maxVideoRes = 0
        TvResolution.resetForTest(); TvResolution.init(ctx); TvResolution.applyMetrics(ctx.resources)
        Playback.stop()
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun autoIsDefaultAndKeepsTvGrid() {
        assertEquals(UiResolutionMode.AUTO, UiResolutionMode.current())
        assertEquals(VideoResolutionLimit.AUTO, EngineConfigRepository.current.maxVideoResolution)
        // TV 1920 × 1080 px: Automatyczna = dotychczasowa siatka 960 × 540 dp (gęstość 2,0)
        assertEquals(2f, TvResolution.init(ctx)!!, 0.01f)
        UiResolutionMode.forced = UiResolutionMode.FHD_1080P; assertEquals(2f, TvResolution.init(ctx)!!, 0.01f)
        // 720p – siatka 640 × 360 dp: elementy 1,5 × większe
        UiResolutionMode.forced = UiResolutionMode.HD_720P; assertEquals(3f, TvResolution.init(ctx)!!, 0.01f)
        UiResolutionMode.forced = UiResolutionMode.NATIVE; assertNull(TvResolution.init(ctx))
        assertNull(TvResolution.composeDensityFor(DeviceDisplayProfile.of(ctx), 1920, 1080, ctx.resources))
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun phoneLandscapeFollowsChoiceButAutoStaysNative() {
        assertNull(TvResolution.init(ctx))                                     // Automatyczna na telefonie – gęstość systemu
        UiResolutionMode.forced = UiResolutionMode.FHD_1080P
        val d = TvResolution.init(ctx)!!                                       // 1600 × 800 px → min(1600/960, 800/540)
        assertEquals(800f / 540f, d, 0.01f)
        assertEquals(d, TvResolution.composeDensityFor(DeviceDisplayProfile.of(ctx), 1600, 800, ctx.resources)!!.density, 0.01f)
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitPhoneAlwaysNativeWhateverTheChoice() {
        for (m in UiResolutionMode.entries) {
            UiResolutionMode.forced = m
            assertNull(m.name, TvResolution.init(ctx))
            assertNull(m.name, TvResolution.composeDensityFor(DeviceDisplayProfile.of(ctx), 800, 1600, ctx.resources))
        }
    }

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-television-hdpi")
    fun switchingToNativeRestoresSystemMetrics() {
        TvResolution.init(ctx); TvResolution.applyMetrics(ctx.resources)
        assertEquals(2f, ctx.resources.displayMetrics.density, 0.01f)         // siatka TV (system 1,5)
        Prefs.uiResolution = UiResolutionMode.NATIVE.ordinal
        TvResolution.modeChanged(ctx)
        assertNull(TvResolution.density)
        assertEquals(1.5f, ctx.resources.displayMetrics.density, 0.01f)
        assertEquals(1280, ctx.resources.configuration.screenWidthDp)
        Prefs.uiResolution = 0; TvResolution.modeChanged(ctx)
        assertEquals(2f, ctx.resources.displayMetrics.density, 0.01f)
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun videoLimitParamsAutoClearsConstraints() {
        val base = androidx.media3.common.TrackSelectionParameters.Builder(ctx).setMaxVideoSize(100, 100).build()
        Playback.videoLimitParams(base, VideoResolutionLimit.AUTO, 0).let { assertEquals(Int.MAX_VALUE, it.maxVideoWidth); assertEquals(Int.MAX_VALUE, it.maxVideoHeight) }
        Playback.videoLimitParams(base, VideoResolutionLimit.UHD_4K, 0).let { assertEquals(3840, it.maxVideoWidth); assertEquals(2160, it.maxVideoHeight) }
        Playback.videoLimitParams(base, VideoResolutionLimit.FHD_1080P, 0).let { assertEquals(1920, it.maxVideoWidth); assertEquals(1080, it.maxVideoHeight) }
        Playback.videoLimitParams(base, VideoResolutionLimit.HD_720P, 0).let { assertEquals(1280, it.maxVideoWidth); assertEquals(720, it.maxVideoHeight) }
        Playback.videoLimitParams(base, VideoResolutionLimit.SD_480P, 0).let { assertEquals(854, it.maxVideoWidth); assertEquals(480, it.maxVideoHeight) }
        // tryb słabszych urządzeń: Automatyczna zachowuje jego limit, przy dwóch limitach – mniejszy
        Playback.videoLimitParams(base, VideoResolutionLimit.AUTO, 1080).let { assertEquals(1920, it.maxVideoWidth); assertEquals(1080, it.maxVideoHeight) }
        Playback.videoLimitParams(base, VideoResolutionLimit.UHD_4K, 720).let { assertEquals(1280, it.maxVideoWidth); assertEquals(720, it.maxVideoHeight) }
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun limitIsSingleSourceAndAppliedLiveWithoutRebuild() {
        val key = EngineConfigRepository.current.playerKey
        EngineConfigRepository.setMaxVideoResolution(VideoResolutionLimit.HD_720P)
        assertEquals(VideoResolutionLimit.HD_720P, EngineConfigRepository.current.maxVideoResolution)
        assertEquals(VideoResolutionLimit.HD_720P.ordinal, Prefs.maxVideoRes)
        assertEquals(key, EngineConfigRepository.current.playerKey)            // bez nowego ExoPlayera
        val p = ExoPlayer.Builder(ctx).build()
        Playback.applyVideoLimit(p)
        assertEquals(720, p.trackSelectionParameters.maxVideoHeight); assertEquals(VideoResolutionLimit.HD_720P, Playback.appliedVideoLimit)
        EngineConfigRepository.setMaxVideoResolution(VideoResolutionLimit.AUTO); Playback.applyVideoLimit(p)
        assertEquals(Int.MAX_VALUE, p.trackSelectionParameters.maxVideoHeight)
        p.release()
        EngineConfigRepository.cycleMaxVideoResolution()
        assertEquals(VideoResolutionLimit.UHD_4K, EngineConfigRepository.current.maxVideoResolution)
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun settingsAndPlayerEngineWindowOffferBothChoices() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        // Ustawienia → Wygląd → Rozdzielczość interfejsu: okno kart, wybór 720p
        val look = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle(600)
        clickable(look.get().window.decorView, SettingsActivity.UI_RESOLUTION_TITLE).performClick(); idle()
        val d = ShadowDialog.getLatestDialog()
        assertTrue(all(d.window!!.decorView).filterIsInstance<android.widget.TextView>().any { it.text == UiResolutionMode.AUTO.label })
        clickable(d.window!!.decorView, UiResolutionMode.HD_720P.label).performClick(); idle()
        assertEquals(UiResolutionMode.HD_720P.ordinal, Prefs.uiResolution)
        assertEquals(3f, TvResolution.density!!, 0.01f)
        look.pause().stop().destroy()
        Prefs.uiResolution = 0; TvResolution.modeChanged(ctx)
        // Ustawienia → Odtwarzanie → Maksymalna rozdzielczość obrazu
        val pb = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(600)
        clickable(pb.get().window.decorView, SettingsActivity.MAX_VIDEO_TITLE).performClick(); idle()
        clickable(ShadowDialog.getLatestDialog().window!!.decorView, VideoResolutionLimit.FHD_1080P.label).performClick(); idle()
        assertEquals(VideoResolutionLimit.FHD_1080P, EngineConfigRepository.current.maxVideoResolution)
        // okno silnika w odtwarzaczu: karta z bieżącym limitem, OK – kolejny próg
        val sheet = EngineSheet.show(pb.get(), null, { null }); idle()
        val card = all(sheet.window!!.decorView).first { it.tag == "engine:maxres" }
        assertTrue(card.contentDescription.toString().startsWith(SettingsActivity.MAX_VIDEO_TITLE))
        card.performClick(); idle()
        assertEquals(VideoResolutionLimit.HD_720P, EngineConfigRepository.current.maxVideoResolution)
        sheet.dismiss(); pb.pause().stop().destroy()
    }
}

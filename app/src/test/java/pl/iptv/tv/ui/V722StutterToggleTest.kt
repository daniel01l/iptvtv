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
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.Prefs
import java.time.Duration

/** 7.22 – „Automatyczna korekta przy zacinaniu obrazu”: jedno źródło (repozytorium), profile, strażnik wyłączony = brak zmian, Ustawienia i okno silnika. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V722StutterToggleTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @After fun reset() { Prefs.dropWatchdog = true; EngineProfiles.apply(EngineProfiles.Profile.AUTO); Playback.stop(); ChannelEngines.resetForTest() }

    @Test fun toggleIsSingleSourceAndDisablesWatchdog() {
        EngineProfiles.apply(EngineProfiles.Profile.AUTO)
        assertTrue(EngineConfigRepository.current.autoStutterCorrectionEnabled)
        assertEquals(EngineProfiles.Profile.AUTO, EngineProfiles.active())
        EngineProfiles.apply(EngineProfiles.Profile.STABLE); assertTrue(Prefs.dropWatchdog)
        // ręczne wyłączenie w dowolnym profilu – „Własny (Zmodyfikowany)”
        EngineConfigRepository.setAutoStutterCorrection(false)
        assertFalse(EngineConfigRepository.current.autoStutterCorrectionEnabled); assertNull(EngineProfiles.active())
        assertEquals(EngineProfiles.CUSTOM, EngineProfiles.activeLabel())
        // wyłączony – strażnik nie zmienia silnika ani nie zapisuje profilu
        ChannelEngines.resetForTest()
        val ch = Channel("st1", "Kanał", "http://127.0.0.1:9/live/st1.ts", "G")
        Playback.play(ctx, ch, ch.url); idle()
        assertFalse(Playback.simulateFrameDrops(25f)); assertFalse(Playback.simulateStalledFrames(10_000))
        assertNull(ChannelEngines.get(ch.key))
        EngineConfigRepository.setAutoStutterCorrection(true)
        assertTrue(Playback.simulateFrameDrops(25f)); assertNotNull(ChannelEngines.get(ch.key))
    }

    @Test fun toggleInSettingsAndPlayerEngineWindow() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.okLongAction = 0
        val st = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(600)
        val row = all(st.get().window.decorView).first { v -> v.isClickable && all(v).filterIsInstance<android.widget.TextView>().any { it.text == SettingsActivity.AUTO_STUTTER_TITLE } }
        row.performClick(); idle()
        assertFalse(Prefs.dropWatchdog)
        st.pause().stop().destroy()
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertNotNull(a)
        val sheetHost = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup().get(); idle(300)
        val d = EngineSheet.show(sheetHost, null, { null }); idle()
        val card = all(d.window!!.decorView).first { it.tag == "engine:stutter" }
        assertTrue(card.contentDescription.toString().startsWith(SettingsActivity.AUTO_STUTTER_TITLE) && card.contentDescription.endsWith("wyłączone"))
        card.performClick(); idle()
        assertTrue(Prefs.dropWatchdog)
        assertTrue(all(d.window!!.decorView).first { it.tag == "engine:stutter" }.contentDescription.endsWith("włączone"))
        d.dismiss()
    }
}

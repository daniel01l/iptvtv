package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/**
 * 7.23 – Ustawienia otwarte z pulpitu (Nowoczesny / PS5 / X25; telefon i TV): Live TV zatrzymane i zwolnione (bez obrazu kanału
 * w tle – tło motywu), wyjście wraca na pulpit (także gdy pulpit zamknął się np. po zmianie motywu). Ustawienia z innych ekranów
 * – jak dotąd (kanał gra w tle).
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723SettingsFromDashboardTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Playback.stop(); Playback.forgetSuspended()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false; Prefs.parentalSettings = false
        val f = File(ctx.filesDir, "v723s.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"d1\" group-title=\"G\",Kanał 1\nhttp://127.0.0.1:9/live/1.ts\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Prefs.modernHome = true; Ui.applyPreset(ModernTheme.ID); X25Theme.justEnabled = false
    }

    @After fun tearDown() { Playback.stop(); Playback.forgetSuspended(); X25Theme.justEnabled = false }

    private fun videoShown(a: android.app.Activity) = all(a.window.decorView).any { it is androidx.media3.ui.PlayerView && it.visibility == View.VISIBLE }

    private fun check() {
        val ch = Repository.channels[0]
        // z pulpitu: telewizja zatrzymana, bez obrazu kanału w tle
        for (cls in listOf(ModernHomeActivity::class.java, Ps5HomeActivity::class.java, X25DashboardActivity::class.java)) {
            Playback.play(ctx, ch, ch.url); idle()
            assertTrue(Playback.active)
            val dash = Robolectric.buildActivity(cls).get()
            val i = SettingsActivity.intentFrom(dash)
            assertTrue(i.getBooleanExtra(SettingsActivity.EXTRA_FROM_DASHBOARD, false))
            val s = Robolectric.buildActivity(SettingsActivity::class.java, i).setup(); idle()
            assertTrue(s.get().openedFromDashboard)
            assertFalse("${cls.simpleName}: Live TV zatrzymane w Ustawieniach", Playback.active); assertNull(Playback.player)
            assertNotNull(Playback.suspended)
            assertFalse(videoShown(s.get()))
            // wyjście: pulpit pod spodem – zwykły powrót (bez otwierania nowego ekranu)
            s.get().finish(); idle()
            assertNull(SettingsActivity.returnedTo)
            assertNull(shadowOf(s.get()).nextStartedActivity)
            s.pause().stop().destroy()
            Playback.stop(); Playback.forgetSuspended()
        }
        // z innego ekranu (np. lista kanałów) – jak dotąd: kanał gra w tle Ustawień
        Playback.play(ctx, ch, ch.url); idle()
        val s2 = Robolectric.buildActivity(SettingsActivity::class.java, Intent(ctx, SettingsActivity::class.java)).setup(); idle()
        assertFalse(s2.get().openedFromDashboard)
        assertTrue(Playback.active)
        s2.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun phonePortraitStopsLiveTvOnlyFromDashboard() = check()

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun phoneLandscapeStopsLiveTvOnlyFromDashboard() = check()

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvStopsLiveTvOnlyFromDashboard() = check()

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun exitReopensDashboardWhenItClosedMeanwhile() {
        val dash = Robolectric.buildActivity(ModernHomeActivity::class.java).get()
        val s = Robolectric.buildActivity(SettingsActivity::class.java, SettingsActivity.intentFrom(dash)).setup(); idle()
        dash.finish()                                     // np. pulpit przebudowany / zamknięty po zmianie motywu
        s.get().finish(); idle()
        assertEquals(ModernHomeActivity::class.java.name, shadowOf(s.get()).nextStartedActivity?.component?.className)
        assertEquals(ModernHomeActivity::class.java.name, SettingsActivity.returnedTo)
        s.pause().stop().destroy()
    }
}

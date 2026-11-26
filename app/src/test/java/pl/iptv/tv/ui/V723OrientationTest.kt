package pl.iptv.tv.ui

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/**
 * 7.23 – orientacja na telefonie we wszystkich motywach: żaden ekran nie ma w manifeście stałej orientacji (dawne `sensorLandscape`
 * w MainActivity i odtwarzaczach dawało chwilowy poziom w trybie pionowym), jedna polityka [OrientationPolicy] dla każdej aktywności
 * (także przyszłej – test przechodzi po wszystkich ekranach z manifestu).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
class V723OrientationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun tearDown() { Prefs.orientation = 0 }

    private fun appActivities(): List<ActivityInfo> =
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
            .filter { it.name.startsWith("pl.iptv.tv.") }

    @Test fun noScreenForcesOrientationInManifest() {
        val acts = appActivities()
        assertTrue(acts.size > 15)
        for (a in acts) assertTrue("${a.name}: ${a.screenOrientation}",
            a.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_BEHIND || a.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    @Test fun everyScreenFollowsOnePolicy() {
        for (info in appActivities()) {
            @Suppress("UNCHECKED_CAST") val cls = Class.forName(info.name) as Class<out android.app.Activity>
            val a = Robolectric.buildActivity(cls).get()
            if (OrientationPolicy.skipped(a)) continue
            // pionowo: menu zawsze pionowo (bez chwilowego poziomu), odtwarzacze obracają się z telefonem
            val portrait = OrientationPolicy.target(a, tv = false, pref = OrientationPolicy.PORTRAIT)
            assertEquals(info.name, if (OrientationPolicy.isPlayer(a)) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, portrait)
            assertEquals(info.name, ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationPolicy.target(a, tv = false, pref = OrientationPolicy.AUTO))
            assertEquals(info.name, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.target(a, tv = false, pref = OrientationPolicy.LANDSCAPE))
            // TV – zawsze poziomo
            assertEquals(info.name, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.target(a, tv = true, pref = OrientationPolicy.PORTRAIT))
        }
    }

    @Test fun portraitModeScreensStartPortraitInEveryTheme() {
        Prefs.orientation = OrientationPolicy.PORTRAIT
        for (theme in Ui.PRESETS.map { it.id }) {
            Ui.applyPreset(theme); X25Theme.justEnabled = false
            val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            assertEquals(theme, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, c.get().requestedOrientation)
            c.pause().stop().destroy()
        }
        Ui.applyPreset(ModernTheme.ID); X25Theme.justEnabled = false
    }
}

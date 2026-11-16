package pl.iptv.tv.ui

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Integracja SmartTube: wykrywanie, normalizacja linków, wyszukiwanie, odtwarzanie, fallbacki. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SmartTubeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val spm get() = shadowOf(ctx.packageManager)
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    private fun activity(): Activity {
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }
    }

    private fun install(pkg: String, enabled: Boolean = true) {
        spm.installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "29.0"
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = enabled }
        })
    }

    private fun addActivity(pkg: String, action: String, scheme: String? = null) {
        val cn = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT); if (scheme != null) addDataScheme(scheme)
        })
    }

    @Test fun normalizeLinks() {
        val w = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(w, SmartTubeIntegrationHelper.normalizeUrl("dQw4w9WgXcQ"))
        assertEquals(w, SmartTubeIntegrationHelper.normalizeUrl("https://youtu.be/dQw4w9WgXcQ?t=10"))
        assertEquals(w, SmartTubeIntegrationHelper.normalizeUrl("youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(w, SmartTubeIntegrationHelper.normalizeUrl("http://m.youtube.com/watch?v=dQw4w9WgXcQ&list=x"))
        assertEquals(w, SmartTubeIntegrationHelper.normalizeUrl(" https://www.youtube.com/live/dQw4w9WgXcQ "))
        assertEquals("https://www.youtube.com/playlist?list=PL1", SmartTubeIntegrationHelper.normalizeUrl("http://www.youtube.com/playlist?list=PL1"))
        assertNull(SmartTubeIntegrationHelper.normalizeUrl("https://vimeo.com/123"))
        assertNull(SmartTubeIntegrationHelper.normalizeUrl(""))
        assertNull(SmartTubeIntegrationHelper.normalizeUrl(null))
        assertTrue(SmartTubeIntegrationHelper.searchUrl("mecz na żywo").endsWith("mecz%20na%20%C5%BCywo"))
    }

    @Test fun detectionPrefersStableAndSkipsDisabled() {
        assertFalse(SmartTubeIntegrationHelper.isInstalled(ctx))
        install(SmartTubeIntegrationHelper.PKG_BETA)
        assertEquals(SmartTubeIntegrationHelper.PKG_BETA, SmartTubeIntegrationHelper.installedPackage(ctx))
        install(SmartTubeIntegrationHelper.PKG_STABLE)
        assertEquals(SmartTubeIntegrationHelper.PKG_STABLE, SmartTubeIntegrationHelper.installedPackage(ctx))
        assertEquals("29.0", SmartTubeIntegrationHelper.installedVersion(ctx))
        install(SmartTubeIntegrationHelper.PKG_STABLE, enabled = false)
        assertEquals(SmartTubeIntegrationHelper.PKG_BETA, SmartTubeIntegrationHelper.installedPackage(ctx))
    }

    @Test fun searchUsesSmartTubeAction() {
        val p = SmartTubeIntegrationHelper.PKG_STABLE
        install(p); addActivity(p, SmartTubeIntegrationHelper.ACTION_SEARCH)
        val a = activity()
        assertTrue(SmartTubeIntegrationHelper.search(a, "  wiadomości "))
        val i = shadowOf(a).nextStartedActivity
        assertEquals(SmartTubeIntegrationHelper.ACTION_SEARCH, i.action)
        assertEquals(p, i.`package`)
        assertEquals("wiadomości", i.getStringExtra(SearchManager.QUERY))
    }

    @Test fun searchFallsBackToResultsPage() {
        val p = SmartTubeIntegrationHelper.PKG_BETA
        install(p); addActivity(p, Intent.ACTION_VIEW, "https")
        val a = activity()
        assertTrue(SmartTubeIntegrationHelper.search(a, "sport"))
        val i = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(p, i.`package`)
        assertTrue(i.dataString!!.contains("search_query=sport"))
    }

    @Test fun playOpensNormalizedLinkInSmartTube() {
        val p = SmartTubeIntegrationHelper.PKG_STABLE
        install(p); addActivity(p, Intent.ACTION_VIEW, "https")
        val a = activity()
        assertTrue(SmartTubeIntegrationHelper.play(a, "https://youtu.be/dQw4w9WgXcQ"))
        val i = shadowOf(a).nextStartedActivity
        assertEquals(p, i.`package`)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", i.dataString)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun notInstalledShowsFallbackDialog() {
        val a = activity()
        assertFalse(SmartTubeIntegrationHelper.play(a, "dQw4w9WgXcQ")); idle()
        assertNull(shadowOf(a).nextStartedActivity)
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        assertNotNull(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
        assertNotNull(d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL))
    }

    @Test fun installedButNoActivityDoesNotCrash() {
        install(SmartTubeIntegrationHelper.PKG_STABLE)
        val a = activity()
        assertFalse(SmartTubeIntegrationHelper.search(a, "x"))
        assertFalse(SmartTubeIntegrationHelper.play(a, "dQw4w9WgXcQ"))
        assertFalse(SmartTubeIntegrationHelper.launch(a))
        assertFalse(SmartTubeIntegrationHelper.play(a, "https://vimeo.com/1"))
        assertNull(shadowOf(a).nextStartedActivity)
    }
}

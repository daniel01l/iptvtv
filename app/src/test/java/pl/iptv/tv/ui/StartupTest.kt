package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.BootReceiver
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StartActions
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class StartupTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setup() {
        val f = File(ctx.filesDir, "pl.m3u")
        f.writeText((1..5).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 group-title=\"G\",K$it\nhttp://t/$it\n" })
        Repository.resetAllEdits()
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true
        runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): Intent? {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        return shadowOf(c.get()).nextStartedActivity.also { c.pause().stop().destroy() }
    }

    @Test fun lastChannelIsDefault() {
        assertEquals(StartActions.LAST_CHANNEL, Prefs.startAction)
        Prefs.lastChannel = "http://t/3"
        val i = launch()!!
        assertEquals(PlayerActivity::class.java.name, i.component!!.className)
        assertEquals("http://t/3", i.getStringExtra(PlayerActivity.EXTRA_CHANNEL))
    }

    @Test fun specificChannelAndMenuPositions() {
        Prefs.startAction = StartActions.CHANNEL; Prefs.startChannel = "http://t/5"
        assertEquals("http://t/5", launch()!!.getStringExtra(PlayerActivity.EXTRA_CHANNEL))
        Prefs.startAction = StartActions.SETTINGS
        assertEquals(SettingsActivity::class.java.name, launch()!!.component!!.className)
        Prefs.startAction = StartActions.EDITOR
        assertEquals(EditorActivity::class.java.name, launch()!!.component!!.className)
        Prefs.startAction = StartActions.LIST
        assertNull(launch())
        Prefs.startAction = StartActions.GUIDE
        assertEquals(GuideActivity::class.java.name, launch()!!.component!!.className)   // przewodnik EPG
        Prefs.startAction = StartActions.SEARCH
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        assertEquals(android.view.View.VISIBLE, c.get().findViewById<android.view.View>(pl.iptv.tv.R.id.searchRow).visibility)
        c.pause().stop().destroy()
        Prefs.startAction = StartActions.LAST_CHANNEL
    }

    @Test fun bootReceiverRespectsSetting() {
        val app = shadowOf(ctx)
        Prefs.bootStart = false
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(app.nextStartedActivity)
        Prefs.bootStart = true
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        val i = app.nextStartedActivity!!
        assertEquals(MainActivity::class.java.name, i.component!!.className)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val am = shadowOf(ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
        assertTrue("ponowienia startu", am.scheduledAlarms.size >= 3)
        // ponowienie: aplikacja nie na ekranie -> otwiera się
        BootReceiver().onReceive(ctx, Intent(BootReceiver.RETRY))
        assertEquals(MainActivity::class.java.name, app.nextStartedActivity.component!!.className)
        Prefs.bootStart = false
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherAndBatteryTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setup() {
        val f = File(ctx.filesDir, "pl.m3u")
        f.writeText("#EXTM3U\n#EXTINF:-1,K1\nhttp://t/1\n")
        runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Prefs.lastChannel = "http://t/1"
    }

    @Test fun firstRunPermissionWizardThenStartAction() {
        Prefs.permissionsAsked = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val a = c.get()
        fun dlg() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val intro = dlg()
        val introText = shadowOf(intro).run { (intro.findViewById<android.widget.TextView>(android.R.id.message))!!.text.toString() }
        assertTrue(introText.contains("Dostęp do plików"))
        assertTrue(introText.contains("Powiadomienia"))
        assertTrue(introText.contains("oszczędzania energii"))
        assertNull(shadowOf(a).nextStartedActivity)                    // akcja startowa czeka
        intro.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        val seen = mutableListOf<String>()
        var notifRequested = false
        repeat(10) {
            val d = dlg(); if (!d.isShowing) return@repeat
            val title = d.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString(); seen += title
            if (title.contains("Powiadomienia")) {
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
                notifRequested = shadowOf(a).lastRequestedPermission?.requestedPermissions?.contains(android.Manifest.permission.POST_NOTIFICATIONS) == true
                shadowOf(a).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
                a.onRequestPermissionsResult(PermissionWizard.REQ, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), intArrayOf(0))
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            } else {
                d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            }
        }
        assertTrue(seen.toString(), notifRequested)
        assertTrue(seen.first().startsWith("1/"))                      // numeracja kroków
        assertTrue(seen.any { it.contains("Dostęp do plików") })
        // po kreatorze – akcja startowa (ostatni kanał)
        val started = generateSequence { shadowOf(a).nextStartedActivity }.toList()
        assertTrue(started.any { it.component?.className == PlayerActivity::class.java.name })
        assertTrue(Prefs.permissionsAsked)
        assertFalse(PermissionWizard.missing(a).any { it.title.contains("Powiadomienia") })
        c.pause().stop().destroy()
        // drugie uruchomienie – bez kreatora
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        assertEquals(PlayerActivity::class.java.name, shadowOf(c2.get()).nextStartedActivity.component!!.className)
        c2.pause().stop().destroy()
    }

    @Test fun launcherModeToggleAndHomeForwarding() {
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true
        assertFalse(SystemTools.isLauncherEnabled(ctx))
        SystemTools.setLauncherEnabled(ctx, true)
        assertTrue(SystemTools.isLauncherEnabled(ctx))
        val h = Robolectric.buildActivity(pl.iptv.tv.HomeActivity::class.java).setup()
        val first = shadowOf(h.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, first.component!!.className)
        assertFalse(first.getBooleanExtra(MainActivity.EXTRA_SKIP_START, true))   // start TV -> akcja startowa
        assertTrue(h.get().isFinishing)
        val h2 = Robolectric.buildActivity(pl.iptv.tv.HomeActivity::class.java).setup()
        assertTrue(shadowOf(h2.get()).nextStartedActivity.getBooleanExtra(MainActivity.EXTRA_SKIP_START, false)) // kolejny HOME
        val m = Robolectric.buildActivity(MainActivity::class.java,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SKIP_START, true)).setup(); idle()
        assertNull(shadowOf(m.get()).nextStartedActivity)          // HOME nie włącza kanału ponownie
        assertEquals(android.view.View.VISIBLE, m.get().findViewById<android.view.View>(pl.iptv.tv.R.id.btnApps).visibility)
        m.pause().stop().destroy()
        SystemTools.setLauncherEnabled(ctx, false)
    }
}

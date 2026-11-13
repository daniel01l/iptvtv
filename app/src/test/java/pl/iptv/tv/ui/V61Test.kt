package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MimeTypes
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.BootReceiver
import pl.iptv.tv.HomeActivity
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/** v6.1: natychmiastowa zmiana motywu, usunięte motywy, logo bez zabarwień, odtwarzanie na żywo, przyciski okien, tryb launchera. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V61Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0; Prefs.mainLayout = 0
        Prefs.bootStart = false
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v61.m3u").apply { writeText((1..4).joinToString("", "#EXTM3U\n") {
            "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G\",Kanał $it\nhttp://v61/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed(); Prefs.mainLayout = 0; Prefs.bootStart = false }

    @Test fun onlyCoreThemesAndMigration() {
        val ids = Ui.PRESETS.map { it.id }
        assertTrue(ids.containsAll(listOf("ios", "xbox")))
        for (gone in listOf("ps5", "win11", "magic")) assertFalse(gone, gone in ids)
        Prefs.theme = "ps5"; assertEquals("xbox", Ui.preset.id)
        Prefs.theme = "magic"; assertEquals("ios", Ui.preset.id)
        Prefs.theme = "win11"; assertEquals("modern", Ui.preset.id)
        assertTrue(Ui.ANIMS.none { "PS5" in it || "MagicOS" in it || "Windows" in it })
        assertTrue(Ui.INFO_STYLES.none { "PS5" in it || "MagicOS" in it || "Windows" in it })
    }

    @Test fun themeAppliesToOpenScreensWithoutRestart() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)

        m.pause().stop()
        val epoch = Ui.themeEpoch
        Ui.applyPreset("xbox"); Ui.changed()
        assertTrue(Ui.themeEpoch > epoch)
        m.restart().start().resume(); idle(400)
        val now = m.get()
        val item = now.findViewById<androidx.recyclerview.widget.RecyclerView>(pl.iptv.tv.R.id.channels).findViewHolderForAdapterPosition(0)!!.itemView
        val sl = item.background.constantState!!.newDrawable() as android.graphics.drawable.StateListDrawable
        sl.state = intArrayOf(android.R.attr.state_focused)
        assertTrue("menu w nowym motywie", sl.current is android.graphics.drawable.LayerDrawable)   // zaznaczenie Xbox
        m.pause().stop().destroy()
        // ustawienia: brak prośby o ponowne uruchomienie
        val s = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle()
        val texts = ArrayList<String>()
        fun walk(v: View) { if (v is TextView) texts += v.text.toString(); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(s.get().window.decorView)
        assertTrue(texts.none { "Uruchom aplikację ponownie" in it })
        s.pause().stop().destroy()
    }

    @Test fun logosKeepOriginalColors() {
        Ui.applyPreset("xbox"); Ui.changed()
        val pv = PiconView(ctx)
        pv.imageTintList = android.content.res.ColorStateList.valueOf(Ui.accent)
        pv.setColorFilter(Ui.focus)
        pv.imageAlpha = 80
        assertNull(pv.imageTintList)
        assertNull(pv.colorFilter)
        assertEquals(255, pv.imageAlpha)
        val d = android.graphics.drawable.ColorDrawable(0xFFFF0000.toInt()).apply { colorFilter = android.graphics.PorterDuffColorFilter(0xFF00FF00.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
        pv.setImageDrawable(d)
        assertNull(pv.drawable.colorFilter)
    }

    @Test fun liveStreamCompatibility() {
        assertEquals(MimeTypes.APPLICATION_M3U8, Playback.guessMime("http://a/live/u/p/1.m3u8?token=1"))
        assertEquals(MimeTypes.APPLICATION_M3U8, Playback.guessMime("http://a/get.php?id=1&output=m3u8"))
        assertEquals(MimeTypes.APPLICATION_MPD, Playback.guessMime("http://a/x.mpd"))
        assertNull(Playback.guessMime("http://a/live/u/p/123"))
        assertNull(Playback.guessMime("http://a/live/u/p/123.ts"))
        val f = androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        assertTrue(Playback.TS_FLAGS and f != 0)
        assertTrue(Playback.TS_FLAGS and androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS != 0)
        assertEquals(0, Playback.streamHttp.callTimeoutMillis)                 // strumień na żywo bez limitu całego żądania
        assertEquals(Playback.STREAM_READ_S * 1000, Playback.streamHttp.readTimeoutMillis.toLong())
        assertTrue(Playback.START_BUFFER_MS >= 2_000 && Playback.REBUFFER_MS >= 5_000)
    }

    @Test fun dialogButtonsNotUnderScrollbars() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)
        val long = (1..80).joinToString("\n") { "Wiersz $it" }
        Ui.dialog(m.get()).setTitle("Długie okno").setMessage(long)
            .setPositiveButton("OK", null).setNegativeButton("Anuluj", null).setNeutralButton("Więcej", null).show(); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val bp = d.findViewById<View>(androidx.appcompat.R.id.buttonPanel)!!
        assertFalse(bp.isVerticalScrollBarEnabled)
        val content = d.findViewById<View>(androidx.appcompat.R.id.scrollView)!!
        assertEquals(View.SCROLLBARS_OUTSIDE_INSET, content.scrollBarStyle)
        for (w in intArrayOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL)) {
            val b = d.getButton(w); assertTrue(b.isShown); assertTrue(b.isFocusable)
        }
        d.dismiss(); idle()
        m.pause().stop().destroy()
    }

    @Test fun launcherBootHandling() {
        Prefs.homeBootId = ""
        assertTrue(HomeActivity.firstSinceBoot(ctx))                          // pierwsze HOME po włączeniu – akcja startowa
        assertFalse(HomeActivity.firstSinceBoot(ctx))                         // kolejne – tylko lista kanałów (także po restarcie procesu)
        assertFalse(BootReceiver.shouldLaunch(ctx))                           // bez opcji i bez trybu launchera – nic
        Prefs.bootStart = true
        assertTrue(BootReceiver.shouldLaunch(ctx))
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val next = shadowOf(home).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertTrue(next.getBooleanExtra(MainActivity.EXTRA_SKIP_START, false))
    }
}

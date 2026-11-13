package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/** v5.9: przycinanie do ramek, pasek informacji wg motywu, odtwarzacz archiwum (oś czasu, przyspieszane przewijanie), listy menu. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V59Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun down(a: android.app.Activity, code: Int, repeat: Int = 0) { a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat)); idle(50) }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0; Prefs.mainLayout = 0
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v59.m3u").apply { writeText((1..6).joinToString("", "#EXTM3U\n") {
            "#EXTINF:-1 catchup=\"default\" catchup-days=\"7\" catchup-source=\"?utc={utc}\" tvg-id=\"c$it\" group-title=\"G\",K$it\nhttp://v59/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed(); Prefs.mainLayout = 0 }

    @Test fun panelsAndLogosClipToTheirFrames() {
        for (id in listOf("modern", "ios", "xbox")) {
            Ui.applyPreset(id); Ui.changed()
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)
            val a = c.get()
            val rv = a.findViewById<RecyclerView>(R.id.channels)
            assertTrue(id, rv.clipToOutline)                                // panel przycina powiększone elementy do ramki
            assertNull(id, rv.itemAnimator)                                  // szybka nawigacja bez animacji zmian
            assertTrue(id, a.findViewById<View>(R.id.previewBox).clipToOutline)
            val pv = rv.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<PiconView>(R.id.picon)
            assertTrue(id, pv.clipToOutline)
            c.pause().stop().destroy()
        }
        Ui.applyPreset("xbox"); Ui.changed(); assertTrue(Ui.logoRadius <= Ui.dp(1))
    }

    @Test fun infoBarFollowsTheme() {
        val expect = mapOf("ios" to 6, "xbox" to 9, "modern" to 0)
        expect.forEach { (id, style) ->
            Ui.applyPreset(id); Ui.changed()
            assertEquals(id, style, Ui.infoStyle)
            if (style >= 5) { assertTrue(id, Ui.infoBarBg(false) is TexturedDrawable); assertNotNull(id, Ui.infoLogoBg()) }
        }
        Ui.applyPreset("ios"); Ui.changed()
        assertTrue(Ui.infoFloating)
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle(600)
        val a = c.get()
        val logo = a.findViewById<ImageView>(R.id.oPicon)
        assertNotNull(logo.background); assertTrue(logo.clipToOutline)
        assertTrue(a.findViewById<View>(R.id.overlay).background is TexturedDrawable)
        c.pause().stop().destroy()
    }

    @Test fun catchupTimelineAndAcceleratedSeek() {
        Ui.applyPreset("xbox"); Ui.changed()
        val now = System.currentTimeMillis()
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
            .putExtra(PlayerActivity.EXTRA_START, now - 7_200_000).putExtra(PlayerActivity.EXTRA_END, now - 3_600_000)).setup(); idle(800)
        val a = c.get()
        val pv = a.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)
        val bar = pv.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        val play = pv.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
        assertTrue(bar.isFocusable)
        assertEquals(androidx.media3.ui.R.id.exo_progress, play.nextFocusUpId)       // ▲ z przycisków – oś czasu
        assertEquals(androidx.media3.ui.R.id.exo_play_pause, bar.nextFocusDownId)
        // przytrzymany ► – coraz większy krok
        val m = PlayerActivity::class.java.getDeclaredMethod("seekAccel", Int::class.javaPrimitiveType).apply { isAccessible = true }
        assertEquals(listOf(1, 2, 4, 8), listOf(0, 5, 12, 30).map { m.invoke(a, it) as Int })
        pv.showController(); idle()
        down(a, KeyEvent.KEYCODE_DPAD_LEFT); down(a, KeyEvent.KEYCODE_DPAD_LEFT, 12)
        val hint = pv.findViewById<TextView>(R.id.ctlHint).text.toString()
        assertTrue(hint, hint.startsWith("⏪ −") && hint.contains("→"))       // przesunięcie i docelowa godzina
        // przycisk w zaznaczeniu powiększony
        play.requestFocus(); idle(400)
        assertTrue(play.scaleX > 1.05f)
        c.pause().stop().destroy()
    }
}

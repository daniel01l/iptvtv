package pl.iptv.tv.ui

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.widget.ImageView
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
import pl.iptv.tv.IptvApp
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/** v6.0: geometria i zaznaczenie motywów systemowych, przycinanie elementów list, logo (limity, ponowienia, inicjały od razu). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V60Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun focused() = (Ui.itemBg() as StateListDrawable).apply { state = intArrayOf(android.R.attr.state_focused) }.current

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.orientation = 0; Prefs.mainLayout = 0
        Ui.applyPreset("modern"); Ui.changed()
        val f = File(ctx.filesDir, "v60.m3u").apply { writeText((1..6).joinToString("", "#EXTM3U\n") {
            "#EXTINF:-1 tvg-id=\"c$it\" tvg-logo=\"http://127.0.0.1:9/logo$it.png\" group-title=\"G\",Kanał $it\nhttp://v60/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed(); Prefs.mainLayout = 0 }

    @Test fun systemThemesGeometryAndFocus() {
        Ui.applyPreset("ios"); Ui.changed()
        assertTrue(Ui.preset.radius in 20..24)                               // duże, zaokrąglone karty
        assertTrue(focused() is LayerDrawable)                               // miękki, świecący pierścień
        Ui.applyPreset("xbox"); Ui.changed()
        assertEquals(0xFF107C10.toInt(), Ui.focus)                           // ramka #107C10
        assertTrue(focused() is LayerDrawable)                               // ramka + jasna linia wewnętrzna
    }

    @Test fun listItemsClipTheirContent() {
        for (id in listOf("modern", "ios", "xbox")) {
            Ui.applyPreset(id); Ui.changed()
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(400)
            val rv = c.get().findViewById<RecyclerView>(R.id.channels)
            val item = rv.findViewHolderForAdapterPosition(0)!!.itemView
            assertTrue(id, item.clipToOutline)                               // logo i tło nie wychodzą poza kafelek
            c.pause().stop().destroy()
        }
    }

    @Test fun logoPipelineLimitsAndPlaceholder() {
        assertTrue(IptvApp.PICON_CONNECT_S >= 40 && IptvApp.PICON_READ_S >= 75 && IptvApp.PICON_CALL_S >= 180)
        assertTrue(IptvApp.PICON_RETRIES >= 3)
        assertEquals(3, PICON_RETRY_DELAYS.size)
        val iv = ImageView(ctx)
        iv.loadPicon(Repository.channels[0])
        assertTrue(iv.drawable is MonogramDrawable)                          // od razu inicjały, bez pustego kafelka
    }
}

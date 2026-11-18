package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.TorrentStreams
import java.time.Duration

/**
 * 7.1: kanał nie dekoduje „na ślepo” za ekranem głównym Nowoczesny (czysty start w odtwarzaczu), jednolite tło odtwarzacza,
 * przejście „Nowoczesny”, źródła P2P przez serwer torrent, wymuszony obraz 4K w Stremio, konfiguracja logowania STube.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V71Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(5); if (cond()) return } }

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.modernHome = true
        Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
    }

    @After fun tearDown() { Playback.stop(); Prefs.torrentServer = ""; Prefs.ytClientId = ""; Prefs.ytClientSecret = "" }

    // ---------------- telewizja i motyw Nowoczesny ----------------

    @Test fun modernHomePausesLiveTvSoChannelStartsClean() {
        val ch = Channel("k1", "Kanał", "http://127.0.0.1:9/live/1.ts", "G")
        Playback.play(ctx, ch, ch.url); idle()
        assertTrue(Playback.active)
        // ekran główny: kanał wstrzymany (dekoder i połączenia zwolnione), zapamiętany do powrotu w menu
        Robolectric.buildActivity(ModernHomeActivity::class.java).setup(); idle()
        assertFalse(Playback.active)
        assertEquals(ch.url, Playback.suspended?.second)
        assertFalse(Playback.headless)
    }

    @Test fun playerScreenBackgroundIsSolidAndModernTransition() {
        val f = java.io.File(ctx.filesDir, "v71.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,K1\nhttp://127.0.0.1:9/1.ts\n") }
        pl.iptv.tv.data.Repository.resetAllEdits(); runBlocking { pl.iptv.tv.data.Repository.loadPlaylist(f.absolutePath) }
        val i = android.content.Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, pl.iptv.tv.data.Repository.channels[0].key)
        val a = Robolectric.buildActivity(PlayerActivity::class.java, i).setup().get(); idle()
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue("pod obrazem jednolity kolor, nie cieniowania motywu", root.background is android.graphics.drawable.ColorDrawable)
        assertEquals(9, Ui.anim)
        assertEquals("Nowoczesny – płynne przenikanie", Ui.ANIMS[9])
        // tło menu: cieniowania z dwiema poświatami i winietą – rysowane bez błędów
        val bg = Ui.bgDrawable(); bg.setBounds(0, 0, 1920, 1080)
        bg.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)))
    }

    // ---------------- Stremio: P2P i 4K ----------------

    @Test fun torrentStreamsThroughServer() = runBlocking {
        val torr = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = if (r.path == "/echo") MockResponse().setBody("MatriX.133") else MockResponse().setResponseCode(404)
        }; start() }
        val strem = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = if (r.path == "/settings") MockResponse().setBody("{}") else MockResponse().setResponseCode(404)
        }; start() }
        try {
            val t = TorrentStreams.probe(torr.url("/").toString())!!
            assertEquals(TorrentStreams.Kind.TORRSERVER, t.kind)
            val s = TorrentStreams.probe(strem.url("/").toString().removePrefix("http://"))!!       // adres bez „http://”
            assertEquals(TorrentStreams.Kind.STREMIO, s.kind)
            // indeks pliku: Stremio od 0, TorrServer od 1
            assertEquals(t.base + "/stream/Film.mkv?link=abcdef&index=3&play", TorrentStreams.streamUrl(t, "ABCDEF", 2, "Film.mkv"))
            assertEquals(s.base + "/abcdef/2", TorrentStreams.streamUrl(s, "ABCDEF", 2))
            // zapisany adres serwera; źródło P2P staje się zwykłym źródłem HTTP (dane torrenta zostają)
            Prefs.torrentServer = strem.url("/").toString()
            val p2p = StremioStream("A", "http://a", name = "A\n1080p", infoHash = "abcdef", fileIdx = 1)
            assertEquals(StremioStream.Kind.TORRENT, p2p.kind)
            val http = TorrentStreams.asHttp(p2p, TorrentStreams.find()!!)
            assertEquals(StremioStream.Kind.HTTP, http.kind); assertEquals("abcdef", http.infoHash)
            assertTrue(http.url.endsWith("/abcdef/1"))
            // martwy adres – brak serwera
            Prefs.torrentServer = "127.0.0.1:9"
            assertNull(TorrentStreams.find())
        } finally { torr.shutdown(); strem.shutdown() }
    }

    @Test fun video4kNotSelectedTriggersForcedVideo() {
        fun tracks(selected: Boolean, type: String) = Tracks(listOf(Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(type).setWidth(3840).setHeight(2160).build()),
            false, intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES), booleanArrayOf(selected))))
        assertTrue(StremioDecoders.needsForcedVideo(tracks(false, "video/hevc")))
        assertFalse(StremioDecoders.needsForcedVideo(tracks(true, "video/hevc")))
        assertFalse(StremioDecoders.needsForcedVideo(tracks(false, "audio/eac3")))            // sam dźwięk (radio, muzyka)
        // wymuszenie obrazu: selektor pozwala przekroczyć zgłaszane możliwości dekodera
        assertTrue(StremioDecoders.trackSelector(ctx, forceVideo = true).parameters.exceedRendererCapabilitiesIfNecessary)
        assertFalse(StremioDecoders.trackSelector(ctx).parameters.exceedRendererCapabilitiesIfNecessary)
    }

    // ---------------- STube: logowanie ----------------

    @Test fun loginWithoutClientShowsSetupWithQr() {
        Prefs.ytClientId = ""
        if (pl.iptv.tv.data.YouTubeAuth.configured) return                                   // klient wbudowany w BuildConfig
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        var done: Boolean? = null
        YouTubeAccountUi.login(a) { done = it }; idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        fun views(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())
        val qr = views(d.window!!.decorView).filterIsInstance<android.widget.ImageView>().first { it.contentDescription == "Kod QR konsoli Google" }
        assertNotNull(qr.drawable)
        assertEquals("Wpisz identyfikator…", d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString())
        d.dismiss(); idle()
        assertEquals(false, done)
    }
}

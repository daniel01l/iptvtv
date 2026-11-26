package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
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
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioStream
import java.io.File
import java.time.Duration

/**
 * 7.25 – przesyłanie na urządzenia w pobliżu (Cast): przycisk tylko przy urządzeniach i włączonym ustawieniu, przekazanie
 * kanału / filmu (lokalnie cisza dopiero po starcie na odbiorniku), przełączanie kanałów w trakcie, błąd / brak odpowiedzi /
 * zakończenie – komunikat i powrót do odtwarzania lokalnego. Odbiornik – sztuczny [CastHub.Backend].
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V725CastTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private val tv = CastHub.Device("tv1", "Salon TV", "Chromecast")

    class FakeBackend(var ok: Boolean = true) : CastHub.Backend {
        var devices = emptyList<CastHub.Device>()
        var onDevices: ((List<CastHub.Device>) -> Unit)? = null
        val casts = ArrayList<Triple<CastHub.Device, CastHub.Media, String>>()
        var cb: ((CastHub.Result) -> Unit)? = null
        var pos = 0L; var stops = 0; var discovering = false
        override fun init(c: android.content.Context) = ok
        override fun startDiscovery(onDevices: (List<CastHub.Device>) -> Unit) { discovering = true; this.onDevices = onDevices; onDevices(devices) }
        override fun stopDiscovery() { discovering = false }
        override fun cast(device: CastHub.Device, media: CastHub.Media, mime: String, cb: (CastHub.Result) -> Unit) { casts += Triple(device, media, mime); this.cb = cb }
        override fun stop() { stops++ }
        override fun positionMs() = pos
        fun publish(list: List<CastHub.Device>) { devices = list; onDevices?.invoke(list) }
    }

    class FakeLocal : CastHub.Local {
        var paused = 0; var resumedAt = -1L
        override fun pause() { paused++ }
        override fun resume(positionMs: Long) { resumedAt = positionMs }
    }

    private lateinit var fake: FakeBackend
    private val defaultFactory = CastHub.backendFactory

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.orientation = 2; Prefs.castButton = true
        val f = File(ctx.filesDir, "v725.m3u").apply { writeText((1..4).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G\",Kanał $it\nhttp://10.1.2.3:9/live/$it.ts\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        CastHub.resetForTest(); fake = FakeBackend(); CastHub.backendFactory = { fake }
    }

    @After fun tearDown() {
        CastHub.resetForTest(); CastHub.backendFactory = defaultFactory
        Prefs.orientation = 0; Prefs.castButton = true; Playback.stop(); TvResolution.resetForTest()
    }

    private fun livePlayer(): org.robolectric.android.controller.ActivityController<PlayerActivity> {
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[1].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
        return Robolectric.buildActivity(PlayerActivity::class.java, i).setup().also { idle(400) }
    }

    private fun castIcon(a: PlayerActivity) = all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "cast" }

    @Test fun buttonVisibleOnlyWithNearbyDevicesAndSetting() {
        val c = livePlayer(); val a = c.get()
        assertTrue("wyszukiwanie urządzeń przy odtwarzaczu", fake.discovering)
        assertEquals("brak urządzeń – ukryty", View.GONE, castIcon(a).visibility)
        fake.publish(listOf(tv)); idle()
        assertEquals("urządzenie w pobliżu – widoczny", View.VISIBLE, castIcon(a).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(pl.iptv.tv.R.id.tCast).visibility)
        Prefs.castButton = false; CastHub.settingChanged(a); idle()
        assertEquals("ustawienie wył. – ukryty", View.GONE, castIcon(a).visibility)
        assertFalse(fake.discovering)
        Prefs.castButton = true; c.pause().stop(); assertFalse(fake.discovering); c.destroy()

        // bez Usług Google Play (backend się nie uruchamia) – nigdy widoczny
        CastHub.resetForTest(); fake = FakeBackend(ok = false).apply { devices = listOf(tv) }; CastHub.backendFactory = { fake }
        val b = livePlayer().get()
        assertFalse(CastHub.ready); assertEquals(View.GONE, castIcon(b).visibility)
    }

    @Test fun liveTvHandsOverChannelFollowsZappingAndReturnsOnReceiverError() {
        fake.devices = listOf(tv)
        val a = livePlayer().get(); idle()
        val ch = Repository.channels[1]
        assertEquals(ch.url, Playback.url)
        castIcon(a).performClick(); idle()
        val picker = CardSheet.shownActions!!
        all(picker.window!!.decorView).first { it.tag == "action:cast:tv1" }.performClick(); idle()
        assertEquals(1, fake.casts.size)
        assertEquals(ch.url, fake.casts[0].second.url); assertTrue(fake.casts[0].second.live); assertEquals("video/mp2t", fake.casts[0].third)
        assertEquals(ch.name, fake.casts[0].second.title)
        // do potwierdzenia startu na odbiorniku kanał gra dalej lokalnie (bez przerwy przy błędzie)
        assertNotNull(Playback.player); assertEquals(ch.url, Playback.url)
        fake.cb!!(CastHub.Result.Playing(tv)); idle()
        assertTrue(CastHub.isCasting(CastHub.Kind.LIVE))
        assertNull("lokalnie zatrzymane – bez dublowania dźwięku", Playback.url); assertNull(Playback.player)
        assertTrue(CastHub.bannerOn(a))
        // zmiana kanału w trakcie – nowy kanał na odbiornik, lokalnie dalej cisza
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, 0))
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CHANNEL_UP, 0)); idle(1_500)
        assertEquals(2, fake.casts.size); assertNotEquals(ch.url, fake.casts[1].second.url)
        assertNull(Playback.url)
        val now = fake.casts[1].second.url
        // odbiornik nie odtwarza strumienia – komunikat i powrót do odtwarzania tutaj (bieżący kanał)
        fake.cb!!(CastHub.Result.Failed("„Salon TV” nie może odtworzyć tego strumienia")); idle(400)
        assertFalse(CastHub.casting); assertFalse(CastHub.bannerOn(a))
        assertEquals(now, Playback.url)
        val msg = CastHub.lastMessage.orEmpty()
        assertTrue(msg, msg.contains("MPEG-TS") && msg.contains(CastHub.BACK_LOCAL))
    }

    @Test fun noAnswerFromDeviceKeepsLocalPlaybackWithMessage() {
        fake.devices = listOf(tv)
        val a = livePlayer().get(); idle()
        val ch = Repository.channels[1]
        castIcon(a).performClick(); idle()
        all(CardSheet.shownActions!!.window!!.decorView).first { it.tag == "action:cast:tv1" }.performClick(); idle()
        idle(CastHub.CONNECT_TIMEOUT_MS + 200)
        assertNull(CastHub.pending); assertFalse(CastHub.casting); assertEquals(1, fake.stops)
        assertEquals("kanał grał cały czas", ch.url, Playback.url)
        assertTrue(CastHub.lastMessage.orEmpty().contains("nie odpowiada"))
        // spóźniona odpowiedź po czasie – pominięta
        fake.cb!!(CastHub.Result.Playing(tv)); idle()
        assertFalse(CastHub.casting); assertEquals(ch.url, Playback.url)
    }

    @Test fun streamRulesAndClearMessages() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val local = FakeLocal()
        CastHub.attach(act, CastHub.Kind.STREMIO, local)
        // brak urządzeń
        CastHub.click(act, CastHub.Kind.STREMIO, local) { CastHub.Media("http://a/x.mp4", "Film") }
        assertEquals(CastHub.NO_DEVICES, CastHub.lastMessage)
        fake.publish(listOf(tv)); idle()
        // nic nie gra / materiał nie do przesłania / adres lokalny / nie-http
        CastHub.click(act, CastHub.Kind.STREMIO, local) { null }
        assertEquals(CastHub.NOTHING_PLAYING, CastHub.lastMessage)
        CastHub.click(act, CastHub.Kind.STUBE, local) { throw CastHub.Unsupported("Tylko osobny obraz i dźwięk") }
        assertEquals("Tylko osobny obraz i dźwięk", CastHub.lastMessage)
        CastHub.click(act, CastHub.Kind.STREMIO, local) { CastHub.Media("http://127.0.0.1:8090/stream/x.mkv", "Torrent") }
        assertTrue(CastHub.lastMessage.orEmpty().contains("tym urządzeniu"))
        assertNotNull(CastHub.unsupported(CastHub.Media("rtmp://srv/live", "x")))
        assertNull(CastHub.unsupported(CastHub.Media("https://cdn/x.m3u8", "x")))
        assertTrue(fake.casts.isEmpty()); assertEquals(0, local.paused)
        // typy dla odbiornika
        fun mime(u: String, live: Boolean = false) = CastHub.castMime(CastHub.Media(u, "x", live = live))
        assertEquals("application/x-mpegURL", mime("http://a/b/index.m3u8?token=1"))
        assertEquals("application/dash+xml", mime("http://a/b.mpd"))
        assertEquals("video/mp4", mime("http://a/film.mp4"))
        assertEquals("video/x-matroska", mime("http://a/film.mkv"))
        assertEquals("video/mp2t", mime("http://a/live/12345", live = true))
        assertEquals("video/mp2t", mime("http://a/live/1.ts"))
        assertEquals("application/x-mpegURL", CastHub.castMime(CastHub.Media("http://a/live/1", "x", mime = "application/x-mpegURL", live = true)))
        assertTrue(CastHub.failureHint(CastHub.Media("http://a/f.mkv", "x")).contains("MKV"))
        assertTrue(CastHub.failureHint(CastHub.Media("http://a/f.mp4", "x", headers = mapOf("Referer" to "r"))).contains("nagłówków"))
        CastHub.detach(act)
    }

    @Test fun vodResumesLocallyAtReceiverPosition() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val local = FakeLocal()
        CastHub.attach(act, CastHub.Kind.STREMIO, local)
        fake.publish(listOf(tv)); idle()
        CastHub.start(act, CastHub.Kind.STREMIO, tv, CastHub.Media("http://a/film.mp4", "Film", positionMs = 120_000), local)
        assertEquals(120_000, fake.casts[0].second.positionMs); assertEquals(0, local.paused)
        fake.cb!!(CastHub.Result.Playing(tv)); idle()
        assertEquals(1, local.paused); assertTrue(CastHub.bannerOn(act))
        // przycisk w trakcie – okno z „Zakończ przesyłanie”
        CastHub.click(act, CastHub.Kind.STREMIO, local) { null }
        assertNotNull(all(CardSheet.shownActions!!.window!!.decorView).firstOrNull { it.tag == "cast:stop" || it.tag == "action:cast:stop" })
        CardSheet.shownActions!!.dismiss()
        fake.pos = 600_000
        CastHub.stopCasting(); idle()
        assertEquals(600_000, local.resumedAt); assertEquals(1, fake.stops); assertFalse(CastHub.bannerOn(act))
        // rozłączenie z odbiornika (powiadomienie / inny telefon) – też powrót, od pozycji z odbiornika
        val l2 = FakeLocal()
        CastHub.start(act, CastHub.Kind.STREMIO, tv, CastHub.Media("http://a/film.mp4", "Film"), l2)
        fake.cb!!(CastHub.Result.Playing(tv)); idle()
        fake.cb!!(CastHub.Result.Ended(42_000, finished = false)); idle()
        assertEquals(42_000, l2.resumedAt); assertFalse(CastHub.casting)
        assertTrue(CastHub.lastMessage.orEmpty().contains(CastHub.BACK_LOCAL))
        CastHub.detach(act)
    }

    @Test fun stremioPlayerOffersCastAndStaysSilentWhileCasting() {
        fake.devices = listOf(tv)
        val i = StremioPlayerActivity.intent(ctx, "movie", "tt2", "Film", "", "tt2", "1080p",
            StremioStream("Wtyczka", "http://10.1.2.3:9", url = "http://10.1.2.3:9/film.mp4", headers = mapOf("Referer" to "http://r")))
        val c = Robolectric.buildActivity(StremioPlayerActivity::class.java, i).setup(); idle(400)
        val a = c.get()
        val icon = all(a.window.decorView).first { it.tag == PortraitPlayerUi.ICON_TAG + "cast" }
        assertEquals(View.VISIBLE, icon.visibility)
        val m = a.castMedia()!!
        assertEquals("http://10.1.2.3:9/film.mp4", m.url); assertEquals("Film", m.title); assertFalse(m.live); assertEquals("http://r", m.headers["Referer"])
        CastHub.start(a, CastHub.Kind.STREMIO, tv, m, a.castLocal)
        fake.cb!!(CastHub.Result.Playing(tv)); idle()
        assertFalse("lokalnie pauza", a.player!!.playWhenReady)
        c.pause().resume(); idle()
        assertFalse("powrót na ekran w trakcie przesyłania – bez lokalnego dźwięku", a.player!!.playWhenReady)
        CastHub.stopCasting(); idle()
        assertTrue("koniec przesyłania – gra tutaj", a.player!!.playWhenReady)
    }
}

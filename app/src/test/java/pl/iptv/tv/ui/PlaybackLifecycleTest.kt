package pl.iptv.tv.ui

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Cykl życia odtwarzacza: zamykanie połączeń przy zmianie kanału, pełne zwalnianie, odwieszanie i odbudowa. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackLifecycleTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        val f = File(ctx.filesDir, "life.m3u").apply { writeText((1..3).joinToString("", "#EXTM3U\n") {
            "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G\",Kanał $it\nhttp://127.0.0.1:9/live/$it.ts\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Playback.stop() }

    /** Strumień, który nigdy nie kończy odpowiedzi (jak zawieszony serwer IPTV). */
    private fun hangingServer() = MockWebServer().apply {
        enqueue(MockResponse().setBody("x".repeat(64)).setBodyDelay(60, TimeUnit.SECONDS)); start()
    }

    @Test fun closeInterruptsHangingStreamAndRejectsNewCalls() {
        val srv = hangingServer()
        try {
            val calls = Playback.StreamCalls(Playback.streamHttp)
            val err = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
            Thread {
                try { calls.newCall(okhttp3.Request.Builder().url(srv.url("/ts")).build()).execute().use { it.body!!.string() } }
                catch (t: Throwable) { err.set(t) } finally { done.countDown() }
            }.start()
            srv.takeRequest(5, TimeUnit.SECONDS)
            assertEquals(1, calls.open)
            val t0 = System.nanoTime()
            calls.close()
            assertTrue("przerwane od razu, nie po 20 s", done.await(3, TimeUnit.SECONDS))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
            assertTrue(err.get() is java.io.IOException)
            assertTrue(calls.closed)
            // po zamknięciu nowe żądania od razu anulowane (stare źródło nie otworzy już połączenia)
            assertTrue(calls.newCall(okhttp3.Request.Builder().url(srv.url("/x")).build()).isCanceled())
        } finally { runCatching { srv.shutdown() } }          // serwer śpi w opóźnieniu odpowiedzi – zamknięcie bez czekania
    }

    @Test fun cancelActiveKeepsGroupUsable() {
        val calls = Playback.StreamCalls(Playback.streamHttp)
        val c1 = calls.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/a").build())
        calls.cancelActive()
        assertTrue(c1.isCanceled())
        assertFalse(calls.closed)
        assertFalse(calls.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/b").build()).isCanceled())
    }

    @Test fun streamClientHasOwnDispatcherAndPool() {
        assertNotSame(Repository.http.dispatcher, Playback.streamHttp.dispatcher)
        assertNotSame(Repository.http.connectionPool, Playback.streamHttp.connectionPool)
        // martwy adres serwera (DNS z rotacją) kosztuje najwyżej 4 s, potem następny adres – nie 12 s zamrożonego obrazu
        assertEquals(4000, Playback.streamHttp.connectTimeoutMillis)
        assertTrue(Playback.streamHttp.retryOnConnectionFailure)
    }

    @Test fun deadServerAddressFallsBackQuickly() {
        // pierwszy adres nie odpowiada (pakiety giną), drugi działa – jak 2 z 12 adresów live7 u dostawcy
        val srv = MockWebServer().apply { enqueue(MockResponse().setBody("#EXTM3U")); start() }
        try {
            val dead = java.net.InetAddress.getByName("10.255.255.1")       // adres nieroutowalny – połączenie wisi
            val client = Playback.streamHttp.newBuilder()
                .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(dead, java.net.InetAddress.getByName(srv.hostName)) }).build()
            val t0 = System.nanoTime()
            val body = client.newCall(okhttp3.Request.Builder().url("http://iptv.test:${srv.port}/list.m3u8").build()).execute().use { it.body!!.string() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals("#EXTM3U", body)
            assertTrue("zapasowy adres po ~4 s, było $ms ms", ms < 7000)
        } finally { srv.shutdown() }
    }

    @Test fun channelSwitchClosesPreviousStreamConnections() {
        val chs = Repository.channels
        Playback.play(ctx, chs[0], chs[0].url); idle()
        val first = Playback::class.java.getDeclaredField("calls").apply { isAccessible = true }.get(Playback) as Playback.StreamCalls
        assertFalse(first.closed)
        Playback.play(ctx, chs[1], chs[1].url); idle()
        assertTrue("połączenia poprzedniego kanału zamknięte", first.closed)
        val second = Playback::class.java.getDeclaredField("calls").apply { isAccessible = true }.get(Playback) as Playback.StreamCalls
        assertNotSame(first, second); assertFalse(second.closed)
        // powrót na pierwszy kanał – nowe połączenia (nie te zamknięte)
        assertTrue(Playback.play(ctx, chs[0], chs[0].url)); idle()
        assertTrue(second.closed)
    }

    @Test fun stopReleasesPlayerConnectionsAndView() {
        val ch = Repository.channels[0]
        val view = PlayerView(ctx)
        Playback.play(ctx, ch, ch.url); Playback.attach(view); idle()
        val calls = Playback::class.java.getDeclaredField("calls").apply { isAccessible = true }.get(Playback) as Playback.StreamCalls
        assertNotNull(view.player)
        Playback.stop()
        assertNull(Playback.player); assertNull(Playback.url)
        assertNull("widok nie trzyma zwolnionego odtwarzacza", view.player)
        assertTrue(calls.closed)
        assertFalse(Playback.isPlayingNow)
    }

    @Test fun rebuildReplacesPlayerKeepsChannelViewAndListeners() {
        val ch = Repository.channels[1]
        val view = PlayerView(ctx)
        val states = mutableListOf<Int>()
        val l = object : Player.Listener { override fun onPlaybackStateChanged(s: Int) { states += s } }
        Playback.addListener(l)
        try {
            Playback.play(ctx, ch, ch.url); Playback.attach(view); idle()
            val old = Playback.player!!
            var changed: Any? = null
            Playback.onPlayerChanged = { changed = it }
            val before = Playback.rebuilds
            Playback.rebuild(ctx); idle()
            val now = Playback.player!!
            assertNotSame(old, now)
            assertSame(now, changed)
            assertEquals(before + 1, Playback.rebuilds)
            assertEquals(ch.url, Playback.url)                      // ten sam kanał
            assertSame(now, view.player)                            // obraz na nowym odtwarzaczu
            states.clear(); now.stop(); idle()
            assertTrue("słuchacz ekranu przeniesiony", states.contains(Player.STATE_IDLE))
        } finally { Playback.removeListener(l); Playback.onPlayerChanged = null }
    }

    @Test fun sourceErrorsRetryLongerWithGrowingDelay() {
        assertTrue(Playback.SOURCE_RETRIES >= 4)
        assertEquals(listOf(2000L, 4000L, 6000L, 8000L), (1..4).map { Playback.retryDelayMs(true, it) })
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 16000L), (1..5).map { Playback.retryDelayMs(false, it) })
        val ch = Repository.channels[0]
        Playback.play(ctx, ch, ch.url); idle()
        repeat(Playback.SOURCE_RETRIES) { Playback.simulateError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS); assertTrue(Playback.retryPending) }
        var fatal = false
        Playback.onFatal = { fatal = true }
        Playback.simulateError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertTrue("po wyczerpaniu prób – komunikat", fatal)
        Playback.onFatal = null
    }

    @Test fun frozenHardwareDecoderSwitchesToSoftwareForSession() {
        Prefs.playerEngine = Playback.ENGINE_HARDWARE; Prefs.hwAccel = true; Prefs.freezeFallback = true
        assertEquals(8000L, Playback.FREEZE_SWITCH_MS)
        assertEquals(0L, Playback.FREEZE_SWITCH_MS % Playback.WATCHDOG_MS)
        val ch = Repository.channels[0]
        val view = PlayerView(ctx)
        Playback.play(ctx, ch, ch.url); Playback.attach(view); idle()
        assertFalse(Playback.softwareDecoding)
        var told = 0; Playback.onDecoderFallback = { told++ }
        val old = Playback.player; val before = Playback.rebuilds
        Playback.simulateFreeze(); idle()
        assertTrue(Playback.sessionSoftware); assertTrue(Playback.softwareDecoding)
        assertEquals(1, told)
        assertEquals(before + 1, Playback.rebuilds)
        assertNotSame(old, Playback.player); assertSame(Playback.player, view.player)
        assertEquals(ch.url, Playback.url)                                       // ten sam kanał
        assertTrue(Playback.decoderInfo().contains("awaryjnie") || Playback.player?.videoFormat == null)
        assertTrue(Prefs.hwAccel); assertEquals(Playback.ENGINE_HARDWARE, Prefs.playerEngine)   // ustawienia bez zmian
        // kolejne zamrożenie na programowym – zwykłe odświeżenie, bez kolejnej zmiany dekodera
        Playback.simulateFreeze(); idle()
        assertEquals(1, told); assertEquals(before + 1, Playback.rebuilds)
        // ręczna zmiana silnika / wyjście – znowu dekoder z ustawień
        Playback.stop()
        assertFalse(Playback.sessionSoftware); assertFalse(Playback.softwareDecoding)
        Playback.onDecoderFallback = null
    }

    @Test fun freezeFallbackCanBeDisabled() {
        Prefs.playerEngine = Playback.ENGINE_HARDWARE; Prefs.hwAccel = true; Prefs.freezeFallback = false
        try {
            val ch = Repository.channels[1]
            Playback.play(ctx, ch, ch.url); idle()
            val before = Playback.rebuilds
            Playback.simulateFreeze(); idle()
            assertFalse(Playback.sessionSoftware); assertEquals(before, Playback.rebuilds)
        } finally { Prefs.freezeFallback = true }
    }

    @Test fun sameChannelRestartsWhenStalled() {
        val ch = Repository.channels[2]
        assertTrue(Playback.play(ctx, ch, ch.url))
        assertFalse("bez zawieszenia – bez restartu", Playback.play(ctx, ch, ch.url))
        assertFalse(Playback.stalledFor(Playback.SAME_URL_STALL_MS))
        assertTrue(Playback.SAME_URL_STALL_MS < Playback.STALL_MS)
    }
}

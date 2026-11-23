package pl.iptv.tv.ui

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/** (7.17) Silnik Live TV: bezpieczny bufor (minimum 2 s), adaptacja po przycięciach, ponawianie HTTP, AFR, opcje przełączania. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V717EngineTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun reset() { Prefs.lowPerf = false; Prefs.lowBufMinS = 15; Prefs.lowBufMaxS = 30; Prefs.zapDelayMs = 250; Prefs.cleanDecoderOnZap = false; Playback.stop() }

    @Test fun lowMinimumBufferNoLongerBreaksPlayer() {
        // „Bufor – minimum = 2 s”: progi z minimum, maksimum ≥ minimum + 5 s
        val d = LiveBuffer.safe(2_000, 5_000)
        assertEquals(LiveBuffer.Durations(2_000, 7_000, 900, 1_500), d)
        assertTrue(d.playMs <= d.minMs && d.rebufferMs <= d.minMs && d.minMs <= d.maxMs)
        assertEquals(1_500, LiveBuffer.safe(500, 1_000).minMs)
        // odtwarzacz w trybie słabszych urządzeń z minimum 2 s buduje się (wcześniej: wyjątek DefaultLoadControl – próg 5 s > minimum)
        Prefs.lowPerf = true; Prefs.lowBufMinS = 2; Prefs.lowBufMaxS = 5
        val p = Playback.buildPlayer(ctx, null)
        assertNotNull(Playback.liveLoad); assertEquals(1_500, Playback.liveLoad!!.baseRebufferMs)
        p.release()
    }

    @Test fun twoRebuffersIn25sRaiseThresholdForSession() {
        val d = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 7_000, 900, 1_500).build()
        val a = AdaptiveLiveLoadControl(d, 1_500)
        a.onPrepared(androidx.media3.exoplayer.analytics.PlayerId.UNSET)
        assertFalse(a.onRebuffer(0)); assertFalse("po 30 s – poza oknem", a.onRebuffer(30_000))
        assertTrue("dwa przycięcia w 25 s", a.onRebuffer(40_000))
        assertEquals(1_500, a.boostMs); assertEquals(3_000, a.rebufferMs)
        // wznowienie dopiero przy 3 s zapasu (Default wznowiłby przy 1,5 s)
        fun params(bufferedMs: Long) = LoadControl.Parameters(androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.common.Timeline.EMPTY,
            LoadControl.EMPTY_MEDIA_PERIOD_ID, 0, bufferedMs * 1000, 1f, true, true, androidx.media3.common.C.TIME_UNSET)
        assertFalse(a.shouldStartPlayback(params(2_000)))
        assertTrue(a.shouldStartPlayback(params(3_000)))
        // 3 s to nadal niski bufor – kolejne dwa przycięcia: 4,5 s; powyżej 3 s – bez dalszych podniesień
        assertFalse(a.onRebuffer(41_000)); assertTrue(a.onRebuffer(42_000)); assertEquals(4_500, a.rebufferMs)
        assertFalse(a.onRebuffer(43_000)); assertFalse(a.onRebuffer(44_000)); assertEquals(3_000, a.boostMs)
    }

    @Test fun streamClientRetriesOverloadedServer() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(429)); server.enqueue(MockResponse().setBody("ts"))
        server.start()
        try {
            val r = Playback.streamHttp.newCall(okhttp3.Request.Builder().url(server.url("/live/1.ts")).build()).execute()
            assertEquals(200, r.code); assertEquals("ts", r.body!!.string())
            assertEquals(3, server.requestCount)
            assertEquals(10_000, Playback.streamHttp.readTimeoutMillis)
            assertEquals(setOf(403, 429, 502, 503, 504), Playback.RETRY_CODES)
        } finally { server.shutdown() }
    }

    @Test fun autoFrameRatePicksMatchingRefresh() {
        val cur = AutoFrameRate.Mode(1, 1920, 1080, 60f)
        val modes = listOf(cur, AutoFrameRate.Mode(2, 1920, 1080, 50f), AutoFrameRate.Mode(3, 1920, 1080, 59.94f),
            AutoFrameRate.Mode(4, 1920, 1080, 23.976f), AutoFrameRate.Mode(5, 3840, 2160, 50f))
        assertEquals(2, AutoFrameRate.pick(modes, cur, 25f)?.id)
        assertEquals(2, AutoFrameRate.pick(modes, cur, 50f)?.id)
        assertEquals(4, AutoFrameRate.pick(modes, cur, 23.976f)?.id)
        assertEquals(3, AutoFrameRate.pick(modes, cur, 29.97f)?.id)
        assertNull(AutoFrameRate.pick(listOf(cur), cur, 25f))
        assertFalse("domyślnie wyłączone", Prefs.autoFrameRate)
    }

    @Test fun songsAlwaysStartFromBeginningVideosResume() {
        fun v(t: String, ch: String = "", dur: String = "PT3M40S") = pl.iptv.tv.data.YtVideo("abcdefghijk", t, ch, duration = dur)
        // piosenki (jak widok muzyczny) – bez wznawiania
        assertTrue(YtEngine.isSong(v("Dawid Podsiadło - Małomiasteczkowy (Official Video)")))
        assertTrue(YtEngine.isSong(v("Sanah – Szampan")))
        assertTrue(YtEngine.isSong(v("Bad Guy", "Billie Eilish - Topic")))
        // filmy, podcasty i długie nagrania – wznawiane jak dotąd
        assertFalse(YtEngine.isSong(v("Jak zrobić pizzę w domu", "Kuchnia", "PT14M")))
        assertFalse(YtEngine.isSong(v("Podcast o technologii #45", "Tech", "PT1H20M")))
    }

    @Test fun channelSwitchOptionsForEveryDevice() {
        Prefs.lowPerf = false
        assertEquals(250, Prefs.zapDelayMs)
        Prefs.lowPerf = true
        assertEquals(450, Prefs.zapDelayMs)
        Prefs.zapDelayMs = 350; assertEquals(350, Prefs.zapDelayMs)
        Prefs.lowPerf = false; Prefs.cleanDecoderOnZap = true
        assertTrue(Prefs.cleanDecoderOnZap)
    }
}

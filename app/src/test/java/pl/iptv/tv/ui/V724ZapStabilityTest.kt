package pl.iptv.tv.ui

import android.os.Looper
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * (7.24) Płynność po dłuższym oglądaniu w trybie szybkiego przełączania: kontrolowane ponowienie startu bez danych,
 * próg bufora po przycięciach tylko dla kanału, zamrożenia w oknie czasu, wygasanie programowego dla całej sesji.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V724ZapStabilityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        EngineProfiles.apply(EngineProfiles.Profile.FAST)
        val f = File(ctx.filesDir, "zap.m3u").apply { writeText((1..3).joinToString("", "#EXTM3U\n") {
            "#EXTINF:-1 tvg-id=\"z$it\" group-title=\"G\",Kanał $it\nhttp://127.0.0.1:9/live/$it.ts\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() { Playback.stop(); EngineProfiles.apply(EngineProfiles.Profile.AUTO) }

    @Test fun startRetryDecision() {
        assertTrue(Playback.startRetryDue(hasData = false, bufferedMs = 0, ready = false, errorHandling = false, done = 0))
        assertFalse("dane już są", Playback.startRetryDue(true, 0, false, false, 0))
        assertFalse("zapas rośnie", Playback.startRetryDue(false, 1_000, false, false, 0))
        assertFalse("kanał gra", Playback.startRetryDue(false, 0, true, false, 0))
        assertFalse("trwa obsługa błędu", Playback.startRetryDue(false, 0, false, true, 0))
        assertFalse("limit prób", Playback.startRetryDue(false, 0, false, false, Playback.START_RETRY_MAX))
    }

    /** Profile: wbudowane i zmodyfikowany przez użytkownika („Własny”). */
    private val profiles: List<Pair<String, () -> Unit>> = EngineProfiles.Profile.entries.map { p -> p.name to { EngineProfiles.apply(p) } } +
        ("CUSTOM" to { EngineProfiles.apply(EngineProfiles.Profile.STABLE); Prefs.zapDelayMs = 600; Prefs.bufMinS = 8; Prefs.cleanDecoderOnZap = false })

    private fun engineSnapshot(): Map<String, Any> = EngineProfiles.PROFILE_KEYS.associateWith<String, Any> { k -> Prefs.has(k) } +
        mapOf("zap" to Prefs.zapDelayMs, "buf" to Prefs.bufMinS, "clean" to Prefs.cleanDecoderOnZap, "async" to Prefs.asyncDecoder,
            "low" to Prefs.lowPerf, "engine" to Prefs.playerEngine, "hw" to Prefs.hwAccel, "label" to EngineProfiles.activeLabel())

    @Test fun silentChannelIsReconnectedInEveryProfileKeepingUserSettings() {
        for ((name, apply) in profiles) {
            Playback.stop(); apply()
            val before = engineSnapshot()
            silentChannelCheck(name)
            assertEquals("profil $name – ustawienia użytkownika bez zmian", before, engineSnapshot())
        }
        // zmodyfikowany profil zostaje „Własny (Zmodyfikowany)”
        assertEquals(EngineProfiles.CUSTOM, EngineProfiles.activeLabel())
    }

    @Test fun longZappingDoesNotAccumulateConnectionsOrPlayersInEveryProfile() {
        for ((name, apply) in profiles) {
            Playback.stop(); apply()
            val srv = MockWebServer().apply {
                dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody("x".repeat(64)).setBodyDelay(60, TimeUnit.SECONDS)
                }
                start()
            }
            try {
                val chans = (1..4).map { Channel("zz$it", "Z $it", srv.url("/live/$it.ts").toString(), "G") }
                Playback.play(ctx, chans[0], chans[0].url); Playback.attach(PlayerView(ctx)); idle()
                val p = Playback.player
                repeat(40) { i -> val c = chans[(i + 1) % chans.size]; Playback.play(ctx, c, c.url); idle(120) }
                waitFor { Playback.openCalls <= 1 }
                assertSame("$name: ten sam odtwarzacz (bez duplikatów)", p, Playback.player)
                assertEquals("$name: bez zaległych ponowień przy szybkim przełączaniu", 0, Playback.startRetries)
            } finally { Playback.stop(); runCatching { srv.shutdown() } }
        }
    }

    private fun silentChannelCheck(name: String) {
        val requests = AtomicInteger()
        val srv = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return MockResponse().setBody("x".repeat(64)).setBodyDelay(60, TimeUnit.SECONDS)   // nagłówki są, danych brak
                }
            }
            start()
        }
        try {
            val silent = Channel("silent", "Cichy", srv.url("/live/s.ts").toString(), "G")
            Playback.play(ctx, silent, silent.url); Playback.attach(PlayerView(ctx))
            waitFor { requests.get() >= 1 }
            assertEquals(name, 0, Playback.startRetries)
            // po START_RETRY_MS bez danych – ponowne połączenie (nowe żądanie), ten sam odtwarzacz
            val p = Playback.player
            idle(Playback.START_RETRY_MS + 200); waitFor { requests.get() >= 2 }
            assertEquals(name, 1, Playback.startRetries); assertSame(name, p, Playback.player)
            // najwyżej START_RETRY_MAX razy
            repeat(4) { idle(Playback.START_RETRY_MS); Thread.sleep(50); idle(50) }
            assertEquals(name, Playback.START_RETRY_MAX, Playback.startRetries)
            // szybkie przełączanie: nowy kanał anuluje zaplanowane ponowienie poprzedniego
            val total = Playback.startRetriesTotal
            val zap = (1..6).map { Channel("silent_z$it", "Cichy $it", srv.url("/live/z$it.ts").toString(), "G") }
            zap.forEach { c -> Playback.play(ctx, c, c.url); idle(2_000) }
            assertEquals("$name: szybkie przełączanie bez ponowień", total, Playback.startRetriesTotal)
            // zostajemy na kanale – jedno kontrolowane ponowienie
            idle(Playback.START_RETRY_MS); waitFor { Playback.startRetriesTotal == total + 1 }
        } finally { Playback.stop(); runCatching { srv.shutdown() } }
    }

    @Test fun rebufferBoostIsResetOnChannelChange() {
        val a = Repository.channels[0]; val b = Repository.channels[1]
        Playback.play(ctx, a, a.url); idle()
        val load = Playback.liveLoad!!
        assertFalse(load.onRebuffer(1_000)); assertTrue("dwa przycięcia – próg podniesiony", load.onRebuffer(2_000)); assertTrue(load.boostMs > 0)
        Playback.play(ctx, b, b.url); idle()
        assertEquals("nowy kanał – próg od podstawy", 0, Playback.liveLoad!!.boostMs)
        assertEquals(0, Playback.sessionRebufferBoostMs)
    }

    @Test fun freezesFarApartDoNotSwitchWholeSessionAndSessionSoftwareExpires() {
        Prefs.playerEngine = Playback.ENGINE_HARDWARE; Prefs.hwAccel = true; Prefs.freezeFallback = true
        val a = Repository.channels[0]; val b = Repository.channels[1]; val c = Repository.channels[2]
        val view = PlayerView(ctx)
        Playback.play(ctx, a, a.url); Playback.attach(view); idle()
        Playback.simulateFreeze(); idle()
        assertEquals(a.key, Playback.softwareChannel)
        // drugi fałszywy alarm po godzinie – tylko ten kanał, reszta sesji na sprzętowym
        Playback.ageFreezesForTest(Playback.SESSION_SOFTWARE_WINDOW_MS + 60_000)
        Playback.play(ctx, b, b.url); idle()
        Playback.simulateFreeze(); idle()
        assertEquals(b.key, Playback.softwareChannel)
        Playback.play(ctx, c, c.url); idle()
        assertFalse("kanał bez zamrożenia – sprzętowy", Playback.softwareDecoding)
        // dwa zamrożenia blisko siebie – cała sesja, ale tylko na [SESSION_SOFTWARE_TTL_MS]
        Playback.simulateFreeze(); idle()
        assertTrue(Playback.sessionSoftware); assertNull(Playback.softwareChannel)
        Playback.ageFreezesForTest(Playback.SESSION_SOFTWARE_TTL_MS + 60_000)
        val d = Channel("z4", "Kanał 4", "http://127.0.0.1:9/live/4.ts", "G")
        Playback.play(ctx, d, d.url); idle()
        assertFalse("po wygaśnięciu – znowu sprzętowy", Playback.softwareDecoding)
        Playback.play(ctx, a, a.url); idle()
        assertTrue("kanał, który zamarzł – nadal od razu programowy", Playback.softwareDecoding)
    }

    @Test fun defaultProfileRuleUnchanged() {
        // zasada z 7.24: aktualizacja nie nadpisuje zapisanego profilu
        Prefs.remove(*(EngineProfiles.PROFILE_KEYS + "engine_profile_init").toTypedArray())
        EngineProfiles.apply(EngineProfiles.Profile.STABLE)
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = false))
        assertEquals(EngineProfiles.Profile.STABLE, EngineProfiles.active())
    }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) { idle(50); Thread.sleep(20) }
        assertTrue("warunek nie spełniony w $ms ms", cond())
    }
}

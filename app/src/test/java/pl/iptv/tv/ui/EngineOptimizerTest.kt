package pl.iptv.tv.ui

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngine
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dobór silnika dla kanałów (ChannelEngineOptimizer): profile, kolejność prób, zapis w Room, oznaczenie niedziałających
 * (bez pętli testów), próby ściśle po kolei i zwalnianie odtwarzacza testowego, wykrycie kanału bez obrazu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EngineOptimizerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(100) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private val statuses = mutableListOf<ChannelEngineOptimizer.Status>()

    @Before fun setup() {
        Playback.stop()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
        StubeHistory.useDatabase(db)
        ChannelEngines.resetForTest(); ChannelEngineOptimizer.resetSession()
        Prefs.engineOptimizer = true; Prefs.engineProfilesVersion = ChannelEngines.PROFILES_VERSION
        ChannelEngineOptimizer.onStatus = { statuses += it }
    }

    @After fun tearDown() {
        ChannelEngineOptimizer.probeOverride = null; ChannelEngineOptimizer.onStatus = null
        Playback.stop(); ChannelEngineOptimizer.resetSession(); ChannelEngines.resetForTest()
        StubeHistory.useDatabase(null); db.close()
    }

    private fun ch(url: String, key: String = "k1") = Channel(key, "Kanał testowy", url, "G")

    @Test fun profilesEncodeAndCandidateOrder() {
        val p = EngineProfile(software = true, ffmpeg = true, async = 2, container = 1)
        assertEquals(p, EngineProfile.decode(p.encode()))
        assertEquals(EngineProfile(), EngineProfile.decode("śmieci"))
        assertTrue(p.label.contains("dekoder programowy") && p.label.contains("FFmpeg") && p.label.contains("HLS"))
        // adres bez rozszerzenia: najpierw HLS; .ts – bez wymuszania formatu; .m3u8 – na końcu zwykły TS
        val unknown = ChannelEngineOptimizer.candidates("http://srv/live/123", null)
        assertEquals(EngineProfile(container = 1), unknown.first())
        val ts = ChannelEngineOptimizer.candidates("http://srv/live/1.ts", null)
        assertTrue(ts.none { it.container != 0 })
        // 7.2: najpierw ustawienia bez pogorszenia obrazu, dekoder programowy na końcu i tylko przy błędzie dekodera
        assertEquals(EngineProfile(ffmpeg = true), ts.first())
        assertTrue(ts.last().software)
        assertTrue(ChannelEngineOptimizer.candidates("http://srv/1.ts", null, allowSoftware = false).none { it.software })
        assertTrue(EngineProfile(container = 2) in ChannelEngineOptimizer.candidates("http://srv/a.m3u8", null))
        // profil, który właśnie zawiódł, nie jest próbowany ponownie
        assertFalse(EngineProfile(software = true) in ChannelEngineOptimizer.candidates("http://srv/1.ts", EngineProfile(software = true)))
    }

    @Test fun roomPersistenceAndFailedFlag() = runBlocking {
        ChannelEngines.save("k1", "http://a/1.ts", EngineProfile(software = true))
        ChannelEngines.markFailed("k2", "http://a/2.ts")
        waitFor { runBlocking { db.channelEngines().all().size } == 2 }
        val rows = db.channelEngines().all().associateBy { it.channelKey }
        assertEquals("sw=1;ff=0;async=0;fmt=0", rows["k1"]!!.config); assertFalse(rows["k1"]!!.testedFailed)
        assertTrue(rows["k2"]!!.testedFailed)
        // ponowne uruchomienie aplikacji: z bazy do pamięci
        ChannelEngines.resetForTest(); ChannelEngines.load()
        assertEquals(EngineProfile(software = true), ChannelEngines.profileFor("k1"))
        assertNull(ChannelEngines.profileFor("k2"))
        assertTrue(ChannelEngines.blocked("k2"))
        assertFalse(ChannelEngines.blocked("k2", now = System.currentTimeMillis() + ChannelEngines.RETEST_MS + 1))   // po dobie – znowu test
        ChannelEngines.forget("k1"); assertNull(ChannelEngines.get("k1"))
    }

    @Test fun findsWorkingProfileSavesItAndRestartsChannelWithIt() {
        val c = ch("http://127.0.0.1:9/live/1.ts")
        Playback.play(ctx, c, c.url); idle()
        ChannelEngineOptimizer.probeOverride = { it.ffmpeg }                     // działa dopiero z dźwiękiem FFmpeg
        assertTrue(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "test"))
        waitFor { !ChannelEngineOptimizer.running && statuses.any { it is ChannelEngineOptimizer.Status.Found } }
        assertEquals(listOf(EngineProfile(ffmpeg = true)), ChannelEngineOptimizer.lastTried)
        assertEquals(EngineProfile(ffmpeg = true), ChannelEngines.profileFor("k1"))
        assertTrue(statuses.first() is ChannelEngineOptimizer.Status.Testing)
        // kanał od nowa z dobranym profilem (nowy odtwarzacz), ten sam adres
        waitFor { Playback.profile == EngineProfile(ffmpeg = true) }
        assertEquals(EngineProfile(ffmpeg = true), Playback.profile); assertEquals(c.url, Playback.url)
        waitFor { runBlocking { db.channelEngines().all().isNotEmpty() } }
        assertEquals("sw=0;ff=1;async=0;fmt=0", runBlocking { db.channelEngines().all() }.single().config)
        // kolejne włączenie: od razu z profilem, bez testów; ten sam kanał w sesji – bez drugiego doboru (bez pętli)
        Playback.stop(); idle()
        Playback.play(ctx, c, c.url); idle()
        assertEquals(EngineProfile(ffmpeg = true), Playback.profile)
        assertFalse(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "test"))
    }

    @Test fun allProfilesFailFlagsChannelSequentiallyWithoutLoop() {
        val c = ch("http://127.0.0.1:9/live/2.ts", "k2")
        Playback.play(ctx, c, c.url); idle()
        val active = AtomicInteger(); val maxActive = AtomicInteger()
        ChannelEngineOptimizer.probeOverride = {
            maxActive.set(maxOf(maxActive.get(), active.incrementAndGet())); delay(30); active.decrementAndGet(); false
        }
        assertTrue(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "test"))
        waitFor { !ChannelEngineOptimizer.running && statuses.any { it is ChannelEngineOptimizer.Status.Failed } }
        assertEquals(1, maxActive.get())                                                     // ściśle po kolei
        assertEquals(ChannelEngineOptimizer.candidates(c.url, null, allowSoftware = false), ChannelEngineOptimizer.lastTried)   // bez błędu dekodera – bez dekodera programowego
        assertTrue(ChannelEngines.get("k2")!!.testedFailed); assertTrue(ChannelEngines.blocked("k2"))
        // kanał gra dalej ze zwykłymi ustawieniami (ponowienia), bez nowego doboru – także w nowej sesji (flaga w bazie)
        waitFor { Playback.url == c.url && Playback.profile == null }
        ChannelEngineOptimizer.resetSession()
        assertFalse(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "test"))
        // wyłączony dobór – zwykła obsługa błędu
        Prefs.engineOptimizer = false
        assertFalse(ChannelEngineOptimizer.onStartFailed(ctx, ch("http://127.0.0.1:9/x.ts", "k3"), "http://127.0.0.1:9/x.ts", "test"))
        Prefs.engineOptimizer = true; Prefs.engineProfilesVersion = ChannelEngines.PROFILES_VERSION
    }

    @Test fun channelChangeCancelsProbing() {
        val c = ch("http://127.0.0.1:9/live/1.ts")
        Playback.play(ctx, c, c.url); idle()
        ChannelEngineOptimizer.probeOverride = { delay(10_000); true }
        assertTrue(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "test"))
        assertTrue(ChannelEngineOptimizer.running)
        val other = ch("http://127.0.0.1:9/live/3.ts", "k3")
        Playback.play(ctx, other, other.url); idle()
        assertFalse(ChannelEngineOptimizer.running)
        assertNull(ChannelEngines.get("k1"))                                                 // przerwany – bez zapisu
    }

    @Test fun realProbeReleasesPlayerAndReportsFailureQuickly() = runBlocking {
        // martwy adres: próba kończy się błędem (albo limitem) i zawsze zwolnieniem odtwarzacza testowego
        val c = ch("http://127.0.0.1:9/dead.ts")
        val ok = withTimeout(ChannelEngineOptimizer.PROBE_MS + 5_000) { ChannelEngineOptimizer.probe(ctx, c, c.url, EngineProfile(software = true)) }
        assertFalse(ok)
    }

    @Test fun slowOrSilentChannelIsNeverStoppedForProbing() {
        // 7.2: serwer odbiera połączenie, ale nie wysyła danych (łącze / serwer) – kanał NIE jest zatrzymywany na testy
        // (w 7.0 takie i wolno startujące kanały dostawały dekoder programowy – zacinanie we wszystkich motywach)
        val srv = MockWebServer().apply { enqueue(MockResponse().setBody("x".repeat(64)).setBodyDelay(60, TimeUnit.SECONDS)); start() }
        try {
            val c = ch(srv.url("/live/9.ts").toString(), "k9")
            ChannelEngineOptimizer.probeOverride = { true }
            Playback.play(ctx, c, c.url)
            repeat(40) { idle(500); Thread.sleep(5) }
            assertTrue(statuses.isEmpty()); assertFalse(ChannelEngineOptimizer.running)
            assertNull(ChannelEngines.get("k9")); assertNull(Playback.profile)
        } finally { Playback.stop(); runCatching { srv.shutdown() } }      // zawieszona odpowiedź – serwer zamykany „na siłę”
    }

    @Test fun startDecisionOnlyForStuckDecoder() {
        // dźwięk / obraz już gra – nic; brak formatu (brak danych) – czekanie, potem nic (łącze, nie silnik)
        assertEquals(Playback.StartDecision.NONE, Playback.startDecision(20_000, true, 5_000, playing = true))
        assertEquals(Playback.StartDecision.WAIT, Playback.startDecision(5_000, false, 0, false))
        assertEquals(Playback.StartDecision.NONE, Playback.startDecision(Playback.START_GIVE_UP_MS, false, 0, false))
        // format znany, zapas rośnie – jeszcze chwila; dopiero po START_STUCK_MS z pełnym zapasem: dekoder stoi → dobór
        assertEquals(Playback.StartDecision.WAIT, Playback.startDecision(6_000, true, 800, false))
        assertEquals(Playback.StartDecision.WAIT, Playback.startDecision(6_000, true, 3_000, false))
        assertEquals(Playback.StartDecision.OPTIMIZE, Playback.startDecision(Playback.START_STUCK_MS, true, 3_000, false))
        assertEquals(Playback.StartDecision.NONE, Playback.startDecision(Playback.START_GIVE_UP_MS, true, 800, false))
    }

    @Test fun profilesFromOlderRulesAreClearedOnce() = runBlocking {
        // profil zapisany przez 7.0 / 7.1 (np. dekoder programowy dla dobrego kanału) – usunięty przy pierwszym starcie 7.2
        db.channelEngines().upsert(ChannelEngine("old", "http://a/1.ts", EngineProfile(software = true).encode()))
        Prefs.engineProfilesVersion = 0
        ChannelEngines.resetForTest(); ChannelEngines.load()
        assertNull(ChannelEngines.get("old")); assertTrue(db.channelEngines().all().isEmpty())
        assertEquals(ChannelEngines.PROFILES_VERSION, Prefs.engineProfilesVersion)
        // kolejne starty – zapisane profile zostają
        db.channelEngines().upsert(ChannelEngine("new", "http://a/2.ts", EngineProfile(ffmpeg = true).encode()))
        ChannelEngines.resetForTest(); ChannelEngines.load()
        assertEquals(EngineProfile(ffmpeg = true), ChannelEngines.profileFor("new"))
    }

    @Test fun decoderErrorAllowsSoftwareAsLastResort() {
        val c = ch("http://127.0.0.1:9/live/5.ts", "k5")
        Playback.play(ctx, c, c.url); idle()
        ChannelEngineOptimizer.probeOverride = { it.software }
        assertTrue(ChannelEngineOptimizer.onStartFailed(ctx, c, c.url, "ERROR_CODE_DECODER_INIT_FAILED", decoderError = true))
        waitFor { !ChannelEngineOptimizer.running && statuses.any { it is ChannelEngineOptimizer.Status.Found } }
        // najpierw wszystkie ustawienia sprzętowe, dekoder programowy dopiero po nich
        val tried = ChannelEngineOptimizer.lastTried
        assertTrue(tried.last().software); assertTrue(tried.dropLast(1).none { it.software })
        assertEquals(EngineProfile(software = true), ChannelEngines.profileFor("k5"))
    }

    @Test fun singleFormatErrorRetriesQuicklyWithoutProbing() {
        // 7.3: jednorazowy błąd formatu – zwykłe, szybkie ponowienie; dobór dopiero, gdy błąd się powtórzy; dekoder – od razu
        assertFalse(Playback.optimizeOnError(decoder = false, retriesSoFar = 0))
        assertTrue(Playback.optimizeOnError(decoder = false, retriesSoFar = 1))
        assertTrue(Playback.optimizeOnError(decoder = true, retriesSoFar = 0))
        assertTrue(Playback.FORMAT_RETRY_MS < Playback.retryDelayMs(true, 1))
    }

    @Test fun containerOnlyProfileKeepsSamePlayer() {
        // 7.3: profil zmieniający tylko rodzaj strumienia nie przebudowuje odtwarzacza przy przełączaniu
        assertTrue(Playback.sameEngine(null, EngineProfile(container = 1)))
        assertTrue(Playback.sameEngine(EngineProfile(), null))
        assertFalse(Playback.sameEngine(null, EngineProfile(ffmpeg = true)))
        ChannelEngines.save("k7", "http://127.0.0.1:9/live/7", EngineProfile(container = 1))
        val a = ch("http://127.0.0.1:9/live/6.ts", "k6"); val b = ch("http://127.0.0.1:9/live/7", "k7")
        Playback.play(ctx, a, a.url); idle()
        val first = Playback.get(ctx)
        Playback.play(ctx, b, b.url); idle()
        assertSame(first, Playback.get(ctx))
        assertEquals(EngineProfile(container = 1), Playback.profile)
        // profil dekodera – nowy odtwarzacz
        ChannelEngines.save("k8", "http://127.0.0.1:9/live/8.ts", EngineProfile(ffmpeg = true))
        val d = ch("http://127.0.0.1:9/live/8.ts", "k8")
        Playback.play(ctx, d, d.url); idle()
        assertNotSame(first, Playback.get(ctx))
    }

    @Test fun entityKeepsUrlAndFlag() {
        val e = ChannelEngine("k", "http://u", EngineProfile(container = 1).encode())
        assertEquals(EngineProfile(container = 1), e.profile)
        assertNull(e.copy(testedFailed = true).profile)
    }
}

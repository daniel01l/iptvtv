package pl.iptv.tv.ui

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration

/**
 * 7.5: ręczne dopasowanie silnika (do 8 prób, zapis w Room, usuwanie), szkielet silników, „Następny odcinek” pod koniec,
 * polska wersja źródeł (lektor / dubbing / audio vs same napisy), domyślne przytrzymanie OK.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V75Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(150) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private val statuses = mutableListOf<ChannelEngineOptimizer.Status>()

    @Before fun setup() {
        Playback.stop()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }   // otwarta od razu – zamknięcie w tearDown nie trafia na otwieranie w tle (zakleszczenie Room)
        StubeHistory.useDatabase(db)
        ChannelEngines.resetForTest(); ChannelEngineOptimizer.resetSession()
        Prefs.engineOptimizer = true; Prefs.engineProfilesVersion = ChannelEngines.PROFILES_VERSION; Prefs.engineManualRunning = ""
        ChannelEngineOptimizer.onStatus = { statuses += it }
    }

    @After fun tearDown() {
        ChannelEngineOptimizer.measureOverride = null; ChannelEngineOptimizer.onStatus = null
        Playback.stop(); ChannelEngineOptimizer.resetSession(); ChannelEngines.resetForTest(); Prefs.engineManualRunning = ""
        StubeHistory.useDatabase(null); db.close()
    }

    private fun ch(url: String, key: String = "m1") = Channel(key, "Kanał zacinający", url, "G")
    private val smooth = ChannelEngineOptimizer.ProbeResult(true, 300, frames = 75, dropped = 0, rebuffers = 0, playedMs = 2_500, frameRate = 25f)
    private val choppy = ChannelEngineOptimizer.ProbeResult(true, 300, frames = 30, dropped = 40, rebuffers = 1, playedMs = 2_500, frameRate = 25f)

    @Test fun bufferProfileEncodesAndOldRowsStillDecode() {
        val p = EngineProfile(async = 2, buffer = 1)
        assertEquals(p, EngineProfile.decode(p.encode()))
        assertTrue(p.label.contains("duży bufor"))
        // zapis sprzed 7.5 (bez „buf=”) – bez zmian
        assertEquals(EngineProfile(ffmpeg = true), EngineProfile.decode("sw=0;ff=1;async=0;fmt=0"))
        assertEquals("sw=0;ff=1;async=0;fmt=0", EngineProfile(ffmpeg = true).encode())
    }

    @Test fun manualCandidatesAtMostEightWithoutCurrentAndSoftwareLast() {
        val l = ChannelEngineOptimizer.manualCandidates("http://srv/live/123", null)
        assertTrue(l.size in 2..ChannelEngineOptimizer.MANUAL_MAX)
        assertFalse("bez ustawień, które się zacinają", EngineProfile() in l)
        assertEquals(EngineProfile(buffer = 1), l.first())
        assertTrue(l.last().software); assertTrue(l.dropLast(2).none { it.software })
        // zapamiętany profil się zacina – najpierw ustawienia domyślne, bez niego samego
        val cur = EngineProfile(async = 2)
        val l2 = ChannelEngineOptimizer.manualCandidates("http://srv/1.ts", cur)
        assertEquals(EngineProfile(), l2.first()); assertFalse(cur in l2)
        assertTrue(ChannelEngineOptimizer.MANUAL_BUDGET_MS <= 20_000)
    }

    @Test fun manualFitStopsAtFirstSmoothSavesInRoomAndRestarts() {
        val c = ch("http://127.0.0.1:9/live/7.ts")
        Playback.play(ctx, c, c.url); idle()
        val tried = mutableListOf<EngineProfile>()
        ChannelEngineOptimizer.measureOverride = { p -> tried += p; if (p == EngineProfile(ffmpeg = true)) smooth else choppy }
        assertEquals(ChannelEngineOptimizer.ManualStart.STARTED, ChannelEngineOptimizer.startManual(ctx, c, c.url))
        assertEquals("trwa – blokada pętli awarii zapisana", c.key, Prefs.engineManualRunning)
        waitFor { !ChannelEngineOptimizer.running && statuses.any { it is ChannelEngineOptimizer.Status.Found } }
        // próby po kolei, do pierwszej płynnej; zapis w bazie, kanał od nowa z tym profilem
        assertEquals(EngineProfile(ffmpeg = true), tried.last())
        assertEquals(EngineProfile(ffmpeg = true), ChannelEngines.profileFor(c.key))
        assertEquals("", Prefs.engineManualRunning)
        waitFor { Playback.profile == EngineProfile(ffmpeg = true) }
        assertEquals(EngineProfile(ffmpeg = true), Playback.profile)
        waitFor { kotlinx.coroutines.runBlocking { db.channelEngines().all() }.isNotEmpty() }
        assertEquals(EngineProfile(ffmpeg = true), kotlinx.coroutines.runBlocking { db.channelEngines().all() }.first().profile)
        // usunięcie dopasowanych ustawień – znowu domyślne
        ChannelEngineOptimizer.resetChannel(c.key, c.url)
        assertNull(ChannelEngines.get(c.key))
    }

    @Test fun manualFitPicksBestWhenNothingSmoothAndHasSessionLimitAndCrashGuard() {
        val c = ch("http://127.0.0.1:9/live/8.ts", "m2")
        Playback.play(ctx, c, c.url); idle()
        ChannelEngineOptimizer.measureOverride = { p ->
            if (p == EngineProfile(async = 2, buffer = 1)) choppy.copy(rebuffers = 0, dropped = 5) else choppy
        }
        ChannelEngineOptimizer.startManual(ctx, c, c.url)
        waitFor { !ChannelEngineOptimizer.running && statuses.isNotEmpty() && statuses.last() !is ChannelEngineOptimizer.Status.Testing }
        assertEquals("najlepszy z pomiarów", EngineProfile(async = 2, buffer = 1), ChannelEngines.profileFor(c.key))
        assertTrue(ChannelEngineOptimizer.lastTried.size <= ChannelEngineOptimizer.MANUAL_MAX)
        // limit w sesji – bez pętli doborów
        ChannelEngineOptimizer.measureOverride = { choppy }
        ChannelEngineOptimizer.startManual(ctx, c, c.url)
        waitFor { !ChannelEngineOptimizer.running }
        assertEquals(ChannelEngineOptimizer.ManualStart.LIMIT, ChannelEngineOptimizer.startManual(ctx, c, c.url))
        // aplikacja przerwana w trakcie dopasowania tego kanału – bez powtórki (ochrona słabszych TV przed pętlą awarii)
        val d = ch("http://127.0.0.1:9/live/9.ts", "m3")
        Prefs.engineManualRunning = d.key
        assertEquals(ChannelEngineOptimizer.ManualStart.CRASHED, ChannelEngineOptimizer.startManual(ctx, d, d.url))
        assertEquals("", Prefs.engineManualRunning)
    }

    @Test fun probeScoreAndSmoothness() {
        assertTrue(smooth.smooth); assertFalse(choppy.smooth)
        assertTrue(smooth.score > choppy.score)
        assertFalse(ChannelEngineOptimizer.ProbeResult(false).smooth)
    }

    @Test fun longOkOpensIconMenuByDefault() {
        Prefs.remove("ok_long2")
        assertEquals(1, Prefs.okLongAction)
    }

    @Test fun engineSkeletonFallbackOnlyWithLibraryAndExhaustedMedia3() {
        assertTrue(PlaybackEngines.media3.available)
        assertFalse("LibVLC nie jest dołączony", PlaybackEngines.libVlc.available)
        assertNull(PlaybackEngines.fallbackFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, media3Exhausted = true))
        val fakeVlc = object : ChannelPlaybackEngine { override val kind = EngineKind.LIBVLC; override val available = true }
        val engines = listOf(PlaybackEngines.media3, fakeVlc)
        assertSame(fakeVlc, PlaybackEngines.fallbackFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, true, engines))
        assertNull(PlaybackEngines.fallbackFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, false, engines))
        assertNull(PlaybackEngines.fallbackFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, true, engines))
    }

    @Test fun nextEpisodeOnlyInLastFourMinutes() {
        val d = 45 * 60_000L
        assertFalse(StremioPlayerActivity.nextDue(d, 10 * 60_000L))
        assertFalse(StremioPlayerActivity.nextDue(d, d - 5 * 60_000L))
        assertTrue(StremioPlayerActivity.nextDue(d, d - 240_000L))
        assertTrue(StremioPlayerActivity.nextDue(d, d - 30_000L))
        assertFalse("koniec", StremioPlayerActivity.nextDue(d, d))
        assertFalse("nieznana długość", StremioPlayerActivity.nextDue(androidx.media3.common.C.TIME_UNSET, 0))
        assertFalse(StremioPlayerActivity.nextDue(0, 0))
    }

    @Test fun polishVoiceVersusSubtitlesByWholeTokens() {
        fun l(s: String) = StremioStreams.languages(s)
        // lektor / dubbing / dźwięk PL
        assertTrue("Lektor PL" in l("Film.2024.1080p.WEB-DL.Lektor.PL.mkv"))
        assertTrue("Dubbing PL" in l("Film 2024 PLDUB 1080p"))
        assertTrue("Dubbing PL" in l("Film 2024 Polski Dubbing 720p"))
        assertTrue("Audio PL" in l("Film 2024 1080p Audio PL"))
        // same napisy PL – nie lektor ani dubbing
        for (s in listOf("Film.2024.1080p.PLSUB.mkv", "Film 2024 napisy PL", "Film 2024 SUB PL 1080p", "Film.2024.Subs.POL.1080p")) {
            val t = l(s)
            assertTrue(s, "Napisy PL" in t)
            assertFalse(s, t.any { it == "Lektor PL" || it == "Dubbing PL" || it == "Audio PL" })
            assertFalse(s, "PL" in t)
        }
        // „pl” wewnątrz słów / przypadkowe ciągi – bez oznaczenia
        for (s in listOf("Complete.Series.1080p.Apple.TV", "Explosion.2024.PLAYlist.1080p", "MPL.Example.720p", "Film 2024 Multiplex")) assertTrue(s, l(s).none { it.endsWith("PL") })
        // samo „PL” bez kontekstu – niepewne „PL” (nie lektor)
        assertEquals(listOf("PL"), l("Film 2024 PL 1080p").filter { it.endsWith("PL") })
        // dubbing innego języka obok – nie PL
        assertTrue(l("Film 2024 ITA Dubbing 1080p").none { it.endsWith("PL") })
        // kolejność: lektor przed napisami
        val lektor = pl.iptv.tv.data.StremioStream("a", "u", title = "Film.Lektor.PL.1080p", url = "http://x/1")
        val subs = pl.iptv.tv.data.StremioStream("a", "u", title = "Film.PLSUB.1080p", url = "http://x/2")
        assertEquals(0, StremioStreams.polishRank(lektor)); assertEquals(1, StremioStreams.polishRank(subs))
        assertFalse(subs.claimsPolishAudio)
    }
}

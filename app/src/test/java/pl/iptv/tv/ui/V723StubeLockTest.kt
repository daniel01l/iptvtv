package pl.iptv.tv.ui

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 7.23 – STube na telefonie: po zablokowaniu ekranu kolejka gra dalej w tle (1–3 utwory), a odblokowanie NIE uruchamia ponownie
 * utworu z chwili blokady. Bieżący utwór i kursor listy „Następne” zawsze z silnika ([YtEngine]), w pionie i w poziomie.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723StubeLockTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    /** Liczba ekstrakcji (wczytań) na film. */
    private val resolves = ConcurrentHashMap<String, AtomicInteger>()
    private val songs = listOf("songAAAAAAA", "songBBBBBBB", "songCCCCCCC", "songDDDDDDD")
    private fun title(id: String) = "Wykonawca – Utwór ${id.last()} (Official Audio)"
    private fun song(id: String) = YtVideo(id, title(id), "Wykonawca - Topic", duration = "PT3M")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            // strumień „ładuje się” (bez odpowiedzi) – bez błędu odtwarzania, który w tle zatrzymałby silnik
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]")
                else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        } }
        server.start()
        pl.iptv.tv.data.SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.orientation = 2; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.ytLyrics = false; Prefs.ytMusicView = true
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtStreams.resolverOverride = { id ->
            resolves.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            YtPlayable.Muxed(title(id), 180, YtCandidate(server.url("/$id.mp4").toString(), 360), emptyList(), emptyList(), channel = "Wykonawca - Topic")
        }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.clearCache()
        Prefs.orientation = 0; Prefs.ytMusicView = false; MobilePortraitScaffold.forced = null; TvResolution.resetForTest()
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun lockAdvanceUnlock(portrait: Boolean) {
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, song(songs[0]))).setup().visible()
        val a = c.get()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == songs[0] }
        idle(300)
        assertEquals(portrait, a.portraitActive)
        // blokada ekranu: onPause + onStop – dźwięk gra dalej w usłudze, słuchacz ekranu odpięty
        c.pause().stop(); idle()
        // kolejka w tle: trzy kolejne utwory (jak YtEngine.finished → load następnego)
        for (id in songs.drop(1)) {
            YtEngine.load(ctx, song(id), force = true)
            waitFor { YtEngine.playable != null && YtEngine.video?.id == id }
        }
        val playerBefore = YtEngine.player
        val loadsBefore = songs.associateWith { resolves[it]?.get() ?: 0 }
        // odblokowanie: onStart + onResume
        c.start().resume(); idle(500)
        // utwór z chwili blokady NIE jest wczytywany ponownie, gra dalej ostatni z kolejki
        assertEquals(songs.last(), YtEngine.video?.id)
        assertEquals(loadsBefore, songs.associateWith { resolves[it]?.get() ?: 0 })
        assertEquals(1, resolves[songs[0]]!!.get())
        assertSame(playerBefore, YtEngine.player)
        // ekran pokazuje faktycznie odtwarzany utwór (intent, tytuł, panel pionowy, widok muzyczny)
        assertEquals(songs.last(), a.intent.getStringExtra(YtPlayerActivity.EXTRA_ID))
        assertEquals(1, a.followedEngine)
        if (portrait) assertEquals(title(songs.last()), a.portraitPanel!!.titleView.text.toString())
        // ponowne wejście bez zmiany utworu – nic się nie dzieje
        c.pause().stop().start().resume(); idle(300)
        assertEquals(songs.last(), YtEngine.video?.id); assertEquals(1, resolves[songs[0]]!!.get()); assertEquals(1, a.followedEngine)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitUnlockFollowsBackgroundQueueWithoutRestartingLockedSong() = lockAdvanceUnlock(portrait = true)

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun landscapeUnlockFollowsBackgroundQueueWithoutRestartingLockedSong() = lockAdvanceUnlock(portrait = false)

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun playbackEndedWhileLockedClosesScreenInsteadOfRestarting() {
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, song(songs[0]))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == songs[0] }
        c.pause().stop(); idle()
        YtEngine.stop(); idle()                         // koniec kolejki / ✖ w powiadomieniu w tle
        c.start().resume(); idle(500)
        assertNull(YtEngine.video)
        assertEquals(1, resolves[songs[0]]!!.get())
        assertTrue(c.get().isFinishing)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun explicitlyChosenSongStillLoadsOverEngine() {
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, song(songs[0]))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == songs[0] }
        // nowy film wybrany z zewnątrz (STube, telefon) – nadal wczytywany
        c.newIntent(YtPlayerActivity.intent(ctx, song(songs[2]))); idle()
        waitFor { YtEngine.video?.id == songs[2] && YtEngine.playable != null }
        assertEquals(songs[2], YtEngine.video?.id)
        assertEquals(songs[2], c.get().intent.getStringExtra(YtPlayerActivity.EXTRA_ID))
        c.pause().stop().destroy()
    }
}

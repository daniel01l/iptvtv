package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.room.Room
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
import pl.iptv.tv.data.*
import java.time.Duration

/**
 * (7.14) STube: gust słuchacza (historia + odrzucone utwory), dopasowanie kolejki po pominięciu, ► usuwa utwór z listy
 * „Następne w kolejce”, powrót z ikon na kafelek „Tekst”.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V714Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        } }
        server.start()
        YtLyrics.apiBase = server.url("/lyrics").toString().trimEnd('/'); YtLyrics.fallbackBase = ""
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.ytMusicView = true; Prefs.ytLyrics = true; Prefs.ytDislikes = ""
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = emptyList<YtVideo>()
            override fun related(id: String) = emptyList<YtVideo>()
        }
    }

    @After fun tearDown() {
        Prefs.ytDislikes = ""
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        YtLyrics.apiBase = "https://lrclib.net/api"; YtLyrics.fallbackBase = "https://api.lyrics.ovh/v1"
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun vid(id: String, title: String, ch: String) = YtVideo(id, title, ch, duration = "PT3M")
    private fun q(id: String, title: String, ch: String) = QueueItem.of(vid(id, title, ch))
    private fun wp(id: String, title: String, ch: String, at: Long) = WatchProgress(id, title, ch, "", 180_000, 180_000, at)

    // ---------------- czyste funkcje ----------------

    @Test fun skipCountsOnlyBeforeHalfOfTheTrack() {
        assertTrue(StubeTaste.isSkip(20_000, 200_000)); assertFalse(StubeTaste.isSkip(150_000, 200_000))
        assertTrue(StubeTaste.isSkip(30_000, 0)); assertFalse(StubeTaste.isSkip(120_000, 0))
    }

    @Test fun skippedSongShrinksSimilarOnesAndKeepsTheRest() {
        val skipped = vid("s", "Metallica – One (Official Video)", "Metallica - Topic")
        val queue = listOf(
            q("a", "Metallica – Fuel", "Metallica - Topic"),            // ten sam wykonawca → znika
            q("b", "Sabaton – Primo Victoria (metal)", "Sabaton"),       // ten sam gatunek (metal) → co drugi
            q("c", "Ed Sheeran – Shape of You (pop)", "Ed Sheeran"),     // inny → zostaje
            q("d", "Slayer – Raining Blood (metal)", "Slayer"),
            q("e", "Taylor Swift – Lover (pop)", "Taylor Swift"),
            q("f", "Another band – Song (metal)", "Band F"),
        )
        val out = StubeTaste.afterSkip(queue, skipped).map { it.ref }
        assertFalse("a" in out)
        assertEquals(listOf("c", "e"), out.take(2))                      // niepodobne nietknięte, na początku
        assertTrue("c" in out && "e" in out)                             // inne zostają widoczne
        assertEquals(2, out.count { it in setOf("b", "d", "f") })        // z trzech metalowych zostają dwa (co drugi)
    }

    @Test fun queueIsNeverEmptiedBySkippingTheOnlySimilarSongs() {
        val skipped = vid("s", "Metallica – One", "Metallica - Topic")
        val queue = listOf(q("a", "Metallica – Fuel", "Metallica - Topic"), q("b", "Metallica – Nothing Else Matters", "Metallica - Topic"))
        assertTrue(StubeTaste.afterSkip(queue, skipped).isNotEmpty())
    }

    @Test fun dislikesAndHistoryShapeSuggestions() {
        val hist = listOf(wp("h1", "Dua Lipa – Levitating (pop)", "Dua Lipa", 1), wp("h2", "Ed Sheeran – Perfect (pop)", "Ed Sheeran", 2),
            wp("h3", "Taylor Swift – Lover (pop)", "Taylor Swift", 3))
        val pop = vid("p", "Ariana Grande – 7 rings (pop)", "Ariana Grande")
        val metal = vid("m", "Sabaton – Primo Victoria (metal)", "Sabaton")
        val unknown = vid("u", "Ktoś – Piosenka", "Nieznany")
        val t = StubeTaste.profile(hist)
        val ranked = StubeTaste.rank(listOf(unknown, metal, pop), t, "Dua Lipa – Houdini (pop)", "Dua Lipa")
        assertEquals("p", ranked.first().id)
        // odrzucenie wykonawcy dwa razy → znika z propozycji; pominięty utwór nigdy
        StubeTaste.recordDislike("x1", "Sabaton – A (metal)", "Sabaton"); StubeTaste.recordDislike("x2", "Sabaton – B (metal)", "Sabaton")
        val t2 = StubeTaste.profile(hist)
        assertTrue(StubeTaste.rank(listOf(metal, pop), t2).none { it.id == "m" })
        assertEquals(-100.0, StubeTaste.score(vid("x1", "Coś innego", "Ktoś"), t2), 0.0)
        // wysłuchany do końca – kara łagodnieje
        StubeTaste.forgive("Sabaton – C (metal)", "Sabaton"); StubeTaste.forgive("Sabaton – C (metal)", "Sabaton")
        assertNull(StubeTaste.profile(hist).artistDislikes["sabaton"])
    }

    @Test fun suggestWithoutTasteKeepsPreviousBehaviour() {
        val rel = (1..5).map { vid("r$it", "Wykonawca $it – Utwór $it (Official Audio)", "Wykonawca $it") }
        assertEquals(StubeMixes.suggest(rel, "cur", "Artist – Song (Official Audio)", "Artist - Topic").map { it.id },
            StubeMixes.suggest(rel, "cur", "Artist – Song (Official Audio)", "Artist - Topic", emptySet(), StubeTaste.EMPTY).map { it.id })
    }

    // ---------------- ekran muzyczny ----------------

    private fun player(title: String, related: List<YtVideo>, channel: String = "Artist - Topic", id: String = "abcdefghijk"): YtPlayerActivity {
        YtStreams.resolverOverride = { YtPlayable.Muxed(title, 300, YtCandidate(server.url("/v.mp4").toString(), 360), emptyList(), related, channel = channel) }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, title, channel))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == id && c.get().player != null }
        idle(300)
        return c.get()
    }

    private fun key(a: YtPlayerActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle()
    }

    @Test fun rightArrowRemovesFocusedSongAndKeepsFocusOnNeighbour() {
        runBlocking { listOf(q("q1", "Ed Sheeran – Perfect (pop)", "Ed Sheeran"), q("q2", "Sabaton – Primo (metal)", "Sabaton"),
            q("q3", "Dua Lipa – Levitating (pop)", "Dua Lipa")).forEach { StubeQueue.add(it) } }
        val a = player("Artist – Song", emptyList())
        val s = a.stage
        s.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        s.layout(0, 0, 960, 540); idle()
        waitFor { s.focusedListPos() == 0 }
        assertEquals(4, s.listView.adapter!!.itemCount)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)                       // na bieżącym utworze – nic nie usuwa
        assertEquals(4, s.listView.adapter!!.itemCount)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor { s.focusedListPos() == 2 }                       // q2 (Sabaton)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor { StubeQueue.cached.none { it.ref == "q2" } }
        assertTrue(StubeQueue.cached.none { it.ref == "q2" })
        assertTrue(Prefs.ytDislikes.contains("a:sabaton"))        // odrzucony wykonawca zapamiętany
        waitFor { s.focusedListPos() >= 1 }
        assertTrue("fokus nie zginął", s.focusedListPos() >= 1)
        // dalej ► usuwa kolejne; fokus nigdy nie ginie
        repeat(3) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertNotNull(s.findFocus()); assertTrue(s.focusedListPos() >= 0)
    }

    @Test fun skippingASongTrimsSimilarOnesInTheQueueInBackground() {
        runBlocking { listOf(q("q1", "Metallica – Fuel (metal)", "Metallica - Topic"), q("q2", "Dua Lipa – Levitating (pop)", "Dua Lipa"),
            q("q3", "Ed Sheeran – Perfect (pop)", "Ed Sheeran")).forEach { StubeQueue.add(it) } }
        player("Metallica – One (Official Video)", emptyList(), "Metallica - Topic")
        YtEngine.next()                                           // pominięcie na początku utworu
        waitFor { YtEngine.video?.id != "abcdefghijk" }
        assertTrue(Prefs.ytDislikes.contains("a:metallica"))
        assertTrue(StubeQueue.cached.none { it.ref == "q1" })     // ten sam wykonawca zniknął
        assertEquals("q2", YtEngine.video?.id)                    // następny jest niepodobny
    }
}

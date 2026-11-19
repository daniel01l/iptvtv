package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeMixes
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtLyrics
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/**
 * (7.9) STube: pasek ikon i pigułki biblioteki, kwadratowe okładki, dwustrefowy widok muzyczny odtwarzacza (okładka, sterowanie,
 * „Następne w kolejce”, tekst), rekomendacje z wielu wykonawców i playlisty 100+ otwierane od razu w odtwarzaczu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V79Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/lyrics/get") -> MockResponse().setBody(JSONObject().put("plainLyrics", "Pierwsza linijka\nDruga linijka").toString())
                request.path!!.startsWith("/sb/") -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(404)
            }
        } }
        server.start()
        YtLyrics.apiBase = server.url("/lyrics").toString().trimEnd('/')
        pl.iptv.tv.data.SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.ytMusicView = true; Prefs.ytLyrics = true
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        // wyniki: wielu wykonawców (kanały), zawsze nowe identyfikatory
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..6).map { YtVideo("k$id-$it", "Kiosk $it", "K$it", duration = "PT3M") }
            override fun search(q: String) = (1..20).map { YtVideo("s${q.hashCode()}-$it", "$q wynik $it", "Artysta ${it % 7}", duration = "PT3M") }
            override fun related(id: String) = (1..10).map { YtVideo("r$id-$it", "Po $id $it", "Inny $it", duration = "PT3M") }
        }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        YtLyrics.apiBase = "https://lrclib.net/api"
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- rekomendacje ----------------

    private fun v(id: String, artist: String) = YtVideo(id, "T $id", artist, duration = "PT3M")

    @Test fun diversifyPutsDifferentArtistsFirstAndStillFillsTheTarget() {
        val one = (1..30).map { v("a$it", "Jedyny Artysta") }
        val many = listOf(v("b1", "B"), v("c1", "C"), v("d1", "D"), v("b2", "B"), v("b3", "B"), v("c2", "C"))
        val out = StubeMixes.diversify(one + many, cap = 2, target = 10)
        // pierwsza runda: po jednym utworze każdego wykonawcy – jeden artysta nie otwiera całej listy
        assertEquals(listOf("Jedyny Artysta", "B", "C", "D"), out.take(4).map { it.channel })
        // przy małej liczbie wykonawców lista nadal dobija do celu (kolejne rundy ponad limit)
        val only = StubeMixes.diversify((1..30).map { v("x$it", "Sam") }, cap = 2, target = 10)
        assertTrue(only.size >= 10)
        // limit na wykonawcę po osiągnięciu celu
        val capped = StubeMixes.diversify((1..30).map { v("y$it", "A${it % 3}") }, cap = 2, target = 4)
        assertTrue(capped.groupBy { it.channel }.values.all { it.size <= 2 })
        assertEquals("kanal", StubeMixes.artistKey("Kanal - Topic").let { "kanal" })
        assertEquals("artysta", StubeMixes.artistKey("Artysta - Topic"))
    }

    private fun wp(id: String, title: String, channel: String, at: Long) = WatchProgress(id, title, channel, "t-$id", 200_000, 200_000, at)

    @Test fun mixesHaveAtLeast100TracksFromManyArtistsEvenForOneArtistListener() {
        val t = System.currentTimeMillis()
        // słuchacz jednego wykonawcy (rock) – miks nie może składać się tylko z niego
        val h = (1..6).map { wp("p$it", "Jedna Kapela – Rock Piosenka $it (Official Video)", "Jedna Kapela", t - it * 1000L) }
        val mixes = StubeMixes.build(h, t)
        assertTrue(mixes.all { it.fill.isNotEmpty() })
        assertTrue(mixes.first { it.id == "fav" }.seeds.isNotEmpty())            // zalążki: propozycje innych artystów
        val fav = mixes.first { it.id == "fav" }
        val vids = runBlocking { StubeMixes.videos(fav, watchedIds = emptySet()) }
        assertTrue("było ${vids.size}", vids.size >= StubeMixes.MIN_PLAYLIST)
        assertEquals(vids.size, vids.map { it.id }.distinct().size)
        assertTrue(vids.map { StubeMixes.artistKey(it.channel) }.distinct().size >= 7)
        // gatunek rock – styl z historii w zapytaniach uzupełniających
        assertTrue(fav.fill.any { it.contains("rock") })
        val genre = runBlocking { StubeMixes.videos(mixes.first { it.id == "g:rock" }, watchedIds = emptySet()) }
        assertTrue(genre.size >= StubeMixes.MIN_PLAYLIST)
        assertTrue(StubeMixes.expand(listOf("rock mix")).size > 5)
    }

    @Test fun mixTileOpensDirectlyInPlayerAndFillsQueue() {
        val t = System.currentTimeMillis()
        runBlocking { (1..4).forEach { StubeHistory.flush(wp("h$it", "Zespół – Rock Hit $it (Official Video)", "Zespół", t - it * 1000L)) } }
        YtStreams.resolverOverride = { YtPlayable.Muxed("x", 300, YtCandidate(server.url("/v.mp4").toString(), 360), emptyList(), emptyList(), channel = "Z") }
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        waitFor { a.rows.firstOrNull { it.id == STubeScreen.ROW_MIXES }?.tiles?.isNotEmpty() == true }
        val row = a.rows.first { it.id == STubeScreen.ROW_MIXES }
        assertTrue(row.square)                                              // okładki kwadratowe
        row.adapter.onClick(row.tiles.first())
        waitFor { shadowOf(a).peekNextStartedActivity() != null }
        val started = shadowOf(a).peekNextStartedActivity()
        assertEquals(YtPlayerActivity::class.java.name, started.component?.className)   // od razu odtwarzacz, bez rzędu pośredniego
        assertNull(a.playlistRow)
        waitFor { StubeQueue.cached.size >= StubeMixes.MIN_PLAYLIST - 1 }
        assertTrue("kolejka ${StubeQueue.cached.size}", StubeQueue.cached.size >= StubeMixes.MIN_PLAYLIST - 1)
    }

    // ---------------- ekran STube ----------------

    @Test fun railChipsAndRounding() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        waitFor { a.rows.isNotEmpty() && a.rows.none { it.loading } }
        for (id in listOf(R.id.stAccount, R.id.stSearch, R.id.stHome, R.id.stLib, R.id.stPhone, R.id.stSmartTube, R.id.stRefresh, R.id.stClose))
            assertNotNull(a.findViewById<View>(id).contentDescription ?: a.findViewById<View>(id))
        // pasek ikon pionowy z lewej, treść obok
        val rail = a.findViewById<android.widget.LinearLayout>(R.id.stRail)
        assertEquals(android.widget.LinearLayout.VERTICAL, rail.orientation)
        // pigułki: Start, Ostatnie, Playlisty, Na później (bez konta)
        val labels = a.chipViews.map { (it as android.widget.TextView).text.toString() }
        assertTrue(labels.toString(), labels.containsAll(listOf("Start", "Ostatnie", "Playlisty", "Na później")))
        a.chipViews.first { (it as android.widget.TextView).text == "Playlisty" }.performClick(); idle()
        assertTrue(a.rows.getOrNull(a.rows.indexOfFirst { it.id == STubeScreen.ROW_LIBRARY }) != null)
        // „Na później” otwiera listę pod biblioteką
        a.chipViews.first { (it as android.widget.TextView).text == "Na później" }.performClick(); idle()
        assertEquals(STubeScreen.PL_LATER, a.playlistRow?.source?.id)
        // muzyka i biblioteka – okładki kwadratowe, reszta 16:9
        assertTrue(a.rows.filter { it.id in STubeScreen.SQUARE_ROWS }.all { it.square })
        assertTrue(a.rows.filter { it.id == STubeScreen.ROW_TRENDING }.none { it.square })
    }

    // ---------------- odtwarzacz: widok muzyczny ----------------

    private fun player(title: String, related: List<YtVideo>, channel: String = "Artist - Topic", id: String = "abcdefghijk"): YtPlayerActivity {
        YtStreams.resolverOverride = { YtPlayable.Muxed(title, 300, YtCandidate(server.url("/v.mp4").toString(), 360), emptyList(), related, channel = channel) }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, title, channel))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == id && c.get().player != null }
        idle(300)
        return c.get()
    }

    @Test fun musicStageHasTwoZonesWithQueueAndLyrics() {
        val rel = (1..3).map { YtVideo("rel$it", "Utwór $it", "Wykonawca $it", duration = "PT3M") }
        val a = player("Artist – Song (Official Music Video)", rel)
        assertTrue(a.stageWanted); assertTrue(a.stageShown)
        val s = a.stage
        assertEquals("Artist – Song (Official Music Video)", s.titleView.text.toString()); assertEquals("Artist", s.artistView.text.toString())
        // okładka kwadratowa
        s.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        assertEquals(s.cover.layoutParams.width, s.cover.layoutParams.height)
        // prawa strefa: bieżący + 3 propozycje (kolejka pusta)
        assertEquals(4, s.listView.adapter!!.itemCount)
        assertTrue(s.queueTab.text.toString().contains("(3)"))
        // pasek wideo ukryty, przyciski sterowania są
        assertFalse(a.shelfShown)
        for (b in listOf(s.playBtn, s.prevBtn, s.nextBtn, s.shuffleBtn, s.repeatBtn, s.videoBtn)) assertEquals(View.VISIBLE, b.visibility)
        // losowo / powtarzanie
        s.shuffleBtn.performClick(); idle(); assertTrue(YtEngine.shuffle)
        s.repeatBtn.performClick(); assertTrue(YtEngine.repeatOne)
        s.repeatBtn.performClick(); assertFalse(YtEngine.repeatOne)
        // tekst piosenki (zakładka)
        s.lyricsTab.performClick()
        waitFor { s.lyricsLabel.contains("Druga linijka") }
        assertTrue(s.lyricsLabel.contains("Pierwsza linijka"))
        assertTrue(server.requestCount > 0)
        s.selectTab(YtMusicStage.TAB_QUEUE)
        // teledysk: zwykły odtwarzacz wideo; powrót przyciskiem „Widok muzyczny”
        s.videoBtn.performClick(); idle()
        assertFalse(a.stageShown); assertFalse(Prefs.ytMusicView)
        a.setMusicView(true); idle()
        assertTrue(a.stageShown)
        // następny utwór z listy: ten sam ekran
        a.playNow(rel[1]); waitFor { YtEngine.video?.id == "rel2" }
        assertEquals("rel2", YtEngine.video?.id)
    }

    @Test fun regularVideoKeepsVideoPlayerAndPreviousGoesBack() {
        val a = player("Przepis na sernik", emptyList(), channel = "Kuchnia domowa", id = "kuchnia0001")
        assertFalse(a.stageShown)
        // „Poprzedni”: po kilku sekundach – od początku; z historii odtworzonych – poprzedni utwór
        val first = player("Artist – Pierwszy (Official Video)", emptyList(), id = "first000001")
        val second = YtVideo("second00002", "Artist – Drugi (Official Video)", "Artist - Topic")
        first.playNow(second); waitFor { YtEngine.video?.id == "second00002" }
        assertTrue(YtEngine.canGoBack)
        YtEngine.previous(ctx)
        waitFor { YtEngine.video?.id == "first000001" }
        assertEquals("first000001", YtEngine.video?.id)
    }

    // ---------------- teksty ----------------

    @Test fun lyricsTitleCleaningAndParsing() {
        assertEquals("Song", YtLyrics.cleanTitle("Song (Official Video) ft. Ktoś"))
        assertEquals("Artist" to "Song", YtLyrics.artistAndTrack("Artist – Song (Official Music Video)", "VEVO"))
        assertEquals("Kanał" to "Sam tytuł", YtLyrics.artistAndTrack("Sam tytuł [HD]", "Kanał - Topic"))
        assertEquals("a\nb", YtLyrics.parse(JSONObject().put("syncedLyrics", "[00:01.00] a\n[00:02.50] b")))
        assertNull(YtLyrics.parse(JSONObject().put("instrumental", true).put("plainLyrics", "x")))
        Prefs.ytLyrics = false
        assertNull(runBlocking { YtLyrics.find("Inny tytuł 1", "Wykonawca", 100) })
    }
}

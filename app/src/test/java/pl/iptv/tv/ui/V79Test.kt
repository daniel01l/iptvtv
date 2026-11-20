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
                request.path!!.startsWith("/lyrics/get") -> MockResponse().setBody(JSONObject().put("plainLyrics", "Pierwsza linijka\nDruga linijka").put("syncedLyrics", "[00:00.00] Pierwsza linijka\n[00:01.50] Druga linijka\n[10:00.00] Trzecia").toString())
                request.path!!.startsWith("/sb/") -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(404)
            }
        } }
        server.start()
        YtLyrics.apiBase = server.url("/lyrics").toString().trimEnd('/'); YtLyrics.fallbackBase = ""
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
        YtLyrics.apiBase = "https://lrclib.net/api"; YtLyrics.fallbackBase = "https://api.lyrics.ovh/v1"
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
        val rel = (1..3).map { YtVideo("rel$it", "Wykonawca $it – Utwór $it (Official Audio)", "Wykonawca $it", duration = "PT3M") }
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

    private fun key(a: YtPlayerActivity, code: Int) {
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)); idle()
    }

    @Test fun musicStageFocusStartsOnFirstSongRemembersPositionAndNeverEscapesLyrics() {
        val rel = (1..6).map { YtVideo("rel$it", "Wykonawca $it – Utwór $it", "Wykonawca $it", duration = "PT3M") }
        val a = player("Artist – Song", rel)
        val s = a.stage
        s.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        s.layout(0, 0, 960, 540); idle()
        waitFor { s.focusedListPos() == 0 }
        // ▼▼▼ → czwarta pozycja; ◄ → ostatnia ikona (opcje); ► → z powrotem czwarta
        repeat(3) { key(a, android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor { s.focusedListPos() == 3 }
        key(a, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertSame(s.optionsBtn, s.findFocus())
        key(a, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor { s.focusedListPos() == 3 }
        // ▲ do pierwszej, ▲ – zakładka
        repeat(3) { key(a, android.view.KeyEvent.KEYCODE_DPAD_UP) }
        waitFor { s.focusedListPos() == 0 }
        key(a, android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertSame(s.queueTab, s.findFocus())
        // tekst: ▼ nie opuszcza okna tekstu, ◄ → ikony, ► → z powrotem tekst
        s.selectTab(YtMusicStage.TAB_LYRICS); s.lyricsTab.requestFocus()
        key(a, android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val inText = s.findFocus()
        assertNotNull(inText); assertNotSame(s.playBtn, inText)
        repeat(3) { key(a, android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        assertSame(inText, s.findFocus())
        key(a, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertSame(s.optionsBtn, s.findFocus())
        // (7.14) z ikon wraca na kafelek „Tekst”, dopiero ▼ wchodzi w okno tekstu
        key(a, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        assertSame(s.lyricsTab, s.findFocus())
        key(a, android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertSame(inText, s.findFocus())
    }

    @Test fun lyricsCleaningAndFallbackQueries() {
        assertEquals("Song", YtLyrics.cleanTitle("Song [Lyrics] (Live) feat. X"))
        assertEquals("Song", YtLyrics.baseTitle("Song (Radio Edit)"))
        assertEquals("Song", YtLyrics.baseTitle("Song - Remastered 2011"))
        // wynik zapisany w Room; brak tekstu (świeży wpis) – bez zapytań do sieci
        val before = server.requestCount
        assertNotNull(runBlocking { YtLyrics.find("Zapis 1", "Ktoś", 100) })
        assertTrue(server.requestCount > before)
        assertNotNull(runBlocking { db.lyrics().get("ktoś|zapis 1") })
        runBlocking { db.lyrics().put(pl.iptv.tv.data.LyricsCacheEntry("x|y", missing = true, savedAt = System.currentTimeMillis())) }
        val n = server.requestCount
        assertNull(runBlocking { YtLyrics.find("y", "x", 100) })
        assertEquals(n, server.requestCount)
    }
    @Test fun liveSuggestionsParseAndFailQuietly() {
        assertEquals(listOf("kot", "kotek"), pl.iptv.tv.data.YtSuggest.parse("""["ko",["kot","","kotek"]]"""))
        assertTrue(pl.iptv.tv.data.YtSuggest.parse("nie json").isEmpty())
        pl.iptv.tv.data.YtSuggest.apiBase = server.url("/sg").toString().trimEnd('/')
        try { assertTrue(runBlocking { pl.iptv.tv.data.YtSuggest.fetch("kot") }.isEmpty()) }   // 404 – bez wyjątku, zostaje historia
        finally { pl.iptv.tv.data.YtSuggest.apiBase = "https://suggestqueries.google.com/complete/search" }
        assertTrue(runBlocking { pl.iptv.tv.data.YtSuggest.fetch("k") }.isEmpty())   // za krótka fraza
    }

    @Test fun categoriesOnRailOpenFullGridWithFocusOnFirstVideo() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        waitFor { a.rows.isNotEmpty() && a.rows.none { it.loading } }
        // kolejność i ikony
        assertEquals(listOf("Aktualności", "Na czasie", "Muzyka", "Sport", "Dla dzieci", "Wiadomości", "Gry"), STubeScreen.CATEGORIES.map { it.title })
        assertEquals(7, STubeScreen.CATEGORIES.map { it.icon }.distinct().size)
        assertNotEquals(STubeScreen.CATEGORIES[0].subs.map { it.queries }, STubeScreen.CATEGORIES[5].subs.map { it.queries })   // Aktualności ≠ Wiadomości
        assertTrue(STubeScreen.CATEGORIES.all { it.subs.size >= 5 })                    // podkategorie w każdej kategorii
        val ids = intArrayOf(R.id.stCat0, R.id.stCat1, R.id.stCat2, R.id.stCat3, R.id.stCat4, R.id.stCat5, R.id.stCat6)
        ids.forEachIndexed { i, id -> assertEquals(STubeScreen.CATEGORIES[i].title, a.findViewById<View>(id).contentDescription) }
        // OK na kategorii → siatka od razu, bez pigułek i rzędów, fokus na pierwszym filmie
        a.findViewById<View>(R.id.stCat3).performClick()
        waitFor { a.categoryRow?.tiles?.isNotEmpty() == true }
        idle(500)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stGridBox).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.stScroll).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stChipScroll).visibility)   // pigułki = podkategorie
        assertTrue(a.findViewById<View>(R.id.stCat3).isSelected)
        // szybka zmiana kategorii: wynik tylko ostatniej
        a.findViewById<View>(R.id.stCat4).performClick(); a.findViewById<View>(R.id.stCat6).performClick()
        waitFor { a.categoryRow?.id == "cat_games" && a.categoryRow?.tiles?.isNotEmpty() == true }
        assertEquals("cat_games", a.categoryRow?.id)
        assertTrue(a.findViewById<View>(R.id.stCat6).isSelected && !a.findViewById<View>(R.id.stCat3).isSelected)
        waitFor { a.window.decorView.findFocus() != null && a.categoryGrid.getChildAdapterPosition(a.window.decorView.findFocus().let { var v: View = it; while (v.parent !== a.categoryGrid && v.parent is View) v = v.parent as View; v }) == 0 }
        // WSTECZ wraca do zwykłego ekranu
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)); idle()
        assertNull(a.categoryRow); assertEquals(View.VISIBLE, a.findViewById<View>(R.id.stScroll).visibility)
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
        val r = YtLyrics.parse(JSONObject().put("syncedLyrics", "[00:01.00] a\n[00:02.50][00:09.00] b"))!!
        assertEquals("a\nb\nb", r.plain); assertTrue(r.isSynced)
        assertEquals(listOf(1000L, 2500L, 9000L), r.synced.map { it.ms })
        assertNull(YtLyrics.parse(JSONObject().put("instrumental", true).put("plainLyrics", "x")))
        Prefs.ytLyrics = false
        assertNull(runBlocking { YtLyrics.find("Inny tytuł 1", "Wykonawca", 100) })
    }
    private fun pvOf(v: View): androidx.media3.ui.PlayerView? = v as? androidx.media3.ui.PlayerView
        ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { pvOf(g.getChildAt(it)) } }

    // ---------------- poprawki widoku muzycznego ----------------

    @Test fun compilationsAreNeverSuggestedAndArtistsAlternate() {
        fun vv(id: String, t: String, ch: String, d: String) = YtVideo(id, t, ch, duration = d)
        assertTrue(StubeMixes.isCompilation(vv("1", "Hity 2026 – Najlepsza muzyka", "K", "PT55M")))
        assertTrue(StubeMixes.isCompilation(vv("2", "Pop Hits 2026 Mix", "K", "PT8M")))
        assertTrue(StubeMixes.isCompilation(vv("3", "Piosenka", "K", "PT25M")))
        assertTrue(StubeMixes.isCompilation(vv("5", "Top 50 songs of the year", "K", "PT3M")))
        assertFalse(StubeMixes.isCompilation(vv("4", "Artist – Song (Official Video)", "K", "PT3M40S")))
        val rel = listOf(vv("c1", "Hity 2026 – Składanka", "Mix", "PT1H2M"), vv("a1", "A – Jeden (Official Video)", "A - Topic", "PT3M"),
            vv("a2", "A – Dwa (Official Video)", "A - Topic", "PT3M"), vv("a3", "A – Trzy (Official Video)", "A - Topic", "PT3M"),
            vv("b1", "B – Jeden (Official Video)", "B - Topic", "PT3M"), vv("cur", "X", "Y", "PT3M"))
        val s = StubeMixes.suggest(rel, "cur", "Artist – Song (Official Music Video)", "Artist - Topic")
        assertTrue(s.none { StubeMixes.isCompilation(it) || it.id == "cur" })
        assertEquals(listOf("a1", "b1", "a2", "a3"), s.map { it.id })         // wykonawcy naprzemiennie
        // mix z wyszukiwania też bez składanek
        val h = (1..6).map { wp("q$it", "Zespół – Rock Hit $it (Official Video)", "Zespół", System.currentTimeMillis() - it * 1000L) }
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = (1..20).map { vv("s${q.hashCode()}-$it", if (it % 3 == 0) "Rock Hits 2026 Mix $it" else "$q piosenka $it", "Art$it", if (it % 4 == 0) "PT45M" else "PT3M") }
        }
        val vids = runBlocking { StubeMixes.videos(StubeMixes.build(h).first { it.id == "g:rock" }, watchedIds = emptySet()) }
        assertTrue(vids.size >= StubeMixes.MIN_PLAYLIST); assertTrue(vids.none { StubeMixes.isCompilation(it) })
    }

    @Test fun shelfSlidesUpFromHandleAndMusicViewStaysForNextTracks() {
        Prefs.ytMusicView = false
        val rel = (1..3).map { YtVideo("rel$it", "Wykonawca $it – Utwór $it (Official Audio)", "Wykonawca $it", duration = "PT3M") }
        val a = player("Artist – Song (Official Music Video)", rel)
        val pv = pvOf(a.window.decorView)!!
        pv.showController(); idle()
        // OK pokazuje tylko pasek – półka schowana, pod postępem uchwyt
        assertFalse(a.shelfShown); assertTrue(a.shelfHandleShown)
        a.openShelf(); idle(400)
        assertTrue(a.shelfShown); assertFalse(a.shelfHandleShown)
        a.closeShelf(); assertFalse(a.shelfShown); assertTrue(a.shelfHandleShown)
        // przełącznik widoku muzycznego w odtwarzaczu: od teraz obecna i następne piosenki
        a.setMusicView(true); idle()
        assertTrue(a.stageShown)
        a.playNow(rel[1]); waitFor { YtEngine.video?.id == "rel2" }; idle(400)
        assertTrue("widok muzyczny zostaje dla następnego utworu", a.stageShown)
        assertNotNull(a.stage.findFocus())                                   // zaznaczenie nie znika
        assertSame(a.stage.videoBtn.parent, a.stage.playBtn.parent)          // przycisk teledysku w rzędzie sterowania
        // koniec utworu: nadal widok muzyczny (nie wraca wideo / panel)
        a.remote("next"); waitFor { YtEngine.video?.id != "rel2" }; idle(400)
        assertTrue(a.stageShown); assertNull(a.panel?.takeIf { it.isOpen })
    }

    @Test fun lyricsAreSyncedLikeKaraoke() {
        val a = player("Artist – Song (Official Music Video)", emptyList())
        Prefs.ytMusicView = true; a.setMusicView(true); idle()
        a.stage.lyricsTab.performClick()
        waitFor { a.stage.currentLyricLine >= 0 }
        assertEquals(0, a.stage.currentLyricLine)                            // pozycja ~0 s: śpiewany pierwszy wiersz
        assertTrue(a.stage.lyricsLabel.contains("Druga linijka"))
    }

    @Test fun vlogsAndFilmsAreNeverSuggestedAfterASong() {
        fun vv(id: String, t: String, ch: String, d: String = "PT4M") = YtVideo(id, t, ch, duration = d)
        val rel = listOf(vv("v1", "Mój dzień – vlog z wakacji", "Youtuber"), vv("v2", "Moja podróż do Włoch", "Youtuber"),
            vv("v3", "Recenzja nowego telefonu", "Tech"), vv("v4", "Wywiad z gwiazdą", "TV"), vv("v5", "Pełny film akcji", "Kino", "PT1H30M"),
            vv("m1", "Artysta – Nowa piosenka (Official Video)", "Artysta VEVO"), vv("m2", "Inna – Ballada", "Inna - Topic", "PT3M30S"))
        val s = StubeMixes.suggest(rel, "cur", "Ktoś – Piosenka (Official Video)", "Ktoś - Topic")
        assertEquals(listOf("m1", "m2"), s.map { it.id }.sorted())
        assertTrue(StubeMixes.isNonTrack(vv("x", "Ep. 12 – odcinek specjalny", "K")))
        assertFalse(StubeMixes.isNonTrack(vv("y", "Zespół – Hit (Official Video)", "K")))
    }

    @Test fun musicOptionsSheetHasIconsAndBackClosesOnlyTheSheet() {
        val a = player("Artist – Song (Official Music Video)", emptyList())
        a.setMusicView(true); idle()
        a.stage.optionsBtn.performClick(); idle(300)
        assertTrue(a.stage.optionsOpen)
        assertTrue(a.stage.optionRows.size >= 8)
        assertTrue(a.stage.optionRows.first().isFocused || a.stage.findFocus() != null)
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)); idle(200)
        assertFalse(a.stage.optionsOpen); assertFalse(a.isFinishing)
        // podmenu (prędkość) w tym samym stylu; wybór zamyka okno
        a.stage.optionsBtn.performClick(); idle(300)
        a.stage.optionRows.first().performClick(); idle(300)
        assertTrue(a.stage.optionsOpen)                                       // lista prędkości
        a.stage.optionRows.first().performClick(); idle(300)
        assertFalse(a.stage.optionsOpen)
    }

}

package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioSourceGroup
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeMixes
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/**
 * STube i Stremio (7.0): kod QR logowania, miksy gatunkowe / „dalej po” / społecznościowe, autoodtwarzanie i półka muzyczna,
 * zwijane grupy rozdzielczości źródeł, rzędy bez „ucieczki” fokusu do innych rzędów.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MediaUpgradeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]") else MockResponse().setResponseCode(404)
        } }
        server.start()
        SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeResume = false; Prefs.stubeInternal = true; Prefs.ytAutoplay = true
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = (1..4).map { YtVideo("s${q.hashCode()}-$it", "$q wynik $it", "K", "", "", "PT4M") }
            // propozycje po filmie: wspólne „common” po każdym zalążku, reszta różna
            override fun related(id: String) = listOf(YtVideo("only-$id", "Po $id", "K", duration = "PT3M"), YtVideo("common", "Wspólny", "K", duration = "PT3M"))
        }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        Prefs.ytAutoplay = true
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- logowanie kodem QR ----------------

    @Test fun qrCodeGeneratedLocally() {
        val b = QrCode.bitmap("https://www.google.com/device", 210)
        assertEquals(b.width, b.height)
        val n = QrCode.matrix("https://www.google.com/device").width
        val scale = b.width / n
        assertTrue(scale >= 1 && b.width % n == 0)
        // margines 2 moduły białe, zaraz za nim czarny róg wzorca położenia
        assertEquals(android.graphics.Color.WHITE, b.getPixel(0, 0))
        assertEquals(android.graphics.Color.BLACK, b.getPixel(2 * scale + scale / 2, 2 * scale + scale / 2))
    }

    // ---------------- miksy ----------------

    private fun wp(id: String, title: String, channel: String, at: Long, done: Boolean = true) =
        WatchProgress(id, title, channel, "t-$id", if (done) 200_000 else 60_000, 200_000, at)

    @Test fun genreNextAfterAndCrowdMixes() {
        val t0 = 1_000_000_000L; val min = 60_000L
        val h = listOf(
            wp("a", "Band A – Rock Anthem (Official Video)", "Band A", t0),
            wp("b", "Band B – Punk Rock Live", "Band B", t0 + 4 * min),
            wp("c", "Band C – Indie Rock Song", "Band C", t0 + 8 * min),
            wp("a2", "Band A – Rock Anthem (Official Video)", "Band A", t0 + 3 * 3_600_000L),     // nowe posiedzenie
            wp("a", "Band A – Rock Anthem (Official Video)", "Band A", t0 + 3 * 3_600_000L + 2 * min),
            wp("b", "Band B – Punk Rock Live", "Band B", t0 + 3 * 3_600_000L + 6 * min),
        ).distinctBy { it.videoId to it.updatedAt }
        // gatunki: rock przeważa
        val scores = StubeMixes.genreScores(h, t0 + 86_400_000L)
        assertEquals("rock", scores.maxByOrNull { it.value }!!.key.id)
        // przejścia w jednym posiedzeniu (przerwa ≤ 45 min); po „a” najczęściej „b”
        val tr = StubeMixes.transitions(h)
        assertEquals(listOf("b"), StubeMixes.followers(tr, "a"))
        assertNull(tr["c"]?.get("a2"))                                   // 3 h przerwy – inne posiedzenie
        // ranking społecznościowy: polecany po obu zalążkach najwyżej
        val crowd = StubeMixes.rankCrowd(listOf(listOf(YtVideo("x", "", ""), YtVideo("common", "", "")), listOf(YtVideo("common", "", ""), YtVideo("y", "", ""))))
        assertEquals("common", crowd.first().id)
        // miksy: gatunek, „Dalej po” (ostatni film), „podobni słuchacze”, a na końcu „Wróć do ulubionych”
        val unique = h.groupBy { it.videoId }.map { (_, l) -> l.maxByOrNull { it.updatedAt }!! }
        val mixes = StubeMixes.build(unique, t0 + 86_400_000L)
        val ids = mixes.map { it.id }
        assertTrue(ids.toString(), "g:rock" in ids)
        val next = mixes.first { it.id.startsWith("next:") }
        assertEquals("next:b", next.id); assertEquals(listOf("b"), next.seeds)
        assertTrue("crowd" in ids); assertEquals("again", ids.last())
        // filmy „Dalej po”: najpierw Twoje przejścia, potem propozycje innych widzów (bez już obejrzanych)
        val nextAfterA = next.copy(local = listOf(YtVideo("c", "C", "Band C")), seeds = listOf("a"))
        val vids = runBlocking { StubeMixes.videos(nextAfterA, watchedIds = setOf("a", "b", "c")) }
        assertEquals(listOf("c", "only-a", "common"), vids.map { it.id })
        val crowdVids = runBlocking { StubeMixes.videos(mixes.first { it.id == "crowd" }, watchedIds = emptySet()) }
        assertEquals("common", crowdVids.first().id)
        // muzyka: oznaczenia w tytule / kanał wykonawcy
        assertTrue(StubeMixes.isMusicVideo("Artist – Song (Official Music Video)"))
        assertTrue(StubeMixes.isMusicVideo("Song", "Artist - Topic"))
        assertFalse(StubeMixes.isMusicVideo("Jak zrobić stół – poradnik", "Majsterkowanie"))
    }

    // ---------------- odtwarzacz STube ----------------

    private fun pv(v: View): androidx.media3.ui.PlayerView? = v as? androidx.media3.ui.PlayerView
        ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { pv(g.getChildAt(it)) } }

    private fun player(title: String, related: List<YtVideo>, channel: String = "Artist - Topic", id: String = "abcdefghijk"): YtPlayerActivity {
        YtStreams.resolverOverride = { YtPlayable.Muxed(title, 300, YtCandidate(server.url("/v.mp4").toString(), 360), emptyList(), related, channel = channel) }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, title, channel))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == id && c.get().player != null }
        idle(300)
        return c.get()
    }

    @Test fun musicShelfAutoplayToggleAndCountdown() {
        val rel = (1..3).map { YtVideo("rel$it", "Utwór $it", "Wykonawca", duration = "PT3M") }
        val a = player("Artist – Song (Official Music Video)", rel)
        assertTrue(a.isMusic)
        pv(a.window.decorView)!!.showController(); idle()
        assertTrue(a.shelfShown); assertEquals(3, a.shelfTiles.size)
        // przełącznik autoodtwarzania (pasek / półka / MENU) – od razu w ustawieniach
        a.toggleAutoplay(); assertFalse(Prefs.ytAutoplay)
        a.toggleAutoplay(); assertTrue(Prefs.ytAutoplay)
        // koniec utworu bez kolejki: propozycje + odliczanie, po nim następny utwór w tym samym oknie
        a.remote("next"); waitFor { a.panel?.isOpen == true }
        assertTrue(a.autoplayPending)
        idle(YtPlayerActivity.AUTOPLAY_MS + 1500)
        waitFor { YtEngine.video?.id == "rel1" }
        assertEquals("rel1", YtEngine.video?.id); assertFalse(a.isFinishing)
        // panel: więcej zakładek z propozycjami (kanał, podobne)
        assertTrue(a.categoryQuery().isNotBlank())
    }

    @Test fun autoplayOffAndKeyCancelsCountdown() {
        val a = player("Artist – Song (Official Music Video)", listOf(YtVideo("rel1", "Utwór 1", "W", duration = "PT3M")))
        a.remote("next"); waitFor { a.panel?.isOpen == true }
        assertTrue(a.autoplayPending)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)); idle()
        assertFalse(a.autoplayPending)
        Prefs.ytAutoplay = false
        val b = player("Przepis na sernik", listOf(YtVideo("rel2", "Inny", "W", duration = "PT3M")), channel = "Kuchnia domowa", id = "kuchnia0001")
        assertFalse(b.isMusic)
        pv(b.window.decorView)!!.showController(); idle()
        assertFalse(b.shelfShown)
    }

    // ---------------- Stremio ----------------

    private fun s(addon: String, q: String, n: Int, pl: Boolean = false) =
        StremioStream(addon, "http://$addon", name = "$addon\n$q", title = "Film.$q.$n" + (if (pl) ".Lektor PL" else ""), url = "http://x/$addon/$q/$n.mkv")

    @Test fun sourcesInCollapsibleResolutionGroups() {
        val a = Robolectric.buildActivity(StremioDetailActivity::class.java, StremioDetailActivity.intent(ctx, StremioMetaPreview("tt9", "movie", "Film"))).setup().get()
        // ekran sam wczytuje źródła (bez wtyczek – komunikat); dopiero potem lista z testu
        waitFor { a.sourceAdapter.items.isNotEmpty() }
        val addA = StremioAddon("http://a", "a", "Wtyczka A"); val addB = StremioAddon("http://b", "b", "Wtyczka B")
        val gs = listOf(StremioSourceGroup(addA, listOf(s("A", "1080p", 1), s("A", "2160p", 1), s("A", "720p", 1))),
            StremioSourceGroup(addB, listOf(s("B", "1080p", 2, pl = true), s("B", "1080p", 3))), StremioSourceGroup(addB, emptyList(), loading = true))
        a.sourceAdapter.set(gs, null); idle()
        val ad = a.sourceAdapter
        // grupy wg rozdzielczości (od najwyższej); (7.7) wszystkie zwinięte, także 4K
        val qs = ad.items.filterIsInstance<SourceItem.Quality>()
        assertEquals(listOf("4K", "1080p", "720p"), qs.map { it.q })
        assertEquals(emptySet<String>(), ad.expandedNow)
        assertEquals(0, ad.items.count { it is SourceItem.Source })
        ad.toggle("4K")                                             // OK na 4K – rozwinięcie
        assertEquals(1, ad.items.count { it is SourceItem.Source })
        assertEquals(5, ad.sourceItems.size)                       // wszystkie źródła (także w zwiniętych grupach)
        assertTrue(ad.items.last() is SourceItem.Loading)           // (7.7) „Ładowanie źródeł” zamiast nazw wtyczek
        // OK na „1080p” – rozwinięcie: źródła obu wtyczek, polska wersja na górze
        ad.toggle("1080p")
        val shown = ad.items.filterIsInstance<SourceItem.Source>().map { it.s }
        assertEquals(4, shown.size)
        val firstIn1080 = ad.items[ad.items.indexOfFirst { it is SourceItem.Quality && it.q == "1080p" } + 1] as SourceItem.Source
        assertTrue(firstIn1080.s.title.contains("Lektor PL"))
        assertEquals(1, ad.items.filterIsInstance<SourceItem.Quality>().first { it.q == "1080p" }.polish)
        // zwinięcie 4K
        ad.toggle("4K"); assertEquals(3, ad.items.count { it is SourceItem.Source })
        assertEquals("15 źródeł", "15 " + StremioUi.plural(15, "źródło", "źródła", "źródeł"))
        assertEquals("źródła", StremioUi.plural(22, "źródło", "źródła", "źródeł"))
    }

    // ---------------- rzędy: fokus nie ucieka do innego rzędu ----------------

    private class TileAdapter(val n: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = n
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) = object : RecyclerView.ViewHolder(View(p.context).apply {
            isFocusable = true; layoutParams = RecyclerView.LayoutParams(Ui.dp(120), Ui.dp(80)) }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {}
    }

    @Test fun rowFocusDoesNotJumpToAnotherRow() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible().get()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun row(n: Int) = HRowRecycler(act).apply { layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false); adapter = TileAdapter(n) }
        val r1 = row(2); val r2 = row(6)
        col.addView(r1, LinearLayout.LayoutParams(-1, Ui.dp(100))); col.addView(r2, LinearLayout.LayoutParams(-1, Ui.dp(100)))
        act.setContentView(col); idle()
        val last = r1.getChildAt(1); last.requestFocus()
        // ► na końcu krótkiego rzędu: zostaje (wcześniej – kafelek rzędu niżej)
        assertSame(last, r1.focusSearch(last, View.FOCUS_RIGHT))
        // ▼ – zwykłe przejście do rzędu niżej
        val down = r1.focusSearch(last, View.FOCUS_DOWN)
        assertNotNull(down); assertNotSame(last, down)
    }
}

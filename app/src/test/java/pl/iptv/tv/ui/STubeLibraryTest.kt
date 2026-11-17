package pl.iptv.tv.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** STube: Subskrypcje (najnowsze filmy kanałów), Biblioteka / Playlisty (Do obejrzenia później, Historia, Polubione, playlisty konta), kanały, migracja bazy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class STubeLibraryTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var server: MockWebServer
    private lateinit var db: StubeDatabase
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()

    private fun item(id: String, title: String, ch: String, at: String) =
        """{"snippet":{"title":"$title","videoOwnerChannelTitle":"$ch","thumbnails":{}},"contentDetails":{"videoId":"$id","videoPublishedAt":"$at"}}"""

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                val body = when {
                    p.startsWith("/yt/subscriptions") -> """{"items":[
                        {"snippet":{"title":"Kanał A","resourceId":{"channelId":"UCaaa"},"thumbnails":{}}},
                        {"snippet":{"title":"Kanał B","resourceId":{"channelId":"UCbbb"},"thumbnails":{}}},
                        {"snippet":{"title":"Kanał C","resourceId":{"channelId":"UCccc"},"thumbnails":{}}}]}"""
                    p.startsWith("/yt/playlistItems") && "playlistId=UUaaa" in p ->
                        """{"items":[${item("a1", "A nowy", "Kanał A", "2026-11-15T10:00:00Z")},${item("a2", "A stary", "Kanał A", "2026-11-01T10:00:00Z")}]}"""
                    p.startsWith("/yt/playlistItems") && "playlistId=UUbbb" in p ->
                        """{"items":[${item("b1", "B najnowszy", "Kanał B", "2026-11-16T08:00:00Z")},{"snippet":{"title":"Deleted video"},"contentDetails":{"videoId":"del"}}]}"""
                    // kanał C bez playlisty – pomijany, rząd zostaje
                    p.startsWith("/yt/playlistItems") && "playlistId=UUccc" in p -> return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
                    p.startsWith("/yt/playlistItems") && "playlistId=PLmine" in p -> """{"items":[${item("m1", "Z mojej listy", "K", "2025-01-01T00:00:00Z")}],"nextPageToken":"N2"}"""
                    p.startsWith("/yt/playlists") -> """{"items":[{"id":"PLmine","snippet":{"title":"Moja lista","thumbnails":{}},"contentDetails":{"itemCount":7}}]}"""
                    p.startsWith("/yt/videos") && "id=" in p && "myRating" !in p -> {
                        val ids = request.requestUrl!!.queryParameter("id")!!.split(',')
                        """{"items":[${ids.joinToString(",") { """{"id":"$it","contentDetails":{"duration":"PT5M"}}""" }}]}"""
                    }
                    p.startsWith("/yt/videos") && "myRating=like" in p -> """{"items":[{"id":"lk1","snippet":{"title":"Polubiony","channelTitle":"K"},"contentDetails":{"duration":"PT1M"}}]}"""
                    p.startsWith("/yt/videos") -> """{"items":[{"id":"tr1","snippet":{"title":"Trend","channelTitle":"K"},"contentDetails":{"duration":"PT2M"}}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        YouTubeRepository.apiBase = server.url("/yt").toString().trimEnd('/')
        Prefs.ytApiKey = "KEY1"; Prefs.ytSource = 0; Prefs.ytRegion = "PL"
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeHistory = true
        YouTubeTokens.useStorage(ctx.getSharedPreferences("yt_lib_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        YouTubeRepository.clearCache(); YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..3).map { YtVideo("$id-$it", "$id $it", "Kanał", "", "", "PT3M") }
            override fun search(q: String) = emptyList<YtVideo>()
        }
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
        StubeHistory.useDatabase(db)
    }

    @After fun tearDown() {
        YouTubeTokens.clear(); Prefs.ytApiKey = ""; YouTubeRepository.clearCache(); server.shutdown()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        StubeHistory.useDatabase(null); db.close()
    }

    private fun waitFor(cond: () -> Boolean) { repeat(100) { idle(100); Thread.sleep(10); if (cond()) return } }

    private fun open(): ActivityController<STubeActivity> {
        val c = Robolectric.buildActivity(STubeActivity::class.java).setup()
        val a = c.get()
        waitFor { a.rows.isNotEmpty() && a.rows.none { it.loading } && a.rows.all { it.tiles.isNotEmpty() } }
        idle(300)
        return c
    }

    private fun rv(a: STubeActivity, i: Int): RecyclerView =
        a.findViewById<ViewGroup>(R.id.stRows).getChildAt(i).let { (it as ViewGroup).getChildAt(2) as RecyclerView }

    private fun row(a: STubeActivity, id: String) = a.rows.indexOfFirst { it.id == id }

    private fun focusedRow(a: STubeActivity): Pair<Int, Int> {
        val f = a.window.decorView.findFocus() ?: return -1 to -1
        for (i in a.rows.indices) rv(a, i).findContainingViewHolder(f)?.let { return i to it.bindingAdapterPosition }
        return -1 to -1
    }

    private fun key(a: STubeActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(300)
    }

    /** Zaznaczenie i OK na kafelku [pos] rzędu [ri]. */
    private fun ok(a: STubeActivity, ri: Int, pos: Int) {
        rv(a, ri).focusPosition(pos); idle(300)
        rv(a, ri).findViewHolderForAdapterPosition(pos)!!.itemView.performClick(); idle(300)
    }

    @Test fun pureHelpers() {
        assertEquals("UUabc", YouTubeRepository.uploadsPlaylist("UCabc"))
        val merged = YouTubeRepository.mergeNewest(listOf(
            YtVideo("x", "", "", publishedAt = "2026-01-01T00:00:00Z"), YtVideo("y", "", "", publishedAt = "2026-03-01T00:00:00Z"),
            YtVideo("x", "", "", publishedAt = "2026-01-01T00:00:00Z"), YtVideo("z", "", "", publishedAt = "2026-02-01T00:00:00Z")), 2)
        assertEquals(listOf("y", "z"), merged.map { it.id })
        val now = java.time.Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()
        assertEquals("30 min temu", STubeScreen.ago("2026-11-16T11:30:00Z", now))
        assertEquals("3 godz. temu", STubeScreen.ago("2026-11-16T09:00:00Z", now))
        assertEquals("wczoraj", STubeScreen.ago("2026-11-15T09:00:00Z", now))
        assertEquals("5 dni temu", STubeScreen.ago("2026-11-11T12:00:00Z", now))
        assertEquals("rok temu", STubeScreen.ago("2025-10-01T12:00:00Z", now))
        assertEquals("2 lata temu", STubeScreen.ago("2024-10-01T12:00:00Z", now))
        assertEquals("5 lat temu", STubeScreen.ago("2021-10-01T12:00:00Z", now))
        assertNull(STubeScreen.ago("")); assertNull(STubeScreen.ago("zła data"))
        assertEquals("🕘", STubeScreen.playlistIcon(STubeScreen.PL_LATER)); assertEquals("📺", STubeScreen.playlistIcon("channel:UC1"))
    }

    @Test fun subscriptionFeedNewestFirst() = runBlocking {
        YouTubeTokens.save("AT1", "RT1", 3600)
        val feed = YouTubeRepository.subscriptionFeed()
        // najnowsze ze wszystkich kanałów, malejąco po dacie; usunięte pominięte; kanał bez playlisty nie psuje rzędu
        assertEquals(listOf("b1", "a1", "a2"), feed.map { it.id })
        assertEquals("PT5M", feed[0].duration)            // czas z jednego zapytania videos?id=
        assertEquals("Kanał B", feed[0].channel)
        val sub = requests.first { it.path!!.startsWith("/yt/subscriptions") }
        assertEquals("Bearer AT1", sub.getHeader("Authorization"))
        assertTrue("order=relevance" in sub.path!!)
        assertEquals(1, requests.count { it.path!!.startsWith("/yt/videos") && "id=" in it.path!! })
        // pamięć 10 min – drugie wywołanie bez zapytań
        val n = requests.size; YouTubeRepository.subscriptionFeed(); assertEquals(n, requests.size)
    }

    @Test fun guestLibraryWatchLaterOpensBelowAndBackCloses() {
        val c = open(); val a = c.get()
        val li = row(a, STubeScreen.ROW_LIBRARY)
        assertEquals("Biblioteka / Playlisty", a.rows[li].title)
        assertEquals(listOf(STubeScreen.PL_LATER, STubeScreen.PL_HISTORY), a.rows[li].tiles.map { (it as STubeTile.Playlist).p.id })
        // kafelek listy: ikona, licznik, podpis „Na tym urządzeniu”; fokusowalny
        val tile = rv(a, li).findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup
        val texts = mutableListOf<String>()
        fun collect(v: View) { if (v is TextView && v.visibility == View.VISIBLE) texts += v.text.toString(); if (v is ViewGroup) (0 until v.childCount).forEach { collect(v.getChildAt(it)) } }
        collect(tile)
        assertTrue(texts.containsAll(listOf("🕘", "☰ 0", "Do obejrzenia później", "Na tym urządzeniu")))
        assertTrue(tile.isFocusable)

        // pusta lista: rząd z podpowiedzią, fokus zostaje na kafelku Biblioteki
        ok(a, li, 0)
        waitFor { a.playlistRow?.loading == false }
        assertEquals(li + 1, row(a, STubeScreen.ROW_PLAYLIST))
        assertTrue(a.playlistRow!!.tiles.isEmpty())
        assertEquals(li to 0, focusedRow(a))
        key(a, KeyEvent.KEYCODE_BACK); assertNull(a.playlistRow); assertFalse(a.isFinishing)

        // dodany film → licznik i rząd listy z filmem, fokus na pierwszym filmie
        runBlocking { StubeHistory.addWatchLater(YtVideo("wl1", "Na później", "K", "", duration = "PT9M")) }
        c.pause().resume(); idle(300)
        collect(rv(a, li).findViewHolderForAdapterPosition(0)!!.itemView); assertTrue("☰ 1" in texts)
        ok(a, li, 0)
        waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }; idle(300)
        assertEquals("🕘  Do obejrzenia później", a.playlistRow!!.title)
        assertEquals("wl1", (a.playlistRow!!.tiles.single() as STubeTile.Video).v.id)
        assertEquals(li + 1 to 0, focusedRow(a))
        // ▲ wraca do Biblioteki, ▼ ponownie do listy (rząd wstawiony uczestniczy w nawigacji)
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals(li, focusedRow(a).first)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(li + 1, focusedRow(a).first)
        // WSTECZ zamyka rząd listy i wraca na kafelek, z którego ją otwarto; kolejne WSTECZ – wyjście ze STube
        key(a, KeyEvent.KEYCODE_BACK)
        assertNull(a.playlistRow); assertEquals(li to 0, focusedRow(a)); assertFalse(a.isFinishing)
        assertEquals(a.rows.size, a.findViewById<ViewGroup>(R.id.stRows).childCount)

        // usunięcie z listy przy otwartym rzędzie – rząd pusty zamknięty, fokus z powrotem w Bibliotece
        ok(a, li, 0); waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }; idle(300)
        runBlocking { StubeHistory.removeWatchLater("wl1") }
        c.pause().resume(); idle(500)
        assertNull(a.playlistRow); assertEquals(li, focusedRow(a).first)
        c.pause().stop().destroy()
    }

    @Test fun historyPlaylistShowsWatchedVideos() {
        StubeHistory.savePosition(YtVideo("h1", "Obejrzany", "K"), 590_000, 600_000)
        runBlocking { StubeHistory.flush(StubeHistory.cached("h1")!!) }
        val a = open().get()
        val li = row(a, STubeScreen.ROW_LIBRARY)
        ok(a, li, 1)
        waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }
        // także ukończone (pełny pasek) – w odróżnieniu od „Kontynuuj oglądanie”
        assertEquals("h1", (a.playlistRow!!.tiles.single() as STubeTile.Video).v.id)
        assertEquals(-1, row(a, STubeScreen.ROW_CONTINUE))
    }

    @Test fun loggedInSubscriptionsLibraryAndChannels() {
        YouTubeTokens.save("AT1", "RT1", 3600)
        val a = open().get()
        assertEquals(listOf("Dla Ciebie", "Subskrypcje", "Na czasie", "Muzyka", "Na żywo", "Biblioteka / Playlisty", "Kanały"), a.rows.map { it.title })
        assertEquals(listOf("b1", "a1", "a2"), a.rows[1].tiles.map { (it as STubeTile.Video).v.id })
        // podpis kafelka subskrypcji: kanał · wiek filmu
        val sub = (rv(a, 1).findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup).let { g -> (0 until g.childCount).map(g::getChildAt).filterIsInstance<TextView>().last() }
        assertTrue(sub.text.startsWith("Kanał B · "))
        // Biblioteka: lokalne + Polubione + playlisty konta (licznik filmów)
        val li = row(a, STubeScreen.ROW_LIBRARY)
        assertEquals(listOf(STubeScreen.PL_LATER, STubeScreen.PL_HISTORY, STubeScreen.PL_LIKED, "PLmine"), a.rows[li].tiles.map { (it as STubeTile.Playlist).p.id })
        assertEquals(7, (a.rows[li].tiles[3] as STubeTile.Playlist).p.count)
        // playlista konta – filmy w rzędzie poniżej (z czasem trwania, kolejna strona)
        ok(a, li, 3)
        waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }
        assertEquals("m1", (a.playlistRow!!.tiles[0] as STubeTile.Video).v.id)
        assertEquals("PT5M", (a.playlistRow!!.tiles[0] as STubeTile.Video).v.duration)
        assertEquals("N2", a.playlistRow!!.next)
        // Polubione zastępują otwarty rząd (jeden naraz)
        key(a, KeyEvent.KEYCODE_BACK)
        ok(a, li, 2); waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }
        assertEquals("lk1", (a.playlistRow!!.tiles[0] as STubeTile.Video).v.id)
        ok(a, li, 3); waitFor { (a.playlistRow?.tiles?.firstOrNull() as? STubeTile.Video)?.v?.id == "m1" }
        assertEquals(1, a.rows.count { it.id == STubeScreen.ROW_PLAYLIST })
        // kanał: OK → najnowsze filmy kanału (playlista „przesłane”) pod rzędem Kanały
        key(a, KeyEvent.KEYCODE_BACK)
        val ci = row(a, STubeScreen.ROW_CHANNELS)
        ok(a, ci, 0)
        waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }
        assertEquals(ci + 1, row(a, STubeScreen.ROW_PLAYLIST))
        assertEquals(listOf("a1", "a2"), a.playlistRow!!.tiles.map { (it as STubeTile.Video).v.id })
        assertEquals("📺  Kanał A", a.playlistRow!!.title)
    }

    @Test fun databaseMigratesFromV1() {
        val name = "stube_mig_test.db"
        ctx.deleteDatabase(name)
        // baza w wersji 1 (schemat app/schemas/…/1.json) z danymi
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { d ->
            d.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`key` TEXT NOT NULL, `query` TEXT NOT NULL, `usedAt` INTEGER NOT NULL, `uses` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            d.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_usedAt` ON `search_history` (`usedAt`)")
            d.execSQL("CREATE TABLE IF NOT EXISTS `watch_progress` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `channel` TEXT NOT NULL, `thumb` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))")
            d.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedAt` ON `watch_progress` (`updatedAt`)")
            d.execSQL("INSERT INTO search_history VALUES ('kot','kot',1,1)")
            d.execSQL("INSERT INTO watch_progress VALUES ('v1','T','K','',60000,240000,1)")
            d.version = 1
        }
        val v2 = Room.databaseBuilder(ctx, StubeDatabase::class.java, name).allowMainThreadQueries().build()
        runBlocking {
            assertEquals("kot", v2.search().get("kot")!!.query)
            assertEquals(60_000L, v2.progress().get("v1")!!.positionMs)
            v2.watchLater().upsert(pl.iptv.tv.data.WatchLater("w", addedAt = 1))
            assertEquals(1, v2.watchLater().count())
        }
        assertEquals(5, v2.openHelper.readableDatabase.version)
        runBlocking { assertTrue(v2.queue().all().isEmpty()) }   // v3: kolejka utworzona migracją
        runBlocking { assertTrue(v2.dash().all().isEmpty()) }    // v4: kafelki pulpitu X25
        runBlocking { assertTrue(v2.stremio().addons().isEmpty()) }   // v5: Stremio
        v2.close(); ctx.deleteDatabase(name)
    }
}

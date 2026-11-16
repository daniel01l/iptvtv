package pl.iptv.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** STube: rzędy (Dla Ciebie, Na czasie, Muzyka, Subskrypcje, wyniki), dane z YouTube API, nawigacja pilotem, przycisk w menu. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class STubeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()

    private fun videos(prefix: String, n: Int, next: String? = null): String {
        val items = (1..n).joinToString(",") { i ->
            """{"id":"$prefix-$i","snippet":{"title":"$prefix film $i","channelTitle":"Kanał $prefix","thumbnails":{"high":{"url":"http://127.0.0.1:1/t.jpg"}}},"contentDetails":{"duration":"PT4M${i}S"}}"""
        }
        return """{${next?.let { "\"nextPageToken\":\"$it\"," } ?: ""}"items":[$items]}"""
    }

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                if (quotaExceeded && p.startsWith("/yt/"))
                    return MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}""")
                val body = when {
                    p.startsWith("/yt/videos") && "myRating=like" in p -> videos("liked", 6)
                    p.startsWith("/yt/videos") && "videoCategoryId=10" in p -> videos("music", 5)
                    p.startsWith("/yt/videos") && "pageToken=P2" in p -> videos("trend2", 3)
                    p.startsWith("/yt/videos") -> videos("trend", 8, next = "P2")
                    p.startsWith("/yt/subscriptions") -> """{"items":[{"snippet":{"title":"Subskrypcja A","resourceId":{"channelId":"UC1"},"thumbnails":{"default":{"url":"http://127.0.0.1:1/a.jpg"}}}}]}"""
                    p.startsWith("/yt/search") -> """{"items":[{"id":{"videoId":"srch0000001"},"snippet":{"title":"Wynik 1","channelTitle":"K"}}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        YouTubeRepository.apiBase = server.url("/yt").toString().trimEnd('/')
        Prefs.ytApiKey = "KEY1"; Prefs.ytSource = 0; Prefs.ytRegion = "PL"
        YouTubeTokens.useStorage(ctx.getSharedPreferences("yt_stube_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        YouTubeRepository.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.mainPreview = false
        // listy bez klucza – bez sieci (4 filmy na listę, wyszukiwanie – 1 wynik)
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..4).map { YtVideo("$id-$it", "$id $it", "Kanał", "", "", "PT3M${it}S") }
            override fun search(q: String) = listOf(YtVideo("kw1", "Wynik K1", "K")).also { keylessQueries += q }
        }
    }

    private var quotaExceeded = false
    private val keylessQueries = java.util.Collections.synchronizedList(mutableListOf<String>())

    @After fun tearDown() {
        YouTubeTokens.clear(); Prefs.ytApiKey = ""; Prefs.stubeInternal = true; YouTubeRepository.clearCache(); server.shutdown()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
    }

    private fun open(): ActivityController<STubeActivity> {
        val c = Robolectric.buildActivity(STubeActivity::class.java).setup()
        waitLoaded(c.get())
        return c
    }

    private fun waitLoaded(a: STubeActivity) {
        repeat(100) {
            idle(100); Thread.sleep(15)
            if (a.rows.isNotEmpty() && a.rows.none { it.loading } && a.rows.all { it.tiles.isNotEmpty() }) { idle(); return }
        }
    }

    private fun rv(a: STubeActivity, i: Int): RecyclerView =
        a.findViewById<ViewGroup>(R.id.stRows).getChildAt(i).let { (it as ViewGroup).getChildAt(2) as RecyclerView }

    private fun key(a: STubeActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(300)
    }

    private fun focusedRow(a: STubeActivity): Pair<Int, Int> {
        val f = a.window.decorView.findFocus() ?: return -1 to -1
        val box = a.findViewById<ViewGroup>(R.id.stRows)
        for (i in 0 until box.childCount) {
            val r = rv(a, i)
            r.findContainingViewHolder(f)?.let { return i to it.bindingAdapterPosition }
        }
        return -1 to -1
    }

    @Test fun rowOrderAndDuration() {
        assertEquals(listOf("login", "trending", "music", "live"), STubeScreen.rowIds(loggedIn = false, personal = true, query = null))
        assertEquals(listOf("for_you", "trending", "music", "live", "subs"), STubeScreen.rowIds(loggedIn = true, personal = true, query = null))
        assertEquals(listOf("trending", "music", "live", "subs"), STubeScreen.rowIds(loggedIn = true, personal = false, query = null))
        assertEquals(listOf("search", "login", "trending", "music", "live"), STubeScreen.rowIds(false, true, "kot"))
        assertEquals("4:05", STubeScreen.duration("PT4M5S"))
        assertEquals("1:02:03", STubeScreen.duration("PT1H2M3S"))
        assertEquals("", STubeScreen.duration("P0D"))
    }

    @Test fun guestSeesTrendingMusicAndLoginTile() {
        val a = open().get()
        assertEquals(listOf("Dla Ciebie", "Na czasie", "Muzyka", "Na żywo"), a.rows.map { it.title })
        val login = a.rows[0].tiles.single() as STubeTile.Action
        assertEquals(STubeScreen.ACT_LOGIN, login.id)
        assertEquals(8, a.rows[1].tiles.size); assertEquals(5, a.rows[2].tiles.size)
        val music = requests.first { "videoCategoryId=10" in it.path!! }
        assertTrue("key=KEY1" in music.path!! && "regionCode=PL" in music.path!!)
        // kafelek: tytuł, kanał, czas
        val tile = rv(a, 1).findViewHolderForAdapterPosition(0)!!.itemView as ViewGroup
        val texts = (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("trend film 1")); assertTrue(texts.contains("Kanał trend"))
        assertTrue(tile.isFocusable)
        // 10-foot UI: duży cel (≥ 200 dp szerokości)
        assertTrue(tile.width >= Ui.dp(200))
    }

    @Test fun loggedInRowsUseAccount() {
        YouTubeTokens.save("AT1", "RT1", 3600)
        val a = open().get()
        assertEquals(listOf("Dla Ciebie", "Na czasie", "Muzyka", "Na żywo", "Subskrypcje"), a.rows.map { it.title })
        assertEquals("liked film 1", (a.rows[0].tiles[0] as STubeTile.Video).v.title)
        assertEquals("Subskrypcja A", (a.rows[4].tiles[0] as STubeTile.Channel).c.title)
        val liked = requests.first { "myRating=like" in it.path!! }
        assertEquals("Bearer AT1", liked.getHeader("Authorization"))
    }

    @Test fun dpadNavigation() {
        val a = open().get()
        // start: pierwszy kafelek pierwszego rzędu
        assertEquals(0 to 0, focusedRow(a))
        // ▼ do „Na czasie”, ►► w rzędzie
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(1, focusedRow(a).first)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1 to 2, focusedRow(a))
        // ▼ do „Muzyka” i ▲ z powrotem na ten sam kafelek (pamięć pozycji rzędu)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(2, focusedRow(a).first)
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals(1 to 2, focusedRow(a))
        // ◄ na pierwszym kafelku: fokus zostaje w rzędzie
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1 to 0, focusedRow(a))
        // ostatni rząd: ▼ nie wychodzi poza ekran, ► na ostatnim kafelku zostaje
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(3, focusedRow(a).first)
        repeat(7) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(3 to 3, focusedRow(a))
        // ▲ z pierwszego rzędu → wyszukiwarka; ▼ z wyszukiwarki → pierwszy rząd
        repeat(4) { key(a, KeyEvent.KEYCODE_DPAD_UP) }
        assertEquals(R.id.stQuery, a.window.decorView.findFocus()?.id)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(0, focusedRow(a).first)
    }

    @Test fun nextPageLoadsNearRowEnd() {
        val a = open().get()
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(5) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(40) { idle(100); Thread.sleep(15); if (a.rows[1].tiles.size == 11) return@repeat }
        assertEquals(11, a.rows[1].tiles.size); assertNull(a.rows[1].next)
    }

    @Test fun videoOpensInSmartTube() {
        Prefs.stubeInternal = false          // wbudowany odtwarzacz wyłączony – od razu SmartTube
        val pkg = SmartTubeIntegrationHelper.PKG_STABLE
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true } })
        val cn = ComponentName(pkg, "$pkg.Main"); spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") })
        val a = open().get()
        rv(a, 1).findViewHolderForAdapterPosition(0)!!.itemView.performClick(); idle()
        val i = shadowOf(a).nextStartedActivity
        assertEquals(pkg, i.`package`)
        assertEquals("https://www.youtube.com/watch?v=" + (a.rows[1].tiles[0] as STubeTile.Video).v.id, i.dataString)
    }

    @Test fun searchAddsResultsRowOnTop() {
        val a = open().get()
        a.findViewById<TextView>(R.id.stQuery).text = "kot"
        a.findViewById<View>(R.id.stSearch).performClick()
        waitLoaded(a)
        assertEquals("Wyniki: kot", a.rows[0].title)
        assertEquals("Wynik K1", (a.rows[0].tiles[0] as STubeTile.Video).v.title)
        assertEquals(STubeScreen.ACT_SMARTTUBE_SEARCH, (a.rows[0].tiles.last() as STubeTile.Action).id)
        assertTrue("wyszukiwanie bez klucza – bez zużycia limitu API", requests.none { it.path!!.startsWith("/yt/search") })
        assertEquals(listOf("kot"), keylessQueries)
        assertEquals(0, focusedRow(a).first)
    }

    @Test fun noApiAccessUsesKeylessLists() {
        Prefs.ytApiKey = ""
        if (YouTubeRepository.publicAvailable) return    // klucz wbudowany w BuildConfig
        val a = open().get()
        // bez klucza i konta: listy publiczne bez API (Na czasie, Muzyka, Na żywo) – bez zapytań do YouTube Data API
        assertEquals(YtKeyless.PODCASTS + "-1", (a.rows[1].tiles[0] as STubeTile.Video).v.id)
        assertEquals(YtKeyless.MUSIC + "-1", (a.rows[2].tiles[0] as STubeTile.Video).v.id)
        assertEquals(YtKeyless.LIVE + "-1", (a.rows[3].tiles[0] as STubeTile.Video).v.id)
        assertTrue(requests.none { it.path!!.startsWith("/yt/") })
        assertEquals(View.GONE, a.findViewById<View>(R.id.stInfo).visibility)
    }

    @Test fun apiErrorFallsBackToKeyless() {
        quotaExceeded = true
        val a = open().get()
        assertEquals(YtKeyless.MUSIC + "-1", (a.rows[2].tiles[0] as STubeTile.Video).v.id)
        assertTrue(requests.any { "videoCategoryId=10" in it.path!! })
    }

    @Test fun keylessHelpers() {
        assertEquals("dQw4w9WgXcQ", YtKeyless.idOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals("dQw4w9WgXcQ", YtKeyless.idOf("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(YtKeyless.idOf("https://www.youtube.com/channel/UC1"))
        assertEquals("PT23M59S", YtKeyless.isoDuration(1439)); assertEquals("23:59", STubeScreen.duration(YtKeyless.isoDuration(1439)))
        assertEquals("PT1H1S", YtKeyless.isoDuration(3601)); assertEquals("1:00:01", STubeScreen.duration("PT1H1S"))
        assertEquals("P0D", YtKeyless.isoDuration(-1)); assertEquals("P0D", YtKeyless.isoDuration(100, live = true))
    }

    @Test fun mainMenuButtonOpensSTube() {
        val f = File(ctx.filesDir, "stube.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,K1\nhttp://s/1\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val b = m.findViewById<TextView>(R.id.btnSTube)
        assertEquals(View.VISIBLE, b.visibility); assertTrue(b.isFocusable); assertEquals("STube", b.text.toString())
        b.performClick(); idle()
        assertEquals(STubeActivity::class.java.name, shadowOf(m).nextStartedActivity.component?.className)
    }
}

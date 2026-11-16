package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreamException
import pl.iptv.tv.data.YtStreams
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** Wbudowany odtwarzacz STube: wybór strumieni, zapytania do serwerów wideo, SponsorBlock, okno awaryjne. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class YtPlayerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private var sbBody = "[]"

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                return when {
                    p.startsWith("/videoplayback") -> MockResponse().setBody("0123456789".repeat(10).take(
                        Regex("range=(\\d+)-(\\d+)").find(p)?.let { (it.groupValues[2].toInt() - it.groupValues[1].toInt() + 1) } ?: 100))
                    p.startsWith("/sb/skipSegments/") -> if (sbBody == "404") MockResponse().setResponseCode(404) else MockResponse().setBody(sbBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.sbEnabled = true; Prefs.sbCategories = listOf("sponsor", "selfpromo"); Prefs.stubeInternal = true
        Prefs.ytMaxHeight = 1080; Prefs.lowPerf = false
        Prefs.permissionsAsked = true; Prefs.setupDone = true
    }

    @After fun tearDown() { YtStreams.resolverOverride = null; server.shutdown() }

    // ---------------- wybór strumieni ----------------

    @Test fun pickVideoPrefersAvcAtMaxHeight() {
        val l = listOf(
            YtCandidate("a", 2160, 30, "vp9"), YtCandidate("b", 1080, 30, "vp9", bitrate = 4000),
            YtCandidate("c", 1080, 30, "avc1.640028", bitrate = 3000), YtCandidate("d", 720, 60, "avc1"),
        )
        assertEquals("c", YtStreams.pickVideo(l, 1080)!!.url)       // 1080p, H.264 przed VP9
        assertEquals("d", YtStreams.pickVideo(l, 720)!!.url)
        assertEquals("d", YtStreams.pickVideo(l, 480)!!.url)        // nic ≤ 480 – najniższy dostępny
        assertNull(YtStreams.pickVideo(emptyList(), 1080))
        // tryb słabszych urządzeń ogranicza jakość
        Prefs.lowPerf = true; Prefs.lowMaxHeight = 720
        assertEquals(720, YtStreams.maxHeight)
        Prefs.lowPerf = false; Prefs.ytMaxHeight = 1440
        assertEquals(1440, YtStreams.maxHeight)
    }

    @Test fun pickAudioOriginalAac() {
        val l = listOf(
            YtCandidate("opus", codec = "opus", bitrate = 160), YtCandidate("dub", codec = "mp4a.40.2", mime = "audio/mp4", bitrate = 128, original = false),
            YtCandidate("aac", codec = "mp4a.40.2", mime = "audio/mp4", bitrate = 128),
        )
        assertEquals("aac", YtStreams.pickAudio(l)!!.url)
    }

    @Test fun errorMessagesArePolish() {
        assertTrue(YtStreams.message(org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException("x")).contains("kraju"))
        assertTrue(YtStreams.message(org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException("x")).contains("wiekowym"))
        assertEquals("Film niedostępny (usunięty, prywatny lub zablokowany)",
            YtStreams.message(org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("Got error ERROR: \"This video is unavailable\"")))
        assertEquals("Brak połączenia z YouTube", YtStreams.message(java.io.IOException()))
    }

    // ---------------- zapytania do serwera wideo ----------------

    @Test fun videoPlaybackRequestsRewritten() {
        val url = server.url("/videoplayback?id=1&c=WEB").toString()
        // zakres od 0: bez parametru range, POST z treścią "x\0", licznik rn, nagłówki klienta web
        YtMedia.vodHttp.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-").build()).execute().use { assertEquals(200, it.code) }
        val r0 = requests.last()
        assertEquals("POST", r0.method); assertArrayEquals(byteArrayOf(0x78, 0), r0.body.readByteArray())
        assertNull(r0.getHeader("Range")); assertNull(r0.requestUrl!!.queryParameter("range"))
        assertNotNull(r0.requestUrl!!.queryParameter("rn"))
        assertEquals("https://www.youtube.com", r0.getHeader("Origin"))
        // zakres od środka: parametr range, odpowiedź 200 zamieniona na 206 z Content-Range (Media3 nie pomija bajtów drugi raz)
        YtMedia.vodHttp.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=10-19").build()).execute().use {
            assertEquals(206, it.code); assertEquals("bytes 10-19/*", it.header("Content-Range")); assertEquals(10, it.body!!.bytes().size)
        }
        val r1 = requests.last()
        assertEquals("10-19", r1.requestUrl!!.queryParameter("range"))
        assertNotEquals(r0.requestUrl!!.queryParameter("rn"), r1.requestUrl!!.queryParameter("rn"))
        // inne adresy bez zmian
        YtMedia.vodHttp.newCall(okhttp3.Request.Builder().url(server.url("/sb/skipSegments/abcd")).build()).execute().close()
        assertEquals("GET", requests.last().method)
        assertEquals(10L to 19L, YtMedia.VideoPlaybackInterceptor().parseRange("bytes=10-19"))
        assertEquals(5L to -1L, YtMedia.VideoPlaybackInterceptor().parseRange("bytes=5-"))
    }

    // ---------------- SponsorBlock ----------------

    @Test fun sponsorBlockHashPrefixAndParsing() = runBlocking {
        assertEquals("46cb", SponsorBlock.hashPrefix("0e3GPea1Tyg"))
        sbBody = """[{"videoID":"other","segments":[{"category":"sponsor","actionType":"skip","segment":[1,50]}]},
            {"videoID":"vid","segments":[{"category":"selfpromo","actionType":"skip","segment":[120.5,130]},
            {"category":"sponsor","actionType":"skip","segment":[10,40.2]},{"category":"intro","actionType":"skip","segment":[0,5]},
            {"category":"sponsor","actionType":"mute","segment":[60,70]},{"category":"sponsor","actionType":"skip","segment":[80,80.2]}]}]"""
        val s = SponsorBlock.segments("vid")
        assertEquals(listOf(SbSegment(10.0, 40.2, "sponsor"), SbSegment(120.5, 130.0, "selfpromo")), s)
        val req = requests.last { it.path!!.startsWith("/sb/") }
        assertTrue(req.path!!.startsWith("/sb/skipSegments/${SponsorBlock.hashPrefix("vid")}"))
        assertFalse("ID filmu nie trafia do serwera", req.path!!.contains("vid&") || req.path!!.endsWith("vid"))
        assertEquals(s[0], SponsorBlock.at(s, 10_000)); assertEquals(s[0], SponsorBlock.at(s, 39_000))
        assertNull(SponsorBlock.at(s, 40_000)); assertNull(SponsorBlock.at(s, 9_999))
        sbBody = "404"; assertTrue(SponsorBlock.segments("vid").isEmpty())
        assertTrue(SponsorBlock.segments("vid", emptyList()).isEmpty())
    }

    // ---------------- ekran odtwarzacza ----------------

    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(10); if (cond()) return } }

    @Test fun blockedVideoShowsFallbackDialog() {
        YtStreams.resolverOverride = { throw YtStreamException("Film z ograniczeniem wiekowym – wymaga zalogowania w YouTube") }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, "abcdefghijk", "Film")).setup()
        val a = c.get()
        waitFor { a.fallbackDialog?.isShowing == true }
        val d = a.fallbackDialog!!
        assertTrue(d.isShowing)
        assertEquals("Odtwórz w SmartTube", d.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Odtwórz w YouTube", d.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Zamknij", d.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isFocusable)
        assertNull(a.player)                              // odtwarzacz zwolniony
        // „Odtwórz w YouTube” – link do filmu (aplikacja YouTube lub przeglądarka)
        val yt = SmartTubeIntegrationHelper.PKG_YT_TV
        val spm = shadowOf(ctx.packageManager)
        val cn = android.content.ComponentName(yt, "$yt.Main"); spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, android.content.IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") })
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        val i = shadowOf(a).nextStartedActivity
        assertEquals(yt, i.`package`); assertEquals("https://www.youtube.com/watch?v=abcdefghijk", i.dataString)
        assertTrue(a.isFinishing)
    }

    @Test fun closeButtonFinishes() {
        YtStreams.resolverOverride = { throw YtStreamException("x") }
        val a = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, "abcdefghijk")).setup().get()
        waitFor { a.fallbackDialog?.isShowing == true }
        a.fallbackDialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        assertTrue(a.isFinishing)
    }

    @Test fun playbackErrorShowsFallbackAndSegmentsSkip() {
        sbBody = """[{"videoID":"abcdefghijk","segments":[{"category":"sponsor","actionType":"skip","segment":[5,30]}]}]"""
        YtStreams.resolverOverride = { YtPlayable.Muxed("Film", 100, YtCandidate(server.url("/missing.mp4").toString(), 360)) }
        val a = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, "abcdefghijk")).setup().get()
        waitFor { a.player != null && a.segments.isNotEmpty() }
        assertEquals(listOf(SbSegment(5.0, 30.0, "sponsor")), a.segments)
        // segment pod pozycją – przeskok na jego koniec
        assertTrue(a.skipIfNeeded(6_000)); assertEquals(30_000L, a.player!!.currentPosition)
        assertEquals("sponsor", a.lastSkipped!!.category)
        assertFalse(a.skipIfNeeded(31_000))
        // błąd strumienia (404) → po jednej ponownej próbie okno awaryjne
        waitFor { a.fallbackDialog?.isShowing == true }
        assertTrue(a.fallbackDialog!!.isShowing)
    }

    @Test fun stubeTileOpensInternalPlayer() {
        Prefs.stubeInternal = true
        val s = Robolectric.buildActivity(STubeActivity::class.java).setup().get(); idle()
        val m = STubeActivity::class.java.getDeclaredMethod("onTile", STubeRow::class.java, STubeTile::class.java).apply { isAccessible = true }
        val row = STubeRow("t", "T") { STubePage(emptyList()) }
        m.invoke(s, row, STubeTile.Video(pl.iptv.tv.data.YtVideo("vid12345678", "Tytuł", "Kanał")))
        val i = shadowOf(s).nextStartedActivity
        assertEquals(YtPlayerActivity::class.java.name, i.component!!.className)
        assertEquals("vid12345678", i.getStringExtra(YtPlayerActivity.EXTRA_ID))
    }
}

package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
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
import pl.iptv.tv.remote.RemoteServer
import java.time.Duration

/**
 * (7.14) Przesyłanie linków do playlist (YouTube, Spotify, lista tytułów) do STube tym samym kanałem co listy M3U: rozpoznanie,
 * pobranie i dopasowanie, podgląd, zapis w Room, kolejka, okno „＋” oraz wpływ na „Moje miksy”.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V714ImportTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(100) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    private val SPOTIFY_HTML = """<html><script id="__NEXT_DATA__" type="application/json">${
        """{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Moje rockowe","trackList":[
            {"title":"Nothing Else Matters","subtitle":"Metallica","duration":388000},
            {"title":"One","subtitle":"Metallica","duration":447000},
            {"title":"Zupełnie Nieistniejący Utwór","subtitle":"Nikt","duration":200000}]}}}}}}"""
    }</script></html>"""

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path!!.startsWith("/embed/playlist/")) MockResponse().setBody(SPOTIFY_HTML) else MockResponse().setResponseCode(404)
        } }
        server.start()
        StubeImport.spotifyEmbedBase = server.url("/embed").toString().trimEnd('/')
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeInternal = true; Prefs.remotePin = "4321"; Prefs.ytDislikes = ""
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String): List<YtVideo> = when {
                q.contains("Nothing Else", true) -> listOf(YtVideo("ne1", "Metallica – Nothing Else Matters (Official Music Video)", "Metallica", duration = "PT6M28S"))
                q.contains("One", true) && q.contains("Metallica", true) -> listOf(YtVideo("one1", "Metallica – One (Official Video)", "Metallica", duration = "PT7M27S"))
                else -> listOf(YtVideo("zz", "Poradnik – jak gotować", "Kucharz", duration = "PT10M"))
            }
            override fun playlist(id: String) = "Playlista testowa" to (1..4).map { YtVideo("pl$it", "Wykonawca $it – Utwór $it", "Wykonawca $it", duration = "PT3M") }
        }
    }

    @After fun tearDown() {
        StubeImport.onPending = null; StubeImport.reset(); StubeImportUi.current?.dismiss()
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache(); Prefs.ytDislikes = ""
        StubeImport.spotifyEmbedBase = "https://open.spotify.com/embed"
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- rozpoznanie i dopasowanie ----------------

    @Test fun linksAreClassifiedAndM3uStaysUntouched() {
        assertEquals(StubeImport.KIND_YT, StubeImport.classify("Zobacz: https://www.youtube.com/playlist?list=PLabcdefghij123 fajne")?.kind)
        assertEquals("PLabcdefghij123", StubeImport.classify("https://music.youtube.com/watch?v=abcdefghijk&list=PLabcdefghij123")?.id)
        assertEquals(StubeImport.KIND_SPOTIFY, StubeImport.classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc")?.kind)
        assertNull(StubeImport.classify("http://example.com/lista.m3u"))              // lista M3U – nie playlista muzyczna
        assertNull(StubeImport.classify("https://www.youtube.com/watch?v=abcdefghijk")) // pojedynczy film bez listy
        assertTrue(StubeImport.looksLikeTrackList("Metallica - One\nQueen - Radio Ga Ga"))
        assertFalse(StubeImport.looksLikeTrackList("Metallica - One"))
    }

    @Test fun spotifyPageIsParsedAndMatchedToYoutubeTracks() {
        val (name, tracks) = StubeImport.parseSpotify(SPOTIFY_HTML)
        assertEquals("Moje rockowe", name); assertEquals(3, tracks.size); assertEquals(388, tracks[0].durationSec)
        val p = runBlocking { StubeImport.resolve("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") }
        assertTrue(p.ok); assertEquals("Moje rockowe", p.name)
        assertEquals(listOf("ne1", "one1"), p.videos.map { it.id })                    // dopasowane
        assertEquals(1, p.missed.size); assertTrue(p.missed[0].contains("Nieistniejący")) // pominięty (brak pewnego wyniku)
    }

    @Test fun youtubePlaylistAndTrackListAndErrorsGivePreview() {
        val yt = runBlocking { StubeImport.resolve("https://www.youtube.com/playlist?list=PLabcdefghij123") }
        assertTrue(yt.ok); assertEquals("Playlista testowa", yt.name); assertEquals(4, yt.videos.size)
        val txt = runBlocking { StubeImport.resolve("Metallica - Nothing Else Matters\nMetallica - One") }
        assertEquals(2, txt.videos.size); assertEquals(StubeImport.KIND_TEXT, txt.kind)
        val bad = runBlocking { StubeImport.resolve("https://example.com/cos") }
        assertFalse(bad.ok); assertNotNull(bad.error)
        YtKeyless.source = object : YtKeyless.Source { override fun kiosk(id: String) = emptyList<YtVideo>(); override fun search(q: String) = emptyList<YtVideo>()
            override fun playlist(id: String): Pair<String, List<YtVideo>> = throw java.io.IOException("403") }
        val denied = runBlocking { StubeImport.resolve("https://www.youtube.com/playlist?list=PLabcdefghij123") }
        assertFalse(denied.ok); assertTrue(denied.error!!.contains("Brak dostępu"))
    }

    // ---------------- ten sam kanał co M3U ----------------

    @Test fun playlistLinkSentLikeAnM3uListGoesToStubeNotToChannelLists() {
        RemoteServer.init(ctx)
        val before = Playlists.all().size
        val res = RemoteServer.receive("4321", "", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", ByteArray(0), "", "", false)
        assertTrue(res.first); assertTrue(res.second.contains("STube"))
        assertEquals(before, Playlists.all().size)                                     // lista kanałów nie powstała
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", StubeImport.pending)
        assertFalse(RemoteServer.receive("0000", "", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", ByteArray(0), "", "", false).first) // zły PIN
        // zwykła lista M3U przez link działa jak dawniej
        StubeImport.takePending()
        val m3u = RemoteServer.receive("4321", "Lista", "http://example.com/lista.m3u", ByteArray(0), "", "", false)
        assertTrue(m3u.first); assertNull(StubeImport.pending); assertEquals(before + 1, Playlists.all().size)
    }

    // ---------------- Room, kolejka, miksy ----------------

    @Test fun savedPlaylistSurvivesAndShapesMixes() {
        val p = runBlocking { StubeImport.resolve("https://www.youtube.com/playlist?list=PLabcdefghij123") }
        val saved = runBlocking { StubeImport.save(p) }
        assertNotNull(saved)
        val again = runBlocking { StubeImport.save(p) }                                  // to samo źródło – nie dubluje
        assertEquals(1, runBlocking { StubeImport.all() }.size); assertNotEquals(saved!!.id, again!!.id)
        assertEquals(4, runBlocking { StubeImport.all() }.first().videos().size)
        assertEquals(1, STubeScreen.importedTiles().size)
        assertTrue(STubeScreen.hasMixHistory())                                          // miksy dostępne od razu, bez historii
        val mixes = runBlocking { StubeMixes.current() }
        val mine = mixes.firstOrNull { it.id == "mine" }
        assertNotNull(mine); assertEquals(4, mine!!.local.size)
        // gust: wykonawcy z przesłanej playlisty to silny sygnał
        val prof = StubeTaste.current()
        assertTrue(prof.artistLikes.containsKey("wykonawca 1"))
        // usunięcie playlisty zdejmuje sygnał
        runBlocking { StubeImport.remove(again.id) }
        assertTrue(runBlocking { StubeMixes.current() }.none { it.id == "mine" })
    }

    // ---------------- okno ＋ ----------------

    private fun stube(): STubeActivity = Robolectric.buildActivity(STubeActivity::class.java).setup().visible().get()

    @Test fun plusIconOpensImportWindowShowsPreviewSavesAndStartsQueue() {
        val a = stube(); idle(500)
        val plus = a.findViewById<View>(pl.iptv.tv.R.id.stAdd)
        assertNotNull(plus); assertTrue(plus.isFocusable || plus.isClickable)
        plus.performClick(); idle()
        val d: AlertDialog = StubeImportUi.current!!
        assertTrue(d.isShowing)
        // link wpisany ręcznie → podgląd
        val input = findEdit(d.window!!.decorView)!!
        input.setText("https://www.youtube.com/playlist?list=PLabcdefghij123")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor { d.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("Odtwórz") }
        assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("Odtwórz"))
        // „Zapisz” – tylko zapis na stałe
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        waitFor { StubeImport.cached.isNotEmpty() }
        assertEquals(1, StubeImport.cached.size); assertEquals("Playlista testowa", StubeImport.cached[0].name)
        assertFalse(d.isShowing)
    }

    @Test fun receivedLinkIsPickedUpByTheWindowAutomatically() {
        val a = stube(); idle(500)
        StubeImport.offer("https://www.youtube.com/playlist?list=PLabcdefghij123")
        idle()
        val d = StubeImportUi.current
        assertNotNull(d)
        waitFor { d!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("Odtwórz") }
        assertTrue(d!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("Odtwórz"))
        assertNull(StubeImport.pending)
        assertNotNull(a)
    }

    private fun findEdit(v: View): android.widget.EditText? {
        if (v is android.widget.EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
        return null
    }
}

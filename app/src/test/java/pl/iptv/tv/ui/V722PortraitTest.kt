package pl.iptv.tv.ui

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtLyrics
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.io.File
import java.time.Duration

/**
 * 7.22 – telefon w pionie (9:16): dolny pasek nawigacji (Live TV, Stremio, STube, Do obejrzenia, Więcej), Live TV z pigułkami
 * kategorii, pionowy feed STube z kartami 16:9 i klawiaturą systemową, odtwarzacz STube z obrazem 16:9 u góry i zakładkami
 * (Następne wideo / Tekst / Tryb muzyczny), obrót bez restartu strumienia. TV i poziomo – układ bez zmian.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V722PortraitTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/lyrics/get") -> MockResponse().setBody(JSONObject().put("plainLyrics", "Raz\nDwa\nTrzy")
                    .put("syncedLyrics", "[00:00.00] Raz\n[00:01.50] Dwa\n[10:00.00] Trzy").toString())
                request.path!!.startsWith("/sb/") -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(404)
            }
        } }
        server.start()
        YtLyrics.apiBase = server.url("/lyrics").toString().trimEnd('/'); YtLyrics.fallbackBase = ""
        pl.iptv.tv.data.SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.mainLayout = 0; Prefs.orientation = 2; Prefs.stubeResume = false; Prefs.stubeInternal = true; Prefs.ytMusicView = false; Prefs.ytLyrics = true
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..6).map { YtVideo("k$id-$it", "Kiosk $it", "Kanał $it", duration = "PT3M", views = 1_250_000L) }
            override fun search(q: String) = (1..8).map { YtVideo("s$it", "$q wynik $it", "Artysta $it", duration = "PT4M", views = 35_000L) }
            override fun related(id: String) = (1..4).map { YtVideo("r$id-$it", "Po $id $it", "Inny $it", duration = "PT3M") }
        }
        val f = File(ctx.filesDir, "v722p.m3u").apply { writeText((1..6).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"p$it\" group-title=\"Grupa ${it % 2}\",Kanał $it\nhttp://v722p/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        YtLyrics.apiBase = "https://lrclib.net/api"; YtLyrics.fallbackBase = "https://api.lyrics.ovh/v1"
        Prefs.orientation = 0; MobilePortraitScaffold.forced = null; TvResolution.resetForTest()
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- izolacja: TV i poziomo bez zmian ----------------

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvKeepsSideNavAndNoBottomBar() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle(400)
        assertFalse(a.portraitPhone); assertNull(a.portraitNav); assertNull(a.portraitChips)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sideNav).visibility)
        assertTrue(all(a.window.decorView).none { it.tag == MobilePortraitScaffold.TAG })
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun landscapePhoneKeepsLayout() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle(400)
        assertFalse(a.portraitPhone); assertNull(a.portraitNav)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.sideNav).visibility)
        assertTrue((a.findViewById<RecyclerView>(R.id.groups).layoutManager as LinearLayoutManager).orientation == LinearLayoutManager.VERTICAL)
    }

    // ---------------- pion: nawigacja i Live TV ----------------

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitLiveTvBottomBarAndCategoryChips() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        assertTrue(a.portraitPhone)
        val nav = a.portraitNav!!
        val tabs = (0 until nav.childCount).map { nav.getChildAt(it) }
        assertEquals(MobilePortraitScaffold.Tab.entries.map { MobilePortraitScaffold.TAB_TAG + it.name }, tabs.map { it.tag })
        assertEquals(listOf("Live TV", "Stremio", "STube", "Do obejrzenia", "Więcej"), tabs.map { it.contentDescription })
        assertTrue(tabs[0].isSelected); assertFalse(tabs[1].isSelected)
        // treść kończy się nad paskiem, pasek przy dolnej krawędzi
        assertEquals(android.view.Gravity.BOTTOM, (nav.layoutParams as FrameLayout.LayoutParams).gravity)
        // boczne przyciski TV ukryte; u góry „Szukaj” + kategorie w poziomym rzędzie pigułek; lista kanałów na całą szerokość
        assertEquals(View.GONE, a.findViewById<View>(R.id.sideNav).visibility)
        val groups = a.findViewById<RecyclerView>(R.id.groups)
        assertEquals(LinearLayoutManager.HORIZONTAL, (groups.layoutManager as LinearLayoutManager).orientation)
        assertTrue(all(a.portraitChips!!).any { it.getTag(R.id.row_key) == "portrait_search" })
        waitFor { (groups.adapter?.itemCount ?: 0) > 0 }
        assertTrue((a.findViewById<RecyclerView>(R.id.channels).adapter?.itemCount ?: 0) > 0)
        // zakładka STube → ekran STube na wierzchu
        tabs[2].performClick(); idle()
        assertEquals(STubeActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitStubeFeedCardsAndSystemKeyboard() {
        val c = Robolectric.buildActivity(STubeActivity::class.java).setup(); idle(500)
        val a = c.get()
        assertTrue(a.portraitNav!!.getChildAt(2).isSelected)
        val q = a.findViewById<android.widget.EditText>(R.id.stQuery)
        assertTrue(q.showSoftInputOnFocus)                                  // klawiatura systemowa telefonu
        assertEquals(View.GONE, a.findViewById<View>(R.id.stClose).visibility)
        waitFor { all(a.window.decorView).any { it.getTag(R.id.row_key) == STubeTileAdapter.FEED_TAG } }
        val card = all(a.window.decorView).first { it.getTag(R.id.row_key) == STubeTileAdapter.FEED_TAG }
        val texts = all(card).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.any { it.contains("1,3 mln wyświetleń") })
        assertTrue(texts.any { it == "3:00" })                              // czas trwania na miniaturze
        // miniatura 16:9 na całą szerokość karty
        val thumb = all(card).filterIsInstance<android.widget.ImageView>().first()
        assertEquals(thumb.layoutParams.width * 9 / 16, thumb.layoutParams.height)
        c.pause().stop().destroy()
    }

    @Test fun viewsLabelsInPolish() {
        assertNull(STubeScreen.viewsLabel(0))
        assertEquals("870 wyświetleń", STubeScreen.viewsLabel(870))
        assertEquals("35 tys. wyświetleń", STubeScreen.viewsLabel(35_400))
        assertEquals("2 mln wyświetleń", STubeScreen.viewsLabel(2_000_000))
        assertEquals("1,3 mln wyświetleń", STubeScreen.viewsLabel(1_250_000))
    }

    // ---------------- odtwarzacz STube w pionie ----------------

    private fun player(title: String, channel: String = "Artist - Topic", id: String = "abcdefghijk"): org.robolectric.android.controller.ActivityController<YtPlayerActivity> {
        YtStreams.resolverOverride = { YtPlayable.Muxed(title, 300, YtCandidate(server.url("/v.mp4").toString(), 360), emptyList(),
            (1..3).map { YtVideo("rel$it", "Wykonawca $it – Utwór $it (Official Audio)", "Wykonawca $it", duration = "PT3M") }, channel = channel) }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, title, channel))).setup().visible()
        waitFor { YtEngine.playable != null && YtEngine.video?.id == id && c.get().player != null }
        idle(300)
        return c
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitPlayerPinnedVideoTabsLyricsAndRotationWithoutRestart() {
        val c = player("Wykonawca – Piosenka pionowa (Official Video)")
        val a = c.get()
        assertTrue(a.portraitActive)
        // telefon z orientacją pionową w Ustawieniach – odtwarzacz obraca się także do pionu
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER, a.requestedOrientation)
        val panel = a.portraitPanel!!
        assertEquals(View.VISIBLE, panel.visibility)
        val pv = all(a.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().first()
        val w = a.resources.displayMetrics.widthPixels
        assertEquals(w * 9 / 16, pv.layoutParams.height)                  // obraz 16:9 przypięty u góry
        assertEquals(w * 9 / 16, (panel.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals("Wykonawca – Piosenka pionowa (Official Video)", panel.titleView.text.toString())
        assertTrue(panel.actions.childCount == 1)                          // pasek ikon w strefie kciuka
        val bar = panel.actions.getChildAt(0) as android.view.ViewGroup
        assertEquals(listOf("music", "lyrics", "quality", "engine", "rotate").map { PortraitPlayerUi.ICON_TAG + it }, (0 until bar.childCount).map { bar.getChildAt(it).tag })
        all(bar).first { it.tag == PortraitPlayerUi.ICON_TAG + "quality" }.performClick(); idle()
        assertTrue(all(a.lastSheet!!.root).filterIsInstance<android.widget.TextView>().any { it.text == "Prędkość odtwarzania" }); a.lastSheet!!.dismiss()
        assertEquals(panel.childCount - 1, panel.indexOfChild(panel.actions))  // na dole panelu
        // zakładki: Następne wideo (bieżący + propozycje), Tekst (zsynchronizowany), Tryb muzyczny
        val tabs = all(panel).filter { (it.getTag(R.id.row_key) as? String)?.startsWith("yt_tab:") == true }
        assertEquals(YtPortraitPanel.TAB_NAMES, tabs.map { (it as android.widget.TextView).text.toString() })
        tabs[1].performClick(); waitFor { panel.lyricLines == 3 }
        assertEquals(3, panel.lyricLines); assertEquals(YtPortraitPanel.TAB_LYRICS, panel.tab)
        panel.updateLine(force = true); assertTrue(panel.currentLine >= 0)
        tabs[2].performClick(); idle()
        assertTrue(a.stageShown)                                           // tryb muzyczny – pełny odtwarzacz audio
        val player = YtEngine.player; val playable = YtEngine.playable
        // obrót do poziomu: ten sam odtwarzacz i strumień, dawny układ (obraz na cały ekran, panel ukryty), widok muzyczny – układ szeroki
        RuntimeEnvironment.setQualifiers("w800dp-h400dp-land-xhdpi")
        c.configurationChange(Configuration(a.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }); idle(300)
        assertFalse(a.portraitActive)
        assertEquals(View.GONE, panel.visibility)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, pv.layoutParams.height)
        assertSame(player, YtEngine.player); assertSame(playable, YtEngine.playable)
        assertTrue(a.stageShown)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvPlayerUnchanged() {
        val c = player("Film na TV", channel = "Kanał")
        val a = c.get()
        assertFalse(a.portraitActive); assertEquals(View.GONE, a.portraitPanel!!.visibility)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, all(a.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().first().layoutParams.height)
        c.pause().stop().destroy()
    }
}

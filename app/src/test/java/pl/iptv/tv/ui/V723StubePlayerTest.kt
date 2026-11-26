package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
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
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/**
 * 7.23 – odtwarzacz STube: pasek w pionie (Muzyka, Tekst, Opcje odtwarzania, Obróć), zakładki Następne wideo / Dodaj do kolejki
 * (wyszukiwarka, „Dodaj” / „Dodaj i odtwórz następnie”, kolejność FIFO za bieżącym), pigułka „Co następuje” od 95 % przez 10 s
 * z ładującym się obramowaniem, bez koła zębatego i napisu ładowania, podwójne stuknięcie po bokach = przewijanie.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723StubePlayerTest {
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
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]")
                else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        } }
        server.start()
        pl.iptv.tv.data.SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.orientation = 2; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.ytLyrics = false; Prefs.ytMusicView = false; Prefs.queueAutoplay = true
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = (1..5).map { YtVideo("$q-$it", "$q utwór $it", "Wykonawca $it", duration = "PT3M") }
        }
        YtStreams.resolverOverride = { id -> YtPlayable.Muxed("Film $id", 200, YtCandidate(server.url("/$id.mp4").toString(), 360), emptyList(), emptyList(), channel = "Kanał") }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        Prefs.orientation = 0; MobilePortraitScaffold.forced = null; TvResolution.resetForTest()
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun player(id: String = "abcdefghijk") = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, "Film $id", "Kanał"))).setup().visible()
        .also { c -> waitFor { YtEngine.playable != null && YtEngine.video?.id == id && c.get().player != null }; idle(300) }

    private fun clickText(root: View, text: String) {
        var v: View? = all(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick(); idle()
    }

    // ---------------- kolejka: „Odtwórz następnie” w kolejności dodania ----------------

    @Test fun playNextKeepsInsertionOrderRightAfterCurrent() = runBlocking {
        StubeQueue.clear()
        StubeQueue.add(QueueItem.of(YtVideo("end1", "Koniec 1", "")))
        StubeQueue.add(QueueItem.of(YtVideo("n1", "Następny 1", "")), next = true)
        StubeQueue.add(QueueItem.of(YtVideo("n2", "Następny 2", "")), next = true)
        StubeQueue.add(QueueItem.of(YtVideo("end2", "Koniec 2", "")))
        StubeQueue.add(QueueItem.of(YtVideo("n3", "Następny 3", "")), next = true)
        assertEquals(listOf("n1", "n2", "n3", "end1", "end2"), StubeQueue.items().map { it.ref })
        // pierwszy zdjęty (zagrany) – kolejny „następnie” trafia za pozostałymi dodanymi w ten sposób
        assertEquals("n1", StubeQueue.pop()?.ref)
        StubeQueue.add(QueueItem.of(YtVideo("n4", "Następny 4", "")), next = true)
        assertEquals(listOf("n2", "n3", "n4", "end1", "end2"), StubeQueue.items().map { it.ref })
    }

    // ---------------- pasek, zakładki, wyszukiwarka ----------------

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitBarTabsAndAddToQueueSearch() {
        runBlocking { StubeQueue.clear() }
        val c = player(); val a = c.get()
        assertTrue(a.portraitActive)
        val panel = a.portraitPanel!!
        val bar = panel.actions.getChildAt(0) as android.view.ViewGroup
        assertEquals(listOf("music", "lyrics", "options", "cast", "rotate").map { PortraitPlayerUi.ICON_TAG + it }, (0 until bar.childCount).map { bar.getChildAt(it).tag })   // (7.25) cast – ukryta bez urządzeń
        assertEquals(listOf("Muzyka", "Tekst", "Opcje odtwarzania", "Przesyłaj", "Obróć"), (0 until bar.childCount).map { bar.getChildAt(it).contentDescription?.toString() ?: all(bar.getChildAt(it)).filterIsInstance<TextView>().first().text.toString() })
        // bez koła zębatego Media3 i bez napisu ładowania na środku
        assertFalse(a.settingsGearShown)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().none { it.isShown && it.text.contains("Wczytywanie") })
        // „Opcje odtwarzania” – to samo okno co ☰ w widoku muzycznym, także nad zwykłym wideo; WSTECZ zamyka
        all(bar).first { it.tag == PortraitPlayerUi.ICON_TAG + "options" }.performClick(); idle()
        assertFalse(a.stageShown)
        assertTrue(a.stage.optionsOpen)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Prędkość" && it.isShown })
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)); idle()
        assertFalse(a.stage.optionsOpen)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().none { it.text == "Prędkość" && it.isShown })
        assertFalse(a.isFinishing)
        // zakładki: Następne wideo + Dodaj do kolejki (bez „Tekst” i „Tryb muzyczny”)
        val tabs = all(panel).filter { (it.getTag(R.id.row_key) as? String)?.startsWith("yt_tab:") == true }
        assertEquals(listOf("Następne wideo", "Dodaj do kolejki"), tabs.map { (it as TextView).text.toString() })
        tabs[1].performClick(); idle()
        assertEquals(YtPortraitPanel.TAB_ADD, panel.tab)
        assertTrue(panel.searchField.isShown)
        // wyszukiwanie → wynik → arkusz „Dodaj” / „Dodaj i odtwórz następnie”
        panel.searchField.setText("rock"); waitFor { panel.searchResults.isNotEmpty() }
        assertEquals("rock-1", panel.searchResults.first().id)
        panel.pickResult(panel.searchResults[0]); idle()
        val sheet = a.lastSheet!!
        val labels = all(sheet.root).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains(YtPlayerActivity.QUEUE_ADD)); assertTrue(labels.contains(YtPlayerActivity.QUEUE_ADD_NEXT))
        clickText(sheet.root, YtPlayerActivity.QUEUE_ADD_NEXT)
        waitFor { StubeQueue.cached.any { it.ref == "rock-1" } }
        assertEquals("", panel.searchField.text.toString())                  // pole puste – od razu kolejny utwór
        assertTrue(panel.searchResults.isEmpty())
        // drugi „następnie” – za pierwszym; „Dodaj” – na koniec
        panel.searchField.setText("pop"); waitFor { panel.searchResults.isNotEmpty() }
        panel.pickResult(panel.searchResults[1]); idle(); clickText(a.lastSheet!!.root, YtPlayerActivity.QUEUE_ADD_NEXT)
        waitFor { StubeQueue.cached.any { it.ref == "pop-2" } }
        panel.searchField.setText("jazz"); waitFor { panel.searchResults.isNotEmpty() }
        panel.pickResult(panel.searchResults[0]); idle(); clickText(a.lastSheet!!.root, YtPlayerActivity.QUEUE_ADD)
        waitFor { StubeQueue.cached.any { it.ref == "jazz-1" } }
        assertEquals(listOf("rock-1", "pop-2", "jazz-1"), StubeQueue.cached.map { it.ref })
        c.pause().stop().destroy()
    }

    // ---------------- pigułka „Co następuje / Proponowane” ----------------

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun upNextPillOnlyFrom95PercentForTenSecondsWithLoadingBorder() {
        runBlocking { StubeQueue.clear(); StubeQueue.add(QueueItem.of(YtVideo("nx1", "Film nx1", "Kanał"))) }
        val c = player(); val a = c.get()
        val pill = a.upNextPill!!
        // kontrolki (pasek) – bez pigułki
        all(a.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().first().showController(); idle()
        assertFalse(pill.shown)
        a.checkUpNext(posMs = 50_000, durMs = 100_000); idle()
        assertFalse(pill.shown)
        a.checkUpNext(posMs = 94_000, durMs = 100_000); idle()
        assertFalse(pill.shown)
        // 95 % – pigułka po stronie następnego (prawa, dół), obramowanie ładuje się przez 10 s
        a.checkUpNext(posMs = 95_000, durMs = 100_000); idle(100)
        assertTrue(pill.shown)
        assertTrue(pill.text.toString().startsWith("⏭ Co następuje:  ▶ Film nx1"))
        assertEquals(android.view.Gravity.END or android.view.Gravity.BOTTOM, (pill.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
        idle(5_000)
        assertTrue(pill.shown); assertTrue(pill.progress in 0.3f..0.7f)
        idle(5_500)
        assertFalse(pill.shown)
        // raz na utwór – nie wraca przy dalszym odtwarzaniu
        a.checkUpNext(posMs = 98_000, durMs = 100_000); idle()
        assertFalse(pill.shown)
        c.pause().stop().destroy()
    }

    // ---------------- (7.23) orientacja: „Obróć” bez powrotu do poziomu ----------------

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun rotateButtonIsReleasedByBackAndWhenLeavingScreen() {
        val c = player(); val a = c.get()
        val full = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER; val land = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        assertEquals(full, a.requestedOrientation)
        PortraitPlayerUi.rotateToLandscape(a); idle()
        assertEquals(land, a.requestedOrientation); assertTrue(OrientationPolicy.isForced(a))
        // WSTECZ – z powrotem orientacja z Ustawień, odtwarzacz zostaje
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(full, a.requestedOrientation); assertFalse(a.isFinishing)
        // „Obróć”, potem wyjście z ekranu (inny ekran / HOME) i powrót – bez „zapamiętanego” poziomu
        PortraitPlayerUi.rotateToLandscape(a); idle()
        c.pause().stop(); idle(); c.start().resume(); idle()
        assertFalse(OrientationPolicy.isForced(a))
        assertEquals(full, a.requestedOrientation)
        c.pause().stop().destroy()
    }

    // ---------------- podwójne stuknięcie ----------------

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun doubleTapOnSidesSeeksBackAndForward() {
        val c = player(); val a = c.get()
        val d = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); d.layout(0, 0, w, h)
        val pv = all(d).filterIsInstance<androidx.media3.ui.PlayerView>().first()
        val loc = IntArray(2); pv.getLocationInWindow(loc)
        val y = loc[1] + pv.height / 2f
        val exo = YtEngine.player!!
        exo.seekTo(30_000); idle()
        // środek – bez przewijania
        assertFalse(a.doubleTapAt(loc[0] + pv.width / 2f, y))
        // prawy bok – do przodu, lewy – do tyłu (plakietka przewijania)
        assertTrue(a.doubleTapAt(loc[0] + pv.width * 0.9f, y)); idle()
        assertEquals(40_000, exo.currentPosition)
        assertTrue(a.seekBarShown)
        assertTrue(a.doubleTapAt(loc[0] + pv.width * 0.1f, y)); idle()
        assertEquals(30_000, exo.currentPosition)
        c.pause().stop().destroy()
    }
}

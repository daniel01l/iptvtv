package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
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
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeMixes
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/**
 * 6.4: odtwarzanie w usłudze (ekran tylko klientem – WSTECZ / HOME nie zatrzymują dźwięku), mini-player, OK pokazuje pasek
 * (bez pauzy), tytuł i kanał, „Co następuje”, PiP, jakość / audio / napisy, miksy z historii, klawiatura wyszukiwarki.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V64Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }   // otwarta od razu – zamknięcie w tearDown nie trafia na otwieranie w tle (zakleszczenie Room)
        StubeHistory.useDatabase(db)
        server = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]") else MockResponse().setResponseCode(404)
        } }
        server.start()
        SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.ytQuality = 0; Prefs.ytSubLang = ""; Prefs.ytApiKey = ""
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..3).map { YtVideo("$id-$it", "$id $it", "Kanał", "", "", "PT3M") }
            override fun search(q: String) = (1..4).map { YtVideo("${q.hashCode()}-$it", "$q wynik $it", "K", "", "", "PT4M") }
        }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null
        YtEngine.releasePlayer(); YtEngine.listeners.clear()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun v(id: String) = YtVideo(id, "Film $id", "Kanał $id", "", duration = "PT3M")

    private fun player(chapters: Boolean = false, id: String = "abcdefghijk"): org.robolectric.android.controller.ActivityController<YtPlayerActivity> {
        YtStreams.resolverOverride = { YtPlayable.Muxed("Tytuł filmu", 300, YtCandidate(server.url("/v.mp4").toString(), 360),
            if (chapters) listOf(pl.iptv.tv.data.YtChapter(0, "A"), pl.iptv.tv.data.YtChapter(60_000, "B")) else emptyList(),
            listOf(v("rel1")), channel = "Kanał Autora") }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, YtVideo(id, "Tytuł filmu", "Kanał Autora"))).setup()
        waitFor { YtEngine.playable != null && c.get().player != null }
        idle(300)
        return c
    }

    private fun pv(v: View): androidx.media3.ui.PlayerView? = v as? androidx.media3.ui.PlayerView
        ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { pv(g.getChildAt(it)) } }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out += v
        if (v is android.view.ViewGroup) (0 until v.childCount).forEach { texts(v.getChildAt(it), out) }
        return out
    }

    // ---------------- usługa: ekran jest tylko klientem ----------------

    @Test fun backAndHomeDoNotPauseOrRelease() {
        val c = player()
        val a = c.get()
        val exo = YtEngine.player!!
        assertTrue(exo.playWhenReady)
        // HOME: onStop – tylko odpięcie obrazu i kontrolera
        c.pause().stop(); idle()
        assertNull(a.client); assertTrue(exo.playWhenReady); assertSame(exo, YtEngine.player); assertTrue(YtEngine.background)
        // powrót – ten sam odtwarzacz bez ponownego wczytania
        c.start().resume(); idle()
        assertSame(exo, a.player); assertFalse(YtEngine.background)
        // WSTECZ: ekran się zamyka, film gra dalej (mini-player)
        a.onBackPressedDispatcher.onBackPressed(); c.pause().stop().destroy(); idle()
        assertTrue(exo.playWhenReady); assertEquals("abcdefghijk", YtEngine.video?.id)
    }

    @Test fun miniPlayerInStubeControlsBackgroundPlayback() {
        player().let { it.pause().stop().destroy() }; idle()
        val s = Robolectric.buildActivity(STubeActivity::class.java).setup(); idle(500)
        val bar = texts(s.get().window.decorView).firstOrNull { it.text == "▶ TERAZ ODTWARZANE" }?.parent?.parent?.parent as? MiniPlayerBar
        assertNotNull(bar); bar!!
        assertTrue(bar.shown)
        assertTrue(texts(bar).any { it.text == "Tytuł filmu" }); assertTrue(texts(bar).any { it.text == "Kanał Autora" })
        assertTrue(bar.playBtn.isFocusable && bar.openBtn.isFocusable && bar.stopBtn.isFocusable)
        // (7.17) pływająca kapsuła: nad treścią w prawym dolnym rogu, ≤ 520 dp, 20 dp od krawędzi, szkło 72 %
        val lp = bar.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.END, lp.gravity)
        assertTrue(lp.width <= Ui.dp(520)); assertEquals(Ui.dp(20), lp.bottomMargin); assertEquals(Ui.dp(20), lp.rightMargin)
        val bg = bar.background as android.graphics.drawable.GradientDrawable
        assertEquals(0xB8, android.graphics.Color.alpha(bg.color!!.defaultColor))
        // treść ekranu się nie kurczy (bez dolnego odstępu całego ekranu); przewijane listy – odstęp na przewinięcie ponad kapsułę
        val content = bar.parent as android.view.ViewGroup
        val main = (0 until content.childCount).map { content.getChildAt(it) }.first { it !== bar && it is android.widget.LinearLayout || it is android.widget.FrameLayout && it !== bar }
        assertEquals(0, main.paddingBottom)
        // ⏯ – okrągły przycisk 36 dp w kolorze akcentu z rysowaną ikoną (bez emoji)
        assertEquals("", bar.playBtn.text.toString()); assertTrue(bar.playBtn.compoundDrawables[0] is PlayerIcon)
        assertEquals(Ui.dp(36), bar.playBtn.layoutParams.width); assertEquals(Ui.dp(36), bar.playBtn.layoutParams.height)
        assertEquals(true, bar.playingIcon)
        // ⏯ – pauza w tle (ikona: odtwarzanie)
        bar.playBtn.performClick(); idle()
        assertFalse(YtEngine.player!!.playWhenReady)
        assertEquals(false, bar.playingIcon)
        // ⛶ – pełny ekran (bieżący film, bez ponownego wczytania)
        bar.openBtn.performClick(); idle()
        assertTrue(shadowOf(s.get()).nextStartedActivity.getBooleanExtra(YtPlayerActivity.EXTRA_RESUME_CURRENT, false))
        // ✖ – koniec odtwarzania, pasek znika
        bar.stopBtn.performClick(); idle(1200)
        assertNull(YtEngine.video); assertFalse(bar.shown)
        s.pause().stop().destroy()
    }

    // ---------------- pilot: OK, tytuł, Co następuje, PiP ----------------

    @Test fun okOnlyShowsControllerAndTitleVisible() {
        runBlocking { StubeQueue.add(QueueItem.of(v("nx1"))) }
        val a = player().get()
        val view = pv(a.window.decorView)!!
        view.controllerAutoShow = false; view.hideController(); idle()
        val playing = YtEngine.player!!.playWhenReady
        // OK przy ukrytym pasku – tylko pokazanie paska (bez pauzy)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        assertTrue(view.isControllerFullyVisible)
        assertEquals(playing, YtEngine.player!!.playWhenReady)
        // Enter – tak samo
        view.hideController(); idle()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)); idle()
        assertEquals(playing, YtEngine.player!!.playWhenReady)
        // Play/Pause na pilocie – pauza
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); idle()
        assertEquals(!playing, YtEngine.player!!.playWhenReady)
        // nakładka: tytuł (duży), kanał, „Co następuje” z kolejki
        val all = texts(a.window.decorView)
        val title = all.first { it.text == "Tytuł filmu" }
        assertTrue(title.textSize >= Ui.dp(24)); assertTrue(title.isShown)
        assertTrue(all.any { it.text.toString() == "📺 Kanał Autora" && it.isShown })
        assertTrue(all.any { it.text.startsWith("⏭ Co następuje:  ▶ Film nx1") && it.isShown })
        // przyciski: jakość, napisy/audio (wg ścieżek), PiP tylko z obsługą systemu
        // 7.0: kapsuły z ikoną (bez emoji) – jakość „Auto …”, autoodtwarzanie
        assertTrue(all.any { it.text.startsWith("Auto") && it.isFocusable && it.compoundDrawables[0] is PlayerIcon })
        assertTrue(all.any { it.text == "Autoodtwarzanie: wł." && it.isFocusable })
        val pip = all.first { it.text == "Okno" }
        assertEquals(if (a.pipSupported) View.VISIBLE else View.GONE, pip.visibility)
    }

    @Test fun pipButtonEntersPictureInPicture() {
        shadowOf(ctx.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val a = player().get()
        assertTrue(a.pipSupported)
        a.enterPip(); idle()
        assertTrue(a.isInPictureInPictureMode)
        assertTrue(YtEngine.player!!.playWhenReady)      // film gra dalej w oknie
    }

    @Test fun qualityAndTrackParams() {
        val a = player().get()
        val p = YtEngine.player!!
        a.setQuality(720)
        assertEquals(720, Prefs.ytQuality); assertEquals(720, p.trackSelectionParameters.maxVideoHeight)
        a.setQuality(0)
        assertEquals(YtStreams.maxHeight, p.trackSelectionParameters.maxVideoHeight)
        // napisy: wyłączenie = typ tekstu wyłączony
        a.selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, null)
        assertTrue(p.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
        assertEquals("", Prefs.ytSubLang)
        // nowy film: jakość z Ustawień, preferowany oryginalny dźwięk
        Prefs.ytQuality = 480
        val split = YtPlayable.Split("x", 10, YtCandidate("v", 1080), YtCandidate("a", lang = "en", label = "English"))
        val params = YtEngine.trackParams(p, split)
        assertEquals(480, params.maxVideoHeight); assertEquals(listOf("en"), params.preferredAudioLanguages)
    }

    @Test fun streamAlternativesAndManifestLanguage() {
        val vids = listOf(YtCandidate("1080a", 1080, codec = "avc1"), YtCandidate("1080v", 1080, codec = "vp9"), YtCandidate("720v", 720, codec = "vp9"),
            YtCandidate("720a", 720, codec = "avc1"), YtCandidate("480a", 480, codec = "avc1"), YtCandidate("2160v", 2160, codec = "vp9"))
        val alts = YtStreams.videoAlternatives(vids, vids[0], cap = 1080)
        assertEquals(listOf("720a", "480a"), alts.map { it.url })              // bez wybranej wysokości, H.264 przed VP9, ≤ limitu
        val auds = listOf(YtCandidate("o", lang = "en", original = true), YtCandidate("pl1", lang = "pl", original = false, bitrate = 1),
            YtCandidate("pl2", lang = "pl", original = false, bitrate = 9), YtCandidate("x", lang = ""))
        assertEquals(listOf("pl2"), YtStreams.audioTracks(auds, auds[0]).map { it.url })
        val mpd = """<MPD><Period><AdaptationSet id="0" mimeType="audio/mp4"><Representation/></AdaptationSet></Period></MPD>"""
        val out = YtMedia.withLanguage(mpd, YtCandidate("a", lang = "pl", label = "polski", original = false))
        assertTrue(out.contains("""<AdaptationSet id="0" mimeType="audio/mp4" lang="pl"><Label>polski</Label>"""))
        assertEquals(mpd, YtMedia.withLanguage(mpd, YtCandidate("v", 720, videoOnly = true, lang = "pl")))
        assertTrue(YtMedia.withLanguage(mpd, YtCandidate("a", lang = "en", label = "English", original = true)).contains("<Label>English (oryginał)</Label>"))
    }

    // ---------------- miksy z historii ----------------

    private fun wp(id: String, title: String, channel: String, done: Boolean = false, at: Long = 1_000_000_000L) =
        WatchProgress(id, title, channel, "t-$id", if (done) 200_000 else 60_000, 200_000, at)

    @Test fun mixesFromHistory() {
        val now = 1_000_000_000L + 86_400_000L
        val h = listOf(
            wp("1", "Dawid Podsiadło – Małomiasteczkowy (Official Video)", "Dawid Podsiadło", done = true),
            wp("2", "Dawid Podsiadło – Trofea (teledysk)", "Dawid Podsiadło", done = true),
            wp("3", "Dawid Podsiadło – Nie ma fal live", "Dawid Podsiadło"),
            wp("4", "Sanah – Szampan (Official Video)", "sanah"),
            wp("5", "Jazz relaks wieczór – muzyka do pracy", "Relax Jazz"),
            wp("6", "Jazz relaks poranek – muzyka kawiarniana", "Relax Jazz"),
        )
        assertEquals(listOf("dawid", "podsiadło", "małomiasteczkowy"), StubeMixes.tokens("Dawid Podsiadło – Małomiasteczkowy (Official Video) 4K 2024"))
        assertTrue(StubeMixes.isMusic(h))
        val mixes = StubeMixes.build(h, now)
        val fav = mixes.first()
        assertEquals("fav", fav.id); assertEquals("Muzyczny miks pod Ciebie", fav.title)
        assertEquals("Dawid Podsiadło music", fav.queries.first())          // najczęściej oglądany wykonawca
        val mood = mixes.first { it.id == "mood" }
        assertEquals(setOf("jazz", "relaks"), mood.queries.first().removeSuffix(" music").split(" ").toSet())   // słowa z tytułów (bez „muzyka”)
        assertTrue(mixes.any { it.title == "Miks: Dawid Podsiadło" })
        val again = mixes.last(); assertEquals("again", again.id); assertEquals("1", again.local.first().id)
        // za mało historii – bez miksów
        assertTrue(StubeMixes.build(h.take(2), now).isEmpty())
        assertEquals(listOf(1, 10, 2, 20, 3), StubeMixes.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
        // filmy miksu: wyniki zapytań bez już obejrzanych
        val vids = runBlocking { StubeMixes.videos(fav.copy(queries = listOf("a", "b")), watchedIds = setOf("${"a".hashCode()}-1")) }
        assertTrue(vids.none { it.id == "${"a".hashCode()}-1" })                      // obejrzane pominięte
        assertTrue(vids.size >= StubeMixes.MIN_PLAYLIST); assertEquals(vids.size, vids.map { it.id }.distinct().size)
    }

    @Test fun mixesRowInStube() {
        runBlocking { (1..4).forEach { StubeHistory.flush(wp("$it", "Rock koncert $it", "Kanał Rock", done = true, at = System.currentTimeMillis())) } }
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        waitFor { a.rows.any { it.id == STubeScreen.ROW_MIXES } && a.rows.first { it.id == STubeScreen.ROW_MIXES }.tiles.isNotEmpty() }
        val row = a.rows.first { it.id == STubeScreen.ROW_MIXES }
        val ids = row.tiles.map { (it as STubeTile.Playlist).p.id }
        assertEquals(STubeScreen.PL_MIX + "fav", ids.first()); assertTrue(STubeScreen.PL_MIX + "again" in ids)
        // otwarcie miksu – filmy z YouTube w rzędzie poniżej
        val ri = a.rows.indexOf(row)
        a.openPlaylist(row, (row.tiles.first() as STubeTile.Playlist).p)
        waitFor { a.playlistRow?.tiles?.isNotEmpty() == true }
        assertEquals(ri + 1, a.rows.indexOfFirst { it.id == STubeScreen.ROW_PLAYLIST })
        assertTrue(a.playlistRow!!.tiles.isNotEmpty())
    }

    // ---------------- klawiatura wyszukiwarki ----------------

    @Test fun okOnSearchFieldOpensSearchPanelWithoutSystemKeyboard() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get(); idle(300)
        // ekran budowany po wczytaniu historii (asynchronicznie) – dopiero potem fokus na pole, inaczej budowa przenosi go na rząd
        waitFor { a.rows.isNotEmpty() }; idle(300)
        val q = a.findViewById<android.widget.AutoCompleteTextView>(pl.iptv.tv.R.id.stQuery)
        q.requestFocus(); idle()
        assertTrue(q.hasFocus())
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); idle(300)
        // (7.17) OK na polu – panel wyszukiwania z klawiaturą ekranową; systemowa klawiatura się nie wysuwa
        assertEquals(STubeSearchActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        assertFalse(a.keyboardRequested)
        val imm = shadowOf(ctx.getSystemService(InputMethodManager::class.java))
        assertFalse(imm.isSoftInputVisible)
        // lupa w pasku – ten sam panel, bez wyszukiwania na ekranie STube
        a.findViewById<View>(pl.iptv.tv.R.id.stSearch).performClick(); idle()
        assertEquals(STubeSearchActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        assertTrue(a.rows.none { it.id == STubeScreen.ROW_SEARCH })
        // akcja „Szukaj” klawiatury TV
        q.setText("koty"); q.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); idle(300)
        assertEquals("Wyniki: koty", a.rows.first().title)
    }
}

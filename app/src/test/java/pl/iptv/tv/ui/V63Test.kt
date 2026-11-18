package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.remote.StubeRemote
import java.io.File
import java.time.Duration

/**
 * 6.3: kolejka „Następne” (Room, filmy i kanały), panel boczny (Propozycje / Kolejka, nawigacja pilotem), odtwarzanie w tle,
 * wygaszacz, sterowanie z telefonu (kod parowania, blokada, polecenia), motywy STube.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V63Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
        StubeHistory.useDatabase(db)
        // zakładki „Z tego kanału” / „Podobne” (7.0) – wyszukiwanie bez sieci
        pl.iptv.tv.data.YtKeyless.clearCache()
        pl.iptv.tv.data.YtKeyless.source = object : pl.iptv.tv.data.YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = listOf(YtVideo("s1", "Wynik $q", "K", duration = "PT3M"))
        }
        server = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]") else MockResponse().setResponseCode(404)
        } }
        server.start()
        SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stubeResume = false; Prefs.stubeInternal = true
        Prefs.stubeRemote = false; Prefs.stubePin = ""; Prefs.stubeTheme = 0; Prefs.stubeLayout = 0
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
    }

    @After fun tearDown() {
        YtStreams.resolverOverride = null; YtEqSession.factory = null; YtEngine.releasePlayer(); YtEngine.listeners.clear()
        pl.iptv.tv.data.YtKeyless.source = pl.iptv.tv.data.YtKeyless.NewPipeSource; pl.iptv.tv.data.YtKeyless.clearCache()
        StubeTheme.exit(); Prefs.stubeTheme = 0; Prefs.stubeLayout = 0; Prefs.stubeRemote = false
        server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    private fun v(id: String) = YtVideo(id, "Film $id", "Kanał", "", duration = "PT3M")

    // ---------------- kolejka ----------------

    @Test fun queueOrderNextMoveAndPop() = runBlocking {
        StubeQueue.add(QueueItem.of(v("a"))); StubeQueue.add(QueueItem.of(v("b")))
        StubeQueue.add(QueueItem.of(v("c")), next = true)                     // „jako następny” – na początek
        assertEquals(listOf("c", "a", "b"), StubeQueue.items().map { it.ref })
        StubeQueue.add(QueueItem.of(v("a")))                                   // bez duplikatu – na koniec
        assertEquals(listOf("c", "b", "a"), StubeQueue.items().map { it.ref })
        StubeQueue.move(StubeQueue.items().last().id, -2)
        assertEquals(listOf("a", "c", "b"), StubeQueue.items().map { it.ref })
        // kanał TV w tej samej kolejce
        val ch = pl.iptv.tv.data.Channel("k1", "TVP 1", "http://x/1", "Ogólne")
        StubeQueue.add(QueueItem.of(ch))
        assertEquals(QueueItem.KIND_IPTV, StubeQueue.items().last().kind)
        assertEquals(4, StubeQueue.cached.size)
        // następny po końcu filmu – bieżący („a”) nie gra drugi raz
        assertEquals("c", StubeQueue.pop(skipRef = "a")!!.ref)
        assertEquals(listOf("b", "k1"), StubeQueue.items().map { it.ref })
        StubeQueue.clear(); assertNull(StubeQueue.pop())
    }

    // ---------------- sterowanie z telefonu ----------------

    @Test fun remotePairingAndLockout() {
        assertNotNull(StubeRemote.authorize("1234"))                          // wyłączone
        Prefs.stubeRemote = true
        val pin = StubeRemote.ensurePin(); assertTrue(Regex("\\d{4}").matches(pin))
        val wrong = if (pin == "0000") "1111" else "0000"
        assertNull(StubeRemote.authorize(pin, now = 1_000))
        repeat(StubeRemote.MAX_FAILS) { assertEquals("Błędny kod parowania", StubeRemote.authorize(wrong, now = 2_000)) }
        // blokada – także poprawny kod, do upływu czasu
        assertTrue(StubeRemote.authorize(pin, now = 3_000)!!.startsWith("Zbyt wiele"))
        assertNull(StubeRemote.authorize(pin, now = 3_000 + StubeRemote.LOCK_MS))
        // nowy kod unieważnia stary
        val p2 = generateSequence { StubeRemote.newPin() }.first { it != pin }
        assertNotNull(StubeRemote.authorize(pin, now = 200_000)); assertNull(StubeRemote.authorize(p2, now = 200_000))
        // linki i ID filmów
        assertEquals("dQw4w9WgXcQ", StubeRemote.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
        assertEquals("dQw4w9WgXcQ", StubeRemote.videoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", StubeRemote.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", StubeRemote.videoId(" dQw4w9WgXcQ "))
        assertNull(StubeRemote.videoId("kot"))
    }

    @Test fun remoteHttpApi() {
        Prefs.stubeRemote = true
        val pin = StubeRemote.ensurePin()
        val page = StubeRemote.handle("GET", "/stube", emptyMap(), ByteArray(0))!!
        assertEquals(200, page.first); assertTrue(page.third.contains("STube – pilot"))
        assertNull(StubeRemote.handle("GET", "/", emptyMap(), ByteArray(0)))   // strona listy – bez zmian
        // bez kodu – 403
        assertEquals(403, StubeRemote.handle("GET", "/api/stube/status", emptyMap(), ByteArray(0))!!.first)
        val h = mapOf("x-pin" to pin)
        assertTrue(JSONObject(StubeRemote.handle("POST", "/api/stube/pair", h, ByteArray(0))!!.third).getBoolean("ok"))
        // dodanie do kolejki z telefonu i stan
        val add = StubeRemote.handle("POST", "/api/stube/cmd", h, """{"cmd":"queue_add","arg":"https://youtu.be/dQw4w9WgXcQ"}""".toByteArray())!!
        assertEquals(200, add.first)
        val st = JSONObject(StubeRemote.handle("GET", "/api/stube/status", h, ByteArray(0))!!.third)
        assertEquals(1, st.getJSONArray("queue").length()); assertTrue(st.isNull("now"))
        assertEquals(400, StubeRemote.handle("POST", "/api/stube/cmd", h, """{"cmd":"play_video","arg":"kot"}""".toByteArray())!!.first)
        // odtwarzanie na TV – ekran odtwarzacza
        StubeRemote.handle("POST", "/api/stube/cmd", h, """{"cmd":"play_video","arg":"dQw4w9WgXcQ"}""".toByteArray())
        val started = shadowOf(ctx).nextStartedActivity
        assertEquals(YtPlayerActivity::class.java.name, started.component!!.className)
        assertEquals("dQw4w9WgXcQ", started.getStringExtra(YtPlayerActivity.EXTRA_ID))
        // wyszukiwanie – STube z frazą
        StubeRemote.handle("POST", "/api/stube/cmd", h, """{"cmd":"search","arg":"koty"}""".toByteArray())
        assertEquals("koty", shadowOf(ctx).nextStartedActivity.getStringExtra(STubeActivity.EXTRA_QUERY))
    }

    @Test fun pairingDialogShowsBigCode() {
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get(); idle()
        a.findViewById<View>(pl.iptv.tv.R.id.stPhone).performClick(); idle()
        val d = StubeRemoteUi.current!!
        assertTrue(d.isShowing); assertTrue(Prefs.stubeRemote)
        assertTrue(d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isFocused)
        val old = Prefs.stubePin
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()   // nowy kod, okno zostaje
        assertTrue(d.isShowing); assertNotEquals("", Prefs.stubePin)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(Prefs.stubeRemote)
        old.length
    }

    // ---------------- odtwarzacz: panel, wygaszacz, tło ----------------

    private fun player(related: List<YtVideo> = listOf(v("r1"), v("r2"))): YtPlayerActivity {
        YtStreams.resolverOverride = { YtPlayable.Muxed("Film", 300, YtCandidate(server.url("/v.mp4").toString(), 360), related = related) }
        val a = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, "abcdefghijk", "Film")).setup().get()
        waitFor { a.player != null && YtEngine.playable != null }
        return a
    }

    private fun key(a: YtPlayerActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(150)
    }

    @Test fun sidePanelDpadAndQueue() {
        runBlocking { StubeQueue.add(QueueItem.of(v("q1"))) }
        val a = player()
        a.openPanel(YtPlayerActivity.TAB_RELATED); waitFor { a.panel!!.entries.isNotEmpty() }
        val p = a.panel!!
        assertTrue(p.isOpen); assertEquals(listOf("r1", "r2"), p.entries.map { it.key })
        // fokus na pierwszej propozycji; ▼ dalej, ▲▲ do zakładek, ► – zakładka „Kolejka”
        fun focusedTitle() = (a.window.decorView.findFocus() as? android.view.ViewGroup)?.contentDescription?.toString()
            ?: (a.window.decorView.findFocus() as? android.widget.TextView)?.text?.toString()
        idle(300); assertEquals("Film r1", focusedTitle())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("Film r2", focusedTitle())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("Film r2", focusedTitle())     // koniec listy – fokus zostaje
        key(a, KeyEvent.KEYCODE_DPAD_UP); key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("▶ Propozycje", focusedTitle())
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); waitFor { p.tab == 1 && p.entries.isNotEmpty() }
        assertEquals(1, p.tab); assertEquals("Film q1", p.entries.single().title)
        // 7.0: dalej zakładki „Z tego kanału” i „Podobne”; na ostatniej ► – fokus zostaje
        // (film bez kanału i bez słów kluczowych – obie zakładki puste, z komunikatem)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); waitFor { p.tab == YtPlayerActivity.TAB_CHANNEL }
        assertEquals(YtPlayerActivity.TAB_CHANNEL, p.tab)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); waitFor { p.tab == YtPlayerActivity.TAB_SIMILAR }
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(YtPlayerActivity.TAB_SIMILAR, p.tab)
        // fokus nie ucieka z panelu do kontrolek odtwarzacza
        repeat(3) { key(a, KeyEvent.KEYCODE_DPAD_LEFT) }; waitFor { p.tab == 0 }
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        var x: View? = a.window.decorView.findFocus(); var inside = false
        // fokus w panelu (pozycja albo zakładka), nie na kontrolkach odtwarzacza pod spodem
        while (x != null) { if (x is androidx.media3.ui.PlayerView) break; if (x.parent == null || x.contentDescription == "Film r1" || x.contentDescription == "Film r2" || (x as? android.widget.TextView)?.text?.startsWith("▶") == true) inside = true; x = x.parent as? View }
        assertTrue(inside)
        // WSTECZ zamyka panel, odtwarzacz zostaje
        key(a, KeyEvent.KEYCODE_BACK); assertFalse(p.isOpen); assertFalse(a.isFinishing)
    }

    @Test fun endOfVideoPlaysNextFromQueue() {
        runBlocking { StubeQueue.add(QueueItem.of(v("next1"))) }
        val a = player()
        a.remote("next")
        // następny film z kolejki – w tym samym oknie (silnik w usłudze), bez nowego ekranu
        waitFor { YtEngine.video?.id == "next1" }
        assertEquals("next1", YtEngine.video?.id); assertFalse(a.isFinishing)
        assertTrue(runBlocking { StubeQueue.items() }.isEmpty())
    }

    @Test fun endWithoutQueueShowsSuggestions() {
        val a = player()
        a.remote("next"); waitFor { a.panel?.isOpen == true }
        assertEquals(YtPlayerActivity.TAB_RELATED, a.panel!!.tab); assertFalse(a.isFinishing)
        key(a, KeyEvent.KEYCODE_BACK); assertTrue(a.isFinishing)                         // ekran końcowy – WSTECZ zamyka
    }

    @Test fun ambientShowsAndAnyKeyReturns() {
        val a = player()
        a.player!!.playWhenReady = false
        a.showAmbient(); assertTrue(a.ambientShown)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(a.ambientShown)                                                       // akcja nie wykonana
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(a.ambientShown); assertFalse(a.player!!.playWhenReady)
    }

    @Test fun closingScreenKeepsEnginePlaying() {
        YtStreams.resolverOverride = { YtPlayable.Muxed("Film", 300, YtCandidate(server.url("/v.mp4").toString(), 360)) }
        val c = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, v("bg1"))).setup()
        val a = c.get()
        waitFor { YtEngine.video?.id == "bg1" }
        val exo = YtEngine.player!!
        // WSTECZ / HOME: ekran znika, odtwarzacz w usłudze dalej gra (bez pause/release), obraz wyłączony
        c.pause().stop().destroy(); idle()
        assertTrue(a.isDestroyed)
        assertSame(exo, YtEngine.player); assertEquals("bg1", YtEngine.video?.id); assertTrue(exo.playWhenReady)
        assertTrue(YtEngine.background)
        assertTrue(exo.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))
        // powrót: ten sam film bez ponownego wczytania, obraz włączony
        val c2 = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.resumeIntent(ctx)).setup(); idle()
        assertSame(exo, c2.get().player); assertFalse(YtEngine.background)
        assertFalse(exo.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO))
        // zatrzymanie (✖ w mini-playerze) – koniec, ekran się zamyka
        YtEngine.stop(); idle()
        assertNull(YtEngine.video); assertFalse(YtEngine.active); assertTrue(c2.get().isFinishing)
    }

    // ---------------- motywy STube ----------------

    @Test fun stubeThemeAndLayout() {
        val appAccent = Ui.accent
        Prefs.stubeTheme = 1; StubeTheme.enter()
        assertEquals(StubeTheme.THEMES[1]!!.accent, Ui.accent); assertEquals(StubeTheme.THEMES[1]!!.bg, Ui.bg)
        StubeTheme.exit(); assertEquals(appAccent, Ui.accent)
        assertEquals(StubeTheme.THEMES.size, StubeTheme.THEME_NAMES.size)
        // układ kompaktowy – węższe kafelki w STube, motyw tylko na ekranie STube
        Prefs.stubeLayout = 1
        YtStreams.resolverOverride = null
        val c = Robolectric.buildActivity(STubeActivity::class.java).setup()
        val a = c.get(); idle(300)
        waitFor { a.rows.isNotEmpty() }
        assertEquals(StubeTheme.THEMES[1]!!.accent, Ui.accent)
        val tile = a.rows.first().adapter.let { ad ->
            val rv = (a.findViewById<android.view.ViewGroup>(pl.iptv.tv.R.id.stRows).getChildAt(0) as android.view.ViewGroup).getChildAt(2) as androidx.recyclerview.widget.RecyclerView
            waitFor { rv.findViewHolderForAdapterPosition(0) != null }
            rv.findViewHolderForAdapterPosition(0)?.itemView
        }
        tile?.let { assertEquals(Ui.dp(196), it.layoutParams.width) }
        c.pause(); assertEquals(appAccent, Ui.accent)
        c.stop().destroy()
    }

    // ---------------- TV na żywo: panel z kolejką ----------------

    @Test fun liveTvPanelAddsCurrentChannel() {
        val f = File(ctx.filesDir, "v63.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 group-title=\"A\",Kanał 1\nhttp://127.0.0.1:1/1\n#EXTINF:-1 group-title=\"A\",Kanał 2\nhttp://127.0.0.1:1/2\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val key = Repository.channels.first().key
        val a = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup().get()
        idle(300)
        a.openQueuePanel(0); idle(300)
        val m = PlayerActivity::class.java.getDeclaredField("sidePanel").apply { isAccessible = true }
        val p = m.get(a) as SidePanel
        waitFor { p.entries.isNotEmpty() }
        assertEquals(listOf("Kanał 1", "Kanał 2"), p.entries.map { it.title })
        p.select(1, focusList = true); waitFor { p.tab == 1 && p.entries.isNotEmpty() }
        assertEquals("＋ Dodaj bieżący kanał do kolejki", p.entries.first().title)
        p.entries.first().onClick(); waitFor { StubeQueue.cached.isNotEmpty() }
        assertEquals(QueueItem.KIND_IPTV, StubeQueue.cached.single().kind); assertEquals(key, StubeQueue.cached.single().ref)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)); idle()
        assertFalse(p.isOpen); assertFalse(a.isFinishing)
    }
}

package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeSearch
import pl.iptv.tv.data.YtVideo
import java.time.Duration

/**
 * (7.17) Panel wyszukiwania STube: wyniki na żywo (debounce 250 ms przy pisaniu, filtr od razu, anulowanie nieaktualnych
 * zapytań), klawiatura ekranowa z fokusem startowym, historia wyszukiwania, otwarcie wyniku.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V717StubeSearchTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val calls = mutableListOf<Pair<String, StubeSearch.Filter>>()
    private var cancelled = 0
    private var slowMs = 0L
    private var frame: View? = null
    private val canvas by lazy { android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)) }

    private fun idle(ms: Long = 200) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 50L); left -= step
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            frame?.let { v -> v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1920, 1080); v.draw(canvas) }
        }
    }

    private lateinit var db: pl.iptv.tv.data.StubeDatabase

    @Before fun setup() {
        pl.iptv.tv.data.Prefs.stubeHistory = true
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, pl.iptv.tv.data.StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        STubeSearchViewModel.engine = { q, f ->
            calls += q to f
            try { if (slowMs > 0) kotlinx.coroutines.delay(slowMs) } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
            (1..3).map { StubeSearch.Item.Video(YtVideo("id$q$it${f.ordinal}".take(11).padEnd(11, 'x'), "Wynik $q $it", "Kanał $it", duration = "PT3M5S")) } +
                StubeSearch.Item.Channel("UC$q", "Kanał $q", subscribers = 12_300)
        }
    }

    private var controller: org.robolectric.android.controller.ActivityController<STubeSearchActivity>? = null
    @After fun tearDown() {
        idle(300); frame = null
        runCatching { controller?.pause()?.stop()?.destroy() }; controller = null; idle(100)
        STubeSearchViewModel.engine = { q, f -> pl.iptv.tv.data.YtKeyless.searchItems(q, f) }
        StubeHistory.useDatabase(null); db.close()
    }

    @Test fun livePipelineDebouncesTypingFiltersImmediatelyAndCancelsStaleRequests() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[STubeSearchViewModel::class.java]
        idle(50)
        assertEquals(StubeSearchState.Idle, vm.results.value); assertTrue(calls.isEmpty())
        // szybkie pisanie: k, ko, kot – jedno zapytanie po 250 ms od ostatniej litery
        vm.type("k"); idle(100); vm.type("o"); idle(100); vm.type("t"); idle(100)
        assertTrue("przed upływem 250 ms – bez zapytania", calls.isEmpty())
        idle(200)
        assertEquals(listOf("kot" to StubeSearch.Filter.ALL), calls)
        val r = vm.results.value as StubeSearchState.Results
        assertEquals("kot", r.query); assertEquals(4, r.items.size)
        // zmiana filtra – od razu (bez czekania na debounce)
        vm.setFilter(StubeSearch.Filter.VIDEOS); idle(20)
        assertEquals("kot" to StubeSearch.Filter.VIDEOS, calls.last())
        // nowa litera anuluje trwające (wolne) zapytanie
        slowMs = 1_000
        vm.type("y"); idle(300)
        assertEquals("koty", calls.last().first)
        vm.type("!"); idle(300)
        assertEquals(1, cancelled)
        assertTrue(vm.results.value is StubeSearchState.Loading)
        idle(1_100)
        assertEquals("koty!", (vm.results.value as StubeSearchState.Results).query)
        // wyczyszczenie – od razu stan pusty (bez zapytania)
        val n = calls.size
        vm.clear(); idle(20)
        assertEquals(StubeSearchState.Idle, vm.results.value); assertEquals(n, calls.size)
        store.clear()
    }

    @Test fun keyboardTypesHistoryAndResultOpening() {
        runBlocking { StubeHistory.addSearch("stare hasło") }
        val c = Robolectric.buildActivity(STubeSearchActivity::class.java, STubeSearchActivity.intent(ctx, "")).setup()
        controller = c; val a = c.get(); frame = a.window.decorView
        idle(600)
        // fokus startowy na klawiaturze (klawisz „q”); OK – litera w zapytaniu, ► OK – kolejna
        fun key(code: Int) {
            a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0)); idle(30)
            a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0)); idle(120)
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("q", a.vm.searchQuery.value)
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("qw", a.vm.searchQuery.value)
        // fizyczna klawiatura / cyfry pilota też piszą, Backspace usuwa
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E)); idle()
        assertEquals("qwe", a.vm.searchQuery.value)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); idle()
        assertEquals("qw", a.vm.searchQuery.value)
        // wyniki na żywo bez zatwierdzania
        idle(400)
        assertEquals("qw", (a.vm.results.value as StubeSearchState.Results).query)
        // systemowa klawiatura się nie wysuwa
        assertFalse(shadowOf(ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)).isSoftInputVisible)
        // historia: zapisane hasło widoczne; otwarcie filmu zapisuje bieżące hasło i uruchamia odtwarzacz
        assertEquals(listOf("stare hasło"), a.vm.history.value)
        a.open((a.vm.results.value as StubeSearchState.Results).items.first())
        assertEquals(YtPlayerActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
        idle(300)
        assertEquals(listOf("qw", "stare hasło"), a.vm.history.value)
        // kanał – STube z filmami kanału (panel się zamyka)
        a.open(StubeSearch.Item.Channel("UCx", "Kanał X"))
        val i = shadowOf(a).nextStartedActivity
        assertEquals(STubeActivity::class.java.name, i.component?.className)
        assertEquals(STubeScreen.PL_CHANNEL + "UCx", i.getStringExtra(STubeActivity.EXTRA_PLAYLIST)); assertTrue(a.isFinishing)
        // usunięcie z historii (przytrzymanie OK na pigułce)
        a.vm.forget("stare hasło"); idle(300)
        assertEquals(listOf("qw"), a.vm.history.value)
        assertTrue(StubeSearch.HISTORY_MAX == 15 && STubeSearchViewModel.DEBOUNCE_MS == 250L)
    }
}

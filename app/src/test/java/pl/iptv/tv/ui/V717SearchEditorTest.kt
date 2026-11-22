package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.room.Room
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
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.ui.theme.ObsidianViews
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * (7.17) Uniwersalna wyszukiwarka (debounce 300 ms, anulowanie poprzednich zapytań, filtry, rzędy wyników), edytor listy
 * (zmiany w pamięci, jeden zapis na końcu, przełącznik widoczności) i okna dialogowe „Obsidian Stream” (panel 92 %, scrim 75 %).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V717SearchEditorTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(50); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private val stubeCalls = AtomicInteger()
    private val stubeQueries = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList()
        Ui.applyPreset("classic"); Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
        StremioApi.defaultAddons = emptyList()
        SearchActivity.stube = { q -> stubeCalls.incrementAndGet(); stubeQueries += q; listOf(YtVideo("v_$q", "Film o $q", "Kanał", duration = "PT4M2S")) }
        val f = File(ctx.filesDir, "srch.m3u").apply {
            writeText("#EXTM3U\n" + listOf("Polsat Sport", "Polsat News", "TVN", "Eurosport").mapIndexed { i, n -> "#EXTINF:-1 tvg-id=\"s$i\" group-title=\"G${i % 2}\",$n\nhttp://srch/$i\n" }.joinToString(""))
        }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() {
        SearchActivity.stube = { q -> pl.iptv.tv.data.YtKeyless.search(q) }
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES)
        Repository.editSession = false
        StubeHistory.useDatabase(null); db.close()
    }

    @Test fun searchDebouncesCancelsAndShowsRowsPerType() {
        val a = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        idle(400)
        // szybkie pisanie: „p”, „po”, „pol”, „pols” co 100 ms – jedno wyszukiwanie (po 300 ms ciszy), tylko ostatnie zapytanie
        for (q in listOf("p", "po", "pol", "pols")) { a.query.value = q; idle(100) }
        assertEquals("przed upływem 300 ms – bez wyszukiwania", 0, a.searches)
        idle(SearchActivity.DEBOUNCE_MS + 50)
        waitFor { a.results.query == "pols" && a.results.stube.isNotEmpty() && !a.results.loading }
        assertEquals(1, a.searches)
        assertEquals(listOf("pols"), stubeQueries.toList())
        assertEquals(listOf("Polsat Sport", "Polsat News"), a.results.channels.map { it.name })
        assertEquals("Film o pols", a.results.stube.single().title)
        // to samo zapytanie ponownie (np. spacja i jej usunięcie) – bez nowego wyszukiwania
        a.query.value = "pols "; idle(SearchActivity.DEBOUNCE_MS + 50); a.query.value = "pols"; idle(SearchActivity.DEBOUNCE_MS + 50)
        assertEquals("distinctUntilChanged", 1, a.searches)
        // rzędy: kanały TV i STube (bez EPG i Stremio – brak wyników)
        waitFor { a.window.decorView.findViewWithTag<View>("stube") != null }
        assertNotNull(a.window.decorView.findViewWithTag<View>("channels"))
        assertNotNull(a.window.decorView.findViewWithTag<View>("stube"))
        assertNull(a.window.decorView.findViewWithTag<View>("epg"))
        // filtr „Kanały TV”: bez zapytań sieciowych, tylko rząd kanałów
        val before = stubeCalls.get()
        a.setFilter(SearchActivity.F_CHANNELS)
        waitFor { a.window.decorView.findViewWithTag<View>("stube") == null }
        assertEquals(before, stubeCalls.get())
        assertTrue(a.filterViews.single { it.isSelected }.text.toString() == "Kanały TV")
        assertNotNull(a.window.decorView.findViewWithTag<View>("channels"))
        // nowe zapytanie anuluje poprzednie (flatMapLatest) – wyniki tylko dla ostatniego
        a.setFilter(SearchActivity.F_ALL)
        a.query.value = "tvn"; idle(SearchActivity.DEBOUNCE_MS + 50)
        waitFor { a.results.query == "tvn" && !a.results.loading }
        assertEquals(listOf("TVN"), a.results.channels.map { it.name })
    }

    @Test fun editorBuffersChangesAndWritesOnceOnDone() {
        val c = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val a = c.get(); idle(400)
        assertTrue("sesja edycji", Repository.editSession)
        val writes = Repository.editWrites
        // ukrycie i zmiana nazwy – od razu na liście, bez zapisu pliku
        val k0 = Repository.allChannels[0].key
        Repository.edit(k0) { it.hidden = true }
        Repository.edit(Repository.allChannels[1].key) { it.name = "Nowa nazwa" }
        assertTrue(Repository.allChannels.first { it.key == k0 }.hidden)
        assertEquals(writes, Repository.editWrites)
        assertTrue(Repository.editsDirty)
        // przełącznik widoczności w wierszu (akcent) – obecny i przełącza
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(pl.iptv.tv.R.id.channels)
        idle(200)
        val sw = rv.getChildAt(0)?.findViewWithTag<android.widget.ImageView>(EditorActivity.SWITCH_TAG)
        assertNotNull("przełącznik widoczności", sw)
        assertTrue(sw!!.drawable is ObsidianViews.Switch)
        // Gotowe – jeden zbiorczy zapis, potem zamknięcie
        a.findViewById<View>(pl.iptv.tv.R.id.btnDone).performClick()
        waitFor { a.isFinishing }
        assertEquals("jeden zapis przy zatwierdzeniu", writes + 1, Repository.editWrites)
        assertFalse(Repository.editsDirty)
        c.pause().stop().destroy()
        assertFalse(Repository.editSession)
        // poza edytorem zmiana zapisuje się od razu (jak dotąd)
        Repository.edit(k0) { it.hidden = false }
        assertEquals(writes + 2, Repository.editWrites)
    }

    @Test fun editorMoveModeAppliesOrderOnce() {
        val c = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val a = c.get(); idle(400)
        val keys = Repository.allChannels.map { it.key }
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(pl.iptv.tv.R.id.channels)
        rv.getChildAt(0).findViewById<View>(pl.iptv.tv.R.id.handle).performClick(); idle()
        val rebuilds = Repository.version
        // ▼▼ – kanał w dół o 2 pozycje: tylko w pamięci listy edytora (bez przebudowy listy kanałów)
        repeat(2) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)); idle(100) }
        assertEquals("▲▼ bez przebudowy listy", rebuilds, Repository.version)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); idle()
        assertEquals(listOf(keys[1], keys[2], keys[0], keys[3]), Repository.allChannels.map { it.key })
        c.pause().stop().destroy()
    }

    @Test fun dialogsUseObsidianPanelAndScrim() {
        val a = Robolectric.buildActivity(SearchActivity::class.java).setup().get(); idle()
        Dialogs.confirm(a, "Pytanie", "Treść") {}; idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val w = d.window!!
        assertEquals(ObsidianViews.DIALOG_DIM, w.attributes.dimAmount, 0.01f)
        val bg = w.decorView.background as? android.graphics.drawable.GradientDrawable
        assertEquals(ObsidianViews.PANEL_92, bg?.color?.defaultColor)
        assertEquals(0xEB, android.graphics.Color.alpha(ObsidianViews.PANEL_92))
        d.dismiss()
    }
}

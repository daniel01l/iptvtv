package pl.iptv.tv.ui

import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
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
import pl.iptv.tv.data.DashTile
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Action
import pl.iptv.tv.data.X25Layout
import pl.iptv.tv.data.X25Store
import java.time.Duration

/**
 * Motyw X25 Dashboard: preset i faktura (tło, akryl, fokus), okna-menu jako panel boczny (Guide), przełącznik z powrotem do
 * poprzedniego motywu, układ kafelków (przesuwanie, przypinanie, ukrywanie, funkcje) z zapisem w Room, pulpit w Compose
 * (pierwszy fokus, panel kafelka, tryb edycji ◄ ►), uruchamianie funkcji przez istniejące ekrany.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class X25Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    /** Klatka: pętla główna + rysowanie okna (Compose mierzy i układa elementy list dopiero przy rysowaniu). */
    private var frameView: android.view.View? = null
    private val frameCanvas by lazy { android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)) }
    private fun idle(ms: Long = 200) {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 3 + 1)); frameView?.let { v -> v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1920, 1080); v.draw(frameCanvas) } }
    }
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(5); if (cond()) return } }
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }   // otwarta od razu – zamknięcie w tearDown nie trafia na otwieranie w tle (zakleszczenie Room)
        StubeHistory.useDatabase(db); X25Store.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        Prefs.x25Dashboard = true; Prefs.x25Guide = true; Prefs.x25Sounds = true
        Ui.applyPreset("modern"); Ui.changed()
    }

    @After fun tearDown() { frameView = null; Ui.applyPreset("modern"); Ui.changed(); X25Theme.justEnabled = false; StubeHistory.useDatabase(null); db.close() }

    private fun ids(l: List<DashTile>) = l.map { it.id }

    // ---------------- motyw ----------------

    @Test fun presetSurfaceBackdropAndFocus() {
        Ui.applyPreset(X25Theme.ID); Ui.changed()
        assertTrue(X25Theme.active); assertEquals(Ui.S_X25, Ui.surface)
        assertEquals(Ui.S_X25, Ui.PRESETS.first { it.id == "x25" }.surface)
        assertTrue(Ui.geo.glow && Ui.geo.topBar && Ui.geo.focusStroke == 3)
        // tło: rysowane raz do bitmapy w połowie rozdzielczości, wspólnej dla kolejnych ekranów
        val bg = Ui.bgDrawable()
        assertTrue(bg is X25Backdrop)
        bg.setBounds(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        bg.draw(android.graphics.Canvas(bmp))
        val renders = X25Backdrop.renders
        Ui.bgDrawable().apply { setBounds(0, 0, 1920, 1080); draw(android.graphics.Canvas(bmp)) }
        assertEquals("drugi ekran – ta sama bitmapa", renders, X25Backdrop.renders)
        val half = X25Backdrop.render(1920, 1080, Ui.bg, Ui.accent)
        assertEquals(960, half.width); assertEquals(540, half.height)
        // fokus: ramka z poświatą (warstwy), w trybie słabszych urządzeń – sama ramka
        Prefs.lowPerf = false
        assertTrue(Ui.focusedBg() is android.graphics.drawable.LayerDrawable)
        Prefs.lowPerf = true
        assertTrue(Ui.focusedBg() is android.graphics.drawable.GradientDrawable)
        Prefs.lowPerf = false
        // pasek informacji – panel z paskiem akcentu
        Prefs.remove("info_style"); assertEquals(7, Ui.infoStyle)
    }

    @Test fun toggleReturnsToPreviousTheme() {
        Ui.applyPreset("classic"); Ui.changed()
        X25Theme.setEnabled(true)
        assertTrue(X25Theme.active); assertEquals("classic", Prefs.x25PrevTheme); assertTrue(X25Theme.consumeJustEnabled())
        assertFalse(X25Theme.consumeJustEnabled())
        X25Theme.setEnabled(false)
        assertFalse(X25Theme.active); assertEquals("classic", Prefs.theme)
        // wybór z listy motywów też pamięta poprzedni
        Ui.applyPreset("xbox"); Ui.applyPreset(X25Theme.ID); assertEquals("xbox", Prefs.x25PrevTheme)
    }

    @Test fun menusOpenAsGuidePanelDialogsStayCentered() {
        Ui.applyPreset(X25Theme.ID); Ui.changed()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val menu = Ui.dialog(a).setTitle("Menu X").setItems(arrayOf("A", "B", "C")) { _, _ -> }.show(); idle()
        assertTrue(X25Theme.isGuide(menu))
        val w = menu.window!!.attributes
        assertEquals(Gravity.START or Gravity.TOP, w.gravity)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, w.height)
        menu.dismiss()
        val msg = Ui.dialog(a).setTitle("Pytanie").setMessage("Na pewno?").setPositiveButton("Tak", null).setNegativeButton("Nie", null).show(); idle()
        assertFalse(X25Theme.isGuide(msg))
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, msg.window!!.attributes.height)
        // przycisk zaznaczony: ciemny tekst na neonowej zieleni
        val b = msg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals(X25Theme.DARK_ON_GREEN, b.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
        msg.dismiss()
        // wyłączony Guide – zwykłe okno
        Prefs.x25Guide = false
        val m2 = Ui.dialog(a).setTitle("Menu Y").setItems(arrayOf("A")) { _, _ -> }.show(); idle()
        assertFalse(X25Theme.isGuide(m2)); m2.dismiss()
    }

    // ---------------- układ kafelków ----------------

    @Test fun layoutMovePinHideAssign() {
        var l = X25Layout.defaults()
        assertEquals(listOf("live", "stube", "stremio", "playlists", "subs", "history", "guide"), ids(l))
        assertEquals(listOf(X25Action.LIVE, X25Action.STUBE, X25Action.STREMIO, X25Action.PLAYLISTS, X25Action.SUBS, X25Action.HISTORY, X25Action.GUIDE), l.map { it.type })
        l = X25Layout.move(l, "live", 1); assertEquals(listOf("stube", "live"), ids(l).take(2))
        l = X25Layout.move(l, "live", -5); assertEquals("live", l[0].id)              // na początek, bez wyjścia poza listę
        assertEquals((0 until l.size).toList(), l.map { it.position })
        // ukryte kafelki są pomijane przy przesuwaniu
        l = X25Layout.setHidden(l, "stube", true)
        l = X25Layout.move(l, "live", 1)
        assertEquals(listOf("stube", "stremio", "live"), ids(l).take(3))
        assertEquals(listOf("stremio", "live"), ids(X25Layout.visible(l)).take(2))
        l = X25Layout.setHidden(l, "stube", false)
        // przypięty na początku; nieprzypięty nie przeskakuje przypiętych
        l = X25Layout.togglePin(l, "history")
        assertEquals("history", l[0].id); assertTrue(l[0].pinned)
        val before = ids(l)
        l = X25Layout.move(l, ids(l)[1], -1); assertEquals(before, ids(l))
        l = X25Layout.togglePin(l, "history"); assertFalse(l.first { it.id == "history" }.pinned)
        // funkcja, rozmiar, nowy kafelek, usunięcie
        l = X25Layout.assign(l, "guide", X25Action(X25Action.CHANNEL, "k1", "TVN"))
        l.first { it.id == "guide" }.let { assertEquals(X25Action.CHANNEL, it.type); assertEquals("TVN", it.action.title) }
        l = X25Layout.toggleBig(l, "subs"); assertTrue(l.first { it.id == "subs" }.big)
        val (l2, nid) = X25Layout.add(l, X25Action(X25Action.MIX, "m1", "Miks lo-fi"))
        assertEquals(nid, l2.last().id); assertFalse(l.any { it.id == nid })
        assertEquals(l.size, X25Layout.remove(l2, nid).size)
        assertEquals("Historia i postęp", X25Action(X25Action.HISTORY).title)
    }

    @Test fun storeSavesInRoomAndResets() = runBlocking {
        assertEquals(ids(X25Layout.defaults()), ids(X25Store.load()))
        val changed = X25Layout.setHidden(X25Layout.move(X25Layout.defaults(), "live", 2), "guide", true)
        X25Store.save(changed)
        X25Store.clearCache()
        val back = X25Store.load()
        assertEquals(ids(changed), ids(back)); assertTrue(back.first { it.id == "guide" }.hidden)
        assertEquals(ids(changed), ids(db.dash().all()))
        assertEquals(ids(X25Layout.defaults()), ids(X25Store.reset()))
    }

    @Test fun recentsSeparateSections() {
        val ch = (1..3).map { pl.iptv.tv.data.Channel("c$it", "Kanał $it", "http://x/$it", "G") }
        val v = (1..2).map { WatchProgress("v$it", "Film $it", positionMs = 1, durationMs = 10, updatedAt = 0) }
        val st = listOf(pl.iptv.tv.data.StremioProgress("tt1", "movie", "Film S", positionMs = 5, durationMs = 10))
        val g = X25Recent.groups(ch, v, st)
        assertEquals(listOf("Live TV", "STube", "Stremio"), g.map { it.title })
        assertEquals(listOf("ch:c1", "ch:c2", "ch:c3"), g[0].items.map { it.key })
        assertEquals(listOf("yt:v1", "yt:v2"), g[1].items.map { it.key })
        assertEquals(listOf("st:tt1"), g[2].items.map { it.key })
        // puste sekcje pomijane, najwyżej MAX w sekcji
        assertEquals(listOf("Stremio"), X25Recent.groups(emptyList(), emptyList(), st).map { it.title })
        val many = (1..10).map { pl.iptv.tv.data.Channel("c$it", "K$it", "http://x/$it", "G") }
        assertEquals(X25Recent.MAX, X25Recent.groups(many, emptyList(), emptyList())[0].items.size)
    }

    @Test fun stremioTileAddedToSavedLayoutOnce() = runBlocking {
        // układ zapisany przed dodaniem Stremio: kafelek dopisany za STube, raz
        val old = X25Layout.normalize(X25Layout.defaults().filter { it.type != X25Action.STREMIO })
        X25Store.save(old); X25Store.clearCache(); Prefs.x25StremioTile = false
        assertEquals(listOf("live", "stube", "stremio", "playlists"), ids(X25Store.load()).take(4))
        assertEquals(ids(X25Store.load()), ids(db.dash().all()))
        // usunięty przez użytkownika już nie wraca
        X25Store.save(X25Layout.remove(X25Store.load(), "stremio")); X25Store.clearCache()
        assertFalse(X25Store.load().any { it.type == X25Action.STREMIO })
        // bez STube: za Live TV; już jest – bez zmian
        assertEquals(listOf("live", "stremio"), ids(X25Layout.withStremio(old.filter { it.type != X25Action.STUBE })).take(2))
        val has = X25Layout.defaults()
        assertSame(has, X25Layout.withStremio(has))
    }

    // ---------------- funkcje kafelków ----------------

    @Test fun actionsUseExistingScreens() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        fun started() = shadowOf(a).nextStartedActivity
        X25Actions.run(a, X25Action(X25Action.PLAYLISTS))
        started().let { assertEquals(STubeActivity::class.java.name, it.component!!.className); assertEquals(STubeScreen.ROW_LIBRARY, it.getStringExtra(STubeActivity.EXTRA_ROW)) }
        X25Actions.run(a, X25Action(X25Action.HISTORY))
        assertEquals(STubeScreen.PL_HISTORY, started().getStringExtra(STubeActivity.EXTRA_PLAYLIST))
        X25Actions.run(a, X25Action(X25Action.MIX, "abc", "Miks"))
        started().let { assertEquals(STubeScreen.PL_MIX + "abc", it.getStringExtra(STubeActivity.EXTRA_PLAYLIST)); assertEquals("Miks", it.getStringExtra(STubeActivity.EXTRA_PLAYLIST_TITLE)) }
        X25Actions.run(a, X25Action(X25Action.STUBE_ROW, STubeScreen.ROW_MUSIC))
        assertEquals(STubeScreen.ROW_MUSIC, started().getStringExtra(STubeActivity.EXTRA_ROW))
        X25Actions.run(a, X25Action(X25Action.LIVE))
        started().let { assertEquals(MainActivity::class.java.name, it.component!!.className); assertTrue(it.flags and android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0) }
        X25Actions.run(a, X25Action(X25Action.GUIDE)); assertEquals(GuideActivity::class.java.name, started().component!!.className)
        X25Actions.run(a, X25Action(X25Action.SETTINGS)); assertEquals(SettingsActivity::class.java.name, started().component!!.className)
        // nieznany kanał – komunikat, bez uruchamiania odtwarzacza
        X25Actions.run(a, X25Action(X25Action.CHANNEL, "brak", "Brak")); assertNull(started())
    }

    // ---------------- pulpit (Compose) ----------------

    private fun dashboard(): org.robolectric.android.controller.ActivityController<X25DashboardActivity> {
        Ui.applyPreset(X25Theme.ID); Ui.changed()
        val c = Robolectric.buildActivity(X25DashboardActivity::class.java).setup().visible()
        c.windowFocusChanged(true)
        frameView = c.get().window.decorView
        waitFor { c.get().tilesNow.isNotEmpty() && c.get().focusedKey?.startsWith("t:") == true }
        return c
    }

    private fun key(a: android.app.Activity, code: Int, long: Boolean = false) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        if (long) { a.dispatchKeyEvent(KeyEvent(t, t + 600, KeyEvent.ACTION_DOWN, code, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS)) }
        a.dispatchKeyEvent(KeyEvent(t, t + if (long) 700 else 50, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Test fun dashboardUpToRecentsAndDownBackToSameTile() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", positionMs = 5, durationMs = 100, updatedAt = 1)) }
        val a = dashboard().get()
        waitFor { a.recentSections == listOf("STube") }
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val tile = a.focusedKey!!
        assertTrue(tile.startsWith("t:") && tile != "t:live")
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("r:yt:v1", a.focusedKey)
        // ▼ – z powrotem na ten sam kafelek funkcji (wcześniej fokus ginął)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(tile, a.focusedKey)
    }

    @Test fun everyPanelItemHasDrawnIcon() {
        val a = dashboard().get()
        // panel kafelka i opcje pulpitu: każda pozycja z ikoną, każda ikona z własnym rysunkiem (nie zapasowy kwadrat)
        key(a, KeyEvent.KEYCODE_MENU)
        val tile = a.flyoutIcons
        a.clickFlyItem("Opcje pulpitu…"); idle()
        val options = a.flyoutIcons
        for (icons in listOf(tile, options)) {
            assertTrue(icons.toString(), icons.isNotEmpty() && icons.all { it != null && X25Icons.known(it) })
        }
        assertTrue(X25Icons.ALL.all { X25Icons.known(it) })
        // panel narysowany (idle rysuje okno do bitmapy) – ikony bez błędów rysowania
        idle()
    }

    @Test @Config(qualifiers = "w890dp-h400dp-land-xxhdpi") fun dashboardCompactOnLowLandscapeScreen() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", positionMs = 5, durationMs = 100, updatedAt = 1)) }
        val a = dashboard().get()
        assertTrue(a.dimsNow.compact)
        waitFor { a.recentSections == listOf("STube") }
        // sekcja ostatnich i kafelki dostępne pilotem w układzie kompaktowym
        assertEquals("t:" + a.tilesNow.first().id, a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals("r:yt:v1", a.focusedKey)
    }

    @Test fun dashboardFullSizeOnTv() {
        assertFalse(dashboard().get().dimsNow.compact)
    }

    @Test fun dashboardFirstFocusTileMenuAndReorder() {
        val c = dashboard(); val a = c.get()
        assertEquals(ids(X25Layout.defaults()), ids(a.tilesNow))
        assertEquals("t:live", a.focusedKey)
        // MENU na kafelku – panel kafelka
        key(a, KeyEvent.KEYCODE_MENU)
        assertEquals(listOf("Live TV"), a.flyoutTitles)
        assertTrue("Zmień funkcję…" in a.flyoutItems && "Ukryj kafelek" in a.flyoutItems)
        // WSTECZ zamyka panel
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(a.flyoutTitles.isEmpty())
        // przytrzymanie OK – ten sam panel; „Przenieś” → tryb edycji, ► przesuwa kafelek
        key(a, KeyEvent.KEYCODE_DPAD_CENTER, long = true)
        assertEquals(listOf("Live TV"), a.flyoutTitles)
        a.clickFlyItem("Przenieś  ◄ ►"); idle()
        assertEquals("live", a.editing)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("stube", "live"), ids(a.tilesNow).take(2))
        assertEquals("t:live", a.focusedKey)                  // fokus idzie za kafelkiem
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertNull(a.editing)
        // zapis w bazie
        waitFor { runBlocking { db.dash().all() }.isNotEmpty() }
        assertEquals(listOf("stube", "live"), runBlocking { ids(db.dash().all()) }.take(2))
    }

    @Test fun dashboardAddsAndAssignsTiles() {
        val c = dashboard(); val a = c.get()
        key(a, KeyEvent.KEYCODE_MENU)
        a.clickFlyItem("Opcje pulpitu…"); idle()
        assertEquals("Pulpit X25", a.flyoutTitles.last())
        a.clickFlyItem("Dodaj kafelek…"); idle()
        a.clickFlyItem("Funkcje aplikacji…"); idle()
        assertEquals(listOf("Live TV", "Pulpit X25", "Dodaj kafelek", "Funkcje aplikacji"), a.flyoutTitles)
        a.clickFlyItem("Ustawienia"); idle(400)
        assertTrue(a.flyoutTitles.isEmpty())
        assertEquals(X25Action.SETTINGS, a.tilesNow.last().type)
        // zmiana funkcji kafelka „Przewodnik TV” na kategorię STube
        key(a, KeyEvent.KEYCODE_MENU)                         // fokus na nowym kafelku → jego panel
        a.clickFlyItem("Zmień funkcję…"); idle()
        a.clickFlyItem("Kategoria STube…"); idle()
        a.clickFlyItem("Muzyka"); idle()
        assertEquals(X25Action.STUBE_ROW, a.tilesNow.last().type); assertEquals(STubeScreen.ROW_MUSIC, a.tilesNow.last().arg)
        // ukrycie i przywrócenie
        val id = a.tilesNow.last().id
        key(a, KeyEvent.KEYCODE_MENU); a.clickFlyItem("Ukryj kafelek"); idle()
        assertTrue(a.tilesNow.first { it.id == id }.hidden)
        key(a, KeyEvent.KEYCODE_MENU); a.clickFlyItem("Opcje pulpitu…"); idle()
        a.clickFlyItem("Pokaż ukryte kafelki (1)…"); idle()
        a.clickFlyItem("Muzyka"); idle()
        assertFalse(a.tilesNow.first { it.id == id }.hidden)
    }

    @Test fun mainOpensDashboardOnlyWithX25() {
        val plain = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        assertNotEquals(X25DashboardActivity::class.java.name, shadowOf(plain.get()).nextStartedActivity?.component?.className)
        plain.pause().stop().destroy()
        Ui.applyPreset(X25Theme.ID); Ui.changed()
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val started = generateSequence { shadowOf(m.get()).nextStartedActivity }.map { it.component?.className }.toList()
        assertTrue(X25DashboardActivity::class.java.name in started)
        // WSTECZ na liście kanałów – z powrotem na pulpit
        m.get().onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(X25DashboardActivity::class.java.name, shadowOf(m.get()).nextStartedActivity?.component?.className)
        // wyłączony pulpit – zwykła lista kanałów
        Prefs.x25Dashboard = false
        assertFalse(X25Theme.dashboardOn)
    }

    @Test fun backFromMainScreensGoesStraightToDashboard() {
        // bez X25: zwykłe zamknięcie ekranu
        val plain = Robolectric.buildActivity(EpgSearchActivity::class.java).setup(); idle()
        plain.get().onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(plain.get().isFinishing)
        assertNotEquals(X25DashboardActivity::class.java.name, shadowOf(plain.get()).nextStartedActivity?.component?.className)
        Ui.applyPreset(X25Theme.ID); Ui.changed()
        for (cls in listOf(EpgSearchActivity::class.java, GuideActivity::class.java, STubeActivity::class.java)) {
            val a = Robolectric.buildActivity(cls).setup(); idle()
            a.get().onBackPressedDispatcher.onBackPressed(); idle()
            // przewodnik: pierwsze WSTECZ w siatce przenosi na przyciski u góry
            if (!a.get().isFinishing) { a.get().onBackPressedDispatcher.onBackPressed(); idle() }
            assertTrue(cls.simpleName, a.get().isFinishing)
            val next = shadowOf(a.get()).nextStartedActivity
            assertEquals(cls.simpleName, X25DashboardActivity::class.java.name, next?.component?.className)
            assertTrue(next!!.flags and android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        }
    }}

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
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Store
import java.time.Duration

/** Motyw Nowoczesny (7.0): preset, tło, zaznaczenie, panele nakładkowe, ekran główny w Compose (pasek ikon, hero, rzędy). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ModernTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
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
        Prefs.modernHome = true; Prefs.modernOverlay = true
        Ui.applyPreset("classic"); Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
    }

    @After fun tearDown() {
        frameView = null; X25Theme.launcherForTest = null; X25Theme.launcherHome = false
        Prefs.modernHome = true; Prefs.modernOverlay = true; Prefs.lowPerf = false
        Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false; StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- motyw ----------------

    @Test fun presetSurfaceFocusAndBackdrop() {
        assertTrue(ModernTheme.active); assertEquals(Ui.S_MODERN, Ui.surface)
        // nadal pięć motywów, Nowoczesny na pierwszym miejscu (to samo id – dotychczasowi użytkownicy dostają nowy wygląd)
        assertEquals(listOf("modern", "classic", "xbox", X25Theme.ID, Ps5Theme.ID), Ui.PRESETS.map { it.id })
        assertEquals("Nowoczesny", Ui.PRESETS.first().name)
        // bez nazw zewnętrznych serwisów w motywie
        for (p in Ui.PRESETS) assertFalse(p.name, p.name.contains("VOD", true) || p.name.contains("TVP", true))
        assertTrue(Ui.geo.blur > 0 && Ui.geo.zoomMax > 0.05f)
        // tło: same cieniowania (bez bitmapy), rysowane na całym ekranie
        val bg = Ui.bgDrawable()
        assertTrue(bg is ModernBackdrop)
        bg.setBounds(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        bg.draw(android.graphics.Canvas(bmp))
        assertEquals(android.graphics.PixelFormat.OPAQUE, @Suppress("DEPRECATION") bg.opacity)
        // ciemne tło, niebieskie zaznaczenie z białym tekstem
        assertTrue(android.graphics.Color.red(Ui.bg) < 0x20 && android.graphics.Color.blue(Ui.accent) > 0xC0)
        assertEquals(android.graphics.Color.WHITE, Ui.focusText)
        Prefs.lowPerf = false
        assertTrue(Ui.focusedBg() is android.graphics.drawable.LayerDrawable)
        Prefs.lowPerf = true
        assertTrue(Ui.focusedBg() is android.graphics.drawable.GradientDrawable)
        Prefs.lowPerf = false
        Prefs.remove("info_style"); assertEquals(0, Ui.infoStyle)
    }

    @Test fun menusAsOverlayPanelAndPillButtons() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val menu = Ui.dialog(a).setTitle("Menu").setItems(arrayOf("A", "B", "C")) { _, _ -> }.show(); idle()
        assertTrue(ModernTheme.isOverlay(menu)); assertFalse(Ps5Theme.isPanel(menu)); assertFalse(X25Theme.isGuide(menu))
        val w = menu.window!!.attributes
        assertEquals(Gravity.END or Gravity.TOP, w.gravity)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, w.height)
        menu.dismiss()
        // komunikat – okno na środku, przyciski-kapsuły: zaznaczony z białym tekstem
        val msg = Ui.dialog(a).setTitle("Pytanie").setMessage("Na pewno?").setPositiveButton("Tak", null).setNegativeButton("Nie", null).show(); idle()
        assertFalse(ModernTheme.isOverlay(msg))
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, msg.window!!.attributes.height)
        val b = msg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals(android.graphics.Color.WHITE, b.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
        msg.dismiss()
        Prefs.modernOverlay = false
        val m2 = Ui.dialog(a).setTitle("Menu 2").setItems(arrayOf("A")) { _, _ -> }.show(); idle()
        assertFalse(ModernTheme.isOverlay(m2)); m2.dismiss()
    }

    @Test fun homeScreenRouting() {
        assertTrue(ModernTheme.homeOn); assertTrue(X25Theme.dashboardOn); assertFalse(Ps5Theme.homeOn)
        assertEquals(ModernHomeActivity::class.java.name, X25Theme.dashboardIntent(ctx).component?.className)
        // lista kanałów przy starcie otwiera ekran główny Nowoczesny (nie PS5 / X25)
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val started = generateSequence { shadowOf(m.get()).nextStartedActivity }.map { it.component?.className }.toList()
        assertTrue(started.toString(), ModernHomeActivity::class.java.name in started)
        assertFalse(Ps5HomeActivity::class.java.name in started)
        // bez ekranu startowego – zwykła lista kanałów; tryb launchera – i tak ekran główny Nowoczesny
        Prefs.modernHome = false
        assertFalse(X25Theme.dashboardOn)
        X25Theme.launcherForTest = true; X25Theme.refreshLauncher(ctx)
        assertTrue(ModernTheme.homeOn); assertFalse(Ps5Theme.homeOn)
        assertEquals(ModernHomeActivity::class.java.name, X25Theme.dashboardIntent(ctx).component?.className)
        // swobodne przełączanie: PS5 i powrót (wyłączenie PS5 wraca do Nowoczesnego)
        X25Theme.launcherForTest = false; X25Theme.refreshLauncher(ctx); Prefs.modernHome = true
        Ps5Theme.setEnabled(true); assertTrue(Ps5Theme.active); assertFalse(ModernTheme.active)
        assertEquals(ModernTheme.ID, Prefs.ps5PrevTheme)
        Ps5Theme.setEnabled(false); assertTrue(ModernTheme.active)
        assertTrue(X25Theme.consumeJustEnabled())
    }

    // ---------------- ekran główny (Compose) ----------------

    private fun home(): ModernHomeActivity {
        val c = Robolectric.buildActivity(ModernHomeActivity::class.java).setup().visible()
        c.windowFocusChanged(true)
        frameView = c.get().window.decorView
        waitFor { c.get().focusedKey != null }
        return c.get()
    }

    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Test fun railHeroAndRowsWithFocusThatNeverGetsLost() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", "Kanał", positionMs = 300_000, durationMs = 1_200_000, updatedAt = 1)) }
        val a = home()
        assertEquals(ModernHomeActivity.HERO_PLAY, a.focusedKey)
        waitFor { "continue" in a.shelfIds }
        repeat(10) { if ("channels" !in a.shelfIds) waitFor { "channels" in a.shelfIds } }   // 7.8: kanały (Live TV) ładowane w tle, są nad „Kontynuuj”
        assertTrue(a.shelfIds.toString(), "channels" in a.shelfIds)
        idle(500)                                                  // rząd kanałów ma już układ (fokus ▼ trafia w pierwszy rząd)
        // Start: hero – ostatnio oglądany film, rzędy „Kontynuuj” (postęp z Room) i skróty
        assertEquals("Film 1", a.featureTitle)
        assertEquals(listOf("yt:v1"), a.tileKeys("continue"))
        assertTrue(a.shelfIds.contains("shortcuts"))
        // ▼ – pierwszy rząd; ▼ – kolejny; hero pokazuje zaznaczony kafelek (z krótkim opóźnieniem)
        // 7.8: Start – najpierw Live TV (kanały), pod nim STube („Kontynuuj”), potem Stremio / skróty
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("r:channels|"))
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("r:continue|yt:v1", a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("r:${a.shelfIds[2]}|"))   // trzeci rząd (zależnie od danych: Stremio albo skróty)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val shortcut = a.focusedKey!!
        idle(400)
        assertNotEquals("Film 1", a.featureTitle)
        // ▲ – z powrotem ten sam kafelek rzędu wyżej, ▲ – przyciski hero, ▼ ▼ – ostatnio zaznaczony skrót
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("r:continue|yt:v1", a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("r:channels|"))
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("h:"))
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("▲ nad hero nie gubi fokusu", a.focusedKey!!.startsWith("h:"))
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN); key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(shortcut, a.focusedKey)
        // ◄ z pierwszej kolumny – pasek ikon (rozwinięty, bieżąca sekcja)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        if (a.focusedKey!!.startsWith("r:")) key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("nav:s0", a.focusedKey); assertTrue(a.railOpenNow)
        // 7.5: OK na Domku – kursor w prawo na rzędy Startu („Ostatnio oglądane”, gdy są)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { a.focusedKey?.startsWith("r:") == true }
        val home = if ("channels" in a.shelfIds) "channels" else a.shelfIds[0]
        assertEquals("r:$home|${a.tileKeys(home)[0]}", a.focusedKey); assertFalse(a.railOpenNow)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        if (a.focusedKey!!.startsWith("r:")) key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("nav:s0", a.focusedKey)
        // ▼ – zaznaczenie „Na żywo”: podgląd sekcji (fokus zostaje w pasku); OK – od razu ekran Live TV
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("nav:s1", a.focusedKey)
        idle(400)                                                 // 7.7: podgląd po zatrzymaniu kursora na pasku
        assertEquals(1, a.sectionNow)
        generateSequence { shadowOf(a).nextStartedActivity }.toList()
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(MainActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        assertEquals("nav:s1", a.focusedKey)
        // WSTECZ z paska – wyjście
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(a.isFinishing)
    }

    @Test fun railTapFirstActivatesCategoryThenOpensIt() {
        // 7.23: pierwsze stuknięcie – tylko aktywacja kategorii na pulpicie, drugie na aktywnej – wejście w jej ekran
        val a = home()
        generateSequence { shadowOf(a).nextStartedActivity }.toList()
        assertEquals(0, a.sectionNow)
        a.railTap(2); idle()
        assertEquals(2, a.sectionNow); assertEquals(1, a.railTaps)
        assertNull(shadowOf(a).nextStartedActivity)
        a.railTap(2); idle()
        assertEquals(STubeActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        // inna kategoria – znowu najpierw aktywacja; Start: aktywacja, potem kursor na rzędach
        a.railTap(3); idle()
        assertEquals(3, a.sectionNow); assertNull(shadowOf(a).nextStartedActivity)
        a.railTap(0); idle()
        assertEquals(0, a.sectionNow); assertEquals(3, a.railTaps)
        a.railTap(0); waitFor { a.focusedKey?.startsWith("r:") == true }
        assertTrue(a.focusedKey!!.startsWith("r:") || a.focusedKey!!.startsWith("h:"))
        assertEquals(DashboardRail.Tap.ACTIVATE, DashboardRail.tap(active = 1, target = 4))
        assertEquals(DashboardRail.Tap.OPEN, DashboardRail.tap(active = 4, target = 4))
    }

    @Test fun startHeroIsAlwaysLastLiveChannel() {
        // 7.3: Start – zawsze ostatni kanał telewizji, nawet gdy później oglądano film STube / Stremio
        val f = java.io.File(ctx.filesDir, "modern73.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 group-title=\"G\",Kanał Ostatni\nhttp://127.0.0.1:9/live/1.ts\n") }
        Prefs.playlistsJson = "[]"; Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        Prefs.lastChannel = Repository.channels[0].key
        runBlocking { StubeHistory.flush(WatchProgress("v9", "Film 9", "Kanał", positionMs = 300_000, durationMs = 1_200_000, updatedAt = System.currentTimeMillis())) }
        val a = home()
        waitFor { "continue" in a.shelfIds }
        assertEquals("Kanał Ostatni", a.featureTitle)
        // próg „Ostatnio oglądane”: 10 s w motywie Nowoczesnym, 1 min w pozostałych
        assertEquals(10_000L, PlayerActivity.recentAfterMs())
        Ui.applyPreset("classic"); assertEquals(60_000L, PlayerActivity.recentAfterMs())
        Ui.applyPreset(ModernTheme.ID)
    }

    @Test fun heldArrowMovesFocusAtSteadyPace() {
        // 7.3: przytrzymana strzałka – równe tempo (powtórzenia pilota co 20 ms nie zalewają przewijania)
        val a = home()
        val t = android.os.SystemClock.uptimeMillis()
        // naciśnięcie + 10 powtórzeń co 20 ms (200 ms): przechodzą tylko te co ≥ 85 ms (2), reszta pominięta
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        for (i in 1..10) a.dispatchKeyEvent(KeyEvent(t, t + i * 20L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, i))
        a.dispatchKeyEvent(KeyEvent(t, t + 220, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0)); idle()
        assertEquals(8, a.navRepeatsDropped)
        assertNotNull("fokus nie zgubiony", a.focusedKey)
        // pojedyncze naciśnięcia – bez opóźnień
        val t2 = t + 1000
        a.dispatchKeyEvent(KeyEvent(t2, t2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0))
        a.dispatchKeyEvent(KeyEvent(t2, t2 + 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0)); idle()
        assertEquals(8, a.navRepeatsDropped)
    }

    @Test fun heroButtonsAndTilesOpenExistingScreens() {
        val a = home()
        // bez historii – powitanie, „Oglądaj telewizję” = lista kanałów
        assertEquals("Witaj w D-IPTV Player", a.featureTitle)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(MainActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        // „Więcej informacji” – przewodnik TV
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(ModernHomeActivity.HERO_INFO, a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(GuideActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }

    @Test fun profileOptionsPanelSwitchesAndThemeChange() {
        val a = home()
        key(a, KeyEvent.KEYCODE_MENU)
        assertTrue(a.optionsOpen)
        assertTrue(a.optionLabels.containsAll(listOf("Ekran główny przy starcie", "Menu jako panel nakładkowy", "Wtyczki Stremio",
            "Przełącz na motyw PS5", "Przełącz na motyw X25")))
        a.clickOption("Menu jako panel nakładkowy"); idle()
        assertFalse(Prefs.modernOverlay)
        a.clickOption("Menu jako panel nakładkowy"); idle()
        assertTrue(Prefs.modernOverlay)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertFalse(a.optionsOpen)
        waitFor { a.focusedKey == ModernHomeActivity.HERO_PLAY }
        assertEquals(ModernHomeActivity.HERO_PLAY, a.focusedKey)
        key(a, KeyEvent.KEYCODE_MENU)
        a.clickOption("Przełącz na motyw PS5"); idle()
        assertTrue(Ps5Theme.active); assertTrue(a.isFinishing)
        assertEquals(Ps5HomeActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }

    @Test @Config(qualifiers = "w890dp-h400dp-land-xxhdpi") fun compactOnLowLandscapeScreen() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", "Kanał", positionMs = 300_000, durationMs = 1_200_000, updatedAt = 1)) }
        val a = home()
        assertTrue(a.dimsNow.compact)
        waitFor { "continue" in a.shelfIds }
        waitFor { "continue" in a.shelfIds }
        val first = a.shelfIds[0]                                  // 7.8: Live TV na górze (gdy kanały są), potem STube
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("r:$first|"))
        if (first == "channels") { key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("r:continue|yt:v1", a.focusedKey) }
    }
}

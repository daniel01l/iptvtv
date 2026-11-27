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
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Store
import java.time.Duration

/** Motyw PS5 (6.5): preset, tło blueprint, fokus, panele i podpowiedzi, przełączanie z X25, ekran główny w Compose. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class Ps5Test {
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
        Prefs.ps5Home = true; Prefs.ps5Panel = true; Prefs.ps5Sounds = true; Prefs.x25Dashboard = true
        Ui.applyPreset("modern"); Ui.changed()
    }

    @After fun tearDown() { frameView = null; Ps5Input.set(null); X25Theme.launcherForTest = null; X25Theme.launcherHome = false; Prefs.ps5Apps = emptyList(); Ui.applyPreset("modern"); Ui.changed(); X25Theme.justEnabled = false; StubeHistory.useDatabase(null); db.close() }

    // ---------------- motyw ----------------

    @Test fun presetSurfaceBlueprintAndFocus() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        assertTrue(Ps5Theme.active); assertFalse(X25Theme.active); assertEquals(Ui.S_PS5, Ui.surface)
        assertTrue(Ui.geo.glow && !Ui.geo.topBar && Ui.geo.zoomMax > 0.04f)
        // stary, usunięty „ps5” (6.1) nadal prowadzi do Xbox – nowy motyw ma osobne id
        Prefs.theme = "ps5"; assertEquals("xbox", Ui.preset.id)
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        // tło: blueprint rysowany raz do bitmapy w połowie rozdzielczości, wspólnej dla kolejnych ekranów
        val bg = Ui.bgDrawable()
        assertTrue(bg is Ps5Backdrop)
        bg.setBounds(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        bg.draw(android.graphics.Canvas(bmp))
        val renders = Ps5Backdrop.renders
        Ui.bgDrawable().apply { setBounds(0, 0, 1920, 1080); draw(android.graphics.Canvas(bmp)) }
        assertEquals("drugi ekran – ta sama bitmapa", renders, Ps5Backdrop.renders)
        val half = Ps5Backdrop.render(1920, 1080, Ui.bg, Ui.accent)
        assertEquals(960, half.width); assertEquals(540, half.height)
        // granat (niebieski przeważa) – nie zieleń X25
        for (c in listOf(Ui.bg, Ui.panel, Ui.accent)) assertTrue(android.graphics.Color.blue(c) > android.graphics.Color.green(c))
        // fokus: biało-błękitna obwódka z poświatą; słabsze urządzenia – sama obwódka
        Prefs.lowPerf = false
        assertTrue(Ui.focusedBg() is android.graphics.drawable.LayerDrawable)
        Prefs.lowPerf = true
        assertTrue(Ui.focusedBg() is android.graphics.drawable.GradientDrawable)
        Prefs.lowPerf = false
        Prefs.remove("info_style"); assertEquals(1, Ui.infoStyle)
    }

    @Test fun switchBetweenX25AndPs5() {
        Ui.applyPreset("classic"); Ui.changed()
        X25Theme.setEnabled(true); assertTrue(X25Theme.active)
        X25Theme.consumeJustEnabled()
        // PS5 przy X25: motyw konsolowy przełączony, poprzedni = X25
        Ps5Theme.setEnabled(true)
        assertTrue(Ps5Theme.active); assertFalse(X25Theme.active)
        assertEquals(X25Theme.ID, Prefs.ps5PrevTheme); assertTrue(X25Theme.consumeJustEnabled())
        assertTrue(X25Theme.dashboardOn); assertFalse(X25Theme.ownDashboard)
        // wyłączenie PS5 – z powrotem X25 (a X25 pamięta „cinema”)
        Ps5Theme.setEnabled(false)
        assertTrue(X25Theme.active); assertEquals("classic", Prefs.x25PrevTheme)
        Ps5Theme.setEnabled(true); X25Theme.setEnabled(true)
        assertTrue(X25Theme.active); assertEquals(Ps5Theme.ID, Prefs.x25PrevTheme)
        // bez ekranu startowego PS5 – zwykła lista kanałów
        Ps5Theme.setEnabled(true); Prefs.ps5Home = false
        assertFalse(X25Theme.dashboardOn)
    }

    @Test fun menusAsSettingsPanelWithButtonHints() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        // ekran ustawień: pasek podpowiedzi przycisków
        assertNotNull(a.window.decorView.findViewWithTag<android.view.View>(Ps5Theme.HINT_TAG))
        val menu = Ui.dialog(a).setTitle("Menu").setItems(arrayOf("A", "B", "C")) { _, _ -> }.show(); idle()
        assertTrue(Ps5Theme.isPanel(menu)); assertFalse(X25Theme.isGuide(menu))
        val w = menu.window!!.attributes
        assertEquals(Gravity.START or Gravity.TOP, w.gravity)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, w.height)
        val hints = menu.window!!.decorView.findViewWithTag<Ps5Hints>(Ps5Theme.HINT_TAG)
        assertEquals(Ps5Hints.LIST, hints.items)
        menu.dismiss()
        val msg = Ui.dialog(a).setTitle("Pytanie").setMessage("Na pewno?").setPositiveButton("Tak", null).setNegativeButton("Nie", null).show(); idle()
        assertFalse(Ps5Theme.isPanel(msg))
        assertNotEquals(ViewGroup.LayoutParams.MATCH_PARENT, msg.window!!.attributes.height)
        assertEquals(Ps5Hints.DIALOG, msg.window!!.decorView.findViewWithTag<Ps5Hints>(Ps5Theme.HINT_TAG).items)
        // przycisk zaznaczony: granatowy tekst na białej kapsule
        val b = msg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals(Ps5Theme.INK, b.textColors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
        msg.dismiss()
        Prefs.ps5Panel = false
        val m2 = Ui.dialog(a).setTitle("Menu 2").setItems(arrayOf("A")) { _, _ -> }.show(); idle()
        assertFalse(Ps5Theme.isPanel(m2)); m2.dismiss()
    }

    @Test fun hintsFollowInputDeviceAndSwitchDraw() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        // TV: pilot (OK / WSTECZ), nie przyciski kontrolera
        assertEquals(Ps5Input.Mode.REMOTE, Ps5Input.detect(ctx))
        val h = Ps5Hints(ctx, Ps5Hints.HOME)
        assertEquals(Ps5Input.Mode.REMOTE, h.mode)
        h.measure(0, 0); assertTrue(h.measuredWidth > 0 && h.measuredHeight > 0)
        h.layout(0, 0, h.measuredWidth, h.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(h.measuredWidth, h.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        h.draw(android.graphics.Canvas(bmp))
        // przycisk pada – symbole pada (A / B); pilot znów – symbole pilota
        Ps5Input.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0, 0, 0, 0, android.view.InputDevice.SOURCE_GAMEPAD))
        assertEquals(Ps5Input.Mode.GAMEPAD, h.mode)
        h.draw(android.graphics.Canvas(bmp))
        Ps5Input.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 0, 0, 0, android.view.InputDevice.SOURCE_DPAD))
        assertEquals(Ps5Input.Mode.REMOTE, h.mode)
        // dotyk – podpowiedzi ukryte
        Ps5Input.onTouch()
        h.forceLayout(); h.measure(0, 0); assertEquals(0, h.measuredHeight)
        Ps5Input.set(null)
        val sw = Ps5Switch(true)
        sw.setBounds(0, 0, sw.intrinsicWidth, sw.intrinsicHeight)
        val sb = android.graphics.Bitmap.createBitmap(sw.intrinsicWidth, sw.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
        sw.draw(android.graphics.Canvas(sb))
        assertTrue(sw.intrinsicWidth > sw.intrinsicHeight)
    }

    // ---------------- ekran główny (Compose) ----------------

    private fun home(): org.robolectric.android.controller.ActivityController<Ps5HomeActivity> {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        val c = Robolectric.buildActivity(Ps5HomeActivity::class.java).setup().visible()
        c.windowFocusChanged(true)
        frameView = c.get().window.decorView
        waitFor { c.get().focusedKey?.startsWith("a:") == true }
        return c
    }

    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Test fun homeTabsAppRowAndCards() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", "Kanał", positionMs = 5, durationMs = 100, updatedAt = 1)) }
        val a = home().get()
        assertEquals(0, a.tabNow)
        assertEquals("a:live", a.focusedKey)
        assertTrue(a.appKeys.containsAll(listOf("live", "guide", "fav", "epg")))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(a.focusedKey!!.startsWith("a:") && a.focusedKey != "a:live")
        // ▲ – zakładki; ► – zakładka „Multimedia” (zmiana od razu przy zaznaczeniu)
        key(a, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(a.focusedKey!!.startsWith("tab:"))
        if (a.focusedKey != "tab:0") key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("tab:1", a.focusedKey); assertEquals(1, a.tabNow)
        waitFor { "yt:v1" in a.appKeys }
        assertEquals(listOf("stube", "stremio", "yt:v1"), a.appKeys.take(3))
        // nazwa pod rzędem – z nowej zakładki (nie kafelek z „Live TV”)
        assertEquals("STube", a.selectedTitle)
        // ▼ – rząd aplikacji nowej zakładki, ▼ – karty
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("a:"))
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("c:"))
        // pamięć urządzenia z systemu
        waitFor { a.storageNow != null }
        assertNotNull(a.storageNow)
    }

    @Test fun addDeviceAppToHomeRow() {
        val a = home().get()
        assertTrue(a.deviceAppIds.isEmpty())
        val id = android.content.ComponentName(ctx, MainActivity::class.java).flattenToString()
        a.addDeviceAppForTest(id)
        waitFor { a.deviceAppIds.isNotEmpty() }
        assertEquals(listOf(id), a.deviceAppIds); assertEquals(listOf(id), Prefs.ps5Apps)
        // kafelek aplikacji w rzędzie (obie zakładki), na końcu
        assertEquals("pkg:$id", a.appKeys.last())
        // systemowa aplikacja „Live TV” (dubluje telewizję tej aplikacji) – pomijana
        a.addDeviceAppForTest("com.android.tv/com.android.tv.TvActivity"); idle(300)
        assertEquals(listOf(id), a.deviceAppIds)
        Prefs.ps5Apps = emptyList()
    }

    @Test fun stubeAndStremioCardsNextToRecentlyPlayed() {
        runBlocking { StubeHistory.flush(WatchProgress("v1", "Film 1", "Kanał", positionMs = 5, durationMs = 100, updatedAt = 1)) }
        val a = home().get()
        waitFor { "yt:v1" in a.appKeys(1) }
        // ▼ – „Ostatnio odtwarzane”, ► – karta STube (nagłówek / wyszukiwarka / ostatnio oglądane)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("c:hero", a.focusedKey)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("c:yt:"))
        // dalej w prawo – karta Stremio
        repeat(2) { if (!a.focusedKey!!.startsWith("c:st:")) key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("c:st:"))
    }

    @Test fun homeOptionsPanelSwitchesAndBack() {
        val a = home().get()
        key(a, KeyEvent.KEYCODE_MENU)
        assertTrue(a.optionsOpen)
        assertTrue(a.optionLabels.containsAll(listOf("Ekran główny przy starcie", "Dźwięki nawigacji", "Przełącz na motyw X25", "Wyłącz motyw PS5")))
        a.clickOption("Dźwięki nawigacji"); idle()
        assertFalse(Prefs.ps5Sounds)
        a.clickOption("Dźwięki nawigacji"); idle()
        assertTrue(Prefs.ps5Sounds)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertFalse(a.optionsOpen)
        waitFor { a.focusedKey == "a:live" }
        assertEquals("a:live", a.focusedKey)
        // przełączenie na X25 – ekran główny PS5 się zamyka, X25 aktywny
        key(a, KeyEvent.KEYCODE_MENU)
        a.clickOption("Przełącz na motyw X25"); idle()
        assertTrue(X25Theme.active); assertTrue(a.isFinishing)
    }

    @Test fun homeAppOpensExistingScreens() {
        val a = home().get()
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(MainActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }

    @Test @Config(qualifiers = "w890dp-h400dp-land-xxhdpi") fun homeCompactOnLowLandscapeScreen() {
        val a = home().get()
        assertTrue(a.dimsNow.compact)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.focusedKey, a.focusedKey!!.startsWith("c:"))
    }

    @Test fun mainAndBackOpenPs5Home() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        val m = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val started = generateSequence { shadowOf(m.get()).nextStartedActivity }.map { it.component?.className }.toList()
        assertTrue(started.toString(), Ps5HomeActivity::class.java.name in started)
        assertFalse(X25DashboardActivity::class.java.name in started)
        val e = Robolectric.buildActivity(EpgSearchActivity::class.java).setup(); idle()
        e.get().onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(e.get().isFinishing)
        assertEquals(Ps5HomeActivity::class.java.name, shadowOf(e.get()).nextStartedActivity?.component?.className)
    }

    // ---------------- motywy (6.5): tylko 5, PS5 przy pierwszym uruchomieniu ----------------

    @Test fun onlyFiveThemesAndPs5OnFirstRun() {
        assertEquals(listOf("modern", "classic", "xbox", X25Theme.ID, Ps5Theme.ID), Ui.PRESETS.map { it.id })
        for (gone in listOf("ios", "amoled", "light", "neon", "cinema", "forest", "magic", "win11")) {
            Prefs.theme = gone; assertEquals(gone, "modern", Ui.preset.id)
        }
        Prefs.theme = "ps5"; assertEquals("xbox", Ui.preset.id)
        // świeża instalacja bez motywu – (7.7) Nowoczesny z ekranem głównym; aktualizacja / wybrany motyw – bez zmian
        Prefs.remove("theme")
        assertFalse(Ui.applyFirstRunTheme(freshInstall = false)); assertEquals("modern", Ui.preset.id)
        assertTrue(Ui.applyFirstRunTheme(freshInstall = true))
        assertTrue(ModernTheme.active); assertTrue(ModernTheme.homeOn); assertFalse(Ps5Theme.active)
        Ui.applyPreset("classic")
        assertFalse(Ui.applyFirstRunTheme(freshInstall = true)); assertEquals("classic", Ui.preset.id)
    }

    // ---------------- tryb launchera ----------------

    @Test fun launcherHomeOpensConsoleDashboardForEveryTheme() {
        X25Theme.launcherForTest = true; X25Theme.refreshLauncher(ctx)
        // motyw bez własnego pulpitu – ekran główny PS5 (w kolorach motywu), nawet przy wyłączonym „ekranie startowym”
        Ui.applyPreset("classic"); Ui.changed(); Prefs.ps5Home = false
        assertTrue(X25Theme.dashboardOn); assertTrue(Ps5Theme.homeOn)
        assertEquals(Ps5HomeActivity::class.java.name, X25Theme.dashboardIntent(ctx).component?.className)
        val home = Robolectric.buildActivity(Ps5HomeActivity::class.java).setup().get(); idle()
        assertFalse("ekran główny zostaje przy motywie Klasyczny", home.isFinishing)
        // WSTECZ na ekranie głównym launchera nie zamyka go
        home.onBackPressedDispatcher.onBackPressed(); idle()
        assertFalse(home.isFinishing)
        // X25 – pulpit X25, także przy wyłączonej opcji pulpitu
        Ui.applyPreset(X25Theme.ID); Ui.changed(); Prefs.x25Dashboard = false
        assertTrue(X25Theme.ownDashboard)
        assertEquals(X25DashboardActivity::class.java.name, X25Theme.dashboardIntent(ctx).component?.className)
        // bez launchera – zwykłe zasady
        X25Theme.launcherForTest = false; X25Theme.refreshLauncher(ctx)
        assertFalse(X25Theme.dashboardOn)
        Prefs.x25Dashboard = true; Prefs.ps5Home = true
    }

    @Test fun homeButtonGoesStraightToDashboard() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        // kolejne HOME (nie pierwsze od włączenia) – od razu ekran główny, bez listy kanałów
        Prefs.homeBootId = pl.iptv.tv.HomeActivity.bootId(ctx)
        val h = Robolectric.buildActivity(pl.iptv.tv.HomeActivity::class.java).setup().get(); idle()
        assertEquals(Ps5HomeActivity::class.java.name, shadowOf(h).nextStartedActivity?.component?.className)
        // „Live TV” z pulpitu – lista kanałów zostaje na wierzchu (nie odsyła z powrotem na pulpit) i bez akcji startowej
        val i = android.content.Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_DASHBOARD, true).putExtra(MainActivity.EXTRA_SKIP_START, true)
        X25Theme.justEnabled = false                                       // (włączenie PS5 w teście – nie „świeżo włączony” motyw)
        val m = Robolectric.buildActivity(MainActivity::class.java, i).setup().get(); idle()
        val started = generateSequence { shadowOf(m).nextStartedActivity }.map { it.component?.className }.toList()
        assertFalse(started.toString(), Ps5HomeActivity::class.java.name in started)
        // 7.4: lista już istniała pod pulpitem (REORDER_TO_FRONT → onNewIntent) – też zostaje; dawniej od razu wracał pulpit
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        generateSequence { shadowOf(ctl.get()).nextStartedActivity }.toList()
        ctl.newIntent(i); idle()
        val again = generateSequence { shadowOf(ctl.get()).nextStartedActivity }.map { it.component?.className }.toList()
        assertFalse(again.toString(), Ps5HomeActivity::class.java.name in again)
        // HOME (bez wejścia z pulpitu) nadal otwiera pulpit
        ctl.newIntent(android.content.Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SKIP_START, true)); idle()
        assertEquals(Ps5HomeActivity::class.java.name, shadowOf(ctl.get()).nextStartedActivity?.component?.className)
    }

    @Test fun appLibraryWithSystemSettings() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        val c = Robolectric.buildActivity(AppLibraryActivity::class.java).setup().visible()
        frameView = c.get().window.decorView
        val a = c.get()
        waitFor { a.labels.isNotEmpty() }
        assertEquals("Ustawienia systemu", a.labels.first())
        a.launch("Ustawienia systemu"); idle()
        assertEquals(android.provider.Settings.ACTION_SETTINGS, shadowOf(a).nextStartedActivity?.action)
        // „Aplikacje” z pulpitu i z menu – biblioteka
        SystemTools.showApps(a)
        assertEquals(AppLibraryActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }
}

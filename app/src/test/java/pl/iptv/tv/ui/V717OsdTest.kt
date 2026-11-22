package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

/**
 * (7.17) OSD odtwarzacza „Obsidian Stream”: panel z kanałem i audycją, pigułki akcji z fokusem, postęp liczony tylko
 * przy widocznym OSD, auto-ukrywanie po 5 s (reset klawiszem), WSTECZ (OSD → wyjście), panel ścieżek z prawej,
 * przejście z podglądu (PiP w menu) na pełny ekran bez ponownego startu strumienia.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V717OsdTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var frame: View? = null
    private val canvas by lazy { android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)) }
    /** Czas płynie w krokach; po każdym – rysowanie okna (Compose w Robolectric potrzebuje klatek). */
    private fun idle(ms: Long = 300) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 100L); left -= step
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()   // zmiany stanu z kodu widoków (w Robolectric globalny menedżer migawek działa tylko w 1. teście procesu)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            frame?.let { v -> v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1920, 1080); v.draw(canvas) }
        }
    }
    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); idle(30)
        a.dispatchKeyEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0)); idle(300)
    }

    @Before fun setup() {
        Playback.stop()
        PlayerOsd.enabled = true                                             // w Robolectric OSD włącza tylko ten test
        PlayerOsd.autoHideMs = 1_500; PlayerOsd.progressTickMs = 100        // krótsze czasy w teście (w aplikacji 5 s i 500 ms)
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.tutorialShown = true; Prefs.keyMapJson = ""
        // stan z wcześniejszych testów (kontrola rodzicielska, panele, ulubione kanały w innej kolejności) – od zera
        Prefs.parentalOn = false; Prefs.parentalPin = ""; pl.iptv.tv.data.Parental.unlocked = false
        Prefs.lockedChannels = emptyList(); Prefs.lockedGroups = emptyList(); Prefs.parentalHide = false
        Prefs.channelPanel = true; Prefs.recentPanel = true; Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList()
        Ui.applyPreset("classic"); Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
        val f = File(ctx.filesDir, "osd.m3u").apply { writeText((1..5).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"o$it\",Kanał $it\nhttp://osd/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
        val now = System.currentTimeMillis()
        fun t(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms)) + " +0000"
        val xml = "<tv>" + (1..5).joinToString("") { "<programme start=\"${t(now - 1_800_000)}\" stop=\"${t(now + 1_800_000)}\" channel=\"o$it\"><title>Audycja $it</title></programme>" } + "</tv>"
        runBlocking { Repository.loadEpg(File(ctx.filesDir, "osd.xml").apply { writeText(xml) }.absolutePath) }
    }

    // animacje i odliczania nakładki dobiegają końca w tym teście (klatka zaplanowana na koniec testu przepadłaby przy czyszczeniu pętli Robolectric i blokowała Compose w kolejnym)
    private var controller: org.robolectric.android.controller.ActivityController<PlayerActivity>? = null
    @After fun tearDown() {
        idle(900)
        // ekran zamknięty do końca – kompozycja i jej zegar klatek nie zostają na kolejny test
        frame = null
        runCatching { controller?.pause()?.stop()?.destroy() }; controller = null; idle(200)
        frame = null; Playback.stop(); PlayerOsd.autoHideMs = PlayerOsd.AUTO_HIDE_MS; PlayerOsd.progressTickMs = PlayerOsd.PROGRESS_TICK_MS
        PlayerOsd.enabled = false
    }

    private fun open(): PlayerActivity {
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup()
        controller = c; val a = c.get()
        frame = a.window.decorView
        idle(400)
        return a
    }

    @Test fun osdShowsChannelLiveAndProgressOnlyWhileVisible() {
        val a = open()
        val s = a.osd!!
        // start kanału pokazał OSD (sam podgląd, bez pigułek) z kanałem i audycją; dawny pasek informacji ukryty
        assertTrue("OSD pokazany po starcie kanału", s.lastInput > 0); assertFalse(s.interactive)
        assertEquals("Kanał 1", s.info.name.trim()); assertTrue(s.info.live); assertEquals("Audycja 1", s.info.title)
        assertEquals(View.GONE, a.findViewById<View>(pl.iptv.tv.R.id.overlay).visibility)
        // ▼ – zmiana kanału: OSD od razu z nowym kanałem
        idle(PlayerOsd.autoHideMs + 200)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("OSD po zmianie kanału", s.visible); assertEquals("Kanał 2", s.info.name.trim())
        idle(600)
        val reads = s.progressReads
        assertTrue("postęp odczytywany cyklicznie przy widocznym OSD", reads >= 2)
        // auto-ukrywanie po 5 s bezczynności – potem zero odczytów postępu
        idle(PlayerOsd.autoHideMs)
        assertFalse(s.visible)
        val hiddenReads = s.progressReads
        idle(800)
        assertEquals("ukryty OSD nie odczytuje pozycji", hiddenReads, s.progressReads)
    }

    @Test fun okShowsActionPillsKeysResetTimerAndBackHidesThenLeaves() {
        val a = open()
        val s = a.osd!!
        idle(PlayerOsd.autoHideMs + 200); assertFalse(s.visible)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(s.visible); assertTrue(s.interactive)
        assertEquals(listOf("Audio / Napisy", "Jakość", "Program TV", "Lista kanałów"), s.actions.map { it.label })
        assertEquals("zaznaczona pierwsza pigułka", 0, s.pillIndex)
        // ► ► – kolejne pigułki (▲▼ przy pigułkach dalej zmieniają kanał), klawisze odsuwają auto-ukrywanie
        idle(600)                                        // OSD od ~1,2 s (odliczanie 1,5 s)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(1, s.pillIndex)
        idle(1000)                                       // od OK > 1,5 s, od ► < 1,5 s
        assertTrue("klawisz zeruje licznik 5 s", s.visible)
        repeat(5) { key(a, KeyEvent.KEYCODE_DPAD_RIGHT) }; assertEquals("ostatnia pigułka – bez wyjścia poza rząd", 3, s.pillIndex)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(2, s.pillIndex)
        // OK na „Jakość”: panel z prawej (w Robolectric strumień nie ma wariantów – komunikat, OSD zostaje)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_CENTER); assertTrue(s.visible)
        // WSTECZ: najpierw chowa OSD, dopiero drugie wychodzi z pełnego ekranu
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertFalse(s.visible); assertFalse(a.isFinishing)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(a.isFinishing)
    }

    @Test fun trackPanelFromRightClosesWithBackAndKeepsOsd() {
        val a = open()
        val s = a.osd!!
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        var picked = ""
        s.openPanel(OsdPanel("Audio / Napisy", listOf(
            OsdSection("Audio", listOf(OsdOption("Polski", true) { picked = "pl" }, OsdOption("English", false) { picked = "en" })),
            OsdSection("Napisy", listOf(OsdOption("Wyłączone", true) { picked = "off" })))))
        idle(500)
        assertEquals("zaznaczona bieżąca ścieżka", 0, s.panelIndex)
        // panel otwarty – bez auto-ukrywania
        idle(PlayerOsd.autoHideMs + 500); assertNotNull("panel otwarty", s.panel); assertTrue("OSD widoczny", s.visible)
        // ◄ ► nie wychodzą z panelu, ▼▼▼ – do ostatniej pozycji (bez wyjścia), ▲▲ – „English”, OK – wybór i zamknięcie panelu
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(0, s.panelIndex); assertNotNull(s.panel)
        repeat(3) { key(a, KeyEvent.KEYCODE_DPAD_DOWN) }; assertEquals(2, s.panelIndex)
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals(1, s.panelIndex)
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("en", picked); assertNull(s.panel); assertTrue(s.visible)
        // WSTECZ zamyka panel, OSD zostaje
        s.openPanel(OsdPanel("Jakość", listOf(OsdSection("", listOf(OsdOption("Automatyczna", true) {}))))); idle(400)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertNull(s.panel); assertTrue(s.visible); assertFalse(a.isFinishing)
    }

    @Test fun previewToFullscreenKeepsSamePlayerWithoutRestart() {
        val ch = Repository.channels[0]
        Playback.play(ctx, ch, ch.url)
        idle(300)
        val p = Playback.player; val starts = Playback.starts
        assertNotNull(p)
        val a = open()
        idle(500)
        assertSame("ten sam ExoPlayer (wspólny odtwarzacz)", p, Playback.player)
        assertEquals("bez ponownego startu strumienia / kodeka", starts, Playback.starts)
        assertSame(p, a.findViewById<androidx.media3.ui.PlayerView>(pl.iptv.tv.R.id.playerView).player)
    }
}

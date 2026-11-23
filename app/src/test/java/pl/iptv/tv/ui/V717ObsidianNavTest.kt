package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
import pl.iptv.tv.R
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.ui.theme.AppNavRail
import pl.iptv.tv.ui.theme.ObsidianViews
import java.io.File
import java.time.Duration

/**
 * (7.17) „Obsidian Stream” na ekranach (motyw Nowoczesny): wspólny pasek nawigacji 72 → 240 dp ze scrimem, Kanały/EPG
 * w 3 kolumnach, STube z paskiem nad treścią, Ustawienia z panelami 90 % – i nawigacja pilotem, w której fokus się nie gubi.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V717ObsidianNavTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun focus(a: android.app.Activity) = a.window.decorView.findFocus()
    private fun inside(v: View?, parent: View): Boolean { var x = v; while (x != null) { if (x === parent) return true; x = x.parent as? View }; return false }
    private fun key(a: android.app.Activity, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); a.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
        idle(400)
    }
    private fun dp(v: Int) = Ui.dp(v)
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.mainPreview = true; Prefs.orientation = 0
        Prefs.modernHome = false; Prefs.mainLayout = 0; Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""
        Ui.applyPreset("classic"); Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
        val f = File(ctx.filesDir, "v717.m3u").apply {
            writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"${listOf("Muzyka", "Filmy", "TV")[it % 3]}\",Kanał $it\nhttp://v717/$it\n" })
        }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @After fun tearDown() {
        Prefs.modernHome = true; Prefs.mainPreview = false
        StubeHistory.useDatabase(null); db.close()
        YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
    }

    @Test fun paletteIsObsidianInModernThemeOnly() {
        assertTrue(ObsidianViews.active)
        assertEquals(ObsidianViews.BG, Ui.bg); assertEquals(ObsidianViews.ACCENT, Ui.accent)
        assertEquals(16, Ui.preset.radius)
        Ui.applyPreset("classic"); Ui.changed()
        assertFalse("inne motywy bez zmian", ObsidianViews.active)
        Ui.applyPreset(ModernTheme.ID); Ui.changed()
    }

    @Test fun channelsEpgThreeColumnsAndRailWithScrim() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        val rail = a.findViewById<ViewGroup>(R.id.sideNav)
        val groups = a.findViewById<RecyclerView>(R.id.groups)
        val channels = a.findViewById<RecyclerView>(R.id.channels)
        val box = a.findViewById<View>(R.id.previewBox)
        // układ: pasek nad treścią (FrameLayout), kolumny 72 dp / 42 % / reszta
        assertTrue(rail.parent is android.widget.FrameLayout)
        assertEquals(dp(72), rail.layoutParams.width)
        assertEquals(dp(72), groups.layoutParams.width)
        assertEquals((a.resources.displayMetrics.widthPixels * 0.42f).toInt(), channels.layoutParams.width)
        assertNotNull(box.background)
        // kategorie jako krótkie pigułki
        val firstGroup = groups.getChildAt(0) as android.widget.TextView
        assertTrue(firstGroup.text.length <= 3)
        val scrim = (rail.parent as ViewGroup).let { p -> p.getChildAt(p.indexOfChild(rail) - 1) }
        assertEquals(View.GONE, scrim.visibility)

        // fokus na kanale → ◄ kategorie → ◄ pasek (rozwinięty, scrim, podpisy)
        channels.focusPosition(0); idle(400)
        assertTrue(inside(focus(a), channels))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("◄ z kanałów → kategorie", inside(focus(a), groups))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("◄ z kategorii → pasek", inside(focus(a), rail))
        assertEquals(dp(AppNavRail.EXPANDED.value.toInt()), rail.layoutParams.width)
        assertEquals(View.VISIBLE, scrim.visibility); assertEquals(1f, scrim.alpha, 0.01f)
        assertEquals("Program", (focus(a) as android.widget.TextView).text.toString())
        // ▼▲ w pasku, ◄ zostaje w pasku
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("Kanały", (focus(a) as android.widget.TextView).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals("Program", (focus(a) as android.widget.TextView).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertTrue(inside(focus(a), rail))
        // ► zamyka pasek: fokus na wybranej kategorii, pasek zwinięty bez podpisów, scrim znika
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("► z paska → kategorie (fokus nie ginie)", inside(focus(a), groups))
        assertEquals(dp(72), rail.layoutParams.width)
        assertEquals(View.GONE, scrim.visibility)
        assertTrue((0 until rail.childCount).all { (rail.getChildAt(it) as? android.widget.TextView)?.text.isNullOrEmpty() })
        // kategorie ▼ i ► kanały, ► program (z EPG – program, bez – kursor zostaje na kanale), ◄ wraca na kanały
        val progs = a.findViewById<RecyclerView>(R.id.programmes)
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertTrue(inside(focus(a), groups))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue("► z kategorii → kanały", inside(focus(a), channels))
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertTrue(inside(focus(a), channels))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("► z kanału nie gubi fokusu", inside(focus(a), channels) || inside(focus(a), progs))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertTrue("◄ wraca na kanały", inside(focus(a), channels))
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertTrue(inside(focus(a), channels))
    }

    @Test fun programmeColumnLivePillAndBackToChannels() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun t(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms)) + " +0000"
        // kanał 1 (kategoria TV jest pierwsza w liście „Wszystkie”): program teraz, potem i jutro
        val xml = "<tv><channel id=\"c1\"><display-name>Kanał 1</display-name></channel>" +
            listOf(Triple(now - 600_000, now + 600_000, "Teraz – Wiadomości"), Triple(now + 600_000, now + 1_800_000, "Potem"), Triple(now + day, now + day + 1_800_000, "Jutro rano"))
                .joinToString("") { (s, e, n) -> "<programme start=\"${t(s)}\" stop=\"${t(e)}\" channel=\"c1\"><title>$n</title></programme>" } + "</tv>"
        runBlocking { Repository.loadEpg(File(ctx.filesDir, "v717.xml").apply { writeText(xml) }.absolutePath) }
        Prefs.lastChannel = Repository.channels.first { it.name == "Kanał 1" }.key
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        val channels = a.findViewById<RecyclerView>(R.id.channels)
        val progs = a.findViewById<RecyclerView>(R.id.programmes)
        val idx = (channels.adapter as ChannelAdapter).items.indexOfFirst { it.name == "Kanał 1" }
        channels.focusPosition(idx); idle(800)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("► z kanału → program", inside(focus(a), progs))
        idle(300)
        val texts = (0 until progs.childCount).flatMap { i -> (progs.getChildAt(i) as ViewGroup).let { g -> listOf(g.findViewById<android.widget.TextView>(R.id.badge), g.findViewById(R.id.day)) } }
        val live = texts.first { it.text.toString() == "NA ŻYWO" }
        assertNotNull("czerwona pigułka NA ŻYWO", live.background)
        assertTrue("oddzielony kolejny dzień", texts.any { it.visibility == View.VISIBLE && it.text.startsWith("Jutro") })
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("◄ z programu → kanały", inside(focus(a), channels))
    }

    @Test fun stubeRailOverlaysContentAndFocusReturns() {
        Prefs.ytApiKey = ""
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = (1..4).map { YtVideo("$id-$it", "$id $it", "Kanał", "", "", "PT3M${it}S") }
            override fun search(q: String) = listOf(YtVideo("kw1", "Wynik", "K"))
        }
        val a = Robolectric.buildActivity(STubeActivity::class.java).setup().get()
        repeat(60) { idle(100); Thread.sleep(10); if (a.rows.firstOrNull()?.tiles?.isNotEmpty() == true && focus(a) != null) return@repeat }
        val railScroll = a.findViewById<View>(R.id.stRailScroll)
        val rail = a.findViewById<ViewGroup>(R.id.stRail)
        assertTrue("pasek nad treścią", railScroll.parent !== a.findViewById<View>(R.id.stRoot))
        assertEquals(dp(72), railScroll.layoutParams.width)
        // pigułki: Start, Ostatnie, Playlisty, Na później (bez konta)
        assertEquals(listOf("Start", "Ostatnie", "Playlisty", "Na później"), a.chipViews.map { (it as android.widget.TextView).text.toString() }.filter { it in setOf("Start", "Ostatnie", "Playlisty", "Na później") })
        // miniatury 16:9
        val lay = StubeTheme.layout
        assertEquals((dp(lay.tileW) - dp(12)) * 9 / 16, STubeScreen.thumbHeightPx(lay))
        // ◄ z pierwszego kafelka → pasek (rozwinięty z podpisem); ► wraca do rzędu, pasek zwinięty
        val firstRow = a.findViewById<ViewGroup>(R.id.stRows)
        assertTrue(inside(focus(a), firstRow))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("◄ → pasek", inside(focus(a), rail))
        assertEquals(dp(AppNavRail.EXPANDED.value.toInt()), railScroll.layoutParams.width)
        assertEquals("Start", (focus(a) as android.widget.TextView).text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN); assertTrue(inside(focus(a), rail))
        key(a, KeyEvent.KEYCODE_DPAD_UP); assertEquals(R.id.stHome, focus(a)?.id)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("► z paska → rzędy (fokus nie ginie)", inside(focus(a), firstRow))
        assertEquals(dp(72), railScroll.layoutParams.width)
        // ▲ → pigułki → wyszukiwarka
        repeat(3) { key(a, KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue(focus(a) in a.chipViews || focus(a)?.id == R.id.stQuery)
        // WSTECZ: ekran zamknięty bez błędów
        a.onBackPressedDispatcher.onBackPressed(); idle()
    }

    @Test fun settingsPanelsNinetyPercentAndDimmedVideo() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle(500)
        val panels = mutableListOf<android.graphics.drawable.GradientDrawable>()
        fun walk(v: View) { (v.background as? android.graphics.drawable.GradientDrawable)?.let { if (it.color?.defaultColor == ObsidianViews.PANEL_90) panels += it }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(a.window.decorView)
        assertTrue("kolumna kart i panel opcji – #0F141F 90 %", panels.size >= 2)
        assertEquals(0xE6, android.graphics.Color.alpha(ObsidianViews.PANEL_90))
        assertEquals(0xA6, android.graphics.Color.alpha(ObsidianViews.VIDEO_DIM_65))
        // ▼ ► ◄ w Ustawieniach – fokus zawsze na elemencie
        repeat(2) { key(a, KeyEvent.KEYCODE_DPAD_DOWN) }; assertNotNull(focus(a))
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertNotNull(focus(a))
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertNotNull(focus(a))
    }
}

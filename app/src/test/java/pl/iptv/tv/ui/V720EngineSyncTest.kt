package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
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
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.io.File
import java.time.Duration

/**
 * 7.20 – jedno źródło ustawień silnika ([EngineConfigRepository]) dla Ustawień i okna silnika w odtwarzaczu ([EngineSheet]),
 * przebudowa odtwarzacza w locie ([Playback.reloadIfNeeded]) z pigułką statusu, plakietki „Globalne” / „Dla tego kanału”,
 * okna z kartami w kolorach aktywnego motywu ([CardSheet.palette]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V720EngineSyncTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase
    private val R = EngineConfigRepository

    @Before fun setup() {
        Playback.stop()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        ChannelEngines.resetForTest(); ChannelEngineOptimizer.resetSession()
        Prefs.engineProfilesVersion = ChannelEngines.PROFILES_VERSION
        Prefs.playerEngine = 0; Prefs.hwAccel = true; Prefs.asyncDecoder = 0; Prefs.surfaceMode = 0; Prefs.autoFrameRate = false
        Prefs.bufMinS = 0; Prefs.lowLatency = false; Prefs.loudnessLeveling = false; Prefs.lowPerf = false; Prefs.decoderFallback = true
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true; Prefs.startAction = 0; Prefs.tutorialShown = true; Prefs.okLongAction = 0
        idle()
    }

    @After fun tearDown() {
        Playback.onEngineApplied = null
        Prefs.playerEngine = 0; Prefs.hwAccel = true; Prefs.asyncDecoder = 0; Prefs.surfaceMode = 0; Prefs.bufMinS = 0; Prefs.loudnessLeveling = false
        Playback.stop(); ChannelEngineOptimizer.resetSession(); ChannelEngines.resetForTest()
        StubeHistory.useDatabase(null); db.close()
    }

    @Test fun repositoryIsSingleSourceForGlobalAndChannelSettings() {
        // każdy zapis ustawień silnika (repozytorium, Prefs z innego miejsca) – od razu w StateFlow
        R.setHwAccel(false); assertFalse(R.effectiveConfig.value.hwAccel); assertTrue(R.current.software)
        Prefs.hwAccel = true; assertTrue(R.effectiveConfig.value.hwAccel)
        // tunelowanie: akceleracja i kolejka asynchroniczna włączane razem; wyłączenie akceleracji – powrót na sprzętowy
        R.setEngine(Playback.ENGINE_TUNNELING); assertTrue(R.current.hwAccel); assertEquals(1, R.current.asyncDecoder); assertTrue(R.current.tunneling)
        R.setHwAccel(false); assertEquals(Playback.ENGINE_HARDWARE, R.current.engine)
        R.setHwAccel(true); R.setAsyncDecoder(0)
        // profil kanału: wartości efektywne i źródło „Dla tego kanału”
        R.bindChannel("k1")
        assertEquals(ConfigSource.GLOBAL, R.current.softwareSource); assertFalse(R.current.hasChannelOverride)
        R.setChannelProfile("k1", "http://u/1", EngineProfile(software = true, async = 2, surface = 1, buffer = 1))
        val c = R.current
        assertTrue(c.software); assertEquals(ConfigSource.CHANNEL, c.softwareSource)
        assertEquals(2, c.effectiveAsync); assertEquals(ConfigSource.CHANNEL, c.asyncSource)
        assertTrue(c.textureView); assertEquals(ConfigSource.CHANNEL, c.surfaceSource)
        assertEquals(ConfigSource.CHANNEL, c.bufferSource); assertFalse(c.tunneling)
        // „Zastosuj globalnie” – ustawienia kanału przeniesione do globalnych, profil kanału usunięty
        R.applyChannelGlobally("k1", "http://u/1")
        assertEquals(Playback.ENGINE_SOFTWARE, Prefs.playerEngine); assertEquals(2, Prefs.asyncDecoder)
        assertEquals(RenderSurfaceMode.TEXTURE_VIEW.ordinal, Prefs.surfaceMode); assertEquals(3, Prefs.bufMinS)
        assertNull(ChannelEngines.get("k1")); assertEquals(ConfigSource.GLOBAL, R.current.softwareSource)
        // tryb zgodności kanału i reset
        R.setChannelCompatibility("k1", "http://u/1", true); assertEquals(EngineProfile(software = true, surface = 1), ChannelEngines.profileFor("k1"))
        R.resetChannel("k1", "http://u/1"); assertNull(ChannelEngines.get("k1")); assertFalse(R.current.hasChannelOverride)
    }

    @Test fun settingChangeRebuildsPlayerLiveAndAnnouncesDecoder() {
        val ch = Channel("hot", "Kanał HD", "http://127.0.0.1:9/live/hot.ts", "G")
        val texts = mutableListOf<String>()
        Playback.onEngineApplied = { texts += it }
        Playback.play(ctx, ch, ch.url); idle()
        val first = Playback.get(ctx); val reloads = Playback.hotReloads
        // powierzchnia, AFR, głośność – bez nowego odtwarzacza
        R.setSurfaceMode(RenderSurfaceMode.TEXTURE_VIEW); R.setAutoFrameRate(true); R.setLoudness(true); idle()
        assertSame(first, Playback.get(ctx)); assertEquals(reloads, Playback.hotReloads)
        // dekoder – nowy odtwarzacz od razu (ten sam kanał, bez przełączania), pigułka „Zastosowano: …”
        R.setEngine(Playback.ENGINE_SOFTWARE); idle(50)
        assertEquals(reloads + 1, Playback.hotReloads)
        assertNotSame(first, Playback.get(ctx)); assertEquals(ch.url, Playback.url); assertTrue(Playback.softwareDecoding)
        idle(Playback.ANNOUNCE_FALLBACK_MS + 100)
        assertTrue(texts.toString(), texts.single().startsWith("Zastosowano: Dekoder programowy"))
        // zmiana bez wpływu na odtwarzacz (ta sama wartość) – bez przebudowy; drugie wywołanie – nic
        val second = Playback.get(ctx)
        R.setEngine(Playback.ENGINE_SOFTWARE); idle()
        assertFalse(Playback.reloadIfNeeded()); assertSame(second, Playback.get(ctx))
        // profil kanału zapisany z okna odtwarzacza – też od razu
        R.setChannelProfile(ch.key, ch.url, EngineProfile(ffmpeg = true)); idle()
        assertNotSame(second, Playback.get(ctx)); assertEquals(EngineProfile(ffmpeg = true), Playback.profile)
        assertEquals("Zastosowano: Dekoder programowy (c2.android.avc) • TextureView",
            Playback.appliedText(R.current.copy(engine = Playback.ENGINE_SOFTWARE, surfaceMode = 2), "c2.android.avc.decoder"))
        assertTrue(Playback.codecSummary().startsWith("Wideo:") && Playback.codecSummary().contains("| Audio:"))
        assertEquals("E-AC3", Playback.audioCodecName(androidx.media3.common.MimeTypes.AUDIO_E_AC3))
    }

    private fun playerOn(key: String) = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
        .putExtra(PlayerActivity.EXTRA_CHANNEL, key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup()

    private fun loadList() {
        val m3u = File(ctx.filesDir, "v720sync.m3u")
        m3u.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"x\",Canal+ Extra 2\nhttp://127.0.0.1:9/live/x.ts\n#EXTINF:-1 tvg-id=\"y\",TVP 1\nhttp://127.0.0.1:9/live/y.ts\n")
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
    }

    @Test fun playerSheetShowsSourcesAppliesGloballyAndStaysInSyncWithSettings() {
        loadList()
        val ch = Repository.channels[0]
        ChannelEngines.save(ch.key, ch.url, EngineProfile(software = true, surface = 1))
        val pc = playerOn(ch.key); idle(800)
        val a = pc.get()
        val d = EngineSheet.show(a, ch, { Playback.url }); idle()
        fun card(key: String) = all(d.window!!.decorView).first { it.tag == "engine:$key" }
        fun chip(key: String) = all(card(key)).firstOrNull { (it.tag as? String)?.startsWith("source:") == true }?.tag
        // nagłówek z kodekami na żywo
        assertTrue(all(d.window!!.decorView).filterIsInstance<TextView>().first { it.tag == "engine_codecs" }.text.startsWith("Wideo:"))
        assertEquals("source:CHANNEL", chip("engine")); assertEquals("source:CHANNEL", chip("surface")); assertEquals("source:GLOBAL", chip("async"))
        assertTrue(card("compat").contentDescription.endsWith("włączone"))
        // bez poziomych linii – karty w panelu (żadnej ListView / separatora)
        assertTrue(all(d.window!!.decorView).none { it is android.widget.ListView })
        // zmiana z Ustawień (to samo repozytorium) – okno odświeżone od razu
        R.setLoudness(true); idle()
        assertTrue(card("loudness").contentDescription.endsWith("włączone"))
        // „Zastosuj globalnie” – globalne, profil kanału usunięty, plakietki „Globalne”
        card("apply_global").performClick(); idle()
        assertEquals(Playback.ENGINE_SOFTWARE, Prefs.playerEngine); assertEquals(RenderSurfaceMode.TEXTURE_VIEW.ordinal, Prefs.surfaceMode)
        assertNull(ChannelEngines.get(ch.key))
        assertEquals("source:GLOBAL", chip("engine")); assertTrue(all(d.window!!.decorView).none { it.tag == "engine:reset_channel" })
        // „Resetuj dla kanału” po włączeniu trybu zgodności kanału
        card("compat").performClick(); idle()
        assertNotNull(ChannelEngines.get(ch.key))
        card("reset_channel").performClick(); idle()
        assertNull(ChannelEngines.get(ch.key))
        d.dismiss(); idle()
        // Ustawienia: wartość zmieniona w odtwarzaczu widoczna (ten sam StateFlow), a zmiana w Ustawieniach – w oknie odtwarzacza
        val st = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(800)
        fun hwRow() = all(st.get().window.decorView).first { v -> v.isClickable && all(v).filterIsInstance<TextView>().any { it.text == "Akceleracja sprzętowa obrazu" } }
        fun hwOn(v: View) = v.contentDescription?.toString()?.endsWith(": włączone") ?: all(v).filterIsInstance<TextView>().any { it.text == "✔ Włączone" }
        assertTrue(hwOn(hwRow()))
        R.setHwAccel(false); idle()                                   // np. z okna w odtwarzaczu
        assertFalse(hwOn(hwRow()))
        hwRow().performClick(); idle()                                // w Ustawieniach
        assertTrue(R.current.hwAccel)
        st.pause().stop().destroy(); pc.pause().stop().destroy()
    }

    @Test fun statusPillAndCardWindowsFollowActiveTheme() {
        loadList()
        val pc = playerOn(Repository.channels[1].key); idle(800)
        val a = pc.get()
        EngineStatusPill.show(a, "Zastosowano: Dekoder programowy (c2.android.avc)"); idle(50)
        val pill = EngineStatusPill.current(a)!!
        assertEquals("Zastosowano: Dekoder programowy (c2.android.avc)", pill.text.toString()); assertFalse(pill.isFocusable)
        idle(EngineStatusPill.SHOW_MS + EngineStatusPill.FADE_MS + 200)
        assertNull(EngineStatusPill.current(a))
        // „Obsidian Stream” (Nowoczesny): panel #0F141F 96 %
        if (pl.iptv.tv.ui.theme.ObsidianViews.active) assertEquals(0xF50F141F.toInt(), CardSheet.palette().panel)
        assertEquals(0xF50F141F.toInt(), Dialogs.CHOICE_PANEL)
        pc.pause().stop().destroy()
        // inny motyw: kolory okna z tokenów motywu
        classicTheme {
            val p = CardSheet.palette()
            assertEquals(CardSheet.withAlpha(Ui.panel, CardSheet.PANEL_ALPHA), p.panel)
            assertEquals(Ui.text, p.text); assertEquals(Ui.accent, p.accent); assertEquals(Ui.focus, p.focusFill); assertEquals(Ui.focusText, p.focusText)
            val act = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle(500)
            val d = Dialogs.choiceCards(act.get(), "Test", listOf("A", "B"), 0) {}; idle()
            val panel = (d.window!!.decorView as android.view.ViewGroup).let { all(it) }.first { it.background is android.graphics.drawable.GradientDrawable }
            assertEquals(p.panel, ((panel.background as android.graphics.drawable.GradientDrawable).color)?.defaultColor)
            d.dismiss(); act.pause().stop().destroy()
        }
    }
}

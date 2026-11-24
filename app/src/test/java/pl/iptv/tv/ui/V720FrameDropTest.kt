package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
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
 * 7.20 – klatkowanie obrazu po 2–3 s przy płynnym dźwięku (kanały 50 kl/s / 1080i50, TCL z Google TV): prawdziwy dekoder
 * programowy (kodeki SoC odrzucone), strażnik zrzucanych klatek z zapisem trybu zgodności dla kanału, TextureView,
 * strategia odświeżania przy wyłączonym AFR, dłuższe dołączanie obrazu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V720FrameDropTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var db: StubeDatabase
    private val realLoudness = ChannelLoudness.factory

    @Before fun setup() {
        Playback.stop()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        ChannelEngines.resetForTest(); ChannelEngineOptimizer.resetSession()
        Prefs.engineProfilesVersion = ChannelEngines.PROFILES_VERSION
        Prefs.surfaceMode = 0; Prefs.dropWatchdog = true; Prefs.loudnessLeveling = false; Prefs.engineOptimizer = true; Prefs.hwAccel = true; Prefs.playerEngine = Playback.ENGINE_HARDWARE
        Prefs.autoFrameRate = false
    }

    @After fun tearDown() {
        Playback.onFrameDropFallback = null
        Prefs.surfaceMode = 0; Prefs.dropWatchdog = true; Prefs.loudnessLeveling = false; ChannelLoudness.release(); ChannelLoudness.factory = realLoudness
        Playback.stop(); ChannelEngineOptimizer.resetSession(); ChannelEngines.resetForTest()
        StubeHistory.useDatabase(null); db.close()
    }

    private data class Codec(val name: String, val hw: Boolean, val sw: Boolean)
    private fun pick(vararg c: Codec) = SoftwareCodecs.pick(c.toList(), { it.name }, { it.hw }, { it.sw }).map { it.name }

    @Test fun softwareModeRejectsSocHardwareCodecs() {
        // lista z TCL (MediaTek): kodek sprzętowy producenta pierwszy, systemowe programowe dalej
        val tcl = pick(Codec("c2.mtk.avc.decoder", true, false), Codec("OMX.MTK.VIDEO.DECODER.AVC", true, false),
            Codec("c2.android.avc.decoder", false, true), Codec("OMX.google.h264.decoder", false, true))
        assertEquals(listOf("c2.android.avc.decoder", "OMX.google.h264.decoder"), tcl)
        // kodek producenta zgłoszony błędnie jako programowy też odrzucony
        listOf("OMX.amlogic.avc.decoder.awesome", "c2.amlogic.avc.decoder", "OMX.realtek.video.decoder", "c2.realtek.avc",
            "OMX.qcom.video.decoder.avc", "c2.qti.avc.decoder").forEach {
            assertTrue(it, SoftwareCodecs.isVendorHardware(it))
            assertFalse(it, SoftwareCodecs.isSystemSoftware(it, hardwareAccelerated = false, softwareOnly = true))
        }
        assertFalse(SoftwareCodecs.isSystemSoftware("c2.android.avc.decoder", hardwareAccelerated = true, softwareOnly = false))
        assertTrue(SoftwareCodecs.isSystemSoftware("c2.android.hevc.decoder", hardwareAccelerated = false, softwareOnly = false))
        assertTrue(SoftwareCodecs.isSystemSoftware("some.sw.decoder", hardwareAccelerated = false, softwareOnly = true))
        // format bez dekodera programowego – pełna lista (programowe pierwsze), żeby obraz w ogóle ruszył
        // („x.sw” – akcelerowany, więc odrzucony przez filtr; w zapasie przed kodekiem SoC)
        assertEquals(listOf("x.sw", "c2.mtk.vc1"), pick(Codec("c2.mtk.vc1", true, false), Codec("x.sw", true, true)))
    }

    @Test fun watchdogFiresOnlyForSustainedDropsWhilePlayingSmoothly() {
        val w = FrameDropWatchdog()
        assertEquals(20f, FrameDropWatchdog.rate(10, 500), 0.01f)
        assertEquals(0f, FrameDropWatchdog.rate(10, 0), 0.01f)
        // przed startem odtwarzania (brak READY) – nic
        assertFalse(w.onDropped(50, 1000, 1000))
        w.onState(Player.STATE_READY, 0)
        // pierwsze 2,5 s – zrzucanie na starcie (dołączanie obrazu) nie liczy się jako klatkowanie
        assertFalse(w.onDropped(10, 500, 1000))
        assertFalse(w.onDropped(10, 500, 2000))
        // po 2,5 s: 10 klatek w 0,5 s (20 kl/s) i ≥ 20 w oknie 3 s → alarm, tylko raz
        assertTrue(w.onDropped(10, 500, 2600))
        assertFalse(w.onDropped(10, 500, 3000))
        // pojedyncze zrzuty (6,7 kl/s) – płynny obraz
        w.reset(); w.onState(Player.STATE_READY, 0)
        repeat(5) { i -> assertFalse(w.onDropped(10, 1500, 3000L + i * 1500)) }
        // buforowanie (łącze, nie dekoder) – okno wyczyszczone, alarm dopiero po nowych zrzutach w READY
        w.reset(); w.onState(Player.STATE_READY, 0)
        assertFalse(w.onDropped(10, 500, 2700))          // 10 w oknie < 20
        w.onState(Player.STATE_BUFFERING, 2800)
        assertFalse(w.onDropped(30, 500, 2900))          // buforowanie
        w.onState(Player.STATE_READY, 3000)
        assertFalse(w.onDropped(10, 500, 3200))          // okno od nowa: 10
        assertTrue(w.onDropped(10, 500, 3400))
    }

    @Test fun profileSurfaceEncodingAndCompatibilityProfile() {
        val p = EngineProfile(software = true, surface = 1)
        assertEquals(p, EngineProfile.decode(p.encode()))
        assertTrue(p.encode().endsWith(";surf=1"))
        // stare zapisane wiersze bez zmian
        assertEquals("sw=0;ff=0;async=0;fmt=0", EngineProfile().encode())
        assertEquals(0, EngineProfile.decode("sw=1;ff=0;async=0;fmt=0")!!.surface)
        assertTrue(p.label.contains("TextureView"))
        // sama powierzchnia – ten sam odtwarzacz (podmieniany tylko widok)
        assertTrue(Playback.sameEngine(EngineProfile(software = true), EngineProfile(software = true, surface = 1)))
        // tryb zgodności: programowy + TextureView, zachowuje resztę profilu kanału
        assertEquals(EngineProfile(software = true, surface = 1), Playback.compatibilityProfileFor(null, false))
        assertEquals(EngineProfile(software = true, ffmpeg = true, container = 1, surface = 1),
            Playback.compatibilityProfileFor(EngineProfile(ffmpeg = true, container = 1), false))
        assertNull(Playback.compatibilityProfileFor(EngineProfile(software = true, surface = 1), false))
        Prefs.surfaceMode = RenderSurfaceMode.TEXTURE_VIEW.ordinal
        assertNull(Playback.compatibilityProfileFor(null, softwareAlready = true))
    }

    @Test fun frameRateStrategyOffWithoutAfr() {
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, Playback.frameRateStrategy(false))
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, Playback.frameRateStrategy(true))
        assertEquals(5_000L, Playback.VIDEO_JOINING_MS)
        assertTrue(Playback.DROP_NOTIFY_FRAMES in 1..FrameDropWatchdog.MIN_DROPPED)
        // odtwarzacz budowany z nowym rendererem obrazu (bez wyjątku) i dekoderem programowym w profilu
        val p = Playback.buildPlayer(ctx, EngineProfile(software = true, surface = 1)); p.release()
    }

    @Test fun stutteringChannelSwitchesToCompatibilityAndIsRemembered() {
        val ch = Channel("cplus2", "Canal+ Extra 2", "http://127.0.0.1:9/live/cplus2.ts", "G")
        val other = Channel("tvp1", "TVP 1", "http://127.0.0.1:9/live/tvp1.ts", "G")
        var shown: EngineProfile? = null
        Playback.onFrameDropFallback = { p, _ -> shown = p }
        Playback.play(ctx, ch, ch.url); idle()
        val before = Playback.get(ctx)
        assertFalse(Playback.wantsTextureView)
        Playback.simulateFrameDrops(); idle()
        val want = EngineProfile(software = true, surface = 1)
        assertEquals(want, ChannelEngines.profileFor(ch.key))            // „Zapamiętane silniki kanałów”
        assertEquals(want, Playback.profile)
        assertEquals(want, shown)
        assertTrue(Playback.wantsTextureView)
        assertNotSame(before, Playback.get(ctx))                          // dekoder programowy – nowy odtwarzacz
        // już w trybie zgodności – bez kolejnych przełączeń
        val compat = Playback.get(ctx); shown = null
        Playback.simulateFrameDrops(); idle()
        assertSame(compat, Playback.get(ctx)); assertNull(shown)
        // inny kanał – zwykłe ustawienia; powrót na Canal+ Extra 2 – od razu zgodność (także po restarcie aplikacji)
        Playback.play(ctx, other, other.url); idle()
        assertNull(Playback.profile); assertFalse(Playback.wantsTextureView)
        Playback.stop()
        Playback.play(ctx, ch, ch.url); idle()
        assertEquals(want, Playback.profile)
        // wyłączony w Ustawieniach – bez automatycznego przełączania
        Prefs.dropWatchdog = false
        Playback.play(ctx, other, other.url); idle()
        Playback.simulateFrameDrops(); idle()
        assertNull(ChannelEngines.profileFor(other.key))
    }

    @Test fun playerScreenSwapsToTextureViewWithoutRestartingStream() {
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true; Prefs.startAction = 0; Prefs.tutorialShown = true
        val m3u = File(ctx.filesDir, "v720.m3u")
        m3u.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"c2\",Canal+ Extra 2\nhttp://127.0.0.1:9/live/c2.ts\n" +
            "#EXTINF:-1 tvg-id=\"c3\",Canal+ Extra 3\nhttp://127.0.0.1:9/live/c3.ts\n")
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
        val c2 = Repository.channels[0]
        val ctl = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, c2.key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup()
        idle(800)
        val a = ctl.get()
        assertFalse(a.usesTextureView)
        val starts = Playback.starts
        Playback.simulateFrameDrops(); idle(500)
        assertTrue(a.usesTextureView)
        val view = a.findViewById<PlayerView>(R.id.playerView)
        assertTrue(view.videoSurfaceView is android.view.TextureView)
        assertSame(Playback.get(ctx), view.player)                     // obraz podłączony do nowego widoku
        assertEquals(1, a.findViewById<android.view.ViewGroup>(R.id.playerRoot).let { r ->
            (0 until r.childCount).count { r.getChildAt(it) is PlayerView } })
        assertEquals(starts + 1, Playback.starts)                     // jeden restart kanału (nowy dekoder), bez kolejnych
        ctl.pause().stop().destroy()
    }

    // ---- krok 2: strażnik ciągły, tryb powierzchni, odzyskiwanie po błędach, głośność, lista silników ----

    @Test fun stalledPictureWithPlayingSoundEntersCompatibilityAfterThreeSeconds() {
        assertEquals(3_000L, FrameDropWatchdog.FREEZE_COMPAT_MS)
        assertTrue(Playback.WATCHDOG_MS <= 1_000L && FrameDropWatchdog.FREEZE_COMPAT_MS % Playback.WATCHDOG_MS == 0L)
        assertTrue(FrameDropWatchdog.FREEZE_COMPAT_MS < Playback.FREEZE_SURFACE_MS)   // przed dawnym odświeżeniem powierzchni
        val ch = Channel("cplus3", "Canal+ Extra 3", "http://127.0.0.1:9/live/cplus3.ts", "G")
        Playback.play(ctx, ch, ch.url); idle()
        assertFalse(Playback.simulateStalledFrames(2_000)); assertNull(ChannelEngines.profileFor(ch.key))
        assertTrue(Playback.simulateStalledFrames(3_000)); idle()
        assertEquals(EngineProfile(software = true, surface = 1), ChannelEngines.profileFor(ch.key))
        assertFalse(Playback.simulateStalledFrames(10_000))          // już tryb zgodności – dalej dawne kroki strażnika
        // podczas długiego oglądania (po kilku minutach) zrzucanie też przełącza – okno przesuwa się
        val w = FrameDropWatchdog(); w.onState(Player.STATE_READY, 0)
        assertFalse(w.onDropped(10, 2_000, 600_000))
        assertTrue(w.onDropped(10, 500, 600_400))
    }

    @Test fun renderSurfaceModeAutoSurfaceAndTexture() {
        assertEquals(listOf("AUTO", "SURFACE_VIEW", "TEXTURE_VIEW"), RenderSurfaceMode.entries.map { it.name })
        assertEquals(RenderSurfaceMode.AUTO, RenderSurfaceMode.current)                // domyślnie
        assertFalse(RenderSurfaceMode.AUTO.texture(false)); assertTrue(RenderSurfaceMode.AUTO.texture(true))
        assertFalse(RenderSurfaceMode.SURFACE_VIEW.texture(true)); assertTrue(RenderSurfaceMode.TEXTURE_VIEW.texture(false))
        // AUTO: zwykły kanał – SurfaceView, kanał zapisany jako wymagający zgodności – TextureView
        ChannelEngines.save("tex", "http://127.0.0.1:9/live/tex.ts", EngineProfile(software = true, surface = 1))
        val plain = Channel("plain", "Zwykły", "http://127.0.0.1:9/live/plain.ts", "G")
        val tex = Channel("tex", "Przeplot", "http://127.0.0.1:9/live/tex.ts", "G")
        Playback.play(ctx, plain, plain.url); idle(); assertFalse(Playback.wantsTextureView)
        Playback.play(ctx, tex, tex.url); idle(); assertTrue(Playback.wantsTextureView)
        Prefs.surfaceMode = RenderSurfaceMode.SURFACE_VIEW.ordinal; assertFalse(Playback.wantsTextureView)
        Prefs.surfaceMode = RenderSurfaceMode.TEXTURE_VIEW.ordinal
        Playback.play(ctx, plain, plain.url); idle(); assertTrue(Playback.wantsTextureView)
        // dawny przełącznik „Obraz na TextureView” (tex_view) → TEXTURE_VIEW
        val sp = ctx.getSharedPreferences("iptv", android.content.Context.MODE_PRIVATE)
        sp.edit().remove("surface_mode").putBoolean("tex_view", true).commit()
        assertEquals(RenderSurfaceMode.TEXTURE_VIEW, RenderSurfaceMode.current)
        sp.edit().remove("tex_view").commit()
        assertEquals(RenderSurfaceMode.AUTO, RenderSurfaceMode.current)
    }

    @Test fun behindLiveWindowRecoversSilentlyAndDecoderErrorFallsBackToSoftware() {
        val ch = Channel("dec", "Kanał HD", "http://127.0.0.1:9/live/dec.ts", "G")
        var fatal = 0; var why: String? = null
        Playback.onFatal = { fatal++ }
        Playback.onFrameDropFallback = { _, w -> why = w }
        try {
            Playback.play(ctx, ch, ch.url); idle()
            // poza oknem na żywo – powrót na bieżące miejsce: bez błędu, bez ponowień
            Playback.simulateError(androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW); idle()
            assertEquals(0, fatal); assertFalse(Playback.retryPending); assertNull(why)
            // błąd dekodera dźwięku – zwykła obsługa (dekoder programowy obrazu nie pomoże)
            val audioFormat = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_E_AC3).build()
            Playback.simulateError(androidx.media3.exoplayer.ExoPlaybackException.createForRenderer(RuntimeException("eac3"), "audio", 1, audioFormat,
                C.FORMAT_HANDLED, false, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)); idle()
            assertNull(ChannelEngines.profileFor(ch.key)); assertNull(why)
            ChannelEngineOptimizer.cancel(); Playback.play(ctx, ch, ch.url, force = true); idle()
            // błąd dekodera obrazu – od razu programowy dla kanału, zapamiętany, kanał od nowa w tle (bez komunikatu błędu)
            val starts = Playback.starts
            Playback.simulateError(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED); idle()
            assertEquals(EngineProfile(software = true), ChannelEngines.profileFor(ch.key))
            assertEquals(EngineProfile(software = true), Playback.profile)
            assertEquals(Playback.FALLBACK_DECODER, why); assertEquals(0, fatal)
            assertEquals(starts + 1, Playback.starts)
            assertFalse(ChannelEngineOptimizer.running)
        } finally { Playback.onFatal = null; ChannelEngineOptimizer.cancel() }
    }

    private class FakeEffect : ChannelLoudness.Effect {
        var gainMb = 0; var on = false; var released = false
        override fun setGain(mB: Int) { gainMb = mB }
        override fun setEnabled(on: Boolean) { this.on = on }
        override fun release() { released = true }
    }

    @Test fun loudnessEnhancerOnPlayerSessionIsOptionalAndSafe() {
        val made = mutableListOf<Pair<Int, FakeEffect>>()
        ChannelLoudness.factory = { s -> FakeEffect().also { made += s to it } }
        assertFalse(Prefs.loudnessLeveling)                            // domyślnie wyłączone
        ChannelLoudness.attach(7); assertFalse(ChannelLoudness.active); assertTrue(made.isEmpty())
        Prefs.loudnessLeveling = true
        ChannelLoudness.attach(7)
        assertTrue(ChannelLoudness.active); assertEquals(7, made.single().first)
        assertEquals(300, made.single().second.gainMb); assertTrue(made.single().second.on)
        ChannelLoudness.attach(7); assertEquals(1, made.size)        // ta sama sesja – bez nowego efektu
        ChannelLoudness.attach(9); assertTrue(made[0].second.released); assertEquals(9, made[1].first)
        ChannelLoudness.attach(C.AUDIO_SESSION_ID_UNSET); assertFalse(ChannelLoudness.active)
        // urządzenie bez efektów na sesji (np. tunelowanej) – bez wyjątku, odtwarzanie bez zmian
        ChannelLoudness.factory = { throw UnsupportedOperationException("tunneled") }
        ChannelLoudness.attach(11); assertFalse(ChannelLoudness.active)
        // odtwarzacz Live TV podpina efekt do swojej sesji i zwalnia go razem z odtwarzaczem
        made.clear(); ChannelLoudness.factory = { s -> FakeEffect().also { made += s to it } }
        val ch = Channel("loud", "AC3", "http://127.0.0.1:9/live/loud.ts", "G")
        Playback.play(ctx, ch, ch.url); idle()
        val session = Playback.get(ctx).audioSessionId
        if (session > 0) assertTrue(ChannelLoudness.active && made.last().first == session)
        Playback.stop(); assertFalse(ChannelLoudness.active)
        Prefs.loudnessLeveling = false; Playback.applyLoudness(); assertFalse(ChannelLoudness.active)
    }

    @Test fun rememberedEnginesListResetsOneChannelOrAll() {
        Prefs.batteryAsked = true; Prefs.permissionsAsked = true
        ChannelEngines.save("a", "http://127.0.0.1:9/a.ts", EngineProfile(software = true, surface = 1))
        Thread.sleep(2)
        ChannelEngines.save("b", "http://127.0.0.1:9/b.ts", EngineProfile(ffmpeg = true))
        ChannelEngines.markFailed("c", "http://127.0.0.1:9/c.ts")
        assertEquals(listOf("b", "a", "c"), ChannelEngines.entries().map { it.channelKey })   // dobrane (najnowsze) → niedziałające
        val st = Robolectric.buildActivity(pl.iptv.tv.ui.SettingsActivity::class.java, Intent().putExtra("tab", 1)).setup(); idle(800)
        val d = st.get().showChannelEngines(); idle()
        fun cards() = mutableListOf<android.view.View>().also { out ->
            fun walk(v: android.view.View) { if (v.isFocusable && v.isClickable && v.contentDescription != null) out += v; (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).forEach { walk(g.getChildAt(it)) } } }
            walk(d.window!!.decorView)
        }
        val labels = cards().map { it.contentDescription.toString() }
        assertEquals("Wyczyść całą pamięć doboru", labels.first())
        assertTrue(labels.containsAll(listOf("a", "b", "c")))
        // poziomych linii (separatorów) brak – karty jako osobne elementy z odstępem
        assertTrue(cards().none { it is android.widget.ListView })
        // reset jednego kanału
        cards().first { it.contentDescription == "a" }.performClick(); idle()
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertNull(ChannelEngines.get("a")); assertNotNull(ChannelEngines.get("b")); assertNotNull(ChannelEngines.get("c"))
        // wyczyszczenie całej pamięci
        val d2 = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(d2.isShowing)
        fun cards2() = mutableListOf<android.view.View>().also { out ->
            fun walk(v: android.view.View) { if (v.isFocusable && v.isClickable && v.contentDescription != null) out += v; (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).forEach { walk(g.getChildAt(it)) } } }
            walk(d2.window!!.decorView)
        }
        assertFalse(cards2().any { it.contentDescription == "a" })
        cards2().first().performClick(); idle()
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(0, ChannelEngines.count)
        st.pause().stop().destroy()
    }
}

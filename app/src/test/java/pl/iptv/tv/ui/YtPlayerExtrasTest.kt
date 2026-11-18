package pl.iptv.tv.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.YtCandidate
import pl.iptv.tv.data.YtChapter
import pl.iptv.tv.data.YtChapters
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreams
import java.time.Duration

/** Odtwarzacz STube: rozdziały (opis / YouTube, oś czasu, ▲▼), prędkość, korektor dźwięku (presety, pasma pilotem). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class YtPlayerExtrasTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(60) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var server: MockWebServer

    /** Atrapa korektora urządzenia: 5 pasm jak w Androidzie, zakres ±15 dB. */
    class FakeEq : YtEqSession.Device {
        val levels = IntArray(5)
        override val bands = 5
        override val range = -1500..1500
        override fun centerHz(band: Int) = listOf(60, 230, 910, 3600, 14000)[band]
        override fun level(band: Int) = levels[band]
        override fun setLevel(band: Int, mb: Int) { levels[band] = mb }
        override var enabled = false
        var released = false
        override fun release() { released = true }
    }
    private var fake = FakeEq()

    @Before fun setup() {
        server = MockWebServer().apply { dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path!!.startsWith("/sb/")) MockResponse().setBody("[]") else MockResponse().setResponseCode(404)
        } }
        server.start()
        SponsorBlock.apiBase = server.url("/sb").toString().trimEnd('/')
        Prefs.sbEnabled = true; Prefs.stubeInternal = true; Prefs.stubeResume = false
        Prefs.ytSpeedPct = 100; Prefs.ytEqPreset = YtEq.FLAT; Prefs.ytEqCustom = emptyList()
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        fake = FakeEq()
        YtEqSession.factory = { YtEqSession(fake) }
    }

    @After fun tearDown() { YtStreams.resolverOverride = null; YtEngine.releasePlayer(); YtEngine.listeners.clear(); YtEqSession.factory = null; server.shutdown(); Prefs.ytSpeedPct = 100; Prefs.ytEqPreset = 0 }

    // ---------------- rozdziały ----------------

    @Test fun chaptersFromDescription() {
        assertEquals(83_000L, YtChapters.parseTime("1:23")); assertEquals(3_723_000L, YtChapters.parseTime("1:02:03"))
        assertNull(YtChapters.parseTime("1:75")); assertNull(YtChapters.parseTime("abc"))
        val desc = "Film o kotach<br>Rozdziały:<br>0:00 Wstęp<br>1:30 – Koty domowe<br>(05:10) Koty dzikie<br>Link: https://x.pl/a?t=1:00:00 bez<br>12:00 | Podsumowanie &amp; pytania"
        val ch = YtChapters.fromDescription(desc, 15 * 60_000L)
        assertEquals(listOf(0L, 90_000L, 310_000L, 720_000L), ch.map { it.startMs })
        assertEquals(listOf("Wstęp", "Koty domowe", "Koty dzikie", "Podsumowanie & pytania"), ch.map { it.title })
        // reguły YouTube: od 0:00, ≥ 3 rozdziały, rosnąco, ≥ 10 s
        assertTrue(YtChapters.fromDescription("0:05 A\n1:00 B\n2:00 C").isEmpty())
        assertTrue(YtChapters.fromDescription("0:00 A\n1:00 B").isEmpty())
        assertTrue(YtChapters.fromDescription("0:00 A\n2:00 B\n1:00 C").isEmpty())
        assertTrue(YtChapters.fromDescription("0:00 A\n0:05 B\n1:00 C").isEmpty())
        // znaczniki poza długością filmu odrzucone
        assertEquals(3, YtChapters.fromDescription("0:00 A\n1:00 B\n2:00 C\n59:00 D", 5 * 60_000L).size)
        // segmenty YouTube (już zweryfikowane) – wystarczą 2
        assertEquals(listOf(YtChapter(0, "Start"), YtChapter(40_000, "Rozdział")), YtChapters.fromSegments(listOf(0 to "Start", 40 to " ")))
    }

    @Test fun chapterNavigationTargets() {
        val c = listOf(YtChapter(0, "A"), YtChapter(60_000, "B"), YtChapter(120_000, "C"))
        assertEquals(1, YtChapters.indexAt(c, 61_000)); assertEquals(-1, YtChapters.indexAt(emptyList(), 5))
        assertEquals(120_000L, YtChapters.nextStart(c, 61_000)); assertNull(YtChapters.nextStart(c, 130_000))
        // ▲ w trakcie rozdziału – jego początek, tuż po początku – poprzedni
        assertEquals(60_000L, YtChapters.prevStart(c, 90_000))
        assertEquals(0L, YtChapters.prevStart(c, 61_000))
        assertEquals(0L, YtChapters.prevStart(c, 1_000))
    }

    // ---------------- korektor ----------------

    @Test fun equalizerPresets() {
        val centers = listOf(60, 230, 910, 3600, 14000)
        assertEquals(listOf(0, 0, 0, 0, 0), YtEq.levels(YtEq.FLAT, centers, -1500, 1500))
        assertEquals(listOf(-300, -100, 200, 400, 0), YtEq.levels(YtEq.MOVIE, centers, -1500, 1500))   // głosy wyraźniej
        assertEquals(listOf(700, 400, 0, 0, 0), YtEq.levels(YtEq.BASS, centers, -1500, 1500))
        // zakres urządzenia
        assertEquals(500, YtEq.levels(YtEq.BASS, centers, -500, 500)[0])
        assertEquals(listOf(100, 200, 0, 0, 0), YtEq.levels(YtEq.CUSTOM, centers, -1500, 1500, custom = listOf(100, 200)))
        assertEquals("60 Hz", YtEq.hzLabel(60)); assertEquals("3,6 kHz", YtEq.hzLabel(3600)); assertEquals("14 kHz", YtEq.hzLabel(14000))
        assertEquals("+3 dB", YtEq.dbLabel(300)); assertEquals("−2 dB", YtEq.dbLabel(-200)); assertEquals("0 dB", YtEq.dbLabel(0))
        // sesja: preset → poziomy pasm, „Płaski” wyłącza efekt
        val s = YtEqSession(fake)
        s.apply(YtEq.BASS); assertTrue(fake.enabled); assertEquals(700, fake.levels[0])
        s.apply(YtEq.FLAT); assertFalse(fake.enabled); assertEquals(0, fake.levels[0])
        // ręczna zmiana pasma = „Własny” i zapis
        s.setBand(3, 600)
        assertEquals(YtEq.CUSTOM, Prefs.ytEqPreset); assertEquals(listOf(0, 0, 0, 600, 0), Prefs.ytEqCustom); assertTrue(fake.enabled)
        // brak efektu na urządzeniu – bez awarii
        val none = YtEqSession(null as YtEqSession.Device?)
        assertFalse(none.available); none.apply(YtEq.BASS); assertTrue(none.setBand(0, 100).isEmpty())
    }

    // ---------------- ekran odtwarzacza ----------------

    private fun open(chapters: List<YtChapter>): YtPlayerActivity {
        YtStreams.resolverOverride = { YtPlayable.Muxed("Film", 300, YtCandidate(server.url("/v.mp4").toString(), 360), chapters) }
        val a = Robolectric.buildActivity(YtPlayerActivity::class.java, YtPlayerActivity.intent(ctx, "abcdefghijk", "Film")).setup().get()
        waitFor { a.player != null && YtEngine.playable != null }
        return a
    }

    @Test fun chaptersSpeedAndEqualizerInPlayer() {
        Prefs.ytSpeedPct = 125; Prefs.ytEqPreset = YtEq.MOVIE
        val ch = listOf(YtChapter(0, "Wstęp"), YtChapter(60_000, "Środek"), YtChapter(120_000, "Koniec"))
        val a = open(ch)
        val p = a.player!!
        // zapamiętana prędkość i preset korektora od startu
        assertEquals(1.25f, p.playbackParameters.speed, 0.001f)
        assertNotNull(a.eq); assertTrue(fake.enabled); assertEquals(400, fake.levels[3])
        assertEquals(ch, a.chapters)
        // ▼ przy ukrytym pasku – następny rozdział, ▲ – początek bieżącego / poprzedni
        fun pv(v: View): androidx.media3.ui.PlayerView? = v as? androidx.media3.ui.PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { pv(g.getChildAt(it)) } }
        pv(a.window.decorView)!!.apply { controllerAutoShow = false; hideController() }; idle()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(60_000L, p.currentPosition); assertEquals(1, a.chapterIndex)
        a.jumpChapter(next = true); assertEquals(120_000L, p.currentPosition)
        assertFalse(a.jumpChapter(next = true))                      // ostatni rozdział
        a.jumpChapter(next = false); assertEquals(60_000L, p.currentPosition)
        // prędkość z menu: zapis i zmiana w odtwarzaczu
        a.setSpeed(200); assertEquals(2f, p.playbackParameters.speed, 0.001f); assertEquals(200, Prefs.ytSpeedPct)
        assertEquals(listOf(50, 100, 125, 150, 200), a.SPEEDS)
        // pasek opcji: przyciski fokusowalne, rozdziały widoczne (film ma rozdziały)
        val texts = mutableListOf<TextView>()
        fun collect(v: View) { if (v is TextView && v.isFocusable) texts += v; if (v is ViewGroup) (0 until v.childCount).forEach { collect(v.getChildAt(it)) } }
        collect(a.window.decorView)
        val labels = texts.map { it.text.toString() }
        // 7.0: podpisy bez emoji – ikony rysowane ([PlayerIcon])
        assertTrue(labels.toString(), labels.contains("2.0×") && labels.contains("Film / Dialogi") && labels.contains("Rozdziały"))
        assertEquals(View.VISIBLE, texts.first { it.text == "Rozdziały" }.visibility)
    }

    @Test fun equalizerPanelWithDpad() {
        val a = open(emptyList())
        val d = a.eqPanel()!!
        idle()
        assertTrue(d.isShowing)
        assertEquals("Płaski", d.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        val root = d.findViewById<View>(android.R.id.content)!!
        val bars = mutableListOf<SeekBar>(); val chips = mutableListOf<TextView>()
        fun walk(v: View) { if (v is SeekBar) bars += v else if (v is TextView && v !is android.widget.Button && v.isFocusable && v.text.toString().removePrefix("✔ ") in YtEq.PRESETS) chips += v
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }
        walk(root)
        assertEquals(5, bars.size); assertEquals(YtEq.PRESETS.size, chips.size)
        assertTrue(bars.all { it.isFocusable } && chips.all { it.isFocusable })
        // fokus startowy na wybranym presecie
        assertTrue(chips[YtEq.FLAT].isFocused); assertEquals("✔ Płaski", chips[0].text.toString())
        // preset Bass Boost
        chips[YtEq.BASS].performClick(); idle()
        assertEquals(YtEq.BASS, Prefs.ytEqPreset); assertEquals(700, fake.levels[0]); assertEquals("✔ Bass Boost", chips[YtEq.BASS].text.toString())
        assertEquals((700 + 1500) / 100, bars[0].progress)
        // ◄► na suwaku pasma: ±1 dB → „Własny”
        bars[4].requestFocus()
        bars[4].dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); idle()
        assertEquals(100, fake.levels[4]); assertEquals(YtEq.CUSTOM, Prefs.ytEqPreset)
        assertEquals("✔ Własny", chips[YtEq.CUSTOM].text.toString())
        // „Płaski” – wyłączenie korektora
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        assertEquals(YtEq.FLAT, Prefs.ytEqPreset); assertFalse(fake.enabled)
    }

    @Test fun noEqualizerOnDeviceShowsMessage() {
        YtEqSession.factory = { YtEqSession(null as YtEqSession.Device?) }
        val a = open(emptyList())
        assertNull(a.eqPanel())
        assertEquals("Korektor dźwięku niedostępny na tym urządzeniu", ShadowToast.getTextOfLatestToast())
    }

    @Test fun eqReleasedWithPlayer() {
        val a = open(emptyList())
        // korektor należy do silnika w usłudze: zamknięcie ekranu go nie zwalnia, koniec usługi – tak
        assertNotNull(a.eq); a.finish(); idle(); assertFalse(fake.released)
        YtEngine.releasePlayer()
        assertTrue(fake.released); assertNull(YtEngine.eq); assertNull(YtEngine.player)
    }
}

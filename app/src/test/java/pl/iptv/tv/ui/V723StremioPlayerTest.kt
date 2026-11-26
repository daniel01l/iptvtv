package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import pl.iptv.tv.data.StremioStream
import java.time.Duration

/**
 * 7.23 – odtwarzacz Stremio na telefonie: ⏪ ⏯ ⏩ na środku w pionie, tytuł (z odcinkiem) w lewym górnym rogu, podwójne
 * stuknięcie po bokach (pion i poziom), dolny pasek Źródła / Odcinki|Części / Audio / Napisy / Obraz / Obróć, okno „Napisy”
 * (przesunięcie, kolor, rozmiar, położenie) i „Obraz” (profil, proporcje, jasność, kontrast, nasycenie, Resetuj) – na żywo,
 * bez nowego odtwarzacza.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723StremioPlayerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.orientation = 2
        PictureAdjust.save(PictureAdjust.DEFAULT); Prefs.stremioAspect = 0; Prefs.stremioAspectRatio = 0; Prefs.stremioSubPos = 1; Prefs.stremioSubColor = 0; Prefs.stremioSubSize = 1
    }
    @After fun tearDown() {
        Prefs.orientation = 0; PictureAdjust.save(PictureAdjust.DEFAULT); Prefs.stremioAspect = 0; Prefs.stremioAspectRatio = 0
        Prefs.stremioSubPos = 1; Prefs.stremioSubColor = 0; Prefs.stremioSubSize = 1; Playback.stop(); TvResolution.resetForTest()
    }

    private fun player(type: String = "series", label: String = "S1E2 · Pilot") =
        Robolectric.buildActivity(StremioPlayerActivity::class.java, StremioPlayerActivity.intent(ctx, type, "tt1", "Serial testowy", "", if (type == "series") "tt1:1:2" else "tt1", label,
            StremioStream("Wtyczka", "http://127.0.0.1:9", url = "http://127.0.0.1:9/x.mp4"))).setup().also { idle(400) }

    private fun layout(a: android.app.Activity) {
        val d = a.window.decorView; val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); d.layout(0, 0, w, h)
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun portraitTransportCenteredTitleTopLeftAndBarOrder() {
        val c = player(); val a = c.get()
        // pasek: dokładnie sześć pozycji w tej kolejności, każda ≥ 48 dp, wszystkie w szerokości ekranu
        val bar = a.portraitBar!!
        assertEquals(listOf("sources", "episodes", "audio", "tracks", "aspect", "rotate").map { PortraitPlayerUi.ICON_TAG + it }, (0 until bar.childCount).map { bar.getChildAt(it).tag })
        assertEquals(listOf("Źródła", "Odcinki", "Audio", "Napisy", "Obraz", "Obróć"), (0 until bar.childCount).map { bar.getChildAt(it).contentDescription.toString() })
        layout(a)
        val w = a.resources.displayMetrics.widthPixels
        for (i in 0 until bar.childCount) bar.getChildAt(i).let { v -> assertTrue(v.width >= Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP)); assertTrue(v.left >= 0 && v.right <= w) }
        // pełny pasek: ⏪ ⏯ ⏩ na środku ekranu, tytuł serialu z odcinkiem w lewym górnym rogu
        a.setBar(StremioPlayerActivity.Bar.FULL); idle(); layout(a)
        assertTrue(a.transportCentered)
        val center = all(a.window.decorView).first { it.tag == "stremio_center_transport" }
        assertEquals(View.VISIBLE, center.visibility)
        assertEquals(android.view.Gravity.CENTER, (center.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
        assertSame(center, a.playBtn.parent)
        val title = all(a.window.decorView).first { it.tag == "stremio_title" } as TextView
        assertEquals(View.VISIBLE, title.visibility)
        assertEquals("Serial testowy  ·  S1E2 · Pilot", title.text.toString())
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.START, (title.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
        val hg = title.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        assertTrue(hg == android.view.Gravity.START || hg == android.view.Gravity.LEFT)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun movieShowsPartsIconOnlyWithOtherParts() {
        val c = player(type = "movie", label = ""); val a = c.get()
        val parts = a.portraitBar!!.findViewWithTag<View>(PortraitPlayerUi.ICON_TAG + "episodes")
        assertEquals("Części", parts.contentDescription)
        assertEquals(View.GONE, parts.visibility)                      // pojedynczy film – bez ikony
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun subtitlesSheetOffsetColorSizePosition() {
        val c = player(); val a = c.get()
        all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "tracks" }.performClick(); idle()
        val s = a.lastSheet!!
        assertTrue(texts(s.root).containsAll(listOf(StremioPlayerActivity.SUB_DELAY, StremioPlayerActivity.SUB_COLOR, StremioPlayerActivity.SUB_SIZE, StremioPlayerActivity.SUB_POSITION)))
        // przesunięcie – suwak
        val slider = all(s.root).filterIsInstance<android.widget.SeekBar>().first()
        slider.progress = slider.max; shadowOf(slider).onSeekBarChangeListener.onProgressChanged(slider, slider.max, true); idle()
        assertEquals(PortraitPlayerUi.DELAY_RANGE_MS, a.subDelayMs)
        // kolor, rozmiar, położenie – kafelki
        fun tile(label: String) = all(s.root).first { it is TextView && it.text.toString() == label && (it.tag as? String)?.contains("sheet_tile") == true }
        tile("Żółty").performClick(); idle(); assertEquals(1, Prefs.stremioSubColor)
        tile("Duży").performClick(); idle(); assertEquals(2, Prefs.stremioSubSize)
        tile("Wysoko").performClick(); idle(); assertEquals(3, Prefs.stremioSubPos)
        s.dismiss(); a.changeDelay(-a.subDelayMs); Prefs.stremioSubDelay = 0
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun pictureSheetAppliesLiveWithoutRestart() {
        val c = player(); val a = c.get()
        val exo = a.player
        // telefon: obraz na TextureView – korekta na żywo
        assertTrue(a.playerView.videoSurfaceView is android.view.TextureView)
        all(a.portraitBar!!).first { it.tag == PortraitPlayerUi.ICON_TAG + "aspect" }.performClick(); idle()
        val s = a.lastSheet!!
        assertTrue(texts(s.root).containsAll(listOf(StremioPlayerActivity.PIC_PROFILE, StremioPlayerActivity.PIC_ASPECT, StremioPlayerActivity.PIC_BRIGHTNESS,
            StremioPlayerActivity.PIC_CONTRAST, StremioPlayerActivity.PIC_SATURATION, StremioPlayerActivity.PIC_RESET)))
        assertTrue(texts(s.root).containsAll(listOf("Standardowy", "Kino", "Sport", "Żywy", "Dopasuj", "Wypełnij / Przytnij", "16:9", "4:3")))
        fun tile(label: String) = all(s.root).first { it is TextView && it.text.toString() == label && (it.tag as? String)?.contains("sheet_tile") == true }
        tile("Kino").performClick(); idle()
        assertEquals(PictureAdjust.Profile.CINEMA, PictureAdjust.current().profile)
        assertEquals(PictureAdjust.Profile.CINEMA, PictureAdjust.applied!!.profile)
        // suwaki: jasność, kontrast, nasycenie (kolejność jak w oknie; pierwszy suwak okna to jasność)
        val sliders = all(s.root).filterIsInstance<android.widget.SeekBar>()
        assertEquals(3, sliders.size)
        fun drag(sb: android.widget.SeekBar, p: Int) { sb.progress = p; shadowOf(sb).onSeekBarChangeListener.onProgressChanged(sb, p, true); idle() }
        drag(sliders[0], sliders[0].max)                                          // jasność +50
        assertEquals(PictureAdjust.BRIGHTNESS_MAX, PictureAdjust.applied!!.brightness)
        drag(all(s.root).filterIsInstance<android.widget.SeekBar>()[1], 0)       // kontrast 50 %
        assertEquals(PictureAdjust.CONTRAST_MIN, PictureAdjust.applied!!.contrast)
        drag(all(s.root).filterIsInstance<android.widget.SeekBar>()[2], 150)     // nasycenie 150 %
        assertEquals(150, PictureAdjust.applied!!.saturation)
        // proporcje: Wypełnij / Przytnij, 16:9 – od razu, zapamiętane
        tile("Wypełnij / Przytnij").performClick(); idle()
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, a.playerView.resizeMode)
        all(a.lastSheet!!.root).first { it is TextView && it.text.toString() == "16:9" && (it.tag as? String)?.contains("sheet_tile") == true }.performClick(); idle()
        assertEquals(1, Prefs.stremioAspectRatio); assertEquals(3, a.pictureAspectIndex())
        // ten sam odtwarzacz – bez restartu ani ponownego buforowania
        assertSame(exo, a.player)
        // Resetuj – wartości domyślne, filtr zdjęty, Dopasuj
        all(s.root).first { it is TextView && it.text.toString() == StremioPlayerActivity.PIC_RESET }.let { v -> var p: View? = v; while (p != null && !p.isClickable) p = p.parent as? View; p!!.performClick() }; idle()
        assertTrue(PictureAdjust.current().isDefault); assertTrue(PictureAdjust.applied!!.isDefault)
        assertEquals(0, Prefs.stremioAspectRatio); assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, a.playerView.resizeMode)
        assertSame(exo, a.player)
        s.dismiss(); c.pause().stop().destroy()
    }

    @Test fun pictureMatrixIdentityAndProfiles() {
        val id = PictureAdjust.matrix(PictureAdjust.DEFAULT).array
        assertArrayEquals(android.graphics.ColorMatrix().array, id, 1e-4f)
        val vivid = PictureAdjust.matrix(PictureAdjust.Values(PictureAdjust.Profile.VIVID)).array
        assertFalse(vivid.contentEquals(id))
        // jasność +50 – dodatnie przesunięcie kanałów
        val bright = PictureAdjust.matrix(PictureAdjust.Values(brightness = 50)).array
        assertTrue(bright[4] > 0f && bright[9] > 0f && bright[14] > 0f)
    }

    private fun doubleTapCheck() {
        val c = player(); val a = c.get()
        layout(a)
        val pv = a.playerView
        val l = IntArray(2); pv.getLocationInWindow(l)
        val y = l[1] + pv.height / 2f
        a.player!!.seekTo(60_000); idle()
        assertFalse(a.doubleTapAt(l[0] + pv.width / 2f, y))                 // środek – bez przewijania
        assertTrue(a.doubleTapAt(l[0] + pv.width * 0.92f, y)); idle()
        assertEquals(70_000, a.player!!.currentPosition)
        assertTrue(a.doubleTapAt(l[0] + pv.width * 0.08f, y)); idle()
        assertEquals(60_000, a.player!!.currentPosition)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun doubleTapSidesSeekInPortrait() = doubleTapCheck()

    @Test @Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
    fun doubleTapSidesSeekInLandscape() = doubleTapCheck()
}

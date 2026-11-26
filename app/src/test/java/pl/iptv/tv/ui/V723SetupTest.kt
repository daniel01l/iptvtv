package pl.iptv.tv.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import java.time.Duration

/**
 * 7.23 – pierwsze uruchomienie na telefonie: orientacja automatyczna od początku (zgoda, aktywacja, kreator), TV bez zmian;
 * kreator w pionie i poziomie – przyciski równej szerokości w wąskim układzie, obrót bez tworzenia ekranu od nowa (krok, wpisy,
 * brak ponownej budowy), odtworzenie ekranu przywraca wpisy, powrót do poprzedniego kroku.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723SetupTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun click(a: android.app.Activity, label: String) {
        var v: View? = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick(); idle()
    }

    @Before fun setup() {
        // pierwsze uruchomienie: orientacja jeszcze nie wybrana
        ctx.getSharedPreferences("iptv", 0).edit().remove("orientation").commit()
        Prefs.permissionsAsked = true; Prefs.playlistsJson = "[]"
    }
    @After fun tearDown() { Prefs.orientation = 0; TvResolution.resetForTest() }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun phoneStartsInAutomaticOrientation() {
        assertFalse(Prefs.has("orientation"))
        assertEquals(OrientationPolicy.AUTO, Prefs.orientation)
        val s = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, s.get().requestedOrientation)
        val l = Robolectric.buildActivity(LockActivity::class.java).setup(); idle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, l.get().requestedOrientation)
        l.pause().stop().destroy(); s.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvStaysLandscape() {
        val s = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        // TV: jak dotąd – poziomo (OrientationPolicy z flagą TV)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.target(s.get(), tv = true))
        assertFalse(s.get().narrowLayout)
        s.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun wizardSurvivesRotationAndKeepsInput() {
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        val a = c.get()
        assertTrue(a.narrowLayout)
        // wąski ekran: trzy przyciski nawigacji równej szerokości (bez ucinania), do 2 linii
        val nav = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Dalej ►" }.parent as LinearLayout
        assertEquals(3, nav.childCount)
        for (i in 0 until nav.childCount) { val lp = nav.getChildAt(i).layoutParams as LinearLayout.LayoutParams; assertEquals(1f, lp.weight); assertEquals(0, lp.width) }
        // krok 2 (lista): wpis adresu
        click(a, "Dalej ►")
        val url = all(a.window.decorView).filterIsInstance<EditText>().first()
        url.setText("http://example.invalid/lista.m3u")
        val builds = a.builds
        // obrót do poziomu: ten sam ekran, ten sam krok i wpis, bez przebudowy kroku; układ szeroki
        RuntimeEnvironment.setQualifiers("w740dp-h360dp-land-xhdpi")
        c.configurationChange(Configuration(a.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE; screenWidthDp = 740; screenHeightDp = 360 }); idle()
        assertSame(a, c.get())
        assertFalse(a.narrowLayout)
        assertEquals(builds, a.builds)
        assertEquals("http://example.invalid/lista.m3u", all(a.window.decorView).filterIsInstance<EditText>().first().text.toString())
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("krok 2/") })
        // powrót do poprzedniego kroku
        click(a, "◄ Wstecz")
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("krok 1/") })
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun recreatedWizardRestoresStepAndTypedFields() {
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        click(c.get(), "Dalej ►")
        val fields = all(c.get().window.decorView).filterIsInstance<EditText>()
        fields[0].setText("http://example.invalid/a.m3u"); fields[1].setText("Moja lista")
        c.recreate(); idle()
        val again = all(c.get().window.decorView).filterIsInstance<EditText>()
        assertTrue(all(c.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("krok 2/") })
        assertEquals("http://example.invalid/a.m3u", again[0].text.toString())
        assertEquals("Moja lista", again[1].text.toString())
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun emptyPlaylistShowsValidationWithoutAdvancing() {
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        click(c.get(), "Dalej ►")
        click(c.get(), "Wczytaj listę")
        // puste pole – komunikat, bez zmiany kroku
        assertTrue(all(c.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Podaj adres listy." })
        assertTrue(all(c.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("krok 2/") })
        c.pause().stop().destroy()
    }
}

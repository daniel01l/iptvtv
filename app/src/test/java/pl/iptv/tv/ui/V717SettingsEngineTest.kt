package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs
import java.time.Duration

/** (7.17) Ustawienia silnika: profile wydajności, bufor dla każdego urządzenia ze statusem, okno wyboru „Obsidian Stream”. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V717SettingsEngineTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @After fun reset() { EngineProfiles.apply(EngineProfiles.Profile.AUTO); Playback.stop() }

    @Test fun profilesSetParametersAndDetectCustom() {
        EngineProfiles.apply(EngineProfiles.Profile.FAST)
        assertEquals(2, Prefs.bufMinS); assertTrue(Prefs.cleanDecoderOnZap); assertEquals(200, Prefs.zapDelayMs); assertFalse(Prefs.lowPerf)
        assertEquals(EngineProfiles.Profile.FAST, EngineProfiles.active())
        EngineProfiles.apply(EngineProfiles.Profile.STABLE)
        assertEquals(6, Prefs.bufMinS); assertEquals(1, Prefs.asyncDecoder); assertTrue(Prefs.decoderFallback)
        EngineProfiles.apply(EngineProfiles.Profile.LOW)
        assertTrue(Prefs.lowPerf); assertEquals(1080, Prefs.lowMaxHeight); assertTrue(Prefs.cleanDecoderOnZap)
        assertEquals(EngineProfiles.Profile.LOW, EngineProfiles.active())
        // ręczna zmiana parametru – „Własny (Zmodyfikowany)”
        Prefs.zapDelayMs = 600
        assertNull(EngineProfiles.active()); assertEquals("Własny (Zmodyfikowany)", EngineProfiles.activeLabel())
        // status bufora
        assertEquals("2 s (tryb szybki)", EngineProfiles.bufferLabel(2, 0))
        assertEquals("6 s (stabilny)", EngineProfiles.bufferLabel(6, 0))
        assertEquals("2 s (tryb szybki) · +1,5 s ochrony", EngineProfiles.bufferLabel(2, 1_500))
        // bufor 2 s dla każdego urządzenia – bezpieczne proporcje (odtwarzacz się buduje)
        EngineProfiles.apply(EngineProfiles.Profile.FAST)
        Playback.buildPlayer(ctx, null).release()
        assertEquals(1_500, Playback.liveLoad!!.baseRebufferMs)
    }

    @Test fun choiceDialogIsOpaqueCardsWithFocusOnSelectedAndDoneButton() {
        EngineProfiles.apply(EngineProfiles.Profile.AUTO)
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        val a = c.get()
        // pigułki profili na górze sekcji, aktywny – Automatyczny
        val pills = (0 until a.profileBar!!.childCount).map { a.profileBar!!.getChildAt(it) as TextView }
        assertEquals(EngineProfiles.Profile.entries.map { it.label }, pills.map { it.text.toString() })
        assertTrue(pills[0].isSelected)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Profil: Automatyczny (Zalecany)" })
        // ◄ ► między profilami (dawniej każde ◄ w treści przenosiło na karty z lewej)
        fun key(code: Int) { a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)); idle(50) }
        pills[0].requestFocus(); idle()
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); assertTrue(pills[1].isFocused)
        repeat(4) { key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }; assertTrue("ostatni profil – zostaje", pills[3].isFocused)
        key(android.view.KeyEvent.KEYCODE_DPAD_LEFT); assertTrue(pills[2].isFocused)
        repeat(2) { key(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }; assertTrue(pills[0].isFocused)
        key(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse("◄ z pierwszego – karty z lewej", pills.any { it.isFocused })
        // OK na „Szybkie przełączanie” – parametry i etykieta
        pills[1].performClick(); idle()
        assertEquals(2, Prefs.bufMinS)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Profil: Szybkie przełączanie (Sport / Live TV)" })
        assertTrue("status bufora", all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "2 s (tryb szybki)" })
        // okno „Kolejka dekodera”: nieprzezroczysty panel, przyciemnienie 80 %, karty z opisem, fokus na wybranej, ▼ z ostatniej – Gotowe
        val row = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Kolejka dekodera" }.parent as View
        row.performClick(); idle()
        val d = a.lastChoice!!
        assertTrue(d.isShowing)
        assertEquals(0.8f, d.window!!.attributes.dimAmount, 0.001f)
        val texts = all(d.window!!.decorView).filterIsInstance<TextView>()
        val cards = texts.filter { it.text in listOf("Automatycznie", "Asynchroniczna (szybsza)", "Synchroniczna (gdy obraz zamarza)") }.map { it.parent.parent as View }
        assertEquals(3, cards.size)
        assertTrue(texts.any { it.text == "Szybsze dekodowanie (wymagana przy tunelowaniu)" })
        assertTrue("fokus na wybranej opcji", cards[0].isFocused)
        val done = texts.first { it.text == "Gotowe" }
        assertEquals(done.id, cards[2].nextFocusDownId)
        // wybór działa od razu, Gotowe zamyka, fokus wraca na wiersz
        cards[1].performClick(); idle()
        assertEquals(1, Prefs.asyncDecoder)
        assertEquals("Własny (Zmodyfikowany)", EngineProfiles.activeLabel())
        done.performClick(); idle()
        assertFalse(d.isShowing)
        val focused = a.window.decorView.findFocus()
        assertTrue("fokus na wierszu „Kolejka dekodera”", focused != null && all(focused).filterIsInstance<TextView>().any { it.text == "Kolejka dekodera" })
        c.pause().stop().destroy()
    }
}

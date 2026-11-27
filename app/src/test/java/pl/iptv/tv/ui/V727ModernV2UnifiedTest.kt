package pl.iptv.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
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
import pl.iptv.tv.ui.theme.GlassIslandDrawable
import pl.iptv.tv.ui.theme.ObsidianGlass
import pl.iptv.tv.ui.theme.V2TopModuleBar
import pl.iptv.tv.ui.theme.V2WaveBackground
import java.time.Duration

/**
 * (v2, ujednolicenie) Nowoczesny v2: tło z falami, belka modułu (logo, sekcja, zegar, profil), V2PillTab, V2GlowPillButton,
 * V2OptionRow (✓ po prawej), V2GlassDialog i przełączniki; Nowoczesny (v1) bez belki i bez zmian w oknach.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V727ModernV2UnifiedTest {
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: android.view.View): List<android.view.View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        Ui.applyPreset(ModernV2.ID); Ui.changed(); X25Theme.justEnabled = false
    }

    @After fun tearDown() { Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false }

    @Test fun tokensMatchSpec() {
        assertEquals(0xFF050811.toInt(), ObsidianGlass.BG_TOP); assertEquals(0xFF090F1E.toInt(), ObsidianGlass.BG_BOTTOM)
        assertEquals(0xA6, ObsidianGlass.PILL_REST ushr 24)                    // #0E1629 65 %
        assertEquals(0x0E1629, ObsidianGlass.PILL_REST and 0xFFFFFF)
        assertEquals(0x38, ObsidianGlass.PILL_TINT ushr 24)                    // #2F80ED 22 %
        assertEquals(0xC7, ObsidianGlass.DIALOG_FILL ushr 24); assertEquals(0xB8, ObsidianGlass.OVERLAY_FILL ushr 24)   // 78 % / 72 %
        assertEquals(0x0C1426, ObsidianGlass.DIALOG_FILL and 0xFFFFFF)
        assertEquals(24, ObsidianGlass.DIALOG_RADIUS_DP)
        assertEquals(0xFF38BDF8.toInt(), ObsidianGlass.FOCUS_RING); assertEquals(0xFF38BDF8.toInt(), ObsidianGlass.CONFIRM_END)
    }

    @Test fun waveBackgroundDialogsPillsAndSwitchesDraw() {
        assertTrue(Ui.bgDrawable() is V2WaveBackground)
        assertTrue(Ui.dialogBg() is pl.iptv.tv.ui.theme.V2PanelDrawable)
        val c = Canvas(Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888))
        Ui.bgDrawable().apply { setBounds(0, 0, 960, 540); draw(c) }
        listOf(ObsidianGlass.pillTab(), ObsidianGlass.button(true), ObsidianGlass.button(false), ObsidianGlass.optionCard()).forEach { d ->
            assertTrue(d is StateListDrawable)
            for (st in listOf(intArrayOf(), intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated))) {
                d.state = st; d.setBounds(0, 0, 200, 48); d.current.draw(c)
            }
        }
        CardSheet.Switch(true, CardSheet.palette()).apply { setBounds(0, 0, 88, 48); draw(c) }
        CardSheet.Switch(false, CardSheet.palette()).apply { setBounds(0, 0, 88, 48); draw(c) }
        assertTrue(CardSheet.panelBg(CardSheet.palette()) is pl.iptv.tv.ui.theme.V2PanelDrawable)
    }

    @Test fun settingsHasModuleBarWithSectionClockAndProfile() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val bar = a.v2TopBar
        assertNotNull(bar)
        val views = all(bar!!)
        assertTrue(views.any { (it as? TextView)?.text == V2TopModuleBar.APP_NAME })
        assertTrue(views.any { (it as? TextView)?.text == "Ustawienia" })
        assertTrue(views.any { it.tag == V2TopModuleBar.PROFILE_TAG && it.isClickable && !it.isFocusable })
        assertTrue(views.filterIsInstance<TextView>().any { it.text.matches(Regex("""\d{2}:\d{2}""")) })
        assertFalse(views.any { it.isFocusable })                                // belka nie przejmuje fokusu pilota
    }

    @Test fun classicModernHasNoModuleBar() {
        Ui.applyPreset(ModernTheme.ID); Ui.changed()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        assertNull(a.v2TopBar)
    }

    @Test fun choiceCardsShowCheckOnTheRight() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val d = Dialogs.choiceCards(a, "Rozdzielczość interfejsu", listOf("Automatyczna", "1080p"), 1) {}
        idle()
        val cards = all(d.window!!.decorView).filter { it.isFocusable && it.contentDescription in listOf("Automatyczna", "1080p") }
        assertEquals(2, cards.size)
        cards.forEach { card -> val g = card as ViewGroup; assertTrue(g.getChildAt(0) is android.widget.LinearLayout); assertFalse(g.getChildAt(g.childCount - 1) is android.widget.LinearLayout) }   // tekst pierwszy, znacznik ✓ po prawej
        assertTrue(cards[1].isActivated)
        d.dismiss()
    }
}

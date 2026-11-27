package pl.iptv.tv.ui

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/** 7.7: własne motywy (zapis, automatyczny zapis przed zmianą motywu, przywracanie), Nowoczesny przy instalacji. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V77Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun customThemeSavedAndRestored() {
        Prefs.customThemes = "[]"
        Ui.applyPreset(ModernV2.ID)   // (v2) motyw bazowy własnego motywu – Nowoczesny v2 (dawniej Klasyczny, usunięty)
        assertFalse(CustomThemes.hasOverrides())
        Ui.setColor("accent", 0xFF112233.toInt()); Prefs.restore(mapOf("font" to 2, "grad_dir" to 1))
        assertTrue(CustomThemes.hasOverrides())
        val s = CustomThemes.save("Mój niebieski")
        assertEquals(ModernV2.ID, s.base)
        // przełączenie motywu zeruje zmiany, ale zapisuje je jako „Własny – <motyw>”
        Ui.applyPreset(ModernTheme.ID)
        assertFalse(CustomThemes.hasOverrides())
        val names = CustomThemes.list().map { it.name }
        assertTrue(names.toString(), "Mój niebieski" in names)
        assertTrue(names.toString(), names.any { it.startsWith(CustomThemes.AUTO_PREFIX) })
        // przywrócenie: motyw bazowy i kolory / czcionka
        CustomThemes.apply(CustomThemes.list().first { it.name == "Mój niebieski" })
        assertEquals(ModernV2.ID, Ui.preset.id)
        assertEquals(0xFF112233.toInt(), Ui.color("accent"))
        assertEquals(2, Prefs.snapshot(listOf("font"))["font"])
        CustomThemes.delete("Mój niebieski")
        assertFalse(CustomThemes.list().any { it.name == "Mój niebieski" })
        Ui.applyPreset(ModernTheme.ID); Prefs.customThemes = "[]"
    }

    @Test fun modernIsDefaultOnFreshInstall() {
        Prefs.remove("theme")
        assertTrue(Ui.applyFirstRunTheme(freshInstall = true))
        assertTrue(ModernTheme.active); assertTrue(ModernTheme.homeOn)
    }

    @Test fun holdOkTimingAndLoadingText() {
        assertEquals(500L, PlayerActivity.HOLD_OK_MS)
        assertEquals("Ładowanie źródeł", StremioDetailActivity.LOADING_TEXT)
        assertTrue(ModernHomeActivity.PREVIEW_DELAY_MS in 150..400)
    }
}

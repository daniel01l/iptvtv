package pl.iptv.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/** (7.24) Profil „Szybkie przełączanie (Sport / Live TV)” domyślny tylko przy pierwszej instalacji; aktualizacja nie zmienia ustawień. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V724EngineDefaultTest {
    private fun wipe() = Prefs.remove(*(EngineProfiles.PROFILE_KEYS + "engine_profile_init").toTypedArray())

    @Before fun clean() = wipe()
    @After fun reset() { EngineProfiles.apply(EngineProfiles.Profile.AUTO); wipe() }

    @Test fun freshInstallGetsFastProfileOnce() {
        assertTrue(EngineProfiles.applyFirstRunDefault(freshInstall = true))
        assertEquals(EngineProfiles.Profile.FAST, EngineProfiles.active())
        assertTrue(Prefs.engineProfileInit)
        // użytkownik wybiera inny profil – kolejne uruchomienia go nie nadpisują
        EngineProfiles.apply(EngineProfiles.Profile.STABLE)
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = true))
        assertEquals(EngineProfiles.Profile.STABLE, EngineProfiles.active())
    }

    @Test fun updateKeepsSavedProfile() {
        EngineProfiles.apply(EngineProfiles.Profile.STABLE)               // zapisany wybór ze starszej wersji
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = false))
        assertEquals(EngineProfiles.Profile.STABLE, EngineProfiles.active())
        assertTrue(Prefs.engineProfileInit)
    }

    @Test fun updateWithoutSavedChoiceKeepsPreviousDefaults() {
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = false))
        assertEquals(EngineProfiles.Profile.AUTO, EngineProfiles.active())
        assertFalse(Prefs.has("buf_min")); assertFalse(Prefs.has("zap_ms"))
        // znacznik ustawiony – późniejsze uruchomienie (nawet z błędnym wykryciem świeżej instalacji) nic nie zmienia
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = true))
        assertEquals(EngineProfiles.Profile.AUTO, EngineProfiles.active())
    }

    @Test fun freshInstallWithExistingEngineSettingsKeepsThem() {
        Prefs.zapDelayMs = 600; Prefs.bufMinS = 8                             // np. ustawienia przywrócone z kopii
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = true))
        assertEquals(600, Prefs.zapDelayMs); assertEquals(8, Prefs.bufMinS)
        assertNull(EngineProfiles.active())
    }

    @Test fun explicitResetSetsFastProfile() {
        EngineProfiles.apply(EngineProfiles.Profile.LOW)
        EngineProfiles.resetToDefault()
        assertEquals(EngineProfiles.Profile.FAST, EngineProfiles.active())
        assertEquals(EngineProfiles.Profile.FAST, EngineProfiles.DEFAULT)
    }
}

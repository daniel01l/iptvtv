package pl.iptv.tv.ui

import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 7.6: układ pionowy na telefonie (wymiary pulpitów), szybsze przejścia kart Ustawień i kategorii. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V76Test {
    @Test fun modernPortraitTilesFitScreenWidth() {
        val avail = (411 - 56 - 10 - 10 - 16).dp
        val d = ModernHomeActivity.Dims(compact = false, narrow = true, portrait = true, availW = avail)
        // dwa szerokie kafelki i trzy plakaty mieszczą się na szerokości (dawniej 236 dp – półtora kafelka)
        assertTrue(d.wideW * 2 + d.gap <= avail)
        assertTrue(d.posterW * 3 + d.gap * 2 <= avail + 1.dp)
        assertEquals(56.dp, d.rail)
        // TV / poziom – bez zmian
        assertEquals(236.dp, ModernHomeActivity.Dims(compact = false, narrow = false).wideW)
    }

    // (7.27) test „ps5PortraitCardsFullWidthAndX25Grid” usunięty – dotyczył usuniętych motywów Klasyczny / Xbox / X25 / PS5 (kopia: docs/archiwum_motywy/)
    @Test fun fasterTabAndCategorySwitching() {
        assertTrue(SettingsActivity.TAB_FOCUS_DELAY_MS < 250)
        assertTrue(SettingsActivity.TAB_ANIM_MS in 100..250)
        assertTrue(MainActivity.GROUP_FOCUS_DELAY_MS < 250)
        assertTrue(ModernHomeActivity.SECTION_ANIM_MS in 150..300)
    }
}

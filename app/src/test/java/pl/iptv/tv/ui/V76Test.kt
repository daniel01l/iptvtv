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

    @Test fun ps5PortraitCardsFullWidthAndX25Grid() {
        val p = Ps5HomeActivity.Dims(compact = false, narrow = true, portrait = true, availW = 379.dp)
        assertEquals(379.dp, p.heroW); assertEquals(379.dp, p.midW); assertEquals(379.dp, p.serviceW)
        assertEquals(380.dp, Ps5HomeActivity.Dims(compact = false, narrow = false).heroW)
        assertTrue(X25DashboardActivity.Dims(compact = false, narrow = true, portrait = true).portrait)
    }

    @Test fun fasterTabAndCategorySwitching() {
        assertTrue(SettingsActivity.TAB_FOCUS_DELAY_MS < 250)
        assertTrue(SettingsActivity.TAB_ANIM_MS in 100..250)
        assertTrue(MainActivity.GROUP_FOCUS_DELAY_MS < 250)
        assertTrue(ModernHomeActivity.SECTION_ANIM_MS in 150..300)
        assertTrue(DASHBOARD_ENTER_MS in 150..350)
    }
}

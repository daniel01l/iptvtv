package pl.iptv.tv.ui

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.ui.theme.*
import java.time.Duration

/** (7.17) System designu „Obsidian Stream”: tokeny, typografia TV, karta szklana i skala fokusu. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ObsidianThemeTest {
    @Test fun tokensAndTvTypography() {
        val c = ObsidianColorScheme()
        assertEquals(Color(0xFF080A0F), c.backgroundPrimary)
        assertEquals(Color(0xD9121722), c.surfaceGlass)
        assertEquals(Color(0xEB1A2233), c.surfaceElevated)
        assertEquals(Color(0xFF3B82F6), c.accentPrimary)
        assertEquals(Color(0x5960A5FA), c.accentGlow)
        assertEquals(Color(0xFFEF4444), c.badgeLive)
        assertEquals(Color(0xFFF8FAFC), c.textPrimary)
        assertEquals(Color(0xFF94A3B8), c.textSecondary)
        val t = ObsidianTypography()
        assertTrue(t.hero.fontSize.value in 32f..36f && t.heroLarge.fontSize.value in 32f..36f)
        assertEquals(FontWeight.Bold, t.hero.fontWeight)
        assertEquals(16f, t.cardTitle.fontSize.value); assertEquals(FontWeight.SemiBold, t.cardTitle.fontWeight)
        assertEquals(14f, t.epgDescription.fontSize.value); assertEquals(ObsidianTokens.TextPrimary, t.epgDescription.color)
        assertEquals(1.06f, TV_FOCUS_SCALE); assertEquals(1f, TV_FOCUS_DAMPING); assertEquals(500f, TV_FOCUS_STIFFNESS)
    }

    @Test fun glassCardDrawsWithAndWithoutFocus() {
        val a = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val focused = mutableStateOf(false)
        a.setContent {
            ObsidianTheme {
                Box(Modifier.padding(24.dp).size(200.dp, 120.dp).tvFocusScale(focused.value).obsidianGlassCard(focused.value))
            }
        }
        fun frame() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val v = a.window.decorView
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1920, 1080)
            v.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)))
        }
        frame()
        focused.value = true
        frame()
        focused.value = false
        frame()
    }
}

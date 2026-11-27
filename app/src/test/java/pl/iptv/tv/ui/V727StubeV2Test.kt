package pl.iptv.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** (v2) STube w Nowoczesnym v2: pigułka samej jakości, czas panelu, ozdobna ramka postępu, nowe ikony. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V727StubeV2Test {
    @Test fun qualityPillShowsOnlyVideoQuality() {
        assertEquals("1080p60", StubeV2Controls.qualityLabel(1080, 59.94f, auto = false))
        assertEquals("Auto 1080p", StubeV2Controls.qualityLabel(1080, 25f, auto = true))
        assertEquals("", StubeV2Controls.qualityLabel(0, 0f, auto = true))
        assertEquals("01:33", StubeV2Controls.clock(93_000)); assertEquals("1:00:05", StubeV2Controls.clock(3_605_000))
    }

    @Test fun progressBorderAndIconsDraw() {
        val c = Canvas(Bitmap.createBitmap(800, 120, Bitmap.Config.ARGB_8888))
        val b = StubeV2Controls.ProgressBorder().apply { setBounds(0, 0, 800, 120) }
        b.progress = 0f; b.draw(c); b.progress = 0.4f; b.draw(c); b.progress = 1f; b.draw(c)
        assertEquals(1f, b.progress, 0f)
        for (k in listOf(PlayerIcon.BACK, PlayerIcon.SHARE, PlayerIcon.MUTE)) {
            assertTrue(k in PlayerIcon.ALL)
            PlayerIcon(k, android.content.res.ColorStateList.valueOf(-1)).apply { setBounds(0, 0, 48, 48); draw(c) }
        }
    }
}

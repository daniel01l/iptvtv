package pl.iptv.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Channel

/** (v2) Live TV w Nowoczesnym v2: ramka ▲▼ (3 wiersze, bez EPG i jakości), szuflady i pierścień logo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V727LiveV2Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun zapOsdShowsPreviousCurrentNextWithoutEpg() {
        val z = LiveV2.ZapOsd(ctx)
        z.show(Channel("a", "Canal+ Extra 1", "http://x/1", "Sport"), Channel("b", "Canal+ Extra 2", "http://x/2", "Sport"), Channel("c", "Eleven Sports 1", "http://x/3", "Sport"), numbers = true)
        assertEquals(View.VISIBLE, z.visibility)
        assertEquals(listOf("a", "b", "c"), z.shownKeys)
        val texts = all(z).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue("Canal+ Extra 2" in texts)
        assertFalse(texts.any { it.contains(":") || it.contains("p50") || it.contains("HW") })   // bez godzin EPG i jakości
        assertFalse(z.isFocusable)
        z.dismiss(); assertEquals(View.GONE, z.visibility)
    }

    @Test fun drawablesDraw() {
        val c = Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        LiveV2.Ring().apply { setBounds(0, 0, 96, 96); draw(c) }
        LiveV2.edge(true).apply { setBounds(0, 0, 200, 200); draw(c) }
        LiveV2.edge(false).apply { setBounds(0, 0, 200, 200); draw(c) }
        assertEquals(0.70f, LiveV2.HUD_WIDTH, 0f)
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
}

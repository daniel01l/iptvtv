package pl.iptv.tv.ui

import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
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
import java.time.Duration

/** 6.6: kolor tekstu w zaznaczonym elemencie, plan języka Stremio, okna gotowe przed pokazaniem, płynne przewijanie pulpitów. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V66Test {
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() { Ui.applyPreset("modern"); Ui.changed() }
    @After fun tearDown() { Ui.applyPreset("modern"); Ui.changed() }

    @Test fun focusedItemTextUsesFocusColor() {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // element listy: tytuł i podpis (text2) w zagnieżdżonym układzie – jak kafelki STube / Stremio / listy kanałów
        val item = LinearLayout(a).apply { tag = "item"; isFocusable = true; orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(a).apply { text = "Tytuł" }
        val sub = TextView(a).apply { text = "Podpis"; tag = "text2" }
        col.addView(title); col.addView(sub); item.addView(col)
        box.addView(item); box.addView(TextView(a).apply { text = "inny"; isFocusable = true; tag = "item" })
        a.setContentView(box)
        Ui.style(box); idle()
        assertEquals(Ui.text2, sub.currentTextColor)
        item.requestFocus(); idle()
        // wcześniej: dziecko z duplicateParentState ustawionym po dodaniu nie dostawało stanu „zaznaczony”
        assertTrue(sub.drawableState.contains(android.R.attr.state_focused))
        assertEquals(Ui.focusText, sub.currentTextColor)
        assertEquals(Ui.focusText, title.currentTextColor)
        // ponowne stylowanie (zmiana motywu) – bez zmian w układzie
        Ui.style(box); idle()
        assertEquals(1, item.childCount); assertSame(col, item.getChildAt(0)); assertEquals(2, col.childCount)
    }

    @Test fun stremioPolishAudioOrPolishSubtitles() {
        // polski dźwięk (lektor) – ta ścieżka, napisy wyłączone
        val a = StremioDecoders.languagePlan(listOf("en" to "English 5.1", "pol" to "Lektor"), listOf("pl" to null))
        assertEquals(1, a.audio); assertTrue(a.subsOff); assertNull(a.embeddedText); assertFalse(a.addonSubs)
        assertTrue(StremioDecoders.isPolish(null, "Polski dubbing")); assertTrue(StremioDecoders.isPolish("pl-PL", null))
        assertFalse(StremioDecoders.isPolish("en", "English")); assertFalse(StremioDecoders.isPolish("spl", "Simple"))
        // brak polskiego dźwięku – wbudowane polskie napisy
        val b = StremioDecoders.languagePlan(listOf("en" to null), listOf("en" to null, "pol" to "Polish"))
        assertNull(b.audio); assertFalse(b.subsOff); assertEquals(1, b.embeddedText); assertFalse(b.addonSubs)
        // brak polskiego dźwięku i napisów w pliku – napisy z wtyczek
        val c = StremioDecoders.languagePlan(listOf("en" to null), emptyList())
        assertNull(c.audio); assertFalse(c.subsOff); assertNull(c.embeddedText); assertTrue(c.addonSubs)
    }

    @Test fun dialogStyledBeforeShowing() {
        Ui.applyPreset(Ps5Theme.ID); Ui.changed()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        // okno-menu: położenie panelu i tło ustawione już przy tworzeniu – bez zwykłego okna przed właściwym
        val d = Ui.dialog(a).setTitle("Menu").setItems(arrayOf("A", "B")) { _, _ -> }.create()
        assertFalse(d.isShowing)
        assertEquals(Gravity.START or Gravity.TOP, d.window!!.attributes.gravity)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, d.window!!.attributes.height)
        d.show(); idle()
        assertEquals(Gravity.START or Gravity.TOP, d.window!!.attributes.gravity)
        // pasek podpowiedzi raz (stylowanie przy tworzeniu i pokazaniu)
        var hints = 0
        fun count(v: android.view.View) { if (v.tag == Ps5Theme.HINT_TAG) hints++; if (v is ViewGroup) for (i in 0 until v.childCount) count(v.getChildAt(i)) }
        count(d.window!!.decorView); assertEquals(1, hints)
        d.dismiss()
    }

    @Test fun stremioSourcesFivePerResolutionPolishFirst() {
        fun s(t: String) = pl.iptv.tv.data.StremioStream("Torrentio", "u", name = "Torrentio", title = t, url = "http://x/" + t.hashCode())
        val list = (1..7).map { s("Film 1080p WEB $it") } + (1..6).map { s("Film 2160p $it") } + listOf(s("Film 720p"), s("Film 1080p lektor PL"),
            s("Film 1080p napisy PL"), s("Film 2160p dubbing PL"))
        val out = pl.iptv.tv.data.StremioStreams.limit(list)
        // po 5 z każdej rozdzielczości, od najwyższej
        assertEquals(listOf("4K", "1080p", "720p"), out.map { it.quality }.distinct())
        assertEquals(5, out.count { it.quality == "4K" }); assertEquals(5, out.count { it.quality == "1080p" }); assertEquals(1, out.count { it.quality == "720p" })
        // polska wersja na górze swojej rozdzielczości: dubbing / lektor, potem napisy
        assertEquals("Film 2160p dubbing PL", out.first { it.quality == "4K" }.title)
        assertEquals(listOf("Film 1080p lektor PL", "Film 1080p napisy PL", "Film 1080p WEB 1"), out.filter { it.quality == "1080p" }.take(3).map { it.title })
    }

    @Test fun stremioBufferAvoidsStalls() {
        // większy zapas niż domyślny Media3 (50 s / 5 s po przerwie), z limitem pamięci
        assertTrue(StremioDecoders.MAX_BUFFER_MS >= 120_000 && StremioDecoders.REBUFFER_MS >= 8_000)
        assertTrue(StremioDecoders.BUFFER_BYTES <= 200 * 1024 * 1024)
        assertNotNull(StremioDecoders.loadControl())
    }

    @Test fun dashboardsScrollOnlyWhenTileLeavesView() {
        val m = Ui.dp(28).toFloat()
        // kafelek w widoku – bez przesuwania rzędu (wcześniej rząd przesuwał się przy każdym ruchu)
        assertEquals(0f, SmoothScroll.calculateScrollDistance(300f, 200f, 1600f))
        // wystaje w prawo – tylko o tyle, ile trzeba (+ margines na obwódkę)
        assertEquals(1500f + 200f - 1600f + m, SmoothScroll.calculateScrollDistance(1500f, 200f, 1600f))
        // wystaje w lewo
        assertEquals(-50f - m, SmoothScroll.calculateScrollDistance(-50f, 200f, 1600f))
    }
}

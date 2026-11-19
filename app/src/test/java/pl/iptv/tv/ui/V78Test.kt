package pl.iptv.tv.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs

/** 7.8: język interfejsu – słownik, szablony, wielkie litery, wiersze, widoki (bez pól wpisywania), zapis wyboru. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V78Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun back() { I18n.setLanguage(ctx, I18n.PL) }

    @Test fun polishByDefaultWithoutAnyChange() {
        assertEquals(I18n.PL, Prefs.language)
        assertFalse(I18n.active)
        assertEquals("Ustawienia", I18n.ts("Ustawienia"))
    }

    @Test fun englishDictionaryFromAssets() {
        I18n.setLanguage(ctx, I18n.EN)
        assertEquals(I18n.EN, Prefs.language)
        assertEquals("Settings", I18n.ts("Ustawienia"))
        assertEquals("Live", I18n.ts("Na żywo"))
        assertEquals("LIVE", I18n.ts("NA ŻYWO"))                    // nazwy sekcji pisane wielkimi literami
        assertEquals("  Settings ", I18n.ts("  Ustawienia "))        // odstępy na brzegach zachowane
        assertEquals("Settings\nLive", I18n.ts("Ustawienia\nNa żywo")) // każdy wiersz osobno
        assertEquals("Polski", I18n.ts("Polski"))                    // nazwa języka zostaje we własnym języku
        assertEquals("▸  Theme", I18n.ts("▸  Motyw"))                // znaczek na początku + jedno słowo
        assertEquals("★ Favorites", I18n.ts("★ Ulubione"))
    }

    @Test fun templatesAndFragments() {
        I18n.setLanguage(ctx, I18n.EN)
        I18n.loadJson(JSONObject().put("Kanał {}: ustawienia domyślne", "Channel {}: default settings")
            .put("Dobrano silnik: {}", "Engine selected: {}").put("dekoder sprzętowy", "hardware decoder")
            .put("Silnik i akceleracja", "Engine and acceleration"))
        assertEquals("Channel TVP 1: default settings", I18n.ts("Kanał TVP 1: ustawienia domyślne"))
        // wstawka też tłumaczona, gdy jest w słowniku
        assertEquals("Engine selected: hardware decoder", I18n.ts("Dobrano silnik: dekoder sprzętowy"))
        // fragment w tekście sklejanym w kodzie
        assertEquals("⚡ Engine and acceleration", I18n.ts("⚡ Silnik i akceleracja"))
        // treść spoza słownika (np. tytuł programu) bez zmian
        assertEquals("Fakty", I18n.ts("Fakty"))
    }

    @Test fun viewsTranslatedButNotInputFields() {
        I18n.setLanguage(ctx, I18n.EN)
        val box = LinearLayout(ctx)
        val tv = TextView(ctx).apply { text = "Ustawienia" }
        val et = EditText(ctx).apply { setText("Ustawienia"); hint = "Na żywo" }
        box.addView(tv); box.addView(et)
        assertTrue(I18n.translateTree(box))
        assertEquals("Settings", tv.text.toString())
        assertEquals("Ustawienia", et.text.toString())               // wpisany tekst użytkownika bez zmian
        assertEquals("Live", et.hint.toString())
        assertFalse("drugi przebieg – nic do zmiany", I18n.translateTree(box))
    }
}

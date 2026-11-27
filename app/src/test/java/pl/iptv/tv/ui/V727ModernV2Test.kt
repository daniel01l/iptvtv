package pl.iptv.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.theme.GlassBackdrop
import pl.iptv.tv.ui.theme.ObsidianGlass

/** Motyw Nowoczesny v2 („Obsidian Glass”): rejestracja, tokeny, ustawienia v2, zachowanie ustawień przy zmianie motywu / aktualizacji. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V727ModernV2Test {

    @Before fun setup() { Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false }

    @After fun tearDown() {
        Prefs.mv2Animations = true; Prefs.mv2Opacity = 1; Prefs.lowPerf = false
        Ui.applyPreset(ModernTheme.ID); Ui.changed(); X25Theme.justEnabled = false
    }

    @Test fun v2IsSecondPresetNextToModernWithSameFeatures() {
        assertEquals(listOf(ModernTheme.ID, ModernV2.ID), Ui.PRESETS.map { it.id })
        assertEquals(ModernV2.NAME, Ui.PRESETS[1].name)
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        assertTrue(ModernV2.active); assertTrue(ModernTheme.active)           // ekran główny, panel nakładkowy, Obsidian – jak Nowoczesny
        assertEquals(Ui.S_MODERN, Ui.surface)
        assertFalse(X25Theme.justEnabled)                                       // Nowoczesny → v2: ten sam ekran główny, bez ponownego otwierania
        assertEquals(ObsidianGlass.BG_TOP, Ui.PRESETS[1].bg)
        assertEquals(ObsidianGlass.FOCUS, Ui.PRESETS[1].focus)
        assertTrue(Ui.bgDrawable() is GlassBackdrop)
        assertEquals(ObsidianGlass.FOCUS_RING, ModernTheme.ring)
        Ui.applyPreset(ModernTheme.ID); Ui.changed()
        assertFalse(ModernV2.active); assertTrue(Ui.bgDrawable() is ModernBackdrop); assertEquals(ModernTheme.RING, ModernTheme.ring)
    }

    @Test fun savedThemeAndSettingsSurviveUpdateAndThemeSwitch() {
        Ui.applyPreset(ModernV2.ID)
        Prefs.mv2Opacity = 2; Prefs.mv2Animations = false; Prefs.zapDelayMs = 300
        assertFalse(Ui.migrateRemovedThemes())                                  // start po aktualizacji – zapisany v2 zostaje
        assertEquals(ModernV2.ID, Prefs.theme); assertEquals(ModernV2.ID, Ui.preset.id)
        Ui.applyPreset(ModernTheme.ID); Ui.applyPreset(ModernV2.ID)             // zmiana motywu nie zeruje ustawień v2 ani silnika
        assertEquals(2, Prefs.mv2Opacity); assertFalse(Prefs.mv2Animations); assertEquals(300, Prefs.zapDelayMs)
        Prefs.zapDelayMs = 250
    }

    @Test fun animationsOffDisablesTransitionsOnlyInV2() {
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        assertEquals(9, Ui.anim)
        Prefs.mv2Animations = false
        assertTrue(ModernV2.animationsOff); assertEquals(0, Ui.anim); assertTrue(ModernColors.fromUi().lowPerf)
        Ui.applyPreset(ModernTheme.ID)
        assertFalse(ModernV2.animationsOff); assertEquals(9, Ui.anim)           // Nowoczesny – bez zmian
    }

    @Test fun opacityLevelsChangeGlassAlpha() {
        assertEquals(listOf("Wysoka", "Średnia", "Niska"), ModernV2.OPACITY_OPTIONS)
        assertEquals(ObsidianGlass.Opacity.MEDIUM, ObsidianGlass.Opacity.current())
        val levels = ObsidianGlass.Opacity.entries
        assertTrue(levels.zipWithNext().all { (a, b) -> a.surfaceAlpha < b.surfaceAlpha && a.panelAlpha < b.panelAlpha })
        Prefs.mv2Opacity = 0; assertEquals(ObsidianGlass.Opacity.HIGH, ObsidianGlass.Opacity.current())
        Prefs.mv2Opacity = 7; assertEquals(ObsidianGlass.Opacity.LOW, ObsidianGlass.Opacity.current())   // poza zakresem – przycięte
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        assertEquals(ObsidianGlass.DIALOG_FILL, CardSheet.palette().panel)   // (v2, ujednolicenie) V2GlassDialog #0C1426 78 %
    }

    // ---------------- Live TV, odtwarzacz, EPG, Edytor (krok 2) ----------------

    @Test fun obsidianViewsPaletteFollowsV2AndOpacity() {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        assertEquals(0xEB0F141F.toInt(), O.PANEL_92); assertEquals(0xFF080A0F.toInt(), O.BG)      // Nowoczesny – bez zmian
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        assertEquals(ObsidianGlass.BG_TOP, O.BG)
        Prefs.mv2Opacity = 0; val high = android.graphics.Color.alpha(O.PANEL_92)
        Prefs.mv2Opacity = 2; val low = android.graphics.Color.alpha(O.PANEL_92)
        assertTrue(high < low)
        assertEquals(ObsidianGlass.OVERLAY_FILL, O.overlay(0))                               // (v2, ujednolicenie) nakładki w odtwarzaczu – V2GlassDialog 72 %
    }

    @Test fun infoBarDefaultsToGlassCardOnlyInV2AndKeepsSavedStyle() {
        assertEquals(0, Ui.infoStyle)
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        assertEquals(Ui.INFO_GLASS, Ui.infoStyle); assertTrue(Ui.infoFloating)
        assertNotNull(Ui.infoBarBg(false)); assertNotNull(Ui.infoLogoBg())
        Ui.infoStyle = 6; assertEquals(6, Ui.infoStyle)                                       // wybór użytkownika ma pierwszeństwo
        Ui.applyPreset(ModernTheme.ID)
    }

    @Test fun streamBadgeShowsResolutionFpsAndDecoder() {
        assertEquals("1080p50 • HW", PlayerActivity.streamBadge(1080, 50f, false))
        assertEquals("576p25 • SW", PlayerActivity.streamBadge(576, 25.0f, true))
        assertEquals("720p • HW", PlayerActivity.streamBadge(720, -1f, false))
        assertNull(PlayerActivity.streamBadge(0, 25f, false))
    }

    @Test fun listRowsGuideCellsAndTilesUseGlassStatesInV2() {
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        val focused = intArrayOf(android.R.attr.state_focused)
        for (d in listOf(Ui.itemBg(), Ui.cellBg(0), Ui.cellBg(2), Ui.tileBg())) {
            assertTrue(d is android.graphics.drawable.StateListDrawable)
            d.state = focused; assertNotNull(d.current)
        }
        assertNotSame(Ui.cellBg(0), Ui.cellBg(0))                                              // każda komórka – własna kopia
        assertTrue(Ui.cellBg(1) !== Ui.cellBg(1))                                              // „Linie (siatka)” – dawne tło
    }

    // ---------------- STube i Stremio VOD (krok 3) ----------------

    @Test fun cinemetaCatalogsGetDistinctRowTitles() {
        val names = listOf("top", "imdbRating", "year").map { StremioActivity.cinemetaPrefix(it) }
        assertEquals(3, names.toSet().size)
        assertEquals(StremioActivity.CINEMETA_POPULAR, StremioActivity.cinemetaPrefix("top"))
        assertEquals(StremioActivity.CINEMETA_POPULAR, StremioActivity.cinemetaPrefix("inny"))
    }

    @Test fun m3uMoviesAndEpisodesAreVodNotLiveTv() {
        val R = pl.iptv.tv.data.Repository
        assertTrue(R.isVod(pl.iptv.tv.data.Channel("m", "Film 2021", "http://x/movie/u/p/1.mkv", "VOD | Akcja")))
        assertTrue(R.isVod(pl.iptv.tv.data.Channel("e", "Serial S01E02", "http://x/series/u/p/2.mp4", "Seriale")))
        assertFalse(R.isVod(pl.iptv.tv.data.Channel("l", "TVN", "http://x/1.ts", "Ogólne")))
        assertFalse(R.isVod(pl.iptv.tv.data.Channel("f", "Canal+ Film", "http://x/123", "Filmy")))      // kanał na żywo w grupie „Filmy” zostaje w Live TV
        assertTrue(Prefs.vodSeparate)                                                          // domyślnie VOD tylko w module VOD
    }

    @Test fun playerPillsUseGlassInV2() {
        val before = PlayerChrome.pillBg()
        Ui.applyPreset(ModernV2.ID); Ui.changed()
        val v2 = PlayerChrome.pillBg() as android.graphics.drawable.StateListDrawable
        v2.state = intArrayOf(android.R.attr.state_focused); assertNotNull(v2.current)
        assertNotNull(before)
    }

    // ---------------- pilot, udostępnianie, launcher (krok 4) ----------------

    @Test fun doubleAndLongPressMappingsAreSeparateFromSingle() {
        val K = pl.iptv.tv.data.KeyMap
        K.clearGestures()
        val single = K.map()
        K.setGesture(pl.iptv.tv.data.KeyMap.Press.DOUBLE, android.view.KeyEvent.KEYCODE_PROG_RED, pl.iptv.tv.data.KeyMap.Action.MUTE)
        K.setGesture(pl.iptv.tv.data.KeyMap.Press.LONG, android.view.KeyEvent.KEYCODE_DPAD_CENTER, pl.iptv.tv.data.KeyMap.Action.SYS_SETTINGS)
        assertEquals(pl.iptv.tv.data.KeyMap.Action.MUTE, K.gestureFor(pl.iptv.tv.data.KeyMap.Press.DOUBLE, android.view.KeyEvent.KEYCODE_PROG_RED))
        assertEquals(pl.iptv.tv.data.KeyMap.Action.SYS_SETTINGS, K.gestureFor(pl.iptv.tv.data.KeyMap.Press.LONG, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(K.gestureFor(pl.iptv.tv.data.KeyMap.Press.LONG, android.view.KeyEvent.KEYCODE_PROG_RED))
        assertEquals(single, K.map())                                                          // pojedyncze bez zmian
        assertEquals(0, K.presetIndex())                                                        // preset nadal rozpoznany
        K.setGesture(pl.iptv.tv.data.KeyMap.Press.DOUBLE, android.view.KeyEvent.KEYCODE_PROG_RED, null)
        assertTrue(K.gestures(pl.iptv.tv.data.KeyMap.Press.DOUBLE).isEmpty())
        K.clearGestures(); assertTrue(K.gestures(pl.iptv.tv.data.KeyMap.Press.LONG).isEmpty())
    }

    @Test fun shareTextsNeverContainStreamAddresses() {
        assertEquals("https://youtu.be/abc?t=75", ShareLinks.youtube("abc", 75_400))
        assertEquals("https://youtu.be/abc", ShareLinks.youtube("abc"))
        val st = ShareLinks.stremio("Film", "movie", "tt0111161")
        assertTrue("imdb.com/title/tt0111161" in st); assertTrue("web.stremio.com/#/detail/movie/tt0111161" in st)
        assertEquals("Serial M3U", ShareLinks.stremio("Serial M3U", "series", "m3u:s:abc"))   // pozycja z listy – bez adresu
        assertEquals("Oglądam: TVN – Fakty", ShareLinks.liveTv("TVN", "Fakty"))
    }

    @Test fun homeGuardIgnoresBurstsOutsideRobolectricOnly() {
        assertFalse(pl.iptv.tv.HomeActivity.guard)                                             // testy – bez ochrony
        pl.iptv.tv.HomeActivity.guardForTest = true; assertTrue(pl.iptv.tv.HomeActivity.guard)
        pl.iptv.tv.HomeActivity.resetForTest(); assertFalse(pl.iptv.tv.HomeActivity.guard)
    }

    @Test fun firstInstallDefaultsToFastProfileButUpdateKeepsUserChoice() {
        Prefs.engineProfileInit = false
        val keys = EngineProfiles.PROFILE_KEYS.filter { Prefs.has(it) }
        if (keys.isEmpty()) {
            assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = false))            // aktualizacja – bez zmiany
            Prefs.engineProfileInit = false
            assertTrue(EngineProfiles.applyFirstRunDefault(freshInstall = true))               // czysta instalacja – FAST
            assertEquals(EngineProfiles.DEFAULT, EngineProfiles.active())
        }
        assertFalse(EngineProfiles.applyFirstRunDefault(freshInstall = true))                  // drugi raz – nic
    }

    @Test fun swatchesShowFivePresetColors() {
        val s = SettingsActivity.themeSwatches(Ui.PRESETS[1]) as android.text.Spanned
        val spans = s.getSpans(0, s.length, android.text.style.ForegroundColorSpan::class.java)
        assertEquals(5, spans.size)
        assertEquals(ObsidianGlass.BG_TOP, spans.first().foregroundColor)
    }
}

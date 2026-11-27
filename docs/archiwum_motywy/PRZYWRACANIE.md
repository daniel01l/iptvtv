# Archiwum motywów: Klasyczny dekoder, Xbox Series X, X25 Dashboard, PS5

W kroku po wydaniu 7.26 (versionCode 86, commit `6352799`) z aktywnej aplikacji usunięto cztery motywy. Został tylko **Nowoczesny**.
Ten katalog leży poza źródłami Gradle (`docs/`), więc nic z niego nie trafia do APK.

| Motyw | id w `Prefs.theme` | Faktura (`Ui.surface`) | Własny ekran |
|---|---|---|---|
| Klasyczny dekoder | `classic` | `S_FLAT` = 0 | – (zwykłe układy list) |
| Xbox Series X – konsola | `xbox` | `S_XBOX` = 5 | – (narzucał układ menu `LAYOUT_CARDS` = 4) |
| X25 Dashboard | `x25` | `S_X25` = 6 | `X25DashboardActivity` (pulpit z kafelkami, Compose) |
| PS5 | `ps5dash` | `S_PS5` = 7 | `Ps5HomeActivity` (ekran główny z zakładkami, Compose) |

## Zawartość archiwum

- `usuniete/` – pliki całkowicie usunięte ze źródeł, w strukturze katalogów projektu:
  - `app/src/main/java/pl/iptv/tv/ui/X25DashboardActivity.kt` – pulpit X25 (`X25Recent`, `Dims`, Flyout/Guide, edycja kafelków)
  - `app/src/main/java/pl/iptv/tv/ui/Ps5HomeActivity.kt` – ekran główny PS5 (`Dims`, karty, `DeviceApp`, panel opcji)
  - `app/src/main/java/pl/iptv/tv/ui/Ps5Theme.kt` – `Ps5Theme` (`ID`, `GLOW`, `active`, `homeOn`, `setEnabled`, `openHome`, panel okien, `buttonText`, `addDialogHints`, `attachScreenHints`, `HINT_TAG`), `Ps5Input`, `Ps5Hints`, `Ps5Switch`, `Ps5Backdrop`
  - `app/src/main/res/anim/x25_guide_in.xml`, `x25_guide_out.xml` – animacje panelu „Guide” X25
  - `app/src/test/java/pl/iptv/tv/ui/X25Test.kt`, `Ps5Test.kt` – testy tych motywów
- `oryginaly_7.26/` – pełne kopie plików z wersji 7.26 sprzed zmiany (te, które zmieniono, i te, które tylko podpinały motywy). Do porównania (`diff`) albo do skopiowania w całości.

Najprościej przywrócić wszystko z gita (wersja 7.26):

```
git checkout 6352799 -- app/src/main/java/pl/iptv/tv/ui/X25DashboardActivity.kt app/src/main/java/pl/iptv/tv/ui/Ps5HomeActivity.kt app/src/main/java/pl/iptv/tv/ui/Ps5Theme.kt app/src/main/res/anim/x25_guide_in.xml app/src/main/res/anim/x25_guide_out.xml app/src/test/java/pl/iptv/tv/ui/X25Test.kt app/src/test/java/pl/iptv/tv/ui/Ps5Test.kt
```

a potem cofnąć zmiany w plikach z listy niżej. Bez gita: skopiować pliki z `usuniete/` i `oryginaly_7.26/` w te same ścieżki projektu. Uwaga: pliki z `oryginaly_7.26/` nie zawierają zmian wprowadzonych po 7.26 (np. motyw „Nowoczesny v2”). Przy przywracaniu po kolejnych wydaniach przenosić więc tylko fragmenty opisane poniżej.

## Gdzie motywy były podpięte (krok po kroku)

### 1. Lista motywów: `app/src/main/java/pl/iptv/tv/ui/Ui.kt`
1. `Ui.PRESETS`: przywrócić 4 wpisy `ThemePreset` (`classic`, `xbox`, `X25Theme.ID`, `Ps5Theme.ID`) za Nowoczesnym. Kolory i parametry są w `oryginaly_7.26/.../ui/Ui.kt` przy `val PRESETS`.
2. Stałe faktur: `S_XBOX = 5`, `S_X25 = 6`, `S_PS5 = 7`.
3. Gałęzie `when (surface)` dla tych faktur w `geo`, `bgDrawable()` (`X25Backdrop`, `Ps5Backdrop`), `texturedSurface()`, `infoStyle` (`S_XBOX -> 9; S_X25 -> 7; S_PS5 -> 1`), `focusDrawable()` z funkcjami `xboxFocus()`, `x25Focus()`, `ps5Focus()`, `createItemBg()`, `logoRadius` (Xbox 1 dp), `zoomAnimator` (PS5 bez cienia elewacji), `dialogBg()`, `pillBg()` (X25 promień 4), `slideIn` (Xbox `DecelerateInterpolator(2.4f)`).
4. `styleDialog()`: panel PS5 (`Ps5Theme.isPanel` / `panelBg` / `layoutPanel`), panel Guide X25 (`X25Theme.isGuide` / `guideBg` / `layoutGuide`, styl `R.style.X25GuideAnim`), kolory przycisków (`X25Theme.buttonText`, `Ps5Theme.buttonText`), podpowiedzi `Ps5Theme.addDialogHints(d)`.
5. `applyPreset()`: zapamiętywanie poprzedniego motywu (`Prefs.x25PrevTheme`, `Prefs.ps5PrevTheme`) i `X25Theme.justEnabled = true` przy włączeniu X25 / PS5.
6. Fallback: usunąć z `REMOVED_THEMES` wpisy `classic`, `xbox`, `x25`, `ps5dash` i wrócić do `"ps5" to "xbox"`. Wywołanie `Ui.migrateRemovedThemes()` w `IptvApp.onCreate` (krok `themeMigration`) usunąć albo zawęzić. Inaczej przy każdym starcie przywrócony motyw zmieni się znów na Nowoczesny.
7. `CustomThemes.parse` mapuje `base` usuniętych motywów na Nowoczesny (`Ui.REMOVED_THEMES`). Po punkcie 6 działa samo.

### 2. Ekran Ustawienia → Wygląd: `ui/SettingsActivity.kt`
1. `buildLook()`: przywrócić sekcje `header("X25 Dashboard")` (włącznik motywu, „Pulpit X25 jako ekran startowy”, „Menu jako panel boczny (Guide)”, „Dźwięki nawigacji”, „Otwórz pulpit X25”, „Przywróć układ kafelków pulpitu” → `X25Store.reset()`) i `header("PS5")` (włącznik, „Ekran główny PS5 jako ekran startowy”, „Menu jako panel ustawień PS5”, „Dźwięki nawigacji”, „Otwórz ekran główny PS5”). Dziś w tym miejscu stoi komentarz `(7.27) sekcje motywów X25 Dashboard i PS5 usunięte`.
2. Wybór „Motyw” (`choice("Motyw", Ui.PRESETS…)`) bierze listę z `Ui.PRESETS`, więc po punkcie 1.1 pokaże motywy sam.
3. `toggle()`: przełącznik `Ps5Switch(on)` przy `Ps5Theme.active`.
4. `override val ps5Hints = true` w `SettingsActivity` i `StremioAddonsActivity` oraz `protected open val ps5Hints` w `BaseActivity`.
5. `isDashboard(a)`: dopisać `Ps5HomeActivity` i `X25DashboardActivity`.
6. Opis trybu launchera: tekst „X25 przy motywie X25, w pozostałych motywach ekran główny w stylu PS5 …”.

### 3. Kreator konfiguracji, krok 5/6 „Wygląd”: `ui/SetupActivity.kt`
`stepLook()` wypisuje `Ui.PRESETS`. Po przywróceniu presetów (1.1) motywy wracają tu same. Przywrócić tylko tekst „Wybierz motyw – zmiana jest widoczna od razu…”. Tłumaczenie jest nadal w `assets/i18n/en.json`.

### 4. Routing i renderowanie ekranów głównych
1. `ui/X25Theme.kt`: przywrócić pełny plik z `oryginaly_7.26/`. Dziś obiekt ma tylko routing: `dashboardOn` = `ModernTheme.homeOn`, `dashboardIntent` → zawsze `ModernHomeActivity`, `launcherHome`, `justEnabled`. Pełny plik zawiera `ID`, `active`, `ownDashboard`, `setEnabled`, Guide, dźwięki (`attachSounds`, `navSound`, `selectSound`) i klasę `X25Backdrop`. Dawny `dashboardIntent`: X25 → `X25DashboardActivity`, Nowoczesny → `ModernHomeActivity`, inaczej `Ps5HomeActivity`. Dawny `dashboardOn` = `ownDashboard || ModernTheme.homeOn || Ps5Theme.homeOn`.
2. `ui/BaseActivity.kt`: `X25Theme.attachSounds(this)` w `onCreate`, `Ps5Input.onKey(event)` w `dispatchKeyEvent`, nadpisanie `dispatchTouchEvent` (`Ps5Input.onTouch()`), `Ps5Theme.attachScreenHints(rootFrame)` w `onResume`.
3. `ui/ModernHomeActivity.kt`: opcje „Przełącz na motyw PS5” i „Przełącz na motyw X25” w `optionItems()` oraz funkcja `switchTheme`.
4. `ui/ModernTheme.kt`: `fun navSound(c) = X25Theme.navSound(c)`.
5. `MainActivity`, `HomeActivity`, `IptvApp` korzystają z `X25Theme.dashboardOn` / `openDashboard` / `refreshLauncher`. Nie zmieniono ich, więc po przywróceniu `X25Theme.kt` znów kierują do X25 / PS5.
6. `AndroidManifest.xml`: wpisy `<activity android:name=".ui.X25DashboardActivity" …>` i `<activity android:name=".ui.Ps5HomeActivity" …>` (obie `screenOrientation="behind"`, `windowSoftInputMode="stateHidden"`), dziś zastąpione komentarzem `(7.27)`.
7. `res/values/themes.xml`: styl `X25GuideAnim` (`x25_guide_in` / `x25_guide_out`).

### 5. Pozostałe podpięcia
- `ui/X25Compose.kt`: `X25Colors`, `LocalX25`, `X25ThemeProvider`, `X25Type`, `x25Frame`, `FlyItem`, `FlyPage` (pulpit X25). Został `x25Clickable`, bo używa go Nowoczesny, OSD i STube.
- `ui/Ps5Compose.kt`: `Ps5Option`, `dashboardEnter`, `DASHBOARD_ENTER_MS` oraz poświata `Ps5Theme.GLOW` w `Ps5Colors.fromUi()`. Zostały wspólne `PS5ThemeProvider`, `ps5Frame`, `ps5Switch`, `TileImage`, `SmoothScroll`, `focusSpring`, `raiseOnFocus`, `focusScale`.
- `ui/AppLibraryActivity.kt`: dźwięki `X25Theme.selectSound` / `navSound`, opcja „Dodaj / Usuń z ekranu głównego” (`Prefs.ps5Apps`, gdy `!X25Theme.active`), pasek podpowiedzi `Ps5Hints(LIBRARY_HINTS)`.
- `ui/StubeTheme.kt`: przy X25 / PS5 paleta STube = null, a w kluczu `key` jest `"x25"` / `"ps5"`.
- `ui/StremioUi.kt` (`StremioAddonAdapter`): przełącznik `Ps5Switch` przy PS5.
- `ui/PlayerActivity.kt`: przyciski archiwum w kształcie Xbox (promień 2 dp, ramka `Ui.focus` 3 dp).
- `data/Prefs.kt`: przywrócić `x25Dashboard` (var), `x25Guide`, `x25Sounds`, `x25PrevTheme`, `ps5Home` (var), `ps5Panel`, `ps5Sounds`, `ps5PrevTheme`, `ps5Apps`. Klucze w pamięci urządzenia nie zostały skasowane, więc przywrócone motywy odczytają dawne wartości.

### 6. Co zostało w aplikacji (nie trzeba przywracać)
- `data/X25Dash.kt`: encja Room `DashTile` (tabela `dash_tiles`), `X25Layout`, `X25Store`, `Prefs.x25StremioTile`. Należą do schematu bazy, a ich usunięcie wymagałoby migracji Room, więc zostały. Zapisany układ kafelków pulpitu X25 jest nadal w bazie.
- Wspólny kod o dawnych nazwach, używany przez Nowoczesny: `X25Actions` / `X25Action` (akcje ekranu głównego), `X25Icons`, `x25Clickable`, pomocnicze funkcje w `Ps5Compose.kt`, `AppLibraryActivity` (biblioteka aplikacji).
- Animacje przejść `xbox_in` / `xbox_out` (pozycja „Konsola – szybkie (Xbox)” w `Ui.ANIMS`, wybierana niezależnie od motywu).
- Układ menu „Karty poziome (konsola)” (`LAYOUT_CARDS`) w Ustawienia → Wygląd → Układ menu głównego.

### 7. Testy
Przywrócić `X25Test.kt`, `Ps5Test.kt` oraz oryginały z `oryginaly_7.26/app/src/test/…`:
- `ModernTest`: lista 5 motywów, routing PS5, opcja „Przełącz na motyw PS5”, próg „Ostatnio oglądane” w motywie Klasyczny.
- `V57Test.newThemesWithTextures`.
- `V58Test`: `consoleThemesSetGeometryAndLayout`, `dialogFramesPerTheme`, `horizontalCardsLayout`, `roundIconTilesInConsoleTheme`.
- `V59Test`, `V60Test.systemThemesGeometryAndFocus`, `V61Test`.
- `V66Test.dialogStyledBeforeShowing`.
- `V76Test.ps5PortraitCardsFullWidthAndX25Grid`.
- `V723SettingsFromDashboardTest`: pętla po trzech pulpitach.

Testy z pomocnikiem `classicTheme` (V23Test.kt) albo `Ui.applyPreset("classic")` nie zostały zmienione. Po przywróceniu motywu Klasyczny znów działają w nim. Bez niego uruchamiają się w Nowoczesnym, bo `"classic"` → `"modern"`.

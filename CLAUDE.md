# Przekazanie projektu D-iptv Player / D-iptv Admin

## Projekt
- Android TV + telefon, odtwarzacz IPTV (M3U, EPG XMLTV, catch-up, nagrywanie, PiP, aktywacja).
- Kod: `IptvTv-source.tar.gz` (Gradle 8.14.3, AGP 8.7.3, Kotlin 2.0.21, Media3 1.4.1, OkHttp, Coil 2.7, Robolectric 4.13).
- Moduły: `:app` (pl.iptv.tv), `:admin` (pl.iptv.admin); wspólny kod `shared/pl/iptv/shared/` (RelayCrypto.kt, ActivationFormat.kt).
- Flavory app: `full` (pl.iptv.tv, plik D-iptv-Player.apk, iptvtv.json, repo github daniel01l/iptvtv) i `secure` (pl.iptv.dplayer, wersja TEST, DPlayer.apk, dplayer.json, repo daniel01l/dplayer). Zmiany robimy tylko w `full`.
- Aktualna wersja: Player 6.1 (versionCode 54), Admin 2.4 (versionCode 10), TEST 1.2 (versionCode 3).

## Podpis i aktywacja
- Klucz podpisu: `iptvtv.keystore` (hasło android, alias androiddebugkey) – umieścić w `keystore/` projektu. Nie publikować.
- Lista aktywacji: https://raw.githubusercontent.com/daniel01l/dplayer/main/activation.json (podpis ECDSA P-256; pola s=test, f=pełna, k=kod admina szyfrowany ECDH). Klucz publiczny w `keystore/activation_pub.txt`.

## Budowanie i testy
```
export ANDROID_HOME=/ścieżka/do/android-sdk
./gradlew :app:testFullReleaseUnitTest :admin:testReleaseUnitTest --continue
./gradlew :app:assembleFullRelease :admin:assembleRelease
```
APK: `app/build/outputs/apk/full/release/D-iptv-Player.apk` (nazwę ustawia `app/build.gradle.kts`).
Na Windows: `JAVA_HOME=C:\Users\dkier\.jdks\jbr-21.0.11`.

## Wydawanie nowej wersji
1. Podbić versionCode/versionName w `app/build.gradle.kts` (defaultConfig).
2. Dopisać sekcję w README.md.
3. Zbudować, sprawdzić podpis (`apksigner verify --print-certs`, SHA-256 041b543f…).
4. Zaktualizować `iptvtv.json` (versionCode, versionName, apk, notes) i wgrać wydanie na GitHub (daniel01l/iptvtv).

## Ostatnie zmiany (6.1)
Usunięte motywy ps5/win11/magic (`Ui.REMOVED_THEMES` mapuje stare id, stałe S_CARD/S_MICA/S_PS usunięte); `Ui.themeEpoch` + `BaseActivity.rebuildForTheme` (ekrany menu przebudowywane po zmianie motywu, Ustawienia w `onWindowFocusChanged`); `PiconView` ignoruje tint/filtr/alpha, `allowRgb565(false)`; `Playback`: `TS_FLAGS`, `extractors`, HLS przez `HlsMediaSource` z `DefaultHlsExtractorFactory`, `streamHttp` (12/20 s, bez callTimeout), `guessMime` + `hlsUrls` (fallback na HLS), bufor `START_BUFFER_MS`/`REBUFFER_MS`; okna: `Ui.fixDialogScrollers`; launcher: `HomeActivity.firstSinceBoot` (BOOT_COUNT), `BootReceiver.shouldLaunch`. Testy: `V61Test`.

## Zmiany 6.0
Motywy: iOS promień 22 + `glowFocus(Color.WHITE)`, MagicOS akcent #2EC7FF + krawędź zaznaczenia, Win11 promień 8, Xbox focus #107C10 (`Ui.xboxFocus`); `Ui.style` przycina każdy `item` (`roundClip`, wyjątek tag `noclip`); logo: `IptvApp.PICON_*` (40/75/180 s, 3 ponowienia), `PICON_RETRY_DELAYS` (4/15/45 s), inicjały od razu w `loadPicon`. Testy: `V60Test`.

## Zmiany 5.9
Przycinanie: `Ui.style` (panel/glass → `clipToOutline`), `Ui.roundClip`, `PiconView` (zaokrąglone logo, `Ui.logoRadius`); pasek informacji: style 5–9 w `Ui.infoBarBg`, `Ui.infoLogoBg`, domyślny styl wg motywu; archiwum: `setupCatchupBar` (kształty, fokus, oś czasu), `seekAccel`, podpowiedź z godziną; listy menu bez `itemAnimator`. Testy: `V59Test`.

## Zmiany 5.8
Geometria motywów `Ui.Geometry`/`Ui.geo` (zoom, nagłówki, ramki okien z `TexturedDrawable.topBar`, `iconBoxBg`, rozmycie), motywy ps5/xbox (`S_PS`, `S_XBOX`, `ThemePreset.layout`), przejścia anim 4–8 (`res/anim`), układ kart `MainActivity.cardsLayout` + `ChannelAdapter.cards`; kafelek „engine” w `MiniMenu` (domyślnie, `okLongAction`=1); logo: `IptvApp.piconHttp` (ponowienia), `Repository.piconCandidates`, `loadPiconChain`, `MonogramDrawable`. Testy: `V58Test`.

## Zmiany 5.7
Motywy z fakturą (`ThemePreset.surface`, `TexturedDrawable`: magic/ios/win11), cache `Ui.itemBg`; przejścia z `R.anim.hold` (czarny ekran), tło okna w `BaseActivity`; Ustawienia: sekcje bez przebudowy (`styleSection`); logo: `IptvApp.newImageLoader`, `PiconView`, opóźnione `loadPicon`; przytrzymanie OK = `PlayerActivity.playbackMenu` (`Prefs.okLongAction`, `Prefs.hwAccel`, `Playback.softwareDecoding`); wyniki: `Prefs.scoreHideResults`, kolejka goli `Scores.takeGoals`, `Scores.liveForProgramme` (EPG). Testy: `V57Test`.

## Wcześniejsze zmiany (5.6 / Admin 2.4)
Silnik odtwarzania i parametry trybu słabszych urządzeń (`Prefs`, `ui/Playback.kt`, Ustawienia → Odtwarzanie); lista po OK z kategoriami ◄ ► (`PlayerActivity.zapCycleGroup`); OK na kategorii w menu; `Ui.stripIcon` (nazwy list bez obcinania); blokada podwójnych okien w `Ui.dialog`; Ustawienia z `screenOrientation=behind`. Admin: Moje urządzenia od razu (`Admin.addPending`, `Activations.update` zwraca zapisany stan), „Nazwa: KOD” (`deviceLabel`), wybór listy (`use_list`), zapisane listy. Testy: `V56Test`, `MyDevicesTest`.

## Zasady pracy (wymagania użytkownika)
- Odpowiedzi po polsku, krótko.
- Każda zmiana: pełne testy + nowy APK.
- Pełny opis funkcji: README.md.

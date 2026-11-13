# Przekazanie projektu D-iptv Player / D-iptv Admin

## Projekt
- Android TV + telefon, odtwarzacz IPTV (M3U, EPG XMLTV, catch-up, nagrywanie, PiP, aktywacja).
- Kod: `IptvTv-source.tar.gz` (Gradle 8.14.3, AGP 8.7.3, Kotlin 2.0.21, Media3 1.4.1, OkHttp, Coil 2.7, Robolectric 4.13).
- Moduły: `:app` (pl.iptv.tv), `:admin` (pl.iptv.admin); wspólny kod `shared/pl/iptv/shared/` (RelayCrypto.kt, ActivationFormat.kt).
- Flavory app: `full` (pl.iptv.tv, plik D-iptv-Player.apk, iptvtv.json, repo github daniel01l/iptvtv) i `secure` (pl.iptv.dplayer, wersja TEST, DPlayer.apk, dplayer.json, repo daniel01l/dplayer). Zmiany robimy tylko w `full`.
- Aktualna wersja: Player 5.6 (versionCode 49), Admin 2.4 (versionCode 10), TEST 1.2 (versionCode 3).

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

## Ostatnie zmiany (5.6 / Admin 2.4)
Silnik odtwarzania i parametry trybu słabszych urządzeń (`Prefs`, `ui/Playback.kt`, Ustawienia → Odtwarzanie); lista po OK z kategoriami ◄ ► (`PlayerActivity.zapCycleGroup`); OK na kategorii w menu; `Ui.stripIcon` (nazwy list bez obcinania); blokada podwójnych okien w `Ui.dialog`; Ustawienia z `screenOrientation=behind`. Admin: Moje urządzenia od razu (`Admin.addPending`, `Activations.update` zwraca zapisany stan), „Nazwa: KOD” (`deviceLabel`), wybór listy (`use_list`), zapisane listy. Testy: `V56Test`, `MyDevicesTest`.

## Zasady pracy (wymagania użytkownika)
- Odpowiedzi po polsku, krótko.
- Każda zmiana: pełne testy + nowy APK.
- Pełny opis funkcji: README.md.

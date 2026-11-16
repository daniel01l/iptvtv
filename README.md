# D-iptv Player – odtwarzacz M3U dla Android TV i telefonów (v6.2)

## Funkcje
**Źródła:** lista M3U z linku, pliku lub ścieżki • EPG XMLTV (`.xml`/`.xml.gz`, adres `url-tvg` z listy lub ręczny) • picony (`tvg-logo`, własny katalog lub szablon `{id}`/`{name}`/`{name_raw}`, ikony z EPG) • catch-up: `default`, `append`, `shift`, `flussonic`, `xc`.

**Wygląd** (Ustawienia → Wygląd):
- 9 motywów: Nowoczesny, Klasyczny dekoder, AMOLED czerń, Jasny, Neon retro, Kino, Leśny oraz iOS – szkło i Xbox Series X – konsola (zmieniają też geometrię: kształty, ramki okien, ikony, przejścia, układ menu). Zmiana motywu działa od razu na wszystkich ekranach, bez ponownego uruchamiania. Motyw ustawia wszystkie poniższe opcje, a potem każdą można zmienić osobno.
- 7 kolorów do zmiany (tło, panele, akcent, zaznaczenie, tekst zaznaczenia, tekst, tekst dodatkowy): paleta lub kod `#RRGGBB`
- 8 krojów czcionki, 4 wielkości tekstu
- animacje: brak, zanikanie, przesunięcie, powiększenie; 3 prędkości; powiększanie zaznaczonego elementu
- pasek postępu: cienka linia, gruby, zaokrąglony, segmenty (klasyczny), ukryty; opcjonalnie pozostały czas
- pasek informacji: na dole lub na górze, czas wyświetlania 3–12 s
- zdjęcie w tle menu (z urządzenia lub z linku), przyciemnienie, krycie paneli
- podgląd na żywo w ustawieniach

**Funkcje** (Ustawienia → Funkcje, każdą można wyłączyć): grupa „Ulubione” na górze • grupa „Ostatnio oglądane” • wyszukiwarka (nazwa, numer, tytuł bieżącego programu) • numery kanałów • panel kanałów (◄) i jego zakres (3–24 h) • panel ostatnio oglądanych (►) i limit (domyślnie 5) • czyszczenie historii i ulubionych.

**Karty w Ustawieniach (v3.4)**
- 📋 **Listy**: wiele list, aktywna ✔, nazwa, adres, EPG, data ważności, usuwanie. Licznik dni do końca ważności aktywnej listy jest w lewym górnym rogu menu (tylko gdy podano datę; datę można też podać w kreatorze).
- 🔒 **Kontrola rodzicielska**: PIN, blokada grup i kanałów, ukrywanie zablokowanych, ochrona ustawień i edytora. Odblokowanie działa do zamknięcia aplikacji.
- ⏺ **Nagrywanie**: z menu programu (też zaplanowane) i z menu odtwarzacza ►►. Zapis do .ts (MPEG-TS i HLS bez szyfrowania), folder do wyboru, marginesy, lista nagrań z odtwarzaniem i usuwaniem.
- 🖼 **Obraz w obrazie**: automatycznie po naciśnięciu HOME w odtwarzaczu (Android 8+, głównie telefony).
- 📥 **Odbierz listę**: telewizor pokazuje nazwę, adres IP i PIN. Na telefonie otwórz `http://IP:8765` w przeglądarce albo w IptvTv: Odbierz listę → 📤 Wyślij listę (urządzenia są wyszukiwane w sieci).
- Menu funkcji ◄◄: 🔊 ścieżka audio, 💬 napisy, 📐 proporcje (Dopasuj / Rozciągnij / Powiększ / 16:9 / 4:3).
- Funkcje → 💾 kopia zapasowa ustawień (eksport i import pliku .json, bez PIN-u).

**Przewodnik EPG** („📅 Program”): siatka kanały × czas, 7 dni wstecz i naprzód; ◄ ► na brzegu przewija czas, przyciski ◄ Dzień / ◄ 1h / Teraz / 1h ► / Dzień ►, gest w bok; OK na programie – menu (archiwum, przypomnienie, inne odcinki, oglądaj kanał), OK na kanale – oglądaj.

**Kreator pierwszej konfiguracji**: uprawnienia → lista → EPG i picony → catch-up → wygląd → uruchamianie (też w Ustawienia → Funkcje).

**Program TV: wyszukiwarka, odcinki, przypomnienia** (przycisk „📅 Program”):
- Wyszukiwanie po tytule programu lub serialu i po **tytule odcinka**, opcjonalnie także w opisach, na wszystkich kanałach. Filtry: Wszystkie / ⟲ Archiwum / ● Teraz / ⏭ Nadchodzące. Wynik z archiwum lub nadawany teraz można od razu włączyć.
- Odcinki rozpoznawane są z `<sub-title>` i `<episode-num>` z XMLTV, a serie po tytule bez dopisków typu „odc. 1856”, „(12)”, „S01E02”, „sezon 2”.
- **Następny odcinek** jest widoczny w opisie programu (lista i panel w odtwarzaczu) oraz w menu programu. Powtórki tego samego odcinka są pomijane.
- **Menu programu** (OK na nadchodzącym programie albo przytrzymanie dowolnego): oglądaj (na żywo / od początku / archiwum), 🔔 przypomnij, 🔁 przypominaj o wszystkich odcinkach (na tym kanale lub wszystkich), ⏭ następny odcinek, 🔎 wszystkie emisje, ℹ szczegóły.
- **Przypomnienia**: okno nad obrazem z przyciskiem „Przełącz na kanał”, na telefonie dodatkowo powiadomienie. Wyprzedzenie od 0 do 15 min (domyślnie 2). Lista i usuwanie w zakładce „🔔 Przypomnienia”. Po restarcie urządzenia przypomnienia planują się ponownie, a reguły serii dodają nowe odcinki po każdej aktualizacji EPG.

**Uruchamianie** (Ustawienia → Funkcje → Uruchamianie):
- *Po włączeniu aplikacji:* lista kanałów, **ostatnio oglądany kanał (domyślnie)**, wybrany kanał, program TV (fokus na bieżącej audycji ostatniego kanału), grupa Ulubione, grupa Ostatnio oglądane, wyszukiwarka, Ustawienia, Edytor. Akcja wykonuje się raz przy uruchomieniu; WSTECZ wraca do listy kanałów.
- *Uruchamiaj aplikację po włączeniu urządzenia.* Na Androidzie 10+ wymaga uprawnienia „Wyświetlanie nad innymi aplikacjami” (aplikacja o nie poprosi). Jeśli urządzenie nie ma takiego ekranu, nadaj je z komputera:
  `adb shell appops set pl.iptv.tv SYSTEM_ALERT_WINDOW allow`

**Uprawnienia przy pierwszym uruchomieniu**: kreator najpierw pokazuje listę, potem pyta o każde uprawnienie z wyjaśnieniem (Zezwól / Pomiń). Później: Ustawienia → Funkcje → 🔐 Uprawnienia aplikacji.
| Uprawnienie | Do czego | Android | Gdy brak ekranu w TV (adb) |
|---|---|---|---|
| Dostęp do plików | lista .m3u, EPG, tło z pamięci lub pendrive'a | 6–10: „Pamięć”, 11+: „Dostęp do wszystkich plików” | `appops set pl.iptv.tv MANAGE_EXTERNAL_STORAGE allow` |
| Powiadomienia | przypomnienia | 13+ | – |
| Bez oszczędzania energii | praca w tle, start, przypomnienia | 6+ | `dumpsys deviceidle whitelist +pl.iptv.tv` |
| Wyświetlanie nad aplikacjami | start po włączeniu, okno przypomnienia | 6+ (wymagane od 10) | `appops set pl.iptv.tv SYSTEM_ALERT_WINDOW allow` |
| Alarmy i przypomnienia | przypomnienia dokładnie o czasie | 12+ | `appops set pl.iptv.tv SCHEDULE_EXACT_ALARM allow` |

(polecenia adb poprzedź `adb shell`)

**Ekran główny i działanie w tle** (Ustawienia → Funkcje):
- Przy pierwszym uruchomieniu aplikacja prosi o wyłączenie oszczędzania energii (systemowe okno „Zezwól”). Bez zgody użytkownika Android na to nie pozwala. Zapasowo z komputera: `adb shell dumpsys deviceidle whitelist +pl.iptv.tv`
- **Tryb launchera**: aplikacja staje się ekranem głównym TV, otwiera się przy każdym włączeniu i po naciśnięciu HOME, a system jej nie zamyka. Pierwsze otwarcie po starcie wykonuje akcję startową, kolejne HOME tylko wracają do listy. W tym trybie pojawia się przycisk „▦ Aplikacje” (inne aplikacje i ustawienia systemu), a WSTECZ na liście otwiera to menu zamiast wychodzić.
- Jeśli telewizor nie pozwala zmienić launchera (Google TV, wiele telewizorów z wbudowanym Android TV), trzeba wyłączyć systemowy launcher przez adb. Aplikacja pokazuje gotowe polecenia z nazwą pakietu:
  `adb shell pm disable-user --user 0 <pakiet>`, przywrócenie: `adb shell pm enable <pakiet>`.
  **Przed wyłączeniem trybu launchera w aplikacji przywróć systemowy launcher.**

**STube** (przycisk „STube” w kolumnie menu głównego): przeglądanie YouTube na TV.
- U góry wyszukiwarka (OK / „🔎 Szukaj” – rząd „Wyniki” na początku, z kafelkiem „Szukaj w SmartTube”), przyciski konta, SmartTube i odświeżania.
- Rzędy: *Dla Ciebie* (polubione z konta; bez logowania – kafelek „Zaloguj się”), *Na czasie*, *Muzyka*, *Na żywo*, *Subskrypcje* (po zalogowaniu). Z kontem lub kluczem API – YouTube Data API v3 (trendy regionu), bez nich albo przy błędzie API – listy publiczne bez klucza (odcinki na czasie, muzyka, transmisje). Dane pamiętane 10 minut; kolejne strony dociągane przy końcu rzędu.
- Pilot: ▲▼ między rzędami (powrót na ostatnio zaznaczony kafelek rzędu), ◄► w rzędzie (na końcach fokus zostaje), ▲ z pierwszego rzędu – wyszukiwarka. OK = wbudowany odtwarzacz (bez reklam, SponsorBlock), długie OK = menu (w aplikacji, w SmartTube, w YouTube, więcej z kanału). Rząd z błędem ma kafelek „Ponów”.
- Duże kafelki 16:9 z czasem filmu; zaznaczenie wg motywu (kolor, ramka, powiększenie).
- Wyszukiwanie działa bez klucza API (nie zużywa limitu), API – zapasowo; na końcu wyników kafelek „Szukaj w SmartTube”.

**Konto YouTube i SmartTube** (Ustawienia → Konto YouTube):
- *Zaloguj* – logowanie bez klawiatury (OAuth 2.0 Device Flow): na ekranie pojawia się duży kod i adres `google.com/device`. Kod wpisujesz na telefonie lub komputerze; aplikacja sama czeka na potwierdzenie (wygasły kod zastępuje nowym, „Anuluj” / WSTECZ przerywa). Dostęp tylko do odczytu (`youtube.readonly`).
- Tokeny są szyfrowane (EncryptedSharedPreferences, klucz w Android Keystore), w osobnym pliku – nie trafiają do kopii zapasowej ustawień. Gdy urządzenie nie ma działającego magazynu kluczy, logowanie obowiązuje do zamknięcia aplikacji. *Wyloguj* odwołuje dostęp także w Google.
- *Źródło filmów*: „Moje” (polubione z konta) lub „Na czasie” (trendy regionu). Bez logowania zawsze listy publiczne.
- *Odtwarzacz STube i SponsorBlock*: wbudowany odtwarzacz (wyłączony = filmy od razu w SmartTube), maksymalna jakość, SponsorBlock i pomijane kategorie.
- *SmartTube*: stan aplikacji; zapasowy odtwarzacz (okno awaryjne, menu kafelka) – wersja stabilna lub beta, a gdy jej brak – okno z linkiem do strony SmartTube lub otwarciem w innej aplikacji.
- *Zaawansowane*: własny identyfikator klienta OAuth (typ „Telewizory i urządzenia z ograniczonym wejściem”), client secret i klucz API YouTube Data v3. Wartości wbudowane: plik `keystore/youtube_oauth.properties` (`client_id`, `client_secret`, `api_key`) przy budowaniu.

**Edytor listy** (przycisk ✎ Edytor): zmiana nazwy, ukrywanie i pokazywanie, kolejność, przenoszenie do grupy (także nowej), mapowanie EPG z wyszukiwarką i podpowiedziami, numer na liście, przywracanie oryginału (jednego kanału lub wszystkich). Dla grup: zmiana nazwy, ukrycie lub pokazanie całej grupy. Zmiany zapisują się od razu i przetrwają odświeżenie listy z linku.

## Sterowanie
| Gdzie | Pilot | Dotyk |
|---|---|---|
| Lista | OK = oglądaj | 1. dotknięcie = program TV, 2. dotknięcie lub „▶ Oglądaj” = oglądaj |
| Lista | długie OK lub MENU = opcje kanału (ulubione, edycja) | przytrzymanie = opcje kanału |
| Program TV | OK na ⟲ = archiwum; na bieżącym: na żywo lub od początku | dotknięcie |
| Program TV (siatka) | ▲▼ natychmiast; ◄ z kolumny kanałów lub ▲ z pierwszego wiersza = górne przyciski; ▼ z przycisków = siatka | przesunięcie |
| Odtwarzacz | ▲▼ / CH± = zmiana kanału, cyfry = numer | przesunięcie w górę/dół |
| Odtwarzacz | ◄ = panel kanałów + program na kilka godzin | przesunięcie w prawo lub „☰ Kanały” |
| Odtwarzacz | ► = okno 1/4 ekranu: ostatnio oglądane, a pod nimi opis oglądanego programu i program kanału (z archiwum ⟲) | przesunięcie w lewo lub „🕘 Ostatnie” |
| Panel kanałów | ◄ (drugi raz) = wąski pasek menu po lewej; ► zamyka; kolejność i widoczność pozycji: Ustawienia → Funkcje. Dawniej: Ustawienia, edytor listy, edytor EPG, opcje kanału, ulubione, wyszukiwarka programu, przypomnienia, odświeżanie, aplikacje; ◄ = zamknij menu / okno | „⋯ Menu” |
| Pasek menu | OK na aplikacji = uruchom ją, przytrzymanie = usuń z menu, „➕ Dodaj aplikację…” | dotknięcie / przytrzymanie |
| Odtwarzacz | OK/INFO = pasek informacji, MENU = opcje kanału | dotknięcie = pasek informacji + przyciski |
| Panel kanałów | ◄ = poprzednia grupa, ► = program (także wcześniejsze audycje: OK na ⟲ = archiwum), WSTECZ = zamknij | ◄ ► w nagłówku, dotknięcie obok = zamknij |
| Archiwum | ◄ ► = przewijanie (sam pasek postępu), 2× ► = pomiń reklamę, OK = pełny panel (fokus na ▶‖) | pasek przewijania |
| Edytor | OK = opcje, „⇅ Przenieś” + ▲▼, OK = koniec | przeciągnij za ≡ |

Kanał trafia do „ostatnio oglądanych” po co najmniej 1 minucie odtwarzania.

**v6.2**:
- **STube** – nowy przycisk w menu głównym: YouTube na TV z wyszukiwarką i rzędami (Dla Ciebie, Na czasie, Muzyka, Na żywo, Subskrypcje). Działa od razu, bez klucza API i konta (listy publiczne); z kontem lub kluczem – dane z YouTube Data API v3 (przy błędzie API, np. wyczerpanym limicie, zapasowo listy publiczne).
- **Wbudowany odtwarzacz YouTube**: film gra w aplikacji (Media3), bez reklam – strumienie pobierane bezpośrednio (NewPipeExtractor), do 1080p (Ustawienia → Konto YouTube → Maksymalna jakość; tryb słabszych urządzeń – jego limit), H.264 przed VP9, dźwięk oryginalny AAC. Pilot: OK = pauza, ◄► = 10 s, WSTECZ = koniec; HOME zatrzymuje film i zwalnia dekodery (powrót – ta sama chwila). Kanał IPTV jest zatrzymywany na czas filmu.
- **SponsorBlock**: automatyczne pomijanie segmentów sponsorowanych, autopromocji, próśb o subskrypcję itp. (kategorie w Ustawieniach), komunikat „Pominięto: …”. Zapytanie bez ID filmu (4 znaki skrótu SHA-256).
- **Okno awaryjne**: gdy film nie da się odtworzyć (blokada, ograniczenie wiekowe/regionalne, błąd strumienia, brak odpowiedzi 25 s) – od razu okno z przyciskami *Odtwórz w SmartTube*, *Odtwórz w YouTube* (aplikacja TV/mobilna lub przeglądarka), *Zamknij*. Wygasły adres strumienia (403/410) – najpierw jedna automatyczna ponowna ekstrakcja od tej samej chwili.
- **Konto YouTube**: logowanie kodem (OAuth Device Flow), szyfrowane tokeny; **SmartTube**: wykrywanie, wyszukiwanie, odtwarzanie linków – szczegóły w sekcji *Konto YouTube i SmartTube*.
- **Silnik odtwarzania kanałów**: zawieszony strumień najpierw otwierany ponownie, a po 3 próbach w 90 s – nowy odtwarzacz (to samo, co dawała ręczna zmiana silnika); zamrożony obraz przy grającym dźwięku (ok. 8 s) → dekoder programowy do końca sesji (Ustawienia → Odtwarzanie → *Dekoder programowy, gdy obraz zamarza*); połączenia poprzedniego kanału przerywane od razu przy zmianie (limit połączeń u dostawcy); łączenie z adresem serwera 4 s (martwy adres z rotacji DNS nie zatrzymuje obrazu); ten sam kanał wybrany ponownie, gdy buforuje > 6 s – start od nowa; więcej prób przy błędach dostawcy (2, 4, 6, 8 s).
- Wersja testowa (secure) – bez przycisku STube.
- Licencja: ekstraktor YouTube (NewPipeExtractor) jest na licencji GPLv3.

**v6.1**:
- **Motywy**: zostały motywy podstawowe oraz iOS – szkło i Xbox Series X – konsola; usunięte PlayStation 5, Windows 11 i MagicOS (kto ich używał, dostaje odpowiednio Xbox, Nowoczesny, iOS). Style paska informacji i przejścia z tamtych motywów zostają jako zwykłe opcje (Pływająca karta, Panel z paskiem akcentu, Pasek z poświatą; Unoszenie, Przyciąganie, Wejście w kartę).
- **Zmiana motywu od razu**: ekran Ustawień przebudowuje się po zamknięciu wyboru motywu, a pozostałe otwarte ekrany (menu, przewodnik, wyszukiwarka…) przy powrocie; odtwarzacz odświeża pasek informacji i pasek archiwum bez przerywania obrazu. Bez ponownego uruchamiania aplikacji.
- **Oryginalne logo kanałów**: logo nigdy nie dostaje koloru motywu (bez zabarwienia, filtrów i przezroczystości – także w pasku informacji, pasku archiwum i przy wpisywaniu numeru); pełna paleta kolorów także na słabszych urządzeniach (bez RGB565).
- **Odtwarzanie na żywo**: strumienie TS jak w zewnętrznych odtwarzaczach (klatki kluczowe nie-IDR, wykrywanie jednostek AU, DTS) – kanały, które nie startowały lub pokazywały czarny obraz, ruszają; HLS z tymi samymi ustawieniami i szybkim startem; adres bez rozszerzenia, który okazuje się listą HLS (np. Xtream), jest automatycznie odtwarzany jako HLS i zapamiętany; osobny klient HTTP strumieni (połączenie 12 s, odczyt 20 s – zawieszony strumień wykrywany szybciej, bez limitu całego żądania, pula połączeń na 5 min); start po 2 s zapasu i 5 s po przycięciu (mniej serii krótkich zatrzymań), bufor 30–60 s.
- **Okna**: pasek przycisków bez paska przewijania i bez przycinania powiększonego przycisku; w przewijanej treści pasek przewijania rysowany obok treści, a zaznaczony element przewijany do widoku z zapasem – przyciski zawsze widoczne i dostępne pilotem.
- **Launcher i start po włączeniu**: tryb launchera rozpoznaje pierwsze wejście po włączeniu urządzenia po numerze rozruchu (akcja startowa raz na rozruch, także gdy system zamknął aplikację w tle); gdy aplikacja jest domyślnym ekranem głównym, start po włączeniu nie otwiera jej drugi raz; włączony tryb launchera (bez ustawienia jako domyślny) też otwiera aplikację po włączeniu.

**v6.0**:
- **Motywy systemowe dopracowane**: iOS – karty o promieniu 22 dp i miękki, świecący biały pierścień zaznaczenia; MagicOS – neonowo-błękitny akcent (#2EC7FF) i cienka błękitna krawędź zaznaczonej karty; Windows 11 – zaokrąglenie 8 dp (jak Fluent); Xbox – zaznaczenie w kolorze #107C10: gruba ramka z wewnętrzną jasną linią (wysoki kontrast na ciemnym tle).
- **Przycinanie elementów**: każdy element list i kafelek (nie tylko panele) przycina treść do swojego zaokrąglonego kształtu – logo, miniatury i tła nie wychodzą poza kafelek w żadnym motywie (wyjątek: tag `noclip`).
- **Logo kanałów**: dłuższe limity (40 s połączenie / 75 s odczyt / 180 s całość), 3 ponowienia żądania, trzecia runda prób po 45 s; do czasu wczytania od razu inicjały kanału (bez pustego kafelka).

**v5.9**:
- **Przycinanie do ramek** (wszystkie motywy): panele i kafelki przycinają treść do swojej zaokrąglonej ramki – powiększony element, logo, podgląd kanału i kafelki szybkiego menu nie wychodzą poza krawędzie. Logo kanałów w zaokrąglonym kafelku (ostrym w Xbox). Ustawienia: powiększony wiersz nie wchodzi na kolumnę kart.
- **Pasek informacji wg motywu** (Wygląd → Pasek informacji → Styl, domyślnie wg motywu): MagicOS – pływająca karta; iOS – szklana kapsuła z większą nazwą kanału; Windows 11 – panel miki z paskiem akcentu; PS5 – szeroki pasek z niebieską poświatą u góry; Xbox – ostry pasek z linią limonki. Logo w kafelku w kształcie motywu. Pasek archiwum korzysta z tego samego stylu (style pływające odsunięte od krawędzi).
- **Odtwarzacz archiwum**: przyciski w kształcie motywu (kapsuła / prostokąt / ostre z limonkową ramką, poświata w PS5) i wyraźne powiększenie zaznaczonego; ▲ z przycisków przechodzi na oś czasu (◄ ► przewijają, ▼ wraca do ▶‖); przytrzymany ◄ ► przewija coraz szybciej (krok ×2, ×4, ×8); podpowiedź pokazuje przesunięcie i godzinę, w którą trafi przewijanie (np. „⏩ +1:30 → 20:47”).
- **Menu główne**: listy bez animacji zmian elementów i ze stałym rozmiarem – szybsza, płynniejsza nawigacja pilotem (odświeżenie programu nie opóźnia fokusu).

**v5.8**:
- **Motywy zmieniają geometrię, nie tylko kolory** (wspólny opis motywu: powiększenie zaznaczenia, nagłówki, ramki okien, kształt ikon, rozmycie, przejścia):
  - **MagicOS – karty**: duże zaokrąglenia (okna 24 dp), lekko rozmyte tło pod oknami, okrągłe kafelki ikon, przejście „unoszenie” (ekran wpływa lekko od dołu).
  - **iOS – szkło**: duże, pogrubione nagłówki (Ustawienia, okna), wyśrodkowany tytuł okna, okna o zaokrągleniu 22 dp, sprężyste powiększenie i panele, przejście „sprężyna” (nowy ekran wsuwa się z prawej, poprzedni odsuwa się z efektem paralaksy).
  - **Windows 11 – mika**: okna z paskiem akcentu u góry i zaokrągleniem 8 dp, akryl (rozmycie), bez powiększania elementów, przejście „przyciąganie” (szybkie pojawienie z lekkim przesunięciem).
  - **PlayStation 5 – konsola** (nowy): układ **kart poziomych** w menu głównym, matowe szkło z niebieską ramką, zaznaczenie – jasna karta z niebieską poświatą, mocne powiększenie zaznaczonej karty (do 9%), okrągłe ikony, przejście „wejście w kartę”.
  - **Xbox Series X – konsola** (nowy): karty poziome, ostre krawędzie (2 dp), zaznaczenie – gruba ramka w kolorze limonki, okna z paskiem akcentu, szybkie przejścia.
  - Przejścia do wyboru także ręcznie (Wygląd → Animacje): unoszenie, sprężyna, przyciąganie, konsola PS5, konsola Xbox.
- **Układ „Karty poziome (konsola)”** (Wygląd → Układ menu; ustawiany przez motywy PS5/Xbox): u góry przyciski, kategorie, podgląd i program; na dole przewijany rząd kart kanałów z dużym logo. ◄ ► – karty (◄ z pierwszej – kategorie), ▲ – program kanału.
- **Przytrzymanie OK**: w szybkim menu ikon nowy kafelek **„⚡ Silnik i akceleracja”** (domyślnie widoczny); OK otwiera okno ze zmianą silnika odtwarzania, akceleracji sprzętowej i kolejki dekodera. Ustawienie „Przytrzymanie OK / ekranu” pozwala otwierać to okno od razu.
- **Logo kanałów**: wielopoziomowo – pamięć → dysk → sieć (limity 25 s połączenie / 45 s odczyt, 2 ponowienia przy błędzie sieci, 5xx i 429, nagłówek User-Agent) → kolejne źródła (tvg-logo, ikona z EPG) → ponowienie po 4 s i 15 s → **inicjały kanału** na kolorowym kafelku. Adresy, które zawiodły, są pomijane przez 10 min (od razu następne źródło). Każdy kanał ma zawsze obraz.

**v5.7**:
- **Nowe motywy** (Ustawienia → Wygląd → Motyw), inspirowane systemami, z pełną obsługą pilota i dotyku: **MagicOS – karty** (czyste karty o dużych zaokrągleniach, zaznaczenie pełnym kolorem), **iOS – szkło** (półprzezroczyste warstwy z podświetloną krawędzią, sprężyste animacje paneli i powiększenia, rozmyte tło pod oknami na Androidzie 12+), **Windows 11 – mika** (tło z fakturą miki i odcieniem akcentu, akryl w oknach, zaznaczenie ramką w kolorze akcentu). Gradienty bez pasm (dithering), wspólna faktura szumu dla miki i szkła.
- **Przytrzymanie OK – menu odtwarzania**: silnik odtwarzania (sprzętowy / programowy / tunelowanie), **akceleracja sprzętowa** wł./wył., kolejka dekodera i podgląd dekodera – zmiana działa od razu (kanał startuje z nowymi ustawieniami, archiwum wraca w to samo miejsce). Z menu przejście do szybkiego menu ikon. Ustawienia → Odtwarzanie: „Przytrzymanie OK / ekranu” (menu odtwarzania albo szybkie menu ikon), „Akceleracja sprzętowa obrazu”; pozycja „⚡ Silnik i akceleracja” do włączenia w szybkim menu ikon.
- **Wyniki bez spoilerów** (Ustawienia → Wyniki → „Ukryj wyniki”): w zakładce wyników „?:?”, przy kanałach i w powiadomieniach same drużyny i minuta; wynik meczu odsłania „👁 Pokaż wynik” w jego szczegółach.
- **Powiadomienia o golach**: wszystkie nowe bramki po kolei (wcześniej tylko pierwsza), wjazd z góry i płynne zniknięcie, nad panelami; kolejny gol nie znika przedwcześnie. Gole wykrywane przy każdym pobraniu wyników (menu główne, przewodnik, odtwarzacz) – nie giną, gdy wyniki pobrał inny ekran; ten sam gol nie pojawia się dwa razy. Chwilowy błąd jednej ligi nie usuwa jej meczów.
- **Wyniki na żywo w EPG**: w siatce przewodnika przy trwającej audycji z meczem i w jej opisie, w liście programu kanału (zamiast „NA ŻYWO”); przewodnik sam odświeża wyniki. Wynik pojawia się też, gdy program TV wczytano po pobraniu wyników.
- **Logo kanałów**: trwała pamięć logo na dysku (160 MB) niezależnie od nagłówków serwera, wspólny klient HTTP, przy szybkim przewijaniu logo wczytywane dopiero po zatrzymaniu wiersza, bez mignięcia logo poprzedniego kanału; doczytane logo nie przelicza układu całej listy (płynniejsze przewijanie).
- **Ustawienia**: rozwijanie/zwijanie sekcji bez przebudowy całej karty; zwinięte sekcje stylowane dopiero przy rozwinięciu; zmiana wyglądu nakłada motyw raz (szybsze przełączanie kart i przewijanie).
- **Czarny ekran przy przejściach** (ustawienia, menu, przewodnik): ekran pod spodem nie zanika razem z nowym (w połowie animacji prześwitywała czerń); tło okna od pierwszej klatki w kolorze motywu; zdjęcie tła menu w pamięci (bez ciemnej klatki przed wczytaniem).
- **Okna**: płynne otwieranie (lekkie powiększenie), na telefonie/tablecie najwyżej 560 dp szerokości, wysokość pilnowana także po odświeżeniu treści (przyciski zawsze widoczne), klawiatura nie zasłania pól, okno nad odtwarzaczem nie wywołuje pasków systemu; płynne przejście zaznaczenia w listach.

**v5.6 / Admin 2.4**:
- **Silnik odtwarzania** (Ustawienia → Odtwarzanie → Silnik odtwarzania i akceleracja): Media3 z dekoderem sprzętowym (zalecany, dekodery sprzętowe zawsze przed programowymi), z dekoderem programowym (zgodność, gdy obraz jest zielony/czarny) albo z tunelowaniem (Android TV: obraz i dźwięk synchronizowane sprzętowo). Do tego kolejka dekodera (automatycznie / asynchroniczna / synchroniczna), zapasowy dekoder przy błędzie, „Niskie opóźnienie na żywo” (start po 0,5 s, bliżej bieżącej chwili transmisji) i podgląd bieżącego dekodera.
- **Tryb dla słabszych urządzeń – edytowalne parametry**: maks. rozdzielczość, bufor (min./maks., pamięć), czyszczenie dekodera przy zmianie kanału, opóźnienie startu po ▲▼, dni programu TV naprzód, długość opisów, szybkość pobierania EPG podczas oglądania; „↺ Przywróć domyślne parametry”.
- **Lista kanałów po OK**: u góry ramka z bieżącą kategorią; ◄ ► zmienia kategorię i od razu zaznacza jej pierwszy kanał (wystarczy OK). Panel ◄: ◄ ► na nagłówku kategorii też zaznacza pierwszy kanał, kolejne ◄ ► dalej zmieniają kategorię.
- **Menu główne**: OK na kategorii (pilot) od razu przenosi do jej pierwszego kanału.
- Nazwy list i nagrań w Ustawieniach nie są ucinane (np. „[PL] Sport”, „|VIP| Filmy” – wcześniej znikał pierwszy znak).
- Telefon: Ustawienia nie obracają się na chwilę do poziomu przy otwieraniu w pionie; w pionie wiersze, podgląd i sekcje mieszczą się w szerokości ekranu (bez wystawania treści).
- To samo okno nie otwiera się dwa razy naraz (podwójne naciśnięcie / dotknięcie).
- Raport do administratora zawiera kod aktywacji i listy zapisane na urządzeniu; nowe polecenie zdalne „use_list” (zmiana aktywnej listy).
- **D-iptv Admin 2.4**: sekcja „🏠 Moje urządzenia”; urządzenie dodane w Aktywacjach do „Moich urządzeń” pojawia się od razu (jako ⏳ oczekujące do pierwszego raportu), a znacznik 🏠 jest widoczny zaraz po potwierdzeniu. Urządzenia opisane jako „Nazwa: KOD” (np. „Salon: K7QF-3M9X”). W menu urządzenia: „📋 Wybierz aktywną listę…” (listy zapisane na urządzeniu) i „📚 Wyślij zapisaną listę…”; przycisk „📚 Listy” – zapisane listy (nazwa, link, EPG, data ważności) do szybkiego wysyłania.

**v5.5**: płynniejsze przełączanie ▲▼ na słabszych urządzeniach (projektory, tanie przystawki): szybkie naciśnięcia pomijają kanały pośrednie (od razu widać nazwę, obraz startuje po zatrzymaniu), po kanale z błędem lub nieuruchomionym dekoder jest czyszczony przed następnym, ograniczona liczba ponowień przy wadliwym strumieniu (zaległe ponowienia nie przeszkadzają kolejnemu kanałowi). Gdy problem dotyczy jednego urządzenia (np. projektora), włącz na nim Ustawienia → Odtwarzanie → „Tryb dla słabszych urządzeń” – dekoder jest wtedy czyszczony przy każdej zmianie kanału.

**v5.4 / Admin 2.3**: nowy układ Ustawień – karty: Listy, Wygląd, Menu i kanały, Odtwarzanie, Program TV, Archiwum, Kontrola rodzicielska, Nagrywanie, Wyniki i pogoda, Pilot, System (połączone pokrewne sekcje). Czytelna instrukcja obsługi (tabela: przycisk – działanie). Skróty aplikacji z menu bocznego także w menu głównym. Nowy pasek archiwum (logo, plakietka, godzina oglądanego momentu, kolory motywu). Edytor: 🚫 przy ukrytych kategoriach; ukrycie kategorii działa od razu w menu i panelu. Panel ◄: nagłówek kategorii (▲ z pierwszego kanału, ◄ ► zmiana, OK – lista kategorii, CH± – następna kategoria, płynna animacja). PiP: okienko zamyka się po powrocie do aplikacji. Audio i napisy: synchronizacja dźwięku (±1000 ms, też w Ustawienia → Odtwarzanie → Dźwięk). Admin: „🏠 Dodaj do Moich urządzeń” przy aktywacji – urządzenie samo zaczyna wysyłać raporty (kod administratora przekazany zaszyfrowany tylko dla tego urządzenia).

**v5.3 / TEST 1.2 / Admin 2.2**: okna nie wyższe niż ekran (przyciski zawsze widoczne); kursor: pole tekstowe → pierwsza pozycja listy → „OK”; kody administratora i urządzeń z automatycznymi myślnikami. Płynniejszy powrót z oglądania do menu (bez przebudowy ekranu) i płynniejsze panele. „🛠 Ustawienia urządzenia” w menu bocznym, szybkim menu (opcjonalnie) i Ustawienia → Funkcje. Kreator dopasowany do telefonu. Słabsze urządzenia: większa pamięć dla aplikacji, krótsze opisy w EPG, pobieranie EPG poza oglądaniem, ochrona przed pętlą awarii przy zbyt dużym EPG (po awarii tryb oszczędny, po dwóch – bez automatycznego wczytywania).

**v5.2 / TEST 1.1**: aktualizacje odporne na chwilowe błędy serwera GitHub (np. HTTP 504): ponowienia, a gdy strona github.com nie odpowiada – pobranie pliku z wydania przez API GitHub; czytelny komunikat.

**v5.1 – aktywacja wersji pełnej**: D-iptv Player (pakiet `pl.iptv.tv`, plik `D-iptv-Player.apk`, wydania repozytorium iptvtv) przy pierwszym uruchomieniu pokazuje zgodę i kod urządzenia; działa po zaznaczeniu „⭐ Pełna” w D-iptv Admin 2.1 → 🔐 Aktywacje. Bez sprawdzenia aktywacji działa 30 dni. Kopia danych aplikacji wyłączona. Wersja testowa (🧪) aktywowana osobno – ten sam kod urządzenia, osobne pole na liście.

**Wersja zabezpieczona (D-iptv Player, pakiet `pl.iptv.dplayer`, plik `DPlayer.apk`)**: osobna aplikacja dla testerów. Przy pierwszym uruchomieniu ekran zgody (co wysyła aplikacja) i kod urządzenia; działa dopiero po aktywacji w D-iptv Admin → 🔐 Aktywacje. Blokada w każdej chwili, bez internetu działa 3 dni, kopia danych wyłączona, bez zdalnych poleceń. Lista aktywacji: podpisany plik `activation.json` w repozytorium `daniel01l/dplayer`; aktualizacje: wydania tego repozytorium (`dplayer.json` + `DPlayer.apk`). Budowanie: `./gradlew :app:assembleSecureRelease` (klucz publiczny w `keystore/activation_pub.txt`); wersja pełna: `:app:assembleFullRelease`.

**v5.0**: instrukcja obsługi przy pierwszym oglądaniu kanału (osobno pilot i ekran dotykowy; ponownie: Ustawienia → Funkcje → ❓ Instrukcja obsługi). Czytelniejsze nazwy opcji (np. „Menu boczne odtwarzacza (2× ◄)”, „Szybkie menu ikon (przytrzymaj OK lub ekran)”, „Lista kanałów po naciśnięciu OK”). Telefon w pionie: Program TV jako lista (kanały u góry, program dnia, ◄ ► dni), Ustawienia z kartami w pasku u góry, wyszukiwarka programu dopasowana do ekranu. Wynik meczu na żywo przy kanale z transmisją (lista kanałów, panel ◄, lista po OK). TV i poziom bez zmian.

**v4.8**: panel ◄ – sama nazwa kanału nad programem. Edytor listy: „⇅ Kategorie” (kolejność i widoczność kategorii, także „Wszystkie”; przytrzymanie kategorii – te same opcje). Telefon: dotknięcie ekranu pokazuje pasek informacji i listę kanałów; w pionie – przyciski u góry jako ikony, mniejszy pasek informacji, kategorie o stałej szerokości z nazwami, mniejsze logo na liście, obraz w tle ustawień bez powiększania.

**v4.7**: wyniki – automatyczne wyszukiwanie meczu w programie TV: pełne nazwy drużyn i polskie odpowiedniki (np. Real Madryt, Bayern Monachium), tytuł, podtytuł i opis audycji, odmiana nazw, przed meczem – audycja w godzinie rozpoczęcia. W oknie meczu lista „Gdzie oglądać” i przycisk „📺 Oglądaj”; gdy nic nie znaleziono – „🔎 Szukaj w programie”.

**v4.6**: menu funkcji ◄◄ w odtwarzaczu – na końcu „✎ Edytuj menu…”: okno z widocznością i kolejnością pozycji (OK na pozycji: pokaż/ukryj, wyżej, niżej, na początek, na koniec; „↺ Domyślne”).

**v4.5**: okna wyboru w Ustawieniach nie zmieniają rozmiaru po zaznaczeniu opcji. Kopia zapasowa w kreatorze wczytuje ustawienia, listę i EPG od razu (bez restartu; odporna na pliki z BOM). Program TV: stała wysokość opisu, WSTECZ (lub MENU, lub ◄ na kanale) przenosi do przycisków u góry, CH± przewija stronę, cyfry przeskakują do kanału. Menu: pod kategoriami „✎ Edytuj” – widoczność i kolejność kategorii (kanały ukrytej kategorii zostają w „Wszystkie”).

**v4.4**: kreator (krok 2) – „💾 Wczytaj kopię ustawień z pliku…” pod opcjami zdalnymi; po wczytaniu aplikacja uruchamia się z ustawieniami z kopii. Bez listy kanałów na środku menu jest „+”, który otwiera Ustawienia → Listy.

**v4.3**: lista kanałów po OK przewija się płynnie o pełne wiersze – pierwszy i ostatni kafelek zawsze w całości (także przy przytrzymaniu ▲▼).

**v4.2**: poprawione sprawdzanie podpisu przy automatycznej aktualizacji (część Android TV nie odczytywała podpisu pobranego pliku i zgłaszała „Inny podpis”). Gdy podpis naprawdę się różni, komunikat mówi, co zrobić.

**v4.1**: nowa nazwa **D-iptv Player** i ikona (litera D z przyciskiem odtwarzania, baner TV). Lista kanałów po OK na środku ekranu – same kafelki, znika po 3 s bez ruchu. Mini menu mieści się w pionie (kilka rzędów), zaznaczona ikona nie jest ucinana. Wyniki: poprawione godziny meczów, herby także w powiadomieniu „GOL!”, nowe rozgrywki 🎯 Dart (PDC, WDF/BDO – wyniki ostatniego dnia turnieju i najbliższy turniej, dane TheSportsDB). D-iptv Admin 1.4.

**v4.0**: OK podczas oglądania = pasek informacji i nad nim lista kanałów z bieżącym programem (▲▼ wybór, OK przełącza; liczba wierszy: Ustawienia → Funkcje). Okna dialogowe w stylu paska informacji, kursor od razu na „OK”, listy w oknach nie są ucinane. Ikony motywu w menu i mini menu. Tryb dla słabszych urządzeń (automatycznie przy małej ilości RAM): maks. 1080p, mniejszy bufor, krótszy EPG, spokojniejsze pobieranie EPG podczas oglądania. Kilka adresów EPG (oddzielone spacją); domyślnie w kreatorze https://epg.ovh/plar.xml i https://epg.ovh/pltv.xml. Zmiana listy zachowuje EPG. Kreator: lista zdalnie przez Wi-Fi, Internet lub IptvTv Admin. Aktualizacje zawsze z wydań GitHub (bez wpisywania adresu). Telefon w pionie: panel ◄ kanały nad programem, wyniki przyciskiem „⚽ Wyniki” lub przesunięciem. IptvTv Admin 1.3: wysłana lista od razu aktywna (EPG opcjonalne, puste = zostaje), token tylko jako notatka pod nazwą urządzenia.

**v3.10**: zakładka wyników wysuwa się od prawej; wiersze z herbami drużyn, wynikiem w ramce w kolorze motywu (na żywo – czerwona). W oknie meczu „➕ Kanały” – przypisanie kanałów z listy do drużyny lub ligi; „📺 Przełącz na mecz” używa kanału z programu TV (dopasowanie nazw drużyn) albo przypisanych kanałów. W Wyglądzie: informacja o ponownym uruchomieniu po zmianie motywu i przycisk „🔄 Uruchom aplikację ponownie teraz”.

**⚽ Wyniki (v3.9)**: podczas oglądania ► (ostatnio oglądane), drugie ► = zakładka z dzisiejszymi meczami obserwowanych lig i drużyn (na żywo z minutą, nadchodzące, zakończone, strzelcy); ◄ wraca. OK na meczu: szczegóły (bramki, kartki) i „📺 Oglądaj”, gdy EPG wskazuje kanał z transmisją. Powiadomienie „⚽ GOL!” nad obrazem. Ustawienia → Wyniki: obserwowane ligi/drużyny, odświeżanie, klucz API-Football. Źródło domyślne ESPN (bez klucza; brak polskich lig) – Ekstraklasa, 1 Liga i Puchar Polski wymagają klucza API-Football (płatny plan; darmowy nie obejmuje bieżącego sezonu).

**Odtwarzacz (v3.7)**: cyfry pilota pokazują na środku ekranu numer, logo i nazwę kanału (ramka w kolorach motywu). Długie OK albo przytrzymanie ekranu = mini menu ikon (⚙ Ustawienia, 📅 Program TV, ℹ Opis programu, ★ Ulubione, ⏺ Nagrywanie, 🎧 Audio i napisy; opcjonalnie 📐 Proporcje, ☰ Lista kanałów), podpis pod zaznaczoną ikoną, znika po 3 s; kolejność i widoczność: Ustawienia → Funkcje → Mini menu. Tam też „➕ Dodaj skrót aplikacji…” – dowolna aplikacja z urządzenia jako ikona w mini menu (OK na skrócie: kolejność, ukrycie, „✕ Usuń skrót”). **Telefon pionowo**: Ustawienia → Wygląd → Orientacja ekranu (Poziomo / Automatycznie / Pionowo – menu pionowe, odtwarzacz z obrotem). Podgląd wyglądu w ustawieniach przewija się razem z opcjami. Listy przycinają elementy na swojej krawędzi.

**Menu główne (v3.6)**: przyciski 📅 Program, 🔍 Kanały, ✎ Edytor, ⚙ Ustawienia w kolumnie po lewej (◄ z kategorii, ► wraca); nazwa kanału na środku pod podglądem; zegar z datą i pogodą (Ustawienia → Pogoda: miejscowość, dane Open-Meteo, co 30 min). Układy menu (Ustawienia → Wygląd → Układ menu): Klasyczny, Podgląd na środku (kanały | podgląd | program), Duży podgląd, Szeroka lista kanałów. W panelu ◄ podczas oglądania: ◄ z programu wraca na kanał, z którego weszliśmy.

**Stabilność odtwarzania (v3.5)**: wspólny silnik sam wznawia transmisję po błędach (do 5 prób z rosnącą przerwą, „za oknem na żywo” → powrót do bieżącej pozycji), restartuje przy buforowaniu >15 s i przy zamrożonym obrazie. Siatka programu TV używa ponownie komórek (brak zawieszeń przy długim przeglądaniu).

## Licencje czcionek
Lato, Ubuntu, PT Serif, Bebas Neue, VT323, Press Start 2P, Lobster, Pacifico – SIL Open Font License / Ubuntu Font Licence (Google Fonts).

## Budowanie i testy
```
echo "sdk.dir=/ścieżka/do/android-sdk" > local.properties
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # 66 testów (w tym aplikacja admina): parsery, catch-up, edycje oraz ekrany (Robolectric)
```
APK jest podpisany kluczem debug (do instalacji ręcznej). Działa od Androida 5.0.

## Struktura
```
BootReceiver – start po włączeniu urządzenia
HomeActivity – tryb launchera (ekran główny)
reminders/ – planowanie (AlarmManager), odbiornik, okno przypomnienia
data/Series, data/Reminders – rozpoznawanie odcinków, przypomnienia i reguły serii
ui/EpgSearchActivity, ui/ProgrammeActions – wyszukiwarka programu, menu programu
ui/PermissionWizard – kreator uprawnień
ui/SystemTools – oszczędzanie energii, launcher, lista aplikacji
ui/SmartTubeIntegrationHelper – wykrywanie SmartTube, wyszukiwanie, odtwarzanie linków, fallbacki
ui/YouTubeAccountUi – okno logowania YouTube (kod, odpytywanie), wylogowanie
ui/STubeActivity, STubeScreen – ekran STube (wyszukiwarka, rzędy kafelków, nawigacja pilotem)
ui/YtPlayerActivity, YtMedia – wbudowany odtwarzacz YouTube (Media3, zapytania do serwerów wideo), okno awaryjne
data/YtStreams, YtKeyless, SponsorBlock – strumienie bez reklam i listy bez klucza (NewPipeExtractor), pomijanie segmentów
data/YouTubeAuth, YouTubeTokens, YouTubeRepository – OAuth Device Flow, szyfrowane tokeny, YouTube Data API v3
data/  StartActions, Models, M3uParser, XmltvParser, CatchupUrlBuilder, ChannelEdits (edycje + JSON), Repository, Prefs
ui/    Ui (motywy, styl, animacje), ProgView (pasek postępu), BaseActivity (tło, przejścia),
       MainActivity, PlayerActivity (panele, gesty), EditorActivity, SettingsActivity (3 zakładki),
       Dialogs + ChannelActions (wspólne okna edycji), Adapters
```
Styl jest nakładany w kodzie na podstawie tagów w layoutach (`panel`, `item`, `field`, `text2`, `accent`, `keepcolor`).

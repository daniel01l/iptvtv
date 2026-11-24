package pl.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {
    private lateinit var sp: SharedPreferences
    private var weakDevice = false
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        weakDevice = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            am.isLowRamDevice || (mi.totalMem in 1 until 2_200_000_000L)
        }.getOrDefault(false)
    }

    /** Tryb dla słabszych urządzeń (domyślnie: mało pamięci RAM). */
    var lowPerf: Boolean get() = if (sp.contains("low_perf")) bool("low_perf", false) else weakDevice; set(v) = putBool("low_perf", v)

    // ---- parametry trybu dla słabszych urządzeń (edytowalne w Ustawienia → Odtwarzanie → Wydajność) ----
    /** Maks. wysokość obrazu (0 = bez limitu). */
    var lowMaxHeight: Int get() = int("low_max_h", 1080); set(v) = putInt("low_max_h", v)
    /** Bufor: minimum i maksimum w sekundach. */
    var lowBufMinS: Int get() = int("low_buf_min", 15); set(v) = putInt("low_buf_min", v)
    var lowBufMaxS: Int get() = int("low_buf_max", 30); set(v) = putInt("low_buf_max", v)
    /** Limit pamięci bufora (MB). */
    var lowBufMb: Int get() = int("low_buf_mb", 24); set(v) = putInt("low_buf_mb", v)
    /** Program TV naprzód (dni). */
    var lowEpgAhead: Int get() = int("low_epg_ahead", 3); set(v) = putInt("low_epg_ahead", v)
    /** Najdłuższy opis programu (znaki). */
    var lowDescLen: Int get() = int("low_desc", 300); set(v) = putInt("low_desc", v)
    /** Pobieranie EPG podczas oglądania (kB/s). */
    var lowEpgKbps: Int get() = int("low_epg_kbps", 600); set(v) = putInt("low_epg_kbps", v)
    /** Opóźnienie startu kanału po ▲▼ (ms). */
    var lowZapDelay: Int get() = int("low_zap_ms", 450); set(v) = putInt("low_zap_ms", v)
    /** Czyszczenie dekodera przy każdej zmianie kanału. */
    var lowCleanDecoder: Boolean get() = bool("low_clean", true); set(v) = putBool("low_clean", v)
    /**
     * (7.17) Opcje przełączania kanałów dla każdego urządzenia (sekcja „Silnik odtwarzania i akceleracja”): opóźnienie startu po
     * ▲▼ – domyślnie 250 ms, w trybie słabszych urządzeń 450 ms (albo dawne [lowZapDelay]); czyszczenie dekodera – domyślnie
     * jak dawniej (tylko w trybie słabszych urządzeń).
     */
    var zapDelayMs: Int get() = optInt("zap_ms") ?: if (lowPerf) lowZapDelay else 250; set(v) = putInt("zap_ms", v)
    var cleanDecoderOnZap: Boolean get() = if (has("clean_dec")) bool("clean_dec", false) else lowPerf && lowCleanDecoder; set(v) = putBool("clean_dec", v)
    /** (7.17) Bufor Live TV dla każdego urządzenia (s; 0 – automatyczny: start 1 s, po przycięciu 2 s, do 60 s). */
    var bufMinS: Int get() = int("buf_min", 0); set(v) = putInt("buf_min", v)
    /** (7.17) Dopasowanie odświeżania ekranu do klatek kanału (AFR) – domyślnie wyłączone. */
    var autoFrameRate: Boolean get() = bool("afr", false); set(v) = putBool("afr", v)
    /**
     * (7.20) Powierzchnia obrazu Live TV ([pl.iptv.tv.ui.RenderSurfaceMode]): 0 – automatycznie (SurfaceView, TextureView dla
     * kanałów w trybie zgodności), 1 – zawsze SurfaceView, 2 – zawsze TextureView (błędy usuwania przeplotu 1080i50 na części
     * TV z Google TV). Dawny przełącznik `tex_view` = wł. → 2.
     */
    var surfaceMode: Int get() = optInt("surface_mode") ?: if (bool("tex_view", false)) 2 else 0; set(v) = putInt("surface_mode", v)
    /** (7.20) Wyrównywanie głośności kanałów (LoudnessEnhancer na sesji dźwięku odtwarzacza, łagodne podbicie) – domyślnie wył. */
    var loudnessLeveling: Boolean get() = bool("loudness", false); set(v) = putBool("loudness", v)
    /** (7.20) Strażnik zrzucanych klatek – klatkujący kanał sam przechodzi na tryb zgodności (zapamiętany dla kanału). */
    var dropWatchdog: Boolean get() = bool("drop_watchdog", true); set(v) = putBool("drop_watchdog", v)

    // ---- silnik odtwarzania ----
    /** 0 = Media3 sprzętowy (domyślny), 1 = Media3 programowy (zgodność), 2 = Media3 tunelowanie (Android TV). */
    var playerEngine: Int get() = int("engine", 0); set(v) = putInt("engine", v)
    /** Kolejka dekodera: 0 = automatycznie (system), 1 = asynchroniczna (szybsza na nowszych urządzeniach), 2 = synchroniczna (gdy obraz zamarza). */
    var asyncDecoder: Int get() = int("async_dec", 0); set(v) = putInt("async_dec", v)
    /** Niskie opóźnienie na żywo: mniejszy bufor startowy i bliżej krawędzi transmisji. */
    var lowLatency: Boolean get() = bool("low_latency", false); set(v) = putBool("low_latency", v)
    /** Zapasowy dekoder, gdy sprzętowy się nie uruchomi. */
    var decoderFallback: Boolean get() = bool("dec_fallback", true); set(v) = putBool("dec_fallback", v)
    /** Zamrożony obraz na dekoderze sprzętowym (dźwięk gra) – automatycznie dekoder programowy do końca sesji. */
    var freezeFallback: Boolean get() = bool("freeze_fallback", true); set(v) = putBool("freeze_fallback", v)
    /** Dobór silnika dla kanałów, które nie ruszają (ChannelEngineOptimizer) – testy w tle, wynik zapamiętany w bazie. */
    var engineOptimizer: Boolean get() = bool("engine_optimizer", true); set(v) = putBool("engine_optimizer", v)
    /** Wersja zasad doboru silnika, z którą zapisano profile kanałów (starsze usuwane – [pl.iptv.tv.data.ChannelEngines.load]). */
    var engineProfilesVersion: Int get() = int("engine_profiles_v", 0); set(v) = putInt("engine_profiles_v", v)
    /** (7.5) Kanał, dla którego trwa ręczne dopasowanie silnika – po awarii aplikacji w trakcie prób bez powtórki (pętla awarii). */
    var engineManualRunning: String get() = str("engine_manual_running"); set(v) = sp.edit().putString("engine_manual_running", v).commit().let { }
    /** Adresy bez rozszerzenia rozpoznane jako HLS (po jednym w linii) – po ponownym uruchomieniu kanał od razu jako HLS. */
    var hlsStreams: String get() = str("hls_streams"); set(v) = put("hls_streams", v)
    /** Akceleracja sprzętowa obrazu: wyłączona = dekoder programowy (zgodność), niezależnie od silnika. */
    var hwAccel: Boolean get() = bool("hw_accel", true); set(v) = putBool("hw_accel", v)
    /** Przytrzymanie OK w odtwarzaczu: 0 = od razu menu odtwarzania (silnik, akceleracja), 1 = szybkie menu ikon z kafelkiem „Silnik i akceleracja” (domyślnie). */
    /** (7.5) domyślnie 0 – przytrzymanie OK od razu otwiera ustawienia silnika kanału (szybkie menu ikon – pozycja w tym menu). */
    var okLongAction: Int get() = int("ok_long2", 1); set(v) = putInt("ok_long2", v)

    /** Domyślne parametry trybu dla słabszych urządzeń. */
    fun resetLowPerfParams() = remove("low_max_h", "low_buf_min", "low_buf_max", "low_buf_mb", "low_epg_ahead", "low_desc", "low_epg_kbps", "low_zap_ms", "low_clean")

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); if (k in ENGINE_KEYS) onEngineChanged?.invoke() }
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) { sp.edit().putInt(k, v).apply(); if (k in ENGINE_KEYS) onEngineChanged?.invoke() }

    /** (7.20) Klucze ustawień silnika odtwarzania – każdy zapis powiadamia [pl.iptv.tv.ui.EngineConfigRepository] (jedno źródło prawdy). */
    val ENGINE_KEYS = setOf("engine", "hw_accel", "async_dec", "surface_mode", "afr", "buf_min", "low_latency", "loudness", "low_perf", "dec_fallback")
    /** (7.20) Ustawia [pl.iptv.tv.ui.EngineConfigRepository]: zmiana któregoś z [ENGINE_KEYS] (z Ustawień, odtwarzacza, profili, testów). */
    @Volatile var onEngineChanged: (() -> Unit)? = null

    /** Wartość opcjonalna – null oznacza „domyślna z motywu”. */
    fun optInt(k: String): Int? = if (sp.contains(k)) sp.getInt(k, 0) else null
    fun setOptInt(k: String, v: Int?) = sp.edit().apply { if (v == null) remove(k) else putInt(k, v) }.apply()
    fun remove(vararg keys: String) = sp.edit().apply { keys.forEach { remove(it) } }.apply()
    fun has(key: String) = sp.contains(key)

    /** (7.7) Bieżące wartości [keys] (tylko ustawione) – zapis własnego motywu. */
    fun snapshot(keys: Collection<String>): Map<String, Any> = sp.all.filterKeys { it in keys }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    /** (7.7) Przywrócenie wartości z [snapshot] (typy: Int, Long, Float, Boolean, String). */
    fun restore(values: Map<String, Any>) = sp.edit().apply {
        values.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v); is Boolean -> putBoolean(k, v); else -> putString(k, v.toString()) } }
    }.apply()

    /** (7.7) Zapisane własne motywy (JSON, [pl.iptv.tv.ui.CustomThemes]). */
    var customThemes: String get() = str("custom_themes", "[]"); set(v) = put("custom_themes", v)

    // ---- YouTube (tokeny konta: YouTubeTokens – szyfrowane, poza kopią zapasową) ----
    /** Źródło filmów: 0 = spersonalizowane (konto), 1 = trendy publiczne. */
    var ytSource: Int get() = int("yt_source", 0); set(v) = putInt("yt_source", v)
    /** Region trendów (ISO 3166-1, np. PL). */
    var ytRegion: String get() = str("yt_region", "PL"); set(v) = put("yt_region", v)
    /** Własny klient OAuth / klucz API (puste = wbudowane w BuildConfig). */
    var ytClientId: String get() = str("yt_client_id"); set(v) = put("yt_client_id", v)
    var ytClientSecret: String get() = str("yt_client_secret"); set(v) = put("yt_client_secret", v)
    var ytApiKey: String get() = str("yt_api_key"); set(v) = put("yt_api_key", v)
    /** STube: odtwarzanie we wbudowanym odtwarzaczu (wyłączone = od razu SmartTube). */
    var stubeInternal: Boolean get() = bool("stube_internal", true); set(v) = putBool("stube_internal", v)
    /** STube: zapisywanie historii wyszukiwania i postępu oglądania w lokalnej bazie ([StubeHistory]). */
    var stubeHistory: Boolean get() = bool("stube_history", true); set(v) = putBool("stube_history", v)
    /** STube: wznawianie filmu od zapisanej pozycji. */
    var stubeResume: Boolean get() = bool("stube_resume", true); set(v) = putBool("stube_resume", v)
    /** Odtwarzacz STube: jakość (maks. wysokość obrazu, 0 = automatycznie) i język napisów (puste = wyłączone) – wybór w odtwarzaczu. */
    var ytQuality: Int get() = int("yt_quality", 0); set(v) = putInt("yt_quality", v)
    var ytSubLang: String get() = str("yt_sub_lang"); set(v) = put("yt_sub_lang", v)
    /** Wygaszacz odtwarzacza: po tylu minutach pauzy bez pilota (0 = wyłączony). */
    var ytAmbientMin: Int get() = int("yt_ambient", 5); set(v) = putInt("yt_ambient", v)
    /** Po końcu filmu – następna pozycja z kolejki (inaczej propozycje). */
    var queueAutoplay: Boolean get() = bool("queue_auto", true); set(v) = putBool("queue_auto", v)
    /** STube: kod parowania telefonu (4 cyfry) i zgoda na sterowanie z telefonu. */
    var stubePin: String get() = str("stube_pin"); set(v) = put("stube_pin", v)
    /** (7.15) Odbiór linków „wyślij na telewizor” z telefonu (NSD + /api/cast). */
    var castReceiver: Boolean get() = bool("cast_receiver", false); set(v) = putBool("cast_receiver", v)
    var stubeRemote: Boolean get() = bool("stube_remote", false); set(v) = putBool("stube_remote", v)
    /** STube: motyw kolorów ([pl.iptv.tv.ui.StubeTheme], 0 = jak aplikacja) i układ kafelków. */
    var stubeTheme: Int get() = int("stube_theme", 0); set(v) = putInt("stube_theme", v)
    var stubeLayout: Int get() = int("stube_layout", 0); set(v) = putInt("stube_layout", v)
    /** Odtwarzacz STube: prędkość (procent, 100 = 1.0×) – zapamiętana jak w SmartTube. */
    var ytSpeedPct: Int get() = int("yt_speed", 100); set(v) = putInt("yt_speed", v)
    /** STube: autoodtwarzanie – po końcu filmu (bez kolejki) następna propozycja YouTube, także w tle. */
    var ytAutoplay: Boolean get() = bool("yt_autoplay", true); set(v) = putBool("yt_autoplay", v)
    /** (7.9) Widok muzyczny odtwarzacza STube (okładka, kolejka, tekst) dla utworów; domyślnie wyłączony (przycisk „Widok muzyczny” w odtwarzaczu włącza go dla bieżącego i kolejnych utworów); wyłączony – zwykły odtwarzacz wideo. */
    var ytMusicView: Boolean get() = bool("yt_music_view", false); set(v) = putBool("yt_music_view", v)
    /** (7.9) Tekst piosenki w widoku muzycznym (zapytanie z tytułem i wykonawcą do bazy tekstów). */
    /** (7.14) Odrzucone utwory STube (wykonawcy, gatunki, id) – patrz [StubeTaste]. */
    var ytDislikes: String get() = str("yt_dislikes"); set(v) = put("yt_dislikes", v)
    /** (7.16) Pasek boczny STube: kolejność ikon (klucze po przecinku; puste = domyślna) i ukryte ikony. */
    var stubeRailOrder: String get() = str("stube_rail_order"); set(v) = put("stube_rail_order", v)
    var stubeRailHidden: String get() = str("stube_rail_hidden"); set(v) = put("stube_rail_hidden", v)
    /** (7.16) Ręczne przesunięcia synchronizacji tekstu: „idFilmu=ms” po przecinku (ostatnie 200). */
    var ytLyricDelays: String get() = str("yt_lyric_delays"); set(v) = put("yt_lyric_delays", v)
    var ytLyrics: Boolean get() = bool("yt_lyrics", true); set(v) = putBool("yt_lyrics", v)
    /** Korektor dźwięku: preset ([pl.iptv.tv.ui.YtEq]) i poziomy pasm presetu „Własny” (millibele, po przecinku). */
    var ytEqPreset: Int get() = int("yt_eq", 0); set(v) = putInt("yt_eq", v)
    var ytEqCustom: List<Int> get() = str("yt_eq_custom").split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = put("yt_eq_custom", v.joinToString(","))
    /** Maks. jakość wbudowanego odtwarzacza (wysokość obrazu; 0 = 1080p). */
    var ytMaxHeight: Int get() = int("yt_max_h", 1080); set(v) = putInt("yt_max_h", v)
    /** SponsorBlock: pomijanie segmentów sponsorowanych i wybrane kategorie (id API). */
    var sbEnabled: Boolean get() = bool("sb_on", true); set(v) = putBool("sb_on", v)
    var sbCategories: List<String> get() = str("sb_cats", "sponsor,selfpromo,interaction,music_offtopic").split(',').filter { it.isNotBlank() }
        set(v) = put("sb_cats", v.joinToString(","))

    // ---- źródła ----
    var playlistSource: String get() = str("playlist"); set(v) = put("playlist", v)
    var epgUrl: String get() = str("epg"); set(v) = put("epg", v)
    var playlistEpgUrl: String get() = str("playlist_epg"); set(v) = put("playlist_epg", v)
    var piconUrl: String get() = str("picon"); set(v) = put("picon", v)
    var userAgent: String get() = str("ua"); set(v) = put("ua", v)
    /** Co ile dni pobierać EPG z sieci (w międzyczasie z pamięci podręcznej). */
    var epgRefreshDays: Int get() = int("epg_days", 3); set(v) = putInt("epg_days", v)
    /** Ochrona przed pętlą awarii przy wczytywaniu zbyt dużego EPG na słabym urządzeniu. */
    var epgParsing: Boolean get() = bool("epg_parsing", false); set(v) { sp.edit().putBoolean("epg_parsing", v).commit() }
    var epgCrashes: Int get() = int("epg_crashes", 0); set(v) = putInt("epg_crashes", v)
    var lastEpgUpdate: Long
        get() = sp.getLong("epg_time", 0)
        set(v) = sp.edit().putLong("epg_time", v).apply()
    var lastChannel: String get() = str("last_key"); set(v) = put("last_key", v)
    val effectiveEpgUrl: String get() = epgUrl.ifBlank { playlistEpgUrl }

    // ---- wygląd ----
    var theme: String get() = str("theme", "modern"); set(v) = put("theme", v)
    var bgImageOn: Boolean get() = bool("bg_on", false); set(v) = putBool("bg_on", v)
    // ---- Stremio ----
    /** Synchronizacja historii z kontem Stremio: -1 nie pytano, 0 nie, 1 tak. */
    var stremioSync: Int get() = int("stremio_sync", -1); set(v) = putInt("stremio_sync", v)
    /** Przeglądanie bez konta (pominięty ekran logowania). */
    var stremioGuest: Boolean get() = bool("stremio_guest", false); set(v) = putBool("stremio_guest", v)
    /** (7.17) Biblioteka Stremio (tytuły dodane przyciskiem „Dodaj do biblioteki”) – JSON, [StremioLibrary]. */
    var stremioLibrary: String get() = str("stremio_library"); set(v) = put("stremio_library", v)
    /** (7.20) Co ile godzin rzędy „Top 10” Stremio są pobierane od nowa (domyślnie 96 h = 4 dni). */
    var stremioTop10Hours: Int get() = int("stremio_top10_h", 96); set(v) = putInt("stremio_top10_h", v)
    /** (7.21) Własny klucz API TMDB użytkownika (Stremio → „Klucz TMDB”): Top 10, polskie opisy, części filmów; pusty – jak w 7.19. */
    var tmdbApiKey: String get() = str("tmdb_key"); set(v) = put("tmdb_key", v.trim())
    /** Odtwarzacz: rozmiar napisów (indeks), kolor (indeks), opóźnienie (ms), dopasowanie obrazu, preferowany język napisów. */
    var stremioSubSize: Int get() = int("stremio_sub_size", 1); set(v) = putInt("stremio_sub_size", v)
    var stremioSubColor: Int get() = int("stremio_sub_color", 0); set(v) = putInt("stremio_sub_color", v)
    var stremioSubDelay: Int get() = int("stremio_sub_delay", 0); set(v) = putInt("stremio_sub_delay", v)
    var stremioAspect: Int get() = int("stremio_aspect", 0); set(v) = putInt("stremio_aspect", v)
    var stremioSubLang: String get() = str("stremio_sub_lang", "pol"); set(v) = put("stremio_sub_lang", v)
    /** Źródła Stremio bez polskiej ścieżki mimo oznaczenia lektora (skróty kluczy, po jednym w linii). */
    var stremioNotPolish: String get() = str("stremio_not_pl"); set(v) = put("stremio_not_pl", v)
    /** (7.18) Wtyczki Stremio, z których naprawdę się ogląda (JSON adres → liczba seansów ≥ 10 %). */
    var stremioAddonScores: String get() = str("stremio_addon_scores"); set(v) = put("stremio_addon_scores", v)
    /** Serwer strumieniujący źródła P2P (TorrServer / serwer Stremio), np. „192.168.1.10:11470”; pusty – szukanie na tym urządzeniu. */
    var torrentServer: String get() = str("torrent_server"); set(v) = put("torrent_server", v)
    // ---- motyw X25 ----
    /** Pulpit X25 jako ekran startowy (gdy włączony motyw X25). */
    var x25Dashboard: Boolean get() = bool("x25_dash", true); set(v) = putBool("x25_dash", v)
    /** Menu i listy wyboru jako panel boczny „Guide”. */
    var x25Guide: Boolean get() = bool("x25_guide", true); set(v) = putBool("x25_guide", v)
    /** Dźwięki nawigacji pilotem. */
    var x25Sounds: Boolean get() = bool("x25_sounds", true); set(v) = putBool("x25_sounds", v)
    /** Motyw sprzed włączenia X25 (wyłączenie wraca do niego). */
    var x25PrevTheme: String get() = str("x25_prev"); set(v) = put("x25_prev", v)
    /** Kafelek Stremio dopisany do zapisanego układu pulpitu (raz – usunięty przez użytkownika nie wraca). */
    var x25StremioTile: Boolean get() = bool("x25_stremio_tile", false); set(v) = putBool("x25_stremio_tile", v)
    // ---- motyw PS5 ----
    /** Ekran główny PS5 jako ekran startowy (gdy włączony motyw PS5). */
    var ps5Home: Boolean get() = bool("ps5_home", true); set(v) = putBool("ps5_home", v)
    /** Menu i listy wyboru jako panel ustawień PS5 (pionowa lista przy lewej krawędzi). */
    var ps5Panel: Boolean get() = bool("ps5_panel", true); set(v) = putBool("ps5_panel", v)
    /** Dźwięki nawigacji pilotem w motywie PS5. */
    var ps5Sounds: Boolean get() = bool("ps5_sounds", true); set(v) = putBool("ps5_sounds", v)
    /** Motyw sprzed włączenia PS5 (wyłączenie wraca do niego – także do X25). */
    var ps5PrevTheme: String get() = str("ps5_prev"); set(v) = put("ps5_prev", v)
    /** Aplikacje z urządzenia dodane do rzędu ekranu głównego PS5 (ComponentName.flattenToString). */
    var ps5Apps: List<String> get() = list("ps5_apps"); set(v) = putList("ps5_apps", v)
    // ---- motyw Nowoczesny (7.0) ----
    /** Ekran główny Nowoczesny (pasek ikon, hero, rzędy) jako ekran startowy (gdy włączony motyw Nowoczesny). */
    var modernHome: Boolean get() = bool("modern_home", true); set(v) = putBool("modern_home", v)
    /** Menu i listy wyboru jako półprzezroczysty panel nakładkowy z prawej strony. */
    var modernOverlay: Boolean get() = bool("modern_overlay", true); set(v) = putBool("modern_overlay", v)

    // ---- funkcje (można wyłączać) ----
    var showFavGroup: Boolean get() = bool("f_fav", true); set(v) = putBool("f_fav", v)
    var showRecentGroup: Boolean get() = bool("f_recent_group", false); set(v) = putBool("f_recent_group", v)
    var searchEnabled: Boolean get() = bool("f_search", true); set(v) = putBool("f_search", v)
    var recentPanel: Boolean get() = bool("f_recent_panel", true); set(v) = putBool("f_recent_panel", v)
    var channelPanel: Boolean get() = bool("f_ch_panel", true); set(v) = putBool("f_ch_panel", v)
    var recentLimit: Int get() = int("recent_limit", 5); set(v) = putInt("recent_limit", v)
    var panelHours: Int get() = int("panel_hours2", 72); set(v) = putInt("panel_hours2", v)
    /** Układ menu głównego: 0 klasyczny, 1 podgląd na środku, 2 duży podgląd, 3 szeroka lista. */
    var mainLayout: Int get() = int("main_layout", 0); set(v) = putInt("main_layout", v)
    // ---- wyniki ----
    var scoresEnabled: Boolean get() = bool("scores_on", true); set(v) = putBool("scores_on", v)
    var scoreGoalAlerts: Boolean get() = bool("scores_goal", true); set(v) = putBool("scores_goal", v)
    /** Bez spoilerów: wyniki meczów ukryte (lista wyników, kanały, powiadomienia) – odsłaniane na życzenie. */
    var scoreHideResults: Boolean get() = bool("scores_hide", false); set(v) = putBool("scores_hide", v)
    var scoreRefresh: Int get() = int("scores_refresh", 60); set(v) = putInt("scores_refresh", v)
    var scoreFollowJson: String get() = str("scores_follow", "[]"); set(v) = put("scores_follow", v)
    var scoreChannelsJson: String get() = str("scores_channels", "{}"); set(v) = put("scores_channels", v)
    var apiFootballKey: String get() = str("af_key"); set(v) = put("af_key", v.trim())
    // ---- pogoda ----
    var weatherEnabled: Boolean get() = bool("weather_on", false); set(v) = putBool("weather_on", v)
    var weatherCity: String get() = str("weather_city"); set(v) = put("weather_city", v)
    var weatherLat: String get() = str("weather_lat"); set(v) = put("weather_lat", v)
    var weatherLon: String get() = str("weather_lon"); set(v) = put("weather_lon", v)
    var weatherText: String get() = str("weather_text"); set(v) = put("weather_text", v)
    var weatherAt: Long get() = sp.getLong("weather_at", 0); set(v) = sp.edit().putLong("weather_at", v).apply()
    var mainPreview: Boolean get() = bool("main_preview", true); set(v) = putBool("main_preview", v)
    var showNumbers: Boolean get() = bool("f_numbers", true); set(v) = putBool("f_numbers", v)

    // ---- uruchamianie ----
    /** Indeks w [StartActions.LABELS]; domyślnie: ostatnio oglądany kanał. */
    var startAction: Int get() = int("start_action", 1); set(v) = putInt("start_action", v)
    var startChannel: String get() = str("start_channel"); set(v) = put("start_channel", v)
    var bootStart: Boolean get() = bool("boot_start", false); set(v) = putBool("boot_start", v)
    /** Rozruch urządzenia, w którym ekran główny (tryb launchera) wykonał już akcję startową. */
    var homeBootId: String get() = str("home_boot_id"); set(v) = sp.edit().putString("home_boot_id", v).commit().let { }

    var permissionsAsked: Boolean get() = bool("perm_wizard", false); set(v) = putBool("perm_wizard", v)
    var batteryAsked: Boolean get() = bool("battery_asked", false); set(v) = putBool("battery_asked", v)

    // ---- catch-up dla kanałów bez informacji w liście ----
    /** "" = wyłączone; inaczej typ: default, append, shift, flussonic, xc */
    var catchupDefaultType: String get() = str("cu_type"); set(v) = put("cu_type", v)
    var catchupDefaultSource: String get() = str("cu_source"); set(v) = put("cu_source", v)
    var catchupDefaultDays: Int get() = int("cu_days", 7); set(v) = putInt("cu_days", v)
    /** Nadpisz także kanały, które mają catch-up w liście. */
    var catchupForce: Boolean get() = bool("cu_force", false); set(v) = putBool("cu_force", v)

    var setupDone: Boolean get() = bool("setup_done", false); set(v) = putBool("setup_done", v)

    // ---- odtwarzanie archiwum ----
    var cuBack: Int get() = int("cu_back", 10); set(v) = putInt("cu_back", v)
    var cuFwd: Int get() = int("cu_fwd", 30); set(v) = putInt("cu_fwd", v)
    /** 0 = strzałki ◄ ► przewijają archiwum, 1 = otwierają panele */
    var cuArrows: Int get() = int("cu_arrows", 0); set(v) = putInt("cu_arrows", v)
    var cuPadBefore: Int get() = int("cu_pad_before", 0); set(v) = putInt("cu_pad_before", v)
    var cuPadAfter: Int get() = int("cu_pad_after", 0); set(v) = putInt("cu_pad_after", v)
    var cuAutoNext: Boolean get() = bool("cu_next", false); set(v) = putBool("cu_next", v)
    /** Pytaj przed końcem programu z archiwum, co dalej. */
    var cuAsk: Boolean get() = bool("cu_ask", true); set(v) = putBool("cu_ask", v)
    var adSkip: Int get() = int("ad_skip", 120); set(v) = putInt("ad_skip", v)

    // ---- program TV / przypomnienia ----
    var epgSearchEnabled: Boolean get() = bool("f_epg_search", true); set(v) = putBool("f_epg_search", v)
    var epgSearchInDesc: Boolean get() = bool("epg_search_desc", false); set(v) = putBool("epg_search_desc", v)
    var remindersEnabled: Boolean get() = bool("f_reminders", true); set(v) = putBool("f_reminders", v)
    /** Wyprzedzenie przypomnienia w minutach. */
    var reminderLead: Int get() = int("reminder_lead", 2); set(v) = putInt("reminder_lead", v)
    var notificationsAsked: Boolean get() = bool("notif_asked", false); set(v) = putBool("notif_asked", v)

    /** Aplikacje dodane do menu odtwarzacza (►►): "pakiet/klasa". */
    var quickApps: List<String> get() = list("quick_apps"); set(v) = putList("quick_apps", v)

    // ---- wiele list ----
    var playlistsJson: String get() = str("playlists", "[]"); set(v) = put("playlists", v)
    var activePlaylist: String get() = str("active_playlist", "main"); set(v) = put("active_playlist", v)

    // ---- kontrola rodzicielska ----
    var parentalOn: Boolean get() = bool("parental_on", false); set(v) = putBool("parental_on", v)
    var parentalPin: String get() = str("parental_pin"); set(v) = put("parental_pin", v)
    var parentalHide: Boolean get() = bool("parental_hide", false); set(v) = putBool("parental_hide", v)
    var parentalSettings: Boolean get() = bool("parental_settings", false); set(v) = putBool("parental_settings", v)
    var lockedGroups: List<String> get() = list("locked_groups"); set(v) = putList("locked_groups", v)
    var lockedChannels: List<String> get() = list("locked_channels"); set(v) = putList("locked_channels", v)

    // ---- nagrywanie ----
    var recEnabled: Boolean get() = bool("rec_on", true); set(v) = putBool("rec_on", v)
    /** "" = folder aplikacji; inaczej ścieżka katalogu. */
    var recFolder: String get() = str("rec_folder"); set(v) = put("rec_folder", v)
    var recBefore: Int get() = int("rec_before", 2); set(v) = putInt("rec_before", v)
    var recAfter: Int get() = int("rec_after", 5); set(v) = putInt("rec_after", v)

    // ---- obraz w obrazie ----
    var pipAuto: Boolean get() = bool("pip_auto", true); set(v) = putBool("pip_auto", v)

    // ---- odbieranie list ----
    var remoteOn: Boolean get() = bool("remote_on", false); set(v) = putBool("remote_on", v)
    var remotePin: String get() = str("remote_pin"); set(v) = put("remote_pin", v)
    var remoteAutoActivate: Boolean get() = bool("remote_activate", false); set(v) = putBool("remote_activate", v)

    // ---- odbiór przez Internet (przekaźnik ntfy) ----
    var relayOn: Boolean get() = bool("relay_on", false); set(v) = putBool("relay_on", v)
    var relayCode: String get() = str("relay_code"); set(v) = put("relay_code", v)
    var relayServer: String get() = str("relay_server"); set(v) = put("relay_server", v)
    var relaySince: String get() = str("relay_since"); set(v) = put("relay_since", v)

    // ---- zdalne zarządzanie (administrator) ----
    var adminCode: String get() = str("admin_code"); set(v) = put("admin_code", v)
    var deviceLabel: String get() = str("device_label"); set(v) = put("device_label", v)
    var deviceId: String get() = str("device_id"); set(v) = put("device_id", v)
    var adminLastReport: Long
        get() = sp.getLong("admin_report", 0)
        set(v) = sp.edit().putLong("admin_report", v).apply()

    // ---- ustawienia: rozwinięte sekcje ----
    var settingsOpen: List<String> get() = list("settings_open"); set(v) = putList("settings_open", v)
    var settingsClosed: List<String> get() = list("settings_closed"); set(v) = putList("settings_closed", v)

    // ---- aktualizacja ----
    val DEFAULT_UPDATE_URL: String = pl.iptv.tv.BuildConfig.UPDATE_URL
    /** Adres aktualizacji: domyślnie wydania na GitHubie (można zmienić zdalnie z aplikacji Admin). */
    var updateUrl: String get() = if (pl.iptv.tv.BuildConfig.SECURE) DEFAULT_UPDATE_URL else str("update_url").ifBlank { DEFAULT_UPDATE_URL }
        set(v) = put("update_url", v)
    var updateAuto: Boolean get() = bool("update_auto", true); set(v) = putBool("update_auto", v)
    var updateLastCheck: Long
        get() = sp.getLong("update_check", 0)
        set(v) = sp.edit().putLong("update_check", v).apply()

    // ---- obraz ----
    var aspectMode: Int get() = int("aspect", 0); set(v) = putInt("aspect", v)

    // ---- menu funkcji (◄◄) ----
    var quickOrder: List<String> get() = list("quick_order"); set(v) = putList("quick_order", v)
    /** OK podczas oglądania: lista kanałów nad paskiem informacji i liczba widocznych wierszy. */
    var zapListEnabled: Boolean get() = bool("zap_list", true); set(v) = putBool("zap_list", v)
    var zapRows: Int get() = int("zap_rows", 8); set(v) = putInt("zap_rows", v)
    /** Przesunięcie dźwięku względem obrazu (ms): ujemne = dźwięk wcześniej, dodatnie = później. */
    var audioDelayMs: Int get() = int("audio_delay", 0); set(v) = putInt("audio_delay", v)
    var tutorialShown: Boolean get() = bool("tutorial_shown", false); set(v) = putBool("tutorial_shown", v)
    var miniMenuEnabled: Boolean get() = bool("mini_on", true); set(v) = putBool("mini_on", v)
    /** 0 poziomo, 1 automatycznie (obrót), 2 pionowo (menu), odtwarzacz zawsze z obrotem. */
    var orientation: Int get() = int("orientation", 0); set(v) = putInt("orientation", v)
    /** (7.8) Język interfejsu: „pl” (domyślny) albo „en” ([pl.iptv.tv.ui.I18n]). */
    var language: String get() = str("language", "pl"); set(v) = put("language", v)
    /** Kategorie w menu: kolejność i ukryte (kanały zostają w „Wszystkie”). */
    var groupOrder: List<String> get() = list("group_order"); set(v) = putList("group_order", v)
    var groupHidden: List<String> get() = list("group_hidden"); set(v) = putList("group_hidden", v)
    var miniApps: List<String> get() = list("mini_apps"); set(v) = putList("mini_apps", v)
    var miniOrder: List<String> get() = list("mini_order"); set(v) = putList("mini_order", v)
    var miniHidden: List<String> get() = list("mini_hidden"); set(v) = putList("mini_hidden", v)
    var quickHidden: List<String> get() = list("quick_hidden"); set(v) = putList("quick_hidden", v)

    // ---- pilot ----
    var keyMapJson: String get() = str("keymap"); set(v) = put("keymap", v)

    // ---- wygląd przewodnika ----
    var guideRows: Int get() = int("g_rows", 6); set(v) = putInt("g_rows", v)
    var guideWindow: Int get() = int("g_window", 120); set(v) = putInt("g_window", v)
    var guideStyle: Int get() = int("g_style", 0); set(v) = putInt("g_style", v)
    var guideChannelCol: Int get() = int("g_chcol", 0); set(v) = putInt("g_chcol", v)
    var guideShowTime: Boolean get() = bool("g_time", true); set(v) = putBool("g_time", v)
    var guideDesc: Int get() = int("g_desc", 1); set(v) = putInt("g_desc", v)
    var guideNowLine: Boolean get() = bool("g_now", true); set(v) = putBool("g_now", v)

    // ---- ulubione / ostatnie (lista kluczy kanałów) ----
    var favorites: List<String> get() = list("favs"); set(v) = putList("favs", v)
    var recent: List<String> get() = list("recent"); set(v) = putList("recent", v)

    private fun list(k: String): List<String> = runCatching {
        val a = JSONArray(str(k, "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    private fun putList(k: String, v: List<String>) = put(k, JSONArray(v).toString())
}

package pl.iptv.tv.data

import java.util.Locale

/**
 * (7.28) Region i język treści STube – jedno źródło dla wszystkich zapytań: NewPipe (wyszukiwanie, kanały, playlisty, Na czasie,
 * propozycje, strumienie), podpowiedzi wpisywania ([YtSuggest]), YouTube Data API v3 ([YouTubeRepository]).
 * `Prefs.ytRegion`: kod kraju (domyślnie „PL”) albo [AUTO] – język i kraj urządzenia. Zmiana działa od następnego zapytania.
 */
object StubeLocale {
    const val AUTO = "auto"
    const val TITLE = "Region i język treści STube"
    const val NOTE = "Wyniki wyszukiwania, podpowiedzi, Główna, Odkrywaj i Na czasie w wybranym regionie i języku (domyślnie Polska)."

    /** Kod → podpis; pozostałe dawne regiony z Ustawień nadal obsługiwane. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        AUTO to "Automatyczny (język urządzenia)", "PL" to "Polska (PL)", "GB" to "Wielka Brytania (GB)", "US" to "USA (US)",
        "DE" to "Niemcy (DE)", "UA" to "Ukraina (UA)", "CZ" to "Czechy (CZ)", "SK" to "Słowacja (SK)", "LT" to "Litwa (LT)",
    )

    private val LANG = mapOf("PL" to "pl", "GB" to "en", "US" to "en", "DE" to "de", "UA" to "uk", "CZ" to "cs", "SK" to "sk", "LT" to "lt")

    private val pref: String get() = runCatching { Prefs.ytRegion }.getOrDefault("PL").ifBlank { "PL" }

    /** Kraj (gl / regionCode / ContentCountry). */
    val gl: String get() = if (pref.equals(AUTO, true)) Locale.getDefault().country.uppercase().ifBlank { "PL" } else pref.uppercase()

    /** Język (hl / relevanceLanguage / Localization). */
    val hl: String get() = if (pref.equals(AUTO, true)) Locale.getDefault().language.lowercase().ifBlank { "pl" } else LANG[gl] ?: "pl"

    /** Nagłówek `Accept-Language`, np. „pl-PL,pl;q=0.9”. */
    val acceptLanguage: String get() = "$hl-$gl,$hl;q=0.9"

    /** Ciasteczko preferencji YouTube (region i język; bez historii – tylko gdy ekstraktor nie wysyła własnych ciasteczek). */
    val prefCookie: String get() = "PREF=hl=$hl&gl=$gl"
}

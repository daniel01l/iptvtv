package pl.iptv.tv.data

import org.json.JSONObject

/**
 * (7.18) Ulubione wtyczki źródeł Stremio: wtyczka dostaje punkt, gdy z jej źródła obejrzysz co najmniej [WATCHED_FRACTION]
 * (10 %) filmu lub odcinka – samo uruchomienie źródła nie liczy się (źródło mogło nie działać). W liście źródeł danej
 * rozdzielczości wtyczki z największą liczbą punktów są na górze ([order]); dalej jak dotąd (polska wersja, kolejność wtyczki).
 */
object StremioAddonScores {
    const val WATCHED_FRACTION = 0.10

    private fun load(): MutableMap<String, Int> = runCatching {
        val o = JSONObject(Prefs.stremioAddonScores.ifBlank { "{}" })
        o.keys().asSequence().associateWith { o.optInt(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Klucz wtyczki: adres bez zapytania i końcowego „/manifest.json” (zmiana konfiguracji wtyczki nie zeruje punktów). */
    fun key(addonUrl: String) = addonUrl.substringBefore('?').removeSuffix("/manifest.json").trimEnd('/').lowercase()

    fun score(addonUrl: String): Int = load()[key(addonUrl)] ?: 0

    fun credit(addonUrl: String) {
        if (addonUrl.isBlank()) return
        val m = load(); val k = key(addonUrl)
        m[k] = (m[k] ?: 0) + 1
        Prefs.stremioAddonScores = JSONObject(m as Map<*, *>).toString()
    }

    /** Czy pozycja [posMs] z [durMs] to już „oglądanie” (≥ 10 %). */
    fun watched(posMs: Long, durMs: Long) = durMs > 0 && posMs >= durMs * WATCHED_FRACTION

    /**
     * Domyślna kolejność wtyczek w źródłach (nazwy z manifestu; wyłączone wtyczki po prostu nie występują). Wtyczki spoza listy –
     * na końcu, w kolejności, w jakiej podały źródła.
     */
    val DEFAULT_ORDER = listOf(
        "AIOStreams | ElfHosted", "Torrentio PM", "Torrentio RD", "Torrentio Lite", "Torrentio",
        "MediaFusion | ElfHosted RD", "Comet | ElfHosted | RD", "Orion", "Cinemeta", "MediaFusion | ElfHosted",
    )
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    private val defaultIndex: Map<String, Int> by lazy { DEFAULT_ORDER.withIndex().associate { (i, n) -> norm(n) to i } }
    fun defaultRank(addonName: String): Int = defaultIndex[norm(addonName)] ?: DEFAULT_ORDER.size

    /**
     * Źródła jednej rozdzielczości: najpierw polska wersja (dubbing / lektor, potem napisy – [StremioStreams.polishRank]),
     * w jej obrębie wtyczki, z których naprawdę oglądasz (więcej punktów wyżej), dalej domyślna kolejność wtyczek ([DEFAULT_ORDER]).
     */
    fun order(l: List<StremioStream>): List<StremioStream> {
        val m = load()
        return l.sortedWith(compareBy<StremioStream> { StremioStreams.polishRank(it) }
            .thenByDescending { m[key(it.addonUrl)] ?: 0 }
            .thenBy { defaultRank(it.addonName) })
    }
}

/**
 * (7.18) Źródła „do pobrania” w debrid (np. Torrentio z RealDebrid dla nieskeszowanego torrenta): adres rozwiązania przekierowuje
 * na film-planszę „pobieranie” zamiast na plik. Serwis debrid udostępnia plik dopiero po pobraniu CAŁOŚCI – nie da się zacząć
 * od części – więc odtwarzacz sprawdza adres co [POLL_MS] i sam startuje, gdy plik jest gotowy.
 */
object DebridResolve {
    enum class State { READY, DOWNLOADING, FAILED }
    const val POLL_MS = 20_000L
    const val MAX_WAIT_MS = 90 * 60_000L

    /** Adresy rozwiązywane przez wtyczkę (przekierowanie do debrid) – tylko je warto sprawdzać przed startem. */
    private val RESOLVE = Regex("""/(resolve|playback|realdebrid|alldebrid|premiumize|debridlink|torbox)/""", RegexOption.IGNORE_CASE)
    private val DOWNLOADING = Regex("""download(ing)?[_-]?(v\d)?\.mp4|/videos/downloading""", RegexOption.IGNORE_CASE)
    private val FAILED = Regex("""failed[_-][a-z_]*(v\d)?\.mp4|/videos/failed""", RegexOption.IGNORE_CASE)

    fun needsCheck(url: String) = RESOLVE.containsMatchIn(url)

    /** Stan po przekierowaniach ([finalUrl] – adres, na który ostatecznie prowadzi źródło). */
    fun classify(finalUrl: String): State = when {
        FAILED.containsMatchIn(finalUrl) -> State.FAILED            // najpierw błąd („failed_download…” zawiera też „download”)
        DOWNLOADING.containsMatchIn(finalUrl) -> State.DOWNLOADING
        else -> State.READY
    }

    /** Sprawdzenie adresu (1 bajt, przekierowania): błąd sieci – READY (odtwarzacz sam zgłosi problem). */
    fun check(url: String, headers: Map<String, String> = emptyMap()): State = runCatching {
        val rb = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> rb.header(k, v) }
        Repository.http.newCall(rb.build()).execute().use { r -> classify(r.request.url.toString()) }
    }.getOrDefault(State.READY)
}

package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Integracja z ekosystemem Stremio: protokół wtyczek (Stremio Addon SDK – manifest, catalog, meta, stream, subtitles),
 * metadane z Cinemeta i konto Stremio (api.strem.io – logowanie, kolekcja wtyczek, biblioteka / historia).
 * Wszystkie funkcje sieciowe są suspend (wątek IO); parsowanie i wybór źródeł to czyste funkcje (testy bez sieci).
 */
class StremioException(message: String, val code: Int = 0) : Exception(message)

/** Wtyczka Stremio (manifest). */
data class StremioAddon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val logo: String = "",
    val types: List<String> = emptyList(),
    val resources: List<Resource> = emptyList(),
    val catalogs: List<Catalog> = emptyList(),
    val idPrefixes: List<String>? = null,
    /** Oryginalny manifest (zapis w bazie – bez ponownego pobierania). */
    val manifestJson: String = "",
) {
    /** Zasób wtyczki; [types]/[idPrefixes] null = jak w manifeście. */
    data class Resource(val name: String, val types: List<String>? = null, val idPrefixes: List<String>? = null)
    /** Katalog; [extra] – nazwy obsługiwanych dodatków (np. „search”, „genre”, „skip”), [required] – wymagane. */
    data class Catalog(val type: String, val id: String, val name: String, val extra: List<String> = emptyList(), val required: List<String> = emptyList())

    /** Adres bazowy (bez „/manifest.json”). */
    val base: String get() = transportUrl.substringBefore("?").removeSuffix("/manifest.json").trimEnd('/')
    val resourceNames: List<String> get() = resources.map { it.name }

    /** Czy wtyczka obsługuje [resource] dla [type] i identyfikatora [id] (np. „tt1234567:1:2”). */
    fun supports(resource: String, type: String, id: String? = null): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val t = r.types ?: types
        if (t.isNotEmpty() && type !in t) return false
        val p = r.idPrefixes ?: idPrefixes
        return id == null || p.isNullOrEmpty() || p.any { id.startsWith(it) }
    }

    val searchCatalogs: List<Catalog> get() = catalogs.filter { "search" in it.extra }
    /** Katalogi do przeglądania (bez wymaganych dodatków). */
    val boardCatalogs: List<Catalog> get() = catalogs.filter { it.required.isEmpty() }

    companion object {
        /** „stremio://host/…” → „https://host/…”. */
        fun normalizeUrl(u: String): String {
            var s = u.trim()
            if (s.startsWith("stremio://")) s = "https://" + s.removePrefix("stremio://")
            if (!s.startsWith("http")) s = "https://$s"
            if (!s.substringBefore("?").endsWith("/manifest.json")) s = s.trimEnd('/') + "/manifest.json"
            return s
        }

        fun parse(transportUrl: String, m: JSONObject): StremioAddon {
            fun strs(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } } }
            val res = m.optJSONArray("resources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    when (val r = arr.opt(i)) {
                        is String -> Resource(r)
                        is JSONObject -> Resource(r.optString("name"), strs(r.optJSONArray("types")), strs(r.optJSONArray("idPrefixes")))
                        else -> null
                    }
                }
            }.orEmpty()
            val cats = m.optJSONArray("catalogs")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val extra = mutableListOf<String>(); val req = mutableListOf<String>()
                    c.optJSONArray("extra")?.let { ex -> for (j in 0 until ex.length()) ex.optJSONObject(j)?.let { e ->
                        extra += e.optString("name"); if (e.optBoolean("isRequired")) req += e.optString("name") } }
                    strs(c.optJSONArray("extraSupported"))?.forEach { if (it !in extra) extra += it }
                    strs(c.optJSONArray("extraRequired"))?.forEach { if (it !in req) req += it }
                    Catalog(c.optString("type"), c.optString("id"), c.optString("name"), extra, req)
                }
            }.orEmpty()
            return StremioAddon(transportUrl, m.optString("id"), m.optString("name", m.optString("id")), m.optString("version"),
                m.optString("description"), m.optString("logo"), strs(m.optJSONArray("types")).orEmpty(), res, cats,
                strs(m.optJSONArray("idPrefixes")), m.toString())
        }
    }
}

/** Element katalogu (plakat). */
data class StremioMetaPreview(
    val id: String, val type: String, val name: String, val poster: String = "", val description: String = "",
    val releaseInfo: String = "", val imdbRating: String = "", val genres: List<String> = emptyList(),
)

/** Odcinek / wideo w metadanych. [id] np. „tt0903747:1:2”. */
data class StremioVideo(val id: String, val title: String, val season: Int = 0, val episode: Int = 0, val released: String = "",
                        val thumbnail: String = "", val overview: String = "") {
    val label: String get() = if (season > 0 || episode > 0) "S${season}E$episode" + (if (title.isNotBlank()) " – $title" else "") else title
}

/** Pełne metadane filmu / serialu. */
data class StremioMeta(
    val id: String, val type: String, val name: String, val poster: String = "", val background: String = "", val logo: String = "",
    val description: String = "", val releaseInfo: String = "", val imdbRating: String = "", val genres: List<String> = emptyList(),
    val runtime: String = "", val videos: List<StremioVideo> = emptyList(),
) {
    val isSeries get() = type == "series" || videos.size > 1
    /** Sezony (najpierw zwykłe, „0” – dodatki – na końcu). */
    val seasons: List<Int> get() = videos.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
    fun episodes(season: Int) = videos.filter { it.season == season }.sortedBy { it.episode }
    /** Następny odcinek po [videoId] (bez dodatków z sezonu 0, chyba że oglądamy dodatki). */
    fun next(videoId: String): StremioVideo? {
        val cur = videos.firstOrNull { it.id == videoId } ?: return null
        val list = videos.filter { (it.season > 0) == (cur.season > 0) }.sortedWith(compareBy({ it.season }, { it.episode }))
        return list.getOrNull(list.indexOf(cur) + 1)
    }
    fun preview() = StremioMetaPreview(id, type, name, poster, description, releaseInfo, imdbRating, genres)
}

/** Napisy z wtyczki. */
data class StremioSubtitle(val id: String, val url: String, val lang: String, val addon: String = "")

/** Źródło (strumień) z wtyczki. */
data class StremioStream(
    val addonName: String, val addonUrl: String,
    val name: String = "", val title: String = "", val description: String = "",
    val url: String = "", val infoHash: String = "", val fileIdx: Int = -1, val ytId: String = "", val externalUrl: String = "",
    val bingeGroup: String = "", val filename: String = "", val videoSize: Long = 0, val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StremioSubtitle> = emptyList(),
) {
    enum class Kind { HTTP, TORRENT, YOUTUBE, EXTERNAL, NONE }
    val kind: Kind get() = when {
        url.isNotBlank() -> Kind.HTTP; ytId.isNotBlank() -> Kind.YOUTUBE; infoHash.isNotBlank() -> Kind.TORRENT
        externalUrl.isNotBlank() -> Kind.EXTERNAL; else -> Kind.NONE
    }
    private val text get() = "$name\n$title\n$description\n$filename"
    // (7.8) wyrażenia regularne raz na źródło (dawniej przy każdym odczycie – sortowanie setek źródeł na głównym wątku blokowało aplikację)
    val quality: String by lazy(LazyThreadSafetyMode.PUBLICATION) { StremioStreams.quality(text) }
    val hdr: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { StremioStreams.hdr(text) }
    private val rawLanguages: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { StremioStreams.languages(text) }
    /** (7.4) Oznaczenia języka; lektor / dubbing PL pomijane, gdy odtwarzacz sprawdził, że źródło nie ma polskiej ścieżki. */
    val languages: List<String> get() = rawLanguages.let { l ->
        if (StremioStreams.verifiedNoPolishAudio(this)) l - setOf("Lektor PL", "Dubbing PL", "Audio PL") else l
    }
    /** Źródło deklaruje polski dźwięk (lektor / dubbing). */
    val claimsPolishAudio: Boolean get() = languages.any { it == "Lektor PL" || it == "Dubbing PL" || it == "Audio PL" }
    /** Klucz źródła (zapamiętana weryfikacja języka). */
    val key: String get() = infoHash.ifBlank { url }.let { if (fileIdx >= 0) "$it#$fileIdx" else it }
    val sizeBytes: Long get() = if (videoSize > 0) videoSize else StremioStreams.size(text)
    /** Pierwsza linia opisu (nazwa pliku / wydania). */
    val headline: String get() = (title.ifBlank { description }).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: name.replace('\n', ' ')
    /** „4K · HDR · 🇵🇱 PL · 15.2 GB”. */
    val badges: String get() = listOfNotNull(quality.ifBlank { null }, hdr.joinToString(" ").ifBlank { null },
        languages.joinToString(" ").ifBlank { null }, sizeBytes.takeIf { it > 0 }?.let { StremioStreams.formatSize(it) },
        if (kind == Kind.TORRENT) "P2P" else null).joinToString("  ·  ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("addonName", addonName); put("addonUrl", addonUrl); put("name", name); put("title", title); put("description", description)
        put("url", url); put("infoHash", infoHash); put("fileIdx", fileIdx); put("ytId", ytId); put("externalUrl", externalUrl)
        put("bingeGroup", bingeGroup); put("filename", filename); put("videoSize", videoSize); put("headers", JSONObject(headers))
        put("subtitles", JSONArray(subtitles.map { JSONObject().put("id", it.id).put("url", it.url).put("lang", it.lang).put("addon", it.addon) }))
    }

    companion object {
        fun fromJson(o: JSONObject) = StremioStream(o.optString("addonName"), o.optString("addonUrl"), o.optString("name"), o.optString("title"),
            o.optString("description"), o.optString("url"), o.optString("infoHash"), o.optInt("fileIdx", -1), o.optString("ytId"),
            o.optString("externalUrl"), o.optString("bingeGroup"), o.optString("filename"), o.optLong("videoSize"),
            o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty(),
            o.optJSONArray("subtitles")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                StremioSubtitle(it.optString("id"), it.optString("url"), it.optString("lang"), it.optString("addon")) } }.orEmpty())
    }
}

/** Grupa źródeł jednej wtyczki (widok „Źródła”). */
data class StremioSourceGroup(val addon: StremioAddon, val streams: List<StremioStream>, val error: String? = null, val loading: Boolean = false, val total: Int = streams.size)

/** Czyste funkcje: jakość, HDR, języki i rozmiar z opisu źródła, limit na wtyczkę, wybór źródła następnego odcinka. */
object StremioStreams {
    /** Najwięcej źródeł jednej wtyczki w każdej rozdzielczości. */
    const val PER_ADDON = 5   // na każdą rozdzielczość (6.6)

    fun quality(t: String): String {
        val s = t.lowercase()
        return when {
            Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(s) -> "4K"
            Regex("1440p").containsMatchIn(s) -> "1440p"
            Regex("1080p|\\bfhd\\b|full ?hd").containsMatchIn(s) -> "1080p"
            Regex("720p|\\bhd\\b").containsMatchIn(s) -> "720p"
            Regex("576p|540p|480p|\\bsd\\b|dvdrip").containsMatchIn(s) -> "480p"
            Regex("\\bcam\\b|hdcam|telesync|\\bts\\b").containsMatchIn(s) -> "CAM"
            else -> ""
        }
    }

    fun hdr(t: String): List<String> = buildList {
        if (Regex("(?i)\\b(dv|dovi|dolby ?vision)\\b").containsMatchIn(t)) add("DV")
        if (Regex("(?i)hdr10\\+|hdr10plus").containsMatchIn(t)) add("HDR10+")
        else if (Regex("(?i)\\bhdr(10)?\\b").containsMatchIn(t)) add("HDR")
    }

    /** Flagi (emoji regionalne) i oznaczenia polskiej wersji (lektor, dubbing, napisy PL). */
    fun languages(t: String): List<String> {
        val out = LinkedHashSet<String>()
        val cps = t.codePoints().toArray()
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                out += String(intArrayOf(a, b), 0, 2); i += 2
            } else i++
        }
        out += polishTags(tokens(t), onlyPolishFlag = out.contains("🇵🇱") && out.count { it.length == 4 } == 1, hasPolishFlag = out.contains("🇵🇱"))
        if ("multi" in tokens(t)) out += "Multi"
        return out.toList()
    }

    /** (7.5) Całe wyrazy / tokeny (litery i cyfry, także polskie znaki), małymi literami – bez dopasowań „pl” w środku słów. */
    fun tokens(t: String): List<String> = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private val PL = setOf("pl", "pol", "polski", "polska", "polskie", "polish", "polsky")
    private val OTHER = setOf("ua", "ukr", "ru", "rus", "cz", "cze", "cs", "sk", "slo", "ita", "it", "ger", "de", "deu", "fr", "fre", "fra",
        "es", "spa", "hu", "hun", "lt", "lv", "ee", "tr", "eng", "en", "nl", "ro", "bg", "hr", "srb", "jp", "jpn", "kor")
    private val DUB = setOf("dub", "dubbing", "dubbed", "dubbingiem")
    private val SUB = setOf("sub", "subs", "subbed", "subtitles", "subtitle", "napisy", "napisami", "srt", "hardsub", "softsub")
    private val AUDIO = setOf("audio", "dźwięk", "dzwiek", "ścieżka", "sciezka", "voice", "voiceover")
    private val JOINED_DUB = setOf("pldub", "dubpl", "pldubbing", "dubbingpl")
    private val JOINED_SUB = setOf("plsub", "plsubs", "subpl", "subspl", "napisypl")
    private val JOINED_LEKTOR = setOf("lektorpl", "pllektor")

    /**
     * (7.5) Oznaczenia polskiej wersji z tokenów:
     * - „Lektor PL” – „lektor” (bez innego języka obok, np. „lektor UA”), „lektor PL”, „PL lektor”;
     * - „Dubbing PL” – dubbing z oznaczeniem PL obok („dubbing PL”, „PL dub”, „polski dubbing”) albo dubbing przy jedynej fladze 🇵🇱;
     * - „Audio PL” – polska ścieżka dźwiękowa („audio PL”, „PL audio”);
     * - „Napisy PL” – same napisy (np. „napisy PL”, „PL sub”, „plsub”) – NIE lektor ani dubbing;
     * - „PL” – samo oznaczenie języka bez kontekstu (niepewne: tylko podbija kolejność, nie oznacza lektora).
     */
    fun polishTags(tk: List<String>, onlyPolishFlag: Boolean = false, hasPolishFlag: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        fun near(i: Int, set: Set<String>) = tk.getOrNull(i - 1) in set || tk.getOrNull(i + 1) in set
        val plNearSubs = mutableSetOf<Int>()                 // indeksy „PL” należące do oznaczenia napisów
        val plNearAudio = mutableSetOf<Int>()
        for ((i, w) in tk.withIndex()) {
            when {
                w in JOINED_LEKTOR -> out += "Lektor PL"
                w == "lektor" -> if (!near(i, OTHER)) { out += "Lektor PL"; if (tk.getOrNull(i + 1) in PL) plNearAudio += i + 1; if (tk.getOrNull(i - 1) in PL) plNearAudio += i - 1 }
                w in JOINED_DUB -> out += "Dubbing PL"
                w in DUB -> when {
                    near(i, PL) -> { out += "Dubbing PL"; if (tk.getOrNull(i + 1) in PL) plNearAudio += i + 1; if (tk.getOrNull(i - 1) in PL) plNearAudio += i - 1 }
                    onlyPolishFlag && !near(i, OTHER) -> out += "Dubbing PL"
                }
                w in AUDIO && near(i, PL) -> { out += "Audio PL"; if (tk.getOrNull(i + 1) in PL) plNearAudio += i + 1; if (tk.getOrNull(i - 1) in PL) plNearAudio += i - 1 }
                w in JOINED_SUB -> out += "Napisy PL"
                w in SUB -> {
                    // „napisy” to polskie słowo – polskie napisy; inne („sub”, „subs”) – tylko z oznaczeniem PL obok
                    if (w == "napisy" || w == "napisami") { if (!near(i, OTHER)) out += "Napisy PL" }
                    else if (near(i, PL)) out += "Napisy PL"
                    if (tk.getOrNull(i + 1) in PL) plNearSubs += i + 1; if (tk.getOrNull(i - 1) in PL) plNearSubs += i - 1
                }
            }
        }
        // samo „PL” – tylko gdy nie jest częścią oznaczenia napisów / dźwięku i bez flagi 🇵🇱 (flaga już mówi o języku)
        val bare = tk.withIndex().any { (i, w) -> w in PL && i !in plNearSubs && i !in plNearAudio }
        if (bare && !hasPolishFlag && out.none { it.endsWith(" PL") }) out += "PL"
        return out.toList()
    }

    /** „15.2 GB”, „700 MB” w opisie (Torrentio: „💾 15.2 GB”). */
    fun size(t: String): Long {
        val m = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|GiB|MB|MiB)\\b").find(t) ?: return 0
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        val mul = when (m.groupValues[2].lowercase()) { "tb" -> 1e12; "gb", "gib" -> 1e9; else -> 1e6 }
        return (v * mul).toLong()
    }

    fun formatSize(b: Long): String = if (b >= 1e9) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else String.format(java.util.Locale.US, "%d MB", (b / 1e6).toLong())

    /** Kolejność rozdzielczości na liście źródeł (od najwyższej; nieokreślona i CAM na końcu). */
    val QUALITY_ORDER = listOf("4K", "1440p", "1080p", "720p", "480p", "", "CAM")

    /**
     * (7.4) Źródła, które deklarowały polskiego lektora / dubbing, a w odtwarzaczu okazały się bez polskiej ścieżki
     * (ścieżki z opisanym językiem, żadna polska). Na liście źródeł nie mają już oznaczenia lektora (najwyżej [MAX_NOT_PL]).
     */
    private val notPolish: MutableSet<String> by lazy {
        java.util.Collections.synchronizedSet(LinkedHashSet(runCatching { Prefs.stremioNotPolish.split('\n').filter { it.isNotBlank() } }.getOrDefault(emptyList())))
    }
    const val MAX_NOT_PL = 500

    fun verifiedNoPolishAudio(s: StremioStream): Boolean = s.key.isNotBlank() && s.key.hashCode().toString() in notPolish

    fun markNoPolishAudio(s: StremioStream) {
        if (s.key.isBlank()) return
        notPolish += s.key.hashCode().toString()
        val l = synchronized(notPolish) { notPolish.toList() }.takeLast(MAX_NOT_PL)
        runCatching { Prefs.stremioNotPolish = l.joinToString("\n") }
    }

    /** Polska wersja: 0 – dubbing / lektor, 1 – napisy PL / flaga / „PL”, 2 – brak. */
    fun polishRank(s: StremioStream): Int {
        val l = s.languages
        return when {
            "Dubbing PL" in l || "Lektor PL" in l || "Audio PL" in l -> 0
            "Napisy PL" in l || "🇵🇱" in l || "PL" in l || s.subtitles.any { it.lang.lowercase().let { x -> x == "pl" || x == "pol" } } -> 1
            else -> 2
        }
    }

    /**
     * Źródła wtyczki: najwyżej [n] z KAŻDEJ rozdzielczości (4K, 1440p, 1080p, 720p, 480p, nieokreślona, CAM), w każdej najpierw
     * polska wersja (dubbing / lektor, potem napisy PL), dalej kolejność wtyczki (sama sortuje wg jakości / popularności).
     */
    fun limit(l: List<StremioStream>, n: Int = PER_ADDON): List<StremioStream> {
        val byQ = l.groupBy { it.quality }
        return QUALITY_ORDER.flatMap { q -> byQ[q].orEmpty().sortedBy { polishRank(it) }.take(n) } +
            byQ.filterKeys { it !in QUALITY_ORDER }.values.flatMap { g -> g.sortedBy { polishRank(it) }.take(n) }
    }

    /**
     * Źródło następnego odcinka z tej samej wtyczki: ta sama grupa (bingeGroup, jak w Stremio), potem ta sama jakość,
     * potem pierwsze odtwarzalne.
     */
    /** (7.8) Kolejność jakości (indeks w [QUALITY_ORDER]; nieznane i CAM na końcu). */
    private fun qualityIndex(q: String) = QUALITY_ORDER.indexOf(q).let { if (it < 0) QUALITY_ORDER.size else it }

    /**
     * (7.8) Źródło do wznowienia / następnego odcinka: ta sama wtyczka i grupa ([bingeGroup]), ta sama wtyczka i jakość, najlepsze
     * z tej wtyczki, wreszcie najlepsze ze wszystkich (polska wersja pierwsza, potem najwyższa rozdzielczość, bez CAM). Tylko HTTP.
     */
    fun pickResume(addonUrl: String, bingeGroup: String, quality: String, candidates: List<StremioStream>): StremioStream? {
        val playable = candidates.filter { it.kind == StremioStream.Kind.HTTP }
        val best = compareBy<StremioStream>({ polishRank(it) }, { qualityIndex(it.quality) })
        val same = playable.filter { addonUrl.isNotBlank() && it.addonUrl == addonUrl }
        if (bingeGroup.isNotBlank()) same.firstOrNull { it.bingeGroup == bingeGroup }?.let { return it }
        if (quality.isNotBlank()) same.filter { it.quality == quality }.minWithOrNull(best)?.let { return it }
        same.filter { it.quality != "CAM" }.minWithOrNull(best)?.let { return it }
        return playable.filter { it.quality != "CAM" }.minWithOrNull(best) ?: playable.firstOrNull()
    }

    fun pickNext(prev: StremioStream, candidates: List<StremioStream>): StremioStream? {
        val playable = candidates.filter { it.kind == StremioStream.Kind.HTTP }
        if (prev.bingeGroup.isNotBlank()) playable.firstOrNull { it.bingeGroup == prev.bingeGroup }?.let { return it }
        prev.quality.takeIf { it.isNotBlank() }?.let { q -> playable.firstOrNull { it.quality == q }?.let { return it } }
        return playable.firstOrNull()
    }
}

/** Sieć: konto (api.strem.io), wtyczki (protokół HTTP), Cinemeta. Adresy i klient do podmiany w testach. */
object StremioApi {
    var apiBase = "https://api.strem.io/api"
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
    /** Wtyczki bez konta (jak domyślne w Stremio): metadane i napisy. */
    var defaultAddons = listOf(CINEMETA, OPENSUBTITLES)
    var client: () -> OkHttpClient = { Repository.http }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw StremioException("HTTP ${r.code}", r.code)
            JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    private suspend fun api(method: String, body: JSONObject): Any? = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder().url("$apiBase/$method").post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: throw StremioException("Błąd serwera Stremio (HTTP ${r.code})", r.code)
            o.optJSONObject("error")?.let { e -> throw StremioException(e.optString("message", "Błąd Stremio"), e.optInt("code")) }
            if (!r.isSuccessful) throw StremioException("HTTP ${r.code}", r.code)
            o.opt("result")
        }
    }

    // ---------------- konto ----------------

    data class Session(val authKey: String, val email: String)

    suspend fun login(email: String, password: String): Session {
        val res = api("login", JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false)) as? JSONObject
            ?: throw StremioException("Nieprawidłowa odpowiedź logowania")
        val key = res.optString("authKey").ifBlank { throw StremioException("Brak klucza sesji") }
        return Session(key, res.optJSONObject("user")?.optString("email").orEmpty().ifBlank { email })
    }

    /** Sprawdza klucz API (logowanie tokenem) – zwraca e-mail konta. */
    suspend fun user(authKey: String): String {
        val res = api("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey)) as? JSONObject
            ?: throw StremioException("Nieprawidłowy token")
        return res.optString("email")
    }

    suspend fun logout(authKey: String) { runCatching { api("logout", JSONObject().put("type", "Logout").put("authKey", authKey)) } }

    // ---------------- logowanie kodem QR (oficjalne „połącz urządzenie”, link.stremio.com) ----------------

    var linkBase = "https://link.stremio.com/api/v2"

    /** Kod do połączenia: [link] – strona do zeskanowania / otwarcia na zalogowanym telefonie, [qr] – gotowy obraz kodu QR. */
    data class Link(val code: String, val link: String, val qr: String)

    suspend fun linkCreate(): Link {
        val o = get("$linkBase/create?type=Create")
        val r = o.optJSONObject("result") ?: o
        val code = r.optString("code").ifBlank { throw StremioException(o.optJSONObject("error")?.optString("message") ?: "Nie udało się utworzyć kodu") }
        val link = r.optString("link").ifBlank { "https://link.stremio.com/$code" }
        return Link(code, link, r.optString("qrcode").ifBlank { "https://link.stremio.com/qr?data=" + java.net.URLEncoder.encode(link, "UTF-8") })
    }

    /** Klucz sesji po potwierdzeniu kodu na telefonie; null – jeszcze niepotwierdzony (serwer zwraca wtedy błąd). */
    suspend fun linkRead(code: String): String? {
        val o = runCatching { get("$linkBase/read?type=Read&code=" + java.net.URLEncoder.encode(code, "UTF-8")) }.getOrNull() ?: return null
        return o.optJSONObject("result")?.optString("authKey")?.takeIf { it.isNotBlank() }
    }

    /** Wtyczki zainstalowane na koncie (z manifestami). */
    suspend fun addonCollection(authKey: String): List<StremioAddon> {
        val res = api("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true)) as? JSONObject
            ?: return emptyList()
        val arr = res.optJSONArray("addons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
            val url = a.optString("transportUrl").ifBlank { return@mapNotNull null }
            StremioAddon.parse(url, a.optJSONObject("manifest") ?: return@mapNotNull null)
        }
    }

    /** Pozycja biblioteki Stremio (postęp oglądania). */
    data class LibraryItem(val id: String, val type: String, val name: String, val poster: String, val videoId: String,
                           val timeOffset: Long, val duration: Long, val lastWatched: Long, val removed: Boolean)

    suspend fun library(authKey: String): List<LibraryItem> {
        val res = api("datastoreGet", JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        val arr = res as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val st = o.optJSONObject("state") ?: JSONObject()
            LibraryItem(o.optString("_id"), o.optString("type"), o.optString("name"), o.optString("poster"), st.optString("video_id").takeIf { it != "null" }.orEmpty(),
                st.optLong("timeOffset"), st.optLong("duration"), isoMs(st.optString("lastWatched")), o.optBoolean("removed"))
        }
    }

    /** Zapis postępu w bibliotece konta (format LibraryItem z stremio-core). */
    suspend fun putLibrary(authKey: String, items: List<JSONObject>) {
        if (items.isEmpty()) return
        api("datastorePut", JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", JSONArray(items)))
    }

    fun libraryJson(p: StremioProgress, now: Long = System.currentTimeMillis()): JSONObject {
        val iso = iso(now)
        return JSONObject().put("_id", p.metaId).put("name", p.name).put("type", p.type).put("poster", p.poster).put("posterShape", "poster")
            .put("removed", false).put("temp", false).put("_ctime", iso(p.createdAt.takeIf { it > 0 } ?: now)).put("_mtime", iso)
            .put("state", JSONObject().put("lastWatched", iso(p.updatedAt)).put("timeWatched", p.positionMs).put("timeOffset", p.positionMs)
                .put("overallTimeWatched", p.positionMs).put("timesWatched", 0).put("flaggedWatched", 0).put("duration", p.durationMs)
                .put("video_id", if (p.videoId.isNotBlank()) p.videoId else JSONObject.NULL).put("watched", JSONObject.NULL).put("noNotif", false))
            .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL))
    }

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    fun isoMs(s: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
    }.getOrDefault(0L)

    // ---------------- wtyczki ----------------

    suspend fun manifest(transportUrl: String): StremioAddon {
        val url = StremioAddon.normalizeUrl(transportUrl)
        return StremioAddon.parse(url, get(url))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun catalog(a: StremioAddon, c: StremioAddon.Catalog, search: String? = null): List<StremioMetaPreview> {
        val extra = search?.let { "/search=" + enc(it) }.orEmpty()
        val o = get("${a.base}/catalog/${c.type}/${enc(c.id)}$extra.json")
        return withContext(Dispatchers.Default) { parseCatalog(c, o) }
    }

    private fun parseCatalog(c: StremioAddon.Catalog, o: JSONObject): List<StremioMetaPreview> {
        val arr = o.optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { m ->
            StremioMetaPreview(m.optString("id").ifBlank { m.optString("imdb_id") }, m.optString("type", c.type), m.optString("name"), m.optString("poster"),
                m.optString("description"), m.optString("releaseInfo").ifBlank { m.optString("year") }, m.optString("imdbRating"),
                m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty())
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    suspend fun meta(a: StremioAddon, type: String, id: String): StremioMeta? {
        val m = get("${a.base}/meta/$type/${enc(id)}.json").optJSONObject("meta") ?: return null
        val videos = m.optJSONArray("videos")?.let { v -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.map { e ->
            StremioVideo(e.optString("id"), e.optString("name").ifBlank { e.optString("title") }, e.optInt("season"), e.optInt("episode", e.optInt("number")),
                e.optString("released").take(10), e.optString("thumbnail"), e.optString("overview").ifBlank { e.optString("description") })
        } }.orEmpty()
        return StremioMeta(m.optString("id", id), m.optString("type", type), m.optString("name"), m.optString("poster"), m.optString("background"),
            m.optString("logo"), m.optString("description"), m.optString("releaseInfo").ifBlank { m.optString("year") }, m.optString("imdbRating"),
            m.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty(), m.optString("runtime"), videos)
    }

    /** (7.8) Limit czasu odpowiedzi jednej wtyczki ze źródłami. */
    const val STREAMS_TIMEOUT_MS = 20_000L

    /**
     * (7.8) Źródła ze wszystkich [addons] równolegle (wątki robocze, limit [timeoutMs] na wtyczkę, anulowanie z zadaniem nadrzędnym);
     * wtyczka z błędem lub po czasie – pominięta.
     */
    suspend fun collectStreams(addons: List<StremioAddon>, type: String, id: String, timeoutMs: Long = STREAMS_TIMEOUT_MS): List<StremioStream> =
        kotlinx.coroutines.coroutineScope {
            addons.map { a -> async(Dispatchers.Default) {
                try { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { streams(a, type, id) }.orEmpty() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { emptyList() }
            } }.map { it.await() }.flatten()
        }

    suspend fun streams(a: StremioAddon, type: String, id: String): List<StremioStream> {
        val o = get("${a.base}/stream/$type/${enc(id)}.json")
        // (7.8) parsowanie setek źródeł poza głównym wątkiem
        return withContext(Dispatchers.Default) { parseStreams(a, o) }
    }

    private fun parseStreams(a: StremioAddon, o: JSONObject): List<StremioStream> {
        val arr = o.optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { s ->
            val bh = s.optJSONObject("behaviorHints") ?: JSONObject()
            val headers = bh.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
            val subs = s.optJSONArray("subtitles")?.let { sa -> (0 until sa.length()).mapNotNull { sa.optJSONObject(it) }.map {
                StremioSubtitle(it.optString("id"), it.optString("url"), it.optString("lang"), a.name) } }.orEmpty()
            StremioStream(a.name, a.transportUrl, s.optString("name"), s.optString("title"), s.optString("description"), s.optString("url"),
                s.optString("infoHash"), s.optInt("fileIdx", -1), s.optString("ytId"), s.optString("externalUrl"), bh.optString("bingeGroup"),
                bh.optString("filename"), bh.optLong("videoSize"), headers, subs)
        }.filter { it.kind != StremioStream.Kind.NONE }
    }

    suspend fun subtitles(a: StremioAddon, type: String, id: String, filename: String = "", videoSize: Long = 0): List<StremioSubtitle> {
        val extra = buildList { if (filename.isNotBlank()) add("filename=" + enc(filename)); if (videoSize > 0) add("videoSize=$videoSize") }
        val o = get("${a.base}/subtitles/$type/${enc(id)}" + (if (extra.isEmpty()) "" else "/" + extra.joinToString("&")) + ".json")
        val arr = o.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { StremioSubtitle(it.optString("id"), it.optString("url"), it.optString("lang"), a.name) }
            .filter { it.url.isNotBlank() }
    }

    /** Tekst napisów (UTF-8, a przy błędnych bajtach – Windows-1250, typowe dla polskich plików SRT). */
    suspend fun subtitleText(url: String): String = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw StremioException("Napisy: HTTP ${r.code}", r.code)
            decode(r.body?.bytes() ?: ByteArray(0))
        }
    }

    fun decode(b: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrElse { String(b, charset("windows-1250")) }.removePrefix("﻿")
    }
}

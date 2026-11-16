package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/** Segment do pominięcia (sekundy). */
data class SbSegment(val start: Double, val end: Double, val category: String) {
    val startMs get() = (start * 1000).toLong()
    val endMs get() = (end * 1000).toLong()
}

/**
 * SponsorBlock (sponsor.ajay.app): segmenty sponsorowane, autopromocja, prośby o subskrypcję itp. zgłoszone przez społeczność.
 * Prywatność: zapytanie o 4 pierwsze znaki skrótu SHA-256 identyfikatora – serwer nie wie, który film jest oglądany.
 */
object SponsorBlock {
    var apiBase = "https://sponsor.ajay.app/api"

    /** Kategorie (id API → nazwa w Ustawieniach); kolejność = lista w Ustawieniach. */
    val CATEGORIES = linkedMapOf(
        "sponsor" to "Sponsor", "selfpromo" to "Autopromocja", "interaction" to "Prośba o subskrypcję / polubienie",
        "intro" to "Wstęp / czołówka", "outro" to "Zakończenie / napisy końcowe", "preview" to "Zapowiedź / podsumowanie",
        "music_offtopic" to "Muzyka: fragment bez muzyki", "filler" to "Dygresje",
    )

    fun label(category: String) = CATEGORIES[category] ?: category

    fun hashPrefix(videoId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    /** Segmenty „skip” filmu [videoId] w wybranych [categories], posortowane; brak / błąd sieci = pusta lista (odtwarzanie bez przerw). */
    suspend fun segments(videoId: String, categories: List<String> = Prefs.sbCategories): List<SbSegment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        val url = "$apiBase/skipSegments/${hashPrefix(videoId)}".toHttpUrl().newBuilder()
            .addQueryParameter("categories", JSONArray(categories).toString())
            .addQueryParameter("actionTypes", JSONArray(listOf("skip")).toString())
            .build()
        runCatching {
            Repository.http.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r ->
                if (r.code == 404 || !r.isSuccessful) return@use emptyList()     // 404 = brak segmentów dla tego prefiksu
                parse(r.body!!.string(), videoId, categories)
            }
        }.getOrDefault(emptyList())
    }

    fun parse(json: String, videoId: String, categories: List<String>): List<SbSegment> {
        val arr = JSONArray(json)
        val out = mutableListOf<SbSegment>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            if (v.optString("videoID") != videoId) continue
            val segs = v.optJSONArray("segments") ?: continue
            for (j in 0 until segs.length()) {
                val s = segs.optJSONObject(j) ?: continue
                if (s.optString("actionType", "skip") != "skip") continue
                val cat = s.optString("category"); if (cat !in categories) continue
                val r = s.optJSONArray("segment") ?: continue
                val a = r.optDouble(0, -1.0); val b = r.optDouble(1, -1.0)
                if (a >= 0 && b > a + 0.5) out += SbSegment(a, b, cat)      // krótsze niż 0,5 s – pomijane (bez „szarpania”)
            }
        }
        return out.sortedBy { it.start }
    }

    /** Segment obejmujący pozycję [posMs] (z marginesem na końcu, by nie wracać w ten sam segment), albo null. */
    fun at(segments: List<SbSegment>, posMs: Long): SbSegment? = segments.firstOrNull { posMs >= it.startMs && posMs < it.endMs - 300 }
}

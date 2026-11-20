package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/**
 * Podpowiedzi wyszukiwania na żywo (zapytania, które inni wpisują w YouTube) dla wpisywanego ciągu. Wysyłany jest tylko
 * wpisany tekst, krótki limit czasu, wynik w pamięci ([CACHE_MAX] fraz); błąd sieci = pusta lista (zostają frazy z historii).
 * Wywołanie w tle (Dispatchers.IO).
 */
object YtSuggest {
    var apiBase = "https://suggestqueries.google.com/complete/search"
    const val CACHE_MAX = 60
    const val LIMIT = 8
    const val TIMEOUT_S = 3L

    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>) = size > CACHE_MAX
    }
    private val client by lazy { Repository.http.newBuilder().callTimeout(TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build() }

    suspend fun fetch(typed: String): List<String> {
        val q = typed.trim().lowercase()
        if (q.length < 2) return emptyList()
        synchronized(cache) { cache[q]?.let { return it } }
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val url = apiBase.toHttpUrl().newBuilder().addQueryParameter("client", "firefox").addQueryParameter("ds", "yt").addQueryParameter("q", q).build()
                client.newCall(Request.Builder().url(url).header("User-Agent", "D-iptv-Player").build()).execute().use { r ->
                    if (!r.isSuccessful) emptyList() else parse(r.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
        }
        if (list.isNotEmpty()) synchronized(cache) { cache[q] = list }
        return list
    }

    /** Odpowiedź: [„fraza”, [„podpowiedź”, …]]. */
    fun parse(body: String): List<String> = runCatching {
        val a = JSONArray(body).optJSONArray(1) ?: return emptyList()
        (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.take(LIMIT)
    }.getOrDefault(emptyList())
}

package pl.iptv.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * (7.17) Biblioteka Stremio na urządzeniu: tytuły dodane przyciskiem „Dodaj do biblioteki” na ekranie szczegółów
 * (filtr „Biblioteka” na ekranie Stremio). Najnowsze na początku, najwyżej [MAX]; zapis w [Prefs.stremioLibrary] (JSON).
 */
object StremioLibrary {
    const val MAX = 300

    fun all(): List<StremioMetaPreview> = runCatching {
        val a = JSONArray(Prefs.stremioLibrary.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            StremioMetaPreview(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("poster"),
                releaseInfo = o.optString("year"), imdbRating = o.optString("rating"))
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    fun contains(id: String) = all().any { it.id == id }

    fun add(m: StremioMetaPreview) = save(listOf(m) + all().filter { it.id != m.id })
    fun remove(id: String) = save(all().filter { it.id != id })

    /** Przełącza obecność tytułu w bibliotece; zwraca true, gdy jest teraz w bibliotece. */
    fun toggle(m: StremioMetaPreview): Boolean = if (contains(m.id)) { remove(m.id); false } else { add(m); true }

    private fun save(list: List<StremioMetaPreview>) {
        Prefs.stremioLibrary = JSONArray().apply {
            list.take(MAX).forEach { m ->
                put(JSONObject().put("id", m.id).put("type", m.type).put("name", m.name).put("poster", m.poster)
                    .put("year", m.releaseInfo).put("rating", m.imdbRating))
            }
        }.toString()
    }
}

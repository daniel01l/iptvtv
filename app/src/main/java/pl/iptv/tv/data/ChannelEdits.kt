package pl.iptv.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Zmiany użytkownika dla jednego kanału. null = bez zmian. */
data class ChannelOverride(
    var name: String? = null,
    var group: String? = null,
    var hidden: Boolean = false,
    /** null = auto, "" = brak EPG, inne = id XMLTV */
    var epg: String? = null,
) {
    fun isEmpty() = name == null && group == null && !hidden && epg == null
}

class EditStore(
    val order: MutableList<String> = mutableListOf(),
    val items: MutableMap<String, ChannelOverride> = mutableMapOf(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("order", JSONArray(order))
        val it = JSONObject()
        for ((k, v) in items) {
            val j = JSONObject()
            v.name?.let { j.put("name", it) }
            v.group?.let { j.put("group", it) }
            if (v.hidden) j.put("hidden", true)
            v.epg?.let { j.put("epg", it) }
            it.put(k, j)
        }
        o.put("items", it)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): EditStore {
            val o = JSONObject(s)
            val store = EditStore()
            o.optJSONArray("order")?.let { a -> for (i in 0 until a.length()) store.order.add(a.getString(i)) }
            o.optJSONObject("items")?.let { items ->
                val keys = items.keys()
                while (keys.hasNext()) {
                    val k = keys.next(); val j = items.getJSONObject(k)
                    store.items[k] = ChannelOverride(
                        name = if (j.has("name")) j.getString("name") else null,
                        group = if (j.has("group")) j.getString("group") else null,
                        hidden = j.optBoolean("hidden", false),
                        epg = if (j.has("epg")) j.getString("epg") else null,
                    )
                }
            }
            return store
        }
    }
}

object ChannelEdits {
    /**
     * Nakłada edycje na oryginalną listę. Zwraca wszystkie kanały (także ukryte)
     * w kolejności użytkownika; kanały nowe (spoza zapisanej kolejności) trafiają na koniec.
     */
    fun apply(raw: List<Channel>, s: EditStore): List<Channel> {
        val byKey = raw.associateBy { it.key }
        val used = HashSet<String>()
        val ordered = ArrayList<Channel>(raw.size)
        s.order.forEach { k -> byKey[k]?.let { if (used.add(k)) ordered.add(it) } }
        raw.forEach { if (used.add(it.key)) ordered.add(it) }
        var n = 0
        return ordered.map { c ->
            val o = s.items[c.key]
            val hidden = o?.hidden == true
            c.copy(
                name = o?.name?.takeIf { it.isNotBlank() } ?: c.origName,
                group = o?.group?.takeIf { it.isNotBlank() } ?: c.origGroup,
                hidden = hidden,
                epgOverride = o?.epg,
                number = if (hidden) 0 else ++n,
            )
        }
    }

    /**
     * Przenosi [key] na miejsce [targetKey] w pełnej kolejności [current].
     * Przenoszenie w dół wstawia za celem, w górę – przed celem.
     */
    fun move(current: List<String>, key: String, targetKey: String): MutableList<String> {
        val list = current.toMutableList()
        val from = list.indexOf(key); val to = list.indexOf(targetKey)
        if (from < 0 || to < 0 || from == to) return list
        list.removeAt(from)
        list.add(to, key)
        return list
    }
}

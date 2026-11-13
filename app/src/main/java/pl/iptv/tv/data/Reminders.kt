package pl.iptv.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Reminder(
    val channelKey: String,
    val start: Long,
    val end: Long,
    val title: String,
    val subTitle: String = "",
    val episode: String = "",
    /** Dodane automatycznie z reguły serii. */
    val fromSeries: Boolean = false,
    var notified: Boolean = false,
) {
    val id get() = "$channelKey|$start"
}

/** Reguła „przypominaj o wszystkich odcinkach”; channelKey null = na każdym kanale. */
data class SeriesRule(val key: String, val title: String, val channelKey: String? = null)

object Reminders {
    private var file: File? = null
    private val reminders = ArrayList<Reminder>()
    private val rules = ArrayList<SeriesRule>()

    fun init(f: File) {
        file = f
        reminders.clear(); rules.clear()
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONArray("reminders")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                    reminders += Reminder(j.getString("ch"), j.getLong("start"), j.getLong("end"), j.getString("title"),
                        j.optString("sub"), j.optString("ep"), j.optBoolean("series"), j.optBoolean("notified"))
                }
            }
            o.optJSONArray("rules")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                    rules += SeriesRule(j.getString("key"), j.getString("title"), if (j.has("ch")) j.getString("ch") else null)
                }
            }
        }
    }

    @Synchronized private fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("reminders", JSONArray().apply {
            reminders.forEach { r -> put(JSONObject().apply {
                put("ch", r.channelKey); put("start", r.start); put("end", r.end); put("title", r.title)
                put("sub", r.subTitle); put("ep", r.episode); put("series", r.fromSeries); put("notified", r.notified)
            }) }
        })
        o.put("rules", JSONArray().apply {
            rules.forEach { r -> put(JSONObject().apply { put("key", r.key); put("title", r.title); r.channelKey?.let { put("ch", it) } }) }
        })
        runCatching { f.writeText(o.toString()) }
    }

    @Synchronized fun all(): List<Reminder> = reminders.sortedBy { it.start }
    @Synchronized fun rules(): List<SeriesRule> = rules.toList()

    @Synchronized fun isSet(ch: Channel, p: Programme) = reminders.any { it.channelKey == ch.key && it.start == p.start }
    @Synchronized fun ruleFor(p: Programme): SeriesRule? { val k = Series.key(p.title); return rules.firstOrNull { it.key == k } }

    @Synchronized fun add(ch: Channel, p: Programme, fromSeries: Boolean = false): Boolean {
        if (isSet(ch, p)) return false
        reminders += Reminder(ch.key, p.start, p.end, p.title, p.subTitle, p.episode, fromSeries)
        save(); return true
    }

    @Synchronized fun remove(channelKey: String, start: Long) {
        reminders.removeAll { it.channelKey == channelKey && it.start == start }; save()
    }

    @Synchronized fun addRule(p: Programme, ch: Channel?) {
        val k = Series.key(p.title)
        rules.removeAll { it.key == k }
        rules += SeriesRule(k, Series.baseTitle(p.title), ch?.key)
        save()
    }

    /** Usuwa regułę serii i przyszłe przypomnienia, które z niej powstały. */
    @Synchronized fun removeRule(key: String) {
        rules.removeAll { it.key == key }
        reminders.removeAll { it.fromSeries && Series.key(it.title) == key && !it.notified }
        save()
    }

    /**
     * Zamienia reguły serii na konkretne przypomnienia (dla odcinków widocznych w EPG)
     * i usuwa stare wpisy. [programmes] – wszystkie programy kanałów.
     */
    @Synchronized fun expandRules(channels: List<Channel>, programmes: (Channel) -> List<Programme>, now: Long) {
        reminders.removeAll { it.end < now - 3_600_000L }
        for (rule in rules) {
            val chs = if (rule.channelKey == null) channels else channels.filter { it.key == rule.channelKey }
            for (ch in chs) for (p in programmes(ch)) {
                if (p.start > now && Series.key(p.title) == rule.key && !isSet(ch, p))
                    reminders += Reminder(ch.key, p.start, p.end, p.title, p.subTitle, p.episode, fromSeries = true)
            }
        }
        // ta sama audycja nadawana równocześnie na kilku kanałach (np. HD/SD) – jedno przypomnienie na serię i godzinę
        val seen = HashSet<String>()
        reminders.removeAll { r -> r.fromSeries && !seen.add(Series.key(r.title) + "|" + r.start) }
        save()
    }

    /** Przypomnienia do pokazania teraz (start - wyprzedzenie <= now). Oznacza je jako pokazane. */
    @Synchronized fun takeDue(now: Long, leadMs: Long): List<Reminder> {
        val due = reminders.filter { !it.notified && it.start - leadMs <= now + 15_000 && it.end > now }
        due.forEach { it.notified = true }
        // pominięte (urządzenie było wyłączone) – oznacz bez pokazywania
        reminders.filter { !it.notified && it.end <= now }.forEach { it.notified = true }
        if (due.isNotEmpty()) save()
        return due
    }

    /** Najbliższy moment, w którym trzeba pokazać przypomnienie (ms) lub null. */
    @Synchronized fun nextTrigger(now: Long, leadMs: Long): Long? =
        reminders.filter { !it.notified && it.end > now }.minOfOrNull { (it.start - leadMs).coerceAtLeast(now + 1000) }
}

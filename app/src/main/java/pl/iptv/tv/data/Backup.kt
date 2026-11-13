package pl.iptv.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Kopia zapasowa: wszystkie ustawienia + edycje listy + przypomnienia. */
object Backup {
    private val files = listOf("edits.json", "reminders.json", "recordings.json")
    private val skip = setOf("parental_pin")      // PIN nie jest eksportowany

    fun export(c: Context): String {
        val sp = c.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        val prefs = JSONObject()
        for ((k, v) in sp.all) {
            if (k in skip || v == null) continue
            prefs.put(k, JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }).put("v", v.toString()))
        }
        val f = JSONObject()
        files.forEach { n -> File(c.filesDir, n).takeIf { it.exists() }?.let { f.put(n, it.readText()) } }
        return JSONObject().put("app", "IptvTv").put("version", 1).put("prefs", prefs).put("files", f).toString(2)
    }

    /** Zwraca liczbę przywróconych ustawień. */
    fun import(c: Context, json: String): Int {
        // plik mógł zostać zapisany z BOM lub z pustymi liniami (inne edytory / przesłanie przez telefon)
        val text = json.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val o = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Plik nie jest kopią zapasową (.json)") }
        require(o.optString("app") in setOf("IptvTv", "D-iptv") && o.has("prefs")) { "To nie jest kopia D-iptv Player" }
        val prefs = o.getJSONObject("prefs")
        val e = c.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
        var n = 0
        for (k in prefs.keys()) {
            if (k in skip) continue
            val j = prefs.optJSONObject(k) ?: continue; val v = j.optString("v")
            // pojedyncza błędna wartość nie przerywa przywracania
            runCatching {
                when (j.optString("t")) {
                    "b" -> e.putBoolean(k, v.toBoolean()); "i" -> e.putInt(k, v.toInt()); "l" -> e.putLong(k, v.toLong())
                    "f" -> e.putFloat(k, v.toFloat()); else -> e.putString(k, v)
                }
                n++
            }
        }
        e.commit()
        o.optJSONObject("files")?.let { f -> for (name in f.keys()) if (name in files) File(c.filesDir, name).writeText(f.getString(name)) }
        return n
    }
}

package pl.iptv.tv.ui

import org.json.JSONArray
import org.json.JSONObject
import pl.iptv.tv.data.Prefs

/**
 * (7.7) Własne motywy: motyw bazowy ([Ui.preset]) + zmiany użytkownika z Ustawień → Wygląd (kolory, gradienty, czcionka,
 * animacje, pasek informacji, powiększenie – [Ui.PRESET_KEYS]). Zapis pod nazwą (do [MAX]) i przywrócenie jednym wyborem.
 * Przed przełączeniem na inny motyw własne zmiany zapisują się same jako „Własny – <motyw>” ([autoSave]) – nie giną.
 */
object CustomThemes {
    data class Saved(val name: String, val base: String, val values: Map<String, Any>, val auto: Boolean = false)

    const val MAX = 12
    const val AUTO_PREFIX = "Własny – "

    /** Bieżący wygląd ma zmiany względem motywu bazowego. */
    fun hasOverrides(): Boolean = Prefs.snapshot(Ui.PRESET_KEYS.toList()).isNotEmpty()

    fun list(): List<Saved> = runCatching { parse(JSONArray(Prefs.customThemes)) }.getOrDefault(emptyList())

    /** Zapis bieżącego wyglądu pod nazwą [name] (ta sama nazwa – nadpisanie). */
    fun save(name: String, auto: Boolean = false): Saved {
        val s = Saved(name.trim().ifBlank { "Mój motyw" }, Ui.preset.id, Prefs.snapshot(Ui.PRESET_KEYS.toList()), auto)
        val l = list().filter { it.name != s.name }.toMutableList()
        l.add(0, s)
        // ponad limit: najpierw odpadają najstarsze automatyczne zapisy, motywy nazwane przez użytkownika zostają
        while (l.size > MAX) { val i = l.indexOfLast { it.auto && it !== s }.takeIf { it >= 0 } ?: l.lastIndex; l.removeAt(i) }
        store(l)
        return s
    }

    /** Przed zmianą motywu: własne zmiany bieżącego motywu zapisane jako „Własny – <motyw>” (bez nich – nic). */
    fun autoSave() {
        if (!hasOverrides()) return
        save(AUTO_PREFIX + Ui.preset.name, auto = true)
    }

    /** Motyw bazowy i zapisane zmiany. */
    fun apply(s: Saved) {
        // ten sam motyw bazowy – applyPreset nie zapisze sam bieżących zmian (wyzeruje je), więc zapis tutaj
        if (s.base == Prefs.theme && s.values != Prefs.snapshot(Ui.PRESET_KEYS.toList())) autoSave()
        Ui.applyPreset(s.base)
        Prefs.restore(s.values)
        Ui.changed()
    }

    fun delete(name: String) = store(list().filter { it.name != name })

    private fun store(l: List<Saved>) {
        Prefs.customThemes = JSONArray(l.map { s ->
            JSONObject().put("name", s.name).put("base", s.base).put("auto", s.auto).put("values", JSONObject().apply {
                s.values.forEach { (k, v) ->
                    put(k, JSONObject().put("t", when (v) { is Int -> "i"; is Long -> "l"; is Float -> "f"; is Boolean -> "b"; else -> "s" }).put("v", v.toString()))
                }
            })
        }).toString()
    }

    private fun parse(a: JSONArray): List<Saved> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val vo = o.optJSONObject("values") ?: JSONObject()
        val values = vo.keys().asSequence().mapNotNull { k ->
            val e = vo.optJSONObject(k) ?: return@mapNotNull null
            val v = e.optString("v")
            val parsed: Any? = when (e.optString("t")) {
                "i" -> v.toIntOrNull(); "l" -> v.toLongOrNull(); "f" -> v.toFloatOrNull(); "b" -> v.toBooleanStrictOrNull(); else -> v
            }
            parsed?.let { k to it }
        }.toMap()
        Saved(o.optString("name"), o.optString("base", "modern"), values, o.optBoolean("auto"))
    }
}

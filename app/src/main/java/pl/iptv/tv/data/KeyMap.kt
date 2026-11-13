package pl.iptv.tv.data

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** Przypisanie klawiszy pilota do akcji w odtwarzaczu. */
object KeyMap {
    enum class Action(val label: String) {
        CH_PREV("Poprzedni kanał (w górę listy)"), CH_NEXT("Następny kanał (w dół listy)"),
        PANEL_CHANNELS("Panel kanałów i programu"), PANEL_RECENT("Panel ostatnio oglądanych"),
        QUICK_MENU("Menu funkcji"), INFO("Pasek informacji"), GUIDE("Program TV (przewodnik)"),
        LAST("Powrót do poprzedniego kanału"), FAV("Dodaj/usuń z ulubionych"), RECORD("Nagrywanie"),
        AUDIO("Ścieżka audio"), SUBS("Napisy"), ASPECT("Proporcje obrazu"), SKIP_AD("Pomiń reklamę (archiwum)"),
    }

    val PRESETS = listOf(
        "Standard: ▲▼ kanały, ◄ ► panele" to mapOf(
            Action.CH_PREV to listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP),
            Action.CH_NEXT to listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN),
            Action.PANEL_CHANNELS to listOf(KeyEvent.KEYCODE_DPAD_LEFT), Action.PANEL_RECENT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT),
            Action.INFO to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO),
            Action.QUICK_MENU to listOf(KeyEvent.KEYCODE_MENU), Action.GUIDE to listOf(KeyEvent.KEYCODE_GUIDE),
            Action.LAST to listOf(KeyEvent.KEYCODE_LAST_CHANNEL), Action.FAV to listOf(KeyEvent.KEYCODE_PROG_YELLOW),
            Action.RECORD to listOf(KeyEvent.KEYCODE_MEDIA_RECORD), Action.AUDIO to listOf(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
            Action.SUBS to listOf(KeyEvent.KEYCODE_CAPTIONS), Action.ASPECT to listOf(KeyEvent.KEYCODE_TV_ZOOM_MODE), Action.SKIP_AD to listOf(KeyEvent.KEYCODE_PROG_BLUE),
        ),
        "Boczne: ◄ ► kanały, ▲ ▼ panele" to mapOf(
            Action.CH_PREV to listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP),
            Action.CH_NEXT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_DOWN),
            Action.PANEL_CHANNELS to listOf(KeyEvent.KEYCODE_DPAD_UP), Action.PANEL_RECENT to listOf(KeyEvent.KEYCODE_DPAD_DOWN),
            Action.INFO to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO),
            Action.QUICK_MENU to listOf(KeyEvent.KEYCODE_MENU), Action.GUIDE to listOf(KeyEvent.KEYCODE_GUIDE),
            Action.LAST to listOf(KeyEvent.KEYCODE_LAST_CHANNEL), Action.FAV to listOf(KeyEvent.KEYCODE_PROG_YELLOW),
            Action.RECORD to listOf(KeyEvent.KEYCODE_MEDIA_RECORD), Action.AUDIO to listOf(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
            Action.SUBS to listOf(KeyEvent.KEYCODE_CAPTIONS), Action.ASPECT to listOf(KeyEvent.KEYCODE_TV_ZOOM_MODE), Action.SKIP_AD to listOf(KeyEvent.KEYCODE_PROG_BLUE),
        ),
        "Tylko CH+/CH−: strzałki otwierają panele, OK = menu" to mapOf(
            Action.CH_PREV to listOf(KeyEvent.KEYCODE_CHANNEL_UP), Action.CH_NEXT to listOf(KeyEvent.KEYCODE_CHANNEL_DOWN),
            Action.PANEL_CHANNELS to listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP),
            Action.PANEL_RECENT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN),
            Action.QUICK_MENU to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU),
            Action.INFO to listOf(KeyEvent.KEYCODE_INFO), Action.GUIDE to listOf(KeyEvent.KEYCODE_GUIDE),
            Action.LAST to listOf(KeyEvent.KEYCODE_LAST_CHANNEL), Action.FAV to listOf(KeyEvent.KEYCODE_PROG_YELLOW),
            Action.RECORD to listOf(KeyEvent.KEYCODE_MEDIA_RECORD), Action.AUDIO to listOf(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
            Action.SUBS to listOf(KeyEvent.KEYCODE_CAPTIONS), Action.ASPECT to listOf(KeyEvent.KEYCODE_TV_ZOOM_MODE), Action.SKIP_AD to listOf(KeyEvent.KEYCODE_PROG_BLUE),
        ),
    )

    fun map(): Map<Action, List<Int>> {
        val j = Prefs.keyMapJson
        if (j.isBlank()) return PRESETS[0].second
        return runCatching {
            val o = JSONObject(j)
            Action.values().associateWith { a -> o.optJSONArray(a.name)?.let { arr -> List(arr.length()) { arr.getInt(it) } } ?: emptyList() }
        }.getOrDefault(PRESETS[0].second)
    }

    fun save(m: Map<Action, List<Int>>) {
        Prefs.keyMapJson = JSONObject().apply { m.forEach { (a, l) -> put(a.name, JSONArray(l)) } }.toString()
    }

    fun applyPreset(i: Int) = save(PRESETS[i].second)

    /** Przypisuje klawisz do akcji (usuwa go z innych akcji). */
    fun assign(a: Action, key: Int) {
        val m = map().mapValues { (act, l) -> if (act == a) l else l - key }.toMutableMap()
        m[a] = ((m[a] ?: emptyList()) - key) + key
        save(m)
    }

    fun clear(a: Action) = save(map().toMutableMap().apply { this[a] = emptyList() })

    fun actionFor(key: Int): Action? = map().entries.firstOrNull { key in it.value }?.key

    fun keyName(k: Int): String = when (k) {
        KeyEvent.KEYCODE_DPAD_UP -> "▲"; KeyEvent.KEYCODE_DPAD_DOWN -> "▼"; KeyEvent.KEYCODE_DPAD_LEFT -> "◄"; KeyEvent.KEYCODE_DPAD_RIGHT -> "►"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "OK"; KeyEvent.KEYCODE_CHANNEL_UP -> "CH+"; KeyEvent.KEYCODE_CHANNEL_DOWN -> "CH−"
        else -> KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_").replace('_', ' ')
    }

    /** Numer wybranego presetu lub -1 (własne). */
    fun presetIndex(): Int = PRESETS.indexOfFirst { (_, m) -> Action.values().all { (m[it] ?: emptyList()).toSet() == (map()[it] ?: emptyList()).toSet() } }
}

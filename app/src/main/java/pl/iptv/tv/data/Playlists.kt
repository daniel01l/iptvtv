package pl.iptv.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class PlaylistInfo(
    val id: String,
    var name: String,
    var source: String,
    var epg: String = "",
    /** Koniec ważności (ms, koniec dnia) lub 0 = nie podano. */
    var expiry: Long = 0,
    var picon: String = "",
    var cuType: String = "",
    var cuSource: String = "",
    var cuDays: Int = 7,
    var cuForce: Boolean = false,
    /** Token użytkownika – wstawiany za {token} w adresie listy i EPG. */
    var token: String = "",
)

/** Wiele list kanałów; jedna aktywna. Dane aktywnej są też w Prefs.playlistSource / epgUrl. */
object Playlists {
    const val MAIN = "main"
    private val fmt get() = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { isLenient = false }

    fun all(): List<PlaylistInfo> = runCatching {
        val a = JSONArray(Prefs.playlistsJson)
        List(a.length()) { i ->
            a.getJSONObject(i).let { PlaylistInfo(it.getString("id"), it.getString("name"), it.getString("source"), it.optString("epg"), it.optLong("expiry"),
                it.optString("picon"), it.optString("cuType"), it.optString("cuSource"), it.optInt("cuDays", 7), it.optBoolean("cuForce"), it.optString("token")) }
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<PlaylistInfo>) {
        Prefs.playlistsJson = JSONArray().apply {
            l.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("source", p.source).put("epg", p.epg).put("expiry", p.expiry)
                .put("picon", p.picon).put("cuType", p.cuType).put("cuSource", p.cuSource).put("cuDays", p.cuDays).put("cuForce", p.cuForce).put("token", p.token)) }
        }.toString()
    }

    /** Przeniesienie ustawień ze starszej wersji (jedna lista). */
    fun migrate() {
        if (all().isEmpty() && Prefs.playlistSource.isNotBlank()) {
            save(listOf(PlaylistInfo(MAIN, "Lista 1", Prefs.playlistSource, Prefs.epgUrl, 0, Prefs.piconUrl,
                Prefs.catchupDefaultType, Prefs.catchupDefaultSource, Prefs.catchupDefaultDays, Prefs.catchupForce)))
            Prefs.activePlaylist = MAIN
        }
    }

    fun active(): PlaylistInfo? = all().firstOrNull { it.id == Prefs.activePlaylist }

    fun add(name: String, source: String, epg: String = "", expiry: Long = 0): PlaylistInfo {
        val l = all().toMutableList()
        val id = if (l.isEmpty()) MAIN else "p" + System.currentTimeMillis().toString(36)
        val p = PlaylistInfo(id, name.ifBlank { "Lista ${l.size + 1}" }, source, epg, expiry)
        l += p; save(l)
        if (l.size == 1) Prefs.activePlaylist = id
        return p
    }

    fun update(p: PlaylistInfo) { save(all().map { if (it.id == p.id) p else it }) }

    fun remove(id: String) {
        save(all().filter { it.id != id })
        if (Prefs.activePlaylist == id) Prefs.activePlaylist = all().firstOrNull()?.id ?: MAIN
    }

    /** Zapisuje źródło/EPG aktywnej listy (tworzy ją, gdy brak). */
    fun syncActive(source: String = Prefs.playlistSource, epg: String = Prefs.epgUrl) {
        val a = active()
        if (a == null) { if (source.isNotBlank()) add("Lista 1", source, epg).also { Prefs.activePlaylist = it.id } }
        else update(a.copy(source = source, epg = epg))
    }

    /** Zapisuje bieżące ustawienia (EPG, picony, catch-up) w aktywnej liście. */
    fun captureActive() {
        val a = active() ?: return
        update(a.copy(epg = Prefs.epgUrl, picon = Prefs.piconUrl, cuType = Prefs.catchupDefaultType,
            cuSource = Prefs.catchupDefaultSource, cuDays = Prefs.catchupDefaultDays, cuForce = Prefs.catchupForce))
    }

    /** Ustawia w Prefs dane listy (przy przełączaniu). */
    fun applyToPrefs(p: PlaylistInfo) {
        Prefs.playlistSource = p.source; Prefs.epgUrl = p.epg; Prefs.piconUrl = p.picon
        Prefs.catchupDefaultType = p.cuType; Prefs.catchupDefaultSource = p.cuSource
        Prefs.catchupDefaultDays = p.cuDays; Prefs.catchupForce = p.cuForce
    }

    fun parseDate(s: String): Long? {
        val t = s.trim(); if (t.isEmpty()) return 0
        return runCatching {
            Calendar.getInstance().apply { time = fmt.parse(t)!!; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }.timeInMillis
        }.getOrNull()
    }

    fun formatDate(ms: Long) = if (ms <= 0) "" else fmt.format(java.util.Date(ms))

    /** Dni do końca ważności aktywnej listy lub null, gdy nie podano daty. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? {
        val e = active()?.expiry ?: return null
        if (e <= 0) return null
        if (e < now) return -1
        fun day(ms: Long) = Calendar.getInstance().run { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); timeInMillis }
        return Math.round((day(e) - day(now)) / 86_400_000.0).toInt()
    }
}

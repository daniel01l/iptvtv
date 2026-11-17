package pl.iptv.tv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kafelek pulpitu X25 (Room, tabela `dash_tiles`): funkcja ([type] + [arg]), własna etykieta, kolejność,
 * przypięcie (przypięte zawsze na początku rzędu), ukrycie i rozmiar (duży = dwa razy szerszy).
 */
@Entity(tableName = "dash_tiles")
data class DashTile(
    @PrimaryKey val id: String,
    val position: Int,
    val type: String,
    val arg: String = "",
    val label: String = "",
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val big: Boolean = false,
) {
    val action get() = X25Action(type, arg, label)
}

@Dao
interface DashTileDao {
    @Query("SELECT * FROM dash_tiles ORDER BY position") suspend fun all(): List<DashTile>
    @Query("DELETE FROM dash_tiles") suspend fun clear()
    @Insert suspend fun insertAll(t: List<DashTile>)
    /** Cały układ zapisywany naraz (kolejność = pozycje) – jedna transakcja. */
    @Transaction suspend fun replace(t: List<DashTile>) { clear(); insertAll(t) }
}

/** Funkcja kafelka. [arg] – kanał (klucz), rząd STube, playlista, miks; [label] – nazwa wyświetlana (puste = domyślna). */
data class X25Action(val type: String, val arg: String = "", val label: String = "") {
    /** Nazwa na kafelku. */
    val title: String get() = label.ifBlank { DEFAULT_TITLES[type] ?: type }

    companion object {
        const val LIVE = "live"; const val STUBE = "stube"; const val PLAYLISTS = "playlists"; const val SUBS = "subs"
        const val HISTORY = "history"; const val FAVORITES = "favorites"; const val GUIDE = "guide"; const val EPG_SEARCH = "epg_search"
        const val SETTINGS = "settings"; const val APPS = "apps"; const val CHANNEL = "channel"; const val STUBE_ROW = "stube_row"
        const val STUBE_PLAYLIST = "stube_pl"; const val MIX = "mix"; const val STREMIO = "stremio"

        val DEFAULT_TITLES = mapOf(
            LIVE to "Live TV", STUBE to "STube", PLAYLISTS to "Moje playlisty", SUBS to "Subskrypcje", HISTORY to "Historia i postęp",
            FAVORITES to "Ulubione kanały", GUIDE to "Przewodnik TV", EPG_SEARCH to "Wyszukiwarka programów", SETTINGS to "Ustawienia",
            APPS to "Aplikacje", CHANNEL to "Kanał", STUBE_ROW to "STube", STUBE_PLAYLIST to "Playlista", MIX to "Miks", STREMIO to "Stremio",
        )
        /** Funkcje aplikacji (bez argumentu) – pierwsza sekcja okna „Zmień funkcję”. */
        val APP_FUNCTIONS = listOf(LIVE, STUBE, STREMIO, PLAYLISTS, SUBS, HISTORY, FAVORITES, GUIDE, EPG_SEARCH, SETTINGS, APPS)
    }
}

/**
 * Układ pulpitu – czyste funkcje (testowane bez bazy i UI). Każda operacja zwraca nową listę z pozycjami 0..n-1:
 * przypięte kafelki na początku (w swojej kolejności), potem pozostałe.
 */
object X25Layout {
    fun defaults(): List<DashTile> = listOf(
        DashTile("live", 0, X25Action.LIVE, big = true),
        DashTile("stube", 1, X25Action.STUBE),
        DashTile("stremio", 2, X25Action.STREMIO),
        DashTile("playlists", 3, X25Action.PLAYLISTS),
        DashTile("subs", 4, X25Action.SUBS),
        DashTile("history", 5, X25Action.HISTORY),
        DashTile("guide", 6, X25Action.GUIDE),
    )

    /**
     * Układ zapisany przed dodaniem Stremio: kafelek Stremio za STube (albo za Live TV / na końcu), jeśli go jeszcze nie ma.
     * Zwraca tę samą listę, gdy kafelek już jest.
     */
    fun withStremio(l: List<DashTile>): List<DashTile> {
        if (l.any { it.type == X25Action.STREMIO }) return l
        var id = "stremio"; var n = 0
        while (l.any { it.id == id }) id = "stremio" + ++n
        val after = l.indexOfFirst { it.type == X25Action.STUBE }.takeIf { it >= 0 } ?: l.indexOfFirst { it.type == X25Action.LIVE }
        val i = if (after >= 0) after + 1 else l.size
        val pinned = i > 0 && l[i - 1].pinned && l.getOrNull(i)?.pinned == true
        return normalize(l.toMutableList().apply { add(i, DashTile(id, i, X25Action.STREMIO, pinned = pinned)) })
    }

    fun normalize(l: List<DashTile>): List<DashTile> =
        (l.filter { it.pinned } + l.filterNot { it.pinned }).mapIndexed { i, t -> if (t.position == i) t else t.copy(position = i) }

    fun visible(l: List<DashTile>) = l.filterNot { it.hidden }

    /**
     * Przesuwa kafelek o [delta] miejsc wśród widocznych kafelków (ukryte zostają na swoich miejscach),
     * nie przekraczając granicy przypiętych / nieprzypiętych.
     */
    fun move(l: List<DashTile>, id: String, delta: Int): List<DashTile> {
        val list = normalize(l).toMutableList()
        val vis = list.filterNot { it.hidden }
        val from = vis.indexOfFirst { it.id == id }
        if (from < 0 || delta == 0) return list
        val t = vis[from]
        val group = vis.filter { it.pinned == t.pinned }
        val gi = group.indexOf(t)
        val target = group.getOrNull((gi + delta).coerceIn(0, group.size - 1)) ?: return list
        if (target.id == t.id) return list
        val a = list.indexOf(t); val b = list.indexOf(target)
        list.removeAt(a); list.add(b, t)
        return normalize(list)
    }

    fun togglePin(l: List<DashTile>, id: String): List<DashTile> {
        val t = l.firstOrNull { it.id == id } ?: return l
        val rest = l.filter { it.id != id }
        // przypięty trafia na koniec przypiętych; odpięty – na początek nieprzypiętych
        val pinned = rest.filter { it.pinned }; val others = rest.filterNot { it.pinned }
        return normalize(if (!t.pinned) pinned + t.copy(pinned = true) + others else pinned + t.copy(pinned = false) + others)
    }

    fun setHidden(l: List<DashTile>, id: String, hidden: Boolean) = normalize(l.map { if (it.id == id) it.copy(hidden = hidden) else it })
    fun toggleBig(l: List<DashTile>, id: String) = l.map { if (it.id == id) it.copy(big = !it.big) else it }
    fun assign(l: List<DashTile>, id: String, a: X25Action) = l.map { if (it.id == id) it.copy(type = a.type, arg = a.arg, label = a.label) else it }
    fun remove(l: List<DashTile>, id: String) = normalize(l.filter { it.id != id })

    /** Nowy kafelek na końcu (unikalne id); zwraca listę i id nowego kafelka. */
    fun add(l: List<DashTile>, a: X25Action): Pair<List<DashTile>, String> {
        var n = l.size; var id: String
        do { id = "t${n++}" } while (l.any { it.id == id })
        return normalize(l + DashTile(id, l.size, a.type, a.arg, a.label)) to id
    }
}

/** Zapis układu pulpitu w bazie aplikacji ([StubeDatabase], tabela `dash_tiles`); pusta tabela = układ domyślny. */
object X25Store {
    @Volatile var cached: List<DashTile>? = null; private set

    private fun dao() = StubeHistory.db()?.dash()

    suspend fun load(): List<DashTile> {
        var stored = dao()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.all() }.getOrDefault(emptyList()) } }.orEmpty()
        // jednorazowo: kafelek Stremio w układzie zapisanym wcześniej (usunięty później przez użytkownika już nie wraca)
        if (!Prefs.x25StremioTile) {
            Prefs.x25StremioTile = true
            if (stored.isNotEmpty()) X25Layout.withStremio(stored).takeIf { it !== stored }?.let { stored = it; save(it) }
        }
        return X25Layout.normalize(stored.ifEmpty { X25Layout.defaults() }).also { cached = it }
    }

    suspend fun save(l: List<DashTile>) {
        val n = X25Layout.normalize(l)
        cached = n
        dao()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.replace(n) } } }
    }

    suspend fun reset(): List<DashTile> { save(X25Layout.defaults()); return cached!! }

    /** Testy. */
    fun clearCache() { cached = null }
}

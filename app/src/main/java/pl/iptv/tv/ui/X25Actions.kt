package pl.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.X25Action
import pl.iptv.tv.data.YtVideo

/**
 * Uruchamianie funkcji kafelków pulpitu X25 – wyłącznie przez istniejące ekrany i ich parametry
 * (lista kanałów, odtwarzacz, STube, przewodnik, wyszukiwarka, ustawienia); żadnej nowej logiki odtwarzania.
 */
object X25Actions {
    /** Rzędy STube do przypisania kafelkowi (id z [STubeScreen]). */
    val STUBE_ROWS = listOf(
        STubeScreen.ROW_TRENDING to "Na czasie", STubeScreen.ROW_MUSIC to "Muzyka", STubeScreen.ROW_LIVE to "Na żywo",
        STubeScreen.ROW_CONTINUE to "Kontynuuj oglądanie", STubeScreen.ROW_MIXES to "Twoje miksy", STubeScreen.ROW_LIBRARY to "Biblioteka",
        STubeScreen.ROW_SUBS to "Subskrypcje (konto)", STubeScreen.ROW_FOR_YOU to "Dla Ciebie (konto)", STubeScreen.ROW_CHANNELS to "Kanały (konto)",
    )

    fun run(a: Activity, act: X25Action) {
        when (act.type) {
            X25Action.LIVE -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_DASHBOARD, true).putExtra(MainActivity.EXTRA_SKIP_START, true))
            X25Action.APPS -> a.startActivity(Intent(a, AppLibraryActivity::class.java))
            X25Action.STUBE -> a.startActivity(Intent(a, STubeActivity::class.java))
            X25Action.STREMIO -> a.startActivity(Intent(a, StremioActivity::class.java))
            X25Action.PLAYLISTS -> stubeRow(a, STubeScreen.ROW_LIBRARY)
            X25Action.SUBS -> stubeRow(a, STubeScreen.ROW_SUBS)
            X25Action.HISTORY -> stubePlaylist(a, STubeScreen.PL_HISTORY, "Historia oglądania")
            X25Action.STUBE_ROW -> stubeRow(a, act.arg)
            X25Action.STUBE_PLAYLIST -> stubePlaylist(a, act.arg, act.title)
            X25Action.MIX -> stubePlaylist(a, STubeScreen.PL_MIX + act.arg, act.title)
            X25Action.FAVORITES -> Repository.favorites().firstOrNull()?.let { playChannel(a, it, Repository.FAV) }
                ?: toast(a, "Brak ulubionych kanałów – dodaj je w menu kanału (★)")
            X25Action.CHANNEL -> Repository.byKey(act.arg)?.takeIf { !it.hidden }?.let { playChannel(a, it) }
                ?: toast(a, "Kanał „${act.title}” nie jest dostępny na bieżącej liście")
            X25Action.GUIDE -> a.startActivity(Intent(a, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, Prefs.lastChannel))
            X25Action.EPG_SEARCH -> a.startActivity(Intent(a, EpgSearchActivity::class.java))
            X25Action.SETTINGS -> a.startActivity(Intent(a, SettingsActivity::class.java))
            else -> toast(a, "Nieznana funkcja kafelka")
        }
    }

    private fun stubeRow(a: Activity, row: String) = a.startActivity(Intent(a, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_ROW, row))
    private fun stubePlaylist(a: Activity, id: String, title: String) =
        a.startActivity(Intent(a, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_PLAYLIST, id).putExtra(STubeActivity.EXTRA_PLAYLIST_TITLE, title))

    /** Kanał w odtwarzaczu (jak OK na liście kanałów). */
    fun playChannel(a: Activity, ch: Channel, group: String = Repository.ALL) {
        Prefs.lastChannel = ch.key
        a.startActivity(Intent(a, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key).putExtra(PlayerActivity.EXTRA_GROUP, group))
    }

    /** Film STube: wbudowany odtwarzacz albo SmartTube (Ustawienia → STube) – jak OK na kafelku STube. */
    fun playVideo(a: Activity, v: YtVideo) {
        if (Prefs.stubeInternal) a.startActivity(YtPlayerActivity.intent(a, v)) else SmartTubeIntegrationHelper.play(a, v.url)
    }

    /** Podpis pod nazwą kafelka (np. ostatnio oglądany kanał). */
    fun subtitle(act: X25Action): String = when (act.type) {
        X25Action.LIVE -> Repository.byKey(Prefs.lastChannel)?.let { "Ostatnio: ${it.name}" } ?: "Telewizja na żywo"
        X25Action.STUBE -> "YouTube bez reklam"
        X25Action.STREMIO -> "Filmy i seriale z wtyczek"
        X25Action.PLAYLISTS -> "Biblioteka STube"
        X25Action.SUBS -> "Najnowsze z kanałów"
        X25Action.HISTORY -> "Obejrzane i rozpoczęte filmy"
        X25Action.FAVORITES -> "${Repository.favorites().size} kanałów"
        X25Action.CHANNEL -> Repository.byKey(act.arg)?.group ?: "Kanał TV"
        X25Action.STUBE_ROW -> "Kategoria STube"
        X25Action.STUBE_PLAYLIST -> "Playlista STube"
        X25Action.MIX -> "Miks z historii"
        X25Action.GUIDE -> "Program telewizyjny"
        X25Action.EPG_SEARCH -> "Szukaj w programie TV"
        X25Action.SETTINGS -> "Wygląd, listy, odtwarzanie"
        X25Action.APPS -> "Zainstalowane aplikacje"
        else -> ""
    }

    fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
}

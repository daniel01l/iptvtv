package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.iptv.tv.data.M3uVod
import pl.iptv.tv.data.VodLibrary

/**
 * (7.23) Odtwarzanie filmów i odcinków z listy M3U w odtwarzaczu Stremio (ten sam kontrakt [StremioPlayerActivity.intent]:
 * typ, identyfikator tytułu, nazwa, plakat, identyfikator wideo, etykieta, źródło) – postęp, „Kontynuuj oglądanie”, napisy,
 * okno „Obraz” działają tak samo; wznowienie rozwiązuje adres z [pl.iptv.tv.data.VodLibrary], bez wtyczek i konta.
 */
object M3uVodUi {
    /** (v2) Komunikat, gdy pozycji nie ma już na liście (lista zmieniona). */
    const val GONE = "Tej pozycji nie ma już na liście"
    fun seasonLabel(s: Int, n: Int) = "Sezon $s  ·  $n " + StremioUi.plural(n, "odcinek", "odcinki", "odcinków")

    fun playIntent(c: Context, m: M3uVod.Movie): Intent =
        StremioPlayerActivity.intent(c, "movie", m.id, m.title, m.poster, m.id, "", M3uVod.stream(m.channel))

    fun playIntent(c: Context, s: M3uVod.Show, e: M3uVod.Episode): Intent =
        StremioPlayerActivity.intent(c, "series", s.id, s.title, s.poster, e.id, M3uVod.episodeLabel(e), M3uVod.stream(e.channel))

    /**
     * (v2) Pozycja z listy [id] (film, serial albo odcinek): film / odcinek – od razu odtwarzacz; serial – wybór sezonu
     * (gdy jest ich kilka), potem odcinka (Tytuł → Sezony → Odcinki). Katalog z [VodLibrary] (bez konta Stremio).
     */
    fun open(a: androidx.activity.ComponentActivity, id: String) = a.lifecycleScope.launch {
        val cat = VodLibrary.current()
        if (a.isFinishing || a.isDestroyed) return@launch
        cat.movie(id)?.let { m -> a.startActivity(playIntent(a, m)); return@launch }
        cat.episode(id)?.let { e -> cat.showOf(id)?.let { s -> a.startActivity(playIntent(a, s, e)); return@launch } }
        val show = cat.show(id)
        if (show == null) { I18n.makeToast(a, GONE, android.widget.Toast.LENGTH_SHORT).show(); return@launch }
        chooseEpisode(a, show)
    }

    /** Serial: kilka sezonów – najpierw sezon, potem odcinki tego sezonu; jeden sezon – od razu lista odcinków. */
    fun chooseEpisode(a: androidx.activity.ComponentActivity, show: M3uVod.Show) {
        val seasons = show.seasons
        fun episodes(list: List<M3uVod.Episode>, title: String) = Dialogs.menu(a, title, list.map { e ->
            M3uVod.episodeLabel(e) to { a.startActivity(playIntent(a, show, e)) }
        })
        if (seasons.size <= 1) { episodes(show.episodes, show.title); return }
        Dialogs.menu(a, show.title, seasons.map { s ->
            val list = show.season(s)
            seasonLabel(s, list.size) to { episodes(list, "${show.title} – sezon $s") }
        })
    }
}

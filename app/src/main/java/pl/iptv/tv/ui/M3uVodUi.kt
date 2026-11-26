package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import pl.iptv.tv.data.M3uVod

/**
 * (7.23) Odtwarzanie filmów i odcinków z listy M3U w odtwarzaczu Stremio (ten sam kontrakt [StremioPlayerActivity.intent]:
 * typ, identyfikator tytułu, nazwa, plakat, identyfikator wideo, etykieta, źródło) – postęp, „Kontynuuj oglądanie”, napisy,
 * okno „Obraz” działają tak samo; wznowienie rozwiązuje adres z [pl.iptv.tv.data.VodLibrary], bez wtyczek i konta.
 */
object M3uVodUi {
    fun playIntent(c: Context, m: M3uVod.Movie): Intent =
        StremioPlayerActivity.intent(c, "movie", m.id, m.title, m.poster, m.id, "", M3uVod.stream(m.channel))

    fun playIntent(c: Context, s: M3uVod.Show, e: M3uVod.Episode): Intent =
        StremioPlayerActivity.intent(c, "series", s.id, s.title, s.poster, e.id, M3uVod.episodeLabel(e), M3uVod.stream(e.channel))
}

package pl.iptv.tv.remote

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.iptv.tv.IptvApp
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeImport
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.ui.I18n
import pl.iptv.tv.ui.YtPlayerActivity

/**
 * (7.15) Odbiór linków z telefonu („wyślij na telewizor”): telewizor jest widoczny w sieci (NSD, [RemoteServer.SERVICE_TYPE]),
 * a telefon wysyła link żądaniem `POST /api/cast` (nagłówek `x-pin`, link w `x-url` lub w treści). Link do playlisty YouTube / Spotify
 * jest pobierany i dopasowywany w tle ([StubeImport.resolve]), zapisywany w Room ([StubeImport.save]) i uruchamiany jako aktywna kolejka;
 * pojedynczy film YouTube gra od razu. Odpowiedź wraca natychmiast (przyjęto), a praca idzie poza wątkiem interfejsu – na głównym
 * wątku jest tylko start ekranu odtwarzacza. Gdy aplikacja nie jest na pierwszym planie (system blokuje start ekranu z tła), link
 * trafia do okna „＋” w STube ([StubeImport.offer]).
 */
object CastReceiver {
    /** Testy podmieniają: czy wolno od razu uruchomić odtwarzacz / jak go uruchomić. */
    @Volatile var foreground: () -> Boolean = { IptvApp.isForeground }
    @Volatile var launcher: (Context, YtVideo) -> Unit = { c, v ->
        c.startActivity(YtPlayerActivity.intent(c, v).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Wątek interfejsu (testy podmieniają – runBlocking na głównym wątku z wstrzymaną pętlą zakleszczałby Dispatchers.Main). */
    @Volatile var ui: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main

    private val VIDEO =Regex("""(?:youtube\.com/(?:watch\?(?:[^#\s]*&)?v=|shorts/|live/)|youtu\.be/|music\.youtube\.com/watch\?(?:[^#\s]*&)?v=)([A-Za-z0-9_-]{11})""")

    /** Pojedynczy film YouTube (link bez playlisty) – identyfikator; null – nie jest takim linkiem. */
    fun videoId(text: String): String? {
        val url = StubeImport.urlIn(text) ?: return null
        if (StubeImport.classify(url) != null) return null
        return VIDEO.find(url)?.groupValues?.get(1)
    }

    /** Czy tekst nadaje się do odtworzenia (playlista YouTube / Spotify, film YouTube, lista „Wykonawca – Tytuł”). */
    fun accepts(text: String): Boolean = StubeImport.classify(text) != null || videoId(text) != null || StubeImport.looksLikeTrackList(text)

    /** Obsługa żądania; zwraca (przyjęto, komunikat) od razu – dalsza praca w tle. */
    fun handle(c: Context, text: String): Pair<Boolean, String> {
        val t = text.trim()
        if (t.isEmpty() || !accepts(t)) return false to "To nie jest link do playlisty ani filmu YouTube / Spotify"
        StubeHistory.scope.launch { runCatching { process(c.applicationContext, t) } }
        return true to "Przyjęto – ${RemoteServer.deviceName}"
    }

    /** Właściwa obsługa (testy wywołują bezpośrednio). */
    suspend fun process(c: Context, t: String) {
        val single = videoId(t)
        if (single != null) {
            val v = YtVideo(single, "", "")
            if (!foreground()) { StubeImport.offer(t); toast(c, "🎵 Otrzymano film – otwórz STube i wybierz ＋"); return }
            withContext(ui) { launcher(c, v) }
            return
        }
        if (!foreground()) { StubeImport.offer(t); toast(c, "🎵 Otrzymano playlistę dla STube – otwórz STube i wybierz ＋"); return }
        toast(c, "🎵 Pobieram playlistę…")
        val p = StubeImport.resolve(t)                                     // sieć + dopasowanie – wątek IO
        if (!p.ok) { toast(c, "✖ ${p.error ?: "Brak utworów"}"); return }
        StubeImport.save(p)                                                // na stałe (Room): Biblioteka i gust
        StubeQueue.replaceAll(p.videos.drop(1).map { QueueItem.of(it) })   // aktywna kolejka
        withContext(ui) { launcher(c, p.videos.first()) }
        toast(c, "▶ „${p.name}” – ${p.videos.size} utworów")
    }

    private suspend fun toast(c: Context, msg: String) = withContext(ui) { I18n.makeToast(c, msg, Toast.LENGTH_LONG).show() }
}

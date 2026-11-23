package pl.iptv.tv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.async
import androidx.recyclerview.widget.RecyclerView
import coil.load
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtChannel
import pl.iptv.tv.data.YtPlaylist
import pl.iptv.tv.data.YtSource
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtException
import pl.iptv.tv.data.StubeHistory

/** Kafelek rzędu STube. */
sealed class STubeTile {
    data class Video(val v: YtVideo) : STubeTile()
    data class Channel(val c: YtChannel) : STubeTile()
    /** Kafelek akcji (zaloguj, szukaj w SmartTube, ponów) – tej samej wielkości co film. */
    data class Action(val id: String, val icon: String, val label: String) : STubeTile()
    /** Playlista Biblioteki (lokalna, Polubione, konta) albo kanał – OK otwiera jej filmy w rzędzie poniżej. */
    data class Playlist(val p: YtPlaylist) : STubeTile()
}

/** Strona danych rzędu: kafelki i token następnej strony. */
data class STubePage(val tiles: List<STubeTile>, val next: String? = null)

/** Rząd: tytuł + loader stron (YouTube Data API v3 przez [YouTubeRepository]). */
class STubeRow(val id: String, val title: String, val load: suspend (page: String?) -> STubePage) {
    val tiles = mutableListOf<STubeTile>()
    var next: String? = null
    var loading = false
    var error: String? = null
    /** Ostatnio zaznaczony kafelek (powrót ▲▼ na tę samą pozycję – jak w Leanback). */
    var lastFocus = 0
    /** Okładki kwadratowe (muzyka, miksy, biblioteka) zamiast miniatur 16:9. */
    var square = false
    /** Rząd playlisty: otwarta playlista ([STubeScreen.playlistRow]). */
    var source: YtPlaylist? = null
    val adapter = STubeTileAdapter(this)
}

/**
 * Ekran STube – układ rzędów i kafelków (10-foot UI): duże kafelki 16:9, wyraźny fokus wg motywu
 * (ramka/poświata i powiększenie z [Ui.style]), brak drobnych elementów – cały kafelek jest jednym celem OK.
 */
object STubeScreen {
    const val ROW_SEARCH = "search"; const val ROW_FOR_YOU = "for_you"; const val ROW_TRENDING = "trending"
    const val ROW_MUSIC = "music"; const val ROW_SUBS = "subs"; const val ROW_LOGIN = "login"
    private val BIG_THUMB = Regex("/(maxres|sd|hq)default(_live)?\\.(jpg|webp)")

    /** (7.16) Mniejszy wariant miniatury YouTube dla widoku o szerokości [widthPx] ≤ 400 (16:9, bez czarnych pasów hqdefault). */
    fun thumbUrl(url: String, widthPx: Int): String =
        if (widthPx in 1..400 && url.contains("ytimg.com/vi")) BIG_THUMB.replace(url) { "/mqdefault${it.groupValues[2]}.${it.groupValues[3]}" } else url

    const val ACT_LOGIN = "login"; const val ACT_SMARTTUBE_SEARCH = "st_search"; const val ACT_RETRY = "retry"

    /** Wymiary kafelków (dp). */
    const val TILE_W = 236; const val THUMB_H = 126; const val CHANNEL_W = 150; const val AVATAR = 112

    /** Bok kwadratowej okładki (dp) – proporcjonalnie do wysokości miniatury wybranego układu. */
    fun squareSide(l: StubeTheme.Layout) = (l.thumbH * 1.3).toInt()

    /**
     * (7.17) Wysokość miniatury filmu (px): „Obsidian Stream” (motyw Nowoczesny) – równe 16:9 do szerokości kafelka,
     * inne motywy – wysokość z układu kafelków.
     */
    fun thumbHeightPx(l: StubeTheme.Layout): Int = if (pl.iptv.tv.ui.theme.ObsidianViews.active) (Ui.dp(l.tileW) - Ui.dp(12)) * 9 / 16 else Ui.dp(l.thumbH)
    /** Zaokrąglenie miniatury (px): Obsidian 16 dp, inne motywy – z motywu. */
    fun thumbRadiusPx(): Float = Ui.dp(if (pl.iptv.tv.ui.theme.ObsidianViews.active) pl.iptv.tv.ui.theme.ObsidianViews.RADIUS_DP else maxOf(Ui.preset.radius - 4, 6)).toFloat()

    /**
     * Kolejność rzędów: [wyniki], Dla Ciebie (konto, źródło „Moje”) albo zachęta do logowania, Na czasie, Muzyka, Subskrypcje (konto).
     * Czysta funkcja – testowana bez sieci.
     */
    const val ROW_LIVE = "live"
    /** „Kontynuuj oglądanie” – rozpoczęte filmy z lokalnej bazy ([StubeHistory]). */
    const val ROW_CONTINUE = "continue"
    /** Payload odświeżenia samego paska obejrzenia (bez ponownego wiązania kafelka – fokus zostaje). */
    const val PAYLOAD_PROGRESS = "progress"
    /** Wysokość paska obejrzenia na miniaturze (dp) – jak w YouTube: cienki pasek przy dolnej krawędzi. */
    const val PROGRESS_H = 4
    /** Biblioteka (playlisty), kanały z subskrypcji i rząd otwartej playlisty / kanału (wstawiany pod rząd, z którego otwarto). */
    const val ROW_LIBRARY = "library"; const val ROW_CHANNELS = "channels"; const val ROW_PLAYLIST = "playlist"
    /** „Twoje miksy” – gotowe playlisty z historii ([StubeMixes]); id playlisty miksu: [PL_MIX] + id. */
    const val ROW_MIXES = "mixes"; const val PL_MIX = "mix:"
    /** Rzędy z kwadratowymi okładkami (jak w aplikacjach muzycznych na TV). */
    val SQUARE_ROWS = setOf(ROW_MUSIC, ROW_MIXES, ROW_LIBRARY)
    /** Identyfikatory playlist specjalnych (nie są playlistami YouTube). */
    /** (7.14) Przesłana playlista (Room): [PL_IMPORTED] + id wiersza. */
    const val PL_IMPORTED = "imp:"
    const val PL_LATER = "local:later"; const val PL_HISTORY = "local:history"; const val PL_LIKED = "liked"; const val PL_CHANNEL = "channel:"

    /** Kanał jako „playlista” (jego najnowsze filmy). */
    fun channelPlaylist(c: YtChannel) = YtPlaylist(PL_CHANNEL + c.id, c.title, c.thumb)

    /** Ikona kafelka playlisty bez miniatury. */
    fun playlistIcon(id: String) = when {
        id == PL_LATER -> "🕘"; id == PL_HISTORY -> "📜"; id.startsWith(PL_MIX) -> "🎵"; id.startsWith(PL_IMPORTED) -> "🎶"; id == PL_LIKED -> "👍"; id.startsWith(PL_CHANNEL) -> "📺"; else -> "☰"
    }

    /** Rysowana ikona kafelka playlisty (zamiast emoji). */
    fun playlistIconKind(id: String) = when {
        id == PL_LATER -> PlayerIcon.DELAY; id == PL_HISTORY -> PlayerIcon.QUEUE; id.startsWith(PL_MIX) || id.startsWith(PL_IMPORTED) -> PlayerIcon.MUSIC
        id == PL_LIKED -> PlayerIcon.AUTOPLAY; id.startsWith(PL_CHANNEL) -> PlayerIcon.VIDEO; else -> PlayerIcon.QUEUE
    }

    /** Ikona kafelka akcji wg dawnego znaku emoji. */
    fun actionIconKind(icon: String) = when (icon) { "👤" -> PlayerIcon.USER; "⟳" -> PlayerIcon.REFRESH; "🔎" -> PlayerIcon.SEARCH; else -> PlayerIcon.INFO }

    /** Kafelki Biblioteki: listy lokalne zawsze, z kontem – Polubione i playlisty konta (błąd API nie ukrywa list lokalnych). */
    suspend fun libraryPage(loggedIn: Boolean, page: String?): STubePage {
        val local = if (page == null) listOf(
            YtPlaylist(PL_LATER, "Do obejrzenia później", count = StubeHistory.watchLaterCount),
            YtPlaylist(PL_HISTORY, "Historia oglądania"),
        ) + importedTiles() + (if (loggedIn) listOf(YtPlaylist(PL_LIKED, "Polubione filmy")) else emptyList()) else emptyList()
        if (!loggedIn) return STubePage(local.map(STubeTile::Playlist))
        val (mine, next) = try { YouTubeRepository.myPlaylists(page) } catch (e: YtException) {
            android.util.Log.w("STube", "playlisty: ${e.message}"); emptyList<YtPlaylist>() to null
        }
        return STubePage((local + mine).map(STubeTile::Playlist), next)
    }

    /** Przesłane playlisty (linki YouTube / Spotify) jako kafelki Biblioteki – pamięć podręczna, bez dostępu do bazy. */
    fun importedTiles(): List<YtPlaylist> = pl.iptv.tv.data.StubeImport.cached.map {
        val v = it.videos(); YtPlaylist(PL_IMPORTED + it.id, it.name, v.firstOrNull()?.thumb.orEmpty(), v.size)
    }

    /** Rząd z filmami playlisty / kanału [p]. */
    fun playlistRow(p: YtPlaylist): STubeRow = STubeRow(ROW_PLAYLIST, playlistIcon(p.id) + "  " + p.title) { page ->
        when {
            p.id == PL_LATER -> STubePage(StubeHistory.watchLater().map { STubeTile.Video(it.toVideo()) })
            p.id == PL_HISTORY -> STubePage(StubeHistory.watched().map { STubeTile.Video(it.toVideo()) })
            p.id.startsWith(PL_IMPORTED) -> STubePage(pl.iptv.tv.data.StubeImport.all().firstOrNull { it.id == p.id.removePrefix(PL_IMPORTED).toLongOrNull() }?.videos().orEmpty().map(STubeTile::Video))
            p.id.startsWith(PL_MIX) -> STubePage((pl.iptv.tv.data.StubeMixes.byId(p.id.removePrefix(PL_MIX))?.let { pl.iptv.tv.data.StubeMixes.videos(it) }.orEmpty()).map(STubeTile::Video))
            p.id == PL_LIKED -> YouTubeRepository.liked(page).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
            // (7.17) bez klucza API (kanał / playlista z panelu wyszukiwania): kanał – filmy z wyszukiwania po nazwie, playlista – NewPipe
            p.id.startsWith(PL_CHANNEL) && !YouTubeRepository.publicAvailable -> STubePage(YtKeyless.search(p.title).map(STubeTile::Video))
            p.id.startsWith(PL_CHANNEL) -> YouTubeRepository.playlistVideos(YouTubeRepository.uploadsPlaylist(p.id.removePrefix(PL_CHANNEL)), page)
                .let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
            !YouTubeRepository.publicAvailable && !YouTubeTokens.loggedIn -> STubePage(YtKeyless.playlist(p.id).second.map(STubeTile::Video))
            else -> YouTubeRepository.playlistVideos(p.id, page).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) }
        }
    }.also { it.source = p; it.square = p.id.startsWith(PL_MIX) || p.id.startsWith(PL_IMPORTED) }

    /** Dość danych na „Twoje miksy”: historia albo przesłane playlisty. */
    fun hasMixHistory() = StubeHistory.historyCount >= pl.iptv.tv.data.StubeMixes.MIN_HISTORY || pl.iptv.tv.data.StubeImport.cached.isNotEmpty()

    fun rowIds(loggedIn: Boolean, personal: Boolean, query: String?, hasContinue: Boolean = false, hasMixes: Boolean = false): List<String> = buildList {
        if (!query.isNullOrBlank()) add(ROW_SEARCH)
        if (hasContinue) add(ROW_CONTINUE)
        if (hasMixes) add(ROW_MIXES)
        if (loggedIn && personal) add(ROW_FOR_YOU) else if (!loggedIn) add(ROW_LOGIN)
        // najnowsze z subskrypcji zaraz pod „Dla Ciebie” (jak karta Subskrypcje w YouTube)
        if (loggedIn) add(ROW_SUBS)
        add(ROW_TRENDING)
        add(ROW_MUSIC)
        add(ROW_LIVE)
        // Biblioteka zawsze (listy lokalne: Do obejrzenia później, Historia); z kontem – Polubione i playlisty konta
        add(ROW_LIBRARY)
        if (loggedIn) add(ROW_CHANNELS)
    }

    /**
     * Dane z YouTube Data API (klucz lub konto), a bez dostępu do API albo przy jego błędzie (limit, wyłączone API) –
     * listy bez klucza ([YtKeyless]). STube pokazuje filmy także bez żadnej konfiguracji Google.
     */
    private suspend fun apiOr(api: suspend () -> STubePage, keyless: suspend () -> List<YtVideo>): STubePage {
        if (YouTubeRepository.publicAvailable) {
            try { return api() } catch (e: YtException) { android.util.Log.w("STube", "API: ${e.message} – listy bez klucza") }
        }
        return STubePage(keyless().map(STubeTile::Video))
    }

    /** Rzędy z loaderami (repozytorium konta YouTube + listy bez klucza). */
    fun rows(query: String?): List<STubeRow> {
        val logged = YouTubeTokens.loggedIn
        val personal = YouTubeRepository.source == YtSource.PERSONAL
        return rowIds(logged, personal, query, StubeHistory.hasUnfinished, hasMixHistory()).map { id ->
            when (id) {
                // gotowe miksy z historii oglądania (Room) – filmy z YouTube po otwarciu
                ROW_MIXES -> STubeRow(id, "Twoje miksy") { _ ->
                    STubePage(pl.iptv.tv.data.StubeMixes.current().map { STubeTile.Playlist(YtPlaylist(PL_MIX + it.id, it.title, it.thumb)) })
                }
                // (7.17) piosenki zawsze od początku – bez nich w „Kontynuuj oglądanie”
                ROW_CONTINUE -> STubeRow(id, "Kontynuuj oglądanie") { _ -> STubePage(StubeHistory.unfinished().map { it.toVideo() }.filterNot { YtEngine.isSong(it) }.map { STubeTile.Video(it) }) }
                // wyszukiwanie bez klucza (nie zużywa limitu API: 100 jednostek za zapytanie); API – zapasowo
                ROW_SEARCH -> STubeRow(id, "Wyniki: ${query!!.trim()}") { _ ->
                    val found = try { YtKeyless.search(query) } catch (e: YtException) {
                        if (YouTubeRepository.publicAvailable) YouTubeRepository.search(query) else throw e
                    }
                    STubePage(found.map { STubeTile.Video(it) } + STubeTile.Action(ACT_SMARTTUBE_SEARCH, "🔎", "Szukaj „${query.trim()}” w SmartTube"))
                }
                ROW_FOR_YOU -> STubeRow(id, "Dla Ciebie") { p -> YouTubeRepository.feed(YtSource.PERSONAL, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }
                ROW_LOGIN -> STubeRow(id, "Dla Ciebie") { _ ->
                    STubePage(listOf(STubeTile.Action(ACT_LOGIN, "👤", "Zaloguj się do YouTube, aby zobaczyć polecane i subskrypcje")))
                }
                ROW_TRENDING -> STubeRow(id, "Na czasie") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(null, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.PODCASTS) }
                }
                ROW_MUSIC -> STubeRow(id, "Muzyka") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(YouTubeRepository.CAT_MUSIC, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.MUSIC) }
                }
                ROW_LIVE -> STubeRow(id, "Na żywo") { _ -> STubePage(YtKeyless.kiosk(YtKeyless.LIVE).map(STubeTile::Video)) }
                // najnowsze filmy z subskrybowanych kanałów (playlisty „przesłane”, scalone po dacie)
                ROW_SUBS -> STubeRow(id, "Subskrypcje") { _ -> STubePage(YouTubeRepository.subscriptionFeed().map(STubeTile::Video)) }
                ROW_LIBRARY -> STubeRow(id, "Biblioteka / Playlisty") { p -> libraryPage(logged, p) }
                else -> STubeRow(id, "Kanały") { _ -> STubePage(YouTubeRepository.subscriptions().map(STubeTile::Channel)) }
            }
        }.onEach { it.square = it.id in SQUARE_ROWS }
    }

    /**
     * Kategoria paska bocznego: od razu pełna siatka filmów. [query] – wyszukiwanie bez klucza (każda kategoria ma własny zestaw;
     * Aktualności = komentarze i analizy bieżących wydarzeń, Wiadomości = serwisy informacyjne); [kiosk] – listy YouTube dla Na czasie / Muzyka.
     */
    data class Category(val id: String, val title: String, val icon: String, val query: String? = null, val kiosk: String? = null, val apiCategory: String? = null,
                        val fallbackQuery: String? = null, val subs: List<Sub> = emptyList())

    /**
     * Podkategoria (pigułka nad siatką): zapytania i listy YouTube łączone równolegle, wyniki przeplatane i rozrzedzone po kanałach
     * ([StubeMixes.diversify]) – jedna kategoria to wielu twórców. [minSec] – tylko filmy co najmniej tak długie (składanki 10 min+),
     * [tracks] – tylko pojedyncze utwory (bez składanek, vlogów i długich wideo).
     */
    data class Sub(val id: String, val title: String, val queries: List<String> = emptyList(), val kiosks: List<String> = emptyList(),
                   val minSec: Int = 0, val tracks: Boolean = false)

    private fun cat(id: String, title: String, icon: String, kiosks: List<String> = emptyList(), vararg subs: Sub): Category {
        // „Wszystko”: lista list kategorii + po jednym (najlepszym) zapytaniu z każdej podkategorii
        val base = subs.filter { it.minSec == 0 }
        val all = Sub(id + "_all", "Wszystko", base.take(8).mapNotNull { it.queries.firstOrNull() }, kiosks, tracks = base.isNotEmpty() && base.all { it.tracks })
        return Category(id, title, icon, kiosk = kiosks.firstOrNull(), subs = listOf(all) + subs)
    }

    private fun genreSub(id: String, title: String, extra: String = "") =
        pl.iptv.tv.data.StubeMixes.GENRES.first { it.id == id }.let { g -> Sub("g_$id", title, g.queries + listOfNotNull(extra.ifEmpty { null }) + "${g.name} teledysk 2026" , tracks = true) }

    /** Kolejność na pasku: Aktualności, Na czasie, Muzyka, Sport, Dla dzieci, Wiadomości, Gry. */
    val CATEGORIES = listOf(
        cat("cat_current", "Aktualności", PlayerIcon.NEWS, emptyList(),
            Sub("pol", "Polityka", listOf("polityka komentarz tygodnia", "sejm debata analiza", "wywiad polityk polska", "publicystyka polityczna dzisiaj", "polityka polska analiza")),
            Sub("eco", "Gospodarka", listOf("gospodarka polska analiza", "giełda inwestowanie finanse", "inflacja ekonomia wyjaśnienie", "biznes przedsiębiorczość polska")),
            Sub("world", "Świat", listOf("geopolityka analiza", "wojna na świecie analiza", "wiadomości ze świata dziś", "polityka międzynarodowa komentarz")),
            Sub("tech", "Technologie i nauka", listOf("technologia nowości", "nauka ciekawostki", "kosmos odkrycia", "sztuczna inteligencja nowości")),
            Sub("doc", "Reportaże i rozmowy", listOf("reportaż dokument polska", "wywiad rozmowa polska", "podcast rozmowa polska"))),
        cat("cat_trending", "Na czasie", PlayerIcon.TRENDING, listOf(YtKeyless.TRENDING),
            Sub("pop", "Popularne", listOf("popularne dziś polska", "viral polska", "najpopularniejsze filmy tygodnia"), listOf(YtKeyless.TRENDING)),
            Sub("music", "Muzyka", listOf("nowe teledyski 2026", "premiery muzyczne polska"), listOf(YtKeyless.MUSIC), tracks = true),
            Sub("games", "Gry", listOf("gry nowości 2026", "gameplay po polsku"), listOf(YtKeyless.GAMING)),
            Sub("movies", "Filmy i seriale", listOf("zwiastuny premiery 2026", "nowe seriale zwiastun"), listOf(YtKeyless.MOVIES)),
            Sub("talk", "Rozmowy", listOf("podcast polski odcinek", "wywiad rozmowa"), listOf(YtKeyless.PODCASTS))),
        cat("cat_music", "Muzyka", PlayerIcon.MUSIC, listOf(YtKeyless.MUSIC),
            Sub("hot", "Na czasie", listOf("nowe teledyski 2026", "premiery muzyczne polska", "new music this week", "nowe piosenki"), listOf(YtKeyless.MUSIC), tracks = true),
            Sub("top", "Popularne", listOf("najpopularniejsze piosenki polskie", "top hits teledysk", "popularne piosenki 2026", "viral hits teledysk"), tracks = true),
            genreSub("pop", "Pop"), genreSub("rock", "Rock"), genreSub("hiphop", "Hip-hop i rap"), genreSub("electronic", "Elektronika"),
            genreSub("discopolo", "Disco polo"), genreSub("jazz", "Jazz i blues"), genreSub("classical", "Klasyczna"), genreSub("chill", "Chill i lo-fi"),
            genreSub("metal", "Metal"), genreSub("latin", "Latino"), genreSub("kpop", "K-pop"), genreSub("reggae", "Reggae"), genreSub("country", "Country i folk"),
            Sub("mix", "Składanki", listOf("składanka hity 2026", "polskie hity mix godzina", "best music mix 2026", "nonstop mix", "składanka disco polo", "lofi 1 hour mix"), minSec = 10 * 60)),
        cat("cat_sport", "Sport", PlayerIcon.SPORT, emptyList(),
            Sub("foot", "Piłka nożna", listOf("skrót meczu ekstraklasa", "liga mistrzów skrót bramki", "reprezentacja polski piłka skrót", "bramki tygodnia piłka", "premier league skrót")),
            Sub("bask", "Koszykówka", listOf("nba skrót najlepsze akcje", "koszykówka polska liga skrót", "koszykówka najlepsze akcje")),
            Sub("volley", "Siatkówka", listOf("siatkówka skrót meczu plusliga", "reprezentacja polski siatkówka", "siatkówka najlepsze akcje")),
            Sub("fight", "MMA i boks", listOf("mma gala skrót ksw", "boks skrót walki", "ufc najlepsze walki", "freak fight")),
            Sub("tennis", "Tenis", listOf("tenis skrót meczu świątek", "tenis najlepsze akcje", "atp wta skrót")),
            Sub("motor", "Motorsport", listOf("formuła 1 skrót wyścigu", "rajdy wrc skrót", "żużel skrót meczu", "motogp skrót")),
            Sub("other", "Inne dyscypliny", listOf("skoki narciarskie skrót", "piłka ręczna skrót", "kolarstwo skrót etapu", "lekkoatletyka najlepsze momenty"))),
        cat("cat_kids", "Dla dzieci", PlayerIcon.KIDS, emptyList(),
            Sub("tales", "Bajki", listOf("bajki dla dzieci po polsku", "bajka na dobranoc", "bajki animowane polskie", "bajki edukacyjne")),
            Sub("songs", "Piosenki", listOf("piosenki dla dzieci", "piosenki dla dzieci zabawy ruchowe", "piosenki dla dzieci po polsku")),
            Sub("learn", "Nauka", listOf("nauka liter i cyfr dla dzieci", "eksperymenty dla dzieci", "edukacyjne dla dzieci po polsku")),
            Sub("fun", "Zabawy i DIY", listOf("zabawy dla dzieci diy", "slime dla dzieci", "kreatywne zabawy dla dzieci")),
            Sub("play", "Gry dla dzieci", listOf("minecraft dla dzieci po polsku", "roblox dla dzieci", "gry dla dzieci"))),
        cat("cat_news", "Wiadomości", PlayerIcon.HEADLINES, emptyList(),
            Sub("pl", "Polska", listOf("wiadomości polska dzisiaj", "tvn24 najnowsze wiadomości", "tvp info wiadomości", "polsat news wiadomości", "wydarzenia dzisiaj")),
            Sub("world2", "Świat", listOf("wiadomości ze świata", "wiadomości zagranica dzisiaj", "serwis informacyjny świat")),
            Sub("weather", "Pogoda", listOf("prognoza pogody", "pogoda na weekend", "pogoda na jutro")),
            Sub("biz", "Biznes", listOf("wiadomości biznes gospodarka", "informacje ekonomiczne dziś")),
            Sub("regional", "Regionalne", listOf("wiadomości lokalne", "informacje z regionu"))),
        cat("cat_games", "Gry", PlayerIcon.GAMES, listOf(YtKeyless.GAMING),
            Sub("mc", "Minecraft", listOf("minecraft po polsku", "minecraft poradnik", "minecraft survival odcinek")),
            Sub("shoot", "Fortnite i strzelanki", listOf("fortnite po polsku", "counter strike 2 po polsku", "valorant po polsku")),
            Sub("rpg", "GTA i RPG", listOf("gta 5 po polsku", "wiedźmin gameplay", "rpg gameplay po polsku")),
            Sub("review", "Recenzje i poradniki", listOf("recenzja gry", "poradnik do gry", "nowości growe")),
            Sub("esport", "E-sport", listOf("esport turniej skrót", "cs2 mecz najlepsze akcje", "lol esport")),
            Sub("retro", "Indie i retro", listOf("gry indie polecane", "retro gry", "symulator gra po polsku"))),
    )

    private val categoryCache = HashMap<String, Pair<Long, List<YtVideo>>>()
    private const val CATEGORY_TTL = 5 * 60_000L
    const val CATEGORY_MIN = 40

    /** Rząd (jedna strona) siatki kategorii [c] i podkategorii [sub] (domyślnie „Wszystko”); pusta lista = brak materiałów (stan pusty w ekranie). */
    fun categoryRow(c: Category, sub: Sub? = c.subs.firstOrNull()): STubeRow = STubeRow(c.id, c.title) { p ->
        if (sub != null) return@STubeRow STubePage(subVideos(c, sub, p == null).map(STubeTile::Video))
        suspend fun searched(q: String) = STubePage(
            (try { YtKeyless.search(q) } catch (e: YtException) { if (YouTubeRepository.publicAvailable) YouTubeRepository.search(q) else throw e })
                .filter { it.id.isNotBlank() }.map(STubeTile::Video))
        val page = when {
            c.query != null -> searched(c.query)
            c.id == "cat_trending" -> apiOr({ YouTubeRepository.cachedTrending(null, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.TRENDING) }
            else -> apiOr({ YouTubeRepository.cachedTrending(c.apiCategory, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(c.kiosk!!) }
        }
        if (page.tiles.isEmpty() && p == null && c.fallbackQuery != null) searched(c.fallbackQuery) else page
    }

    /**
     * Filmy podkategorii: listy YouTube i wyszukiwania równolegle (każde niezależnie – błąd jednego nie pusta całości), przeplatane,
     * bez powtórzeń, filtr długości / utworów, rozrzedzone po kanałach (do 2 filmów z jednego, aż do [CATEGORY_MIN] sztuk).
     * Wyniki w pamięci 5 min – powrót do kategorii pokazuje się od razu.
     */
    suspend fun subVideos(c: Category, sub: Sub, useCache: Boolean = true): List<YtVideo> {
        val key = c.id + "/" + sub.id
        if (useCache) synchronized(categoryCache) { categoryCache[key]?.takeIf { System.currentTimeMillis() - it.first < CATEGORY_TTL }?.let { return it.second } }
        val lists = kotlinx.coroutines.coroutineScope {
            val k = sub.kiosks.map { id -> async { runCatching { YtKeyless.kiosk(id) }.getOrDefault(emptyList()) } }
            val q = sub.queries.map { qq -> async { runCatching {
                try { YtKeyless.search(qq) } catch (e: YtException) { if (YouTubeRepository.publicAvailable) YouTubeRepository.search(qq) else throw e }
            }.getOrDefault(emptyList()) } }
            (k + q).map { it.await() }
        }
        val merged = pl.iptv.tv.data.StubeMixes.interleave(lists).filter { it.id.isNotBlank() }.distinctBy { it.id }.filter { v ->
            val sec = pl.iptv.tv.data.StubeMixes.durationSec(v.duration)
            when {
                sub.minSec > 0 -> sec >= sub.minSec
                sub.tracks -> v.duration != "P0D" && !pl.iptv.tv.data.StubeMixes.isNonTrack(v)
                else -> true
            }
        }
        val out = pl.iptv.tv.data.StubeMixes.diversify(merged, 2, CATEGORY_MIN)
        if (out.isNotEmpty()) synchronized(categoryCache) { categoryCache[key] = System.currentTimeMillis() to out }
        return out
    }

    fun clearCategoryCache() = synchronized(categoryCache) { categoryCache.clear() }

    /** Wiek filmu z daty publikacji ISO 8601 („3 godz. temu”, „2 dni temu”) – podpis kafelków subskrypcji; null przy braku daty. */
    fun ago(iso: String, now: Long = System.currentTimeMillis()): String? {
        if (iso.isBlank()) return null
        val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return null
        val min = ((now - t) / 60_000L).coerceAtLeast(0)
        return when {
            min < 60 -> "${min.coerceAtLeast(1)} min temu"
            min < 24 * 60 -> "${min / 60} godz. temu"
            min < 2 * 24 * 60 -> "wczoraj"
            min < 30L * 24 * 60 -> "${min / (24 * 60)} dni temu"
            min < 365L * 24 * 60 -> "${min / (30L * 24 * 60)} mies. temu"
            else -> (min / (365L * 24 * 60)).let { y -> if (y == 1L) "rok temu" else if (y % 10 in 2..4 && y % 100 !in 12..14) "$y lata temu" else "$y lat temu" }
        }
    }

    /** Czas z ISO 8601 (PT1H2M3S) → „1:02:03”; puste dla transmisji na żywo. */
    fun duration(iso: String): String {
        val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").find(iso) ?: return ""
        val (d, h, min, s) = m.destructured
        val hours = (d.toIntOrNull() ?: 0) * 24 + (h.toIntOrNull() ?: 0)
        val mm = min.toIntOrNull() ?: 0; val ss = s.toIntOrNull() ?: 0
        if (hours == 0 && mm == 0 && ss == 0) return ""
        return if (hours > 0) "%d:%02d:%02d".format(hours, mm, ss) else "%d:%02d".format(mm, ss)
    }
}

/** Kafelki jednego rzędu (poziomy RecyclerView). */
class STubeTileAdapter(private val row: STubeRow) : RecyclerView.Adapter<STubeTileAdapter.VH>() {
    var onClick: (STubeTile) -> Unit = {}
    var onLongClick: (STubeTile) -> Unit = {}
    var onFocus: (Int) -> Unit = {}

    class VH(val root: LinearLayout, val thumb: ImageView, val badge: TextView, val icon: ImageView, val title: TextView, val sub: TextView,
             val progress: WatchBar?) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = row.tiles.size
    override fun getItemViewType(position: Int) = if (row.tiles[position] is STubeTile.Channel) 1 else if (row.square) 2 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val channel = viewType == 1; val sq = viewType == 2
        val lay = StubeTheme.layout
        val side = STubeScreen.squareSide(lay)
        val w = Ui.dp(if (channel) STubeScreen.CHANNEL_W else if (sq) side + 12 else lay.tileW)
        val imgW = w - Ui.dp(12); val imgH = if (channel) Ui.dp(STubeScreen.AVATAR) else if (sq) Ui.dp(side) else STubeScreen.thumbHeightPx(lay)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
            setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8))
            gravity = if (channel) Gravity.CENTER_HORIZONTAL else Gravity.START
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(lay.gap) }
        }
        val frame = FrameLayout(c)
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x30)); cornerRadius = Ui.dp(8).toFloat() }
        }
        frame.addView(thumb, FrameLayout.LayoutParams(if (channel) imgH else imgW, imgH))
        Ui.roundClip(thumb, if (channel) imgH / 2f else STubeScreen.thumbRadiusPx())
        // ikona kafelka akcji (zamiast miniatury)
        // ikona kafelka (lista lokalna, akcja): rysowana, w kolorach motywu, zmienia kolor wraz z zaznaczeniem kafelka
        val icon = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; visibility = View.GONE; isDuplicateParentStateEnabled = true; setPadding(imgH / 4, imgH / 4, imgH / 4, imgH / 4) }
        frame.addView(icon, FrameLayout.LayoutParams(imgW, imgH))
        // czas filmu w rogu miniatury (stałe kolory – czytelny na każdym obrazie)
        val badge = TextView(c).apply {
            tag = "keepcolor"; setTextColor(Color.WHITE); textSize = 13f; setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2))
            background = if (pl.iptv.tv.ui.theme.ObsidianViews.active) pl.iptv.tv.ui.theme.ObsidianViews.durationBadge() else GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = Ui.dp(4).toFloat() }
            if (pl.iptv.tv.ui.theme.ObsidianViews.active) { textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(7), Ui.dp(2), Ui.dp(7), Ui.dp(2)) }
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(6), Ui.dp(6) + Ui.dp(STubeScreen.PROGRESS_H)) })
        // pasek obejrzenia (jak w YouTube): na dole miniatury, na całą szerokość; ramka przycięta do zaokrąglenia miniatury
        val progress = if (channel) null else WatchBar(c).also { bar ->
            bar.visibility = View.GONE
            frame.addView(bar, FrameLayout.LayoutParams(imgW, Ui.dp(STubeScreen.PROGRESS_H), Gravity.BOTTOM))
            Ui.roundClip(frame, STubeScreen.thumbRadiusPx())
        }
        root.addView(frame, LinearLayout.LayoutParams(-2, -2))
        val title = TextView(c).apply {
            textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0)
            if (channel) gravity = Gravity.CENTER_HORIZONTAL
            // stała wysokość (2 linie) – rzędy nie skaczą przy przewijaniu
            setLines(lay.titleLines)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val sub = TextView(c).apply { tag = "text2"; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), 0) }
        root.addView(sub, LinearLayout.LayoutParams(-1, -2))
        val vh = VH(root, thumb, badge, icon, title, sub, progress)
        root.setOnClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnLongClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onLongClick); true }
        root.setOnFocusChangeListener { _, f -> if (f && vh.bindingAdapterPosition >= 0) { row.lastFocus = vh.bindingAdapterPosition; onFocus(vh.bindingAdapterPosition) } }
        // styl motywu (tło kafelka ze stanem fokusu, powiększenie, przycięcie) – raz, przy tworzeniu widoku
        Ui.style(root)
        return vh
    }

    /**
     * (7.16) Miniatura w rozmiarze widoku (bez dekodowania pełnego obrazu), mniejszy wariant adresu YouTube dla małych kafelków
     * (mqdefault 320×180 – kilka razy mniej danych niż maxresdefault) i łagodne pojawienie się obrazu.
     */
    private fun loadThumb(iv: ImageView, url: String) {
        val lp = iv.layoutParams
        val w = lp?.width ?: 0; val h = lp?.height ?: 0
        val smooth = !runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)
        iv.load(STubeScreen.thumbUrl(url, w)) {
            crossfade(if (smooth) 140 else 0)
            // bez stałych wymiarów widoku – rozmiar kafelka z bieżącego układu (nigdy pełna rozdzielczość miniatury)
            val lay = StubeTheme.layout
            fitTo(iv, Ui.dp(lay.tileW) - Ui.dp(12), STubeScreen.thumbHeightPx(lay))
        }
    }

    /** Wczytuje z wyprzedzeniem miniatury [count] kafelków od [from] (do pamięci podręcznej) – siatka pojawia się już z obrazami. */
    fun prefetch(ctx: android.content.Context, from: Int, count: Int) {
        val lay = StubeTheme.layout
        val w = Ui.dp(lay.tileW) - Ui.dp(12); val h = STubeScreen.thumbHeightPx(lay)
        val loader = coil.Coil.imageLoader(ctx)
        for (i in from until minOf(row.tiles.size, from + count)) {
            val v = (row.tiles[i] as? STubeTile.Video)?.v ?: continue
            if (v.thumb.isBlank()) continue
            loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(STubeScreen.thumbUrl(v.thumb, w)).size(w, h).build())
        }
    }

    private fun tileIcon(kind: String) = PlayerIcon(kind, android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(Ui.focusText, Ui.focusText, Ui.withAlpha(Ui.text, 0xB0))))

    /** Payload [STubeScreen.PAYLOAD_PROGRESS] – tylko pasek obejrzenia (powrót z odtwarzacza bez migania miniatur). */
    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == STubeScreen.PAYLOAD_PROGRESS }) bindProgress(h, row.tiles.getOrNull(position))
        else onBindViewHolder(h, position)
    }

    private fun bindProgress(h: VH, t: STubeTile?) {
        val bar = h.progress ?: return
        val f = (t as? STubeTile.Video)?.v?.id?.let(StubeHistory::fractionOf)
        bar.fraction = f ?: 0f
        bar.visibility = if (f != null) View.VISIBLE else View.GONE
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        bindProgress(h, row.tiles[position])
        coil.util.CoilUtils.dispose(h.thumb)
        h.thumb.setImageDrawable(null)
        when (val t = row.tiles[position]) {
            is STubeTile.Video -> {
                h.icon.visibility = View.GONE; h.thumb.visibility = View.VISIBLE
                if (t.v.thumb.isNotBlank()) loadThumb(h.thumb, t.v.thumb)
                val d = STubeScreen.duration(t.v.duration)
                h.badge.text = d.ifBlank { if (t.v.duration == "P0D") "● NA ŻYWO" else "" }
                h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
                h.title.text = t.v.title; h.sub.visibility = if (StubeTheme.layout.showSub) View.VISIBLE else View.GONE
                h.sub.text = t.v.channel + STubeScreen.ago(t.v.publishedAt)?.let { " · $it" }.orEmpty()
                h.root.contentDescription = t.v.title
            }
            is STubeTile.Channel -> {
                h.icon.visibility = View.GONE; h.badge.visibility = View.GONE
                if (t.c.thumb.isNotBlank()) loadThumb(h.thumb, t.c.thumb)
                h.title.text = t.c.title; h.sub.visibility = View.GONE
                h.root.contentDescription = t.c.title
            }
            is STubeTile.Playlist -> {
                // miniatura playlisty (konto/kanał) albo duża ikona (listy lokalne); liczba filmów w rogu
                val hasThumb = t.p.thumb.isNotBlank()
                h.icon.visibility = if (hasThumb) View.GONE else View.VISIBLE; h.icon.setImageDrawable(tileIcon(STubeScreen.playlistIconKind(t.p.id)))
                if (hasThumb) loadThumb(h.thumb, t.p.thumb)
                val count = if (t.p.id == STubeScreen.PL_LATER) StubeHistory.watchLaterCount else t.p.count
                h.badge.text = if (count >= 0) "☰ $count" else ""
                h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
                h.title.text = t.p.title
                h.sub.text = when {
                    t.p.id.startsWith("local:") -> "Na tym urządzeniu"
                    count >= 0 && !t.p.id.startsWith(STubeScreen.PL_MIX) -> "Playlista • $count"
                    t.p.id.startsWith(STubeScreen.PL_MIX) -> pl.iptv.tv.data.StubeMixes.byId(t.p.id.removePrefix(STubeScreen.PL_MIX))?.subtitle ?: "Gotowy miks"
                    t.p.id == STubeScreen.PL_LIKED -> "Konto YouTube"
                    else -> "Playlista"
                }
                h.sub.visibility = View.VISIBLE
                h.root.contentDescription = t.p.title
            }
            is STubeTile.Action -> {
                h.icon.visibility = View.VISIBLE; h.icon.setImageDrawable(tileIcon(STubeScreen.actionIconKind(t.icon))); h.badge.visibility = View.GONE
                h.title.text = t.label; h.sub.visibility = View.GONE
                h.root.contentDescription = t.label
            }
        }
    }
}

/**
 * Pasek obejrzenia na miniaturze (jak w YouTube): półprzezroczysty tor na całą szerokość i wypełnienie w kolorze akcentu motywu.
 * [fraction] 0..1 – część obejrzana ([StubeHistory.fractionOf]).
 */
class WatchBar(c: android.content.Context) : View(c) {
    var fraction = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private val track = android.graphics.Paint().apply { color = 0x66FFFFFF }
    private val fill = android.graphics.Paint().apply { color = Ui.accent or 0xFF000000.toInt() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, track)
        if (fraction > 0f) canvas.drawRect(0f, 0f, w * fraction, h, fill)
    }
}

/**
 * Podpowiedzi pola wyszukiwania STube (historia z [StubeHistory]): duży tekst, wyraźne zaznaczenie ▲▼ wg motywu (10-foot UI).
 * Lista ustawiana z zewnątrz ([set]); filtr nie zawęża – dopasowanie robi zapytanie do bazy.
 */
class STubeSuggestAdapter(private val c: android.content.Context) : android.widget.BaseAdapter(), android.widget.Filterable {
    var items: List<String> = emptyList(); private set

    private var history: Set<String> = emptySet()
    /** [fromHistory] – frazy z historii (ikona zegara); pozostałe to podpowiedzi na żywo (lupa). */
    fun set(list: List<String>, fromHistory: Set<String> = list.toSet()) { items = list; history = fromHistory; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(c).apply {
            textSize = 18f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8)); minHeight = Ui.dp(44); gravity = Gravity.CENTER_VERTICAL
            val sel = intArrayOf(android.R.attr.state_selected); val foc = intArrayOf(android.R.attr.state_focused); val pr = intArrayOf(android.R.attr.state_pressed)
            setTextColor(android.content.res.ColorStateList(arrayOf(sel, foc, pr, intArrayOf()), intArrayOf(Ui.focusText, Ui.focusText, Ui.focusText, Ui.text)))
            background = android.graphics.drawable.StateListDrawable().apply {
                val on = GradientDrawable().apply { setColor(Ui.focus); cornerRadius = Ui.dp(maxOf(Ui.preset.radius - 6, 4)).toFloat() }
                addState(sel, on); addState(foc, on); addState(pr, on)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
        }
        tv.text = items[position]
        // rysowana ikona (historia – zegar, podpowiedź – lupa) w kolorach motywu zamiast emoji
        tv.setCompoundDrawables(PlayerIcon(if (items[position] in history) PlayerIcon.DELAY else PlayerIcon.SEARCH, tv.textColors).apply { setBounds(0, 0, Ui.dp(20), Ui.dp(20)) }, null, null, null)
        tv.compoundDrawablePadding = Ui.dp(14)
        return tv
    }

    private val noFilter = object : android.widget.Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply { values = items; count = items.size }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        override fun convertResultToString(resultValue: Any?) = resultValue as? String ?: ""
    }
    override fun getFilter(): android.widget.Filter = noFilter
}

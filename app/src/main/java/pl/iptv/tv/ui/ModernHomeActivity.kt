package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as NativeKey
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.iptv.tv.ui.theme.obsidianAmbientGlow
import pl.iptv.tv.ui.theme.obsidianHeroVignette
import pl.iptv.tv.ui.theme.obsidianGlassCard
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Action

/**
 * Ekran główny motywu Nowoczesny (Jetpack Compose, [ModernThemeProvider]) – układ serwisu VOD na telewizorze.
 *
 * Przy lewej krawędzi pionowy pasek ikon (profil i opcje, szukaj, sekcje: Start, Na żywo, STube, Stremio, Moje playlisty,
 * ustawienia, wyjście) – po wejściu fokusu rozwija się z podpisami. U góry nazwa sekcji i zegar, pod nią „hero”: tytuł, opis,
 * metadane (kanał, godziny emisji, rodzaj) i przyciski „Odtwórz” / „Więcej informacji” zaznaczonego materiału, a za nimi
 * jego obraz na pełną szerokość wygaszany w lewo i w dół. Niżej rzędy kafelków (nagłówek z czerwoną kropką): kanały
 * (z paskiem postępu bieżącego programu), filmy STube 16:9 i plakaty Stremio z postępem oglądania z Room, skróty funkcji.
 *
 * Funkcje uruchamiane wyłącznie przez istniejące ekrany ([X25Actions]) – telewizja, STube, Stremio, odtwarzacze i Room bez zmian.
 * Wydajność: obraz hero zmieniany z opóźnieniem (szybkie przewijanie nie wczytuje obrazów), gradienty i obwódki rysowane
 * w Compose ([modernScrim], [modernFrame]) – bez bitmap tła; powiększenie kafelka tylko w warstwie (bez nowego układu).
 */
@OptIn(ExperimentalComposeUiApi::class)
class ModernHomeActivity : BaseActivity() {
    override val videoBackground = false
    /**
     * Telewizja wstrzymana na ekranie głównym (jak w STube / Stremio): bez dekodowania kanału „w tle bez obrazu”. Wcześniej kanał
     * grał tu bez powierzchni obrazu, a odtwarzacz przejmował potem ten sam dekoder – na części TV obraz się zacinał, aż do
     * ręcznej zmiany silnika. Wybrany kanał startuje czysto; po wejściu do menu wstrzymany kanał wraca.
     */
    override val pausesLiveTv = true

    enum class Kind { WIDE, POSTER, CHANNEL, ACTION }

    /** Materiał w „hero”: [images] – kolejne adresy (następny, gdy poprzedni się nie wczyta), [logo] – logo kanału na gradiencie. */
    @androidx.compose.runtime.Immutable
    data class Feature(
        val key: String, val title: String, val meta: List<String> = emptyList(), val time: String = "", val badge: String = "",
        val desc: String = "", val images: List<String> = emptyList(), val logo: Boolean = false, val fraction: Float = -1f,
        val playLabel: String = "Odtwórz", val play: () -> Unit, val info: () -> Unit,
        /** (7.8) Kanały: przycisk „Program TV” obok „Więcej informacji” – przewodnik TV ustawiony na tym kanale. */
        val guide: (() -> Unit)? = null,
    )

    @androidx.compose.runtime.Immutable
    data class Tile(val key: String, val title: String, val sub: String, val image: String, val kind: Kind, val feature: Feature,
                    val fraction: Float = -1f, val icon: String = "",
                    /** Kanały: czasy bieżącego programu – pasek postępu liczony przy rysowaniu ([EpgProgressFill]). */
                    val progStart: Long = 0L, val progEnd: Long = 0L, val run: () -> Unit)

    @androidx.compose.runtime.Immutable
    data class Shelf(val id: String, val title: String, val tiles: List<Tile>)

    private class Nav(val icon: String, val label: String)
    private val NAV = listOf(Nav(ModernIcons.HOME, "Start"), Nav(X25Action.LIVE, "Na żywo"), Nav(X25Action.STUBE, "STube"),
        Nav(X25Action.STREMIO, "Stremio"), Nav(ModernIcons.PLAYLIST, "Moje playlisty"))

    private var section by mutableIntStateOf(0)
    private var recent by mutableStateOf<List<Channel>>(emptyList())
    private var favs by mutableStateOf<List<Channel>>(emptyList())
    private var all by mutableStateOf<List<Channel>>(emptyList())
    private var videos by mutableStateOf<List<WatchProgress>>(emptyList())
    private var titles by mutableStateOf<List<StremioProgress>>(emptyList())
    private var clock by mutableStateOf("")
    /**
     * (7.17) Zmiana bieżących programów kanałów: rzędy przebudowywane dopiero, gdy któryś pokazany program się skończył
     * (albo zaczął) – dawniej co minutę cała lista. Postęp programu przesuwa się sam ([EpgProgressFill], [EpgClock]).
     */
    private var epgEpoch by mutableStateOf(0)
    /** Najbliższy koniec / początek programu na pokazanych kafelkach kanałów (ms). */
    private var nextEpgChange = Long.MAX_VALUE
    private var feature by mutableStateOf<Feature?>(null)
    private var options by mutableStateOf(false)
    private var railOpen by mutableStateOf(false)
    private var loaded = false
    private var repoVersion = -1
    private var featureJob: Job? = null

    private var pendingFocus by mutableStateOf<String?>(null)
    /** (7.8) Zmiana wymusza przewinięcie rzędów na górę (OK na Domku – pierwszy rząd musi być złożony, żeby dostać fokus). */
    private var topTick by mutableIntStateOf(0)
    private var lastFocus: String? = null
    /** Ostatnio zaznaczony element treści (hero / rzędy) – tu wraca fokus z paska ikon. */
    private var lastContent: String? = null
    private var focusedNow: String? = null
    private val requesters = HashMap<String, FocusRequester>()
    private val attached = HashSet<String>()
    private val rowLast = HashMap<String, String>()

    // ---- dostęp dla testów ----
    val focusedKey get() = lastFocus
    val sectionNow get() = section
    val shelfIds get() = shelves().map { it.id }
    fun tileKeys(shelf: String) = shelves().firstOrNull { it.id == shelf }?.tiles?.map { it.key } ?: emptyList()
    val featureTitle get() = feature?.title
    val optionsOpen get() = options
    val optionLabels get() = if (options) optionItems().map { it.label } else emptyList()
    fun clickOption(label: String) { optionItems().firstOrNull { it.label == label }?.action?.invoke() }
    val railOpenNow get() = railOpen
    val dimsNow get() = dims

    private fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = onBack() })
        setContent { TvResolution1080pProvider { ModernThemeProvider { Home() } } }
        pendingFocus = HERO_PLAY
        lifecycleScope.launch {
            if (!StubeHistory.cacheLoaded) runCatching { StubeHistory.loadCache() }
            loaded = true
            refresh(force = true)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { tick(); delay(2_000) } } }
    }

    /** Wyłączony motyw / ekran główny: ustępuje liście kanałów (albo innemu pulpitowi); inne kolory – przebudowa. */
    override fun onThemeEpochChanged() { if (ModernTheme.homeOn) super.onThemeEpochChanged() else finish() }

    override fun onResume() {
        super.onResume()
        if (!ModernTheme.homeOn) { if (!isFinishing) finish(); return }
        if (loaded) refresh(force = true)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        clock = Fmt.time(now)
        if (now >= nextEpgChange) { nextEpgChange = Long.MAX_VALUE; epgEpoch++ }
        if (loaded && Repository.version != repoVersion) refresh()
    }

    private fun refresh(force: Boolean = false) {
        if (!force && Repository.version == repoVersion) return
        repoVersion = Repository.version
        lifecycleScope.launch {
            val r = Repository.recent(12)
            val f = Repository.favorites().take(40)
            val a = Repository.channels.asSequence().filter { !it.hidden }.take(60).toList()
            val vids = runCatching { StubeHistory.watched(50) }.getOrDefault(emptyList())
            val st = runCatching { StremioStore.progress(50) }.getOrDefault(emptyList())
            if (r != recent) recent = r
            if (f != favs) favs = f
            if (a != all) all = a
            if (vids != videos) videos = vids
            if (st != titles) titles = st
            if (feature == null || !(lastFocus?.startsWith("r:") ?: false)) feature = defaultFeature()
        }
    }

    // ---------------- WSTECZ / MENU ----------------

    /** WSTECZ: panel opcji → zamknięcie; z treści → pasek ikon (bieżąca sekcja); z paska → wyjście (launcher: zostaje). */
    private fun onBack() {
        when {
            options -> closeOptions()
            focusedNow?.startsWith("nav:") != true -> pendingFocus = "nav:s$section"
            X25Theme.launcherHome -> pendingFocus = lastContent ?: HERO_PLAY
            else -> finishAffinity()
        }
    }

    override val navRepeatMs get() = COMPOSE_NAV_REPEAT_MS

    override fun dispatchKeyEvent(event: NativeKey): Boolean {
        if (event.keyCode == NativeKey.KEYCODE_MENU) {
            if (event.action == NativeKey.ACTION_UP) { if (options) closeOptions() else openOptions() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openOptions() { options = true }
    private fun closeOptions() { options = false; pendingFocus = lastFocus ?: HERO_PLAY }

    private var optionsVersion by mutableIntStateOf(0)
    private fun refreshOptions() { optionsVersion++ }

    private fun optionItems(): List<ModernOption> = listOf(
        ModernOption("Ekran główny przy starcie", "Otwierany przy uruchomieniu i przyciskiem HOME", Prefs.modernHome) {
            Prefs.modernHome = !Prefs.modernHome; refreshOptions() },
        ModernOption("Menu jako panel nakładkowy", "Listy wyboru wysuwane z prawej strony", Prefs.modernOverlay) {
            Prefs.modernOverlay = !Prefs.modernOverlay; refreshOptions() },
        ModernOption("Konto i ustawienia STube", "Logowanie, subskrypcje, historia") { options = false; X25Actions.run(this, X25Action(X25Action.SETTINGS)) },
        ModernOption("Wtyczki Stremio", "Włącz lub wyłącz źródła") { options = false; startActivity(Intent(this, StremioAddonsActivity::class.java)) },
        ModernOption("Wszystkie aplikacje", "Biblioteka aplikacji urządzenia") { options = false; SystemTools.showApps(this) },
        ModernOption("Ustawienia systemu", "Sieć, dźwięk, obraz, konta urządzenia") { options = false; SystemTools.openSystemSettings(this) },
        ModernOption("Ustawienia aplikacji", "Wygląd, listy, odtwarzanie, tryb launchera") { options = false; X25Actions.run(this, X25Action(X25Action.SETTINGS)) },
        ModernOption("Przełącz na motyw PS5", "Granat, tło blueprint, ekran główny konsoli") { options = false; switchTheme { Ps5Theme.setEnabled(true) } },
        ModernOption("Przełącz na motyw X25", "Pulpit z kafelkami w stylu konsoli") { options = false; switchTheme { X25Theme.setEnabled(true) } },
    )

    private fun switchTheme(enable: () -> Unit) {
        enable(); X25Theme.justEnabled = false
        if (X25Theme.dashboardOn) X25Theme.openDashboard(this)
        finish()
    }

    // ---------------- zawartość sekcji ----------------

    private fun act(type: String, arg: String = "", title: String = "") = { X25Actions.run(this, X25Action(type, label = title, arg = arg)) }

    private fun timeRange(s: Long, e: Long) = Fmt.time(s) + " – " + Fmt.time(e)

    private fun channelFeature(c: Channel): Feature {
        val p = runCatching { Repository.current(c) }.getOrNull()
        return Feature("ch:" + c.key, p?.fullTitle?.ifBlank { null } ?: c.name, listOfNotNull(c.name, p?.category?.ifBlank { null } ?: c.group.ifBlank { null }),
            p?.let { timeRange(it.start, it.end) } ?: "", if (pl.iptv.tv.data.isActualLiveBroadcast(p)) "NA ŻYWO" else "", p?.desc?.ifBlank { null } ?: "Telewizja na żywo · ${c.group}",
            listOfNotNull(c.logo.ifBlank { null } ?: c.epgIcon), logo = true, playLabel = "Oglądaj",
            play = { X25Actions.playChannel(this, c) },
            // (7.8) „Więcej informacji” – opis bieżącego programu; „Program TV” – przewodnik TV na tym kanale
            info = { channelInfo(c) },
            guide = { startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, c.key)) })
    }

    /** (7.8) Okno z opisem programu emitowanego teraz na kanale [c]; przycisk „Program TV” otwiera przewodnik. */
    private fun channelInfo(c: Channel) {
        val p = runCatching { Repository.current(c) }.getOrNull()
        val msg = if (p == null) "Brak informacji o programie dla tego kanału.\n\nKanał: ${c.name}" else
            listOfNotNull(timeRange(p.start, p.end), p.category.takeIf { it.isNotBlank() }, c.name).joinToString("  ·  ") +
                (p.desc.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "")
        Ui.dialog(this).setTitle(p?.fullTitle?.ifBlank { null } ?: c.name).setMessage(msg)
            .setPositiveButton("Zamknij", null)
            .setNeutralButton("Program TV") { _, _ -> startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, c.key)) }
            .show()
    }

    private fun channelTile(shelf: String, c: Channel): Tile {
        val p = runCatching { Repository.current(c) }.getOrNull()
        val now = System.currentTimeMillis()
        val frac = p?.let { EpgClock.fraction(it.start, it.end, now) } ?: -1f
        // następna zmiana kafelka: koniec bieżącego programu albo początek następnego (przerwa w EPG)
        val change = p?.end ?: runCatching { Repository.next(c, now)?.start }.getOrNull()
        if (change != null && change > now) nextEpgChange = minOf(nextEpgChange, change)
        return Tile("ch:" + c.key, p?.fullTitle?.ifBlank { null } ?: c.name, if (p != null) c.name else c.group, c.logo.ifBlank { c.epgIcon ?: "" },
            Kind.CHANNEL, channelFeature(c), frac, progStart = p?.start ?: 0L, progEnd = p?.end ?: 0L) { X25Actions.playChannel(this, c, if (shelf == "fav") Repository.FAV else Repository.ALL) }
    }

    private fun videoFeature(v: WatchProgress) = Feature("yt:" + v.videoId, v.title.ifBlank { "Film" }, listOf("STube", v.channel).filter { it.isNotBlank() },
        badge = "KONTYNUUJ", desc = "Obejrzano ${(v.fraction * 100).toInt()}%. Wróć do filmu dokładnie tam, gdzie skończyłeś.",
        images = listOf("https://i.ytimg.com/vi/${v.videoId}/maxresdefault.jpg", v.thumb).filter { it.isNotBlank() },
        fraction = v.fraction, playLabel = "Wznów", play = { X25Actions.playVideo(this, v.toVideo()) },
        info = { startActivity(Intent(this, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_QUERY, v.channel.ifBlank { v.title })) })

    private fun videoTile(v: WatchProgress) = Tile("yt:" + v.videoId, v.title.ifBlank { "Film" }, v.channel, v.thumb, Kind.WIDE, videoFeature(v), v.fraction) {
        X25Actions.playVideo(this, v.toVideo())
    }

    private fun titleFeature(t: StremioProgress): Feature {
        val preview = pl.iptv.tv.data.StremioMetaPreview(t.metaId, t.type, t.name, t.poster)
        val bg = if (t.metaId.startsWith("tt")) "https://images.metahub.space/background/medium/${t.metaId.substringBefore(':')}/img" else ""
        return Feature("st:" + t.metaId, t.name.ifBlank { "Stremio" },
            listOf(if (t.type == "series") "Serial" else "Film", t.videoLabel).filter { it.isNotBlank() }, badge = "KONTYNUUJ",
            desc = "Obejrzano ${(t.fraction * 100).toInt()}%. Odtwarzanie od miejsca, w którym skończyłeś, ze źródeł włączonych wtyczek.",
            images = listOf(bg, t.poster).filter { it.isNotBlank() }, fraction = t.fraction, playLabel = "Wznów",
            play = { startActivity(StremioPlayerActivity.resumeIntent(this, t)) },   // (7.8) od razu odtwarzanie
            info = { startActivity(StremioDetailActivity.intent(this, preview, null)) })
    }

    private fun titleTile(t: StremioProgress) = Tile("st:" + t.metaId, t.name.ifBlank { "Stremio" }, t.videoLabel.ifBlank { if (t.type == "series") "Serial" else "Film" },
        t.poster, Kind.POSTER, titleFeature(t), t.fraction) { titleFeature(t).play() }

    /** Kafelek funkcji (skrót): opis w hero, OK – istniejący ekran. */
    private fun actionTile(key: String, icon: String, title: String, desc: String, run: () -> Unit) =
        Tile("a:$key", title, desc, "", Kind.ACTION, Feature("a:$key", title, listOf("D-IPTV Player"), desc = desc, playLabel = "Otwórz", play = run, info = run), icon = icon, run = run)

    private fun searchStube() = actionTile("stsearch", X25Icons.SEARCH, "Szukaj w STube", "Filmy, kanały i muzyka – wyszukiwanie w STube.") { searchIn(true) }
    private fun searchStremio() = actionTile("stremiosearch", X25Icons.SEARCH, "Szukaj filmów i seriali", "Wyszukiwanie we wtyczkach Stremio.") { searchIn(false) }

    /** Rzędy sekcji (puste pomijane). */
    private fun shelves(s: Int = section): List<Shelf> = when (s) {
        1 -> listOf(
            Shelf("recent", "Ostatnio oglądane", recent.map { channelTile("recent", it) }),
            Shelf("fav", "Ulubione kanały", favs.map { channelTile("fav", it) }),
            Shelf("all", "Kanały TV", all.map { channelTile("all", it) }),
            Shelf("tv", "Telewizja", listOf(
                actionTile("live", X25Action.LIVE, "Lista kanałów", "Wszystkie kanały z kategoriami i podglądem.", act(X25Action.LIVE)),
                actionTile("guide", X25Action.GUIDE, "Przewodnik TV", "Program telewizyjny wszystkich kanałów, nagrywanie i archiwum.", act(X25Action.GUIDE)),
                actionTile("epg", X25Action.EPG_SEARCH, "Wyszukiwarka programów", "Filmy, mecze i seriale w programie TV, przypomnienia.", act(X25Action.EPG_SEARCH)),
                actionTile("favplay", X25Action.FAVORITES, "Ulubione", "Odtwarzanie ulubionych kanałów po kolei.", act(X25Action.FAVORITES)))),
        )
        2 -> listOf(
            Shelf("continue", "Kontynuuj oglądanie", videos.filter { it.positionMs > 0 && !it.finished }.map { videoTile(it) }),
            Shelf("discover", "Odkrywaj", listOf(
                searchStube(),
                actionTile("trending", X25Action.STUBE_ROW, "Na czasie", "Najpopularniejsze filmy teraz.", act(X25Action.STUBE_ROW, STubeScreen.ROW_TRENDING)),
                actionTile("foryou", X25Action.STUBE_ROW, "Dla Ciebie", "Propozycje z konta YouTube.", act(X25Action.STUBE_ROW, STubeScreen.ROW_FOR_YOU)),
                actionTile("subs", X25Action.SUBS, "Subskrypcje", "Najnowsze filmy z subskrybowanych kanałów.", act(X25Action.SUBS)),
                actionTile("music", X25Action.STUBE_ROW, "Muzyka", "Teledyski i muzyka.", act(X25Action.STUBE_ROW, STubeScreen.ROW_MUSIC)),
                actionTile("mixes", X25Action.STUBE_ROW, "Twoje miksy", "Miksy z historii oglądania.", act(X25Action.STUBE_ROW, STubeScreen.ROW_MIXES)),
                actionTile("stube", X25Action.STUBE, "Otwórz STube", "YouTube bez reklam ze SponsorBlockiem.", act(X25Action.STUBE)))),
            Shelf("watched", "Obejrzane", videos.map { videoTile(it) }),
        )
        3 -> listOf(
            Shelf("stcontinue", "Oglądaj dalej", titles.filter { !it.finished }.map { titleTile(it) }),
            // (7.5) jak na tablicy Stremio: pod „Oglądaj dalej” propozycje filmów, pod nimi – seriali
            Shelf("stmovies", "Filmy – propozycje", stMovies.map { metaTile(it) }),
            Shelf("stseries", "Seriale – propozycje", stSeries.map { metaTile(it) }),
            Shelf("stremio", "Filmy i seriale", listOf(
                actionTile("stremio", X25Action.STREMIO, "Przeglądaj", "Katalogi filmów i seriali z włączonych wtyczek.", act(X25Action.STREMIO)),
                searchStremio(),
                actionTile("addons", X25Icons.EDIT, "Wtyczki", "Włącz lub wyłącz źródła, dodaj wtyczkę z adresu.") {
                    startActivity(Intent(this, StremioAddonsActivity::class.java)) })),
            Shelf("stall", "Ostatnio oglądane", titles.filter { it.finished }.map { titleTile(it) }),
        )
        4 -> listOf(
            Shelf("library", "Biblioteka", listOf(
                actionTile("later", ModernIcons.PLAYLIST, "Do obejrzenia później", "Filmy odłożone na później.",
                    act(X25Action.STUBE_PLAYLIST, STubeScreen.PL_LATER, "Do obejrzenia później")),
                actionTile("history", X25Action.HISTORY, "Historia oglądania", "Obejrzane i rozpoczęte filmy.", act(X25Action.HISTORY)),
                actionTile("liked", X25Action.STUBE_PLAYLIST, "Polubione", "Filmy polubione na koncie YouTube.",
                    act(X25Action.STUBE_PLAYLIST, STubeScreen.PL_LIKED, "Polubione")),
                actionTile("playlists", X25Action.PLAYLISTS, "Wszystkie playlisty", "Playlisty z konta i zapisane lokalnie.", act(X25Action.PLAYLISTS)),
                actionTile("subs", X25Action.SUBS, "Subskrypcje", "Najnowsze z kanałów.", act(X25Action.SUBS)))),
            Shelf("hist", "Historia oglądania", videos.map { videoTile(it) }),
            Shelf("sthist", "Filmy i seriale", titles.map { titleTile(it) }),
        )
        else -> listOf(
            // (7.8) Start: zawsze najpierw Live TV, pod nim STube, na końcu Stremio
            Shelf("channels", if (recent.isNotEmpty()) "Ostatnio oglądane" else "Kanały TV", recent.ifEmpty { all.take(12) }.map { channelTile("channels", it) }),
            Shelf("continue", "Kontynuuj oglądanie", videos.filter { it.positionMs > 0 && !it.finished }.map { videoTile(it) }),
            Shelf("stcontinue", "Filmy i seriale", titles.map { titleTile(it) }),
            Shelf("shortcuts", "Na skróty", listOf(
                actionTile("live", X25Action.LIVE, "Telewizja", "Wszystkie kanały na żywo.", act(X25Action.LIVE)),
                actionTile("guide", X25Action.GUIDE, "Przewodnik TV", "Program telewizyjny.", act(X25Action.GUIDE)),
                actionTile("stube", X25Action.STUBE, "STube", "YouTube bez reklam.", act(X25Action.STUBE)),
                actionTile("stremio", X25Action.STREMIO, "Stremio", "Filmy i seriale z wtyczek.", act(X25Action.STREMIO)),
                actionTile("epg", X25Action.EPG_SEARCH, "Wyszukiwarka programów", "Szukaj w programie TV.", act(X25Action.EPG_SEARCH)),
                actionTile("apps", X25Action.APPS, "Aplikacje", "Zainstalowane aplikacje.", act(X25Action.APPS)))),
        )
    }.filter { it.tiles.isNotEmpty() }

    /** Hero sekcji przed zaznaczeniem kafelka: ostatnio oglądany kanał / film / tytuł, inaczej powitanie. */
    private fun defaultFeature(s: Int = section): Feature {
        val lastCh = Repository.byKey(Prefs.lastChannel)?.takeIf { !it.hidden } ?: recent.firstOrNull()
        return when (s) {
            1 -> lastCh?.let { channelFeature(it) }
            2 -> videos.firstOrNull()?.let { videoFeature(it) }
            3 -> titles.firstOrNull()?.let { titleFeature(it) }
            4 -> null
            // (7.3) Start: zawsze ostatni kanał telewizji; film / tytuł tylko, gdy żadnego kanału jeszcze nie oglądano
            else -> lastCh?.let { channelFeature(it) } ?: run {
                val v = videos.firstOrNull(); val t = titles.firstOrNull()
                when {
                    v != null && (t == null || v.updatedAt >= t.updatedAt) -> videoFeature(v)
                    t != null -> titleFeature(t)
                    else -> null
                }
            }
        } ?: when (s) {
            4 -> Feature("w:lib", "Moje playlisty", listOf("STube", "Biblioteka"), desc = "Filmy na później, historia, polubione i wszystkie playlisty w jednym miejscu.",
                playLabel = "Otwórz", play = act(X25Action.PLAYLISTS), info = act(X25Action.HISTORY))
            3 -> Feature("w:st", "Filmy i seriale", listOf("Stremio"), desc = "Katalogi z włączonych wtyczek, źródła wg jakości, polski dźwięk i napisy.",
                playLabel = "Przeglądaj", play = act(X25Action.STREMIO), info = { startActivity(Intent(this, StremioAddonsActivity::class.java)) })
            2 -> Feature("w:yt", "STube", listOf("YouTube bez reklam"), desc = "Filmy, muzyka i subskrypcje z pominięciem sponsorowanych fragmentów.",
                playLabel = "Otwórz", play = act(X25Action.STUBE), info = act(X25Action.SUBS))
            else -> Feature("w:tv", "Witaj w D-IPTV Player", listOf("Telewizja", "STube", "Stremio"),
                desc = "Telewizja na żywo z przewodnikiem i archiwum, YouTube bez reklam oraz filmy i seriale – wszystko w jednym miejscu.",
                playLabel = "Oglądaj telewizję", play = act(X25Action.LIVE), info = act(X25Action.GUIDE))
        }
    }

    /** Nowa sekcja: hero i rzędy od początku, fokus na pierwszym kafelku (gdy rzędy są) albo na „Odtwórz”. */
    private fun selectSection(i: Int) {
        if (section != i) { section = i; rowLast.clear() }
        if (i == 3) loadStremioSuggestions()
        feature = defaultFeature(i)
        val first = shelves(i).firstOrNull()?.let { "r:${it.id}|${it.tiles.first().key}" }
        lastContent = null
        pendingFocus = first ?: HERO_PLAY
    }

    /** (7.6) Postęp przejścia między sekcjami (0 → 1). */
    private val sectionAnim = androidx.compose.animation.core.Animatable(1f)
    /** Sekcja, dla której działa [sectionAnim] (różna od [section] = przejście jeszcze nie ruszyło – rysunek od zera). */
    private var animSection by mutableIntStateOf(0)     // stan – przypisanie odświeża warstwę także w trybie słabszych urządzeń (bez animacji)
    private val lowPerfNow get() = runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)

    /** Przenikanie z przesunięciem o 18 dp wg [sectionAnim] – odczyt w warstwie rysowania (bez ponownego składania). */
    private fun Modifier.sectionFade(): Modifier = graphicsLayer {
        // (7.14) nowa sekcja rysowana od razu z v = 0 (wcześniej jedna klatka z pełną widocznością, potem skok); bez przesunięcia pionowego
        // (7.16) bez wygaszania całej listy przy zmianie kategorii (wyglądało źle i kosztowało klatki) – treść podmieniana od razu,
        // jak w oknie opcji STube; płynność zapewnia przenikanie obrazu hero
        if (animSection != section) alpha = 1f
    }

    /**
     * (7.7) Podgląd sekcji dopiero, gdy kursor zatrzyma się na pasku ([PREVIEW_DELAY_MS]) – szybkie przejście po kilku ikonach
     * nie przebudowuje po drodze każdej sekcji (rzędy, hero, obrazy) – płynny ruch po pasku.
     */
    private var previewJob: Job? = null
    private fun schedulePreview(i: Int) {
        previewJob?.cancel()
        if (section == i) return
        previewJob = lifecycleScope.launch { delay(PREVIEW_DELAY_MS); previewSection(i) }
    }

    /** (7.5) Zaznaczenie sekcji w pasku – podgląd jej rzędów i hero (fokus zostaje w pasku). */
    private fun previewSection(i: Int) {
        if (section == i) return
        section = i; rowLast.clear(); lastContent = null
        feature = defaultFeature(i)
        if (i == 3) loadStremioSuggestions()
    }

    /**
     * (7.5) OK / dotknięcie zakładki w pasku: od razu ta zakładka – Na żywo, STube, Stremio, Moje playlisty otwierają swoje ekrany;
     * Domek (Start) – kursor w prawo na „Ostatnio oglądane”.
     */
    private fun openSection(i: Int) {
        previewJob?.cancel()
        when (i) {
            0 -> {
                if (section != 0) { section = 0; rowLast.clear() }
                topTick++
                feature = defaultFeature(0)
                val list = shelves(0)
                val target = list.firstOrNull { it.id == "channels" } ?: list.firstOrNull()
                lastContent = null
                pendingFocus = target?.let { "r:${it.id}|${it.tiles.first().key}" } ?: HERO_PLAY
            }
            1 -> act(X25Action.LIVE)()
            2 -> act(X25Action.STUBE)()
            3 -> act(X25Action.STREMIO)()
            else -> act(X25Action.PLAYLISTS)()
        }
    }

    // ---------------- (7.5) propozycje Stremio: filmy, potem seriale ----------------

    private var stMovies by mutableStateOf<List<pl.iptv.tv.data.StremioMetaPreview>>(emptyList())
    private var stSeries by mutableStateOf<List<pl.iptv.tv.data.StremioMetaPreview>>(emptyList())
    private var stLoad: Job? = null
    val stremioRowsNow get() = stMovies.size to stSeries.size

    /** Pierwszy katalog filmów i pierwszy katalog seriali z włączonych wtyczek (jak tablica Stremio) – raz na ekran. */
    private fun loadStremioSuggestions() {
        if (stLoad != null) return
        stLoad = lifecycleScope.launch {
            val addons = runCatching { pl.iptv.tv.data.StremioStore.enabledAddons() }.getOrDefault(emptyList())
            suspend fun first(type: String) = addons.asSequence().flatMap { a -> a.boardCatalogs.filter { it.type == type }.map { a to it } }
                .firstOrNull()?.let { (a, c) -> runCatching { pl.iptv.tv.data.StremioApi.catalog(a, c) }.getOrNull() }.orEmpty().take(20)
            val m = first("movie"); val s = first("series")
            if (m != stMovies) stMovies = m
            if (s != stSeries) stSeries = s
        }
    }

    private fun metaFeature(m: pl.iptv.tv.data.StremioMetaPreview): Feature {
        val bg = if (m.id.startsWith("tt")) "https://images.metahub.space/background/medium/${m.id.substringBefore(':')}/img" else ""
        val open = { startActivity(StremioDetailActivity.intent(this, m)) }
        return Feature("sm:" + m.id, m.name.ifBlank { "Stremio" },
            listOf(if (m.type == "series") "Serial" else "Film", m.releaseInfo, m.imdbRating.takeIf { it.isNotBlank() }?.let { "IMDb $it" }.orEmpty())
                .filter { it.isNotBlank() },
            desc = m.description, images = listOf(bg, m.poster).filter { it.isNotBlank() }, playLabel = "Otwórz", play = open, info = open)
    }

    private fun metaTile(m: pl.iptv.tv.data.StremioMetaPreview) = metaFeature(m).let { f ->
        Tile("sm:" + m.id, m.name.ifBlank { "Stremio" }, m.releaseInfo.ifBlank { if (m.type == "series") "Serial" else "Film" }, m.poster, Kind.POSTER, f) { f.play() }
    }

    /** Hero zaznaczonego kafelka – z krótkim opóźnieniem (szybkie przewijanie nie wczytuje kolejnych obrazów). */
    private fun showFeature(f: Feature) {
        featureJob?.cancel()
        if (feature?.key == f.key) { feature = f; return }
        featureJob = lifecycleScope.launch { delay(FEATURE_DELAY_MS); feature = f }
    }

    /** (7.17) „Szukaj” – uniwersalna wyszukiwarka (kanały, program EPG, STube, Stremio), filtr wg bieżącej sekcji. */
    private fun search() {
        val f = when (section) { 1 -> SearchActivity.F_CHANNELS; 2 -> SearchActivity.F_STUBE; 3 -> SearchActivity.F_STREMIO; else -> SearchActivity.F_ALL }
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_FILTER, f))
    }

    private fun searchIn(stube: Boolean) {
        Dialogs.input(this, if (stube) "Szukaj w STube" else "Szukaj w Stremio", "", if (stube) "Filmy, kanały, muzyka" else "Filmy i seriale") { q ->
            val t = q.trim(); if (t.isEmpty()) return@input
            startActivity(if (stube) Intent(this, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_QUERY, t)
                else Intent(this, StremioActivity::class.java).putExtra(StremioActivity.EXTRA_QUERY, t))
        }
    }

    // ---------------- fokus ----------------

    private fun onFocused(key: String) {
        lastFocus = key; focusedNow = key
        if (pendingFocus == key) pendingFocus = null
        if (key.startsWith("r:")) rowLast[key.substringBefore('|') + "|"] = key
        if (key.startsWith("r:") || key.startsWith("h:")) lastContent = key
    }

    /** Wejście fokusu do rzędu (▲ ▼): ostatnio zaznaczony kafelek tego rzędu, inaczej najbliższy. */
    private fun Modifier.rowEntry(prefix: String) = focusProperties {
        enter = { rowLast[prefix]?.takeIf { it in attached }?.let { requester(it) } ?: FocusRequester.Default }
    }

    @Composable
    private fun AutoFocus(key: String) {
        DisposableEffect(key) { attached += key; onDispose { attached -= key } }
        val p = pendingFocus
        LaunchedEffect(p) {
            while (p == key && pendingFocus == key) {
                if (focusedNow == key) { pendingFocus = null; break }
                runCatching { requester(key).requestFocus() }; delay(120)
            }
        }
    }

    /** Element z fokusem: zapamiętanie, OK / dotknięcie, obwódka i powiększenie (tylko w warstwie – bez nowego układu). */
    @Composable
    private fun Modifier.item(key: String, radius: Dp, scaleTo: Float, focused: MutableState<Boolean>, frame: Boolean = true,
                              onFocus: () -> Unit = {}, toRail: Boolean = false, onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier {
        val c = LocalModern.current
        val scale by focusScale(focused.value, scaleTo, "ms")
        AutoFocus(key)
        return this
            .focusRequester(requester(key))
            // ◄ z pierwszej kolumny (przyciski hero, pierwszy kafelek rzędu) – zawsze pasek ikon (bieżąca sekcja), nie element wyżej
            .then(if (toRail) Modifier.focusProperties { left = requester("nav:s$section") } else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused.value) { focused.value = it.isFocused; if (it.isFocused) { onFocused(key); onFocus() } else if (focusedNow == key) focusedNow = null }
            }
            .x25Clickable(onClick, onLong)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (frame) Modifier.modernFrame(focused.value, radius, c.ring, halo = false) else Modifier)
    }

    // ================= UI =================

    /** Wymiary wg ekranu: TV – pełne, niski ekran (telefon w poziomie) – mniejsze kafelki i hero, wąski – mniejsze marginesy. */
    /**
     * (7.6) [portrait] – telefon w pionie: węższy pasek, kafelki z szerokości ekranu ([availW]) – 2 szerokie / 3 plakaty
     * na ekran (dawniej stałe 236 dp – półtora kafelka i przycinanie), krótsze przyciski hero.
     */
    @androidx.compose.runtime.Immutable
    data class Dims(val compact: Boolean, val narrow: Boolean, val portrait: Boolean = false, val availW: Dp = 0.dp) {
        // (7.17) pasek „Obsidian Stream”: TV 72 → 240 dp, pigułki 48 dp; telefon – węższy
        val rail get() = if (compact || portrait) 56.dp else pl.iptv.tv.ui.theme.AppNavRail.COLLAPSED
        val railOpen get() = if (compact || portrait) 200.dp else pl.iptv.tv.ui.theme.AppNavRail.EXPANDED
        val railItem get() = if (compact) 34.dp else if (portrait) 40.dp else pl.iptv.tv.ui.theme.AppNavRail.ITEM_H
        val railPad get() = if (compact || portrait) 8.dp else pl.iptv.tv.ui.theme.AppNavRail.PAD
        val side get() = if (narrow) 10.dp else if (compact) 16.dp else 28.dp
        val heroH get() = if (compact) 150.dp else if (portrait) 210.dp else 236.dp
        val heroTitle get() = if (compact) 22.sp else if (portrait) 26.sp else 34.sp
        val wideW get() = if (portrait) ((availW - gap) / 2).coerceIn(120.dp, 236.dp) else if (compact) 160.dp else 236.dp
        val wideH get() = wideW * 9f / 16f
        val posterW get() = if (portrait) ((availW - gap * 2) / 3).coerceIn(84.dp, 132.dp) else if (compact) 92.dp else 132.dp
        val posterH get() = posterW * 1.5f
        val gap get() = if (compact) 12.dp else if (portrait) 12.dp else 18.dp
    }
    private var dims = Dims(compact = false, narrow = false)

    @Composable
    private fun Home() {
        val c = LocalModern.current
        val cfg = LocalConfiguration.current
        val portrait = cfg.screenWidthDp < 600 && cfg.screenHeightDp > cfg.screenWidthDp
        // dostępna szerokość rzędów: ekran − pasek − marginesy − odstęp z lewej rzędu (10) i z prawej (≈ 16)
        dims = Dims(compact = cfg.screenHeightDp < 520, narrow = cfg.screenWidthDp < 600, portrait = portrait,
            availW = (cfg.screenWidthDp - 56 - 10 - 10 - 16).dp)
        // (7.17) modyfikator tła zapamiętany – bitmapa gradientów powstaje raz (nie przy każdej rekompozycji ekranu)
        val backdrop = remember(c.bg, c.accent) { Modifier.modernBackdrop(c.bg, c.accent) }
        Box(Modifier.fillMaxSize().then(backdrop)
            .focusProperties { enter = { ((pendingFocus ?: lastFocus)?.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup()) {
            HeroBackdrop()
            Column(Modifier.fillMaxSize().padding(start = dims.rail + dims.side)
                .focusProperties { enter = { (lastContent?.takeIf { it in attached } ?: HERO_PLAY.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default } }
                .focusGroup()) {
                Header()
                HeroInfo()
                Shelves()
            }
            // (7.17) rozwinięty pasek: treść pod nim przyciemniona (podpisy menu nie nachodzą na tytuł hero)
            pl.iptv.tv.ui.theme.AppNavigationScrim(railOpen, lowPerf = c.lowPerf)
            Rail()
            if (options) Options()
        }
        // (7.6) zmiana sekcji: rzędy i opis hero wchodzą przenikaniem z lekkim przesunięciem (tylko w warstwie – bez przebudowy)
        LaunchedEffect(section) {
            if (lowPerfNow) { animSection = section; return@LaunchedEffect }
            animSection = section
        }
        // zapasowo: element docelowy zniknął (np. rząd po odświeżeniu) – „Odtwórz” w hero
        LaunchedEffect(pendingFocus) {
            val k = pendingFocus ?: return@LaunchedEffect
            delay(1500)
            if (pendingFocus == k) { pendingFocus = null; runCatching { requester(HERO_PLAY).requestFocus() } }
        }
    }

    // ---------------- tło hero ----------------

    @Composable
    private fun BoxScope.HeroBackdrop() {
        val c = LocalModern.current
        val f = feature
        // (7.17) „Obsidian Stream”: hero na pełną szerokość, winieta od lewej (95 % → 0 %) i od dołu, poświata w kolorze materiału
        val glow = remember(f?.key) { f?.let { ambientColor(it, c.accent) } ?: c.accent }
        // (7.17) warstwy zapamiętane po kolorze – bitmapy tylko przy zmianie materiału (poświata) / motywu (winieta)
        val glowLayer = remember(glow) { Modifier.obsidianAmbientGlow(glow) }
        val vignette = remember(c.bg) { Modifier.obsidianHeroVignette(c.bg) }
        Box(Modifier.fillMaxSize().then(glowLayer))
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth().fillMaxHeight(0.82f).then(vignette)) {
            // przenikanie tylko przy zmianie materiału (klucz), nie przy odświeżeniu danych tego samego materiału
            val byKey = remember { HashMap<String, Feature>() }
            if (f != null) { if (byKey.size > 16) byKey.clear(); byKey[f.key] = f }
            Crossfade(f?.key, animationSpec = tween(if (c.lowPerf) 0 else 300), label = "hero") { k -> byKey[k]?.let { HeroImage(it) } }
        }
    }

    /** Kolor poświaty ambient: kanał – kolor jego gradientu, film / tytuł – stały kolor z klucza (bez czytania pikseli obrazu). */
    private fun ambientColor(f: Feature, accent: Color): Color =
        if (f.logo) channelGradient(f.meta.firstOrNull() ?: f.title).first()
        else if (f.key.startsWith("yt:")) androidx.compose.ui.graphics.lerp(accent, Color(0xFFEF4444), 0.35f)
        else androidx.compose.ui.graphics.lerp(accent, channelGradient(f.key).first(), 0.5f)

    @Composable
    private fun HeroImage(f: Feature) {
        val c = LocalModern.current
        val ctx = LocalContext.current
        when {
            f.logo -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(channelGradient(f.meta.firstOrNull() ?: f.title)))) {
                if (f.images.isNotEmpty()) TileImage(f.images.first(), "", Modifier.align(Alignment.CenterEnd).padding(end = 96.dp).fillMaxWidth(0.3f).fillMaxHeight(0.42f), ContentScale.Fit,
                    ModernType.hero, sizePx = Ui.dp(320))
            }
            f.images.isEmpty() -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.55f), c.bg))))
            else -> {
                var idx by remember(f.key) { mutableIntStateOf(0) }
                val url = f.images.getOrNull(idx)
                if (url == null) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.45f), c.bg))))
                else {
                    // (7.17) hero na pełną szerokość: dekodowanie w 60 % szerokości ekranu (obraz pod winietą – ostrość wystarcza, pamięć mała)
                    val w = (resources.displayMetrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)
                    val req = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).crossfade(false).precision(coil.size.Precision.INEXACT)
                        .memoryCacheKey("$url@hero").size(w, (w * 9 / 16).coerceAtLeast(1)).build() }
                    coil.compose.AsyncImage(req, null, Modifier.fillMaxSize().clipToBounds(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                        onError = { idx++ })
                }
            }
        }
    }

    // ---------------- nagłówek, hero ----------------

    @Composable
    private fun Header() {
        val c = LocalModern.current
        Row(Modifier.fillMaxWidth().padding(top = if (dims.compact) 10.dp else 22.dp, end = dims.side + 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(NAV[section].label.uppercase(), Modifier.weight(1f), style = ModernType.section.copy(fontSize = if (dims.compact) 16.sp else 20.sp))
            BasicText(clock, style = ModernType.section.copy(fontWeight = FontWeight.Medium, color = c.text.copy(alpha = 0.9f), fontSize = if (dims.compact) 15.sp else 19.sp))
        }
    }

    @Composable
    private fun HeroInfo() {
        val c = LocalModern.current
        val f = feature ?: remember(section, recent, videos, titles) { defaultFeature() }
        Column(Modifier.fillMaxWidth(if (dims.narrow) 1f else 0.56f).height(dims.heroH).sectionFade().padding(top = if (dims.compact) 4.dp else 14.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
          // (7.14) treść od góry w stałych miejscach (rezerwa na plakietkę), przyciski zawsze przy dolnej krawędzi – zmiana kategorii
          // nie przesuwa tytułu ani przycisków, zmienia się tylko to, co pod tytułem
          Column(Modifier.weight(1f).clipToBounds()) {
            if (!dims.compact) Box(Modifier.height(28.dp), contentAlignment = Alignment.TopStart) {
                if (f.badge.isNotEmpty()) BasicText(f.badge, Modifier.clip(RoundedCornerShape(3.dp))
                    .background(if (f.badge == "NA ŻYWO") c.live else c.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                    style = ModernType.small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp))
            }
            BasicText(f.title, style = ModernType.hero.copy(fontSize = dims.heroTitle), maxLines = if (dims.compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                f.meta.firstOrNull()?.let { BasicText(it, style = ModernType.meta.copy(fontWeight = FontWeight.Bold), maxLines = 1) }
                if (f.time.isNotEmpty()) Row(Modifier.padding(start = 10.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.live))
                    BasicText(f.time, Modifier.padding(start = 5.dp), style = ModernType.small.copy(fontWeight = FontWeight.Bold))
                }
                f.meta.drop(1).forEach { m ->
                    Box(Modifier.padding(horizontal = 8.dp).size(4.dp).clip(CircleShape).background(c.accent))
                    BasicText(m, style = ModernType.meta.copy(color = c.text.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // opis dostaje tylko pozostałe miejsce (do 3 linii) – hero ma stałą wysokość, przyciski nigdy nie nachodzą na rzędy
            // (7.17) tylko linie, które mieszczą się w całości (wielokropek na ostatniej); gdy nie ma miejsca nawet na jedną
            // (dwuwierszowy tytuł) – opis ukryty, zamiast linii uciętej w połowie liter
            if (!dims.compact) {
                var fit by remember(f.key, f.desc) { mutableIntStateOf(3) }
                BasicText(f.desc, Modifier.padding(top = 10.dp).weight(1f, fill = false).clipToBounds()
                    .graphicsLayer { alpha = if (fit == 0) 0f else 1f },
                    style = ModernType.body.copy(color = c.text.copy(alpha = 0.88f)), maxLines = fit.coerceAtLeast(1), overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r ->
                        var n = 0
                        while (n < r.lineCount && r.getLineBottom(n) <= r.size.height + 0.5f) n++
                        if (n < fit) fit = n
                    })
            }
            if (f.fraction >= 0f) Box(Modifier.padding(top = 8.dp).width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(f.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
          }
            // przyciski nigdy nie są ściskane (pełny „Program TV”): wiersz może być szerszy od kolumny, a odstępy węższe na wąskim ekranie
            Row(Modifier.padding(top = if (dims.compact) 8.dp else 14.dp).wrapContentWidth(Alignment.Start, unbounded = true)
                .focusProperties { up = FocusRequester.Cancel },
                horizontalArrangement = Arrangement.spacedBy(if (dims.narrow) 8.dp else 12.dp)) {
                PillButton(HERO_PLAY, f.playLabel, primary = true, icon = "play", toRail = true) { f.play() }
                PillButton(HERO_INFO, if (dims.narrow) "Więcej" else "Więcej informacji", primary = false, icon = ModernIcons.INFO) { f.info() }
                f.guide?.let { g -> PillButton(HERO_GUIDE, if (dims.narrow) "Program" else "Program TV", primary = false, icon = X25Action.GUIDE) { g() } }
            }
        }
    }

    /**
     * (7.17) Pigułka CTA „Obsidian Stream”: główna – akcent, pozostałe – szkło ze specular; zaznaczona – biała z ciemnym
     * tekstem i ikoną, obwódka w kolorze akcentu i poświata (`obsidianGlassCard`), skala fokusu na warstwie GPU.
     */
    @Composable
    private fun PillButton(key: String, label: String, primary: Boolean, icon: String, toRail: Boolean = false, onClick: () -> Unit) {
        val c = LocalModern.current
        val focused = remember { mutableStateOf(false) }
        val h = if (dims.compact) 32.dp else 44.dp
        val ink = if (focused.value) pl.iptv.tv.ui.theme.ObsidianTokens.BackgroundPrimary else Color.White
        Row(Modifier.height(h).zIndex(if (focused.value) 1f else 0f).item(key, h / 2, 1.06f, focused, frame = false, toRail = toRail, onClick = onClick)
            .obsidianGlassCard(focused.value, h / 2, c.accent)
            .clip(RoundedCornerShape(h / 2))
            .background(when { focused.value -> Color.White; primary -> c.accent; else -> Color.Transparent })
            .padding(horizontal = if (label.isEmpty()) 12.dp else if (dims.compact || dims.narrow) 12.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(if (dims.compact) 12.dp else 16.dp)) { if (icon == "play") ModernIcons.play(this, ink) else ModernIcons.draw(this, icon, ink) }
            // pełny napis: bez zawijania i przycinania (kapsuła rośnie do tekstu)
            if (label.isNotEmpty()) BasicText(label.uppercase(), Modifier.padding(start = 10.dp), style = ModernType.button.copy(color = ink, fontSize = if (dims.compact) 12.sp else 15.sp),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
        }
    }

    private fun lighten(c: Color) = androidx.compose.ui.graphics.lerp(c, Color.White, 0.18f)

    // ---------------- rzędy ----------------

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun Shelves() {
        // (7.17) rzędy kanałów przebudowywane tylko przy zmianie programu (sekcje z kanałami); postęp – lokalnie w kafelku
        val tick = if (section == 0 || section == 1) epgEpoch else 0
        val list = remember(section, recent, favs, all, videos, titles, tick, stMovies, stSeries) { shelves() }
        // (7.14) stan listy per sekcja: nowa sekcja od razu od góry (bez klatki ze starym przewinięciem i skoku)
        val state = remember(section) { androidx.compose.foundation.lazy.LazyListState() }
        // nowa sekcja albo doładowane rzędy (np. „Kontynuuj” nad „Na skróty”), gdy fokus nie jest w rzędach – od pierwszego rzędu
        // (lista trzyma pozycję wg klucza: rząd dodany wyżej zostałby poza ekranem)
        val ids = list.map { it.id }
        LaunchedEffect(section, ids, topTick) { if (focusedNow?.startsWith("r:") != true) state.scrollToItem(0) }
        // zaznaczony rząd przesuwa się pod hero (nagłówek rzędu widoczny) – jak w serwisach VOD
        val spec = remember { ShelfScroll(Ui.dp(if (dims.compact) 28 else 36).toFloat(), Ui.dp(if (dims.compact) 40 else 50).toFloat()) }
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
            LazyColumn(Modifier.fillMaxSize().sectionFade(), state = state,
                contentPadding = PaddingValues(bottom = 80.dp)) {
                items(list, key = { "$section/${it.id}" }, contentType = { "shelf" }) { s -> ShelfRow(s) }
            }
        }
    }

    @Composable
    private fun ShelfRow(s: Shelf) {
        val c = LocalModern.current
        Column(Modifier.fillMaxWidth().raiseOnFocus().padding(top = if (dims.compact) 6.dp else 10.dp)) {
            Row(Modifier.padding(start = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.live))
                BasicText(s.title.uppercase(), Modifier.padding(start = 8.dp), style = ModernType.row.copy(fontSize = if (dims.compact) 12.sp else 14.sp))
            }
            SmoothScrolling {
                LazyRow(Modifier.fillMaxWidth().rowEntry("r:${s.id}|").focusGroup(),
                    contentPadding = PaddingValues(start = 20.dp, end = 48.dp, top = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(dims.gap)) {
                    itemsIndexed(s.tiles, key = { _, t -> t.key }, contentType = { _, t -> if (t.kind == Kind.POSTER) "poster" else t.kind.name }) { i, t -> Card(s.id, t, first = i == 0) }
                }
            }
        }
    }

    @Composable
    private fun Card(shelf: String, t: Tile, first: Boolean) {
        val c = LocalModern.current
        val key = "r:$shelf|${t.key}"
        val focused = remember { mutableStateOf(false) }
        val (w, h) = when (t.kind) { Kind.POSTER -> dims.posterW to dims.posterH; else -> dims.wideW to dims.wideH }
        // (7.17) „Obsidian Stream”: karty 16 dp; kanały – szklana karta (obsidianGlassCard) z logo na środku i paskiem 3 dp
        val r = 16.dp
        val glass = t.kind == Kind.CHANNEL
        Column(Modifier.width(w).zIndex(if (focused.value) 1f else 0f)) {
            Box(Modifier.size(w, h).item(key, r, 1.06f, focused, frame = !glass, onFocus = { showFeature(t.feature) }, toRail = first, onClick = t.run)
                .then(if (glass) Modifier.obsidianGlassCard(focused.value, r, c.accent) else Modifier.modernTileSheen(r))
                .clip(RoundedCornerShape(r)).then(if (glass) Modifier else Modifier.background(c.panel))) {
                when (t.kind) {
                    Kind.CHANNEL -> {
                        // logo nadawcy wyśrodkowane na szkle (inicjały, gdy logo brak)
                        TileImage(t.image, t.sub.takeIf { t.fraction >= 0f } ?: t.title, Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = w * 0.22f, vertical = h * 0.2f),
                            ContentScale.Fit, ModernType.tile.copy(fontSize = if (dims.compact) 15.sp else 20.sp), sizePx = Ui.dp(w.value.toInt()))
                    }
                    Kind.ACTION -> {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lighten(c.panel), c.panel))))
                        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Box(Modifier.size(if (dims.compact) 26.dp else 34.dp).clip(RoundedCornerShape(8.dp)).background(c.accent).padding(7.dp)) {
                                Canvas(Modifier.fillMaxSize()) { ModernIcons.draw(this, t.icon, Color.White) }
                            }
                            BasicText(t.title, style = ModernType.tile.copy(fontSize = if (dims.compact) 14.sp else 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> TileImage(t.image, initialsOf(t.title), Modifier.fillMaxSize(), ContentScale.Crop, ModernType.tile.copy(color = c.text2),
                        sizePx = Ui.dp(w.value.toInt()), heightPx = Ui.dp(h.value.toInt()))
                }
                // pasek postępu: programu (kanały – czerwony) albo oglądania (STube / Stremio – niebieski)
                if (t.fraction >= 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (glass) 3.dp else 4.dp).background(Color.Black.copy(alpha = 0.45f))) {
                    // (7.17) program kanału: postęp z zegara EPG przy rysowaniu – bez przebudowy rzędów co minutę
                    if (t.kind == Kind.CHANNEL && t.progEnd > t.progStart) EpgProgressFill(t.progStart, t.progEnd, c.live, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxWidth(t.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(if (t.kind == Kind.CHANNEL) c.live else c.accent))
                }
            }
            if (t.kind != Kind.ACTION) {
                BasicText(t.title, Modifier.padding(top = 8.dp), style = ModernType.card.copy(color = if (focused.value) Color.White else c.text.copy(alpha = 0.86f),
                    fontSize = if (dims.compact) 11.sp else 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(t.sub, style = ModernType.small.copy(color = c.text2, fontSize = if (dims.compact) 10.sp else 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else BasicText(t.sub, Modifier.padding(top = 8.dp), style = ModernType.small.copy(color = c.text2, fontSize = if (dims.compact) 10.sp else 11.sp),
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    // ---------------- pasek ikon ----------------

    /**
     * (7.17) Wspólny pasek „Obsidian Stream” ([pl.iptv.tv.ui.theme.AppNavigationRail]): 72 dp → 240 dp (220 ms, FastOutSlowIn)
     * nad treścią; pod nim scrim ([pl.iptv.tv.ui.theme.AppNavigationScrim] w [Home]) – podpisy nie zlewają się z hero.
     */
    @Composable
    private fun Rail() {
        pl.iptv.tv.ui.theme.AppNavigationRail(railOpen,
            Modifier
            // (7.7) kursor opuścił pasek – zaplanowany podgląd sekcji odwołany (nie podmienia rzędów pod kursorem)
            .onFocusChanged { if (railOpen != it.hasFocus) railOpen = it.hasFocus; if (!it.hasFocus) previewJob?.cancel() }
            .focusProperties { enter = { "nav:s$section".takeIf { it in attached }?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup(),
            collapsedWidth = dims.rail, expandedWidth = dims.railOpen, lowPerf = LocalModern.current.lowPerf,
            padding = dims.railPad, spacing = if (dims.compact) 2.dp else 4.dp) {
            RailItem("nav:profile", X25Icons.PROFILE, "Profil i opcje", avatar = true) { openOptions() }
            RailItem("nav:search", X25Icons.SEARCH, "Szukaj") { search() }
            Spacer(Modifier.height(if (dims.compact) 4.dp else 10.dp))
            // (7.5) zaznaczenie sekcji – podgląd jej rzędów; OK / dotknięcie – od razu ta zakładka (Domek – kursor na „Ostatnio oglądane”)
            NAV.forEachIndexed { i, n -> RailItem("nav:s$i", n.icon, n.label, selected = section == i, onFocus = { schedulePreview(i) }) { openSection(i) } }
            Spacer(Modifier.weight(1f))
            RailItem("nav:settings", X25Action.SETTINGS, "Ustawienia") { X25Actions.run(this@ModernHomeActivity, X25Action(X25Action.SETTINGS)) }
            // launcher: ekran główny się nie zamyka – bez „Wyjścia”
            if (!X25Theme.launcherHome) RailItem("nav:exit", ModernIcons.EXIT, "Wyjście") { finishAffinity() }
        }
    }

    @Composable
    private fun RailItem(key: String, icon: String, label: String, selected: Boolean = false, avatar: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
        val c = LocalModern.current
        val focused = remember { mutableStateOf(false) }
        val h = dims.railItem
        pl.iptv.tv.ui.theme.AppNavigationRailItem(label, expanded = railOpen, focused = focused.value, selected = selected,
            focusModifier = Modifier.item(key, h / 2, 1f, focused, frame = false, onFocus = onFocus, onClick = onClick),
            accent = c.accent, live = c.live, itemHeight = h, iconSize = if (dims.compact) h * 0.56f else 24.dp,
            railWidth = dims.rail, railPadding = dims.railPad, avatar = avatar) { tint -> ModernIcons.draw(this, icon, tint) }
    }

    // ---------------- panel opcji (nakładka z prawej) ----------------

    @Composable
    private fun Options() {
        val c = LocalModern.current
        val first = remember { FocusRequester() }
        val opts = remember(optionsVersion) { optionItems() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { closeOptions() } }) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (dims.narrow) 0.9f else 0.38f)
                .background(Brush.horizontalGradient(listOf(c.bg.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.97f))))
                .focusGroup().focusProperties { exit = { FocusRequester.Cancel } }) {
                Row(Modifier.padding(start = 26.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c.accent, c.live))).padding(9.dp)) {
                        Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, X25Icons.PROFILE, Color.White) }
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        BasicText("Profil i opcje", style = ModernType.section)
                        BasicText("D-IPTV Player", style = ModernType.small.copy(color = c.text2))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(opts, key = { it.label }, contentType = { "option" }) { o -> OptionRow(o, if (o === opts.first()) first else null) }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }

    @Composable
    private fun OptionRow(o: ModernOption, req: FocusRequester?) {
        val c = LocalModern.current
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(if (o.sub.isEmpty()) 50.dp else 60.dp)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { if (it.isFocused != focused) focused = it.isFocused }
            .x25Clickable(o.action)
            .focusable()
            .clip(RoundedCornerShape(30.dp))
            .background(if (focused) c.accent else Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(o.label, style = ModernType.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (o.sub.isNotEmpty()) BasicText(o.sub, style = ModernType.small.copy(color = if (focused) Color.White.copy(alpha = 0.85f) else c.text2),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sw = o.switch
            if (sw != null) Canvas(Modifier.padding(start = 12.dp).size(44.dp, 24.dp)) { ps5Switch(sw, if (focused) Color.White.copy(alpha = 0.45f) else c.accent) }
        }
    }

    companion object {
        const val HERO_PLAY = "h:play"
        const val HERO_INFO = "h:info"
        const val HERO_GUIDE = "h:guide"
        /** Opóźnienie zmiany hero po zaznaczeniu kafelka (szybkie przewijanie nie wczytuje obrazów po drodze). */
        const val FEATURE_DELAY_MS = 300L
        /** (7.6) Przejście między sekcjami ekranu głównego. */
        const val SECTION_ANIM_MS = 180
        /** (7.7) Podgląd sekcji po zatrzymaniu kursora na pasku. */
        const val PREVIEW_DELAY_MS = 220L
    }
}

/**
 * Przewijanie rzędów ekranu głównego: zaznaczony rząd zawsze w całości widoczny (z nagłówkiem [header]) –
 * kolejne rzędy „wjeżdżają” pod hero jak w serwisach VOD, łagodną animacją.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class ShelfScroll(private val header: Float, private val labelBelow: Float = 0f) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = SCROLL_STIFFNESS)      // (7.3) jak [SmoothScroll]

    /**
     * Rząd w pełni widoczny razem z nagłówkiem: wychodzi u góry – przewinięcie, aż nagłówek będzie widoczny; wychodzi u dołu –
     * aż cały rząd się zmieści. Zawsze osiągalne (bez żądania przewinięcia poza koniec listy – inaczej pętla animacji).
     */
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val bottom = header / 3 + labelBelow        // podpisy pod kafelkami (tytuł + opis) też mają być widoczne
        return when {
            offset < header -> offset - header
            offset + size > containerSize - bottom -> minOf(offset + size - containerSize + bottom, offset - header)
            else -> 0f
        }
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as NativeKey
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.DashTile
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeMixes
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Action
import pl.iptv.tv.data.X25Layout
import pl.iptv.tv.data.X25Store
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens

/** Ostatnio oglądany materiał na pulpicie: kanał TV, film STube albo tytuł Stremio. */
@androidx.compose.runtime.Immutable
sealed class X25Recent {
    abstract val key: String
    data class Ch(val c: Channel) : X25Recent() { override val key get() = "ch:" + c.key }
    data class Vid(val p: WatchProgress) : X25Recent() { override val key get() = "yt:" + p.videoId }
    data class St(val p: StremioProgress) : X25Recent() { override val key get() = "st:" + p.metaId }

    /** Sekcja „Ostatnio …” – każde źródło osobno. */
    @androidx.compose.runtime.Immutable
    data class Group(val id: String, val title: String, val items: List<X25Recent>)

    companion object {
        const val MAX = 6
        /** Sekcje w stałej kolejności (Live TV, STube, Stremio); puste pomijane. */
        fun groups(ch: List<Channel>, vid: List<WatchProgress>, st: List<StremioProgress>): List<Group> = listOfNotNull(
            Group("tv", "Live TV", ch.take(MAX).map { Ch(it) }),
            Group("yt", "STube", vid.take(MAX).map { Vid(it) }),
            Group("st", "Stremio", st.take(MAX).map { St(it) }),
        ).filter { it.items.isNotEmpty() }
    }
}

/**
 * Pulpit motywu X25 (Jetpack Compose): u góry pasek (szybkie ikony, sieć i zegar), środek – nazwa zaznaczonego kafelka,
 * niżej rząd ostatnio oglądanych w osobnych sekcjach (Live TV, STube, Stremio) i rząd kafelków funkcji.
 *
 * Kafelki funkcji są w pełni edytowalne ([X25Layout], zapis w Room – [X25Store]): przytrzymanie OK albo MENU otwiera panel kafelka
 * (przenieś ◄ ►, zmień funkcję, nazwa, przypnij, rozmiar, ukryj, usuń), MENU poza kafelkiem – opcje pulpitu.
 * Ekran stoi nad listą kanałów ([MainActivity]); „Live TV” wraca do niej, WSTECZ na liście kanałów – z powrotem na pulpit.
 */
@OptIn(ExperimentalComposeUiApi::class)
class X25DashboardActivity : BaseActivity() {
    override val videoBackground = false

    private var tiles by mutableStateOf<List<DashTile>>(emptyList())
    private var loaded by mutableStateOf(false)
    private var recents by mutableStateOf<List<X25Recent.Group>>(emptyList())
    private var editingId by mutableStateOf<String?>(null)
    private val flyout = mutableStateListOf<FlyPage>()
    private var hero by mutableStateOf("" to "")
    private var clock by mutableStateOf("")
    private var net by mutableStateOf(X25Icons.OFFLINE)
    /** Element, na który wraca fokus po zamknięciu panelu / zmianie układu (klucz z [requester]). */
    private var pendingFocus by mutableStateOf<String?>(null)
    private var lastFocus: String? = null
    /** Pierwszy fokus na kafelku funkcji (nie na ikonie paska, którą Compose zaznacza sam przy starcie). */
    private var initialFocusDone = false
    private val requesters = HashMap<String, FocusRequester>()
    /** Elementy aktualnie skomponowane (ich [FocusRequester] jest podpięty). */
    private val attached = HashSet<String>()
    private var repoVersion = -1

    // ---- dostęp dla testów ----
    val tilesNow get() = tiles
    val editing get() = editingId
    val flyoutTitles get() = flyout.map { it.title }
    val flyoutItems get() = flyout.lastOrNull()?.items?.map { it.label }.orEmpty()
    val flyoutIcons get() = flyout.lastOrNull()?.items?.map { it.icon }.orEmpty()
    val recentKeys get() = recents.flatMap { g -> g.items.map { it.key } }
    val recentSections get() = recents.map { it.title }
    val focusedKey get() = lastFocus
    /** Testy: pozycja otwartego panelu (jak OK na niej). */
    fun clickFlyItem(label: String) { flyout.lastOrNull()?.items?.firstOrNull { it.label == label }?.action?.invoke() }

    private fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = onBack() })
        setContent { X25ThemeProvider { SmoothScrolling { Dashboard() } } }
        lifecycleScope.launch {
            if (!StubeHistory.cacheLoaded) runCatching { StubeHistory.loadCache() }
            tiles = X25Store.load(); loaded = true
            if (!initialFocusDone) { initialFocusDone = true; X25Layout.visible(tiles).firstOrNull()?.let { pendingFocus = "t:" + it.id } }
            refresh(force = true)
        }
        lifecycleScope.launch {
            // zegar, sieć, ostatnio oglądane (lista kanałów wczytuje się w tle – rząd uzupełnia się sam)
            repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { tick(); delay(2_000) } }
        }
    }

    /** Wyłączony motyw / pulpit: ekran ustępuje liście kanałów; inny motyw X25 (kolory) – przebudowa. */
    override fun onThemeEpochChanged() { if (X25Theme.ownDashboard) super.onThemeEpochChanged() else finish() }

    override fun onResume() {
        super.onResume()
        if (!X25Theme.ownDashboard) { if (!isFinishing) finish(); return }
        if (loaded) refresh(force = true)
    }

    private var tickN = 0
    private fun tick() {
        clock = Fmt.time(System.currentTimeMillis())
        if (tickN++ % 8 == 0) net = networkIcon()
        if (loaded && Repository.version != repoVersion) refresh()
    }

    private fun refresh(force: Boolean = false) {
        if (!force && Repository.version == repoVersion) return
        repoVersion = Repository.version
        lifecycleScope.launch {
            val vids = runCatching { StubeHistory.watched(X25Recent.MAX) }.getOrDefault(emptyList())
            val st = runCatching { StremioStore.progress(X25Recent.MAX) }.getOrDefault(emptyList())
            val g = X25Recent.groups(Repository.recent(X25Recent.MAX), vids, st)
            // bez zmian – bez rekompozycji rzędu (powrót z odtwarzacza nie przebudowuje kafelków)
            if (g != recents) recents = g
            if (hero.first.isEmpty()) tiles.firstOrNull { !it.hidden }?.let { hero = it.action.title to X25Actions.subtitle(it.action) }
        }
    }

    @Suppress("DEPRECATION")
    private fun networkIcon(): String = runCatching {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return X25Icons.OFFLINE
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> X25Icons.ETHERNET
                else -> X25Icons.WIFI
            }
        } else when (cm.activeNetworkInfo?.type) {
            null -> X25Icons.OFFLINE; android.net.ConnectivityManager.TYPE_ETHERNET -> X25Icons.ETHERNET; else -> X25Icons.WIFI
        }
    }.getOrDefault(X25Icons.OFFLINE)

    // ---------------- WSTECZ / MENU ----------------

    private fun onBack() {
        when {
            flyout.isNotEmpty() -> { flyout.removeAt(flyout.lastIndex); if (flyout.isEmpty()) pendingFocus = lastFocus }
            editingId != null -> stopEditing()
            SystemTools.isLauncherEnabled(this) && SystemTools.isDefaultHome(this) -> Dialogs.menu(this, "Menu", listOf(
                "▦ Aplikacje" to { SystemTools.showApps(this) },
                "⚙ Ustawienia systemu" to { SystemTools.openSystemSettings(this) },
                "⚙ Ustawienia aplikacji" to { startActivity(Intent(this, SettingsActivity::class.java)) },
            ))
            else -> finishAffinity()
        }
    }

    override val navRepeatMs get() = COMPOSE_NAV_REPEAT_MS

    override fun dispatchKeyEvent(event: NativeKey): Boolean {
        // MENU poza kafelkiem funkcji (pasek, ostatnio oglądane) – opcje pulpitu
        if (event.keyCode == NativeKey.KEYCODE_MENU && flyout.isEmpty() && lastFocus?.startsWith("t:") != true) {
            if (event.action == NativeKey.ACTION_UP) dashboardMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- zmiany układu ----------------

    private fun update(l: List<DashTile>, focus: String? = null) {
        tiles = X25Layout.normalize(l)
        lifecycleScope.launch { X25Store.save(tiles) }
        focus?.let { pendingFocus = "t:$it" }
    }

    private fun startEditing(id: String) { editingId = id; pendingFocus = "t:$id" }
    private fun stopEditing() { val id = editingId; editingId = null; id?.let { pendingFocus = "t:$it" } }

    private fun moveTile(id: String, d: Int) {
        val n = X25Layout.move(tiles, id, d)
        if (n.map { it.id } != tiles.map { it.id }) { update(n); X25Theme.navSound(this) }
    }

    /** Sąsiad kafelka znikającego z ekranu (ukrycie / usunięcie) – na niego przechodzi fokus. */
    private fun neighbour(id: String): String? {
        val v = X25Layout.visible(tiles); val i = v.indexOfFirst { it.id == id }
        return (v.getOrNull(i + 1) ?: v.getOrNull(i - 1))?.id
    }

    private fun push(p: FlyPage) { flyout += p }
    private fun replaceTop(p: FlyPage) { if (flyout.isNotEmpty()) flyout[flyout.lastIndex] = p }
    private fun closeFlyout(focus: String? = lastFocus) { flyout.clear(); pendingFocus = focus }

    private fun runTile(t: DashTile) { X25Theme.selectSound(this); X25Actions.run(this, t.action) }

    // ---------------- panele (Flyout) ----------------

    private fun tileMenu(t: DashTile) {
        val visible = X25Layout.visible(tiles)
        push(FlyPage(t.action.title, buildList {
            add(FlyItem("Otwórz", X25Actions.subtitle(t.action), t.type) { closeFlyout(); runTile(t) })
            add(FlyItem("Przenieś  ◄ ►", "Zmień kolejność pilotem", X25Icons.MOVE) { flyout.clear(); startEditing(t.id) })
            add(FlyItem("Zmień funkcję…", t.action.title, X25Action.APPS) { assignRoot(t.id) })
            add(FlyItem("Zmień nazwę…", t.label.ifBlank { "domyślna" }, X25Icons.RENAME) { closeFlyout(); rename(t) })
            add(FlyItem(if (t.pinned) "Odepnij" else "Przypnij na początku", "", X25Icons.PIN) { closeFlyout(); update(X25Layout.togglePin(tiles, t.id), t.id) })
            add(FlyItem(if (t.big) "Standardowy rozmiar" else "Duży kafelek", "", X25Icons.RESIZE) { closeFlyout(); update(X25Layout.toggleBig(tiles, t.id), t.id) })
            if (visible.size > 1) {
                add(FlyItem("Ukryj kafelek", "Przywrócisz go w opcjach pulpitu", X25Icons.HIDE) { val n = neighbour(t.id); closeFlyout(); update(X25Layout.setHidden(tiles, t.id, true), n) })
                add(FlyItem("Usuń kafelek", "", X25Icons.DELETE) { val n = neighbour(t.id); closeFlyout(); update(X25Layout.remove(tiles, t.id), n) })
            }
            add(FlyItem("Opcje pulpitu…", "", X25Action.SETTINGS) { dashboardMenu() })
        }))
    }

    private fun dashboardMenu() {
        val hidden = tiles.filter { it.hidden }
        push(FlyPage("Pulpit X25", buildList {
            add(FlyItem("Tryb edycji układu", "◄ ► przesuwa zaznaczony kafelek", X25Icons.EDIT) {
                val id = lastFocus?.removePrefix("t:")?.takeIf { k -> tiles.any { it.id == k && !it.hidden } } ?: X25Layout.visible(tiles).firstOrNull()?.id
                flyout.clear(); id?.let { startEditing(it) }
            })
            add(FlyItem("Dodaj kafelek…", "Dowolna funkcja, kanał, kategoria, playlista, miks", X25Action.APPS) { assignRoot(null) })
            if (hidden.isNotEmpty()) add(FlyItem("Pokaż ukryte kafelki (${hidden.size})…", "", X25Icons.SHOW) {
                push(FlyPage("Ukryte kafelki", hidden.map { h -> FlyItem(h.action.title, "Przywróć na pulpit", h.type) {
                    closeFlyout(); update(X25Layout.setHidden(tiles, h.id, false), h.id) } }))
            })
            add(FlyItem("Przywróć układ domyślny", "", X25Icons.RESET) {
                closeFlyout(); lifecycleScope.launch { tiles = X25Store.reset(); pendingFocus = "t:" + (tiles.firstOrNull()?.id ?: "") }
            })
            add(FlyItem("Ustawienia aplikacji", "", X25Action.SETTINGS) { closeFlyout(); startActivity(Intent(this@X25DashboardActivity, SettingsActivity::class.java)) })
            add(FlyItem("Wyłącz motyw X25", "Powrót do poprzedniego wyglądu", X25Icons.POWER) { flyout.clear(); X25Theme.setEnabled(false); finish() })
        }))
    }

    /** Wybór funkcji kafelka [target] (null = nowy kafelek). */
    private fun assignRoot(target: String?) {
        fun pick(a: X25Action) {
            if (target == null) { val (l, id) = X25Layout.add(tiles, a); closeFlyout(); update(l, id) }
            else { closeFlyout(); update(X25Layout.assign(tiles, target, a), target) }
        }
        push(FlyPage(if (target == null) "Dodaj kafelek" else "Zmień funkcję", listOf(
            FlyItem("Funkcje aplikacji…", "Live TV, STube, Historia, Ustawienia…", X25Action.APPS) {
                push(FlyPage("Funkcje aplikacji", X25Action.APP_FUNCTIONS.map { f -> FlyItem(X25Action.DEFAULT_TITLES[f] ?: f, "", f) { pick(X25Action(f)) } }))
            },
            FlyItem("Kanał TV…", "Skrót do wybranego kanału", X25Action.CHANNEL) {
                val fav = Repository.favorites(); val favKeys = fav.map { it.key }.toSet()
                val all = fav + Repository.channels.filter { !it.hidden && it.key !in favKeys }
                push(FlyPage("Kanał TV", all.map { c -> FlyItem((if (c.key in favKeys) "★ " else "") + c.name, c.group, X25Action.CHANNEL) {
                    pick(X25Action(X25Action.CHANNEL, c.key, c.name)) } },
                    subtitle = if (all.isEmpty()) "Brak kanałów – wczytaj listę w Ustawieniach" else ""))
            },
            FlyItem("Kategoria STube…", "Na czasie, Muzyka, Biblioteka…", X25Action.STUBE_ROW) {
                push(FlyPage("Kategoria STube", X25Actions.STUBE_ROWS.map { (id, name) -> FlyItem(name, "", X25Action.STUBE_ROW) { pick(X25Action(X25Action.STUBE_ROW, id, name)) } }))
            },
            FlyItem("Playlista STube…", "Biblioteka i playlisty konta", X25Action.STUBE_PLAYLIST) {
                push(FlyPage("Playlista STube", emptyList(), loading = true))
                lifecycleScope.launch {
                    val local = listOf(STubeScreen.PL_LATER to "Do obejrzenia później", STubeScreen.PL_HISTORY to "Historia oglądania") +
                        (if (YouTubeTokens.loggedIn) listOf(STubeScreen.PL_LIKED to "Polubione filmy") else emptyList())
                    val mine = if (YouTubeTokens.loggedIn) runCatching { YouTubeRepository.myPlaylists(null).first }.getOrDefault(emptyList()).map { it.id to it.title } else emptyList()
                    replaceTop(FlyPage("Playlista STube", (local + mine).map { (id, t) -> FlyItem(t, "", X25Action.STUBE_PLAYLIST) { pick(X25Action(X25Action.STUBE_PLAYLIST, id, t)) } }))
                }
            },
            FlyItem("Miks…", "Gotowe miksy z historii oglądania", X25Action.MIX) {
                push(FlyPage("Miks", emptyList(), loading = true))
                lifecycleScope.launch {
                    val mixes = runCatching { StubeMixes.current() }.getOrDefault(emptyList())
                    replaceTop(FlyPage("Miks", mixes.map { m -> FlyItem(m.title, m.subtitle, X25Action.MIX) { pick(X25Action(X25Action.MIX, m.id, m.title)) } },
                        subtitle = if (mixes.isEmpty()) "Obejrzyj kilka filmów w STube – miksy powstaną z historii" else ""))
                }
            },
        )))
    }

    private fun rename(t: DashTile) = Dialogs.input(this, "Nazwa kafelka", t.label.ifBlank { t.action.title }, "Puste – nazwa domyślna") { s ->
        val name = s.trim().takeIf { it != (X25Action.DEFAULT_TITLES[t.type] ?: "") }.orEmpty()
        update(tiles.map { if (it.id == t.id) it.copy(label = name) else it }, t.id)
    }

    private fun recentMenu(r: X25Recent) {
        when (r) {
            is X25Recent.Ch -> push(FlyPage(r.c.name, listOf(
                FlyItem("Oglądaj", r.c.group, X25Action.LIVE) { closeFlyout(); X25Actions.playChannel(this, r.c) },
                FlyItem("Dodaj kafelek kanału", "Skrót w rzędzie funkcji", X25Icons.PIN) {
                    val (l, id) = X25Layout.add(tiles, X25Action(X25Action.CHANNEL, r.c.key, r.c.name)); closeFlyout(); update(l, id) },
            )))
            is X25Recent.Vid -> push(FlyPage(r.p.title.ifBlank { "Film" }, listOf(
                FlyItem("Odtwórz", r.p.channel, X25Icons.PLAY) { closeFlyout(); X25Actions.playVideo(this, r.p.toVideo()) },
                FlyItem("Historia oglądania", "", X25Action.HISTORY) { closeFlyout(); X25Actions.run(this, X25Action(X25Action.HISTORY)) },
            )))
            is X25Recent.St -> push(FlyPage(r.p.name.ifBlank { "Stremio" }, listOf(
                FlyItem("Kontynuuj", r.p.videoLabel, X25Icons.PLAY) { closeFlyout(); openRecent(r) },
                FlyItem("Stremio", "", X25Action.STREMIO) { closeFlyout(); X25Actions.run(this, X25Action(X25Action.STREMIO)) },
            )))
        }
    }

    private fun openRecent(r: X25Recent) {
        X25Theme.selectSound(this)
        when (r) {
            is X25Recent.Ch -> X25Actions.playChannel(this, r.c)
            is X25Recent.Vid -> X25Actions.playVideo(this, r.p.toVideo())
            is X25Recent.St -> startActivity(StremioPlayerActivity.resumeIntent(this, r.p))   // (7.8) od razu odtwarzanie
        }
    }

    private fun onFocused(key: String, title: String, sub: String) {
        if (lastFocus != null && lastFocus != key) X25Theme.navSound(this)
        lastFocus = key; hero = title to sub
        rowLast[key.substringBefore(':') + ":"] = key
    }

    /** Ostatnio zaznaczony element każdego rzędu (prefiks klucza „r:” / „t:”). */
    private val rowLast = HashMap<String, String>()

    /**
     * Wejście fokusu do rzędu z innego rzędu (▲ ▼): ostatnio zaznaczony element tego rzędu, jeśli jest skomponowany,
     * inaczej wybór domyślny (najbliższy element). Zastępuje `focusRestorer`, który przy wejściu z rzędu sekcji gubił fokus.
     */
    private fun Modifier.rowEntry(prefix: String) = focusProperties {
        enter = { rowLast[prefix]?.takeIf { it in attached }?.let { requester(it) } ?: FocusRequester.Default }
    }

    // ================= UI =================

    /**
     * Wymiary pulpitu wg ekranu: TV / tablet – pełne; niski ekran (telefon w poziomie) – mniejszy pasek, kafelki i nagłówek,
     * żeby sekcje ostatnich i kafelki funkcji mieściły się bez nachodzenia; wąski (telefon w pionie) – ikony paska od lewej.
     */
    /** (7.6) [portrait] – telefon w pionie: kafelki funkcji w siatce 2 kolumn na resztę ekranu (bez pustej połowy u góry). */
    @androidx.compose.runtime.Immutable
    data class Dims(val compact: Boolean, val narrow: Boolean, val portrait: Boolean = false) {
        val topH get() = if (compact) 50.dp else 66.dp
        val topIcon get() = if (compact || narrow) 36.dp else 44.dp
        val topGap get() = if (narrow) 6.dp else 10.dp
        val tileW get() = if (compact) 150.dp else 196.dp
        val tileBigW get() = if (compact) 230.dp else 300.dp
        val tileH get() = if (compact) 92.dp else 128.dp
        val tileIcon get() = if (compact) 28.dp else 36.dp
        val recent get() = if (compact) 74.dp else 104.dp
        val heroSize get() = if (compact) 24.sp else 34.sp
        val padTop get() = if (compact) 6.dp else 18.dp
        val rowPad get() = if (compact) 8.dp else 14.dp
    }
    private var dims = Dims(compact = false, narrow = false)
    /** Testy: wymiary bieżącego układu. */
    val dimsNow get() = dims

    @Composable
    private fun Dashboard() {
        val c = LocalX25.current
        val cfg = LocalConfiguration.current
        val narrow = cfg.screenWidthDp < 600
        dims = Dims(compact = cfg.screenHeightDp < 520, narrow = narrow, portrait = narrow && cfg.screenHeightDp > cfg.screenWidthDp)
        val side = if (narrow) 16.dp else 48.dp
        // wejście fokusu na ekran (okno odzyskuje fokus: start, powrót z innego ekranu) – ostatni element albo pierwszy kafelek, nie ikona paska
        Box(Modifier.fillMaxSize()
            .focusProperties { enter = { entryTarget()?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup()) {
            Column(Modifier.fillMaxSize().dashboardEnter().padding(top = dims.padTop, bottom = if (dims.compact) 6.dp else 16.dp)) {
                TopBar(side)
                if (dims.portrait) {
                    // (7.6) telefon w pionie: nagłówek pod paskiem, ostatnie, a kafelki w siatce na resztę ekranu
                    Box(Modifier.fillMaxWidth().padding(start = side, end = side, top = 18.dp, bottom = 6.dp)) { Hero(Modifier) }
                    if (recents.isNotEmpty()) RecentRow(side)
                    if (editingId != null) SectionLabel("TRYB EDYCJI · OK / WSTECZ – gotowe", side, c.ring)
                    else SectionLabel("Twoje funkcje · przytrzymaj – edycja", side)
                    FunctionGrid(side, Modifier.weight(1f))
                } else {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = side)) { Hero(Modifier.align(Alignment.BottomStart)) }
                if (recents.isNotEmpty()) RecentRow(side)
                if (editingId != null) SectionLabel("TRYB EDYCJI   ◄ ► przesuń · OK / WSTECZ – gotowe · MENU – opcje kafelka", side, c.ring)
                else SectionLabel("Twoje funkcje   ·   przytrzymaj OK – edycja kafelka", side)
                FunctionRow(side)
                }
            }
            if (flyout.isNotEmpty()) Flyout(narrow)
        }
        // pierwszy fokus i powrót fokusu po zmianach układu / zamknięciu panelu
        // zapasowo: element docelowy zniknął (np. ukryty kafelek) – pierwszy kafelek funkcji
        LaunchedEffect(pendingFocus) {
            val k = pendingFocus ?: return@LaunchedEffect
            delay(700)
            if (pendingFocus == k) { pendingFocus = null; X25Layout.visible(tiles).firstOrNull()?.let { runCatching { requester("t:" + it.id).requestFocus() } } }
        }
    }

    private fun entryTarget(): String? = (pendingFocus ?: lastFocus)?.takeIf { it in attached }
        ?: X25Layout.visible(tiles).firstOrNull()?.let { "t:" + it.id }?.takeIf { it in attached }

    /**
     * Fokus czekający na element [key]: żądanie wysyłane, gdy element jest już skomponowany i podpięty
     * (kafelki LazyRow powstają dopiero przy układzie – pierwszy fokus, kafelek po przesunięciu, powrót z panelu).
     */
    @Composable
    private fun AutoFocus(key: String) {
        androidx.compose.runtime.DisposableEffect(key) { attached += key; onDispose { attached -= key } }
        val p = pendingFocus
        LaunchedEffect(p) { if (p == key && runCatching { requester(key).requestFocus() }.isSuccess && pendingFocus == key) pendingFocus = null }
    }

    @Composable
    private fun SectionLabel(t: String, side: androidx.compose.ui.unit.Dp, color: Color = LocalX25.current.text2) =
        BasicText(t, Modifier.padding(start = side, top = 6.dp), style = X25Type.label.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)

    @Composable
    private fun Hero(m: Modifier) {
        val c = LocalX25.current
        Column(m.padding(bottom = if (dims.compact) 2.dp else 8.dp)) {
            BasicText(hero.first, style = X25Type.hero.copy(fontSize = dims.heroSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hero.second.isNotEmpty()) BasicText(hero.second, style = X25Type.title.copy(color = c.text2, fontWeight = FontWeight.Normal), maxLines = 1)
        }
    }

    // ---------------- pasek statusu ----------------

    @Composable
    private fun TopBar(side: androidx.compose.ui.unit.Dp) {
        val c = LocalX25.current
        Box(Modifier.fillMaxWidth().height(dims.topH).padding(horizontal = side)) {
            Row(Modifier.align(if (dims.narrow) Alignment.TopStart else Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(dims.topGap)) {
                TopIcon(X25Action.LIVE, "Live TV") { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.LIVE)) }
                TopIcon(X25Action.STUBE, "STube") { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.STUBE)) }
                TopIcon(X25Action.STREMIO, "Stremio") { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.STREMIO)) }
                TopIcon(X25Action.GUIDE, "Przewodnik") { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.GUIDE)) }
                TopIcon(X25Icons.SEARCH, "Szukaj") { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.EPG_SEARCH)) }
                TopIcon(X25Icons.MORE, "Pulpit") { dashboardMenu() }
                TopIcon(X25Action.SETTINGS, "Ustawienia", ring = true) { X25Actions.run(this@X25DashboardActivity, X25Action(X25Action.SETTINGS)) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (!dims.narrow) Canvas(Modifier.size(22.dp)) { X25Icons.draw(this, net, c.text2) }
                Spacer(Modifier.width(12.dp))
                BasicText(clock, style = X25Type.title.copy(fontSize = 20.sp))
            }
        }
    }

    @Composable
    private fun TopIcon(kind: String, label: String, ring: Boolean = false, onClick: () -> Unit) {
        val c = LocalX25.current
        var focused by remember { mutableStateOf(false) }
        val key = "top:$kind"
        AutoFocus(key)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(dims.topIcon)
                .focusRequester(requester(key))
                .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocused(key, label, "") } }
                .x25Clickable(onClick)
                .focusable()
                .x25Frame(focused, 22.dp, c.focus, glow = !c.lowPerf)
                .clip(CircleShape)
                .background(if (focused) c.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
                .x25Frame(ring && !focused, 22.dp, c.ring, glow = false)
                .padding(dims.topIcon / 4)) {
                Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, kind, if (focused || ring) Color.White else c.text2) }
            }
            BasicText(if (focused) label else "", Modifier.padding(top = 2.dp), style = X25Type.small.copy(color = Color.White))
        }
    }

    // ---------------- rząd: ostatnio oglądane ----------------

    /** Ostatnio oglądane: jedna linia, osobne sekcje (Live TV · STube · Stremio) z własnym nagłówkiem, oddzielone kreską. */
    @Composable
    private fun RecentRow(side: androidx.compose.ui.unit.Dp) {
        val c = LocalX25.current
        LazyRow(Modifier.fillMaxWidth().rowEntry("r:").focusGroup(), contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = dims.rowPad),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(recents, key = { _, g -> g.id }, contentType = { _, _ -> "recent" }) { i, g ->
                Row(Modifier.raiseOnFocus(), verticalAlignment = Alignment.Bottom) {
                    if (i > 0) Box(Modifier.padding(end = 14.dp, bottom = 8.dp).width(1.dp).height(dims.recent - 8.dp).background(c.text2.copy(alpha = 0.25f)))
                    Column {
                        BasicText("Ostatnio · " + g.title, Modifier.padding(bottom = if (dims.compact) 4.dp else 8.dp), style = X25Type.label.copy(color = c.text2), maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { g.items.forEach { r -> androidx.compose.runtime.key(r.key) { RecentTile(r) } } }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecentTile(r: X25Recent) {
        val c = LocalX25.current
        var focused by remember { mutableStateOf(false) }
        val scale by focusScale(focused, 1.05f, "rs")
        val key = "r:" + r.key
        AutoFocus(key)
        val (title, sub) = when (r) {
            is X25Recent.Ch -> r.c.name to "Live TV · ${r.c.group}"
            is X25Recent.Vid -> r.p.title to "STube · ${r.p.channel}"
            is X25Recent.St -> r.p.name to listOf("Stremio", r.p.videoLabel).filter { it.isNotBlank() }.joinToString(" · ")
        }
        // Stremio: plakat pionowy; kanały i filmy – kwadrat
        Box(Modifier.size(if (r is X25Recent.St) dims.recent * 0.68f else dims.recent, dims.recent)
            .zIndex(if (focused) 1f else 0f)
            .focusRequester(requester(key))
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocused(key, title, sub) } }
            .x25Clickable(onClick = { openRecent(r) }, onLong = { recentMenu(r) })
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .x25Frame(focused, 10.dp, c.focus, glow = !c.lowPerf)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(c.panel.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.85f))))) {
            when (r) {
                // logo kanału; inicjały tylko, gdy logo się nie wczyta (wcześniej zawsze pod logo – prześwitywały)
                // (7.17) logo nad nazwą kanału – kanały z tym samym logo (np. kolejne kanały sportowe) da się odróżnić
                is X25Recent.Ch -> {
                    TileImage(r.c.logo, initialsOf(r.c.name), Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 46.dp), ContentScale.Fit,
                        X25Type.hero.copy(fontSize = 26.sp, color = c.text2), sizePx = Ui.dp(dims.recent.value.toInt()))
                    BasicText(r.c.name, Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                        style = X25Type.title.copy(fontSize = 12.sp, lineHeight = 14.sp, color = c.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                is X25Recent.Vid -> {
                    if (r.p.thumb.isNotBlank()) TileImage(r.p.thumb, "", Modifier.fillMaxSize(), ContentScale.Crop, X25Type.title, sizePx = Ui.dp(dims.recent.value.toInt()))
                    else Canvas(Modifier.size(40.dp).align(Alignment.Center)) { X25Icons.draw(this, X25Icons.PLAY, c.text2) }
                    // pasek obejrzenia (jak na kafelkach STube)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(r.p.fraction.coerceIn(0f, 1f)).height(3.dp).background(c.accent))
                }
                is X25Recent.St -> {
                    TileImage(r.p.poster, r.p.name.take(1).uppercase(), Modifier.fillMaxSize(), ContentScale.Crop,
                        X25Type.hero.copy(fontSize = 26.sp, color = c.text2), sizePx = Ui.dp(dims.recent.value.toInt()))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(r.p.fraction).height(3.dp).background(c.accent))
                }
            }
        }
    }

    // ---------------- rząd: kafelki funkcji ----------------

    @Composable
    private fun FunctionRow(side: androidx.compose.ui.unit.Dp) {
        val state = rememberLazyListState()
        val visible = X25Layout.visible(tiles)
        LazyRow(Modifier.fillMaxWidth().rowEntry("t:").focusGroup(), state = state,
            contentPadding = PaddingValues(horizontal = side, vertical = dims.rowPad), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(visible, key = { _, t -> t.id }, contentType = { _, _ -> "tile" }) { _, t ->
                FnTile(t, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1))))
            }
        }
        // kafelek, na który ma przejść fokus, poza ekranem – przewinięcie (wtedy powstaje i przejmuje fokus)
        LaunchedEffect(pendingFocus) {
            val id = pendingFocus?.takeIf { it.startsWith("t:") }?.removePrefix("t:") ?: return@LaunchedEffect
            val i = visible.indexOfFirst { it.id == id }
            if (i >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == i }) state.scrollToItem(i)
        }
        // przesuwany kafelek zawsze widoczny
        LaunchedEffect(tiles, editingId) {
            val id = editingId ?: return@LaunchedEffect
            val i = visible.indexOfFirst { it.id == id }
            val vis = state.layoutInfo.visibleItemsInfo
            if (i >= 0 && (vis.isEmpty() || i <= vis.first().index || i >= vis.last().index)) state.animateScrollToItem((i - 1).coerceAtLeast(0))
        }
    }

    /** (7.6) Kafelki funkcji w pionie: siatka 2 kolumn (kafelek na całą szerokość komórki), przewijanie w pionie. */
    @Composable
    private fun FunctionGrid(side: androidx.compose.ui.unit.Dp, m: Modifier) {
        val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        val visible = X25Layout.visible(tiles)
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            m.fillMaxWidth().rowEntry("t:").focusGroup(), state = state,
            contentPadding = PaddingValues(start = side, end = side, top = dims.rowPad, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visible.size, key = { visible[it].id }, contentType = { "fn" }) { i -> FnTile(visible[i], Modifier, fill = true) }
        }
        LaunchedEffect(pendingFocus) {
            val id = pendingFocus?.takeIf { it.startsWith("t:") }?.removePrefix("t:") ?: return@LaunchedEffect
            val i = visible.indexOfFirst { it.id == id }
            if (i >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == i }) state.scrollToItem(i)
        }
    }

    @Composable
    private fun FnTile(t: DashTile, m: Modifier, fill: Boolean = false) {
        val c = LocalX25.current
        var focused by remember { mutableStateOf(false) }
        val editing = editingId == t.id
        val scale by focusScale(focused || editing, 1.045f, "fs")
        val key = "t:" + t.id
        AutoFocus(key)
        val title = t.action.title
        val sub = X25Actions.subtitle(t.action)
        val dim = editingId != null && !editing
        val base = if (t.type == X25Action.LIVE || t.big) c.accent.copy(alpha = 0.30f) else c.panel.copy(alpha = 0.88f)
        Box((if (fill) m.fillMaxWidth().height(dims.tileH) else m.size(if (t.big) dims.tileBigW else dims.tileW, dims.tileH))
            .zIndex(if (focused || editing) 1f else 0f)
            .focusRequester(requester(key))
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocused(key, title, sub) } }
            .x25Clickable(onClick = { if (editingId != null) stopEditing() else runTile(t) }, onLong = { if (editingId == null) tileMenu(t) }, onKey = { e -> tileKey(t, e) })
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (dim) 0.55f else 1f }
            .x25Frame(focused || editing, 12.dp, if (editing) c.ring else c.focus, glow = !c.lowPerf)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(if (focused) lerp(base, c.accent, 0.12f) else base, Color.Black.copy(alpha = 0.78f))))
            .padding(if (dims.compact) 10.dp else 14.dp)) {
            Canvas(Modifier.size(dims.tileIcon).align(Alignment.TopStart)) { X25Icons.draw(this, t.type, if (focused) Color.White else c.accent) }
            if (t.pinned) Canvas(Modifier.size(14.dp).align(Alignment.TopEnd)) { X25Icons.draw(this, X25Icons.PIN, c.text2) }
            if (editing) BasicText("◄  ►", Modifier.align(Alignment.TopEnd), style = X25Type.title.copy(color = c.ring))
            Column(Modifier.align(Alignment.BottomStart)) {
                BasicText(title, style = X25Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.big && sub.isNotEmpty()) BasicText(sub, style = X25Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

    /** Klawisze kafelka: MENU – panel kafelka; w trybie edycji ◄ ► przesuwa, ▲ ▼ nie opuszczają kafelka. */
    private fun tileKey(t: DashTile, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val ne = e.nativeKeyEvent
        if (ne.keyCode == NativeKey.KEYCODE_MENU) { if (ne.action == NativeKey.ACTION_UP && editingId == null) tileMenu(t); return true }
        if (editingId != t.id) return false
        return when (ne.keyCode) {
            NativeKey.KEYCODE_DPAD_LEFT, NativeKey.KEYCODE_DPAD_RIGHT -> {
                if (ne.action == NativeKey.ACTION_DOWN) moveTile(t.id, if (ne.keyCode == NativeKey.KEYCODE_DPAD_LEFT) -1 else 1); true
            }
            NativeKey.KEYCODE_DPAD_UP, NativeKey.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
    }

    // ---------------- panel boczny (Flyout / Guide) ----------------

    @Composable
    private fun Flyout(narrow: Boolean) {
        val c = LocalX25.current
        val page = flyout.last()
        val first = remember(page) { FocusRequester() }
        // przyciemnienie: dotknięcie poza panelem zamyka go (kafelki pod spodem nie reagują)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { closeFlyout() } }) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(if (narrow) Modifier.fillMaxWidth(0.88f) else Modifier.width(400.dp))
                .background(Brush.verticalGradient(listOf(lerp(c.panel, c.accent, 0.05f).copy(alpha = 0.97f), c.bg.copy(alpha = 0.98f))))
                .focusGroup().focusProperties { exit = { FocusRequester.Cancel } }) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.accent))
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)) {
                    if (flyout.size > 1) BasicText(flyout[flyout.size - 2].title + "  ›", style = X25Type.small.copy(color = c.text2), maxLines = 1)
                    BasicText(page.title, style = X25Type.hero.copy(fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (page.subtitle.isNotEmpty()) BasicText(page.subtitle, Modifier.padding(top = 4.dp), style = X25Type.label.copy(color = c.text2))
                    if (page.loading) BasicText("Wczytywanie…", Modifier.padding(top = 8.dp), style = X25Type.label.copy(color = c.text2))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(page.items, key = { i, it -> "$i:${it.label}" }, contentType = { _, _ -> "fly" }) { i, it -> FlyRow(it, if (i == 0) first else null) }
                }
                BasicText("OK – wybierz   ·   WSTECZ – " + (if (flyout.size > 1) "poprzedni panel" else "zamknij"),
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = X25Type.small.copy(color = c.text2))
            }
        }
        LaunchedEffect(page, page.items.size) {
            if (page.items.isEmpty()) return@LaunchedEffect
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    @Composable
    private fun FlyRow(item: FlyItem, req: FocusRequester?) {
        val c = LocalX25.current
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().widthIn(min = 200.dp).height(if (item.sub.isEmpty()) 50.dp else 60.dp)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) X25Theme.navSound(this@X25DashboardActivity) } }
            .x25Clickable({ X25Theme.selectSound(this); item.action() })
            .focusable()
            .x25Frame(focused, 8.dp, c.focus, glow = false)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) c.accent.copy(alpha = 0.20f) else Color.Transparent)
            .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            // miejsce na ikonę zawsze (równe wcięcie pozycji z ikoną i bez)
            Box(Modifier.size(24.dp)) { if (item.icon != null) Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, item.icon, if (focused) Color.White else c.accent) } }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(item.label, style = X25Type.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.sub.isNotEmpty()) BasicText(item.sub, style = X25Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

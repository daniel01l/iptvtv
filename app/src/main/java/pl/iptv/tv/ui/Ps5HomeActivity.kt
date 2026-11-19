package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as NativeKey
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.WatchProgress
import pl.iptv.tv.data.X25Action

/**
 * Ekran główny motywu PS5 (Jetpack Compose, [PS5ThemeProvider]).
 *
 * Układ jak na konsoli: u góry zakładki sekcji („Live TV”, „Multimedia”) i po prawej szybkie ikony (wyszukiwarka, ustawienia,
 * opcje) z zegarem; pod nimi poziomy rząd zaokrąglonych kafelków aplikacji / ostatnio oglądanych (zaznaczony – większy,
 * nazwa pod rzędem), niżej karty: ostatnio odtwarzane (duża, z podglądem), statystyki oglądania w stylu „trofeów”,
 * pamięć urządzenia i skróty. MENU / ikona „…” – panel opcji w stylu ustawień PS5 (przełączniki „switch”, podpowiedzi przycisków).
 *
 * Funkcje uruchamiane wyłącznie przez istniejące ekrany ([X25Actions]) – telewizja, STube, Stremio, odtwarzacze i Room bez zmian.
 */
@OptIn(ExperimentalComposeUiApi::class)
class Ps5HomeActivity : BaseActivity() {
    override val videoBackground = false

    /**
     * Kafelek rzędu aplikacji. [image] – logo / miniatura / plakat, [bitmap] – ikona aplikacji z urządzenia
     * (puste = ikona rysowana); [onLong] – przytrzymanie OK.
     */
    data class App(val key: String, val title: String, val sub: String, val icon: String, val image: String = "",
                   val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null, val onLong: (() -> Unit)? = null, val run: () -> Unit)

    /** Aplikacja z urządzenia dodana do ekranu głównego ([Prefs.ps5Apps]). */
    data class DeviceApp(val id: String, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap?, val intent: Intent)

    /** Pamięć: wolne / całkowite miejsce (bajty) i pamięć podręczna aplikacji. */
    data class Storage(val free: Long, val total: Long, val cache: Long) {
        val used get() = if (total > 0) 1f - free.toFloat() / total else 0f
    }

    private var tab by mutableIntStateOf(0)
    private var channels by mutableStateOf<List<Channel>>(emptyList())
    private var videos by mutableStateOf<List<WatchProgress>>(emptyList())
    private var titles by mutableStateOf<List<StremioProgress>>(emptyList())
    private var deviceApps by mutableStateOf<List<DeviceApp>>(emptyList())
    private var storage by mutableStateOf<Storage?>(null)
    private var clock by mutableStateOf("")
    private var selected by mutableStateOf("" to "")
    private var options by mutableStateOf(false)
    private var loaded = false
    private var repoVersion = -1

    /** Element, na który wraca fokus (start, zamknięcie panelu opcji). */
    private var pendingFocus by mutableStateOf<String?>(null)
    private var lastFocus: String? = null
    private val requesters = HashMap<String, FocusRequester>()
    private val attached = HashSet<String>()
    /** Ostatnio zaznaczony element każdej części ekranu (prefiks klucza). */
    private val rowLast = HashMap<String, String>()

    // ---- dostęp dla testów ----
    val focusedKey get() = lastFocus
    val tabNow get() = tab
    val selectedTitle get() = selected.first
    val appKeys get() = apps().map { it.key }
    fun appKeys(t: Int) = apps(t).map { it.key }
    val deviceAppIds get() = deviceApps.map { it.id }
    val storageNow get() = storage
    val optionsOpen get() = options
    val optionLabels get() = if (options) optionItems().map { it.label } else emptyList()
    fun clickOption(label: String) { optionItems().firstOrNull { it.label == label }?.action?.invoke() }
    val dimsNow get() = dims

    private fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = onBack() })
        setContent { PS5ThemeProvider { SmoothScrolling { Home() } } }
        pendingFocus = "a:" + apps().first().key
        lifecycleScope.launch {
            if (!StubeHistory.cacheLoaded) runCatching { StubeHistory.loadCache() }
            loaded = true
            refresh(force = true)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { tick(); delay(2_000) } } }
    }

    /** Wyłączony motyw / ekran główny: ustępuje liście kanałów; inne kolory PS5 – przebudowa. */
    override fun onThemeEpochChanged() { if (Ps5Theme.homeOn) super.onThemeEpochChanged() else finish() }

    override fun onResume() {
        super.onResume()
        if (!Ps5Theme.homeOn) { if (!isFinishing) finish(); return }
        if (loaded) refresh(force = true)
    }

    private fun tick() {
        clock = Fmt.time(System.currentTimeMillis())
        if (loaded && Repository.version != repoVersion) refresh()
    }

    private fun refresh(force: Boolean = false) {
        if (!force && Repository.version == repoVersion) return
        repoVersion = Repository.version
        lifecycleScope.launch {
            val ch = Repository.recent(X25Recent.MAX)
            val vids = runCatching { StubeHistory.watched(50) }.getOrDefault(emptyList())
            val st = runCatching { StremioStore.progress(50) }.getOrDefault(emptyList())
            if (ch != channels) channels = ch
            if (vids.take(X25Recent.MAX) != videos) videos = vids.take(X25Recent.MAX)
            if (st.take(X25Recent.MAX) != titles) titles = st.take(X25Recent.MAX)
            loadDeviceApps()
            storage = withContext(Dispatchers.IO) { readStorage() }
        }
    }

    private fun readStorage(): Storage? = runCatching {
        val fs = android.os.StatFs(filesDir.path)
        fun size(f: java.io.File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0L else f.length()
        Storage(fs.availableBytes, fs.totalBytes, size(cacheDir))
    }.getOrNull()

    // ---------------- aplikacje z urządzenia ----------------

    /** Zapisane aplikacje (pomija odinstalowane) z ikoną jako bitmapa – wczytywane w tle. */
    private suspend fun loadDeviceApps() {
        val ids = Prefs.ps5Apps.filter { !isSystemTv(it) }
        val list = withContext(Dispatchers.IO) {
            val pm = packageManager; val px = Ui.dp(96)
            ids.mapNotNull { id ->
                val cn = android.content.ComponentName.unflattenFromString(id) ?: return@mapNotNull null
                val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull() ?: return@mapNotNull null
                val icon = runCatching { info.loadIcon(pm).toBitmap(px, px).asImageBitmap() }.getOrNull()
                DeviceApp(id, info.loadLabel(pm).toString(), icon, Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (list.map { it.id } != deviceApps.map { it.id }) deviceApps = list
    }

    /**
     * Systemowa aplikacja „Live TV” (Android TV / Live Channels) – dublowała kafelek telewizji tej aplikacji o tej samej nazwie,
     * więc nie trafia do rzędu ani na listę wyboru.
     */
    private fun isSystemTv(id: String) = id.substringBefore('/') in SYSTEM_TV
    private val SYSTEM_TV = setOf("com.google.android.tv", "com.android.tv", "com.google.android.tv.dtvinput")

    /** Wybór aplikacji zainstalowanej na urządzeniu (bez już dodanych) – lista w stylu panelu ustawień PS5. */
    private fun addDeviceApp() {
        X25Theme.selectSound(this)
        val have = Prefs.ps5Apps.toSet()
        val apps = SystemTools.installedApps(this).filter { e -> e.intent.component?.flattenToString().let { it !in have && it != null && !isSystemTv(it) } }
        if (apps.isEmpty()) { X25Actions.toast(this, "Brak innych aplikacji do dodania"); return }
        Dialogs.menu(this, "Dodaj aplikację", apps.map { e ->
            e.label to {
                val id = e.intent.component!!.flattenToString()
                Prefs.ps5Apps = Prefs.ps5Apps + id
                // fokus na nowym kafelku – gdy okno wyboru zniknie i ekran odzyska fokus
                lifecycleScope.launch { loadDeviceApps(); delay(300); pendingFocus = "a:pkg:$id" }
                X25Actions.toast(this, "Dodano: ${e.label}")
            }
        })
    }

    private fun removeDeviceApp(a: DeviceApp) = Dialogs.confirm(this, a.label, "Usunąć aplikację z ekranu głównego?") {
        Prefs.ps5Apps = Prefs.ps5Apps - a.id
        deviceApps = deviceApps.filter { it.id != a.id }
        pendingFocus = "a:" + apps().first().key
    }

    private fun launchDeviceApp(a: DeviceApp) {
        X25Theme.selectSound(this)
        if (runCatching { startActivity(a.intent) }.isFailure) X25Actions.toast(this, "Nie można uruchomić: ${a.label}")
    }

    /** Testy: dodanie / usunięcie bez okna wyboru. */
    fun addDeviceAppForTest(id: String) { Prefs.ps5Apps = Prefs.ps5Apps + id; lifecycleScope.launch { loadDeviceApps() } }

    // ---------------- WSTECZ / MENU ----------------

    private fun onBack() {
        when {
            options -> closeOptions()
            // launcher: WSTECZ nie zamyka ekranu głównego – wraca na pierwszy kafelek (jak na konsoli)
            X25Theme.launcherHome -> { pendingFocus = "a:" + apps().first().key }
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

    private fun openOptions() { options = true; X25Theme.selectSound(this) }
    private fun closeOptions() { options = false; pendingFocus = lastFocus }

    private fun optionItems(): List<Ps5Option> = listOf(
        Ps5Option("Ekran główny przy starcie", "Otwierany przy uruchomieniu i przyciskiem HOME", Prefs.ps5Home) {
            Prefs.ps5Home = !Prefs.ps5Home; refreshOptions() },
        Ps5Option("Menu jako panel ustawień", "Listy wyboru wysuwane z lewej strony", Prefs.ps5Panel) {
            Prefs.ps5Panel = !Prefs.ps5Panel; refreshOptions() },
        Ps5Option("Dźwięki nawigacji", "Krótki dźwięk przy ruchu pilotem", Prefs.ps5Sounds) {
            Prefs.ps5Sounds = !Prefs.ps5Sounds; refreshOptions() },
        Ps5Option("Wszystkie aplikacje", "Biblioteka aplikacji urządzenia") { options = false; SystemTools.showApps(this) },
        Ps5Option("Ustawienia systemu", "Sieć, dźwięk, obraz, konta urządzenia") { options = false; SystemTools.openSystemSettings(this) },
        Ps5Option("Wtyczki Stremio", "Włącz lub wyłącz źródła") { options = false; startActivity(Intent(this, StremioAddonsActivity::class.java)) },
        Ps5Option("Ustawienia aplikacji", "Wygląd, listy, odtwarzanie, tryb launchera") { options = false; X25Actions.run(this, X25Action(X25Action.SETTINGS)) },
        Ps5Option("Przełącz na motyw X25", "Pulpit z kafelkami w stylu Xbox") { options = false; X25Theme.setEnabled(true); X25Theme.justEnabled = false; X25Theme.openDashboard(this); finish() },
        // ekran główny przy innym motywie (tryb launchera) – włączenie PS5; przy PS5 – wyłączenie
        if (Ps5Theme.active) Ps5Option("Wyłącz motyw PS5", "Powrót do poprzedniego wyglądu") { options = false; Ps5Theme.setEnabled(false); if (!Ps5Theme.homeOn) finish() }
        else Ps5Option("Włącz motyw PS5", "Granat, tło blueprint, ekran główny konsoli") { options = false; Ps5Theme.setEnabled(true); recreate() },
    )

    /** Zmiana przełącznika: panel przebudowany z nowym stanem, fokus zostaje na pozycji. */
    private var optionsVersion by mutableIntStateOf(0)
    private fun refreshOptions() { optionsVersion++; X25Theme.selectSound(this) }

    // ---------------- zawartość zakładek ----------------

    private fun act(type: String) = { X25Theme.selectSound(this); X25Actions.run(this, X25Action(type)) }

    /** Rząd aplikacji bieżącej zakładki: funkcje + ostatnio oglądane (logo, miniatury, plakaty) + aplikacje z urządzenia. */
    private fun apps(t: Int = tab): List<App> = sectionApps(t) + deviceApps.map { d ->
        App("pkg:" + d.id, d.label, "Aplikacja z urządzenia · przytrzymaj OK – usuń", X25Action.APPS, bitmap = d.icon, onLong = { removeDeviceApp(d) }) { launchDeviceApp(d) }
    }

    private fun sectionApps(t: Int): List<App> = if (t == 0) buildList {
        add(App("live", "Live TV", X25Actions.subtitle(X25Action(X25Action.LIVE)), X25Action.LIVE, run = act(X25Action.LIVE)))
        channels.forEach { c -> add(App("ch:" + c.key, c.name, "Live TV · ${c.group}", X25Action.CHANNEL, c.logo) { X25Theme.selectSound(this@Ps5HomeActivity); X25Actions.playChannel(this@Ps5HomeActivity, c) }) }
        add(App("guide", "Przewodnik TV", "Program telewizyjny", X25Action.GUIDE, run = act(X25Action.GUIDE)))
        add(App("fav", "Ulubione kanały", X25Actions.subtitle(X25Action(X25Action.FAVORITES)), X25Action.FAVORITES, run = act(X25Action.FAVORITES)))
        add(App("epg", "Wyszukiwarka programów", "Szukaj w programie TV", X25Action.EPG_SEARCH, run = act(X25Action.EPG_SEARCH)))
        add(App("apps", "Aplikacje", "Zainstalowane aplikacje", X25Action.APPS, run = act(X25Action.APPS)))
    } else buildList {
        add(App("stube", "STube", "YouTube bez reklam", X25Action.STUBE, run = act(X25Action.STUBE)))
        add(App("stremio", "Stremio", "Filmy i seriale z wtyczek", X25Action.STREMIO, run = act(X25Action.STREMIO)))
        videos.forEach { v -> add(App("yt:" + v.videoId, v.title.ifBlank { "Film" }, "STube · ${v.channel}", X25Icons.PLAY, v.thumb) { openVideo(v) }) }
        titles.forEach { t -> add(App("st:" + t.metaId, t.name.ifBlank { "Stremio" }, listOf("Stremio", t.videoLabel).filter { it.isNotBlank() }.joinToString(" · "),
            X25Action.STREMIO, t.poster) { openTitle(t) }) }
        add(App("playlists", "Moje playlisty", "Biblioteka STube", X25Action.PLAYLISTS, run = act(X25Action.PLAYLISTS)))
        add(App("subs", "Subskrypcje", "Najnowsze z kanałów", X25Action.SUBS, run = act(X25Action.SUBS)))
        add(App("history", "Historia i postęp", "Obejrzane i rozpoczęte filmy", X25Action.HISTORY, run = act(X25Action.HISTORY)))
    }

    private fun openVideo(v: WatchProgress) { X25Theme.selectSound(this); X25Actions.playVideo(this, v.toVideo()) }
    private fun openTitle(t: StremioProgress) {
        X25Theme.selectSound(this)
        startActivity(StremioDetailActivity.intent(this, pl.iptv.tv.data.StremioMetaPreview(t.metaId, t.type, t.name, t.poster), t.videoId.ifBlank { null }))
    }

    /** Duża karta „Ostatnio odtwarzane” – zależnie od zakładki: kanał TV albo najnowszy film / tytuł. */
    private data class Hero(val title: String, val sub: String, val image: String, val fraction: Float, val logo: Boolean, val run: () -> Unit)

    private fun hero(): Hero? = if (tab == 0) {
        (Repository.byKey(Prefs.lastChannel)?.takeIf { !it.hidden } ?: channels.firstOrNull())?.let { c ->
            Hero(c.name, "Live TV · ${c.group}", c.logo, -1f, true) { X25Theme.selectSound(this); X25Actions.playChannel(this, c) }
        }
    } else media().firstOrNull()

    /** Filmy STube i tytuły Stremio od najnowszego (karty „Ostatnio odtwarzane” i „Kontynuuj”). */
    private fun media(): List<Hero> = (videos.map { v -> v.updatedAt to Hero(v.title.ifBlank { "Film" }, "STube · ${v.channel}", v.thumb, v.fraction, false) { openVideo(v) } } +
        titles.map { t -> t.updatedAt to Hero(t.name.ifBlank { "Stremio" }, listOf("Stremio", t.videoLabel).filter { it.isNotBlank() }.joinToString(" · "), t.poster, t.fraction, false) { openTitle(t) } })
        .sortedByDescending { it.first }.map { it.second }

    // ---------------- fokus ----------------

    /** Element, który ma teraz fokus (null – żaden z ekranu głównego, np. otwarte okno lub panel). */
    private var focusedNow: String? = null

    private fun onFocused(key: String, title: String = "", sub: String = "") {
        if (lastFocus != null && lastFocus != key) X25Theme.navSound(this)
        lastFocus = key; focusedNow = key
        if (pendingFocus == key) pendingFocus = null
        rowLast[key.substringBefore(':') + ":"] = key
        if (key.startsWith("a:")) selected = title to sub
    }

    /** Wejście fokusu do części ekranu (▲ ▼): ostatnio zaznaczony jej element, inaczej najbliższy. */
    private fun Modifier.rowEntry(prefix: String) = focusProperties {
        enter = { rowLast[prefix]?.takeIf { it in attached }?.let { requester(it) } ?: FocusRequester.Default }
    }

    @Composable
    private fun AutoFocus(key: String) {
        DisposableEffect(key) { attached += key; onDispose { attached -= key } }
        val p = pendingFocus
        // ponawiane, aż element naprawdę dostanie fokus (np. okno jeszcze wraca po zamknięciu listy wyboru) – czyści je [onFocused]
        LaunchedEffect(p) {
            while (p == key && pendingFocus == key) {
                if (focusedNow == key) { pendingFocus = null; break }
                runCatching { requester(key).requestFocus() }; delay(120)
            }
        }
    }

    /** Wspólny modyfikator elementu z fokusem: zapamiętanie, OK / dotknięcie, obwódka i powiększenie. */
    @Composable
    private fun Modifier.ps5Item(key: String, radius: Dp, scaleTo: Float, onFocus: () -> Unit, onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier {
        val c = LocalPs5.current
        var focused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if (focused) scaleTo else 1f, tween(110), label = "ps5s")
        AutoFocus(key)
        return this
            .focusRequester(requester(key))
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus() else if (focusedNow == key) focusedNow = null } }
            .x25Clickable(onClick, onLong)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .ps5Frame(focused, radius, c.ring, c.glow, halo = !c.lowPerf)
    }

    // ================= UI =================

    /** Wymiary wg ekranu: TV – pełne, niski ekran (telefon w poziomie) – mniejsze kafelki i karty, wąski – mniejsze marginesy. */
    /**
     * (7.6) [portrait] – telefon w pionie: karty jedna pod drugą na całą szerokość ([availW]), mniejsze kafelki aplikacji.
     */
    data class Dims(val compact: Boolean, val narrow: Boolean, val portrait: Boolean = false, val availW: Dp = 0.dp) {
        val side get() = if (narrow) 16.dp else if (compact) 28.dp else 56.dp
        val topH get() = if (compact) 44.dp else 56.dp
        val tile get() = if (compact) 58.dp else if (portrait) 72.dp else 84.dp
        val bigH get() = if (compact) 160.dp else if (portrait) 200.dp else 230.dp
        val smallH get() = if (compact) 48.dp else if (portrait) 64.dp else 70.dp
        val heroW get() = if (portrait) availW else if (compact) 260.dp else 380.dp
        val midW get() = if (portrait) availW else if (compact) 200.dp else 290.dp
        val serviceW get() = if (portrait) availW else if (compact) 250.dp else 340.dp
        val gap get() = if (compact) 8.dp else 12.dp
        val label get() = if (compact) 20.sp else if (portrait) 24.sp else 28.sp
    }
    private var dims = Dims(compact = false, narrow = false)

    @Composable
    private fun Home() {
        val cfg = LocalConfiguration.current
        val portrait = cfg.screenWidthDp < 600 && cfg.screenHeightDp > cfg.screenWidthDp
        dims = Dims(compact = cfg.screenHeightDp < 520, narrow = cfg.screenWidthDp < 600, portrait = portrait, availW = (cfg.screenWidthDp - 32).dp)
        Box(Modifier.fillMaxSize()
            .focusProperties { enter = { ((pendingFocus ?: lastFocus)?.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup()) {
            Column(Modifier.fillMaxSize().dashboardEnter().padding(top = if (dims.compact) 6.dp else 14.dp)) {
                TopBar()
                Crossfade(tab, animationSpec = tween(160), label = "tab") { t -> AppRow(t) }
                SelectedLabel()
                Cards()
            }
            // tryb launchera: WSTECZ nie zamyka ekranu głównego – bez podpowiedzi „Wyjście”
            val hints = if (X25Theme.launcherHome) Ps5Hints.HOME.filter { it.first != Ps5Hints.O } else Ps5Hints.HOME
            AndroidView({ Ps5Hints(it, hints) }, Modifier.align(Alignment.BottomEnd).padding(end = dims.side, bottom = 8.dp))
            if (options) Options()
        }
        // zapasowo: element docelowy zniknął – pierwszy kafelek rzędu aplikacji
        LaunchedEffect(pendingFocus) {
            val k = pendingFocus ?: return@LaunchedEffect
            delay(1500)
            if (pendingFocus == k) { pendingFocus = null; runCatching { requester("a:" + apps().first().key).requestFocus() } }
        }
    }

    // ---------------- pasek górny: zakładki, ikony, zegar ----------------

    @Composable
    private fun TopBar() {
        val c = LocalPs5.current
        Row(Modifier.fillMaxWidth().height(dims.topH).padding(horizontal = dims.side).rowEntry("tab:"), verticalAlignment = Alignment.CenterVertically) {
            listOf("Live TV", "Multimedia").forEachIndexed { i, name ->
                val key = "tab:$i"
                val on = tab == i
                Box(Modifier.padding(end = 26.dp)
                    .ps5Item(key, 8.dp, 1.04f, onFocus = { onFocused(key); selectTab(i) }, onClick = { selectTab(i) })
                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    BasicText(name, style = Ps5Type.tab.copy(color = if (on) Color.White else c.text2, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Light,
                        fontSize = if (dims.compact) 18.sp else 22.sp))
                }
            }
            Spacer(Modifier.weight(1f))
            TopIcon(X25Icons.SEARCH) { X25Theme.selectSound(this@Ps5HomeActivity); X25Actions.run(this@Ps5HomeActivity, X25Action(X25Action.EPG_SEARCH)) }
            TopIcon(X25Action.SETTINGS) { X25Theme.selectSound(this@Ps5HomeActivity); X25Actions.run(this@Ps5HomeActivity, X25Action(X25Action.SETTINGS)) }
            TopIcon(X25Icons.MORE) { openOptions() }
            Spacer(Modifier.width(18.dp))
            BasicText(clock, style = Ps5Type.tab.copy(fontSize = if (dims.compact) 17.sp else 21.sp))
        }
    }

    /** Nowa zakładka: rząd aplikacji od początku, pod rzędem nazwa jej pierwszego kafelka (nie z poprzedniej zakładki). */
    private fun selectTab(i: Int) {
        if (tab == i) return
        tab = i; rowLast.remove("a:")
        apps(i).firstOrNull()?.let { selected = it.title to it.sub }
    }

    @Composable
    private fun TopIcon(kind: String, onClick: () -> Unit) {
        val c = LocalPs5.current
        val key = "top:$kind"
        val s = if (dims.compact) 34.dp else 42.dp
        Box(Modifier.padding(start = 10.dp).size(s)
            .ps5Item(key, s / 2, 1.08f, onFocus = { onFocused(key) }, onClick = onClick)
            .padding(s / 4)) {
            Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, kind, Color.White) }
        }
    }

    // ---------------- rząd aplikacji ----------------

    @Composable
    private fun AppRow(t: Int) {
        val list = remember(t, channels, videos, titles, deviceApps) { apps(t) }
        LazyRow(Modifier.fillMaxWidth().rowEntry("a:").focusGroup(),
            contentPadding = PaddingValues(horizontal = dims.side, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (dims.compact) 10.dp else 14.dp)) {
            itemsIndexed(list, key = { _, a -> a.key }) { i, a -> AppTile(a, i == 0) }
        }
    }

    @Composable
    private fun AppTile(a: App, first: Boolean) {
        val c = LocalPs5.current
        val key = "a:" + a.key
        val r = if (dims.compact) 12.dp else 16.dp
        Box(Modifier.size(dims.tile)
            .ps5Item(key, r, 1.14f, onFocus = { onFocused(key, a.title, a.sub) }, onClick = a.run, onLong = a.onLong)
            .clip(RoundedCornerShape(r))
            .background(if (first) Brush.linearGradient(listOf(c.glow, Color(0xFF1B3FA8)))
                else Brush.verticalGradient(listOf(Color(0xFF1A2754), Color(0xFF0B1230))))) {
            when {
                // ikona aplikacji z urządzenia
                a.bitmap != null -> androidx.compose.foundation.Image(a.bitmap, a.title, Modifier.fillMaxSize().padding(dims.tile * 0.16f), contentScale = ContentScale.Fit)
                // logo kanału (inicjały tylko, gdy logo się nie wczyta)
                a.icon == X25Action.CHANNEL -> TileImage(a.image, initials(a.title), Modifier.fillMaxSize().padding(10.dp), ContentScale.Fit,
                    Ps5Type.title.copy(color = c.text2), sizePx = Ui.dp(dims.tile.value.toInt()))
                a.image.isNotBlank() -> TileImage(a.image, initials(a.title), Modifier.fillMaxSize(), ContentScale.Crop,
                    Ps5Type.title.copy(color = c.text2), sizePx = Ui.dp(dims.tile.value.toInt()))
                else -> Canvas(Modifier.fillMaxSize().padding(dims.tile * 0.26f)) { X25Icons.draw(this, a.icon, Color.White) }
            }
        }
    }

    private fun lerpC(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f).copy(alpha = 1f)

    private fun initials(s: String) = initialsOf(s)

    /** Nazwa zaznaczonego kafelka pod rzędem (jak „Witamy” na konsoli). */
    @Composable
    private fun SelectedLabel() {
        val c = LocalPs5.current
        Row(Modifier.fillMaxWidth().padding(horizontal = dims.side).padding(bottom = if (dims.compact) 6.dp else 12.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(selected.first, Modifier.weight(1f, fill = false), style = Ps5Type.display.copy(fontSize = dims.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected.second.isNotEmpty()) BasicText(selected.second, Modifier.padding(start = 16.dp, bottom = 4.dp),
                style = Ps5Type.body.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // ---------------- karty (widgety) ----------------

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun Cards() {
        val g = dims.gap
        val tallH = dims.bigH - dims.smallH - g
        // na TV Compose domyślnie trzyma zaznaczony element w ~30% szerokości – karty przewijają się tylko, gdy wychodzą poza ekran
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SmoothScroll) {
        // (7.6) telefon w pionie: karty jedna pod drugą (przewijanie w pionie, cała szerokość)
        if (dims.portrait) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().rowEntry("c:").focusGroup(),
                contentPadding = PaddingValues(start = dims.side, end = dims.side, top = 8.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(g)) {
                item(key = "hero") { HeroCard() }
                item(key = "stube") { ServiceCard(stube = true) }
                item(key = "stremio") { ServiceCard(stube = false) }
                item(key = "add") { AddAppCard(dims.smallH) }
                item(key = "q1") {
                    if (tab == 0) QuickCard("c:q1", X25Action.GUIDE, "Przewodnik TV", "Co teraz w telewizji", act(X25Action.GUIDE))
                    else QuickCard("c:q1", X25Action.HISTORY, "Historia i postęp", "Wróć do filmów", act(X25Action.HISTORY))
                }
                item(key = "second") { SecondCard(dims.bigH) }
                item(key = "storage") { StorageCard() }
                item(key = "settings") { QuickCard("c:settings", X25Action.SETTINGS, "Ustawienia", "Wygląd, listy, odtwarzanie", act(X25Action.SETTINGS)) }
                item(key = "options") { QuickCard("c:options", X25Icons.MORE, "Opcje ekranu głównego", "Przełączniki motywu PS5") { openOptions() } }
                item(key = "apps") { QuickCard("c:apps", X25Action.APPS, "Aplikacje", "Zainstalowane aplikacje", act(X25Action.APPS)) }
            }
            return@CompositionLocalProvider
        }
        LazyRow(Modifier.fillMaxWidth().rowEntry("c:").focusGroup(), contentPadding = PaddingValues(horizontal = dims.side, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(g)) {
            item(key = "hero") { HeroCard() }
            // obok „Ostatnio odtwarzane”: STube i Stremio – wyszukiwarka i ostatnio oglądane
            item(key = "stube") { ServiceCard(stube = true) }
            item(key = "stremio") { ServiceCard(stube = false) }
            item(key = "col2") {
                Column(verticalArrangement = Arrangement.spacedBy(g)) {
                    AddAppCard(tallH)
                    if (tab == 0) QuickCard("c:q1", X25Action.GUIDE, "Przewodnik TV", "Co teraz w telewizji", act(X25Action.GUIDE))
                    else QuickCard("c:q1", X25Action.HISTORY, "Historia i postęp", "Wróć do filmów", act(X25Action.HISTORY))
                }
            }
            item(key = "col3") {
                Column(verticalArrangement = Arrangement.spacedBy(g)) {
                    StorageCard()
                    SecondCard(tallH)
                }
            }
            item(key = "col4") {
                Column(verticalArrangement = Arrangement.spacedBy(g)) {
                    QuickCard("c:settings", X25Action.SETTINGS, "Ustawienia", "Wygląd, listy, odtwarzanie", act(X25Action.SETTINGS))
                    QuickCard("c:options", X25Icons.MORE, "Opcje ekranu głównego", "Przełączniki motywu PS5") { openOptions() }
                    if (dims.bigH - dims.smallH * 2 - g * 2 >= dims.smallH)
                        QuickCard("c:apps", X25Action.APPS, "Aplikacje", "Zainstalowane aplikacje", act(X25Action.APPS))
                }
            }
        }
        }
    }


    /** Wyszukiwanie z ekranu głównego: okno z polem tekstowym, wynik w STube / Stremio (istniejące ekrany wyszukiwania). */
    private fun searchIn(stube: Boolean) {
        X25Theme.selectSound(this)
        Dialogs.input(this, if (stube) "Szukaj w STube" else "Szukaj w Stremio", "", if (stube) "Filmy, kanały, muzyka" else "Filmy i seriale") { q ->
            val t = q.trim(); if (t.isEmpty()) return@input
            startActivity(if (stube) Intent(this, STubeActivity::class.java).putExtra(STubeActivity.EXTRA_QUERY, t)
                else Intent(this, StremioActivity::class.java).putExtra(StremioActivity.EXTRA_QUERY, t))
        }
    }

    /**
     * Karta STube / Stremio: nagłówek (OK – otwiera aplikację), pasek wyszukiwania i ostatnio oglądane
     * (STube – miniatury 16:9, Stremio – plakaty) z paskiem postępu. Sama karta nie ma fokusu – jej elementy tak.
     */
    @Composable
    private fun ServiceCard(stube: Boolean) {
        val c = LocalPs5.current
        val p = if (stube) "c:yt" else "c:st"
        val name = if (stube) "STube" else "Stremio"
        val w = dims.serviceW
        Column(Modifier.size(w, dims.bigH).clip(RoundedCornerShape(14.dp)).background(c.card)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(if (dims.compact) 6.dp else 10.dp)) {
            // nagłówek – otwiera aplikację
            Row(Modifier.fillMaxWidth().ps5Item("$p:open", 10.dp, 1.02f, onFocus = { onFocused("$p:open", name, if (stube) "YouTube bez reklam" else "Filmy i seriale z wtyczek") },
                onClick = act(if (stube) X25Action.STUBE else X25Action.STREMIO)).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (dims.compact) 24.dp else 30.dp).clip(RoundedCornerShape(8.dp)).background(c.glow).padding(5.dp)) {
                    Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, if (stube) X25Action.STUBE else X25Action.STREMIO, Color.White) }
                }
                BasicText(name, Modifier.padding(start = 10.dp).weight(1f), style = Ps5Type.title)
                BasicText("Otwórz ›", style = Ps5Type.small.copy(color = c.text2))
            }
            // pasek wyszukiwania (kapsuła)
            Row(Modifier.fillMaxWidth().height(if (dims.compact) 30.dp else 40.dp)
                .ps5Item("$p:search", 20.dp, 1.02f, onFocus = { onFocused("$p:search", "Szukaj w $name", "OK – wpisz, czego szukasz") }, onClick = { searchIn(stube) })
                .clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(16.dp)) { X25Icons.draw(this, X25Icons.SEARCH, c.text2) }
                BasicText(if (stube) "Szukaj filmów i kanałów…" else "Szukaj filmów i seriali…", Modifier.padding(start = 10.dp),
                    style = Ps5Type.body.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicText("Ostatnio oglądane", Modifier.padding(start = 4.dp), style = Ps5Type.small.copy(color = c.text2))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stube) {
                    if (videos.isEmpty()) EmptyRecent("Obejrzyj coś w STube – pojawi się tutaj.")
                    videos.take(3).forEach { v -> RecentThumb("$p:${v.videoId}", v.title, "STube · ${v.channel}", v.thumb, v.fraction, wide = true) { openVideo(v) } }
                } else {
                    if (titles.isEmpty()) EmptyRecent("Obejrzyj film lub serial – pojawi się tutaj.")
                    titles.take(4).forEach { t -> RecentThumb("$p:${t.metaId}", t.name, listOf("Stremio", t.videoLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        t.poster, t.fraction, wide = false) { openTitle(t) } }
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.EmptyRecent(text: String) {
        val c = LocalPs5.current
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(10.dp)) {
            BasicText(text, Modifier.align(Alignment.Center), style = Ps5Type.small.copy(color = c.text2), maxLines = 3)
        }
    }

    /** Miniatura ostatnio oglądanego (STube 16:9 – szerokie, Stremio – plakat) z paskiem postępu. */
    @Composable
    private fun RecentThumb(key: String, title: String, sub: String, image: String, fraction: Float, wide: Boolean, onClick: () -> Unit) {
        val c = LocalPs5.current
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
            // STube: 16:9 (najwyżej 100 dp szerokości – trzy obok siebie), Stremio: plakat 2:3 na całą wysokość
            val ww = if (wide) minOf(maxHeight * 16f / 9f, 100.dp) else maxHeight * 0.66f
            val hh = if (wide) ww * 9f / 16f else maxHeight
            Box(Modifier.size(ww, hh)
                .ps5Item(key, 8.dp, 1.06f, onFocus = { onFocused(key, title, sub) }, onClick = onClick)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A2754))) {
                TileImage(image, initials(title), Modifier.fillMaxSize(), ContentScale.Crop, Ps5Type.title.copy(color = c.text2), sizePx = Ui.dp(120))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(c.glow))
            }
        }
    }

    @Composable
    private fun CardFrame(key: String, w: Dp, h: Dp, onClick: () -> Unit, title: String = "", sub: String = "", body: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val c = LocalPs5.current
        Box(Modifier.size(w, h)
            .ps5Item(key, 14.dp, 1.04f, onFocus = { onFocused(key, title, sub) }, onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(c.card)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp)), content = body)
    }

    @Composable
    private fun CardHeader(icon: String, title: String, m: Modifier = Modifier) {
        val c = LocalPs5.current
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp)) { X25Icons.draw(this, icon, c.text2) }
            BasicText(title, Modifier.padding(start = 8.dp), style = Ps5Type.body.copy(color = c.text2, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }

    @Composable
    private fun HeroCard() {
        val c = LocalPs5.current
        val h = remember(tab, channels, videos, titles) { hero() }
        CardFrame("c:hero", dims.heroW, dims.bigH, onClick = h?.run ?: act(if (tab == 0) X25Action.LIVE else X25Action.STUBE)) {
            if (h == null) {
                // jak „Rozpocznij” na konsoli: zachęta, gdy nic jeszcze nie oglądano
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C4FD6), Color(0xFF0A1A55)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    BasicText("Rozpocznij", style = Ps5Type.title)
                    BasicText(if (tab == 0) "Wybierz kanał – ostatnio oglądane pojawią się tutaj." else "Obejrzyj film w STube lub Stremio – wrócisz do niego jednym kliknięciem.",
                        style = Ps5Type.small.copy(color = c.text2), maxLines = 2)
                }
            } else {
                TileImage(h.image, initials(h.title), if (h.logo) Modifier.fillMaxSize().padding(36.dp) else Modifier.fillMaxSize(),
                    if (h.logo) ContentScale.Fit else ContentScale.Crop, Ps5Type.display.copy(color = c.text2), sizePx = Ui.dp(dims.heroW.value.toInt()))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                CardHeader(if (tab == 0) X25Action.LIVE else X25Icons.PLAY, "Ostatnio odtwarzane", Modifier.padding(14.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    BasicText(h.title, style = Ps5Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(h.sub, style = Ps5Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.fraction >= 0f) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(h.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.glow))
                    }
                }
            }
        }
    }

    /**
     * „Dodaj aplikację”: wybór aplikacji zainstalowanej na urządzeniu – trafia do rzędu kafelków na górze (obie zakładki).
     * Karta pokazuje ikony już dodanych aplikacji.
     */
    @Composable
    private fun AddAppCard(h: Dp) {
        val c = LocalPs5.current
        val apps = deviceApps
        CardFrame("c:addapp", dims.midW, h, onClick = { addDeviceApp() }, "Dodaj aplikację",
            if (apps.isEmpty()) "Aplikacje z urządzenia na ekranie głównym" else "Na ekranie głównym: ${apps.size}") {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                CardHeader(X25Action.APPS, "Aplikacje")
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // duży „+” w kółku, obok ikony dodanych aplikacji
                    Canvas(Modifier.size(if (dims.compact) 30.dp else 44.dp)) {
                        val r = size.minDimension / 2; val k = r * 0.45f; val w = 2.5.dp.toPx()
                        drawCircle(c.glow.copy(alpha = 0.25f), r)
                        drawCircle(c.glow, r - w / 2, style = Stroke(w))
                        drawLine(Color.White, Offset(r - k, r), Offset(r + k, r), w)
                        drawLine(Color.White, Offset(r, r - k), Offset(r, r + k), w)
                    }
                    Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        apps.take(if (dims.compact) 3 else 4).forEach { a ->
                            a.icon?.let { androidx.compose.foundation.Image(it, null, Modifier.size(if (dims.compact) 22.dp else 30.dp).clip(RoundedCornerShape(6.dp))) }
                        }
                    }
                }
                BasicText("Dodaj aplikację", style = Ps5Type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                if (!dims.compact) BasicText(if (apps.isEmpty()) "Aplikacje z urządzenia w rzędzie u góry" else "Przytrzymaj OK na kafelku aplikacji – usuń",
                    style = Ps5Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    /** Pamięć urządzenia: wolne miejsce i pasek zajętości, w podpisie pamięć podręczna aplikacji. */
    @Composable
    private fun StorageCard() {
        val c = LocalPs5.current
        val st = storage
        CardFrame("c:storage", dims.midW, dims.smallH, onClick = act(X25Action.SETTINGS), "Pamięć urządzenia",
            st?.let { "Pamięć podręczna: ${bytes(it.cache)}" } ?: "") {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                CardHeader(X25Icons.RESIZE, "Pamięć")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Wolne miejsce", Modifier.weight(1f), style = Ps5Type.small.copy(color = c.text2))
                    BasicText(st?.let { bytes(it.free) } ?: "—", style = Ps5Type.number.copy(fontSize = 13.sp))
                }
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(st?.used ?: 0f).fillMaxHeight().background(Brush.horizontalGradient(listOf(c.glow, Color(0xFF9B6BFF)))))
                }
            }
        }
    }

    /** Druga karta: w „Live TV” – wyszukiwarka programów; w „Multimedia” – drugi ostatnio oglądany materiał. */
    @Composable
    private fun SecondCard(h: Dp) {
        val c = LocalPs5.current
        val v = remember(tab, videos, titles) { if (tab == 1) media().getOrNull(1) else null }
        if (v == null) {
            CardFrame("c:q2", dims.midW, h, onClick = act(X25Action.EPG_SEARCH), "Wyszukiwarka programów", "Szukaj w programie TV") {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF14306E), Color(0xFF0A1433)))))
                CardHeader(X25Icons.SEARCH, "Wyszukaj", Modifier.padding(12.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    BasicText("Wyszukiwarka programów", style = Ps5Type.title, maxLines = 1)
                    BasicText("Filmy, mecze, seriale w programie TV", style = Ps5Type.small.copy(color = c.text2), maxLines = 1)
                }
            }
            return
        }
        CardFrame("c:q2", dims.midW, h, onClick = v.run, v.title, v.sub) {
            TileImage(v.image, "", Modifier.fillMaxSize(), ContentScale.Crop, Ps5Type.title, sizePx = Ui.dp(dims.midW.value.toInt()))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            CardHeader(X25Icons.PLAY, "Kontynuuj", Modifier.padding(12.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                BasicText(v.title, style = Ps5Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(v.sub, style = Ps5Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (v.fraction >= 0f) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(v.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.glow))
                }
            }
        }
    }

    @Composable
    private fun QuickCard(key: String, icon: String, title: String, sub: String, onClick: () -> Unit) {
        val c = LocalPs5.current
        CardFrame(key, dims.midW, dims.smallH, onClick, title, sub) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (dims.compact) 28.dp else 38.dp).clip(RoundedCornerShape(10.dp)).background(c.glow.copy(alpha = 0.22f)).padding(7.dp)) {
                    Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, icon, Color.White) }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    BasicText(title, style = Ps5Type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!dims.compact) BasicText(sub, style = Ps5Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    private fun bytes(b: Long): String {
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) String.format(java.util.Locale("pl"), "%.1f GB", gb) else String.format(java.util.Locale("pl"), "%.0f MB", b / 1_048_576.0)
    }

    // ---------------- panel opcji (styl ustawień PS5) ----------------

    @Composable
    private fun Options() {
        val c = LocalPs5.current
        val first = remember { FocusRequester() }
        val opts = remember(optionsVersion) { optionItems() }
        val narrow = dims.narrow
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { closeOptions() } }) {
            // nieprzezroczysty panel (tekst ekranu głównego nie prześwituje), jak lista ustawień konsoli – ok. 40% szerokości
            Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(if (narrow) 0.9f else 0.42f)
                .background(Brush.horizontalGradient(listOf(lerpC(c.panel, c.glow, 0.05f), lerpC(c.bg, c.panel, 0.4f))))
                .focusGroup().focusProperties { exit = { FocusRequester.Cancel } }) {
                BasicText("Ekran główny", Modifier.padding(start = 28.dp, top = 26.dp, bottom = 14.dp), style = Ps5Type.display.copy(fontSize = 26.sp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(opts, key = { it.label }) { o -> OptionRow(o, if (o === opts.first()) first else null) }
                }
                AndroidView({ Ps5Hints(it, Ps5Hints.LIST) }, Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }

    @Composable
    private fun OptionRow(o: Ps5Option, req: FocusRequester?) {
        val c = LocalPs5.current
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(if (o.sub.isEmpty()) 54.dp else 66.dp)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) X25Theme.navSound(this@Ps5HomeActivity) } }
            .x25Clickable(o.action)
            .focusable()
            .ps5Frame(focused, 10.dp, c.ring, c.glow, halo = false)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(o.label, style = Ps5Type.title.copy(fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (o.sub.isNotEmpty() && focused) BasicText(o.sub, style = Ps5Type.small.copy(color = c.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sw = o.switch
            if (sw != null) Canvas(Modifier.padding(start = 12.dp).size(46.dp, 26.dp)) { ps5Switch(sw, c.glow) }
        }
    }
}

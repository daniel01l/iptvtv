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
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import pl.iptv.tv.ui.theme.glassFocusGlow
import pl.iptv.tv.ui.theme.glassSurface
import pl.iptv.tv.ui.theme.obsidianGlassBackdrop
import pl.iptv.tv.ui.theme.obsidianPrimary
import pl.iptv.tv.ui.theme.LocalObsidianGlass
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import pl.iptv.tv.data.M3uVod
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.VodCatalog
import pl.iptv.tv.ui.theme.ObsidianGlass
import pl.iptv.tv.ui.theme.v2PillTab
import pl.iptv.tv.ui.theme.v2GlassDialog
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
        if (v2) startV2()
        if (intent?.getBooleanExtra(EXTRA_OPTIONS, false) == true) openOptions()
    }

    /** (v2) Ikona profilu w belce modułu ([pl.iptv.tv.ui.theme.V2TopModuleBar]) – panel „Profil i opcje”. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPTIONS, false)) openOptions()
    }

    /** Wyłączony motyw / ekran główny: ustępuje liście kanałów (albo innemu pulpitowi); inne kolory – przebudowa. */
    override fun onThemeEpochChanged() { if (ModernTheme.homeOn) super.onThemeEpochChanged() else finish() }

    override fun onResume() {
        super.onResume()
        if (!ModernTheme.homeOn) { if (!isFinishing) finish(); return }
        if (loaded) refresh(force = true)
        if (v2 && loaded) loadV2()        // (v2) Top 10 / kina – nieaktualne odświeżane w tle (z pamięci od razu)
    }

    /** (7.28+) Pulpit widoczny – automatyczna zmiana slajdów hero tylko wtedy. */
    private var screenStarted by androidx.compose.runtime.mutableStateOf(false)
    override fun onStart() { super.onStart(); screenStarted = true }

    /**
     * (7.28+) Pulpit przykryty (Live TV, STube, Stremio…): praca v2 w tle przerwana – pobieranie Top 10 / TMDB (trendy, pula
     * platform, kina), automatyczne slajdy hero z pobieraniem i dekodowaniem dużych teł. Dawniej `lifecycleScope` pulpitu
     * działał dalej pod odtwarzaczem i zabierał łącze / CPU słabego boxa startującemu kanałowi (start do ~10 s, przy każdym
     * uruchomieniu z motywem v2). Wznowienie – [onResume] → [loadV2] (zapis z pamięci od razu).
     */
    override fun onStop() {
        screenStarted = false
        v2Job?.cancel(); v2Job = null
        super.onStop()
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
            // (v2) pulpit z górnym paskiem: z treści – lista na górę i fokus na pasek; z paska – wyjście (launcher: zostaje)
            v2 && focusedNow?.startsWith(TOP_PREFIX) != true -> { topTick++; pendingFocus = lastTop?.takeIf { it in attached } ?: TOP_LIVE }
            v2 && X25Theme.launcherHome -> pendingFocus = HERO_PLAY
            v2 -> finishAffinity()
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
        if (heroKey(event)) return true      // (v2) slajdy banera ◄ ►
        if (heroUpKey(event)) return true    // (v2) ▲ z pierwszego rzędu – cały baner, nie pasek u góry
        return super.dispatchKeyEvent(event)
    }

    /** (v2) Lista treści pulpitu i id pierwszego rzędu pod banerem (bieżąca sekcja). */
    private var v2List: androidx.compose.foundation.lazy.LazyListState? = null
    private var v2FirstRow: String? = null
    /** Liczba powrotów ▲ z pierwszego rzędu na baner (testy). */
    var heroUps = 0; private set

    /**
     * (v2) ▲ z pierwszego rzędu: baner mógł być już poza kompozycją listy (przewinięty) – wtedy wyszukiwanie fokusu Compose
     * przeskakiwało od razu na górny pasek, a lista zostawała przewinięta z uciętym banerem. Teraz lista wraca na samą górę
     * (cały baner widoczny), a kursor trafia na „Oglądaj”.
     */
    private fun heroUpKey(e: NativeKey): Boolean {
        if (!v2 || options || heroCount == 0 || e.keyCode != NativeKey.KEYCODE_DPAD_UP) return false
        val row = v2FirstRow ?: return false
        if (focusedNow?.startsWith("r:$row|") != true) return false
        if (e.action != NativeKey.ACTION_DOWN) return true
        val st = v2List ?: return false
        heroUps++
        lifecycleScope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            // najpierw baner do kompozycji, potem kursor, a na końcu lista na samą górę (przewinięcie za fokusem zostawiało ucięty baner)
            if (st.firstVisibleItemIndex > 0) runCatching { st.animateScrollToItem(0) }
            runCatching { requester(HERO_PLAY).requestFocus() }.onFailure { pendingFocus = HERO_PLAY }
            kotlinx.coroutines.delay(HERO_SCROLL_DELAY_MS)
            runCatching { st.animateScrollToItem(0) }
        }
        return true
    }

    private fun openOptions() { options = true }
    private fun closeOptions() { options = false; pendingFocus = lastFocus ?: HERO_PLAY }

    private var optionsVersion by mutableIntStateOf(0)
    private fun refreshOptions() { optionsVersion++ }

    private fun optionItems(): List<ModernOption> = listOfNotNull(
        // (v2) ikona „Profil / Moje playlisty” górnego paska – biblioteka STube na początku panelu
        if (v2) ModernOption("Moje playlisty", "Na później, historia, polubione i playlisty") { options = false; act(X25Action.PLAYLISTS)() } else null,
        ModernOption("Ekran główny przy starcie", "Otwierany przy uruchomieniu i przyciskiem HOME", Prefs.modernHome) {
            Prefs.modernHome = !Prefs.modernHome; refreshOptions() },
        ModernOption("Menu jako panel nakładkowy", "Listy wyboru wysuwane z prawej strony", Prefs.modernOverlay) {
            Prefs.modernOverlay = !Prefs.modernOverlay; refreshOptions() },
        ModernOption("Konto i ustawienia STube", "Logowanie, subskrypcje, historia") { options = false; X25Actions.run(this, X25Action(X25Action.SETTINGS)) },
        ModernOption("Wtyczki Stremio", "Włącz lub wyłącz źródła") { options = false; startActivity(Intent(this, StremioAddonsActivity::class.java)) },
        ModernOption("Wszystkie aplikacje", "Biblioteka aplikacji urządzenia") { options = false; SystemTools.showApps(this) },
        ModernOption("Ustawienia systemu", "Sieć, dźwięk, obraz, konta urządzenia") { options = false; SystemTools.openSystemSettings(this) },
        ModernOption("Ustawienia aplikacji", "Wygląd, listy, odtwarzanie, tryb launchera") { options = false; X25Actions.run(this, X25Action(X25Action.SETTINGS)) },
        // (7.27) „Przełącz na motyw PS5 / X25” usunięte razem z motywami (docs/archiwum_motywy/)
    )

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

    /** (v2) Motyw Nowoczesny v2 („Obsidian Glass”) – odczyt przy każdej kompozycji (zmiana motywu przebudowuje ekran). */
    private val v2: Boolean get() = ModernV2.active

    /** (7.6) Postęp przejścia między sekcjami (0 → 1). */
    private val sectionAnim = androidx.compose.animation.core.Animatable(1f)
    /** Sekcja, dla której działa [sectionAnim] (różna od [section] = przejście jeszcze nie ruszyło – rysunek od zera). */
    private var animSection by mutableIntStateOf(0)     // stan – przypisanie odświeża warstwę także w trybie słabszych urządzeń (bez animacji)
    private val lowPerfNow get() = pl.iptv.tv.ui.DevicePerformanceProfile.uiLow

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
        // (7.23) dwustopniowo ([DashboardRail]): nieaktywna kategoria – tylko aktywacja na pulpicie (fokus zostaje na ikonie),
        // ponowne stuknięcie / OK na aktywnej – wejście w jej ekran
        DashboardRail.click(section, i, activate = { previewSection(i); railTaps++ }, open = { enterSection(i) })
    }

    /** Liczba stuknięć, które tylko aktywowały kategorię (testy). */
    var railTaps = 0; private set
    /** Stuknięcie ikony kategorii [i] paska bocznego (testy – to samo co dotknięcie / OK). */
    fun railTap(i: Int) = openSection(i)

    /** Wejście w aktywną kategorię: Na żywo, STube, Stremio, Moje playlisty – ich ekrany; Start – kursor na „Ostatnio oglądane”. */
    private fun enterSection(i: Int) {
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
        // (v2) górny pasek: hero wraca do karuzeli slajdów (podgląd plakatu z rzędu znika)
        if (key.startsWith(TOP_PREFIX)) { lastTop = key; v2HoverJob?.cancel(); if (heroHover != null) heroHover = null }
        // (v2) kursor na banerze (z dowolnego miejsca – ▲ z rzędu, z paska, po WSTECZ): lista na samą górę, cały baner widoczny.
        // Po krótkiej zwłoce – przewinięcie „za fokusem” (BringIntoView) kończy się wcześniej i nie zostawia uciętego banera.
        if (v2 && (key == HERO_PLAY || key == HERO_INFO)) {
            heroScrollJob?.cancel()
            heroScrollJob = lifecycleScope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {      // animacja przewijania wymaga zegara klatek Compose
                kotlinx.coroutines.delay(HERO_SCROLL_DELAY_MS)
                val st = v2List ?: return@launch
                // przewijanie za fokusem (wyższy priorytet) anulowałoby nasze – czekamy na jego koniec
                repeat(HERO_SCROLL_WAIT_STEPS) { if (!st.isScrollInProgress) return@repeat; kotlinx.coroutines.delay(16) }
                if (st.firstVisibleItemIndex != 0 || st.firstVisibleItemScrollOffset != 0) runCatching { st.animateScrollToItem(0) }
            }
        }
    }
    private var heroScrollJob: kotlinx.coroutines.Job? = null

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
        val scale by focusScale(focused.value, if (c.lowPerf && v2) 1f else scaleTo, "ms")
        val view = androidx.compose.ui.platform.LocalView.current
        val had = remember(key) { booleanArrayOf(false) }
        AutoFocus(key)
        return this
            .focusRequester(requester(key))
            // ◄ z pierwszej kolumny (przyciski hero, pierwszy kafelek rzędu) – zawsze pasek ikon (bieżąca sekcja), nie element wyżej
            .then(if (toRail && !v2) Modifier.focusProperties { left = requester("nav:s$section") } else Modifier)
            .onFocusChanged {
                if (it.isFocused != had[0]) { had[0] = it.isFocused; if (it.isFocused) { onFocused(key); onFocus() } else if (focusedNow == key) focusedNow = null }
                // (v2) telefon w pionie obsługiwany dotykiem: bez ramki / poświaty fokusu (kursor z pilota – nadal widoczny)
                val shown = it.isFocused && !(v2 && dims.portrait && view.isInTouchMode)
                if (shown != focused.value) focused.value = shown
            }
            .x25Clickable(onClick, onLong)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // (v2) Nowoczesny v2: wyraźna niebieska ramka z poświatą Obsidian Glass (obiekty w drawWithCache)
            .then(if (!frame) Modifier else if (v2) Modifier.glassFocusGlow(focused.value, radius, c.lowPerf)
                else Modifier.modernFrame(focused.value, radius, c.ring, halo = false))
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
        // (v2) Nowoczesny v2: osobny układ – górny pasek, szeroki baner hero, Top 10 / kina / VOD / kanały / skróty ([V2Home])
        if (v2) { V2Home(); return }
        val c = LocalModern.current
        val cfg = LocalConfiguration.current
        val portrait = cfg.screenWidthDp < 600 && cfg.screenHeightDp > cfg.screenWidthDp
        // dostępna szerokość rzędów: ekran − pasek − marginesy − odstęp z lewej rzędu (10) i z prawej (≈ 16)
        dims = Dims(compact = cfg.screenHeightDp < 520, narrow = cfg.screenWidthDp < 600, portrait = portrait,
            availW = (cfg.screenWidthDp - 56 - 10 - 10 - 16).dp)
        // (7.17) modyfikator tła zapamiętany – bitmapa gradientów powstaje raz (nie przy każdej rekompozycji ekranu)
        val glass = v2
        val backdrop = remember(c.bg, c.accent, glass) { if (glass) Modifier.obsidianGlassBackdrop() else Modifier.modernBackdrop(c.bg, c.accent) }
        Box(Modifier.fillMaxSize().then(backdrop)
            .focusProperties { enter = { ((pendingFocus ?: lastFocus)?.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup()) {
            HeroBackdrop()
            // (v2) telefon: treść nad paskiem nawigacji systemu i klawiaturą (na TV i przy ukrytych paskach odstęp 0)
            Column(Modifier.fillMaxSize().then(if (glass) Modifier.navigationBarsPadding().imePadding() else Modifier).padding(start = dims.rail + dims.side + if (glass) (pl.iptv.tv.ui.theme.ObsidianGlass.ISLAND_MARGIN_DP * 2).dp else 0.dp)   // (v2) miejsce na pływającą wyspę
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
        // (7.28) tryb szybki: bez poświaty zależnej od materiału – tło nie przerysowuje się przy ruchu po kafelkach
        val glowLayer = remember(glow, DevicePerformanceProfile.lite) { if (DevicePerformanceProfile.lite) Modifier else Modifier.obsidianAmbientGlow(glow) }
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
        // (v2) zawsze biały tekst: przycisk główny – gradient #2F80ED → #1D4ED8, pozostałe – szkło; fokus – tint + błękitna ramka + poświata
        val ink = if (focused.value && !v2) pl.iptv.tv.ui.theme.ObsidianTokens.BackgroundPrimary else Color.White
        Row(Modifier.height(h).zIndex(if (focused.value) 1f else 0f).item(key, h / 2, if (v2) 1.03f else 1.06f, focused, frame = false, toRail = toRail, onClick = onClick)
            .then(if (v2) Modifier.glassFocusGlow(focused.value, h / 2, c.lowPerf, tint = !primary)
                    .then(if (primary) Modifier.obsidianPrimary(h / 2) else Modifier.glassSurface(h / 2, opacity = LocalObsidianGlass.current.opacity))
                else Modifier.obsidianGlassCard(focused.value, h / 2, c.accent))
            .clip(RoundedCornerShape(h / 2))
            .background(when { v2 -> Color.Transparent; focused.value -> Color.White; primary -> c.accent; else -> Color.Transparent })
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
            // (v2) każda karta na szkle Obsidian Glass, fokus – tint + błękitna ramka 2 dp + poświata, powiększenie 1,03 (graphicsLayer)
            val g2 = v2
            Box(Modifier.size(w, h).item(key, r, if (g2) 1.04f else 1.06f, focused, frame = g2 || !glass, onFocus = { showFeature(t.feature) }, toRail = first, onClick = t.run)
                .then(if (g2) Modifier.glassSurface(r, opacity = LocalObsidianGlass.current.opacity) else if (glass) Modifier.obsidianGlassCard(focused.value, r, c.accent) else Modifier.modernTileSheen(r))
                .then(if (g2 && focused.value) Modifier.background(pl.iptv.tv.ui.theme.ObsidianGlass.Tokens.FocusTint, RoundedCornerShape(r)) else Modifier)   // (v2) tint 18 % nad szkłem
                .clip(RoundedCornerShape(r)).then(if (glass || g2) Modifier else Modifier.background(c.panel))) {
                when (t.kind) {
                    Kind.CHANNEL -> {
                        // logo nadawcy wyśrodkowane na szkle (inicjały, gdy logo brak)
                        TileImage(t.image, t.sub.takeIf { t.fraction >= 0f } ?: t.title, Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = w * 0.22f, vertical = h * 0.2f),
                            ContentScale.Fit, ModernType.tile.copy(fontSize = if (dims.compact) 15.sp else 20.sp), sizePx = Ui.dp(w.value.toInt()))
                    }
                    Kind.ACTION -> {
                        if (!g2) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lighten(c.panel), c.panel))))
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

    // =====================================================================================================================
    // (v2) Pulpit START motywu Nowoczesny v2 („Obsidian Glass”): górna szklana belka (logo, pigułki modułów Telewizja / STube /
    // Stremio / warunkowo VOD, Szukaj, Przypomnienia, Ustawienia, Profil), szeroki szklany baner hero (slajdy: Top 10, kina,
    // VOD z listy, ostatni kanał – kropki, ◄ ► / przesunięcie palcem), rzędy: Top 10 z numerami, Obecnie w kinach, VOD z Twojej
    // listy (tylko gdy lista ma filmy / seriale), KANAŁY TV, KONTYNUUJ OGLĄDANIE, NA SKRÓTY. Stary Nowoczesny (v1) bez zmian.
    // =====================================================================================================================

    /** (v2) Slajd hero: tytuł, rok, linia metadanych, opis, obraz (tło / plakat / logo kanału), pigułka techniczna, akcje. */
    @androidx.compose.runtime.Immutable
    data class V2Slide(val key: String, val title: String, val year: String = "", val meta: List<String> = emptyList(), val desc: String = "",
                       val image: String = "", val logo: Boolean = false, val tech: String = "", val badge: String = "",
                       val playLabel: String = HERO_WATCH, val play: () -> Unit, val info: () -> Unit)

    /** (v2) Plakat rzędu: Top 10 ([rank] > 0 – duży numer), „Obecnie w kinach”, „VOD z Twojej listy”. */
    @androidx.compose.runtime.Immutable
    data class V2Poster(val key: String, val title: String, val image: String, val rating: String, val rank: Int, val badge: String,
                        val slide: V2Slide, val open: () -> Unit)

    private var top10Spec by mutableStateOf<pl.iptv.tv.data.StremioTop10.RowSpec?>(null)
    private var top10 by mutableStateOf<List<StremioMetaPreview>>(emptyList())
    private var cinema by mutableStateOf<List<StremioMetaPreview>>(emptyList())
    /** Katalog VOD z list M3U ([pl.iptv.tv.data.VodLibrary.catalog]) – pigułka „VOD” i rząd „VOD z Twojej listy” tylko, gdy niepusty. */
    private var vodCat by mutableStateOf(VodCatalog())
    private var heroIndex by mutableIntStateOf(0)
    /** Plakat pod kursorem pokazany w hero (null – karuzela slajdów). */
    private var heroHover by mutableStateOf<V2Slide?>(null)
    private var v2HoverJob: Job? = null
    private var v2Job: Job? = null
    /** Ostatnio zaznaczona pozycja górnego paska (tam wraca fokus z hero / WSTECZ). */
    private var lastTop: String? = null

    // ---- dostęp dla testów ----
    val vodTabShown get() = !vodCat.isEmpty
    val v2RowIdsNow get() = v2RowIds()
    val heroSlideKeys get() = v2Slides().map { it.key }
    val heroIndexNow get() = heroIndex
    /** Plakaty Top 10 w ostatniej kompozycji (testy). */
    var composedTop10 = -1; private set
    var contentComps = 0; private set
    /** Klucze fokusu obecne w kompozycji (testy). */
    val attachedKeys: Set<String> get() = attached
    val heroHoverKey get() = heroHover?.key
    fun heroStep(d: Int) = stepHero(d, v2SlidesFor(section).size)
    /** Testy: Top 10 i „Obecnie w kinach” bez sieci. */
    internal fun setV2ContentForTest(top: List<StremioMetaPreview>, cin: List<StremioMetaPreview>, spec: pl.iptv.tv.data.StremioTop10.RowSpec? = null) {
        testContent = true; v2Job?.cancel()
        lifecycleScope.launch { top10Spec = spec ?: pl.iptv.tv.data.StremioTop10.rows("movie", all = true).firstOrNull(); top10 = top; cinema = cin }
    }

    /** (v2) Start pulpitu: katalog VOD obserwowany (zmiana listy M3U – pigułka i rząd od razu), Top 10 i kina z pamięci / w tle. */
    private fun startV2() {
        pendingFocus = HERO_PLAY
        lifecycleScope.launch { runCatching { pl.iptv.tv.data.VodLibrary.current() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { pl.iptv.tv.data.VodLibrary.catalog.collect { onVodCatalog(it) } }
        }
        loadV2()
    }

    /**
     * Nowy katalog VOD. Pigułka „VOD” stoi na końcu zakładek modułów (klucze stałe) – pojawienie się nie przesuwa kursora;
     * gdy znika z kursorem na sobie (albo w rzędzie VOD), kursor przechodzi na sąsiednią „Stremio” / „Oglądaj” (nigdy nie ginie).
     */
    private fun onVodCatalog(c: VodCatalog) {
        val had = !vodCat.isEmpty
        if (c === vodCat) return
        vodCat = c
        if (had && c.isEmpty) {
            val f = focusedNow ?: lastFocus
            if (f == TOP_VOD) pendingFocus = TOP_STREMIO
            else if (f?.startsWith("r:$V2_VOD|") == true) pendingFocus = HERO_PLAY
            if (lastTop == TOP_VOD) lastTop = TOP_STREMIO
            if (heroHover?.key?.startsWith("v") == true) heroHover = null
        }
    }

    /** Top 10 (pierwsza włączona platforma z zapisem, filmy; brak – seriale) i „Obecnie w kinach”: zapis od razu, sieć w tle. */
    private var testContent = false
    private fun loadV2() {
        if (testContent) return
        if (v2Job?.isActive == true) return
        v2Job = lifecycleScope.launch {
            launch { val l = loadCinema(); if (l.isNotEmpty() && l != cinema) cinema = l }
            val T = pl.iptv.tv.data.StremioTop10
            if (!pl.iptv.tv.data.StremioTmdb.available) { top10Spec = null; if (top10.isNotEmpty()) top10 = emptyList(); return@launch }
            T.init(this@ModernHomeActivity)
            val specs = T.rows("movie").ifEmpty { T.rows("series") }
            if (specs.isEmpty()) { top10Spec = null; top10 = emptyList(); return@launch }
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { specs.firstOrNull { !T.cached(it)?.items.isNullOrEmpty() } }
            val spec = saved ?: specs.first()
            top10Spec = spec
            T.cached(spec)?.items?.takeIf { it.isNotEmpty() }?.take(T.SIZE)?.let { if (it != top10) top10 = it }
            if (T.isStale(spec)) runCatching { T.refresh(spec) }.getOrNull()?.takeIf { it.isNotEmpty() }?.take(T.SIZE)
                ?.let { if (top10Spec == spec && it != top10) top10 = it }
            // pierwsza platforma bez wyników – następna
            if (top10.isEmpty()) for (s in specs) {
                if (s == spec) continue
                val l = runCatching { T.refresh(s) }.getOrNull()
                if (!l.isNullOrEmpty()) { top10Spec = s; top10 = l.take(T.SIZE); break }
            }
        }
    }

    /** „Obecnie w kinach”: TMDB (polski repertuar), bez klucza / wyników – Cinemeta; pamięć [CINEMA_TTL_MS]. */
    private suspend fun loadCinema(): List<StremioMetaPreview> {
        val now = System.currentTimeMillis()
        cinemaCache?.takeIf { now - it.first < CINEMA_TTL_MS && it.second.isNotEmpty() }?.let { return it.second }
        val tmdb = if (pl.iptv.tv.data.StremioTmdb.available) runCatching { pl.iptv.tv.data.StremioTmdb.nowInCinemas() }.getOrNull().orEmpty() else emptyList()
        val list = tmdb.ifEmpty {
            runCatching {
                StremioStore.enabledAddons().firstOrNull { a -> a.catalogs.any { it.type == "movie" && it.id == "year" } }
                    ?.let { pl.iptv.tv.data.StremioApi.inCinemas(it) }
            }.getOrNull().orEmpty()
        }.take(20)
        if (list.isNotEmpty()) cinemaCache = now to list
        return list
    }

    /** Tytuł katalogu Stremio / TMDB: z listy M3U – odtwarzanie; TMDB – identyfikator IMDb, potem szczegóły ze źródłami. */
    private fun openMeta(m: StremioMetaPreview) {
        if (M3uVod.isM3uId(m.id)) { M3uVodUi.open(this, m.id); return }
        if (!pl.iptv.tv.data.StremioTmdb.isTmdbId(m.id)) { startActivity(StremioDetailActivity.intent(this, m, null)); return }
        lifecycleScope.launch {
            val r = runCatching { pl.iptv.tv.data.StremioTmdb.resolve(m) }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (r == null) I18n.makeToast(this@ModernHomeActivity, "Tego tytułu nie ma jeszcze w katalogach Stremio: ${m.name}", android.widget.Toast.LENGTH_SHORT).show()
            else startActivity(StremioDetailActivity.intent(this@ModernHomeActivity, r, null))
        }
    }

    private fun metaSlide(m: StremioMetaPreview, tech: String, badge: String): V2Slide {
        val bg = if (m.id.startsWith("tt")) "https://images.metahub.space/background/medium/${m.id.substringBefore(':')}/img" else m.poster
        val open = { openMeta(m) }
        return V2Slide("sm:" + m.id, m.name.ifBlank { "Stremio" }, m.releaseInfo.ifBlank { m.released }.take(4),
            listOfNotNull(if (m.type == "series") "Serial" else "Film", m.imdbRating.takeIf { it.isNotBlank() }?.let { "★ " + it.take(3) }) + m.genres.take(3),
            m.description, bg, tech = tech, badge = badge, play = open, info = open)
    }

    private fun featureSlide(f: Feature, tech: String) = V2Slide(f.key, f.title, "", f.meta, f.desc, f.images.firstOrNull().orEmpty(), f.logo, tech, f.badge,
        f.playLabel, f.play, f.info)

    private fun channelSlide(c: Channel): V2Slide = channelFeature(c).let { f -> featureSlide(f, listOf("Live TV", f.time.ifBlank { c.group }).filter { it.isNotBlank() }.joinToString(" • ")) }

    private fun vodMovieSlide(m: M3uVod.Movie) = V2Slide("vm:" + m.id, m.title, m.year, listOf("Film") + m.genres.take(3),
        "Film z Twojej listy M3U – odtwarzanie od razu, z postępem i napisami jak w Stremio.", m.poster, tech = VOD_TECH, badge = TAB_VOD,
        play = { M3uVodUi.open(this, m.id) }, info = { vodInfo(m.id) })

    private fun vodShowSlide(s: M3uVod.Show) = V2Slide("vs:" + s.id, s.title, "",
        listOf("Serial", "${s.seasons.size} sez. · ${s.episodes.size} odc.") + s.genres.take(2),
        "Serial z Twojej listy M3U – wybierz sezon i odcinek.", s.poster, tech = VOD_TECH, badge = TAB_VOD,
        play = { M3uVodUi.open(this, s.id) }, info = { vodInfo(s.id) })

    /** Okno informacji pozycji VOD z listy (rok, gatunki, grupa / sezony), przycisk „Oglądaj”. */
    private fun vodInfo(id: String) {
        val cat = vodCat
        val m = cat.movie(id); val s = cat.show(id)
        val title = m?.title ?: s?.title ?: return
        val msg = if (m != null) listOf(m.year, m.genres.joinToString(", "), m.channel.group).filter { it.isNotBlank() }.joinToString("  ·  ")
            else s!!.seasons.joinToString("\n") { n -> M3uVodUi.seasonLabel(n, s.season(n).size) } + s.genres.joinToString(", ").let { if (it.isBlank()) "" else "\n\n$it" }
        Ui.dialog(this).setTitle(title).setMessage(msg.ifBlank { "Pozycja z Twojej listy M3U." })
            .setPositiveButton(HERO_WATCH) { _, _ -> M3uVodUi.open(this, id) }
            .setNegativeButton("Zamknij", null).show()
    }

    /** Slajdy hero: Top 10 (4), kina (2), VOD z listy (film, serial), ostatni kanał; nic – powitanie. Najwyżej [HERO_SLIDES]. */
    private fun v2Slides(): List<V2Slide> {
        val out = ArrayList<V2Slide>()
        val platform = top10Spec?.platform?.name.orEmpty()
        top10.take(4).forEachIndexed { i, m -> out += metaSlide(m, top10Tech(i, platform), "TOP 10") }
        cinema.take(2).forEach { out += metaSlide(it, CINEMA_TECH, StremioUi.IN_CINEMAS_BADGE) }
        vodCat.movies.firstOrNull()?.let { out += vodMovieSlide(it) }
        vodCat.series.firstOrNull()?.let { out += vodShowSlide(it) }
        (Repository.byKey(Prefs.lastChannel)?.takeIf { !it.hidden } ?: recent.firstOrNull())?.let { out += channelSlide(it) }
        if (out.isEmpty()) out += featureSlide(defaultFeature(0), APP_NAME)
        return out.distinctBy { it.key }.take(HERO_SLIDES)
    }

    /** (v2) Slajdy hero sekcji [s]: Telewizja – ostatni / ulubione kanały, STube – filmy, Stremio – Top 10, kina, oglądane, VOD – z listy. */
    private fun v2SlidesFor(s: Int): List<V2Slide> = if (s !in listOf(1, 2, 3, SECTION_VOD)) v2Slides() else when (s) {
        1 -> (listOfNotNull(Repository.byKey(Prefs.lastChannel)?.takeIf { !it.hidden }) + recent + favs).distinctBy { it.key }.map { channelSlide(it) }
        2 -> videos.map { featureSlide(videoFeature(it), "STube") }
        3 -> top10.take(4).mapIndexed { i, m -> metaSlide(m, top10Tech(i, top10Spec?.platform?.name.orEmpty()), "TOP 10") } +
            cinema.take(2).map { metaSlide(it, CINEMA_TECH, StremioUi.IN_CINEMAS_BADGE) } + titles.take(2).map { featureSlide(titleFeature(it), "Stremio") }
        SECTION_VOD -> vodCat.movies.take(3).map { vodMovieSlide(it) } + vodCat.series.take(3).map { vodShowSlide(it) }
        else -> emptyList()
    }.ifEmpty { listOf(featureSlide(defaultFeature(s), APP_NAME)) }.distinctBy { it.key }.take(HERO_SLIDES)

    /** (v2) Sekcja VOD: osobno filmy i seriale z listy M3U. */
    private fun vodSectionRows(): List<Triple<String, String, List<V2Poster>>> {
        val m = vodCat.movies.take(VOD_ROW_MAX * 2).map { mv -> vodMovieSlide(mv).let { s -> V2Poster("m:" + mv.id, mv.title, mv.poster, "", 0, "", s, s.play) } }
        val s = vodCat.series.take(VOD_ROW_MAX * 2).map { sh -> vodShowSlide(sh).let { sl -> V2Poster("s:" + sh.id, sh.title, sh.poster, "", 0, "", sl, sl.play) } }
        return listOf(Triple("vodm", StremioActivity.M3U_MOVIES_ROW, m), Triple("vods", StremioActivity.M3U_SERIES_ROW, s))
    }

    /** (v2) Zakładka sekcji [i] z fokusem: podgląd sekcji na pulpicie (jak pasek w v1, po zatrzymaniu kursora). */
    private fun v2TabFocus(i: Int) { schedulePreview(i) }

    /** (v2) OK / dotknięcie zakładki: wejście do ekranu sekcji (Start – kursor na rzędy). */
    private fun v2TabOpen(i: Int) {
        previewJob?.cancel()
        // (7.28) dotyk (telefon): dwustopniowo jak pasek v1 ([DashboardRail]) – pierwsze stuknięcie nieaktywnej zakładki tylko ją
        // aktywuje (rzędy i baner sekcji na pulpicie), kolejne – wejście; pilot: fokus już aktywuje (podgląd), OK – wejście jak dotąd
        if (window.decorView.isInTouchMode && DashboardRail.tap(section, i) == DashboardRail.Tap.ACTIVATE) {
            previewSection(i); railTaps++; return
        }
        if (section != i) previewSection(i)
        when (i) {
            0 -> { topTick++; pendingFocus = HERO_PLAY }
            SECTION_VOD -> startActivity(StremioActivity.vodIntent(this))
            else -> enterSection(i)
        }
    }

    private fun top10Tech(i: Int, platform: String) = listOf("#${i + 1}", platform, "TMDB").filter { it.isNotBlank() }.joinToString(" • ")

    private fun metaPoster(row: String, m: StremioMetaPreview, rank: Int, slide: V2Slide, badge: String = "") =
        V2Poster("$row:${m.id}", m.name, m.poster, m.imdbRating.take(3), rank, badge, slide) { openMeta(m) }

    /** Rząd „VOD z Twojej listy”: filmy i seriale na przemian (najwyżej [VOD_ROW_MAX]). */
    private fun vodPosters(): List<V2Poster> {
        val c = vodCat
        if (c.isEmpty) return emptyList()
        val movies = c.movies.take(VOD_ROW_MAX).map { mv -> vodMovieSlide(mv).let { s -> V2Poster("m:" + mv.id, mv.title, mv.poster, "", 0, "FILM", s, s.play) } }
        val shows = c.series.take(VOD_ROW_MAX).map { sh -> vodShowSlide(sh).let { s -> V2Poster("s:" + sh.id, sh.title, sh.poster, "", 0, "SERIAL", s, s.play) } }
        return buildList { for (i in 0 until maxOf(movies.size, shows.size)) { movies.getOrNull(i)?.let { add(it) }; shows.getOrNull(i)?.let { add(it) } } }.take(VOD_ROW_MAX)
    }

    /** „Kanały TV”: ostatnio oglądane + ulubione (bez powtórzeń), bez historii – pierwsze kanały listy. */
    private fun v2Channels(): List<Channel> = (recent + favs).distinctBy { it.key }.ifEmpty { all }.take(16)

    private fun openEditor() {
        if (Repository.allChannels.isEmpty()) { I18n.makeToast(this, "Najpierw dodaj listę kanałów", android.widget.Toast.LENGTH_SHORT).show(); return }
        val go = { startActivity(Intent(this, EditorActivity::class.java)) }
        if (Prefs.parentalSettings) ParentalUi.require(this) { go() } else go()
    }

    /** „Na skróty”: Program TV, Edytor listy, Nagrywanie, Ustawienia + wyszukiwarka programów i aplikacje (jak dotąd na pulpicie). */
    private fun v2Shortcuts() = listOf(
        actionTile("guide", X25Action.GUIDE, "Program TV", "Program telewizyjny wszystkich kanałów, nagrywanie i archiwum.", act(X25Action.GUIDE)),
        actionTile("editor", X25Icons.EDIT, "Edytor listy", "Kolejność, nazwy i ukrywanie kanałów oraz kategorii.") { openEditor() },
        actionTile("rec", ModernIcons.REC, "Nagrywanie", "Nagrania, folder zapisu i ustawienia nagrywania.") {
            startActivity(SettingsActivity.intentFrom(this).putExtra("tab", REC_SETTINGS_TAB)) },
        actionTile("settings", X25Action.SETTINGS, "Ustawienia", "Wygląd, listy, odtwarzanie, tryb launchera.", act(X25Action.SETTINGS)),
        actionTile("epg", X25Action.EPG_SEARCH, "Wyszukiwarka programów", "Filmy, mecze i seriale w programie TV, przypomnienia.", act(X25Action.EPG_SEARCH)),
        actionTile("apps", X25Action.APPS, "Aplikacje", "Zainstalowane aplikacje.", act(X25Action.APPS)),
    )

    /** Rzędy pulpitu v2 w kolejności (puste pominięte) – ta sama reguła, co w [V2Content]. */
    private fun v2RowIds(): List<String> = listOfNotNull(
        V2_TOP10.takeIf { top10.isNotEmpty() }, V2_CINEMA.takeIf { cinema.isNotEmpty() }, V2_VOD.takeIf { !vodCat.isEmpty },
        V2_CHANNELS.takeIf { v2Channels().isNotEmpty() }, V2_CONTINUE.takeIf { videos.any { it.positionMs > 0 && !it.finished } },
        V2_STCONTINUE.takeIf { titles.isNotEmpty() }, V2_SHORTCUTS)

    /** Podgląd plakatu w hero z opóźnieniem (szybkie przewijanie nie wczytuje obrazów po drodze). */
    private fun hoverSlide(s: V2Slide) {
        v2HoverJob?.cancel()
        if (heroHover?.key == s.key) return
        v2HoverJob = lifecycleScope.launch { delay(FEATURE_DELAY_MS); heroHover = s }
    }

    /** Kolejny / poprzedni slajd ([d]); podgląd plakatu – najpierw powrót do karuzeli. */
    private fun stepHero(d: Int, n: Int) {
        v2HoverJob?.cancel()
        if (heroHover != null) { heroHover = null; return }
        if (n > 0) heroIndex = Math.floorMod(heroIndex + d, n)
    }

    /** ◄ na „Oglądaj” – poprzedni slajd, ► na „Więcej informacji” – następny (fokus zostaje na przycisku). */
    /** Liczba slajdów hero na ekranie (ustawiana przy kompozycji [V2Hero]). */
    private var heroCount = 0

    /** ◄ na „Oglądaj” – poprzedni slajd, ► na „Więcej informacji” – następny (fokus zostaje na przycisku); zwolnienie klawisza też pochłonięte. */
    private fun heroKey(e: NativeKey): Boolean {
        if (!v2 || options) return false
        val left = e.keyCode == NativeKey.KEYCODE_DPAD_LEFT && focusedNow == HERO_PLAY
        val right = e.keyCode == NativeKey.KEYCODE_DPAD_RIGHT && focusedNow == HERO_INFO
        if (!left && !right) return false
        val n = v2SlidesFor(section).size       // ze stanu (nie z ostatniej kompozycji)
        if (n < 2 && heroHover == null) return false
        if (e.action == NativeKey.ACTION_DOWN) stepHero(if (right) 1 else -1, n)
        return true
    }

    private val v2PosterW: Dp get() = if (dims.portrait) ((dims.availW - dims.gap * 2) / 3.2f).coerceIn(84.dp, 130.dp) else if (dims.compact) 86.dp else 118.dp
    private val v2HeroH: Dp get() = if (dims.portrait) 300.dp else if (dims.compact) 168.dp else 214.dp
    private val logoBrush = Brush.linearGradient(listOf(ObsidianGlass.Tokens.Focus, ObsidianGlass.Tokens.Violet))
    private val rankFill = Brush.verticalGradient(listOf(Color(0x5560A5FA), Color(0x1A6366F1)))
    private val rankStroke = Brush.verticalGradient(listOf(Color(0xFFBAE6FD), Color(0xFF60A5FA), Color(0xFF8B5CF6)))

    @Composable
    private fun V2Home() {
        val cfg = LocalConfiguration.current
        val portrait = cfg.screenWidthDp < 600 && cfg.screenHeightDp > cfg.screenWidthDp
        val side = if (portrait) 14 else if (cfg.screenHeightDp < 520) 18 else 32
        dims = Dims(compact = cfg.screenHeightDp < 520 && !portrait, narrow = cfg.screenWidthDp < 600, portrait = portrait,
            availW = (cfg.screenWidthDp - side * 2).dp)
        // tło zapamiętane – bitmapa poświaty powstaje raz (przesuwanie fokusu nie przerysowuje tła)
        val backdrop = remember { Modifier.obsidianGlassBackdrop() }
        Box(Modifier.fillMaxSize().then(backdrop)
            .focusProperties { enter = { ((pendingFocus ?: lastFocus)?.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default } }
            .focusGroup()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                V2TopBar(portrait, side.dp)
                V2Content(side.dp)
            }
            if (options) Options()
        }
        // zapasowo: element docelowy zniknął – „Oglądaj” w hero
        LaunchedEffect(pendingFocus) {
            val k = pendingFocus ?: return@LaunchedEffect
            delay(1500)
            if (pendingFocus == k) { pendingFocus = null; runCatching { requester(HERO_PLAY).requestFocus() } }
        }
    }

    // ---------------- (v2) górny pasek ----------------

    @Composable
    private fun V2TopBar(portrait: Boolean, side: Dp) {
        val vod = !vodCat.isEmpty
        // wejście fokusu w pasek (▲ z hero): ostatnia pozycja paska, inaczej „Telewizja”
        val group = Modifier.focusProperties {
            enter = { (lastTop?.takeIf { it in attached } ?: TOP_LIVE.takeIf { it in attached })?.let { requester(it) } ?: FocusRequester.Default }
        }.focusGroup()
        val tabs: @Composable () -> Unit = {
            V2Tab(TOP_HOME, TAB_HOME, section = 0) { v2TabOpen(0) }
            V2Tab(TOP_LIVE, TAB_LIVE, section = 1) { v2TabOpen(1) }
            V2Tab(TOP_STUBE, TAB_STUBE, section = 2) { v2TabOpen(2) }
            V2Tab(TOP_STREMIO, TAB_STREMIO, section = 3) { v2TabOpen(3) }
            // warunkowo, zawsze na końcu zakładek – pojawienie się / zniknięcie nie przesuwa pozostałych pigułek
            if (vod) V2Tab(TOP_VOD, TAB_VOD, section = SECTION_VOD) { v2TabOpen(SECTION_VOD) }
        }
        if (portrait) Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp).then(group)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                V2Logo(true)
                Spacer(Modifier.weight(1f))
                V2Icons(searchPill = false)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { tabs() }
        } else Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = side, end = side, top = if (dims.compact) 8.dp else 16.dp, bottom = 4.dp).then(group),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dims.compact) 6.dp else 10.dp)) {
            V2Logo(dims.compact)
            Spacer(Modifier.width(if (dims.compact) 6.dp else 16.dp))
            tabs()
            Spacer(Modifier.weight(1f))
            V2Icons(searchPill = !dims.narrow)
        }
    }

    @Composable
    private fun V2Logo(compact: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 26.dp else 30.dp).clip(RoundedCornerShape(8.dp)).background(logoBrush).padding(if (compact) 7.dp else 8.dp)) {
                Canvas(Modifier.fillMaxSize()) { ModernIcons.play(this, Color.White) }
            }
            BasicText(APP_NAME, Modifier.padding(start = 10.dp), style = ModernType.section.copy(fontSize = if (compact) 16.sp else 19.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp), maxLines = 1, softWrap = false)
        }
    }

    @Composable
    private fun V2Icons(searchPill: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dims.compact) 4.dp else 8.dp)) {
            if (searchPill) V2Tab(TOP_SEARCH, "$SEARCH_LABEL…", icon = X25Icons.SEARCH, glassRest = true, minWidth = if (dims.compact) 120.dp else 168.dp) { search() }
            else V2IconButton(TOP_SEARCH, X25Icons.SEARCH, SEARCH_LABEL) { search() }
            V2IconButton(TOP_REMINDERS, ModernIcons.BELL, "Przypomnienia") {
                startActivity(Intent(this@ModernHomeActivity, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_TAB, EpgSearchActivity.TAB_REMINDERS))
            }
            V2IconButton(TOP_SETTINGS, X25Action.SETTINGS, "Ustawienia") { X25Actions.run(this@ModernHomeActivity, X25Action(X25Action.SETTINGS)) }
            V2IconButton(TOP_PROFILE, X25Icons.PROFILE, "Profil / Moje playlisty", avatar = true) { openOptions() }
        }
    }

    /**
     * Pigułka górnego paska: spoczynek – sam tekst (pole „Szukaj” – szkło), fokus – szklane wypełnienie, niebieski tint, ramka
     * 1,5 dp `#38BDF8` i subtelna poświata ([v2PillFrame]); skala tylko w warstwie GPU.
     */
    @Composable
    private fun V2Tab(key: String, label: String, icon: String = "", glassRest: Boolean = false, minWidth: Dp = 0.dp, section: Int = -1, onClick: () -> Unit) {
        val c = LocalModern.current
        val spec = LocalObsidianGlass.current
        val focused = remember { mutableStateOf(false) }
        val h = if (dims.compact) 34.dp else 40.dp
        Row(Modifier.height(h).widthIn(min = minWidth).zIndex(if (focused.value) 1f else 0f).semantics { contentDescription = label }
            .item(key, h / 2, 1.03f, focused, frame = false, onFocus = { if (section >= 0) v2TabFocus(section) }, onClick = onClick)
            // (v2, ujednolicenie) V2PillTab: spoczynek – ciemne szkło #0E1629 65 % (pole „Szukaj” i zakładki), fokus – tint + ramka 2 dp #38BDF8 + poświata
            .v2PillTab(focused.value, section >= 0 && this@ModernHomeActivity.section == section, h / 2, c.lowPerf)      // bieżąca sekcja – aktywna pigułka
            .padding(horizontal = if (dims.compact) 12.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = if (focused.value || (section >= 0 && this@ModernHomeActivity.section == section)) Color.White else c.text.copy(alpha = if (glassRest) 0.7f else 0.86f)
            if (icon.isNotEmpty()) Canvas(Modifier.size(16.dp)) { ModernIcons.draw(this, icon, ink) }
            BasicText(label, Modifier.padding(start = if (icon.isNotEmpty()) 8.dp else 0.dp),
                style = ModernType.button.copy(color = ink, fontSize = if (dims.compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
                maxLines = 1, softWrap = false)
        }
    }

    /** Okrągły przycisk ikony górnego paska (Szukaj na wąskim ekranie, Przypomnienia, Ustawienia, Profil – awatar). */
    @Composable
    private fun V2IconButton(key: String, icon: String, label: String, avatar: Boolean = false, onClick: () -> Unit) {
        val c = LocalModern.current
        val spec = LocalObsidianGlass.current
        val focused = remember { mutableStateOf(false) }
        val s = if (dims.compact) 34.dp else 40.dp
        Box(Modifier.size(s).zIndex(if (focused.value) 1f else 0f).semantics { contentDescription = label }
            .item(key, s / 2, 1.06f, focused, frame = false, onClick = onClick)
            .then(if (focused.value) Modifier.glassSurface(s / 2, opacity = spec.opacity) else Modifier)
            .v2PillFrame(focused.value, s / 2, c.lowPerf)
            .then(if (avatar) Modifier.padding(5.dp).clip(CircleShape).background(logoBrush).padding(5.dp) else Modifier.padding(if (dims.compact) 8.dp else 10.dp))) {
            Canvas(Modifier.fillMaxSize()) { ModernIcons.draw(this, icon, if (focused.value || avatar) Color.White else c.text.copy(alpha = 0.86f)) }
        }
    }

    /** Fokus pigułki v2: tint `#2F80ED` 18 %, ramka 1,5 dp `#38BDF8` i poświata (pasma na zewnątrz – bez przykrycia szkła). */
    private fun Modifier.v2PillFrame(focused: Boolean, radius: Dp, lowPerf: Boolean): Modifier = if (!focused) this else drawWithCache {
        val r = radius.toPx(); val sw = 1.5.dp.toPx()
        val ring = ObsidianGlass.Tokens.FocusRing; val tint = ObsidianGlass.Tokens.FocusTint
        val glow = if (lowPerf) emptyList() else listOf(9.dp.toPx() to 0.06f, 5.dp.toPx() to 0.1f, 2.5.dp.toPx() to 0.16f)
        val ringStroke = Stroke(sw)
        onDrawBehind {
            glow.forEach { (g, a) -> drawRoundRect(ring.copy(alpha = a), Offset(-g / 2, -g / 2), Size(size.width + g, size.height + g), CornerRadius(r + g / 2), style = Stroke(g)) }
            drawRoundRect(tint, cornerRadius = CornerRadius(r))
            drawRoundRect(ring, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2), style = ringStroke)
        }
    }

    // ---------------- (v2) treść: hero i rzędy ----------------

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun V2Content(side: Dp) {
        val tick = epgEpoch
        val sec = section
        // wszystkie modele liczone tylko przy zmianie danych / sekcji (przesuwanie fokusu nie przebudowuje rzędów ani obrazów)
        val slides = remember(sec, top10, top10Spec, cinema, vodCat, recent, favs, videos, titles) { v2SlidesFor(sec) }
        val topPosters = remember(top10, top10Spec) { val p = top10Spec?.platform?.name.orEmpty(); top10.mapIndexed { i, m -> metaPoster(V2_TOP10, m, i + 1, metaSlide(m, top10Tech(i, p), "TOP 10")) } }
        val cinemaPosters = remember(cinema) { cinema.map { m -> metaPoster(V2_CINEMA, m, 0, metaSlide(m, CINEMA_TECH, StremioUi.IN_CINEMAS_BADGE)) } }
        val vod = remember(vodCat) { vodPosters() }
        composedTop10 = topPosters.size; contentComps++      // testy: rzędy w kompozycji
        // (v2) sekcje: Start – przegląd; Telewizja / STube / Stremio / VOD – rzędy i funkcje danej sekcji (podgląd na żywo z paska)
        val posterRows: List<Triple<String, String, List<V2Poster>>> = when (sec) {
            0 -> listOf(Triple(V2_TOP10, top10Spec?.title ?: "Top 10", topPosters), Triple(V2_CINEMA, ROW_CINEMA, cinemaPosters), Triple(V2_VOD, ROW_VOD, vod))
            3 -> listOf(Triple(V2_TOP10, top10Spec?.title ?: "Top 10", topPosters), Triple(V2_CINEMA, ROW_CINEMA, cinemaPosters))
            SECTION_VOD -> remember(vodCat) { vodSectionRows() }
            else -> emptyList()
        }.filter { it.third.isNotEmpty() }
        val shelves = remember(sec, recent, favs, all, videos, titles, tick, stMovies, stSeries) {
            when (sec) {
                0 -> listOf(Shelf(V2_CHANNELS, ROW_CHANNELS, v2Channels().map { channelTile(V2_CHANNELS, it) }),
                    Shelf(V2_CONTINUE, ROW_CONTINUE, videos.filter { it.positionMs > 0 && !it.finished }.map { videoTile(it) }),
                    Shelf(V2_STCONTINUE, ROW_STCONTINUE, titles.map { titleTile(it) }),
                    Shelf(V2_SHORTCUTS, ROW_SHORTCUTS, v2Shortcuts()))
                SECTION_VOD -> listOf(Shelf("vodact", "VOD", listOf(actionTile("vodopen", ModernIcons.PLAYLIST, "Katalog VOD", "Filmy i seriale z Twoich list M3U – gatunki, sezony i odcinki.") {
                    startActivity(StremioActivity.vodIntent(this)) })))
                else -> shelves(sec)
            }.filter { it.tiles.isNotEmpty() }
        }
        val state = remember(sec) { androidx.compose.foundation.lazy.LazyListState() }
        v2List = state
        v2FirstRow = posterRows.firstOrNull()?.first ?: shelves.firstOrNull()?.id
        // WSTECZ z treści – lista na górę (hero widoczne pod paskiem)
        LaunchedEffect(topTick) { if (topTick > 0) state.animateScrollToItem(0) }
        val spec = remember { ShelfScroll(Ui.dp(if (dims.compact) 28 else 36).toFloat(), Ui.dp(10).toFloat()) }
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 56.dp)) {
                item(key = "hero$sec", contentType = "hero") { V2Hero(slides, side) }
                posterRows.forEach { (id, title, posters) -> item(key = "$sec/$id", contentType = "posters") { V2PosterRow(id, title, posters, side, ranked = id == V2_TOP10) } }
                // kanały (pasek postępu EPG), kontynuacja, skróty – te same kafelki co dotąd (Card), rząd od lewej krawędzi treści
                items(shelves, key = { "$sec/${it.id}" }, contentType = { "shelf" }) { s -> Box(Modifier.padding(start = (side - 18.dp).coerceAtLeast(0.dp))) { ShelfRow(s) } }
            }
        }
    }

    /** Szeroki szklany baner hero (24 dp): obraz po prawej z winietą, tytuł (rok), metadane, opis, OGLĄDAJ / WIĘCEJ INFORMACJI, kropki. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun V2Hero(slides: List<V2Slide>, side: Dp) {
        val heroBiv = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        val heroBivScope = androidx.compose.runtime.rememberCoroutineScope()
        val c = LocalModern.current
        val spec = LocalObsidianGlass.current
        val n = slides.size
        val idx = if (n == 0) 0 else Math.floorMod(heroIndex, n)
        heroCount = n
        val hover = heroHover
        val s = hover ?: slides.getOrNull(idx) ?: return
        val portrait = dims.portrait
        val shape = RoundedCornerShape(24.dp)
        val byKey = remember { HashMap<String, V2Slide>() }
        if (byKey.size > 32) byKey.clear()
        byKey[s.key] = s
        val fade = remember(portrait) {
            if (portrait) Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x550A1020), 1f to Color(0xF20A1020))
            else Brush.horizontalGradient(0f to Color(0xF00A1020), 0.36f to Color(0xD90A1020), 0.62f to Color(0x330A1020), 1f to Color.Transparent)
        }
        val bottomFade = remember { Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x990A1020)) }
        val anim = !c.lowPerf && spec.animations
        Box(Modifier.padding(horizontal = side, vertical = if (dims.compact) 4.dp else 8.dp).fillMaxWidth().height(v2HeroH)
            .glassSurface(24.dp, opacity = spec.opacity).clip(shape)
            .pointerInput(n) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = { if (kotlin.math.abs(acc) > 48.dp.toPx()) stepHero(if (acc < 0) 1 else -1, n) }) { _, d -> acc += d }
            }
            // (v2) kursor w banerze: widoczny cały baner (lista na samej górze) – przewinięcie za fokusem pokazywało tylko przycisk
            .bringIntoViewRequester(heroBiv)
            .onFocusChanged { if (it.hasFocus) heroBivScope.launch { kotlinx.coroutines.delay(HERO_SCROLL_DELAY_MS); runCatching { heroBiv.bringIntoView() } } }
            // ▲ ▼ do banera: zawsze „Oglądaj”
            .focusProperties { enter = { if (HERO_PLAY in attached) requester(HERO_PLAY) else FocusRequester.Default } }
            .focusGroup()) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (portrait) 1f else 0.64f)) {
                Crossfade(s.key, animationSpec = tween(if (anim) 350 else 0), label = "v2hero") { k -> byKey[k]?.let { V2HeroArt(it) } }
                if (!portrait && hover == null && n > 1) V2Dots(n, idx, anim, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            }
            Box(Modifier.fillMaxSize().background(fade))
            if (!portrait) Box(Modifier.fillMaxSize().background(bottomFade))
            if (portrait && hover == null && n > 1) V2Dots(n, idx, anim, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp))
            if (s.tech.isNotEmpty()) Row(Modifier.align(Alignment.TopEnd).padding(14.dp).glassSurface(12.dp, opacity = spec.opacity)
                .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (s.logo) c.live else ObsidianGlass.Tokens.FocusRing))
                BasicText(s.tech, Modifier.padding(start = 6.dp), style = ModernType.small.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp), maxLines = 1)
            }
            Column(Modifier.align(if (portrait) Alignment.BottomStart else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(if (portrait) 1f else 0.56f)
                .padding(start = if (dims.compact) 18.dp else 26.dp, end = 12.dp, top = if (dims.compact) 12.dp else 20.dp, bottom = if (dims.compact) 12.dp else 20.dp),
                verticalArrangement = if (portrait) Arrangement.Bottom else Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f, fill = !portrait).clipToBounds(), verticalArrangement = if (portrait) Arrangement.Bottom else Arrangement.Top) {
                    if (s.badge.isNotEmpty() && !dims.compact) BasicText(s.badge, Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (s.badge == "NA ŻYWO") c.live else ObsidianGlass.Tokens.Focus).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ModernType.small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp))
                    BasicText(s.title + if (s.year.isNotBlank()) " (${s.year})" else "", style = ModernType.hero.copy(fontSize = if (dims.compact) 22.sp else if (portrait) 24.sp else 30.sp),
                        maxLines = if (dims.compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (s.meta.isNotEmpty()) BasicText(s.meta.joinToString("  •  "), Modifier.padding(top = 4.dp),
                        style = ModernType.meta.copy(color = c.text.copy(alpha = 0.8f), fontSize = if (dims.compact) 12.sp else 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.desc.isNotBlank()) BasicText(s.desc, Modifier.padding(top = 6.dp).weight(1f, fill = false),
                        style = ModernType.body.copy(color = c.text.copy(alpha = 0.86f), fontSize = if (dims.compact) 12.sp else 14.sp),
                        maxLines = if (dims.compact || portrait) 2 else 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = if (dims.compact) 8.dp else 12.dp).wrapContentWidth(Alignment.Start, unbounded = true)
                    , horizontalArrangement = Arrangement.spacedBy(if (dims.narrow) 8.dp else 12.dp)) {
                    PillButton(HERO_PLAY, s.playLabel.ifBlank { HERO_WATCH }, primary = true, icon = "play") { s.play() }
                    PillButton(HERO_INFO, if (dims.narrow) "Więcej" else HERO_MORE, primary = false, icon = ModernIcons.INFO) { s.info() }
                }
            }
        }
        // automatyczna zmiana slajdu tylko, gdy kursor nie jest w hero i nie ma podglądu plakatu (tryb słabszych / bez animacji – wył.)
        // (7.28+) tylko na widocznym pulpicie – pod Live TV slajdy (i pobieranie ich teł) stoją
        LaunchedEffect(n, idx, hover == null, screenStarted) {
            if (n < 2 || hover != null || !anim || !screenStarted) return@LaunchedEffect
            while (true) {
                delay(HERO_AUTO_MS)
                if (focusedNow?.startsWith("h:") != true && heroHover == null) { heroIndex = (idx + 1) % n; break }
            }
        }
    }

    /** Kropki paginacji (aktywna – szersza, `#38BDF8`); dotknięcie – ten slajd. */
    @Composable
    private fun V2Dots(n: Int, idx: Int, anim: Boolean, modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            repeat(n) { i ->
                val w by animateDpAsState(if (i == idx) 18.dp else 6.dp, tween(if (anim) 220 else 0), label = "dot")
                Box(Modifier.pointerInput(i) { detectTapGestures { v2HoverJob?.cancel(); heroHover = null; heroIndex = i } }.padding(horizontal = 3.dp, vertical = 6.dp)) {
                    Box(Modifier.size(w, 6.dp).clip(CircleShape).background(if (i == idx) ObsidianGlass.Tokens.FocusRing else Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }

    @Composable
    private fun V2HeroArt(s: V2Slide) {
        val ctx = LocalContext.current
        when {
            s.logo -> Box(Modifier.fillMaxSize().background(remember(s.key) { Brush.linearGradient(channelGradient(s.meta.firstOrNull() ?: s.title)) })) {
                if (s.image.isNotEmpty()) TileImage(s.image, "", Modifier.align(Alignment.Center).fillMaxWidth(0.4f).fillMaxHeight(0.5f), ContentScale.Fit,
                    ModernType.hero, sizePx = Ui.dp(280))
            }
            s.image.isEmpty() -> Box(Modifier.fillMaxSize().background(remember {
                Brush.linearGradient(listOf(ObsidianGlass.Tokens.Focus.copy(alpha = 0.5f), ObsidianGlass.Tokens.Violet.copy(alpha = 0.35f), Color.Transparent)) }))
            else -> {
                // dekodowanie w 60 % szerokości ekranu (obraz pod winietą) – mało pamięci, stały klucz (bez ponownego dekodowania)
                val w = (resources.displayMetrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)
                val req = remember(s.image) { coil.request.ImageRequest.Builder(ctx).data(s.image).crossfade(false).precision(coil.size.Precision.INEXACT)
                    .memoryCacheKey("${s.image}@hero").size(w, (w * 9 / 16).coerceAtLeast(1)).build() }
                coil.compose.AsyncImage(req, null, Modifier.fillMaxSize().clipToBounds(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            }
        }
    }

    /** Rząd plakatów v2 (nagłówek z kropką, stabilne klucze, ◄ ► w rzędzie, wejście ▲ ▼ – ostatni plakat rzędu). */
    @Composable
    private fun V2PosterRow(id: String, title: String, posters: List<V2Poster>, side: Dp, ranked: Boolean = false) {
        val c = LocalModern.current
        Column(Modifier.fillMaxWidth().raiseOnFocus().padding(top = if (dims.compact) 4.dp else 8.dp)) {
            Row(Modifier.padding(start = side + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (ranked) ObsidianGlass.Tokens.FocusRing else c.live))
                BasicText(title, Modifier.padding(start = 8.dp), style = ModernType.row.copy(fontSize = if (dims.compact) 14.sp else 17.sp, letterSpacing = 0.2.sp), maxLines = 1)
            }
            SmoothScrolling {
                LazyRow(Modifier.fillMaxWidth().rowEntry("r:$id|").focusGroup(),
                    contentPadding = PaddingValues(start = side, end = 48.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (ranked) 6.dp else dims.gap)) {
                    items(posters, key = { it.key }, contentType = { if (ranked) "rank" else "poster" }) { p -> V2PosterCard(id, p, ranked) }
                }
            }
        }
    }

    /**
     * Plakat w szklanej ramce (V2GlassSurface: `#10192E`, krawędź 1,5 dp, 14 dp), ocena ★ w prawym górnym rogu; fokus – ramka 2 dp
     * `#38BDF8`, poświata i powiększenie 1,04 (warstwa GPU). Top 10: duży półprzezroczysty, świetlisty numer „#N” zachodzący z lewej.
     */
    @Composable
    private fun V2PosterCard(row: String, p: V2Poster, ranked: Boolean) {
        val c = LocalModern.current
        val spec = LocalObsidianGlass.current
        val key = "r:$row|${p.key}"
        val focused = remember { mutableStateOf(false) }
        val w = v2PosterW; val h = w * 1.5f
        val r = 14.dp
        val poster: @Composable (Modifier) -> Unit = { m ->
            Box(m.size(w, h).item(key, r, 1.04f, focused, onFocus = { hoverSlide(p.slide) }, onClick = p.open)
                .glassSurface(r, opacity = spec.opacity).padding(3.dp).clip(RoundedCornerShape(r - 2.dp))) {
                TileImage(p.image, if (p.image.isBlank()) p.title else initialsOf(p.title), Modifier.fillMaxSize().padding(if (p.image.isBlank()) 8.dp else 0.dp),
                    ContentScale.Crop, ModernType.tile.copy(color = c.text2, fontSize = 13.sp), sizePx = Ui.dp(w.value.toInt()), heightPx = Ui.dp(h.value.toInt()))
                if (p.rating.isNotBlank()) Row(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC070B14))
                    .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("★", style = ModernType.small.copy(color = Color(0xFFFBBF24), fontSize = 10.sp))
                    BasicText(p.rating, Modifier.padding(start = 3.dp), style = ModernType.small.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
                }
                if (p.badge.isNotBlank()) BasicText(p.badge, Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp))
                    .background(ObsidianGlass.Tokens.Focus.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp),
                    style = ModernType.small.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.4.sp))
            }
        }
        if (!ranked) { poster(Modifier.zIndex(if (focused.value) 1f else 0f)); return }
        val numW = w * 0.62f
        Box(Modifier.width(numW + w * 0.78f).height(h).zIndex(if (focused.value) 1f else 0f)) {
            poster(Modifier.align(Alignment.CenterEnd))
            RankNumber(p.rank, Modifier.align(Alignment.BottomStart).offset(y = h * 0.06f), h)
        }
    }

    /** Numer „#N” Top 10: półprzezroczyste wypełnienie + gradientowy kontur z poświatą `#38BDF8` (style zapamiętane). */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    @Composable
    private fun RankNumber(rank: Int, modifier: Modifier, posterH: Dp) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val size = (posterH.value * (if (rank >= 10) 0.34f else 0.44f) / density.fontScale).sp
        val styles = remember(size, density) {
            val stroke = with(density) { 2.5.dp.toPx() }
            val glow = with(density) { 14.dp.toPx() }
            TextStyle(brush = rankFill, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-2).sp) to
                TextStyle(brush = rankStroke, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, drawStyle = Stroke(stroke),
                    shadow = Shadow(ObsidianGlass.Tokens.FocusRing.copy(alpha = 0.8f), blurRadius = glow))
        }
        Box(modifier) {
            BasicText("#$rank", style = styles.first, maxLines = 1, softWrap = false)
            BasicText("#$rank", style = styles.second, maxLines = 1, softWrap = false)
        }
    }

    // ---------------- panel opcji (nakładka z prawej) ----------------

    @Composable
    private fun Options() {
        val c = LocalModern.current
        val first = remember { FocusRequester() }
        val opts = remember(optionsVersion) { optionItems() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { closeOptions() } }) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (dims.narrow) 0.9f else 0.38f)
                .then(if (v2) Modifier.padding(10.dp).v2GlassDialog().statusBarsPadding().navigationBarsPadding()   // (v2) V2GlassDialog – pływający panel z prawej
                    else Modifier.background(Brush.horizontalGradient(listOf(c.bg.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.97f)))))
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
            // (v2) fokus – tint + błękitna ramka + poświata; spoczynek – szkło
            .then(if (v2) Modifier.v2PillTab(focused, false, 16.dp, c.lowPerf) else Modifier)   // (v2) V2OptionRow – karta 16 dp
            .clip(RoundedCornerShape(if (v2) 16.dp else 30.dp))
            .background(if (v2) Color.Transparent else if (focused) c.accent else Color.White.copy(alpha = 0.06f))
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
        /** (v2) Zwłoka końcowego przewinięcia listy na górę po powrocie kursora na baner (ms). */
        const val HERO_SCROLL_DELAY_MS = 60L
        /** (v2) Najdłuższe oczekiwanie na koniec przewijania za fokusem (kroki po 16 ms). */
        const val HERO_SCROLL_WAIT_STEPS = 60
        const val HERO_INFO = "h:info"
        const val HERO_GUIDE = "h:guide"
        /** Opóźnienie zmiany hero po zaznaczeniu kafelka (szybkie przewijanie nie wczytuje obrazów po drodze). */
        const val FEATURE_DELAY_MS = 300L
        /** (7.6) Przejście między sekcjami ekranu głównego. */
        const val SECTION_ANIM_MS = 180
        /** (7.7) Podgląd sekcji po zatrzymaniu kursora na pasku. */
        const val PREVIEW_DELAY_MS = 220L

        /** (v2) Otwórz od razu panel „Profil i opcje” (ikona profilu w belce modułów). */
        const val EXTRA_OPTIONS = "open_options"

        // ---- (v2) pulpit START Nowoczesnego v2 ([V2Home]) ----
        /** Klucze fokusu górnego paska (`t:*`). */
        const val TOP_PREFIX = "t:"
        /** (v2) Sekcja VOD pulpitu (Start 0, Telewizja 1, STube 2, Stremio 3). */
        const val SECTION_VOD = 5; const val TOP_HOME = "t:home"; const val TAB_HOME = "Start"
        const val TOP_LIVE = "t:live"; const val TOP_STUBE = "t:stube"; const val TOP_STREMIO = "t:stremio"; const val TOP_VOD = "t:vod"
        const val TOP_SEARCH = "t:search"; const val TOP_REMINDERS = "t:reminders"; const val TOP_SETTINGS = "t:settings"; const val TOP_PROFILE = "t:profile"
        const val APP_NAME = "D-iptv Player"
        const val TAB_LIVE = "Telewizja"; const val TAB_STUBE = "STube"; const val TAB_STREMIO = "Stremio"; const val TAB_VOD = "VOD"
        const val SEARCH_LABEL = "Szukaj"
        const val HERO_WATCH = "Oglądaj"; const val HERO_MORE = "Więcej informacji"
        const val ROW_CINEMA = "Obecnie w kinach"; const val ROW_VOD = "VOD z Twojej listy"; const val ROW_CHANNELS = "Kanały TV"
        const val ROW_SHORTCUTS = "Na skróty"; const val ROW_CONTINUE = "Kontynuuj oglądanie"; const val ROW_STCONTINUE = "Filmy i seriale"
        /** Identyfikatory rzędów v2 (klucze fokusu `r:<id>|…`). */
        const val V2_TOP10 = "top10"; const val V2_CINEMA = "cinema"; const val V2_VOD = "vod"; const val V2_CHANNELS = "channels"
        const val V2_SHORTCUTS = "shortcuts"; const val V2_CONTINUE = "continue"; const val V2_STCONTINUE = "stcontinue"
        /** Liczba slajdów hero (kropki paginacji) i czas automatycznej zmiany slajdu (gdy kursor nie jest w hero). */
        const val HERO_SLIDES = 6
        const val HERO_AUTO_MS = 9_000L
        /** Pozycje w rzędzie „VOD z Twojej listy”. */
        const val VOD_ROW_MAX = 20
        /** Pigułki techniczne hero (źródło slajdu) i karta Ustawień „Nagrywanie” (skrót). */
        const val VOD_TECH = "Lista M3U • VOD"; const val CINEMA_TECH = "W kinach • TMDB"
        const val REC_SETTINGS_TAB = 7
        /** „Obecnie w kinach” – pamięć wspólna (TMDB, zapas – Cinemeta), ważna 3 h. */
        const val CINEMA_TTL_MS = 3 * 3_600_000L
        @Volatile internal var cinemaCache: Pair<Long, List<pl.iptv.tv.data.StremioMetaPreview>>? = null
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

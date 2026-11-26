package pl.iptv.tv.ui

import coil.load
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioStore

/**
 * Zakładka Stremio: logowanie (e-mail i hasło albo klucz API), potem tablica jak w Stremio – „Kontynuuj oglądanie”,
 * katalogi włączonych wtyczek, wyszukiwarka (plakaty filmów i seriali w osobnych rzędach, opis zaznaczonego tytułu u góry).
 */
class StremioActivity : BaseActivity() {
    /** (7.22) Telefon pionowo: „Stremio” albo „Do obejrzenia” (Biblioteka) w dolnym pasku. */
    override val portraitTab get() = if (filter == FILTER_LIBRARY) MobilePortraitScaffold.Tab.WATCHLIST else MobilePortraitScaffold.Tab.STREMIO
    override val pausesLiveTv = true
    companion object {
        /** Odpytywanie potwierdzenia kodu QR i ważność kodu (nowy kod po czasie). */
        var LINK_POLL_MS = 3_000L
        var LINK_TTL_MS = 5 * 60_000L
        /** Wyszukiwanie od razu po otwarciu (pole wyszukiwania na ekranie głównym PS5). */
        const val EXTRA_QUERY = "query"
        /** (7.22) Zakładka startowa ([FILTER_ALL]…[FILTER_LIBRARY]) – dolny pasek telefonu w pionie. */
        const val EXTRA_FILTER = "filter"
        const val IN_CINEMAS = "Obecnie w kinach"
        /** (7.22) Pionowa karta wyróżnionego tytułu (tag) i jej przyciski. */
        const val HERO_TAG = "portrait_hero"
        const val HERO_PLAY = "Odtwórz"
        const val HERO_WATCHLIST = "+ Do obejrzenia"
        /** (7.23) Karta banerowa na Odkrywaj – „Kontynuuj oglądanie”: przycisk wznowienia, liczba pozycji w karuzeli. */
        const val HERO_RESUME = "▶ Wznów"
        const val HERO_MAX = 8
        const val HERO_CONTINUE_LABEL = "KONTYNUUJ OGLĄDANIE"
        const val HERO_SUGGEST_MOVIES = "PROPONOWANE FILMY"
        const val HERO_SUGGEST_SERIES = "PROPONOWANE SERIALE"
        /** (7.17) Filtry tablicy (pigułki u góry w „Obsidian Stream”). */
        // (7.17) kolejność zakładek: Odkrywaj (domyślna), Filmy, Seriale, Biblioteka
        const val FILTER_ALL = 0; const val FILTER_MOVIES = 1; const val FILTER_SERIES = 2; const val FILTER_LIBRARY = 3
        val FILTER_NAMES = listOf("Odkrywaj", "Filmy", "Seriale", "Biblioteka")
        /** (7.20) Lista „Do obejrzenia” ([pl.iptv.tv.data.StremioLibrary]) – rząd w Bibliotece (dawniej „Moja biblioteka”). */
        const val LIBRARY_ROW = "Do obejrzenia"
        const val WATCHLIST_REMOVE = "Usuń z listy do obejrzenia"
        /** Menu plakatu czeka na metadane (zwiastun) najwyżej tyle. */
        const val TITLE_MENU_META_MS = 4_000L
        const val CONTINUE_ROW = "Kontynuuj oglądanie"
        /** (7.23) Filmy i seriale z listy M3U ([pl.iptv.tv.data.VodLibrary]) – także bez konta Stremio. */
        const val M3U_MOVIES_ROW = "Filmy z Twojej listy"; const val M3U_SERIES_ROW = "Seriale z Twojej listy"
        const val M3U_GENRE_TAG = "m3u_genre:"; const val M3U_GENRES_TAG = "m3u_genres"
        const val M3U_EMPTY_GENRE = "Brak pozycji w tym gatunku"
        const val WATCHED_ROW = "Obejrzane"
        /** (7.17) Katalog techniczny wtyczki (nazwa, id wtyczki i katalogu, adres) – bez rzędu na tablicy. */
        fun isHiddenCatalog(a: StremioAddon, c: StremioAddon.Catalog): Boolean =
            HIDDEN_CATALOGS.containsMatchIn(listOf(a.name, a.id, c.name, c.id, a.transportUrl.substringBefore("?").substringAfter("://").substringBefore("/")).joinToString(" "))
        /** Katalogi usługowych wtyczek, których nie pokazujemy na tablicy (AIOStreams, ElfHosted, Store RD, Store PM). */
        /**
         * Katalogi usługowych wtyczek pomijane na tablicy (Odkrywaj / Filmy / Seriale) i w wynikach wyszukiwania – wtyczki
         * dalej podają źródła (`stream`) w opisie tytułu. (7.17) także Torrentio i debrid (RealDebrid, „RD”).
         */
        val HIDDEN_CATALOGS = Regex("(?i)aiostreams|elfhosted|store[\\s_-]*(rd|pm)\\b|torrentio|real[\\s_-]?debrid|(?<![a-z0-9])rd(?![a-z0-9])")
    }
    override val x25Root = true
    override val miniPlayer = true

    lateinit var rowsBox: LinearLayout; private set
    private lateinit var scroll: RowsScroll

    /**
     * Przewijanie rzędów: na tablicy ([auto] = false) rząd zaznaczonego plakatu ustawiany jest zawsze u góry (onFocus),
     * bez własnego przewijania ScrollView do zaznaczonego elementu (oba naraz – ucięty rząd i przeskakiwanie).
     */
    class RowsScroll(c: android.content.Context) : ScrollView(c) {
        var auto = true
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int = if (auto) super.computeScrollDeltaToGetChildRectOnScreen(rect) else 0
    }
    private lateinit var query: EditText
    private lateinit var searchBar: LinearLayout
    private lateinit var account: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoDesc: TextView
    /** (7.20) Tytuł, którego opis jest w panelu (wczytanie opisu po chwili trafia tylko do niego). */
    private var infoId = ""
    private lateinit var infoBox: LinearLayout
    private val jobs = mutableListOf<Job>()
    private var focusJob: Job? = null
    var searchQuery: String? = null; private set
    /** Tytuły rzędów na ekranie (testy). */
    val rowTitles = mutableListOf<String>()
    var loginShown = false; private set

    // ---------------- (7.17) „Obsidian Stream” ----------------

    /** Filtr tablicy: [FILTER_MOVIES] Filmy, [FILTER_SERIES] Seriale, [FILTER_ALL] Odkrywaj (wszystkie katalogi), [FILTER_LIBRARY] Biblioteka. */
    var filter = FILTER_ALL; private set
    /** (7.22) Telefon pionowo (9:16): nagłówek kompaktowy, karta wyróżnionego tytułu, pigułki filtrów w każdym motywie. */
    private val portrait by lazy { portraitPhone }
    /** „Obsidian Stream” w poziomie / na TV (w pionie telefonu – wspólny układ pionowy z kolorami motywu). */
    private val obsidian by lazy { pl.iptv.tv.ui.theme.ObsidianViews.active && !portrait }
    private var filterBar: LinearLayout? = null
    private val filterPills = mutableListOf<TextView>()
    private var navRail: pl.iptv.tv.ui.theme.AppNavigationRailViews? = null
    private lateinit var railBox: LinearLayout
    /** Pigułki filtrów (testy). */
    val filterViews: List<TextView> get() = filterPills

    /** Zmiana filtru: tablica od nowa, kursor na wybranej pigułce. */
    fun setFilter(f: Int) {
        if (filter == f && searchQuery == null) return
        filter = f
        filterPills.forEachIndexed { i, p -> p.isSelected = i == f }
        // (7.22) dolny pasek: Biblioteka = „Do obejrzenia”, pozostałe zakładki = „Stremio”
        portraitNav?.let { nav -> val sel = portraitTab.name; for (i in 0 until nav.childCount) nav.getChildAt(i).let { it.isSelected = it.tag == MobilePortraitScaffold.TAB_TAG + sel } }
        if (searchQuery != null) { searchQuery = null; query.setText("") }
        build(null)
    }

    /** (7.22) Dolny pasek: Stremio ↔ Do obejrzenia na tym samym ekranie. */
    override fun onPortraitTab(t: MobilePortraitScaffold.Tab) {
        if (t == MobilePortraitScaffold.Tab.WATCHLIST) setFilter(FILTER_LIBRARY) else if (t == MobilePortraitScaffold.Tab.STREMIO) setFilter(FILTER_ALL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(EXTRA_FILTER, -1).takeIf { it in FILTER_ALL..FILTER_LIBRARY }?.let { if (!loginShown) setFilter(it) }
    }

    /** Pigułki filtrów w pionie telefonu – kolory aktywnego motywu ([MobilePortraitScaffold.chip]). */
    private fun buildPortraitFilters(): LinearLayout {
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, Ui.dp(6), 0, Ui.dp(6)) }
        FILTER_NAMES.forEachIndexed { i, name ->
            val p = MobilePortraitScaffold.chip(this, name, i == filter) { setFilter(i) }
            filterPills += p
            bar.addView(p, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) })
        }
        return bar
    }

    /** Pigułki filtrów z fokusem (akcent przy zaznaczeniu, wybrany filtr – obwódka). */
    private fun buildFilterBar(): LinearLayout {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        FILTER_NAMES.forEachIndexed { i, name ->
            val p = TextView(this).apply {
                text = name; textSize = 15f; tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(Ui.dp(20), Ui.dp(9), Ui.dp(20), Ui.dp(9))
                background = O.pill(rest = 0x1FFFFFFF); setTextColor(O.pillText()); isSelected = i == filter
                setOnClickListener { setFilter(i) }
            }
            filterPills += p
            bar.addView(p, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
        }
        return bar
    }

    /**
     * Wspólny pasek nawigacji nad treścią (72 → 240 dp ze scrimem): konto, szukaj, wtyczki, odśwież, zamknij – te same przyciski,
     * co dawniej w nagłówku (podpisy w opisie dostępności, widoczne po rozwinięciu paska).
     */
    private fun installRail() {
        val scrim = View(this)
        val at = rootFrame.childCount
        rootFrame.addView(scrim, at, android.widget.FrameLayout.LayoutParams(-1, -1))
        railBox.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF5080A0F.toInt(), 0xE0121722.toInt()))
        railBox.setPadding(Ui.dp(12), Ui.dp(18), Ui.dp(12), Ui.dp(18))
        rootFrame.addView(railBox, at + 1, android.widget.FrameLayout.LayoutParams(Ui.dp(72), -1))
        navRail = pl.iptv.tv.ui.theme.AppNavigationRailViews(railBox, scrim,
            items = { (0 until railBox.childCount).mapNotNull { railBox.getChildAt(it) as? TextView } },
            label = { (it.contentDescription ?: "").toString() }, lowPerf = { Prefs.lowPerf }).also { it.attach() }
    }

    override fun onDestroy() { navRail?.detach(); super.onDestroy() }
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (7.22) zakładka z dolnego paska telefonu (Stremio / Do obejrzenia)
        intent.getIntExtra(EXTRA_FILTER, -1).takeIf { it in FILTER_ALL..FILTER_LIBRARY }?.let { filter = it }
        setThemedContent(build())
        if (obsidian) installRail()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (searchQuery != null) { query.setText(""); search("") } else leaveScreen() }
        })
        // (7.23) filmy i seriale z listy M3U – dostępne bez konta: ekran logowania nie blokuje tablicy, gdy lista ma treści VOD
        if (!StremioAccount.loggedIn && !Prefs.stremioGuest && !pl.iptv.tv.data.VodLibrary.hasContent) {
            showLogin()
            lifecycleScope.launch { if (!pl.iptv.tv.data.VodLibrary.current().isEmpty && loginShown && !StremioAccount.loggedIn) showBoard() }
        } else {
            intent.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { q -> searchQuery = q; query.setText(q) }
            showBoard()
        }
    }

    override fun onResume() {
        super.onResume()
        // powrót z odtwarzacza / wtyczek: nowy postęp i stan wtyczek
        if (::rowsBox.isInitialized && !loginShown && rowsBox.childCount > 0) refreshContinue()
        // (7.17) biblioteka zmieniona na ekranie szczegółów – rząd „Moja biblioteka” od razu aktualny
        if (resumedOnce && ::rowsBox.isInitialized && !loginShown && filter == FILTER_LIBRARY && searchQuery == null) {
            val r = rows.firstOrNull { it.title == LIBRARY_ROW }
            if (r != null) fillLibrary(r) else if (pl.iptv.tv.data.StremioLibrary.all().isNotEmpty()) build(null)
        }
        resumedOnce = true
    }

    /**
     * (7.22) Stremio w pionie telefonu: u góry „Stremio” + ikony (wtyczki, odśwież, klucz TMDB, konto), pod spodem pole
     * wyszukiwania na całą szerokość (klawiatura systemowa), pigułki filtrów; dalej przewijana kolumna: karta wyróżnionego
     * tytułu ([showPortraitHero]) i wszystkie rzędy. Bez bocznego paska i bez przycisku zamknięcia (dolny pasek nawigacji).
     */
    private fun buildPortrait(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), 0) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val t = runCatching { ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top }.getOrDefault(0)
            v.setPadding(v.paddingLeft, Ui.dp(10) + t, v.paddingRight, 0); ins
        }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(StremioUi.text(this, "Stremio", 22f, "accent", bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        fun icon(kind: String, desc: String, onClick: () -> Unit) = StremioUi.button(this, "", kind, onClick).apply { contentDescription = desc }
        header.addView(icon(PlayerIcon.PLUG, "Wtyczki") { startActivity(Intent(this, StremioAddonsActivity::class.java)) })
        header.addView(icon(PlayerIcon.REFRESH, "Odśwież wtyczki") { refreshAddons() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(4) })
        header.addView(icon(PlayerIcon.STAR, TmdbKeyUi.TITLE) { TmdbKeyUi.show(this, onTop10Changed = { applyTop10Selection() }) { build(searchQuery) } },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(4) })
        account = icon(PlayerIcon.USER, "Konto") { accountMenu() }
        header.addView(account, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(4) })
        root.addView(header)
        searchBar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        query = EditText(this).apply {
            hint = "Szukaj filmów i seriali…"; tag = "field"; setSingleLine(); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10))
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(text.toString()); true } else false }
        }
        searchBar.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
        searchBar.addView(icon(PlayerIcon.SEARCH, "Szukaj") { if (query.text.isBlank()) showKeyboard() else search(query.text.toString()) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(6) })
        root.addView(searchBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        filterBar = buildPortraitFilters()
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(filterBar) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(4) })
        // opis zaznaczonego tytułu – w pionie zastępuje go karta wyróżnionego tytułu (pola zostają dla wspólnej logiki)
        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        infoTitle = StremioUi.text(this, "", 18f, bold = true, lines = 1); infoMeta = StremioUi.text(this, "", 13f, "accent", lines = 1); infoDesc = StremioUi.text(this, "", 13f, "text2", lines = 2)
        infoBox.addView(infoTitle); infoBox.addView(infoMeta); infoBox.addView(infoDesc)
        root.addView(infoBox)
        scroll = RowsScroll(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(6), 0, Ui.dp(16)) }
        scroll.addView(rowsBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    /**
     * (7.23) Pozycja karty banerowej: tytuł ([tile] – ten sam kafelek co w rzędach, „Odtwórz” = [open]) i – na Odkrywaj –
     * postęp „Kontynuuj oglądanie” ([progress]; „▶ Wznów” od razu wznawia odtwarzanie).
     */
    data class HeroItem(val tile: StremioTile, val progress: pl.iptv.tv.data.StremioProgress? = null)

    /** Pozycje karty banerowej (karuzela) i bieżąca. */
    var heroItems: List<HeroItem> = emptyList(); private set
    var heroIndex = 0; private set
    /** (7.22) Wyróżniony tytuł na karcie u góry (testy). */
    val heroMeta: StremioMetaPreview? get() = heroItems.getOrNull(heroIndex)?.tile?.meta
    private var heroCard: FrameLayout? = null
    private var heroImg: ImageView? = null
    private var heroTitle: TextView? = null
    private var heroLine: TextView? = null
    private var heroLabel: TextView? = null
    private var heroCounter: TextView? = null
    private var heroPlay: TextView? = null
    private var heroBar: android.widget.ProgressBar? = null
    private var heroNav: View? = null

    /** Karta banerowa możliwa: telefon pionowo, tablica (nie wyszukiwanie, nie Biblioteka). */
    private val heroAllowed get() = portrait && searchQuery == null && filter != FILTER_LIBRARY

    /**
     * (7.23) Filmy / Seriale: propozycje tego typu z pierwszego wypełnionego rzędu tablicy (kina, Top 10, katalogi – rzędy są już
     * filtrowane typem) – do [HERO_MAX] pozycji w karuzeli. Odkrywaj – tylko „Kontynuuj oglądanie” ([showContinueHero]).
     */
    private fun showPortraitHero(t: StremioTile, more: List<StremioTile> = listOf(t)) {
        if (!heroAllowed || filter == FILTER_ALL || heroItems.isNotEmpty()) return
        val type = typeFilter
        val picks = (listOf(t) + more).distinctBy { it.meta.id }.filter { type == null || it.meta.type == type }.take(HERO_MAX)
        if (picks.isEmpty()) return
        showHero(picks.map { HeroItem(it.copy(rank = 0, badge = "")) })
    }

    /** (7.23) Odkrywaj: karta banerowa = „Kontynuuj oglądanie” (najnowsze pierwsze); pusta lista – karty nie ma (bez losowego tytułu). */
    private suspend fun showContinueHero() {
        if (!heroAllowed || filter != FILTER_ALL) return
        val list = StremioStore.progress().filter { !it.finished }.take(HERO_MAX)
        if (!heroAllowed || filter != FILTER_ALL) return
        if (list.isEmpty()) { hideHero(); return }
        val items = list.map { p -> HeroItem(StremioTile(StremioMetaPreview(p.metaId, p.type, p.name, p.poster), p), p) }
        if (items.map { it.progress } == heroItems.map { it.progress } && heroCard != null) return     // bez zmian – karta zostaje
        val keep = heroMeta?.id
        showHero(items, start = items.indexOfFirst { it.tile.meta.id == keep }.coerceAtLeast(0))
    }

    private fun hideHero() {
        heroCard?.let { rowsBox.removeView(it) }
        heroCard = null; heroItems = emptyList(); heroIndex = 0
    }

    /**
     * Karta banerowa w pionie (ten sam układ co w 7.22): obraz 16:10 (tło z metadanych, bez niego plakat), gradient od dołu w kolorze
     * tła motywu, podpis sekcji, tytuł, ocena ★ / rok / typ (przy wznowieniu – odcinek i procent, pasek postępu), przyciski
     * „Odtwórz” / „▶ Wznów” i „+ Do obejrzenia”; przy kilku pozycjach – ‹ › z licznikiem i przesunięcie palcem w bok.
     */
    private fun showHero(items: List<HeroItem>, start: Int = 0) {
        heroItems = items; heroIndex = start.coerceIn(0, items.size - 1)
        if (heroCard == null) buildHero()
        bindHero()
    }

    private fun buildHero() {
        val pal = CardSheet.palette()
        val w = (resources.displayMetrics.widthPixels - Ui.dp(24)).coerceAtLeast(Ui.dp(200)); val h = w * 10 / 16
        val card = FrameLayout(this).apply { tag = HERO_TAG; clipChildren = true }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(img, FrameLayout.LayoutParams(-1, h))
        card.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Ui.bg or 0xFF000000.toInt(), CardSheet.withAlpha(Ui.bg, 0xB0), CardSheet.withAlpha(Ui.bg, 0x00)))
        }, FrameLayout.LayoutParams(-1, h * 3 / 4, android.view.Gravity.BOTTOM))
        Ui.roundClip(card, Ui.dp(16).toFloat())
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(12)) }
        val label = TextView(this).apply { textSize = 11f; tag = "keepcolor hero_label"; setTextColor(pal.accent or 0xFF000000.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.08f }
        info.addView(label)
        val title = TextView(this).apply { textSize = 22f; tag = "keepcolor"; setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(title)
        val line = TextView(this).apply { textSize = 13f; tag = "keepcolor"; setTextColor(pal.text2) }
        info.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(2) })
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; tag = "hero_progress"; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent or 0xFF000000.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(CardSheet.withAlpha(pal.text2, 0x55))
        }
        info.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(4)).apply { topMargin = Ui.dp(6) })
        val buttons = LinearLayout(this).apply { clipChildren = false; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(8), 0, 0) }
        val play = CardSheet.pill(this, HERO_PLAY, pal) { heroItems.getOrNull(heroIndex)?.let { open(it.tile) } }.apply { tag = "keepcolor hero_play"; CardSheet.focusScale(this) }
        buttons.addView(play)
        buttons.addView(CardSheet.pill(this, HERO_WATCHLIST, pal) {
            val m = heroMeta ?: return@pill
            withResolved(m) { r ->
                val now = pl.iptv.tv.data.StremioLibrary.toggle(r)
                I18n.makeToast(this, if (now) "Dodano do listy do obejrzenia: ${r.name}" else "Usunięto z listy do obejrzenia: ${r.name}", Toast.LENGTH_SHORT).show()
            }
        }.apply { tag = "keepcolor hero_watchlist"; CardSheet.focusScale(this) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // przełączanie pozycji: ‹ licznik › (pilot / dotyk) i przesunięcie palcem po karcie
        val nav = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; clipChildren = false }
        nav.addView(CardSheet.pill(this, "‹", pal) { heroStep(-1) }.apply { tag = "keepcolor hero_prev"; contentDescription = "Poprzedni"; CardSheet.focusScale(this) })
        val counter = TextView(this).apply { textSize = 12f; tag = "keepcolor hero_counter"; setTextColor(pal.text2); setPadding(Ui.dp(8), 0, Ui.dp(8), 0) }
        nav.addView(counter)
        nav.addView(CardSheet.pill(this, "›", pal) { heroStep(1) }.apply { tag = "keepcolor hero_next"; contentDescription = "Następny"; CardSheet.focusScale(this) })
        buttons.addView(nav)
        info.addView(buttons)
        card.addView(info, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < kotlin.math.abs(vy) * 1.5f || kotlin.math.abs(vx) < Ui.dp(300)) return false
                heroStep(if (vx < 0) 1 else -1); return true
            }
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { heroItems.getOrNull(heroIndex)?.let { open(it.tile) }; return true }
        })
        img.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }
        rowsBox.addView(card, 0, LinearLayout.LayoutParams(-1, h).apply { bottomMargin = Ui.dp(12) })
        heroCard = card; heroImg = img; heroTitle = title; heroLine = line; heroLabel = label; heroCounter = counter; heroPlay = play; heroBar = bar; heroNav = nav
    }

    /** Następna / poprzednia pozycja karuzeli (zawija się). */
    fun heroStep(d: Int) {
        if (heroItems.size < 2) return
        heroIndex = Math.floorMod(heroIndex + d, heroItems.size)
        bindHero()
    }

    private fun bindHero() {
        val item = heroItems.getOrNull(heroIndex) ?: return
        val m = item.tile.meta; val p = item.progress
        val w = (resources.displayMetrics.widthPixels - Ui.dp(24)).coerceAtLeast(Ui.dp(200)); val h = w * 10 / 16
        heroLabel?.text = when { p != null -> HERO_CONTINUE_LABEL; typeFilter == "series" -> HERO_SUGGEST_SERIES; else -> HERO_SUGGEST_MOVIES }
        heroTitle?.text = m.name
        heroLine?.text = if (p != null) listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.typeName(m.type), "${(p.fraction * 100).toInt()}%").joinToString("  ·  ")
            else listOfNotNull(m.imdbRating.takeIf { r -> r.isNotBlank() && r != "0" && r != "0.0" }?.let { r -> "★ $r" }, m.releaseInfo.take(4).ifBlank { null }, StremioUi.typeName(m.type)).joinToString("  ·  ")
        heroBar?.apply { visibility = if (p != null) View.VISIBLE else View.GONE; progress = ((p?.fraction ?: 0f) * 1000).toInt() }
        heroPlay?.text = if (p != null) HERO_RESUME else HERO_PLAY
        heroNav?.visibility = if (heroItems.size > 1) View.VISIBLE else View.GONE
        heroCounter?.text = "${heroIndex + 1} / ${heroItems.size}"
        heroCard?.contentDescription = m.name
        val img = heroImg ?: return
        coil.util.CoilUtils.dispose(img); img.setImageDrawable(null)
        fun poster() { if (m.poster.isNotBlank()) img.load(m.poster) { crossfade(false); size(w, h); scale(coil.size.Scale.FILL) } }
        // tło 16:10 z metadanych (identyfikator IMDb); pozycje TMDB i brak tła – plakat
        poster()
        if (!pl.iptv.tv.data.StremioTmdb.isTmdbId(m.id)) lifecycleScope.launch {
            StremioMetas.get(m.type, m.id)?.background?.takeIf { b -> b.isNotBlank() }?.let { b -> if (heroMeta == m) img.load(b) { crossfade(false); size(w, h); scale(coil.size.Scale.FILL) } }
        }
    }

    private fun build(): View {
        if (portrait) return buildPortrait()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(36), Ui.dp(18), Ui.dp(36), Ui.dp(8)) }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(StremioUi.text(this, "Stremio", 26f, "accent", bold = true).apply { setPadding(0, 0, Ui.dp(18), 0) })
        searchBar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        query = EditText(this).apply {
            hint = "Szukaj filmów i seriali…"; tag = "field"; setSingleLine(); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10))
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(text.toString()); true } else false }
            setOnClickListener { showKeyboard() }
        }
        searchBar.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
        searchBar.addView(StremioUi.button(this, "Szukaj", PlayerIcon.SEARCH) { if (query.text.isBlank()) showKeyboard() else search(query.text.toString()) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        searchBar.addView(StremioUi.button(this, "Wtyczki", PlayerIcon.PLUG) { startActivity(Intent(this, StremioAddonsActivity::class.java)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        // (7.8) odświeżenie wtyczek (zmiany na koncie) i ponowne wczytanie tablicy
        searchBar.addView(StremioUi.button(this, "", PlayerIcon.REFRESH) { refreshAddons() }.apply { contentDescription = "Odśwież wtyczki" },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        // (7.21) własny klucz TMDB (Top 10, polskie opisy, części filmów) – wpisanie pilotem albo z telefonu (kod QR)
        searchBar.addView(StremioUi.button(this, "", PlayerIcon.STAR) { TmdbKeyUi.show(this, onTop10Changed = { applyTop10Selection() }) { build(searchQuery) } }.apply { contentDescription = TmdbKeyUi.TITLE },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        account = StremioUi.button(this, "", PlayerIcon.USER) { accountMenu() }
        if (obsidian) {
            // (7.17) „Obsidian Stream”: przyciski nagłówka w pasku nawigacji (nad treścią), u góry pigułki filtrów i wyszukiwarka
            val O = pl.iptv.tv.ui.theme.ObsidianViews
            root.setPadding(Ui.dp(96), Ui.dp(22), Ui.dp(40), Ui.dp(8))
            header.removeAllViews()
            val buttons = (0 until searchBar.childCount).map { searchBar.getChildAt(it) }.filter { it !== query }
            buttons.forEach { searchBar.removeView(it) }
            railBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            account.contentDescription = "Konto"
            railBox.addView(account, LinearLayout.LayoutParams(-1, -2))
            railBox.addView(StremioUi.button(this, "", PlayerIcon.SEARCH) { query.requestFocus(); showKeyboard() }.apply { contentDescription = "Szukaj" }, LinearLayout.LayoutParams(-1, -2))
            buttons.getOrNull(1)?.let { (it as TextView).text = ""; it.contentDescription = "Wtyczki"; railBox.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            buttons.getOrNull(2)?.let { railBox.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            buttons.getOrNull(3)?.let { railBox.addView(it, LinearLayout.LayoutParams(-1, -2)) }        // (7.21) Klucz TMDB
            railBox.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            railBox.addView(StremioUi.button(this, "", PlayerIcon.CLOSE) { leaveScreen() }.apply { contentDescription = "Zamknij" }, LinearLayout.LayoutParams(-1, -2))
            filterBar = buildFilterBar()
            header.addView(filterBar, LinearLayout.LayoutParams(0, -2, 1f))
            query.tag = "keepcolor"; query.setTextColor(O.TEXT); query.setHintTextColor(O.TEXT2); query.hint = "Szukaj filmów i seriali…"
            query.background = android.graphics.drawable.StateListDrawable().apply {
                fun f(stroke: Int, color: Int, w: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(28).toFloat(); setColor(color); setStroke(Ui.dp(w), stroke) }
                addState(intArrayOf(android.R.attr.state_focused), f(O.ACCENT, O.SURFACE_ELEVATED, 2))
                addState(intArrayOf(), f(O.SPECULAR, O.SURFACE_GLASS, 1))
            }
            query.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(10))
            header.addView(searchBar, LinearLayout.LayoutParams(Ui.dp(360), -2).apply { marginStart = Ui.dp(12) })
        } else {
            header.addView(searchBar, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(account, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
            header.addView(StremioUi.button(this, "", PlayerIcon.CLOSE) { leaveScreen() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        }
        root.addView(header)
        // opis zaznaczonego tytułu (jak podgląd po najechaniu w Stremio)
        infoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(4)); minimumHeight = Ui.dp(96) }
        // stała wysokość opisu (dokładnie 1 + 1 + 3 linie): rzędy plakatów nie przeskakują przy krótszych / dłuższych opisach
        infoTitle = StremioUi.text(this, "", 22f, bold = true, lines = 1).apply { setLines(1) }
        infoMeta = StremioUi.text(this, "", 14f, "accent", lines = 1).apply { setLines(1) }
        infoDesc = StremioUi.text(this, "", 14f, "text2", lines = 3).apply { setLines(3) }
        infoBox.addView(infoTitle); infoBox.addView(infoMeta); infoBox.addView(infoDesc)
        root.addView(infoBox)
        scroll = RowsScroll(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rowsBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun setAccountText(t: String) {
        // (7.17) pasek nawigacji: nazwa konta jako podpis (widoczny po rozwinięciu paska)
        if (obsidian) { account.contentDescription = if (!StremioAccount.loggedIn) "Zaloguj" else if (t.isEmpty()) "Konto" else "Konto: $t"; navRail?.refreshLabels(); return }
        account.text = t; account.compoundDrawablePadding = if (t.isEmpty()) 0 else Ui.dp(8)
        account.setPadding(Ui.dp(if (t.isEmpty()) 12 else 16), Ui.dp(10), Ui.dp(if (t.isEmpty()) 12 else 16), Ui.dp(10))
    }

    private fun showKeyboard() {
        query.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------- logowanie ----------------

    private fun showLogin() {
        loginShown = true; scroll.auto = true
        searchBar.visibility = View.GONE; infoBox.visibility = View.GONE; filterBar?.visibility = View.GONE
        setAccountText("")
        rowsBox.removeAllViews(); rowTitles.clear()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "panel"; setPadding(Ui.dp(28), Ui.dp(22), Ui.dp(28), Ui.dp(22)) }
        card.addView(StremioUi.text(this, "Zaloguj się do Stremio", 24f, bold = true))
        card.addView(StremioUi.text(this, "Twoje wtyczki, biblioteka i postęp oglądania z konta Stremio.", 14f, "text2").apply { setPadding(0, Ui.dp(4), 0, Ui.dp(14)) })
        fun field(h: String, type: Int) = EditText(this).apply { hint = h; tag = "field"; setSingleLine(); inputType = type; textSize = 16f
            setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10)) }
        val email = field("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Hasło", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val status = StremioUi.text(this, "", 14f, "accent")
        card.addView(email, LinearLayout.LayoutParams(-1, -2))
        card.addView(pass, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        card.addView(StremioUi.button(this, "Zaloguj") { login(status) { StremioApi.login(email.text.toString().trim(), pass.text.toString()) } }.apply { setTag(pl.iptv.tv.R.id.row_key, "login_btn") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(10) })
        card.addView(StremioUi.text(this, "albo klucz API (authKey) z ustawień konta / Stremio Web:", 13f, "text2").apply { setPadding(0, Ui.dp(16), 0, Ui.dp(4)) })
        val token = field("Klucz API (authKey)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        card.addView(token, LinearLayout.LayoutParams(-1, -2))
        card.addView(StremioUi.button(this, "Zaloguj kluczem API") {
            val k = token.text.toString().trim()
            login(status) { StremioApi.Session(k, StremioApi.user(k)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        card.addView(StremioUi.button(this, "Przeglądaj bez konta") { Prefs.stremioGuest = true; showBoard() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(10) })
        // kod QR obok formularza (TV), nad nim na wąskim ekranie (telefon)
        // szerokość pod treść ekranu (bez marginesów 2 × 36 dp) – na telefonie w poziomie obie karty mieszczą się obok siebie
        val avail = resources.displayMetrics.widthPixels - Ui.dp(72)
        val wide = avail >= Ui.dp(600)
        val wrap = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val gap = Ui.dp(16)
        val qrW = if (wide) minOf(Ui.dp(320), (avail - gap) * 2 / 5) else minOf(Ui.dp(560), avail)
        val cardW = if (wide) minOf(Ui.dp(560), avail - gap - qrW) else minOf(Ui.dp(560), avail)
        wrap.addView(qrCard(status), LinearLayout.LayoutParams(qrW, -2).apply { if (wide) marginEnd = gap else bottomMargin = gap })
        wrap.addView(card, LinearLayout.LayoutParams(cardW, -2))
        rowsBox.addView(wrap)
        Ui.style(wrap)
        email.post { email.requestFocus() }
    }

    // ---------------- logowanie kodem QR ----------------

    private var linkJob: kotlinx.coroutines.Job? = null
    /** Bieżący kod połączenia (testy). */
    var linkCode: String? = null; private set

    /** Karta z kodem QR: zeskanowanie telefonem zalogowanym w Stremio i potwierdzenie loguje telewizor od razu. */
    private fun qrCard(status: TextView): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "panel"; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(22)) }
        box.addView(StremioUi.text(this, "Zaloguj kodem QR", 20f, bold = true))
        box.addView(StremioUi.text(this, "Zeskanuj aparatem telefonu zalogowanego w Stremio i potwierdź – telewizor zaloguje się sam.", 13f, "text2")
            .apply { setPadding(0, Ui.dp(4), 0, Ui.dp(12)); gravity = android.view.Gravity.CENTER })
        val img = android.widget.ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE); setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        box.addView(img, LinearLayout.LayoutParams(Ui.dp(200), Ui.dp(200)))
        val linkText = StremioUi.text(this, "Pobieranie kodu…", 14f, "accent").apply { setPadding(0, Ui.dp(10), 0, 0); gravity = android.view.Gravity.CENTER }
        box.addView(linkText)
        linkJob?.cancel()
        linkJob = lifecycleScope.launch {
            while (loginShown) {
                val res = runCatching { StremioApi.linkCreate() }
                val l = res.getOrNull()
                if (l == null) {
                    linkText.text = "Kod QR niedostępny: ${res.exceptionOrNull()?.message ?: "brak połączenia"}"; kotlinx.coroutines.delay(15_000); continue
                }
                linkCode = l.code
                img.load(l.qr) { crossfade(false); fitTo(img, Ui.dp(220), Ui.dp(220)) }
                linkText.text = "${l.link.removePrefix("https://")}\nkod: ${l.code}"
                // kod ważny kilka minut – odpytywanie co 3 s (tylko na wierzchu), potem nowy kod
                val until = android.os.SystemClock.elapsedRealtime() + LINK_TTL_MS
                while (loginShown && android.os.SystemClock.elapsedRealtime() < until) {
                    kotlinx.coroutines.delay(LINK_POLL_MS)
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) continue
                    val key = StremioApi.linkRead(l.code) ?: continue
                    linkCode = null
                    login(status) { StremioApi.Session(key, runCatching { StremioApi.user(key) }.getOrDefault("")) }
                    return@launch
                }
            }
        }
        return box
    }

    private fun login(status: TextView, call: suspend () -> StremioApi.Session) {
        status.text = "Logowanie…"
        lifecycleScope.launch {
            runCatching { call() }.onSuccess { s ->
                StremioAccount.save(s); Prefs.stremioGuest = false
                status.text = "Zalogowano: ${s.email}. Pobieranie wtyczek…"
                runCatching { StremioStore.replaceFromAccount(StremioApi.addonCollection(s.authKey)) }
                    .onFailure { I18n.makeToast(this@StremioActivity, "Nie udało się pobrać wtyczek: ${it.message}", Toast.LENGTH_LONG).show() }
                askSync()
            }.onFailure { status.text = "Błąd logowania: " + (it.message ?: "brak połączenia") }
        }
    }

    /** Okno po zalogowaniu: synchronizacja historii z kontem. */
    fun askSync() {
        Ui.dialog(this).setTitle("Synchronizacja historii")
            .setMessage("Czy chcesz zsynchronizować historię odtwarzania ze swoim kontem Stremio?")
            .setPositiveButton("Tak") { _, _ ->
                Prefs.stremioSync = 1
                lifecycleScope.launch {
                    runCatching { StremioStore.syncLibrary() }
                        .onSuccess { I18n.makeToast(this@StremioActivity, "Zsynchronizowano historię ($it pozycji z konta)", Toast.LENGTH_SHORT).show() }
                        .onFailure { I18n.makeToast(this@StremioActivity, "Synchronizacja nieudana: ${it.message}", Toast.LENGTH_LONG).show() }
                    showBoard()
                }
            }
            .setNegativeButton("Nie") { _, _ -> Prefs.stremioSync = 0; showBoard() }
            .setCancelable(false).show()
    }

    private fun accountMenu() {
        if (!StremioAccount.loggedIn) { showLogin(); return }
        Dialogs.menu(this, "Konto Stremio – ${StremioAccount.email}", buildList {
            add("⟳ Odśwież wtyczki z konta" to { lifecycleScope.launch { runCatching { StremioStore.replaceFromAccount(StremioApi.addonCollection(StremioAccount.authKey)) }; showBoard() } })
            add((if (Prefs.stremioSync == 1) "✔ Synchronizacja historii: włączona" else "✖ Synchronizacja historii: wyłączona") to {
                Prefs.stremioSync = if (Prefs.stremioSync == 1) 0 else 1
                if (Prefs.stremioSync == 1) lifecycleScope.launch { runCatching { StremioStore.syncLibrary() }; refreshContinue() }
            })
            add("⏏ Wyloguj" to {
                lifecycleScope.launch { StremioApi.logout(StremioAccount.authKey); StremioAccount.clear(); StremioStore.forgetAccount(); Prefs.stremioGuest = false; showLogin() }
            })
        })
    }

    // ---------------- tablica i wyszukiwanie ----------------

    private fun showBoard() {
        loginShown = false; linkJob?.cancel(); linkJob = null; linkCode = null; scroll.auto = false
        searchBar.visibility = View.VISIBLE; infoBox.visibility = if (portrait) View.GONE else View.VISIBLE; filterBar?.visibility = View.VISIBLE
        // (7.17) fokus startowy na wybranym filtrze (nie na pasku nawigacji – pasek nie rozwija się sam); pierwszy rząd przejmie go po wczytaniu
        if (obsidian) filterPills.getOrNull(filter)?.let { p -> p.post { if (window.decorView.findFocus().let { it == null || inRail(it) }) p.requestFocus() } }
        setAccountText(if (StremioAccount.loggedIn) StremioAccount.email.substringBefore('@') else "Zaloguj")
        build(searchQuery)
    }

    fun search(q: String) {
        searchQuery = q.trim().ifBlank { null }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        build(searchQuery)
    }

    private class Row(val title: String, val adapter: StremioPosterAdapter, val view: View, val status: TextView) { var lastPos = 0 }
    private val rows = mutableListOf<Row>()

    /** Użytkownik poruszył się pilotem od ostatniej przebudowy tablicy – bez automatycznego przenoszenia fokusu. */
    private var userMoved = false

    private fun rowRv(r: Row) = (r.view as LinearLayout).getChildAt(2) as RecyclerView

    /**
     * (7.11) Stabilny kursor przy przewijaniu rzędów: ▲▼ prowadzi aplikacja – następny / poprzedni rząd, który już ma plakaty
     * (rzędy jeszcze się wczytujące i puste są pomijane, więc kursor nie przeskakuje o kilka rzędów ani nie ginie), z tą samą
     * kolumną. Ostatni rząd: kursor zostaje (blokada krawędzi). ▲ z pierwszego rzędu – do paska wyszukiwania.
     */
    private fun rowsVerticalKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN && event.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        val f = window.decorView.findFocus() ?: return false
        val ri = rows.indexOfFirst { rowRv(it).findContainingItemView(f) != null }
        if (ri < 0) return false
        val pos = rowRv(rows[ri]).findContainingViewHolder(f)?.bindingAdapterPosition?.takeIf { it >= 0 } ?: rows[ri].lastPos
        rows[ri].lastPos = pos
        val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
        var j = ri + step
        while (j in rows.indices && rows[j].adapter.items.isEmpty()) j += step
        if (j !in rows.indices) {
            // (7.17) ▲ z pierwszego rzędu – z powrotem na wybraną zakładkę (np. „Filmy”), nie na pierwszą
            if (step < 0 && obsidian) filterPills.getOrNull(filter)?.takeIf { it.isShown }?.let { p -> if (event.action == KeyEvent.ACTION_DOWN) p.requestFocus(); return true }
            return step > 0                                 // ▼ na końcu – zostaje; ▲ z pierwszego rzędu – zwykła nawigacja do paska
        }
        if (event.action == KeyEvent.ACTION_DOWN) rowRv(rows[j]).focusPosition(pos.coerceAtMost(rows[j].adapter.items.size - 1))
        return true
    }

    /** Po wczytaniu / usunięciu rzędu nad kursorem zaznaczony rząd zostaje u góry widoku (nic nie „ucieka” spod kursora). */
    private fun anchorFocusedRow() { focusedRow()?.let { box -> scroll.post { scroll.scrollTo(0, (box.top - Ui.dp(4)).coerceAtLeast(0)) } } }

    /**
     * Kursor może zostać przeniesiony na pierwszy rząd plakatów: nic nie jest zaznaczone albo zaznaczenie jest tylko startowe
     * (wyszukiwarka, a w „Obsidian Stream” – pierwszy element paska nawigacji / pigułka filtru, zanim użytkownik ruszył pilotem).
     */
    private fun autoFocusAllowed(): Boolean {
        // (7.17) „Obsidian Stream”: na tablicy kursor startowy zostaje na wybranej zakładce (Odkrywaj) – ▼ przenosi go do plakatów;
        // wyniki wyszukiwania – jak dawniej (pierwszy rząd przejmuje fokus startowy)
        val board = obsidian && filterPills.isNotEmpty() && searchQuery == null
        val f = window.decorView.findFocus() ?: return !board
        if (f === query) return true
        return obsidian && !userMoved && !board && (inRail(f) || f in filterPills)
    }

    /** (7.17) Element treści, z którego kursor wszedł na pasek nawigacji (► wraca dokładnie tam). */
    private var railReturn: java.lang.ref.WeakReference<View>? = null
    private fun railItems() = (0 until railBox.childCount).map { railBox.getChildAt(it) }.filter { it.isFocusable && it.visibility == View.VISIBLE }
    private fun inRail(v: View?): Boolean { var x = v; while (x != null) { if (x === railBox) return true; x = x.parent as? View }; return false }

    /**
     * Pasek nawigacji „Obsidian Stream”: ◄ z pierwszego plakatu rzędu albo z pierwszej pigułki filtrów – pasek (rozwinięty);
     * ▲▼ w pasku – kolejne pozycje (na końcach zostaje); ► z paska – element, z którego przyszedł kursor (albo wybrany filtr).
     */
    private fun railKey(event: KeyEvent): Boolean {
        if (!obsidian || !::railBox.isInitialized) return false
        val f = window.decorView.findFocus() ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (inRail(f)) return true
                val firstPoster = rows.any { r -> rowRv(r).findContainingViewHolder(f)?.bindingAdapterPosition == 0 }
                if (firstPoster || f === filterPills.firstOrNull() || (loginShown && !inRail(f) && f.focusSearch(View.FOCUS_LEFT).let { it == null || inRail(it) })) {
                    if (down) { railReturn = java.lang.ref.WeakReference(f); railItems().firstOrNull()?.requestFocus() }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inRail(f)) {
                if (down) (railReturn?.get()?.takeIf { it.isShown && it.isAttachedToWindow } ?: filterPills.getOrNull(filter)?.takeIf { it.isShown }
                    ?: rows.firstOrNull { it.adapter.items.isNotEmpty() }?.let { rowRv(it).getChildAt(0) })?.requestFocus()
                return true
            }
            // (7.17) ▼ z zakładek – pierwszy plakat pierwszego rzędu (nigdy pasek nawigacji); brak rzędów – zostaje na zakładce
            KeyEvent.KEYCODE_DPAD_DOWN -> if (f in filterPills) {
                if (down) rows.firstOrNull { it.adapter.items.isNotEmpty() }?.let { r -> rowRv(r).focusPosition(0) }
                return true
            } else if (inRail(f)) {
                if (down) { val items = railItems(); items.getOrNull(items.indexOf(f) + 1)?.requestFocus() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (inRail(f)) {
                if (down) { val items = railItems(); val i = items.indexOf(f) + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1; items.getOrNull(i)?.requestFocus() }
                return true
            }
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DPAD_KEYS) userMoved = true
        if (railKey(event)) return true
        if (!loginShown && rowsVerticalKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

    private fun build(q: String?) {
        jobs.forEach { it.cancel() }; jobs.clear()
        scrolledTo = null
        rowsBox.removeAllViews(); rows.clear(); rowTitles.clear(); heroItems = emptyList(); heroIndex = 0; heroCard = null
        userMoved = false
        var first = true
        // (7.17) filtr tablicy: Filmy / Seriale – tylko katalogi i rzędy tego typu, Biblioteka – rzędy z urządzenia (bez katalogów)
        val f = filter
        val type = typeFilter
        jobs += lifecycleScope.launch {
            // (7.17) historia i postęp tylko w Bibliotece: Odkrywaj / Filmy / Seriale zaczynają się od katalogów
            if (q == null && f == FILTER_LIBRARY) {
                fillContinue(addRow(CONTINUE_ROW))
                fillLibrary(addRow(LIBRARY_ROW))
                fillWatched(addRow(WATCHED_ROW))
                if (rows.isEmpty()) rowsBox.addView(StremioUi.text(this@StremioActivity, "Biblioteka jest pusta – dodaj tytuł przyciskiem „Do obejrzenia” w jego opisie albo przytrzymaj OK na plakacie.", 16f, "text2")
                    .apply { setPadding(Ui.dp(6), Ui.dp(20), 0, 0) })
                return@launch
            }
            // (7.23) filmy / seriale z listy M3U z filtrem gatunków – przed katalogami wtyczek (bez sieci, katalog z pamięci)
            addM3uRows(q, type)
            val addons = StremioStore.enabledAddons()
            // (7.12) „Obecnie w kinach” – pierwszy rząd katalogów. (7.22) Z kluczem TMDB – polski repertuar kinowy (now_playing +
            // premiery kinowe z 45 dni, pl-PL, odznaka „W KINACH”); bez klucza albo bez wyników – jak dotąd z Cinemety
            val cinemaAddon = addons.firstOrNull { a -> a.catalogs.any { it.type == "movie" && it.id == "year" } }
            val tmdb = pl.iptv.tv.data.StremioTmdb.available
            if (q == null && (type == null || type == "movie") && (tmdb || cinemaAddon != null)) {
                val r = addRow(IN_CINEMAS)
                jobs += lifecycleScope.launch {
                    val list = (if (tmdb) runCatching { pl.iptv.tv.data.StremioTmdb.nowInCinemas() }.getOrNull() else null)?.takeIf { it.isNotEmpty() }
                        ?: cinemaAddon?.let { a -> runCatching { StremioApi.inCinemas(a) }.getOrDefault(emptyList()) }.orEmpty()
                    if (list.isEmpty()) { removeRow(r); return@launch }
                    r.adapter.items += list.map { StremioTile(it, badge = StremioUi.IN_CINEMAS_BADGE) }; r.adapter.notifyItemRangeInserted(0, list.size); r.status.visibility = View.GONE; showPortraitHero(r.adapter.items[0], r.adapter.items)
                    if (rowsHaveFocus()) anchorFocusedRow()
                    if (first && !userMoved && autoFocusAllowed()) { first = false; focusRow(r) }
                }
            }
            // (7.20) Top 10 platform (Odkrywaj – 10 rzędów, Filmy / Seriale – po 5): od razu z pamięci podręcznej, sieć tylko w tle
            // (7.22) telefon pionowo: na Odkrywaj także „Do obejrzenia” (pusty rząd znika sam). (7.23) „Kontynuuj oglądanie” – nie jako rząd
            // (ani pod „Obecnie w kinach”, ani w Filmach / Serialach), tylko w karcie banerowej u góry Odkrywaj
            if (portrait && q == null && f == FILTER_ALL) { showContinueHero(); fillLibrary(addRow(LIBRARY_ROW)) }
            if (q == null) addTop10Rows(type) { r -> if (first && !userMoved && autoFocusAllowed()) { first = false; focusRow(r) } }
            // (7.12) katalogi usługowych wtyczek (AIOStreams / ElfHosted, Store RD / PM) nie są pokazywane na tablicy
            fun hidden(a: StremioAddon, c: StremioAddon.Catalog) = isHiddenCatalog(a, c)
            fun wanted(c: StremioAddon.Catalog) = type == null || c.type == type
            val catalogs: List<Pair<StremioAddon, StremioAddon.Catalog>> = if (q != null)
                addons.flatMap { a -> a.searchCatalogs.filterNot { hidden(a, it) }.filter(::wanted).map { a to it } }.sortedBy { (_, c) -> when (c.type) { "movie" -> 0; "series" -> 1; else -> 2 } }
            else addons.flatMap { a -> a.boardCatalogs.filterNot { hidden(a, it) }.filter(::wanted).map { a to it } }.take(8)
            if (catalogs.isEmpty()) {
                if (m3uRowsShown) return@launch                              // (7.23) tablica z samą listą M3U – bez komunikatu o wtyczkach
                rowsBox.addView(StremioUi.text(this@StremioActivity, if (addons.isEmpty()) "Brak włączonych wtyczek – włącz je w „🧩 Wtyczki”." else "Włączone wtyczki nie mają katalogów.", 16f, "text2")
                    .apply { setPadding(Ui.dp(6), Ui.dp(20), 0, 0) })
                return@launch
            }
            for ((a, c) in catalogs) {
                val title = rowTitle(a, c, q != null)
                val r = addRow(title)
                jobs += lifecycleScope.launch {
                    val res = runCatching { StremioApi.catalog(a, c, q) }
                    res.onSuccess { list ->
                        if (list.isEmpty()) { removeRow(r); return@onSuccess }
                        val start = r.adapter.items.size
                        r.adapter.items += list.map { StremioTile(it) }; r.adapter.notifyItemRangeInserted(start, list.size); r.status.visibility = View.GONE; showPortraitHero(r.adapter.items[0], r.adapter.items)
                        if (rowsHaveFocus()) anchorFocusedRow()
                        // fokus na pierwszym rzędzie tylko, dopóki użytkownik niczego nie zaznaczył pilotem – wcześniej każdy
                        // doładowany katalog mógł przenieść kursor (np. gdy fokus był na przycisku u góry albo chwilowo zniknął)
                        if (first && !userMoved && autoFocusAllowed()) { first = false; focusRow(r) }
                    }.onFailure { removeRow(r) }   // (7.11) wtyczka, która zawiodła, jest pomijana po cichu (bez komunikatów o błędach)
                }
            }
        }
    }

    /**
     * (7.20) Rzędy „Top 10 Filmy / Seriale – [Platforma]” ([pl.iptv.tv.data.StremioTop10]): zapisane pozycje od razu (bez sieci),
     * a nieaktualne (≥ 4 dni) lub puste – pobierane w tle i podmieniane w miejscu (fokus zostaje). Bez klucza TMDB i bez zapisu
     * rząd się nie pojawia; pusty wynik – rząd usuwany. Plakaty z odznaką pozycji „#1”–„#10”.
     */
    private fun addTop10Rows(type: String?, onFilled: (Row) -> Unit) {
        val T = pl.iptv.tv.data.StremioTop10
        T.init(this)
        val canFetch = pl.iptv.tv.data.StremioTmdb.available
        // (7.21) bez klucza TMDB – tablica jak w 7.19 (żadnych rzędów Top 10, także z pamięci podręcznej)
        if (!canFetch) return
        val byKey = LinkedHashMap<String, Row>()
        val specs = T.rows(type)
        for (s in specs) {
            val c = T.cached(s)
            if ((c == null || c.items.isEmpty()) && !canFetch) continue
            val r = addRow(s.title)
            byKey[s.key] = r
            c?.items?.takeIf { it.isNotEmpty() }?.let { fillTop10(r, it); onFilled(r) }
        }
        if (!canFetch || byKey.isEmpty()) return
        jobs += lifecycleScope.launch {
            specs.filter { it.key in byKey && T.isStale(it) }.forEach { s ->
                launch {
                    val l = runCatching { T.refresh(s) }.getOrNull()
                    val r = byKey[s.key] ?: return@launch
                    if (r !in rows) return@launch
                    if (l.isNullOrEmpty()) { if (r.adapter.items.isEmpty()) removeRow(r) } else { fillTop10(r, l); onFilled(r) }
                }
            }
        }
    }

    /**
     * (7.22) Zmiana „Widocznych sekcji TOP 10” (okno Klucz TMDB): rzędy odznaczonych platform znikają od razu; rzędy nowo
     * zaznaczonych pojawiają się po zamknięciu okna (tablica wczytywana od nowa – [TmdbKeyUi.show] → onChanged).
     */
    internal fun applyTop10Selection() {
        val T = pl.iptv.tv.data.StremioTop10
        val wanted = T.rows(typeFilter).map { it.title }.toSet()
        val known = T.rows(null, all = true).map { it.title }.toSet()
        rows.filter { it.title in known && it.title !in wanted }.forEach { removeRow(it) }
    }

    private fun fillTop10(r: Row, list: List<StremioMetaPreview>) {
        val tiles = list.take(pl.iptv.tv.data.StremioTop10.SIZE).mapIndexed { i, m -> StremioTile(m, rank = i + 1) }
        if (tiles == r.adapter.items) return
        val old = r.adapter.items.size
        r.adapter.items.clear(); r.adapter.items += tiles
        when {
            old == 0 -> r.adapter.notifyItemRangeInserted(0, tiles.size)
            old == tiles.size -> r.adapter.notifyItemRangeChanged(0, tiles.size)        // te same widoki – fokus zostaje
            else -> r.adapter.notifyDataSetChanged()
        }
        r.status.visibility = View.GONE; r.adapter.items.firstOrNull()?.let { showPortraitHero(it, r.adapter.items) }
        if (rowsHaveFocus()) anchorFocusedRow()
    }

    private var refreshingAddons = false

    private fun refreshAddons() {
        if (refreshingAddons) return
        refreshingAddons = true
        I18n.makeToast(this, "Odświeżanie wtyczek…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = runCatching { StremioStore.refreshAddons() }
            refreshingAddons = false
            r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            I18n.makeToast(this@StremioActivity, r.fold({ "Wtyczki odświeżone ($it)" }, { "Błąd odświeżania: ${it.message}" }), android.widget.Toast.LENGTH_SHORT).show()
            build(searchQuery)
        }
    }

    private fun rowsHaveFocus(): Boolean { var v: View? = window.decorView.findFocus(); while (v != null) { if (v === rowsBox) return true; v = v.parent as? View }; return false }

    private fun rowTitle(a: StremioAddon, c: StremioAddon.Catalog, search: Boolean): String {
        val t = StremioUi.typeName(c.type).let { if (c.type == "movie") "Filmy" else if (c.type == "series") "Seriale" else it }
        return if (a.transportUrl == StremioApi.CINEMETA) (if (search) t else "Popularne – " + t.lowercase()) else "${a.name} · ${c.name.ifBlank { t }}" + if (search) " ($t)" else ""
    }

    private fun rowIcon(title: String): String = when {
        title.startsWith("Top 10") -> PlayerIcon.STAR
        title == "Kontynuuj oglądanie" -> PlayerIcon.PLAY
        title == "Obejrzane" -> PlayerIcon.CHECK
        title == IN_CINEMAS -> PlayerIcon.CINEMA
        title.startsWith("Popularne") -> PlayerIcon.STAR
        title.contains("Seriale", true) || title.contains("serial", true) -> PlayerIcon.TVSET
        title.contains("Filmy", true) || title.contains("film", true) -> PlayerIcon.FILM
        else -> PlayerIcon.TRENDING
    }

    /** Wiersz rzędu, na którym zakończyło się ostatnie przewinięcie (przewijanie tylko przy zmianie rzędu, nie przy każdym plakacie). */
    private var scrolledTo: View? = null

    // ---------------- (7.23) filmy i seriale z listy M3U ----------------

    /** Wybrany gatunek filtra rzędów z listy ([pl.iptv.tv.data.M3uVod.ALL] – wszystkie). */
    var m3uGenre = pl.iptv.tv.data.M3uVod.ALL; private set
    private var m3uMovies: Row? = null
    private var m3uSeries: Row? = null
    private var m3uRowsShown = false
    /** Pigułki gatunków (testy). */
    var m3uGenreViews: List<TextView> = emptyList(); private set

    private fun m3uTile(m: pl.iptv.tv.data.M3uVod.Movie) = StremioTile(StremioMetaPreview(m.id, "movie", m.title, m.poster, releaseInfo = m.year, genres = m.genres))
    private fun m3uTile(s: pl.iptv.tv.data.M3uVod.Show) = StremioTile(StremioMetaPreview(s.id, "series", s.title, s.poster, genres = s.genres),
        note = "${s.episodes.size} " + StremioUi.plural(s.episodes.size, "odcinek", "odcinki", "odcinków"))

    /**
     * Rzędy „Filmy z Twojej listy” / „Seriale z Twojej listy” (wg zakładki: Odkrywaj – oba, Filmy / Seriale – jeden) i pigułki
     * gatunków („Wszystkie”, gatunki, „Pozostałe”) nad nimi; wyszukiwanie – pozycje z tytułem zawierającym zapytanie.
     * Katalog z [pl.iptv.tv.data.VodLibrary] (budowany w tle); pusta lista / brak VOD – nic się nie dodaje.
     */
    private suspend fun addM3uRows(q: String?, type: String?) {
        m3uMovies = null; m3uSeries = null; m3uRowsShown = false; m3uGenreViews = emptyList()
        val cat = pl.iptv.tv.data.VodLibrary.current()
        if (cat.isEmpty) return
        val genres = cat.genres(type)
        if (m3uGenre !in genres) m3uGenre = pl.iptv.tv.data.M3uVod.ALL
        if (q == null && genres.size > 2) addGenreChips(genres)
        if (type != "series" && (q != null || cat.movies.isNotEmpty())) m3uMovies = addRow(if (q != null) "$M3U_MOVIES_ROW – wyniki" else M3U_MOVIES_ROW)
        if (type != "movie" && (q != null || cat.series.isNotEmpty())) m3uSeries = addRow(if (q != null) "$M3U_SERIES_ROW – wyniki" else M3U_SERIES_ROW)
        m3uRowsShown = m3uMovies != null || m3uSeries != null
        fillM3uRows(cat, q)
    }

    private fun fillM3uRows(cat: pl.iptv.tv.data.VodCatalog, q: String?) {
        fun match(t: String) = q == null || t.contains(q, ignoreCase = true)
        fun fill(r: Row?, tiles: List<StremioTile>) {
            r ?: return
            if (r !in rows) return
            if (tiles.isEmpty() && q != null) { removeRow(r); if (r === m3uMovies) m3uMovies = null else m3uSeries = null; return }
            r.adapter.items.clear(); r.adapter.items += tiles; r.adapter.notifyDataSetChanged()
            r.status.text = M3U_EMPTY_GENRE; r.status.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
            tiles.firstOrNull()?.let { showPortraitHero(it, tiles) }
        }
        fill(m3uMovies, cat.movies(m3uGenre).filter { match(it.title) }.map(::m3uTile))
        fill(m3uSeries, cat.series(m3uGenre).filter { match(it.title) }.map(::m3uTile))
    }

    private fun addGenreChips(genres: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(24), Ui.dp(4)) }
        val views = genres.map { g ->
            MobilePortraitScaffold.chip(this, g, g == m3uGenre) { setM3uGenre(g) }.apply { tag = "keepcolor nozoom"; setTag(pl.iptv.tv.R.id.row_key, M3U_GENRE_TAG + g) }
                .also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) }) }
        }
        m3uGenreViews = views
        rowsBox.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setTag(pl.iptv.tv.R.id.row_key, M3U_GENRES_TAG); addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Filtr gatunku [g]: rzędy z listy podmieniane w miejscu (bez przebudowy tablicy i bez sieci). */
    fun setM3uGenre(g: String) {
        m3uGenre = g
        m3uGenreViews.forEach { it.isSelected = it.text.toString() == g || it.getTag(pl.iptv.tv.R.id.row_key) == M3U_GENRE_TAG + g }
        lifecycleScope.launch { fillM3uRows(pl.iptv.tv.data.VodLibrary.current(), searchQuery) }
    }

    /** Pozycja z listy: film – od razu odtwarzacz; serial – wybór odcinka (sezon, odcinek), potem odtwarzacz. */
    private fun openM3u(t: StremioTile) = lifecycleScope.launch {
        val cat = pl.iptv.tv.data.VodLibrary.current()
        val id = t.meta.id
        cat.movie(id)?.let { m -> startActivity(M3uVodUi.playIntent(this@StremioActivity, m)); return@launch }
        val show = cat.show(id) ?: cat.showOf(id)
        if (show == null) { I18n.makeToast(this@StremioActivity, "Tej pozycji nie ma już na liście", Toast.LENGTH_SHORT).show(); return@launch }
        if (isFinishing || isDestroyed) return@launch
        Dialogs.menu(this@StremioActivity, show.title, show.episodes.map { e ->
            pl.iptv.tv.data.M3uVod.episodeLabel(e) to { startActivity(M3uVodUi.playIntent(this@StremioActivity, show, e)) }
        })
    }

    private fun addRow(title: String): Row {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(6), 0, 0) }
        // nagłówek rzędu z rysowaną ikoną w kolorze akcentu (spójną z motywem) zamiast samego tekstu
        box.addView(StremioUi.text(this, title, 18f * Ui.geo.headerScale, "accent", bold = true).apply {
            setPadding(Ui.dp(4), 0, 0, Ui.dp(2)); compoundDrawablePadding = Ui.dp(8); gravity = android.view.Gravity.CENTER_VERTICAL
            setCompoundDrawables(StremioUi.icon(rowIcon(title), 20, accent = true), null, null, null)
        })
        val status = StremioUi.text(this, "Wczytywanie…", 14f, "text2").apply { setPadding(Ui.dp(4), Ui.dp(4), 0, Ui.dp(4)) }
        box.addView(status)
        val adapter = StremioPosterAdapter()
        val rv = HRowRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@StremioActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter; itemAnimator = null
            setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(40), Ui.dp(10)); clipToPadding = false
            setItemViewCacheSize(10)   // płynniejsze przewijanie rzędów (mniej przebudowy widoków)
        }
        box.addView(rv, LinearLayout.LayoutParams(-1, -2))
        val r = Row(title, adapter, box, status)
        adapter.onClick = { t -> open(t) }
        adapter.onLongClick = { t -> titleMenu(t) }
        adapter.onFocus = { t -> showInfo(t); if (scrolledTo !== box) { scrolledTo = box; scroll.post { scroll.smoothScrollTo(0, (box.top - Ui.dp(4)).coerceAtLeast(0)) } } }
        rowsBox.addView(box); rows += r; rowTitles += title
        Ui.style(box)
        return r
    }

    /** Usunięcie pustego rzędu; zaznaczony rząd zostaje na swoim miejscu u góry (bez przesuwania widoku pod kursorem). */
    private fun removeRow(r: Row) {
        rowsBox.removeView(r.view); rows.remove(r); rowTitles.remove(r.title)
        focusedRow()?.let { box -> scroll.post { scroll.scrollTo(0, (box.top - Ui.dp(4)).coerceAtLeast(0)) } }
    }

    private fun focusedRow(): View? {
        var v: View? = window.decorView.findFocus()
        while (v != null && v.parent !== rowsBox) v = v.parent as? View
        return v
    }

    private fun focusRow(r: Row) { (r.view as LinearLayout).getChildAt(2).let { it as RecyclerView }.focusPosition(0) }

    /** (7.17) Typ katalogów dla filtru (Filmy – „movie”, Seriale – „series”, inne – wszystkie). */
    private val typeFilter: String? get() = when (filter) { FILTER_MOVIES -> "movie"; FILTER_SERIES -> "series"; else -> null }

    /** (7.17) „Do obejrzenia” (7.20; dawniej „Moja biblioteka”): tytuły dodane w szczegółach lub z menu plakatu ([pl.iptv.tv.data.StremioLibrary]), najnowsze pierwsze. */
    private fun fillLibrary(r: Row) {
        val tiles = pl.iptv.tv.data.StremioLibrary.all().map { StremioTile(it) }
        if (tiles.isEmpty()) { removeRow(r); return }
        if (tiles == r.adapter.items) return
        r.adapter.items.clear(); r.adapter.items += tiles; r.adapter.notifyDataSetChanged(); r.status.visibility = View.GONE
        if (!userMoved && autoFocusAllowed()) focusRow(r)
    }

    private suspend fun fillContinue(r: Row) {
        val type = typeFilter
        val list = StremioStore.progress().filter { !it.finished && (type == null || it.type == type) }
        if (list.isEmpty()) { removeRow(r); return }
        val tiles = list.map { p -> StremioTile(StremioMetaPreview(p.metaId, p.type, p.name, p.poster), p) }
        if (tiles == r.adapter.items) return                         // bez zmian – bez przebudowy (zaznaczenie zostaje)
        // zaznaczony kafelek w tym rzędzie (powrót z odtwarzacza) – fokus wraca na tę samą pozycję po odświeżeniu
        val rv = (r.view as LinearLayout).getChildAt(2) as RecyclerView
        val had = rv.findFocus()?.let { f -> rv.findContainingViewHolder(f)?.bindingAdapterPosition }
        r.adapter.items.clear(); r.adapter.items += tiles
        r.adapter.notifyDataSetChanged(); r.status.visibility = View.GONE
        if (had != null && had >= 0) rv.focusPosition(had.coerceAtMost(tiles.size - 1))
    }

    /**
     * (7.11) „Obejrzane”: filmy (obejrzane do końca) i seriale z zaliczonymi odcinkami, najnowsze pierwsze. Przy serialu podpis
     * i pasek na plakacie pokazują procent obejrzanego sezonu (zaliczone odcinki / odcinki sezonu) – liczony po wczytaniu opisu serialu.
     */
    private suspend fun fillWatched(r: Row) {
        val movies = StremioStore.progress(200).filter { it.type == "movie" && it.finished }
            .map { it.updatedAt to StremioTile(StremioMetaPreview(it.metaId, "movie", it.name, it.poster), note = "Film · obejrzany", fraction = 1f) }
        val byMeta = StremioStore.allWatched().groupBy { it.metaId }
        val series = byMeta.map { (id, list) ->
            val last = list.maxByOrNull { it.watchedAt }!!
            last.watchedAt to StremioTile(StremioMetaPreview(id, "series", last.name, last.poster), note = "${list.size} " + StremioUi.plural(list.size, "odcinek", "odcinki", "odcinków"))
        }
        val tiles = (movies + series).sortedByDescending { it.first }.map { it.second }
        if (tiles.isEmpty()) { removeRow(r); return }
        if (tiles == r.adapter.items) return
        val rv = rowRv(r)
        val had = rv.findFocus()?.let { f -> rv.findContainingViewHolder(f)?.bindingAdapterPosition }
        r.adapter.items.clear(); r.adapter.items += tiles; r.adapter.notifyDataSetChanged(); r.status.visibility = View.GONE
        if (had != null && had >= 0) rv.focusPosition(had.coerceAtMost(tiles.size - 1))
        byMeta.forEach { (id, list) ->
            jobs += lifecycleScope.launch {
                val m = StremioMetas.get("series", id) ?: return@launch
                val season = list.maxByOrNull { it.watchedAt }!!.season
                val pct = StremioStore.seasonPercent(m, season, list.mapTo(HashSet()) { it.videoId })
                val i = r.adapter.items.indexOfFirst { it.meta.id == id }.takeIf { it >= 0 } ?: return@launch
                r.adapter.items[i] = r.adapter.items[i].copy(note = (if (season > 0) "Sezon $season" else "Dodatki") + " · $pct%", fraction = pct / 100f)
                r.adapter.notifyItemChanged(i)
            }
        }
    }

    private fun refreshContinue() {
        // (7.23) Odkrywaj w pionie: karta „Kontynuuj oglądanie” po powrocie z odtwarzacza (nowy postęp / koniec) – bez przebudowy tablicy
        if (filter == FILTER_ALL && searchQuery == null && portrait) { lifecycleScope.launch { showContinueHero() }; return }
        // (7.17) rzędy historii są tylko w Bibliotece – inne zakładki bez przebudowy po powrocie z odtwarzacza
        if (filter != FILTER_LIBRARY || searchQuery != null) return
        val r = rows.firstOrNull { it.title == CONTINUE_ROW }
        lifecycleScope.launch {
            if (r != null) fillContinue(r)
            else if (StremioStore.progress().any { !it.finished }) build(searchQuery)
            rows.firstOrNull { it.title == WATCHED_ROW }?.let { fillWatched(it) }
        }
    }

    /** (7.12) Przytrzymanie plakatu: cały film / serial jako obejrzany albo nieobejrzany (serial – wszystkie odcinki sezonów). */
    /**
     * Menu tytułu (przytrzymanie OK na plakacie). (7.20) Okno z kartami w kolorach motywu ([CardSheet.actions]):
     * „Do obejrzenia” / „Usuń z listy do obejrzenia”, „Zwiastun” (gdy metadane mają zwiastun), oznaczenie obejrzenia.
     */
    private fun titleMenu(t: StremioTile) {
        // (7.22) pozycja TMDB – menu (lista do obejrzenia, obejrzane) na identyfikatorze IMDb
        if (pl.iptv.tv.data.StremioTmdb.isTmdbId(t.meta.id)) { withResolved(t.meta) { m -> titleMenu(t.copy(meta = m)) }; return }
        val m = t.meta
        lifecycleScope.launch {
            val meta = runCatching { kotlinx.coroutines.withTimeoutOrNull(TITLE_MENU_META_MS) { StremioMetas.get(m.type, m.id) } }.getOrNull()
            val done = StremioStore.isTitleWatched(m, meta?.takeIf { m.type == "series" })
            if (isFinishing || isDestroyed) return@launch
            val onList = pl.iptv.tv.data.StremioLibrary.contains(m.id)
            val items = buildList {
                add(CardSheet.Action(if (onList) WATCHLIST_REMOVE else StremioDetailActivity.WATCHLIST_ADD, if (onList) PlayerIcon.BOOKMARK_ADDED else PlayerIcon.BOOKMARK_ADD, key = "watchlist") {
                    val now = pl.iptv.tv.data.StremioLibrary.toggle(m)
                    I18n.makeToast(this@StremioActivity, if (now) "Dodano do listy do obejrzenia: ${m.name}" else "Usunięto z listy do obejrzenia: ${m.name}", Toast.LENGTH_SHORT).show()
                    if (filter == FILTER_LIBRARY && searchQuery == null) build(null)
                })
                meta?.trailers?.firstOrNull()?.let { yt -> add(CardSheet.Action("Zwiastun", PlayerIcon.FILM, key = "trailer") { StremioTrailer.play(this@StremioActivity, meta.name, meta.releaseInfo, yt) }) }
                add(CardSheet.Action(if (done) "Odznacz jako obejrzany" else "Oznacz jako obejrzany", PlayerIcon.CHECK, key = "watched") {
                    lifecycleScope.launch {
                        StremioStore.setTitleWatched(m, meta?.takeIf { m.type == "series" }, !done)
                        I18n.makeToast(this@StremioActivity, if (done) "Odznaczono: ${m.name}" else "Oznaczono jako obejrzany: ${m.name}", Toast.LENGTH_SHORT).show()
                        // rząd „Obejrzane” pojawia się dopiero po pierwszej pozycji – wtedy tablica wczytuje się od nowa
                        if (searchQuery == null && rows.none { it.title == WATCHED_ROW } && !done) build(null) else refreshContinue()
                    }
                })
            }
            CardSheet.actions(this@StremioActivity, meta?.name ?: m.name, items, subtitle = StremioUi.typeName(m.type) + (m.releaseInfo.ifBlank { null }?.let { " · $it" } ?: ""))
        }
    }

    /** Opis zaznaczonego tytułu; bez opisu w katalogu – metadane wczytane po chwili. */
    private fun showInfo(t: StremioTile) {
        val m = t.meta
        infoTitle.text = m.name
        infoMeta.text = StremioUi.metaLine(m.type, m.releaseInfo, m.imdbRating, m.genres) + (t.progress?.let { p -> "   ·   ▶ " + listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" ") } ?: "")
        infoDesc.text = m.description
        infoId = m.id
        focusJob?.cancel()
        // (7.20) aplikacja po polsku (z kluczem TMDB): opis, tytuł i gatunki z metadanych pl-PL zamiast angielskich z katalogu
        val polish = pl.iptv.tv.data.StremioTmdb.polishWanted && pl.iptv.tv.data.StremioTmdb.available
        // (7.22) pozycje TMDB mają już polski tytuł i opis – bez zapytań o metadane przy każdym ruchu kursora
        if (pl.iptv.tv.data.StremioTmdb.isTmdbId(m.id)) return
        if (m.description.isBlank() || polish) focusJob = lifecycleScope.launch {
            delay(350)
            val full = StremioMetas.get(m.type, m.id) ?: return@launch
            if (infoId == m.id) {
                if (polish) infoTitle.text = full.name.ifBlank { m.name }
                infoDesc.text = full.description.ifBlank { m.description }
                infoMeta.text = StremioUi.metaLine(m.type, full.releaseInfo, full.imdbRating, full.genres)
            }
        }
    }

    /** (7.8) „Kontynuuj oglądanie” – od razu odtwarzanie ([StremioPlayerActivity.resumeIntent]); katalog – ekran szczegółów. */
    private fun open(t: StremioTile) {
        t.progress?.let { startActivity(StremioPlayerActivity.resumeIntent(this, it)); return }
        if (pl.iptv.tv.data.M3uVod.isM3uId(t.meta.id)) { openM3u(t); return }     // (7.23) pozycja z listy M3U
        // (7.22) pozycja TMDB (Top 10, „Obecnie w kinach”): external_ids → IMDb (tt…), potem pełny ekran ze źródłami wtyczek
        if (pl.iptv.tv.data.StremioTmdb.isTmdbId(t.meta.id)) { withResolved(t.meta) { m -> startActivity(StremioDetailActivity.intent(this, m, null)) }; return }
        startActivity(StremioDetailActivity.intent(this, t.meta, null))
    }

    /** (7.22) Pozycja TMDB z identyfikatorem IMDb; brak odpowiednika w Stremio / sieci – komunikat (bez otwierania). */
    private fun withResolved(m: StremioMetaPreview, then: (StremioMetaPreview) -> Unit) {
        if (!pl.iptv.tv.data.StremioTmdb.isTmdbId(m.id)) { then(m); return }
        lifecycleScope.launch {
            val r = runCatching { pl.iptv.tv.data.StremioTmdb.resolve(m) }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (r == null) I18n.makeToast(this@StremioActivity, "Tego tytułu nie ma jeszcze w katalogach Stremio: ${m.name}", Toast.LENGTH_SHORT).show()
            else then(r)
        }
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.iptv.tv.R
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeImport
import pl.iptv.tv.data.StubeQueue

/**
 * STube – przeglądanie YouTube na TV: wyszukiwarka u góry (podpowiedzi z historii) i poziome rzędy (Kontynuuj oglądanie, Dla Ciebie,
 * Subskrypcje, Na czasie, Muzyka, Na żywo, Biblioteka / Playlisty, Kanały). Playlista lub kanał otwiera się w rzędzie pod źródłem (WSTECZ zamyka).
 * Dane: YouTube Data API v3 ([YouTubeRepository]); odtwarzanie i wyszukiwanie zewnętrzne: SmartTube.
 * Pilot: ▲▼ między rzędami (powrót na ostatnio zaznaczony kafelek rzędu), ◄► w rzędzie (bez wypadania fokusu na końcach),
 * ▼ z wyszukiwarki do pierwszego rzędu, ▲ z pierwszego rzędu do wyszukiwarki.
 */
class STubeActivity : BaseActivity() {
    override val pausesLiveTv = true
    /** Wąski ekran (telefon w pionie) – zwarty pasek u góry. */
    private val narrowHeader get() = resources.configuration.screenWidthDp < 600
    override val x25Root = true
    override val miniPlayer = true
    /** Wygląd, z którym zbudowano ekran (zmiana motywu STube w Ustawieniach → przebudowa). */
    private var builtLook = ""

    override fun onPause() { super.onPause(); StubeTheme.exit(); if (StubeImportUi.current == null) StubeImport.onPending = null }

    companion object {
        /** Fraza do wyszukania od razu (polecenie z telefonu). */
        const val EXTRA_QUERY = "query"
        /** Zwłoka podglądu kategorii / podkategorii przy najechaniu (ms). */
        const val LIVE_PREVIEW_MS = 200L
        /** Zwłoka po ostatniej wpisanej literze przed zapytaniem o podpowiedzi na żywo; maksymalna liczba podpowiedzi. */
        const val SUGGEST_DEBOUNCE_MS = 220L; const val SUGGEST_MAX = 8
        /** Pulpit X25: rząd do zaznaczenia od razu (id z [STubeScreen], np. „library”, „subs”). */
        const val EXTRA_ROW = "row"
        /** Pulpit X25: playlista do otwarcia od razu (id jak w [STubeScreen.playlistRow]) i jej tytuł. */
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_PLAYLIST_TITLE = "playlist_title"
    }

    private lateinit var query: AutoCompleteTextView
    private lateinit var suggest: STubeSuggestAdapter
    private var suggestJob: Job? = null
    private lateinit var info: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var account: TextView
    private lateinit var rail: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: android.widget.HorizontalScrollView
    /** Pigułki biblioteki (testy). */
    val chipViews: List<View> get() = (0 until chips.childCount).map(chips::getChildAt)

    /** Bieżące rzędy (testy). */
    var rows: List<STubeRow> = emptyList(); private set
    private val rowViews = mutableListOf<RecyclerView>()
    private val jobs = mutableListOf<Job>()
    private val rowJobs = java.util.WeakHashMap<STubeRow, Job>()
    private var builtFor: String? = null
    private var searchQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        StubeTheme.enter(); builtLook = StubeTheme.key
        super.onCreate(savedInstanceState)
        setThemedContent(R.layout.activity_stube)
        query = findViewById(R.id.stQuery)
        info = findViewById(R.id.stInfo)
        rowsBox = findViewById(R.id.stRows)
        scroll = findViewById(R.id.stScroll)
        account = findViewById(R.id.stAccount)
        rail = findViewById(R.id.stRail); chips = findViewById(R.id.stChips); chipScroll = findViewById(R.id.stChipScroll)
        for ((id, desc) in listOf(R.id.stSearch to "Szukaj", R.id.stHome to "Start", R.id.stLib to "Biblioteka", R.id.stAdd to "Dodaj playlistę (link YouTube / Spotify)", R.id.stPhone to "Sterowanie z telefonu",
            R.id.stNow to "Teraz odtwarzane – otwórz odtwarzacz", R.id.stSmartTube to "SmartTube", R.id.stRefresh to "Odśwież", R.id.stClose to "Zamknij")) findViewById<View>(id).contentDescription = desc
        styleRail()
        findViewById<View>(R.id.stHome).setOnClickListener { goStart() }
        findViewById<View>(R.id.stLib).setOnClickListener { jumpTo(STubeScreen.ROW_LIBRARY) }
        findViewById<View>(R.id.stAdd).setOnClickListener { StubeImportUi.show(this) }
        if (narrowHeader) {
            // wąski ekran (telefon w pionie): pasek ikon poziomo u góry, bez tytułu – wszystko mieści się w szerokości
            val root = findViewById<LinearLayout>(R.id.stRoot); root.orientation = LinearLayout.VERTICAL
            rail.orientation = LinearLayout.HORIZONTAL; rail.setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(6), 0)
            run {   // 16 ikon nie mieści się w szerokości – pasek przewijany poziomo
                val vs = findViewById<android.view.ViewGroup>(R.id.stRailScroll); vs.removeView(rail); root.removeView(vs)
                val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isFocusable = false; clipChildren = false; addView(rail, android.widget.FrameLayout.LayoutParams(-2, -2)) }
                root.addView(hs, 0, LinearLayout.LayoutParams(-1, -2))
            }
            findViewById<View>(R.id.stMain).let { it.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); it.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(8)) }
            findViewById<View>(R.id.stTitle).visibility = View.GONE
            findViewById<View>(R.id.stRailSpace).layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            for (i in 0 until rail.childCount) rail.getChildAt(i).let { b ->
                if (b.id == R.id.stRailSpace) return@let
                b.layoutParams = LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)).apply { marginStart = Ui.dp(2); marginEnd = Ui.dp(2) }
            }
            findViewById<View>(R.id.stSearch).let { (it.layoutParams as LinearLayout.LayoutParams).topMargin = 0 }
        }

        findViewById<View>(R.id.stClose).setOnClickListener { finish() }
        findViewById<View>(R.id.stRefresh).setOnClickListener { YouTubeRepository.clearCache(); pl.iptv.tv.data.YtKeyless.clearCache(); build(force = true) }
        findViewById<View>(R.id.stSmartTube).setOnClickListener { SmartTubeIntegrationHelper.launch(this) }
        // skrót „Teraz odtwarzane”: ikona na pasku (widoczna, gdy coś gra w tle) otwiera odtwarzacz – bez zjeżdżania na dół ekranu
        findViewById<View>(R.id.stNow).let { now ->
            now.setOnClickListener { startActivity(YtPlayerActivity.resumeIntent(this)) }
            miniBar?.let { b ->
                val prev = b.onVisibilityChanged
                b.onVisibilityChanged = { on -> prev?.invoke(on); now.visibility = if (on) View.VISIBLE else View.GONE }
                if (b.shown) now.visibility = View.VISIBLE
            }
        }
        account.setOnClickListener { openAccount() }
        findViewById<View>(R.id.stSearch).setOnClickListener { search(query.text.toString()) }
        // dotknięcie / OK na polu – klawiatura systemowa (na TV samo zaznaczenie pola jej nie pokazuje)
        query.setOnClickListener { showKeyboard() }
        query.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                search(query.text.toString()); true
            } else false
        }
        searchQuery = (savedInstanceState?.getString("q") ?: intent.getStringExtra(EXTRA_QUERY)?.trim())?.ifBlank { null }
        searchQuery?.let { query.setText(it) }
        findViewById<View>(R.id.stPhone).setOnClickListener { StubeRemoteUi.show(this) }
        setupSuggestions()
        setupCategories()
        // pierwszy fokus: pierwszy kafelek (wyszukiwarka jest o jedno ▲ wyżej, klawiatura nie wyskakuje od razu)
        if (StubeHistory.cacheLoaded) { build(); focusRow(startRow(), fallback = query); openTarget(intent) }
        else lifecycleScope.launch {
            // postęp oglądania do pamięci (paski na kafelkach, rząd „Kontynuuj oglądanie”) – przed pierwszym układem rzędów
            StubeHistory.loadCache()
            build(); focusRow(startRow(), fallback = query); openTarget(intent)
        }
    }

    /** Rząd z [EXTRA_ROW] (gdy jest na ekranie), inaczej pierwszy. */
    private fun startRow(): Int = intent.getStringExtra(EXTRA_ROW)?.let { id -> rows.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0

    /** Playlista z [EXTRA_PLAYLIST] otwierana pod Biblioteką (miks – najpierw lista miksów z historii). */
    private fun openTarget(i: Intent) {
        val id = i.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() } ?: return
        val title = i.getStringExtra(EXTRA_PLAYLIST_TITLE).orEmpty()
        lifecycleScope.launch {
            if (id.startsWith(STubeScreen.PL_MIX) && pl.iptv.tv.data.StubeMixes.byId(id.removePrefix(STubeScreen.PL_MIX)) == null)
                runCatching { pl.iptv.tv.data.StubeMixes.current() }
            closeCategory()
            val from = rows.firstOrNull { it.id == STubeScreen.ROW_LIBRARY } ?: rows.firstOrNull() ?: return@launch
            openPlaylist(from, pl.iptv.tv.data.YtPlaylist(id, title))
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("q", searchQuery) }

    /** Wyszukiwanie z telefonu przy otwartym STube. */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { q -> query.setText(q); search(q) }
    }

    override fun onResume() {
        super.onResume()
        StubeTheme.enter()
        if (builtLook != StubeTheme.key) { recreate(); return }
        // (7.14) link do playlisty odebrany z telefonu (kanał listy M3U): okno „＋” od razu z podglądem
        StubeImport.onPending = { if (StubeImportUi.current == null) StubeImportUi.show(this) }
        if (StubeImport.pending != null && StubeImportUi.current == null) StubeImportUi.show(this)
        if (builtFor == null) return   // pierwsze rzędy po wczytaniu postępu (onCreate)
        // powrót z Ustawień / odtwarzacza: nowe rzędy (konto, źródło, historia) – z zachowaniem rzędu i kafelka, na którym był fokus
        if (builtFor != stateKey()) buildKeepingPosition()
        else refreshProgress()
    }

    /** Biblioteka po zapisaniu / usunięciu przesłanej playlisty (kafelki i „Twoje miksy”). */
    fun libraryChanged() { if (builtFor != stateKey()) buildKeepingPosition() }

    /** Przebudowa rzędów bez gubienia miejsca: fokus wraca na ten sam rząd (po id) i kafelek, o ile rząd nadal istnieje. */
    private fun buildKeepingPosition() {
        val ri = rowIndexOf(window.decorView.findFocus())
        val keepId = rows.getOrNull(ri)?.id; val keepTile = rows.getOrNull(ri)?.lastFocus ?: 0
        build()
        if (keepId == null) return
        val ni = rows.indexOfFirst { it.id == keepId }
        if (ni >= 0) { rows[ni].lastFocus = keepTile; focusRow(ni, fallback = query) }
    }

    private fun stateKey() = "${YouTubeTokens.loggedIn}|${YouTubeRepository.source}|${pl.iptv.tv.data.Prefs.ytRegion}|${searchQuery.orEmpty()}|" +
        "${StubeHistory.hasUnfinished}|${STubeScreen.hasMixHistory()}|${pl.iptv.tv.data.StubeImport.version}"

    /**
     * Powrót z odtwarzacza: paski obejrzenia odświeżone w miejscu (payload – bez migania miniatur, fokus zostaje),
     * rząd „Kontynuuj oglądanie” wczytany ponownie (ostatnio oglądany film na początku).
     */
    private fun refreshProgress() {
        rows.forEach { r -> if (r.tiles.isNotEmpty()) r.adapter.notifyItemRangeChanged(0, r.tiles.size, STubeScreen.PAYLOAD_PROGRESS) }
        categoryRow?.let { r -> if (r.tiles.isNotEmpty()) r.adapter.notifyItemRangeChanged(0, r.tiles.size, STubeScreen.PAYLOAD_PROGRESS) }
        rows.firstOrNull { it.id == STubeScreen.ROW_CONTINUE }?.let { r ->
            jobs += lifecycleScope.launch {
                val fresh = StubeHistory.unfinished().map { STubeTile.Video(it.toVideo()) }
                if (fresh.isEmpty() || fresh == r.tiles) return@launch
                // ostatnio oglądany na początku – fokus na nim
                r.lastFocus = 0; reloadInPlace(r, fresh)
            }
        }
        // otwarta „Historia oglądania” – nowa kolejność po obejrzeniu filmu
        playlistRow?.takeIf { it.source?.id == STubeScreen.PL_HISTORY }?.let { r ->
            jobs += lifecycleScope.launch { reloadInPlace(r, StubeHistory.watched().map { STubeTile.Video(it.toVideo()) }) }
        }
        watchLaterChanged()
    }

    // ---------------- klawiatura ekranowa ----------------

    /**
     * Klawiatura systemowa dla pola wyszukiwania (Android TV nie pokazuje jej po samym zaznaczeniu pola pilotem):
     * fokus na polu → kontroler okien (Android 11+) → InputMethodManager, z ponowieniem po rozmieszczeniu okna.
     * Akcja „Szukaj” klawiatury (IME_ACTION_SEARCH) wyszukuje.
     */
    fun showKeyboard() {
        suggestJob?.cancel(); query.dismissDropDown()
        if (!query.hasFocus()) query.requestFocus()
        query.setSelection(query.text.length)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        androidx.core.view.WindowCompat.getInsetsController(window, query).show(androidx.core.view.WindowInsetsCompat.Type.ime())
        if (imm?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT) != true) {
            query.postDelayed({
                if (!query.hasFocus()) query.requestFocus()
                @Suppress("DEPRECATION") if (imm?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT) != true) imm?.showSoftInput(query, InputMethodManager.SHOW_FORCED)
            }, 150)
        }
        keyboardRequested = true
    }

    /** Klawiatura wywołana (testy). */
    var keyboardRequested = false; private set

    // ---------------- podpowiedzi (historia wyszukiwania) ----------------

    private fun setupSuggestions() {
        suggest = STubeSuggestAdapter(this)
        query.setAdapter(suggest)
        query.setDropDownBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.panel or 0xFF000000.toInt()); cornerRadius = Ui.dp(maxOf(Ui.preset.radius, 6)).toFloat()
            setStroke(Ui.dp(1), Ui.withAlpha(Ui.accent, 0x80))
        })
        query.dropDownVerticalOffset = Ui.dp(6)
        query.setOnItemClickListener { _, _, pos, _ ->
            suggest.items.getOrNull(pos)?.let { q -> query.setText(q); query.setSelection(q.length); search(q) }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.toString()?.trim() != searchQuery) suppressSuggest = false
                if (query.hasFocus()) loadSuggestions()
            }
        })
        // ▲ z rzędów do pola – od razu ostatnie frazy (jak w YouTube na TV); ▼ wchodzi w listę, OK = szukaj
        query.setOnFocusChangeListener { _, f -> if (f) loadSuggestions() else { suppressSuggest = false; suggestJob?.cancel(); query.dismissDropDown() } }
    }

    /** Po wyszukaniu lista nie wraca, dopóki pole nie straci fokusu albo fraza się nie zmieni. */
    private var suppressSuggest = false

    private fun loadSuggestions() {
        if (suppressSuggest) return
        suggestJob?.cancel()
        val typed = query.text.toString()
        suggestJob = lifecycleScope.launch {
            // najpierw lokalna historia (natychmiast), potem – po krótkiej zwłoce na kolejne litery – podpowiedzi na żywo
            val hist = StubeHistory.suggestions(typed)
            fun stale() = !query.hasFocus() || query.text.toString() != typed || suppressSuggest
            if (stale()) return@launch
            show(hist, hist.toSet())
            if (typed.trim().length < 2) return@launch
            kotlinx.coroutines.delay(SUGGEST_DEBOUNCE_MS)
            val live = pl.iptv.tv.data.YtSuggest.fetch(typed)
            if (stale() || live.isEmpty()) return@launch
            val typedKey = typed.trim().lowercase()
            val merged = (hist + live).distinctBy { it.lowercase() }.filter { it.lowercase() != typedKey }.take(SUGGEST_MAX)
            show(merged, hist.toSet())
        }
    }

    private fun show(list: List<String>, fromHistory: Set<String>) {
        suggest.set(list, fromHistory)
        if (list.isEmpty()) { query.dismissDropDown(); return }
        // zmiana danych adaptera zleca AutoCompleteTextView zamknięcie listy (próg podpowiedzi = 1000) – pokazanie dopiero po tym zleceniu
        query.post {
            if (!isFinishing && !suppressSuggest && query.hasFocus() && suggest.items.isNotEmpty() && window.decorView.windowToken != null) runCatching {
                query.dropDownWidth = query.width.coerceAtLeast(Ui.dp(240))   // lista tylko pod polem (nie zasłania paska ikon)
                query.showDropDown()
            }
        }
    }

    /** Podpowiedzi widoczne (testy). */
    val suggestions: List<String> get() = suggest.items

    private fun openAccount() = startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", SettingsActivity.YOUTUBE_TAB))

    private fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) { showKeyboard(); return }   // pusta fraza – klawiatura do wpisania
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
        suppressSuggest = true; suggestJob?.cancel(); query.dismissDropDown()
        closeCategory()
        // fraza do historii (podpowiedzi przy kolejnym wyszukiwaniu) – zapis w tle
        StubeHistory.scope.launch { runCatching { StubeHistory.addSearch(t) } }
        // wyszukiwanie działa także bez klucza API i konta (YtKeyless); SmartTube – kafelek na końcu wyników
        searchQuery = t
        build()
        focusRow(0, fallback = query)
    }

    // ---------------- rzędy ----------------

    private fun build(force: Boolean = false) {
        jobs.forEach { it.cancel() }; jobs.clear()
        builtFor = stateKey()
        // konto: pierwsza litera nazwy po zalogowaniu, inaczej rysowana ikona użytkownika
        val initial = if (YouTubeTokens.loggedIn) YouTubeTokens.accountName.trim().take(1).uppercase() else ""
        account.text = initial; account.textSize = 20f; account.typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (initial.isNotEmpty()) { account.setCompoundDrawables(null, null, null, null); account.setPadding(0, 0, 0, 0); account.gravity = Gravity.CENTER }
        else account.setCompoundDrawables(PlayerIcon(PlayerIcon.USER, account.textColors).apply { setBounds(0, 0, Ui.dp(24), Ui.dp(24)) }, null, null, null)
        account.contentDescription = if (YouTubeTokens.loggedIn) "Konto YouTube: " + YouTubeTokens.accountName.ifBlank { "Konto" } else "Zaloguj do YouTube"
        info.visibility = View.GONE
        rows = STubeScreen.rows(searchQuery)
        rowViews.clear(); rowsBox.removeAllViews(); playlistParent = null
        rows.forEachIndexed { i, r -> rowsBox.addView(rowView(r, i)) }
        Ui.style(rowsBox)
        rows.forEach { load(it, null, force) }
        buildChips()
    }


    // ---------------- kategorie na pasku bocznym (7.11): od razu pełna siatka, fokus na pierwszym filmie ----------------

    private lateinit var grid: RecyclerView
    private lateinit var gridBox: View
    private lateinit var gridStatus: TextView
    private val categoryIds = intArrayOf(R.id.stCat0, R.id.stCat1, R.id.stCat2, R.id.stCat3, R.id.stCat4, R.id.stCat5, R.id.stCat6)
    private var categoryJob: Job? = null
    /** Otwarta kategoria (null – zwykły ekran rzędów); testy. */
    var categoryRow: STubeRow? = null; private set
    private var category: STubeScreen.Category? = null
    val categoryGrid: RecyclerView get() = grid

    /** Liczba kolumn siatki z szerokości (okładki kwadratowe – węższa komórka niż miniatura 16:9). */
    private fun updateSpan() {
        val lay = StubeTheme.layout
        val cellW = if (categoryRow?.square == true) STubeScreen.squareSide(lay) + 12 else lay.tileW
        val cell = Ui.dp(cellW) + Ui.dp(lay.gap)
        val w = grid.width - grid.paddingLeft - grid.paddingRight
        if (w > 0) (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount = maxOf(1, w / cell)
    }

    private fun setupCategories() {
        grid = findViewById(R.id.stGrid); gridBox = findViewById(R.id.stGridBox); gridStatus = findViewById(R.id.stGridStatus)
        grid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        grid.itemAnimator = null; grid.setHasFixedSize(false); grid.isFocusable = false; grid.setItemViewCacheSize(12)
        grid.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        // przycięta siatka z miejscem na powiększony kafelek (zaznaczenie nie jest ucinane przy krawędzi)
        grid.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(8), Ui.dp(120)); grid.clipToPadding = false
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: android.graphics.Rect, v: View, p: RecyclerView, s: RecyclerView.State) { out.bottom = Ui.dp(StubeTheme.layout.gap) }
        })
        // liczba kolumn z szerokości (TV, tablet, telefon w pionie i poziomie)
        grid.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol) return@addOnLayoutChangeListener
            updateSpan()
        }
        categoryIds.forEachIndexed { i, id ->
            val c = STubeScreen.CATEGORIES[i]
            findViewById<View>(id).apply {
                contentDescription = c.title
                // OK: kategoria już pokazana (podgląd na żywo) → kursor na pierwszy film
                setOnClickListener { if (category?.id == c.id) focusGridWhenReady() else openCategory(c) }
                // najechanie ikoną od razu pokazuje kategorię (po krótkiej zwłoce – szybkie przejście ▲▼ nie ładuje każdej po drodze)
                setOnFocusChangeListener { _, f -> if (f) scheduleLive { if (category?.id != c.id) openCategory(c, focusGrid = false) } else cancelLive() }
            }
        }
    }

    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var liveTask: Runnable? = null
    /** Zwłoka podglądu na żywo przy najechaniu ikoną kategorii / podkategorii. */
    private fun scheduleLive(action: () -> Unit) {
        cancelLive()
        val t = Runnable { liveTask = null; action() }
        liveTask = t; liveHandler.postDelayed(t, LIVE_PREVIEW_MS)
    }
    private fun cancelLive() { liveTask?.let(liveHandler::removeCallbacks); liveTask = null }
    /** Po załadowaniu siatki kursor ma przejść na pierwszy film (OK / ► na ikonie, zanim podgląd się wczytał). */
    private var wantGridFocus = false
    private var sub: STubeScreen.Sub? = null

    /** Kursor na pierwszy film siatki – od razu albo zaraz po wczytaniu. */
    fun focusGridWhenReady() {
        val row = categoryRow ?: return
        if (row.loading) wantGridFocus = true else focusFirstInGrid()
    }

    /** Pigułki podkategorii nad siatką (najechanie pokazuje podkategorię na żywo, OK / ▼ – kursor na pierwszy film). */
    private fun buildSubChips(c: STubeScreen.Category) {
        chips.removeAllViews()
        subChips = c.subs.map { s -> Chip(s.title, "", { scheduleLive { if (sub?.id != s.id) showSub(c, s, focusGrid = false) } },
            { if (sub?.id == s.id) focusGridWhenReady() else showSub(c, s, focusGrid = true) }) }
        fillChips(subChips)
        chipScroll.visibility = if (c.subs.size > 1) View.VISIBLE else View.GONE
        markSubChip()
    }
    private var subChips: List<Chip> = emptyList()
    private fun markSubChip() { val c = category ?: return; for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = c.subs.getOrNull(i)?.id == sub?.id }

    /** Zmiana podkategorii bez przebudowy pigułek (kursor zostaje na pigułce). */
    private fun showSub(c: STubeScreen.Category, s: STubeScreen.Sub, focusGrid: Boolean) { openCategory(c, focusGrid, s, rebuildChips = false) }

    /** Wejście w kategorię: poprzednie ładowanie anulowane, ekran rzędów zastąpiony siatką, fokus na pierwszym filmie po wczytaniu. */
    fun openCategory(c: STubeScreen.Category, focusGrid: Boolean = true, subcategory: STubeScreen.Sub? = null, rebuildChips: Boolean = true) {
        categoryJob?.cancel(); closePlaylist(refocus = false)
        category = c
        sub = subcategory ?: c.subs.firstOrNull()
        wantGridFocus = focusGrid
        val row = STubeScreen.categoryRow(c, sub)
        categoryRow = row
        row.square = false
        updateSpan()
        row.adapter.onClick = { t -> onTile(row, t) }
        row.adapter.onLongClick = { t -> tileMenu(row, t) }
        row.adapter.onFocus = { pos -> row.lastFocus = pos; if (pos >= row.tiles.size - 6 && row.next != null && !row.loading) loadCategoryPage(row) }
        grid.adapter = row.adapter
        findViewById<TextView>(R.id.stTitle).apply { text = c.title; if (narrowHeader) visibility = View.VISIBLE }
        if (rebuildChips) buildSubChips(c) else markSubChip()
        scroll.visibility = View.GONE; info.visibility = View.GONE
        gridBox.visibility = View.VISIBLE
        categoryIds.forEachIndexed { i, id -> findViewById<View>(id).isSelected = STubeScreen.CATEGORIES[i].id == c.id }
        gridStatus.text = "Wczytywanie…"; gridStatus.translationY = 0f; gridStatus.visibility = View.VISIBLE
        loadCategory(row)
    }

    private fun loadCategory(row: STubeRow) {
        row.loading = true
        categoryJob = lifecycleScope.launch {
            try {
                val p = row.load(null)
                if (categoryRow !== row) return@launch   // w międzyczasie inna kategoria – stare wyniki nie wracają
                row.tiles.clear(); row.tiles += p.tiles; row.next = p.next; row.error = null
                if (row.tiles.isEmpty()) {
                    // pusty widok z możliwością ponowienia (OK) albo powrotu na pasek (◄)
                    row.tiles += STubeTile.Action(STubeScreen.ACT_RETRY, "⟳", "Ponów")
                    gridStatus.text = "Brak materiałów w tej kategorii"; gridStatus.visibility = View.VISIBLE
                    gridStatus.translationY = -Ui.dp(90).toFloat()
                } else gridStatus.visibility = View.GONE
                row.adapter.notifyDataSetChanged()
                grid.scrollToPosition(0)
                if (wantGridFocus) { wantGridFocus = false; focusFirstInGrid() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (categoryRow !== row) return@launch
                row.error = e.message ?: e.javaClass.simpleName
                row.tiles.clear(); row.tiles += STubeTile.Action(STubeScreen.ACT_RETRY, "⟳", "Ponów"); row.adapter.notifyDataSetChanged()
                gridStatus.text = "⚠ ${row.error}"; gridStatus.visibility = View.VISIBLE; gridStatus.translationY = -Ui.dp(90).toFloat()
                if (wantGridFocus) { wantGridFocus = false; focusFirstInGrid() }
            } finally { row.loading = false }
        }
    }

    private fun loadCategoryPage(row: STubeRow) {
        val page = row.next ?: return
        row.loading = true
        categoryJob = lifecycleScope.launch {
            try {
                val p = row.load(page)
                if (categoryRow !== row) return@launch
                val start = row.tiles.size; row.tiles += p.tiles; row.next = p.next
                row.adapter.notifyItemRangeInserted(start, p.tiles.size)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { row.next = null } finally { row.loading = false }
        }
    }

    private fun categoryIcon(): View = findViewById(categoryIds[STubeScreen.CATEGORIES.indexOfFirst { it.id == category?.id }.coerceAtLeast(0)])

    /** Fokus na pierwszym filmie siatki (ponawiany do rozmieszczenia siatki; nie odbiera kursora, gdy użytkownik już się ruszył). */
    private fun focusFirstInGrid() {
        grid.scrollToPosition(0)
        grid.focusPosition(0, fallback = categoryIcon())
    }

    /** Powrót z kategorii do zwykłego ekranu rzędów (Start, wyszukiwanie, biblioteka). */
    private fun closeCategory(): Boolean {
        if (categoryRow == null) return false
        categoryJob?.cancel(); cancelLive(); categoryRow = null; category = null; sub = null; wantGridFocus = false
        gridBox.visibility = View.GONE; gridStatus.visibility = View.GONE; gridStatus.translationY = 0f
        grid.adapter = null
        scroll.visibility = View.VISIBLE
        buildChips()   // pigułki biblioteki w miejsce podkategorii
        findViewById<TextView>(R.id.stTitle).apply { text = "STube"; if (narrowHeader) visibility = View.GONE }
        categoryIds.forEach { findViewById<View>(it).isSelected = false }
        return true
    }

    /** Klawisze siatki kategorii: krawędzie bez ucieczki kursora; ◄ z pierwszej kolumny → ikona kategorii na pasku, ▲ z pierwszego wiersza → pole szukania. */
    private fun gridKey(event: KeyEvent): Boolean {
        val f = window.decorView.findFocus() ?: return false
        var v: View? = f; while (v != null && v.parent !== grid) v = v.parent as? View
        v ?: return false
        val pos = grid.getChildAdapterPosition(v); val n = grid.adapter?.itemCount ?: return false
        val span = (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount
        val down = event.action == KeyEvent.ACTION_DOWN
        fun act(b: () -> Unit): Boolean { if (down) b(); return true }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pos % span == 0) act { if (!narrowHeader) categoryIcon().requestFocus() } else false
            KeyEvent.KEYCODE_DPAD_RIGHT -> pos % span == span - 1 || pos == n - 1
            KeyEvent.KEYCODE_DPAD_UP -> if (pos < span) act { selectedSubChip()?.requestFocus() ?: query.requestFocus() } else false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pos / span >= (n - 1) / span) act { if (miniBar?.shown == true) miniBar?.focusBar() } else false
            else -> false
        }
    }
    // ---------------- pigułki biblioteki (Ostatnie / Playlisty / Miksy / Polubione / Subskrypcje) ----------------

    private val RAIL_ICONS = mapOf(R.id.stAccount to PlayerIcon.USER, R.id.stSearch to PlayerIcon.SEARCH, R.id.stHome to PlayerIcon.HOME, R.id.stLib to PlayerIcon.QUEUE, R.id.stAdd to PlayerIcon.PLUS,
        R.id.stCat0 to PlayerIcon.NEWS, R.id.stCat1 to PlayerIcon.TRENDING, R.id.stCat2 to PlayerIcon.MUSIC, R.id.stCat3 to PlayerIcon.SPORT, R.id.stCat4 to PlayerIcon.KIDS, R.id.stCat5 to PlayerIcon.HEADLINES, R.id.stCat6 to PlayerIcon.GAMES,
        R.id.stPhone to PlayerIcon.PHONE, R.id.stNow to PlayerIcon.AUDIO_ONLY, R.id.stSmartTube to PlayerIcon.PLAY, R.id.stRefresh to PlayerIcon.REFRESH, R.id.stClose to PlayerIcon.CLOSE)

    /** Ikony paska: okrągłe, bez ramek; zaznaczona – wypełnienie koloru zaznaczenia motywu (jak aktywna ikona w aplikacjach muzycznych na TV). */
    private fun styleRail() {
        val foc = intArrayOf(android.R.attr.state_focused); val pr = intArrayOf(android.R.attr.state_pressed)
        for (i in 0 until rail.childCount) (rail.getChildAt(i) as? TextView)?.let { b ->
            b.tag = "keepcolor nozoom"
            b.setTextColor(android.content.res.ColorStateList(arrayOf(foc, pr, intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(Ui.focusText, Ui.focusText, Ui.accent or 0xFF000000.toInt(), Ui.text)))
            // ikona rysowana (jak w odtwarzaczach) zamiast emoji: spójna z motywem, ostra w każdym rozmiarze, wyśrodkowana
            RAIL_ICONS[b.id]?.let { kind ->
                if (b.id == R.id.stAccount) { b.gravity = Gravity.CENTER; b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if ((v as TextView).text.isEmpty()) { val pad = (v.width - Ui.dp(24)) / 2; if (v.paddingLeft != pad) v.setPadding(pad, 0, pad, 0) } }; return@let }
                b.text = ""
                b.setCompoundDrawables(PlayerIcon(kind, b.textColors).apply { setBounds(0, 0, Ui.dp(24), Ui.dp(24)) }, null, null, null)
                b.gravity = Gravity.CENTER_VERTICAL
                b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> val pad = (v.width - Ui.dp(24)) / 2; if (v.paddingLeft != pad) v.setPadding(pad, 0, pad, 0) }
            }
            b.background = android.graphics.drawable.StateListDrawable().apply {
                val on = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.focus or 0xFF000000.toInt()) }
                val active = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.withAlpha(Ui.accent, 0x33)); setStroke(Ui.dp(2), Ui.accent or 0xFF000000.toInt()) }
                addState(foc, on); addState(pr, on); addState(intArrayOf(android.R.attr.state_selected), active); addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
        }
    }

    private class Chip(val label: String, val icon: String = PlayerIcon.MUSIC, val onFocus: (() -> Unit)? = null, val action: () -> Unit)

    private fun chipSpecs(): List<Chip> = buildList {
        add(Chip("Start", PlayerIcon.MUSIC) { goStart() })
        if (rows.any { it.id == STubeScreen.ROW_CONTINUE }) add(Chip("Ostatnie", PlayerIcon.DELAY) { jumpTo(STubeScreen.ROW_CONTINUE) })
        else add(Chip("Ostatnie", PlayerIcon.DELAY) { openLibraryList(pl.iptv.tv.data.YtPlaylist(STubeScreen.PL_HISTORY, "Historia oglądania")) })
        if (rows.any { it.id == STubeScreen.ROW_MIXES }) add(Chip("Twoje miksy", PlayerIcon.AUTOPLAY) { jumpTo(STubeScreen.ROW_MIXES) })
        add(Chip("Playlisty", PlayerIcon.QUEUE) { jumpTo(STubeScreen.ROW_LIBRARY) })
        if (YouTubeTokens.loggedIn) add(Chip("Polubione", PlayerIcon.INFO) { openLibraryList(pl.iptv.tv.data.YtPlaylist(STubeScreen.PL_LIKED, "Polubione filmy")) })
        else add(Chip("Na później", PlayerIcon.DELAY) { openLibraryList(pl.iptv.tv.data.YtPlaylist(STubeScreen.PL_LATER, "Do obejrzenia później")) })
        if (YouTubeTokens.loggedIn) add(Chip("Subskrypcje", PlayerIcon.RELATED) { jumpTo(if (rows.any { it.id == STubeScreen.ROW_SUBS }) STubeScreen.ROW_SUBS else STubeScreen.ROW_CHANNELS) })
    }

    /** Pigułki: zaokrąglone do pełna, kolory z motywu (stan zaznaczenia jak w całej aplikacji), treść przycięta do kształtu. */
    private fun buildChips() {
        if (categoryRow != null) return   // w kategorii pigułki to podkategorie
        chips.removeAllViews()
        val specs = chipSpecs()
        chipScroll.visibility = if (specs.size > 1) View.VISIBLE else View.GONE
        fillChips(specs)
    }

    private fun fillChips(specs: List<Chip>) {
        val sel = intArrayOf(android.R.attr.state_selected); val foc = intArrayOf(android.R.attr.state_focused); val pr = intArrayOf(android.R.attr.state_pressed)
        specs.forEach { c ->
            chips.addView(TextView(this).apply {
                text = c.label; textSize = 15f; maxLines = 1; isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8))
                setTextColor(android.content.res.ColorStateList(arrayOf(sel, foc, pr, intArrayOf()), intArrayOf(Ui.focusText, Ui.focusText, Ui.focusText, Ui.text)))
                val pill = { color: Int -> android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = Ui.dp(40).toFloat() } }
                background = android.graphics.drawable.StateListDrawable().apply {
                    val on = pill(Ui.focus or 0xFF000000.toInt())
                    addState(sel, on); addState(foc, on); addState(pr, on)
                    addState(intArrayOf(), pill(Ui.withAlpha(Ui.text2, 0x38)))
                }
                if (c.icon.isNotEmpty()) {
                    setCompoundDrawables(PlayerIcon(c.icon, android.content.res.ColorStateList(arrayOf(foc, pr, sel, intArrayOf()), intArrayOf(Ui.focusText, Ui.focusText, Ui.focusText, Ui.text))).apply { setBounds(0, 0, Ui.dp(18), Ui.dp(18)) }, null, null, null)
                    compoundDrawablePadding = Ui.dp(8)
                }
                setOnClickListener { c.action() }
                setOnFocusChangeListener { v, f ->
                    if (f) { chipScroll.post { chipScroll.smoothScrollTo((v.left - Ui.dp(24)).coerceAtLeast(0), 0) }; c.onFocus?.invoke() } else if (c.onFocus != null) cancelLive()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
        }
    }

    /** Początek ekranu: pierwszy rząd, widok na górze. */
    private fun goStart() { closeCategory(); scroll.smoothScrollTo(0, 0); rows.indices.firstOrNull { rows[it].tiles.isNotEmpty() }?.let { focusRow(it) } ?: query.requestFocus() }

    /** Przejście do rzędu [id] (jeśli jest na ekranie). */
    private fun jumpTo(id: String) { closeCategory(); rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { focusRow(it) } }

    /** Lista biblioteki otwarta pod rzędem Biblioteka. */
    private fun openLibraryList(p: pl.iptv.tv.data.YtPlaylist) {
        closeCategory()
        val from = rows.firstOrNull { it.id == STubeScreen.ROW_LIBRARY } ?: rows.firstOrNull() ?: return
        openPlaylist(from, p)
    }

    private fun rowView(r: STubeRow, index: Int, at: Int = -1): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(if (index == 0) 2 else 8), 0, 0) }
        box.addView(TextView(this).apply { text = r.title; textSize = 22f * Ui.geo.headerScale; setTypeface(typeface, 1); setTextColor(Ui.text); setPadding(Ui.dp(4), Ui.dp(4), 0, Ui.dp(4)) })
        val status = TextView(this).apply { tag = "text2"; textSize = 14f; setPadding(Ui.dp(4), Ui.dp(4), 0, Ui.dp(4)); text = "Wczytywanie…"; setTag(R.id.row_key, "status") }
        box.addView(status)
        val rv = HRowRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@STubeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = r.adapter; itemAnimator = null; setHasFixedSize(false)
            isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            // miejsce na powiększony kafelek (zaznaczenie nie jest ucinane przy krawędzi rzędu)
            setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(40), Ui.dp(10)); clipToPadding = false
            setTag(R.id.row_key, r.id)
        }
        r.adapter.onClick = { t -> onTile(r, t) }
        r.adapter.onLongClick = { t -> tileMenu(r, t) }
        r.adapter.onFocus = { pos ->
            lastRow = rows.indexOf(r).coerceAtLeast(0)
            // ostatnie kafelki zaznaczone → następna strona
            if (pos >= r.tiles.size - 4 && r.next != null && !r.loading) load(r, r.next, false)
            // zaznaczony rząd w całości na ekranie (tytuł nad kafelkami też)
            scroll.post { scroll.smoothScrollTo(0, (box.top - Ui.dp(6)).coerceAtLeast(0)) }
        }
        box.addView(rv, LinearLayout.LayoutParams(-1, -2))
        if (at < 0) rowViews += rv else rowViews.add(at, rv)
        return box
    }

    private fun statusOf(r: STubeRow): TextView? = rowViews.getOrNull(rows.indexOf(r))?.let { rv ->
        (rv.parent as? LinearLayout)?.let { box -> (0 until box.childCount).map(box::getChildAt).firstOrNull { it.getTag(R.id.row_key) == "status" } as? TextView }
    }

    private fun load(r: STubeRow, page: String?, force: Boolean) {
        if (r.loading) return
        r.loading = true
        if (force && page == null) { YouTubeRepository.clearCache(); pl.iptv.tv.data.YtKeyless.clearCache() }
        // zadanie rzędu da się anulować osobno (zamknięcie playlisty / szybka zmiana kategorii) – stare wyniki nie wracają
        jobs += lifecycleScope.launch {
            rowJobs[r] = coroutineContext[Job]!!
            try {
                val p = r.load(page)
                if (page == null && r.tiles.isNotEmpty()) { r.tiles.clear(); r.adapter.notifyDataSetChanged() }
                val start = r.tiles.size
                r.tiles += p.tiles; r.next = p.next; r.error = null
                r.adapter.notifyItemRangeInserted(start, p.tiles.size)
                statusOf(r)?.let { s -> s.visibility = if (r.tiles.isEmpty()) View.VISIBLE else View.GONE; s.text = emptyText(r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r.error = e.message ?: e.javaClass.simpleName
                if (r.tiles.isEmpty()) {
                    // rząd z błędem: kafelek „Ponów” (fokusowalny – ▲▼ nie przeskakuje pustego rzędu bez informacji)
                    r.tiles += STubeTile.Action(STubeScreen.ACT_RETRY, "⟳", "Ponów"); r.adapter.notifyDataSetChanged()
                }
                statusOf(r)?.let { s -> s.visibility = View.VISIBLE; s.text = "⚠ ${r.error}" }
            } finally { r.loading = false }
        }
    }

    private fun emptyText(r: STubeRow) = if (r.id == STubeScreen.ROW_SEARCH) "Brak wyników${searchQuery?.let { " dla „$it”" }.orEmpty()} – zmień frazę (▲ pole wyszukiwania) albo ⟳ odśwież"
    else when (r.source?.id) {
        STubeScreen.PL_LATER -> "Lista jest pusta – długie OK na filmie → „Do obejrzenia później”"
        STubeScreen.PL_HISTORY -> "Brak obejrzanych filmów"
        else -> "Brak filmów"
    }

    private fun onTile(r: STubeRow, t: STubeTile) {
        when (t) {
            // wbudowany odtwarzacz (bez reklam, SponsorBlock); wyłączony w Ustawieniach – od razu SmartTube
            is STubeTile.Video -> if (pl.iptv.tv.data.Prefs.stubeInternal) playInApp(t.v) else SmartTubeIntegrationHelper.play(this, t.v.url)
            // kanał i playlista: filmy w nowym rzędzie tuż pod spodem (bez opuszczania ekranu; WSTECZ zamyka rząd)
            is STubeTile.Channel -> openPlaylist(r, STubeScreen.channelPlaylist(t.c))
            // gotowy miks (100+ utworów): od razu w odtwarzaczu, bez ekranu pośredniego
            is STubeTile.Playlist -> if (t.p.id.startsWith(STubeScreen.PL_MIX)) playMix(t.p, playNow = true) else openPlaylist(r, t.p)
            is STubeTile.Action -> when (t.id) {
                STubeScreen.ACT_LOGIN -> openAccount()
                STubeScreen.ACT_SMARTTUBE_SEARCH -> searchQuery?.let { SmartTubeIntegrationHelper.search(this, it) }
                else -> if (r === categoryRow) category?.let { openCategory(it) } else { r.tiles.clear(); r.adapter.notifyDataSetChanged(); statusOf(r)?.text = "Wczytywanie…"; load(r, null, true) }
            }
        }
    }

    private fun playInApp(v: pl.iptv.tv.data.YtVideo) = startActivity(YtPlayerActivity.intent(this, v))

    // ---------------- playlisty / kanały (rząd pod rzędem źródłowym) ----------------

    /** Otwarty rząd playlisty (jeden naraz). */
    val playlistRow: STubeRow? get() = rows.firstOrNull { it.id == STubeScreen.ROW_PLAYLIST }
    /** Rząd, z którego otwarto playlistę (powrót WSTECZ). */
    private var playlistParent: STubeRow? = null

    /** Filmy playlisty [p] w rzędzie wstawionym pod [from]; fokus przechodzi na pierwszy film po wczytaniu. */
    fun openPlaylist(from: STubeRow, p: pl.iptv.tv.data.YtPlaylist) {
        val open = playlistRow
        if (open != null && open.source?.id == p.id) { focusRow(rows.indexOf(open)); return }
        // kanał / playlista otwarta z rzędu playlisty – zamiana rzędu pod tym samym źródłem
        val origin = if (from === open) playlistParent ?: from else from
        closePlaylist(refocus = false)
        val at = rows.indexOf(origin) + 1
        if (at <= 0) return
        val r = STubeScreen.playlistRow(p)
        rows = rows.toMutableList().apply { add(at, r) }
        val view = rowView(r, at, at = at)
        rowsBox.addView(view, at)
        Ui.style(view)
        playlistParent = origin
        load(r, null, false)
        focusRow(at, fallback = window.decorView.findFocus())
    }

    /** Zamyka rząd playlisty; [refocus] – fokus wraca na kafelek, z którego ją otwarto. */
    fun closePlaylist(refocus: Boolean = true): Boolean {
        val r = playlistRow ?: return false
        rowJobs.remove(r)?.cancel()
        val i = rows.indexOf(r)
        rows = rows.toMutableList().apply { removeAt(i) }
        rowViews.removeAt(i); rowsBox.removeViewAt(i)
        val parent = playlistParent; playlistParent = null
        if (refocus) parent?.let { rows.indexOf(it) }?.takeIf { it >= 0 }?.let { focusRow(it) }
        return true
    }

    /** Nowa zawartość rzędu listy lokalnej w miejscu; fokus zostaje w rzędzie (pusty rząd playlisty – zamknięty). */
    private fun reloadInPlace(r: STubeRow, fresh: List<STubeTile>) {
        if (fresh == r.tiles) return
        val ri = rows.indexOf(r).takeIf { it >= 0 } ?: return
        val hadFocus = rowIndexOf(window.decorView.findFocus()) == ri
        val pos = r.lastFocus
        r.tiles.clear(); r.tiles += fresh
        r.adapter.notifyDataSetChanged()
        statusOf(r)?.let { s -> s.visibility = if (r.tiles.isEmpty()) View.VISIBLE else View.GONE; s.text = emptyText(r) }
        if (!hadFocus) return
        when {
            r.tiles.isNotEmpty() -> rowViews[ri].focusPosition(pos.coerceIn(0, r.tiles.size - 1))
            r === playlistRow -> closePlaylist()
            else -> rows.indices.firstOrNull { rows[it].tiles.isNotEmpty() }?.let { focusRow(it) } ?: query.requestFocus()
        }
    }

    /** Lista „Do obejrzenia później” zmieniona: licznik na kafelku Biblioteki i otwarty rząd tej listy. */
    private fun watchLaterChanged() {
        rows.firstOrNull { it.id == STubeScreen.ROW_LIBRARY }?.let { lib ->
            val i = lib.tiles.indexOfFirst { (it as? STubeTile.Playlist)?.p?.id == STubeScreen.PL_LATER }
            if (i >= 0) lib.adapter.notifyItemChanged(i)
        }
        playlistRow?.takeIf { it.source?.id == STubeScreen.PL_LATER }?.let { r ->
            jobs += lifecycleScope.launch { reloadInPlace(r, StubeHistory.watchLater().map { STubeTile.Video(it.toVideo()) }) }
        }
    }

    // ---------------- menu kafelka (długie OK) ----------------

    private fun tileMenu(r: STubeRow, t: STubeTile) {
        when (t) {
            is STubeTile.Video -> videoMenu(t.v)
            is STubeTile.Channel -> {
                val url = "https://www.youtube.com/channel/${t.c.id}"
                Dialogs.menu(this, t.c.title, listOf(
                    "☰ Najnowsze filmy kanału" to { openPlaylist(r, STubeScreen.channelPlaylist(t.c)) },
                    "▶ Otwórz w SmartTube" to { SmartTubeIntegrationHelper.play(this, url); Unit },
                    "▶ Otwórz w YouTube" to { SmartTubeIntegrationHelper.playInYouTube(this, url); Unit },
                ))
            }
            is STubeTile.Playlist -> playlistMenu(r, t.p)
            is STubeTile.Action -> Unit
        }
    }

    private fun playlistMenu(r: STubeRow, p: pl.iptv.tv.data.YtPlaylist) {
        Dialogs.menu(this, p.title, buildList {
            add("☰ Pokaż filmy" to { openPlaylist(r, p) })
            when {
                p.id == STubeScreen.PL_LATER -> add("✖ Wyczyść listę" to {
                    Dialogs.confirm(this@STubeActivity, "Wyczyścić?", "Usunąć wszystkie filmy z listy „Do obejrzenia później”?") {
                        lifecycleScope.launch { StubeHistory.clearWatchLater(); watchLaterChanged() }
                    }
                })
                p.id == STubeScreen.PL_HISTORY -> add("✖ Wyczyść historię oglądania" to {
                    Dialogs.confirm(this@STubeActivity, "Wyczyścić?", "Usunąć postęp oglądania wszystkich filmów?") {
                        lifecycleScope.launch { StubeHistory.clearProgress(); if (builtFor != stateKey()) buildKeepingPosition() else refreshProgress() }
                    }
                })
                p.id.startsWith(STubeScreen.PL_MIX) -> {
                    // gotowy miks: od razu do odtwarzacza (pierwszy film), reszta – kolejka „Następne”
                    add("▶ Odtwórz miks" to { playMix(p, playNow = true) })
                    add("☰ Pokaż utwory miksu" to { openPlaylist(r, p) })
                    add("＋ Dodaj miks do kolejki" to { playMix(p, playNow = false) })
                }
                p.id.startsWith(STubeScreen.PL_IMPORTED) -> {
                    add("▶ Odtwórz jako kolejkę" to { playImported(p, playNow = true) })
                    add("☰ Pokaż utwory" to { openPlaylist(r, p) })
                    add("＋ Dodaj do kolejki" to { playImported(p, playNow = false) })
                    add("✖ Usuń z biblioteki" to {
                        Dialogs.confirm(this@STubeActivity, "Usunąć playlistę?", "Playlista „${p.title}” zniknie z biblioteki i przestanie wpływać na „Twoje miksy”.") {
                            lifecycleScope.launch { p.id.removePrefix(STubeScreen.PL_IMPORTED).toLongOrNull()?.let { StubeImport.remove(it) }; libraryChanged() }
                        }
                    })
                }
                p.id == STubeScreen.PL_LIKED || p.id.startsWith(STubeScreen.PL_CHANNEL) -> Unit
                else -> {
                    val url = "https://www.youtube.com/playlist?list=${p.id}"
                    add("▶ Otwórz w SmartTube" to { SmartTubeIntegrationHelper.play(this@STubeActivity, url); Unit })
                    add("▶ Otwórz w YouTube" to { SmartTubeIntegrationHelper.playInYouTube(this@STubeActivity, url); Unit })
                }
            }
        })
    }

    /** Przesłana playlista [p] (Room): jako aktywna kolejka z natychmiastowym startem albo dodana na koniec kolejki. */
    private fun playImported(p: pl.iptv.tv.data.YtPlaylist, playNow: Boolean) {
        lifecycleScope.launch {
            val vids = StubeImport.all().firstOrNull { it.id == p.id.removePrefix(STubeScreen.PL_IMPORTED).toLongOrNull() }?.videos().orEmpty()
            if (vids.isEmpty()) { toast("Playlista jest pusta"); return@launch }
            if (playNow) StubeImportUi.start(this@STubeActivity, vids)
            else { vids.forEach { StubeQueue.add(pl.iptv.tv.data.QueueItem.of(it)) }; toast("Dodano do kolejki: ${vids.size} utworów") }
        }
    }

    /** Miks [p]: filmy z YouTube → pierwszy od razu (gdy [playNow]), pozostałe do kolejki. */
    private fun playMix(p: pl.iptv.tv.data.YtPlaylist, playNow: Boolean) {
        val mix = pl.iptv.tv.data.StubeMixes.byId(p.id.removePrefix(STubeScreen.PL_MIX)) ?: return
        toast("Przygotowanie miksu…")
        lifecycleScope.launch {
            var started: String? = null
            // pierwsza partia → odtwarzanie rusza od razu; reszta dochodzi do kolejki, gdy uzupełnienie się wczyta
            val vids = pl.iptv.tv.data.StubeMixes.videos(mix, null, if (!playNow) null else { first ->
                started = first.first().id
                pl.iptv.tv.data.StubeQueue.replaceAll(first.drop(1).map { pl.iptv.tv.data.QueueItem.of(it) })
                playInApp(first.first())
            })
            if (vids.isEmpty()) { toast("Brak filmów dla tego miksu"); return@launch }
            val rest = if (playNow) vids.filter { it.id != (started ?: vids.first().id) } else vids
            if (playNow) {
                // playlista zastępuje kolejkę i od razu gra w odtwarzaczu (panel „Następne w kolejce” pokazuje resztę)
                pl.iptv.tv.data.StubeQueue.replaceAll(rest.map { pl.iptv.tv.data.QueueItem.of(it) })
                if (started == null) playInApp(vids.first())
            } else {
                rest.forEach { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(it)) }
                toast("Dodano do kolejki: ${rest.size} filmów")
            }
        }
    }

    private fun videoMenu(v: pl.iptv.tv.data.YtVideo) {
        val watched = StubeHistory.cached(v.id)
        Dialogs.menu(this, v.title, buildList {
            add("▶ Odtwórz w aplikacji" to { playInApp(v) })
            // od początku, gdy zapisana pozycja (wznawianie domyślnie od miejsca zakończenia)
            if (StubeHistory.resumeFrom(watched) > 0) add("⏮ Odtwórz od początku" to { startActivity(YtPlayerActivity.intent(this@STubeActivity, v, fromStart = true)) })
            if (StubeHistory.isWatchLater(v.id)) add("✖ Usuń z „Do obejrzenia później”" to {
                lifecycleScope.launch { StubeHistory.removeWatchLater(v.id); toast("Usunięto z „Do obejrzenia później”"); watchLaterChanged() }; Unit
            }) else add("🕘 Do obejrzenia później" to {
                lifecycleScope.launch { if (StubeHistory.addWatchLater(v)) { toast("Dodano do „Do obejrzenia później”"); watchLaterChanged() } }; Unit
            })
            // kolejka „Następne” – wspólna z telewizją na żywo (panel boczny odtwarzaczy)
            add("＋ Dodaj do kolejki" to { lifecycleScope.launch { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(v)); toast("Dodano do kolejki") }; Unit })
            add("⏭ Odtwórz jako następny" to { lifecycleScope.launch { pl.iptv.tv.data.StubeQueue.add(pl.iptv.tv.data.QueueItem.of(v), next = true); toast("Następny w kolejce") }; Unit })
            add("▶ Odtwórz w SmartTube" to { SmartTubeIntegrationHelper.play(this@STubeActivity, v.url); Unit })
            add("▶ Odtwórz w YouTube" to { SmartTubeIntegrationHelper.playInYouTube(this@STubeActivity, v.url); Unit })
            add("🔎 Więcej z kanału: ${v.channel}" to { query.setText(v.channel); search(v.channel) })
            if (watched != null) add("✖ Usuń z historii oglądania" to {
                lifecycleScope.launch {
                    StubeHistory.removeProgress(v.id)
                    if (builtFor != stateKey()) buildKeepingPosition() else refreshProgress()
                }; Unit
            })
        })
    }

    // ---------------- pilot ----------------

    private fun rowIndexOf(v: View?): Int {
        var x = v
        while (x != null) { val i = rowViews.indexOf(x); if (i >= 0) return i; x = x.parent as? View }
        return -1
    }

    /** Zaznacza kafelek w rzędzie [index] (ostatnio zaznaczony w tym rzędzie). */
    private fun focusRow(index: Int, fallback: View? = null) {
        val rv = rowViews.getOrNull(index) ?: run { fallback?.requestFocus(); return }
        val r = rows[index]
        if (r.tiles.isEmpty()) {
            // rząd jeszcze się wczytuje – fokus po wczytaniu (bez „gubienia” zaznaczenia)
            fallback?.requestFocus()
            r.adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = once()
                override fun onChanged() = once()
                fun once() {
                    if (r.tiles.isEmpty()) return
                    r.adapter.unregisterAdapterDataObserver(this)
                    val f = window.decorView.findFocus()
                    if (f == null || f === fallback) rv.focusPosition(r.lastFocus, fallback = fallback)
                }
            })
            return
        }
        rv.focusPosition(r.lastFocus.coerceIn(0, r.tiles.size - 1), fallback = fallback)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK / Enter na polu wyszukiwania – klawiatura ekranowa (chyba że wybrano podpowiedź z listy – wtedy wyszukanie)
        if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            window.decorView.findFocus() === query && !(query.isPopupShowing && query.listSelection != android.widget.ListView.INVALID_POSITION)) {
            if (event.action == KeyEvent.ACTION_UP) showKeyboard()
            return true
        }
        // pasek „Teraz odtwarzane”: ▲ wraca do ostatnio zaznaczonego rzędu, ◄► między przyciskami paska
        window.decorView.findFocus()?.let { f -> if (miniBar?.isInside(f) == true && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            rows.indices.lastOrNull { rows[it].tiles.isNotEmpty() }?.let { focusRow(it) } ?: query.requestFocus(); return true } }
        // pilot: INFO / „Teraz odtwarzane” z dowolnego miejsca ekranu otwiera odtwarzacz (gdy coś gra)
        if (event.keyCode == KeyEvent.KEYCODE_INFO && miniBar?.shown == true) { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) miniBar?.open(); return true }
        // kategoria otwarta: WSTECZ wraca do zwykłego ekranu (potem kolejne WSTECZ zamyka STube); siatka – własna obsługa krawędzi
        if (categoryRow != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && !query.isPopupShowing) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { closeCategory(); goStart() }
                return true
            }
            if (gridKey(event)) return true
            val f = window.decorView.findFocus()
            if (f != null && event.action == KeyEvent.ACTION_DOWN && inside(f, R.id.stRail) && !narrowHeader && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val i = categoryIds.indexOfFirst { it == f.id }
                if (i >= 0 && STubeScreen.CATEGORIES[i].id != category?.id) openCategory(STubeScreen.CATEGORIES[i]) else focusGridWhenReady()
                return true
            }
            if (f != null && event.action == KeyEvent.ACTION_DOWN && inside(f, R.id.stSearchRow) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !(f === query && query.isPopupShowing)) { focusChipsOrGrid(); return true }
        } else {
            // ► na ikonie kategorii, zanim zdążył się wczytać podgląd: otwiera kategorię i przenosi kursor na pierwszy film
            val f = window.decorView.findFocus()
            val i = categoryIds.indexOfFirst { it == f?.id }
            if (i >= 0 && !narrowHeader && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { if (event.action == KeyEvent.ACTION_DOWN) openCategory(STubeScreen.CATEGORIES[i]); return true }
        }
        // WSTECZ przy otwartej playliście / kanale – zamyka jej rząd i wraca na kafelek źródłowy (dopiero kolejne WSTECZ zamyka STube)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && playlistRow != null && !query.isPopupShowing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closePlaylist()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = window.decorView.findFocus()
            val ri = rowIndexOf(f)
            if (ri >= 0) {
                val rv = rowViews[ri]
                val pos = rv.findContainingViewHolder(f!!)?.bindingAdapterPosition ?: -1
                when (event.keyCode) {
                    // ◄► kafelek po kafelku; na końcach rzędu fokus zostaje (nie skacze do innego rzędu ani przycisków)
                    // ◄ na początku rzędu – pasek ikon po lewej (tylko układ poziomy; na wąskim ekranie fokus zostaje)
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (pos > 0) rv.focusPosition(pos - 1) else if (!narrowHeader) focusRail(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pos >= 0 && pos < rows[ri].tiles.size - 1) rv.focusPosition(pos + 1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val next = (ri + 1 until rows.size).firstOrNull { rows[it].tiles.isNotEmpty() }
                        if (next != null) focusRow(next) else if (miniBar?.shown == true) miniBar?.focusBar()   // ostatni rząd → pasek „Teraz odtwarzane”
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val prev = (ri - 1 downTo 0).firstOrNull { rows[it].tiles.isNotEmpty() }
                        // ▲ z pierwszego rzędu: pigułki biblioteki, a bez nich wyszukiwarka
                        if (prev != null) focusRow(prev) else { scroll.smoothScrollTo(0, 0); focusChipsOrQuery() }
                        return true
                    }
                }
            } else if (f != null && inside(f, R.id.stChips)) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { query.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (categoryRow != null) focusGridWhenReady() else goStart(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (f === chips.getChildAt(0) && !narrowHeader) { if (categoryRow != null) categoryIcon().requestFocus() else focusRail(); return true }
                    // krawędź pigułek: kursor nie ucieka poza pasek
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (f === chips.getChildAt(chips.childCount - 1)) return true
                }
            } else if (f != null && inside(f, R.id.stRail) && !narrowHeader) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true   // lewa krawędź ekranu
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    rows.indices.firstOrNull { rows[it].tiles.isNotEmpty() }?.let { focusRow(lastRow.takeIf { i -> rows.getOrNull(i)?.tiles?.isNotEmpty() == true } ?: it) } ?: query.requestFocus()
                    return true
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && inside(f, R.id.stSearchRow) &&
                    !(f === query && query.isPopupShowing)) {   // lista podpowiedzi: ▼ przechodzi do niej (obsługuje AutoCompleteTextView)
                if (chipScroll.visibility == View.VISIBLE && chips.childCount > 0) chips.getChildAt(0).requestFocus() else goStart()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Rząd, w którym ostatnio był fokus (powrót z paska ikon). */
    private var lastRow = 0

    /** Pasek ikon po lewej (Start; z konta – pierwszy element). */
    private fun focusRail() { findViewById<View>(if (searchQuery != null) R.id.stSearch else R.id.stHome).requestFocus() }

    private fun selectedSubChip(): View? =
        if (chipScroll.visibility == View.VISIBLE) (0 until chips.childCount).map(chips::getChildAt).firstOrNull { it.isSelected } ?: chips.getChildAt(0) else null

    private fun focusChipsOrGrid() { selectedSubChip()?.requestFocus() ?: focusGridWhenReady() }

    private fun focusChipsOrQuery() {
        if (chipScroll.visibility == View.VISIBLE && chips.childCount > 0) chips.getChildAt(0).requestFocus() else query.requestFocus()
    }

    private fun inside(v: View, parentId: Int): Boolean {
        val p = findViewById<View>(parentId) ?: return false
        var x: View? = v
        while (x != null) { if (x === p) return true; x = x.parent as? View }
        return false
    }

    private fun toast(s: String) = I18n.makeToast(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
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

/**
 * STube – przeglądanie YouTube na TV: wyszukiwarka u góry (podpowiedzi z historii) i poziome rzędy (Kontynuuj oglądanie, Dla Ciebie,
 * Subskrypcje, Na czasie, Muzyka, Na żywo, Biblioteka / Playlisty, Kanały). Playlista lub kanał otwiera się w rzędzie pod źródłem (WSTECZ zamyka).
 * Dane: YouTube Data API v3 ([YouTubeRepository]); odtwarzanie i wyszukiwanie zewnętrzne: SmartTube.
 * Pilot: ▲▼ między rzędami (powrót na ostatnio zaznaczony kafelek rzędu), ◄► w rzędzie (bez wypadania fokusu na końcach),
 * ▼ z wyszukiwarki do pierwszego rzędu, ▲ z pierwszego rzędu do wyszukiwarki.
 */
class STubeActivity : BaseActivity() {
    /** Wygląd, z którym zbudowano ekran (zmiana motywu STube w Ustawieniach → przebudowa). */
    private var builtLook = ""

    override fun onPause() { super.onPause(); StubeTheme.exit() }

    companion object {
        /** Fraza do wyszukania od razu (polecenie z telefonu). */
        const val EXTRA_QUERY = "query"
    }

    private lateinit var query: AutoCompleteTextView
    private lateinit var suggest: STubeSuggestAdapter
    private var suggestJob: Job? = null
    private lateinit var info: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var account: TextView

    /** Bieżące rzędy (testy). */
    var rows: List<STubeRow> = emptyList(); private set
    private val rowViews = mutableListOf<RecyclerView>()
    private val jobs = mutableListOf<Job>()
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
        if (resources.configuration.screenWidthDp < 600)
            content.setPadding(Ui.dp(10), Ui.dp(12), Ui.dp(10), Ui.dp(8))

        findViewById<View>(R.id.stClose).setOnClickListener { finish() }
        findViewById<View>(R.id.stRefresh).setOnClickListener { YouTubeRepository.clearCache(); pl.iptv.tv.data.YtKeyless.clearCache(); build(force = true) }
        findViewById<View>(R.id.stSmartTube).setOnClickListener { SmartTubeIntegrationHelper.launch(this) }
        account.setOnClickListener { openAccount() }
        findViewById<View>(R.id.stSearch).setOnClickListener { search(query.text.toString()) }
        query.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                search(query.text.toString()); true
            } else false
        }
        searchQuery = (savedInstanceState?.getString("q") ?: intent.getStringExtra(EXTRA_QUERY)?.trim())?.ifBlank { null }
        searchQuery?.let { query.setText(it) }
        findViewById<View>(R.id.stPhone).setOnClickListener { StubeRemoteUi.show(this) }
        setupSuggestions()
        // pierwszy fokus: pierwszy kafelek (wyszukiwarka jest o jedno ▲ wyżej, klawiatura nie wyskakuje od razu)
        if (StubeHistory.cacheLoaded) { build(); focusRow(0, fallback = query) }
        else lifecycleScope.launch {
            // postęp oglądania do pamięci (paski na kafelkach, rząd „Kontynuuj oglądanie”) – przed pierwszym układem rzędów
            StubeHistory.loadCache()
            build(); focusRow(0, fallback = query)
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
        if (builtFor == null) return   // pierwsze rzędy po wczytaniu postępu (onCreate)
        // powrót z Ustawień: logowanie / wylogowanie / zmiana źródła → nowe rzędy
        if (builtFor != stateKey()) build()
        else refreshProgress()
    }

    private fun stateKey() = "${YouTubeTokens.loggedIn}|${YouTubeRepository.source}|${pl.iptv.tv.data.Prefs.ytRegion}|${searchQuery.orEmpty()}|" +
        "${StubeHistory.hasUnfinished}"

    /**
     * Powrót z odtwarzacza: paski obejrzenia odświeżone w miejscu (payload – bez migania miniatur, fokus zostaje),
     * rząd „Kontynuuj oglądanie” wczytany ponownie (ostatnio oglądany film na początku).
     */
    private fun refreshProgress() {
        rows.forEach { r -> if (r.tiles.isNotEmpty()) r.adapter.notifyItemRangeChanged(0, r.tiles.size, STubeScreen.PAYLOAD_PROGRESS) }
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
            val list = StubeHistory.suggestions(typed)
            // wynik nieaktualny: pole straciło fokus albo fraza właśnie wyszukana (wybór z listy)
            if (!query.hasFocus() || query.text.toString() != typed || suppressSuggest) return@launch
            suggest.set(list)
            if (list.isEmpty()) query.dismissDropDown()
            else if (!isFinishing && window.decorView.windowToken != null) runCatching { query.showDropDown() }
        }
    }

    /** Podpowiedzi widoczne (testy). */
    val suggestions: List<String> get() = suggest.items

    private fun openAccount() = startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", SettingsActivity.YOUTUBE_TAB))

    private fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) { toast("Wpisz frazę do wyszukania"); return }
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
        suppressSuggest = true; suggestJob?.cancel(); query.dismissDropDown()
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
        account.text = if (YouTubeTokens.loggedIn) "👤 " + YouTubeTokens.accountName.ifBlank { "Konto" } else "👤 Zaloguj"
        info.visibility = View.GONE
        rows = STubeScreen.rows(searchQuery)
        rowViews.clear(); rowsBox.removeAllViews(); playlistParent = null
        rows.forEachIndexed { i, r -> rowsBox.addView(rowView(r, i)) }
        Ui.style(rowsBox)
        rows.forEach { load(it, null, force) }
    }

    private fun rowView(r: STubeRow, index: Int, at: Int = -1): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(if (index == 0) 2 else 8), 0, 0) }
        box.addView(TextView(this).apply { text = r.title; tag = "accent"; textSize = 18f * Ui.geo.headerScale; setTypeface(typeface, 1); setPadding(Ui.dp(4), 0, 0, Ui.dp(2)) })
        val status = TextView(this).apply { tag = "text2"; textSize = 14f; setPadding(Ui.dp(4), Ui.dp(4), 0, Ui.dp(4)); text = "Wczytywanie…"; setTag(R.id.row_key, "status") }
        box.addView(status)
        val rv = RecyclerView(this).apply {
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
        jobs += lifecycleScope.launch {
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

    private fun emptyText(r: STubeRow) = when (r.source?.id) {
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
            is STubeTile.Playlist -> openPlaylist(r, t.p)
            is STubeTile.Action -> when (t.id) {
                STubeScreen.ACT_LOGIN -> openAccount()
                STubeScreen.ACT_SMARTTUBE_SEARCH -> searchQuery?.let { SmartTubeIntegrationHelper.search(this, it) }
                else -> { r.tiles.clear(); r.adapter.notifyDataSetChanged(); statusOf(r)?.text = "Wczytywanie…"; load(r, null, true) }
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
                        lifecycleScope.launch { StubeHistory.clearProgress(); if (builtFor != stateKey()) build() else refreshProgress() }
                    }
                })
                p.id == STubeScreen.PL_LIKED || p.id.startsWith(STubeScreen.PL_CHANNEL) -> Unit
                else -> {
                    val url = "https://www.youtube.com/playlist?list=${p.id}"
                    add("▶ Otwórz w SmartTube" to { SmartTubeIntegrationHelper.play(this@STubeActivity, url); Unit })
                    add("▶ Otwórz w YouTube" to { SmartTubeIntegrationHelper.playInYouTube(this@STubeActivity, url); Unit })
                }
            }
        })
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
                    if (builtFor != stateKey()) build() else refreshProgress()
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
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (pos > 0) rv.focusPosition(pos - 1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pos >= 0 && pos < rows[ri].tiles.size - 1) rv.focusPosition(pos + 1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val next = (ri + 1 until rows.size).firstOrNull { rows[it].tiles.isNotEmpty() }
                        if (next != null) focusRow(next)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val prev = (ri - 1 downTo 0).firstOrNull { rows[it].tiles.isNotEmpty() }
                        if (prev != null) focusRow(prev) else { scroll.smoothScrollTo(0, 0); query.requestFocus() }
                        return true
                    }
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && inside(f, R.id.stSearchRow) &&
                    !(f === query && query.isPopupShowing)) {   // lista podpowiedzi: ▼ przechodzi do niej (obsługuje AutoCompleteTextView)
                rows.indices.firstOrNull { rows[it].tiles.isNotEmpty() }?.let { focusRow(it) }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun inside(v: View, parentId: Int): Boolean {
        val p = findViewById<View>(parentId) ?: return false
        var x: View? = v
        while (x != null) { if (x === p) return true; x = x.parent as? View }
        return false
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

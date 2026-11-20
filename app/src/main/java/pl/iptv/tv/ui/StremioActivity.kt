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
    override val pausesLiveTv = true
    companion object {
        /** Odpytywanie potwierdzenia kodu QR i ważność kodu (nowy kod po czasie). */
        var LINK_POLL_MS = 3_000L
        var LINK_TTL_MS = 5 * 60_000L
        /** Wyszukiwanie od razu po otwarciu (pole wyszukiwania na ekranie głównym PS5). */
        const val EXTRA_QUERY = "query"
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
    private lateinit var infoBox: LinearLayout
    private val jobs = mutableListOf<Job>()
    private var focusJob: Job? = null
    var searchQuery: String? = null; private set
    /** Tytuły rzędów na ekranie (testy). */
    val rowTitles = mutableListOf<String>()
    var loginShown = false; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(build())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (searchQuery != null) { query.setText(""); search("") } else leaveScreen() }
        })
        if (!StremioAccount.loggedIn && !Prefs.stremioGuest) showLogin() else {
            intent.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { q -> searchQuery = q; query.setText(q) }
            showBoard()
        }
    }

    override fun onResume() {
        super.onResume()
        // powrót z odtwarzacza / wtyczek: nowy postęp i stan wtyczek
        if (::rowsBox.isInitialized && !loginShown && rowsBox.childCount > 0) refreshContinue()
    }

    private fun build(): View {
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
        searchBar.addView(StremioUi.button(this, "🔎 Szukaj") { if (query.text.isBlank()) showKeyboard() else search(query.text.toString()) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        searchBar.addView(StremioUi.button(this, "🧩 Wtyczki") { startActivity(Intent(this, StremioAddonsActivity::class.java)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        // (7.8) odświeżenie wtyczek (zmiany na koncie) i ponowne wczytanie tablicy
        searchBar.addView(StremioUi.button(this, "⟳") { refreshAddons() }.apply { contentDescription = "Odśwież wtyczki" },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        header.addView(searchBar, LinearLayout.LayoutParams(0, -2, 1f))
        account = StremioUi.button(this, "👤") { accountMenu() }
        header.addView(account, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        header.addView(StremioUi.button(this, "✖") { leaveScreen() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
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

    private fun showKeyboard() {
        query.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------- logowanie ----------------

    private fun showLogin() {
        loginShown = true; scroll.auto = true
        searchBar.visibility = View.GONE; infoBox.visibility = View.GONE
        account.text = "👤"
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
                img.load(l.qr) { crossfade(false) }
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
        searchBar.visibility = View.VISIBLE; infoBox.visibility = View.VISIBLE
        account.text = if (StremioAccount.loggedIn) "👤 " + StremioAccount.email.substringBefore('@') else "👤 Zaloguj"
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
        if (j !in rows.indices) return step > 0            // ▼ na końcu – zostaje; ▲ z pierwszego rzędu – zwykła nawigacja do paska
        if (event.action == KeyEvent.ACTION_DOWN) rowRv(rows[j]).focusPosition(pos.coerceAtMost(rows[j].adapter.items.size - 1))
        return true
    }

    /** Po wczytaniu / usunięciu rzędu nad kursorem zaznaczony rząd zostaje u góry widoku (nic nie „ucieka” spod kursora). */
    private fun anchorFocusedRow() { focusedRow()?.let { box -> scroll.post { scroll.scrollTo(0, (box.top - Ui.dp(4)).coerceAtLeast(0)) } } }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DPAD_KEYS) userMoved = true
        if (!loginShown && rowsVerticalKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

    private fun build(q: String?) {
        jobs.forEach { it.cancel() }; jobs.clear()
        rowsBox.removeAllViews(); rows.clear(); rowTitles.clear()
        userMoved = false
        var first = true
        jobs += lifecycleScope.launch {
            val continueRow = addRow("Kontynuuj oglądanie")
            fillContinue(continueRow)
            if (q == null) fillWatched(addRow("Obejrzane"))
            val addons = StremioStore.enabledAddons()
            val catalogs: List<Pair<StremioAddon, StremioAddon.Catalog>> = if (q != null)
                addons.flatMap { a -> a.searchCatalogs.map { a to it } }.sortedBy { (_, c) -> when (c.type) { "movie" -> 0; "series" -> 1; else -> 2 } }
            else addons.flatMap { a -> a.boardCatalogs.map { a to it } }.take(8)
            if (catalogs.isEmpty()) {
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
                        r.adapter.items += list.map { StremioTile(it) }; r.adapter.notifyItemRangeInserted(start, list.size); r.status.visibility = View.GONE
                        if (rowsHaveFocus()) anchorFocusedRow()
                        // fokus na pierwszym rzędzie tylko, dopóki użytkownik niczego nie zaznaczył pilotem – wcześniej każdy
                        // doładowany katalog mógł przenieść kursor (np. gdy fokus był na przycisku u góry albo chwilowo zniknął)
                        if (first && !userMoved && window.decorView.findFocus().let { it == null || it === query }) { first = false; focusRow(r) }
                    }.onFailure { removeRow(r) }   // (7.11) wtyczka, która zawiodła, jest pomijana po cichu (bez komunikatów o błędach)
                }
            }
        }
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

    private fun addRow(title: String): Row {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(6), 0, 0) }
        box.addView(StremioUi.text(this, title, 18f * Ui.geo.headerScale, "accent", bold = true).apply { setPadding(Ui.dp(4), 0, 0, Ui.dp(2)) })
        val status = StremioUi.text(this, "Wczytywanie…", 14f, "text2").apply { setPadding(Ui.dp(4), Ui.dp(4), 0, Ui.dp(4)) }
        box.addView(status)
        val adapter = StremioPosterAdapter()
        val rv = HRowRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@StremioActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter; itemAnimator = null
            setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(40), Ui.dp(10)); clipToPadding = false
        }
        box.addView(rv, LinearLayout.LayoutParams(-1, -2))
        val r = Row(title, adapter, box, status)
        adapter.onClick = { t -> open(t) }
        adapter.onFocus = { t -> showInfo(t); scroll.post { scroll.smoothScrollTo(0, (box.top - Ui.dp(4)).coerceAtLeast(0)) } }
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

    private suspend fun fillContinue(r: Row) {
        val list = StremioStore.progress().filter { !it.finished }
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
        val r = rows.firstOrNull { it.title == "Kontynuuj oglądanie" }
        lifecycleScope.launch {
            if (r != null) fillContinue(r)
            else if (StremioStore.progress().any { !it.finished }) build(searchQuery)
            rows.firstOrNull { it.title == "Obejrzane" }?.let { fillWatched(it) }
        }
    }

    /** Opis zaznaczonego tytułu; bez opisu w katalogu – metadane wczytane po chwili. */
    private fun showInfo(t: StremioTile) {
        val m = t.meta
        infoTitle.text = m.name
        infoMeta.text = StremioUi.metaLine(m.type, m.releaseInfo, m.imdbRating, m.genres) + (t.progress?.let { p -> "   ·   ▶ " + listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" ") } ?: "")
        infoDesc.text = m.description
        focusJob?.cancel()
        if (m.description.isBlank()) focusJob = lifecycleScope.launch {
            delay(350)
            val full = StremioMetas.get(m.type, m.id) ?: return@launch
            if (infoTitle.text == m.name) {
                infoDesc.text = full.description
                infoMeta.text = StremioUi.metaLine(m.type, full.releaseInfo, full.imdbRating, full.genres)
            }
        }
    }

    /** (7.8) „Kontynuuj oglądanie” – od razu odtwarzanie ([StremioPlayerActivity.resumeIntent]); katalog – ekran szczegółów. */
    private fun open(t: StremioTile) = startActivity(t.progress?.let { StremioPlayerActivity.resumeIntent(this, it) }
        ?: StremioDetailActivity.intent(this, t.meta, null))
}

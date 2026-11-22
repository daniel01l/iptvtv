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
        const val IN_CINEMAS = "Obecnie w kinach"
        /** (7.17) Filtry tablicy (pigułki u góry w „Obsidian Stream”). */
        const val FILTER_MOVIES = 0; const val FILTER_SERIES = 1; const val FILTER_ALL = 2; const val FILTER_LIBRARY = 3
        val FILTER_NAMES = listOf("Filmy", "Seriale", "Odkrywaj", "Biblioteka")
        const val LIBRARY_ROW = "Moja biblioteka"
        /** Katalogi usługowych wtyczek, których nie pokazujemy na tablicy (AIOStreams, ElfHosted, Store RD, Store PM). */
        val HIDDEN_CATALOGS = Regex("(?i)aiostreams|elfhosted|store[\\s_-]*(rd|pm)\\b")
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

    // ---------------- (7.17) „Obsidian Stream” ----------------

    /** Filtr tablicy: [FILTER_MOVIES] Filmy, [FILTER_SERIES] Seriale, [FILTER_ALL] Odkrywaj (wszystkie katalogi), [FILTER_LIBRARY] Biblioteka. */
    var filter = FILTER_ALL; private set
    private val obsidian by lazy { pl.iptv.tv.ui.theme.ObsidianViews.active }
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
        if (searchQuery != null) { searchQuery = null; query.setText("") }
        build(null)
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
        setThemedContent(build())
        if (obsidian) installRail()
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
        // (7.17) biblioteka zmieniona na ekranie szczegółów – rząd „Moja biblioteka” od razu aktualny
        if (resumedOnce && ::rowsBox.isInitialized && !loginShown && filter == FILTER_LIBRARY && searchQuery == null) {
            val r = rows.firstOrNull { it.title == LIBRARY_ROW }
            if (r != null) fillLibrary(r) else if (pl.iptv.tv.data.StremioLibrary.all().isNotEmpty()) build(null)
        }
        resumedOnce = true
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
        searchBar.addView(StremioUi.button(this, "Szukaj", PlayerIcon.SEARCH) { if (query.text.isBlank()) showKeyboard() else search(query.text.toString()) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        searchBar.addView(StremioUi.button(this, "Wtyczki", PlayerIcon.PLUG) { startActivity(Intent(this, StremioAddonsActivity::class.java)) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        // (7.8) odświeżenie wtyczek (zmiany na koncie) i ponowne wczytanie tablicy
        searchBar.addView(StremioUi.button(this, "", PlayerIcon.REFRESH) { refreshAddons() }.apply { contentDescription = "Odśwież wtyczki" },
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
        searchBar.visibility = View.VISIBLE; infoBox.visibility = View.VISIBLE; filterBar?.visibility = View.VISIBLE
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
        if (j !in rows.indices) return step > 0            // ▼ na końcu – zostaje; ▲ z pierwszego rzędu – zwykła nawigacja do paska
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
        val f = window.decorView.findFocus() ?: return true
        return f === query || (obsidian && !userMoved && (inRail(f) || f in filterPills))
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (inRail(f)) {
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
        rowsBox.removeAllViews(); rows.clear(); rowTitles.clear()
        userMoved = false
        var first = true
        // (7.17) filtr tablicy: Filmy / Seriale – tylko katalogi i rzędy tego typu, Biblioteka – rzędy z urządzenia (bez katalogów)
        val f = filter
        val type = typeFilter
        jobs += lifecycleScope.launch {
            val continueRow = addRow("Kontynuuj oglądanie")
            fillContinue(continueRow)
            if (q == null && f == FILTER_LIBRARY) fillLibrary(addRow(LIBRARY_ROW))
            if (q == null && (f == FILTER_ALL || f == FILTER_LIBRARY)) fillWatched(addRow("Obejrzane"))
            if (q == null && f == FILTER_LIBRARY) {
                if (rows.isEmpty()) rowsBox.addView(StremioUi.text(this@StremioActivity, "Biblioteka jest pusta – dodaj tytuł przyciskiem „Dodaj do biblioteki” w jego opisie.", 16f, "text2")
                    .apply { setPadding(Ui.dp(6), Ui.dp(20), 0, 0) })
                return@launch
            }
            val addons = StremioStore.enabledAddons()
            // (7.12) „Obecnie w kinach” – pierwszy rząd katalogów (Cinemeta, nowe premiery)
            if (q == null && (type == null || type == "movie")) addons.firstOrNull { a -> a.catalogs.any { it.type == "movie" && it.id == "year" } }?.let { a ->
                val r = addRow(IN_CINEMAS)
                jobs += lifecycleScope.launch {
                    val list = runCatching { StremioApi.inCinemas(a) }.getOrDefault(emptyList())
                    if (list.isEmpty()) { removeRow(r); return@launch }
                    r.adapter.items += list.map { StremioTile(it) }; r.adapter.notifyItemRangeInserted(0, list.size); r.status.visibility = View.GONE
                    if (rowsHaveFocus()) anchorFocusedRow()
                    if (first && !userMoved && autoFocusAllowed()) { first = false; focusRow(r) }
                }
            }
            // (7.12) katalogi usługowych wtyczek (AIOStreams / ElfHosted, Store RD / PM) nie są pokazywane na tablicy
            fun hidden(a: StremioAddon, c: StremioAddon.Catalog) = HIDDEN_CATALOGS.containsMatchIn(a.name + " " + c.name)
            fun wanted(c: StremioAddon.Catalog) = type == null || c.type == type
            val catalogs: List<Pair<StremioAddon, StremioAddon.Catalog>> = if (q != null)
                addons.flatMap { a -> a.searchCatalogs.filterNot { hidden(a, it) }.filter(::wanted).map { a to it } }.sortedBy { (_, c) -> when (c.type) { "movie" -> 0; "series" -> 1; else -> 2 } }
            else addons.flatMap { a -> a.boardCatalogs.filterNot { hidden(a, it) }.filter(::wanted).map { a to it } }.take(8)
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
                        if (first && !userMoved && autoFocusAllowed()) { first = false; focusRow(r) }
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

    private fun rowIcon(title: String): String = when {
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

    /** (7.17) „Moja biblioteka”: tytuły dodane na ekranie szczegółów ([pl.iptv.tv.data.StremioLibrary]), najnowsze pierwsze. */
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
        val r = rows.firstOrNull { it.title == "Kontynuuj oglądanie" }
        lifecycleScope.launch {
            if (r != null) fillContinue(r)
            else if (StremioStore.progress().any { !it.finished }) build(searchQuery)
            rows.firstOrNull { it.title == "Obejrzane" }?.let { fillWatched(it) }
        }
    }

    /** (7.12) Przytrzymanie plakatu: cały film / serial jako obejrzany albo nieobejrzany (serial – wszystkie odcinki sezonów). */
    private fun titleMenu(t: StremioTile) {
        val m = t.meta
        lifecycleScope.launch {
            val meta = if (m.type == "series") StremioMetas.get("series", m.id) else null
            val done = StremioStore.isTitleWatched(m, meta)
            Dialogs.menu(this@StremioActivity, m.name, listOf(
                (if (done) "↺ Odznacz jako obejrzany" else "✔ Oznacz jako obejrzany") to {
                    lifecycleScope.launch {
                        StremioStore.setTitleWatched(m, meta, !done)
                        I18n.makeToast(this@StremioActivity, if (done) "Odznaczono: ${m.name}" else "Oznaczono jako obejrzany: ${m.name}", Toast.LENGTH_SHORT).show()
                        // rząd „Obejrzane” pojawia się dopiero po pierwszej pozycji – wtedy tablica wczytuje się od nowa
                        if (searchQuery == null && rows.none { it.title == "Obejrzane" } && !done) build(null) else refreshContinue()
                    }
                },
            ))
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

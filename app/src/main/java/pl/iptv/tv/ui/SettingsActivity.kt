package pl.iptv.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import pl.iptv.tv.R
import pl.iptv.tv.data.Parental
import pl.iptv.tv.data.PlaylistInfo
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StartActions
import java.text.DateFormat
import java.util.Date

class SettingsActivity : BaseActivity() {
    /** (7.22) Telefon pionowo: zakładka „Więcej” dolnego paska. */
    override val portraitTab get() = MobilePortraitScaffold.Tab.MORE
    override val ps5Hints = true

    /**
     * (7.23) Ustawienia otwarte z pulpitu ([fromDashboard] – Nowoczesny, PS5, X25; telefon i TV): Live TV zatrzymane i zwolnione
     * (dekodery, połączenia, fokus audio – [Playback.suspendForApp] w [BaseActivity.onResume]), w tle tylko tło aktywnego motywu
     * (bez obrazu kanału). Po wyjściu – z powrotem na ten sam pulpit ([returnToOrigin]); kanał wraca tak jak przed wejściem
     * (pulpit, który sam wstrzymuje telewizję, nadal ją wstrzymuje). Ustawienia otwarte z innych ekranów – bez zmian.
     */
    val openedFromDashboard: Boolean get() = intent?.getBooleanExtra(EXTRA_FROM_DASHBOARD, false) == true
    override val pausesLiveTv get() = openedFromDashboard
    private var returning = false

    /** Wyjście z Ustawień otwartych z pulpitu: pulpit pod spodem – zwykły powrót; pulpit zamknięty (np. zmiana motywu) – otwarty ponownie. */
    private fun returnToOrigin() {
        if (!openedFromDashboard || returning) return
        returning = true
        val o = origin?.get()
        if (o != null && !o.isFinishing && !o.isDestroyed) return          // pulpit pod spodem – finish() wraca na niego
        // pulpit aktywnego motywu (X25 / Nowoczesny / PS5 – po zmianie motywu w Ustawieniach to może być inny niż ten, z którego weszliśmy);
        // pulpity wyłączone – zwykły powrót na ekran pod spodem
        if (X25Theme.dashboardOn) { X25Theme.openDashboard(this); returnedTo = X25Theme.dashboardIntent(this).component?.className }
    }

    override fun finish() { returnToOrigin(); super.finish() }

    private lateinit var container: LinearLayout
    /** (7.20) Ustawienia silnika, z którymi zbudowano ekran (obserwacja [EngineConfigRepository.effectiveConfig]). */
    private var seenEngine: PlayerEngineConfig? = null
    private lateinit var sticky: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabs = mutableListOf<TextView>()
    private var tabStrip: android.widget.HorizontalScrollView? = null
    private var tab = 0

    // pola zakładki „Źródła”
    private var playlistUrl: EditText? = null
    private var epgUrl: EditText? = null
    private var piconUrl: EditText? = null
    private var userAgent: EditText? = null
    private var log: TextView? = null
    private var logText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (7.20) to samo źródło ustawień silnika co okno w odtwarzaczu: zmiana z innego miejsca (odtwarzacz, strażnik, profil)
        // – sekcja odświeżana od razu; własne zmiany ekranu już są w [seenEngine] (bez podwójnej przebudowy)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                EngineConfigRepository.effectiveConfig.collect { c -> if (seenEngine != null && c.global != seenEngine && ::container.isInitialized) build(keepFocus = true) }
            }
        }
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        val narrow = resources.configuration.screenWidthDp < 700
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(if (narrow) 12 else 32), Ui.dp(16), Ui.dp(if (narrow) 12 else 32), Ui.dp(8))
        }
        // nagłówek wg motywu (iOS: duży, pogrubiony)
        root.addView(TextView(this).apply { text = "Ustawienia"; textSize = 24f * Ui.geo.headerScale; tag = "accent"; setTypeface(typeface, 1) })
        // karty w pionowej kolumnie po lewej, treść po prawej
        // telefon w pionie: karty w przewijanym pasku u góry, treść na pełną szerokość (TV i poziom – kolumna po lewej)
        val portrait = narrow && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val body = LinearLayout(this).apply { setPadding(0, Ui.dp(10), 0, 0); if (portrait) orientation = LinearLayout.VERTICAL }
        // (7.17) „Obsidian Stream” (Nowoczesny): kolumna kart i panel opcji kryjące w 90 % (#0F141F) – tekst czytelny nad kanałem w tle
        val obsidian = pl.iptv.tv.ui.theme.ObsidianViews.active
        val tabCol = LinearLayout(this).apply { orientation = if (portrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (obsidian) background = pl.iptv.tv.ui.theme.ObsidianViews.panel90() else tag = "panel"
            setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)) }
        TAB_NAMES.forEachIndexed { i, t ->
            val b = topButton(t) { switchTab(i) }
            b.setOnFocusChangeListener { _, f ->
                handler.removeCallbacks(tabSwitch)
                // tylko ruch pilotem (nie zaznaczenie przy otwarciu ekranu)
                if (f && i != tab && i != PARENTAL_TAB && android.os.SystemClock.uptimeMillis() - lastKeyAt < 400) { pendingTab = i; handler.postDelayed(tabSwitch, TAB_FOCUS_DELAY_MS) }
            }
            b.layoutParams = if (portrait) LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(4) }
                else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) }
            tabs += b; tabCol.addView(b)
        }
        if (portrait) {
            // (7.6) wygaszone krawędzie paska kart – widać, że w bok jest więcej kart
            tabStrip = android.widget.HorizontalScrollView(this).apply { isFocusable = false; isHorizontalScrollBarEnabled = false; addView(tabCol)
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(Ui.dp(28)) }
            body.addView(tabStrip, LinearLayout.LayoutParams(-1, -2))
        } else body.addView(ScrollView(this).apply { isFocusable = false; addView(tabCol) }, LinearLayout.LayoutParams(Ui.dp(if (narrow) 150 else 240), -1))
        // pion: treść przycięta do szerokości ekranu (bez wystawania i poziomego „rozjeżdżania”), pasek przewijania poza treścią
        // treść przycinana na krawędzi przewijanego obszaru (powiększony wiersz nie wchodzi na kolumnę kart)
        scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false; clipChildren = true
            if (portrait) { scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY; isVerticalScrollBarEnabled = false } }
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(if (portrait) 4 else 20), Ui.dp(12), Ui.dp(if (portrait) 4 else 14), Ui.dp(40)); clipChildren = portrait; clipToPadding = portrait }
        scroll.addView(container)
        // podgląd wyglądu przypięty nad przewijaną treścią karty
        sticky = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(if (portrait) 4 else 16), 0, Ui.dp(4), Ui.dp(8)) }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            // (7.6) Nowoczesny: treść na lekkiej, zaokrąglonej powierzchni (sam kształt – bez bitmap i bez dodatkowego przerysowania tła)
            if (obsidian) background = pl.iptv.tv.ui.theme.ObsidianViews.panel90() }
        right.addView(sticky, LinearLayout.LayoutParams(-1, -2))
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(right, if (portrait) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setThemedContent(root)
        build()
        tabs[tab].requestFocus()
        if (Parental.enabled && Prefs.parentalSettings) ParentalUi.require(this, onCancel = { finish() }) {}
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingTab = -1
    private val tabSwitch = Runnable { if (pendingTab >= 0 && pendingTab != tab) switchTab(pendingTab) }

    private fun inside(v: View?, parent: View): Boolean {
        var x = v
        while (x != null) { if (x === parent) return true; x = x.parent as? View }
        return false
    }

    /** ◄ z treści – zawsze na bieżącą kartę; ► z karty – do pierwszego elementu treści. */
    private var lastKeyAt = -100_000L

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            lastKeyAt = android.os.SystemClock.uptimeMillis()
            val f = window.decorView.findFocus()
            val inContent = inside(f, container) || inside(f, sticky)
            // (7.17) pigułki profili wydajności: ◄ ► między profilami (◄ z pierwszego – karty z lewej, ► na ostatnim – zostaje)
            profileBar?.takeIf { f != null && f.parent === it }?.let { bar ->
                val i = bar.indexOfChild(f)
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (i > 0) { bar.getChildAt(i - 1).requestFocus(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { bar.getChildAt(i + 1)?.requestFocus(); return true }
                }
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && inContent && f !is EditText) { tabs[tab].requestFocus(); return true }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && f != null && f in tabs) {
                handler.removeCallbacks(tabSwitch)
                val i = tabs.indexOf(f)
                if (i != tab) { if (i == PARENTAL_TAB) { switchTab(i); return true } else switchTab(i) }
                (firstFocusable(sticky) ?: firstFocusable(container))?.requestFocus(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable && v !is ViewGroup) return v
        if (v.isFocusable && v is LinearLayout && v.getTag(R.id.row_key) != null) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", tab) }
    override fun onPause() { super.onPause(); saveFields() }

    private fun switchTab(i: Int) {
        saveFields()
        // po zmianie karty rozwinięte sekcje wracają do stanu domyślnego
        if (i != tab) { Prefs.settingsOpen = emptyList(); Prefs.settingsClosed = emptyList(); lastKey = null }
        // karta kontroli rodzicielskiej wymaga PIN-u, gdy kontrola jest włączona
        if (i == 5 && Parental.enabled && !Parental.unlocked) { ParentalUi.require(this) { tab = i; build() }; return }
        val from = tab
        tab = i; build()
        if (i != from) animateTabIn(if (i > from) 1 else -1)
    }

    /**
     * (7.6) Płynne przejście treści nowej karty: krótkie przenikanie z przesunięciem w stronę karty (160 ms, na warstwie GPU –
     * bez przerysowywania wierszy w każdej klatce), treść od góry. W trybie słabszych urządzeń – bez animacji.
     */
    private fun animateTabIn(dir: Int) {
        scroll.scrollTo(0, 0)
        if (Prefs.lowPerf) return
        val body = scroll
        body.animate().cancel()
        body.alpha = 0f; body.translationX = Ui.dp(24).toFloat() * dir
        body.animate().alpha(1f).translationX(0f).setDuration(TAB_ANIM_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).withLayer().start()
    }

    /** (7.6) Telefon / tablet: przesunięcie palcem w bok po treści – sąsiednia karta. */
    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val a = e1 ?: return false
                val dx = e2.x - a.x; val dy = e2.y - a.y
                if (kotlin.math.abs(dx) < Ui.dp(90) || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2 || kotlin.math.abs(vx) < Ui.dp(400)) return false
                // przesunięcie po pasku kart przewija pasek (nie zmienia karty)
                tabStrip?.let { hs -> val r = android.graphics.Rect(); hs.getGlobalVisibleRect(r); if (r.contains(a.rawX.toInt(), a.rawY.toInt())) return false }
                val next = tab + if (dx < 0) 1 else -1
                if (next !in TAB_NAMES.indices) return false
                switchTab(next)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // gest obserwowany „w tle” – dotknięcia i przewijanie treści działają jak dotąd
        runCatching { swipe.onTouchEvent(ev) }
        return super.dispatchTouchEvent(ev)
    }

    /** Buduje bieżącą kartę; zachowuje fokus na tym samym wierszu (po tytule). */
    private fun build(keepFocus: Boolean = false, key: Any? = null) {
        if (tab !in TAB_NAMES.indices) tab = 0
        seenEngine = EngineConfigRepository.current.global
        val focusKey = key ?: if (keepFocus) currentKey() else null
        buildNow(focusKey)
    }

    private fun currentKey(): Any? = (generateSequence(container.findFocus() ?: sticky.findFocus()) { it.parent as? View }
            .firstNotNullOfOrNull { it.getTag(R.id.row_key) } ?: lastKey)

    private fun buildNow(focusKey: Any?) {
        container.removeAllViews(); section = null; sectionIndex = 0
        sticky.removeAllViews()
        playlistUrl = null; epgUrl = null; piconUrl = null; userAgent = null; log = null
        tabs.forEachIndexed { i, t -> t.isActivated = i == tab }
        // pasek kart w pionie: wybrana karta widoczna
        tabStrip?.let { hs -> tabs.getOrNull(tab)?.let { t -> hs.post { hs.smoothScrollTo((t.left - Ui.dp(24)).coerceAtLeast(0), 0) } } }
        when (tab) {
            0 -> buildLists(); 1 -> buildLook(); 2 -> buildMenuChannels(); 3 -> buildPlayback(); 4 -> buildProgramTv(); 5 -> buildCatchup()
            6 -> buildParental(); 7 -> buildRecording(); 8 -> buildScores(); 9 -> buildRemoteKeys(); YOUTUBE_TAB -> buildYouTube(); else -> buildSystem()
        }
        section = null
        saveButton()
        // stylowane tylko widoczne elementy; zwinięte sekcje – dopiero przy rozwinięciu (szybsze przełączanie kart i przebudowa)
        for (i in 0 until container.childCount) container.getChildAt(i).let { if (it.visibility == View.VISIBLE) styleSection(it) }
        Ui.style(sticky)
        sticky.visibility = if (sticky.childCount > 0) View.VISIBLE else View.GONE
        focusKey?.let { k -> ((findByKey(container, k) ?: findByKey(sticky, k)) ?: tabs.getOrNull(tab))?.requestFocus() }
    }

    private fun styleSection(v: View) {
        if (v.getTag(R.id.settings_styled) == Ui.version) return
        v.setTag(R.id.settings_styled, Ui.version)
        Ui.style(v)
    }

    private fun findByKey(v: View, k: Any): View? {
        if (v.getTag(R.id.row_key) == k && v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findByKey(v.getChildAt(i), k)?.let { return it }
        return null
    }

    private fun saveButton() {
        container.addView(TextView(this).apply {
            text = "Zapisz i zamknij"; textSize = 18f; tag = "item"; isFocusable = true; isClickable = true
            gravity = Gravity.CENTER; setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14))
            setOnClickListener { saveAndClose() }
            // (7.17) ostatni element treści: ▼ i ► nie uciekają do kart po lewej (FocusFinder szukał najbliższego widoku
            // w całym oknie); ▼ tylko dosuwa notatkę pod przyciskiem
            setOnKeyListener { _, code, e ->
                when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (e.action == android.view.KeyEvent.ACTION_DOWN) scroll.smoothScrollTo(0, container.height); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(24) })
        note("Ustawienia zapisują się też automatycznie. Jeśli wpisano nowy adres listy, zostanie ona wczytana przed zamknięciem.")
    }

    /** Zapisuje pola; nową (niewczytaną) listę najpierw wczytuje. */
    private fun saveAndClose() {
        saveFields()
        val src = playlistUrl?.text?.toString()?.trim().orEmpty()
        if (src.isNotBlank() && (src != Prefs.playlistSource || Repository.allChannels.isEmpty())) {
            loadPlaylist(src) { ok -> if (ok) { toast("Zapisano"); finish() } }
        } else { toast("Zapisano"); finish() }
    }

    private fun lookChanged() {
        // najpierw pusta karta: nowy motyw nakładany raz (wcześniej cała karta była stylowana dwukrotnie)
        val k = currentKey(); container.removeAllViews(); sticky.removeAllViews()
        Ui.changed(); applyTheme(); build(key = k)
    }

    // ---------------- elementy ----------------

    private fun topButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; tag = "item"; isFocusable = true; isClickable = true; textSize = 16f
        setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) }
        setOnClickListener { onClick() }
    }

    /** Ostatnio użyty wiersz – zaznaczenie wraca na niego po zamknięciu okna i przebudowie karty. */
    private var lastKey: Any? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // okno wyboru motywu zamknięte: ekran ustawień budowany od nowa w nowym motywie (karta i wiersz zostają)
        if (themeChangedSinceCreate()) { rebuildForTheme(); return }
        val f = window.decorView.findFocus()
        if (f == null || !f.isAttachedToWindow || (f in tabs && f !== tabs.getOrNull(tab)) || (f === tabs.getOrNull(tab) && lastKey != null)) {
            val target = lastKey?.let { findByKey(container, it) ?: findByKey(sticky, it) }
            (target ?: tabs.getOrNull(tab))?.requestFocus()
        }
    }

    /** Bieżąca rozwijana sekcja – do niej trafiają kolejne wiersze. */
    private var section: LinearLayout? = null
    private var sectionIndex = 0
    private fun target(): LinearLayout = section ?: container

    /** Usuwa ikonę z początku tytułu (ustawienia bez emotikon). */
    private fun clean(t: String) = Ui.stripIcon(t)

    /** Nagłówek rozwijanej sekcji; domyślnie rozwinięta tylko pierwsza sekcja karty. */
    private fun header(title: String) {
        section = null
        val t = clean(title)
        val key = "$tab|$t"
        val open = key in Prefs.settingsOpen        // domyślnie wszystkie sekcje zwinięte
        sectionIndex++
        val sec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(if (narrowPortrait) 6 else 14), Ui.dp(4), Ui.dp(if (narrowPortrait) 2 else 6), Ui.dp(4))
            clipChildren = narrowPortrait; clipToPadding = narrowPortrait
            visibility = if (open) View.VISIBLE else View.GONE
        }
        val h = TextView(this).apply {
            text = (if (open) "▾  " else "▸  ") + t; textSize = 17f * (1f + (Ui.geo.headerScale - 1f) / 2); tag = "item"; setTypeface(typeface, 1)
            isFocusable = true; isClickable = true; setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
            setTag(R.id.row_key, "hdr|$key")
            // rozwinięcie / zwinięcie bez przebudowy całej karty (wcześniej każde OK na nagłówku tworzyło kartę od nowa)
            setOnClickListener {
                val nowOpen = sec.visibility != View.VISIBLE
                if (nowOpen) { Prefs.settingsClosed = Prefs.settingsClosed - key; Prefs.settingsOpen = (Prefs.settingsOpen - key) + key }
                else { Prefs.settingsOpen = Prefs.settingsOpen - key; Prefs.settingsClosed = (Prefs.settingsClosed - key) + key }
                text = (if (nowOpen) "▾  " else "▸  ") + t
                if (nowOpen) styleSection(sec)
                sec.visibility = if (nowOpen) View.VISIBLE else View.GONE
                lastKey = "hdr|$key"
            }
        }
        container.addView(h, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        container.addView(sec)
        section = sec
    }

    /** Podtytuł wewnątrz sekcji (powiązane ustawienia w jednej sekcji, czytelnie rozdzielone). */
    private fun subheader(t: String) {
        target().addView(TextView(this).apply { text = t; tag = "accent"; textSize = 14f; setTypeface(typeface, 1); setPadding(Ui.dp(4), Ui.dp(14), 0, Ui.dp(2)) })
    }

    private fun note(t: String) {
        target().addView(TextView(this).apply { text = t; tag = "text2"; textSize = 13f; setPadding(Ui.dp(4), Ui.dp(2), 0, Ui.dp(4)) })
    }

    /** Telefon w pionie (wąski ekran) – wiersze i podgląd mieszczą się w szerokości ekranu. */
    private val narrowPortrait get() = resources.configuration.screenWidthDp < 700 &&
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    /** [raw] – tytuł to dane użytkownika (nazwa listy, kanału): bez usuwania znaków z początku. */
    private fun row(title: String, value: String? = null, valueColor: Int? = null, key: String? = null, raw: Boolean = false, onClick: () -> Unit): View {
        val shown = if (raw) title else clean(title)
        val rk = key ?: "row|$shown"
        val r = LinearLayout(this).apply {
            tag = "item"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(if (narrowPortrait) 10 else 14), Ui.dp(12), Ui.dp(if (narrowPortrait) 10 else 14), Ui.dp(12))
            setOnClickListener { lastKey = rk; onClick() }
        }
        r.setTag(R.id.row_key, rk)
        r.addView(TextView(this).apply { text = shown; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(TextView(this).apply {
            text = value; textSize = 15f; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // pion: wartość nie wypycha tytułu poza ekran (najwyżej 45% szerokości, zawijanie do 2 linii)
            if (narrowPortrait) { maxWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt(); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            if (valueColor != null) { tag = "keepcolor"; setTextColor(valueColor) } else tag = "text2"
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        target().addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) })
        return r
    }

    private fun toggle(title: String, get: () -> Boolean, look: Boolean = false, set: (Boolean) -> Unit): View {
        val click = { set(!get()); if (look) lookChanged() else build(keepFocus = true) }
        val obsidian = pl.iptv.tv.ui.theme.ObsidianViews.active
        if (!Ps5Theme.active && !obsidian) return row(title, if (get()) "✔ Włączone" else "✖ Wyłączone") { click() }
        // PS5 i „Obsidian Stream”: przełącznik po prawej stronie wiersza (Obsidian – tor w kolorze akcentu)
        val on = get()
        return (row(title) { click() } as LinearLayout).also { r ->
            r.contentDescription = clean(title) + if (on) ": włączone" else ": wyłączone"
            r.addView(android.widget.ImageView(this).apply { setImageDrawable(if (obsidian) pl.iptv.tv.ui.theme.ObsidianViews.Switch(on) else Ps5Switch(on)); tag = "ps5switch"; isDuplicateParentStateEnabled = true },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(12) })
        }
    }

    /**
     * Wiersz wyboru. Wygląd ([look]) – dawne okno z boku (podgląd motywu na żywo widoczny pod spodem); pozostałe – (7.17) okno
     * „Obsidian Stream” z kartami ([Dialogs.choiceCards], opisy [descriptions]); po zamknięciu fokus wraca na ten wiersz.
     */
    /**
     * (7.20) „Zapamiętane silniki kanałów”: okno „Obsidian Stream” (karty bez linii) z listą kanałów i ich ustawieniami;
     * pierwsza karta – wyczyszczenie całej pamięci doboru, kanał – reset tylko jego ustawień (po potwierdzeniu). Po zmianie
     * okno otwierane ponownie z aktualną listą.
     */
    internal fun showChannelEngines(): android.app.Dialog {
        val E = pl.iptv.tv.data.ChannelEngines
        val entries = E.entries()
        val names = pl.iptv.tv.data.Repository.channels.associate { it.key to it.name }
        val options = listOf("Wyczyść całą pamięć doboru") + entries.map { names[it.channelKey] ?: it.channelKey }
        val descriptions = listOf(if (entries.isEmpty()) "Brak zapamiętanych kanałów" else "${E.count - E.failedCount} dobranych, ${E.failedCount} niedziałających") +
            entries.map { e -> if (e.testedFailed) "Nie działał na żadnym silniku – ponowny test po dobie" else (e.profile?.label ?: "ustawienia domyślne") }
        var dialog: android.app.Dialog? = null
        val k = lastKey ?: currentKey()
        dialog = Dialogs.choiceCards(this, "Zapamiętane silniki kanałów", options, -1, descriptions,
            onDismiss = { k?.let { key -> (findByKey(container, key) ?: findByKey(sticky, key))?.requestFocus() } }) { i ->
            if (i == 0) {
                if (entries.isEmpty()) return@choiceCards
                Dialogs.confirm(this, "Zapamiętane silniki", "Usunąć dobrane ustawienia silnika wszystkich kanałów? Kanały, które nie ruszą, zostaną przetestowane ponownie.") {
                    E.clear(); ChannelEngineOptimizer.resetSession(); dialog?.dismiss(); build(keepFocus = true)
                }
            } else {
                val e = entries[i - 1]
                Dialogs.confirm(this, options[i], "Przywrócić domyślne ustawienia silnika tego kanału? Przy kolejnym włączeniu zostanie dobrany od nowa, jeśli będzie trzeba.") {
                    ChannelEngineOptimizer.resetChannel(e.channelKey, e.streamUrl)
                    dialog?.dismiss(); build(keepFocus = true); showChannelEngines()
                }
            }
        }
        return dialog
    }

    private fun choice(title: String, options: List<String>, get: () -> Int, look: Boolean = true, descriptions: List<String> = emptyList(), set: (Int) -> Unit) =
        row(title, options.getOrNull(get()) ?: "") {
            if (look) Dialogs.choiceLive(this, title, options, get()) { set(it); lookChanged() }
            else {
                val k = lastKey ?: currentKey()          // wiersz, który otworzył okno (ustawiany przy kliknięciu)
                lastChoice = Dialogs.choiceCards(this, title, options, get(), descriptions,
                    onDismiss = { k?.let { key -> (findByKey(container, key) ?: findByKey(sticky, key))?.requestFocus() } }) { set(it); build(keepFocus = true) }
            }
        }

    /**
     * (7.17) Szybkie profile wydajności ([EngineProfiles]): poziomy rząd pigułek (odstęp na fokus – bez ucinania), aktywny profil
     * zaznaczony, a nad nim podpis „Profil: …” („Własny (Zmodyfikowany)” po ręcznej zmianie parametru). OK – profil od razu.
     */
    private fun profilePills() {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val active = EngineProfiles.active()
        target().addView(TextView(this).apply { text = "Profil: " + EngineProfiles.activeLabel(); tag = "text2"; textSize = 14f; setPadding(Ui.dp(4), Ui.dp(6), 0, 0) })
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(8), Ui.dp(10)) }
        EngineProfiles.Profile.entries.forEach { p ->
            bar.addView(TextView(this).apply {
                text = p.label; textSize = 14f; tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(O.pillText())
                background = O.pill(rest = 0x1FFFFFFF); isSelected = p == active
                setPadding(Ui.dp(18), Ui.dp(9), Ui.dp(18), Ui.dp(9))
                setTag(R.id.row_key, "profile|" + p.name)
                setOnFocusChangeListener { v, f -> Ui.focusZoom(v, f, 1.05f) }
                setOnClickListener { lastKey = "profile|" + p.name; EngineProfiles.apply(p); build(keepFocus = true) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
        }
        profileBar = bar
        target().addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false; addView(bar)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Rząd pigułek profili (testy). */
    var profileBar: LinearLayout? = null; private set

    /** Ostatnio otwarte okno wyboru (testy). */
    var lastChoice: android.app.Dialog? = null; private set

    private fun field(label: String, initial: String, hint: String, uri: Boolean = true): EditText {
        target().addView(TextView(this).apply { text = label; tag = "text2"; textSize = 14f; setPadding(Ui.dp(4), Ui.dp(10), 0, Ui.dp(4)) })
        val e = EditText(this).apply {
            setText(initial); this.hint = hint; tag = "field"; setSingleLine()
            inputType = if (uri) InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT
            setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10))
        }
        target().addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    // ---------------- Źródła ----------------

    private fun buildActiveList() {
        header("Aktywna lista" + (Playlists.active()?.let { " – ${it.name}" } ?: ""))
        playlistUrl = field("Link (http/https) lub ścieżka pliku, np. /sdcard/Download/lista.m3u",
            Prefs.playlistSource, "http://…/lista.m3u")
        row("Wczytaj listę z podanego adresu") { saveFields(); loadPlaylist(playlistUrl?.text.toString().trim()) }
        row("Wybierz plik .m3u z urządzenia…") { pickFile(REQ_FILE, "*/*") }
        val tok = Playlists.active()?.token.orEmpty()
        row("Token listy", if (tok.isBlank()) "nie ustawiono" else tok.take(4) + "…" + tok.takeLast(2)) {
            Dialogs.input(this, "Token (wstawiany za {token} w adresie listy i EPG)", tok, "np. ab12cd34") { v ->
                Playlists.active()?.let { Playlists.update(it.copy(token = v)) }
                build(keepFocus = true)
                if (Prefs.playlistSource.contains("{token}")) loadPlaylist(Prefs.playlistSource)
            }
        }
        note("Każdy użytkownik może mieć własny token: wpisz adres z {token}, np. http://dostawca.pl/get.php?token={token}, a token podaj osobno.")
        piconUrl = field("Picony – katalog lub szablon z {id} / {name} / {name_raw} (opcjonalnie, domyślnie tvg-logo)",
            Prefs.piconUrl, "http://…/picons/{name}.png")
        userAgent = field("User-Agent (opcjonalnie)", Prefs.userAgent, Repository.DEFAULT_UA, uri = false)
        log = TextView(this).apply { tag = "text2"; setPadding(Ui.dp(4), Ui.dp(14), 0, 0); text = logText }
        target().addView(log)
    }

    private fun buildEpgSource() {
        header("Źródło programu TV (EPG)")
        epgUrl = field("Link do .xml lub .xml.gz – kilka adresów oddziel spacją (puste = adres url-tvg z listy; można użyć {token})", Prefs.epgUrl, SetupActivity.DEFAULT_EPG)
        val t = Prefs.lastEpgUpdate
        note((if (Prefs.playlistEpgUrl.isNotBlank()) "Adres z listy: ${Prefs.playlistEpgUrl}\n" else "") +
            "Ostatnia aktualizacja: " + (if (t == 0L) "nigdy" else DateFormat.getDateTimeInstance().format(Date(t))) +
            "  •  dopasowane kanały: ${Repository.allChannels.count { it.epgKey != null }}/${Repository.allChannels.size}")
        row("Wczytaj EPG teraz") { saveFields(); loadEpg() }
        val ed = listOf(1, 2, 3, 5, 7)
        choice("Pobieraj nowe EPG co", ed.map { if (it == 1) "1 dzień" else "$it dni" }, { ed.indexOf(Prefs.epgRefreshDays).coerceAtLeast(0) }, look = false) { Prefs.epgRefreshDays = ed[it] }
    }

    private fun buildProgramTv() {
        buildEpgSource()
        header("Wygląd przewodnika")
        GuideLook.items(this, { lookChanged() }).forEach { (t, v, f) -> row(t, v) { f() } }
        header("Panel kanałów ◄ podczas oglądania")
        val hours = listOf(3, 6, 12, 24, 48, 72)
        choice("Zakres programu w panelu ◄", hours.map { if (it % 24 == 0) "${it / 24} dni" else "$it godz." }, { hours.indexOf(Prefs.panelHours).coerceAtLeast(0) }, look = false) {
            Prefs.panelHours = hours[it]
        }
        header("Wyszukiwarka i przypomnienia")
        toggle("Wyszukiwarka w programie TV (📅 Program)", { Prefs.epgSearchEnabled }) { Prefs.epgSearchEnabled = it }
        toggle("Przypomnienia o programach", { Prefs.remindersEnabled }) {
            Prefs.remindersEnabled = it; pl.iptv.tv.reminders.ReminderScheduler.schedule(this)
        }
        val leads = listOf(0, 1, 2, 5, 10, 15)
        choice("Przypominaj przed rozpoczęciem", leads.map { if (it == 0) "w chwili startu" else "$it min" },
            { leads.indexOf(Prefs.reminderLead).coerceAtLeast(0) }, look = false) {
            Prefs.reminderLead = leads[it]; pl.iptv.tv.reminders.ReminderScheduler.schedule(this)
        }
        row("🔔 Lista przypomnień", "${pl.iptv.tv.data.Reminders.all().count { it.end > System.currentTimeMillis() }}") {
            startActivity(Intent(this, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_TAB, EpgSearchActivity.TAB_REMINDERS))
        }
        note("Przypomnienie pojawia się jako okno nad obrazem. Gdy aplikacja jest zamknięta, okno wymaga uprawnienia " +
            "„Wyświetlanie nad innymi aplikacjami” (patrz niżej); na telefonie pojawia się też powiadomienie.")

        header("Edycja programu TV")
        row("Otwórz przewodnik") { startActivity(Intent(this, GuideActivity::class.java)) }
        row("Mapowanie EPG kanałów (edytor listy)") { startActivity(Intent(this, EditorActivity::class.java)) }
        row("Wyczyść pamięć EPG i pobierz ponownie") {
            java.io.File(filesDir, "epg.bin").delete(); filesDir.listFiles()?.filter { it.name.startsWith("epg") && it.name.endsWith(".bin") }?.forEach { it.delete() }
            Prefs.lastEpgUpdate = 0; loadEpg()
        }
    }

    private fun buildCatchup() {
        buildCatchupSection()
        header("Odtwarzanie archiwum")
        val back = listOf(5, 10, 15, 30, 60); val fwd = listOf(10, 15, 30, 60, 120, 300)
        choice("Przewijanie do tyłu (◄ / ⏪)", back.map { "$it s" }, { back.indexOf(Prefs.cuBack).coerceAtLeast(0) }, look = false) { Prefs.cuBack = back[it] }
        choice("Przewijanie do przodu (► / ⏩)", fwd.map { if (it >= 60) "${it / 60} min" else "$it s" }, { fwd.indexOf(Prefs.cuFwd).coerceAtLeast(0) }, look = false) { Prefs.cuFwd = fwd[it] }
        choice("Strzałki ◄ ► w archiwum", listOf("Przewijanie", "Panele (jak na żywo)"), { Prefs.cuArrows }, look = false) { Prefs.cuArrows = it }
        val pads = listOf(0, 1, 2, 5, 10)
        choice("Zacznij wcześniej", pads.map { if (it == 0) "bez marginesu" else "$it min" }, { pads.indexOf(Prefs.cuPadBefore).coerceAtLeast(0) }, look = false) { Prefs.cuPadBefore = pads[it] }
        choice("Zakończ później", pads.map { if (it == 0) "bez marginesu" else "$it min" }, { pads.indexOf(Prefs.cuPadAfter).coerceAtLeast(0) }, look = false) { Prefs.cuPadAfter = pads[it] }
        toggle("Pytaj przed końcem programu (30 s)", { Prefs.cuAsk }) { Prefs.cuAsk = it }
        choice("Domyślnie po zakończeniu programu", listOf("Następny program z archiwum", "Kanał na żywo"),
            { if (Prefs.cuAutoNext) 0 else 1 }, look = false) { Prefs.cuAutoNext = it == 0 }
        choice("Czas wyświetlania w archiwum", Ui.ARCHIVE_TIMEOUTS.map { "$it s" }, { Ui.archiveInfoIndex }) { Ui.archiveInfoIndex = it }
        header("Pomijanie reklam")
        val ads = listOf(30, 60, 90, 120, 180, 300)
        choice("„Pomiń reklamę” przeskakuje o", ads.map { if (it >= 60 && it % 60 == 0) "${it / 60} min" else "$it s" }, { ads.indexOf(Prefs.adSkip).coerceAtLeast(0) }, look = false) { Prefs.adSkip = ads[it] }
        note("W archiwum: 2× ► (podwójne naciśnięcie), przycisk „Pomiń reklamę” na dolnym pasku, niebieski przycisk pilota (zmienisz w karcie Pilot) lub menu funkcji ◄◄.")
    }

    private fun buildReceive() {
        header("Odbieranie list z telefonu lub komputera")
        toggle("Odbieraj listy z innych urządzeń", { Prefs.remoteOn }) { on ->
            Prefs.remoteOn = on
            if (on) pl.iptv.tv.remote.RemoteServer.start(this) else if (!pl.iptv.tv.remote.RemoteServer.wanted) pl.iptv.tv.remote.RemoteServer.stop()
        }
        toggle("Odbieraj linki do odtwarzania (wyślij na telewizor)", { Prefs.castReceiver }) { on ->
            Prefs.castReceiver = on
            if (on) { pl.iptv.tv.remote.RemoteServer.start(this); pl.iptv.tv.remote.RemoteServer.refreshService() }
            else if (!pl.iptv.tv.remote.RemoteServer.wanted) pl.iptv.tv.remote.RemoteServer.stop() else pl.iptv.tv.remote.RemoteServer.refreshService()
        }
        if (Prefs.castReceiver) note("Telewizor jest widoczny w sieci Wi-Fi (usługa ${pl.iptv.tv.remote.RemoteServer.SERVICE_TYPE}). Telefon wysyła link do playlisty YouTube / Spotify lub filmu YouTube na /api/cast z kodem PIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()} – odtwarzanie rusza jako aktywna kolejka.")
        if (Prefs.remoteOn) {
            if (!pl.iptv.tv.remote.RemoteServer.running) pl.iptv.tv.remote.RemoteServer.start(this)
            val ips = pl.iptv.tv.remote.RemoteServer.localIps()
            row("📺 Nazwa urządzenia", pl.iptv.tv.remote.RemoteServer.deviceName) {}
            row("🌐 Adres", ips.joinToString(", ") { "$it:${pl.iptv.tv.remote.RemoteServer.PORT}" }.ifEmpty { "brak sieci" }) {}
            // (7.21) kod QR z adresem strony – telefon otwiera ją bez wpisywania IP
            ips.firstOrNull()?.let { ip -> target().addView(QrCode.image(this, "http://$ip:${pl.iptv.tv.remote.RemoteServer.PORT}", 120), LinearLayout.LayoutParams(Ui.dp(120), Ui.dp(120)).apply { setMargins(Ui.dp(14), Ui.dp(4), 0, Ui.dp(6)) }) }
            row("🔑 Kod PIN", pl.iptv.tv.remote.RemoteServer.ensurePin()) { Prefs.remotePin = ""; pl.iptv.tv.remote.RemoteServer.ensurePin(); build(true) }
            toggle("Od razu ustawiaj odebraną listę jako aktywną", { Prefs.remoteAutoActivate }) { Prefs.remoteAutoActivate = it }
            note("Na telefonie (ta sama sieć Wi-Fi):\n• otwórz w przeglądarce http://${ips.firstOrNull() ?: "IP"}:${pl.iptv.tv.remote.RemoteServer.PORT}, wybierz plik i wpisz PIN, albo\n" +
                "• w aplikacji D-iptv Player na telefonie: Ustawienia → Listy → Wyślij w tej samej sieci Wi-Fi – urządzenie pojawi się na liście.")
        }
        subheader("Przez Internet")
        toggle("Odbieraj listy przez Internet", { Prefs.relayOn }) { on ->
            Prefs.relayOn = on
            if (on) pl.iptv.tv.remote.RemoteRelay.start(this) else pl.iptv.tv.remote.RemoteRelay.stop()
        }
        if (Prefs.relayOn) {
            row("Kod tego urządzenia", pl.iptv.tv.remote.RemoteRelay.ensureCode()) {
                Dialogs.confirm(this, "Nowy kod?", "Stary kod przestanie działać.") { Prefs.relayCode = ""; pl.iptv.tv.remote.RemoteRelay.ensureCode(); build(keepFocus = true) }
            }
            row("Kod PIN", pl.iptv.tv.remote.RemoteServer.ensurePin()) {}
            row("Sprawdź teraz") {
                lifecycleScope.launch {
                    val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pl.iptv.tv.remote.RemoteRelay.pollOnce() } }
                    toast(r.fold({ if (it == 0) "Brak nowych list" else "Odebrano list: $it" }, { "Błąd: ${it.message}" }))
                }
            }
            row("Serwer przekaźnika", pl.iptv.tv.remote.RemoteRelay.server) {
                Dialogs.input(this, "Adres serwera ntfy (puste = ntfy.sh)", Prefs.relayServer, pl.iptv.tv.remote.RemoteRelay.DEFAULT_SERVER) { Prefs.relayServer = it; build(keepFocus = true) }
            }
            note("Na telefonie (dowolna sieć, np. komórkowa) w aplikacji D-iptv Player: Ustawienia → Listy → Wyślij przez Internet, wpisz kod i PIN tego urządzenia. " +
                "Dane są szyfrowane kodem urządzenia. Urządzenie sprawdza skrzynkę co 30 s, gdy aplikacja działa; lista czeka na przekaźniku do ok. 3 godzin.")
        }

    }

    /** Prywatność: co aplikacja wysyła (wersja zabezpieczona) i informacja o treściach. */
    private fun buildPrivacy() {
        if (!pl.iptv.tv.data.Activation.enabled) return
        header("Prywatność i aktywacja")
        row("Kod tego urządzenia", pl.iptv.tv.data.Activation.code) {}
        row("Co wysyła aplikacja") {
            Ui.dialog(this).setTitle("Co wysyła aplikacja").setMessage(pl.iptv.tv.data.Activation.describeSent() +
                "\n\nTylko do sprawdzenia aktywacji. Nie są wysyłane listy, adresy, hasła, historia oglądania, pliki ani IP do administratora.")
                .setPositiveButton("OK", null).show()
        }
        note("Aplikacja jest wyłącznie odtwarzaczem i nie dostarcza żadnych treści.")
    }

    private fun buildAdmin() {
        header("Zdalne zarządzanie (administrator)")
        row("Kod administratora", Prefs.adminCode.ifBlank { "nie ustawiono" }) {
            Dialogs.input(this, "Kod z aplikacji D-iptv Admin (XXXX-XXXX-XXXX)", Prefs.adminCode, "ABCD-EFGH-JKMN", code = true) { v ->
                if (v.isNotBlank() && pl.iptv.tv.remote.RemoteRelay.normalize(v).length != 12) { toast("Kod ma 12 znaków"); return@input }
                Prefs.adminCode = v.uppercase()
                if (v.isNotBlank()) { Prefs.relayOn = true; pl.iptv.tv.remote.RemoteRelay.start(this); sendReport() }
                build(keepFocus = true)
            }
        }
        row("Nazwa urządzenia (dla administratora)", Prefs.deviceLabel.ifBlank { pl.iptv.tv.remote.RemoteServer.deviceName }) {
            Dialogs.input(this, "Nazwa, np. „Salon – Kowalscy”", Prefs.deviceLabel) { Prefs.deviceLabel = it; sendReport(); build(keepFocus = true) }
        }
        if (Prefs.adminCode.isNotBlank()) row("Wyślij raport teraz") { sendReport() }
        note("Po wpisaniu kodu urządzenie raz dziennie (przy pierwszym włączeniu aplikacji) i na żądanie wysyła do administratora: nazwę, adres IP, kod i PIN, wersję, aktywną listę i dni do końca ważności. " +
            "Administrator może zdalnie wysłać listę, aktualizację, odświeżyć listę i EPG. Wymaga włączonego odbioru przez Internet (włącza się sam).")

    }

    private fun buildSend() {
        header("Wysyłanie listy na inne urządzenie")
        row("Wyślij w tej samej sieci Wi-Fi…") { sendFlow() }
        row("Wyślij przez Internet (kod urządzenia)…") { sendRelayFlow() }
    }

    private fun buildCatchupSection() {
        header("Archiwum kanałów")
        val all = Repository.allChannels
        note("Kanały z archiwum: ${all.count { it.hasCatchup }}/${all.size}" +
            (if (Prefs.catchupDefaultType.isNotBlank()) " (z ustawieniem domyślnym)" else " (wg listy)"))
        val types = listOf("" to "Wyłączone – tylko wg listy", "shift" to "shift (?utc=…&lutc=…)", "default" to "default (własny szablon)",
            "append" to "append (doklejany szablon)", "flussonic" to "Flussonic", "xc" to "Xtream Codes")
        choice("Catch-up dla kanałów bez informacji w liście", types.map { it.second },
            { types.indexOfFirst { it.first == Prefs.catchupDefaultType }.coerceAtLeast(0) }, look = false) {
            Prefs.catchupDefaultType = types[it].first; Playlists.captureActive(); Repository.reapply()
        }
        if (Prefs.catchupDefaultType in listOf("default", "append")) {
            row("Szablon (catch-up-source)", Prefs.catchupDefaultSource.ifBlank { "nie ustawiono" }) {
                Dialogs.input(this, "Szablon catch-up", Prefs.catchupDefaultSource,
                    if (Prefs.catchupDefaultType == "append") "?utc={utc}&lutc={lutc}" else "http://serwer/{id}/{utc}-{duration}.m3u8") { v ->
                    Prefs.catchupDefaultSource = v; Playlists.captureActive(); Repository.reapply(); build(keepFocus = true)
                }
            }
            note("Zmienne: {utc} {utcend} {lutc} {duration} {offset} {Y}{m}{d}{H}{M}{S} \${start} \${end} \${timestamp}")
        }
        if (Prefs.catchupDefaultType.isNotBlank()) {
            val days = listOf(1, 2, 3, 5, 7, 14)
            choice("Długość archiwum", days.map { "$it dni" }, { days.indexOf(Prefs.catchupDefaultDays).coerceAtLeast(0) }, look = false) {
                Prefs.catchupDefaultDays = days[it]; Playlists.captureActive(); Repository.reapply()
            }
            toggle("Nadpisz również kanały z catch-up w liście", { Prefs.catchupForce }) { Prefs.catchupForce = it; Playlists.captureActive(); Repository.reapply() }
        }
    }

    private fun saveFields() {
        epgUrl?.let { Prefs.epgUrl = it.text.toString().trim(); Playlists.active()?.let { a -> Playlists.update(a.copy(epg = Prefs.epgUrl)) } }
        piconUrl?.let { Prefs.piconUrl = it.text.toString().trim() }
        userAgent?.let { Prefs.userAgent = it.text.toString().trim() }
        Playlists.captureActive()
    }

    private fun log(s: String, reset: Boolean = false) {
        if (reset) logText.clear() else if (logText.isNotEmpty()) logText.append('\n')
        logText.append(s); log?.text = logText
    }

    private fun loadPlaylist(src: String, done: (Boolean) -> Unit = {}) {
        if (src.isBlank()) { log("Podaj link lub ścieżkę do pliku .m3u", true); done(false); return }
        if (!src.startsWith("http", true) && !src.startsWith("content:") && !isAppPrivate(src) && needsStoragePermission()) return
        log("Wczytywanie listy…", true)
        lifecycleScope.launch {
            runCatching { Repository.loadPlaylist(src) }
                .onSuccess { n ->
                    log("Wczytano $n kanałów.")
                    if (Prefs.effectiveEpgUrl.isNotBlank()) loadEpg()
                    done(true)
                }
                .onFailure { log("Błąd: ${it.message}"); toast("Nie udało się wczytać listy: ${it.message}"); done(false) }
        }
    }

    private fun loadEpg() {
        if (Prefs.effectiveEpgUrl.isBlank()) { log("Brak adresu EPG"); return }
        log("Pobieranie EPG… (może potrwać)")
        lifecycleScope.launch {
            runCatching { Repository.loadEpg() }
                .onSuccess { n ->
                    val mapped = Repository.allChannels.count { it.epgKey != null }
                    log("EPG: $n programów, dopasowano $mapped/${Repository.allChannels.size} kanałów.")
                    if (tab == 0) build()
                }
                .onFailure { log("Błąd EPG: ${it.message}") }
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && !runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)) {
            log("Brak dostępu do plików – nadaj uprawnienie i spróbuj ponownie.", true)
            wizard = PermissionWizard(this) { wizard = null }.also { it.start() }
            return true
        }
        if (Build.VERSION.SDK_INT in 23..29 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1); return true }
        return false
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == PermissionWizard.REQ) { wizard?.onPermissionResult(rc); return }
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadPlaylist(Prefs.playlistSource.ifBlank { playlistUrl?.text.toString() })
        else log("Brak uprawnień do odczytu plików")
    }

    // ---------------- Wygląd ----------------

    private fun hex(c: Int) = String.format("■  #%06X", c and 0xFFFFFF)

    private fun buildLook() {
        // (7.8) język interfejsu (polski / angielski) – ekrany przebudowywane od razu
        header("Język / Language")
        choice("Język / Language", I18n.LANGS.map { it.second }, { I18n.LANGS.indexOfFirst { it.first == I18n.lang }.coerceAtLeast(0) }) {
            I18n.setLanguage(this, I18n.LANGS[it].first); I18n.attach(window)
        }
        header("Motyw")
        choice("Motyw", Ui.PRESETS.map { it.name }, { Ui.PRESETS.indexOf(Ui.preset) }) {
            Ui.applyPreset(Ui.PRESETS[it].id)
        }
        // (7.7) własne motywy: zapis bieżącego wyglądu (kolory, czcionka, animacje…) i przywracanie
        row("💾 Zapisz bieżący wygląd jako własny motyw…") {
            Dialogs.input(this, "Własny motyw", "Mój motyw", "Nazwa motywu") { name ->
                val s = CustomThemes.save(name)
                toast("Zapisano motyw „${s.name}”"); build(keepFocus = true)
            }
        }
        CustomThemes.list().forEach { s ->
            row("★ ${s.name}", Ui.PRESETS.firstOrNull { it.id == s.base }?.name.orEmpty()) {
                Dialogs.menu(this, s.name, listOf(
                    "Zastosuj" to { CustomThemes.apply(s); toast("Motyw „${s.name}”"); lookChanged() },
                    "Usuń" to { CustomThemes.delete(s.name); build(keepFocus = true) },
                ))
            }
        }
        note("Motyw działa od razu na wszystkich ekranach (menu, odtwarzacz, pasek informacji, okna) – bez ponownego uruchamiania.")
        note("Własne zmiany wyglądu zapisują się same jako „Własny – <motyw>” przed przełączeniem na inny motyw – można do nich wrócić.")
        note("Wybór motywu ustawia kolory, czcionkę, animacje, pasek postępu i położenie paska informacji. Każdą z tych opcji możesz potem zmienić osobno.")
        // (7.22) siatka interfejsu (TvResolution); okno kart w palecie aktywnego motywu (Dialogs.choiceCards)
        header(UI_RESOLUTION_TITLE)
        val uiModes = UiResolutionMode.entries
        choice(UI_RESOLUTION_TITLE, uiModes.map { it.label }, { UiResolutionMode.current().ordinal }, look = false, descriptions = uiModes.map { it.description }) {
            if (it != Prefs.uiResolution) { Prefs.uiResolution = it; TvResolution.modeChanged(this) }
        }
        note(UI_RESOLUTION_NOTE)
        header("Nowoczesny")
        if (ModernTheme.active) {
            toggle("Ekran główny jako ekran startowy", { Prefs.modernHome }) { Prefs.modernHome = it }
            toggle("Menu jako panel nakładkowy", { Prefs.modernOverlay }) { Prefs.modernOverlay = it }
            row("▶ Otwórz ekran główny") { ModernTheme.openHome(this) }
        } else row("Włącz motyw Nowoczesny") { ModernTheme.enable(); lookChanged() }
        note("Nowoczesny: głęboka czerń, pasek ikon przy lewej krawędzi, duży podgląd zaznaczonego materiału z przyciskami „Odtwórz” i „Więcej informacji”, " +
            "rzędy kanałów, filmów STube i tytułów Stremio z postępem oglądania; menu jako półprzezroczysty panel z prawej strony.")
        header("X25 Dashboard")
        toggle("Motyw X25 Dashboard", { X25Theme.active }, look = true) { X25Theme.setEnabled(it) }
        if (X25Theme.active) {
            toggle("Pulpit X25 jako ekran startowy", { Prefs.x25Dashboard }) { Prefs.x25Dashboard = it }
            toggle("Menu jako panel boczny (Guide)", { Prefs.x25Guide }) { Prefs.x25Guide = it }
            toggle("Dźwięki nawigacji", { Prefs.x25Sounds }) { Prefs.x25Sounds = it }
            row("▶ Otwórz pulpit X25") { X25Theme.openDashboard(this) }
            row("↺ Przywróć układ kafelków pulpitu") {
                lifecycleScope.launch { pl.iptv.tv.data.X25Store.reset(); I18n.makeToast(this@SettingsActivity, "Przywrócono domyślny układ pulpitu", Toast.LENGTH_SHORT).show() }
            }
        }
        note("X25: grafit i czerń z neonową zielenią, akrylowe panele i pulpit z kafelkami. Kafelki edytujesz na pulpicie: przytrzymaj OK lub MENU. Wyłączenie wraca do poprzedniego motywu.")
        header("PS5")
        toggle("Motyw PS5", { Ps5Theme.active }, look = true) { Ps5Theme.setEnabled(it) }
        if (Ps5Theme.active) {
            toggle("Ekran główny PS5 jako ekran startowy", { Prefs.ps5Home }) { Prefs.ps5Home = it }
            toggle("Menu jako panel ustawień PS5", { Prefs.ps5Panel }) { Prefs.ps5Panel = it }
            toggle("Dźwięki nawigacji", { Prefs.ps5Sounds }) { Prefs.ps5Sounds = it }
            row("▶ Otwórz ekran główny PS5") { Ps5Theme.openHome(this) }
        }
        note("PS5: głęboki granat, świetliste błękity i tło w stylu rysunku technicznego, ekran główny z zakładkami, rzędem aplikacji i kartami. " +
            "Włączenie PS5 przy X25 (i odwrotnie) przełącza motyw konsolowy; wyłączenie wraca do poprzedniego.")
        header("Układ menu głównego")
        choice("Układ menu", MAIN_LAYOUTS, { Prefs.mainLayout }, look = false) { Prefs.mainLayout = it }
        choice("Orientacja ekranu (telefon)", listOf("Poziomo", "Automatycznie (obrót)", "Pionowo (odtwarzacz z obrotem)"), { Prefs.orientation }, look = false) {
            Prefs.orientation = it; recreate()
        }

        // podgląd przewija się razem z treścią (nie zasłania opcji)
        container.addView(buildPreview())
        container.addView(TextView(this).apply {
            text = "Pokaż animację paneli"; tag = "item"; isFocusable = true; isClickable = true; textSize = 14f
            setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6)); setTag(R.id.row_key, "row|anim")
            setOnClickListener { previewPanel?.let { p -> p.visibility = View.GONE; Ui.slideIn(p, fromLeft = true) } }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(4) })

        header("Kolory")
        Ui.COLOR_KEYS.forEach { (key, label) ->
            val title = label + if (key in Ui.GRADIENT_KEYS) "  (kolor lub gradient)" else ""
            row(title, "■  " + Ui.colorLabel(key), Ui.color(key)) { editColor(key, label) }
        }
        choice("Kierunek gradientów", Ui.GRAD_DIRS, { Ui.gradDir }) { Ui.gradDir = it }
        row("↺ Przywróć kolory motywu") { Ui.resetColors(); lookChanged() }

        header("Czcionka")
        choice("Krój czcionki", Ui.FONTS.map { it.label }, { Ui.fontIndex }) { Ui.fontIndex = it }
        choice("Wielkość tekstu", Ui.FONT_SCALES.map { it.second }, { Ui.fontScaleIndex }) { Ui.fontScaleIndex = it }

        header("Przejścia i animacje")
        choice("Animacja przejść i paneli", Ui.ANIMS, { Ui.anim }) { Ui.anim = it }
        choice("Szybkość animacji", Ui.ANIM_SPEEDS.map { it.second }, { Ui.animSpeedIndex }) { Ui.animSpeedIndex = it }
        toggle("Powiększanie zaznaczonego elementu", { Ui.zoom }, look = true) { Ui.zoom = it }

        header("Pasek informacji i postęp programu")
        choice("Wygląd paska", Ui.PROGRESS, { Ui.progressStyle }) { Ui.progressStyle = it }
        toggle("Pokazuj pozostały czas programu", { Ui.progressRemaining }, look = true) { Ui.progressRemaining = it }

        subheader("Pasek informacji podczas oglądania")
        choice("Położenie", Ui.INFO_POS, { Ui.infoPos }) { Ui.infoPos = it }
        choice("Styl paska", Ui.INFO_STYLES, { Ui.infoStyle }) { Ui.infoStyle = it }
        choice("Krycie tła paska", Ui.INFO_ALPHAS.map { "$it %" }, { Ui.infoAlphaIndex }) { Ui.infoAlphaIndex = it }
        choice("Wielkość paska", Ui.INFO_SIZES.map { it.second }, { Ui.infoSizeIndex }) { Ui.infoSizeIndex = it }
        choice("Czas wyświetlania", Ui.INFO_TIMEOUTS.map { "$it s" }, { Ui.infoTimeoutIndex }) { Ui.infoTimeoutIndex = it }
        toggle("Logo kanału", { Ui.infoPicon }, look = true) { Ui.infoPicon = it }
        toggle("Zegar", { Ui.infoClock }, look = true) { Ui.infoClock = it }
        toggle("Następny program / opis", { Ui.infoNext }, look = true) { Ui.infoNext = it }

        header("Tło menu")
        val hasBg = Prefs.bgImageOn && Repository.bgImageFile.exists()
        row("🖼 Zdjęcie w tle", if (hasBg) "Ustawione" else "Brak") {
            Dialogs.menu(this, "Zdjęcie w tle", listOf(
                "📂 Wybierz zdjęcie z urządzenia…" to { pickFile(REQ_IMAGE, "image/*") },
                "🔗 Pobierz z linku…" to {
                    Dialogs.input(this, "Link do zdjęcia (jpg/png)", "", "https://…/tlo.jpg") { url ->
                        if (url.isNotBlank()) setBackground(url)
                    }
                },
                "✕ Usuń zdjęcie" to { Prefs.bgImageOn = false; Repository.bgImageFile.delete(); lookChanged() },
            ))
        }
        choice("Przyciemnienie zdjęcia", Ui.DIMS.map { "$it %" }, { Ui.dimIndex }) { Ui.dimIndex = it }
        choice("Krycie paneli", Ui.PANEL_ALPHAS.map { "$it %" }, { Ui.panelAlphaIndex }) { Ui.panelAlphaIndex = it }
    }

    private var previewPanel: View? = null

    /** Miniatura ekranu: tło (kolor/gradient/zdjęcie), panel z listą, zaznaczenie, pasek postępu, pasek informacji. */
    private fun buildPreview(): View {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val screen = android.widget.FrameLayout(this).apply { background = Ui.bgDrawable(); clipChildren = true }
        val bgFile = Repository.bgImageFile
        if (Prefs.bgImageOn && bgFile.exists()) {
            screen.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(android.graphics.BitmapFactory.decodeFile(bgFile.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }))
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            screen.addView(View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(Ui.DIMS[Ui.dimIndex.coerceIn(Ui.DIMS.indices)] * 255 / 100, 0, 0, 0))
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        // panel z dwoma kanałami: zwykły i zaznaczony
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.panelBg(); setPadding(px(6), px(6), px(6), px(6))
        }
        fun channelRow(name: String, now: String, frac: Float, focused: Boolean): View {
            val r = layoutInflater.inflate(R.layout.item_channel, panel, false)
            r.layoutParams = LinearLayout.LayoutParams(-1, px(52)).apply { bottomMargin = px(3) }
            r.findViewById<TextView>(R.id.num).text = if (focused) "2" else "1"
            r.findViewById<TextView>(R.id.name).text = name
            r.findViewById<TextView>(R.id.now).text = now + if (Ui.progressRemaining) "  (jeszcze 18 min)" else ""
            r.findViewById<ImageView>(R.id.picon).setImageResource(R.drawable.ic_tv)
            r.findViewById<ProgView>(R.id.progress).apply { fraction = frac; visibility = if (Ui.progressStyle == 4) View.GONE else View.VISIBLE }
            r.findViewById<TextView>(R.id.catchup).setTextColor(Ui.archive)
            r.findViewById<View>(R.id.fav).visibility = if (focused) View.VISIBLE else View.GONE
            Ui.style(r)
            r.isFocusable = false; r.isClickable = false
            if (focused) {
                r.background = Ui.focusedBg()
                listOf(R.id.name, R.id.now, R.id.num, R.id.fav).forEach { r.findViewById<TextView>(it).setTextColor(Ui.focusText) }
                if (Ui.zoom) { r.scaleX = 1.035f; r.scaleY = 1.035f }
            }
            return r
        }
        panel.addView(channelRow("Przykładowy kanał HD", "20:00 Wiadomości", 0.6f, false))
        panel.addView(channelRow("Kanał zaznaczony", "20:15 Film fabularny", 0.3f, true))
        previewPanel = panel
        // układ pionowy: pasek informacji nad lub pod listą (bez nakładania)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1))
        val panelBox = android.widget.FrameLayout(this)
        // na wąskim ekranie panel podglądu nie szerszy niż 75% ekranu (wcześniej wystawał poza ekran w pionie)
        panelBox.addView(panel, android.widget.FrameLayout.LayoutParams(minOf(px(300), (resources.displayMetrics.widthPixels * 0.75f).toInt()), -2).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; leftMargin = px(12)
        })
        // pasek informacji (góra/dół)
        val top = Ui.infoPos == 1
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.infoBarBg(top); setPadding(px(14), px(8), px(14), px(8))
        }
        val sc = Ui.infoScale
        info.addView(TextView(this).apply { text = "1. Przykładowy kanał HD" + if (Ui.infoClock) "   ⏱ 20:12" else ""; textSize = 14f * sc; setTypeface(typeface, 1) })
        info.addView(TextView(this).apply { text = "20:00–20:30  Wiadomości"; textSize = 12f * sc })
        if (Ui.infoNext) info.addView(TextView(this).apply { text = "Następnie: 20:30 Pogoda"; textSize = 11f * sc; tag = "text2" })
        info.addView(ProgView(this).apply { fraction = 0.4f; visibility = if (Ui.progressStyle == 4) View.GONE else View.VISIBLE },
            LinearLayout.LayoutParams(-1, px(6)).apply { topMargin = px(4) })
        Ui.style(info)
        if (Ui.infoStyle == 4) (0 until info.childCount).forEach { (info.getChildAt(it) as? TextView)?.setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK) }
        val infoLp = LinearLayout.LayoutParams(-1, -2).apply { if (Ui.infoFloating) setMargins(px(14), px(6), px(14), px(6)) }
        if (top) column.addView(info, infoLp)
        column.addView(panelBox, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!top) column.addView(info, infoLp)
        screen.layoutParams = LinearLayout.LayoutParams(-1, px(if (resources.configuration.screenHeightDp < 600) 200 else 230))
        return screen
    }

    private fun editColor(key: String, label: String) {
        if (key !in Ui.GRADIENT_KEYS) {
            Dialogs.color(this, label, Ui.color(key), live = true) { Ui.setColor(key, it); lookChanged() }; return
        }
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "■ Kolor jednolity…" to { Dialogs.color(this, label, Ui.color(key), live = true) { Ui.setGradient(key, it, null); lookChanged() } },
            "▤ Gradient – wybierz 2 kolory…" to {
                Dialogs.color(this, "$label – kolor 1", Ui.color(key)) { c1 ->
                    Dialogs.color(this, "$label – kolor 2", Ui.color2(key) ?: Ui.shade(c1, 0.3f)) { c2 -> Ui.setGradient(key, c1, c2); lookChanged() }
                }
            },
            "✨ Gotowy gradient…" to {
                Dialogs.menu(this, "Gotowe gradienty", Ui.GRADIENT_PRESETS.map { (n, a, b) ->
                    "$n  ($a → $b)" to { Ui.setGradient(key, android.graphics.Color.parseColor(a), android.graphics.Color.parseColor(b)); lookChanged() }
                })
            },
        )
        if (Ui.color2(key) != null) items += "✕ Usuń gradient (zostaw kolor 1)" to { Ui.setGradient(key, Ui.color(key), null); lookChanged() }
        Dialogs.menu(this, label, items)
    }

    private fun setBackground(src: String) {
        I18n.makeToast(this, "Wczytywanie zdjęcia…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { Repository.saveBackground(src) }
                .onSuccess { lookChanged() }
                .onFailure { I18n.makeToast(this@SettingsActivity, "Błąd: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Funkcje ----------------

    // ================= Menu i kanały =================
    private fun buildMenuChannels() {
        header("Lista kanałów w menu")
        toggle("Grupa „Ulubione” na górze listy", { Prefs.showFavGroup }) { Prefs.showFavGroup = it }
        toggle("Grupa „Ostatnio oglądane” na liście", { Prefs.showRecentGroup }) { Prefs.showRecentGroup = it }
        toggle("Wyszukiwarka kanałów", { Prefs.searchEnabled }) { Prefs.searchEnabled = it }
        toggle("Numery kanałów", { Prefs.showNumbers }) { Prefs.showNumbers = it }
        toggle("Podgląd kanału na żywo w menu głównym", { Prefs.mainPreview }) { Prefs.mainPreview = it }
        row("✎ Otwórz edytor listy kanałów") { startActivity(Intent(this, EditorActivity::class.java)) }

        header("Kategorie")
        row("⇅ Kolejność i widoczność kategorii…") { Dialogs.editGroups(this) { build(keepFocus = true) } }
        note("Kanały ukrytej kategorii zostają w „Wszystkie”. To samo okno otworzysz w menu: „✎ Edytuj” na końcu kategorii.")

        header("Ulubione i ostatnio oglądane")
        val limits = listOf(3, 4, 5, 6, 8, 10, 12, 15, 20)
        choice("Limit ostatnio oglądanych", limits.map { "$it kanałów" }, { limits.indexOf(Prefs.recentLimit).coerceAtLeast(0) }, look = false) {
            Prefs.recentLimit = limits[it]
        }
        note("Kanał trafia do ostatnio oglądanych po co najmniej 1 minucie oglądania.")
        row("🗑 Wyczyść historię oglądania") {
            Dialogs.confirm(this, "Historia", "Usunąć listę ostatnio oglądanych?") { Repository.clearRecent(); toast("Wyczyszczono") }
        }
        row("🗑 Wyczyść ulubione") {
            Dialogs.confirm(this, "Ulubione", "Usunąć wszystkie ulubione kanały?") { Prefs.favorites = emptyList(); toast("Wyczyszczono") }
        }
    }

    // ================= Odtwarzanie =================
    private fun buildPlayback() {
        header("Panele i lista kanałów")
        toggle("Lista kanałów z boku (◄)", { Prefs.channelPanel }) { Prefs.channelPanel = it }
        toggle("Ostatnio oglądane i opis programu (►)", { Prefs.recentPanel }) { Prefs.recentPanel = it }
        toggle("Lista kanałów po naciśnięciu OK", { Prefs.zapListEnabled }) { Prefs.zapListEnabled = it }
        if (Prefs.zapListEnabled) {
            val zr = (5..12).toList()
            choice("Ile kanałów na liście po OK", zr.map { "$it" }, { zr.indexOf(Prefs.zapRows).coerceAtLeast(0) }, look = false) { Prefs.zapRows = zr[it] }
        }

        header("Menu boczne (2× ◄)")
        note("OK na pozycji: pokaż/ukryj i zmiana kolejności.")
        row("Aplikacje w menu bocznym (2× ◄)", "${SystemTools.quickApps(this).size}") {
            SystemTools.manageQuickApps(this) { build(keepFocus = true) }
        }
        val order = pl.iptv.tv.data.QuickMenu.order()
        order.forEachIndexed { i, id ->
            val hidden = pl.iptv.tv.data.QuickMenu.isHidden(id)
            row("${i + 1}. " + pl.iptv.tv.data.QuickMenu.ITEMS[id], if (hidden) "✖ ukryte" else "✔ widoczne", key = "q|$id") { quickItemMenu(id) }
        }
        row("↺ Przywróć domyślne menu") { pl.iptv.tv.data.QuickMenu.reset(); build(keepFocus = true) }

        header("Szybkie menu ikon (przytrzymaj OK lub ekran)")
        choice("Przytrzymanie OK / ekranu", listOf("Od razu menu silnika i akceleracji", "Szybkie menu ikon (z kafelkiem silnika) – domyślnie"), { Prefs.okLongAction.coerceIn(0, 1) }, look = false) { Prefs.okLongAction = it }
        toggle("Szybkie menu ikon w odtwarzaczu", { Prefs.miniMenuEnabled }) { Prefs.miniMenuEnabled = it }
        note("Ikony obok siebie na środku ekranu, podpis pod zaznaczoną; znika po 3 s. OK na pozycji: pokaż/ukryj i kolejność.")
        val mini = pl.iptv.tv.data.MiniMenu
        mini.order().forEachIndexed { i, id ->
            row("${i + 1}. " + mini.ITEMS[id], if (mini.isHidden(id)) "✖ ukryte" else "✔ widoczne", key = "m|$id") { quickItemMenu(id, mini) }
        }
        row("➕ Dodaj skrót aplikacji…") {
            val apps = SystemTools.installedApps(this).filter { !mini.hasApp(it.intent.component!!.flattenToString()) }
            if (apps.isEmpty()) toast("Brak aplikacji do dodania")
            else Dialogs.menu(this, "Skrót w mini menu", apps.map { e -> e.label to {
                mini.addApp(e.intent.component!!.flattenToString(), e.label); toast("Dodano: ${e.label}"); build(keepFocus = true)
            } })
        }
        row("↺ Przywróć domyślne mini menu") { mini.reset(); build(keepFocus = true) }

        buildPip()
        header("Dźwięk")
        val delays = (-1000..1000 step 100).toList()
        choice("Synchronizacja dźwięku z obrazem", delays.map { PlayerActivity.delayLabel(it) }, { delays.indexOf(Prefs.audioDelayMs).coerceAtLeast(10) }, look = false) { Prefs.audioDelayMs = delays[it] }
        note("Gdy dźwięk spóźnia się względem obrazu, wybierz „dźwięk wcześniej”. Zmienisz to też podczas oglądania: przytrzymaj OK → Audio i napisy.")

        header("Silnik odtwarzania i akceleracja")
        profilePills()
        // (7.17) bufor dla każdego urządzenia ze stanem ochrony (np. „2 s (tryb szybki)”, „+1,5 s ochrony” po przycięciach)
        val bufs = EngineProfiles.BUFFER_VALUES
        choice("Bufor – minimum", bufs.map { if (it == Prefs.bufMinS) EngineProfiles.bufferLabel(it) else EngineProfiles.bufferLabel(it, 0) },
            { bufs.indexOf(EngineConfigRepository.current.bufMinS).coerceAtLeast(0) }, look = false,
            descriptions = bufs.map { when { it == 0 -> "Start po 1 s, po przycięciu 2 s – ustawienie zalecane"; it <= 3 -> "Najszybsze przełączanie kanałów; po częstych przycięciach zapas rośnie sam"; it <= 5 -> "Szybki start i mniej przycięć"; else -> "Najmniej przycięć przy słabym łączu, dłuższy start" } }) {
            EngineConfigRepository.setBufMin(bufs[it])           // (7.20) jedno źródło ustawień – odtwarzacz przebudowany w locie
        }
        choice("Silnik odtwarzania", Playback.ENGINES, { EngineConfigRepository.current.engine.coerceIn(Playback.ENGINES.indices) }, look = false,
            descriptions = listOf("Obraz dekoduje układ graficzny – najmniejsze obciążenie", "Gdy sprzętowy daje zielony / czarny obraz – większe obciążenie procesora",
                "Obraz i dźwięk synchronizowane sprzętowo (włącza akcelerację i kolejkę asynchroniczną)")) {
            // (7.17) tunelowanie wymaga dekodera sprzętowego i asynchronicznej kolejki – obie opcje włączane od razu (repozytorium)
            EngineConfigRepository.setEngine(it); build(keepFocus = true)
        }
        note("Dekoder sprzętowy: obraz dekoduje układ graficzny (najmniejsze obciążenie, zalecany). Programowy: gdy sprzętowy daje zielony/czarny obraz lub nie obsługuje formatu (większe obciążenie procesora). Tunelowanie: obraz i dźwięk synchronizowane sprzętowo – na zgodnych Android TV najpłynniejszy obraz; synchronizacja dźwięku z ustawień może wtedy nie działać.")
        // (7.17) spójność z tunelowaniem: inna kolejka albo wyłączona akceleracja – powrót do dekodera sprzętowego bez tunelowania
        choice("Kolejka dekodera", listOf("Automatycznie", "Asynchroniczna (szybsza)", "Synchroniczna (gdy obraz zamarza)"), { EngineConfigRepository.current.asyncDecoder.coerceIn(0, 2) }, look = false,
            descriptions = listOf("Wybór Media3 wg urządzenia", "Szybsze dekodowanie (wymagana przy tunelowaniu)", "Na telewizorach, na których obraz staje przy działającym dźwięku")) {
            EngineConfigRepository.setAsyncDecoder(it); build(keepFocus = true)
        }
        toggle("Akceleracja sprzętowa obrazu", { EngineConfigRepository.current.hwAccel }) {
            EngineConfigRepository.setHwAccel(it)
        }
        note("Wyłączona akceleracja = dekoder programowy (gdy obraz ma artefakty lub jest zielony). Silnik i akceleracja zmienisz też podczas oglądania: przytrzymaj OK.")
        toggle("Zapasowy dekoder przy błędzie", { EngineConfigRepository.current.decoderFallback }) { EngineConfigRepository.setDecoderFallback(it) }
        toggle("Dekoder programowy, gdy obraz zamarza", { Prefs.freezeFallback }) { Prefs.freezeFallback = it; Playback.stop() }
        note("Gdy obraz stoi, a dźwięk gra (ok. ${Playback.FREEZE_SWITCH_MS / 1000} s), odtwarzacz sam przechodzi na dekoder programowy – do zamknięcia aplikacji albo ręcznej zmiany silnika.")
        // (7.22) strażnik klatkowania – całkowite włączenie / wyłączenie (to samo źródło co okno silnika w odtwarzaczu)
        toggle(AUTO_STUTTER_TITLE, { EngineConfigRepository.current.autoStutterCorrectionEnabled }) { EngineConfigRepository.setAutoStutterCorrection(it) }
        note(AUTO_STUTTER_NOTE)
        toggle("Dobór silnika dla niedziałających kanałów", { Prefs.engineOptimizer }) { Prefs.engineOptimizer = it }
        row("Zapamiętane silniki kanałów", "${pl.iptv.tv.data.ChannelEngines.count - pl.iptv.tv.data.ChannelEngines.failedCount} dobranych, ${pl.iptv.tv.data.ChannelEngines.failedCount} niedziałających") {
            showChannelEngines()
        }
        note("Kanał bez obrazu po ${Playback.START_CHECK_MS / 1000} s albo z błędem dekodera: odtwarzacz sprawdza po kolei inne ustawienia (dekoder programowy, dźwięk FFmpeg, kolejka dekodera, HLS / TS – po ok. ${ChannelEngineOptimizer.PROBE_MS / 1000} s na próbę, w tle) i zapamiętuje działające dla tego kanału. Gdy nic nie pomaga (np. serwer nie działa), kanał nie jest testowany ponownie przez dobę.")
        // (7.17) przełączanie kanałów – dla każdego urządzenia (dawniej tylko w trybie słabszych urządzeń)
        toggle("Czyszczenie dekodera przy zmianie kanału", { Prefs.cleanDecoderOnZap }) { Prefs.cleanDecoderOnZap = it }
        val zapDelays = listOf(150, 200, 250, 350, 450, 600, 800, 1000, 1500)
        choice("Opóźnienie startu po ▲▼", zapDelays.map { "$it ms" }, { zapDelays.indexOf(Prefs.zapDelayMs).let { i -> if (i < 0) zapDelays.indexOfFirst { v -> v >= Prefs.zapDelayMs }.coerceAtLeast(0) else i } }, look = false) {
            Prefs.zapDelayMs = zapDelays[it]
        }
        note("Czyszczenie dekodera: każdy kanał startuje na świeżym dekoderze (pomaga, gdy obraz zacina się po ▲▼). Opóźnienie startu: szybkie przewijanie kanałów nie uruchamia każdego po drodze (domyślnie 250 ms, w trybie słabszych urządzeń 450 ms).")
        toggle("Dopasowanie odświeżania ekranu (Auto Frame Rate)", { EngineConfigRepository.current.autoFrameRate }) { EngineConfigRepository.setAutoFrameRate(it) }
        note("Automatycznie przełącza odświeżanie TV na 50 Hz / 24 Hz dla idealnej płynności ruchu (wg klatek kanału). Po wyjściu z odtwarzacza ekran wraca do zwykłego trybu; działa, gdy telewizor ma taki tryb.")
        // (7.20) klatkowanie po 2–3 s przy płynnym dźwięku (kanały 50 kl/s / 1080i50, np. TCL z Google TV)
        val surfaces = RenderSurfaceMode.entries
        choice("Powierzchnia obrazu", surfaces.map { it.label }, { EngineConfigRepository.current.surface.ordinal }, look = false, descriptions = surfaces.map { it.description }) {
            EngineConfigRepository.setSurfaceMode(RenderSurfaceMode.entries[it])
        }
        note("Automatycznie: wydajny SurfaceView, a TextureView dla kanałów, które klatkowały – pomaga, gdy telewizor z Google TV źle usuwa przeplot (1080i50).")
        // (7.22) limit rozdzielczości obrazu – to samo źródło co okno silnika w odtwarzaczu (zmiana w locie, bez przebudowy)
        val limits = VideoResolutionLimit.entries
        choice(MAX_VIDEO_TITLE, limits.map { it.label }, { EngineConfigRepository.current.maxVideoResolution.ordinal }, look = false, descriptions = limits.map { it.description }) {
            EngineConfigRepository.setMaxVideoResolution(limits[it])
        }
        note(MAX_VIDEO_NOTE)
        toggle("Wyrównywanie głośności kanałów", { EngineConfigRepository.current.loudness }) { EngineConfigRepository.setLoudness(it) }
        note("Łagodne podbicie (+3 dB) cichych ścieżek (np. AC3 / E-AC3) – mniejsze różnice głośności między kanałami. Na części urządzeń (np. z tunelowaniem) efekt jest niedostępny i opcja nic nie zmienia.")
        toggle("Niskie opóźnienie na żywo", { EngineConfigRepository.current.lowLatency }) { EngineConfigRepository.setLowLatency(it) }
        note("Niskie opóźnienie: start po 0,5 s buforu i odtwarzanie bliżej bieżącej chwili transmisji (mniejsze opóźnienie, przy słabym łączu częstsze buforowanie).")
        row("Bieżący dekoder", Playback.decoderInfo().take(60)) { Dialogs.confirm(this, "Bieżący dekoder", Playback.decoderInfo()) {} }
        note("Zmiana silnika lub dekodera zatrzymuje odtwarzanie – następny kanał startuje z nowymi ustawieniami.")

        header("Wydajność")
        toggle("Tryb dla słabszych urządzeń", { Prefs.lowPerf }) { Prefs.lowPerf = it; Playback.stop() }
        note("Tryb dla słabszych urządzeń: ograniczona rozdzielczość, mniejszy bufor w pamięci, krótszy program TV, spokojniejsze pobieranie EPG podczas oglądania oraz czyszczenie dekodera przy każdej zmianie kanału (pomaga, gdy obraz zacina się po ▲▼, np. na projektorach). Domyślnie włączany na urządzeniach z małą ilością pamięci. Poniższe wartości można zmienić.")
        if (Prefs.lowPerf) {
            subheader("Parametry trybu dla słabszych urządzeń")
            fun num(title: String, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit, restart: Boolean = true) =
                choice(title, values.map(label), { values.indexOf(get()).let { if (it < 0) values.indexOfFirst { v -> v >= get() }.coerceAtLeast(0) else it } }, look = false) {
                    set(values[it]); if (restart) Playback.stop()
                }
            num("Maksymalna rozdzielczość", listOf(480, 576, 720, 1080, 1440, 2160, 0), { if (it == 0) "bez limitu" else "${it}p" }, { Prefs.lowMaxHeight }, { Prefs.lowMaxHeight = it })
            num("Bufor – minimum", listOf(2, 5, 8, 10, 15, 20, 30, 45, 60), { "$it s" }, { Prefs.lowBufMinS }, { Prefs.lowBufMinS = it; if (Prefs.lowBufMaxS < it) Prefs.lowBufMaxS = it })
            num("Bufor – maksimum", listOf(5, 10, 15, 20, 30, 45, 60, 90, 120), { "$it s" }, { Prefs.lowBufMaxS }, { Prefs.lowBufMaxS = it; if (Prefs.lowBufMinS > it) Prefs.lowBufMinS = it })
            num("Pamięć bufora", listOf(8, 16, 24, 32, 48, 64, 96, 128), { "$it MB" }, { Prefs.lowBufMb }, { Prefs.lowBufMb = it })
            num("Program TV naprzód", (1..7).toList(), { if (it == 1) "1 dzień" else "$it dni" }, { Prefs.lowEpgAhead }, { Prefs.lowEpgAhead = it }, restart = false)
            num("Długość opisu programu", listOf(100, 200, 300, 500, 1000, 2000), { "$it znaków" }, { Prefs.lowDescLen }, { Prefs.lowDescLen = it }, restart = false)
            num("Pobieranie EPG podczas oglądania", listOf(200, 400, 600, 1000, 1500, 2500, 5000), { "$it kB/s" }, { Prefs.lowEpgKbps }, { Prefs.lowEpgKbps = it }, restart = false)
            note("Program TV i opisy: zmiana działa po następnym pobraniu EPG.")
            row("↺ Przywróć domyślne parametry") { Prefs.resetLowPerfParams(); Playback.stop(); build(keepFocus = true) }
        }

        // (7.20) rzędy „Top 10” platform w Stremio: odświeżanie domyślnie co 4 dni (96 h), zmiana ręczna i pobranie od razu
        header("Stremio – Top 10")
        val T = pl.iptv.tv.data.StremioTop10
        val hours = T.HOUR_CHOICES
        fun hLabel(h: Int) = if (h % 24 == 0) (h / 24).let { d -> if (d == 1) "co 1 dzień" else "co $d dni" } + " ($h h)" else "co $h h"
        choice("Odświeżanie Top 10", hours.map { hLabel(it) }, { hours.indexOf(Prefs.stremioTop10Hours).let { if (it < 0) hours.indexOf(T.DEFAULT_HOURS) else it } }, look = false,
            descriptions = hours.map { if (it == T.DEFAULT_HOURS) "Zalecane – rankingi platform zmieniają się wolno" else "" }) { Prefs.stremioTop10Hours = hours[it] }
        row("Odśwież Top 10 teraz") {
            if (!pl.iptv.tv.data.StremioTmdb.available) { toast("Top 10 wymaga klucza TMDB (Stremio → ★ Klucz TMDB)"); return@row }
            T.init(this); toast("Pobieranie Top 10…")
            lifecycleScope.launch {
                val changed = runCatching { T.refreshStale(T.rows(null), force = true) }.getOrDefault(emptyList())
                toast("Top 10 odświeżone (${changed.size} z ${T.rows(null).size} rzędów zmienionych)")
            }
        }
        // (7.22) wybór platform (to samo, co w oknie „Klucz TMDB” w Stremio)
        if (pl.iptv.tv.data.StremioTmdb.available) row(TmdbKeyUi.PLATFORMS_TITLE, "${T.enabledPlatforms.size} z ${T.PLATFORMS.size}") {
            TmdbKeyUi.showPlatforms(this) { build(keepFocus = true) }
        }
        note("Do 10 platform (Netflix, Max, Disney+, Prime Video, SkyShowtime, Apple TV+, CANAL+, Player, Polsat Box Go, TVP VOD) – osobno filmy i seriale (ranking popularności w Polsce z TMDB). Rzędy wczytują się od razu z pamięci urządzenia, a sieć tylko w tle, gdy minie wybrany czas.")
    }

    // ================= System =================
    private fun buildSystem() {
        header("Pomoc i konfiguracja")
        row("❓ Instrukcja obsługi (pilot i dotyk)") { Tutorial.show(this) }
        row("🛠 Ustawienia urządzenia (systemu)") { SystemTools.openSystemSettings(this) }
        row("🧭 Kreator pierwszej konfiguracji") { startActivity(Intent(this, SetupActivity::class.java)) }

        header("Uruchamianie i działanie w tle")
        choice("Po włączeniu aplikacji", StartActions.LABELS, { Prefs.startAction }, look = false) { Prefs.startAction = it }
        if (Prefs.startAction == StartActions.CHANNEL) {
            val cur = Repository.byKey(Prefs.startChannel)
            row("Kanał startowy", cur?.let { "${it.number}. ${it.name}" } ?: "nie wybrano") {
                val chs = Repository.channels
                if (chs.isEmpty()) { toast("Najpierw wczytaj listę kanałów"); return@row }
                Dialogs.choice(this, "Kanał startowy", chs.map { "${it.number}. ${it.name}" },
                    chs.indexOfFirst { it.key == Prefs.startChannel }) { Prefs.startChannel = chs[it].key; build(keepFocus = true) }
            }
        }
        toggle("Uruchamiaj aplikację po włączeniu urządzenia", { Prefs.bootStart }) { on ->
            Prefs.bootStart = on
            if (on) ensureOverlayPermission()
        }
        if (Prefs.bootStart && !canStartFromBackground())
            note("⚠ Brak uprawnienia „Wyświetlanie nad innymi aplikacjami” – Android 10+ może zablokować start po włączeniu. " +
                "Kliknij przełącznik ponownie lub wykonaj: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")

        val batteryOff = SystemTools.isBatteryOptimizationOff(this)
        row("Oszczędzanie energii dla aplikacji", if (batteryOff) "✔ Wyłączone" else "⚠ Włączone – kliknij, aby wyłączyć") {
            if (batteryOff) toast("Oszczędzanie energii jest już wyłączone") else SystemTools.requestBatteryOptimizationOff(this)
        }
        val launcherOn = SystemTools.isLauncherEnabled(this)
        val isHome = SystemTools.isDefaultHome(this)
        row("Tryb launchera (zastępuje ekran główny TV)", when {
            !launcherOn -> "✖ Wyłączony"
            isHome -> "✔ Włączony – domyślny ekran główny"
            else -> "⚠ Włączony – nie jest domyślny"
        }) { launcherMenu(launcherOn, isHome) }
        note("Jako ekran główny aplikacja otwiera się przy każdym włączeniu telewizora, a HOME od razu pokazuje pulpit: " +
            "X25 przy motywie X25, w pozostałych motywach ekran główny w stylu PS5 (telewizja, STube, Stremio, aplikacje z urządzenia). " +
            "Wszystkie aplikacje i ustawienia systemu – w bibliotece aplikacji (kafelek „Aplikacje”, MENU → Wszystkie aplikacje).")
        if (launcherOn) row("▦ Aplikacje systemu") { SystemTools.showApps(this) }

        header("Uprawnienia")
        row("🔐 Uprawnienia aplikacji", PermissionWizard.summary(this)) {
            if (PermissionWizard.missing(this).isEmpty()) toast("Wszystkie uprawnienia są nadane")
            else wizard = PermissionWizard(this) { wizard = null; build(keepFocus = true) }.also { it.start() }
        }
        PermissionWizard.missing(this).forEach { note("✖ ${it.title}") }

        if (!pl.iptv.tv.BuildConfig.SECURE) buildAdmin()      // wersja zabezpieczona: bez zdalnego zarządzania (tylko aktywacja)
        buildPrivacy()
        buildPip()

        header("Kopia zapasowa")
        row("💾 Eksportuj ustawienia do pliku…") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "IptvTv-kopia.json")
            try { startActivityForResult(i, REQ_BACKUP_EXPORT) } catch (_: ActivityNotFoundException) { exportToDownloads() }
        }
        row("📂 Przywróć ustawienia z pliku…") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try { startActivityForResult(i, REQ_BACKUP_IMPORT) } catch (_: ActivityNotFoundException) { toast("Brak menedżera plików") }
        }
        note("Kopia zawiera listy, wygląd, edycje kanałów, ulubione, przypomnienia i harmonogram nagrań (bez PIN-u).")

        buildUpdate()
    }

    private fun launcherMenu(on: Boolean, isHome: Boolean) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!on) items += "✔ Włącz tryb launchera" to {
            SystemTools.setLauncherEnabled(this, true)
            SystemTools.chooseDefaultHome(this)
            build(keepFocus = true)
        }
        if (on && !isHome) items += "🏠 Ustaw jako domyślny ekran główny" to { SystemTools.chooseDefaultHome(this) }
        if (on) items += "✖ Wyłącz tryb launchera" to {
            Dialogs.confirm(this, "Wyłączyć tryb launchera?",
                "Jeśli systemowy launcher został wyłączony przez adb, najpierw go włącz (adb shell pm enable …), " +
                    "inaczej telewizor nie będzie miał ekranu głównego.") {
                SystemTools.setLauncherEnabled(this, false); build(keepFocus = true)
            }
        }
        items += "ℹ Pomoc: telewizor nie pozwala zmienić launchera" to { SystemTools.showAdbHelp(this) }
        Dialogs.menu(this, "Tryb launchera", items)
    }

    private fun canStartFromBackground() =
        Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(this)

    /** Android 10+ pozwala uruchomić ekran po starcie systemu tylko z tym uprawnieniem. */
    private fun ensureOverlayPermission() {
        if (canStartFromBackground()) return
        val i = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (_: ActivityNotFoundException) {
            Dialogs.confirm(this, "Wymagane uprawnienie",
                "To urządzenie nie ma ekranu ustawień „Wyświetlanie nad innymi aplikacjami”. " +
                    "Bez niego start po włączeniu może nie działać na Androidzie 10+.\n\n" +
                    "Można je nadać z komputera:\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n\nOtworzyć ustawienia aplikacji?") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            }
        }
    }

    private var wizard: PermissionWizard? = null

    override fun onResume() {
        super.onResume()
        wizard?.onResume()
        if (wizard?.running != true && tab == 2 && ::container.isInitialized) build(keepFocus = true)
    }

    /** Pliki w folderach aplikacji nie wymagają uprawnień. */
    private fun isAppPrivate(path: String): Boolean {
        val p = path.removePrefix("file://")
        return listOfNotNull(filesDir.parentFile?.path, getExternalFilesDir(null)?.parentFile?.path).any { p.startsWith(it) }
    }

    private fun quickItemMenu(id: String, Q: pl.iptv.tv.data.OrderedMenu = pl.iptv.tv.data.QuickMenu) {
        Dialogs.menuLive(this, Q.ITEMS[id] ?: id) { listOf(
            (if (Q.isHidden(id)) "👁 Pokaż w menu" else "🚫 Ukryj w menu") to { Q.toggle(id); build(keepFocus = true) },
            "▲ Przesuń w górę  (pozycja ${Q.order().indexOf(id) + 1})" to { Q.move(id, -1); build(keepFocus = true) },
            "▼ Przesuń w dół" to { Q.move(id, 1); build(keepFocus = true) },
            "⤒ Na początek" to { Q.move(id, -100); build(keepFocus = true) },
            "⤓ Na koniec" to { Q.move(id, 100); build(keepFocus = true) },
        ) + (if (Q === pl.iptv.tv.data.MiniMenu && id.startsWith(pl.iptv.tv.data.MiniMenu.APP))
            listOf("✕ Usuń skrót" to { pl.iptv.tv.data.MiniMenu.removeApp(id); build(keepFocus = true) }) else emptyList()) }
    }

    // ================= Pogoda =================
    private fun buildWeather() {
        val W = pl.iptv.tv.data.Weather
        header("Pogoda w menu głównym")
        toggle("Pokazuj pogodę obok zegara", { Prefs.weatherEnabled }) { Prefs.weatherEnabled = it }
        row("Miejscowość", Prefs.weatherCity.ifBlank { "nie ustawiono" }) {
            Dialogs.input(this, "Miejscowość", Prefs.weatherCity.substringBefore(','), "np. Warszawa") { name ->
                if (name.isBlank()) return@input
                toast("Wyszukiwanie…")
                lifecycleScope.launch {
                    runCatching { val full = W.setCity(name); Prefs.weatherEnabled = true; full + "  " + W.refresh(force = true) }
                        .onSuccess { toast(it) }.onFailure { toast("Błąd: ${it.message}") }
                    build(keepFocus = true)
                }
            }
        }
        if (Prefs.weatherLat.isNotBlank()) row("Teraz", Prefs.weatherText.ifBlank { "—" }) {
            lifecycleScope.launch { runCatching { W.refresh(force = true) }.onSuccess { toast(it) }.onFailure { toast("Błąd: ${it.message}") }; build(keepFocus = true) }
        }
        note("Dane: Open-Meteo (bez konta). Odświeżanie co 30 minut.")
    }

    // ================= Konto YouTube =================
    private fun buildYouTube() {
        val T = pl.iptv.tv.data.YouTubeTokens
        val A = pl.iptv.tv.data.YouTubeAuth
        val R = pl.iptv.tv.data.YouTubeRepository
        header("Konto / Logowanie YouTube")
        if (T.loggedIn) {
            row("Konto", "✔ " + T.accountName.ifBlank { "zalogowano" }, key = "yt|account") { toast("Zalogowano" + T.accountName.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()) }
            row("Wyloguj", key = "yt|logout") { YouTubeAccountUi.logout(this) { build(key = "yt|login") } }
        } else {
            row("Zaloguj (kod na telefonie lub komputerze)", if (A.configured) "✖ Niezalogowano" else "⚠ Brak klienta OAuth", key = "yt|login") {
                YouTubeAccountUi.login(this) { ok -> if (ok) build(key = "yt|account") }
            }
        }
        note("Logowanie bez klawiatury: na ekranie pojawi się kod do wpisania na google.com/device. " +
            "Aplikacja ma dostęp tylko do odczytu (polubione, subskrypcje).")
        if (!T.persistent) note("⚠ Brak bezpiecznego magazynu kluczy – logowanie obowiązuje do zamknięcia aplikacji.")

        header("Treści YouTube")
        choice("Źródło filmów", pl.iptv.tv.data.YtSource.entries.map { it.label }, { R.source.ordinal }, look = false) {
            R.source = pl.iptv.tv.data.YtSource.of(it)
        }
        if (R.source == pl.iptv.tv.data.YtSource.PERSONAL && !T.loggedIn) note("Bez logowania wyświetlane są trendy publiczne.")
        choice("Region trendów", YT_REGIONS, { YT_REGIONS.indexOf(Prefs.ytRegion).coerceAtLeast(0) }, look = false) {
            Prefs.ytRegion = YT_REGIONS[it]; R.clearCache()
        }
        row("Sprawdź połączenie", key = "yt|test") {
            toast("Pobieranie…")
            lifecycleScope.launch {
                runCatching { R.feed(force = true) }
                    .onSuccess { f -> toast("✔ ${f.source.label}: ${f.videos.size} filmów" + if (f.fellBack) " (bez konta – trendy)" else "") }
                    .onFailure { toast("Błąd: ${it.message}") }
            }
        }

        header("Odtwarzacz STube i SponsorBlock")
        toggle("Wbudowany odtwarzacz (bez reklam)", { Prefs.stubeInternal }) { Prefs.stubeInternal = it }
        note("Wyłączony – filmy od razu w SmartTube. Gdy film nie da się odtworzyć w aplikacji, pojawi się wybór: SmartTube, YouTube lub zamknięcie.")
        toggle("Widok muzyczny (okładka, kolejka, tekst)", { Prefs.ytMusicView }) { Prefs.ytMusicView = it }
        toggle("Teksty piosenek (z internetu)", { Prefs.ytLyrics }) { Prefs.ytLyrics = it }
        note("Teksty pobierane są z otwartej bazy LRCLIB – wysyłany jest tylko tytuł, wykonawca i czas utworu. Wyłączone – zakładka „Tekst” pokazuje brak tekstu.")
        val qualities = listOf(2160, 1440, 1080, 720, 480, 360)
        choice("Maksymalna jakość", qualities.map { "${it}p" }, { qualities.indexOf(Prefs.ytMaxHeight).let { i -> if (i < 0) 2 else i } }, look = false) {
            Prefs.ytMaxHeight = qualities[it]
        }
        if (Prefs.lowPerf && Prefs.lowMaxHeight > 0) note("Tryb słabszych urządzeń: najwyżej ${Prefs.lowMaxHeight}p.")
        val speeds = listOf(50, 100, 125, 150, 200)
        choice("Prędkość odtwarzania", speeds.map { "%s×".format((it / 100f).toString()) }, { speeds.indexOf(Prefs.ytSpeedPct).let { i -> if (i < 0) 1 else i } }, look = false) {
            Prefs.ytSpeedPct = speeds[it]
        }
        choice("Korektor dźwięku", YtEq.PRESETS, { Prefs.ytEqPreset.coerceIn(YtEq.PRESETS.indices) }, look = false) { Prefs.ytEqPreset = it }
        note("W odtwarzaczu: przyciski u góry (▲ z paska odtwarzania) lub MENU na pilocie – prędkość, korektor z regulacją pasm, rozdziały. " +
            "▲▼ przy ukrytym pasku – poprzedni / następny rozdział.")
        toggle("SponsorBlock – pomijaj segmenty", { Prefs.sbEnabled }) { Prefs.sbEnabled = it }
        if (Prefs.sbEnabled) {
            val ids = pl.iptv.tv.data.SponsorBlock.CATEGORIES.keys.toList()
            row("Pomijane kategorie", "${Prefs.sbCategories.size} z ${ids.size}", key = "yt|sbcats") {
                val checked = BooleanArray(ids.size) { ids[it] in Prefs.sbCategories }
                Dialogs.multi(this, "Pomijane kategorie", ids.map { pl.iptv.tv.data.SponsorBlock.label(it) }, checked) { res ->
                    Prefs.sbCategories = ids.filterIndexed { i, _ -> res[i] }; build(keepFocus = true)
                }
            }
            note("Segmenty zgłaszane przez społeczność SponsorBlock (sponsor.ajay.app). Zapytanie bez ID filmu – tylko fragment skrótu.")
        }

        header("Odtwarzanie w tle, wygaszacz, kolejka")
        note("Film STube gra dalej po wyjściu z odtwarzacza (WSTECZ, HOME) – pasek „Teraz odtwarzane” w STube i menu, powiadomienie z przyciskami. Zatrzymanie: ✖ na pasku lub ⏹ w opcjach odtwarzacza.")
        val ambient = listOf(0, 2, 5, 10, 15, 30)
        choice("Wygaszacz w pauzie", ambient.map { if (it == 0) "Wyłączony" else "po $it min" },
            { ambient.indexOf(Prefs.ytAmbientMin).let { i -> if (i < 0) 2 else i } }, look = false) { Prefs.ytAmbientMin = ambient[it] }
        toggle("Po końcu filmu – następny z kolejki", { Prefs.queueAutoplay }) { Prefs.queueAutoplay = it }
        note("Kolejka „Następne” jest wspólna dla filmów STube i kanałów TV (panel w odtwarzaczu: „📋 Następne”, w TV – menu ►).")

        header("Połącz z telefonem")
        row("📱 Sterowanie z telefonu", StubeRemoteUi.state(), key = "yt|phone") { StubeRemoteUi.show(this) { build(keepFocus = true) } }
        note("Kod parowania i adres strony pilota (ta sama sieć Wi-Fi). Z telefonu: odtwarzanie, pauza, przewijanie, kolejka, wyszukiwanie, linki YouTube.")

        header("Wygląd STube")
        choice("Motyw STube", StubeTheme.THEME_NAMES, { Prefs.stubeTheme.coerceIn(StubeTheme.THEMES.indices) }, look = false) { Prefs.stubeTheme = it }
        choice("Układ kafelków", StubeTheme.LAYOUTS.map { it.name }, { Prefs.stubeLayout.coerceIn(StubeTheme.LAYOUTS.indices) }, look = false) { Prefs.stubeLayout = it }
        note("Kolory ekranu STube i odtwarzacza filmów – niezależne od motywu telewizji („Jak aplikacja” – bez zmian).")

        header("Historia STube (na tym urządzeniu)")
        val H = pl.iptv.tv.data.StubeHistory
        toggle("Zapisuj historię wyszukiwania i oglądania", { Prefs.stubeHistory }) { Prefs.stubeHistory = it }
        toggle("Wznawiaj filmy od miejsca przerwania", { Prefs.stubeResume }) { Prefs.stubeResume = it }
        row("Wyczyść historię wyszukiwania", key = "yt|clrsearch") {
            Dialogs.confirm(this, "Wyczyścić?", "Usunąć zapisane frazy wyszukiwania (podpowiedzi w STube)?") {
                lifecycleScope.launch { H.clearSearches(); toast("Wyczyszczono historię wyszukiwania") }
            }
        }
        row("Wyczyść historię oglądania", key = "yt|clrwatch") {
            Dialogs.confirm(this, "Wyczyścić?", "Usunąć postęp oglądania filmów (paski na kafelkach, „Kontynuuj oglądanie”)?") {
                lifecycleScope.launch { H.clearProgress(); toast("Wyczyszczono historię oglądania") }
            }
        }
        note("Dane tylko lokalnie (baza na urządzeniu) – nie są wysyłane do Google ani do kopii zapasowej.")

        header("SmartTube")
        val st = SmartTubeIntegrationHelper.installedVersion(this)
        row("Aplikacja SmartTube", if (SmartTubeIntegrationHelper.isInstalled(this)) "✔ " + (st ?: "zainstalowana") else "✖ Brak", key = "yt|smarttube") {
            SmartTubeIntegrationHelper.launch(this)
        }
        note("SmartTube – zapasowy odtwarzacz (okno awaryjne, długie OK na kafelku w STube).")

        header("Zaawansowane (własny klient Google)")
        fun mask(s: String) = if (s.isBlank()) "wbudowany / brak" else s.take(6) + "…" + s.takeLast(4)
        row("Identyfikator klienta OAuth", mask(Prefs.ytClientId), key = "yt|cid") {
            Dialogs.input(this, "Identyfikator klienta OAuth", Prefs.ytClientId, "….apps.googleusercontent.com") { Prefs.ytClientId = it; build(keepFocus = true) }
        }
        row("Klucz klienta (client secret)", mask(Prefs.ytClientSecret), key = "yt|secret") {
            Dialogs.input(this, "Klucz klienta (client secret)", Prefs.ytClientSecret, "GOCSPX-…") { Prefs.ytClientSecret = it; build(keepFocus = true) }
        }
        row("Klucz API (trendy bez logowania)", mask(Prefs.ytApiKey), key = "yt|key") {
            Dialogs.input(this, "Klucz API YouTube Data v3", Prefs.ytApiKey, "AIza…") { Prefs.ytApiKey = it; R.clearCache(); build(keepFocus = true) }
        }
        note("Google Cloud Console → włącz YouTube Data API v3 → Dane logowania → Identyfikator klienta OAuth typu " +
            "„Telewizory i urządzenia z ograniczonym wejściem”. Puste pola = wartości wbudowane w aplikację.")
    }

    // ================= Wyniki =================
    private fun buildScores() {
        buildWeather()
        val S = pl.iptv.tv.data.Scores
        header("Wyniki meczów (► ► podczas oglądania)")
        toggle("Wyniki meczów (2× ►)", { Prefs.scoresEnabled }) { Prefs.scoresEnabled = it }
        toggle("Powiadomienie o golu nad obrazem", { Prefs.scoreGoalAlerts }) { Prefs.scoreGoalAlerts = it }
        toggle("Ukryj wyniki (bez spoilerów)", { Prefs.scoreHideResults }) { Prefs.scoreHideResults = it }
        note("Bez spoilerów: w zakładce wyników, przy kanałach i w powiadomieniach widać drużyny i minutę, ale nie wynik. Wynik meczu odsłonisz w jego szczegółach („👁 Pokaż wynik”).")
        val rates = listOf(30, 60, 120, 300)
        choice("Odświeżanie podczas meczu", rates.map { "co $it s" }, { rates.indexOf(Prefs.scoreRefresh).coerceAtLeast(1) }, look = false) { Prefs.scoreRefresh = rates[it] }

        header("Obserwowane")
        row("➕ Obserwuj ligę…") {
            val ls = S.available()
            Dialogs.menu(this, "Liga", ls.map { l -> (l.name + if (S.isFollowed(l.id)) "  ✔" else "") to {
                if (!S.isFollowed(l.id)) S.follow(pl.iptv.tv.data.Scores.Follow(l.id, null, l.name)); build(keepFocus = true)
            } })
        }
        row("➕ Obserwuj drużynę…") {
            val ls = S.available()
            Dialogs.menu(this, "Liga drużyny", ls.map { l -> l.name to {
                toast("Pobieranie drużyn…")
                lifecycleScope.launch {
                    runCatching { S.teams(l) }.onSuccess { teams ->
                        if (teams.isEmpty()) toast("Brak drużyn")
                        else Dialogs.menu(this@SettingsActivity, l.name, teams.map { (id, name) -> (name + if (S.isFollowed(l.id, id)) "  ✔" else "") to {
                            S.follow(pl.iptv.tv.data.Scores.Follow(l.id, id, name)); toast("Obserwujesz: $name"); build(keepFocus = true)
                        } })
                    }.onFailure { toast("Błąd: ${it.message}") }
                }
            } })
        }
        val f = S.follows
        if (f.isEmpty()) note("Nic jeszcze nie obserwujesz.")
        f.forEachIndexed { i, x ->
            val label = if (x.team == null) "🏆 ${x.name}" else "👕 ${x.name}  (${S.league(x.league)?.name ?: x.league})"
            row("${i + 1}. $label", key = "sf|${x.key}") {
                Dialogs.menu(this, x.name, listOf(
                    "✕ Przestań obserwować" to { S.unfollow(x); build(keepFocus = true) },
                    "▲ Wyżej" to { S.move(x, -1); build(keepFocus = true) },
                    "▼ Niżej" to { S.move(x, 1); build(keepFocus = true) },
                ))
            }
        }

        header("Źródło danych")
        row("Klucz API-Football (opcjonalny)", if (Prefs.apiFootballKey.isBlank()) "brak – ESPN" else "ustawiony") {
            Dialogs.input(this, "Klucz API-Football", Prefs.apiFootballKey, "z dashboard.api-football.com") { Prefs.apiFootballKey = it; build(keepFocus = true) }
        }
        note("Bez klucza: ESPN (darmowe, na żywo, bez polskich lig). Z kluczem API-Football: wszystkie ligi, w tym Ekstraklasa, 1 Liga i Puchar Polski. " +
            "Darmowy plan API-Football nie obejmuje bieżącego sezonu – potrzebny płatny plan. Po zmianie źródła dodaj obserwowane drużyny ponownie.")
    }

    // ================= Pilot =================
    private fun buildRemoteKeys() {
        val K = pl.iptv.tv.data.KeyMap
        header("Układ przycisków pilota (podczas oglądania)")
        val pi = K.presetIndex()
        choice("Gotowy układ", K.PRESETS.map { it.first } + "Własny", { if (pi < 0) K.PRESETS.size else pi }, look = false) {
            if (it < K.PRESETS.size) K.applyPreset(it)
        }
        note("Wybierz akcję, aby przypisać do niej dowolny klawisz (naciśnij go na pilocie). Klawisz przypisany do innej akcji zostanie przeniesiony. " +
            "W panelach ◄ ► i OK działają zawsze jak nawigacja; cyfry wybierają numer kanału.")
        val m = K.map()
        pl.iptv.tv.data.KeyMap.Action.values().forEach { a ->
            row(a.label, m[a].orEmpty().joinToString(", ") { K.keyName(it) }.ifEmpty { "—" }) {
                Dialogs.menu(this, a.label, listOf(
                    "➕ Przypisz klawisz (naciśnij)…" to { learnKey(a) },
                    "✕ Usuń przypisania" to { K.clear(a); build(keepFocus = true) },
                ))
            }
        }
        row("↺ Przywróć układ standardowy") { K.applyPreset(0); build(keepFocus = true) }
    }

    /** Okno czekające na naciśnięcie klawisza (WSTECZ anuluje). */
    private fun learnKey(a: pl.iptv.tv.data.KeyMap.Action) {
        val d = Ui.dialog(this).setTitle(a.label).setMessage("Naciśnij klawisz na pilocie…\n(WSTECZ – anuluj)")
            .setNegativeButton("Anuluj", null).create()
        d.setOnKeyListener { dlg, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (ev.action == android.view.KeyEvent.ACTION_DOWN) {
                pl.iptv.tv.data.KeyMap.assign(a, code)
                toast("${a.label}: ${pl.iptv.tv.data.KeyMap.keyName(code)}")
                dlg.dismiss(); build(keepFocus = true)
            }
            true
        }
        d.show()
    }

    // ================= Aktualizacja =================
    private var pendingApk: ((ByteArray?) -> Unit)? = null

    private fun buildUpdate() {
        val U = pl.iptv.tv.update.Updater
        header("Aktualizacja")
        row("Zainstalowana wersja", U.currentName(this)) {}
        row("Sprawdź i zainstaluj aktualizację") {
            toast("Sprawdzanie…")
            lifecycleScope.launch { toast(runCatching { U.updateFromUrl(this@SettingsActivity) }.getOrElse { "Błąd: ${it.message}" }) }
        }
        toggle("Automatycznie sprawdzaj i instaluj (co 6 godz.)", { Prefs.updateAuto }) { Prefs.updateAuto = it }
        row("Zgoda na instalowanie aktualizacji", if (U.canInstall(this)) "✔ nadana" else "✖ brak – kliknij") {
            if (!U.canInstall(this)) wizard = PermissionWizard(this) { wizard = null; build(keepFocus = true) }.also { it.start() }
        }
        row("Zainstaluj z pliku .apk…") {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_APK_INSTALL) }
            catch (_: ActivityNotFoundException) { toast("Brak menedżera plików") }
        }
        note("Nowe wersje pobierane są z oficjalnych wydań aplikacji (GitHub). Instalowane są tylko nowsze wersje z tym samym podpisem. " +
            "Pierwsza aktualizacja wymaga potwierdzenia; na Androidzie 12+ kolejne mogą instalować się bez pytania.")

        if (pl.iptv.tv.BuildConfig.SECURE) return
        header("Aktualizuj inne urządzenie")
        note("Na urządzeniu docelowym włącz odbiór: Ustawienia → Listy → Dodaj listę zdalnie (w sieci Wi-Fi lub przez Internet).")
        row("Wyślij aktualizację w tej samej sieci Wi-Fi…") { pickApk { apk -> chooseDevice { host, port, name ->
            Dialogs.input(this, "PIN urządzenia $name", "", password = true) { pin -> sendApk { pl.iptv.tv.remote.RemoteClient.sendApk(host, port, pin, apk) } }
        } } }
        row("Wyślij aktualizację przez Internet (kod urządzenia)…") { pickApk { apk ->
            Dialogs.input(this, "Kod urządzenia docelowego (XXXX-XXXX-XXXX)", "", "ABCD-EFGH-JKMN", code = true) { code ->
                Dialogs.input(this, "PIN urządzenia docelowego", "", password = true) { pin ->
                    sendApk { pl.iptv.tv.remote.RemoteRelay.sendApk(code, pin, apk); "Wysłano – urządzenie zainstaluje aktualizację w ciągu ok. 30 s" }
                }
            }
        } }
    }

    private fun editUpdateUrl() = Dialogs.input(this, "Adres aktualizacji (JSON lub .apk)", Prefs.updateUrl, "https://…/iptvtv.json") {
        Prefs.updateUrl = it; build(keepFocus = true)
    }

    /** Wybór pliku do wysłania: wersja zainstalowana tutaj lub plik .apk. */
    private fun pickApk(done: (ByteArray) -> Unit) {
        Dialogs.menu(this, "Którą wersję wysłać?", listOf(
            "Wersję z tego urządzenia (${pl.iptv.tv.update.Updater.currentName(this)})" to {
                runCatching { java.io.File(applicationInfo.sourceDir).readBytes() }.onSuccess(done).onFailure { toast("Błąd: ${it.message}") }
            },
            "Plik .apk…" to {
                pendingApk = { b -> if (b == null) toast("Nie można odczytać pliku") else done(b) }
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_APK_SEND) }
                catch (_: ActivityNotFoundException) { toast("Brak menedżera plików") }
            },
        ))
    }

    private fun sendApk(op: () -> String) {
        toast("Wysyłanie…")
        lifecycleScope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { op() } }
                .onSuccess { toast("✔ $it") }.onFailure { toast("✖ ${it.message}") }
        }
    }

    /** Wyszukanie urządzenia w sieci (lub adres ręcznie). */
    private fun chooseDevice(done: (String, Int, String) -> Unit) {
        toast("Szukanie urządzeń w sieci…")
        val port0 = pl.iptv.tv.remote.RemoteServer.PORT
        pl.iptv.tv.remote.RemoteClient.discover(this, 3500) { devs ->
            val items = devs.map { d -> "${d.name}  (${d.host})" to { done(d.host, d.port, d.name) } }.toMutableList()
            items += "Wpisz adres IP ręcznie…" to {
                Dialogs.input(this, "Adres IP urządzenia", "", "192.168.1.20") { ip ->
                    if (ip.isNotBlank()) done(ip.substringBefore(':'), ip.substringAfter(':', "$port0").toIntOrNull() ?: port0, ip)
                }
            }
            Dialogs.menu(this, if (devs.isEmpty()) "Nie znaleziono urządzeń" else "Wybierz urządzenie", items)
        }
    }

    private fun sendReport() = lifecycleScope.launch {
        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pl.iptv.tv.remote.RemoteRelay.report("Raport ręczny") } }
        toast(if (r.isSuccess) "Wysłano raport do administratora" else "Błąd raportu: ${r.exceptionOrNull()?.message}")
    }

    private fun exportToDownloads() {
        runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, "IptvTv-kopia.json").writeText(pl.iptv.tv.data.Backup.export(this))
        }.onSuccess { toast("Zapisano w Pobrane/IptvTv-kopia.json") }.onFailure { toast("Błąd: ${it.message}") }
    }

    private fun restartApp() {
        startActivity(packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAffinity(); Runtime.getRuntime().exit(0)
    }

    // ================= Listy =================
    private var pendingListSource: ((String) -> Unit)? = null

    private fun buildLists() {
        buildActiveList()
        val active = Prefs.activePlaylist
        header("Listy kanałów")
        val lists = Playlists.all()
        if (lists.isEmpty()) note("Brak list. Dodaj pierwszą poniżej.")
        lists.forEach { p ->
            val days = if (p.expiry <= 0) "bez daty" else if (p.expiry < System.currentTimeMillis()) "wygasła"
                else "do ${Playlists.formatDate(p.expiry)}"
            row(p.name + if (p.id == active) "  (aktywna)" else "", days, key = "list|${p.id}", raw = true) { listMenu(p) }
        }
        row("Dodaj listę (kreator)…") { startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ADD_LIST, true)) }
        note("Program TV, logo kanałów, token i archiwum są zapisywane osobno dla każdej listy.")
        buildReceive()
        buildSend()
    }

    /** Włącza odbiór i pokazuje dane do wysłania listy z telefonu. */
    private fun addRemoteInfo() {
        Dialogs.menu(this, "Dodaj listę zdalnie", listOf(
            "W tej samej sieci Wi-Fi" to {
                Prefs.remoteOn = true; pl.iptv.tv.remote.RemoteServer.start(this)
                val ip = pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull() ?: "brak sieci"
                Dialogs.confirm(this, "Odbiór w sieci Wi-Fi włączony", "Na telefonie otwórz w przeglądarce:\nhttp://$ip:${pl.iptv.tv.remote.RemoteServer.PORT}\n" +
                    "albo w D-iptv Player: Ustawienia → Listy → Wyślij w tej samej sieci Wi-Fi.\n\nPIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}") { build(keepFocus = true) }
            },
            "Przez Internet (z dowolnej sieci)" to {
                Prefs.relayOn = true; pl.iptv.tv.remote.RemoteRelay.start(this)
                Dialogs.confirm(this, "Odbiór przez Internet włączony", "W D-iptv Player na telefonie: Ustawienia → Listy → Wyślij przez Internet.\n\n" +
                    "Kod urządzenia: ${pl.iptv.tv.remote.RemoteRelay.ensureCode()}\nPIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}") { build(keepFocus = true) }
            },
        ))
    }

    private fun listMenu(p: PlaylistInfo) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (p.id != Prefs.activePlaylist) items += "✔ Ustaw jako aktywną" to { activateList(p) }
        items += "✎ Nazwa" to { Dialogs.input(this, "Nazwa listy", p.name) { Playlists.update(p.copy(name = it.ifBlank { p.name })); build(true) } }
        items += "🔗 Adres listy" to { Dialogs.input(this, "Link lub ścieżka", p.source) { v -> Playlists.update(p.copy(source = v)); if (p.id == Prefs.activePlaylist) Prefs.playlistSource = v; build(true) } }
        items += "📺 Adres EPG" to { Dialogs.input(this, "Link do EPG (puste = z listy)", p.epg) { v -> Playlists.update(p.copy(epg = v)); if (p.id == Prefs.activePlaylist) Prefs.epgUrl = v; build(true) } }
        items += "📅 Data ważności" to { editExpiry(p) }
        items += "🗑 Usuń listę" to {
            Dialogs.confirm(this, "Usunąć listę „${p.name}”?", "Edycje kanałów i ulubione pozostaną.") {
                Repository.deletePlaylistFiles(p.id); val wasActive = p.id == Prefs.activePlaylist
                Playlists.remove(p.id)
                if (wasActive) Playlists.all().firstOrNull()?.let { activateList(it) } ?: build(true) else build(true)
            }
        }
        Dialogs.menu(this, p.name, items)
    }

    private fun editExpiry(p: PlaylistInfo) {
        Dialogs.input(this, "Data ważności (DD.MM.RRRR, puste = brak)", Playlists.formatDate(p.expiry), "31.12.2026") { v ->
            val d = Playlists.parseDate(v)
            if (d == null) toast("Błędna data – użyj formatu DD.MM.RRRR") else { Playlists.update(p.copy(expiry = d)); build(true) }
        }
    }

    private fun activateList(p: PlaylistInfo) {
        toast("Wczytywanie listy „${p.name}”…")
        lifecycleScope.launch {
            runCatching { Repository.switchPlaylist(p.id) }
                .onSuccess { toast("Aktywna lista: ${p.name} ($it kanałów)") }
                .onFailure { toast("Błąd: ${it.message}") }
            build(true)
        }
    }

    private fun addList() {
        Dialogs.input(this, "Nazwa nowej listy", "Lista ${Playlists.all().size + 1}") { name ->
            Dialogs.menu(this, "Źródło listy „$name”", listOf(
                "🔗 Link lub ścieżka…" to { Dialogs.input(this, "Link (http) lub ścieżka pliku", "", "http://…/lista.m3u") { src -> if (src.isNotBlank()) askExpiryAndAdd(name, src) } },
                "📂 Plik z urządzenia…" to {
                    pendingListSource = { src -> askExpiryAndAdd(name, src) }
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_LIST_FILE) }
                    catch (_: ActivityNotFoundException) { toast("Brak menedżera plików – użyj ścieżki") }
                },
            ))
        }
    }

    private fun askExpiryAndAdd(name: String, src: String) {
        Dialogs.input(this, "Data ważności (opcjonalnie, DD.MM.RRRR)", "", "31.12.2026") { v ->
            val d = Playlists.parseDate(v) ?: 0L
            val p = Playlists.add(name, src, "", d)
            Dialogs.confirm(this, "Dodano listę „${p.name}”", "Ustawić ją teraz jako aktywną?") { activateList(p) }
            build(true)
        }
    }

    // ================= Kontrola rodzicielska =================
    private fun buildParental() {
        header("Kontrola rodzicielska")
        val hasPin = Prefs.parentalPin.isNotBlank()
        row("Kontrola rodzicielska", if (Prefs.parentalOn && hasPin) "✔ Włączona" else "✖ Wyłączona") {
            if (!Prefs.parentalOn && !hasPin) ParentalUi.setPin(this) { ok -> if (ok) { Prefs.parentalOn = true; Parental.unlocked = true; Repository.reapply() }; build(true) }
            else { Prefs.parentalOn = !Prefs.parentalOn; Repository.reapply(); build(true) }
        }
        row("🔑 " + (if (hasPin) "Zmień PIN" else "Ustaw PIN")) { ParentalUi.setPin(this) { build(true) } }
        val groups = Repository.allChannels.map { it.origGroup }.distinct() + Repository.allChannels.map { it.group }.distinct()
        val g = groups.distinct()
        row("📁 Zablokowane grupy", "${Prefs.lockedGroups.count { it in g }}") {
            Dialogs.multi(this, "Zablokowane grupy", g, BooleanArray(g.size) { g[it] in Prefs.lockedGroups }) { st ->
                Prefs.lockedGroups = g.filterIndexed { i, _ -> st[i] }; Repository.reapply(); build(true)
            }
        }
        val chs = Repository.allChannels
        row("📺 Zablokowane kanały", "${Prefs.lockedChannels.count { k -> chs.any { it.key == k } }}") {
            Dialogs.multi(this, "Zablokowane kanały", chs.map { "${it.number}. ${it.name}" }, BooleanArray(chs.size) { chs[it].key in Prefs.lockedChannels }) { st ->
                Prefs.lockedChannels = chs.filterIndexed { i, _ -> st[i] }.map { it.key }; Repository.reapply(); build(true)
            }
        }
        toggle("Ukryj zablokowane kanały na listach", { Prefs.parentalHide }) { Prefs.parentalHide = it; Repository.reapply() }
        toggle("Chroń PIN-em ustawienia i edytor", { Prefs.parentalSettings }) { Prefs.parentalSettings = it }
        row("🔒 Zablokuj ponownie teraz", if (Parental.unlocked) "odblokowane" else "zablokowane") {
            Parental.unlocked = false; Repository.reapply(); toast("Zablokowano"); tab = 0; build()
        }
        note("Po podaniu PIN-u zablokowane kanały są dostępne do zamknięcia aplikacji.")
    }

    // ================= Nagrywanie =================
    private fun buildRecording() {
        header("Nagrywanie")
        toggle("Nagrywanie (przyciski w menu programu i menu funkcji)", { Prefs.recEnabled }) { Prefs.recEnabled = it }
        val folder = pl.iptv.tv.rec.RecordingService.folder(this)
        row("📁 Folder nagrań", folder.path) {
            val movies = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "IptvTv").path
            Dialogs.menu(this, "Folder nagrań", listOf(
                "Pamięć aplikacji (bez uprawnień, usuwana z aplikacją)" to { Prefs.recFolder = ""; build(true) },
                "Filmy/IptvTv (wymaga dostępu do plików)" to { Prefs.recFolder = movies; build(true) },
                "Własna ścieżka (np. pendrive)…" to { Dialogs.input(this, "Ścieżka folderu", Prefs.recFolder, "/storage/XXXX-XXXX/Nagrania") { Prefs.recFolder = it; build(true) } },
            ))
        }
        val mins = listOf(0, 1, 2, 5, 10, 15)
        choice("Margines przed programem", mins.map { "$it min" }, { mins.indexOf(Prefs.recBefore).coerceAtLeast(0) }, look = false) { Prefs.recBefore = mins[it] }
        choice("Margines po programie", mins.map { "$it min" }, { mins.indexOf(Prefs.recAfter).coerceAtLeast(0) }, look = false) { Prefs.recAfter = mins[it] }
        note("Zapis do .ts: strumienie MPEG-TS i HLS (bez szyfrowania). Nagranie działa w tle, także przy wyłączonym ekranie.")
        header("Nagrania")
        val jobs = pl.iptv.tv.data.Recordings.all()
        if (jobs.isEmpty()) note("Brak nagrań. Nagrywaj z menu programu („Nagraj program”) lub z menu funkcji (◄◄).")
        jobs.forEach { j ->
            val f = java.io.File(j.file)
            val state = when (j.state) {
                pl.iptv.tv.data.RecJob.SCHEDULED -> "⏰ ${Fmt.shortDay(j.start)} ${Fmt.time(j.start)}"
                pl.iptv.tv.data.RecJob.RECORDING -> "⏺ trwa (${f.length() / 1_048_576} MB)"
                pl.iptv.tv.data.RecJob.DONE -> "✔ ${f.length() / 1_048_576} MB"
                else -> "✖ ${j.error.take(30)}"
            }
            row("${j.channelName}: ${j.title}", state, raw = true) { recMenu(j) }
        }
    }

    private fun recMenu(j: pl.iptv.tv.data.RecJob) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val f = java.io.File(j.file)
        if (f.exists() && f.length() > 0) items += "▶ Odtwórz" to { startActivity(Intent(this, RecordingPlayerActivity::class.java).putExtra(RecordingPlayerActivity.EXTRA_FILE, j.file)) }
        when (j.state) {
            pl.iptv.tv.data.RecJob.SCHEDULED -> items += "✕ Anuluj zaplanowane" to { pl.iptv.tv.rec.RecordingService.stop(this, j.id); build(true) }
            pl.iptv.tv.data.RecJob.RECORDING -> items += "⏹ Zatrzymaj" to { pl.iptv.tv.rec.RecordingService.stop(this, j.id); toast("Zatrzymywanie…") }
            else -> items += "🗑 Usuń nagranie" to { Dialogs.confirm(this, "Usunąć nagranie?", j.title) { f.delete(); pl.iptv.tv.data.Recordings.remove(j.id); build(true) } }
        }
        Dialogs.menu(this, "${j.channelName}: ${j.title}", items)
    }

    // ================= Obraz w obrazie =================
    private fun buildPip() {
        header("Obraz w obrazie (PiP)")
        val supported = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        note(if (supported) "Gdy wyjdziesz z odtwarzacza przyciskiem HOME, obraz zmniejszy się do okienka nad innymi aplikacjami."
            else "To urządzenie nie obsługuje trybu obraz w obrazie (zwykle dostępny na telefonach z Androidem 8+).")
        toggle("Automatycznie przy wyjściu z odtwarzacza (HOME)", { Prefs.pipAuto }) { Prefs.pipAuto = it }
    }

    // ================= Odbieranie / wysyłanie list =================
    private var pendingSendFile: ((android.net.Uri) -> Unit)? = null

    private fun sendFlow() {
        toast("Szukanie urządzeń w sieci…")
        pl.iptv.tv.remote.RemoteClient.discover(this, 3500) { devs ->
            val items = devs.map { d -> "📺 ${d.name}  (${d.host})" to { sendTo(d.host, d.port, d.name) } }.toMutableList()
            items += "⌨ Wpisz adres IP ręcznie…" to {
                Dialogs.input(this, "Adres IP urządzenia", "", "192.168.1.20") { ip -> if (ip.isNotBlank()) sendTo(ip.substringBefore(':'), ip.substringAfter(':', "${pl.iptv.tv.remote.RemoteServer.PORT}").toIntOrNull() ?: pl.iptv.tv.remote.RemoteServer.PORT, ip) }
            }
            Dialogs.menu(this, if (devs.isEmpty()) "Nie znaleziono urządzeń" else "Wybierz urządzenie", items)
        }
    }

    /** Wysyłanie przez przekaźnik: kod + PIN urządzenia docelowego. */
    private fun sendRelayFlow() {
        Dialogs.input(this, "Kod urządzenia docelowego (XXXX-XXXX-XXXX)", "", "ABCD-EFGH-JKMN", code = true) { code ->
            if (pl.iptv.tv.remote.RemoteRelay.normalize(code).length != 12) { toast("Kod ma 12 znaków"); return@input }
            pickContent("urządzenie $code") { url, bytes, defName ->
                Dialogs.input(this, "Nazwa listy", defName) { listName ->
                    Dialogs.input(this, "Data ważności (opcjonalnie, DD.MM.RRRR)", "", "31.12.2026") { exp ->
                        Dialogs.input(this, "PIN urządzenia docelowego", "", password = true) { pin ->
                            lifecycleScope.launch {
                                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    pl.iptv.tv.remote.RemoteRelay.send(code, pin, listName, url, bytes, "", exp) } }
                                    .onSuccess { toast("Wysłano – urządzenie odbierze listę w ciągu ok. 30 s (gdy aplikacja jest włączona)") }
                                    .onFailure { toast("Błąd: ${it.message}") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Wybór treści do wysłania: plik / link / aktywna lista. */
    private fun pickContent(target: String, done: (url: String, bytes: ByteArray?, defName: String) -> Unit) {
        Dialogs.menu(this, "Co wysłać na $target?", listOf(
            "Plik .m3u z tego urządzenia…" to {
                pendingSendFile = { uri ->
                    val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
                    if (bytes == null) toast("Nie można odczytać pliku") else done("", bytes, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Lista")
                }
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_SEND_FILE) }
                catch (_: ActivityNotFoundException) { toast("Brak menedżera plików") }
            },
            "Link do listy…" to { Dialogs.input(this, "Link do listy", "", "http://…/lista.m3u") { u -> if (u.isNotBlank()) done(u, null, "Lista") } },
            "Aktywna lista z tego urządzenia" to {
                val a = Playlists.active()
                if (a == null) toast("Brak aktywnej listy")
                else if (a.source.startsWith("http")) done(a.source, null, a.name)
                else done("", runCatching { java.io.File(filesDir, if (a.id == Playlists.MAIN) "playlist.m3u" else "playlist_${a.id}.m3u").readBytes() }.getOrNull(), a.name)
            },
        ))
    }

    private fun sendTo(host: String, port: Int, name: String) {
        fun details(url: String, bytes: ByteArray?, defName: String) {
            Dialogs.input(this, "Nazwa listy", defName) { listName ->
                Dialogs.input(this, "Data ważności (opcjonalnie, DD.MM.RRRR)", "", "31.12.2026") { exp ->
                    Dialogs.input(this, "PIN widoczny na urządzeniu $name", "", password = true) { pin ->
                        lifecycleScope.launch {
                            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                pl.iptv.tv.remote.RemoteClient.send(host, port, pin, listName, url, bytes, "", exp, false) } }
                                .onSuccess { toast("✔ $it") }.onFailure { toast("✖ ${it.message}") }
                        }
                    }
                }
            }
        }
        Dialogs.menu(this, "Co wysłać na $name?", listOf(
            "📂 Plik .m3u z tego urządzenia…" to {
                pendingSendFile = { uri ->
                    val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
                    if (bytes == null) toast("Nie można odczytać pliku") else details("", bytes, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Lista")
                }
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_SEND_FILE) }
                catch (_: ActivityNotFoundException) { toast("Brak menedżera plików") }
            },
            "🔗 Link do listy…" to { Dialogs.input(this, "Link do listy", "", "http://…/lista.m3u") { u -> if (u.isNotBlank()) details(u, null, "Lista") } },
            "📋 Aktywna lista z tego urządzenia" to {
                val a = Playlists.active()
                if (a == null) toast("Brak aktywnej listy")
                else if (a.source.startsWith("http")) details(a.source, null, a.name)
                else details("", runCatching { java.io.File(filesDir, if (a.id == Playlists.MAIN) "playlist.m3u" else "playlist_${a.id}.m3u").readBytes() }.getOrNull(), a.name)
            },
        ))
    }

    private fun toast(s: String) = I18n.makeToast(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- wybór pliku ----------------

    private fun pickFile(req: Int, mime: String) {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime }
        val get = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime }
        try { startActivityForResult(open, req) } catch (_: ActivityNotFoundException) {
            try { startActivityForResult(get, req) } catch (_: ActivityNotFoundException) {
                val msg = if (req == REQ_FILE) "Brak menedżera plików. Wpisz ścieżkę, np. /sdcard/Download/lista.m3u, i wybierz „Wczytaj listę”."
                else "Brak menedżera plików. Użyj opcji „Pobierz z linku”."
                if (req == REQ_FILE) log(msg, true) else I18n.makeToast(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_FILE -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                playlistUrl?.setText(uri.toString())
                saveFields(); loadPlaylist(uri.toString())
            }
            REQ_IMAGE -> setBackground(uri.toString())
            REQ_LIST_FILE -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                pendingListSource?.invoke(uri.toString()); pendingListSource = null
            }
            REQ_BACKUP_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(pl.iptv.tv.data.Backup.export(this).toByteArray()) }
            }.onSuccess { toast("Zapisano kopię zapasową") }.onFailure { toast("Błąd: ${it.message}") }
            REQ_BACKUP_IMPORT -> runCatching {
                pl.iptv.tv.data.Backup.import(this, contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            }.onSuccess { n ->
                Dialogs.confirm(this, "Przywrócono $n ustawień", "Aplikacja uruchomi się ponownie, aby zastosować kopię.") { restartApp() }
            }.onFailure { toast("Błąd: ${it.message}") }
            REQ_SEND_FILE -> pendingSendFile?.invoke(uri)
            REQ_APK_INSTALL -> runCatching {
                val f = java.io.File(cacheDir, "update.apk"); contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; f
            }.onSuccess { f -> toast(pl.iptv.tv.update.Updater.handleIncoming(this, f).second) }.onFailure { toast("Błąd: ${it.message}") }
            REQ_APK_SEND -> pendingApk?.invoke(runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull())
        }
    }

    companion object {
        /** (7.23) Ustawienia otwarte z pulpitu – [fromDashboard]. */
        const val EXTRA_FROM_DASHBOARD = "from_dashboard"; const val EXTRA_ORIGIN = "origin"
        /** Pulpit, z którego otwarto Ustawienia (powrót). */
        @Volatile var origin: java.lang.ref.WeakReference<android.app.Activity>? = null; private set
        /** Ponowne otwarcie pulpitu przy wyjściu (testy). */
        @Volatile var returnedTo: String? = null; internal set

        /** Ekrany pulpitu (wszystkie motywy). Nowy pulpit – dopisać tutaj. */
        fun isDashboard(a: android.app.Activity) = a is ModernHomeActivity || a is Ps5HomeActivity || a is X25DashboardActivity

        /** Ustawienia z ekranu [a]: z pulpitu – tryb „z pulpitu” (Live TV zatrzymane, powrót na ten pulpit), inaczej jak dotąd. */
        fun intentFrom(a: android.app.Activity): Intent {
            val i = Intent(a, SettingsActivity::class.java)
            if (isDashboard(a)) { origin = java.lang.ref.WeakReference(a); returnedTo = null; i.putExtra(EXTRA_FROM_DASHBOARD, true).putExtra(EXTRA_ORIGIN, a.javaClass.name) }
            return i
        }

        /** (7.6) Karta pod kursorem pilota otwiera się po tej chwili (dawniej 250 ms). */
        const val TAB_FOCUS_DELAY_MS = 180L
        /** (7.6) Przejście treści przy zmianie karty. */
        const val TAB_ANIM_MS = 160L
        private const val REQ_FILE = 10; private const val REQ_IMAGE = 11; private const val REQ_LIST_FILE = 12
        private const val REQ_BACKUP_EXPORT = 13; private const val REQ_BACKUP_IMPORT = 14; private const val REQ_SEND_FILE = 15
        private const val REQ_APK_INSTALL = 16; private const val REQ_APK_SEND = 17
        /** (7.22) Przełącznik strażnika klatkowania (Ustawienia i okno silnika w odtwarzaczu). */
        const val AUTO_STUTTER_TITLE = "Automatyczna korekta przy zacinaniu obrazu"
        /** (7.22) Ustawienia → Wygląd. */
        const val UI_RESOLUTION_TITLE = "Rozdzielczość interfejsu"
        const val UI_RESOLUTION_NOTE = "Wielkość menu, okien i kafelków (obraz wideo bez zmian). Na telefonie trzymanym pionowo zawsze obowiązuje gęstość systemu."
        /** (7.22) Ustawienia → Odtwarzanie → Silnik odtwarzania i akceleracja oraz okno silnika w odtwarzaczu. */
        const val MAX_VIDEO_TITLE = "Maksymalna rozdzielczość obrazu"
        const val MAX_VIDEO_NOTE = "Ogranicza jakość wybieraną ze strumieni wielojakościowych (HLS / DASH) – mniej danych i lżejszy dekoder. Kanał nadawany tylko w wyższej rozdzielczości gra dalej. Automatyczna – jak dotąd, bez limitu."
        const val AUTO_STUTTER_NOTE = "Gdy obraz wyraźnie klatkuje przez kilka sekund, odtwarzacz automatycznie dostosowuje tryb wyświetlania (bez przełączania kanałów HD/FHD na słaby dekoder programowy). Wyłącz, aby zachować pełną kontrolę ręczną."
        val TAB_NAMES = listOf("Listy", "Wygląd", "Menu i kanały", "Odtwarzanie", "Program TV", "Archiwum (catch-up)", "Kontrola rodzicielska",
            "Nagrywanie", "Wyniki i pogoda", "Pilot", "System", "Konto YouTube")
        /** Karta „Konto / Logowanie YouTube” (ostatnia – numery wcześniejszych kart bez zmian). */
        val YOUTUBE_TAB = 11
        val YT_REGIONS = listOf("PL", "DE", "GB", "US", "UA", "CZ", "SK", "LT")
        val PARENTAL_TAB = 6
        val MAIN_LAYOUTS = listOf("Klasyczny (kanały | podgląd i program)", "Podgląd na środku (kanały | podgląd | program)", "Duży podgląd", "Szeroka lista kanałów", "Karty poziome (konsola)")
    }
}

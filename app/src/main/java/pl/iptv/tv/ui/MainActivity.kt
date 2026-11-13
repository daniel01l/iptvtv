package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StartActions

class MainActivity : BaseActivity() {
    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
        /** Ustawiane przez launcher przy kolejnych naciśnięciach HOME. */
        const val EXTRA_SKIP_START = "skip_start"
        const val EDIT_GROUPS = "✎ Edytuj"
    }

    private lateinit var groupsRv: RecyclerView
    private lateinit var channelsRv: RecyclerView
    private lateinit var progRv: RecyclerView
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var epgChannel: TextView
    private lateinit var epgDesc: TextView
    private lateinit var empty: View
    private lateinit var listContent: View
    private lateinit var searchRow: View
    private lateinit var searchField: EditText
    private lateinit var btnSearch: View

    // szybkie przechodzenie po kategoriach: zaznaczenie od razu, lista kanałów po zatrzymaniu (250 ms)
    private val groupAdapter = GroupAdapter(onFocus = { g -> groupsRv.post { previewGroup(g) } }, onClick = ::onGroupClick)
    private var pendingGroup: String? = null
    private val applyPendingGroup = Runnable { pendingGroup?.let { selectGroup(it) }; pendingGroup = null }
    private fun previewGroup(g: String) {
        if (g == EDIT_GROUPS) return                          // pozycja „Edytuj” nie zmienia listy kanałów
        if (searching) closeSearch()
        groupAdapter.selected = g
        pendingGroup = g
        handler.removeCallbacks(applyPendingGroup); handler.postDelayed(applyPendingGroup, 250)
    }
    override val videoBackground = false
    private lateinit var preview: androidx.media3.ui.PlayerView
    private val channelAdapter = ChannelAdapter(onFocus = ::scheduleShowEpg, onClick = ::onChannelClick, onLongClick = ::channelMenu)
    private val progAdapter: ProgrammeAdapter = ProgrammeAdapter(onClick = ::onProgrammeClick, onFocus = { epgDesc.text = describe(it) },
        onLongClick = { p -> shownEpgFor?.let { ch -> ProgrammeActions.menu(this, ch, p, ::play) { progAdapter.notifyDataSetChanged() } } })

    private val handler = Handler(Looper.getMainLooper())
    private var focusedChannel: Channel? = null
    private var shownEpgFor: Channel? = null
    private var initialLoadDone = false
    private val searching get() = searchRow.visibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            clock.text = Fmt.time(System.currentTimeMillis())
            findViewById<TextView>(R.id.date).text = dateFmt.format(java.util.Date())
            updateWeather()
            updateScores()
            if (initialLoadDone && Prefs.lowPerf) lifecycleScope.launch { maybeRefreshEpg() }
            refreshEpgOnly()
            handler.postDelayed(this, 60_000)
        }
    }

    private val dateFmt = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("pl"))

    /** Pogoda obok zegara (Ustawienia → Pogoda), odświeżana co 30 min. */
    private var weatherJob: kotlinx.coroutines.Job? = null
    private fun updateWeather() {
        val tv = findViewById<TextView>(R.id.weather)
        if (!Prefs.weatherEnabled || Prefs.weatherLat.isBlank()) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        tv.text = Prefs.weatherText
        if (!pl.iptv.tv.data.Weather.stale || weatherJob?.isActive == true) return
        weatherJob = lifecycleScope.launch { runCatching { pl.iptv.tv.data.Weather.refresh() }.onSuccess { tv.text = it } }
    }

    /** Wyniki na żywo przy kanałach z meczem (co minutę podczas meczu, poza tym co 10 min). */
    private var scoresJob: kotlinx.coroutines.Job? = null
    private fun updateScores() {
        val S = pl.iptv.tv.data.Scores
        if (!Prefs.scoresEnabled || S.follows.isEmpty() || scoresJob?.isActive == true) return
        val live = S.last.any { it.state == pl.iptv.tv.data.Scores.State.LIVE } || S.last.any { it.state == pl.iptv.tv.data.Scores.State.PRE && it.start - System.currentTimeMillis() < 15 * 60_000 }
        if (System.currentTimeMillis() - S.lastAt < (if (live) 55_000L else 600_000L)) return
        scoresJob = lifecycleScope.launch {
            runCatching { S.fetch() }
            channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount, ChannelAdapter.EPG)
        }
    }

    private var layoutApplied = -1
    /** Układ menu głównego (Ustawienia → Wygląd → Układ menu). */
    private fun applyMainLayout() {
        val mode = Prefs.mainLayout
        if (mode == layoutApplied) return
        if (layoutApplied != -1) { recreate(); return }
        layoutApplied = mode
        val content = findViewById<android.widget.LinearLayout>(R.id.content)
        val info = findViewById<android.widget.LinearLayout>(R.id.infoPanel)
        val box = findViewById<View>(R.id.previewBox)
        fun weight(v: View, w: Float) { (v.layoutParams as android.widget.LinearLayout.LayoutParams).weight = w }
        // podgląd 16:9 do szerokości panelu (najwyżej 60% wysokości)
        fun wideBox() = info.post {
            val w = info.width - info.paddingLeft - info.paddingRight
            if (w > 0) { box.layoutParams.height = minOf(w * 9 / 16, (info.height * 0.6f).toInt()); box.requestLayout() }
        }
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // telefon pionowo: podgląd i program u góry, przyciski + kategorie + kanały na dole
            content.orientation = android.widget.LinearLayout.VERTICAL
            // przyciski w poziomym rzędzie (pełna szerokość dla listy kanałów)
            val bottom = android.widget.LinearLayout(this)
            listOf(sideNav, groupsRv, channelsRv).forEach { v -> content.removeView(v) }
            bottom.addView(groupsRv); bottom.addView(channelsRv)
            (groupsRv.layoutParams as android.widget.LinearLayout.LayoutParams).apply { height = -1; width = Ui.dp(118) }
            portraitGroups = true; groupAdapter.compact = false        // stała szerokość z nazwami (bez rozsuwania)
            channelAdapter.compactLogo = true
            channelsRv.layoutParams = android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(6) }
            sideNav.orientation = android.widget.LinearLayout.HORIZONTAL
            for (i in 0 until sideNav.childCount) sideNav.getChildAt(i).layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(4) }
            info.layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
            content.removeView(info)
            content.addView(info)
            content.addView(sideNav, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
            content.addView(bottom, android.widget.LinearLayout.LayoutParams(-1, 0, 1.3f).apply { topMargin = Ui.dp(8) })
            findViewById<View>(R.id.status).visibility = View.GONE
            wideBox(); return
        }
        when (mode) {
            1 -> {
                // kanały | podgląd + opis | program
                val prog = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; tag = "panel"; setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10))
                }
                info.removeView(progRv)
                prog.addView(progRv, android.widget.LinearLayout.LayoutParams(-1, -1))
                content.addView(prog, android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(10) })
                weight(info, 1.3f); weight(channelsRv, 0.9f)
                findViewById<TextView>(R.id.epgDesc).maxLines = 6
                Ui.style(prog); wideBox()
            }
            2 -> { weight(channelsRv, 0.8f); weight(info, 1.7f); wideBox() }
            3 -> { weight(channelsRv, 1.6f); weight(info, 1f) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(R.layout.activity_main)
        groupsRv = findViewById(R.id.groups)
        channelsRv = findViewById(R.id.channels)
        progRv = findViewById(R.id.programmes)
        status = findViewById(R.id.status)
        clock = findViewById(R.id.clock)
        epgChannel = findViewById(R.id.epgChannel)
        epgDesc = findViewById(R.id.epgDesc)
        empty = findViewById(R.id.empty)
        listContent = findViewById(R.id.content)
        searchRow = findViewById(R.id.searchRow)
        searchField = findViewById(R.id.searchField)
        btnSearch = findViewById(R.id.btnSearch)

        groupsRv.layoutManager = LinearLayoutManager(this); groupsRv.adapter = groupAdapter
        groupAdapter.compact = true
        // kategorie rozwijają się w bok, gdy są zaznaczone pilotem
        groupsRv.viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
            if (!groupsRv.isInTouchMode) { val e = new != null && inside(new, groupsRv); groupsRv.post { setGroupsExpanded(e) } }
        }
        preview = findViewById(R.id.preview)
        applyMainLayout()
        preview.setKeepContentOnPlayerReset(true)
        findViewById<View>(R.id.previewBox).visibility = if (Prefs.mainPreview) View.VISIBLE else View.GONE
        findViewById<View>(R.id.previewBox).setOnClickListener { shownEpgFor?.let { play(it) } }
        channelsRv.layoutManager = LinearLayoutManager(this); channelsRv.adapter = channelAdapter
        progRv.layoutManager = LinearLayoutManager(this); progRv.adapter = progAdapter
        groupAdapter.label = { g ->
            if (g == EDIT_GROUPS) g else "$g  (${Repository.channelsFor(g).size})"
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // brak listy: „+” na środku otwiera Ustawienia → Listy
        findViewById<View>(R.id.emptyAdd).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", 0)) }
        findViewById<View>(R.id.btnEditor).setOnClickListener {
            if (pl.iptv.tv.data.Prefs.parentalSettings) ParentalUi.require(this) { startActivity(Intent(this, EditorActivity::class.java)) }
            else startActivity(Intent(this, EditorActivity::class.java))
        }
        btnSearch.setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnEpgSearch).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, focusedChannel?.key))
        }
        findViewById<View>(R.id.btnApps).setOnClickListener { SystemTools.showApps(this) }
        findViewById<View>(R.id.searchClose).setOnClickListener { closeSearch() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (searching) applySearch(s.toString()) }
        })
        searchField.setOnEditorActionListener { _, _, _ ->
            channelsRv.post { channelsRv.getChildAt(0)?.requestFocus() }; true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searching -> closeSearch()
                    // jako launcher nie można „wyjść” – zamiast tego menu systemowe
                    SystemTools.isLauncherEnabled(this@MainActivity) && SystemTools.isDefaultHome(this@MainActivity) -> launcherMenu()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        // akcja startowa tylko przy świeżym uruchomieniu (nie po obrocie / odtworzeniu)
        initialLoad(runStartAction = savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SKIP_START, false))
    }

    /** Okno: widoczność i kolejność kategorii (kanały ukrytej kategorii zostają w „Wszystkie”). */
    private fun editGroups() = Dialogs.editGroups(this) {
        refreshUi(); groupsRv.post { groupsRv.focusPosition(groupAdapter.items.indexOf(EDIT_GROUPS).coerceAtLeast(0)) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun launcherMenu() = Dialogs.menu(this, "Menu", listOf(
        "▦ Aplikacje" to { SystemTools.showApps(this) },
        "⚙ Ustawienia systemu" to { SystemTools.openSystemSettings(this) },
        "⚙ Ustawienia aplikacji" to { startActivity(Intent(this, SettingsActivity::class.java)) },
    ))

    private var wizard: PermissionWizard? = null

    /** Przy pierwszym uruchomieniu: kreator uprawnień, potem akcja startowa. */
    private fun firstRunThen(next: () -> Unit) {
        if (Prefs.permissionsAsked) { next(); return }
        Prefs.permissionsAsked = true; Prefs.batteryAsked = true
        wizard = PermissionWizard(this) { wizard = null; next() }.also { it.start() }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        wizard?.onPermissionResult(rc)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_BOOT, false) && initialLoadDone) runStartAction()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        wizard?.onResume()
        applyMainLayout()
        findViewById<View>(R.id.previewBox).visibility = if (Prefs.mainPreview) View.VISIBLE else View.GONE
        if (Prefs.mainPreview && Playback.active) Playback.attach(preview)
        pl.iptv.tv.remote.RemoteServer.listener = { if (initialLoadDone) refreshUi() }
        handler.post(tick)
        if (initialLoadDone) {
            // powrót z odtwarzacza: oglądany kanał (bez przełączania podglądu na pierwszy kanał – telefon nie ma fokusu)
            Repository.byKey(Prefs.lastChannel)?.takeIf { !it.hidden }?.let { focusedChannel = it; channelAdapter.selectedKey = it.key }
            // bez zmian w liście: tylko program i zaznaczenie (płynny powrót z oglądania, bez przebudowy ekranu)
            if (Repository.version == uiVersion && groupAdapter.items == Repository.groups() + EDIT_GROUPS && !searching &&
                groupAdapter.selected != Repository.FAV && groupAdapter.selected != Repository.RECENT) {
                updateExpiry()
                channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount, ChannelAdapter.EPG)
                focusedChannel?.let { ch -> if (shownEpgFor?.key != ch.key) { shownEpgFor = null; showEpg(ch) } else showEpg(ch) }
            } else refreshUi()
            focusLastChannel()
        }        // po powrocie z odtwarzacza – oglądany kanał
    }

    // ---------- kategorie zwijane w bok ----------
    private var groupsExpanded = false
    private var portraitGroups = false
    private fun setGroupsExpanded(e: Boolean) {
        if (portraitGroups) return
        if (e == groupsExpanded) return
        groupsExpanded = e
        groupAdapter.compact = !e
        val from = groupsRv.layoutParams.width; val to = Ui.dp(if (e) 200 else 64)
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = if (Ui.anim == 0) 0 else 160
            addUpdateListener { groupsRv.layoutParams.width = it.animatedValue as Int; groupsRv.requestLayout() }
        }.start()
    }

    private val collapseGroups = Runnable { if (groupsRv.isInTouchMode) setGroupsExpanded(false) }

    /** Dotyk: 1. stuknięcie w zwiniętą kolumnę rozwija ją, wybór kategorii zwija po chwili. */
    private fun onGroupClick(g: String) {
        if (g == EDIT_GROUPS) { editGroups(); return }
        // telefon w pionie: kolumna kategorii zostaje wąska (bez rozsuwania i przesuwania listy kanałów)
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (portrait) { selectGroup(g); return }
        if (groupsRv.isInTouchMode && !groupsExpanded) { setGroupsExpanded(true); return }
        selectGroup(g)
        if (groupsRv.isInTouchMode) { handler.removeCallbacks(collapseGroups); handler.postDelayed(collapseGroups, 1500) }
    }

    private fun inside(v: View, parent: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === parent) return true; x = x.parent as? View }
        return false
    }

    /** Jawna nawigacja pilotem między kolumnami (kategorie ↔ kanały ↔ program). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !searching) {
            val f = window.decorView.findFocus()
            val chIdx = channelAdapter.indexOfKey(focusedChannel?.key).coerceAtLeast(0)
            when {
                f == null -> {}
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && inside(f, channelsRv) -> {
                    setGroupsExpanded(true)
                    groupsRv.focusPosition(groupAdapter.items.indexOf(groupAdapter.selected).coerceAtLeast(0)); return true
                }
                // ◄ z kategorii -> przyciski po lewej (Program, Kanały, Edytor, Ustawienia)
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && inside(f, groupsRv) -> {
                    sideButtons().firstOrNull()?.let { setGroupsExpanded(false); it.requestFocus() }; return true
                }
                inside(f, sideNav) && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> return true
                inside(f, sideNav) && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (channelAdapter.itemCount == 0) return true
                    setGroupsExpanded(true)
                    groupsRv.focusPosition(groupAdapter.items.indexOf(groupAdapter.selected).coerceAtLeast(0)); return true
                }
                inside(f, sideNav) && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) -> {
                    val b = sideButtons(); val i = b.indexOf(f) + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    b.getOrNull(i)?.requestFocus(); return true
                }
                (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) && inside(f, groupsRv) -> {
                    val cur = groupsRv.findContainingViewHolder(f)?.bindingAdapterPosition ?: 0
                    val next = cur + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next < 0) return true
                    if (next < groupAdapter.itemCount) groupsRv.focusPosition(next)
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && inside(f, groupsRv) -> {
                    if (channelAdapter.itemCount > 0) channelsRv.focusPosition(chIdx); return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && inside(f, channelsRv) -> {
                    val now = System.currentTimeMillis()
                    val i = progAdapter.items.indexOfFirst { it.isNow(now) }.coerceAtLeast(0)
                    if (progAdapter.itemCount > 0) progRv.focusPosition(i, R.id.row)
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && inside(f, progRv) -> {
                    channelsRv.focusPosition(chIdx); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var isResumedNow = false
    private val sideNav by lazy { findViewById<android.widget.LinearLayout>(R.id.sideNav) }
    private fun sideButtons() = (0 until sideNav.childCount).map { sideNav.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.isFocusable }

    // ---------- podgląd kanału ----------
    private val previewRunnable = Runnable { startPreview() }

    private fun startPreview() {
        val ch = shownEpgFor ?: return
        // tylko na widocznym menu (inaczej podgląd przejąłby odtwarzacz uruchomiony akcją startową)
        if (!Prefs.mainPreview || isFinishing || !isResumedNow) return
        if (pl.iptv.tv.data.Parental.needsPin(ch)) { findViewById<TextView>(R.id.previewHint).text = "🔒"; return }
        findViewById<TextView>(R.id.previewHint).text = ""
        Playback.play(this, ch, ch.url)
        Playback.attach(preview)
    }

    override fun onPause() { super.onPause(); isResumedNow = false; handler.removeCallbacksAndMessages(null); pl.iptv.tv.remote.RemoteServer.listener = null }

    /** Skróty aplikacji z menu bocznego odtwarzacza – także w kolumnie przycisków menu głównego. */
    private fun appShortcuts() {
        val nav = findViewById<android.widget.LinearLayout>(R.id.sideNav) ?: return
        for (i in nav.childCount - 1 downTo 0) if (nav.getChildAt(i).getTag(R.id.row_key) == "app") nav.removeViewAt(i)
        val portrait = nav.orientation == android.widget.LinearLayout.HORIZONTAL
        SystemTools.quickApps(this).take(if (portrait) 2 else 6).forEach { app ->
            val b = TextView(this, null, 0, R.style.SideBtn).apply {
                text = app.label; setTag(R.id.row_key, "app"); tag = "item"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                app.icon?.mutate()?.let { d -> d.setBounds(0, 0, Ui.dp(28), Ui.dp(28)); setCompoundDrawables(null, d, null, null); compoundDrawablePadding = Ui.dp(4) }
                setOnClickListener { SystemTools.launch(this@MainActivity, app) }
                setOnLongClickListener { SystemTools.removeQuickApp(this@MainActivity, app) { appShortcuts() }; true }
            }
            nav.addView(b, if (portrait) android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(4) }
                else android.widget.LinearLayout.LayoutParams(Ui.dp(78), -2).apply { bottomMargin = Ui.dp(6) })
            Ui.style(b)
        }
    }

    private fun styleSideNav() {
        mapOf(R.id.btnEpgSearch to R.drawable.ic_n_epg, R.id.btnSearch to R.drawable.ic_n_search, R.id.btnEditor to R.drawable.ic_n_edit,
            R.id.btnSettings to R.drawable.ic_n_settings, R.id.btnApps to R.drawable.ic_n_apps)
            .forEach { (id, ic) -> findViewById<TextView>(id)?.let { Ui.iconOnTop(it, ic) } }
    }

    override fun applyTheme() {
        super.applyTheme()
        styleSideNav()
        if (::btnSearch.isInitialized) {
            groupAdapter.notifyDataSetChanged(); channelAdapter.notifyDataSetChanged(); progAdapter.notifyDataSetChanged()
            // widoki w RecyclerView są stylowane przy tworzeniu – wymuś ponowne utworzenie
            listOf(groupsRv, channelsRv, progRv).forEach { it.recycledViewPool.clear(); it.adapter = it.adapter }
        }
    }

    private fun initialLoad(runStartAction: Boolean) = lifecycleScope.launch {
        status.text = "Wczytywanie…"
        if (Repository.allChannels.isEmpty()) Repository.loadPlaylistFromCache()
        if (Repository.epg.programmes.isEmpty() && Prefs.startAction == StartActions.GUIDE) Repository.loadEpgFromCache()
        refreshUi(); focusLastChannel()
        if (Repository.allChannels.isNotEmpty()) Prefs.setupDone = true     // istniejąca konfiguracja
        if (!Prefs.setupDone) {
            // pierwsze uruchomienie: kreator (zawiera też uprawnienia)
            Prefs.permissionsAsked = true
            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
        } else firstRunThen { if (runStartAction) runStartAction() }
        val src = Prefs.playlistSource
        // odświeżenie listy z sieci: pełna przebudowa tylko, gdy zmienił się skład kanałów
        val before = Repository.allChannels.map { it.key }
        if (src.startsWith("http", true)) runCatching { Repository.loadPlaylist(src) }
            .onSuccess { if (Repository.allChannels.map { it.key } != before) refreshUi() else refreshEpgOnly() }
        if (Repository.epgAutoBlocked) {
            // dwie awarie przy wczytywaniu EPG: bez automatycznego wczytywania (koniec pętli zacinania i zamykania)
            Toast.makeText(this@MainActivity, "Program TV jest zbyt duży dla tego urządzenia – wczytywanie automatyczne wstrzymane. " +
                "Wczytaj go ręcznie w Ustawienia → Program TV (najlepiej z mniejszego pliku EPG).", Toast.LENGTH_LONG).show()
            status.text = ""; initialLoadDone = true; return@launch
        }
        if (Repository.epg.programmes.isEmpty()) {
            status.text = "Wczytywanie EPG…"
            Repository.loadEpgFromCache(); refreshEpgOnly()
        }
        maybeRefreshEpg()
        status.text = ""
        initialLoadDone = true
    }

    private var epgLoading = false

    /** Pobranie EPG z sieci, gdy jest nieaktualne. Słabsze urządzenie: nie w trakcie oglądania (bez zacinania obrazu), wtedy później z menu. */
    private suspend fun maybeRefreshEpg() {
        if (epgLoading || Repository.epgAutoBlocked || Prefs.effectiveEpgUrl.isBlank()) return
        val empty = Repository.epg.programmes.isEmpty()
        val stale = System.currentTimeMillis() - Prefs.lastEpgUpdate > Prefs.epgRefreshDays * 86_400_000L
        if (!empty && !stale) return
        if (!empty && Prefs.lowPerf && Playback.isPlayingNow && !isResumedNow) return
        epgLoading = true
        status.text = "Pobieranie EPG…"
        runCatching { Repository.loadEpg() }
            .onFailure { Toast.makeText(this@MainActivity, "EPG: ${it.message}", Toast.LENGTH_LONG).show() }
        epgLoading = false
        status.text = ""
        refreshEpgOnly()
    }

    /** Licznik dni do końca ważności aktywnej listy (tylko gdy podano datę). */
    private fun updateExpiry() {
        val tv = findViewById<TextView>(R.id.expiry)
        val d = pl.iptv.tv.data.Playlists.daysLeft()
        if (d == null) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        val color = when { d < 0 -> Ui.live; d <= 3 -> Ui.live; d <= 7 -> android.graphics.Color.parseColor("#FF9800"); else -> Ui.accent }
        tv.text = when { d < 0 -> "⛔ Lista wygasła"; d == 0 -> "⚠ Ważna do dziś"; d == 1 -> "⚠ 1 dzień"; else -> "📅 $d dni" }
        tv.setTextColor(color)
        tv.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(12).toFloat(); setStroke(Ui.dp(2), color) }
    }

    /** Po wczytaniu EPG i co minutę: tylko teksty programów i postęp (bez przebudowy list – bez przycięć odtwarzania). */
    private fun refreshEpgOnly() {
        channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount, ChannelAdapter.EPG)
        progAdapter.notifyItemRangeChanged(0, progAdapter.itemCount)
        shownEpgFor?.let { ch ->
            val now = System.currentTimeMillis()
            if (progAdapter.itemCount == 0 && Repository.programmes(ch).isNotEmpty()) { shownEpgFor = null; showEpg(ch) }
            else progAdapter.items.firstOrNull { it.isNow(now) }?.let { if (!inside(window.decorView.findFocus() ?: progRv, progRv)) epgDesc.text = describe(it) }
        }
    }

    private var uiVersion = -1

    private fun refreshUi() {
        uiVersion = Repository.version
        updateExpiry()
        btnSearch.visibility = if (Prefs.searchEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnEpgSearch).visibility = View.VISIBLE
        findViewById<View>(R.id.btnApps).visibility = if (SystemTools.isLauncherEnabled(this)) View.VISIBLE else View.GONE
        appShortcuts()
        if (!Prefs.searchEnabled && searching) closeSearch()
        val chs = Repository.channels
        empty.visibility = if (chs.isEmpty()) View.VISIBLE else View.GONE
        listContent.visibility = if (chs.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (chs.isEmpty()) { findViewById<View>(R.id.emptyAdd).requestFocus(); return }
        val groups = Repository.groups()
        if (groupAdapter.selected !in groups) groupAdapter.selected = if (Repository.ALL in groups) Repository.ALL else groups.first()
        groupAdapter.items = groups + EDIT_GROUPS             // na końcu: edycja widoczności i kolejności kategorii
        if (searching) applySearch(searchField.text.toString()) else applyGroup(groupAdapter.selected)
        shownEpgFor = null
        val ch = focusedChannel?.let { Repository.byKey(it.key) }?.takeIf { !it.hidden } ?: channelAdapter.items.firstOrNull()
        ch?.let { focusedChannel = it; showEpg(it) }
    }

    /** Wykonuje akcję wybraną w Ustawienia → Funkcje → Po włączeniu aplikacji. */
    private fun runStartAction() {
        val chs = Repository.channels
        fun channel(key: String) = Repository.byKey(key)?.takeIf { !it.hidden }
        when (Prefs.startAction) {
            StartActions.LAST_CHANNEL -> channel(Prefs.lastChannel)?.let { play(it) }
            StartActions.CHANNEL -> (channel(Prefs.startChannel) ?: channel(Prefs.lastChannel))?.let { play(it) }
            StartActions.GUIDE -> startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, Prefs.lastChannel))
            StartActions.FAVORITES -> if (Repository.favorites().isNotEmpty()) openGroup(Repository.FAV)
            StartActions.RECENT -> if (Repository.recent().isNotEmpty()) openGroup(Repository.RECENT)
            StartActions.SEARCH -> if (Prefs.searchEnabled && chs.isNotEmpty()) openSearch()
            StartActions.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            StartActions.EDITOR -> if (Repository.allChannels.isNotEmpty()) startActivity(Intent(this, EditorActivity::class.java))
        }
    }

    /** Pokazuje grupę także wtedy, gdy jest ukryta na liście grup (np. wyłączone „Ulubione”). */
    private fun openGroup(g: String) {
        groupAdapter.selected = g
        applyGroup(g)
        channelsRv.post { channelsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun focusLastChannel() {
        if (channelAdapter.items.isEmpty()) return
        val idx = channelAdapter.indexOfKey(Prefs.lastChannel).coerceAtLeast(0)
        channelsRv.scrollToPosition(idx)
        channelsRv.post { channelsRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }
    }

    private var appliedGroup: String? = null
    private fun selectGroup(g: String) {
        if (searching) closeSearch()
        if (g == appliedGroup) return
        groupAdapter.selected = g
        applyGroup(g)
    }

    private fun applyGroup(g: String) { appliedGroup = g; channelAdapter.items = Repository.channelsFor(g) }

    // ---------- wyszukiwarka ----------
    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        Ui.style(searchRow)
        searchField.requestFocus()
        applySearch(searchField.text.toString())
    }

    private fun closeSearch() {
        searchRow.visibility = View.GONE
        searchField.setText("")
        applyGroup(groupAdapter.selected)
        channelsRv.post { channelsRv.getChildAt(0)?.requestFocus() }
    }

    private fun applySearch(q: String) {
        channelAdapter.items = if (q.isBlank()) Repository.channelsFor(groupAdapter.selected) else Repository.search(q)
    }

    // ---------- EPG ----------
    private val showEpgRunnable = Runnable { focusedChannel?.let { showEpg(it) } }
    private fun scheduleShowEpg(ch: Channel) {
        focusedChannel = ch
        handler.removeCallbacks(showEpgRunnable)
        handler.postDelayed(showEpgRunnable, 250)
    }

    private fun showEpg(ch: Channel) {
        handler.removeCallbacks(previewRunnable)
        if (Prefs.mainPreview && isResumedNow) handler.postDelayed(previewRunnable, 700)
        if (shownEpgFor === ch) return
        shownEpgFor = ch
        val now = System.currentTimeMillis()
        epgChannel.text = ch.name
        val progs = Repository.programmes(ch)
        val catchupFrom = if (ch.hasCatchup) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
        val visibleFrom = if (ch.hasCatchup) catchupFrom else now - 3 * 3_600_000L
        val list = progs.filter { it.end > visibleFrom }
        progAdapter.submit(list, catchupFrom, ch)
        val cur = list.indexOfFirst { it.isNow(now) }
        epgDesc.text = if (list.isEmpty()) "Brak programu TV dla tego kanału" else list.getOrNull(cur)?.let { describe(it) } ?: ""
        if (cur >= 0) (progRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(cur, 0)
    }

    private fun describe(p: Programme): String {
        return "${Fmt.time(p.start)}–${Fmt.time(p.end)}  ${p.fullTitle}" +
            (if (p.category.isNotBlank()) "  [${p.category}]" else "") +
            (if (p.desc.isNotBlank()) "\n${p.desc}" else "")
    }

    // ---------- akcje ----------
    /** Pilot: OK = oglądaj. Dotyk: 1. dotknięcie pokazuje program, 2. dotknięcie = oglądaj. */
    private fun onChannelClick(ch: Channel, v: View) {
        if (v.isInTouchMode && channelAdapter.selectedKey != ch.key) {
            channelAdapter.selectedKey = ch.key
            focusedChannel = ch; showEpg(ch)
        } else play(ch)
    }

    private fun channelMenu(ch: Channel) {
        ChannelActions.menu(this, ch, extra = listOf("▶ Oglądaj" to { play(ch) })) { refreshUi() }
    }

    private fun onProgrammeClick(p: Programme) {
        val ch = shownEpgFor ?: return
        val now = System.currentTimeMillis()
        if (p.end <= now && p.start < progAdapter.catchupFrom) { ProgrammeActions.menu(this, ch, p, ::play); return }
        val archived = p.start >= progAdapter.catchupFrom
        when {
            p.start > now -> ProgrammeActions.menu(this, ch, p, ::play) { progAdapter.notifyDataSetChanged() }
            p.isNow(now) && archived -> Ui.dialog(this).setTitle(p.title)
                .setItems(arrayOf("Oglądaj na żywo", "Oglądaj od początku")) { _, w -> if (w == 0) play(ch) else play(ch, p) }
                .show()
            p.isNow(now) -> play(ch)
            archived -> play(ch, p)
            else -> Toast.makeText(this, "Ten program nie jest dostępny w archiwum", Toast.LENGTH_SHORT).show()
        }
    }

    private fun play(ch: Channel, p: Programme? = null) {
        Prefs.lastChannel = ch.key
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            putExtra(PlayerActivity.EXTRA_GROUP, if (searching) Repository.ALL else groupAdapter.selected)
            if (p != null) { putExtra(PlayerActivity.EXTRA_START, p.start); putExtra(PlayerActivity.EXTRA_END, p.end) }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val ch = (currentFocus?.let { channelsRv.findContainingViewHolder(it) }?.bindingAdapterPosition)
                    ?.let { channelAdapter.items.getOrNull(it) }
                if (ch != null) channelMenu(ch) else startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> if (Prefs.searchEnabled) { openSearch(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
    /** (v2, ujednolicenie) Górna belka modułu (V2TopModuleBar) – nazwa sekcji. */
    override val v2ModuleTitle: String? get() = "Live TV"
    /** (7.22) Telefon pionowo: zakładka „Live TV” dolnego paska ([MobilePortraitScaffold]). */
    override val portraitTab get() = MobilePortraitScaffold.Tab.LIVE
    override val miniPlayer = true
    companion object {
        /** (7.22) Wysokość listy programu pod podglądem w pionie telefonu (dp). */
        const val PORTRAIT_EPG_DP = 132
        /** (v2) Tagi akcji szklanego pustego stanu (brak listy kanałów) w motywie Nowoczesny v2. */
        const val EMPTY_ADD_TAG = "empty_add_m3u"
        const val EMPTY_WIFI_TAG = "empty_wifi"
        /** (7.6) Kategoria pod kursorem pilota otwiera się po tej chwili (dawniej 250 ms). */
        const val GROUP_FOCUS_DELAY_MS = 180L
        const val EXTRA_FROM_BOOT = "from_boot"
        /** Ustawiane przez launcher przy kolejnych naciśnięciach HOME. */
        const val EXTRA_SKIP_START = "skip_start"
        /** Otwarta z pulpitu (X25 / PS5) przyciskiem „Live TV” – bez powrotu na pulpit i bez akcji startowej. */
        const val EXTRA_FROM_DASHBOARD = "from_dashboard"
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
        handler.removeCallbacks(applyPendingGroup); handler.postDelayed(applyPendingGroup, GROUP_FOCUS_DELAY_MS)
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

    /** Układ „karty poziome” (motywy konsolowe): u góry przyciski, kategorie i podgląd z programem, na dole przewijany rząd kart kanałów. */
    private var cardsMode = false

    private fun cardsLayout(content: android.widget.LinearLayout, info: android.widget.LinearLayout) {
        cardsMode = true; channelAdapter.cards = true
        content.orientation = android.widget.LinearLayout.VERTICAL
        listOf(sideNav, groupsRv, channelsRv, info).forEach { content.removeView(it) }
        val upper = android.widget.LinearLayout(this)
        upper.addView(sideNav); upper.addView(groupsRv)
        upper.addView(info, android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(10) })
        content.addView(upper, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        // rząd kart: zapas na powiększenie zaznaczonej karty (bez ucinania u góry i na dole)
        channelsRv.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12))
        content.addView(channelsRv, android.widget.LinearLayout.LayoutParams(-1, Ui.dp(184)).apply { topMargin = Ui.dp(10) })
        findViewById<TextView>(R.id.epgDesc).maxLines = 3
    }

    /** (7.17) Układ „Obsidian Stream” włączony (pasek nawigacji nad treścią, kolumny 72 dp / 42 % / reszta). */
    private var obsidianMode = false
    private var navRail: pl.iptv.tv.ui.theme.AppNavigationRailViews? = null
    private fun railButtons() = (0 until sideNav.childCount).mapNotNull { sideNav.getChildAt(it) as? TextView }

    /**
     * Kanały / EPG w stylu „Obsidian Stream” na tle `#080A0F`:
     * - pasek nawigacji ([pl.iptv.tv.ui.theme.AppNavigationRailViews]) NAD treścią – 72 dp, z fokusem 240 dp z podpisami
     *   i scrimem nad kolumnami (podpisy nie nachodzą na listę);
     * - kolumna 1 (72 dp): pigułki kategorii (skróty AL, MU, …), bez rozsuwania;
     * - kolumna 2 (42 % szerokości): lista kanałów (numer, logo, nazwa, program, postęp 3 dp, fokus w akcencie);
     * - kolumna 3 (reszta): podgląd PiP 16:9 zaokrąglony 16 dp z subtelną ramką, pod nim program z pigułką NA ŻYWO.
     * Nawigacja pilotem bez zmian ([dispatchKeyEvent]: pasek ↔ kategorie ↔ kanały ↔ program).
     */
    private fun obsidianLayout(content: android.widget.LinearLayout, info: android.widget.LinearLayout) {
        obsidianMode = true
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val host = content.parent as android.widget.FrameLayout
        // pasek nad treścią: w kolumnach zostaje tylko miejsce na zwinięty pasek
        content.removeView(sideNav)
        content.addView(View(this), 0, android.widget.LinearLayout.LayoutParams(Ui.dp(72), -1).apply { marginEnd = Ui.dp(12) })
        val scrim = View(this)
        host.addView(scrim, android.widget.FrameLayout.LayoutParams(-1, -1))
        sideNav.tag = null
        sideNav.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF5080A0F.toInt(), 0xEB121722.toInt())).apply { cornerRadius = Ui.dp(16).toFloat(); setStroke(Ui.dp(1), O.SPECULAR) }
        sideNav.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
        sideNav.clipChildren = true; sideNav.clipToPadding = true
        host.addView(sideNav, android.widget.FrameLayout.LayoutParams(Ui.dp(72), -1))
        navRail = pl.iptv.tv.ui.theme.AppNavigationRailViews(sideNav, scrim, ::railButtons, { it.contentDescription?.toString() ?: "" },
            lowPerf = { Prefs.lowPerf }).also { it.attach() }
        // kolumna 1: pigułki kategorii
        groupsRv.tag = null; groupsRv.background = null
        groupsRv.layoutParams = (groupsRv.layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = Ui.dp(72) }
        groupsRv.setPadding(0, Ui.dp(4), 0, Ui.dp(4))
        groupAdapter.obsidian = true; groupAdapter.compact = true
        // kolumna 2: 42 % szerokości ekranu
        channelsRv.tag = null; channelsRv.background = null
        channelsRv.layoutParams = android.widget.LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.42f).toInt(), -1).apply { marginStart = Ui.dp(12) }
        channelAdapter.obsidian = true
        // kolumna 3: podgląd PiP i program
        info.tag = null; info.background = null
        info.layoutParams = android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(20) }
        info.setPadding(0, 0, 0, 0)
        val box = findViewById<android.widget.FrameLayout>(R.id.previewBox)
        box.background = O.previewFrame()
        if (android.os.Build.VERSION.SDK_INT >= 23) box.foreground = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(O.RADIUS_DP).toFloat(); setStroke(Ui.dp(1), 0x40FFFFFF); setColor(0) }
        Ui.roundClip(box, Ui.dp(O.RADIUS_DP).toFloat())
        progAdapter.obsidian = true
        epgChannel.gravity = android.view.Gravity.START; epgDesc.gravity = android.view.Gravity.START
        epgChannel.textSize = 22f; epgDesc.textSize = 14f; epgDesc.setTextColor(O.TEXT); epgDesc.tag = "keepcolor"
        (epgChannel.layoutParams as ViewGroup.MarginLayoutParams).topMargin = Ui.dp(12)
        // podgląd 16:9 na szerokość kolumny (najwyżej 55 % wysokości)
        info.post {
            val w = info.width
            if (w > 0) { box.layoutParams.height = minOf(w * 9 / 16, (info.height * 0.55f).toInt()); box.requestLayout() }
        }
        if (ModernV2.active) glassColumns(info, box)
    }

    /**
     * (v2) Nowoczesny v2 – „Obsidian Glass” w widoku 3-kolumnowym: pasek nawigacji (Program, Kanały, STube, Stremio, Edytor,
     * Ustawienia) na szklanej kapsule 24 dp, kolumna kategorii i lista kanałów na szklanych kartach (wiersze, fokus z ramką
     * i poświatą – [Ui.itemBg]), podgląd na żywo z niebieską ramką i ramówka pod nim na karcie. Układ, nawigacja pilotem
     * i dotyk bez zmian (te same widoki – tylko tła i odstępy).
     */
    private fun glassColumns(info: android.widget.LinearLayout, box: android.widget.FrameLayout) {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        // pływająca wyspa nawigacji: odsunięta 12 dp od krawędzi, 24 dp, #0D1426 72 %, gradientowe obramowanie 1,5 dp
        sideNav.background = G.island()
        val m = Ui.dp(G.ISLAND_MARGIN_DP)
        (sideNav.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { it.setMargins(m, m, m, m); sideNav.layoutParams = it }
        // miejsce w kolumnach zarezerwowane dla zwiniętego paska (pierwsze dziecko treści) – szersze o odsunięcie wyspy
        (info.parent as? android.widget.LinearLayout)?.getChildAt(0)?.let { ph ->
            (ph.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { it.marginStart = m * 2; ph.layoutParams = it }
        }
        fun card(v: View, pad: Int) {
            v.background = G.glassOverlay(G.DIALOG_RADIUS_DP);   /* (v2, ujednolicenie) V2GlassDialog */ v.setPadding(Ui.dp(pad), Ui.dp(pad), Ui.dp(pad), Ui.dp(pad))
            (v as? ViewGroup)?.clipToPadding = false
        }
        card(groupsRv, 6); card(channelsRv, 8)
        if (android.os.Build.VERSION.SDK_INT >= 23) box.foreground = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(G.RADIUS_CARD_DP).toFloat(); setStroke(Ui.dp(G.FOCUS_STROKE_DP), G.withAlpha(G.FOCUS_RING, 0xB0)); setColor(0) }
        progRv.background = G.glassOverlay(G.DIALOG_RADIUS_DP); progRv.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8)); progRv.clipToPadding = false
        epgDesc.setTextColor(G.TEXT2)
    }

    /**
     * (v2) Pusty stan bez listy kanałów: szklana karta z „+” (Ustawienia → Listy, jak dotąd) i akcjami szybkiego dodania listy
     * M3U (kreator nowej listy) oraz przesłania jej przez Wi-Fi (strona w przeglądarce telefonu / aplikacja – adres, PIN i QR).
     */
    /**
     * (v2) Nagłówek Live TV w belce modułu: bez powtórzonej nazwy aplikacji (jest w lewym górnym rogu belki); po prawej – dni
     * ważności listy obok godziny, pod nimi data (dotychczasowy zegar belki ukryty); stan i pogoda obok. Dawny pasek znika.
     */
    private fun v2Header() {
        val bar = v2TopBar as? android.widget.LinearLayout ?: return
        val top = findViewById<android.widget.LinearLayout>(R.id.topBar) ?: return
        val expiry = findViewById<View>(R.id.expiry); val date = findViewById<TextView>(R.id.date); val weather = findViewById<View>(R.id.weather)
        val clockCol = clock.parent as? ViewGroup
        for (v in listOf(expiry, status, weather, clock, date)) (v.parent as? ViewGroup)?.removeView(v)
        top.visibility = View.GONE

        (0 until bar.childCount).map { bar.getChildAt(it) }.firstOrNull { (it.tag as? String)?.contains(pl.iptv.tv.ui.theme.V2TopModuleBar.CLOCK_TAG) == true }?.let { bar.removeView(it) }
        val at = maxOf(0, bar.childCount - 1)          // przed ikoną profilu
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        (expiry as? TextView)?.setSingleLine(); clock.setSingleLine(); status.maxWidth = Ui.dp(260); status.setSingleLine()
        row.addView(expiry, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) }); row.addView(clock, android.widget.LinearLayout.LayoutParams(-2, -2))
        clock.text = Fmt.time(System.currentTimeMillis())
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.END }
        col.addView(row, android.widget.LinearLayout.LayoutParams(-2, -2)); col.addView(date, android.widget.LinearLayout.LayoutParams(-2, -2))
        bar.addView(status, at, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(12) })
        bar.addView(weather, at + 1, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(12) })
        bar.addView(col, at + 2, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(14) })
        clockCol?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun glassEmptyState() {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        val box = empty as? android.widget.LinearLayout ?: return
        if (box.childCount > 2) return                    // karta już zbudowana
        box.background = G.glassDialog()      // (v2) V2GlassDialog
        box.setPadding(Ui.dp(28), Ui.dp(24), Ui.dp(28), Ui.dp(24))
        (box.getChildAt(1) as? TextView)?.text = "Brak listy kanałów.\nDodaj listę M3U albo prześlij ją z telefonu przez Wi-Fi."
        fun action(text: String, tag: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; this.tag = "item $tag"; textSize = 16f; gravity = android.view.Gravity.CENTER
            isFocusable = true; isClickable = true; setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(12))
            setOnClickListener { onClick() }
        }
        val row = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER; orientation =
            if (resources.configuration.screenWidthDp < 600) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL }
        row.addView(action("＋ Dodaj listę M3U", EMPTY_ADD_TAG) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ADD_LIST, true))
        }, android.widget.LinearLayout.LayoutParams(-2, -2))
        row.addView(action("📶 Prześlij przez Wi-Fi", EMPTY_WIFI_TAG) { wifiTransfer() }, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
            if (row.orientation == android.widget.LinearLayout.HORIZONTAL) marginStart = Ui.dp(12) else topMargin = Ui.dp(10) })
        box.addView(row, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(18) })
        Ui.style(box)
    }

    /** (v2) Odbiór listy przez Wi-Fi z pustego stanu – jak w kreatorze: serwer włączony, adres, PIN i kod QR strony. */
    private fun wifiTransfer() {
        Prefs.remoteOn = true; pl.iptv.tv.remote.RemoteServer.start(this)
        val ip = pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull()
        val url = "http://${ip ?: "?"}:${pl.iptv.tv.remote.RemoteServer.PORT}"
        Ui.dialog(this).setTitle("📶 Lista przez Wi-Fi").setMessage(
            "Na telefonie lub komputerze w tej samej sieci otwórz w przeglądarce:\n\n$url\n\nPIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}\n\n" +
                "Albo w D-iptv Player na telefonie: Ustawienia → Listy → Wyślij w tej samej sieci Wi-Fi. Lista pojawi się tutaj automatycznie.")
            .apply { if (ip != null) setView(android.widget.FrameLayout(this@MainActivity).apply { setPadding(0, Ui.dp(8), 0, 0)
                addView(QrCode.image(this@MainActivity, url, 140), android.widget.FrameLayout.LayoutParams(Ui.dp(140), Ui.dp(140), android.view.Gravity.CENTER_HORIZONTAL)) }) }
            .setPositiveButton("OK", null).show()
    }

    private var layoutApplied = -1
    /** Układ menu głównego (Ustawienia → Wygląd → Układ menu). */
    /** (7.22) Rząd „Szukaj” + pigułki kategorii (testy). */
    var portraitChips: android.widget.LinearLayout? = null; private set

    /**
     * (7.22) Live TV na telefonie w pionie: u góry kompaktowy rząd – „Szukaj” i kategorie jako przewijane w poziomie pigułki
     * (kolory motywu), pod nim szybki podgląd 16:9 (najwyżej 28 % wysokości) z bieżącym programem i krótką listą EPG, dalej
     * lista kanałów na całą szerokość (logo, nazwa, bieżący program z paskiem postępu). Boczne przyciski TV ukryte – ich
     * funkcje są w dolnym pasku (Stremio, STube, Do obejrzenia, Więcej).
     */
    private fun portraitScaffoldLayout(content: android.widget.LinearLayout, info: android.widget.LinearLayout, box: View) {
        content.orientation = android.widget.LinearLayout.VERTICAL
        listOf(sideNav, groupsRv, channelsRv, info).forEach { v -> content.removeView(v) }
        sideNav.visibility = View.GONE
        content.addView(sideNav, android.widget.LinearLayout.LayoutParams(0, 0))
        val row = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        row.addView(MobilePortraitScaffold.chip(this, "Szukaj", false) { openSearch() }.apply {
            setCompoundDrawables(PlayerIcon(PlayerIcon.SEARCH, textColors).apply { setBounds(0, 0, Ui.dp(16), Ui.dp(16)) }, null, null, null); compoundDrawablePadding = Ui.dp(6)
            setTag(R.id.row_key, "portrait_search")
        }, android.widget.LinearLayout.LayoutParams(-2, Ui.dp(38)).apply { marginEnd = Ui.dp(8) })
        groupsRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        groupAdapter.chips = true; groupAdapter.compact = false; portraitGroups = true
        groupsRv.tag = "nopad nopivot"; groupsRv.background = null; groupsRv.setPadding(0, 0, 0, 0); groupsRv.clipToPadding = false
        row.addView(groupsRv, android.widget.LinearLayout.LayoutParams(0, Ui.dp(44), 1f))
        portraitChips = row
        content.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
        info.layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(6) }
        content.addView(info)
        progRv.layoutParams = progRv.layoutParams.apply { height = Ui.dp(PORTRAIT_EPG_DP) }
        channelAdapter.compactLogo = false
        channelsRv.layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(8) }
        content.addView(channelsRv)
        findViewById<View>(R.id.status).visibility = View.GONE
        info.post {
            val w = info.width - info.paddingLeft - info.paddingRight
            if (w > 0) { box.layoutParams.height = minOf(w * 9 / 16, (resources.displayMetrics.heightPixels * 0.28f).toInt()); box.requestLayout() }
        }
    }

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
        // (7.22) telefon pionowo (9:16) z dolnym paskiem nawigacji: wyszukiwanie + pigułki kategorii u góry, podgląd, lista kanałów
        if (portraitPhone) { portraitScaffoldLayout(content, info, box); return }
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
        // (7.17) „Obsidian Stream” (motyw Nowoczesny, poziomo, poza układem kart): pasek nawigacji + 3 kolumny
        if (pl.iptv.tv.ui.theme.ObsidianViews.active && mode != Ui.LAYOUT_CARDS) { obsidianLayout(content, info); return }
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
            Ui.LAYOUT_CARDS -> cardsLayout(content, info)
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
        if (ModernV2.active) v2Header()
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
        channelsRv.layoutManager = LinearLayoutManager(this, if (cardsMode) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL, false)
        channelsRv.adapter = channelAdapter
        progRv.layoutManager = LinearLayoutManager(this); progRv.adapter = progAdapter
        // szybka nawigacja pilotem: bez animacji zmian elementów (odświeżenie EPG nie „mruga” i nie opóźnia fokusu), stały rozmiar list
        listOf(groupsRv, channelsRv, progRv).forEach { it.itemAnimator = null; it.setHasFixedSize(true) }
        Ui.roundClip(findViewById(R.id.previewBox), Ui.dp(maxOf(Ui.preset.radius - 4, 4)).toFloat())
        groupAdapter.label = { g ->
            if (g == EDIT_GROUPS) g else "$g  (${Repository.channelsFor(g).size})"
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // brak listy: „+” na środku otwiera Ustawienia → Listy
        findViewById<View>(R.id.emptyAdd).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", 0)) }
        if (ModernV2.active) glassEmptyState()
        findViewById<View>(R.id.btnEditor).setOnClickListener {
            if (pl.iptv.tv.data.Prefs.parentalSettings) ParentalUi.require(this) { startActivity(Intent(this, EditorActivity::class.java)) }
            else startActivity(Intent(this, EditorActivity::class.java))
        }
        btnSearch.setOnClickListener { openSearch() }
        findViewById<View>(R.id.btnEpgSearch).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL, focusedChannel?.key))
        }
        findViewById<View>(R.id.btnApps).setOnClickListener { SystemTools.showApps(this) }
        findViewById<View>(R.id.btnSTube).setOnClickListener { startActivity(Intent(this, STubeActivity::class.java)) }
        findViewById<View>(R.id.btnStremio).setOnClickListener { startActivity(Intent(this, StremioActivity::class.java)) }
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
                    // motyw X25: lista kanałów wraca na pulpit
                    X25Theme.dashboardOn -> X25Theme.openDashboard(this@MainActivity)
                    // jako launcher nie można „wyjść” – zamiast tego menu systemowe
                    SystemTools.isLauncherEnabled(this@MainActivity) && SystemTools.isDefaultHome(this@MainActivity) -> launcherMenu()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        // motyw X25: pulpit nad listą kanałów – przed akcją startową (uruchomiony kanał / przewodnik jest nad pulpitem, WSTECZ wraca na pulpit);
        // nie przy pierwszym uruchomieniu (kreator i okna uprawnień są na tym ekranie)
        // (lista otwarta z pulpitu – „Live TV” – zostaje na wierzchu: pulpit mógł startować pierwszy, np. HOME w trybie launchera)
        if (savedInstanceState == null && X25Theme.dashboardOn && Prefs.permissionsAsked && Prefs.setupDone && !intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false))
            X25Theme.openDashboard(this)
        // akcja startowa tylko przy świeżym uruchomieniu (nie po obrocie / odtworzeniu)
        initialLoad(runStartAction = savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SKIP_START, false))
    }

    /** Okno: widoczność i kolejność kategorii (kanały ukrytej kategorii zostają w „Wszystkie”). */
    private fun editGroups() = Dialogs.editGroups(this) {
        refreshUi(); groupsRv.post { groupsRv.focusPosition(groupAdapter.items.indexOf(EDIT_GROUPS).coerceAtLeast(0)) }
    }

    private fun toast(t: String) = I18n.makeToast(this, t, Toast.LENGTH_SHORT).show()

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
        // HOME (launcher) przy motywie X25 – pulpit
        // (7.4) „Live TV” z pulpitu (lista już istniała pod pulpitem) – lista zostaje; dawniej od razu wracał pulpit
        else if (intent.getBooleanExtra(EXTRA_SKIP_START, false) && !intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false) && X25Theme.dashboardOn)
            X25Theme.openDashboard(this)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        // właśnie włączony motyw X25 (Ustawienia) – od razu pulpit
        if (X25Theme.consumeJustEnabled() && X25Theme.dashboardOn) X25Theme.openDashboard(this)
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
        if (portraitGroups || obsidianMode) return          // (7.17) Obsidian: stała kolumna pigułek 72 dp
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
        handler.removeCallbacks(applyPendingGroup); pendingGroup = null
        selectGroup(g)
        if (groupsRv.isInTouchMode) { handler.removeCallbacks(collapseGroups); handler.postDelayed(collapseGroups, 1500); return }
        // pilot: OK na kategorii = od razu wejście do niej (fokus na pierwszym kanale)
        enterGroup()
    }

    /** Fokus na pierwszym kanale bieżącej kategorii (po OK na kategorii). */
    private fun enterGroup() {
        if (channelAdapter.itemCount == 0) return
        setGroupsExpanded(false)
        channelsRv.scrollToPosition(0)
        channelsRv.post { channelsRv.focusPosition(0) }
    }

    private fun inside(v: View, parent: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === parent) return true; x = x.parent as? View }
        return false
    }

    /** Jawna nawigacja pilotem między kolumnami (kategorie ↔ kanały ↔ program). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ▼ z najniższego elementu kolumny – pasek „Teraz odtwarzane” (film STube w tle)
        if (miniBar?.handleKey(event) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && !searching) {
            val f = window.decorView.findFocus()
            val chIdx = channelAdapter.indexOfKey(focusedChannel?.key).coerceAtLeast(0)
            when {
                f == null -> {}
                // karty poziome: ◄ ► po kartach (◄ z pierwszej – kategorie), ▲ – program kanału
                cardsMode && inside(f, channelsRv) && (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (channelsRv.findContainingViewHolder(f)?.bindingAdapterPosition ?: 0) > 0)) -> {
                    val cur = channelsRv.findContainingViewHolder(f)?.bindingAdapterPosition ?: 0
                    val next = cur + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    if (next < channelAdapter.itemCount) channelsRv.focusPosition(next)
                    return true
                }
                cardsMode && inside(f, channelsRv) && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    val now = System.currentTimeMillis()
                    if (progAdapter.itemCount > 0) progRv.focusPosition(progAdapter.items.indexOfFirst { it.isNow(now) }.coerceAtLeast(0), R.id.row)
                    else groupsRv.focusPosition(groupAdapter.items.indexOf(groupAdapter.selected).coerceAtLeast(0))
                    return true
                }
                cardsMode && inside(f, channelsRv) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> return true
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
        // film STube gra w tle (pasek „Teraz odtwarzane”) – bez podglądu kanału (dwa dźwięki naraz)
        if (YtEngine.active) return
        // (7.25) trwa przesyłanie na telewizor – bez lokalnego podglądu (dźwięk tylko na odbiorniku)
        if (CastHub.casting) return
        if (pl.iptv.tv.data.Parental.needsPin(ch)) { findViewById<TextView>(R.id.previewHint).text = "🔒"; return }
        findViewById<TextView>(R.id.previewHint).text = ""
        Playback.play(this, ch, ch.url)
        Playback.attach(preview)
    }

    /** Podgląd kanału odpięty od wspólnego odtwarzacza (odtwarzanie trwa dalej na innym widoku albo bez obrazu). */
    override fun onDestroy() { navRail?.detach(); if (::preview.isInitialized) Playback.detach(preview); super.onDestroy() }

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
        navRail?.styleItems()
    }

    /**
     * (7.17) Pasek „Obsidian Stream”: pigułki 48 dp – skróty aplikacji, które nie mieszczą się w wysokości paska, są ukrywane
     * (dostępne w ◄◄ odtwarzacza i w Aplikacjach); podpisy zostają (pasek rozwija się z fokusem).
     */
    private fun fitObsidianRail() = sideNav.post {
        val avail = sideNav.height - sideNav.paddingTop - sideNav.paddingBottom
        if (avail <= 0) return@post
        fun visible() = (0 until sideNav.childCount).map { sideNav.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        val step = Ui.dp(pl.iptv.tv.ui.theme.AppNavRail.ITEM_H.value.toInt() + 6)
        while (visible().size * step > avail) visible().lastOrNull { it.getTag(R.id.row_key) == "app" }?.let { it.visibility = View.GONE } ?: break
    }

    /**
     * Niski ekran (telefon w poziomie): przyciski menu bocznego nie mieszczą się w kolumnie – same ikony (podpis zostaje
     * w opisie dostępności), a skróty aplikacji, które dalej się nie mieszczą, są ukrywane. Na TV kolumna mieści wszystko – bez zmian.
     */
    private fun fitSideNav() {
        if (obsidianMode) { fitObsidianRail(); return }
        if (sideNav.orientation != android.widget.LinearLayout.VERTICAL) return
        sideNav.post {
            val avail = sideNav.height - sideNav.paddingTop - sideNav.paddingBottom
            fun visible() = (0 until sideNav.childCount).map { sideNav.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
            fun need() = visible().sumOf { it.measuredHeight + (it.layoutParams as ViewGroup.MarginLayoutParams).let { lp -> lp.topMargin + lp.bottomMargin } }
            if (avail <= 0 || need() <= avail) return@post
            visible().filterIsInstance<TextView>().forEach { b ->
                if (b.text.isNotEmpty()) { b.contentDescription = b.text; b.text = "" }
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1f)          // pusta linia tekstu bez wysokości
                // mniejsza ikona i odstępy, bez minimalnej wysokości stylu przycisku
                b.compoundDrawables[1]?.let { d -> d.setBounds(0, 0, Ui.dp(22), Ui.dp(22)); b.setCompoundDrawables(null, d, null, null) }
                b.compoundDrawablePadding = 0; b.minHeight = 0; b.minimumHeight = 0
                b.setPadding(b.paddingLeft, Ui.dp(5), b.paddingRight, Ui.dp(5))
                (b.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = Ui.dp(3)
            }
            sideNav.measure(View.MeasureSpec.makeMeasureSpec(sideNav.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(sideNav.height, View.MeasureSpec.EXACTLY))
            // dalej za mało miejsca – ostatnie skróty aplikacji ukryte (dostępne w ◄◄ odtwarzacza i w Aplikacjach)
            while (need() > avail) visible().lastOrNull { it.getTag(R.id.row_key) == "app" }?.let { it.visibility = View.GONE } ?: break
            sideNav.requestLayout()
        }
    }

    private fun styleSideNav() {
        mapOf(R.id.btnEpgSearch to R.drawable.ic_n_epg, R.id.btnSearch to R.drawable.ic_n_search, R.id.btnEditor to R.drawable.ic_n_edit,
            R.id.btnSettings to R.drawable.ic_n_settings, R.id.btnApps to R.drawable.ic_n_apps, R.id.btnSTube to R.drawable.ic_n_stube, R.id.btnStremio to R.drawable.ic_n_stremio)
            .forEach { (id, ic) -> findViewById<TextView>(id)?.let { Ui.iconOnTop(it, ic) } }
    }

    override fun applyTheme() {
        super.applyTheme()
        styleSideNav()
        navRail?.styleItems()                  // (7.17) ikony po lewej, pigułki paska nawigacji
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
            I18n.makeToast(this@MainActivity, "Program TV jest zbyt duży dla tego urządzenia – wczytywanie automatyczne wstrzymane. " +
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
            .onFailure { I18n.makeToast(this@MainActivity, "EPG: ${it.message}", Toast.LENGTH_LONG).show() }
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
        (tv.parent as? View)?.let { p -> p.requestLayout(); (p.parent as? View)?.requestLayout() }
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
        // STube tylko w wersji pełnej (wersja testowa dla testerów – bez YouTube)
        findViewById<View>(R.id.btnSTube).visibility = if (pl.iptv.tv.BuildConfig.FULL_EDITION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnStremio).visibility = if (pl.iptv.tv.BuildConfig.FULL_EDITION) View.VISIBLE else View.GONE
        appShortcuts()
        fitSideNav()
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
        // film STube gra w tle – powrót do menu bez przełączania na telewizję (dźwięk gra dalej, pasek „Teraz odtwarzane”)
        if (YtEngine.active && YtEngine.player?.playWhenReady == true && Prefs.startAction in listOf(StartActions.LAST_CHANNEL, StartActions.CHANNEL)) return
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

    private fun applyGroup(g: String) {
        val changed = appliedGroup != g
        appliedGroup = g; channelAdapter.items = Repository.channelsFor(g)
        // (7.6) nowa kategoria od pierwszego kanału (dawniej lista zostawała w miejscu przewinięcia poprzedniej), z krótkim przenikaniem
        if (changed) {
            channelsRv.scrollToPosition(0)
            if (!Prefs.lowPerf) { channelsRv.alpha = 0.35f; channelsRv.animate().alpha(1f).setDuration(140).withLayer().start() }
        }
    }

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
            else -> I18n.makeToast(this, "Ten program nie jest dostępny w archiwum", Toast.LENGTH_SHORT).show()
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

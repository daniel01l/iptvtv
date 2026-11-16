package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

/**
 * STube – przeglądanie YouTube na TV: wyszukiwarka u góry i poziome rzędy (Dla Ciebie, Na czasie, Muzyka, Subskrypcje).
 * Dane: YouTube Data API v3 ([YouTubeRepository]); odtwarzanie i wyszukiwanie zewnętrzne: SmartTube.
 * Pilot: ▲▼ między rzędami (powrót na ostatnio zaznaczony kafelek rzędu), ◄► w rzędzie (bez wypadania fokusu na końcach),
 * ▼ z wyszukiwarki do pierwszego rzędu, ▲ z pierwszego rzędu do wyszukiwarki.
 */
class STubeActivity : BaseActivity() {
    private lateinit var query: EditText
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
        searchQuery = savedInstanceState?.getString("q")?.ifBlank { null }
        build()
        // pierwszy fokus: pierwszy kafelek (wyszukiwarka jest o jedno ▲ wyżej, klawiatura nie wyskakuje od razu)
        focusRow(0, fallback = query)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("q", searchQuery) }

    override fun onResume() {
        super.onResume()
        // powrót z Ustawień: logowanie / wylogowanie / zmiana źródła → nowe rzędy
        if (builtFor != stateKey()) build()
    }

    private fun stateKey() = "${YouTubeTokens.loggedIn}|${YouTubeRepository.source}|${pl.iptv.tv.data.Prefs.ytRegion}|${searchQuery.orEmpty()}"

    private fun openAccount() = startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", SettingsActivity.YOUTUBE_TAB))

    private fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) { toast("Wpisz frazę do wyszukania"); return }
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
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
        rowViews.clear(); rowsBox.removeAllViews()
        rows.forEachIndexed { i, r -> rowsBox.addView(rowView(r, i)) }
        Ui.style(rowsBox)
        rows.forEach { load(it, null, force) }
    }

    private fun rowView(r: STubeRow, index: Int): View {
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
        r.adapter.onLongClick = { t -> tileMenu(t) }
        r.adapter.onFocus = { pos ->
            // ostatnie kafelki zaznaczone → następna strona
            if (pos >= r.tiles.size - 4 && r.next != null && !r.loading) load(r, r.next, false)
            // zaznaczony rząd w całości na ekranie (tytuł nad kafelkami też)
            scroll.post { scroll.smoothScrollTo(0, (box.top - Ui.dp(6)).coerceAtLeast(0)) }
        }
        box.addView(rv, LinearLayout.LayoutParams(-1, -2))
        rowViews += rv
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
                statusOf(r)?.let { s -> s.visibility = if (r.tiles.isEmpty()) View.VISIBLE else View.GONE; s.text = "Brak filmów" }
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

    private fun onTile(r: STubeRow, t: STubeTile) {
        when (t) {
            // wbudowany odtwarzacz (bez reklam, SponsorBlock); wyłączony w Ustawieniach – od razu SmartTube
            is STubeTile.Video -> if (pl.iptv.tv.data.Prefs.stubeInternal) playInApp(t.v) else SmartTubeIntegrationHelper.play(this, t.v.url)
            is STubeTile.Channel -> SmartTubeIntegrationHelper.play(this, "https://www.youtube.com/channel/${t.c.id}")
            is STubeTile.Action -> when (t.id) {
                STubeScreen.ACT_LOGIN -> openAccount()
                STubeScreen.ACT_SMARTTUBE_SEARCH -> searchQuery?.let { SmartTubeIntegrationHelper.search(this, it) }
                else -> { r.tiles.clear(); r.adapter.notifyDataSetChanged(); statusOf(r)?.text = "Wczytywanie…"; load(r, null, true) }
            }
        }
    }

    private fun playInApp(v: pl.iptv.tv.data.YtVideo) = startActivity(YtPlayerActivity.intent(this, v.id, v.title))

    private fun tileMenu(t: STubeTile) {
        val v = (t as? STubeTile.Video)?.v ?: return
        Dialogs.menu(this, v.title, listOf(
            "▶ Odtwórz w aplikacji" to { playInApp(v) },
            "▶ Odtwórz w SmartTube" to { SmartTubeIntegrationHelper.play(this, v.url); Unit },
            "▶ Odtwórz w YouTube" to { SmartTubeIntegrationHelper.playInYouTube(this, v.url); Unit },
            "🔎 Więcej z kanału: ${v.channel}" to { query.setText(v.channel); search(v.channel) },
        ))
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
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && inside(f, R.id.stSearchRow)) {
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

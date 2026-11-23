package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Reminder
import pl.iptv.tv.data.Reminders
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.SeriesRule
import pl.iptv.tv.reminders.ReminderScheduler

/** Wyszukiwarka w programie TV (wszystkie kanały) + lista przypomnień. */
class EpgSearchActivity : BaseActivity() {
    override val x25Root = true
    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_TAB = "tab"
        const val TAB_SEARCH = 0
        const val TAB_REMINDERS = 1
    }

    /** Wiersz listy: wynik wyszukiwania, przypomnienie albo reguła serii. */
    sealed class Row {
        data class Hit(val ch: Channel, val p: Programme) : Row()
        data class Rem(val r: Reminder, val ch: Channel?, val p: Programme) : Row()
        data class Rule(val rule: SeriesRule) : Row()
    }

    private lateinit var query: EditText
    private lateinit var info: TextView
    private lateinit var results: RecyclerView
    private lateinit var searchBox: View
    private val adapter = ResultAdapter()
    private var tab = TAB_SEARCH
    private var filter = Repository.When.ALL
    private var job: Job? = null

    /** Telefon w pionie: bez tytułu w nagłówku, filtry w przewijanym rzędzie, węższe marginesy (TV bez zmian). */
    private fun fitPortrait() {
        content.setPadding(Ui.dp(10), Ui.dp(12), Ui.dp(10), Ui.dp(8))
        val header = findViewById<View>(R.id.tabSearch).parent as android.view.ViewGroup
        header.getChildAt(0).visibility = View.GONE
        header.addView(View(this), 0, android.widget.LinearLayout.LayoutParams(0, 1, 1f))
        val row = findViewById<View>(R.id.fAll).parent as android.view.ViewGroup
        val box = row.parent as android.view.ViewGroup
        val i = box.indexOfChild(row); box.removeView(row)
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false
            addView(row, android.view.ViewGroup.LayoutParams(-2, -2)) }, i, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        (row.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(R.layout.activity_epg_search)
        if (resources.configuration.screenWidthDp < 600 && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) fitPortrait()
        query = findViewById(R.id.query)
        info = findViewById(R.id.info)
        results = findViewById(R.id.results)
        searchBox = findViewById(R.id.searchBox)
        results.layoutManager = LinearLayoutManager(this); results.adapter = adapter

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.tabSearch).setOnClickListener { setTab(TAB_SEARCH) }
        findViewById<View>(R.id.tabReminders).setOnClickListener { setTab(TAB_REMINDERS) }
        mapOf(R.id.fAll to Repository.When.ALL, R.id.fArchive to Repository.When.ARCHIVE,
            R.id.fNow to Repository.When.NOW, R.id.fUpcoming to Repository.When.UPCOMING).forEach { (id, w) ->
            findViewById<View>(id).setOnClickListener { filter = w; updateFilterButtons(); search(0) }
        }
        findViewById<View>(R.id.fDesc).setOnClickListener { Prefs.epgSearchInDesc = !Prefs.epgSearchInDesc; updateFilterButtons(); search(0) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (tab == TAB_SEARCH) search() }
        })
        query.setOnEditorActionListener { _, _, _ -> search(0); results.post { results.getChildAt(0)?.requestFocus() }; true }

        intent.getStringExtra(EXTRA_QUERY)?.let { query.setText(it); query.setSelection(it.length) }
        updateFilterButtons()
        setTab(intent.getIntExtra(EXTRA_TAB, TAB_SEARCH))
        if (Repository.epg.programmes.isEmpty()) info.text = "Brak programu TV – wczytaj EPG w ustawieniach."
    }

    override fun onResume() { super.onResume(); if (tab == TAB_REMINDERS) showReminders() else adapter.notifyDataSetChanged() }

    private fun setTab(t: Int) {
        tab = t
        findViewById<View>(R.id.tabSearch).isActivated = t == TAB_SEARCH
        findViewById<View>(R.id.tabReminders).isActivated = t == TAB_REMINDERS
        searchBox.visibility = if (t == TAB_SEARCH) View.VISIBLE else View.GONE
        if (t == TAB_SEARCH) { search(0); query.requestFocus() } else showReminders()
    }

    private fun updateFilterButtons() {
        mapOf(R.id.fAll to Repository.When.ALL, R.id.fArchive to Repository.When.ARCHIVE,
            R.id.fNow to Repository.When.NOW, R.id.fUpcoming to Repository.When.UPCOMING).forEach { (id, w) ->
            findViewById<View>(id).isActivated = filter == w
        }
        findViewById<TextView>(R.id.fDesc).text = if (Prefs.epgSearchInDesc) "☑ Także w opisach" else "☐ Także w opisach"
    }

    private fun search(debounce: Long = 300) {
        job?.cancel()
        val q = query.text.toString()
        job = lifecycleScope.launch {
            if (debounce > 0) delay(debounce)
            if (q.trim().length < 2) { adapter.items = emptyList(); info.text = "Wpisz co najmniej 2 znaki"; return@launch }
            info.text = "Szukanie…"
            val inDesc = Prefs.epgSearchInDesc
            val hits = withContext(Dispatchers.Default) { Repository.searchProgrammes(q, inDesc, filter) }
            adapter.items = hits.map { Row.Hit(it.channel, it.programme) }
            info.text = if (hits.isEmpty()) "Nic nie znaleziono" else "Znaleziono: ${hits.size}" +
                (if (hits.size >= 400) " (pokazano pierwsze 400)" else "") + "  •  OK – opcje (oglądaj, przypomnij, następny odcinek)"
            val now = System.currentTimeMillis()
            val firstUpcoming = hits.indexOfFirst { it.programme.end > now }.coerceAtLeast(0)
            (results.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(firstUpcoming, 0)
        }
    }

    private fun showReminders() {
        val now = System.currentTimeMillis()
        val rules = Reminders.rules().map { Row.Rule(it) }
        val rems = Reminders.all().filter { it.end > now }.map { r ->
            val ch = Repository.byKey(r.channelKey)
            val p = ch?.let { c -> Repository.programmes(c).firstOrNull { it.start == r.start } }
                ?: Programme(r.start, r.end, r.title, subTitle = r.subTitle, episode = r.episode)
            Row.Rem(r, ch, p)
        }
        adapter.items = rules + rems
        info.text = if (rules.isEmpty() && rems.isEmpty())
            "Brak przypomnień. Ustaw je w menu programu (OK na nadchodzącym programie)."
        else "Serie: ${rules.size}  •  Przypomnienia: ${rems.size}  •  wyprzedzenie: ${Prefs.reminderLead} min"
        results.post { results.getChildAt(0)?.requestFocus() }
    }

    private fun play(ch: Channel, p: Programme?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)
            if (p != null) { putExtra(PlayerActivity.EXTRA_START, p.start); putExtra(PlayerActivity.EXTRA_END, p.end) }
        })
    }

    private fun refresh() { if (tab == TAB_REMINDERS) showReminders() else adapter.notifyDataSetChanged() }

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.Hit -> ProgrammeActions.menu(this, row.ch, row.p, ::play, channelItem = true, onChanged = ::refresh)
            is Row.Rem -> {
                val items = mutableListOf<Pair<String, () -> Unit>>(
                    "🔕 Usuń przypomnienie" to { Reminders.remove(row.r.channelKey, row.r.start); ReminderScheduler.schedule(this); refresh() })
                row.ch?.let { ch -> items += "☰ Opcje programu" to { ProgrammeActions.menu(this, ch, row.p, ::play, channelItem = true, onChanged = ::refresh) } }
                Dialogs.menu(this, row.p.fullTitle, items)
            }
            is Row.Rule -> Dialogs.menu(this, "Seria: ${row.rule.title}", listOf(
                "🔕 Nie przypominaj o tej serii" to { Reminders.removeRule(row.rule.key); ReminderScheduler.schedule(this); refresh() },
                "🔎 Pokaż odcinki w programie" to { query.setText(row.rule.title); setTab(TAB_SEARCH) },
            ))
        }
    }

    inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.VH>() {
        var items: List<Row> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val picon: ImageView = v.findViewById(R.id.picon)
            val whenTv: TextView = v.findViewById(R.id.`when`)
            val title: TextView = v.findViewById(R.id.title)
            val sub: TextView = v.findViewById(R.id.sub)
            val badge: TextView = v.findViewById(R.id.badge)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_epg_result, p, false); Ui.style(v); return VH(v)
        }
        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = items[pos]
            val now = System.currentTimeMillis()
            h.itemView.setOnClickListener { onRowClick(row) }
            h.itemView.setOnLongClickListener { onRowClick(row); true }
            when (row) {
                is Row.Rule -> {
                    h.picon.setImageResource(R.drawable.ic_tv)
                    h.whenTv.text = "Przypominanie o wszystkich odcinkach"
                    h.title.text = "🔁 ${row.rule.title}"
                    h.sub.text = row.rule.channelKey?.let { Repository.byKey(it)?.name?.let { n -> "tylko kanał $n" } } ?: "na wszystkich kanałach"
                    h.badge.text = ""
                }
                is Row.Hit -> bindProg(h, row.ch, row.p, now)
                is Row.Rem -> if (row.ch != null) bindProg(h, row.ch, row.p, now) else {
                    h.picon.setImageResource(R.drawable.ic_tv)
                    h.whenTv.text = "${Fmt.shortDay(row.p.start)} ${Fmt.time(row.p.start)} • kanał niedostępny"
                    h.title.text = row.p.title; h.sub.text = ""; h.badge.text = "🔔"; h.badge.setTextColor(Ui.accent)
                }
            }
        }

        private fun bindProg(h: VH, ch: Channel, p: Programme, now: Long) {
            h.picon.loadPicon(ch)
            h.whenTv.text = "${Fmt.shortDay(p.start)} ${Fmt.time(p.start)}–${Fmt.time(p.end)} • ${ch.name}"
            h.title.text = p.title
            h.sub.text = listOf(p.episode, p.subTitle).filter { it.isNotBlank() }.joinToString(" – ")
                .ifEmpty { p.desc.take(120) }
            when {
                p.isNow(now) && pl.iptv.tv.data.isActualLiveBroadcast(p) -> { h.badge.text = "● NA ŻYWO"; h.badge.setTextColor(Ui.live) }
                p.isNow(now) -> h.badge.text = ""
                p.end <= now && Repository.isInArchive(ch, p, now) -> { h.badge.text = "⟲ archiwum"; h.badge.setTextColor(Ui.archive) }
                p.end <= now -> { h.badge.text = "minął"; h.badge.setTextColor(Ui.textColors(Ui.text2)) }
                Reminders.isSet(ch, p) -> { h.badge.text = "🔔"; h.badge.setTextColor(Ui.accent) }
                else -> h.badge.text = ""
            }
        }
    }
}

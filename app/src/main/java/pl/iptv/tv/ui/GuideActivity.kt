package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Reminders
import pl.iptv.tv.data.Repository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Przewodnik EPG: siatka kanały × czas, 7 dni wstecz i naprzód.
 * Okno czasowe przesuwa się pilotem (◄ ► na brzegu siatki), przyciskami lub gestem.
 */
class GuideActivity : BaseActivity() {
    override val x25Root = true
    companion object {
        const val EXTRA_CHANNEL = "ch"
        const val EXTRA_FROM_PLAYER = "from_player"
        private const val MIN = 60_000L
        private const val STEP = 30 * MIN
        private const val DAYS = 7
    }

    override fun finish() {
        super.finish()
        // powrót do odtwarzacza: to samo krótkie zanikanie co przy wejściu
        @Suppress("DEPRECATION") if (intent.getBooleanExtra(EXTRA_FROM_PLAYER, false)) overridePendingTransition(R.anim.hold, R.anim.fade_out)
    }

    private lateinit var rows: RecyclerView
    private lateinit var timeRow: LinearLayout
    private lateinit var dayLabel: TextView
    private val adapter = RowAdapter()

    private var group = Repository.ALL
    private var windowMin = 120
    private var winStart = 0L
    private val winEnd get() = winStart + windowMin * MIN
    private val minTime get() = floor30(System.currentTimeMillis() - DAYS * 86_400_000L)
    private val maxTime get() = System.currentTimeMillis() + DAYS * 86_400_000L

    /** Czas i wiersz zaznaczonej komórki – do odtworzenia fokusu po przesunięciu okna. */
    private var focusTime = 0L
    private var focusRow = 0

    private fun floor30(t: Long) = t / STEP * STEP

    private val d get() = resources.displayMetrics.density
    private fun px(dp: Int) = (dp * d + 0.5f).toInt()
    private val phone get() = resources.configuration.screenWidthDp < 700
    private var rowHeight = 0
    private var chColWidth = 0
    private var selectedCell: View? = null

    /** Szerokość kolumny kanałów i zakres czasu wg ustawień wyglądu. */
    private fun applyLook() {
        windowMin = Prefs.guideWindow
        chColWidth = px(when (Prefs.guideChannelCol) { 1 -> if (phone) 64 else 90; 2 -> if (phone) 110 else 150; else -> if (phone) 116 else 166 })
        findViewById<View>(R.id.timeSpacer).layoutParams.width = chColWidth + px(4)
        val desc = findViewById<TextView>(R.id.dDesc)
        findViewById<View>(R.id.descPanel).visibility = if (Prefs.guideDesc == 0) View.GONE else View.VISIBLE
        // stała wysokość opisu – panel nie „skacze” przy przechodzeniu między programami
        val lines = when (Prefs.guideDesc) { 1 -> 2; 2 -> 4; else -> 7 }
        desc.minLines = lines; desc.maxLines = lines
        findViewById<View>(R.id.nowLine).setBackgroundColor(Ui.live)
        computeRowHeight()
    }

    private fun computeRowHeight() {
        val h = rows.height - rows.paddingTop - rows.paddingBottom
        rowHeight = if (h > 0) h / Prefs.guideRows.coerceAtLeast(1) - px(3) else px(54)
    }

    /** Pionowa linia aktualnej godziny. */
    private fun updateNowLine() {
        val line = findViewById<View>(R.id.nowLine)
        val now = System.currentTimeMillis()
        val cellsW = rows.width - rows.paddingLeft - rows.paddingRight - chColWidth - px(4)
        if (!Prefs.guideNowLine || now !in winStart until winEnd || cellsW <= 0) { line.visibility = View.GONE; return }
        line.visibility = View.VISIBLE
        line.translationX = rows.paddingLeft + chColWidth + px(4) + (now - winStart).toFloat() / (winEnd - winStart) * cellsW
    }

    private val minuteTick = object : Runnable {
        override fun run() {
            updateNowLine()
            val n = adapter.itemCount
            if (n > 0) { if (focusRow > 0) adapter.notifyItemRangeChanged(0, focusRow); if (focusRow + 1 < n) adapter.notifyItemRangeChanged(focusRow + 1, n - focusRow - 1) }
            // wyniki na żywo przy trwających audycjach (mecz) – pobierane także w przewodniku
            if (pl.iptv.tv.data.Scores.due()) refreshScores()
            rows.postDelayed(this, 60_000)
        }
    }
    override fun onPause() { super.onPause(); if (!redirected) rows.removeCallbacks(minuteTick) }

    private var scoresJob: kotlinx.coroutines.Job? = null
    private fun refreshScores() {
        if (scoresJob?.isActive == true) return
        scoresJob = lifecycleScope.launch {
            if (pl.iptv.tv.data.Scores.refreshIfDue()) { val n = adapter.itemCount
                if (n > 0) { if (focusRow > 0) adapter.notifyItemRangeChanged(0, focusRow); if (focusRow + 1 < n) adapter.notifyItemRangeChanged(focusRow + 1, n - focusRow - 1) } }
        }
    }

    private var redirected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // telefon w pionie: lista programu zamiast siatki (TV i poziom – siatka bez zmian)
        if (GuideListActivity.isPortraitPhone(this)) {
            redirected = true
            startActivity(Intent(this, GuideListActivity::class.java).putExtras(intent)); finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0); return
        }
        setThemedContent(R.layout.activity_guide)
        rows = findViewById(R.id.rows)
        timeRow = findViewById(R.id.timeRow)
        dayLabel = findViewById(R.id.dayLabel)
        rows.layoutManager = LinearLayoutManager(this)
        rows.adapter = adapter
        rows.itemAnimator = null
        applyLook()
        findViewById<View>(R.id.btnLook).setOnClickListener { GuideLook.menu(this) { applyLook(); adapter.notifyDataSetChanged(); render() } }
        // po zmianie wysokości siatki przelicz wiersze (poza bieżącym układem)
        rows.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) rows.post { val old = rowHeight; computeRowHeight(); if (old != rowHeight) { adapter.notifyDataSetChanged(); restoreFocus() } }
        }

        val now = System.currentTimeMillis()
        winStart = floor30(now - 30 * MIN)
        focusTime = now
        val key = intent.getStringExtra(EXTRA_CHANNEL) ?: Prefs.lastChannel
        findViewById<View>(R.id.btnGroup).setOnClickListener { chooseGroup() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { shift(-2 * STEP) }
        findViewById<View>(R.id.btnNext).setOnClickListener { shift(2 * STEP) }
        findViewById<View>(R.id.btnDayPrev).setOnClickListener { shift(-86_400_000L) }
        findViewById<View>(R.id.btnDayNext).setOnClickListener { shift(86_400_000L) }
        findViewById<View>(R.id.btnNow).setOnClickListener { goNow() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { startActivity(Intent(this, EpgSearchActivity::class.java)) }
        findViewById<View>(R.id.btnReminders).setOnClickListener {
            startActivity(Intent(this, EpgSearchActivity::class.java).putExtra(EpgSearchActivity.EXTRA_TAB, EpgSearchActivity.TAB_REMINDERS))
        }
        findViewById<View>(R.id.btnSearch).visibility = if (Prefs.epgSearchEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnReminders).visibility = if (Prefs.remindersEnabled) View.VISIBLE else View.GONE

        // WSTECZ w siatce = przyciski u góry (szybki dostęp), drugi raz = wyjście
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inGrid()) { focusTop(); return }
                isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true
            }
        })
        loadGroup()
        focusRow = adapter.items.indexOfFirst { it.key == key }.coerceAtLeast(0)
        render()
        if (Repository.epg.programmes.isEmpty()) findViewById<TextView>(R.id.dTitle).text = "Brak programu TV – wczytaj EPG w ustawieniach."
    }

    override fun onResume() {
        super.onResume()
        if (redirected) return
        val inGrid = window.decorView.findFocus().let { it == null || it === sink || (it.getTag(R.id.guide_cell) != null || it.id == R.id.chCell) }
        if (inGrid) parkFocus()
        adapter.notifyDataSetChanged(); rows.post(minuteTick)
        if (inGrid && adapter.itemCount > 0) restoreFocus()
    }

    private fun loadGroup() {
        adapter.items = Repository.channelsFor(group).ifEmpty { group = Repository.ALL; Repository.channels }
        findViewById<TextView>(R.id.btnGroup).text = "☰ $group"
    }

    private fun chooseGroup() {
        val g = Repository.groups()
        Dialogs.choice(this, "Grupa kanałów", g, g.indexOf(group)) { group = g[it]; loadGroup(); focusRow = 0; render() }
    }

    private fun goNow() { winStart = floor30(System.currentTimeMillis() - 30 * MIN); focusTime = System.currentTimeMillis(); render() }

    /** Przesuwa okno czasowe w granicach 7 dni wstecz / naprzód. */
    private fun shift(delta: Long, newFocusTime: Long = focusTime + delta) {
        val target = (winStart + delta).coerceIn(minTime, floor30(maxTime) - windowMin * MIN)
        if (target == winStart) return
        winStart = target
        focusTime = newFocusTime.coerceIn(winStart, winEnd - 1)
        render()
    }

    private val sink by lazy { findViewById<View>(R.id.focusSink) }

    private fun inGrid(): Boolean {
        val f = window.decorView.findFocus() ?: return false
        return f === sink || f.getTag(R.id.guide_cell) != null || f.id == R.id.chCell
    }

    private fun focusTop() { findViewById<View>(R.id.btnGroup).requestFocus() }

    // cyfry: skok do kanału o tym numerze
    private var numberInput = ""
    private val applyNumber = Runnable {
        val n = numberInput.toIntOrNull(); numberInput = ""
        val i = adapter.items.indexOfFirst { it.number == n }
        if (i >= 0) { parkFocus(); focusRow = i; restoreFocus() }
        else I18n.makeToast(this, "Brak kanału nr $n w tej grupie", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Przed przebudową wierszy fokus czeka na niewidocznym elemencie – bez przeskoku na górne przyciski („☰ Wszystkie”). */
    private fun parkFocus() {
        val f = window.decorView.findFocus() ?: return
        var x: View? = f
        while (x != null && x !== rows) x = x.parent as? View
        if (x === rows) sink.requestFocus()
    }

    private fun render() {
        parkFocus()
        dayLabel.text = Fmt.day(winStart) + "  ${Fmt.time(winStart)}–${Fmt.time(winEnd)}"
        timeRow.removeAllViews()
        val tick = if (windowMin > 180) 2 * STEP else STEP
        var t = winStart
        while (t < winEnd) {
            timeRow.addView(TextView(this).apply {
                text = Fmt.time(t); textSize = 12f; tag = "text2"; setPadding(Ui.dp(4), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, (minOf(tick, winEnd - t) / MIN).toFloat()))
            t += tick
        }
        selectedCell = null
        rows.post { updateNowLine() }
        Ui.style(timeRow)
        adapter.notifyDataSetChanged()
        restoreFocus()
    }

    /** Zaznaczenie sąsiedniego wiersza od razu (bez chwilowego przeskoku na górne przyciski). */
    private fun focusRowNow(channelCell: Boolean) {
        val row = rows.findViewHolderForAdapterPosition(focusRow) as? RowAdapter.VH
        if (row == null) { parkFocus(); restoreFocus(channelCell = channelCell); return }
        if (channelCell) { row.chCell.requestFocus(); return }
        val cells = row.cells
        val target = (0 until cells.childCount).map { cells.getChildAt(it) }.firstOrNull { c ->
            val r = c.getTag(R.id.guide_cell) as? LongArray ?: return@firstOrNull false
            focusTime >= r[0] && focusTime < r[1]
        } ?: (0 until cells.childCount).map { cells.getChildAt(it) }.firstOrNull { it.isFocusable }
        (target ?: row.chCell).requestFocus()
    }

    private fun restoreFocus(tries: Int = 15, channelCell: Boolean = false) {
        val lm = rows.layoutManager as LinearLayoutManager
        if (focusRow < lm.findFirstVisibleItemPosition() || focusRow > lm.findLastVisibleItemPosition()) rows.scrollToPosition(focusRow)
        rows.postDelayed({
            val row = (rows.findViewHolderForAdapterPosition(focusRow) as? RowAdapter.VH)?.takeUnless { rows.hasPendingAdapterUpdates() || rows.isComputingLayout }
            if (row == null) { if (tries > 0) restoreFocus(tries - 1, channelCell) else if (sink.isFocused) findViewById<View>(R.id.btnGroup).requestFocus(); return@postDelayed }
            if (channelCell) { row.chCell.requestFocus(); return@postDelayed }
            val cells = row.cells
            val target = (0 until cells.childCount).map { cells.getChildAt(it) }.firstOrNull { c ->
                val r = c.getTag(R.id.guide_cell) as? LongArray ?: return@firstOrNull false
                focusTime >= r[0] && focusTime < r[1]
            } ?: (0 until cells.childCount).map { cells.getChildAt(it) }.firstOrNull { it.isFocusable }
            (target ?: row.chCell).requestFocus()
            val ch = adapter.items.getOrNull(focusRow) ?: return@postDelayed
            showDetails(ch, Repository.programmes(ch).firstOrNull { focusTime >= it.start && focusTime < it.end })
        }, 30)
    }

    private fun play(ch: Channel, p: Programme?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            putExtra(PlayerActivity.EXTRA_GROUP, group)
            if (p != null) { putExtra(PlayerActivity.EXTRA_START, p.start); putExtra(PlayerActivity.EXTRA_END, p.end) }
        })
    }

    private fun showDetails(ch: Channel, p: Programme?) {
        val title = findViewById<TextView>(R.id.dTitle)
        val time = findViewById<TextView>(R.id.dTime)
        val desc = findViewById<TextView>(R.id.dDesc)
        if (p == null) { title.text = ch.name; time.text = "Brak programu w tym czasie"; desc.text = "OK – oglądaj kanał"; return }
        val now = System.currentTimeMillis()
        title.text = p.fullTitle
        val status = when {
            p.isNow(now) && pl.iptv.tv.data.isActualLiveBroadcast(p) -> "● NA ŻYWO"
            p.isNow(now) -> "● teraz"
            p.end <= now && Repository.isInArchive(ch, p, now) -> "⟲ w archiwum"
            p.end <= now -> "minął"
            Reminders.isSet(ch, p) -> "🔔 przypomnienie ustawione"
            else -> ""
        }
        time.text = "${Fmt.shortDay(p.start)} ${Fmt.time(p.start)}–${Fmt.time(p.end)} • ${ch.name}" +
            (if (status.isNotEmpty()) " • $status" else "") + (if (p.category.isNotBlank()) " • ${p.category}" else "") +
            (pl.iptv.tv.data.Scores.liveForProgramme(ch, p, now)?.let { " • $it" } ?: "")
        val next = Repository.nextEpisodeIfReady(ch, p)
        desc.text = p.desc.ifBlank { "Brak opisu w programie TV." } +
            (if (next != null) "\n⏭ Następny odcinek: ${ProgrammeActions.whenText(next.channel, next.programme)}" else "")
    }

    // ---------------- pilot ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = window.decorView.findFocus()
            // w trakcie przebudowy (kilkadziesiąt ms) strzałki czekają
            if (f === sink && event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return true
            val range = f?.getTag(R.id.guide_cell) as? LongArray
            val parent = f?.parent as? ViewGroup
            if (range != null && parent != null) {
                val idx = parent.indexOfChild(f)
                val focusables = (0 until parent.childCount).filter { parent.getChildAt(it).isFocusable }
                val pos = focusables.indexOf(idx)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        pos > 0 -> { parent.getChildAt(focusables[pos - 1]).requestFocus(); return true }
                        winStart > minTime -> { shift(-STEP, winStart - MIN); return true }
                        else -> { ((parent.parent as? ViewGroup)?.findViewById<View>(R.id.chCell))?.requestFocus(); return true }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        pos in 0 until focusables.size - 1 -> { parent.getChildAt(focusables[pos + 1]).requestFocus(); return true }
                        else -> { shift(STEP, range[1] + MIN); return true }
                    }
                }
            }
            // ▲▼: ten sam czas w sąsiednim wierszu (lub kolumna kanałów)
            val onChannel = f?.id == R.id.chCell
            if ((range != null || onChannel) && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                val next = focusRow + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (next in adapter.items.indices) { focusRow = next; focusRowNow(onChannel); return true }
                if (next >= adapter.items.size) return true
                findViewById<View>(R.id.btnGroup).requestFocus(); return true          // ▲ z pierwszego wiersza
            }
            if (onChannel && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { findViewById<View>(R.id.btnGroup).requestFocus(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && f != null && (f.parent as? View)?.id == R.id.guideTop) { restoreFocus(); return true }
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                numberInput = (numberInput + (event.keyCode - KeyEvent.KEYCODE_0)).take(4)
                rows.removeCallbacks(applyNumber); rows.postDelayed(applyNumber, 1200); return true
            }
            // MENU / CH± : przyciski u góry / strona w górę i w dół
            if (event.keyCode == KeyEvent.KEYCODE_MENU && inGrid()) { focusTop(); return true }
            if ((event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) && inGrid()) {
                val page = Prefs.guideRows.coerceAtLeast(1)
                val next = (focusRow + if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) page else -page).coerceIn(0, adapter.itemCount - 1)
                if (next != focusRow) { parkFocus(); focusRow = next; restoreFocus() }
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { shift(-86_400_000L); return true }
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shift(86_400_000L); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- dotyk: przesunięcie w bok = przewijanie czasu ----------------

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 700) { shift(if (vx < 0) 2 * STEP else -2 * STEP); return true }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { gestures.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }

    // ---------------- wiersze ----------------

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        // stałe identyfikatory: przebudowa siatki nie odłącza wiersza z zaznaczeniem (bez utraty fokusu)
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = position.toLong()
        var items: List<Channel> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val chCell: View = v.findViewById(R.id.chCell)
            val num: TextView = v.findViewById(R.id.num)
            val picon: ImageView = v.findViewById(R.id.picon)
            val name: TextView = v.findViewById(R.id.name)
            val cells: LinearLayout = v.findViewById(R.id.cells)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_guide_row, p, false); Ui.style(v)
            return VH(v)
        }
        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ch = items[pos]
            if (rowHeight <= 0) computeRowHeight()
            h.itemView.layoutParams.height = rowHeight
            h.chCell.layoutParams.width = chColWidth
            val mode = Prefs.guideChannelCol
            h.picon.visibility = if (mode == 2 || mode == 3) View.GONE else View.VISIBLE
            h.name.visibility = if (mode == 1) View.GONE else View.VISIBLE
            h.num.visibility = if (mode == 3 || (mode == 0 && Prefs.showNumbers)) View.VISIBLE else View.GONE
            h.picon.layoutParams.width = px(if (mode == 1) 70 else 46)
            h.num.text = ch.number.toString()
            h.name.text = ch.name
            h.picon.loadPicon(ch)
            h.chCell.setOnClickListener { play(ch, null) }
            h.chCell.setOnFocusChangeListener { _, f -> if (f) { focusRow = h.bindingAdapterPosition; showDetails(ch, Repository.current(ch)) } }
            buildCells(h, ch)
        }

        private fun buildCells(h: VH, ch: Channel) {
            val cells = h.cells; this.cells = cells
            // komórki używane ponownie (bez tworzenia setek widoków przy przewijaniu – mniej pamięci, brak przycięć)
            val pool = ArrayDeque<TextView>(); val spacers = ArrayDeque<View>()
            for (i in 0 until cells.childCount) cells.getChildAt(i).let { if (it is TextView) pool += it else spacers += it }
            cellPool = pool
            // przebudowa wiersza z zaznaczoną komórką: fokus czeka na niewidocznym elemencie i wraca po przebudowie
            if (cells.hasFocus()) { sink.requestFocus(); rows.post { if (sink.isFocused) restoreFocus() } }
            cells.removeAllViews()
            val now = System.currentTimeMillis()
            val ws = winStart; val we = winEnd
            val progs = Repository.programmesIn(ch, ws, we)
            var cursor = ws
            fun spacer(until: Long) {
                if (until <= cursor) return
                cells.addView(spacers.removeFirstOrNull() ?: View(this@GuideActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ((until - cursor) / MIN).toFloat()))
                cursor = until
            }
            if (progs.isEmpty()) {
                // pusta komórka na cały wiersz – żeby pilot mógł przez nią przejść
                cells.addView(cell(ch, null, ws, we, now), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, windowMin.toFloat()))
                return
            }
            for (p in progs) {
                val s = maxOf(p.start, ws); val e = minOf(p.end, we)
                if (s < cursor) continue
                spacer(s)
                if (e <= s) continue
                cells.addView(cell(ch, p, s, e, now), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ((e - s) / MIN).toFloat()).apply {
                    marginEnd = when (Prefs.guideStyle) { 1 -> 0; 2 -> px(1); else -> px(2) }
                })
                cursor = e
            }
            spacer(we)
        }

        private lateinit var cells: LinearLayout
        private var cellPool = ArrayDeque<TextView>()
        private fun cell(ch: Channel, p: Programme?, s: Long, e: Long, now: Long): View {
            val reused = cellPool.removeFirstOrNull()
            val tv = reused ?: TextView(this@GuideActivity).apply {
                tag = "item nozoom"; isFocusable = true; isClickable = true
                gravity = Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Ui.dp(6), 0, Ui.dp(4), 0)
            }
            tv.setTag(R.id.guide_cell, longArrayOf(s, e))
            tv.alpha = 1f; tv.isActivated = false; tv.isSelected = false
            tv.setOnLongClickListener(null)
            if (p == null) {
                tv.text = "Brak programu – OK: oglądaj"
                tv.alpha = 0.6f
                tv.setOnClickListener { play(ch, null) }
            } else {
                val mark = when {
                    p.isNow(now) -> "● "
                    p.end <= now && Repository.isInArchive(ch, p, now) -> "⟲ "
                    p.start > now && Reminders.isSet(ch, p) -> "🔔 "
                    else -> ""
                }
                val base = mark + (if (Prefs.guideShowTime) "${Fmt.time(p.start)} " else "") + p.title
                // trwający mecz: wynik na żywo przed tytułem audycji (jak na liście kanałów)
                val live = pl.iptv.tv.data.Scores.liveForProgramme(ch, p, now)
                tv.text = if (live == null) base else android.text.SpannableStringBuilder(live).apply {
                    setSpan(android.text.style.ForegroundColorSpan(Ui.live), 0, length, 0)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
                    append("  ").append(base)
                }
                if (p.end <= now && !Repository.isInArchive(ch, p, now)) tv.alpha = 0.55f
                tv.isActivated = p.isNow(now)
                val open = { ProgrammeActions.menu(this@GuideActivity, ch, p, ::play, channelItem = true) { adapter.notifyDataSetChanged() } }
                // dotyk: 1. stuknięcie = opis, 2. = menu; pilot: OK = menu
                tv.setOnClickListener { v ->
                    if (v.isInTouchMode && selectedCell !== v) {
                        selectedCell?.isSelected = false; v.isSelected = true; selectedCell = v
                        focusRow = items.indexOf(ch).coerceAtLeast(0); showDetails(ch, p)
                    } else open()
                }
                tv.setOnLongClickListener { open(); true }
            }
            tv.setOnFocusChangeListener { v, f ->
                if (!f) return@setOnFocusChangeListener
                val r = v.getTag(R.id.guide_cell) as LongArray
                focusTime = r[0]
                focusRow = items.indexOf(ch).coerceAtLeast(0)
                showDetails(ch, p)
            }
            if (reused == null || tv.getTag(R.id.ui_style) == null || tv.getTag(R.id.zoom_listener) != Prefs.guideStyle) {
                tv.textSize = 13f; tv.setTag(R.id.ui_size, null)
                Ui.style(tv)
                tv.background = Ui.cellBg(Prefs.guideStyle)
                tv.setTag(R.id.zoom_listener, Prefs.guideStyle)
            }
            tv.maxLines = if (Prefs.guideStyle == 2) 1 else 2
            return tv
        }
    }
}

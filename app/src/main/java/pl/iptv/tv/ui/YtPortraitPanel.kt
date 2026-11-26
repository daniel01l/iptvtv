package pl.iptv.tv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * (7.22) Odtwarzacz STube na telefonie w pionie (9:16): pod przypiętym oknem wideo 16:9 – tytuł, kanał, przewijany w poziomie
 * pasek akcji (te same przyciski co w poziomie: prędkość, jakość, audio, napisy, dźwięk, okno, tylko dźwięk…) i trzy zakładki:
 * **Następne wideo** (bieżący + kolejka / propozycje – [Host.entries], dotknięcie – [Host.pick]), **Tekst** (tekst piosenki
 * zsynchronizowany z odtwarzaniem – [pl.iptv.tv.data.YtLyrics], podświetlenie i automatyczne przewijanie bieżącego wiersza)
 * i **Tryb muzyczny** ([Host.openMusic] – pełnoekranowy [YtMusicStage] w układzie pionowym: duża okładka, sterowanie w zasięgu
 * kciuka, tekst karaoke). Ten sam silnik i usługa ([YtEngine] / MediaSession) – zakładki niczego nie restartują.
 *
 * (7.23) Nad listą tylko dwie zakładki: **Następne wideo** i **Dodaj do kolejki** (wyszukiwarka utworów – [Host.search], wybór
 * wyniku – [Host.searchPicked]: arkusz „Dodaj” / „Dodaj i odtwórz następnie”, potem pole wyczyszczone). Tekst piosenki – z przycisku
 * „Tekst” na dolnym pasku ([toggleLyrics]), tryb muzyczny – przycisk „Muzyka”.
 *
 * Kolory wyłącznie z tokenów aktywnego motywu ([CardSheet.palette], [Ui]).
 */
class YtPortraitPanel(c: Context, private val host: Host) : LinearLayout(c) {
    interface Host {
        fun entries(): List<YtMusicStage.Entry>
        fun pick(e: YtMusicStage.Entry)
        fun openMusic()
        /** Pozycja odtwarzania (ms) – podświetlenie wiersza tekstu. */
        fun positionMs(): Long
        fun seekTo(ms: Long)
        /** Tekst bieżącego utworu (null – brak); wywołanie w tle. */
        suspend fun lyrics(): pl.iptv.tv.data.YtLyrics.Result?
        fun lyricDelayMs(): Long = 0L
        /** (7.23) Wyszukiwanie utworów do kolejki (w tle). */
        suspend fun search(q: String): List<pl.iptv.tv.data.YtVideo> = emptyList()
        /** (7.23) Dotknięty wynik wyszukiwania; [onAdded] – po dodaniu do kolejki (czyści zapytanie). */
        fun searchPicked(v: pl.iptv.tv.data.YtVideo, onAdded: () -> Unit) {}
    }

    companion object {
        /** Zakładki [TAB_NAMES]: 0 – Następne wideo, 1 – Dodaj do kolejki; [TAB_LYRICS] – tekst (bez zakładki, przycisk „Tekst”). */
        const val TAB_NEXT = 0; const val TAB_ADD = 1; const val TAB_LYRICS = 2
        val TAB_NAMES = listOf("Następne wideo", "Dodaj do kolejki")
        const val SEARCH_HINT = "Wyszukaj utwór do kolejki"
        const val SEARCH_DEBOUNCE_MS = 350L
        const val SEARCH_TAG = "yt_queue_search"
        const val TAG = "yt_portrait"
        const val TICK_MS = 300L
    }

    private val pal = CardSheet.palette()
    val titleView = TextView(c).apply { textSize = 18f; setTextColor(pal.text); tag = "keepcolor"; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val channelView = TextView(c).apply { textSize = 14f; setTextColor(pal.text2); tag = "keepcolor"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    /** Miejsce na pasek ikon odtwarzacza ([PortraitPlayerUi.bar]) – na dole panelu. */
    val actions = FrameLayout(c)
    private val tabViews = mutableListOf<TextView>()
    var tab = TAB_NEXT; private set
    private val body = FrameLayout(c)
    private val list = RecyclerView(c)
    private val lyricsScroll = ScrollView(c)
    private val lyricsBox = LinearLayout(c).apply { orientation = VERTICAL; setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), Ui.dp(120)) }
    /** (7.23) Wyszukiwarka „Dodaj do kolejki”: pole (klawiatura systemowa) i wyniki. */
    private val searchBox = LinearLayout(c).apply { orientation = VERTICAL; visibility = View.GONE }
    val searchField = android.widget.EditText(c).apply {
        hint = SEARCH_HINT; textSize = 16f; isSingleLine = true; tag = "keepcolor"; setTextColor(pal.text); setHintTextColor(pal.text2)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10))
        background = GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.text2, 0x22)); cornerRadius = Ui.dp(22).toFloat() }
        setTag(pl.iptv.tv.R.id.row_key, SEARCH_TAG)
    }
    private val searchStatus = TextView(c).apply { textSize = 14f; setTextColor(pal.text2); tag = "keepcolor"; setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), 0) }
    private val results = RecyclerView(c)
    /** Wyniki wyszukiwania (testy). */
    var searchResults: List<pl.iptv.tv.data.YtVideo> = emptyList(); private set
    private var searchJob: kotlinx.coroutines.Job? = null
    private val lyricsStatus = TextView(c).apply { textSize = 15f; setTextColor(pal.text2); tag = "keepcolor"; setPadding(Ui.dp(6), Ui.dp(12), Ui.dp(6), 0) }
    private var entries: List<YtMusicStage.Entry> = emptyList()
    private var lines: List<TextView> = emptyList(); private var times = LongArray(0); private var current = -1
    private var lyricsFor = ""
    /** Tekst wczytany (testy). */
    val lyricLines: Int get() = lines.size
    val currentLine: Int get() = current

    init {
        orientation = VERTICAL; tag = TAG; setBackgroundColor(Ui.bg or 0xFF000000.toInt())
        setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), 0)
        addView(titleView); addView(channelView, LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
        val tabs = LinearLayout(c).apply { clipChildren = false; clipToPadding = false; setPadding(0, Ui.dp(8), 0, Ui.dp(6)) }
        TAB_NAMES.forEachIndexed { i, n ->
            val t = MobilePortraitScaffold.chip(c, n, i == tab) { selectTab(i) }.apply { setTag(pl.iptv.tv.R.id.row_key, "yt_tab:$i") }
            tabViews += t; tabs.addView(t, LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) })
        }
        addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        list.apply { layoutManager = LinearLayoutManager(c); adapter = Adapter(); itemAnimator = null; clipToPadding = false; setPadding(0, 0, 0, Ui.dp(24)) }
        lyricsScroll.apply { isVerticalScrollBarEnabled = false; addView(lyricsBox); visibility = View.GONE }
        body.addView(list, FrameLayout.LayoutParams(-1, -1)); body.addView(lyricsScroll, FrameLayout.LayoutParams(-1, -1))
        results.apply { layoutManager = LinearLayoutManager(c); adapter = ResultsAdapter(); itemAnimator = null; clipToPadding = false; setPadding(0, Ui.dp(6), 0, Ui.dp(24)) }
        searchBox.addView(searchField, LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
        searchBox.addView(searchStatus, LayoutParams(-1, -2))
        searchBox.addView(results, LayoutParams(-1, 0, 1f))
        body.addView(searchBox, FrameLayout.LayoutParams(-1, -1))
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { scheduleSearch(SEARCH_DEBOUNCE_MS) }
        })
        searchField.setOnEditorActionListener { _, _, _ -> scheduleSearch(0); true }
        addView(body, LayoutParams(-1, 0, 1f))
        // (7.22) pasek ikon odtwarzacza w strefie kciuka – na dole panelu, nad paskiem nawigacji systemu
        addView(actions, LayoutParams(-1, -2).apply { topMargin = Ui.dp(4) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
            val n = runCatching { ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom }.getOrDefault(0)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, n); ins
        }
    }

    private val tick = object : Runnable { override fun run() { updateLine(); if (isAttachedToWindow && tab == TAB_LYRICS) postDelayed(this, TICK_MS) } }

    fun selectTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { k, v -> v.isSelected = k == i }
        list.visibility = if (i == TAB_NEXT) View.VISIBLE else View.GONE
        lyricsScroll.visibility = if (i == TAB_LYRICS) View.VISIBLE else View.GONE
        searchBox.visibility = if (i == TAB_ADD) View.VISIBLE else View.GONE
        if (i != TAB_ADD) hideKeyboard()
        removeCallbacks(tick)
        if (i == TAB_LYRICS) { loadLyrics(); post(tick) }
    }

    /** (7.23) Przycisk „Tekst” na dolnym pasku: tekst piosenki ↔ „Następne wideo”. */
    fun toggleLyrics() = selectTab(if (tab == TAB_LYRICS) TAB_NEXT else TAB_LYRICS)

    /** (7.23) Wyszukiwanie po [delayMs] od ostatniej zmiany pola; puste pole – brak wyników. */
    private fun scheduleSearch(delayMs: Long) {
        searchJob?.cancel()
        val q = searchField.text.toString().trim()
        if (q.isEmpty()) { searchResults = emptyList(); results.adapter?.notifyDataSetChanged(); searchStatus.text = ""; return }
        val owner = context as? androidx.lifecycle.LifecycleOwner ?: return
        searchJob = owner.lifecycleScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            searchStatus.text = "Szukanie…"
            val r = runCatching { host.search(q) }.getOrDefault(emptyList())
            if (searchField.text.toString().trim() != q) return@launch
            searchResults = r; results.adapter?.notifyDataSetChanged(); results.scrollToPosition(0)
            searchStatus.text = if (r.isEmpty()) "Brak wyników" else ""
        }
    }

    /** (7.23) Wynik dotknięty: arkusz wyboru; po dodaniu – pole puste, gotowe na kolejny utwór. */
    fun pickResult(v: pl.iptv.tv.data.YtVideo) = host.searchPicked(v) { clearSearch() }

    fun clearSearch() {
        searchJob?.cancel()
        searchField.setText("")
        searchResults = emptyList(); results.adapter?.notifyDataSetChanged(); searchStatus.text = ""
    }

    private fun hideKeyboard() {
        if (!searchField.hasFocus()) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
    }

    /** Nowy tytuł / kolejka – odświeżenie (wywoływane przy każdej zmianie utworu i kolejki). */
    fun bind(title: String, channel: String, videoId: String) {
        titleView.text = title; channelView.text = channel; channelView.visibility = if (channel.isBlank()) View.GONE else View.VISIBLE
        entries = host.entries(); list.adapter?.notifyDataSetChanged()
        // (7.23) inny utwór (także po powrocie z tła / odblokowaniu) – lista na faktycznie odtwarzanym utworze (pozycja 0)
        if (lyricsFor != videoId) list.scrollToPosition(0)
        if (lyricsFor != videoId) { lyricsFor = videoId; lines = emptyList(); times = LongArray(0); current = -1; lyricsBox.removeAllViews(); loaded = false
            if (tab == TAB_LYRICS) loadLyrics() }
    }

    private var loaded = false
    private var job: kotlinx.coroutines.Job? = null
    private fun loadLyrics() {
        if (loaded) return
        loaded = true
        lyricsBox.removeAllViews(); lyricsStatus.text = "Szukanie tekstu…"; lyricsBox.addView(lyricsStatus)
        val owner = context as? androidx.lifecycle.LifecycleOwner ?: return
        val forId = lyricsFor
        job?.cancel()
        job = owner.lifecycleScope.launch {
            val r = runCatching { host.lyrics() }.getOrNull()
            if (forId == lyricsFor) setLyrics(r)
        }
    }

    /** Tekst do wyświetlenia (także testy). */
    fun setLyrics(r: pl.iptv.tv.data.YtLyrics.Result?) {
        lyricsBox.removeAllViews(); current = -1
        if (r == null) { lines = emptyList(); lyricsStatus.text = "Brak tekstu dla tego utworu"; lyricsBox.addView(lyricsStatus); return }
        if (!r.isSynced) { lines = emptyList(); lyricsStatus.text = r.plain; lyricsStatus.setTextColor(pal.text); lyricsBox.addView(lyricsStatus); return }
        times = LongArray(r.synced.size) { r.synced[it].ms }
        lines = r.synced.map { l ->
            TextView(context).apply {
                text = l.text.ifBlank { "♪" }; textSize = 18f; tag = "keepcolor"; setTextColor(CardSheet.withAlpha(pal.text, 0x88)); setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(6), Ui.dp(8))
                isClickable = true; setOnClickListener { host.seekTo((l.ms + host.lyricDelayMs()).coerceAtLeast(0)) }
            }.also { lyricsBox.addView(it, LayoutParams(-1, -2)) }
        }
        updateLine(force = true)
    }

    /** Wiersz śpiewany teraz: akcent motywu, pogrubienie, płynne wyśrodkowanie. */
    fun updateLine(force: Boolean = false) {
        if (lines.isEmpty()) return
        val pos = host.positionMs() + 250 - host.lyricDelayMs()
        var i = -1
        for (k in times.indices) { if (times[k] <= pos) i = k else break }
        if (i == current && !force) return
        lines.getOrNull(current)?.apply { setTextColor(CardSheet.withAlpha(pal.text, 0x88)); setTypeface(null, android.graphics.Typeface.NORMAL); textSize = 18f }
        current = i
        val cur = lines.getOrNull(i) ?: return
        cur.setTextColor(pal.accent or 0xFF000000.toInt()); cur.setTypeface(null, android.graphics.Typeface.BOLD); cur.textSize = 21f
        lyricsScroll.post { lyricsScroll.smoothScrollTo(0, (cur.top - (lyricsScroll.height - cur.height) / 2).coerceAtLeast(0)) }
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); job?.cancel(); searchJob?.cancel(); super.onDetachedFromWindow() }

    /** (7.23) Wyniki wyszukiwarki „Dodaj do kolejki”: miniatura, tytuł, kanał; dotknięcie – [pickResult]. */
    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.VH>() {
        inner class VH(val root: LinearLayout, val thumb: ImageView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)
        override fun getItemCount() = searchResults.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val root = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) } }
            val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.text2, 0x26)) } }
            Ui.roundClip(thumb, Ui.dp(8).toFloat())
            root.addView(thumb, LayoutParams(Ui.dp(96), Ui.dp(54)).apply { marginEnd = Ui.dp(10) })
            val col = LinearLayout(c).apply { orientation = VERTICAL; isDuplicateParentStateEnabled = true }
            val t = TextView(c).apply { textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isDuplicateParentStateEnabled = true }
            val s = TextView(c).apply { textSize = 12f; tag = "text2"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isDuplicateParentStateEnabled = true }
            col.addView(t); col.addView(s)
            root.addView(col, LayoutParams(0, -2, 1f))
            root.addView(TextView(c).apply { text = "+"; textSize = 24f; setTextColor(pal.accent or 0xFF000000.toInt()); tag = "keepcolor"; setPadding(Ui.dp(10), 0, Ui.dp(6), 0) })
            val vh = VH(root, thumb, t, s)
            root.setOnClickListener { searchResults.getOrNull(vh.bindingAdapterPosition)?.let(::pickResult) }
            Ui.style(root)
            return vh
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val v = searchResults[position]
            h.title.text = v.title
            h.sub.text = listOf(v.channel, if (v.duration.isBlank()) "" else STubeScreen.duration(v.duration)).filter { it.isNotBlank() }.joinToString(" · ")
            coil.util.CoilUtils.dispose(h.thumb); h.thumb.setImageDrawable(null)
            if (v.thumb.isNotBlank()) h.thumb.load(STubeScreen.thumbUrl(v.thumb, Ui.dp(96))) { crossfade(false); size(Ui.dp(96), Ui.dp(54)) }
        }
    }

    /** Lista „Następne wideo”: miniatura 16:9, tytuł, kanał; bieżący wyróżniony (akcent). */
    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val root: LinearLayout, val thumb: ImageView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val root = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "item"; isFocusable = true; isClickable = true
                setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(4) } }
            val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.text2, 0x26)) } }
            Ui.roundClip(thumb, Ui.dp(8).toFloat())
            root.addView(thumb, LayoutParams(Ui.dp(128), Ui.dp(72)).apply { marginEnd = Ui.dp(10) })
            val col = LinearLayout(c).apply { orientation = VERTICAL; isDuplicateParentStateEnabled = true }
            val t = TextView(c).apply { textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isDuplicateParentStateEnabled = true }
            val s = TextView(c).apply { textSize = 12f; tag = "text2"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isDuplicateParentStateEnabled = true }
            col.addView(t); col.addView(s)
            root.addView(col, LayoutParams(0, -2, 1f))
            val vh = VH(root, thumb, t, s)
            root.setOnClickListener { entries.getOrNull(vh.bindingAdapterPosition)?.let { if (!it.current) host.pick(it) } }
            Ui.style(root)
            return vh
        }
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            h.title.text = e.title; h.sub.text = if (e.current) "▶ Teraz odtwarzane · " + e.artist else e.artist
            h.root.isActivated = e.current
            coil.util.CoilUtils.dispose(h.thumb); h.thumb.setImageDrawable(null)
            if (e.thumb.isNotBlank()) h.thumb.load(STubeScreen.thumbUrl(e.thumb, Ui.dp(128))) { crossfade(false); size(Ui.dp(128), Ui.dp(72)) }
        }
    }
}


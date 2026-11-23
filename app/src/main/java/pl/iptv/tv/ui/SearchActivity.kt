package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.ui.theme.ObsidianViews

/**
 * (7.17) Uniwersalna wyszukiwarka „Obsidian Stream”: szeroka szklana kapsuła (16 dp) z wyszukiwaniem głosowym
 * (RecognizerIntent), pigułki filtrów (Wszystko / Kanały TV / Program EPG / STube / Stremio) i osobne poziome rzędy wyników
 * dla każdego rodzaju treści.
 *
 * Zapytanie to [MutableStateFlow]: `debounce(300)` → `distinctUntilChanged()` → `flatMapLatest` – szybkie pisanie na
 * klawiaturze ekranowej nie uruchamia kolejnych wyszukiwań, a nowe zapytanie anuluje poprzednie (lista, EPG i sieć).
 * Wyniki z urządzenia (kanały, EPG) pojawiają się od razu, z sieci (STube, Stremio) – gdy przyjdą.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchActivity : BaseActivity() {
    companion object {
        const val EXTRA_QUERY = "query"
        const val DEBOUNCE_MS = 300L
        /** Filtr na starcie ([F_ALL] … [F_STREMIO]). */
        const val EXTRA_FILTER = "filter"
        const val F_ALL = 0; const val F_CHANNELS = 1; const val F_EPG = 2; const val F_STUBE = 3; const val F_STREMIO = 4
        val FILTERS = listOf("Wszystko", "Kanały TV", "Program EPG", "STube", "Stremio")
        /** Limit czasu jednej wtyczki / zapytania sieciowego. */
        const val NET_TIMEOUT_MS = 8_000L
        /** Źródło wyszukiwania STube (w testach podmieniane). */
        var stube: suspend (String) -> List<YtVideo> = { q -> YtKeyless.search(q) }
    }
    override val x25Root = true

    /** Wyniki jednego zapytania (rzędy budowane przyrostowo). */
    data class Results(
        val query: String = "",
        val channels: List<Channel> = emptyList(),
        val epg: List<Repository.Hit> = emptyList(),
        val stube: List<YtVideo> = emptyList(),
        val stremio: List<StremioMetaPreview> = emptyList(),
        val loading: Boolean = false,
    )

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(F_ALL)
    /** Ostatnio pokazane wyniki i liczba uruchomionych wyszukiwań (testy). */
    var results = Results(); private set
    var searches = 0; private set

    private lateinit var field: EditText
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val pills = mutableListOf<TextView>()
    val filterViews: List<TextView> get() = pills
    private val O = ObsidianViews

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { t -> field.setText(t); field.setSelection(t.length); query.value = t }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(build())
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let { field.setText(it); query.value = it }
        intent.getIntExtra(EXTRA_FILTER, F_ALL).takeIf { it in FILTERS.indices && it != F_ALL }?.let { setFilter(it) }
        field.post { field.requestFocus() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(query.debounce(DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged(), filter) { q, f -> q to f }
                    .distinctUntilChanged()
                    .flatMapLatest { (q, f) -> search(q, f) }
                    .collect { render(it) }
            }
        }
    }

    // ---------------- wyszukiwanie ----------------

    /** Wyniki zapytania [q] dla filtru [f]: najpierw z urządzenia, potem z sieci (równolegle); nowe zapytanie anuluje ten przepływ. */
    private fun search(q: String, f: Int): Flow<Results> = channelFlow {
        if (q.length < 2) { send(Results(q)); return@channelFlow }
        searches++
        var r = Results(q, loading = true)
        val local = withContext(Dispatchers.Default) {
            r.copy(channels = if (f == F_ALL || f == F_CHANNELS) Repository.search(q).filter { !it.hidden }.take(40) else emptyList(),
                epg = if (f == F_ALL || f == F_EPG) Repository.searchProgrammes(q, inDesc = false, w = Repository.When.ALL, limit = 40) else emptyList())
        }
        r = local; send(r)
        val stube = if ((f == F_ALL || f == F_STUBE) && pl.iptv.tv.BuildConfig.FULL_EDITION)
            async { runCatching { withTimeoutOrNull(NET_TIMEOUT_MS) { stube(q) } }.getOrNull().orEmpty().take(30) } else null
        val stremio = if (f == F_ALL || f == F_STREMIO) async { stremioSearch(q) } else null
        stube?.let { val v = it.await(); r = r.copy(stube = v); send(r) }
        stremio?.let { val v = it.await(); r = r.copy(stremio = v); send(r) }
        send(r.copy(loading = false))
    }

    /** Stremio: katalogi wyszukiwania włączonych wtyczek (filmy przed serialami), bez powtórzeń; wtyczki z błędem pomijane. */
    private suspend fun stremioSearch(q: String): List<StremioMetaPreview> = runCatching {
        val addons = StremioStore.enabledAddons()
        val cats = addons.flatMap { a -> a.searchCatalogs.map { a to it } }.sortedBy { (_, c) -> if (c.type == "movie") 0 else 1 }.take(4)
        kotlinx.coroutines.coroutineScope {
            cats.map { (a, c) -> async(Dispatchers.IO) { runCatching { withTimeoutOrNull(NET_TIMEOUT_MS) { StremioApi.catalog(a, c, q) } }.getOrNull().orEmpty() } }
                .flatMap { it.await() }
        }.distinctBy { it.type + it.id }.take(40)
    }.getOrDefault(emptyList())

    // ---------------- widok ----------------

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(48), Ui.dp(24), Ui.dp(40), Ui.dp(8)) }
        // szeroka szklana kapsuła z ikoną lupy i mikrofonem
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(18), 0, Ui.dp(8), 0)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(O.SURFACE_GLASS, O.SURFACE_ELEVATED))
                .apply { cornerRadius = Ui.dp(16).toFloat(); setStroke(Ui.dp(1), O.SPECULAR) }
        }
        bar.addView(ImageView(this).apply { setImageDrawable(PlayerIcon(PlayerIcon.SEARCH, android.content.res.ColorStateList.valueOf(O.TEXT2))) },
            LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)))
        field = EditText(this).apply {
            hint = "Szukaj kanałów, programów, filmów STube i Stremio…"; tag = "keepcolor"; setSingleLine(); textSize = 18f
            setTextColor(O.TEXT); setHintTextColor(O.TEXT2); background = null
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query.value = s?.toString().orEmpty() }
            })
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { hideKeyboard(); firstResult()?.requestFocus(); true } else false
            }
            setOnFocusChangeListener { _, f -> (bar.background as? android.graphics.drawable.GradientDrawable)?.setStroke(Ui.dp(if (f) 2 else 1), if (f) O.ACCENT else O.SPECULAR) }
        }
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        // wyszukiwanie głosowe Android TV (gdy urządzenie ma rozpoznawanie mowy)
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL").putExtra(RecognizerIntent.EXTRA_PROMPT, "Powiedz, czego szukasz")
        val mic = TextView(this).apply {
            tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; contentDescription = "Wyszukiwanie głosowe"
            setCompoundDrawables(PlayerIcon(PlayerIcon.MIC, O.pillText()).apply { setBounds(0, 0, Ui.dp(22), Ui.dp(22)) }, null, null, null)
            setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10)); background = O.pill()
            setOnClickListener { runCatching { voice.launch(speech) }.onFailure { I18n.makeToast(this@SearchActivity, "Wyszukiwanie głosowe jest niedostępne", android.widget.Toast.LENGTH_SHORT).show() } }
            visibility = if (packageManager.queryIntentActivities(speech, 0).isNotEmpty()) View.VISIBLE else View.GONE
        }
        bar.addView(mic, LinearLayout.LayoutParams(-2, -2))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        // pigułki filtrów
        val fbar = LinearLayout(this).apply { setPadding(0, Ui.dp(14), 0, Ui.dp(6)) }
        FILTERS.forEachIndexed { i, name ->
            if (i == F_STUBE && !pl.iptv.tv.BuildConfig.FULL_EDITION) return@forEachIndexed
            val p = TextView(this).apply {
                text = name; textSize = 15f; tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(20), Ui.dp(9), Ui.dp(20), Ui.dp(9))
                background = O.pill(rest = 0x1FFFFFFF); setTextColor(O.pillText()); isSelected = i == F_ALL
                setOnClickListener { setFilter(i) }; setTag(pl.iptv.tv.R.id.row_key, i)
            }
            pills += p
            fbar.addView(p, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(10) })
        }
        root.addView(fbar)
        status = TextView(this).apply { textSize = 15f; tag = "keepcolor"; setTextColor(O.TEXT2); setPadding(Ui.dp(4), Ui.dp(10), 0, Ui.dp(4)); text = "Wpisz co najmniej 2 znaki." }
        root.addView(status)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dp(80)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFocusable = false; addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    fun setFilter(f: Int) {
        filter.value = f
        pills.forEach { p -> p.isSelected = p.getTag(pl.iptv.tv.R.id.row_key) == f }
    }

    private fun hideKeyboard() = (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)

    private fun firstResult(): View? = (0 until rows.childCount).asSequence().mapNotNull { (rows.getChildAt(it) as? ViewGroup)?.getChildAt(1) as? RecyclerView }
        .firstOrNull { (it.adapter?.itemCount ?: 0) > 0 }?.getChildAt(0)

    /** Rzędy wyników: istniejące rzędy aktualizowane w miejscu (fokus zostaje), nowe dopisywane w stałej kolejności. */
    private fun render(r: Results) {
        results = r
        val hadFocus = window.decorView.findFocus()?.let { f -> var x: View? = f; while (x != null && x !== rows) x = x.parent as? View; x != null } == true
        val sections = listOfNotNull(
            ("channels" to "Kanały TV").takeIf { r.channels.isNotEmpty() },
            ("epg" to "Program EPG").takeIf { r.epg.isNotEmpty() },
            ("stube" to "STube").takeIf { r.stube.isNotEmpty() },
            ("stremio" to "Stremio – filmy i seriale").takeIf { r.stremio.isNotEmpty() })
        val ids = sections.map { it.first }
        for (i in rows.childCount - 1 downTo 0) if (rows.getChildAt(i).tag !in ids) rows.removeViewAt(i)
        sections.forEachIndexed { idx, (id, title) ->
            val box = (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == id } as? LinearLayout
                ?: rowBox(id, title).also { rows.addView(it, idx.coerceAtMost(rows.childCount)) }
            val rv = box.getChildAt(1) as RecyclerView
            when (id) {
                "channels" -> (rv.adapter as ChannelCards).submit(r.channels)
                "epg" -> (rv.adapter as EpgCards).submit(r.epg)
                "stube" -> (rv.adapter as VideoCards).submit(r.stube)
                "stremio" -> (rv.adapter as StremioPosterAdapter).let { a ->
                    if (a.items.map { it.meta } != r.stremio) { a.items.clear(); a.items += r.stremio.map { StremioTile(it) }; a.notifyDataSetChanged() }
                }
            }
        }
        status.text = when {
            r.query.length < 2 -> "Wpisz co najmniej 2 znaki."
            sections.isEmpty() && r.loading -> "Szukanie…"
            sections.isEmpty() -> "Brak wyników dla „${r.query}”."
            r.loading -> "Szukanie w sieci…"
            else -> ""
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (hadFocus && window.decorView.findFocus() == null) firstResult()?.requestFocus()
    }

    private fun rowBox(id: String, title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; tag = id; setPadding(0, Ui.dp(10), 0, 0)
        addView(TextView(context).apply { text = title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); tag = "keepcolor"; setTextColor(O.TEXT); setPadding(Ui.dp(4), 0, 0, Ui.dp(4)) })
        val rv = HRowRecycler(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false); itemAnimator = null
            setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(40), Ui.dp(10)); clipToPadding = false
            adapter = when (id) {
                "channels" -> ChannelCards { ch -> X25Actions.playChannel(this@SearchActivity, ch) }
                "epg" -> EpgCards { h -> ProgrammeActions.menu(this@SearchActivity, h.channel, h.programme, { c, _ -> X25Actions.playChannel(this@SearchActivity, c) }) }
                "stube" -> VideoCards { v -> X25Actions.playVideo(this@SearchActivity, v) }
                else -> StremioPosterAdapter().also { a -> a.onClick = { t -> startActivity(StremioDetailActivity.intent(this@SearchActivity, t.meta)) } }
            }
        }
        addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------------- karty wyników ----------------

    /** Wspólna karta wyników: szkło 16 dp, zaznaczona – obwódka w akcencie i skala 1,06 (właściwości widoku – GPU). */
    abstract class Cards<T>(private val onClick: (T) -> Unit) : RecyclerView.Adapter<Cards.VH>() {
        var items: List<T> = emptyList(); private set
        init { setHasStableIds(true) }
        abstract fun id(t: T): String
        override fun getItemId(position: Int) = id(items[position]).hashCode().toLong()
        override fun getItemCount() = items.size
        fun submit(list: List<T>) { if (list != items) { items = list; notifyDataSetChanged() } }

        class VH(val root: LinearLayout, val image: ImageView, val badge: TextView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)
        abstract val widthDp: Int
        abstract val imageHeightDp: Int
        abstract fun bind(h: VH, t: T)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context; val O = ObsidianViews
            val w = Ui.dp(widthDp)
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; tag = "keepcolor nozoom"; isFocusable = true; isClickable = true
                setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(6))
                layoutParams = RecyclerView.LayoutParams(w + Ui.dp(8), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(12) }
            }
            val frame = FrameLayout(c).apply {
                background = O.glass(); isDuplicateParentStateEnabled = true
                if (android.os.Build.VERSION.SDK_INT >= 23) foreground = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(16).toFloat(); setStroke(Ui.dp(2), O.ACCENT) })
                }
            }
            Ui.roundClip(frame, Ui.dp(16).toFloat())
            val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, FrameLayout.LayoutParams(w, Ui.dp(imageHeightDp)))
            val badge = TextView(c).apply {
                tag = "keepcolor"; textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(O.TEXT); visibility = View.GONE
                setPadding(Ui.dp(7), Ui.dp(2), Ui.dp(7), Ui.dp(2)); background = O.durationBadge()
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(8), Ui.dp(8)) })
            root.addView(frame, LinearLayout.LayoutParams(w, Ui.dp(imageHeightDp)))
            val title = TextView(c).apply { tag = "keepcolor"; textSize = 14f; setTextColor(O.TEXT); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0) }
            val sub = TextView(c).apply { tag = "keepcolor"; textSize = 12f; setTextColor(O.TEXT2); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), 0) }
            root.addView(title, LinearLayout.LayoutParams(-1, -2)); root.addView(sub, LinearLayout.LayoutParams(-1, -2))
            val vh = VH(root, image, badge, title, sub)
            root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
            root.setOnFocusChangeListener { v, f ->
                Ui.focusZoom(v, f, 1.06f)
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            coil.util.CoilUtils.dispose(h.image); h.image.setImageDrawable(null); h.badge.visibility = View.GONE
            bind(h, items[position])
        }
    }

    /** Kanały TV: logo na środku karty (rozmiar karty), nazwa i bieżący program. */
    class ChannelCards(onClick: (Channel) -> Unit) : Cards<Channel>(onClick) {
        override val widthDp = 180; override val imageHeightDp = 101
        override fun id(t: Channel) = t.key
        override fun bind(h: VH, t: Channel) {
            h.image.scaleType = ImageView.ScaleType.FIT_CENTER
            h.image.setPadding(Ui.dp(28), Ui.dp(18), Ui.dp(28), Ui.dp(18))
            h.image.loadPicon(t)
            h.title.text = t.name
            h.sub.text = Repository.current(t)?.title ?: t.group
        }
    }

    /** Program EPG: godzina emisji (dzień), tytuł, kanał; trwający – „NA ŻYWO”. */
    class EpgCards(onClick: (Repository.Hit) -> Unit) : Cards<Repository.Hit>(onClick) {
        override val widthDp = 240; override val imageHeightDp = 72
        override fun id(t: Repository.Hit) = t.channel.key + "@" + t.programme.start
        override fun bind(h: VH, t: Repository.Hit) {
            val p = t.programme; val now = System.currentTimeMillis()
            h.image.scaleType = ImageView.ScaleType.FIT_CENTER
            h.image.setPadding(Ui.dp(70), Ui.dp(12), Ui.dp(70), Ui.dp(12))
            h.image.loadPicon(t.channel)
            h.badge.visibility = View.VISIBLE
            if (p.isNow(now) && pl.iptv.tv.data.isActualLiveBroadcast(p)) { h.badge.text = "NA ŻYWO"; h.badge.background = ObsidianViews.livePill() }
            else { h.badge.text = Fmt.shortDay(p.start) + " " + Fmt.time(p.start); h.badge.background = ObsidianViews.durationBadge() }
            h.title.text = p.fullTitle
            h.sub.text = "${Fmt.time(p.start)}–${Fmt.time(p.end)}  ·  ${t.channel.name}"
        }
    }

    /** Filmy STube: miniatura 16:9 w rozmiarze karty, czas trwania w rogu, tytuł i kanał. */
    class VideoCards(onClick: (YtVideo) -> Unit) : Cards<YtVideo>(onClick) {
        override val widthDp = 240; override val imageHeightDp = 135
        override fun id(t: YtVideo) = t.id
        override fun bind(h: VH, t: YtVideo) {
            h.image.scaleType = ImageView.ScaleType.CENTER_CROP; h.image.setPadding(0, 0, 0, 0)
            if (t.thumb.isNotBlank()) h.image.load(STubeScreen.thumbUrl(t.thumb, Ui.dp(widthDp))) { crossfade(false); fitTo(h.image, Ui.dp(widthDp), Ui.dp(imageHeightDp)) }
            val d = STubeScreen.duration(t.duration)
            h.badge.text = d.ifBlank { if (t.duration == "P0D") "NA ŻYWO" else "" }
            h.badge.visibility = if (h.badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            h.title.text = t.title; h.sub.text = t.channel
        }
    }
}

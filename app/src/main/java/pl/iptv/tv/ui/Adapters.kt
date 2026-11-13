package pl.iptv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Repository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val day = SimpleDateFormat("EEEE, d MMMM", Locale("pl"))
    fun time(ms: Long): String = time.format(Date(ms))
    fun day(ms: Long): String {
        val a = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12) }
        val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
        val diff = Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
        val label = when (diff) { 0 -> "Dziś"; -1 -> "Wczoraj"; 1 -> "Jutro"; else -> null }
        return (label?.let { "$it – " } ?: "") + day.format(Date(ms))
    }
    /** "dziś", "jutro", "wczoraj" albo "śr 5.11". */
    fun shortDay(ms: Long): String {
        val a = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12) }
        val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
        return when (Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()) {
            0 -> "dziś"; 1 -> "jutro"; -1 -> "wczoraj"
            else -> SimpleDateFormat("EEE d.MM", Locale("pl")).format(Date(ms))
        }
    }

    fun dayKey(ms: Long): Int = Calendar.getInstance().run { timeInMillis = ms; get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR) }

    /** "20:00 Tytuł" + opcjonalnie "(jeszcze 25 min)". */
    fun nowLine(p: Programme, now: Long): String {
        val rem = if (Ui.progressRemaining) "  (jeszcze ${((p.end - now) / 60_000).coerceAtLeast(0)} min)" else ""
        return "${time(p.start)} ${p.title}$rem"
    }
}

/** Logo kanału: z pamięci od razu (bez ikony ładowania), inaczej pobranie w tle. */
/**
 * Logo kanału o stałym rozmiarze: wczytanie obrazu nie przelicza układu całej listy
 * (wcześniej każde doczytane logo wywoływało ponowny układ RecyclerView – przycięcia przy przewijaniu).
 */
class PiconView @JvmOverloads constructor(c: android.content.Context, a: android.util.AttributeSet? = null) :
    androidx.appcompat.widget.AppCompatImageView(c, a) {
    private var fixedUpdate = false
    init {
        // logo przycięte do zaokrąglonego kafelka wg motywu – kwadratowe rogi obrazu nie wystają poza kartę
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.logoRadius) }
        }
        clipToOutline = true
    }
    override fun setImageDrawable(d: android.graphics.drawable.Drawable?) {
        val lp = layoutParams
        fixedUpdate = lp != null && lp.width > 0 && lp.height > 0 && isLaidOut
        try { super.setImageDrawable(d) } finally { fixedUpdate = false }
    }
    override fun requestLayout() { if (!fixedUpdate) super.requestLayout() }
}

private val piconSize = coil.size.Size(240, 144)

/** Zastępcze logo: inicjały kanału na kolorowym kafelku (kolor stały dla nazwy) – kanał bez logo nie zostaje pusty. */
class MonogramDrawable(name: String) : android.graphics.drawable.Drawable() {
    private val label = name.replace(Regex("\\b(HD|FHD|UHD|4K|SD|PL|TV)\\b", RegexOption.IGNORE_CASE), " ")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(if (it.all(Char::isDigit)) 3 else 1) }
        .uppercase().ifEmpty { "TV" }.take(3)
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        val h = (name.lowercase().hashCode() and 0x7FFFFFFF) % 360
        color = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.45f, 0.42f))
    }
    private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val r = android.graphics.RectF()
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds; if (b.isEmpty) return
        // kafelek o proporcjach logo (do 16:10), wyśrodkowany
        val w = minOf(b.width().toFloat(), b.height() * 1.6f); val h = minOf(b.height().toFloat(), w / 1.2f)
        r.set(b.exactCenterX() - w / 2, b.exactCenterY() - h / 2, b.exactCenterX() + w / 2, b.exactCenterY() + h / 2)
        c.drawRoundRect(r, h * 0.18f, h * 0.18f, bgPaint)
        txt.textSize = h * (if (label.length > 2) 0.38f else 0.48f)
        c.drawText(label, r.centerX(), r.centerY() - (txt.descent() + txt.ascent()) / 2, txt)
    }
    override fun setAlpha(a: Int) { bgPaint.alpha = a; txt.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Adresy logo, które się nie wczytały (adres → czas) – przez 10 min od razu następne źródło, bez ponownego czekania. */
private val piconFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()
private const val PICON_FAIL_MS = 10 * 60_000L
/** Przerwy przed kolejnymi rundami prób wczytania logo (po porażce wszystkich źródeł). */
val PICON_RETRY_DELAYS = longArrayOf(4_000L, 15_000L, 45_000L)
fun piconFailedRecently(url: String) = piconFailed[url]?.let { System.currentTimeMillis() - it < PICON_FAIL_MS } == true

/**
 * Logo kanału, wielopoziomowo: pamięć RAM → dysk → sieć (klient z długimi limitami i ponowieniami) → kolejne źródła
 * (tvg-logo, ikona z EPG) → ponowienie po chwili (2×) → inicjały kanału. Każdy kanał ma zawsze jakiś obraz.
 */
fun ImageView.loadPicon(ch: Channel) {
    val urls = Repository.piconCandidates(ch)
    val key = ch.key + "|" + urls.joinToString("|")
    coil.util.CoilUtils.dispose(this)
    setTag(R.id.picon_url, key)
    if (urls.isEmpty()) { setImageDrawable(MonogramDrawable(ch.name)); return }
    val mem = coil.Coil.imageLoader(context).memoryCache
    urls.firstNotNullOfOrNull { u -> mem?.get(coil.memory.MemoryCache.Key(u))?.bitmap }?.let { setImageBitmap(it); return }
    // inicjały kanału do czasu wczytania logo (widok z recyklingu) – bez mignięcia złego logo i bez pustego kafelka
    setImageDrawable(MonogramDrawable(ch.name))
    val rv = parent?.let { p -> generateSequence(p) { it.parent }.firstOrNull { it is RecyclerView } } as RecyclerView?
    val start = { if (getTag(R.id.picon_url) == key) loadPiconChain(ch, urls, key, 0, 0) }
    // szybkie przewijanie (przytrzymany ▲▼): logo wczytywane dopiero dla wierszy, które się zatrzymały na ekranie
    if (rv != null && rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) postDelayed(start, 140) else start()
}

private fun ImageView.loadPiconChain(ch: Channel, urls: List<String>, key: String, index: Int, round: Int) {
    if (getTag(R.id.picon_url) != key) return                // widok pokazuje już inny kanał
    // pomijane adresy, które niedawno zawiodły (o ile zostało coś innego do spróbowania)
    var i = index
    while (i < urls.size - 1 && round == 0 && piconFailedRecently(urls[i])) i++
    if (i >= urls.size) {
        // wszystkie źródła zawiodły: inicjały od razu, ponowna próba po chwili (wolny lub chwilowo niedostępny serwer)
        if (drawable == null) setImageDrawable(MonogramDrawable(ch.name))
        if (round < PICON_RETRY_DELAYS.size) postDelayed({ loadPiconChain(ch, urls, key, 0, round + 1) }, PICON_RETRY_DELAYS[round])
        return
    }
    val url = urls[i]
    val req = coil.request.ImageRequest.Builder(context).data(url).size(piconSize).crossfade(false)
        .target(
            onSuccess = { d -> if (getTag(R.id.picon_url) == key) { piconFailed.remove(url); setImageDrawable(d) } },
            onError = { _ -> piconFailed[url] = System.currentTimeMillis(); loadPiconChain(ch, urls, key, i + 1, round) },
        ).build()
    coil.Coil.imageLoader(context).enqueue(req)
}

/** Wczytanie logo do pamięci z wyprzedzeniem (np. sąsiednie kanały przy przełączaniu). */
fun prefetchPicon(c: android.content.Context, ch: Channel) {
    val url = Repository.piconFor(ch) ?: return
    if (coil.Coil.imageLoader(c).memoryCache?.get(coil.memory.MemoryCache.Key(url)) != null) return
    coil.Coil.imageLoader(c).enqueue(coil.request.ImageRequest.Builder(c).data(url).size(piconSize).build())
}

/**
 * Ustawia fokus na elemencie listy, czekając aż RecyclerView go narysuje
 * (po pokazaniu panelu elementy nie istnieją jeszcze w pierwszej klatce).
 */
fun RecyclerView.focusPosition(pos: Int, childId: Int? = null, tries: Int = 20, fallback: View? = null) {
    if (adapter?.itemCount ?: 0 == 0) { fallback?.requestFocus(); return }
    val p = pos.coerceIn(0, adapter!!.itemCount - 1)
    val v = findViewHolderForAdapterPosition(p)?.itemView?.let { iv -> childId?.let { iv.findViewById<View>(it) } ?: iv }
    if (v != null && v.isShown && v.requestFocus()) return
    if (tries <= 0) { fallback?.requestFocus(); return }
    if (v == null) scrollToPosition(p)
    postDelayed({ focusPosition(p, childId, tries - 1, fallback) }, 30)
}

private fun inflate(p: ViewGroup, layout: Int): View =
    LayoutInflater.from(p.context).inflate(layout, p, false).also { Ui.style(it) }

class GroupAdapter(
    private val onFocus: (String) -> Unit,
    private val onClick: (String) -> Unit = onFocus,
    private val onLongClick: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<GroupAdapter.VH>() {
    var items: List<String> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var selected: String = Repository.ALL
        set(v) {
            if (field == v) return
            val old = items.indexOf(field); field = v
            // tylko zmienione wiersze – pełne odświeżenie w trakcie ruchu zaznaczenia zamykało aplikację
            if (old >= 0) notifyItemChanged(old)
            items.indexOf(v).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }
    var label: (String) -> String = { it }
    /** Kolumna zwinięta: tylko skrót nazwy. */
    var compact = false
        set(v) { if (field != v) { field = v; notifyItemRangeChanged(0, itemCount) } }

    fun short(g: String): String = when {
        g == Repository.ALL -> "≡"
        g.isNotEmpty() && !g[0].isLetterOrDigit() -> g.substringBefore(' ')
        else -> g.filter { it.isLetterOrDigit() }.take(2).uppercase()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(inflate(p, R.layout.item_group) as TextView)
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = items[pos]
        h.tv.text = if (compact) short(g) else label(g)
        h.tv.gravity = if (compact) android.view.Gravity.CENTER else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        val d = h.tv.resources.displayMetrics.density
        // zwinięte: małe marginesy, by zmieściły się 2 litery
        if (compact) h.tv.setPadding((2 * d).toInt(), (12 * d).toInt(), (2 * d).toInt(), (12 * d).toInt())
        else h.tv.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        h.tv.isActivated = g == selected
        h.tv.setOnFocusChangeListener { _, f -> if (f) onFocus(g) }
        h.tv.setOnClickListener { onClick(g) }
        h.tv.setOnLongClickListener(onLongClick?.let { l -> View.OnLongClickListener { l(g); true } })
    }
}

class ChannelAdapter(
    private val onFocus: (Channel) -> Unit,
    private val onClick: (Channel, View) -> Unit,
    private val onLongClick: ((Channel) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    var items: List<Channel> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    /** Telefon w pionie: mniejsze logo i numer – więcej miejsca na nazwę kanału. */
    var compactLogo = false
    /** Karty poziome (motywy konsolowe): pionowa karta z dużym logo, nazwą i bieżącym programem. */
    var cards = false
    /** Kanał zaznaczony (np. dotknięty raz na telefonie). */
    var selectedKey: String? = null
        set(v) {
            if (field == v) return
            val old = indexOfKey(field); field = v
            if (old >= 0) notifyItemChanged(old); indexOfKey(v).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val picon: ImageView = v.findViewById(R.id.picon)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgView = v.findViewById(R.id.progress)
        val catchup: TextView = v.findViewById(R.id.catchup)
        val fav: TextView = v.findViewById(R.id.fav)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(inflate(p, R.layout.item_channel).also { if (cards) toCard(it) })

    private fun toCard(v: View) {
        val d = v.resources.displayMetrics.density; fun px(x: Int) = (x * d).toInt()
        val root = v as android.widget.LinearLayout
        root.orientation = android.widget.LinearLayout.VERTICAL; root.gravity = android.view.Gravity.CENTER
        root.setPadding(px(10), px(10), px(10), px(8))
        root.layoutParams = RecyclerView.LayoutParams(px(196), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = px(12) }
        (root.getChildAt(2).layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.MATCH_PARENT; weight = 0f; marginStart = 0; topMargin = px(8) }
        v.findViewById<TextView>(R.id.name).gravity = android.view.Gravity.CENTER_HORIZONTAL
        v.findViewById<TextView>(R.id.now).gravity = android.view.Gravity.CENTER_HORIZONTAL
    }
    override fun getItemCount() = items.size
    fun indexOfKey(key: String?) = items.indexOfFirst { it.key == key }

    /** Odświeżenie tylko programu i postępu (bez ponownego ładowania logo). */
    override fun onBindViewHolder(h: VH, pos: Int, payloads: MutableList<Any>) {
        if (payloads.contains(EPG)) {
            val now = System.currentTimeMillis()
            val cur = Repository.current(items[pos], now)
            nowText(h, items[pos], cur, now)
            h.progress.show(cur, now)
        } else onBindViewHolder(h, pos)
    }

    /** Bieżący program; przy meczu na żywo – wynik (z zakładki Wyniki) przed tytułem. */
    private fun nowText(h: VH, ch: Channel, cur: Programme?, now: Long) {
        val live = pl.iptv.tv.data.Scores.liveFor(ch)
        val prog = cur?.let { Fmt.nowLine(it, now) } ?: ""
        h.now.text = if (live == null) prog else android.text.SpannableStringBuilder(live).apply {
            setSpan(android.text.style.ForegroundColorSpan(Ui.live), 0, length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            if (prog.isNotEmpty()) append("  •  $prog")
        }
        h.now.visibility = if (h.now.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object { const val EPG = "epg" }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        val now = System.currentTimeMillis()
        val d = h.itemView.resources.displayMetrics.density
        h.picon.layoutParams.width = ((if (cards) 132 else if (compactLogo) 48 else 76) * d).toInt(); h.picon.layoutParams.height = ((if (cards) 80 else if (compactLogo) 30 else 46) * d).toInt()
        h.num.layoutParams.width = ((if (compactLogo) 26 else 38) * d).toInt()
        h.num.visibility = if (Prefs.showNumbers && !cards) View.VISIBLE else View.GONE
        h.num.text = ch.number.toString()
        h.name.text = (if (pl.iptv.tv.data.Parental.isLocked(ch)) "🔒 " else "") + ch.name
        val cur = Repository.current(ch, now)
        nowText(h, ch, cur, now)
        h.progress.show(cur, now)
        h.catchup.visibility = if (ch.hasCatchup && !cards) View.VISIBLE else View.GONE
        h.catchup.setTextColor(Ui.archive)
        h.fav.visibility = if (Repository.isFav(ch) && !cards) View.VISIBLE else View.GONE
        h.itemView.isActivated = ch.key == selectedKey
        h.picon.loadPicon(ch)
        h.itemView.setOnFocusChangeListener { _, f -> if (f) onFocus(ch) }
        h.itemView.setOnClickListener { onClick(ch, it) }
        h.itemView.setOnLongClickListener(onLongClick?.let { l -> View.OnLongClickListener { l(ch); true } })
    }
}

class ProgrammeAdapter(
    private val onClick: (Programme) -> Unit,
    private val onFocus: (Programme) -> Unit,
    private val onLongClick: ((Programme) -> Unit)? = null,
) : RecyclerView.Adapter<ProgrammeAdapter.VH>() {
    var items: List<Programme> = emptyList(); private set
    var catchupFrom: Long = Long.MAX_VALUE; private set
    var channel: Channel? = null; private set

    fun submit(list: List<Programme>, catchupFromMs: Long, ch: Channel? = null) {
        items = list; catchupFrom = catchupFromMs; channel = ch; notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val day: TextView = v.findViewById(R.id.day)
        val row: View = v.findViewById(R.id.row)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(inflate(p, R.layout.item_programme))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        val now = System.currentTimeMillis()
        val newDay = pos == 0 || Fmt.dayKey(items[pos - 1].start) != Fmt.dayKey(p.start)
        h.day.visibility = if (newDay) View.VISIBLE else View.GONE
        h.day.text = Fmt.day(p.start)
        h.time.text = Fmt.time(p.start)
        h.title.text = p.fullTitle
        val ch = channel
        when {
            // trwający mecz: wynik na żywo zamiast samego „NA ŻYWO” (program TV kanału, lista EPG)
            p.isNow(now) -> { h.badge.text = ch?.let { pl.iptv.tv.data.Scores.liveForProgramme(it, p, now) } ?: "● NA ŻYWO"; h.badge.setTextColor(Ui.live) }
            p.start > now && ch != null && pl.iptv.tv.data.Reminders.isSet(ch, p) -> { h.badge.text = "🔔"; h.badge.setTextColor(Ui.accent) }
            p.end <= now && p.start >= catchupFrom -> { h.badge.text = "⟲"; h.badge.setTextColor(Ui.archive) }
            else -> h.badge.text = ""
        }
        h.title.alpha = if (p.end <= now && p.start < catchupFrom) 0.5f else 1f
        h.row.setOnClickListener { onClick(p) }
        h.row.setOnFocusChangeListener { _, f -> if (f) onFocus(p) }
        h.row.setOnLongClickListener(onLongClick?.let { l -> View.OnLongClickListener { l(p); true } })
    }
}

class RecentAdapter(private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<RecentAdapter.VH>() {
    var items: List<Channel> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val picon: ImageView = v.findViewById(R.id.picon)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgView = v.findViewById(R.id.progress)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(inflate(p, R.layout.item_recent))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]; val now = System.currentTimeMillis()
        h.picon.loadPicon(ch)
        h.name.text = (if (Prefs.showNumbers) "${ch.number}. " else "") + ch.name
        val cur = Repository.current(ch, now)
        h.now.text = cur?.title ?: ""
        h.progress.show(cur, now)
        h.itemView.setOnClickListener { onClick(ch) }
    }
}

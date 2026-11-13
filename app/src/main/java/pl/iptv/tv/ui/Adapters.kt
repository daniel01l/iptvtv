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
fun ImageView.loadPicon(ch: Channel) {
    val url = Repository.piconFor(ch)
    if (url == null) { setImageResource(R.drawable.ic_tv); return }
    val cached = coil.Coil.imageLoader(context).memoryCache?.get(coil.memory.MemoryCache.Key(url))?.bitmap
    if (cached != null) { coil.util.CoilUtils.dispose(this); setImageBitmap(cached); return }
    load(url) { crossfade(false); placeholder(drawable); error(R.drawable.ic_tv); size(coil.size.Size(320, 192)) }
}

/** Wczytanie logo do pamięci z wyprzedzeniem (np. sąsiednie kanały przy przełączaniu). */
fun prefetchPicon(c: android.content.Context, ch: Channel) {
    val url = Repository.piconFor(ch) ?: return
    if (coil.Coil.imageLoader(c).memoryCache?.get(coil.memory.MemoryCache.Key(url)) != null) return
    coil.Coil.imageLoader(c).enqueue(coil.request.ImageRequest.Builder(c).data(url).size(coil.size.Size(320, 192)).build())
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
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(inflate(p, R.layout.item_channel))
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
        h.picon.layoutParams.width = ((if (compactLogo) 48 else 76) * d).toInt(); h.picon.layoutParams.height = ((if (compactLogo) 30 else 46) * d).toInt()
        h.num.layoutParams.width = ((if (compactLogo) 26 else 38) * d).toInt()
        h.num.visibility = if (Prefs.showNumbers) View.VISIBLE else View.GONE
        h.num.text = ch.number.toString()
        h.name.text = (if (pl.iptv.tv.data.Parental.isLocked(ch)) "🔒 " else "") + ch.name
        val cur = Repository.current(ch, now)
        nowText(h, ch, cur, now)
        h.progress.show(cur, now)
        h.catchup.visibility = if (ch.hasCatchup) View.VISIBLE else View.GONE
        h.catchup.setTextColor(Ui.archive)
        h.fav.visibility = if (Repository.isFav(ch)) View.VISIBLE else View.GONE
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
            p.isNow(now) -> { h.badge.text = "● NA ŻYWO"; h.badge.setTextColor(Ui.live) }
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

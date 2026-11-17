package pl.iptv.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMeta
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore

/** Wspólne elementy ekranów Stremio (widoki w stylu motywu – [Ui.style]: zaznaczenie, powiększenie, X25). */
object StremioUi {
    const val POSTER_W = 150; const val POSTER_H = 222

    fun button(c: Context, text: String, onClick: () -> Unit) = TextView(c).apply {
        this.text = text; tag = "item"; isFocusable = true; isClickable = true; textSize = 16f; gravity = Gravity.CENTER
        setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10)); setOnClickListener { onClick() }
    }

    fun text(c: Context, t: String = "", size: Float = 15f, tag2: String? = null, bold: Boolean = false, lines: Int = 0) = TextView(c).apply {
        text = t; textSize = size; if (tag2 != null) tag = tag2; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    fun typeName(type: String) = when (type) { "movie" -> "Film"; "series" -> "Serial"; "channel" -> "Kanał"; "tv" -> "TV"; else -> type }

    fun metaLine(type: String, year: String, rating: String, genres: List<String>) =
        listOfNotNull(typeName(type), year.ifBlank { null }, rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }, genres.take(3).joinToString(", ").ifBlank { null })
            .joinToString("   ·   ")

    fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60) }
}

/** Metadane (Cinemeta i inne wtyczki „meta”) z pamięcią podręczną. */
object StremioMetas {
    private val cache = HashMap<String, StremioMeta>()
    fun cached(type: String, id: String) = synchronized(cache) { cache["$type/$id"] }

    suspend fun get(type: String, id: String): StremioMeta? {
        cached(type, id)?.let { return it }
        val addons = StremioStore.addons().map { it.first }.filter { it.supports("meta", type, id) }
            .sortedBy { if (it.transportUrl == StremioApi.CINEMETA) 0 else 1 }
            .ifEmpty { listOfNotNull(runCatching { StremioApi.manifest(StremioApi.CINEMETA) }.getOrNull()) }
        for (a in addons) {
            val m = runCatching { StremioApi.meta(a, type, id) }.getOrNull() ?: continue
            synchronized(cache) { cache["$type/$id"] = m }
            return m
        }
        return null
    }

    fun clear() = synchronized(cache) { cache.clear() }
}

/** Kafelek plakatu: pozycja katalogu (opcjonalnie z postępem oglądania). */
data class StremioTile(val meta: StremioMetaPreview, val progress: StremioProgress? = null)

/** Rząd plakatów (katalog / wyniki wyszukiwania / „Kontynuuj oglądanie”). */
class StremioPosterAdapter(val items: MutableList<StremioTile> = mutableListOf()) : RecyclerView.Adapter<StremioPosterAdapter.VH>() {
    var onClick: (StremioTile) -> Unit = {}
    var onLongClick: (StremioTile) -> Unit = {}
    var onFocus: (StremioTile) -> Unit = {}

    class VH(val root: LinearLayout, val poster: ImageView, val initials: TextView, val bar: View, val barBox: FrameLayout,
             val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val w = Ui.dp(StremioUi.POSTER_W); val ph = Ui.dp(StremioUi.POSTER_H)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
            setPadding(Ui.dp(5), Ui.dp(5), Ui.dp(5), Ui.dp(7))
            layoutParams = RecyclerView.LayoutParams(w + Ui.dp(10), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(12) }
        }
        val frame = FrameLayout(c)
        val initials = TextView(c).apply { tag = "text2"; textSize = 20f; gravity = Gravity.CENTER; setPadding(Ui.dp(8), 0, Ui.dp(8), 0) }
        frame.addView(initials, FrameLayout.LayoutParams(w, ph))
        val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(poster, FrameLayout.LayoutParams(w, ph))
        frame.background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x26)); cornerRadius = Ui.dp(8).toFloat() }
        Ui.roundClip(frame, Ui.dp(8).toFloat())
        // pasek obejrzenia na dole plakatu
        val barBox = FrameLayout(c).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE }
        val bar = View(c).apply { setBackgroundColor(Ui.accent) }
        barBox.addView(bar, FrameLayout.LayoutParams(0, -1))
        frame.addView(barBox, FrameLayout.LayoutParams(w, Ui.dp(4), Gravity.BOTTOM))
        root.addView(frame, LinearLayout.LayoutParams(w, ph))
        val title = StremioUi.text(c, size = 14f, lines = 1).apply { setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0) }
        val sub = StremioUi.text(c, size = 12f, tag2 = "text2", lines = 1).apply { setPadding(Ui.dp(2), Ui.dp(1), Ui.dp(2), 0) }
        root.addView(title, LinearLayout.LayoutParams(-1, -2)); root.addView(sub, LinearLayout.LayoutParams(-1, -2))
        val vh = VH(root, poster, initials, bar, barBox, title, sub)
        root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnLongClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onLongClick); true }
        root.setOnFocusChangeListener { _, f -> if (f) items.getOrNull(vh.bindingAdapterPosition)?.let(onFocus) }
        Ui.style(root)
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]; val m = t.meta
        h.title.text = m.name
        h.sub.text = t.progress?.let { p -> listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" · ") }
            ?: listOfNotNull(m.releaseInfo.ifBlank { null }, StremioUi.typeName(m.type)).joinToString(" · ")
        h.initials.text = m.name
        h.poster.setImageDrawable(null)
        if (m.poster.isNotBlank()) h.poster.load(m.poster) { crossfade(false); size(Ui.dp(StremioUi.POSTER_W), Ui.dp(StremioUi.POSTER_H)) }
        val f = t.progress?.fraction ?: 0f
        h.barBox.visibility = if (f > 0f) View.VISIBLE else View.GONE
        h.bar.layoutParams = (h.bar.layoutParams as FrameLayout.LayoutParams).apply { width = (Ui.dp(StremioUi.POSTER_W) * f).toInt() }
    }
}

/** Lista wtyczek z przełącznikami (ekran zarządzania). */
class StremioAddonAdapter(var items: List<Pair<StremioAddon, Boolean>>, private val onToggle: (StremioAddon, Boolean) -> Unit,
                          private val onLong: (StremioAddon) -> Unit) : RecyclerView.Adapter<StremioAddonAdapter.VH>() {
    class VH(val root: LinearLayout, val name: TextView, val desc: TextView, val state: TextView) : RecyclerView.ViewHolder(root)
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; tag = "item"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(6) }
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val name = StremioUi.text(c, size = 18f, bold = true, lines = 1)
        val desc = StremioUi.text(c, size = 13f, tag2 = "text2", lines = 2)
        col.addView(name); col.addView(desc)
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val state = StremioUi.text(c, size = 16f).apply { gravity = Gravity.END; setPadding(Ui.dp(14), 0, 0, 0) }
        root.addView(state)
        val vh = VH(root, name, desc, state)
        root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { (a, on) -> onToggle(a, !on) } }
        root.setOnLongClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { onLong(it.first) }; true }
        Ui.style(root)
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (a, on) = items[position]
        h.name.text = a.name + (if (a.version.isNotBlank()) "  v${a.version}" else "")
        val res = a.resourceNames.joinToString(", ") { when (it) { "stream" -> "źródła"; "subtitles" -> "napisy"; "catalog" -> "katalogi"; "meta" -> "metadane"; else -> it } }
        h.desc.text = listOfNotNull(res.ifBlank { null }, a.description.ifBlank { null }).joinToString("  ·  ")
        if (Ps5Theme.active) {
            // PS5: przełącznik „switch” po prawej (jak w ustawieniach konsoli)
            h.state.text = if (on) "Włączona" else "Wyłączona"
            h.state.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ps5Switch(on), null)
            h.state.compoundDrawablePadding = Ui.dp(12)
        } else {
            h.state.text = if (on) "✔ Włączona" else "✖ Wyłączona"
            h.state.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
        h.state.alpha = if (on) 1f else 0.6f
    }
}

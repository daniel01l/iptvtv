package pl.iptv.tv.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** (7.17) Wysokość plakatu (dp): „Obsidian Stream” – dokładnie 2:3 do szerokości, inne motywy – jak dotąd. */
    val posterH: Int get() = if (pl.iptv.tv.ui.theme.ObsidianViews.active) POSTER_W * 3 / 2 else POSTER_H
    /** Skala zaznaczonego plakatu (warstwa GPU – animacja właściwości widoku, bez przebudowy układu). */
    const val POSTER_FOCUS_SCALE = 1.07f

    /** Odmiana liczebnika: 1 źródło, 2–4 źródła, 5+ źródeł (12–14 – „źródeł”). */
    fun plural(n: Int, one: String, few: String, many: String) = when {
        n == 1 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

    /** Przycisk w stylu motywu; [icon] – rysowana ikona ([PlayerIcon]) zamiast emoji, zmienia kolor wraz z zaznaczeniem. */
    fun button(c: Context, text: String, icon: String? = null, onClick: () -> Unit) = TextView(c).apply {
        this.text = text; tag = "item"; isFocusable = true; isClickable = true; textSize = 16f; gravity = Gravity.CENTER
        setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10)); setOnClickListener { onClick() }
        if (icon != null) {
            setCompoundDrawables(icon(icon, 20), null, null, null)
            compoundDrawablePadding = if (text.isEmpty()) 0 else Ui.dp(8)
            if (text.isEmpty()) setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10))
        }
    }

    /** Ikona w kolorach zaznaczenia motywu (zaznaczona – kolor tekstu zaznaczenia, zwykła – kolor tekstu). */
    fun icon(kind: String, dp: Int, accent: Boolean = false): PlayerIcon {
        val on = Ui.focusText; val off = if (accent) Ui.accent or 0xFF000000.toInt() else Ui.text
        val st = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_activated), intArrayOf()), intArrayOf(on, on, on, off))
        return PlayerIcon(kind, st).apply { setBounds(0, 0, Ui.dp(dp), Ui.dp(dp)) }
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

    /** Tytuł bazowy serii filmowej: bez numeru części i podtytułu („Matrix: Reaktywacja”, „Shrek 2” → „Matrix”, „Shrek”); za krótki – pusty. */
    fun baseTitle(name: String): String = name.substringBefore(":").substringBefore(" - ")
        .replace(Regex("(?i)\\s+(part|część|czesc|chapter)?\\s*([0-9]{1,2}|[ivx]{1,4})$"), "").trim().takeIf { it.length >= 3 }.orEmpty()

    /**
     * (7.11) Inne części filmu (sequele / prequele / powiązane), jeśli istnieją: wyszukiwanie po tytule bazowym we włączonych
     * wtyczkach z wyszukiwaniem filmów, tylko pozycje zaczynające się tym tytułem, bez bieżącego filmu, od najstarszych.
     * Wtyczki, które zawiodą lub przekroczą czas, są pomijane po cichu.
     */
    suspend fun parts(name: String, currentId: String): List<StremioMetaPreview> {
        val base = baseTitle(name)
        if (base.isEmpty()) return emptyList()
        val addons = StremioStore.enabledAddons().filter { a -> a.searchCatalogs.any { it.type == "movie" } }.take(4)
        val found = coroutineScope {
            addons.map { a -> async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { kotlinx.coroutines.withTimeoutOrNull(6_000) {
                    a.searchCatalogs.filter { it.type == "movie" }.take(1).flatMap { c -> StremioApi.catalog(a, c, base) }
                } }.getOrNull().orEmpty()
            } }.flatMap { it.await() }
        }
        val b = base.lowercase()
        return found.filter { it.type == "movie" && it.id != currentId && it.name.lowercase().startsWith(b) }
            .distinctBy { it.id }.sortedBy { it.releaseInfo.take(4).toIntOrNull() ?: 9999 }.take(20)
    }
}

/** Kafelek plakatu: pozycja katalogu (opcjonalnie z postępem oglądania). */
/** [note] – podpis zamiast domyślnego (np. „Sezon 2 · 60%”), [fraction] – pasek na plakacie (np. procent obejrzanego sezonu). */
data class StremioTile(val meta: StremioMetaPreview, val progress: StremioProgress? = null, val note: String = "", val fraction: Float? = null)

/** Rząd plakatów (katalog / wyniki wyszukiwania / „Kontynuuj oglądanie”). */
class StremioPosterAdapter(val items: MutableList<StremioTile> = mutableListOf()) : RecyclerView.Adapter<StremioPosterAdapter.VH>() {
    var onClick: (StremioTile) -> Unit = {}
    var onLongClick: (StremioTile) -> Unit = {}
    var onFocus: (StremioTile) -> Unit = {}

    class VH(val root: LinearLayout, val poster: ImageView, val initials: TextView, val bar: View, val barBox: FrameLayout,
             val title: TextView, val sub: TextView, val rating: TextView? = null) : RecyclerView.ViewHolder(root)

    /** (7.17) Stałe identyfikatory pozycji (typ + id tytułu) i jeden typ widoku „plakat” – odświeżenie rzędu bez gubienia fokusu. */
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.meta?.let { (it.type + "/" + it.id).hashCode().toLong() } ?: RecyclerView.NO_ID
    override fun getItemViewType(position: Int) = VIEW_POSTER
    companion object { const val VIEW_POSTER = 1 }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val obsidian = pl.iptv.tv.ui.theme.ObsidianViews.active
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        val w = Ui.dp(StremioUi.POSTER_W); val ph = Ui.dp(StremioUi.posterH)
        val radius = Ui.dp(if (obsidian) O.RADIUS_DP else 8).toFloat()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; tag = if (obsidian) "keepcolor nozoom" else "item"; isFocusable = true; isClickable = true
            setPadding(Ui.dp(5), Ui.dp(5), Ui.dp(5), Ui.dp(7))
            layoutParams = RecyclerView.LayoutParams(w + Ui.dp(10), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(12) }
        }
        val frame = FrameLayout(c)
        val initials = TextView(c).apply { tag = "text2"; textSize = 20f; gravity = Gravity.CENTER; setPadding(Ui.dp(8), 0, Ui.dp(8), 0) }
        frame.addView(initials, FrameLayout.LayoutParams(w, ph))
        val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(poster, FrameLayout.LayoutParams(w, ph))
        frame.background = if (obsidian) O.glass() else GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x26)); cornerRadius = radius }
        Ui.roundClip(frame, radius)
        // (7.17) „Obsidian Stream”: obwódka plakatu – zaznaczony: 2 dp w akcencie + poświata (cień w kolorze akcentu), zwykły: krawędź specular
        var rating: TextView? = null
        if (obsidian) {
            frame.isDuplicateParentStateEnabled = true
            if (android.os.Build.VERSION.SDK_INT >= 23) frame.foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = radius; setStroke(Ui.dp(2), O.ACCENT) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = radius; setStroke(Ui.dp(1), O.SPECULAR) })
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) { frame.outlineSpotShadowColor = O.ACCENT; frame.outlineAmbientShadowColor = O.ACCENT }
            // dyskretna pigułka z oceną w prawym górnym rogu
            rating = TextView(c).apply {
                tag = "keepcolor"; textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(O.TEXT)
                setPadding(Ui.dp(7), Ui.dp(2), Ui.dp(7), Ui.dp(2)); visibility = View.GONE
                background = GradientDrawable().apply { setColor(0xCC080A0F.toInt()); cornerRadius = Ui.dp(8).toFloat() }
            }
            frame.addView(rating, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(7), Ui.dp(7), 0) })
        }
        // pasek obejrzenia na dole plakatu
        val barBox = FrameLayout(c).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE }
        val bar = View(c).apply { setBackgroundColor(Ui.accent) }
        barBox.addView(bar, FrameLayout.LayoutParams(0, -1))
        frame.addView(barBox, FrameLayout.LayoutParams(w, Ui.dp(4), Gravity.BOTTOM))
        root.addView(frame, LinearLayout.LayoutParams(w, ph))
        val title = StremioUi.text(c, size = 14f, lines = 1).apply { setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0) }
        val sub = StremioUi.text(c, size = 12f, tag2 = "text2", lines = 1).apply { setPadding(Ui.dp(2), Ui.dp(1), Ui.dp(2), 0) }
        root.addView(title, LinearLayout.LayoutParams(-1, -2)); root.addView(sub, LinearLayout.LayoutParams(-1, -2))
        if (obsidian) { title.setTextColor(O.TEXT); title.tag = "keepcolor"; sub.setTextColor(O.TEXT2); sub.tag = "keepcolor"; initials.setTextColor(O.TEXT2); initials.tag = "keepcolor" }
        val vh = VH(root, poster, initials, bar, barBox, title, sub, rating)
        root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnLongClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onLongClick); true }
        val focusAnim = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        root.setOnFocusChangeListener { _, f ->
            if (f) items.getOrNull(vh.bindingAdapterPosition)?.let(onFocus)
            if (obsidian) {
                // skala i poświata tylko we właściwościach widoku (RenderNode – GPU), bez ponownego pomiaru rzędu
                val s = if (f) StremioUi.POSTER_FOCUS_SCALE else 1f
                val ms = if (pl.iptv.tv.data.Prefs.lowPerf || Ui.anim == 0) 0L else 160L
                root.animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(focusAnim).start()
                frame.elevation = if (f) Ui.dp(14).toFloat() else 0f
            }
        }
        if (obsidian) root.pivotY = 0f
        Ui.style(root)
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]; val m = t.meta
        h.title.text = m.name
        val obsidian = h.rating != null
        h.sub.text = t.note.ifBlank { null } ?: t.progress?.let { p -> listOfNotNull(p.videoLabel.ifBlank { null }, StremioUi.fmt(p.positionMs)).joinToString(" · ") }
            // (7.17) Obsidian: pod plakatem czysty tytuł i rok produkcji
            ?: if (obsidian) m.releaseInfo.take(4).ifBlank { StremioUi.typeName(m.type) }
            else listOfNotNull(m.releaseInfo.ifBlank { null }, StremioUi.typeName(m.type)).joinToString(" · ")
        h.rating?.let { r ->
            val v = m.imdbRating.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }
            r.text = v?.let { "★ $it" } ?: ""; r.visibility = if (v != null) View.VISIBLE else View.GONE
        }
        h.initials.text = m.name
        coil.util.CoilUtils.dispose(h.poster)
        h.poster.setImageDrawable(null)
        // dokładny rozmiar 2:3 kafelka (EXACT): plakat 2000×3000 px nie trafia do pamięci w pełnej rozdzielczości
        if (m.poster.isNotBlank()) h.poster.load(m.poster) {
            crossfade(false); size(Ui.dp(StremioUi.POSTER_W), Ui.dp(StremioUi.posterH))
            scale(coil.size.Scale.FILL); precision(coil.size.Precision.EXACT)
        }
        val f = t.fraction ?: t.progress?.fraction ?: 0f
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
        val obsidian = pl.iptv.tv.ui.theme.ObsidianViews.active
        if (Ps5Theme.active || obsidian) {
            // PS5: przełącznik „switch” po prawej (jak w ustawieniach konsoli)
            h.state.text = if (on) "Włączona" else "Wyłączona"
            h.state.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, if (obsidian) pl.iptv.tv.ui.theme.ObsidianViews.Switch(on) else Ps5Switch(on), null)
            h.state.compoundDrawablePadding = Ui.dp(12)
        } else {
            h.state.text = if (on) "✔ Włączona" else "✖ Wyłączona"
            h.state.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
        h.state.alpha = if (on) 1f else 0.6f
    }
}

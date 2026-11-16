package pl.iptv.tv.ui

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
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtChannel
import pl.iptv.tv.data.YtSource
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.data.YtKeyless
import pl.iptv.tv.data.YtException

/** Kafelek rzędu STube. */
sealed class STubeTile {
    data class Video(val v: YtVideo) : STubeTile()
    data class Channel(val c: YtChannel) : STubeTile()
    /** Kafelek akcji (zaloguj, szukaj w SmartTube, ponów) – tej samej wielkości co film. */
    data class Action(val id: String, val icon: String, val label: String) : STubeTile()
}

/** Strona danych rzędu: kafelki i token następnej strony. */
data class STubePage(val tiles: List<STubeTile>, val next: String? = null)

/** Rząd: tytuł + loader stron (YouTube Data API v3 przez [YouTubeRepository]). */
class STubeRow(val id: String, val title: String, val load: suspend (page: String?) -> STubePage) {
    val tiles = mutableListOf<STubeTile>()
    var next: String? = null
    var loading = false
    var error: String? = null
    /** Ostatnio zaznaczony kafelek (powrót ▲▼ na tę samą pozycję – jak w Leanback). */
    var lastFocus = 0
    val adapter = STubeTileAdapter(this)
}

/**
 * Ekran STube – układ rzędów i kafelków (10-foot UI): duże kafelki 16:9, wyraźny fokus wg motywu
 * (ramka/poświata i powiększenie z [Ui.style]), brak drobnych elementów – cały kafelek jest jednym celem OK.
 */
object STubeScreen {
    const val ROW_SEARCH = "search"; const val ROW_FOR_YOU = "for_you"; const val ROW_TRENDING = "trending"
    const val ROW_MUSIC = "music"; const val ROW_SUBS = "subs"; const val ROW_LOGIN = "login"
    const val ACT_LOGIN = "login"; const val ACT_SMARTTUBE_SEARCH = "st_search"; const val ACT_RETRY = "retry"

    /** Wymiary kafelków (dp). */
    const val TILE_W = 236; const val THUMB_H = 126; const val CHANNEL_W = 150; const val AVATAR = 96

    /**
     * Kolejność rzędów: [wyniki], Dla Ciebie (konto, źródło „Moje”) albo zachęta do logowania, Na czasie, Muzyka, Subskrypcje (konto).
     * Czysta funkcja – testowana bez sieci.
     */
    const val ROW_LIVE = "live"

    fun rowIds(loggedIn: Boolean, personal: Boolean, query: String?): List<String> = buildList {
        if (!query.isNullOrBlank()) add(ROW_SEARCH)
        if (loggedIn && personal) add(ROW_FOR_YOU) else if (!loggedIn) add(ROW_LOGIN)
        add(ROW_TRENDING)
        add(ROW_MUSIC)
        add(ROW_LIVE)
        if (loggedIn) add(ROW_SUBS)
    }

    /**
     * Dane z YouTube Data API (klucz lub konto), a bez dostępu do API albo przy jego błędzie (limit, wyłączone API) –
     * listy bez klucza ([YtKeyless]). STube pokazuje filmy także bez żadnej konfiguracji Google.
     */
    private suspend fun apiOr(api: suspend () -> STubePage, keyless: suspend () -> List<YtVideo>): STubePage {
        if (YouTubeRepository.publicAvailable) {
            try { return api() } catch (e: YtException) { android.util.Log.w("STube", "API: ${e.message} – listy bez klucza") }
        }
        return STubePage(keyless().map(STubeTile::Video))
    }

    /** Rzędy z loaderami (repozytorium konta YouTube + listy bez klucza). */
    fun rows(query: String?): List<STubeRow> {
        val logged = YouTubeTokens.loggedIn
        val personal = YouTubeRepository.source == YtSource.PERSONAL
        return rowIds(logged, personal, query).map { id ->
            when (id) {
                // wyszukiwanie bez klucza (nie zużywa limitu API: 100 jednostek za zapytanie); API – zapasowo
                ROW_SEARCH -> STubeRow(id, "Wyniki: ${query!!.trim()}") { _ ->
                    val found = try { YtKeyless.search(query) } catch (e: YtException) {
                        if (YouTubeRepository.publicAvailable) YouTubeRepository.search(query) else throw e
                    }
                    STubePage(found.map { STubeTile.Video(it) } + STubeTile.Action(ACT_SMARTTUBE_SEARCH, "🔎", "Szukaj „${query.trim()}” w SmartTube"))
                }
                ROW_FOR_YOU -> STubeRow(id, "Dla Ciebie") { p -> YouTubeRepository.feed(YtSource.PERSONAL, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }
                ROW_LOGIN -> STubeRow(id, "Dla Ciebie") { _ ->
                    STubePage(listOf(STubeTile.Action(ACT_LOGIN, "👤", "Zaloguj się do YouTube, aby zobaczyć polecane i subskrypcje")))
                }
                ROW_TRENDING -> STubeRow(id, "Na czasie") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(null, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.PODCASTS) }
                }
                ROW_MUSIC -> STubeRow(id, "Muzyka") { p ->
                    apiOr({ YouTubeRepository.cachedTrending(YouTubeRepository.CAT_MUSIC, p).let { STubePage(it.videos.map(STubeTile::Video), it.nextPage) } }) { YtKeyless.kiosk(YtKeyless.MUSIC) }
                }
                ROW_LIVE -> STubeRow(id, "Na żywo") { _ -> STubePage(YtKeyless.kiosk(YtKeyless.LIVE).map(STubeTile::Video)) }
                else -> STubeRow(id, "Subskrypcje") { _ -> STubePage(YouTubeRepository.subscriptions().map(STubeTile::Channel)) }
            }
        }
    }

    /** Czas z ISO 8601 (PT1H2M3S) → „1:02:03”; puste dla transmisji na żywo. */
    fun duration(iso: String): String {
        val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").find(iso) ?: return ""
        val (d, h, min, s) = m.destructured
        val hours = (d.toIntOrNull() ?: 0) * 24 + (h.toIntOrNull() ?: 0)
        val mm = min.toIntOrNull() ?: 0; val ss = s.toIntOrNull() ?: 0
        if (hours == 0 && mm == 0 && ss == 0) return ""
        return if (hours > 0) "%d:%02d:%02d".format(hours, mm, ss) else "%d:%02d".format(mm, ss)
    }
}

/** Kafelki jednego rzędu (poziomy RecyclerView). */
class STubeTileAdapter(private val row: STubeRow) : RecyclerView.Adapter<STubeTileAdapter.VH>() {
    var onClick: (STubeTile) -> Unit = {}
    var onLongClick: (STubeTile) -> Unit = {}
    var onFocus: (Int) -> Unit = {}

    class VH(val root: LinearLayout, val thumb: ImageView, val badge: TextView, val icon: TextView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = row.tiles.size
    override fun getItemViewType(position: Int) = if (row.tiles[position] is STubeTile.Channel) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val channel = viewType == 1
        val w = Ui.dp(if (channel) STubeScreen.CHANNEL_W else STubeScreen.TILE_W)
        val imgW = w - Ui.dp(12); val imgH = Ui.dp(if (channel) STubeScreen.AVATAR else STubeScreen.THUMB_H)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; tag = "item"; isFocusable = true; isClickable = true
            setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8))
            gravity = if (channel) Gravity.CENTER_HORIZONTAL else Gravity.START
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(14) }
        }
        val frame = FrameLayout(c)
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x30)); cornerRadius = Ui.dp(8).toFloat() }
        }
        frame.addView(thumb, FrameLayout.LayoutParams(if (channel) imgH else imgW, imgH))
        Ui.roundClip(thumb, if (channel) imgH / 2f else Ui.dp(maxOf(Ui.preset.radius - 4, 6)).toFloat())
        // ikona kafelka akcji (zamiast miniatury)
        val icon = TextView(c).apply { textSize = 44f; gravity = Gravity.CENTER; visibility = View.GONE }
        frame.addView(icon, FrameLayout.LayoutParams(imgW, imgH))
        // czas filmu w rogu miniatury (stałe kolory – czytelny na każdym obrazie)
        val badge = TextView(c).apply {
            tag = "keepcolor"; setTextColor(Color.WHITE); textSize = 13f; setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = Ui.dp(4).toFloat() }
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(6), Ui.dp(6)) })
        root.addView(frame, LinearLayout.LayoutParams(-2, -2))
        val title = TextView(c).apply {
            textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0)
            if (channel) gravity = Gravity.CENTER_HORIZONTAL
            // stała wysokość (2 linie) – rzędy nie skaczą przy przewijaniu
            setLines(2)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val sub = TextView(c).apply { tag = "text2"; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), 0) }
        root.addView(sub, LinearLayout.LayoutParams(-1, -2))
        val vh = VH(root, thumb, badge, icon, title, sub)
        root.setOnClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnLongClickListener { row.tiles.getOrNull(vh.bindingAdapterPosition)?.let(onLongClick); true }
        root.setOnFocusChangeListener { _, f -> if (f && vh.bindingAdapterPosition >= 0) { row.lastFocus = vh.bindingAdapterPosition; onFocus(vh.bindingAdapterPosition) } }
        // styl motywu (tło kafelka ze stanem fokusu, powiększenie, przycięcie) – raz, przy tworzeniu widoku
        Ui.style(root)
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        coil.util.CoilUtils.dispose(h.thumb)
        h.thumb.setImageDrawable(null)
        when (val t = row.tiles[position]) {
            is STubeTile.Video -> {
                h.icon.visibility = View.GONE; h.thumb.visibility = View.VISIBLE
                if (t.v.thumb.isNotBlank()) h.thumb.load(t.v.thumb) { crossfade(false) }
                val d = STubeScreen.duration(t.v.duration)
                h.badge.text = d.ifBlank { if (t.v.duration == "P0D") "● NA ŻYWO" else "" }
                h.badge.visibility = if (h.badge.text.isEmpty()) View.GONE else View.VISIBLE
                h.title.text = t.v.title; h.sub.text = t.v.channel; h.sub.visibility = View.VISIBLE
                h.root.contentDescription = t.v.title
            }
            is STubeTile.Channel -> {
                h.icon.visibility = View.GONE; h.badge.visibility = View.GONE
                if (t.c.thumb.isNotBlank()) h.thumb.load(t.c.thumb) { crossfade(false) }
                h.title.text = t.c.title; h.sub.visibility = View.GONE
                h.root.contentDescription = t.c.title
            }
            is STubeTile.Action -> {
                h.icon.visibility = View.VISIBLE; h.icon.text = t.icon; h.badge.visibility = View.GONE
                h.title.text = t.label; h.sub.visibility = View.GONE
                h.root.contentDescription = t.label
            }
        }
    }
}

package pl.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtVideo

/**
 * Wysuwany panel boczny (prawa krawędź) nad odtwarzaczem: zakładki u góry, lista pozycji z miniaturą, podpisem i paskiem postępu.
 * Nawigacja pilotem prowadzona przez panel ([onKey]) – fokus nie ucieka do kontrolek pod spodem:
 * ◄► między zakładkami (zaznaczenie = przełączenie), ▼ z zakładek do listy, ▲ z pierwszej pozycji do zakładek,
 * OK = akcja pozycji, długie OK / MENU = menu pozycji, WSTECZ = zamknięcie.
 */
class SidePanel(private val a: Activity, private val tabs: List<Tab>, private val onClosed: () -> Unit = {}) {
    /** Pozycja listy: miniatura (lub ikona), tytuł, podpis, postęp 0..1 (null – bez paska). */
    data class Entry(
        val key: String, val title: String, val sub: String = "", val thumb: String = "", val icon: String = "",
        val badge: String = "", val progress: Float? = null, val onLong: (() -> Unit)? = null, val onClick: () -> Unit,
    )
    class Tab(val title: String, val empty: String, val load: suspend () -> List<Entry>)

    private var root: FrameLayout? = null
    private lateinit var tabViews: List<TextView>
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = EntryAdapter()
    private var job: Job? = null
    var tab = 0; private set
    val isOpen get() = root?.parent != null
    /** Pozycje bieżącej zakładki (testy). */
    val entries: List<Entry> get() = adapter.items

    fun open(startTab: Int = tab) {
        if (isOpen) { select(startTab, focusList = true); return }
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val r = FrameLayout(a).apply {
            isClickable = true
            setBackgroundColor(pl.iptv.tv.ui.theme.ObsidianViews.scrim(0x66000000))
            setOnClickListener { close() }
        }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; tag = "panel"; isClickable = true
            setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(10))
        }
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        tabViews = tabs.mapIndexed { i, t ->
            TextView(a).apply {
                text = t.title; tag = "item"; textSize = 17f; isFocusable = true; isClickable = true
                setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9))
                setOnClickListener { select(i, focusList = true) }
                setOnFocusChangeListener { _, f -> if (f && tab != i) select(i, focusList = false) }
            }.also { bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(8) }) }
        }
        panel.addView(bar)
        emptyView = TextView(a).apply { tag = "text2"; textSize = 15f; setPadding(Ui.dp(6), Ui.dp(16), Ui.dp(6), 0); visibility = View.GONE }
        panel.addView(emptyView)
        list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a); adapter = this@SidePanel.adapter; itemAnimator = null
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(8) })
        panel.addView(TextView(a).apply {
            tag = "text2"; textSize = 13f; setPadding(Ui.dp(6), Ui.dp(6), 0, 0)
            text = "OK – odtwórz  •  długie OK / MENU – opcje  •  ◄► zakładki  •  WSTECZ – zamknij"
        })
        val w = minOf(Ui.dp(460), (a.resources.displayMetrics.widthPixels * 0.9).toInt())
        r.addView(panel, FrameLayout.LayoutParams(w, -1, Gravity.END).apply { setMargins(0, Ui.dp(12), Ui.dp(12), Ui.dp(12)) })
        content.addView(r, FrameLayout.LayoutParams(-1, -1))
        root = r
        Ui.style(panel)
        Ui.slideIn(panel, fromLeft = false)
        tab = startTab.coerceIn(tabs.indices)
        select(tab, focusList = true)
    }

    fun close() {
        val r = root ?: return
        job?.cancel()
        (r.parent as? ViewGroup)?.removeView(r)
        root = null
        onClosed()
    }

    /** Wczytanie zakładki [i]; [focusList] – fokus na pierwszą pozycję (albo zakładkę, gdy pusta). */
    fun select(i: Int, focusList: Boolean) {
        tab = i.coerceIn(tabs.indices)
        tabViews.forEachIndexed { j, v -> v.isActivated = j == tab }
        job?.cancel()
        // bez listy poprzedniej zakładki w trakcie wczytywania nowej
        adapter.items = emptyList(); adapter.notifyDataSetChanged(); emptyView.visibility = View.GONE
        job = (a as LifecycleOwner).lifecycleScope.launch {
            val items = runCatching { tabs[tab].load() }.getOrDefault(emptyList())
            adapter.items = items; adapter.notifyDataSetChanged()
            emptyView.text = tabs[tab].empty
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (focusList && items.isNotEmpty()) list.focusPosition(0, fallback = tabViews[tab]) else if (focusList) tabViews[tab].requestFocus()
        }
    }

    /** Ponowne wczytanie bieżącej zakładki (np. zmiana kolejki); fokus zostaje na tej samej pozycji. */
    fun reload() {
        if (!isOpen) return
        val f = a.window.decorView.findFocus()
        val pos = f?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
        job?.cancel()
        job = (a as LifecycleOwner).lifecycleScope.launch {
            val items = runCatching { tabs[tab].load() }.getOrDefault(emptyList())
            adapter.items = items; adapter.notifyDataSetChanged()
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (pos >= 0) { if (items.isNotEmpty()) list.focusPosition(pos.coerceAtMost(items.size - 1)) else tabViews[tab].requestFocus() }
        }
    }

    /** Klawisze przy otwartym panelu (wywoływane na początku dispatchKeyEvent ekranu). true – obsłużone. */
    fun onKey(e: KeyEvent): Boolean {
        if (!isOpen) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val f = a.window.decorView.findFocus()
        val ti = tabViews.indexOf(f)
        val li = f?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (down) close(); return true }
            KeyEvent.KEYCODE_MENU -> { if (down && li >= 0) adapter.items.getOrNull(li)?.onLong?.invoke(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down && ti >= 0) {
                    val n = ti + if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    if (n in tabViews.indices) tabViews[n].requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) when {
                    ti >= 0 -> if (adapter.items.isNotEmpty()) list.focusPosition(0)
                    li >= 0 -> if (li < adapter.items.size - 1) list.focusPosition(li + 1)
                    else -> focusDefault()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) when {
                    li > 0 -> list.focusPosition(li - 1)
                    li == 0 -> tabViews[tab].requestFocus()
                    ti < 0 -> focusDefault()
                }
                return true
            }
        }
        // OK i pozostałe – do zaznaczonego elementu panelu (fokus zawsze w panelu)
        if (f == null || !isInside(f)) { if (down) focusDefault(); return true }
        return false
    }

    private fun focusDefault() { if (adapter.items.isNotEmpty()) list.focusPosition(0) else tabViews[tab].requestFocus() }

    private fun isInside(v: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === root) return true; x = x.parent as? View }
        return false
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
        var items: List<Entry> = emptyList()
        inner class VH(val row: LinearLayout, val thumb: ImageView, val icon: TextView, val badge: TextView, val bar: WatchBar,
                       val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; tag = "item"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(6) }
            }
            val tw = Ui.dp(128); val th = Ui.dp(72)
            val frame = FrameLayout(c)
            val thumb = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(Ui.withAlpha(Ui.text2, 0x30)); cornerRadius = Ui.dp(6).toFloat() }
            }
            frame.addView(thumb, FrameLayout.LayoutParams(tw, th))
            val icon = TextView(c).apply { textSize = 30f; gravity = Gravity.CENTER; visibility = View.GONE }
            frame.addView(icon, FrameLayout.LayoutParams(tw, th))
            val badge = TextView(c).apply {
                tag = "keepcolor"; setTextColor(android.graphics.Color.WHITE); textSize = 12f; setPadding(Ui.dp(4), Ui.dp(1), Ui.dp(4), Ui.dp(1))
                background = GradientDrawable().apply { setColor(pl.iptv.tv.ui.theme.ObsidianViews.overlay(0xCC000000.toInt())); cornerRadius = Ui.dp(4).toFloat() }
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(4), Ui.dp(6)) })
            val bar = WatchBar(c).apply { visibility = View.GONE }
            frame.addView(bar, FrameLayout.LayoutParams(tw, Ui.dp(STubeScreen.PROGRESS_H), Gravity.BOTTOM))
            Ui.roundClip(frame, Ui.dp(6).toFloat())
            row.addView(frame, LinearLayout.LayoutParams(tw, th))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(12), 0, 0, 0) }
            val title = TextView(c).apply { textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val sub = TextView(c).apply { tag = "text2"; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            col.addView(title); col.addView(sub)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val vh = VH(row, thumb, icon, badge, bar, title, sub)
            row.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.onClick?.invoke() }
            row.setOnLongClickListener { items.getOrNull(vh.bindingAdapterPosition)?.onLong?.invoke(); true }
            Ui.style(row)
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            coil.util.CoilUtils.dispose(h.thumb); h.thumb.setImageDrawable(null)
            if (e.thumb.isNotBlank()) h.thumb.load(e.thumb) { crossfade(false); fitTo(h.thumb, Ui.dp(112), Ui.dp(63)) }
            h.icon.text = e.icon; h.icon.visibility = if (e.thumb.isBlank() && e.icon.isNotBlank()) View.VISIBLE else View.GONE
            h.badge.text = e.badge; h.badge.visibility = if (e.badge.isBlank()) View.GONE else View.VISIBLE
            h.bar.fraction = e.progress ?: 0f; h.bar.visibility = if (e.progress != null && e.progress > 0f) View.VISIBLE else View.GONE
            h.title.text = e.title; h.sub.text = e.sub; h.sub.visibility = if (e.sub.isBlank()) View.GONE else View.VISIBLE
            h.row.contentDescription = e.title
        }
    }
}

/**
 * Kolejka „Następne” i propozycje w panelu bocznym – wspólne dla odtwarzacza STube i telewizji na żywo.
 * Film: postęp z historii oglądania (Room), kanał: bieżący program z EPG i jego postęp.
 */
object QueueUi {
    /** Odtworzenie pozycji kolejki (zdjętej z kolejki): film – odtwarzacz STube, kanał – odtwarzacz TV. */
    fun play(a: Activity, q: QueueItem) {
        if (q.isVideo) a.startActivity(YtPlayerActivity.intent(a, q.toVideo()))
        else a.startActivity(Intent(a, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, q.ref).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL))
    }

    /** Pozycja kolejki w panelu. */
    fun entry(a: Activity, q: QueueItem, onChanged: () -> Unit, onPlay: (QueueItem) -> Unit): SidePanel.Entry {
        val scope = (a as LifecycleOwner).lifecycleScope
        val menu = {
            Dialogs.menu(a, q.title, listOf(
                "▶ Odtwórz teraz" to { onPlay(q) },
                "▲ Przesuń wyżej" to { scope.launch { StubeQueue.move(q.id, -1); onChanged() }; Unit },
                "▼ Przesuń niżej" to { scope.launch { StubeQueue.move(q.id, 1); onChanged() }; Unit },
                "✖ Usuń z kolejki" to { scope.launch { StubeQueue.remove(q.id); onChanged() }; Unit },
                "🗑 Wyczyść kolejkę" to { Dialogs.confirm(a, "Wyczyścić kolejkę?", "Usunąć wszystkie pozycje z kolejki odtwarzania?") { scope.launch { StubeQueue.clear(); onChanged() } } },
            ))
        }
        if (q.isVideo) {
            return SidePanel.Entry("q${q.id}", q.title, q.subtitle, q.thumb, "▶", STubeScreen.duration(q.duration),
                StubeHistory.fractionOf(q.ref), onLong = menu) { onPlay(q) }
        }
        val ch = Repository.byKey(q.ref)
        val now = System.currentTimeMillis()
        val prog = ch?.let { Repository.current(it, now) }
        val sub = "📺 " + (prog?.let { "${it.title} • ${Fmt.time(it.start)}–${Fmt.time(it.end)}" } ?: (ch?.group ?: "Kanał niedostępny na liście"))
        val progress = prog?.let { ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) }
        return SidePanel.Entry("q${q.id}", q.title, sub, ch?.logo ?: q.thumb, "📺", "NA ŻYWO", progress, onLong = menu) { onPlay(q) }
    }

    suspend fun entries(a: Activity, onChanged: () -> Unit, onPlay: (QueueItem) -> Unit): List<SidePanel.Entry> =
        StubeQueue.items().map { entry(a, it, onChanged, onPlay) }

    /** Film z propozycji: OK – odtwórz teraz, długie OK – do kolejki / jako następny. */
    fun videoEntry(a: Activity, v: YtVideo, onChanged: () -> Unit = {}, onPlay: (YtVideo) -> Unit): SidePanel.Entry {
        val scope = (a as LifecycleOwner).lifecycleScope
        return SidePanel.Entry(v.id, v.title, v.channel, v.thumb, "▶", STubeScreen.duration(v.duration), StubeHistory.fractionOf(v.id), onLong = {
            Dialogs.menu(a, v.title, listOf(
                "▶ Odtwórz teraz" to { onPlay(v) },
                "⏭ Odtwórz jako następny" to { scope.launch { StubeQueue.add(QueueItem.of(v), next = true); toast(a, "Następny w kolejce"); onChanged() }; Unit },
                "＋ Dodaj do kolejki" to { scope.launch { StubeQueue.add(QueueItem.of(v)); toast(a, "Dodano do kolejki"); onChanged() }; Unit },
                "🕘 Do obejrzenia później" to { scope.launch { StubeHistory.addWatchLater(v); toast(a, "Dodano do „Do obejrzenia później”") }; Unit },
            ))
        }) { onPlay(v) }
    }

    /** Kanał TV na żywo jako propozycja (bieżący program, postęp). */
    fun channelEntry(ch: Channel, onLong: (() -> Unit)?, onPlay: (Channel) -> Unit): SidePanel.Entry {
        val now = System.currentTimeMillis()
        val prog = Repository.current(ch, now)
        val progress = prog?.let { ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) }
        return SidePanel.Entry(ch.key, ch.name, prog?.let { "${it.title} • do ${Fmt.time(it.end)}" } ?: ch.group, ch.logo, "📺", "", progress, onLong) { onPlay(ch) }
    }

    fun toast(a: Activity, s: String) = I18n.makeToast(a, s, android.widget.Toast.LENGTH_SHORT).show()
}

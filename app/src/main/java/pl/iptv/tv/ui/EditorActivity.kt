package pl.iptv.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Repository

/**
 * Edytor listy: zmiana nazwy, ukrywanie, kolejność (przeciąganie ≡ lub tryb „Przenieś” + ▲▼),
 * zmiana grupy, mapowanie EPG. Zmiany zapisują się od razu.
 */
class EditorActivity : BaseActivity() {
    companion object { const val ALL = "Wszystkie kanały"; const val HIDDEN = "🚫 Ukryte" }

    private lateinit var groupsRv: RecyclerView
    private lateinit var channelsRv: RecyclerView
    private lateinit var hint: TextView
    private val groupAdapter = GroupAdapter(onFocus = ::selectGroup, onLongClick = ::groupMenu)
    private val adapter = EditorAdapter()
    private lateinit var touchHelper: ItemTouchHelper

    private var group = ALL
    private var moveKey: String? = null
    private var dragKey: String? = null
    private var dragSnapshot: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent(R.layout.activity_editor)
        groupsRv = findViewById(R.id.groups)
        channelsRv = findViewById(R.id.channels)
        hint = findViewById(R.id.hint)
        groupsRv.layoutManager = LinearLayoutManager(this); groupsRv.adapter = groupAdapter
        channelsRv.layoutManager = LinearLayoutManager(this); channelsRv.adapter = adapter
        groupAdapter.selected = ALL
        // ukryta w menu kategoria oznaczona 🚫
        groupAdapter.label = { g -> (if (g in pl.iptv.tv.data.Prefs.groupHidden) "🚫 " else "") + "$g  (${channelsOf(g).size})" }

        findViewById<View>(R.id.btnDone).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGroups).setOnClickListener { Dialogs.editGroups(this) { reload() } }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            Dialogs.confirm(this, "Przywróć oryginał", "Usunąć wszystkie zmiany (nazwy, kolejność, grupy, ukrycie, mapowanie EPG)?") {
                Repository.resetAllEdits(); reload()
            }
        }

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.swap(vh.bindingAdapterPosition, target.bindingAdapterPosition); return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    dragKey = adapter.items[vh.bindingAdapterPosition].key
                    dragSnapshot = adapter.items.toList()
                    vh.itemView.isActivated = true
                }
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.isActivated = false
                val key = dragKey ?: return
                dragKey = null
                val j = adapter.items.indexOfFirst { it.key == key }
                val target = dragSnapshot.getOrNull(j) ?: return
                if (target.key != key) { Repository.moveChannel(key, target.key); reload() }
            }
        })
        touchHelper.attachToRecyclerView(channelsRv)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (moveKey != null) endMove() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        reload()
        groupsRv.post { groupsRv.getChildAt(0)?.requestFocus() }
        if (pl.iptv.tv.data.Prefs.parentalSettings) ParentalUi.require(this, onCancel = { finish() }) {}
    }

    private fun groupsList(): List<String> = buildList {
        add(ALL)
        // kolejność jak w menu (ustawiona w „⇅ Kategorie”)
        val order = pl.iptv.tv.data.Prefs.groupOrder
        addAll(Repository.allChannels.map { it.group }.distinct().sortedBy { g -> order.indexOf(g).let { if (it < 0) Int.MAX_VALUE else it } })
        if (Repository.allChannels.any { it.hidden }) add(HIDDEN)
    }

    private fun channelsOf(g: String): List<Channel> = when (g) {
        ALL -> Repository.allChannels
        HIDDEN -> Repository.allChannels.filter { it.hidden }
        else -> Repository.allChannels.filter { it.group == g }
    }

    private fun reload() {
        val gl = groupsList()
        if (group !in gl) group = ALL
        groupAdapter.selected = group
        groupAdapter.items = gl
        adapter.items = channelsOf(group).toMutableList()
        updateHint()
    }

    private fun updateHint() {
        hint.text = if (moveKey != null)
            "TRYB PRZENOSZENIA: ▲▼ przesuwa kanał  •  OK lub WSTECZ kończy"
        else "OK / dotknięcie – opcje kanału  •  przeciągnij ≡ palcem, aby zmienić kolejność  •  " +
            "przytrzymaj grupę – zmiana nazwy / ukrycie grupy  •  zmiany zapisują się od razu"
    }

    private fun selectGroup(g: String) {
        if (moveKey != null || g == group) return
        group = g; groupAdapter.selected = g
        adapter.items = channelsOf(g).toMutableList()
    }

    private fun groupMenu(g: String) {
        if (g == HIDDEN) return
        val P = pl.iptv.tv.data.Prefs
        if (g == ALL) {
            Dialogs.menu(this, "Kategoria „${Repository.ALL}”", listOf(
                (if (Repository.ALL in P.groupHidden) "👁 Pokaż „${Repository.ALL}” w menu" else "🚫 Ukryj „${Repository.ALL}” w menu") to {
                    if (Repository.ALL !in P.groupHidden && Repository.groups().size <= 1) I18n.makeToast(this, "Musi zostać co najmniej jedna kategoria", android.widget.Toast.LENGTH_SHORT).show()
                    else Repository.toggleGroupVisible(Repository.ALL)
                },
                "⇅ Kolejność i widoczność kategorii…" to { Dialogs.editGroups(this) { reload() } },
            ))
            return
        }
        Dialogs.menu(this, "Grupa: $g", listOf(
            "⇅ Kolejność i widoczność kategorii…" to { Dialogs.editGroups(this) { reload() } },
            (if (g in P.groupHidden) "👁 Pokaż kategorię w menu" else "🚫 Ukryj kategorię w menu (kanały zostają w „Wszystkie”)") to { Repository.toggleGroupVisible(g); reload() },
            "✎ Zmień nazwę grupy" to {
                Dialogs.input(this, "Nowa nazwa grupy", g) { n -> if (n.isNotBlank() && n != g) { Repository.renameGroup(g, n); group = n; reload() } }
            },
            "🚫 Ukryj wszystkie kanały w grupie" to { Repository.setGroupHidden(g, true); reload() },
            "👁 Pokaż wszystkie kanały w grupie" to { Repository.setGroupHidden(g, false); reload() },
        ))
    }

    private fun channelMenu(ch: Channel) {
        ChannelActions.menu(this, ch, extra = listOf("⇅ Przenieś (strzałkami ▲▼)" to { startMove(ch) })) { reload(); refocus(ch.key) }
    }

    // ---------- tryb przenoszenia (pilot) ----------
    private fun startMove(ch: Channel) { moveKey = ch.key; adapter.notifyDataSetChanged(); updateHint(); refocus(ch.key) }
    private fun endMove() { val k = moveKey; moveKey = null; adapter.notifyDataSetChanged(); updateHint(); refocus(k) }

    private fun moveBy(d: Int) {
        val key = moveKey ?: return
        val list = adapter.items
        val i = list.indexOfFirst { it.key == key }
        val j = i + d
        if (i < 0 || j !in list.indices) return
        Repository.moveChannel(key, list[j].key)
        adapter.items = channelsOf(group).toMutableList()
        refocus(key)
    }

    private fun refocus(key: String?) {
        val i = adapter.items.indexOfFirst { it.key == key }
        if (i < 0) return
        val lm = channelsRv.layoutManager as LinearLayoutManager
        if (i < lm.findFirstCompletelyVisibleItemPosition() || i > lm.findLastCompletelyVisibleItemPosition()) channelsRv.scrollToPosition(i)
        channelsRv.post { channelsRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (moveKey != null && event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveBy(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveBy(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { endMove(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        if (moveKey != null && event.action == KeyEvent.ACTION_UP &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------- adapter ----------
    inner class EditorAdapter : RecyclerView.Adapter<EditorAdapter.VH>() {
        var items: MutableList<Channel> = mutableListOf()
            set(v) { field = v; notifyDataSetChanged() }

        fun swap(a: Int, b: Int) {
            if (a !in items.indices || b !in items.indices) return
            java.util.Collections.swap(items, a, b); notifyItemMoved(a, b)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val picon: ImageView = v.findViewById(R.id.picon)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val flags: TextView = v.findViewById(R.id.flags)
            val handle: View = v.findViewById(R.id.handle)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_editor, p, false); Ui.style(v); return VH(v)
        }
        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, pos: Int) {
            val ch = items[pos]
            h.num.text = if (ch.hidden) "–" else ch.number.toString()
            h.name.text = ch.name
            val epgName = ch.epgKey?.let { Repository.epg.channelNames[it] ?: it }
            val epgTxt = when {
                ch.epgOverride == "" -> "EPG: wyłączone"
                epgName != null -> "EPG: $epgName" + if (ch.epgOverride != null) " (ręcznie)" else ""
                else -> "EPG: brak dopasowania"
            }
            h.sub.text = buildString {
                append(ch.group); append("  •  "); append(epgTxt)
                if (ch.name != ch.origName) append("  •  oryg.: ${ch.origName}")
            }
            h.flags.text = buildString {
                if (Repository.isFav(ch)) append("★ ")
                if (ch.hidden) append("🚫 ")
                if (ch.key == moveKey) append("⇅")
            }
            h.itemView.alpha = if (ch.hidden) 0.55f else 1f
            h.itemView.isActivated = ch.key == moveKey
            h.picon.loadPicon(ch)
            h.itemView.setOnClickListener { if (moveKey != null) endMove() else channelMenu(ch) }
            h.itemView.setOnLongClickListener { if (moveKey == null) channelMenu(ch); true }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN && moveKey == null) touchHelper.startDrag(h)
                false
            }
        }
    }
}

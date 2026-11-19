package pl.iptv.tv.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.XmltvParser

object Dialogs {

    fun choice(a: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val d = Ui.dialog(a).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, w -> d.dismiss(); onPick(w) }
            .setNegativeButton("Anuluj", null).show()
        focusSelected(d, selected)
    }

    /** Pilot startuje od bieżącej opcji, nie od pierwszej. */
    private fun focusSelected(d: AlertDialog, selected: Int) {
        val lv = d.listView ?: return
        if (selected < 0) return
        lv.post { lv.requestFocus(); lv.setSelection(selected) }
    }

    /** Wybór, który zostaje otwarty: każda zmiana działa od razu, „Gotowe”/WSTECZ zamyka. */
    fun choiceLive(a: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): AlertDialog {
        lateinit var d: AlertDialog
        d = Ui.dialog(a).setTitle(title)
            // bez ponownego stylowania okna – rozmiar i położenie zostają (wcześniej okno „skakało” po każdym wyborze)
            .setSingleChoiceItems(options.toTypedArray(), selected) { _, w -> onPick(w); d.listView?.let { lv -> lv.setItemChecked(w, true); lv.invalidateViews()
                lv.post { for (i in 0 until lv.childCount) lv.getChildAt(i).refreshDrawableState() } } }
            .setPositiveButton("Gotowe", null).create()
        d.show()
        // okno z boku – podgląd zmian zostaje widoczny
        d.window?.decorView?.post { val w = d.window ?: return@post
            val dm = a.resources.displayMetrics
            if (dm.widthPixels / dm.density >= 900) {
                w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                w.setLayout((dm.widthPixels * 0.38f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                w.attributes = w.attributes.apply { x = Ui.dp(24) }
                w.setDimAmount(0.35f)
            }
        }
        focusSelected(d, selected)
        return d
    }

    /** Menu akcji, które zostaje otwarte (zmiany na żywo w tle); etykiety odświeżane po każdej akcji. */
    fun menuLive(a: Activity, title: String, items: () -> List<Pair<String, () -> Unit>>): AlertDialog {
        var cur = items()
        val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, cur.map { it.first }.toMutableList())
        val lv = ListView(a).apply { this.adapter = adapter; isFocusable = true }
        val d = Ui.dialog(a).setTitle(title).setView(lv).setPositiveButton("Gotowe", null).create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            cur[pos].second()
            cur = items(); adapter.clear(); adapter.addAll(cur.map { it.first })
            lv.post { lv.requestFocus(); lv.setSelection(pos) }
        }
        d.show(); lv.requestFocus()
        return d
    }

    /**
     * Edycja widoczności i kolejności (np. kategorii): OK na pozycji = okno akcji obok (pokaż/ukryj, wyżej, niżej…),
     * lista odświeża się na bieżąco, rozmiar okna się nie zmienia.
     */
    fun orderEditor(
        a: Activity, title: String, ids: () -> List<String>, label: (String) -> String, hidden: (String) -> Boolean,
        toggle: (String) -> Unit, move: (String, Int) -> Unit, reset: () -> Unit, onDone: () -> Unit, onChange: () -> Unit = {},
    ) {
        fun lines() = ids().let { l -> l.mapIndexed { i, id -> "${i + 1}.  ${if (hidden(id)) "✖" else "✔"}  ${label(id)}" } }
        val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, lines().toMutableList())
        val lv = ListView(a).apply { this.adapter = adapter; isFocusable = true }
        fun refresh(focusId: String?) {
            onChange()                                    // ekran pod oknem od razu pokazuje zmianę
            val l = ids(); adapter.clear(); adapter.addAll(lines())
            val i = focusId?.let { l.indexOf(it) } ?: -1
            if (i >= 0) lv.post { lv.requestFocus(); lv.setSelection(i) }
        }
        val d = Ui.dialog(a).setTitle(title).setView(lv)
            .setPositiveButton("Gotowe", null)
            .setNeutralButton("↺ Domyślne") { _, _ -> reset(); onDone() }
            .create()
        d.setOnDismissListener { onDone() }
        lv.setOnItemClickListener { _, _, pos, _ ->
            val id = ids().getOrNull(pos) ?: return@setOnItemClickListener
            menuLive(a, label(id)) { listOf(
                (if (hidden(id)) "👁 Pokaż" else "🚫 Ukryj") to { toggle(id); refresh(id) },
                "▲ Wyżej" to { move(id, -1); refresh(id) },
                "▼ Niżej" to { move(id, 1); refresh(id) },
                "⤒ Na początek" to { move(id, -1000); refresh(id) },
                "⤓ Na koniec" to { move(id, 1000); refresh(id) },
            ) }
        }
        d.show(); lv.requestFocus()
    }

    /** Kolejność i widoczność kategorii w menu (także „Wszystkie”, „Ulubione”, „Ostatnio oglądane”). */
    fun editGroups(a: Activity, onDone: () -> Unit) = orderEditor(a, "Kategorie – widoczność i kolejność",
        ids = { Repository.allGroups() }, label = { it }, hidden = { it in pl.iptv.tv.data.Prefs.groupHidden },
        toggle = { g ->
            if (g !in pl.iptv.tv.data.Prefs.groupHidden && Repository.groups().size <= 1)
                I18n.makeToast(a, "Musi zostać co najmniej jedna kategoria", Toast.LENGTH_SHORT).show()
            else Repository.toggleGroupVisible(g)
        },
        move = { g, d -> Repository.moveGroup(g, d) }, reset = { Repository.resetGroupLayout() }, onDone = onDone, onChange = onDone)

    fun menu(a: Activity, title: String, items: List<Pair<String, () -> Unit>>) {
        Ui.dialog(a).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton("Anuluj", null).show()
    }

    fun input(
        a: Activity, title: String, initial: String, hint: String = "",
        number: Boolean = false, neutral: Pair<String, () -> Unit>? = null, password: Boolean = false,
        onCancel: () -> Unit = {}, code: Boolean = false, onOk: (String) -> Unit,
    ) {
        val et = EditText(a).apply {
            setText(initial); setSelectAllOnFocus(true); selectAll(); this.hint = hint; setSingleLine()
            inputType = when {
                password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        if (code) codeFormatter(et)
        val box = LinearLayout(a).apply { setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0); addView(et, ViewGroup.LayoutParams(-1, -2)) }
        val b = Ui.dialog(a).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Anuluj") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
        neutral?.let { n -> b.setNeutralButton(n.first) { _, _ -> n.second() } }
        b.show()
        et.requestFocus()
    }

    /** Kod XXXX-XXXX-XXXX: wielkie litery i myślniki wstawiane automatycznie podczas pisania. */
    fun codeFormatter(et: EditText) {
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                val raw = s.toString().uppercase().filter { it.isLetterOrDigit() }.take(12)
                val f = raw.chunked(4).joinToString("-")
                if (f != s.toString()) { busy = true; s.replace(0, s.length, f); busy = false }
            }
        })
    }

    fun multi(a: Activity, title: String, items: List<String>, checked: BooleanArray, onDone: (BooleanArray) -> Unit) {
        val state = checked.copyOf()
        Ui.dialog(a).setTitle(title)
            .setMultiChoiceItems(items.toTypedArray(), state) { _, w, c -> state[w] = c }
            .setPositiveButton("Zapisz") { _, _ -> onDone(state) }
            .setNegativeButton("Anuluj", null).show()
    }

    fun confirm(a: Activity, title: String, msg: String, onYes: () -> Unit) {
        Ui.dialog(a).setTitle(title).setMessage(msg)
            .setPositiveButton("Tak") { _, _ -> onYes() }.setNegativeButton("Nie", null).show()
    }

    private val PALETTE = listOf(
        "#000000", "#0F1218", "#171C25", "#263238", "#455A64", "#9E9E9E", "#E0E0E0", "#FFFFFF",
        "#06134A", "#0D2470", "#1565C0", "#2F80ED", "#03A9F4", "#00BCD4", "#00BFA5", "#009688",
        "#2E7D32", "#66BB6A", "#CDDC39", "#FFEB3B", "#FFC400", "#FF9800", "#FF5722", "#E53935",
        "#E50914", "#C2185B", "#FF2BD6", "#9C27B0", "#673AB7", "#3F51B5", "#795548", "#3E2723",
    )

    /** Prosty wybór koloru: siatka próbek (pilot/dotyk) + pole na kod #RRGGBB. */
    fun color(a: Activity, title: String, current: Int, live: Boolean = false, onPick: (Int) -> Unit): AlertDialog {
        lateinit var dlg: AlertDialog
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), 0) }
        val perRow = 8
        PALETTE.chunked(perRow).forEach { row ->
            val line = LinearLayout(a)
            row.forEach { hex ->
                val col = Color.parseColor(hex)
                val sw = View(a).apply {
                    isFocusable = true; isClickable = true
                    background = StateListDrawable().apply {
                        val ring = GradientDrawable().apply { setColor(col); cornerRadius = Ui.dp(6).toFloat(); setStroke(Ui.dp(4), Color.WHITE) }
                        addState(intArrayOf(android.R.attr.state_focused), ring)
                        addState(intArrayOf(android.R.attr.state_pressed), ring)
                        addState(intArrayOf(), GradientDrawable().apply {
                            setColor(col); cornerRadius = Ui.dp(6).toFloat(); setStroke(Ui.dp(1), Color.GRAY)
                        })
                    }
                    setOnClickListener { onPick(col); if (live) Ui.styleDialog(dlg) else dlg.dismiss() }
                }
                line.addView(sw, LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(36)).apply { setMargins(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3)) })
            }
            root.addView(line)
        }
        val hex = EditText(a).apply {
            setSingleLine(); hint = "#RRGGBB"
            setText(String.format("#%06X", current and 0xFFFFFF))
        }
        root.addView(hex)
        dlg = Ui.dialog(a).setTitle(title).setView(root)
            .setPositiveButton("Użyj kodu") { _, _ ->
                runCatching { Color.parseColor(hex.text.toString().trim()) }
                    .onSuccess { onPick(it or (0xFF shl 24)) }
                    .onFailure { I18n.makeToast(a, "Niepoprawny kod koloru", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(if (live) "Gotowe" else "Anuluj", null).create()
        dlg.show()
        (root.getChildAt(0) as LinearLayout).getChildAt(0).requestFocus()
        return dlg
    }
}

/** Akcje edycji kanału – wspólne dla edytora, listy głównej i odtwarzacza. */
object ChannelActions {

    fun menu(a: Activity, ch: Channel, extra: List<Pair<String, () -> Unit>> = emptyList(), onChanged: () -> Unit) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (Repository.isFav(ch)) "☆ Usuń z ulubionych" else "★ Dodaj do ulubionych") to {
            val added = Repository.toggleFav(ch)
            I18n.makeToast(a, if (added) "Dodano do ulubionych" else "Usunięto z ulubionych", Toast.LENGTH_SHORT).show()
            onChanged()
        }
        items += extra
        items += "✎ Zmień nazwę" to { rename(a, ch, onChanged) }
        items += "📁 Przenieś do grupy" to { group(a, ch, onChanged) }
        items += "📺 Mapowanie EPG" to { epg(a, ch, onChanged) }
        items += "# Ustaw numer na liście" to { position(a, ch, onChanged) }
        items += (if (ch.hidden) "👁 Pokaż kanał" else "🚫 Ukryj kanał") to {
            Repository.edit(ch.key) { it.hidden = !ch.hidden }; onChanged()
        }
        items += "↺ Przywróć oryginalne ustawienia" to { Repository.resetChannel(ch.key); onChanged() }
        Dialogs.menu(a, ch.name, items)
    }

    fun rename(a: Activity, ch: Channel, onChanged: () -> Unit) {
        Dialogs.input(a, "Nazwa kanału", ch.name, ch.origName,
            neutral = "Oryginalna" to { Repository.edit(ch.key) { it.name = null }; onChanged() }) { v ->
            Repository.edit(ch.key) { it.name = v.ifBlank { null } }; onChanged()
        }
    }

    fun group(a: Activity, ch: Channel, onChanged: () -> Unit) {
        val groups = Repository.allChannels.map { it.group }.distinct()
        val opts = mutableListOf("+ Nowa grupa…")
        if (ch.group != ch.origGroup) opts += "↺ Oryginalna: ${ch.origGroup}"
        opts += groups
        Dialogs.choice(a, "Grupa dla: ${ch.name}", opts, opts.indexOf(ch.group)) { w ->
            when {
                w == 0 -> Dialogs.input(a, "Nazwa nowej grupy", "") { g ->
                    if (g.isNotBlank()) { Repository.edit(ch.key) { it.group = g }; onChanged() }
                }
                opts[w].startsWith("↺") -> { Repository.edit(ch.key) { it.group = null }; onChanged() }
                else -> { Repository.edit(ch.key) { it.group = opts[w] }; onChanged() }
            }
        }
    }

    fun position(a: Activity, ch: Channel, onChanged: () -> Unit) {
        val vis = Repository.channels
        Dialogs.input(a, "Numer kanału (1–${vis.size})", ch.number.takeIf { it > 0 }?.toString() ?: "", number = true) { v ->
            val n = v.toIntOrNull()?.coerceIn(1, vis.size) ?: return@input
            val target = vis[n - 1]
            if (target.key != ch.key) { Repository.moveChannel(ch.key, target.key); onChanged() }
        }
    }

    private data class EpgEntry(val id: String?, val label: String)

    /** Wybór kanału EPG z wyszukiwarką; propozycje podobnych nazw na górze. */
    fun epg(a: Activity, ch: Channel, onChanged: () -> Unit) {
        val names = Repository.epg.channelNames
        if (names.isEmpty()) {
            I18n.makeToast(a, "Najpierw wczytaj EPG w ustawieniach", Toast.LENGTH_LONG).show(); return
        }
        val n = XmltvParser.norm(ch.name); val n2 = XmltvParser.norm(ch.origName)
        fun score(id: String, name: String): Int {
            val m = XmltvParser.norm(name); val mi = XmltvParser.norm(id)
            return when {
                m == n || m == n2 || mi == n -> 3
                m.isNotEmpty() && (m.contains(n) || n.contains(m) || m.contains(n2)) -> 2
                n.length >= 3 && m.startsWith(n.take(3)) -> 1
                else -> 0
            }
        }
        val all = names.entries.map { (id, name) -> Triple(id, name, score(id, name)) }
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second.lowercase() })
        val current = ch.epgKey?.let { names[it] ?: it } ?: "brak"
        val fixed = listOf(
            EpgEntry(null, "⟳ Automatycznie (teraz: $current)"),
            EpgEntry("", "✕ Bez programu TV"),
        )
        fun build(q: String): List<EpgEntry> = fixed + all
            .filter { q.isBlank() || it.second.contains(q, true) || it.first.contains(q, true) }
            .map { EpgEntry(it.first, (if (it.third >= 2) "★ " else "") + "${it.second}   [${it.first}]") }

        var entries = build("")
        val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, entries.map { it.label }.toMutableList())
        val search = EditText(a).apply { hint = "Szukaj kanału EPG…"; setSingleLine() }
        val list = ListView(a).apply { this.adapter = adapter; isFocusable = true }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(16), 0, Ui.dp(16), 0)
            addView(search); addView(list, LinearLayout.LayoutParams(-1, Ui.dp(340)))
        }
        val dlg = Ui.dialog(a).setTitle("EPG dla: ${ch.name}").setView(box)
            .setNegativeButton("Anuluj", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                entries = build(s.toString()); adapter.clear(); adapter.addAll(entries.map { it.label })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            Repository.edit(ch.key) { it.epg = e.id }
            dlg.dismiss()
            (a as? LifecycleOwner)?.lifecycleScope?.launch {
                Repository.refreshEpgMapping()
                onChanged()
                val mapped = Repository.byKey(ch.key)?.epgKey
                I18n.makeToast(a, if (mapped != null) "EPG: ${names[mapped] ?: mapped}" else "Kanał bez EPG", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
        list.requestFocus()
    }
}

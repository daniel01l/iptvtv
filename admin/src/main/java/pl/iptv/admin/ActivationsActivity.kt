package pl.iptv.admin

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.iptv.shared.ActivationFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Aktywacje D-iptv Player (wersja zabezpieczona): klucz, GitHub, urządzenia testerów – aktywuj / zablokuj / usuń. */
class ActivationsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var info: TextView
    private val ACCENT = Color.parseColor("#2F80ED")
    private var remote: Activations.Remote? = null
    private var busy = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Admin.init(this); Activations.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(Color.parseColor("#0F1218")) }
        root.addView(text("🔐 Aktywacje – D-iptv Player", 21f, ACCENT, bold = true))
        info = text("", 13f, Color.LTGRAY)
        root.addView(row(button("⟳ Odśwież") { refresh() }, info))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render(); refresh()
    }

    // ---------------- widoki ----------------
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3))
    }
    private fun card(border: Int = Color.parseColor("#2A3140")) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.parseColor("#171C25")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
    private fun row(vararg v: View) = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(label: String, s: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, s)); toast("Skopiowano") }
    private fun share(s: String, title: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), title))
    private fun ago(ts: Long): String {
        if (ts <= 0) return "nigdy"
        val m = (System.currentTimeMillis() - ts) / 60_000
        return when { m < 1 -> "teraz"; m < 60 -> "$m min temu"; m < 1440 -> "${m / 60} godz. temu"; else -> SimpleDateFormat("dd.MM HH:mm", Locale("pl")).format(Date(ts)) }
    }

    private fun input(title: String, initial: String = "", hint: String = "", password: Boolean = false, codeLen: Int = 0, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(initial); this.hint = hint; setSingleLine()
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT }
        // kod: wielkie litery i myślnik co 4 znaki wstawiane automatycznie
        if (codeLen > 0) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.addTextChangedListener(object : android.text.TextWatcher {
                var busy = false
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (busy || s == null) return
                    val f = s.toString().uppercase().filter { it.isLetterOrDigit() }.take(codeLen).chunked(4).joinToString("-")
                    if (f != s.toString()) { busy = true; s.replace(0, s.length, f); busy = false }
                }
            })
        }
        AlertDialog.Builder(this).setTitle(title).setView(LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString().trim()) }.setNegativeButton("Anuluj", null).show()
    }

    // ---------------- ekran ----------------
    private fun render() {
        body.removeAllViews()
        // 1. klucz
        body.addView(card().apply {
            addView(text("1. Klucz podpisu (tylko na tym telefonie)", 15f, Color.WHITE, bold = true))
            if (!Activations.hasKey) {
                addView(text("Klucz podpisuje listę aktywacji. Aplikacja na urządzeniach ma tylko klucz publiczny – nie da się podrobić aktywacji.", 13f, Color.LTGRAY))
                addView(row(button("Wygeneruj klucz") { Activations.generateKey(); render(); keyBackupHint() }, button("Wczytaj kopię klucza") { importKey() }))
            } else {
                addView(text("Odcisk: ${ActivationFormat.fingerprint(Activations.publicKey)}", 13f, Color.LTGRAY))
                addView(text("Klucz publiczny (podaj go do zbudowania aplikacji):", 13f, Color.LTGRAY))
                addView(text(Activations.publicKey, 11f, Color.WHITE).apply { setTextIsSelectable(true) })
                addView(row(button("Kopiuj publiczny") { copy("klucz", Activations.publicKey) }, button("Udostępnij") { share(Activations.publicKey, "Klucz publiczny") }))
                addView(row(button("💾 Kopia klucza") { keyBackupHint() }, button("Wczytaj kopię") { importKey() }))
            }
        })
        // 2. GitHub
        body.addView(card().apply {
            addView(text("2. Repozytorium z listą aktywacji", 15f, Color.WHITE, bold = true))
            addView(text("Repozytorium: ${Activations.repo}\nToken: ${if (Activations.token.isBlank()) "brak" else "ustawiony (••••${Activations.token.takeLast(4)})"}", 13f, Color.LTGRAY))
            addView(row(button("Zmień repozytorium") { input("Repozytorium (właściciel/nazwa)", Activations.repo, "daniel01l/dplayer") { Activations.repo = it; render() } },
                button("Token") { input("Token GitHub (fine-grained, tylko to repozytorium, Contents: Read and write)", "", "github_pat_…", password = true) { if (it.isNotBlank()) { Activations.token = it; render(); refresh() } } }))
        })
        // 3. urządzenia
        val r = remote
        val seen = Activations.seen()
        body.addView(card().apply {
            addView(text("3. Urządzenia", 15f, Color.WHITE, bold = true))
            if (r != null && !r.validSignature) addView(text("⚠ Lista w repozytorium ma podpis innego klucza – zapis nadpisze ją Twoim kluczem.", 13f, Color.parseColor("#FF9800")))
            addView(row(button("➕ Aktywuj kod…") { addCode() }))
            addView(text("Kod pokazuje się na urządzeniu przy pierwszym uruchomieniu. Usunięcie z listy = aplikacja znowu prosi o aktywację.", 12f, Color.GRAY))
        })
        val entries = r?.entries.orEmpty()
        fun ed(e: String) = if (e == "full") "⭐ pełna" else "🧪 test"
        fun st(v: String) = when (v) { ActivationFormat.ACTIVE -> "aktywna"; ActivationFormat.BLOCKED -> "zablokowana"; else -> "—" }
        // oczekujące: zgłosiły się, a ich wersja (pełna/test) nie jest aktywowana
        seen.groupBy { it.code to it.edition }.forEach { (key, list) ->
            val (code, edition) = key
            val e = entries[code]
            if (e != null && e.forEdition(edition == "full").isNotEmpty()) return@forEach
            val s = list.maxBy { it.ts }
            body.addView(card(Color.parseColor("#FF9800")).apply {
                addView(text("🕓 " + deviceLabel(e?.note?.takeIf { it.isNotBlank() } ?: s.model, code) + " – ${ed(edition)} – oczekuje na aktywację", 17f, Color.WHITE, bold = true))
                addView(text("${s.model} • wersja ${s.version} • ${ago(s.ts)}" + (e?.note?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""), 13f, Color.LTGRAY))
                addView(row(button("✔ Aktywuj ${ed(edition)}") { setStatus(code, edition == "full", ActivationFormat.ACTIVE, null) }, button("Ukryj") { Activations.forget(code); render() }))
            })
        }
        entries.toSortedMap().forEach { (code, e) ->
            val last = seen.filter { it.code == code }.maxByOrNull { it.ts }
            val dup = Activations.duplicates(code)
            val anyActive = e.status == ActivationFormat.ACTIVE || e.full == ActivationFormat.ACTIVE
            val anyBlocked = e.status == ActivationFormat.BLOCKED || e.full == ActivationFormat.BLOCKED
            body.addView(card(if (dup > 1 || (anyBlocked && !anyActive)) Color.parseColor("#E53935") else Color.parseColor("#2E7D32")).apply {
                addView(text(deviceLabel(labelFor(code, e), code), 17f, Color.WHITE, bold = true))
                addView(text("⭐ Pełna: ${st(e.full)}     🧪 Test: ${st(e.status)}" + (if (e.admin.isNotEmpty()) "     🏠 moje urządzenie" else ""), 14f, Color.WHITE))
                addView(text("ostatnie zgłoszenie: ${ago(last?.ts ?: 0)}" + (last?.let { " • ${it.model} • ${ed(it.edition)} ${it.version}" } ?: ""), 13f, Color.LTGRAY))
                if (dup > 1) addView(text("⚠ Ten kod zgłosił się z $dup różnych instalacji tej samej wersji w ciągu godziny – możliwa kopia urządzenia.", 13f, Color.parseColor("#E53935")))
                setOnClickListener { deviceMenu(code, e) }
            })
        }
    }

    private fun keyBackupHint() {
        AlertDialog.Builder(this).setTitle("Kopia klucza – ważne")
            .setMessage("Bez klucza nie zmienisz listy aktywacji (np. po zmianie telefonu). Zapisz kopię w bezpiecznym miejscu (np. menedżer haseł, prywatna notatka). Nikomu jej nie wysyłaj – kto ma klucz, może aktywować urządzenia.")
            .setPositiveButton("Udostępnij kopię…") { _, _ -> share(Activations.exportKey(), "Kopia klucza D-iptv") }
            .setNeutralButton("Kopiuj") { _, _ -> copy("kopia klucza", Activations.exportKey()) }
            .setNegativeButton("Później", null).show()
    }

    private fun importKey() = input("Wklej kopię klucza") { t ->
        runCatching { Activations.importKey(t) }.onSuccess { toast("Klucz wczytany"); render() }.onFailure { toast("✖ ${it.message}") }
    }

    private fun addCode() = input("Kod urządzenia (XXXX-XXXX)", "", "K7QF-3M9X", codeLen = 8) { c ->
        val code = ActivationFormat.normalize(c)
        if (code.length != 9) { toast("Kod ma 8 znaków (XXXX-XXXX)"); return@input }
        AlertDialog.Builder(this).setTitle("Którą wersję aktywować?")
            .setItems(arrayOf("⭐ Pełna (D-iptv Player z repozytorium iptvtv)", "🧪 Test (D-iptv Player TEST)")) { _, w ->
                input("Notatka (np. imię) – opcjonalnie") { note -> setStatus(code, w == 0, ActivationFormat.ACTIVE, note) }
            }.show()
    }

    private fun deviceMenu(code: String, e: ActivationFormat.Entry) {
        fun toggle(full: Boolean): Pair<String, () -> Unit> {
            val cur = e.forEdition(full); val name = if (full) "⭐ Pełna" else "🧪 Test"
            return if (cur == ActivationFormat.ACTIVE) "$name: ⛔ zablokuj" to { setStatus(code, full, ActivationFormat.BLOCKED, null) }
            else "$name: ✅ aktywuj" to { setStatus(code, full, ActivationFormat.ACTIVE, null) }
        }
        val mine = e.admin.isNotEmpty()
        val items = linkedMapOf(
            toggle(true), toggle(false),
            (if (mine) "🏠 Usuń z „Moich urządzeń”" else "🏠 Dodaj do „Moich urządzeń” (raporty, zdalne zarządzanie)") to {
                if (mine) change("Usunięto z moich $code") { m -> m[code] = (m[code] ?: e).copy(admin = "") }
                else {
                    val sealed = Activations.sealAdminCodeFor(code)
                    if (sealed == null) toast("Urządzenie musi najpierw połączyć się z pełną wersją 5.4 lub nowszą (⟳ Odśwież po chwili)")
                    else if (e.full != ActivationFormat.ACTIVE) toast("Najpierw aktywuj ⭐ pełną wersję")
                    else { lastChanged = code; change("Dodano do moich $code") { m -> m[code] = (m[code] ?: e).copy(admin = sealed) } }
                }
            },
            "✎ Notatka" to { input("Notatka", e.note) { n -> change("Notatka $code") { m -> m[code] = (m[code] ?: e).copy(note = n) } } },
            "🗑 Usuń z listy (obie wersje znowu proszą o aktywację)" to {
                AlertDialog.Builder(this).setTitle("Usunąć $code?").setMessage("Urządzenie przestanie działać do ponownej aktywacji.")
                    .setPositiveButton("Usuń") { _, _ -> change("Usunięto $code") { it.remove(code) } }.setNegativeButton("Anuluj", null).show()
            },
            "Kopiuj kod" to { copy("kod", code) },
        )
        AlertDialog.Builder(this).setTitle(deviceLabel(labelFor(code, e), code)).setItems(items.keys.toTypedArray()) { _, w -> items.values.elementAt(w)() }.show()
    }

    private fun setStatus(code: String, full: Boolean, status: String, note: String?) =
        change((if (status == ActivationFormat.ACTIVE) "Aktywacja " else "Blokada ") + (if (full) "pełnej " else "testowej ") + code) { m ->
            val old = m[code] ?: ActivationFormat.Entry("")
            val n = note ?: old.note
            m[code] = if (full) old.copy(full = status, note = n, changed = System.currentTimeMillis())
                else old.copy(status = status, note = n, changed = System.currentTimeMillis())
        }

    private fun change(msg: String, op: (MutableMap<String, ActivationFormat.Entry>) -> Unit) {
        if (busy) return
        if (!Activations.hasKey) { toast("Najpierw wygeneruj klucz (krok 1)"); return }
        if (Activations.token.isBlank()) { toast("Najpierw wpisz token GitHub (krok 2)"); return }
        busy = true; info.text = "Zapisywanie…"
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Activations.update(msg, op) } }
            busy = false
            r.onSuccess { remote = it; syncMine(it); lastChanged = ""; info.text = "✔ $msg – urządzenie zastosuje zmianę w ciągu kilku minut"; render() }
                .onFailure { info.text = "✖ ${it.message}" }
        }
    }

    /** Etykieta urządzenia: notatka, a gdy jej brak – model z ostatniego zgłoszenia. */
    private fun labelFor(code: String, e: ActivationFormat.Entry?): String =
        e?.note?.takeIf { it.isNotBlank() } ?: Activations.seen().filter { it.code == code }.maxByOrNull { it.ts }?.model.orEmpty().ifBlank { "Urządzenie" }

    /** „Moje urządzenia” w panelu głównym zgodne z listą: dodane – od razu widoczne (oczekujące), usunięte – znikają. */
    private fun syncMine(r: Activations.Remote, add: Boolean = true) {
        r.entries.forEach { (code, e) -> if (e.admin.isEmpty()) Admin.removePending(code) else if (add && code == lastChanged) Admin.addPending(code, labelFor(code, e)) }
    }
    /** Kod ostatnio zmienianego wpisu (oczekujący wpis dodajemy tylko po własnej zmianie, nie przy każdym odświeżeniu). */
    private var lastChanged = ""

    private fun refresh() {
        if (busy) return
        info.text = "Odświeżanie…"
        lifecycleScope.launch {
            val n = runCatching { withContext(Dispatchers.IO) { Activations.pollCheckins() } }
            val r = if (Activations.token.isNotBlank()) runCatching { withContext(Dispatchers.IO) { Activations.load() } } else null
            r?.getOrNull()?.let { remote = it; syncMine(it, add = false) }
            info.text = when {
                r?.isFailure == true -> "✖ ${r.exceptionOrNull()?.message}"
                n.isFailure -> "✖ Zgłoszenia: ${n.exceptionOrNull()?.message}"
                Activations.token.isBlank() -> "Wpisz token GitHub, aby zarządzać listą"
                else -> "Zaktualizowano ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}"
            }
            render()
        }
    }
}

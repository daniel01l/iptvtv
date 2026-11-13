package pl.iptv.admin

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Panel administratora: wszystkie urządzenia IptvTv, ich stan i zdalne akcje. */
class MainActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFile: ((ByteArray?, String) -> Unit)? = null
    private val ACCENT = Color.parseColor("#2F80ED")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Admin.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(Color.parseColor("#0F1218")) }
        root.addView(text("D-iptv Admin", 24f, ACCENT, bold = true))
        root.addView(row(button("🔐 Aktywacje (pełna i test)") { startActivity(Intent(this, ActivationsActivity::class.java)) }))
        root.addView(card().apply {
            addView(text("Kod administratora (wpisz go na każdym TV: Ustawienia → Funkcje → Zdalne zarządzanie):", 13f, Color.LTGRAY))
            addView(text(Admin.code, 26f, Color.WHITE, bold = true).apply { id = 1; gravity = Gravity.CENTER; setTextIsSelectable(true) })
            addView(row(button("Kopiuj") { copy(Admin.code) }, button("Serwer") { editServer() }, button("Nowy kod") { newCode() }))
        })
        info = text("", 13f, Color.LTGRAY)
        root.addView(row(button("⟳ Odśwież") { refresh() }, button("📚 Listy") { listsManager() }, info))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private val auto = object : Runnable { override fun run() { refresh(quiet = true); handler.postDelayed(this, 30_000) } }
    // po powrocie (np. z Aktywacji) lista od razu z aktualnego stanu, potem pobranie raportów
    override fun onResume() { super.onResume(); render(); handler.post(auto) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(auto) }

    // ---------------- widoki ----------------
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.parseColor("#171C25")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#2A3140")) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
    private fun row(vararg v: android.view.View) = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) } }

    private fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60_000
        return when { m < 1 -> "teraz"; m < 60 -> "$m min temu"; m < 1440 -> "${m / 60} godz. temu"; else -> SimpleDateFormat("dd.MM HH:mm", Locale("pl")).format(Date(ts)) }
    }

    private fun render() {
        list.removeAllViews()
        val ds = Admin.devices()
        list.addView(text("🏠 Moje urządzenia", 18f, ACCENT, bold = true))
        if (ds.isEmpty()) list.addView(text("Brak urządzeń. Wpisz powyższy kod na telewizorze – urządzenie pojawi się w ciągu minuty.", 15f, Color.LTGRAY))
        else list.addView(text("🟢 raport z dzisiaj (wysyłany przy pierwszym włączeniu TV). Aktualny stan: dotknij urządzenia → „Poproś o raport”.", 12f, Color.GRAY))
        ds.forEach { d ->
            val j = d.j
            // dodane w Aktywacjach, czeka na pierwszy raport – widoczne od razu
            if (d.pending) {
                val c = card()
                c.addView(text("⏳ " + deviceLabel(d.name, d.displayCode), 18f, Color.WHITE, bold = true))
                c.addView(text("Dodano do „Moich urządzeń” ${ago(d.ts)}. Urządzenie odbierze zmianę i wyśle pierwszy raport w ciągu kilku minut (musi być włączone).", 13f, Color.LTGRAY))
                c.setOnClickListener {
                    AlertDialog.Builder(this).setTitle(deviceLabel(d.name, d.displayCode)).setItems(arrayOf("⟳ Sprawdź raporty", "Zmień nazwę (tylko w panelu)", "Usuń z panelu")) { _, w ->
                        when (w) { 0 -> refresh(); 1 -> input("Nazwa", d.name) { Admin.setAlias(d, it); render() }; else -> { Admin.remove(d); render() } }
                    }.show()
                }
                list.addView(c); return@forEach
            }
            val f = SimpleDateFormat("yyyyMMdd", Locale.US)
            val online = f.format(Date(d.ts)) == f.format(Date())       // raport z dzisiaj
            val c = card()
            c.addView(text((if (online) "🟢 " else "⚪ ") + deviceLabel(d.name, d.displayCode), 18f, Color.WHITE, bold = true))
            if (d.token.isNotBlank()) c.addView(text("🔑 Token: ${d.token}", 14f, Color.parseColor("#64B5F6")))
            c.addView(text("IP: ${j.optString("ip").ifBlank { "?" }}   •   PIN: ${d.pin}" + (if (d.act.isNotBlank()) "   •   Przekaźnik: ${d.code}" else "   •   Kod: ${d.code}"), 14f, Color.LTGRAY))
            val days = d.days
            val (dTxt, dCol) = when {
                days == -999 -> "bez daty ważności" to Color.LTGRAY
                days < 0 -> "lista wygasła" to Color.parseColor("#E53935")
                days <= 3 -> "$days dni (do ${j.optString("expiry")})" to Color.parseColor("#E53935")
                days <= 7 -> "$days dni (do ${j.optString("expiry")})" to Color.parseColor("#FF9800")
                else -> "$days dni (do ${j.optString("expiry")})" to Color.parseColor("#43A047")
            }
            c.addView(text("Lista: ${j.optString("list").ifBlank { "—" }} – $dTxt", 15f, dCol, bold = true))
            d.playlists.takeIf { it.size > 1 }?.let { pl -> c.addView(text("Listy na urządzeniu: " + pl.joinToString(", ") { (if (it.active) "✔ " else "") + it.name }, 13f, Color.LTGRAY)) }
            c.addView(text("Wersja ${j.optString("ver")} • kanały ${j.optInt("channels")} • z EPG ${j.optInt("epg")} • ${j.optString("model")}", 13f, Color.GRAY))
            j.optString("watching").takeIf { it.isNotBlank() }?.let { c.addView(text("Ostatni kanał: $it", 13f, Color.GRAY)) }
            c.addView(text("Raport: ${ago(d.ts)}" + j.optString("msg").takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty(), 13f, Color.GRAY))
            c.setOnClickListener { actions(d) }
            list.addView(c)
        }
    }

    private fun refresh(quiet: Boolean = false) = lifecycleScope.launch {
        if (!quiet) info.text = "Odświeżanie…"
        val r = runCatching { withContext(Dispatchers.IO) { Admin.poll() } }
        info.text = r.fold({ "Zaktualizowano ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}" + if (it > 0) " (+$it)" else "" }, { "Błąd: ${it.message}" })
        render()
    }

    // ---------------- akcje ----------------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun copy(s: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("kod", s)); toast("Skopiowano") }

    private fun input(title: String, initial: String = "", hint: String = "", ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(initial); this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString().trim()) }.setNegativeButton("Anuluj", null).show()
    }

    private fun run(label: String, op: () -> Unit) = lifecycleScope.launch {
        toast("$label…")
        runCatching { withContext(Dispatchers.IO) { op() } }
            .onSuccess { toast("✔ $label – urządzenie wykona je w ciągu ok. 30 s") }.onFailure { toast("✖ ${it.message}") }
    }

    private fun actions(d: Device) {
        val items = linkedMapOf<String, () -> Unit>(
            "📋 Wybierz aktywną listę…" to { chooseDeviceList(d) },
            "📚 Wyślij zapisaną listę…" to { chooseSavedList(d) },
            "Wyślij listę (plik .m3u)" to { pickFile { b, n -> b?.let { askList(d, "", it, n) } } },
            "Wyślij listę (link)" to { input("Link do listy (można użyć {token})", "", "http://…/get.php?token={token}") { u -> if (u.isNotBlank()) askList(d, u, null, "Lista") } },
            "Odśwież listę i EPG" to { run("Odświeżanie listy") { Admin.command(d, "refresh") } },
            "Ustaw link EPG" to { input("Link do EPG (.xml / .xml.gz, można użyć {token})", "", "http://…/epg.xml.gz") { u -> run("Ustawianie EPG") { Admin.command(d, "set_epg", mapOf("url" to u)) } } },
            "Token (notatka pod nazwą)" to { input("Token – informacja widoczna pod nazwą urządzenia (nie zmienia listy ani EPG)", d.token) { t -> Admin.setToken(d, t); render() } },
            "Ustaw datę ważności listy" to { input("Data (DD.MM.RRRR)", "", "31.12.2026") { v -> run("Data ważności") { Admin.command(d, "set_expiry", mapOf("date" to v)) } } },
            "Zaktualizuj aplikację (z adresu GitHub)" to { run("Aktualizacja") { Admin.command(d, "update") } },
            "Ustaw adres aktualizacji" to { input("Adres (iptvtv.json lub .apk)", "", "https://github.com/…/latest/download/iptvtv.json") { u -> run("Adres aktualizacji") { Admin.command(d, "set_update_url", mapOf("url" to u)) } } },
            "Wyślij plik aktualizacji (.apk)" to { pickFile { b, _ -> b?.let { run("Wysyłanie aktualizacji") { Admin.sendApk(d, it) } } } },
            "Poproś o raport" to { run("Prośba o raport") { Admin.command(d, "report") } },
            "Kopiuj dane urządzenia" to { copy("${d.name}\nIP: ${d.j.optString("ip")}\nKod: ${d.code}\nPIN: ${d.pin}") },
            "Zmień nazwę (tylko w panelu)" to { input("Nazwa", d.name) { Admin.setAlias(d, it); render() } },
            "Usuń z panelu" to { Admin.remove(d); render() },
        )
        AlertDialog.Builder(this).setTitle(deviceLabel(d.name, d.displayCode)).setItems(items.keys.toTypedArray()) { _, w -> items.values.elementAt(w)() }.show()
    }

    // ---------------- listy ----------------

    /** Wybór aktywnej listy spośród list zapisanych na urządzeniu (z ostatniego raportu). */
    private fun chooseDeviceList(d: Device) {
        val pl = d.playlists
        if (pl.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Listy na urządzeniu").setMessage("Brak informacji o listach – urządzenie wyśle je w raporcie (wymagana wersja 5.6). Poprosić o raport?")
                .setPositiveButton("Poproś o raport") { _, _ -> run("Prośba o raport") { Admin.command(d, "report") } }.setNegativeButton("Anuluj", null).show()
            return
        }
        val cur = pl.indexOfFirst { it.active }
        var pick = cur
        AlertDialog.Builder(this).setTitle("Aktywna lista – ${deviceLabel(d.name, d.displayCode)}")
            .setSingleChoiceItems(pl.map { it.name + (if (it.expiry.isNotBlank()) "  (do ${it.expiry})" else "") }.toTypedArray(), cur) { _, w -> pick = w }
            .setPositiveButton("Ustaw") { _, _ ->
                val p = pl.getOrNull(pick) ?: return@setPositiveButton
                if (pick == cur) toast("„${p.name}” jest już aktywna") else run("Zmiana listy na „${p.name}”") { Admin.useList(d, p.id) }
            }
            .setNeutralButton("📚 Wyślij zapisaną…") { _, _ -> chooseSavedList(d) }
            .setNegativeButton("Anuluj", null).show()
    }

    /** Wysyłka listy z „📚 Listy” (od razu aktywna na urządzeniu). */
    private fun chooseSavedList(d: Device) {
        val saved = Admin.savedLists()
        if (saved.isEmpty()) { toast("Brak zapisanych list – dodaj je przyciskiem „📚 Listy”"); listsManager(); return }
        var pick = 0
        AlertDialog.Builder(this).setTitle("Wyślij listę – ${deviceLabel(d.name, d.displayCode)}")
            .setSingleChoiceItems(saved.map { it.name }.toTypedArray(), 0) { _, w -> pick = w }
            .setPositiveButton("Wyślij") { _, _ ->
                val l = saved.getOrNull(pick) ?: return@setPositiveButton
                run("Wysyłanie listy „${l.name}”") { Admin.sendList(d, l.name, l.url, null, l.epg, l.expiry) }
            }.setNegativeButton("Anuluj", null).show()
    }

    /** Zapisane listy (nazwa, link, EPG, data ważności) – do szybkiego wysyłania na urządzenia. */
    private fun listsManager() {
        val saved = Admin.savedLists()
        val names = saved.map { "📋 ${it.name}" } + "➕ Dodaj listę…"
        AlertDialog.Builder(this).setTitle("📚 Zapisane listy").setItems(names.toTypedArray()) { _, w ->
            val l = saved.getOrNull(w)
            if (l == null) editSavedList(null)
            else AlertDialog.Builder(this).setTitle(l.name).setMessage("Link: ${l.url}\nEPG: ${l.epg.ifBlank { "—" }}\nWażna do: ${l.expiry.ifBlank { "—" }}")
                .setPositiveButton("Edytuj") { _, _ -> editSavedList(l) }
                .setNeutralButton("Usuń") { _, _ -> Admin.removeList(l.name); listsManager() }
                .setNegativeButton("Zamknij", null).show()
        }.setNegativeButton("Zamknij", null).show()
    }

    private fun editSavedList(old: SavedList?) {
        input("Nazwa listy", old?.name.orEmpty()) { name ->
            if (name.isBlank()) { toast("Podaj nazwę"); return@input }
            input("Link do listy (można użyć {token})", old?.url.orEmpty(), "http://…/get.php?token={token}") { url ->
                if (!url.startsWith("http")) { toast("Link musi zaczynać się od http"); return@input }
                input("Link do EPG (opcjonalnie)", old?.epg.orEmpty(), "https://…/epg.xml") { epg ->
                    input("Data ważności (opcjonalnie, DD.MM.RRRR)", old?.expiry.orEmpty(), "31.12.2026") { exp ->
                        if (old != null && old.name != name) Admin.removeList(old.name)
                        Admin.putList(SavedList(name, url, epg, exp)); toast("Zapisano listę „$name”"); listsManager()
                    }
                }
            }
        }
    }

    private fun askList(d: Device, url: String, bytes: ByteArray?, defName: String) {
        input("Nazwa listy", defName) { name ->
            input("Data ważności (opcjonalnie, DD.MM.RRRR)", "", "31.12.2026") { exp ->
                input("Link do EPG (opcjonalnie – puste: zostaje obecne)", "", "https://…/epg.xml") { epg ->
                    run("Wysyłanie listy") { Admin.sendList(d, name, url, bytes, epg, exp) }
                }
            }
        }
    }

    private fun pickFile(done: (ByteArray?, String) -> Unit) {
        pendingFile = done
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val uri = data?.data ?: return
        val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        pendingFile?.invoke(bytes, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Lista"); pendingFile = null
    }

    private fun editServer() = input("Serwer przekaźnika (puste = ntfy.sh)", Admin.server) { Admin.server = it; refresh() }

    private fun newCode() = AlertDialog.Builder(this).setTitle("Nowy kod administratora?")
        .setMessage("Wszystkie telewizory trzeba będzie połączyć ponownie nowym kodem.")
        .setPositiveButton("Tak") { _, _ -> Admin.code = pl.iptv.shared.RelayCrypto.newCode(); recreate() }.setNegativeButton("Nie", null).show()
}

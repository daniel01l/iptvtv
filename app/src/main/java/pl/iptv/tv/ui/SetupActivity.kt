package pl.iptv.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StartActions

/** Kreator pierwszej konfiguracji: uprawnienia → lista → EPG → catch-up → wygląd → uruchamianie. */
class SetupActivity : BaseActivity() {
    companion object { const val EXTRA_ADD_LIST = "add_list"; const val DEFAULT_EPG = "https://epg.ovh/plar.xml https://epg.ovh/pltv.xml"; private const val REQ_BACKUP = 11 }
    private val titles = listOf("Witaj", "Lista kanałów", "Program TV (EPG) i picony", "Catch-up (archiwum)", "Wygląd", "Uruchamianie")
    /** Tryb „Dodaj listę”: tylko kroki lista → EPG → catch-up, zapisywane w nowej liście. */
    private val addMode by lazy { intent.getBooleanExtra(EXTRA_ADD_LIST, false) }
    private val order by lazy { if (addMode) listOf(1, 2, 3) else titles.indices.toList() }
    private var pos = 0
    private val step get() = order[pos]
    /** Lista utworzona w trybie dodawania. */
    private var newId: String? = null
    private lateinit var head: TextView
    private lateinit var stepsBar: LinearLayout
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnBack: TextView
    private lateinit var btnNext: TextView
    private var field: EditText? = null
    private var field2: EditText? = null
    private var fieldName: EditText? = null
    private var fieldExpiry: EditText? = null
    private var status: TextView? = null
    private var busy = false
    private var wizard: PermissionWizard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pos = savedInstanceState?.getInt("step") ?: 0
        newId = savedInstanceState?.getString("newId")
        // telefon: węższe marginesy, pasek kroków przewijany w bok
        val narrow = resources.configuration.screenWidthDp < 600
        val side = if (narrow) 12 else 48
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(side), Ui.dp(if (narrow) 12 else 20), Ui.dp(side), Ui.dp(12)) }
        head = TextView(this).apply { textSize = if (narrow) 19f else 24f; tag = "accent"; setTypeface(typeface, 1) }
        root.addView(head)
        stepsBar = LinearLayout(this).apply { setPadding(0, Ui.dp(8), 0, Ui.dp(8)) }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; addView(stepsBar) })
        scroll = ScrollView(this).apply { isFocusable = false }
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "panel"
            setPadding(Ui.dp(if (narrow) 10 else 18), Ui.dp(14), Ui.dp(if (narrow) 10 else 18), Ui.dp(18)) }
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(12), 0, 0) }
        btnBack = navButton("◄ Wstecz") { go(pos - 1) }
        nav.addView(btnBack)
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(navButton("Pomiń krok") { if (addMode && step == 1 && newId == null) toast("Najpierw podaj adres listy") else go(pos + 1) })
        btnNext = navButton("Dalej ►") { next() }
        nav.addView(btnNext)
        root.addView(nav)
        setThemedContent(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (pos > 0) go(pos - 1) else if (addMode) finish() else finishSetup() }
        })
        build()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("step", pos); out.putString("newId", newId) }
    override fun onResume() { super.onResume(); wizard?.onResume(); if (wizard?.running != true && step == 0) build() }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); wizard?.onPermissionResult(rc) }

    private fun navButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        val narrow = resources.configuration.screenWidthDp < 600
        text = t; tag = "item"; textSize = if (narrow) 15f else 17f; isFocusable = true; isClickable = true; maxLines = 1
        setPadding(Ui.dp(if (narrow) 12 else 22), Ui.dp(12), Ui.dp(if (narrow) 12 else 22), Ui.dp(12))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(if (narrow) 6 else 10) }
        setOnClickListener { if (!busy) onClick() }
    }

    private fun go(p: Int) {
        if (p < 0) return
        if (p >= order.size) { finishSetup(); return }
        saveStep(); pos = p; build()
    }

    private fun finishSetup() {
        saveStep()
        if (addMode) {
            Playlists.captureActive()
            toast(Playlists.active()?.let { "Dodano listę: ${it.name}" } ?: "Gotowe")
            finish(); return
        }
        Prefs.setupDone = true; Prefs.permissionsAsked = true
        Ui.changed()
        finish()
    }

    /** „Dalej”: na kroku listy/EPG najpierw wczytuje wpisany adres. */
    private fun next() {
        when (step) {
            1 -> {
                val src = field?.text?.toString()?.trim().orEmpty()
                val need = src.isNotBlank() && (src != Prefs.playlistSource || Repository.allChannels.isEmpty() || (addMode && newId == null))
                if (need) loadPlaylist(src) { ok -> if (ok) { saveStep(); go(pos + 1) } }
                else if (addMode && newId == null) setStatus("Podaj link lub plik listy.")
                else if (Repository.allChannels.isEmpty()) {
                    Dialogs.confirm(this, "Brak listy", "Nie wczytano listy kanałów. Przejść dalej mimo to?") { go(pos + 1) }
                } else go(pos + 1)
            }
            2 -> {
                saveStep()
                if (Prefs.effectiveEpgUrl.isNotBlank() && Repository.epg.programmes.isEmpty()) loadEpg { go(pos + 1) } else go(pos + 1)
            }
            else -> go(pos + 1)
        }
    }

    private fun saveStep() {
        when (step) {
            1 -> Playlists.active()?.let { a ->
                val exp = fieldExpiry?.text?.toString()?.let { Playlists.parseDate(it) }
                if (exp == null && fieldExpiry?.text?.isNotBlank() == true) toast("Błędna data ważności – pominięto (DD.MM.RRRR)")
                Playlists.update(a.copy(name = fieldName?.text?.toString()?.trim()?.ifBlank { a.name } ?: a.name, expiry = exp ?: a.expiry))
            }
            2 -> { field?.let { Prefs.epgUrl = it.text.toString().trim() }; field2?.let { Prefs.piconUrl = it.text.toString().trim() }; Playlists.captureActive() }
            3 -> Playlists.captureActive()
        }
    }

    // ---------------- budowa kroku ----------------

    private fun build() {
        head.text = (if (addMode) "Nowa lista" else "Konfiguracja") + "  •  krok ${pos + 1}/${order.size}: ${titles[step]}"
        stepsBar.removeAllViews()
        order.forEachIndexed { i, st ->
            stepsBar.addView(TextView(this).apply {
                text = "${i + 1}. ${titles[st]}"; textSize = 12f; tag = if (i == pos) "accent" else "text2"
                setPadding(0, 0, Ui.dp(14), 0); if (i == pos) setTypeface(typeface, 1)
            })
        }
        container.removeAllViews(); field = null; field2 = null; fieldName = null; fieldExpiry = null; status = null
        when (step) { 0 -> stepWelcome(); 1 -> stepPlaylist(); 2 -> stepEpg(); 3 -> stepCatchup(); 4 -> stepLook(); else -> stepStart() }
        btnBack.visibility = if (pos == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = if (pos == order.lastIndex) (if (addMode) "✓ Zapisz listę" else "✓ Zakończ") else "Dalej ►"
        applyTheme()
        scroll.scrollTo(0, 0)
        scroll.post { (field ?: firstFocusableDeep(container) ?: btnNext).requestFocus() }
    }

    private fun firstFocusableDeep(v: View): View? {
        if (v !== container && v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusableDeep(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun firstFocusable(): View? = (0 until container.childCount).map { container.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun text(t: String, size: Float = 15f, tagS: String? = null) =
        container.addView(TextView(this).apply { text = t; textSize = size; tag = tagS; setPadding(0, Ui.dp(4), 0, Ui.dp(4)) })

    private fun row(title: String, value: String? = null, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            tag = "item"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12)); setOnClickListener { if (!busy) onClick() }
        }
        r.addView(TextView(this).apply { text = Ui.stripIcon(title); textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(TextView(this).apply { text = value; textSize = 15f; tag = "text2" })
        container.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(4) })
        return r
    }

    private fun choice(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) =
        row(title, options.getOrNull(get()) ?: "") { Dialogs.choice(this, title, options, get()) { set(it); build() } }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        row(title, if (get()) "✔ Włączone" else "✖ Wyłączone") { set(!get()); build() }

    private fun input(label: String, initial: String, hint: String): EditText {
        text(label, 14f, "text2")
        val e = EditText(this).apply {
            setText(initial); this.hint = hint; tag = "field"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10))
        }
        container.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun statusLine(t: String) { status = TextView(this).apply { text = t; tag = "accent"; textSize = 15f; setPadding(0, Ui.dp(10), 0, 0) }; container.addView(status) }
    private fun setStatus(t: String) { status?.text = t }

    // ---------------- kroki ----------------

    private fun stepWelcome() {
        // (7.8) język interfejsu – od razu na początku (napis dwujęzyczny: do znalezienia w obu językach)
        choice("Język / Language", I18n.LANGS.map { it.second }, { I18n.LANGS.indexOfFirst { it.first == I18n.lang }.coerceAtLeast(0) }) {
            I18n.setLanguage(this, I18n.LANGS[it].first); I18n.attach(window)
        }
        text("Ten kreator pomoże skonfigurować aplikację w kilku krokach: lista kanałów, program TV, archiwum (catch-up), wygląd i uruchamianie.", 17f)
        text("Każdy krok możesz pominąć, a wszystkie ustawienia zmienisz później w ⚙ Ustawieniach. Pilot: strzałki i OK, dotyk: stuknij.", 14f, "text2")
        val missing = PermissionWizard.missing(this)
        row("🔐 Nadaj uprawnienia (pliki, powiadomienia, praca w tle…)", PermissionWizard.summary(this)) {
            if (missing.isEmpty()) toast("Wszystkie uprawnienia są nadane")
            else wizard = PermissionWizard(this) { wizard = null; build() }.also { it.start() }
        }
        missing.forEach { text("✖ ${it.title}", 13f, "text2") }
    }

    private fun stepPlaylist() {
        text("Podaj link do listy M3U od dostawcy albo ścieżkę pliku na urządzeniu.", 16f)
        val act = if (addMode && newId == null) null else Playlists.active()
        field = input("Link (http/https) lub ścieżka, np. /sdcard/Download/lista.m3u", act?.source ?: "", "http://…/lista.m3u")
        fieldName = input("Nazwa listy (opcjonalnie)", act?.name ?: "", "np. Moja telewizja")
        fieldExpiry = input("Data ważności listy (opcjonalnie, DD.MM.RRRR) – liczba dni pojawi się w menu",
            Playlists.formatDate(act?.expiry ?: 0), "31.12.2026")
        row("⬇ Wczytaj listę") { loadPlaylist(field!!.text.toString().trim()) {} }
        row("📂 Wybierz plik .m3u z urządzenia…") { pickFile() }
        text("…albo wyślij listę na to urządzenie z telefonu lub komputera:", 15f, "accent")
        row("📶 Zdalnie przez Wi-Fi (ta sama sieć)") { remoteWifi() }
        row("🌐 Zdalnie przez Internet (kod urządzenia)") { remoteInternet() }
        if (!pl.iptv.tv.BuildConfig.SECURE) row("🛡 Przez aplikację D-iptv Admin (kod administratora)", Prefs.adminCode.ifBlank { null }) { remoteAdmin() }
        if (!addMode) {
            text("…albo przywróć wszystko z kopii zapasowej (listy, EPG, wygląd, ulubione, przypomnienia):", 15f, "accent")
            row("💾 Wczytaj kopię ustawień z pliku…") { pickBackup() }
        }
        statusLine(if (Repository.allChannels.isEmpty() || (addMode && newId == null)) "Lista nie jest jeszcze wczytana."
            else "✔ Wczytano ${Repository.allChannels.size} kanałów w ${Repository.realGroups.size} grupach.")
    }

    private fun stepEpg() {
        val auto = Prefs.playlistEpgUrl
        // domyślny program TV (polskie kanały), gdy lista nie ma własnego adresu
        if (Prefs.epgUrl.isBlank() && auto.isBlank()) Prefs.epgUrl = DEFAULT_EPG
        text(if (auto.isNotBlank()) "Lista zawiera adres programu TV:\n$auto\nZostaw puste pole, aby go użyć, albo podaj inny."
            else "Domyślnie ustawiony jest program TV:\n• https://epg.ovh/plar.xml\n• https://epg.ovh/pltv.xml\n" +
                "Możesz dopisać własny adres (oddziel spacją) albo zastąpić go swoim (.xml lub .xml.gz).", 15f)
        field = input("Link do EPG (XMLTV) – kilka adresów oddziel spacją", Prefs.epgUrl, auto.ifBlank { "http://…/epg.xml.gz" })
        row("⬇ Wczytaj EPG teraz") { Prefs.epgUrl = field!!.text.toString().trim(); loadEpg {} }
        field2 = input("Picony – katalog lub szablon z {id} / {name} (opcjonalnie, domyślnie logo z listy)", Prefs.piconUrl, "http://…/picons/{name}.png")
        val mapped = Repository.allChannels.count { it.epgKey != null }
        statusLine(if (Repository.epg.programmes.isEmpty()) "EPG nie jest jeszcze wczytane."
            else "✔ EPG: dopasowano $mapped/${Repository.allChannels.size} kanałów. Pozostałe przypiszesz w ✎ Edytorze → Mapowanie EPG.")
    }

    private fun stepCatchup() {
        val all = Repository.allChannels
        val withCu = all.count { it.hasCatchup }
        text("Catch-up pozwala oglądać programy, które już się skończyły (archiwum).", 16f)
        text("Kanały z archiwum według listy: $withCu/${all.size}.", 15f, "accent")
        text(if (withCu > 0) "Lista zawiera informacje o archiwum – zwykle nic nie trzeba zmieniać."
            else "Lista nie zawiera informacji o archiwum. Jeśli Twój dostawca je udostępnia, wybierz jego typ (zapytaj dostawcę lub sprawdź jego instrukcję).", 14f, "text2")
        val types = listOf("" to "Wyłączone – tylko wg listy", "shift" to "shift (?utc=…&lutc=…)", "default" to "default (własny szablon)",
            "append" to "append (doklejany szablon)", "flussonic" to "Flussonic", "xc" to "Xtream Codes")
        choice("Typ archiwum dla kanałów bez informacji", types.map { it.second },
            { types.indexOfFirst { it.first == Prefs.catchupDefaultType }.coerceAtLeast(0) }) {
            Prefs.catchupDefaultType = types[it].first; Playlists.captureActive(); Repository.reapply()
        }
        if (Prefs.catchupDefaultType in listOf("default", "append")) row("Szablon", Prefs.catchupDefaultSource.ifBlank { "nie ustawiono" }) {
            Dialogs.input(this, "Szablon catch-up", Prefs.catchupDefaultSource, "?utc={utc}&lutc={lutc}") { v ->
                Prefs.catchupDefaultSource = v; Playlists.captureActive(); Repository.reapply(); build()
            }
        }
        if (Prefs.catchupDefaultType.isNotBlank()) {
            val days = listOf(1, 2, 3, 5, 7, 14)
            choice("Długość archiwum", days.map { "$it dni" }, { days.indexOf(Prefs.catchupDefaultDays).coerceAtLeast(0) }) {
                Prefs.catchupDefaultDays = days[it]; Playlists.captureActive(); Repository.reapply()
            }
            toggle("Nadpisz również kanały z catch-up w liście", { Prefs.catchupForce }) { Prefs.catchupForce = it; Playlists.captureActive(); Repository.reapply() }
            statusLine("Kanały z archiwum po zmianie: ${Repository.allChannels.count { it.hasCatchup }}/${all.size}")
        }
    }

    private fun stepLook() {
        text("Wybierz motyw – zmiana jest widoczna od razu. Szczegóły (kolory, gradienty, czcionki, animacje) ustawisz później w Ustawienia → Wygląd.", 15f)
        Ui.PRESETS.forEach { p ->
            val r = row(p.name + if (Ui.preset.id == p.id) "  ✔" else "", "■ ■ ■") { Ui.applyPreset(p.id); Ui.changed(); build() }
            ((r as LinearLayout).getChildAt(1) as TextView).apply {
                tag = "keepcolor"
                text = android.text.SpannableStringBuilder().apply {
                    listOf(p.bg, p.panel, p.accent, p.focus).forEach { c ->
                        val st = length; append("■ ")
                        setSpan(android.text.style.ForegroundColorSpan(c), st, st + 1, 0)
                    }
                }
            }
        }
        choice("Wielkość tekstu", Ui.FONT_SCALES.map { it.second }, { Ui.fontScaleIndex }) { Ui.fontScaleIndex = it; Ui.changed() }
        choice("Czcionka", Ui.FONTS.map { it.label }, { Ui.fontIndex }) { Ui.fontIndex = it; Ui.changed() }
    }

    private fun stepStart() {
        text("Co ma się dziać po włączeniu aplikacji?", 16f)
        choice("Po włączeniu aplikacji", StartActions.LABELS, { Prefs.startAction }) { Prefs.startAction = it }
        toggle("Uruchamiaj po włączeniu urządzenia", { Prefs.bootStart }) { Prefs.bootStart = it }
        text("Na telewizorach, które zamykają aplikację po starcie, włącz w Ustawienia → Funkcje „Tryb launchera”.", 13f, "text2")
        statusLine("Gotowe! Wybierz „✓ Zakończ”, aby przejść do listy kanałów.")
    }

    // ---------------- lista zdalnie ----------------

    /** Po odebraniu listy (Wi-Fi / Internet / administrator) kreator od razu ją pokazuje. */
    private fun listenRemote() {
        Prefs.remoteAutoActivate = true
        pl.iptv.tv.remote.RemoteServer.listener = { if (step == 1) { build(); setStatus("✔ Odebrano listę: ${Repository.allChannels.size} kanałów.") } }
    }

    private fun remoteWifi() {
        Prefs.remoteOn = true; pl.iptv.tv.remote.RemoteServer.start(this); listenRemote()
        val ip = pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull()
        Ui.dialog(this).setTitle("📶 Lista przez Wi-Fi").setMessage(
            "Na telefonie lub komputerze w tej samej sieci otwórz w przeglądarce:\n\nhttp://${ip ?: "?"}:${pl.iptv.tv.remote.RemoteServer.PORT}\n\n" +
            "PIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}\n\nAlbo w D-iptv Player na telefonie: Ustawienia → Listy → Wyślij w tej samej sieci Wi-Fi.\n" +
            "Lista pojawi się tutaj automatycznie.").setPositiveButton("OK", null).show()
    }

    private fun remoteInternet() {
        Prefs.relayOn = true; pl.iptv.tv.remote.RemoteRelay.start(this); listenRemote()
        Ui.dialog(this).setTitle("🌐 Lista przez Internet").setMessage(
            "Kod urządzenia: ${pl.iptv.tv.remote.RemoteRelay.ensureCode()}\nPIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}\n\n" +
            "W D-iptv Player na telefonie (dowolna sieć): Ustawienia → Listy → Wyślij przez Internet i wpisz kod oraz PIN.\n" +
            "Urządzenie sprawdza skrzynkę co 30 s – lista pojawi się tutaj automatycznie.").setPositiveButton("OK", null).show()
    }

    private fun remoteAdmin() {
        Dialogs.input(this, "Kod z aplikacji D-iptv Admin (XXXX-XXXX-XXXX)", Prefs.adminCode, "ABCD-EFGH-JKMN", code = true) { v ->
            if (pl.iptv.tv.remote.RemoteRelay.normalize(v).length != 12) { toast("Kod ma 12 znaków"); return@input }
            Prefs.adminCode = v.uppercase(); Prefs.relayOn = true
            pl.iptv.tv.remote.RemoteRelay.start(this); listenRemote()
            lifecycleScope.launch {
                val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pl.iptv.tv.remote.RemoteRelay.report("Kreator konfiguracji") } }
                toast(if (r.isSuccess) "Połączono z administratorem – może teraz wysłać listę" else "Błąd: ${r.exceptionOrNull()?.message}")
                build()
            }
        }
    }

    // ---------------- wczytywanie ----------------

    private fun loadPlaylist(src: String, done: (Boolean) -> Unit) {
        if (src.isBlank()) { setStatus("Podaj adres listy."); done(false); return }
        if (!src.startsWith("http", true) && !src.startsWith("content:") && !isAppPrivate(src) && Build.VERSION.SDK_INT >= 30 &&
            !runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)) {
            setStatus("Brak dostępu do plików – nadaj uprawnienie lub użyj „Wybierz plik”.")
            wizard = PermissionWizard(this) { wizard = null }.also { it.start() }
            done(false); return
        }
        busy = true; setStatus("Wczytywanie listy…")
        lifecycleScope.launch {
            runCatching {
                if (addMode && newId == null) {
                    val p = Playlists.add(fieldName?.text?.toString()?.trim().orEmpty(), src, "",
                        Playlists.parseDate(fieldExpiry?.text?.toString().orEmpty()) ?: 0L)
                    newId = p.id
                    Repository.switchPlaylist(p.id)
                } else Repository.loadPlaylist(src)
            }
                .onSuccess { n -> busy = false; setStatus("✔ Wczytano $n kanałów."); done(true) }
                .onFailure { busy = false; setStatus("✖ Błąd: ${it.message}"); done(false) }
        }
    }

    private fun loadEpg(done: () -> Unit) {
        if (Prefs.effectiveEpgUrl.isBlank()) { setStatus("Brak adresu EPG."); done(); return }
        busy = true; setStatus("Pobieranie EPG… (może potrwać)")
        lifecycleScope.launch {
            runCatching { Repository.loadEpg() }
                .onSuccess { busy = false; setStatus("✔ EPG: dopasowano ${Repository.allChannels.count { c -> c.epgKey != null }}/${Repository.allChannels.size} kanałów.") }
                .onFailure { busy = false; setStatus("✖ Błąd EPG: ${it.message}") }
            done()
        }
    }

    private fun pickFile() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(open, 10) } catch (_: ActivityNotFoundException) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, 10) }
            catch (_: ActivityNotFoundException) { setStatus("Brak menedżera plików – wpisz ścieżkę pliku.") }
        }
    }

    @Deprecated("Deprecated in Java")
    private fun pickBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, REQ_BACKUP) } catch (_: ActivityNotFoundException) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, REQ_BACKUP) }
            catch (_: ActivityNotFoundException) { setStatus("Brak menedżera plików.") }
        }
    }

    /** Kopia zapasowa w kreatorze: ustawienia, lista i EPG wczytane od razu (bez ponownego uruchamiania), potem koniec kreatora. */
    private fun importBackup(uri: android.net.Uri) {
        val json = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { setStatus("✖ Nie można odczytać pliku: ${it.message}"); toast("Nie można odczytać pliku"); return }
        val n = runCatching { pl.iptv.tv.data.Backup.import(this, json) }
            .getOrElse { setStatus("✖ ${it.message}"); toast("${it.message}"); return }
        Prefs.setupDone = true; Prefs.permissionsAsked = true
        Ui.changed(); applyTheme()
        if (Prefs.remoteOn) pl.iptv.tv.remote.RemoteServer.start(this)
        if (Prefs.relayOn) pl.iptv.tv.remote.RemoteRelay.start(this)
        busy = true; setStatus("✔ Przywrócono $n ustawień. Wczytywanie listy kanałów…")
        lifecycleScope.launch {
            val r = runCatching { Repository.reloadAfterRestore() }
            busy = false
            val msg = r.fold({ "Przywrócono kopię: $n ustawień, $it kanałów." },
                { "Przywrócono $n ustawień. Listy nie udało się wczytać (${it.message}) – sprawdź ją w Ustawienia → Listy." })
            toast(msg)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == REQ_BACKUP && resultCode == RESULT_OK) { importBackup(uri); return }
        if (requestCode != 10 || resultCode != RESULT_OK) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        field?.setText(uri.toString())
        loadPlaylist(uri.toString()) {}
    }

    /** Pliki w folderach aplikacji nie wymagają uprawnień. */
    private fun isAppPrivate(path: String): Boolean {
        val p = path.removePrefix("file://")
        return listOfNotNull(filesDir.parentFile?.path, getExternalFilesDir(null)?.parentFile?.path).any { p.startsWith(it) }
    }

    private fun toast(s: String) = I18n.makeToast(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

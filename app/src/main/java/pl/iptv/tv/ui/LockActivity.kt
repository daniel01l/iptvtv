package pl.iptv.tv.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import pl.iptv.tv.BuildConfig
import pl.iptv.tv.data.Activation
import pl.iptv.tv.data.Activation.State

/**
 * Ekran wersji zabezpieczonej: zgoda (co wysyła aplikacja), kod urządzenia do aktywacji, blokada.
 * Nie da się go ominąć – WSTECZ zamyka aplikację.
 */
class LockActivity : AppCompatActivity() {
    /** (7.20) Siatka interfejsu TV 960 × 540 dp (1920 × 1080) niezależnie od DPI systemu – [pl.iptv.tv.ui.TvResolution]. */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(pl.iptv.tv.ui.TvResolution.wrap(newBase)) }
    override fun applyOverrideConfiguration(o: android.content.res.Configuration?) { super.applyOverrideConfiguration(pl.iptv.tv.ui.TvResolution.adjust(this, o)) }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    /** (7.25) Stan i błąd pokazane na ekranie – widok przebudowywany tylko przy zmianie (odpytywanie co [Activation.POLL_MS]). */
    private var shownState: State? = null
    private var shownError: String? = null
    private var unlocked = false
    /** Linia „Sprawdzono …” – aktualizowana bez przebudowy ekranu. */
    private var checkedTv: TextView? = null
    /** Liczba sprawdzeń z odpytywania (testy). */
    var polls = 0; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        pl.iptv.tv.ui.TvResolution.applyMetrics(resources); pl.iptv.tv.ui.TvResolution.applyMetrics(applicationContext.resources)   // (7.20) siatka TV 960 × 540 dp
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.bg)
        if (ModernV2.active) window.decorView.background = Ui.bgDrawable()      // (v2) granat → czerń z poświatą (GlassBackdrop)
        box =LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; val s = Ui.dp(if (resources.configuration.screenWidthDp < 600) 16 else 28); setPadding(s, Ui.dp(24), s, Ui.dp(24)) }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(box) }
        setContentView(sv)
        // (7.23) telefon: treść pod paskami systemowymi z własnymi odstępami (pasek stanu / nawigacji / wycięcie) – przyciski nie są zasłonięte
        if (!Tutorial.isTv(this)) {
            runCatching { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false) }
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sv) { v, ins ->
                val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finishAffinity() } })
        Playback.stop()
        render()
        startPolling()
    }

    /**
     * (7.25) Odpytywanie powiązane z cyklem życia: co [Activation.POLL_MS] od razu po wejściu na ekran (STARTED), zatrzymane
     * po wyjściu / zamknięciu (repeatOnLifecycle anuluje pętlę). Ten sam kod, który widzi użytkownik ([Activation.code]).
     */
    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (isActive && !unlocked) {
                    if (Activation.consent) { polls++; onChecked(Activation.check(poll = true)) }
                    delay(Activation.POLL_MS)
                }
            }
        }
    }

    private fun check() {
        if (!Activation.consent) return
        lifecycleScope.launch { onChecked(Activation.check()) }
    }

    /** Wynik sprawdzenia: aktywna – od razu dalej; inaczej ekran tylko przy zmianie stanu / błędu. */
    private fun onChecked(st: State) {
        if (unlocked || isFinishing) return
        if (st == State.ACTIVE) { unlock(); return }
        if (st != shownState || Activation.lastError != shownError) render()
        else checkedTv?.text = checkedText()
    }

    private fun checkedText() = "Sprawdzono: " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) +
        (Activation.lastError.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")

    /** (7.25) Obrót / zmiana rozmiaru bez odtworzenia ekranu (manifest `configChanges`) – układ wąski / szeroki od nowa. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!unlocked) render()
    }

    private fun unlock() {
        if (unlocked) return
        unlocked = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun text(t: CharSequence, size: Float, color: Int = Ui.text, bold: Boolean = false, top: Int = 10) = TextView(this).apply {
        text = t; textSize = size * Ui.fontScale; setTextColor(color); gravity = Gravity.CENTER
        // kolory motywu zachowane przy stylowaniu ekranu
        tag = when (color) { Ui.accent -> "accent"; Ui.text2 -> "text2"; else -> null }
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(top) }
    }

    /** (7.23) Wąski ekran (telefon pionowo): przyciski jeden pod drugim na całą szerokość, mniejszy kod urządzenia. */
    private val narrow get() = resources.configuration.screenWidthDp < 600

    /** Rząd przycisków: obok siebie (szeroki ekran / TV) albo w kolumnie (wąski). */
    private fun buttonRow(a: View, b: View): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(18) }
        if (narrow) {
            orientation = LinearLayout.VERTICAL
            addView(a, LinearLayout.LayoutParams(-1, -2)); addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(10) })
        } else { addView(a); addView(View(this@LockActivity), LinearLayout.LayoutParams(Ui.dp(12), 1)); addView(b) }
    }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; tag = "item"; textSize = 17f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setPadding(Ui.dp(26), Ui.dp(12), Ui.dp(26), Ui.dp(12)); setOnClickListener { onClick() }
        if (primary) setTypeface(typeface, Typeface.BOLD)
    }

    private fun render() {
        box.removeAllViews(); checkedTv = null
        val st = Activation.state()
        shownState = st; shownError = Activation.lastError
        box.addView(text("D-iptv Player", 24f, Ui.accent, bold = true, top = 0))
        if (st == State.NO_CONSENT) { renderConsent(); return }
        val (title, info) = when (st) {
            State.BLOCKED -> "⛔ Dostęp zablokowany" to "Administrator zablokował aplikację na tym urządzeniu. W sprawie odblokowania skontaktuj się z nim, podając kod:"
            State.OFFLINE -> "📡 Brak połączenia" to "Aplikacja nie mogła sprawdzić aktywacji od ponad ${Activation.graceDays} dni. Połącz urządzenie z internetem i wybierz „Sprawdź ponownie”. Kod urządzenia:"
            else -> "🔑 Aplikacja wymaga aktywacji" to "Podaj ten kod administratorowi. Po zatwierdzeniu urządzenia aplikacja uruchomi się sama w ciągu kilku sekund – bez ponownego wpisywania kodu i bez ponownego uruchamiania."
        }
        box.addView(text(title, 21f, bold = true, top = 18))
        box.addView(text(info, 15f, Ui.text2))
        if (pl.iptv.tv.ui.ModernV2.active) { renderGlass(st); return }
        box.addView(text(Activation.code, if (narrow) 30f else 44f, Ui.accent, bold = true, top = 14).apply { letterSpacing = if (narrow) 0.06f else 0.12f; setTextIsSelectable(false) })
        // kontakt: QR do Telegrama
        val qr = resources.getIdentifier("contact_qr", "drawable", packageName)
        if (qr != 0) box.addView(ImageView(this).apply { setImageResource(qr); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)) },
            LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(150)).apply { topMargin = Ui.dp(16) })
        if (BuildConfig.CONTACT.isNotBlank()) box.addView(text("Kontakt: Telegram ${BuildConfig.CONTACT} (zeskanuj kod QR)", 14f, Ui.text2, top = 8))
        checkedTv = text(checkedText(), 12f, Ui.text2, top = 8).also { box.addView(it) }
        val row = buttonRow(button("🔄 Sprawdź ponownie", true) { check() }, button("Zamknij", false) { finishAffinity() })
        box.addView(row)
        Ui.style(box)
        row.getChildAt(0).requestFocus()
    }

    /**
     * (v2) Nowoczesny v2 – „Obsidian Glass”: szklana karta kodu urządzenia (kod + QR z tym samym kodem do zeskanowania telefonem),
     * pod nią plakietka stanu sprawdzania (kropka w kolorze stanu, „Sprawdzono: HH:mm:ss” aktualizowane bez przebudowy), kontakt
     * i przyciski. Logika aktywacji bez zmian ([Activation.code], odpytywanie [startPolling]).
     */
    private fun renderGlass(st: State) {
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; tag = CODE_CARD_TAG
            background = G.glass(G.RADIUS_SHEET_DP, panel = true)
            val p = Ui.dp(if (narrow) 16 else 24); setPadding(p, p, p, p)
        }
        card.addView(text("KOD URZĄDZENIA", 12f, Ui.text2, bold = true, top = 0).apply { letterSpacing = 0.18f })
        card.addView(text(Activation.code, if (narrow) 30f else 44f, Ui.accent, bold = true, top = 6).apply { letterSpacing = if (narrow) 0.06f else 0.12f; setTextIsSelectable(false) })
        val qrDp = if (narrow) 132 else 150
        runCatching { QrCode.image(this, Activation.code, qrDp) }.getOrNull()?.let { q ->
            q.tag = "keepcolor nozoom $CODE_QR_TAG"; q.contentDescription = "Kod QR z kodem urządzenia"
            q.setBackgroundColor(0xFFFFFFFF.toInt()); q.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8))   // biała ramka – skaner rozpozna kod na ciemnym tle
            card.addView(q, LinearLayout.LayoutParams(Ui.dp(qrDp + 16), Ui.dp(qrDp + 16)).apply { topMargin = Ui.dp(14); gravity = Gravity.CENTER_HORIZONTAL })
            card.addView(text("Zeskanuj telefonem, aby przepisać kod bez pomyłek", 12f, Ui.text2, top = 6))
        }
        box.addView(card, LinearLayout.LayoutParams(if (narrow) -1 else -2, -2).apply { topMargin = Ui.dp(16); gravity = Gravity.CENTER_HORIZONTAL })
        // plakietka stanu: niebieska – czekanie na zatwierdzenie, czerwona – blokada, pomarańczowa – brak połączenia / błąd
        val color = when { st == State.BLOCKED -> G.LIVE; st == State.OFFLINE || Activation.lastError.isNotBlank() -> G.WARNING; else -> G.ACCENT }
        val chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = STATUS_TAG; background = G.statusChip(color)
            setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7))
        }
        chip.addView(View(this).apply { background = G.dot(color) }, LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8)).apply { marginEnd = Ui.dp(8) })
        val status = text(checkedText(), 13f, top = 0).apply { tag = "keepcolor"; setTextColor(G.TEXT); layoutParams = LinearLayout.LayoutParams(-2, -2) }
        checkedTv = status
        chip.addView(status)
        box.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        val qr = resources.getIdentifier("contact_qr", "drawable", packageName)
        if (qr != 0) box.addView(ImageView(this).apply { setImageResource(qr); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)) },
            LinearLayout.LayoutParams(Ui.dp(110), Ui.dp(110)).apply { topMargin = Ui.dp(16) })
        if (BuildConfig.CONTACT.isNotBlank()) box.addView(text("Kontakt: Telegram ${BuildConfig.CONTACT} (zeskanuj kod QR)", 14f, Ui.text2, top = 8))
        val row = buttonRow(button("🔄 Sprawdź ponownie", true) { check() }, button("Zamknij", false) { finishAffinity() })
        box.addView(row)
        Ui.style(box)
        row.getChildAt(0).requestFocus()
    }

    companion object {
        /** (v2) Tagi ekranu aktywacji Nowoczesny v2 (testy). */
        const val CODE_CARD_TAG = "lock_code_card"
        const val CODE_QR_TAG = "lock_code_qr"
        const val STATUS_TAG = "lock_status"
        const val CONSENT_CARD_TAG = "lock_consent_card"
    }

    private fun renderConsent() {
        box.addView(text("Zanim zaczniesz", 21f, bold = true, top = 18))
        box.addView(text("""Ta wersja aplikacji wymaga aktywacji przez administratora.

Co wysyła aplikacja: tylko kod tego urządzenia (nie da się z niego odczytać numeru urządzenia), numer instalacji, wersję aplikacji, model urządzenia i czas sprawdzenia. Sprawdzanie aktywacji pobiera listę z serwisu GitHub.

Aplikacja NIE wysyła: list kanałów, adresów, haseł, historii oglądania, plików ani adresu IP do administratora. Nie zawiera reklam ani narzędzi śledzących.

Aplikacja jest wyłącznie odtwarzaczem i nie dostarcza żadnych treści – korzystasz z własnych list.""", 14f, Ui.text2))
        // (v2) Nowoczesny v2: treść zgody na szklanej karcie (24 dp), wyrównana do lewej – łatwiejsza do czytania
        if (ModernV2.active) (box.getChildAt(box.childCount - 1) as? TextView)?.let { t ->
            box.removeView(t)
            t.gravity = Gravity.START; t.layoutParams = LinearLayout.LayoutParams(-1, -2)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; tag = CONSENT_CARD_TAG
                background = pl.iptv.tv.ui.theme.ObsidianGlass.glass(pl.iptv.tv.ui.theme.ObsidianGlass.RADIUS_SHEET_DP, panel = true)
                val p = Ui.dp(if (narrow) 16 else 24); setPadding(p, p, p, p)
            }
            card.addView(t)
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(14) })
        }
        val row = buttonRow(button("✔ Zgadzam się", true) { if (!Activation.consent) { Activation.consent = true; render(); check() } }, button("Wyjdź", false) { finishAffinity() })
        box.addView(row)
        Ui.style(box)
        row.getChildAt(0).requestFocus()
    }
}

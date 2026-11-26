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
import kotlinx.coroutines.launch
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
    private val recheck = object : Runnable {
        override fun run() { check(); handler.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pl.iptv.tv.ui.TvResolution.applyMetrics(resources); pl.iptv.tv.ui.TvResolution.applyMetrics(applicationContext.resources)   // (7.20) siatka TV 960 × 540 dp
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.bg)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; val s = Ui.dp(if (resources.configuration.screenWidthDp < 600) 16 else 28); setPadding(s, Ui.dp(24), s, Ui.dp(24)) }
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
    }

    override fun onResume() { super.onResume(); handler.post(recheck) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(recheck) }

    private fun check() {
        if (!Activation.consent) return
        lifecycleScope.launch { if (Activation.check() == State.ACTIVE) unlock() else render() }
    }

    private fun unlock() {
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
        box.removeAllViews()
        val st = Activation.state()
        box.addView(text("D-iptv Player", 24f, Ui.accent, bold = true, top = 0))
        if (st == State.NO_CONSENT) { renderConsent(); return }
        val (title, info) = when (st) {
            State.BLOCKED -> "⛔ Dostęp zablokowany" to "Administrator zablokował aplikację na tym urządzeniu. W sprawie odblokowania skontaktuj się z nim, podając kod:"
            State.OFFLINE -> "📡 Brak połączenia" to "Aplikacja nie mogła sprawdzić aktywacji od ponad ${Activation.graceDays} dni. Połącz urządzenie z internetem i wybierz „Sprawdź ponownie”. Kod urządzenia:"
            else -> "🔑 Aplikacja wymaga aktywacji" to "Podaj ten kod administratorowi. Po aktywacji aplikacja uruchomi się sama (sprawdzanie co 30 s)."
        }
        box.addView(text(title, 21f, bold = true, top = 18))
        box.addView(text(info, 15f, Ui.text2))
        box.addView(text(Activation.code, if (narrow) 30f else 44f, Ui.accent, bold = true, top = 14).apply { letterSpacing = if (narrow) 0.06f else 0.12f; setTextIsSelectable(false) })
        // kontakt: QR do Telegrama
        val qr = resources.getIdentifier("contact_qr", "drawable", packageName)
        if (qr != 0) box.addView(ImageView(this).apply { setImageResource(qr); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6)) },
            LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(150)).apply { topMargin = Ui.dp(16) })
        if (BuildConfig.CONTACT.isNotBlank()) box.addView(text("Kontakt: Telegram ${BuildConfig.CONTACT} (zeskanuj kod QR)", 14f, Ui.text2, top = 8))
        Activation.lastError.takeIf { it.isNotBlank() }?.let { box.addView(text("Ostatnie sprawdzenie: $it", 12f, Ui.text2, top = 8)) }
        val row = buttonRow(button("🔄 Sprawdź ponownie", true) { check() }, button("Zamknij", false) { finishAffinity() })
        box.addView(row)
        Ui.style(box)
        row.getChildAt(0).requestFocus()
    }

    private fun renderConsent() {
        box.addView(text("Zanim zaczniesz", 21f, bold = true, top = 18))
        box.addView(text("""Ta wersja aplikacji wymaga aktywacji przez administratora.

Co wysyła aplikacja: tylko kod tego urządzenia (nie da się z niego odczytać numeru urządzenia), numer instalacji, wersję aplikacji, model urządzenia i czas sprawdzenia. Sprawdzanie aktywacji pobiera listę z serwisu GitHub.

Aplikacja NIE wysyła: list kanałów, adresów, haseł, historii oglądania, plików ani adresu IP do administratora. Nie zawiera reklam ani narzędzi śledzących.

Aplikacja jest wyłącznie odtwarzaczem i nie dostarcza żadnych treści – korzystasz z własnych list.""", 14f, Ui.text2))
        val row = buttonRow(button("✔ Zgadzam się", true) { if (!Activation.consent) { Activation.consent = true; render(); check() } }, button("Wyjdź", false) { finishAffinity() })
        box.addView(row)
        Ui.style(box)
        row.getChildAt(0).requestFocus()
    }
}

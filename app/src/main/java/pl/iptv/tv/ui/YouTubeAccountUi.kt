package pl.iptv.tv.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.iptv.tv.data.YouTubeAuth
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens

/**
 * Logowanie YouTube na TV (OAuth Device Flow): okno z dużym kodem i adresem do wpisania na telefonie/komputerze.
 * W tle korutyna odpytuje Google; zamknięcie okna (◄ Wstecz / „Anuluj”) przerywa oczekiwanie.
 * Wygasły kod jest automatycznie zastępowany nowym (do [MAX_CODES] razy).
 */
object YouTubeAccountUi {
    private const val MAX_CODES = 3

    /** Uchwyt do testów: bieżące okno logowania. */
    @Volatile var current: AlertDialog? = null; private set

    fun login(a: AppCompatActivity, onDone: (Boolean) -> Unit) {
        if (!YouTubeAuth.configured) {
            Ui.dialog(a).setTitle("Logowanie YouTube")
                .setMessage("Brak identyfikatora klienta OAuth.\n\nWpisz go w sekcji „Zaawansowane” (Google Cloud Console → Dane logowania → " +
                    "Identyfikator klienta OAuth, typ „Telewizory i urządzenia z ograniczonym wejściem”, z włączonym YouTube Data API v3).")
                .setPositiveButton("OK", null).show()
            onDone(false); return
        }
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val step1 = TextView(a).apply { text = "1.  Na telefonie lub komputerze otwórz:"; tag = "text2"; textSize = 16f }
        val url = TextView(a).apply { tag = "accent"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, px(4), 0, px(14)) }
        val step2 = TextView(a).apply { text = "2.  Zaloguj się na konto Google i wpisz kod:"; tag = "text2"; textSize = 16f }
        // 10-foot UI: kod czytelny z kanapy (ok. 3 m) – duży, o stałej szerokości znaków, z odstępami
        val pin = TextView(a).apply {
            text = "· · · ·"; textSize = 54f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER; setPadding(0, px(10), 0, px(10))
        }
        val status = TextView(a).apply { text = "Pobieranie kodu…"; tag = "text2"; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), px(4))
            listOf(step1, url, step2, pin, status).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        var job: Job? = null
        var success = false
        val dlg = Ui.dialog(a).setTitle("Logowanie YouTube").setView(box)
            .setNegativeButton("Anuluj", null)
            .create()
        dlg.setOnDismissListener { job?.cancel(); current = null; onDone(success) }
        dlg.show()
        current = dlg
        // ekran nie gaśnie podczas wpisywania kodu na telefonie
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        job = a.lifecycleScope.launch {
            var codes = 0
            try {
                while (isActive && codes < MAX_CODES) {
                    codes++
                    val dc = YouTubeAuth.requestDeviceCode()
                    url.text = dc.verificationUrl.removePrefix("https://").removePrefix("http://")
                    pin.text = dc.userCode
                    val ticker = launch {
                        while (isActive) {
                            val s = dc.remainingS()
                            status.text = "Oczekiwanie na potwierdzenie…  (kod ważny ${s / 60}:${"%02d".format(s % 60)})"
                            delay(1000)
                        }
                    }
                    try {
                        YouTubeAuth.awaitAuthorization(dc)
                        ticker.cancel()
                        status.text = "Potwierdzono – pobieranie danych konta…"
                        YouTubeRepository.myChannelTitle()?.let { YouTubeTokens.saveAccount(it) }
                        YouTubeRepository.clearCache()
                        success = true
                        toast(a, "Zalogowano" + YouTubeTokens.accountName.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
                        dlg.dismiss()
                        return@launch
                    } catch (e: YouTubeAuth.AuthException) {
                        ticker.cancel()
                        if (e.code != "expired_token") throw e
                        status.text = "Kod wygasł – nowy kod…"
                    }
                }
                status.text = "Kod wygasł. Spróbuj ponownie."
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeAuth.AuthException) {
                status.text = e.message; pin.text = "—"
            } catch (e: Exception) {
                status.text = "Brak połączenia z Google: ${e.message ?: e.javaClass.simpleName}"; pin.text = "—"
            }
        }
    }

    /** Wylogowanie z potwierdzeniem. */
    fun logout(a: AppCompatActivity, onDone: () -> Unit) {
        val who = YouTubeTokens.accountName.ifBlank { "konta YouTube" }
        Dialogs.confirm(a, "Wylogować?", "Wylogować z $who na tym urządzeniu? Dostęp aplikacji zostanie też odwołany w Google.") {
            a.lifecycleScope.launch {
                YouTubeAuth.logout(); YouTubeRepository.clearCache()
                toast(a, "Wylogowano"); onDone()
            }
        }
    }

    private fun toast(a: AppCompatActivity, s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}

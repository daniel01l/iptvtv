package pl.iptv.tv.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
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
        if (!YouTubeAuth.configured) { setupClient(a, onDone); return }
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
        val steps = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            listOf(step1, url, step2, pin, status).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        // kod QR: zeskanowanie aparatem telefonu otwiera stronę logowania Google (bez przepisywania adresu)
        val qrSize = px(if (a.resources.configuration.screenHeightDp < 520) 150 else 210)
        val qr = ImageView(a).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Kod QR logowania" }
        val qrBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(qr, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(TextView(a).apply { text = "Zeskanuj telefonem,\nzaloguj się i wpisz kod obok"; tag = "text2"; textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, px(8), 0, 0) }, LinearLayout.LayoutParams(qrSize + px(40), -2))
        }
        val wide = a.resources.configuration.screenWidthDp >= 640
        val box = LinearLayout(a).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER; setPadding(px(24), px(8), px(24), px(4))
            addView(qrBox, LinearLayout.LayoutParams(-2, -2).apply { if (wide) marginEnd = px(24) else bottomMargin = px(12) })
            addView(steps, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, 1f))
        }
        var job: Job? = null
        var viaToken = false
        var success = false
        val dlg = Ui.dialog(a).setTitle("Logowanie YouTube").setView(box)
            .setNegativeButton("Anuluj", null)
            // logowanie tokenem odświeżania (np. przeniesionym z innego urządzenia z tym samym klientem OAuth)
            .setNeutralButton("Token…") { _, _ -> viaToken = true; tokenLogin(a, onDone) }
            .create()
        dlg.setOnDismissListener { job?.cancel(); current = null; if (!viaToken) onDone(success) }
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
                    runCatching { qr.setImageBitmap(QrCode.bitmap(dc.verificationUrl, qrSize)) }
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

    /** Strona konsoli Google z danymi logowania (identyfikator klienta OAuth) – w kodzie QR okna konfiguracji. */
    const val CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"

    /**
     * Brak identyfikatora klienta OAuth (aplikacja zbudowana bez niego): okno konfiguracji z kodem QR do konsoli Google
     * (telefon) i instrukcją, wpisanie identyfikatora i klucza od razu tutaj, potem od razu logowanie kodem QR / kodem urządzenia.
     */
    fun setupClient(a: AppCompatActivity, onDone: (Boolean) -> Unit) {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val qrSize = px(if (a.resources.configuration.screenHeightDp < 520) 140 else 190)
        val qr = ImageView(a).apply { setBackgroundColor(Color.WHITE); contentDescription = "Kod QR konsoli Google"
            runCatching { setImageBitmap(QrCode.bitmap(CONSOLE_URL, qrSize)) } }
        val steps = TextView(a).apply {
            tag = "text2"; textSize = 15f
            text = "Logowanie do YouTube wymaga własnego identyfikatora klienta Google (jednorazowo, bezpłatnie). Google nie pozwala " +
                "wpisywać e-maila i hasła konta w aplikacjach innych firm – logujesz się na telefonie kodem QR.\n\n" +
                "1. Zeskanuj kod QR telefonem (konsola Google Cloud).\n" +
                "2. Utwórz projekt, włącz „YouTube Data API v3”.\n" +
                "3. Dane logowania → Utwórz → Identyfikator klienta OAuth → typ „Telewizory i urządzenia z ograniczonym wejściem”.\n" +
                "4. Wpisz tu identyfikator klienta i klucz klienta – od razu pojawi się kod QR logowania."
        }
        val wide = a.resources.configuration.screenWidthDp >= 640
        val box = LinearLayout(a).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(24), px(8), px(24), px(4))
            addView(qr, LinearLayout.LayoutParams(qrSize, qrSize).apply { if (wide) marginEnd = px(22) else bottomMargin = px(12) })
            addView(steps, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, 1f))
        }
        var next = false
        Ui.dialog(a).setTitle("Logowanie YouTube – konfiguracja").setView(box)
            .setPositiveButton("Wpisz identyfikator…") { _, _ ->
                next = true
                Dialogs.input(a, "Identyfikator klienta OAuth", pl.iptv.tv.data.Prefs.ytClientId, "…apps.googleusercontent.com", onCancel = { onDone(false) }) { id ->
                    Dialogs.input(a, "Klucz klienta (client secret)", pl.iptv.tv.data.Prefs.ytClientSecret, "GOCSPX-…", password = true, onCancel = { onDone(false) }) { secret ->
                        pl.iptv.tv.data.Prefs.ytClientId = id.trim(); pl.iptv.tv.data.Prefs.ytClientSecret = secret.trim()
                        if (YouTubeAuth.configured) login(a, onDone) else onDone(false)
                    }
                }
            }
            .setNegativeButton("Anuluj", null)
            .setOnDismissListener { if (!next) onDone(false) }
            .show()
    }

    /**
     * Logowanie tokenem odświeżania (refresh token tego samego klienta OAuth) – dla zaawansowanych; Google nie pozwala
     * wpisywać hasła konta w aplikacjach zewnętrznych, więc zamiast e-maila i hasła: kod QR / kod urządzenia albo token.
     */
    fun tokenLogin(a: AppCompatActivity, onDone: (Boolean) -> Unit) {
        Dialogs.input(a, "Logowanie tokenem", "", "Token odświeżania (refresh token)", password = true, onCancel = { onDone(false) }) { t ->
            a.lifecycleScope.launch {
                runCatching {
                    YouTubeAuth.loginWithRefreshToken(t)
                    YouTubeRepository.myChannelTitle()?.let { YouTubeTokens.saveAccount(it) }
                    YouTubeRepository.clearCache()
                }.onSuccess { toast(a, "Zalogowano" + YouTubeTokens.accountName.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()); onDone(true) }
                    .onFailure { toast(a, it.message ?: "Logowanie nieudane"); onDone(false) }
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

    private fun toast(a: AppCompatActivity, s: String) = I18n.makeToast(a, s, Toast.LENGTH_SHORT).show()
}

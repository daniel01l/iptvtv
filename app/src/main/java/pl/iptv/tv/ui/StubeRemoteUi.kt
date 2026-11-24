package pl.iptv.tv.ui

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.remote.StubeRemote

/**
 * „Połącz z telefonem” (10-foot UI): duży 4-cyfrowy kod parowania i adres strony pilota w sieci lokalnej.
 * Otwarcie okna włącza sterowanie (serwer w sieci lokalnej, dostęp tylko z kodem); „Wyłącz” – serwer STube nieaktywny.
 */
object StubeRemoteUi {
    /** Bieżące okno (testy). */
    @Volatile var current: AlertDialog? = null; private set

    fun show(a: AppCompatActivity, onChanged: () -> Unit = {}): AlertDialog {
        StubeRemote.setEnabled(a, true)
        val d0 = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d0).toInt()
        val step1 = TextView(a).apply { text = "1.  Na telefonie w tej samej sieci Wi-Fi otwórz w przeglądarce:"; tag = "text2"; textSize = 16f }
        val url = TextView(a).apply {
            tag = "accent"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, px(4), 0, px(12))
            text = StubeRemote.pageUrl()?.removePrefix("http://") ?: "Brak połączenia z siecią"
        }
        val step2 = TextView(a).apply { text = "2.  Wpisz kod parowania:"; tag = "text2"; textSize = 16f }
        // kod czytelny z kanapy: duży, o stałej szerokości, z odstępami (jak przy logowaniu YouTube)
        val pin = TextView(a).apply {
            text = StubeRemote.ensurePin(); textSize = 60f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.3f; gravity = Gravity.CENTER; setPadding(0, px(8), 0, px(8))
        }
        val info = TextView(a).apply {
            tag = "text2"; textSize = 14f; gravity = Gravity.CENTER
            text = "Z telefonu: odtwarzanie, pauza, przewijanie, rozdziały, kolejka, wyszukiwanie i otwieranie linków YouTube na TV."
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), px(4))
            listOf(step1, url).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
            // (7.21) kod QR z adresem – telefon otwiera stronę bez wpisywania IP
            StubeRemote.pageUrl()?.let { u -> addView(QrCode.image(a, u, 140), LinearLayout.LayoutParams(px(140), px(140)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(8) }) }
            listOf(step2, pin, info).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val d = Ui.dialog(a).setTitle("📱 Połącz z telefonem").setView(box)
            .setPositiveButton("Gotowe", null)
            .setNeutralButton("Nowy kod", null)
            .setNegativeButton("Wyłącz sterowanie") { _, _ -> StubeRemote.setEnabled(a, false); onChanged() }
            .create()
        d.setOnDismissListener { current = null; onChanged() }
        d.show()
        // „Nowy kod” bez zamykania okna – poprzednio sparowane telefony tracą dostęp
        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { pin.text = StubeRemote.newPin() }
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        current = d
        return d
    }

    /** Opis stanu do wiersza Ustawień. */
    fun state(): String = if (Prefs.stubeRemote) "✔ Włączone • kod ${StubeRemote.ensurePin()}" else "✖ Wyłączone"
}

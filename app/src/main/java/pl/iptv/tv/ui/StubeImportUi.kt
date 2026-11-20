package pl.iptv.tv.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.StubeImport
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtVideo
import pl.iptv.tv.remote.RemoteServer

/**
 * (7.14) Okno „＋ Dodaj playlistę” w STube (ikona ＋ na pasku): odbiera link wysłany z telefonu tym samym kanałem co listy M3U
 * (serwer [RemoteServer] + PIN) albo wklejony / wpisany tutaj, pobiera i dopasowuje utwory w tle ([StubeImport.resolve]), pokazuje
 * podgląd (liczba dopasowań, pominięte pozycje, błąd dostępu) i po zatwierdzeniu uruchamia playlistę jako aktywną kolejkę oraz zapisuje
 * ją na stałe (Room). Okno w stylu aktywnego motywu ([Ui.dialog]); zamknięcie anuluje pobieranie.
 */
object StubeImportUi {
    /** Otwarte okno (testy). */
    @Volatile var current: AlertDialog? = null; private set

    private enum class Step { INPUT, LOADING, PREVIEW, ERROR }

    fun show(a: AppCompatActivity, initial: String? = null): AlertDialog {
        current?.let { runCatching { it.dismiss() } }
        // serwer odbioru (jak przy listach M3U) – działa, dopóki okno jest otwarte lub włączono go w Ustawieniach
        val serverWasOn = RemoteServer.running
        RemoteServer.start(a)
        val d0 = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d0).toInt()

        val intro = TextView(a).apply { tag = "text2"; textSize = 16f; text = "Wklej link do playlisty (YouTube lub Spotify) albo listę „Wykonawca – Tytuł”:" }
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2; maxLines = 4; textSize = 16f; hint = "https://…"; contentDescription = "Link do playlisty"
        }
        val phone = TextView(a).apply {
            tag = "text2"; textSize = 14f; setPadding(0, px(10), 0, 0)
            val ip = RemoteServer.localIps().firstOrNull()
            text = if (ip != null) "Z telefonu (ta sama sieć Wi-Fi): otwórz http://$ip:${RemoteServer.PORT}, wpisz PIN ${RemoteServer.ensurePin()} i wklej link w polu „link do listy”."
            else "Z telefonu: brak połączenia z siecią lokalną."
        }
        val status = TextView(a).apply { textSize = 16f; visibility = View.GONE }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val details = TextView(a).apply { tag = "text2"; textSize = 14f; visibility = View.GONE }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), px(4))
            listOf<View>(intro, input, phone, status, bar, details).forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val d = Ui.dialog(a).setTitle("＋ Dodaj playlistę").setView(box)
            .setPositiveButton("Sprawdź", null).setNeutralButton("Wklej", null).setNegativeButton("Anuluj", null).create()

        var step = Step.INPUT
        var job: Job? = null
        var preview: StubeImport.Preview? = null

        fun btn(w: Int) = d.getButton(w)
        fun setStep(s: Step) {
            step = s
            val inputVisible = if (s == Step.INPUT || s == Step.ERROR) View.VISIBLE else View.GONE
            intro.visibility = inputVisible; input.visibility = inputVisible; phone.visibility = if (s == Step.INPUT) View.VISIBLE else View.GONE
            status.visibility = if (s == Step.INPUT) View.GONE else View.VISIBLE
            bar.visibility = if (s == Step.LOADING) View.VISIBLE else View.GONE
            details.visibility = if (s == Step.PREVIEW || s == Step.ERROR) View.VISIBLE else View.GONE
            when (s) {
                Step.INPUT, Step.ERROR -> { btn(AlertDialog.BUTTON_POSITIVE)?.text = "Sprawdź"; btn(AlertDialog.BUTTON_NEUTRAL)?.text = "Wklej"; btn(AlertDialog.BUTTON_NEGATIVE)?.text = "Anuluj" }
                Step.LOADING -> { btn(AlertDialog.BUTTON_POSITIVE)?.text = "Czekaj…"; btn(AlertDialog.BUTTON_NEUTRAL)?.text = "Wstecz"; btn(AlertDialog.BUTTON_NEGATIVE)?.text = "Anuluj" }
                Step.PREVIEW -> { btn(AlertDialog.BUTTON_POSITIVE)?.text = "▶ Odtwórz i zapisz"; btn(AlertDialog.BUTTON_NEUTRAL)?.text = "Zapisz"; btn(AlertDialog.BUTTON_NEGATIVE)?.text = "Wstecz" }
            }
            btn(AlertDialog.BUTTON_POSITIVE)?.isEnabled = s != Step.LOADING
            btn(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.VISIBLE
        }

        fun showPreview(p: StubeImport.Preview) {
            preview = p
            if (!p.ok) {
                status.text = "✖ ${p.error ?: "Brak utworów"}"; status.setTextColor(0xFFFF6B6B.toInt()); details.text = ""
                setStep(Step.ERROR); btn(AlertDialog.BUTTON_POSITIVE)?.requestFocus(); return
            }
            status.setTextColor(Ui.text)
            status.text = "✔ „${p.name}” – dopasowano ${p.videos.size} " + (if (p.missed.isEmpty()) "utworów" else "z ${p.videos.size + p.missed.size} utworów (pominięto ${p.missed.size})")
            details.text = (p.videos.take(6).joinToString("\n") { "♪ ${it.title}" } + (if (p.videos.size > 6) "\n… i ${p.videos.size - 6} więcej" else "") +
                (if (p.missed.isNotEmpty()) "\n\nPominięte (brak w STube): " + p.missed.take(4).joinToString("; ") + (if (p.missed.size > 4) "…" else "") else ""))
            setStep(Step.PREVIEW); btn(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }

        fun check(text: String) {
            if (text.isBlank()) { status.text = "Wklej link lub listę utworów"; status.visibility = View.VISIBLE; return }
            setStep(Step.LOADING); status.setTextColor(Ui.text); status.text = "Pobieranie i dopasowanie utworów…"; bar.isIndeterminate = true
            job?.cancel()
            job = a.lifecycleScope.launch {
                val p = StubeImport.resolve(text) { done, total ->
                    a.runOnUiThread { bar.isIndeterminate = false; bar.progress = if (total > 0) done * 100 / total else 0; status.text = "Dopasowanie utworów: $done / $total" }
                }
                if (current === d) showPreview(p)
            }
        }

        fun commit(play: Boolean) {
            val p = preview?.takeIf { it.ok } ?: return
            a.lifecycleScope.launch {
                StubeImport.save(p)                                        // na stałe (Room) – wśród playlist Biblioteki i w sygnale gustu
                if (play) start(a, p.videos) else I18n.makeToast(a, "Zapisano playlistę „${p.name}” (${p.videos.size} utworów)", android.widget.Toast.LENGTH_LONG).show()
                a.runCatching { (this as? STubeActivity)?.libraryChanged() }
            }
            d.dismiss()
        }

        d.setOnDismissListener {
            job?.cancel(); job = null
            if (current === d) current = null
            StubeImport.onPending = null
            if (!serverWasOn && !RemoteServer.wanted) RemoteServer.stop()   // czyszczenie: serwer tylko na czas okna
        }
        d.show(); current = d
        btn(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            when (step) { Step.PREVIEW -> commit(true); Step.LOADING -> Unit; else -> check(input.text.toString()) }
        }
        btn(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            when (step) {
                Step.PREVIEW -> commit(false)
                Step.LOADING -> { job?.cancel(); setStep(Step.INPUT) }
                else -> clipboard(a)?.let { input.setText(it); input.setSelection(it.length); check(it) } ?: run { status.text = "Schowek jest pusty"; status.visibility = View.VISIBLE }
            }
        }
        btn(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            when (step) { Step.PREVIEW -> setStep(Step.INPUT); else -> d.dismiss() }
        }
        setStep(Step.INPUT)
        // link już odebrany z telefonu – od razu podgląd; kolejne odebrane w trakcie – też
        fun fromPending() { StubeImport.takePending()?.let { input.setText(it); check(it) } }
        StubeImport.onPending = { if (current === d && step != Step.LOADING) fromPending() }
        val first = initial ?: StubeImport.takePending()
        if (first != null) { input.setText(first); check(first) } else input.requestFocus()
        return d
    }

    private fun clipboard(c: Context): String? =
        runCatching { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Playlista jako aktywna kolejka: pierwszy utwór gra od razu w odtwarzaczu, reszta zastępuje kolejkę „Następne”
     * (jedna transakcja Room na wątku IO – interfejs nie czeka).
     */
    suspend fun start(a: AppCompatActivity, videos: List<YtVideo>) {
        val first = videos.firstOrNull() ?: return
        StubeQueue.replaceAll(videos.drop(1).map { QueueItem.of(it) })
        a.startActivity(YtPlayerActivity.intent(a, first))
    }
}

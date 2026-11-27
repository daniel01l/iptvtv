package pl.iptv.tv.ui

import android.app.Activity
import android.content.Intent

/**
 * (v2) Systemowe udostępnianie (ACTION_SEND, tekst) z odtwarzaczy i karty szczegółów. Nigdy nie udostępnia adresów strumieni
 * z list IPTV (zawierają dane logowania) – tylko publiczne linki (YouTube, IMDb, Stremio) i opis „co oglądam”.
 */
object ShareLinks {
    const val TITLE = "Udostępnij"

    /** Link YouTube do filmu (z bieżącą pozycją, gdy > 0 s). */
    fun youtube(videoId: String, positionMs: Long = 0): String =
        "https://youtu.be/$videoId" + if (positionMs >= 1000) "?t=${positionMs / 1000}" else ""

    /** Tytuł Stremio / IMDb: link IMDb dla „tt…” i strona Stremio; inne identyfikatory (np. z listy M3U) – sam tytuł. */
    fun stremio(name: String, type: String, id: String): String {
        val base = id.substringBefore(':')
        val lines = mutableListOf(name)
        if (base.startsWith("tt")) {
            lines += "https://www.imdb.com/title/$base/"
            lines += "https://web.stremio.com/#/detail/$type/$base"
        }
        return lines.joinToString("\n")
    }

    /** Kanał Live TV: nazwa i bieżący program (bez adresu strumienia). */
    fun liveTv(channel: String, programme: String?): String =
        "Oglądam: $channel" + (programme?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")

    /** Ostatnia intencja udostępniania (testy). */
    var lastIntent: Intent? = null; private set

    /** Okno systemowe udostępniania; false – brak aplikacji obsługujących udostępnianie (np. część telewizorów). */
    fun share(a: Activity, subject: String, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        lastIntent = send
        // telewizor: systemowe okno udostępniania zwykle nie działa („funkcja niedostępna”) – kod QR z linkiem do zeskanowania telefonem
        if (useQr(a)) return showQr(a, subject, text)
        return runCatching { a.startActivity(Intent.createChooser(send, TITLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            .getOrElse { showQr(a, subject, text) }
    }

    /** Wymuszenie trybu QR w testach (null – wg urządzenia). */
    var forceQr: Boolean? = null
    /** Tekst ostatnio pokazanego kodu QR (testy). */
    var lastQr: String? = null; private set

    /** TV albo brak aplikacji przyjmujących udostępniany tekst. */
    private fun useQr(a: Activity): Boolean = forceQr ?: (TvResolution.isTv(a) || runCatching {
        a.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0).isEmpty() }.getOrDefault(true))

    /** Okno z kodem QR pierwszego linku z [text] (bez linku – sam opis i komunikat). */
    fun showQr(a: Activity, subject: String, text: String): Boolean {
        val url = Regex("https?://\\S+").findAll(text).map { it.value }.lastOrNull()
        lastQr = url
        if (url == null) { I18n.makeToast(a, "Brak linku do udostępnienia dla tej pozycji", android.widget.Toast.LENGTH_SHORT).show(); return false }
        val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(4)) }
        box.addView(android.widget.FrameLayout(a).apply { setBackgroundColor(android.graphics.Color.WHITE); setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8)); addView(QrCode.image(a, url, 190)) },
            android.widget.LinearLayout.LayoutParams(-2, -2))
        box.addView(android.widget.TextView(a).apply { this.text = QR_HINT; textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(0, Ui.dp(12), 0, 0) })
        box.addView(android.widget.TextView(a).apply { this.text = url; textSize = 12f; tag = "text2"; gravity = android.view.Gravity.CENTER; setPadding(0, Ui.dp(4), 0, 0) })
        runCatching { Ui.dialog(a).setTitle(subject.ifBlank { TITLE }).setView(box).setPositiveButton("Gotowe", null).show() }
        return true
    }
    const val QR_HINT = "Zeskanuj telefonem, aby otworzyć i udostępnić"
}

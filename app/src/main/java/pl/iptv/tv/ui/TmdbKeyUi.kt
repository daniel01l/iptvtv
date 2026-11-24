package pl.iptv.tv.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioTmdb

/**
 * (7.21) „Klucz TMDB” w Stremio: własny klucz API użytkownika włącza Top 10 platform, polskie opisy i części filmów;
 * bez klucza Stremio wygląda jak w 7.19. Wpisanie pilotem (pole tekstowe) albo z telefonu – kod QR otwiera stronę
 * `/tmdb` aplikacji w sieci lokalnej ([pl.iptv.tv.remote.RemoteServer], PIN). Klucz sprawdzany jednym zapytaniem.
 */
object TmdbKeyUi {
    const val TITLE = "Klucz TMDB"
    /** Po zapisie klucza z telefonu (strona /tmdb) – otwarte okno odświeża stan. */
    @Volatile var onRemoteKey: (() -> Unit)? = null

    fun show(a: Activity, onChanged: () -> Unit) {
        val pal = CardSheet.palette()
        val has = Prefs.tmdbApiKey.isNotBlank()
        pl.iptv.tv.remote.RemoteServer.start(a)
        val ip = pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull()
        val url = ip?.let { "http://$it:${pl.iptv.tv.remote.RemoteServer.PORT}/tmdb" }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), 0) }
        fun text(s: String, size: Float = 14f, color: Int = pal.text2) = TextView(a).apply { text = s; textSize = size; setTextColor(color); tag = "keepcolor" }
        box.addView(text(if (has) "Klucz zapisany – Top 10, polskie opisy i części filmów są włączone." else "Bez klucza Stremio działa jak dotąd. Darmowy klucz API (v3) założysz na themoviedb.org → Ustawienia → API.", 15f, pal.text))
        if (url != null) {
            box.addView(text("Wpisz klucz z telefonu: zeskanuj kod (ta sama sieć Wi-Fi), PIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}").apply { setPadding(0, Ui.dp(10), 0, Ui.dp(6)) })
            box.addView(QrCode.image(a, url, 140)); box.addView(text(url, 12f))
        }
        box.addView(text(StremioTmdb.ATTRIBUTION, 11f).apply { setPadding(0, Ui.dp(10), 0, 0) })
        val d = Ui.dialog(a).setTitle(TITLE).setView(box)
            .setPositiveButton(if (has) "Zmień klucz" else "Wpisz klucz") { _, _ -> ask(a, onChanged) }
            .setNegativeButton("Zamknij", null)
            .apply { if (has) setNeutralButton("Usuń klucz") { _, _ -> Prefs.tmdbApiKey = ""; StremioTmdb.clearCache(); StremioMetas.clear(); toast(a, "Klucz TMDB usunięty"); onChanged() } }
            .create()
        onRemoteKey = { a.runOnUiThread { if (d.isShowing) d.dismiss(); toast(a, "Klucz TMDB zapisany z telefonu"); onChanged() } }
        d.setOnDismissListener { onRemoteKey = null }
        d.show()
    }

    private fun toast(a: Activity, s: String) = I18n.makeToast(a, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun ask(a: Activity, onChanged: () -> Unit) {
        Dialogs.input(a, TITLE, Prefs.tmdbApiKey, "Klucz API v3 (32 znaki)") { key -> save(a, key.trim(), onChanged) }
    }

    /** Sprawdzenie i zapis klucza (pole tekstowe w aplikacji). */
    fun save(a: Activity, key: String, onChanged: () -> Unit) {
        if (key.isBlank()) return
        val scope = (a as? LifecycleOwner)?.lifecycleScope ?: return
        scope.launch {
            when (StremioTmdb.validate(key)) {
                true -> { Prefs.tmdbApiKey = key; StremioTmdb.clearCache(); StremioMetas.clear(); toast(a, "Klucz działa – Top 10 i polskie opisy włączone"); onChanged() }
                false -> toast(a, "Nieprawidłowy klucz TMDB")
                null -> { Prefs.tmdbApiKey = key; toast(a, "Brak połączenia – klucz zapisany, zostanie sprawdzony przy pierwszym użyciu"); onChanged() }
            }
        }
    }
}

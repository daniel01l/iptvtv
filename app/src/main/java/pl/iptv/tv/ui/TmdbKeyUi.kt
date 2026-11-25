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

    /** (7.22) Nagłówek listy platform (pod kluczem, tylko z zapisanym kluczem). */
    const val PLATFORMS_TITLE = "Widoczne sekcje TOP 10 w Stremio"
    const val SELECT_ALL = "Zaznacz wszystkie"
    const val SELECT_NONE = "Odznacz wszystkie"
    /** Tag karty platformy: „top10:<klucz>”. */
    const val PLATFORM_TAG = "top10:"

    /**
     * [onTop10Changed] – (7.22) zmiana widocznych platform (od razu, np. ukrycie rzędów na tablicy); po zamknięciu okna
     * z nowymi platformami – [onChanged] (tablica wczytywana od nowa).
     */
    fun show(a: Activity, onTop10Changed: (() -> Unit)? = null, onChanged: () -> Unit) {
        val pal = CardSheet.palette()
        val has = Prefs.tmdbApiKey.isNotBlank()
        pl.iptv.tv.remote.RemoteServer.start(a)
        val ip = pl.iptv.tv.remote.RemoteServer.localIps().firstOrNull()
        val url = ip?.let { "http://$it:${pl.iptv.tv.remote.RemoteServer.PORT}/tmdb" }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), 0) }
        fun text(s: String, size: Float = 14f, color: Int = pal.text2) = TextView(a).apply { text = s; textSize = size; setTextColor(color); tag = "keepcolor" }
        box.addView(text(if (has) "Klucz zapisany – Top 10, polskie opisy i części filmów są włączone." else "Bez klucza Stremio działa jak dotąd. Darmowy klucz API (v3) założysz na themoviedb.org → Ustawienia → API.", 15f, pal.text))
        val enabledBefore = pl.iptv.tv.data.StremioTop10.enabledKeys()
        if (has) box.addView(platformList(a, pal) { onTop10Changed?.invoke() })
        if (url != null) {
            box.addView(text("Wpisz klucz z telefonu: zeskanuj kod (ta sama sieć Wi-Fi), PIN: ${pl.iptv.tv.remote.RemoteServer.ensurePin()}").apply { setPadding(0, Ui.dp(10), 0, Ui.dp(6)) })
            box.addView(QrCode.image(a, url, 140)); box.addView(text(url, 12f))
        }
        box.addView(text(StremioTmdb.ATTRIBUTION, 11f).apply { setPadding(0, Ui.dp(10), 0, 0) })
        // długa lista (10 platform) – przewijana; karty ze strefą na powiększenie fokusu (nic nie jest ucinane)
        val scroll = android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(box) }
        val d = Ui.dialog(a).setTitle(TITLE).setView(scroll)
            .setPositiveButton(if (has) "Zmień klucz" else "Wpisz klucz") { _, _ -> ask(a, onChanged) }
            .setNegativeButton("Zamknij", null)
            .apply { if (has) setNeutralButton("Usuń klucz") { _, _ -> Prefs.tmdbApiKey = ""; StremioTmdb.clearCache(); StremioMetas.clear(); toast(a, "Klucz TMDB usunięty"); onChanged() } }
            .create()
        onRemoteKey = { a.runOnUiThread { if (d.isShowing) d.dismiss(); toast(a, "Klucz TMDB zapisany z telefonu"); onChanged() } }
        d.setOnDismissListener {
            onRemoteKey = null
            // nowo zaznaczone platformy – tablica od nowa (odznaczone zniknęły już przy przełączeniu)
            if (has && (pl.iptv.tv.data.StremioTop10.enabledKeys() - enabledBefore).isNotEmpty()) onChanged()
        }
        d.show()
    }

    /** (7.22) Sama lista platform (Ustawienia → Odtwarzanie → Stremio – Top 10); [onDone] – po zamknięciu. */
    fun showPlatforms(a: Activity, onDone: () -> Unit): android.app.Dialog {
        val pal = CardSheet.palette()
        val scroll = android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(platformList(a, pal, title = false) {}) }
        val d = Ui.dialog(a).setTitle(PLATFORMS_TITLE).setView(scroll).setPositiveButton("Gotowe", null).create()
        d.setOnDismissListener { onDone() }
        d.show()
        return d
    }

    /**
     * (7.22) „Widoczne sekcje TOP 10 w Stremio”: przyciski „Zaznacz wszystkie” / „Odznacz wszystkie” i 10 kart z przełącznikiem
     * (paleta aktywnego motywu, bez kresek, powiększenie fokusu na warstwie widoku – [CardSheet.focusScale]). Każda zaznaczona
     * platforma = rzędy „Top 10 Filmy – …” i „Top 10 Seriale – …”.
     */
    fun platformList(a: Activity, pal: CardSheet.Palette, title: Boolean = true, onToggle: () -> Unit): LinearLayout {
        val T = pl.iptv.tv.data.StremioTop10
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(12), Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(4))
        }
        if (title) col.addView(TextView(a).apply { text = PLATFORMS_TITLE; textSize = 16f; tag = "keepcolor"; setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, Ui.dp(8)) })
        val switches = HashMap<String, android.widget.ImageView>()
        val cards = HashMap<String, LinearLayout>()
        fun refresh() {
            val on = T.enabledKeys()
            T.PLATFORMS.forEach { p ->
                val sw = switches[p.key] ?: return@forEach
                (sw.drawable as? CardSheet.Switch)?.let { it.on = p.key in on; sw.invalidate(); sw.invalidateDrawable(it) }
                cards[p.key]?.contentDescription = p.name + if (p.key in on) ": włączone" else ": wyłączone"
            }
        }
        val pills = LinearLayout(a).apply { clipChildren = false; clipToPadding = false; setPadding(0, Ui.dp(2), 0, Ui.dp(10)) }
        pills.addView(CardSheet.pill(a, SELECT_ALL, pal) { T.setAll(true); refresh(); onToggle() }.apply { tag = "keepcolor top10_all"; CardSheet.focusScale(this) })
        pills.addView(CardSheet.pill(a, SELECT_NONE, pal) { T.setAll(false); refresh(); onToggle() }.apply { tag = "keepcolor top10_none"; CardSheet.focusScale(this) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
        col.addView(pills)
        T.PLATFORMS.forEachIndexed { i, p ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; id = android.view.View.generateViewId(); tag = PLATFORM_TAG + p.key
                background = CardSheet.cardBg(pal); setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10))
                setOnClickListener { T.setEnabled(p.key, !T.isEnabled(p.key)); refresh(); onToggle() }
            }
            card.addView(TextView(a).apply { text = p.name; textSize = 15f; tag = "keepcolor"; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true; setTypeface(typeface, android.graphics.Typeface.BOLD) },
                LinearLayout.LayoutParams(0, -2, 1f))
            val sw = android.widget.ImageView(a).apply { setImageDrawable(CardSheet.Switch(T.isEnabled(p.key), pal)); tag = "switch"; isDuplicateParentStateEnabled = true }
            card.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(12) })
            CardSheet.focusScale(card)
            switches[p.key] = sw; cards[p.key] = card
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { if (i < T.PLATFORMS.size - 1) bottomMargin = Ui.dp(8) })
        }
        refresh()
        return col
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

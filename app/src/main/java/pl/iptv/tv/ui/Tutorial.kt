package pl.iptv.tv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pl.iptv.tv.data.Prefs

/** Instrukcja obsługi – osobno dla pilota (TV) i ekranu dotykowego. Pokazywana raz, przy pierwszym oglądaniu kanału. */
object Tutorial {
    fun isTv(a: Activity) = a.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        !a.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    /** Sekcja: tytuł i pary „przycisk / gest” → „co robi”. */
    private class Section(val title: String, val rows: List<Pair<String, String>>)

    private val TV = listOf(
        Section("📺 Oglądanie", listOf(
            "▲ ▼  lub  CH±" to "następny / poprzedni kanał",
            "cyfry" to "kanał o danym numerze",
            "OK" to "informacje o programie i lista kanałów",
            "przytrzymaj OK" to "szybkie menu: program TV, opis, ulubione, nagrywanie, audio i napisy",
            "◄" to "lista kanałów z programem",
            "◄ ◄" to "menu boczne (ustawienia, aplikacje…)",
            "►" to "ostatnio oglądane i opis programu",
            "► ►" to "wyniki meczów",
            "WSTECZ" to "zamyka okno, potem wraca do menu",
        )),
        Section("📅 Program TV", listOf(
            "OK na programie" to "oglądaj, archiwum, przypomnienie",
            "WSTECZ" to "przyciski u góry (dzień, godzina, grupa)",
            "CH±  /  cyfry" to "strona kanałów / skok do kanału",
        )),
        Section("⏪ Archiwum", listOf(
            "◄  ►" to "przewijanie",
            "► ►  (szybko)" to "pomiń reklamę",
            "OK" to "panel sterowania",
        )),
        Section("🏠 Menu główne", listOf(
            "◄  z listy kanałów" to "kategorie",
            "◄ ◄" to "Program, Kanały, Edytor, Ustawienia",
            "„✎ Edytuj”" to "kolejność i ukrywanie kategorii",
        )),
    )

    private val TOUCH = listOf(
        Section("📺 Oglądanie", listOf(
            "przesuń w górę / w dół" to "następny / poprzedni kanał",
            "dotknij ekranu" to "informacje, lista kanałów i przyciski u góry",
            "przytrzymaj ekran" to "szybkie menu: program TV, opis, ulubione, nagrywanie, audio i napisy",
            "przesuń w prawo" to "lista kanałów z programem",
            "przesuń w lewo" to "ostatnio oglądane; „⚽ Wyniki” – wyniki meczów",
            "☰ 📺 🕘 ☆ ▲▼ ✕" to "menu, kanały, ostatnie, ulubione, zmiana kanału, zamknij",
        )),
        Section("📅 Program TV", listOf(
            "dotknij programu" to "opis",
            "dotknij jeszcze raz" to "oglądaj, archiwum, przypomnienie",
        )),
        Section("🏠 Menu główne", listOf(
            "dotknij kanału" to "podgląd i program; drugi raz – oglądaj",
            "przytrzymaj kanał" to "opcje kanału",
            "„✎ Edytuj”" to "kolejność i ukrywanie kategorii",
        )),
    )

    /** Czytelny układ: nagłówki sekcji, po lewej przycisk/gest (akcent), po prawej opis. */
    private fun content(a: Activity, sections: List<Section>): ScrollView {
        val narrow = a.resources.configuration.screenWidthDp < 600
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(22), Ui.dp(4), Ui.dp(22), Ui.dp(8)) }
        sections.forEachIndexed { i, sec ->
            box.addView(TextView(a).apply {
                text = sec.title; textSize = 16f * Ui.fontScale; setTypeface(Ui.typeface(Typeface.BOLD)); setTextColor(Ui.text)
                setPadding(0, Ui.dp(if (i == 0) 4 else 14), 0, Ui.dp(6)); tag = "keepcolor"
            })
            sec.rows.forEach { (k, v) ->
                val row = LinearLayout(a).apply { orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(3), 0, Ui.dp(3)) }
                row.addView(TextView(a).apply {
                    text = k; textSize = 14f * Ui.fontScale; setTextColor(Ui.accent); setTypeface(Ui.typeface(Typeface.BOLD)); tag = "keepcolor"
                    gravity = Gravity.START
                }, if (narrow) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(Ui.dp(190), -2))
                row.addView(TextView(a).apply { text = v; textSize = 14f * Ui.fontScale; setTextColor(Ui.text2); tag = "keepcolor" },
                    if (narrow) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(row)
            }
        }
        return ScrollView(a).apply { addView(box); isFocusable = true; isFocusableInTouchMode = false }
    }

    /** Okno instrukcji; drugi przycisk przełącza pilot ↔ dotyk. */
    fun show(a: Activity, tv: Boolean = isTv(a)) {
        Ui.dialog(a).setTitle(if (tv) "Jak korzystać – pilot" else "Jak korzystać – ekran dotykowy")
            .setView(content(a, if (tv) TV else TOUCH))
            .setPositiveButton("Rozumiem", null)
            .setNeutralButton(if (tv) "Wersja dotykowa" else "Wersja dla pilota") { _, _ -> show(a, !tv) }
            .show().also { d ->
                d.window?.decorView?.post {
                    val dm = a.resources.displayMetrics
                    val maxH = (dm.heightPixels * 0.9f).toInt()
                    val h = d.window?.decorView?.height ?: 0
                    val w = if (dm.widthPixels / dm.density >= 900) (dm.widthPixels * 0.62f).toInt() else d.window!!.attributes.width
                    d.window?.setLayout(w, if (h > maxH) maxH else android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.requestFocus()
                }
            }
    }

    /** W testach automatycznych okno nie pojawia się samo (włączane w teście instrukcji). */
    var auto = android.os.Build.FINGERPRINT != "robolectric"

    fun showOnce(a: Activity) {
        if (Prefs.tutorialShown || !auto) return
        Prefs.tutorialShown = true
        show(a)
    }
}

package pl.iptv.tv.ui

import android.app.Activity
import pl.iptv.tv.data.Prefs

/** Opcje wyglądu przewodnika EPG – wspólne dla Ustawień i przycisku 🎨 w przewodniku. */
object GuideLook {
    val ROWS = listOf(3, 4, 5, 6, 7, 8, 10, 12)
    val WINDOWS = listOf(60, 90, 120, 180, 240, 360)
    val STYLES = listOf("Kafelki", "Linie (siatka)", "Kompaktowy", "Klasyczny (dekoder)")
    val CHCOL = listOf("Logo + nazwa", "Tylko logo", "Tylko nazwa", "Numer + nazwa")
    val DESC = listOf("Ukryty", "Mały (2 linie)", "Średni (4 linie)", "Duży (7 linii)")
    fun windowLabel(m: Int) = if (m % 60 == 0) "${m / 60} godz." else "${m / 60},5 godz."

    /** Pozycje: (tytuł, bieżąca wartość, akcja zmiany). */
    fun items(a: Activity, changed: () -> Unit, reopen: () -> Unit = {}): List<Triple<String, String, () -> Unit>> {
        fun pick(t: String, o: List<String>, cur: Int, set: (Int) -> Unit) = {
            Dialogs.choiceLive(a, t, o, cur) { set(it); changed() }.setOnDismissListener { reopen() }
        }
        return listOf(
            Triple("Kanałów na ekranie", "${Prefs.guideRows}", pick("Kanałów na ekranie", ROWS.map { "$it" }, ROWS.indexOf(Prefs.guideRows)) { Prefs.guideRows = ROWS[it] }),
            Triple("Zakres czasu na ekranie", windowLabel(Prefs.guideWindow), pick("Zakres czasu", WINDOWS.map(::windowLabel), WINDOWS.indexOf(Prefs.guideWindow)) { Prefs.guideWindow = WINDOWS[it] }),
            Triple("Styl siatki", STYLES[Prefs.guideStyle.coerceIn(STYLES.indices)], pick("Styl siatki", STYLES, Prefs.guideStyle) { Prefs.guideStyle = it }),
            Triple("Kolumna kanałów", CHCOL[Prefs.guideChannelCol.coerceIn(CHCOL.indices)], pick("Kolumna kanałów", CHCOL, Prefs.guideChannelCol) { Prefs.guideChannelCol = it }),
            Triple("Opis programu pod siatką", DESC[Prefs.guideDesc.coerceIn(DESC.indices)], pick("Opis programu", DESC, Prefs.guideDesc) { Prefs.guideDesc = it }),
            Triple("Godzina w komórce", if (Prefs.guideShowTime) "✔ Tak" else "✖ Nie", { Prefs.guideShowTime = !Prefs.guideShowTime; changed(); reopen() }),
            Triple("Linia „teraz”", if (Prefs.guideNowLine) "✔ Tak" else "✖ Nie", { Prefs.guideNowLine = !Prefs.guideNowLine; changed(); reopen() }),
        )
    }

    fun menu(a: Activity, changed: () -> Unit) {
        Dialogs.menu(a, "🎨 Wygląd programu TV", items(a, changed) { menu(a, changed) }.map { (t, v, f) -> "$t:  $v" to f })
    }
}

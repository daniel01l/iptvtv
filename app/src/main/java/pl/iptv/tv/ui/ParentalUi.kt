package pl.iptv.tv.ui

import android.app.Activity
import android.widget.Toast
import pl.iptv.tv.data.Parental

object ParentalUi {
    /** Prosi o PIN, jeśli kontrola jest włączona i niezablokowana; po poprawnym PIN-ie odblokowuje do zamknięcia aplikacji. */
    fun require(a: Activity, title: String = "🔒 Podaj PIN", onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (!Parental.enabled || Parental.unlocked) { onOk(); return }
        Dialogs.input(a, title, "", "PIN", password = true, onCancel = onCancel) { pin ->
            if (Parental.check(pin)) { Parental.unlocked = true; pl.iptv.tv.data.Repository.reapply(); onOk() }
            else { I18n.makeToast(a, "Błędny PIN", Toast.LENGTH_SHORT).show(); onCancel() }
        }
    }

    /** Ustawienie nowego PIN-u (2× wpisanie). */
    fun setPin(a: Activity, done: (Boolean) -> Unit) {
        Dialogs.input(a, "Nowy PIN (4–8 cyfr)", "", password = true, onCancel = { done(false) }) { p1 ->
            if (p1.length !in 4..8) { I18n.makeToast(a, "PIN musi mieć 4–8 cyfr", Toast.LENGTH_SHORT).show(); done(false); return@input }
            Dialogs.input(a, "Powtórz PIN", "", password = true, onCancel = { done(false) }) { p2 ->
                if (p1 == p2) { Parental.setPin(p1); I18n.makeToast(a, "PIN zapisany", Toast.LENGTH_SHORT).show(); done(true) }
                else { I18n.makeToast(a, "PIN-y się różnią", Toast.LENGTH_SHORT).show(); done(false) }
            }
        }
    }
}

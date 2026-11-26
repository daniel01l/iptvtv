package pl.iptv.tv.ui

/**
 * (7.23) Wspólna reguła kliknięcia ikon paska bocznego pulpitu – dla każdego motywu i każdego przyszłego paska pulpitu:
 * pierwsze stuknięcie / OK na ikonie kategorii, która nie jest aktywna, tylko ją **aktywuje na pulpicie** (hero i rzędy tej
 * kategorii, bez wchodzenia w jej ekran); kolejne stuknięcie na już aktywnej kategorii **wchodzi** w jej funkcję / menu.
 * Pilot: zatrzymanie kursora na ikonie aktywuje kategorię (podgląd), więc OK wchodzi od razu – jak dotąd.
 */
object DashboardRail {
    enum class Tap { ACTIVATE, OPEN }

    /** Co zrobić po kliknięciu ikony [target], gdy na pulpicie aktywna jest kategoria [active]. */
    fun tap(active: Int, target: Int): Tap = if (active == target) Tap.OPEN else Tap.ACTIVATE

    /** Kliknięcie ikony [target]: [activate] (inna kategoria) albo [open] (ta sama, już aktywna). */
    inline fun click(active: Int, target: Int, activate: () -> Unit, open: () -> Unit) {
        if (tap(active, target) == Tap.OPEN) open() else activate()
    }
}

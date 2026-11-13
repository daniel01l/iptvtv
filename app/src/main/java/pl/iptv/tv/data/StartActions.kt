package pl.iptv.tv.data

object StartActions {
    const val LIST = 0
    const val LAST_CHANNEL = 1
    const val CHANNEL = 2
    const val GUIDE = 3
    const val FAVORITES = 4
    const val RECENT = 5
    const val SEARCH = 6
    const val SETTINGS = 7
    const val EDITOR = 8

    val LABELS = listOf(
        "Lista kanałów",
        "Ostatnio oglądany kanał",
        "Wybrany kanał",
        "Program TV (przewodnik)",
        "Grupa „Ulubione”",
        "Grupa „Ostatnio oglądane”",
        "Wyszukiwarka",
        "Ustawienia",
        "Edytor listy kanałów",
    )
}

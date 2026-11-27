package pl.iptv.tv.data

/** Menu z kolejnością i widocznością ustawianą przez użytkownika. */
open class OrderedMenu(
    private val base: LinkedHashMap<String, String>,
    private val getOrder: () -> List<String>, private val setOrder: (List<String>) -> Unit,
    private val getHidden: () -> List<String>, private val setHidden: (List<String>) -> Unit,
    private val defaultHidden: List<String> = emptyList(),
) {
    open val ITEMS: LinkedHashMap<String, String> get() = base

    /** Pełna kolejność (nowe pozycje dopisywane na końcu). */
    fun order(): List<String> = (getOrder().filter { it in ITEMS } + ITEMS.keys).distinct()
    private fun hidden() = getHidden().let { if (it == listOf("-")) emptyList() else it.ifEmpty { defaultHidden } }
    fun visible(): List<String> = order().filter { it !in hidden() }
    fun isHidden(id: String) = id in hidden()
    fun toggle(id: String) { val h = if (isHidden(id)) hidden() - id else hidden() + id; setHidden(h.ifEmpty { listOf("-") }) }

    fun move(id: String, delta: Int) {
        val l = order().toMutableList(); val i = l.indexOf(id); val j = (i + delta).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.removeAt(i); l.add(j, id); setOrder(l)
    }

    fun reset() { setOrder(emptyList()); setHidden(emptyList()) }
}

/** Pozycje menu funkcji (◄◄). */
object QuickMenu : OrderedMenu(linkedMapOf(
    "settings" to "⚙ Ustawienia", "sys_settings" to "🛠 Ustawienia urządzenia", "editor" to "✎ Edytor listy kanałów", "epg_edit" to "📺 Edytor EPG (ten kanał)",
    "channel" to "☰ Opcje kanału", "fav" to "★ Ulubione", "guide" to "📅 Program TV (przewodnik)",
    "search" to "🔎 Szukaj w programie TV", "reminders" to "🔔 Przypomnienia", "audio" to "🔊 Ścieżka audio",
    "subs" to "💬 Napisy", "aspect" to "📐 Proporcje obrazu", "skip_ad" to "⏭ Pomiń reklamę (archiwum)", "record" to "⏺ Nagrywanie",
    "queue" to "📋 Kolejka i propozycje", "refresh" to "🔄 Odśwież listę i EPG", "share" to "↗ Udostępnij (co oglądam)", "apps_all" to "▦ Wszystkie aplikacje", "apps" to "📱 Moje aplikacje (skróty)",
), { Prefs.quickOrder }, { Prefs.quickOrder = it }, { Prefs.quickHidden }, { Prefs.quickHidden = it })

/** Mini menu ikon (długie OK / przytrzymanie ekranu w odtwarzaczu). */
object MiniMenu : OrderedMenu(linkedMapOf(
    "settings" to "⚙ Ustawienia", "sys_settings" to "🛠 Ustawienia urządzenia", "guide" to "📅 Program TV", "desc" to "ℹ Opis programu", "fav" to "★ Dodaj do ulubionych",
    "record" to "⏺ Nagrywanie", "tracks" to "🎧 Audio i napisy", "engine" to "⚡ Silnik i akceleracja", "aspect" to "📐 Proporcje obrazu", "channels" to "☰ Lista kanałów",
    "queue" to "📋 Kolejka i propozycje",
), { Prefs.miniOrder }, { Prefs.miniOrder = it }, { Prefs.miniHidden }, { Prefs.miniHidden = it }, defaultHidden = listOf("aspect", "channels", "sys_settings", "queue")) {
    const val APP = "app:"
    /** Skróty aplikacji: "app:pakiet/klasa" -> nazwa (Prefs.miniApps: "komponent|nazwa"). */
    private fun apps() = Prefs.miniApps.mapNotNull { e -> e.split('|', limit = 2).takeIf { it.size == 2 }?.let { APP + it[0] to "📱 " + it[1] } }
    override val ITEMS: LinkedHashMap<String, String> get() = LinkedHashMap(super.ITEMS).apply { putAll(apps()) }
    fun hasApp(component: String) = Prefs.miniApps.any { it.substringBefore('|') == component }
    fun addApp(component: String, label: String) { if (!hasApp(component)) Prefs.miniApps = Prefs.miniApps + "$component|${label.replace('|', ' ')}" }
    fun removeApp(id: String) { val cn = id.removePrefix(APP); Prefs.miniApps = Prefs.miniApps.filter { it.substringBefore('|') != cn } }
}

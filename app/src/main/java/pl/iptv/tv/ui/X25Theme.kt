package pl.iptv.tv.ui

import android.app.Activity
import android.content.Context

/**
 * (7.27) Routing ekranu głównego (pulpitu) i trybu launchera.
 *
 * Dawniej obiekt motywu „X25 Dashboard” – motyw X25 (oraz PS5, Xbox, Klasyczny dekoder) usunięty z aplikacji, kopia w
 * `docs/archiwum_motywy/` (instrukcja: `PRZYWRACANIE.md`). Nazwa obiektu zostaje, bo korzysta z niego wiele ekranów
 * (lista kanałów, HOME, WSTECZ z ekranów głównych, Ustawienia) – jedynym pulpitem jest teraz ekran główny Nowoczesny.
 */
object X25Theme {
    /** Ekran główny jako ekran startowy (motyw Nowoczesny z opcją „Ekran główny jako ekran startowy” albo tryb launchera). */
    val dashboardOn: Boolean get() = ModernTheme.homeOn

    /**
     * Tryb launchera: aplikacja jest domyślnym ekranem głównym urządzenia. Wtedy HOME zawsze otwiera ekran główny.
     * Odświeżane przy starcie i powrocie na ekrany ([refreshLauncher]) – zapytania do PackageManagera nie przy każdym odczycie.
     */
    @Volatile var launcherHome = false
    /** Testy: wymuszony tryb launchera (null – stan z systemu). */
    @Volatile var launcherForTest: Boolean? = null
    fun refreshLauncher(c: Context) {
        launcherHome = launcherForTest ?: runCatching { SystemTools.isLauncherEnabled(c) && SystemTools.isDefaultHome(c) }.getOrDefault(false)
    }

    /** Motyw właśnie włączony (lista kanałów otwiera wtedy ekran główny przy powrocie z Ustawień). */
    @Volatile var justEnabled = false
    fun consumeJustEnabled(): Boolean = justEnabled.also { justEnabled = false }

    /** Otwiera ekran główny (istniejący wraca na wierzch – bez budowania od nowa). */
    fun openDashboard(a: Activity) { a.startActivity(dashboardIntent(a)) }

    /** Ekran pulpitu: ekran główny Nowoczesny. */
    fun dashboardIntent(c: Context): android.content.Intent =
        android.content.Intent(c, ModernHomeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
}

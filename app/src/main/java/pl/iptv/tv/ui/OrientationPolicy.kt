package pl.iptv.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import java.util.WeakHashMap

/**
 * (7.23) Jedno źródło orientacji ekranu dla KAŻDEJ aktywności aplikacji (także przyszłych – podpięte w [pl.iptv.tv.IptvApp]
 * przez ActivityLifecycleCallbacks, przed `onCreate` od API 29 i przy każdym wznowieniu), we wszystkich motywach:
 *
 * - TV – zawsze poziomo (jak dotąd),
 * - telefon, Ustawienia → Wygląd → Orientacja ([pl.iptv.tv.data.Prefs.orientation]): 0 – poziomo (czujnik), 1 – automatycznie
 *   (wszystkie ekrany wg czujnika / blokady obrotu), 2 – pionowo: ekrany menu zawsze pionowo, odtwarzacze ([isPlayer]) mogą obrócić się
 *   z telefonem,
 * - „Obróć” w odtwarzaczu ([forceLandscape]) – poziomo tylko dla tego ekranu, do WSTECZ albo wyjścia z ekranu ([release]).
 *
 * Dawniej `MainActivity`, `YtPlayerActivity` i `StremioPlayerActivity` miały w manifeście `sensorLandscape`: w trybie pionowym ekran
 * startował na chwilę poziomo (MainActivity bez `configChanges` był nawet tworzony drugi raz), a odtwarzacze ustawiały orientację dopiero
 * po zbudowaniu widoku. Teraz manifest ma `behind` (nowy ekran dziedziczy orientację poprzedniego, czyli już właściwą), a wartość
 * docelowa jest ustawiana przed utworzeniem okna. Aktywności przezroczyste (Android 8.0 nie pozwala im ustalać orientacji) są pomijane.
 */
object OrientationPolicy {
    const val LANDSCAPE = 0; const val AUTO = 1; const val PORTRAIT = 2

    /** Ekrany odtwarzania – w trybie pionowym obracają się razem z telefonem. */
    fun isPlayer(a: Activity) = a is YtPlayerActivity || a is StremioPlayerActivity || a is PlayerActivity || a is RecordingPlayerActivity

    /** Przezroczyste okna (HOME przekierowujące, przypomnienie) – bez własnej orientacji. */
    fun skipped(a: Activity) = a is pl.iptv.tv.HomeActivity || a is pl.iptv.tv.reminders.ReminderActivity

    private val forced = WeakHashMap<Activity, Boolean>()
    /** Ekran z wymuszonym poziomem przyciskiem „Obróć” (testy). */
    fun isForced(a: Activity) = forced[a] == true

    /** Orientacja docelowa ekranu [a] (bez wymuszenia „Obróć”). */
    fun target(a: Activity, tv: Boolean = runCatching { Tutorial.isTv(a) }.getOrDefault(false),
               pref: Int = runCatching { pl.iptv.tv.data.Prefs.orientation }.getOrDefault(AUTO)): Int = when {
        tv -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pref == AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        pref == PORTRAIT -> if (isPlayer(a)) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Ustawia orientację [a] (tylko gdy inna – bez zbędnego przeliczania konfiguracji). */
    fun apply(a: Activity) {
        if (skipped(a)) return
        val o = if (isForced(a)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else target(a)
        if (a.requestedOrientation != o) runCatching { a.requestedOrientation = o }
    }

    /** „Obróć”: odtwarzacz poziomo do [release] (WSTECZ / wyjście z ekranu). */
    fun forceLandscape(a: Activity) { forced[a] = true; apply(a) }

    fun release(a: Activity) { if (forced.remove(a) != null) apply(a) }

    /**
     * Ekran schowany: użytkownik przeszedł gdzie indziej (ekran włączony) – wymuszony poziom znika, żeby po powrocie (np. ten sam
     * odtwarzacz `singleTask`) nie wrócił niepoprawnie do poziomu. Wygaszenie / blokada ekranu – zostaje.
     */
    fun onStopped(a: Activity) {
        if (!isForced(a)) return
        val pm = a.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (a.isFinishing || pm?.isInteractive != false) forced.remove(a)
    }
}

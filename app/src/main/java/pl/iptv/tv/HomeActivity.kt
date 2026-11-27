package pl.iptv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.MainActivity

/**
 * Ekran główny systemu (tryb launchera). Niewidoczny – przekazuje do [MainActivity].
 * Pierwsze wywołanie po włączeniu urządzenia wykonuje akcję startową (także gdy system zabił proces i HOME
 * uruchamia go od nowa – rozpoznawane po numerze rozruchu, nie po stanie procesu), kolejne HOME tylko przywracają listę kanałów.
 */
class HomeActivity : Activity() {
    companion object {
        /** (v2) Ochrona przed pętlą HOME: powtórzenie w tym czasie – bez nowego startu; seria wywołań – zwykła lista kanałów. */
        const val REPEAT_MS = 600L
        const val STORM_MS = 10_000L
        const val STORM_COUNT = 6
        const val RETRY_MS = 1_500L
        @Volatile var lastForwardAt = -REPEAT_MS * 10
        /** Ochrona włączona (Robolectric – wył., testy wywołują HOME seriami; [guardForTest] włącza). */
        var guardForTest: Boolean? = null
        val guard: Boolean get() = guardForTest ?: (android.os.Build.FINGERPRINT != "robolectric")
        val recentForwards = ArrayDeque<Long>()
        fun resetForTest() { lastForwardAt = -REPEAT_MS * 10; recentForwards.clear(); guardForTest = null }
        /** Identyfikator bieżącego rozruchu: licznik rozruchów systemu albo (starsze systemy) minuta włączenia urządzenia. */
        fun bootId(c: Context): String {
            val n = runCatching { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrNull()
            return if (n != null) "c$n" else "t" + (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000
        }

        /** Czy to pierwsze wejście na ekran główny od włączenia urządzenia (i zapamiętanie go). */
        fun firstSinceBoot(c: Context): Boolean {
            val id = bootId(c)
            if (Prefs.homeBootId == id) return false
            Prefs.homeBootId = id
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); forward() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); forward() }

    private fun forward() {
        // (v2) seria HOME / ponownych startów (szybkie naciśnięcia, launcher systemu wołający ekran główny w pętli): powtórzenie
        // w [REPEAT_MS] – sam powrót (ekran już się otwiera), od [STORM_COUNT] wywołań w [STORM_MS] – zawsze zwykła lista kanałów
        // bez pulpitu i akcji startowej (żadnej pętli przeładowań)
        val now = android.os.SystemClock.elapsedRealtime()
        if (guard && now - lastForwardAt < REPEAT_MS) { lastForwardAt = now; finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0); return }
        recentForwards.addLast(now); while (recentForwards.isNotEmpty() && now - recentForwards.first() > STORM_MS) recentForwards.removeFirst()
        lastForwardAt = now
        val storm = guard && recentForwards.size >= STORM_COUNT
        val first = !storm && runCatching { Prefs.init(this); firstSinceBoot(this) }.getOrDefault(false)
        if (storm) runCatching { Prefs.init(this) }
        runCatching { pl.iptv.tv.ui.X25Theme.refreshLauncher(this) }
        // kolejne HOME: od razu pulpit (X25 / ekran główny PS5) – bez przechodzenia przez listę kanałów (bez mignięcia)
        if (!first && !storm && runCatching { pl.iptv.tv.ui.X25Theme.dashboardOn }.getOrDefault(false) && runCatching {
                startActivity(pl.iptv.tv.ui.X25Theme.dashboardIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess) {
            finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0); return
        }
        val started = runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SKIP_START, !first)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, first))
        }.isSuccess
        // (v2) nie udało się otworzyć listy (np. błąd przy starcie systemu) – ponowna próba raz, po chwili, z tego samego kontekstu aplikacji
        if (!started) applicationContext.let { app -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SKIP_START, true)) }
        }, RETRY_MS) }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

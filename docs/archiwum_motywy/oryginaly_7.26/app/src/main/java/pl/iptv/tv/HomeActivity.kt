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
        val first = runCatching { Prefs.init(this); firstSinceBoot(this) }.getOrDefault(false)
        runCatching { pl.iptv.tv.ui.X25Theme.refreshLauncher(this) }
        // kolejne HOME: od razu pulpit (X25 / ekran główny PS5) – bez przechodzenia przez listę kanałów (bez mignięcia)
        if (!first && runCatching { pl.iptv.tv.ui.X25Theme.dashboardOn }.getOrDefault(false) && runCatching {
                startActivity(pl.iptv.tv.ui.X25Theme.dashboardIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess) {
            finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0); return
        }
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SKIP_START, !first)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, first))
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

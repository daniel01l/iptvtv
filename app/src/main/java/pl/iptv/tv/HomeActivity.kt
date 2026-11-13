package pl.iptv.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import pl.iptv.tv.ui.MainActivity

/**
 * Ekran główny systemu (tryb launchera). Niewidoczny – przekazuje do [MainActivity].
 * Pierwsze wywołanie po starcie procesu (włączenie TV) wykonuje akcję startową,
 * kolejne naciśnięcia HOME tylko przywracają listę kanałów.
 */
class HomeActivity : Activity() {
    companion object { @Volatile private var started = false }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); forward() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); forward() }

    private fun forward() {
        val first = !started
        started = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SKIP_START, !first))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

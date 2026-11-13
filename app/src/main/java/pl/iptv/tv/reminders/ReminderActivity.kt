package pl.iptv.tv.reminders

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pl.iptv.tv.data.Reminder
import pl.iptv.tv.data.Repository
import pl.iptv.tv.ui.Fmt
import pl.iptv.tv.ui.PlayerActivity
import pl.iptv.tv.ui.Ui

/** Okno przypomnienia wyświetlane nad wszystkim (także nad odtwarzanym obrazem). */
class ReminderActivity : AppCompatActivity() {
    companion object {
        private const val K = "keys"; private const val T = "titles"; private const val S = "starts"; private const val E = "subs"
        fun intent(c: Context, due: List<Reminder>) = Intent(c, ReminderActivity::class.java)
            .putExtra(K, due.map { it.channelKey }.toTypedArray())
            .putExtra(T, due.map { it.title }.toTypedArray())
            .putExtra(E, due.map { listOf(it.episode, it.subTitle).filter { s -> s.isNotBlank() }.joinToString(" – ") }.toTypedArray())
            .putExtra(S, due.map { it.start }.toLongArray())
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keys = intent.getStringArrayExtra(K) ?: return finish()
        val titles = intent.getStringArrayExtra(T) ?: return finish()
        val subs = intent.getStringArrayExtra(E) ?: arrayOf()
        val starts = intent.getLongArrayExtra(S) ?: return finish()
        val now = System.currentTimeMillis()

        fun label(i: Int): String {
            val ch = Repository.byKey(keys[i])
            val mins = (starts[i] - now) / 60_000
            val whenTxt = if (mins <= 0) "trwa od ${Fmt.time(starts[i])}" else "za $mins min (${Fmt.time(starts[i])})"
            val sub = subs.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
            return "▶ ${titles[i]}$sub\n${ch?.name ?: "?"} • $whenTxt"
        }

        val b = Ui.dialog(this).setTitle("🔔 Przypomnienie")
        if (keys.size == 1) {
            b.setMessage(label(0).removePrefix("▶ "))
                .setPositiveButton("Przełącz na kanał") { _, _ -> watch(keys[0]) }
        } else {
            b.setItems(Array(keys.size) { label(it) }) { _, w -> watch(keys[w]) }
        }
        val dlg = b.setNegativeButton("Zamknij", null).setOnDismissListener { finish() }.show()
        // zamknij samo po 2 minutach, jeśli nikt nie zareagował
        handler.postDelayed({ if (dlg.isShowing) dlg.dismiss() }, 120_000)
    }

    private fun watch(key: String) {
        val ch = Repository.byKey(key) ?: return
        startActivity(Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            .putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL))
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}

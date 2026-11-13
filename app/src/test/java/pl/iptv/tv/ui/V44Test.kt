package pl.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.R
import pl.iptv.tv.data.Backup
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V44Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test fun emptyListShowsPlusOpeningListsSettings() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.playlistSource = ""
        Repository.resetAllEdits()
        File(ctx.filesDir, "playlist.m3u").delete()
        Repository.clearPlaylist()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(800)
        val a = c.get()
        val plus = a.findViewById<TextView>(R.id.emptyAdd)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.empty).visibility)
        assertTrue(plus.isShown); assertEquals("+", plus.text.toString())
        assertTrue(plus.isFocused)
        plus.performClick(); idle()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, next.component!!.className)
        assertEquals(0, next.getIntExtra("tab", -1))
        c.pause().stop().destroy()
    }

    @Test fun wizardRestoresBackupFromFile() {
        Prefs.setupDone = false; Prefs.permissionsAsked = true
        Prefs.weatherCity = "Gdańsk"
        val json = Backup.export(ctx)
        Prefs.weatherCity = ""
        val f = File(ctx.cacheDir, "kopia.json").apply { writeText(json) }
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        val a = c.get()
        all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Dalej ►") }.performClick(); idle()
        val row = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Wczytaj kopię ustawień z pliku…") }
        // wiersz kopii jest pod opcjami zdalnymi
        val texts = all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.indexOfFirst { it.contains("D-iptv Admin") } < texts.indexOfFirst { it.endsWith("Wczytaj kopię ustawień z pliku…") })
        (row.parent as? View ?: row).let { if (it.isClickable) it.performClick() else row.performClick() }; idle()
        val req = shadowOf(a).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, req.intent.action)
        shadowOf(a).receiveResult(req.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(f))); idle()
        repeat(20) { idle(100); Thread.sleep(30) }
        assertEquals("Gdańsk", Prefs.weatherCity)
        assertTrue(Prefs.setupDone)
        assertTrue(a.isFinishing)                                  // kreator kończy się bez ponownego uruchamiania
        // plik z BOM i błędny plik
        assertTrue(Backup.import(ctx, "\uFEFF" + json) > 0)
        assertTrue(runCatching { Backup.import(ctx, "nie json") }.exceptionOrNull()!!.message!!.contains("kopią"))
        c.pause().stop().destroy()
    }
}

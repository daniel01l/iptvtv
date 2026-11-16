package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.shared.ActivationFormat
import pl.iptv.tv.R
import pl.iptv.tv.data.Activation
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V51Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(a: android.app.Activity, code: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); idle(500) }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Before fun setup() {
        Playback.stop()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"; Prefs.mainPreview = false
        Prefs.groupOrder = emptyList(); Prefs.groupHidden = emptyList(); Prefs.keyMapJson = ""; Prefs.audioDelayMs = 0
        val f = File(ctx.filesDir, "v51.m3u").apply { writeText((1..12).joinToString("", "#EXTM3U\n") { "#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G${it % 3}\",K$it\nhttp://v51/$it\n" }) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun settingsStructure() {
        assertEquals(listOf("Listy", "Wygląd", "Menu i kanały", "Odtwarzanie", "Program TV", "Archiwum (catch-up)", "Kontrola rodzicielska",
            "Nagrywanie", "Wyniki i pogoda", "Pilot", "System", "Konto YouTube"), SettingsActivity.TAB_NAMES)
        fun headers(tab: Int): List<String> {
            val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", tab)).setup(); idle()
            val h = all(c.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.filter { it.startsWith("▸") || it.startsWith("▾") }.map { it.drop(3) }
            c.pause().stop().destroy(); return h
        }
        assertEquals(listOf("Lista kanałów w menu", "Kategorie", "Ulubione i ostatnio oglądane"), headers(2))
        assertTrue(headers(3).containsAll(listOf("Panele i lista kanałów", "Menu boczne (2× ◄)", "Szybkie menu ikon (przytrzymaj OK lub ekran)", "Obraz w obrazie (PiP)", "Dźwięk", "Wydajność")))
        assertTrue(headers(8).containsAll(listOf("Pogoda w menu głównym", "Obserwowane")))
        assertTrue(headers(10).containsAll(listOf("Pomoc i konfiguracja", "Uruchamianie i działanie w tle", "Kopia zapasowa", "Aktualizacja")))
        assertTrue(headers(0).contains("Odbieranie list z telefonu lub komputera"))
    }

    @Test fun tutorialIsReadableTable() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        Tutorial.show(c.get(), true); idle(500)
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val t = all(d.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(t.contains("📺 Oglądanie")); assertTrue(t.contains("przytrzymaj OK")); assertTrue(t.contains("następny / poprzedni kanał"))
        assertTrue(d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isFocused)
        d.dismiss(); c.pause().stop().destroy()
    }

    @Test fun hiddenGroupMarkedAndRemovedAtOnce() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val a = c.get()
        val ga = a.findViewById<RecyclerView>(R.id.groups).adapter as GroupAdapter
        Dialogs.editGroups(a) {
            // tak jak w menu: odświeżenie ekranu przy każdej zmianie
            a.javaClass.getDeclaredMethod("refreshUi").apply { isAccessible = true }.invoke(a)
        }; idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val lv = all(d.window!!.decorView).filterIsInstance<android.widget.ListView>().first()
        lv.performItemClick(null, 1, 1); idle()                                  // G1 → okno akcji
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).let { m ->
            all(m.window!!.decorView).filterIsInstance<android.widget.ListView>().first().performItemClick(null, 0, 0) }; idle()   // ukryj
        assertTrue("G1" in Prefs.groupHidden)
        assertFalse("G1" in ga.items)                                            // menu bez zamykania okna
        c.pause().stop().destroy()
        val e = Robolectric.buildActivity(EditorActivity::class.java).setup(); idle()
        val eg = e.get().findViewById<RecyclerView>(R.id.groups).adapter as GroupAdapter
        assertTrue(eg.label("G1").startsWith("🚫"))
        e.pause().stop().destroy()
    }

    @Test fun playerPanelGroupHeader() {
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, Repository.channels[0].key).putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL)).setup(); idle()
        val a = c.get()
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        val g = a.findViewById<TextView>(R.id.lpGroup)
        assertEquals("Wszystkie  1/4", g.text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_UP)                                          // z pierwszego kanału na nagłówek
        assertTrue(g.isFocused)
        // v5.6: zmiana kategorii zaznacza od razu pierwszy kanał (wystarczy OK); kolejne ◄ ► dalej zmieniają kategorię
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("G1  2/4", g.text.toString())
        val lp = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lpChannels)
        assertEquals(0, lp.findContainingViewHolder(a.window.decorView.findFocus()!!)?.bindingAdapterPosition)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT); key(a, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals("G0  4/4", g.text.toString())
        assertEquals(0, lp.findContainingViewHolder(a.window.decorView.findFocus()!!)?.bindingAdapterPosition)
        key(a, KeyEvent.KEYCODE_CHANNEL_DOWN); assertEquals("Wszystkie  1/4", g.text.toString())
        key(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(generateSequence(a.window.decorView.findFocus()) { it.parent as? View }.any { it.id == R.id.lpChannels })
        c.pause().stop().destroy()
    }

    @Test fun audioDelayShiftsClockAndAppShortcutsInMenu() {
        val p = Playback.get(ctx)
        val f = androidx.media3.exoplayer.DefaultRenderersFactory::class.java
        assertNotNull(p)                                                          // odtwarzacz z własnym zegarem dźwięku buduje się
        Playback.stop()
        assertEquals("dźwięk wcześniej o 200 ms", PlayerActivity.delayLabel(-200))
        // skróty aplikacji z menu bocznego także w menu głównym
        val cn = android.content.ComponentName(ctx, SettingsActivity::class.java)
        Prefs.quickApps = listOf(cn.flattenToString())
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle(500)
        val nav = c.get().findViewById<android.view.ViewGroup>(R.id.sideNav)
        assertTrue((0 until nav.childCount).any { nav.getChildAt(it).getTag(R.id.row_key) == "app" })
        c.pause().stop().destroy(); Prefs.quickApps = emptyList()
    }

    @Test fun adminLinkViaActivation() {
        val keys = ActivationFormat.newKeyPair()
        var file = ""; val checkins = mutableListOf<String>()
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = when {
                r.path!!.startsWith("/activation.json") -> MockResponse().setBody(file)
                r.method == "POST" -> { synchronized(checkins) { checkins += r.body.readUtf8() }; MockResponse().setBody("{}") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        srv.start()
        try {
            Activation.forceForTest = true; Activation.fullEdition = true
            Activation.listUrl = srv.url("/activation.json").toString(); Activation.relay = srv.url("/").toString().trimEnd('/'); Activation.pubKey = keys.second
            ctx.getSharedPreferences("activation", 0).edit().clear().commit(); Activation.consent = true
            Prefs.adminCode = ""; Prefs.relayServer = srv.url("/").toString()
            fun publish(e: ActivationFormat.Entry, ts: Long) { val p = ActivationFormat.payload(mapOf(Activation.code to e), ts); file = ActivationFormat.wrap(p, ActivationFormat.sign(p, keys.first)) }
            publish(ActivationFormat.Entry("", full = ActivationFormat.ACTIVE), 1)
            runBlocking { Activation.check() }
            val dk = JSONObject(synchronized(checkins) { checkins.last() }).getString("dk")       // klucz urządzenia w zgłoszeniu
            // Admin: kod administratora zaszyfrowany kluczem wspólnym
            val sealed = ActivationFormat.seal("ABCD-EFGH-JKMN", ActivationFormat.sharedKey(keys.first, dk))
            publish(ActivationFormat.Entry("", full = ActivationFormat.ACTIVE, admin = sealed), 2)
            runBlocking { Activation.check() }
            assertEquals("ABCD-EFGH-JKMN", Prefs.adminCode); assertTrue(Prefs.relayOn)
            // usunięcie z „moich” – kod znika
            publish(ActivationFormat.Entry("", full = ActivationFormat.ACTIVE), 3)
            runBlocking { Activation.check() }
            assertEquals("", Prefs.adminCode)
            // obcy klucz nie odszyfruje
            val other = ActivationFormat.newKeyPair()
            assertTrue(runCatching { ActivationFormat.open(sealed, ActivationFormat.sharedKey(other.first, keys.second)) }.isFailure)
        } finally {
            srv.shutdown(); Activation.forceForTest = false; Activation.fullEdition = pl.iptv.tv.BuildConfig.FULL_EDITION
            pl.iptv.tv.remote.RemoteRelay.stop(); Prefs.relayOn = false; Prefs.relayServer = ""
        }
    }
}

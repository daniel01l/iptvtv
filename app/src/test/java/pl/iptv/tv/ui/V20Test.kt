package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.remote.RemoteRelay
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class V20Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0; Prefs.playlistsJson = "[]"
        Prefs.settingsOpen = emptyList(); Prefs.settingsClosed = emptyList()
        val f = File(ctx.filesDir, "v20.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n") }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    /** Minimalny serwer ntfy: PUT /temat (załącznik), GET /temat/json?poll=1, GET /file/N. */
    private fun fakeNtfy(): MockWebServer {
        val s = MockWebServer()
        val store = mutableListOf<Pair<String, ByteArray>>()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val path = r.path!!
                return when {
                    r.method == "PUT" -> { store += path.trim('/') to r.body.readByteArray(); MockResponse().setBody("{\"id\":\"m${store.size}\"}") }
                    path.contains("/json?poll=1") -> {
                        val topic = path.substringBefore("/json").trim('/')
                        val since = path.substringAfter("since=")
                        val lines = store.mapIndexedNotNull { i, (t, _) ->
                            if (t != topic) null else JSONObject().put("id", "m${i + 1}").put("event", "message").put("message", "")
                                .put("attachment", JSONObject().put("url", s.url("/file/$i").toString())).toString()
                        }.dropWhile { since != "all" && JSONObject(it).getString("id") != since }.let { if (since == "all") it else it.drop(1) }
                        MockResponse().setBody(lines.joinToString("\n"))
                    }
                    path.startsWith("/file/") -> MockResponse().setBody(Buffer().write(store[path.substringAfter("/file/").toInt()].second))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start(); return s
    }

    @Test fun relayEncryptsAndDeliversAcrossNetworks() {
        val code = RemoteRelay.ensureCode()
        assertEquals(12, RemoteRelay.normalize(code).length)
        val plain = "tajny link http://u:haslo@x".toByteArray()
        val blob = RemoteRelay.encrypt(code, plain)
        assertFalse(String(blob).contains("haslo"))
        assertArrayEquals(plain, RemoteRelay.decrypt(code, blob))
        assertFalse(RemoteRelay.topic(code).contains(RemoteRelay.normalize(code)))
        val srv = fakeNtfy()
        try {
            Prefs.relayServer = srv.url("/").toString(); Prefs.relaySince = ""; Prefs.remotePin = "2468"
            pl.iptv.tv.remote.RemoteServer.init(ctx)
            // telefon: plik + data; drugi komunikat ze złym PIN-em; trzeci zaszyfrowany innym kodem
            RemoteRelay.send(code, "2468", "Z daleka", "", "#EXTM3U\n#EXTINF:-1,X\nhttp://x/1\n#EXTINF:-1,Y\nhttp://x/2\n".toByteArray(), "http://e/epg", "24.12.2030")
            RemoteRelay.send(code, "0000", "Zły PIN", "http://z/1.m3u", null, "", "")
            RemoteRelay.send("ZZZZ-ZZZZ-ZZZZ", "2468", "Obcy", "http://o/1.m3u", null, "", "")
            assertEquals(1, RemoteRelay.pollOnce())
            idle()
            val p = Playlists.all().first { it.name == "Z daleka" }
            assertEquals("24.12.2030", Playlists.formatDate(p.expiry)); assertEquals("http://e/epg", p.epg)
            assertEquals(2, pl.iptv.tv.data.M3uParser.parse(File(p.source).readText()).channels.size)
            assertFalse(Playlists.all().any { it.name == "Zły PIN" || it.name == "Obcy" })
            assertEquals(0, RemoteRelay.pollOnce())                     // nic nowego (since = ostatni id)
            RemoteRelay.send(code, "2468", "Link", "http://l/1.m3u", null, "", "")
            assertEquals(1, RemoteRelay.pollOnce())
            assertEquals("http://l/1.m3u", Playlists.all().first { it.name == "Link" }.source)
        } finally { srv.shutdown(); Prefs.relayServer = "" }
    }

    @Test fun settingsVerticalTabsCollapsibleSectionsNoIcons() {
        SettingsActivity.TAB_NAMES.forEach { assertTrue(it, it.first().isLetter()) }
        assertFalse(SettingsActivity.TAB_NAMES.any { it.contains("obrazie") })
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 3)).setup(); idle()
        val a = c.get()
        val tab = all(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Listy" }
        assertEquals(LinearLayout.VERTICAL, (tab.parent as LinearLayout).orientation)
        val texts = all(a.window.decorView).filterIsInstance<TextView>()
        val pip = texts.first { it.text.toString().endsWith("Obraz w obrazie (PiP)") }
        assertTrue(pip.text.startsWith("▸"))                             // sekcja zwinięta
        val sec = (pip.parent as ViewGroup).let { p -> p.getChildAt(p.indexOfChild(pip) + 1) }
        assertEquals(View.GONE, sec.visibility)
        pip.performClick(); idle()
        val pip2 = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().endsWith("Obraz w obrazie (PiP)") }
        assertTrue(pip2.text.startsWith("▾"))
        assertEquals(View.VISIBLE, (pip2.parent as ViewGroup).let { p -> p.getChildAt(p.indexOfChild(pip2) + 1) }.visibility)
        // tytuły wierszy bez ikon
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Lista kanałów z boku (◄)" })
        c.pause().stop().destroy()
    }
}

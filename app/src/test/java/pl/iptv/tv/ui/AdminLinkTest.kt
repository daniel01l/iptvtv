package pl.iptv.tv.ui

import android.os.Looper
import android.util.Base64
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.shared.RelayCrypto
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.remote.RemoteRelay
import pl.iptv.tv.remote.RemoteServer
import java.io.File

/** Telewizor ↔ administrator przez przekaźnik (serwer testowy zgodny z ntfy). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AdminLinkTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun ntfy(): Pair<MockWebServer, MutableList<Triple<String, String, ByteArray?>>> {
        val s = MockWebServer(); val store = mutableListOf<Triple<String, String, ByteArray?>>()   // temat, tekst, załącznik
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val path = r.path!!
                return when {
                    r.method == "PUT" -> { store += Triple(path.trim('/'), "", r.body.readByteArray()); MockResponse().setBody("{}") }
                    r.method == "POST" -> { store += Triple(path.trim('/'), r.body.readUtf8(), null); MockResponse().setBody("{}") }
                    path.contains("/json?poll=1") -> {
                        val topic = path.substringBefore("/json").trim('/'); val since = path.substringAfter("since=")
                        val all = store.mapIndexedNotNull { i, (t, msg, att) -> if (t != topic) null else
                            JSONObject().put("id", "m$i").put("event", "message").put("message", msg).apply {
                                if (att != null) put("attachment", JSONObject().put("url", s.url("/file/$i").toString())) }.toString() }
                        val out = if (since == "all") all else all.dropWhile { JSONObject(it).getString("id") != since }.drop(1)
                        MockResponse().setBody(out.joinToString("\n"))
                    }
                    path.startsWith("/file/") -> MockResponse().setBody(Buffer().write(store[path.substringAfter("/file/").toInt()].third!!))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start(); return s to store
    }

    /** Odczyt skrzynki administratora (jak w aplikacji IptvTv Admin). */
    private fun adminPoll(srv: MockWebServer, admin: String): List<JSONObject> {
        val body = Repository.http.newCall(okhttp3.Request.Builder().url(srv.url("/${RelayCrypto.adminTopic(admin)}/json?poll=1&since=all")).build()).execute().body!!.string()
        return body.lines().filter { it.isNotBlank() }.map { JSONObject(String(RelayCrypto.decrypt(admin, Base64.decode(JSONObject(it).getString("message"), Base64.DEFAULT)))) }
    }

    /** Polecenie od administratora (jak w aplikacji IptvTv Admin). */
    private fun adminSend(srv: MockWebServer, st: JSONObject, payload: JSONObject) {
        val blob = RelayCrypto.encrypt(st.getString("code"), payload.put("pin", st.getString("pin")).toString().toByteArray())
        Repository.http.newCall(okhttp3.Request.Builder().url(srv.url("/${RelayCrypto.topic(st.getString("code"))}"))
            .put(okhttp3.RequestBody.create(null, blob)).build()).execute().close()
    }

    @Test fun reportCommandsAndListFromAdmin() {
        val (srv, _) = ntfy()
        try {
            Prefs.playlistsJson = "[]"; Prefs.relaySince = ""; Prefs.relayServer = srv.url("/").toString()
            val m3u = File(ctx.filesDir, "adm.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n#EXTINF:-1,B\nhttp://a/2\n") }
            Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(m3u.absolutePath) }
            RemoteServer.init(ctx); Prefs.remotePin = "4242"
            val admin = RelayCrypto.newCode(); Prefs.adminCode = admin; Prefs.deviceLabel = "Salon – Kowalscy"
            Prefs.adminLastReport = 0
            assertFalse(RemoteRelay.reportedToday())
            RemoteRelay.report("start")
            assertTrue(RemoteRelay.reportedToday())                              // kolejny raport automatyczny dopiero jutro
            val st = adminPoll(srv, admin).last()
            assertEquals("Salon – Kowalscy", st.getString("name")); assertEquals("4242", st.getString("pin"))
            assertEquals(2, st.getInt("channels")); assertEquals(-999, st.getInt("days"))
            assertEquals(RemoteRelay.ensureCode(), st.getString("code"))
            // polecenie: data ważności -> nowy raport z liczbą dni
            adminSend(srv, st, JSONObject().put("type", "cmd").put("cmd", "set_expiry").put("date", "31.12.2035"))
            assertEquals(1, RemoteRelay.pollOnce())
            val st2 = adminPoll(srv, admin).last()
            assertTrue(st2.getInt("days") > 3000); assertEquals("31.12.2035", st2.getString("expiry"))
            assertTrue(st2.getString("msg").contains("Data ważności"))
            // lista od administratora
            adminSend(srv, st, JSONObject().put("name", "Od admina").put("url", "").put("epg", "").put("expiry", "")
                .put("file", Base64.encodeToString("#EXTM3U\n#EXTINF:-1,Z\nhttp://z/1\n".toByteArray(), Base64.NO_WRAP)))
            assertEquals(1, RemoteRelay.pollOnce())
            assertTrue(Playlists.all().any { it.name == "Od admina" })
            // token + EPG od administratora
            val tokFile = File(ctx.filesDir, "tok-SEKRET.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,T1\nhttp://t/1\n#EXTINF:-1,T2\nhttp://t/2\n#EXTINF:-1,T3\nhttp://t/3\n") }
            Prefs.playlistSource = File(ctx.filesDir, "tok-{token}.m3u").absolutePath; Playlists.syncActive(Prefs.playlistSource)
            adminSend(srv, st, JSONObject().put("type", "cmd").put("cmd", "set_token").put("token", "SEKRET"))
            assertEquals(1, RemoteRelay.pollOnce())
            // token jest tylko notatką administratora – lista i EPG bez zmian
            assertEquals("", Playlists.active()!!.token); assertEquals(2, Repository.allChannels.size)
            assertTrue(adminPoll(srv, admin).last().getString("msg").contains("Token"))
            // lista od administratora z flagą „activate” – od razu aktywna, EPG zachowane
            Prefs.epgUrl = "http://stare/epg.xml"; Playlists.captureActive()
            adminSend(srv, st, JSONObject().put("name", "Aktywna od admina").put("url", "").put("epg", "").put("expiry", "").put("activate", true)
                .put("file", Base64.encodeToString("#EXTM3U\n#EXTINF:-1,Q\nhttp://q/1\n#EXTINF:-1,W\nhttp://q/2\n".toByteArray(), Base64.NO_WRAP)))
            assertEquals(1, RemoteRelay.pollOnce())
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
            assertEquals("Aktywna od admina", Playlists.active()!!.name)
            assertEquals("http://stare/epg.xml", Playlists.active()!!.epg); assertEquals("http://stare/epg.xml", Prefs.effectiveEpgUrl)
            adminSend(srv, st, JSONObject().put("type", "cmd").put("cmd", "set_epg").put("url", "http://brak/epg.xml"))
            assertEquals(1, RemoteRelay.pollOnce())
            assertEquals("http://brak/epg.xml", Prefs.epgUrl); assertEquals("http://brak/epg.xml", Playlists.active()!!.epg)

            // zły PIN – polecenie ignorowane
            adminSend(srv, JSONObject(st.toString()).put("pin", "0000"), JSONObject().put("type", "cmd").put("cmd", "set_update_url").put("url", "http://zly"))
            RemoteRelay.pollOnce()
            assertNotEquals("http://zly", Prefs.updateUrl)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { srv.shutdown(); Prefs.relayServer = ""; Prefs.adminCode = "" }
    }
}

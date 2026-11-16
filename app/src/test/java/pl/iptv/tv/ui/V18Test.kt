package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import pl.iptv.tv.R
import pl.iptv.tv.data.Backup
import pl.iptv.tv.data.Parental
import pl.iptv.tv.data.Playlists
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.rec.StreamRecorder
import pl.iptv.tv.remote.RemoteClient
import pl.iptv.tv.remote.RemoteServer
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class V18Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).let { if (ms > 0) it.idleFor(Duration.ofMillis(ms)) else it.idle() }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private lateinit var a1: File; private lateinit var a2: File

    @Before fun setup() {
        Prefs.permissionsAsked = true; Prefs.batteryAsked = true; Prefs.setupDone = true; Prefs.startAction = 0
        Prefs.playlistsJson = "[]"; Prefs.parentalOn = false; Prefs.parentalPin = ""; Parental.unlocked = false
        Prefs.lockedChannels = emptyList(); Prefs.lockedGroups = emptyList(); Prefs.parentalHide = false
        a1 = File(ctx.filesDir, "a1.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Bajki\",Bajka\nhttp://t/1\n#EXTINF:-1 group-title=\"Info\",Info\nhttp://t/2\n") }
        a2 = File(ctx.filesDir, "a2.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1,X\nhttp://x/1\n#EXTINF:-1,Y\nhttp://x/2\n#EXTINF:-1,Z\nhttp://x/3\n") }
        Repository.resetAllEdits()
        runBlocking { Repository.loadPlaylist(a1.absolutePath) }
    }

    @Test fun multiplePlaylistsAndExpiryBadge() {
        val main = Playlists.active()!!
        assertEquals(a1.absolutePath, main.source)
        Playlists.update(main.copy(expiry = System.currentTimeMillis() + 10 * 86_400_000L))
        assertEquals(10, Playlists.daysLeft())
        val second = Playlists.add("Druga", a2.absolutePath)
        runBlocking { Repository.switchPlaylist(second.id) }
        assertEquals(3, Repository.allChannels.size)
        assertNull(Playlists.daysLeft())                                 // druga lista bez daty -> brak licznika
        var c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        assertEquals(View.GONE, c.get().findViewById<View>(R.id.expiry).visibility)
        c.pause().stop().destroy()
        runBlocking { Repository.switchPlaylist(main.id) }
        assertEquals(2, Repository.allChannels.size)                     // z pamięci podręcznej pierwszej listy
        c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val badge = c.get().findViewById<TextView>(R.id.expiry)
        assertEquals(View.VISIBLE, badge.visibility); assertTrue(badge.text.contains("10 dni"))
        c.pause().stop().destroy()
        assertEquals(0L, Playlists.parseDate("")); assertNull(Playlists.parseDate("31.02.2026"))
    }

    @Test fun parentalLockAsksPinInPlayer() {
        Parental.setPin("1234"); Prefs.parentalOn = true; Prefs.lockedGroups = listOf("Bajki"); Repository.reapply()
        val locked = Repository.channels.first { it.group == "Bajki" }
        assertTrue(Parental.needsPin(locked)); assertFalse(Parental.needsPin(Repository.channels.first { it.group == "Info" }))
        val c = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, locked.key)).setup(); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("0000")
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(c.get().isFinishing)                                  // zły PIN przy pierwszym kanale -> wyjście
        c.pause().stop().destroy()
        val c2 = Robolectric.buildActivity(PlayerActivity::class.java, Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, locked.key)).setup(); idle()
        val d2 = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        all(d2.window!!.decorView).filterIsInstance<EditText>().first().setText("1234")
        d2.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(Parental.unlocked); assertFalse(c2.get().isFinishing)
        assertEquals(locked.key, Prefs.lastChannel)
        c2.pause().stop().destroy()
        // ukrywanie zablokowanych
        Parental.unlocked = false; Prefs.parentalHide = true; Repository.reapply()
        assertEquals(1, Repository.channels.size)
        Prefs.parentalOn = false; Repository.reapply()
    }

    @Test fun backupRoundTrip() {
        Prefs.recentLimit = 9; Parental.setPin("5555"); Prefs.favorites = listOf("http://t/2")
        val json = Backup.export(ctx)
        assertFalse(json.contains(Prefs.parentalPin))                    // PIN nie trafia do kopii
        Prefs.recentLimit = 3; Prefs.favorites = emptyList()
        assertTrue(Backup.import(ctx, json) > 5)
        assertEquals(9, Prefs.recentLimit); assertEquals(listOf("http://t/2"), Prefs.favorites)
        try { Backup.import(ctx, "{\"app\":\"inna\"}"); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun remoteReceiveFromPhone() {
        Prefs.remotePin = "4321"
        RemoteServer.start(ctx)
        try {
            assertTrue(RemoteServer.running)
            val page = Repository.http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${RemoteServer.PORT}/").build()).execute().body!!.string()
            assertTrue(page.contains("Kod PIN") && page.contains("type=\"file\""))
            try { RemoteClient.send("127.0.0.1", RemoteServer.PORT, "0000", "Zła", "", a2.readBytes(), "", "", false); fail() }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("PIN")) }
            val msg = RemoteClient.send("127.0.0.1", RemoteServer.PORT, "4321", "Z telefonu ąę", "", a2.readBytes(), "http://e/epg.xml", "31.12.2030", false)
            assertTrue(msg, msg.contains("3 kanałów"))
            val p = Playlists.all().first { it.name == "Z telefonu ąę" }
            assertEquals("http://e/epg.xml", p.epg); assertEquals("31.12.2030", Playlists.formatDate(p.expiry))
            // formularz z przeglądarki (multipart)
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("pin", "4321").addFormDataPart("name", "Z przeglądarki").addFormDataPart("expiry", "2030-06-15")
                .addFormDataPart("file", "l.m3u", okhttp3.RequestBody.create(null, a1.readBytes())).build()
            val r = Repository.http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${RemoteServer.PORT}/upload").post(body).build()).execute().body!!.string()
            assertTrue(r, r.contains("✔"))
            assertEquals("15.06.2030", Playlists.formatDate(Playlists.all().first { it.name == "Z przeglądarki" }.expiry))
            idle()
        } finally { RemoteServer.stop() }
    }

    @Test fun recorderTsAndHls() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when (r.path) {
                "/live.ts" -> MockResponse().setBody(Buffer().write(ByteArray(5000) { 0x47 }))
                "/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhigh.m3u8\n")
                "/high.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:2,\ns1.ts\n#EXTINF:2,\ns2.ts\n#EXTINF:2,\ns3.ts\n#EXT-X-ENDLIST\n")
                "/s1.ts" -> MockResponse().setBody("AAA"); "/s2.ts" -> MockResponse().setBody("BBB"); "/s3.ts" -> MockResponse().setBody("CCC")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val rec = StreamRecorder(Repository.http, "test", "")
            val ts = File(ctx.cacheDir, "a.ts")
            runBlocking { rec.record(server.url("/live.ts").toString(), ts, Long.MAX_VALUE) }
            assertEquals(5000, ts.length())
            val hls = File(ctx.cacheDir, "b.ts")
            runBlocking { rec.record(server.url("/master.m3u8").toString(), hls, Long.MAX_VALUE) }
            assertEquals("AAABBBCCC", hls.readText())                   // najwyższa jakość, segmenty po kolei
            assertTrue(server.requestCount >= 5)
        } finally { server.shutdown() }
    }

    @Test fun settingsHasAllTabsAndTheyBuild() {
        assertEquals(12, SettingsActivity.TAB_NAMES.size)
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 0)).setup(); idle()
        val a = c.get()
        for (name in SettingsActivity.TAB_NAMES) {
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == name }.performClick(); idle()
            assertTrue(name, all(a.window.decorView).filterIsInstance<TextView>().any { it.text.contains("Zapisz i zamknij") })
        }
        RemoteServer.stop()
        c.pause().stop().destroy()
    }

    @Test fun setupStoresExpiry() {
        val s = Robolectric.buildActivity(SetupActivity::class.java).setup(); idle()
        val a = s.get()
        fun btn(t: String) = all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith(t) }
        btn("Dalej").performClick(); idle()
        val fields = all(a.window.decorView).filterIsInstance<EditText>()
        fields[1].setText("Moja"); fields[2].setText("01.01.2031")
        btn("Dalej").performClick(); idle()
        assertEquals("Moja", Playlists.active()!!.name)
        assertEquals("01.01.2031", Playlists.formatDate(Playlists.active()!!.expiry))
        s.pause().stop().destroy()
    }
}

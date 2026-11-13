package pl.iptv.admin

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import pl.iptv.shared.RelayCrypto

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AdminTest {
    @Test fun pollsDevicesAndSendsCommands() {
        Admin.init(ApplicationProvider.getApplicationContext())
        val admin = Admin.code
        val tvCode = RelayCrypto.newCode()
        val status = JSONObject().put("type", "status").put("id", "dev1").put("name", "Salon").put("code", tvCode).put("pin", "1111")
            .put("ip", "192.168.1.5").put("days", 12).put("expiry", "01.01.2031").put("list", "Moja").put("ts", System.currentTimeMillis())
        val msg = Base64.encodeToString(RelayCrypto.encrypt(admin, status.toString().toByteArray()), Base64.NO_WRAP)
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when {
                r.method == "PUT" -> { sent += r.path!!.trim('/') to r.body.readByteArray(); MockResponse().setBody("{}") }
                else -> MockResponse().setBody(JSONObject().put("id", "x1").put("event", "message").put("message", msg).toString())
            }
        }
        s.start()
        try {
            Admin.server = s.url("/").toString()
            assertEquals(1, Admin.poll())
            val d = Admin.devices().single()
            assertEquals("Salon", d.name); assertEquals("192.168.1.5", d.j.getString("ip")); assertEquals(12, d.days)
            Admin.command(d, "refresh")
            val (topic, blob) = sent.single()
            assertEquals(RelayCrypto.topic(tvCode), topic)
            val cmd = JSONObject(String(RelayCrypto.decrypt(tvCode, blob)))
            assertEquals("refresh", cmd.getString("cmd")); assertEquals("1111", cmd.getString("pin"))
            Admin.setAlias(d, "Babcia"); assertEquals("Babcia", Admin.devices().single().name)
            // token: tylko notatka w panelu (nic nie jest wysyłane), zostaje po nowym raporcie
            Admin.setToken(Admin.devices().single(), "TOK123"); assertEquals(1, sent.size)
            assertEquals("TOK123", Admin.devices().single().token)
            Admin.sendList(Admin.devices().single(), "L", "http://x/l.m3u", null, "", "")
            val list = JSONObject(String(RelayCrypto.decrypt(tvCode, sent.last().second)))
            assertTrue(list.getBoolean("activate")); assertEquals("http://x/l.m3u", list.getString("url"))
            assertEquals("TOK123", Admin.devices().single().token)
            Robolectric.buildActivity(MainActivity::class.java).setup()       // ekran się buduje
        } finally { s.shutdown() }
    }
}

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
import pl.iptv.shared.ActivationFormat

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivationsTest {
    @Test fun keyListAndCheckins() {
        val c = ApplicationProvider.getApplicationContext<android.app.Application>()
        Admin.init(c); Activations.init(c)
        Activations.generateKey()
        assertTrue(Activations.hasKey)
        val backup = Activations.exportKey(); val pub = Activations.publicKey
        Activations.importKey(backup); assertEquals(pub, Activations.publicKey)
        assertTrue(runCatching { Activations.importKey("{\"type\":\"x\"}") }.isFailure)
        var stored: String? = null; var sha = 0; var auth = ""
        val topic = ActivationFormat.checkinTopic(pub)
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                return when {
                    p.contains("/contents/activation.json") && r.method == "GET" -> { auth = r.getHeader("Authorization").orEmpty()
                        if (stored == null) MockResponse().setResponseCode(404)
                        else MockResponse().setBody(JSONObject().put("content", Base64.encodeToString(stored!!.toByteArray(), Base64.DEFAULT)).put("sha", "s$sha").toString()) }
                    p.contains("/contents/activation.json") && r.method == "PUT" -> {
                        val b = JSONObject(r.body.readUtf8())
                        if (stored != null && b.optString("sha") != "s$sha") MockResponse().setResponseCode(409)
                        else { stored = String(Base64.decode(b.getString("content"), Base64.DEFAULT)); sha++; MockResponse().setBody("{}") } }
                    p.startsWith("/$topic/json") -> MockResponse().setBody(
                        JSONObject().put("id", "m1").put("event", "message").put("time", 1).put("message",
                            JSONObject().put("code", "aaaa-bbbb").put("inst", "i1").put("ts", System.currentTimeMillis()).put("ver", "1.0").put("model", "TV X").toString()).toString() + "\n" +
                        JSONObject().put("id", "m2").put("event", "message").put("time", 1).put("message",
                            JSONObject().put("code", "AAAA-BBBB").put("inst", "i2").put("ts", System.currentTimeMillis()).put("ver", "1.0").put("model", "TV Y").toString()).toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        try {
            Activations.api = s.url("").toString().trimEnd('/'); Activations.relay = s.url("").toString().trimEnd('/')
            Activations.repo = "https://github.com/daniel01l/dplayer"; assertEquals("daniel01l/dplayer", Activations.repo)
            Activations.token = "tok"
            assertTrue(Activations.load().entries.isEmpty())
            assertEquals("Bearer tok", auth)
            Activations.update("Aktywacja") { it["AAAA-BBBB"] = ActivationFormat.Entry(ActivationFormat.ACTIVE, "Jan") }
            Activations.update("Blokada") { it["CCCC-DDDD"] = ActivationFormat.Entry(ActivationFormat.BLOCKED) }
            Activations.update("Pełna") { it["AAAA-BBBB"] = it["AAAA-BBBB"]!!.copy(full = ActivationFormat.ACTIVE) }
            val r = Activations.load()
            assertTrue(r.validSignature)
            assertEquals(ActivationFormat.ACTIVE, r.entries["AAAA-BBBB"]!!.status); assertEquals("Jan", r.entries["AAAA-BBBB"]!!.note)
            assertEquals(ActivationFormat.BLOCKED, r.entries["CCCC-DDDD"]!!.status)
            assertEquals(ActivationFormat.ACTIVE, r.entries["AAAA-BBBB"]!!.full); assertEquals("", r.entries["CCCC-DDDD"]!!.full)
            assertTrue(r.entries["AAAA-BBBB"]!!.forEdition(true) == ActivationFormat.ACTIVE)
            // plik weryfikuje się kluczem publicznym (jak w odtwarzaczu)
            val (payload, sig) = ActivationFormat.unwrap(stored!!)
            assertTrue(ActivationFormat.verify(payload, sig, pub))
            assertFalse(ActivationFormat.verify(payload.replace("\"a\"", "\"b\""), sig, pub))
            // zgłoszenia: ten sam kod z dwóch instalacji = ostrzeżenie
            assertEquals(2, Activations.pollCheckins())
            assertEquals(2, Activations.duplicates("AAAA-BBBB"))
            val a = Robolectric.buildActivity(ActivationsActivity::class.java).setup()      // ekran się buduje
            a.pause().stop().destroy()
        } finally { s.shutdown() }
    }
}

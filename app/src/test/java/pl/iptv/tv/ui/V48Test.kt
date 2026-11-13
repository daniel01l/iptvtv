package pl.iptv.tv.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.shared.ActivationFormat
import pl.iptv.shared.ActivationFormat.Entry
import pl.iptv.tv.data.Activation
import pl.iptv.tv.data.Activation.State
import pl.iptv.tv.data.Prefs
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V48Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val srv = MockWebServer()
    @Volatile private var file = ""
    private val checkins = mutableListOf<String>()
    private val keys = ActivationFormat.newKeyPair()

    private fun publish(entries: Map<String, Entry>, ts: Long, priv: String = keys.first) {
        val p = ActivationFormat.payload(entries, ts); file = ActivationFormat.wrap(p, ActivationFormat.sign(p, priv))
    }

    @Before fun setup() {
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = when {
                r.path!!.startsWith("/activation.json") -> if (file.isEmpty()) MockResponse().setResponseCode(404) else MockResponse().setBody(file)
                r.method == "POST" -> { synchronized(checkins) { checkins += r.body.readUtf8() }; MockResponse().setBody("{}") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        srv.start()
        Activation.forceForTest = true; Activation.fullEdition = false
        Activation.listUrl = srv.url("/activation.json").toString(); Activation.relay = srv.url("/").toString().trimEnd('/'); Activation.pubKey = keys.second
        ctx.getSharedPreferences("activation", 0).edit().clear().commit()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0
    }

    @After fun down() { Activation.forceForTest = false; srv.shutdown() }

    @Test fun codeIsStableAndFormatted() {
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(Activation.code))
        assertEquals(ActivationFormat.codeFromId("abc"), ActivationFormat.codeFromId("abc"))
        assertNotEquals(ActivationFormat.codeFromId("abc"), ActivationFormat.codeFromId("abd"))
        assertEquals("K7QF-3M9X", ActivationFormat.normalize(" k7qf3m9x "))
    }

    @Test fun statesFromSignedList() {
        assertEquals(State.NO_CONSENT, Activation.state())
        Activation.consent = true
        assertEquals(State.PENDING, runBlocking { Activation.check() })            // brak listy
        val code = Activation.code
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE)), 1000)
        assertEquals(State.ACTIVE, runBlocking { Activation.check() })
        val ci = JSONObject(synchronized(checkins) { checkins.last() })
        assertEquals(code, ci.getString("code")); assertFalse(ci.has("ip")); assertTrue(ci.has("inst"))
        // podrobiona lista (inny klucz) – bez zmian
        val other = ActivationFormat.newKeyPair()
        publish(mapOf(code to Entry(ActivationFormat.BLOCKED)), 2000, other.first)
        assertEquals(State.ACTIVE, runBlocking { Activation.check() }); assertTrue(Activation.lastError.contains("podpis"))
        // blokada
        publish(mapOf(code to Entry(ActivationFormat.BLOCKED)), 3000)
        assertEquals(State.BLOCKED, runBlocking { Activation.check() })
        // starsza lista nie cofa blokady
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE)), 2500)
        assertEquals(State.BLOCKED, runBlocking { Activation.check() })
        // odblokowanie
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE)), 4000)
        assertEquals(State.ACTIVE, runBlocking { Activation.check() })
        // bez internetu: 3 dni działa, potem blokada
        srv.shutdown()
        assertEquals(State.ACTIVE, runBlocking { Activation.check() })
        assertEquals(State.ACTIVE, Activation.state(System.currentTimeMillis() + 2 * 86_400_000L))
        assertEquals(State.OFFLINE, Activation.state(System.currentTimeMillis() + 4 * 86_400_000L))
        // inne urządzenie (kod spoza listy) = oczekuje
        ctx.getSharedPreferences("activation", 0).edit().putString("status", "").commit()
        assertEquals(State.PENDING, Activation.state())
    }

    @Test fun fullEditionUsesItsOwnField() {
        Activation.consent = true; Activation.fullEdition = true
        val code = Activation.code
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE)), 1000)                         // tylko test aktywny
        assertEquals(State.PENDING, runBlocking { Activation.check() })                      // pełna – nie
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE, full = ActivationFormat.ACTIVE)), 2000)
        assertEquals(State.ACTIVE, runBlocking { Activation.check() })
        assertEquals("full", JSONObject(synchronized(checkins) { checkins.last() }).getString("ed"))
        publish(mapOf(code to Entry(ActivationFormat.ACTIVE, full = ActivationFormat.BLOCKED)), 3000)   // blokada tylko pełnej
        assertEquals(State.BLOCKED, runBlocking { Activation.check() })
        // pełna: 30 dni bez sprawdzenia
        publish(mapOf(code to Entry("", full = ActivationFormat.ACTIVE)), 4000)
        assertEquals(State.ACTIVE, runBlocking { Activation.check() })
        assertEquals(State.ACTIVE, Activation.state(System.currentTimeMillis() + 20 * 86_400_000L))
        assertEquals(State.OFFLINE, Activation.state(System.currentTimeMillis() + 31 * 86_400_000L))
        Activation.fullEdition = false
    }

    @Test fun lockScreenGatesApp() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val next = shadowOf(c.get()).nextStartedActivity
        assertEquals(LockActivity::class.java.name, next.component!!.className)
        val l = Robolectric.buildActivity(LockActivity::class.java, next).setup(); idle()
        fun all(v: android.view.View): List<android.view.View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val texts = { all(l.get().window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() } }
        assertTrue(texts().any { it.contains("NIE wysyła") })                     // zgoda: co wysyła aplikacja
        all(l.get().window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString().contains("Zgadzam się") }.performClick()
        repeat(10) { idle(100); Thread.sleep(50) }
        assertTrue(texts().any { it == Activation.code })                          // kod do podania administratorowi
        // aktywacja przez administratora → odblokowanie
        publish(mapOf(Activation.code to Entry(ActivationFormat.ACTIVE)), 5000)
        all(l.get().window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString().contains("Sprawdź ponownie") }.performClick()
        repeat(20) { idle(100); Thread.sleep(50) }
        assertTrue(l.get().isFinishing)
        assertEquals(State.ACTIVE, Activation.state())
        c.pause().stop().destroy()
    }
}

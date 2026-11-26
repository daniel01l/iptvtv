package pl.iptv.tv.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.async
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

/**
 * 7.25 – aktywacja przy pierwszym uruchomieniu: ekran odpytuje co [Activation.POLL_MS] (adres unikalny, bez pamięci podręcznej),
 * dokładnie kod pokazany na ekranie, po zatwierdzeniu – od razu dalej i stan zapisany trwale; obrót bez odtworzenia ekranu,
 * wyjście z ekranu zatrzymuje odpytywanie, zgłoszenia do administratora nie co 2,5 s.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V725ActivationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 100) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val srv = MockWebServer()
    private val keys = ActivationFormat.newKeyPair()
    @Volatile private var file = ""
    private val requests = java.util.Collections.synchronizedList(ArrayList<RecordedRequest>())
    private val checkins = java.util.concurrent.atomic.AtomicInteger()

    private fun publish(entries: Map<String, Entry>, ts: Long) {
        val p = ActivationFormat.payload(entries, ts); file = ActivationFormat.wrap(p, ActivationFormat.sign(p, keys.first))
    }

    /** Czas rzeczywisty (sprawdzenie na wątku IO) + pętla główna (opóźnienia odpytywania). */
    private fun runFor(ms: Long, until: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !until()) { idle(250); Thread.sleep(30) }
    }

    @Before fun setup() {
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = when {
                r.path!!.startsWith("/activation.json") -> { requests += r; if (file.isEmpty()) MockResponse().setResponseCode(404) else MockResponse().setBody(file) }
                r.method == "POST" -> { checkins.incrementAndGet(); MockResponse().setBody("{}") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        srv.start()
        Activation.forceForTest = true; Activation.fullEdition = true
        Activation.listUrl = srv.url("/activation.json").toString(); Activation.relay = srv.url("/").toString().trimEnd('/'); Activation.pubKey = keys.second
        ctx.getSharedPreferences("activation", 0).edit().clear().commit()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.startAction = 0
        Activation.consent = true; Activation.resetPollForTest()
    }

    @After fun down() { Activation.forceForTest = false; Activation.fullEdition = pl.iptv.tv.BuildConfig.FULL_EDITION; srv.shutdown() }

    @Test fun approvalIsPickedUpWithinSecondsAndPersisted() {
        publish(mapOf("AAAA-BBBB" to Entry("", full = ActivationFormat.ACTIVE)), 1000)      // inne urządzenie
        val c = Robolectric.buildActivity(LockActivity::class.java).setup()
        val a = c.get()
        runFor(8_000) { a.polls >= 3 }
        assertTrue("odpytywanie co ${Activation.POLL_MS} ms", a.polls >= 3)
        assertFalse(a.isFinishing); assertEquals(State.PENDING, Activation.state())
        // każde sprawdzenie – inny adres i nagłówki bez pamięci podręcznej
        val paths = synchronized(requests) { requests.map { it.path!! } }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(synchronized(requests) { requests.all { it.getHeader("Cache-Control").orEmpty().contains("no-cache") } })
        // zgłoszenie administratorowi – raz (kod widoczny w Adminie), nie przy każdym odpytaniu
        assertTrue("zgłoszenia: ${checkins.get()}", checkins.get() in 1..2)
        // zatwierdzenie w Adminie – kod z ekranu, zapisany bez myślnika i małymi literami
        publish(mapOf(Activation.code.replace("-", "").lowercase() to Entry("", full = ActivationFormat.ACTIVE)), 2000)
        val t0 = System.currentTimeMillis()
        runFor(Activation.POLL_MS * 3) { a.isFinishing }
        assertTrue("odblokowanie bez ponownego wpisywania kodu", a.isFinishing)
        assertTrue("w ciągu kilku sekund", System.currentTimeMillis() - t0 < Activation.POLL_MS * 3)
        assertEquals(pl.iptv.tv.ui.MainActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        // stan od razu na dysku (zamknięcie aplikacji nie cofa aktywacji)
        assertEquals(ActivationFormat.ACTIVE, ctx.getSharedPreferences("activation", 0).getString("status", ""))
        assertEquals(State.ACTIVE, Activation.state())
        // po odblokowaniu – koniec odpytywania
        val n = a.polls; runFor(Activation.POLL_MS * 2); assertEquals(n, a.polls)
        c.pause().stop().destroy()
    }

    @Test fun rotationKeepsScreenAndLeavingStopsPolling() {
        val c = Robolectric.buildActivity(LockActivity::class.java).setup()
        val a = c.get()
        runFor(4_000) { a.polls >= 1 }
        fun texts() = all(a.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(texts().contains(Activation.code))
        val cfg = android.content.res.Configuration(a.resources.configuration).apply {
            orientation = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
            val w = screenWidthDp; screenWidthDp = screenHeightDp; screenHeightDp = w
        }
        c.configurationChange(cfg); idle()
        assertSame("obrót – ten sam ekran (bez odtworzenia)", a, c.get())
        assertTrue(texts().contains(Activation.code))
        val before = a.polls
        runFor(Activation.POLL_MS * 2 + 500) { a.polls > before }
        assertTrue("odpytywanie trwa po obrocie", a.polls > before)
        // wyjście z ekranu – odpytywanie wstrzymane (cykl życia), powrót – wznowione od razu
        c.pause().stop(); runFor(500)
        val stopped = Activation.fetches
        runFor(Activation.POLL_MS * 2)
        assertEquals(stopped, Activation.fetches)
        c.start().resume(); runFor(3_000) { Activation.fetches > stopped }
        assertTrue(Activation.fetches > stopped)
        c.pause().stop().destroy()
    }

    @Test fun checksNeverOverlapAndUseSameCode() {
        assertEquals(Activation.code, ActivationFormat.normalize(Activation.code))
        val e = Entry("", full = ActivationFormat.ACTIVE)
        assertSame(e, Activation.entryFor(mapOf(Activation.code to e)))
        assertSame(e, Activation.entryFor(mapOf(Activation.code.replace("-", " ").lowercase() to e)))
        assertNull(Activation.entryFor(mapOf("ZZZZ-ZZZZ" to e)))
        assertNotEquals(Activation.freshUrl(5), Activation.freshUrl(5))
        // kilka sprawdzeń naraz (ekran, pętla aplikacji, przycisk) – po kolei, bez sprzecznych zapisów
        publish(mapOf(Activation.code to e), 3000)
        val results = (1..4).map { kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { Activation.check(poll = true) } }
        val states = kotlinx.coroutines.runBlocking { results.map { it.await() } }
        assertTrue(states.all { it == State.ACTIVE })
    }

    private fun all(v: android.view.View): List<android.view.View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
}

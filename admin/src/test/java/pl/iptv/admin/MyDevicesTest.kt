package pl.iptv.admin

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.shared.ActivationFormat
import pl.iptv.shared.RelayCrypto

/** Admin 2.4: „Moje urządzenia” widoczne od razu, format „Etykieta: KOD”, wybór listy, zapisane listy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MyDevicesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun labelFormat() {
        assertEquals("Salon: K7QF-3M9X", deviceLabel("Salon", "K7QF-3M9X"))
        assertEquals("Urządzenie: K7QF-3M9X", deviceLabel("  ", "K7QF-3M9X"))
    }

    @Test fun addedDeviceVisibleAtOnceThenReplacedByReport() {
        Admin.init(ctx)
        Admin.devices().forEach { Admin.remove(it) }
        Admin.addPending("AAAA-BBBB", "Salon")
        val p = Admin.devices().single()
        assertTrue(p.pending); assertEquals("AAAA-BBBB", p.displayCode); assertEquals("Salon", p.name)
        Admin.setAlias(p, "Babcia")
        // pierwszy raport z tym kodem aktywacji zastępuje wpis oczekujący (nazwa z panelu zostaje)
        val admin = Admin.code
        val status = JSONObject().put("type", "status").put("id", "dev9").put("name", "TV").put("code", RelayCrypto.newCode()).put("pin", "2222")
            .put("act", "AAAA-BBBB").put("ts", System.currentTimeMillis())
            .put("playlists", JSONArray().put(JSONObject().put("id", "main").put("name", "[PL] Sport").put("active", true))
                .put(JSONObject().put("id", "p2").put("name", "Filmy").put("active", false)))
        val msg = Base64.encodeToString(RelayCrypto.encrypt(admin, status.toString().toByteArray()), Base64.NO_WRAP)
        val sent = mutableListOf<JSONObject>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when (r.method) {
                "PUT" -> {
                    val code = Admin.devices().single().code
                    sent += JSONObject(String(RelayCrypto.decrypt(code, r.body.readByteArray()))); MockResponse().setBody("{}")
                }
                else -> MockResponse().setBody(JSONObject().put("id", "m1").put("event", "message").put("message", msg).toString())
            }
        }
        s.start()
        try {
            Admin.server = s.url("/").toString()
            assertEquals(1, Admin.poll())
            val d = Admin.devices().single()
            assertFalse(d.pending); assertEquals("Babcia", d.name); assertEquals("Babcia: AAAA-BBBB", deviceLabel(d.name, d.displayCode))
            assertEquals(listOf("[PL] Sport", "Filmy"), d.playlists.map { it.name }); assertTrue(d.playlists[0].active)
            // wybór aktywnej listy na urządzeniu
            Admin.useList(d, "p2")
            assertEquals("use_list", sent.single().getString("cmd")); assertEquals("p2", sent.single().getString("id"))
            // ponowne dodanie (urządzenie już raportuje) nie tworzy duplikatu
            Admin.addPending("AAAA-BBBB", "Salon"); assertEquals(1, Admin.devices().size)
        } finally { s.shutdown(); Admin.server = "" }
    }

    @Test fun removePendingAndSavedLists() {
        Admin.init(ctx)
        Admin.devices().forEach { Admin.remove(it) }
        Admin.addPending("CCCC-DDDD", "Kuchnia"); assertEquals(1, Admin.devices().size)
        Admin.removePending("CCCC-DDDD"); assertTrue(Admin.devices().isEmpty())
        Admin.savedLists().forEach { Admin.removeList(it.name) }
        Admin.putList(SavedList("Sport", "http://x/s.m3u", "http://x/e.xml", "31.12.2030"))
        Admin.putList(SavedList("Filmy", "http://x/f.m3u"))
        Admin.putList(SavedList("Sport", "http://x/s2.m3u"))            // ta sama nazwa – zastąpienie
        assertEquals(listOf("Filmy", "Sport"), Admin.savedLists().map { it.name })
        assertEquals("http://x/s2.m3u", Admin.savedLists().last().url)
        Admin.removeList("Filmy"); assertEquals(listOf("Sport"), Admin.savedLists().map { it.name })
    }

    /** Zapis zwraca nowy stan (GitHub chwilę po zapisie oddaje jeszcze starą wersję – zmiana musi być widoczna od razu). */
    @Test fun updateReturnsSavedStateWithoutStaleReload() {
        Activations.init(ctx)
        if (!Activations.hasKey) Activations.generateKey()
        Activations.token = "tok"
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when (r.method) {
                "PUT" -> MockResponse().setBody(JSONObject().put("content", JSONObject().put("sha", "nowy")).toString())
                else -> MockResponse().setResponseCode(404)           // „stara” wersja: pliku jeszcze nie ma
            }
        }
        s.start()
        try {
            Activations.api = s.url("/").toString().trimEnd('/')
            val r = Activations.update("Dodano do moich EEEE-FFFF") { m -> m["EEEE-FFFF"] = ActivationFormat.Entry("", full = ActivationFormat.ACTIVE, admin = "zaszyfrowany") }
            assertEquals("nowy", r.sha)
            assertEquals("zaszyfrowany", r.entries["EEEE-FFFF"]!!.admin)
        } finally { s.shutdown(); Activations.api = "https://api.github.com" }
    }
}

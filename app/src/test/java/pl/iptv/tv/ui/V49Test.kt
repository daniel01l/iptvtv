package pl.iptv.tv.ui

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
import pl.iptv.tv.update.Updater
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V49Test {
    @Test fun updateRetriesOn504AndFallsBackToApi() {
        val n = AtomicInteger(0)
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!
                return when {
                    p == "/o/r/releases/latest/download/iptvtv.json" -> if (n.incrementAndGet() < 3) MockResponse().setResponseCode(504) else MockResponse().setBody("{\"versionCode\":99}")
                    p == "/o/r/releases/latest/download/D-iptv-Player.apk" -> MockResponse().setResponseCode(504)
                    p == "/api/repos/o/r/releases/latest" -> MockResponse().setBody(JSONObject().put("assets", JSONArray().put(
                        JSONObject().put("name", "D-iptv-Player.apk").put("url", s.url("/api/asset/7").toString()))).toString())
                    p == "/api/asset/7" -> if (r.getHeader("Accept") == "application/octet-stream") MockResponse().setBody("APKDATA") else MockResponse().setResponseCode(415)
                    p == "/o/r/releases/latest/download/brak.json" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        s.start()
        try {
            Updater.retryDelaysMs = listOf(10L, 10L); Updater.apiBase = s.url("/api").toString().trimEnd('/')
            // 504, 504, potem OK – ponowienia
            assertEquals("{\"versionCode\":99}", String(Updater.fetch(s.url("/o/r/releases/latest/download/iptvtv.json").toString())))
            assertEquals(3, n.get())
            // ciągle 504 – plik z wydania przez API
            assertEquals("APKDATA", String(Updater.fetch(s.url("/o/r/releases/latest/download/D-iptv-Player.apk").toString())))
            // 404 – bez ponowień, czytelny błąd
            val e = runCatching { Updater.fetch(s.url("/o/r/releases/latest/download/brak.json").toString()) }.exceptionOrNull()
            assertEquals("HTTP 404", e?.message)
        } finally { s.shutdown(); Updater.retryDelaysMs = listOf(2_000L, 5_000L); Updater.apiBase = "https://api.github.com" }
    }
}

package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.YouTubeAuth
import pl.iptv.tv.data.YouTubeRepository
import pl.iptv.tv.data.YouTubeTokens
import pl.iptv.tv.data.YtException
import pl.iptv.tv.data.YtSource
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** Konto YouTube: OAuth Device Flow (kod, odpytywanie, odświeżanie), magazyn tokenów, repozytorium API v3, karta Ustawień. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class YouTubeAccountTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private lateinit var server: MockWebServer
    /** Kolejne odpowiedzi endpointu tokenów. */
    private val tokenReplies = ConcurrentLinkedQueue<MockResponse>()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private var apiHandler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                return when {
                    p.startsWith("/device") -> json(200, """{"device_code":"DEV1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":1}""")
                    p.startsWith("/token") -> tokenReplies.poll() ?: json(428, """{"error":"authorization_pending"}""")
                    p.startsWith("/revoke") -> json(200, "{}")
                    p.startsWith("/yt/") -> apiHandler(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        YouTubeAuth.deviceCodeUrl = server.url("/device").toString()
        YouTubeAuth.tokenUrl = server.url("/token").toString()
        YouTubeAuth.revokeUrl = server.url("/revoke").toString()
        YouTubeAuth.slowDownStepMs = 10
        YouTubeRepository.apiBase = server.url("/yt").toString().trimEnd('/')
        Prefs.ytClientId = "test-client.apps.googleusercontent.com"; Prefs.ytClientSecret = "secret"; Prefs.ytApiKey = ""
        Prefs.ytSource = 0; Prefs.ytRegion = "PL"
        YouTubeTokens.useStorage(ctx.getSharedPreferences("yt_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        YouTubeRepository.clearCache()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.settingsOpen = emptyList()
    }

    @After fun tearDown() { YouTubeTokens.clear(); Prefs.ytClientId = ""; Prefs.ytClientSecret = ""; Prefs.ytApiKey = ""; server.shutdown() }

    private fun dc(intervalMs: Long = 10, expiresIn: Long = 60_000) =
        YouTubeAuth.DeviceCode("DEV1", "ABCD-EFGH", "https://www.google.com/device", System.currentTimeMillis() + expiresIn, intervalMs)

    private fun tokenOk(access: String = "AT1", refresh: String? = "RT1", exp: Int = 3600) =
        json(200, """{"access_token":"$access",${refresh?.let { "\"refresh_token\":\"$it\"," } ?: ""}"expires_in":$exp,"scope":"${YouTubeAuth.SCOPE}","token_type":"Bearer"}""")

    @Test fun deviceCodeRequest() = runBlocking {
        val d = YouTubeAuth.requestDeviceCode(now = 1_000L)
        assertEquals("ABCD-EFGH", d.userCode); assertEquals("DEV1", d.deviceCode)
        assertEquals(1_000L + 1_800_000L, d.expiresAt); assertEquals(1000L, d.intervalMs)
        val body = requests.first { it.path!!.startsWith("/device") }.body.readUtf8()
        assertTrue(body.contains("client_id=test-client")); assertTrue(body.contains("youtube.readonly"))
    }

    @Test fun noClientIdFailsClearly() = runBlocking {
        Prefs.ytClientId = ""
        if (YouTubeAuth.configured) return@runBlocking   // klient wbudowany w BuildConfig
        val e = runCatching { YouTubeAuth.requestDeviceCode() }.exceptionOrNull()
        assertTrue(e is YouTubeAuth.AuthException && e.code == "no_client")
    }

    @Test fun pollingPendingSlowDownThenSuccess() = runBlocking {
        tokenReplies += json(428, """{"error":"authorization_pending"}""")
        tokenReplies += json(403, """{"error":"slow_down"}""")
        tokenReplies += tokenOk()
        assertTrue(YouTubeAuth.awaitAuthorization(dc()))
        assertEquals("AT1", YouTubeTokens.accessToken); assertEquals("RT1", YouTubeTokens.refreshToken)
        assertTrue(YouTubeTokens.loggedIn); assertFalse(YouTubeTokens.expired())
        val polls = requests.filter { it.path!!.startsWith("/token") }
        assertEquals(3, polls.size)
        assertTrue(polls.all { it.body.clone().readUtf8().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code") })
    }

    @Test fun pollingDeniedAndExpired() = runBlocking {
        tokenReplies += json(403, """{"error":"access_denied"}""")
        val e = runCatching { YouTubeAuth.awaitAuthorization(dc()) }.exceptionOrNull() as YouTubeAuth.AuthException
        assertEquals("access_denied", e.code); assertFalse(YouTubeTokens.loggedIn)
        val e2 = runCatching { YouTubeAuth.awaitAuthorization(dc(expiresIn = 0)) }.exceptionOrNull() as YouTubeAuth.AuthException
        assertEquals("expired_token", e2.code)
    }

    @Test fun refreshKeepsRefreshTokenAndInvalidGrantLogsOut() = runBlocking {
        YouTubeTokens.save("OLD", "RT1", expiresInS = 0)
        assertTrue(YouTubeTokens.expired())
        tokenReplies += tokenOk(access = "NEW", refresh = null)
        assertEquals("NEW", YouTubeAuth.accessToken())
        assertEquals("RT1", YouTubeTokens.refreshToken)     // Google nie zwraca refresh_token przy odświeżeniu
        assertEquals("NEW", YouTubeAuth.accessToken())       // ważny – bez kolejnego zapytania
        assertEquals(1, requests.count { it.path!!.startsWith("/token") })

        YouTubeTokens.save("OLD", null, expiresInS = 0)
        tokenReplies += json(400, """{"error":"invalid_grant"}""")
        assertNull(YouTubeAuth.accessToken())
        assertFalse(YouTubeTokens.loggedIn); assertEquals("", YouTubeTokens.refreshToken)
    }

    @Test fun logoutRevokesAndClears() = runBlocking {
        YouTubeTokens.save("AT", "RT", 3600); YouTubeTokens.saveAccount("Kanał")
        YouTubeAuth.logout()
        assertFalse(YouTubeTokens.loggedIn); assertEquals("", YouTubeTokens.accountName)
        assertTrue(requests.first { it.path!!.startsWith("/revoke") }.body.readUtf8().contains("token=RT"))
    }

    @Test fun tokensNotInBackupPrefsAndMemoryFallback() {
        YouTubeTokens.save("AT", "RT", 3600)
        val main = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE).all
        assertTrue(main.values.none { it == "AT" || it == "RT" })
        // bez Keystore (np. Robolectric, uszkodzony magazyn) – tylko pamięć, nic na dysku
        YouTubeTokens.useStorage(null)
        assertFalse(YouTubeTokens.persistent)
        YouTubeTokens.save("MEM", "RTM", 3600)
        assertEquals("MEM", YouTubeTokens.accessToken); assertTrue(YouTubeTokens.loggedIn)
        YouTubeTokens.clear(); assertFalse(YouTubeTokens.loggedIn)
    }

    private val videosJson = """{"nextPageToken":"P2","items":[{"id":"dQw4w9WgXcQ","snippet":{"title":"Film","channelTitle":"Kanał",
        "thumbnails":{"high":{"url":"https://i.ytimg.com/h.jpg"}}},"contentDetails":{"duration":"PT3M33S"}}]}"""

    @Test fun personalUsesBearerTrendingUsesKey() = runBlocking {
        apiHandler = { json(200, videosJson) }
        Prefs.ytApiKey = "KEY1"
        // bez konta: spersonalizowane → trendy z kluczem API
        val f0 = YouTubeRepository.feed(YtSource.PERSONAL)
        assertEquals(YtSource.TRENDING, f0.source); assertTrue(f0.fellBack)
        val r0 = requests.last { it.path!!.startsWith("/yt/") }
        assertTrue(r0.path!!.contains("chart=mostPopular") && r0.path!!.contains("regionCode=PL") && r0.path!!.contains("key=KEY1"))
        assertNull(r0.getHeader("Authorization"))
        // z kontem: polubione z tokenem
        YouTubeTokens.save("AT1", "RT1", 3600)
        val f1 = YouTubeRepository.feed(YtSource.PERSONAL)
        assertEquals(YtSource.PERSONAL, f1.source); assertFalse(f1.fellBack)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", f1.videos.single().url)
        assertEquals("PT3M33S", f1.videos.single().duration); assertEquals("P2", f1.nextPage)
        val r1 = requests.last { it.path!!.startsWith("/yt/") }
        assertTrue(r1.path!!.contains("myRating=like")); assertEquals("Bearer AT1", r1.getHeader("Authorization"))
        // przełączenie źródła
        YouTubeRepository.source = YtSource.TRENDING
        assertEquals(YtSource.TRENDING, YouTubeRepository.effectiveSource)
        assertEquals(1, Prefs.ytSource)
    }

    @Test fun unauthorizedRefreshesAndRetries() = runBlocking {
        YouTubeTokens.save("STALE", "RT1", 3600)
        apiHandler = { r -> if (r.getHeader("Authorization") == "Bearer STALE") json(401, """{"error":{"code":401}}""") else json(200, videosJson) }
        tokenReplies += tokenOk(access = "FRESH", refresh = null)
        val f = YouTubeRepository.feed(YtSource.PERSONAL, force = true)
        assertEquals(1, f.videos.size); assertEquals("FRESH", YouTubeTokens.accessToken)
        // dostęp odwołany: odświeżenie odrzucone → wylogowanie i trendy (z kluczem)
        Prefs.ytApiKey = "KEY1"; YouTubeTokens.save("STALE", "RT1", 3600)
        tokenReplies += json(400, """{"error":"invalid_grant"}""")
        val f2 = YouTubeRepository.feed(YtSource.PERSONAL, force = true)
        assertTrue(f2.fellBack); assertFalse(YouTubeTokens.loggedIn)
    }

    @Test fun quotaErrorMessage() = runBlocking {
        Prefs.ytApiKey = "KEY1"
        apiHandler = { json(403, """{"error":{"code":403,"message":"x","errors":[{"reason":"quotaExceeded"}]}}""") }
        val e = runCatching { YouTubeRepository.trending() }.exceptionOrNull() as YtException
        assertEquals(403, e.http); assertTrue(e.message!!.contains("limit"))
        Prefs.ytApiKey = ""
        val e2 = runCatching { YouTubeRepository.trending() }.exceptionOrNull() as YtException
        assertTrue(e2.message!!.contains("klucza API"))
    }

    // ---------------- interfejs ----------------

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun settingsTab(): SettingsActivity {
        Prefs.settingsOpen = listOf("${SettingsActivity.YOUTUBE_TAB}|Konto / Logowanie YouTube")
        return Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", SettingsActivity.YOUTUBE_TAB)).setup().get().also { idle() }
    }

    @Test fun settingsTabAndLoginDialog() {
        assertEquals("Konto YouTube", SettingsActivity.TAB_NAMES[SettingsActivity.YOUTUBE_TAB])
        val a = settingsTab()
        val all = texts(a.window.decorView)
        assertTrue(all.any { it.startsWith("Zaloguj") })
        assertTrue(all.any { it.startsWith("▾") && it.contains("Konto / Logowanie YouTube") })
        // wiersz „Zaloguj” fokusowalny (D-pad) i otwiera okno z kodem
        val row = generateSequence(listOf(a.window.decorView)) { l -> l.flatMap { (it as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty() }.ifEmpty { null } }
            .flatten().first { it.getTag(pl.iptv.tv.R.id.row_key) == "yt|login" }
        assertTrue(row.isFocusable)
        row.performClick()
        var pinShown = false
        repeat(60) {
            idle(100); Thread.sleep(20)
            val d = YouTubeAccountUi.current
            if (d != null && texts(d.window!!.decorView).contains("ABCD-EFGH")) { pinShown = true; return@repeat }
        }
        assertTrue("kod widoczny", pinShown)
        val d = YouTubeAccountUi.current!!
        assertTrue(texts(d.window!!.decorView).any { it.contains("google.com/device") })
        // 7.0: kod QR z adresem logowania (generowany lokalnie) obok kodu
        fun views(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())
        val qr = views(d.window!!.decorView).filterIsInstance<android.widget.ImageView>().first { it.contentDescription == "Kod QR logowania" }
        assertNotNull(qr.drawable)
        assertEquals("Token…", d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
        // potwierdzenie na telefonie → zapis tokenów, okno zamknięte
        tokenReplies += tokenOk()
        apiHandler = { json(200, """{"items":[{"snippet":{"title":"Mój kanał"}}]}""") }
        repeat(80) { if (!d.isShowing) return@repeat; idle(1100); Thread.sleep(20) }
        assertFalse(d.isShowing)
        assertTrue(YouTubeTokens.loggedIn); assertEquals("Mój kanał", YouTubeTokens.accountName)
        idle()
        assertTrue(texts(a.window.decorView).any { it.contains("Mój kanał") })
    }

    @Test fun cancelStopsPolling() {
        val a = settingsTab()
        YouTubeAccountUi.login(a) {}
        repeat(30) { idle(100); Thread.sleep(20) }
        YouTubeAccountUi.current!!.dismiss(); idle()
        val n = requests.count { it.path!!.startsWith("/token") }
        repeat(5) { idle(1100); Thread.sleep(20) }
        assertEquals("po zamknięciu okna bez odpytywania", n, requests.count { it.path!!.startsWith("/token") })
        assertNull(YouTubeAccountUi.current)
    }
}

package pl.iptv.tv.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioTmdb
import pl.iptv.tv.data.StremioTop10
import java.time.Duration

/** 7.21 – własny klucz TMDB w Stremio: bez klucza wszystko jak w 7.19 (bez Top 10, także z pamięci), klucz sprawdzany, z telefonu przez /api/tmdb z PIN-em, kod QR. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V721TmdbKeyTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun keyFromSettingsNotBuiltInValidatedAndQr() = runBlocking {
        assertEquals("", pl.iptv.tv.BuildConfig.TMDB_API_KEY)                        // klucz nie jest wbudowany w APK
        Prefs.tmdbApiKey = ""; StremioTmdb.apiKey = { Prefs.tmdbApiKey }
        assertFalse(StremioTmdb.available)
        // bez klucza – rzędy Top 10 nie pojawiają się nawet z pamięci podręcznej
        StremioTop10.resetForTest(ctx)
        Prefs.tmdbApiKey = "  abc123  "; assertEquals("abc123", Prefs.tmdbApiKey); assertTrue(StremioTmdb.available)
        Prefs.tmdbApiKey = ""
        val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(200).setBody("{}")); s.enqueue(MockResponse().setResponseCode(401)); s.start()
        StremioApi.client = { okhttp3.OkHttpClient() }; StremioTmdb.base = s.url("/3").toString().removeSuffix("/")
        try {
            assertEquals(true, StremioTmdb.validate("good")); assertEquals(false, StremioTmdb.validate("bad"))
        } finally { s.shutdown(); StremioTmdb.base = "https://api.themoviedb.org/3"; StremioApi.client = { pl.iptv.tv.data.Repository.http } }
        val q = QrCode.image(ctx, "http://192.168.1.2:8765/tmdb", 140)
        assertNotNull(q.drawable); assertEquals("Kod QR: http://192.168.1.2:8765/tmdb", q.contentDescription)
        assertTrue(StremioTmdb.ATTRIBUTION.contains("not endorsed or certified by TMDB"))
        Unit
    }

    @Test fun boardWithoutKeyLooksLike719() {
        Prefs.tmdbApiKey = ""; StremioTmdb.apiKey = { Prefs.tmdbApiKey }
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = true; Prefs.stremioSync = 0
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertTrue(a.rowTitles.toString(), a.rowTitles.none { it.startsWith("Top 10") })
        // przycisk „Klucz TMDB” dostępny na ekranie Stremio
        fun all(v: android.view.View): List<android.view.View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        assertTrue(all(a.window.decorView).any { it.contentDescription == TmdbKeyUi.TITLE })
        Prefs.stremioGuest = false
    }
}

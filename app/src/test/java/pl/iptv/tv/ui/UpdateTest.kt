package pl.iptv.tv.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import pl.iptv.tv.remote.RemoteClient
import pl.iptv.tv.remote.RemoteServer
import pl.iptv.tv.update.Updater
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun manifestAndVersionCheck() {
        val m = Updater.parseManifest("https://x.pl/app/iptvtv.json", """{"versionCode":99,"versionName":"9.9","apk":"IptvTv.apk","notes":"n"}""")
        assertEquals("https://x.pl/app/IptvTv.apk", m.apkUrl); assertEquals(99, m.versionCode)
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"versionCode":${Updater.currentCode(ctx) + 1},"versionName":"nowa","apk":"a.apk"}"""))
        s.enqueue(MockResponse().setBody("""{"versionCode":${Updater.currentCode(ctx)},"versionName":"ta sama","apk":"a.apk"}"""))
        s.start()
        try {
            val newer = runBlocking { Updater.check(ctx, s.url("/v.json").toString()) }
            assertEquals("nowa", newer!!.versionName); assertTrue(newer.apkUrl.endsWith("/a.apk"))
            assertNull(runBlocking { Updater.check(ctx, s.url("/v.json").toString()) })     // brak nowszej
        } finally { s.shutdown() }
        val apkOnly = runBlocking { Updater.check(ctx, "https://x.pl/IptvTv.apk") }
        assertEquals(-1L, apkOnly!!.versionCode)
    }

    @Test fun rejectsForeignFilesAndWrongPin() {
        val junk = File(ctx.cacheDir, "junk.apk").apply { writeBytes(ByteArray(5000) { 7 }) }
        assertNotNull(Updater.verify(ctx, junk))                                   // nie APK / inny podpis
        Prefs.remotePin = "1357"
        RemoteServer.start(ctx)
        try {
            try { RemoteClient.sendApk("127.0.0.1", RemoteServer.PORT, "0000", junk.readBytes()); fail() }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("PIN")) }
            try { RemoteClient.sendApk("127.0.0.1", RemoteServer.PORT, "1357", junk.readBytes()); fail() }
            catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.isNotBlank()) }  // odrzucony przez weryfikację
            val page = pl.iptv.tv.data.Repository.http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${RemoteServer.PORT}/").build()).execute().body!!.string()
            assertTrue(page.contains("upload-apk"))
        } finally { RemoteServer.stop() }
        assertFalse(RemoteServer.receiveApk("1357", ByteArray(10)).first)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun settingsUpdateTab() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java, Intent().putExtra("tab", 10)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        val t = all(c.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        listOf("Zainstalowana wersja", "Sprawdź i zainstaluj aktualizację", "Zgoda na instalowanie aktualizacji",
            "Wyślij aktualizację w tej samej sieci Wi-Fi…", "Wyślij aktualizację przez Internet (kod urządzenia)…").forEach { assertTrue(it, t.contains(it)) }
        c.pause().stop().destroy()
    }
}

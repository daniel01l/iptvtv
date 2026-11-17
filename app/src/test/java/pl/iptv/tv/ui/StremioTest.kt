package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
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
import org.robolectric.shadows.ShadowDialog
import androidx.media3.common.PlaybackException as PE
import pl.iptv.tv.ui.StremioDecoders as D
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioMetaPreview
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StremioSubs
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stremio: manifest i protokół wtyczek, jakość / język / rozmiar źródeł, limit 5 źródeł na wtyczkę i tylko włączone wtyczki,
 * logowanie i okno synchronizacji, wtyczki z konta (stan włącz/wyłącz w Room), synchronizacja biblioteki, napisy SRT/VTT
 * z opóźnieniem, odtwarzacz (napisy z wtyczki, opóźnienie, obraz, następny odcinek z tej samej grupy źródeł).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class StremioTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private lateinit var db: StubeDatabase
    private lateinit var server: MockWebServer
    @Volatile private var linkConfirmed = false
    private val paths = CopyOnWriteArrayList<String>()
    private val puts = CopyOnWriteArrayList<String>()
    private fun url(p: String) = server.url(p).toString()

    private fun manifest(id: String, name: String, resources: JSONArray, catalogs: JSONArray = JSONArray()) = JSONObject()
        .put("id", id).put("name", name).put("version", "1.0.0").put("types", JSONArray(listOf("movie", "series"))).put("idPrefixes", JSONArray(listOf("tt")))
        .put("resources", resources).put("catalogs", catalogs)

    private val metaManifest get() = manifest("meta", "Meta", JSONArray(listOf("catalog", "meta", JSONObject().put("name", "subtitles").put("types", JSONArray(listOf("movie", "series"))))),
        JSONArray().put(JSONObject().put("type", "movie").put("id", "top").put("name", "Popularne").put("extra", JSONArray().put(JSONObject().put("name", "search"))))
            .put(JSONObject().put("type", "series").put("id", "top").put("name", "Popularne").put("extra", JSONArray().put(JSONObject().put("name", "search")))))
    private fun streamManifest(id: String) = manifest(id, "Źródła $id", JSONArray(listOf("stream")))

    private fun streams(addon: String, n: Int) = JSONObject().put("streams", JSONArray((1..n).map { i ->
        JSONObject().put("name", "$addon\n${if (i == 1) "4k HDR" else "1080p"}").put("title", "Film.$i.${if (i == 1) "2160p" else "1080p"}.WEB-DL.mkv\n👤 12 💾 ${i}.5 GB\n🇵🇱 / 🇬🇧")
            .put("url", url("/v$i.mp4")).put("behaviorHints", JSONObject().put("bingeGroup", "$addon|${if (i == 1) "4k" else "1080p"}"))
    }))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build()
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioMetas.clear()
        Prefs.stremioSync = -1; Prefs.stremioGuest = false; Prefs.stremioSubDelay = 0; Prefs.stremioAspect = 0; Prefs.stremioSubLang = "pol"
        Prefs.permissionsAsked = true; Prefs.setupDone = true
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!; paths += p
                fun ok(o: Any) = MockResponse().setBody(o.toString()).setHeader("Content-Type", "application/json")
                return when {
                    p == "/api/login" -> if (r.body.readUtf8().contains("\"password\":\"dobre\"")) ok(JSONObject().put("result", JSONObject().put("authKey", "AK1").put("user", JSONObject().put("email", "jan@x.pl"))))
                        else ok(JSONObject().put("error", JSONObject().put("message", "Nieprawidłowe hasło").put("code", 2)))
                    p == "/api/getUser" -> ok(JSONObject().put("result", JSONObject().put("email", "tok@x.pl")))
                    p == "/api/addonCollectionGet" -> ok(JSONObject().put("result", JSONObject().put("addons", JSONArray()
                        .put(JSONObject().put("transportUrl", url("/m/manifest.json")).put("manifest", metaManifest))
                        .put(JSONObject().put("transportUrl", url("/a/manifest.json")).put("manifest", streamManifest("a")))
                        .put(JSONObject().put("transportUrl", url("/b/manifest.json")).put("manifest", streamManifest("b"))))))
                    p == "/api/datastoreGet" -> ok(JSONObject().put("result", JSONArray().put(JSONObject().put("_id", "tt9").put("type", "movie").put("name", "Z konta")
                        .put("poster", "").put("removed", false).put("state", JSONObject().put("timeOffset", 600_000).put("duration", 6_000_000)
                            .put("video_id", "tt9").put("lastWatched", "2026-11-10T20:00:00.000Z")))))
                    p == "/api/datastorePut" -> { puts += r.body.readUtf8(); ok(JSONObject().put("result", JSONObject().put("success", true))) }
                    p == "/api/logout" -> ok(JSONObject().put("result", JSONObject()))
                    p == "/link/create?type=Create" -> ok(JSONObject().put("result", JSONObject().put("success", true).put("code", "QR12").put("link", "https://link.stremio.com/QR12").put("qrcode", url("/link/qr.png"))))
                    p == "/link/read?type=Read&code=QR12" -> if (linkConfirmed) ok(JSONObject().put("result", JSONObject().put("authKey", "AKQ"))) else ok(JSONObject().put("error", JSONObject().put("code", 101).put("message", "Invalid or expired token")))
                    p == "/m/manifest.json" -> ok(metaManifest)
                    p == "/a/manifest.json" -> ok(streamManifest("a"))
                    p == "/b/manifest.json" -> ok(streamManifest("b"))
                    p.startsWith("/m/catalog/movie/top") -> ok(JSONObject().put("metas", JSONArray().put(JSONObject().put("id", "tt1").put("type", "movie").put("name", "Film Testowy").put("poster", "").put("releaseInfo", "2024").put("description", "Opis filmu"))))
                    p.startsWith("/m/catalog/series/top") -> ok(JSONObject().put("metas", JSONArray().put(JSONObject().put("id", "tt2").put("type", "series").put("name", "Serial Testowy").put("poster", ""))))
                    p.startsWith("/m/meta/series/tt2") -> ok(JSONObject().put("meta", JSONObject().put("id", "tt2").put("type", "series").put("name", "Serial Testowy").put("description", "Opis serialu")
                        .put("videos", JSONArray().put(JSONObject().put("id", "tt2:1:1").put("name", "Pilot").put("season", 1).put("episode", 1))
                            .put(JSONObject().put("id", "tt2:1:2").put("name", "Drugi").put("season", 1).put("episode", 2))
                            .put(JSONObject().put("id", "tt2:0:1").put("name", "Kulisy").put("season", 0).put("episode", 1)))))
                    p.startsWith("/m/meta/movie/tt1") -> ok(JSONObject().put("meta", JSONObject().put("id", "tt1").put("type", "movie").put("name", "Film Testowy").put("description", "Opis filmu")))
                    p.startsWith("/m/subtitles/") -> ok(JSONObject().put("subtitles", JSONArray().put(JSONObject().put("id", "s1").put("url", url("/pl.srt")).put("lang", "pol"))))
                    p.startsWith("/a/stream/") -> ok(streams("a", 8))
                    p.startsWith("/b/stream/") -> ok(streams("b", 3))
                    p == "/pl.srt" -> MockResponse().setBody("1\n00:00:01,000 --> 00:00:03,000\nZażółć <i>gęślą</i>\n\n2\n00:00:05,000 --> 00:00:06,500\nDruga linia\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        StremioApi.apiBase = url("/api").trimEnd('/')
        StremioApi.linkBase = url("/link").trimEnd('/'); StremioActivity.LINK_POLL_MS = 50
        StremioApi.defaultAddons = listOf(url("/m/manifest.json"), url("/a/manifest.json"))
        StremioApi.client = { okhttp3.OkHttpClient() }
    }

    @After fun tearDown() {
        StremioApi.linkBase = "https://link.stremio.com/api/v2"; StremioActivity.LINK_POLL_MS = 3_000
        StremioApi.apiBase = "https://api.strem.io/api"; StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES)
        StremioApi.client = { pl.iptv.tv.data.Repository.http }
        StremioAccount.clear(); server.shutdown(); StubeHistory.useDatabase(null); db.close()
    }

    // ---------------- czyste funkcje ----------------

    @Test fun manifestResourcesAndSupport() {
        val a = StremioAddon.parse(url("/m/manifest.json"), metaManifest)
        assertEquals(listOf("catalog", "meta", "subtitles"), a.resourceNames)
        assertEquals(2, a.searchCatalogs.size)
        assertTrue(a.supports("meta", "series", "tt2:1:1")); assertFalse(a.supports("meta", "series", "kitsu:1")); assertFalse(a.supports("stream", "movie", "tt1"))
        assertEquals(url("/m"), a.base)
        assertEquals("https://x.y/z/manifest.json", StremioAddon.normalizeUrl("stremio://x.y/z/manifest.json"))
        assertEquals("https://x.y/manifest.json", StremioAddon.normalizeUrl("x.y/"))
    }

    @Test fun streamBadgesLimitAndNext() {
        val s = StremioStream("Torrentio", "u", name = "Torrentio\n4k DV HDR10", title = "Film.2160p.mkv\n👤 31 💾 15.2 GB ⚙️ X\n🇵🇱 / 🇬🇧 Lektor", url = "http://x/1")
        assertEquals("4K", s.quality); assertEquals(listOf("DV", "HDR"), s.hdr)
        assertTrue("🇵🇱" in s.languages && "Lektor PL" in s.languages)
        assertEquals("15.2 GB", StremioStreams.formatSize(s.sizeBytes))
        assertTrue(s.badges.startsWith("4K  ·  DV HDR"))
        assertEquals("Film.2160p.mkv", s.headline)
        assertEquals(StremioStream.Kind.TORRENT, StremioStream("x", "u", infoHash = "abc").kind)
        assertEquals(5, StremioStreams.limit((1..9).map { StremioStream("a", "u", url = "http://$it") }).size)
        val prev = s.copy(bingeGroup = "t|4k")
        val cands = listOf(StremioStream("a", "u", url = "http://1", name = "1080p"), StremioStream("a", "u", url = "http://2", bingeGroup = "t|4k"))
        assertEquals("http://2", StremioStreams.pickNext(prev, cands)!!.url)
        assertEquals("http://3", StremioStreams.pickNext(prev.copy(bingeGroup = ""), cands + StremioStream("a", "u", url = "http://3", name = "4k"))!!.url)
        // JSON w intencji odtwarzacza
        assertEquals(prev, StremioStream.fromJson(prev.toJson()))
    }

    @Test fun subtitlesParseDelayAndEncoding() {
        val l = StremioSubs.parse("WEBVTT\n\n00:01.000 --> 00:02.500\n<b>Raz</b>\n\n00:00:04.000 --> 00:00:05.000\nDwa\n")
        assertEquals(2, l.size); assertEquals("Raz", l[0].text); assertEquals(2500, l[0].endMs)
        assertEquals("Raz", StremioSubs.at(l, 1500, 0)); assertNull(StremioSubs.at(l, 3000, 0))
        assertEquals("Raz", StremioSubs.at(l, 3000, 1000))        // napisy 1 s później
        assertEquals("Dwa", StremioSubs.at(l, 3500, -1000))       // 1 s wcześniej
        assertEquals("zażółć", StremioApi.decode("zażółć".toByteArray(charset("windows-1250"))))
        assertEquals("Polski", StremioSubs.langName("pol"))
    }

    // ---------------- konto i wtyczki ----------------

    @Test fun loginAccountAddonsAndLibrarySync() = runBlocking {
        try { StremioApi.login("jan@x.pl", "złe"); fail() } catch (e: Exception) { assertEquals("Nieprawidłowe hasło", e.message) }
        val s = StremioApi.login("jan@x.pl", "dobre")
        assertEquals("AK1", s.authKey); StremioAccount.save(s)
        StremioStore.replaceFromAccount(StremioApi.addonCollection(s.authKey))
        assertEquals(listOf("Meta", "Źródła a", "Źródła b"), StremioStore.addons().map { it.first.name })
        StremioStore.setEnabled(url("/b/manifest.json"), false)
        // ponowne pobranie z konta zachowuje wyłączenie
        StremioStore.replaceFromAccount(StremioApi.addonCollection(s.authKey))
        assertEquals(listOf("Meta", "Źródła a"), StremioStore.enabledAddons().map { it.name })
        // synchronizacja: pozycja z konta → baza; lokalny postęp → konto
        Prefs.stremioSync = 1
        StremioStore.saveProgress(StremioProgress("tt2", "series", "Serial", "", "tt2:1:2", "S1E2", 120_000, 2_400_000, System.currentTimeMillis()))
        assertEquals(1, StremioStore.syncLibrary())
        assertEquals(600_000, StremioStore.progressOf("tt9")!!.positionMs)
        val body = JSONObject(puts.last())
        val item = body.getJSONArray("changes").getJSONObject(0)
        assertEquals("tt2", item.getString("_id")); assertEquals("tt2:1:2", item.getJSONObject("state").getString("video_id"))
        assertEquals(120_000, item.getJSONObject("state").getLong("timeOffset"))
        assertEquals(0, StremioStore.pushPending())               // już wysłane
    }

    // ---------------- ekrany ----------------

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out += v
        if (v is android.view.ViewGroup) (0 until v.childCount).forEach { texts(v.getChildAt(it), out) }
        return out
    }

    @Test fun loginScreenThenSyncDialog() {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get(); idle()
        assertTrue(a.loginShown)
        val fields = texts(a.window.decorView).filterIsInstance<android.widget.EditText>()
        fields.first { it.hint == "E-mail" }.setText("jan@x.pl"); fields.first { it.hint == "Hasło" }.setText("dobre")
        texts(a.window.decorView).first { it.text == "Zaloguj" }.performClick()
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(texts(d.window!!.decorView).any { it.text.contains("zsynchronizować historię odtwarzania") })
        assertEquals("jan@x.pl", StremioAccount.email)
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { !a.loginShown && runBlocking { StremioStore.progressOf("tt9") } != null }
        assertEquals(1, Prefs.stremioSync)
        assertFalse(a.loginShown)
        assertNull("po zalogowaniu bez odpytywania kodu QR", a.linkCode)
    }

    @Test fun qrLoginLogsInRightAfterConfirmOnPhone() {
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { a.linkCode == "QR12" }
        assertTrue(texts(a.window.decorView).any { it.text.contains("link.stremio.com/QR12") && it.text.contains("kod: QR12") })
        // niepotwierdzony kod (serwer zwraca błąd) – dalej ekran logowania
        waitFor { paths.count { it == "/link/read?type=Read&code=QR12" } >= 2 }
        assertTrue(a.loginShown); assertFalse(StremioAccount.loggedIn)
        // potwierdzenie na telefonie – telewizor loguje się sam
        linkConfirmed = true
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        assertEquals("AKQ", StremioAccount.authKey); assertEquals("tok@x.pl", StremioAccount.email)
        assertTrue(paths.contains("/api/addonCollectionGet"))
    }

    @Test fun linkApiParsesCodeAndPendingState() = runBlocking {
        val l = StremioApi.linkCreate()
        assertEquals("QR12", l.code); assertEquals("https://link.stremio.com/QR12", l.link); assertTrue(l.qr.endsWith("/link/qr.png"))
        assertNull(StremioApi.linkRead("QR12"))
        linkConfirmed = true
        assertEquals("AKQ", StremioApi.linkRead("QR12"))
    }

    @Test fun guestSearchShowsMoviesAndSeriesRows() {
        Prefs.stremioGuest = true
        val a = Robolectric.buildActivity(StremioActivity::class.java).setup().get()
        waitFor { a.rowTitles.isNotEmpty() && paths.any { it.contains("/catalog/") } }
        a.search("test")
        waitFor { a.rowTitles.containsAll(listOf("Meta · Popularne (Filmy)", "Meta · Popularne (Seriale)")) }
        assertEquals(listOf("Meta · Popularne (Filmy)", "Meta · Popularne (Seriale)"), a.rowTitles)
        assertTrue(paths.any { it.startsWith("/m/catalog/movie/top/search=test") })
        waitFor { texts(a.window.decorView).any { it.text == "Film Testowy" } }
        assertTrue(texts(a.window.decorView).any { it.text == "Film Testowy" })
    }

    @Test fun detailQueriesOnlyEnabledAddonsFiveEach() = runBlocking {
        StremioAccount.save(StremioApi.Session("AK1", "jan@x.pl"))
        StremioStore.replaceFromAccount(StremioApi.addonCollection("AK1"))
        StremioStore.setEnabled(url("/b/manifest.json"), false)
        paths.clear()
        val a = Robolectric.buildActivity(StremioDetailActivity::class.java, StremioDetailActivity.intent(ctx, StremioMetaPreview("tt2", "series", "Serial Testowy"))).setup().get()
        waitFor { a.groups.isNotEmpty() && a.groups.none { it.loading } }
        assertEquals("tt2:1:1", a.videoId)
        assertEquals(listOf("Źródła a"), a.groups.map { it.addon.name })
        assertEquals(5, a.groups[0].streams.size); assertEquals(8, a.groups[0].total)
        assertTrue(paths.any { it.startsWith("/a/stream/series/tt2%3A1%3A1") || it.startsWith("/a/stream/series/tt2:1:1") })
        assertFalse(paths.any { it.startsWith("/b/stream") })      // wyłączona wtyczka nie jest pytana
        assertEquals(5, a.sourceAdapter.sourceItems.size)
        // odcinek 2 – nowe źródła
        a.selectEpisode(a.meta!!.episodes(1)[1])
        waitFor { a.videoId == "tt2:1:2" && a.groups.none { it.loading } }
        assertTrue(paths.any { it.contains("tt2%3A1%3A2") || it.contains("tt2:1:2") })
        // OK na źródle – odtwarzacz z wybranym źródłem
        a.play(a.sourceAdapter.sourceItems[0])
        val i = shadowOf(a).nextStartedActivity
        assertEquals(StremioPlayerActivity::class.java.name, i.component!!.className)
        assertEquals("tt2:1:2", i.getStringExtra(StremioPlayerActivity.EXTRA_VIDEO))
    }

    @Test fun playerSubtitlesDelayAspectAndNextEpisode() = runBlocking {
        StremioAccount.save(StremioApi.Session("AK1", "jan@x.pl"))
        StremioStore.replaceFromAccount(StremioApi.addonCollection("AK1"))
        val s = StremioApi.streams(StremioStore.enabledAddons().first { it.name == "Źródła a" }, "series", "tt2:1:1")[1]
        val c = Robolectric.buildActivity(StremioPlayerActivity::class.java,
            StremioPlayerActivity.intent(ctx, "series", "tt2", "Serial Testowy", "", "tt2:1:1", "S1E1 – Pilot", s)).setup()
        val p = c.get()
        // napisy z wtyczki w preferowanym języku (polski) – wczytane i ustawione od razu
        waitFor { p.externalSub != null }
        assertEquals("pol", p.externalSub!!.lang)
        assertTrue(p.addonSubs.isNotEmpty())
        p.changeDelay(StremioPlayerActivity.DELAY_STEP); p.changeDelay(StremioPlayerActivity.DELAY_STEP)
        assertEquals(500, p.subDelayMs); assertEquals(500, Prefs.stremioSubDelay)
        p.setAspect(2); assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, p.playerView.resizeMode)
        // następny odcinek: ta sama wtyczka, ta sama grupa (1080p)
        waitFor { paths.any { it.startsWith("/m/meta/series/tt2") } }
        idle(500)
        p.playNext()
        waitFor { p.videoId == "tt2:1:2" }
        assertEquals("tt2:1:2", p.videoId); assertEquals("a|1080p", p.stream.bingeGroup)
        c.pause().stop().destroy(); Unit
    }

    // ---------------- dekodery: tryb programowy i okno awaryjne ----------------

    private fun decoderInitError(mime: String) = androidx.media3.exoplayer.ExoPlaybackException.createForRenderer(
        androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException(
            androidx.media3.common.Format.Builder().setSampleMimeType(mime).build(), RuntimeException("no codec"), false, -49999),
        "MediaCodecAudioRenderer", 1, androidx.media3.common.Format.Builder().setSampleMimeType(mime).build(),
        androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE, false, androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)

    @Test fun decoderStagesDescriptionsAndExternalIntent() {
        // błąd dekodera / AudioTrack – jedna próba w trybie bezpiecznym, potem koniec; błąd sieci – od razu okno
        assertEquals(D.Stage.SAFE, D.nextStage(D.Stage.NORMAL, PE.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(D.Stage.SAFE, D.nextStage(D.Stage.NORMAL, PE.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertNull(D.nextStage(D.Stage.SAFE, PE.ERROR_CODE_DECODER_INIT_FAILED))
        assertNull(D.nextStage(D.Stage.NORMAL, PE.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        // opis z kodekiem, którego dekoder odrzucił
        assertEquals("Telewizor nie potrafi zdekodować formatu DTS-HD.", D.describe(decoderInitError(androidx.media3.common.MimeTypes.AUDIO_DTS_HD)))
        assertEquals("Dolby TrueHD", D.codecName(androidx.media3.common.MimeTypes.AUDIO_TRUEHD))
        assertTrue(D.describe(PE("x", null, PE.ERROR_CODE_IO_BAD_HTTP_STATUS)).startsWith("Nie udało się pobrać"))
        // selektor: nie wybiera na siłę ścieżek, których urządzenie nie obsługuje
        assertFalse(D.trackSelector(ctx).parameters.exceedRendererCapabilitiesIfNecessary)
        // dekoder FFmpeg dołączony: w trybie normalnym po dekoderach systemu (zapas), w bezpiecznym – przed nimi
        assertTrue(D.ffmpegAvailable)
        for (st in D.Stage.values()) {
            val r = D.renderersFactory(ctx, st).createRenderers(android.os.Handler(Looper.getMainLooper()),
                object : androidx.media3.exoplayer.video.VideoRendererEventListener {}, object : androidx.media3.exoplayer.audio.AudioRendererEventListener {}, { }, { })
            assertTrue(r.any { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer })
            val codec = r.indexOfFirst { it is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer }
            val ffmpeg = r.indexOfFirst { it is androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer }
            assertTrue("$st: $codec / $ffmpeg", codec >= 0 && ffmpeg >= 0)
            assertEquals(st.name, st == D.Stage.SAFE, ffmpeg < codec)
        }
        // zewnętrzny odtwarzacz: wideo, tytuł, pozycja (MX / Just Player), nagłówki
        val i = D.externalView("http://x/film.mkv", "Film", 61_000, mapOf("Referer" to "http://r"))
        assertEquals(android.content.Intent.ACTION_VIEW, i.action); assertEquals("video/*", i.type)
        assertEquals("http://x/film.mkv", i.dataString); assertEquals("Film", i.getStringExtra("title"))
        assertEquals(61_000, i.getIntExtra("position", 0))
        assertArrayEquals(arrayOf("Referer", "http://r"), i.getStringArrayExtra("headers"))
    }

    @Test fun decoderErrorSwitchesToSoftwareThenOffersOtherSourceOrExternalPlayer() {
        val s = StremioStream("Źródła a", url("/a/manifest.json"), "a", "Film 4K DTS", url = url("/film.mkv"))
        val c = Robolectric.buildActivity(StremioPlayerActivity::class.java,
            StremioPlayerActivity.intent(ctx, "movie", "tt1", "Film Testowy", "", "tt1", "", s)).setup()
        val p = c.get(); idle()
        val first = p.player
        assertEquals(D.Stage.NORMAL, p.decoderStage)
        // 1. błąd inicjalizacji dekodera – nowy odtwarzacz w trybie programowym, od tej samej pozycji, bez okna
        p.onError(decoderInitError(androidx.media3.common.MimeTypes.AUDIO_TRUEHD), 90_000); idle()
        assertEquals(D.Stage.SAFE, p.decoderStage)
        assertNotSame(first, p.player); assertNull(p.failure)
        assertEquals(90_000, p.player!!.currentPosition)
        // 2. dalej błąd – odtwarzanie przerwane, okno z wyborem
        p.onError(decoderInitError(androidx.media3.common.MimeTypes.AUDIO_TRUEHD), 91_000); idle()
        assertEquals("Telewizor nie potrafi zdekodować formatu Dolby TrueHD.", p.failure)
        assertFalse(p.player!!.playWhenReady && p.player!!.playbackState != androidx.media3.common.Player.STATE_IDLE)
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        assertEquals("Wybierz inne źródło", d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text)
        assertEquals("Odtwórz w zewnętrznym odtwarzaczu", d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).text)
        // bez zewnętrznego odtwarzacza – komunikat, okno wraca
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(p.isFinishing)
        assertTrue((ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).isShowing)
        // z zainstalowanym odtwarzaczem – okno wyboru aplikacji z tym strumieniem
        shadowOf(ctx.packageManager).addResolveInfoForIntent(D.externalView(s.url, "Film Testowy", 0, emptyMap()),
            android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = "org.videolan.vlc"; name = "Player" } })
        p.openExternal(91_000); idle()
        val chooser = shadowOf(p).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        val view = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(s.url, view.dataString); assertEquals(91_000, view.getIntExtra("position", 0))
        assertTrue(p.isFinishing)
        c.pause().stop().destroy(); Unit
    }

    @Test fun otherSourceButtonReturnsToSourceList() {
        val s = StremioStream("Źródła a", url("/a/manifest.json"), "a", "Film", url = url("/film.mkv"))
        val p = Robolectric.buildActivity(StremioPlayerActivity::class.java,
            StremioPlayerActivity.intent(ctx, "movie", "tt1", "Film Testowy", "", "tt1", "", s)).setup().get(); idle()
        // błąd sieci – bez prób z dekoderem, od razu okno; „Wybierz inne źródło” zamyka odtwarzacz (pod spodem lista źródeł)
        p.onError(androidx.media3.common.PlaybackException("x", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED), 0); idle()
        assertEquals(D.Stage.NORMAL, p.decoderStage)
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(p.isFinishing)
    }
}

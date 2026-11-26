package pl.iptv.tv.ui

import android.os.Looper
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.M3uParser
import pl.iptv.tv.data.M3uVod
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.VodLibrary
import java.io.File
import java.time.Duration

/**
 * 7.23 – filmy i seriale z list M3U: rozpoznawanie (tvg-type, adresy Xtream, pliki, grupy, wzorce odcinków), grupowanie odcinków
 * po tytule / sezonie / odcinku, normalizacja gatunków („VOD”, „Filmy”, „PL”… usuwane, „Pozostałe”, „Wszystkie”), budowa
 * katalogu poza głównym wątkiem, moduł VOD (Stremio) bez logowania, pusta lista i lista bez gatunków, obrót ekranu.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class V723M3uVodTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(cond: () -> Boolean) { repeat(80) { idle(100); Thread.sleep(10); if (cond()) return } }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private lateinit var db: StubeDatabase

    private val M3U = """
        #EXTM3U
        #EXTINF:-1 tvg-id="tvp1" group-title="Ogólne",TVP 1 HD
        http://srv/live/1.ts
        #EXTINF:-1 tvg-type="movie" tvg-genre="Akcja, Komedia" tvg-logo="http://p/1.jpg" group-title="VOD | Filmy PL",Szybki film (2021) [1080p]
        http://srv/movie/u/p/101.mp4
        #EXTINF:-1 group-title="Filmy - DRAMAT",Długi.Dramat.2019.PL.1080p
        http://srv/vod/dramat.mkv
        #EXTINF:-1 group-title="VOD PL",Bez gatunku
        http://srv/movie/u/p/103.mkv
        #EXTINF:-1 group-title="Seriale | Kryminalne",Detektyw S01E02 Trop
        http://srv/series/u/p/202.mkv
        #EXTINF:-1 group-title="Seriale | Kryminalne",Detektyw S01E01 Początek
        http://srv/series/u/p/201.mkv
        #EXTINF:-1 group-title="Seriale | Kryminalne",Detektyw S02E01
        http://srv/series/u/p/203.mkv
        #EXTINF:-1 tvg-type="series" group-title="SERIES",Inny serial 1x03
        http://srv/x/301
    """.trimIndent()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db); StremioAccount.useStorage(null); StremioAccount.clear()
        Prefs.permissionsAsked = true; Prefs.setupDone = true; Prefs.stremioGuest = false; Prefs.playlistsJson = "[]"; Prefs.tmdbApiKey = ""
        StremioApi.defaultAddons = emptyList()
        VodLibrary.resetForTest()
    }

    @After fun tearDown() {
        StremioApi.defaultAddons = listOf(StremioApi.CINEMETA, StremioApi.OPENSUBTITLES)
        VodLibrary.resetForTest(); StubeHistory.useDatabase(null); db.close(); Prefs.orientation = 0
    }

    private fun load(text: String) {
        val f = File(ctx.filesDir, "vod723.m3u").apply { writeText(text) }
        Repository.resetAllEdits(); runBlocking { Repository.loadPlaylist(f.absolutePath) }
    }

    @Test fun classifiesEpisodesAndGenres() {
        val pl = M3uParser.parse(M3U)
        val kinds = pl.channels.map { M3uVod.classify(it) }
        assertEquals(listOf(M3uVod.Kind.LIVE, M3uVod.Kind.MOVIE, M3uVod.Kind.MOVIE, M3uVod.Kind.MOVIE, M3uVod.Kind.SERIES, M3uVod.Kind.SERIES, M3uVod.Kind.SERIES, M3uVod.Kind.SERIES), kinds)
        assertEquals("movie", pl.channels[1].tvgType); assertEquals("Akcja, Komedia", pl.channels[1].genre)
        // wzorce odcinków
        assertEquals(M3uVod.EpisodeInfo("Detektyw", 1, 2, "Trop"), M3uVod.episodeOf("Detektyw S01E02 Trop"))
        assertEquals(2 to 5, M3uVod.episodeOf("Serial 2x05")!!.let { it.season to it.episode })
        assertEquals(3 to 7, M3uVod.episodeOf("Ranczo Sezon 3 Odcinek 7")!!.let { it.season to it.episode })
        assertEquals(1 to 4, M3uVod.episodeOf("Klan odc. 4")!!.let { it.season to it.episode })
        assertNull(M3uVod.episodeOf("Zwykły film 2020"))
        // gatunki: bez „VOD”, „Filmy”, „Seriale”, „PL”; jednolita wielkość liter; synonimy; brak – „Pozostałe”
        assertEquals(listOf("Dramat"), M3uVod.normalizeGenres("Filmy - DRAMAT"))
        assertEquals(listOf("Akcja", "Komedia"), M3uVod.normalizeGenres("Akcja, Komedia"))
        assertEquals(listOf("Kryminał"), M3uVod.normalizeGenres("Seriale | Kryminalne"))
        assertEquals(listOf(M3uVod.OTHER), M3uVod.normalizeGenres("VOD PL"))
        assertEquals(listOf(M3uVod.OTHER), M3uVod.normalizeGenres(""))
        assertEquals("Długi Dramat", M3uVod.cleanTitle("Długi.Dramat.2019.PL.1080p"))
        assertEquals("Szybki film", M3uVod.cleanTitle("Szybki film (2021) [1080p]"))
    }

    @Test fun buildsCatalogWithGroupedSeries() {
        val cat = M3uVod.build(M3uParser.parse(M3U).channels)
        assertEquals(3, cat.movies.size)
        assertEquals(listOf("Szybki film", "Długi Dramat", "Bez gatunku"), cat.movies.map { it.title })
        assertEquals("2021", cat.movies[0].year); assertEquals(listOf("Akcja", "Komedia"), cat.movies[0].genres)
        val det = cat.series.first { it.title == "Detektyw" }
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), det.episodes.map { it.season to it.episode })   // sezon, odcinek – rosnąco
        assertEquals(listOf(1, 2), det.seasons); assertEquals(listOf("Kryminał"), det.genres)
        assertEquals(2, cat.series.size)
        // filtr: „Wszystkie” pierwsze, „Pozostałe” ostatnie
        assertEquals(listOf(M3uVod.ALL, "Akcja", "Dramat", "Komedia", "Kryminał", M3uVod.OTHER), cat.genres())
        assertEquals(listOf(M3uVod.ALL, "Akcja", "Dramat", "Komedia", M3uVod.OTHER), cat.genres("movie"))
        assertEquals(listOf("Szybki film"), cat.movies("Komedia").map { it.title })
        assertEquals(3, cat.movies(M3uVod.ALL).size)
        // adres odcinka i filmu z katalogu (wznawianie bez wtyczek)
        assertEquals("http://srv/series/u/p/201.mkv", cat.channelFor(det.episodes[0].id)!!.url)
        assertSame(det, cat.showOf(det.episodes[2].id))
        // lista bez VOD i pusta lista
        assertTrue(M3uVod.build(listOf(Channel("k", "TVN", "http://x/1.ts", "Ogólne"))).isEmpty)
        assertTrue(M3uVod.build(emptyList()).isEmpty)
        assertEquals(listOf(M3uVod.ALL), M3uVod.build(emptyList()).genres())
    }

    @Test fun listWithoutGenresFallsBackToOther() {
        val cat = M3uVod.build(M3uParser.parse("#EXTM3U\n#EXTINF:-1,Film A\nhttp://s/a.mp4\n#EXTINF:-1,Film B\nhttp://s/b.mkv\n").channels)
        assertEquals(2, cat.movies.size)
        assertTrue(cat.movies.all { it.genres == listOf(M3uVod.OTHER) })                // domyślna grupa parsera „Inne” – „Pozostałe”
        assertEquals(listOf(M3uVod.ALL, M3uVod.OTHER), cat.genres())
        assertEquals(M3uVod.ALL, cat.genres().first())
    }

    @Test fun libraryBuildsOffMainThreadAfterPlaylistLoad() {
        load(M3U)
        val cat = runBlocking { VodLibrary.current() }
        assertEquals(3, cat.movies.size); assertEquals(Repository.version, cat.version)
        assertTrue(VodLibrary.hasContent)
        // pusta lista VOD (same kanały) – katalog pusty, bez błędów
        load("#EXTM3U\n#EXTINF:-1,TVN\nhttp://s/live.ts\n")
        assertTrue(runBlocking { VodLibrary.current() }.isEmpty)
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun vodBoardWithoutStremioLoginInPortraitAndAfterRotation() = boardCheck(portrait = true)

    @Test @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun vodBoardWithoutStremioLoginOnTv() = boardCheck(portrait = false)

    private fun boardCheck(portrait: Boolean) {
        if (portrait) Prefs.orientation = 2
        load(M3U); runBlocking { VodLibrary.current() }
        assertFalse(StremioAccount.loggedIn); assertFalse(Prefs.stremioGuest)
        val c = Robolectric.buildActivity(StremioActivity::class.java).setup()
        val a = c.get()
        waitFor { a.rowTitles.contains(StremioActivity.M3U_MOVIES_ROW) }
        // bez ekranu logowania – tablica z treściami z listy i filtrem gatunków
        assertFalse(a.loginShown)
        assertTrue(a.rowTitles.containsAll(listOf(StremioActivity.M3U_MOVIES_ROW, StremioActivity.M3U_SERIES_ROW)))
        assertEquals(listOf(M3uVod.ALL, "Akcja", "Dramat", "Komedia", "Kryminał", M3uVod.OTHER), a.m3uGenreViews.map { it.getTag(pl.iptv.tv.R.id.row_key).toString().removePrefix(StremioActivity.M3U_GENRE_TAG) })
        a.setM3uGenre("Dramat"); idle(300)
        assertEquals("Dramat", a.m3uGenre)
        // Filmy – tylko filmy z listy
        a.setFilter(StremioActivity.FILTER_MOVIES); waitFor { a.rowTitles.contains(StremioActivity.M3U_MOVIES_ROW) }
        assertFalse(a.rowTitles.contains(StremioActivity.M3U_SERIES_ROW))
        // obrót / odtworzenie ekranu – tablica z listy nadal bez logowania
        c.recreate()
        waitFor { c.get().rowTitles.contains(StremioActivity.M3U_MOVIES_ROW) }
        assertFalse(c.get().loginShown)
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h800dp-port-xhdpi")
    fun withoutVodStillAsksForLogin() {
        load("#EXTM3U\n#EXTINF:-1,TVN\nhttp://s/live.ts\n"); runBlocking { VodLibrary.current() }
        val c = Robolectric.buildActivity(StremioActivity::class.java).setup(); idle(500)
        assertTrue(c.get().loginShown)
        c.pause().stop().destroy()
    }
}

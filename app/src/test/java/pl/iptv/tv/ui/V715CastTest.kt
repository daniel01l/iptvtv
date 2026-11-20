package pl.iptv.tv.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.*
import pl.iptv.tv.remote.CastReceiver
import pl.iptv.tv.remote.RemoteServer

/** (7.15) „Wyślij na telewizor”: odbiór linków z telefonu (/api/cast), kolejka, Room, tło aplikacji. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V715CastTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var db: StubeDatabase
    private val started = ArrayList<YtVideo>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
        Prefs.remotePin = "4321"; Prefs.castReceiver = true; Prefs.stubeInternal = true
        RemoteServer.init(ctx)
        started.clear()
        CastReceiver.foreground = { true }; CastReceiver.ui = kotlinx.coroutines.Dispatchers.Unconfined
        CastReceiver.launcher = { _: Context, v: YtVideo -> started.add(v) }
        YtKeyless.clearCache()
        YtKeyless.source = object : YtKeyless.Source {
            override fun kiosk(id: String) = emptyList<YtVideo>()
            override fun search(q: String) = emptyList<YtVideo>()
            override fun playlist(id: String) = "Z telefonu" to (1..4).map { YtVideo("c$it", "Wykonawca $it – Utwór $it", "Wykonawca $it", duration = "PT3M") }
        }
    }

    @After fun tearDown() {
        CastReceiver.foreground = { pl.iptv.tv.IptvApp.isForeground }; CastReceiver.ui = kotlinx.coroutines.Dispatchers.Main
        StubeImport.reset(); StubeImport.takePending(); YtKeyless.source = YtKeyless.NewPipeSource; YtKeyless.clearCache()
        Prefs.castReceiver = false; StubeHistory.useDatabase(null); db.close()
    }

    @Test fun acceptsPlaylistsAndSingleVideosOnly() {
        assertTrue(CastReceiver.accepts("https://www.youtube.com/playlist?list=PLabcdefghij123"))
        assertTrue(CastReceiver.accepts("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(CastReceiver.accepts("https://youtu.be/abcdefghijk"))
        assertEquals("abcdefghijk", CastReceiver.videoId("https://www.youtube.com/watch?v=abcdefghijk&t=5"))
        assertNull(CastReceiver.videoId("https://www.youtube.com/watch?v=abcdefghijk&list=PLabcdefghij123"))   // lista ma pierwszeństwo
        assertFalse(CastReceiver.accepts("https://example.com/cos"))
    }

    @Test fun requestNeedsPinAndEnabledReceiver() {
        val link = "https://www.youtube.com/playlist?list=PLabcdefghij123"
        assertFalse(RemoteServer.receiveCast("0000", link).first)
        Prefs.castReceiver = false
        assertFalse("odbiór wyłączony", RemoteServer.receiveCast("4321", link).first)
        Prefs.castReceiver = true
        assertFalse(RemoteServer.receiveCast("4321", "https://example.com/cos").first)
        assertTrue(RemoteServer.receiveCast("4321", link).first)
    }

    @Test fun playlistBecomesActiveQueueAndIsSavedInRoom() = runBlocking {
        CastReceiver.process(ctx, "https://www.youtube.com/playlist?list=PLabcdefghij123")
        assertEquals(listOf("c1"), started.map { it.id })                       // pierwszy utwór gra od razu
        assertEquals(listOf("c2", "c3", "c4"), StubeQueue.items().orEmpty().map { it.ref })   // reszta = aktywna kolejka
        assertEquals(1, StubeImport.all().size)                                 // zapisane na stałe
    }

    @Test fun singleVideoPlaysImmediately() = runBlocking {
        CastReceiver.process(ctx, "https://youtu.be/abcdefghijk")
        assertEquals(listOf("abcdefghijk"), started.map { it.id })
    }

    @Test fun inBackgroundLinkWaitsInStubeInsteadOfStartingScreen() = runBlocking {
        CastReceiver.foreground = { false }
        CastReceiver.process(ctx, "https://www.youtube.com/playlist?list=PLabcdefghij123")
        assertTrue(started.isEmpty())
        assertEquals("https://www.youtube.com/playlist?list=PLabcdefghij123", StubeImport.pending)
    }
}

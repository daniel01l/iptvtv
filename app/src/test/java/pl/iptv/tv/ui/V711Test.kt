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
import pl.iptv.tv.data.StremioMeta
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioVideo
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory

/** 7.11: obejrzane odcinki (Room), procent sezonu, tytuł bazowy serii filmowej, krok paska postępu. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V711Test {
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StubeDatabase::class.java).allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
    }
    @After fun tearDown() { StubeHistory.useDatabase(null); db.close() }

    private val meta = StremioMeta("tt1", "series", "Serial", videos = (1..5).map { StremioVideo("tt1:1:$it", "Odc $it", 1, it) } + (1..2).map { StremioVideo("tt1:2:$it", "S2 $it", 2, it) })

    @Test fun watchedEpisodesArePersistedAndGiveSeasonPercent() = runBlocking {
        val e = meta.videos
        StremioStore.setWatched("tt1", e[0], "series", "Serial", "", true)
        StremioStore.setWatched("tt1", e[1], "series", "Serial", "", true)
        StremioStore.setWatched("tt1", e[2], "series", "Serial", "", true)
        var w = StremioStore.watchedIds("tt1")
        assertEquals(setOf("tt1:1:1", "tt1:1:2", "tt1:1:3"), w)
        assertEquals(60, StremioStore.seasonPercent(meta, 1, w))      // 3 z 5 odcinków
        assertEquals(0, StremioStore.seasonPercent(meta, 2, w))
        // odznaczenie
        StremioStore.setWatched("tt1", e[2], "series", "Serial", "", false)
        w = StremioStore.watchedIds("tt1")
        assertEquals(40, StremioStore.seasonPercent(meta, 1, w))
        assertEquals(1, StremioStore.allWatched().map { it.metaId }.distinct().size)
        assertEquals(180_000L, StremioStore.WATCHED_AFTER_MS)
    }

    @Test fun movieSeriesBaseTitle() {
        assertEquals("Matrix", StremioMetas.baseTitle("Matrix: Reaktywacja"))
        assertEquals("Shrek", StremioMetas.baseTitle("Shrek 2"))
        assertEquals("Szybcy i wściekli", StremioMetas.baseTitle("Szybcy i wściekli - Hobbs & Shaw"))
        assertEquals("", StremioMetas.baseTitle("It"))
    }
}

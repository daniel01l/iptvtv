package pl.iptv.tv.ui

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
import pl.iptv.tv.data.StremioProgress
import pl.iptv.tv.data.StremioResume
import pl.iptv.tv.data.StremioStore
import pl.iptv.tv.data.StremioStream
import pl.iptv.tv.data.StremioStreams
import pl.iptv.tv.data.StremioVideo
import pl.iptv.tv.data.StubeDatabase
import pl.iptv.tv.data.StubeHistory

/** 7.8: wznawianie z flagą „Następny odcinek” (Room), wybór źródła przy wznowieniu, angielski jako dalszy wybór języka. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V78bTest {
    private lateinit var db: StubeDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StubeDatabase::class.java).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }
        StubeHistory.useDatabase(db)
    }

    @After fun tearDown() { StubeHistory.useDatabase(null); db.close() }

    private val meta = StremioMeta("tt2", "series", "Serial", videos = listOf(
        StremioVideo("tt2:1:1", "Pilot", 1, 1), StremioVideo("tt2:1:2", "Drugi", 1, 2), StremioVideo("tt2:1:3", "Trzeci", 1, 3)))

    private fun prog(video: String, shown: Boolean, pos: Long = 600_000, dur: Long = 2_400_000, type: String = "series") =
        StremioProgress("tt2", type, "Serial", "", video, StremioStore.videoLabel(video), pos, dur, 1L, nextShown = shown)

    @Test fun resumeOpensNextEpisodeOnlyAfterNextWindowWasShown() {
        val before = StremioResume.target(prog("tt2:1:2", shown = false), meta)
        assertEquals("tt2:1:2", before.videoId); assertFalse(before.next)
        val after = StremioResume.target(prog("tt2:1:2", shown = true), meta)
        assertEquals("tt2:1:3", after.videoId); assertTrue(after.next)
        // ostatni odcinek – nie ma następnego, zostaje bieżący
        val last = StremioResume.target(prog("tt2:1:3", shown = true), meta)
        assertEquals("tt2:1:3", last.videoId); assertFalse(last.next)
        // film – zawsze ten sam
        assertFalse(StremioResume.target(prog("tt1", shown = true, type = "movie"), null).next)
    }

    @Test fun nextShownFlagIsPersistedPerEpisode() = runBlocking {
        StremioStore.saveProgress(prog("tt2:1:2", shown = true))
        assertTrue(StremioStore.progressOf("tt2")!!.nextShown)
        // kolejny zapis tego samego odcinka bez flagi (np. po przewinięciu wstecz) nie kasuje jej
        StremioStore.saveProgress(prog("tt2:1:2", shown = false, pos = 300_000))
        assertTrue(StremioStore.progressOf("tt2")!!.nextShown)
        // inny odcinek – flaga liczona od nowa
        StremioStore.saveProgress(prog("tt2:1:3", shown = false))
        assertFalse(StremioStore.progressOf("tt2")!!.nextShown)
    }

    @Test fun resumePicksSameSourceThenPolishThenBestQuality() {
        fun s(addon: String, name: String, group: String = "", title: String = "") = StremioStream(addon, addon, name = name, title = title, url = "http://x/$addon/$name/$group", bingeGroup = group)
        val list = listOf(s("a", "4k", "a|4k"), s("a", "1080p", "a|1080p"), s("b", "1080p lektor PL", "b|1080p"), s("b", "720p"))
        // ta sama wtyczka i grupa
        assertEquals("1080p", StremioStreams.pickResume("a", "a|1080p", "1080p", list)!!.name)
        // grupa zniknęła – ta sama wtyczka i jakość
        assertEquals("1080p", StremioStreams.pickResume("a", "a|zniknela", "1080p", list)!!.name)
        // wtyczka bez źródeł – najlepsze ze wszystkich: polska wersja przed wyższą rozdzielczością
        assertEquals("1080p lektor PL", StremioStreams.pickResume("c", "", "", list)!!.name)
        assertNull(StremioStreams.pickResume("a", "", "", emptyList()))
    }

    @Test fun polishSourcesAreOnTopOfEveryResolutionGroup() {
        val l = listOf(StremioStream("a", "a", name = "1080p", url = "http://x/1"), StremioStream("a", "a", name = "1080p lektor PL", url = "http://x/2"),
            StremioStream("a", "a", name = "1080p napisy PL", url = "http://x/3"))
        val sorted = l.map { it to StremioStreams.polishRank(it) }.sortedBy { it.second }.map { it.first.name }
        assertEquals(listOf("1080p lektor PL", "1080p napisy PL", "1080p"), sorted)
    }

    @Test fun englishIsTheNextChoiceWhenThereIsNoPolishAudioOrSubtitles() {
        val en = "eng" to "English"; val de = "ger" to "Deutsch"; val pl = "pol" to "Polski"
        // polski dźwięk – bez zmian
        assertEquals(1, D.languagePlan(listOf(en, pl), listOf(en)).audio)
        // polskie napisy wbudowane – przed angielskim dźwiękiem
        val plSubs = D.languagePlan(listOf(en), listOf(en, pl))
        assertEquals(1, plSubs.embeddedText)
        // brak polskiego: angielski dźwięk, potem angielskie napisy
        val p = D.languagePlan(listOf(de, en), listOf(en))
        assertNull(p.embeddedText); assertTrue(p.addonSubs); assertEquals(1, p.englishAudio); assertEquals(0, p.englishText)
        val onlySubs = D.languagePlan(listOf(de), listOf(en))
        assertNull(onlySubs.englishAudio); assertEquals(0, onlySubs.englishText)
        assertTrue(D.isEnglishSubtitle("eng")); assertTrue(D.isEnglishSubtitle("en-US")); assertFalse(D.isEnglishSubtitle("pol"))
    }

    private val D = StremioDecoders
}

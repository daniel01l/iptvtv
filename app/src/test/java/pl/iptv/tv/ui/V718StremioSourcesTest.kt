package pl.iptv.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import pl.iptv.tv.data.DebridResolve
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.StremioAddonScores
import pl.iptv.tv.data.StremioStream

/** (7.18) Stremio: ulubione wtyczki na górze rozdzielczości (≥ 10 % obejrzane), czekanie na pobranie w debrid. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V718StremioSourcesTest {
    @After fun reset() { Prefs.stremioAddonScores = "" }

    private fun s(addon: String, name: String = "1080p", title: String = "") = StremioStream(addon, "https://$addon.example/manifest.json", name, title, url = "https://$addon.example/v/$name")

    @Test fun watchedAddonsGoToTopOfResolution() {
        val list = listOf(s("a", title = "Lektor PL"), s("b"), s("c"))
        // bez punktów – jak dotąd (polska wersja pierwsza)
        assertEquals(listOf("a", "b", "c"), StremioAddonScores.order(list).map { it.addonName })
        // samo uruchomienie nie daje punktu – dopiero 10 % obejrzane
        assertFalse(StremioAddonScores.watched(5 * 60_000, 60 * 60_000))
        assertTrue(StremioAddonScores.watched(6 * 60_000, 60 * 60_000))
        StremioAddonScores.credit("https://c.example/manifest.json"); StremioAddonScores.credit("https://c.example/manifest.json?x=1")
        StremioAddonScores.credit("https://b.example/manifest.json")
        // polska wersja zostaje na górze, dalej wtyczki z obejrzanymi źródłami
        assertEquals(listOf("a", "c", "b"), StremioAddonScores.order(list).map { it.addonName })
        assertEquals(1, StremioAddonScores.score("https://b.example/manifest.json"))
    }

    @Test fun defaultAddonOrderPolishFirstThenWatched() {
        Prefs.stremioAddonScores = ""
        fun st(addon: String, title: String = "") = StremioStream(addon, "https://x/${addon.hashCode()}/manifest.json", "1080p", title, url = "https://x/v/${addon.hashCode()}")
        val l = listOf(st("Cinemeta"), st("Orion"), st("Własna wtyczka"), st("Torrentio"), st("Torrentio RD"), st("AIOStreams | ElfHosted"),
            st("MediaFusion | ElfHosted RD"), st("Comet | ElfHosted | RD"), st("Torrentio PM"), st("MediaFusion | ElfHosted"), st("Torrentio Lite"))
        assertEquals(listOf("AIOStreams | ElfHosted", "Torrentio PM", "Torrentio RD", "Torrentio Lite", "Torrentio", "MediaFusion | ElfHosted RD",
            "Comet | ElfHosted | RD", "Orion", "Cinemeta", "MediaFusion | ElfHosted", "Własna wtyczka"), StremioAddonScores.order(l).map { it.addonName })
        // polskie źródło ze słabszej wtyczki – nadal na górze; obejrzane 10 % z Orion – Orion przed resztą
        val pl = st("Cinemeta", "Lektor PL")
        StremioAddonScores.credit("https://x/${"Orion".hashCode()}/manifest.json")
        val o = StremioAddonScores.order(l.drop(1) + pl).map { it.addonName }
        assertEquals(listOf("Cinemeta", "Orion", "AIOStreams | ElfHosted"), o.take(3))
    }

    @Test fun torrentWithoutUrlIsP2P() {
        val p2p = StremioStream("t", "https://t.example/manifest.json", "1080p", infoHash = "abc")
        assertEquals(StremioStream.Kind.TORRENT, p2p.kind)
        assertEquals(StremioStream.Kind.HTTP, s("x").kind)
    }

    @Test fun debridDownloadingIsDetectedAfterRedirect() {
        assertTrue(DebridResolve.needsCheck("https://torrentio.strem.fun/resolve/realdebrid/KEY/hash/null/0/file.mkv"))
        assertFalse(DebridResolve.needsCheck("https://cdn.example/movie.mp4"))
        assertEquals(DebridResolve.State.DOWNLOADING, DebridResolve.classify("https://torrentio.strem.fun/videos/downloading_v2.mp4"))
        assertEquals(DebridResolve.State.FAILED, DebridResolve.classify("https://torrentio.strem.fun/videos/failed_download_v2.mp4"))
        assertEquals(DebridResolve.State.READY, DebridResolve.classify("https://real-debrid.com/d/ABC/movie.mkv"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/videos/downloading_v2.mp4"))
        server.enqueue(MockResponse().setBody("x"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/d/file.mkv"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        server.start()
        try {
            val u = server.url("/resolve/realdebrid/k/h/0").toString()
            assertEquals(DebridResolve.State.DOWNLOADING, DebridResolve.check(u))
            assertEquals("gotowe po pobraniu", DebridResolve.State.READY, DebridResolve.check(u))
        } finally { server.shutdown() }
    }
}

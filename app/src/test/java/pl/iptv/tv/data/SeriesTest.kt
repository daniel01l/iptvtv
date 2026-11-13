package pl.iptv.tv.data

import org.junit.Assert.*
import org.junit.Test

class SeriesTest {
    @Test fun baseTitles() {
        val same = listOf("M jak miłość (1856)", "M jak miłość odc. 1857", "M jak miłość - odc. 12", "M jak miłość: odcinek 3", "M JAK MIŁOŚĆ")
        assertEquals(1, same.map { Series.key(it) }.distinct().size)
        assertEquals(Series.key("Friends"), Series.key("Friends S03E12"))
        assertEquals(Series.key("Dr House"), Series.key("Dr House, sezon 2"))
        assertEquals(Series.key("Teleexpress"), Series.key("Teleexpress (powtórka)"))
        assertNotEquals(Series.key("Fakty"), Series.key("Fakty po faktach"))
        assertEquals("M jak miłość", Series.baseTitle("M jak miłość (1856)"))
    }

    @Test fun episodeNumbers() {
        assertEquals("S01E12", XmltvParser.episodeText("xmltv_ns", "0.11.0/1"))
        assertEquals("E05", XmltvParser.episodeText("xmltv_ns", " . 4 . "))
        assertEquals("S2 Odc.3", XmltvParser.episodeText("onscreen", "S2 Odc.3"))
        assertNull(XmltvParser.episodeText("dd_progid", "EP123"))
    }

    @Test fun differentEpisode() {
        val a = Programme(0, 1, "X", episode = "S01E01"); val b = Programme(2, 3, "X", episode = "S01E01")
        assertFalse(Series.isDifferentEpisode(a, b))                // powtórka
        assertTrue(Series.isDifferentEpisode(a, b.copy(episode = "S01E02")))
        assertFalse(Series.isDifferentEpisode(Programme(0, 1, "X", subTitle = "Pilot"), Programme(2, 3, "X", subTitle = "pilot")))
    }
}

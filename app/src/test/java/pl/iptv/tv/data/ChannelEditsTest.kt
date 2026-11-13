package pl.iptv.tv.data

import org.junit.Assert.*
import org.junit.Test

class ChannelEditsTest {
    private val raw = M3uParser.parse("""
#EXTM3U
#EXTINF:-1 group-title="A",Jeden
http://s/1
#EXTINF:-1 group-title="A",Dwa
http://s/2
#EXTINF:-1 group-title="B",Trzy
http://s/3
#EXTINF:-1 group-title="B",Trzy kopia
http://s/3
""".trimIndent()).channels

    @Test fun duplicateUrlsGetUniqueKeys() {
        assertEquals(listOf("http://s/1", "http://s/2", "http://s/3", "http://s/3#1"), raw.map { it.key })
    }

    @Test fun appliesOverridesAndNumbering() {
        val s = EditStore()
        s.items["http://s/1"] = ChannelOverride(name = "Pierwszy", group = "Nowa")
        s.items["http://s/2"] = ChannelOverride(hidden = true)
        s.items["http://s/3"] = ChannelOverride(epg = "x.id")
        val r = ChannelEdits.apply(raw, s)
        assertEquals("Pierwszy", r[0].name); assertEquals("Jeden", r[0].origName); assertEquals("Nowa", r[0].group)
        assertTrue(r[1].hidden); assertEquals(0, r[1].number)
        assertEquals(listOf(1, 0, 2, 3), r.map { it.number })
        assertEquals("x.id", r[2].epgOverride)
    }

    @Test fun moveAndOrderWithNewChannels() {
        val keys = raw.map { it.key }
        val down = ChannelEdits.move(keys, "http://s/1", "http://s/3")
        assertEquals(listOf("http://s/2", "http://s/3", "http://s/1", "http://s/3#1"), down)
        val up = ChannelEdits.move(keys, "http://s/3#1", "http://s/2")
        assertEquals(listOf("http://s/1", "http://s/3#1", "http://s/2", "http://s/3"), up)
        // zapisana kolejność bez nowego kanału -> nowy trafia na koniec
        val s = EditStore(order = mutableListOf("http://s/3", "http://s/1"))
        assertEquals(listOf("http://s/3", "http://s/1", "http://s/2", "http://s/3#1"), ChannelEdits.apply(raw, s).map { it.key })
    }

    @Test fun jsonRoundTrip() {
        val s = EditStore(mutableListOf("b", "a"), mutableMapOf(
            "a" to ChannelOverride(name = "N", epg = ""), "b" to ChannelOverride(hidden = true, group = "G")))
        val r = EditStore.fromJson(s.toJson())
        assertEquals(listOf("b", "a"), r.order)
        assertEquals("N", r.items["a"]!!.name); assertEquals("", r.items["a"]!!.epg)
        assertTrue(r.items["b"]!!.hidden); assertEquals("G", r.items["b"]!!.group); assertNull(r.items["b"]!!.epg)
    }

    @Test fun epgOverrideInParser() {
        val chs = ChannelEdits.apply(raw, EditStore(items = mutableMapOf(
            "http://s/1" to ChannelOverride(epg = "Other.id"), "http://s/2" to ChannelOverride(epg = ""))))
        val xml = """<tv><channel id="Other.id"><display-name>Zupełnie inna</display-name></channel>
<channel id="Dwa.pl"><display-name>Dwa</display-name></channel>
<programme start="20231114200000 +0000" stop="20231114210000 +0000" channel="Other.id"><title>T</title></programme>
<programme start="20231114200000 +0000" stop="20231114210000 +0000" channel="Dwa.pl"><title>T2</title></programme></tv>"""
        val d = XmltvParser.parse(xml.byteInputStream(), chs, 0, Long.MAX_VALUE)
        assertEquals("Other.id", d.keyFor(chs[0]))
        assertNull(d.keyFor(chs[1]))           // "" = bez EPG mimo pasującej nazwy
        assertEquals(2, d.channelNames.size)   // wszystkie kanały XMLTV dostępne do mapowania
        assertEquals(1, d.programmes["Other.id"]!!.size)
    }
}

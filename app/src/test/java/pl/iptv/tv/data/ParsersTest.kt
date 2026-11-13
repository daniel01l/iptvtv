package pl.iptv.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ParsersTest {
    private val m3u = """
#EXTM3U url-tvg="http://epg.example/guide.xml.gz" catchup="shift" catchup-days="3"
#EXTINF:-1 tvg-id="tvp1.pl" tvg-name="TVP 1 HD" tvg-logo="http://img/tvp1.png" group-title="Ogólne",TVP 1 HD
http://srv/tvp1/index.m3u8
#EXTINF:-1 tvg-id="polsat.pl" catchup="default" catchup-source="http://srv/arch/polsat?start={utc}&end={utcend}&d={duration}" catchup-days="7" group-title="Ogólne",Polsat, HD
#EXTVLCOPT:http-user-agent=MyUA
http://srv/polsat.ts
#EXTINF:-1 group-title="Sport" catchup="flussonic",Eleven
http://fs.host:8080/eleven1/index.m3u8?token=abc
#EXTINF:-1 catchup="xc",XC kanał
http://xc.host:8000/user/pass/1234.ts
""".trimIndent()

    @Test fun parsesM3u() {
        val pl = M3uParser.parse(m3u)
        assertEquals(listOf("http://epg.example/guide.xml.gz"), pl.epgUrls)
        assertEquals(4, pl.channels.size)
        val c0 = pl.channels[0]
        assertEquals("TVP 1 HD", c0.name); assertEquals("tvp1.pl", c0.tvgId)
        assertEquals("http://img/tvp1.png", c0.logo); assertEquals("Ogólne", c0.group)
        assertEquals("shift", c0.catchupType); assertEquals(3, c0.catchupDays)
        val c1 = pl.channels[1]
        assertEquals("Polsat, HD", c1.name); assertEquals("MyUA", c1.userAgent); assertEquals(7, c1.catchupDays)
        assertEquals("Sport", pl.channels[2].group)
    }

    @Test fun catchupUrls() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val ch = M3uParser.parse(m3u).channels
        val s = 1_700_000_000_000L; val e = s + 3_600_000L; val n = s + 7_200_000L
        assertEquals("http://srv/tvp1/index.m3u8?utc=1700000000&lutc=1700007200", CatchupUrlBuilder.build(ch[0], s, e, n))
        assertEquals("http://srv/arch/polsat?start=1700000000&end=1700003600&d=3600", CatchupUrlBuilder.build(ch[1], s, e, n))
        assertEquals("http://fs.host:8080/eleven1/index-1700000000-3600.m3u8?token=abc", CatchupUrlBuilder.build(ch[2], s, e, n))
        assertEquals("http://xc.host:8000/timeshift/user/pass/60/2023-11-14:22-13/1234.ts", CatchupUrlBuilder.build(ch[3], s, e, n))
        assertEquals("a/2023/11/14/22-13", CatchupUrlBuilder.fill("a/{Y}/{m}/{d}/{H}-{M}", s, e, n))
        assertEquals("x=20231114221320", CatchupUrlBuilder.fill("x=\${(b)YmdHMS}", s, e, n))
    }

    @Test fun parsesXmltv() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
 <channel id="TVP1.pl"><display-name>TVP 1</display-name><icon src="http://i/tvp1.png"/></channel>
 <channel id="Polsat.pl"><display-name lang="pl">Polsat</display-name></channel>
 <channel id="Other"><display-name>Other</display-name></channel>
 <programme start="20231114220000 +0100" stop="20231114230000 +0100" channel="TVP1.pl">
   <title lang="pl">Wiadomości</title><desc>Opis &amp; info</desc><category>news</category></programme>
 <programme start="20231114200000 +0000" stop="20231114210000 +0000" channel="Polsat.pl"><title>Film</title></programme>
 <programme start="20231114200000 +0000" stop="20231114210000 +0000" channel="Other"><title>X</title></programme>
</tv>"""
        val chs = M3uParser.parse(m3u).channels
        val data = XmltvParser.parse(xml.byteInputStream(), chs, 0, Long.MAX_VALUE)
        assertEquals("TVP1.pl", data.keyFor(chs[0]))
        assertEquals("Polsat.pl", data.keyFor(chs[1]))
        assertFalse(data.programmes.containsKey("Other"))
        val p = data.programmes["TVP1.pl"]!!.single()
        assertEquals("Wiadomości", p.title); assertEquals("Opis & info", p.desc)
        assertEquals(XmltvParser.parseTime("20231114210000 +0000"), p.start)
        assertEquals("http://i/tvp1.png", data.icons["TVP1.pl"])
        // gzip
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        val gz = XmltvParser.parse(bos.toByteArray().inputStream(), chs, 0, Long.MAX_VALUE)
        assertEquals(2, gz.programmes.size)
    }
}

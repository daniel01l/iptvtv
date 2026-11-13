package pl.iptv.tv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class EpgData(
    val programmes: Map<String, List<Programme>>,
    val idByLower: Map<String, String>,
    val idByName: Map<String, String>,
    val icons: Map<String, String>,
    /** Wszystkie kanały z pliku XMLTV: id -> nazwa (do ręcznego mapowania). */
    val channelNames: Map<String, String> = emptyMap(),
) {
    fun keyFor(ch: Channel): String? {
        ch.epgOverride?.let { o -> return if (o.isEmpty()) null else idByLower[o.lowercase()] ?: o }
        return ch.tvgId.takeIf { it.isNotBlank() }?.let { idByLower[it.lowercase()] }
            ?: ch.tvgId.takeIf { programmes.containsKey(it) }
            ?: idByName[XmltvParser.norm(ch.tvgName)]
            ?: idByName[XmltvParser.norm(ch.name)]
            ?: idByName[XmltvParser.norm(ch.origName)]
    }

    companion object { val EMPTY = EpgData(emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
}

object XmltvParser {

    fun norm(s: String): String {
        if (s.isBlank()) return "\u0000"
        return s.lowercase()
            .replace(Regex("""\b(hd|fhd|uhd|sd|4k|hevc|h265)\b"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
    }

    /** Otwiera strumień, automatycznie rozpakowując gzip. */
    fun maybeGunzip(input: InputStream): InputStream {
        val bis = BufferedInputStream(input, 64 * 1024)
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(bis, 64 * 1024)) else bis
    }

    /** Parsuje czas XMLTV: "20240101203000 +0100" -> ms UTC. */
    /** Dni od 1970-01-01 dla daty kalendarzowej (bez Calendar – szybko dla setek tysięcy programów). */
    private fun epochDay(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 12) return 0
        val t = s.trim()
        fun num(a: Int, b: Int): Int {
            if (t.length < b) return 0
            var v = 0
            for (i in a until b) { val c = t[i]; if (c !in '0'..'9') return 0; v = v * 10 + (c - '0') }
            return v
        }
        var ms = ((epochDay(num(0, 4), num(4, 6), num(6, 8)) * 24 + num(8, 10)) * 60 + num(10, 12)) * 60_000L + num(12, 14) * 1000L
        val tz = t.substringAfter(' ', "").trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val h = tz.substring(1, 3).toIntOrNull() ?: 0
            val m = tz.substring(3, 5).toIntOrNull() ?: 0
            val off = (h * 60 + m) * 60_000L
            ms = if (tz[0] == '+') ms - off else ms + off
        }
        return ms
    }

    /**
     * Strumieniowo parsuje XMLTV. Zachowuje tylko programy kanałów z playlisty
     * (jeśli [channels] niepuste) i w oknie czasowym [from, to].
     */
    /** [maxDesc] – najdłuższy zapamiętany opis (słabsze urządzenia: krótsze opisy = mniej pamięci). */
    fun parse(input: InputStream, channels: List<Channel>, from: Long, to: Long, maxDesc: Int = Int.MAX_VALUE): EpgData {
        // te same tytuły i kategorie (np. „Wiadomości”) przechowywane raz
        val pool = HashMap<String, String>()
        fun shared(s: String) = if (s.length > 80) s else pool.getOrPut(s) { s }
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(maybeGunzip(input), null)

        val wantIds = HashSet<String>()
        channels.forEach { c ->
            if (c.tvgId.isNotBlank()) wantIds.add(c.tvgId.lowercase())
            c.epgOverride?.takeIf { it.isNotEmpty() }?.let { wantIds.add(it.lowercase()) }
        }
        val wantNames = HashSet<String>().apply {
            channels.forEach { add(norm(it.name)); add(norm(it.tvgName)); add(norm(it.origName)) }
            remove("\u0000")
        }
        val filter = channels.isNotEmpty()

        val idByLower = HashMap<String, String>()
        val idByName = HashMap<String, String>()
        val icons = HashMap<String, String>()
        val allNames = HashMap<String, String>()
        val matched = HashSet<String>()
        val progs = HashMap<String, ArrayList<Programme>>()

        // stan bieżącego elementu
        var curChannelId: String? = null
        var curNames = ArrayList<String>()
        var pChannel: String? = null
        var pStart = 0L; var pEnd = 0L
        var pTitle = ""; var pDesc = ""; var pCat = ""; var pSub = ""; var pEp = ""; var epSystem = ""
        var textTarget = 0 // 1 display-name, 2 title, 3 desc, 4 category

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> { curChannelId = parser.getAttributeValue(null, "id"); curNames = ArrayList() }
                    "display-name" -> if (curChannelId != null) textTarget = 1
                    "icon" -> if (curChannelId != null && pChannel == null) {
                        parser.getAttributeValue(null, "src")?.let { icons[curChannelId!!] = it }
                    }
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")
                        val st = parseTime(parser.getAttributeValue(null, "start"))
                        var en = parseTime(parser.getAttributeValue(null, "stop"))
                        if (en <= st) en = st + 30 * 60_000L
                        val keep = ch != null && en >= from && st <= to &&
                            (!filter || ch in matched || ch.lowercase() in wantIds)
                        if (keep) {
                            pChannel = ch; pStart = st; pEnd = en; pTitle = ""; pDesc = ""; pCat = ""; pSub = ""; pEp = ""
                        } else {
                            skipElement(parser)
                        }
                    }
                    "title" -> if (pChannel != null && pTitle.isEmpty()) textTarget = 2
                    "desc" -> if (pChannel != null && pDesc.isEmpty()) textTarget = 3
                    "category" -> if (pChannel != null && pCat.isEmpty()) textTarget = 4
                    "sub-title" -> if (pChannel != null && pSub.isEmpty()) textTarget = 5
                    "episode-num" -> if (pChannel != null) {
                        epSystem = parser.getAttributeValue(null, "system") ?: ""
                        // xmltv_ns ma pierwszeństwo przed onscreen
                        if (pEp.isEmpty() || epSystem == "xmltv_ns") textTarget = 6
                    }
                }
                XmlPullParser.TEXT -> when (textTarget) {
                    1 -> curNames.add(parser.text.trim())
                    2 -> pTitle = shared(parser.text.trim())
                    3 -> pDesc = parser.text.trim().let { if (it.length > maxDesc) it.take(maxDesc).substringBeforeLast(' ') + "…" else it }
                    4 -> pCat = parser.text.trim().intern()
                    5 -> pSub = shared(parser.text.trim())
                    6 -> episodeText(epSystem, parser.text)?.let { pEp = it }
                }
                XmlPullParser.END_TAG -> {
                    textTarget = 0
                    when (parser.name) {
                        "channel" -> curChannelId?.let { id ->
                            allNames.putIfAbsent(id, curNames.firstOrNull() ?: id)
                            val nNames = curNames.map { norm(it) }
                            if (!filter || id.lowercase() in wantIds || nNames.any { it in wantNames }) {
                                matched.add(id)
                                idByLower[id.lowercase()] = id
                                nNames.forEach { n -> idByName.putIfAbsent(n, id) }
                            }
                            curChannelId = null
                        }
                        "programme" -> pChannel?.let { ch ->
                            progs.getOrPut(ch) { ArrayList() }.add(Programme(pStart, pEnd, pTitle, pDesc, pCat, pSub, pEp))
                            idByLower.putIfAbsent(ch.lowercase(), ch)
                            pChannel = null
                        }
                    }
                }
            }
            ev = parser.next()
        }
        progs.values.forEach { list -> list.sortBy { it.start } }
        return EpgData(progs, idByLower, idByName, icons, allNames)
    }

    /** "0.11.0/1" (xmltv_ns, liczone od 0) -> "S01E12"; onscreen bez zmian. */
    fun episodeText(system: String, raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (system != "xmltv_ns") return if (system == "onscreen" || system.isEmpty()) t else null
        val parts = t.split('.')
        fun num(i: Int) = parts.getOrNull(i)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
        val se = num(0); val ep = num(1)
        return when {
            se != null && ep != null -> "S%02dE%02d".format(se, ep)
            ep != null -> "E%02d".format(ep)
            se != null -> "S%02d".format(se)
            else -> null
        }
    }

    private fun skipElement(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

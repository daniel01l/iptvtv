package pl.iptv.tv.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Buduje adres archiwum (catch-up) zgodnie z konwencjami M3U
 * (catchup="default|append|shift|flussonic|xc", catchup-source="...").
 */
object CatchupUrlBuilder {

    fun build(ch: Channel, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        if (!ch.hasCatchup) return null
        val type = ch.catchupType.lowercase(Locale.ROOT)
        val src = ch.catchupSource
        return when (type) {
            "append" -> ch.url + fill(src, startMs, endMs, nowMs)
            "shift", "timeshift" -> appendQuery(ch.url, "utc=${startMs / 1000}&lutc=${nowMs / 1000}")
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(ch.url, startMs, endMs, type)
            "xc", "xtream" -> xtream(ch.url, startMs, endMs)
            else -> when {
                // "default" lub brak typu z podanym szablonem
                src.startsWith("http", true) || src.startsWith("rtmp", true) || src.startsWith("rtsp", true) ->
                    fill(src, startMs, endMs, nowMs)
                src.isNotBlank() -> ch.url + fill(src, startMs, endMs, nowMs)
                else -> xtream(ch.url, startMs, endMs)
                    ?: appendQuery(ch.url, "utc=${startMs / 1000}&lutc=${nowMs / 1000}")
            }
        }
    }

    private fun appendQuery(url: String, q: String) = url + (if (url.contains('?')) "&" else "?") + q

    private val fmtRegex = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}|\$\{\((b|e)\)([^}]+)\}""")
    private val dateTokRegex = Regex("""\{(Y|m|d|H|M|S)\}""")

    fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long): String {
        val s = startMs / 1000; val e = endMs / 1000; val n = nowMs / 1000
        val dur = (e - s).coerceAtLeast(0)
        var out = fmtRegex.replace(template) { m ->
            if (m.groups[1] != null) {
                val t = when (m.groupValues[1]) { "utcend", "end" -> endMs; "lutc", "now", "timestamp" -> nowMs; else -> startMs }
                formatDate(m.groupValues[2], t)
            } else formatDate(m.groupValues[4], if (m.groupValues[3] == "e") endMs else startMs)
        }
        out = Regex("""\{duration:(\d+)\}""").replace(out) { (dur / it.groupValues[1].toLong()).toString() }
        val map = linkedMapOf(
            "\${start}" to s, "\${end}" to e, "\${now}" to n, "\${timestamp}" to n,
            "\${duration}" to dur, "\${offset}" to (n - s),
            "{utcend}" to e, "{utc}" to s, "{start}" to s, "{end}" to e,
            "{lutc}" to n, "{now}" to n, "{timestamp}" to n,
            "{duration}" to dur, "{offset}" to (n - s),
        )
        for ((k, v) in map) out = out.replace(k, v.toString())
        out = dateTokRegex.replace(out) { formatDate(it.groupValues[1], startMs) }
        return out
    }

    /** Format z literami Y m d H M S (np. "Y-m-d:H-M"); pozostałe znaki bez zmian. */
    fun formatDate(fmt: String, ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val sb = StringBuilder()
        for (ch in fmt) sb.append(
            when (ch) {
                'Y' -> "%04d".format(c.get(Calendar.YEAR))
                'm' -> "%02d".format(c.get(Calendar.MONTH) + 1)
                'd' -> "%02d".format(c.get(Calendar.DAY_OF_MONTH))
                'H' -> "%02d".format(c.get(Calendar.HOUR_OF_DAY))
                'M' -> "%02d".format(c.get(Calendar.MINUTE))
                'S' -> "%02d".format(c.get(Calendar.SECOND))
                else -> ch.toString()
            }
        )
        return sb.toString()
    }

    private fun flussonic(url: String, startMs: Long, endMs: Long, type: String): String? {
        val s = startMs / 1000; val dur = (endMs - startMs) / 1000
        val m = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""").find(url) ?: return null
        val host = m.groupValues[1]; val path = m.groupValues[2]; val file = m.groupValues[3]
        val q = m.groupValues[4]
        return when {
            file == "mpegts" || type == "flussonic-ts" -> "$host/$path/timeshift_abs-$s.ts$q"
            file.endsWith(".m3u8") -> {
                val listType = if (file.startsWith("mono")) "mono" else "index"
                "$host/$path/$listType-$s-$dur.m3u8$q"
            }
            else -> "$host/$path/$file/index-$s-$dur.m3u8$q"
        }
    }

    /** Xtream Codes: http://host/(live/)user/pass/id.ext -> /timeshift/user/pass/min/Y-m-d:H-M/id.ext */
    private fun xtream(url: String, startMs: Long, endMs: Long): String? {
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""").find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        val ext = m.groupValues[5].ifEmpty { ".ts" }
        val minutes = ((endMs - startMs) / 60_000).coerceAtLeast(1)
        return "$host/timeshift/$user/$pass/$minutes/${formatDate("Y-m-d:H-M", startMs)}/$id$ext"
    }
}

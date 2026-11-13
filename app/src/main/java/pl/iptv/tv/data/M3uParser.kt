package pl.iptv.tv.data

import java.io.BufferedReader
import java.io.Reader

object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)=(?:"([^"]*)"|'([^']*)'|([^\s,"]+))""")

    fun parseAttrs(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(s)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            map[m.groupValues[1].lowercase()] = v.trim()
        }
        return map
    }

    /** Zwraca nazwę kanału: tekst po pierwszym przecinku poza cudzysłowami. */
    private fun extractName(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return ""
    }

    fun parse(text: String): Playlist = parse(text.reader())

    fun parse(reader: Reader): Playlist {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)
        val channels = ArrayList<Channel>()
        val epgUrls = ArrayList<String>()
        var header: Map<String, String> = emptyMap()

        var attrs: Map<String, String>? = null
        var name = ""
        var extGroup = ""
        var ua = ""
        var ref = ""
        val urlCount = HashMap<String, Int>()

        br.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U", true) -> {
                    header = parseAttrs(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { k ->
                        header[k]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", true) -> {
                    val name0 = extractName(line)
                    val attrPart = line.substringBefore(",$name0").let { if (name0.isEmpty()) line else it }
                    attrs = parseAttrs(attrPart)
                    name = name0
                }
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substring(11).trim()
                    when {
                        opt.startsWith("http-user-agent=", true) -> ua = opt.substringAfter('=')
                        opt.startsWith("http-referrer=", true) -> ref = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> {}
                else -> {
                    val a = attrs ?: emptyMap()
                    val days = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"]
                        ?: header["catchup-days"] ?: header["tvg-rec"] ?: "0").toIntOrNull() ?: 0
                    var type = a["catchup"] ?: a["catchup-type"] ?: header["catchup"] ?: ""
                    if (type.isEmpty() && (a["tvg-rec"] ?: a["timeshift"] ?: "0") != "0") type = "default"
                    val chName = name.ifEmpty { a["tvg-name"] ?: "Kanał ${channels.size + 1}" }
                    val n = urlCount.getOrDefault(line, 0); urlCount[line] = n + 1
                    channels += Channel(
                        key = if (n == 0) line else "$line#$n",
                        number = channels.size + 1,
                        name = chName,
                        url = line,
                        group = (a["group-title"] ?: extGroup).ifEmpty { "Inne" },
                        tvgId = a["tvg-id"] ?: "",
                        tvgName = a["tvg-name"] ?: "",
                        logo = a["tvg-logo"] ?: a["logo"] ?: "",
                        catchupType = type,
                        catchupSource = a["catchup-source"] ?: header["catchup-source"] ?: "",
                        catchupDays = if (days == 0 && type.isNotEmpty()) 7 else days,
                        userAgent = ua.ifEmpty { a["http-user-agent"] ?: a["user-agent"] ?: "" },
                        referrer = ref.ifEmpty { a["http-referrer"] ?: a["referrer"] ?: "" },
                    )
                    attrs = null; name = ""; extGroup = ""; ua = ""; ref = ""
                }
            }
        }
        return Playlist(channels, epgUrls.distinct())
    }
}

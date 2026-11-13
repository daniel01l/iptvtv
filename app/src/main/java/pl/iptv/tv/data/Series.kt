package pl.iptv.tv.data

/** Rozpoznawanie, że dwa programy to odcinki tej samej serii. */
object Series {
    private val patterns = listOf(
        Regex("""\s*[\-:–|,]\s*(odc\.?|odcinek|ep\.?|episode|sezon|season|seria|cz\.|część)\s*\d.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\b(odc\.?|odcinek|ep\.?|episode)\s*\d+.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\b(s\d{1,2}\s*[ex]\s*\d{1,3}|\d{1,2}x\d{1,3})\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\b(sezon|season|seria)\s*\d+.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[]\s*(odc\.?\s*)?\d+(\s*/\s*\d+)?\s*[)\]]\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[]\s*(powtórka|premiera|nowy odcinek|live|na żywo)\s*[)\]]\s*$""", RegexOption.IGNORE_CASE),
    )

    fun baseTitle(title: String): String {
        var t = title.trim()
        for (r in patterns) t = r.replace(t, "")
        t = t.trimEnd(' ', '-', ':', '–', ',', '.', '|')
        return t.ifEmpty { title.trim() }
    }

    private val nonAlnum = Regex("""[^\p{L}\p{N}]""")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Klucz serii (buforowany – tytuły w EPG często się powtarzają). */
    fun key(title: String): String = cache[title] ?: baseTitle(title).lowercase().replace(nonAlnum, "").also {
        if (cache.size > 200_000) cache.clear()
        cache[title] = it
    }

    /** Czy [b] to inny odcinek niż [a] (a nie powtórka tego samego). */
    fun isDifferentEpisode(a: Programme, b: Programme): Boolean {
        if (a.episode.isNotBlank() && b.episode.isNotBlank()) return a.episode != b.episode
        if (a.subTitle.isNotBlank() && b.subTitle.isNotBlank()) return !a.subTitle.equals(b.subTitle, true)
        if (a.title != b.title) return true
        return true
    }
}

package pl.iptv.tv.data

/** Rozdział filmu: początek (ms) i tytuł. */
data class YtChapter(val startMs: Long, val title: String)

/**
 * Rozdziały filmu YouTube: z danych YouTube (NewPipe: segmenty filmu) albo – gdy ich brak – z opisu (znaczniki czasu w liniach,
 * jak „0:00 Wstęp”). Reguły YouTube: pierwszy rozdział od 0:00, co najmniej 3 rozdziały, rosnąco, każdy ≥ 10 s.
 * Czyste funkcje – testowane bez sieci.
 */
object YtChapters {
    const val MIN_CHAPTERS = 3
    const val MIN_LENGTH_MS = 10_000L
    /** ▲ w pierwszych sekundach rozdziału – poprzedni rozdział, później – początek bieżącego (jak „Poprzedni” w odtwarzaczach). */
    const val RESTART_WINDOW_MS = 3_000L

    private val TIME = Regex("(?<![\\d:])((?:\\d{1,2}:)?\\d{1,2}:\\d{2})(?![\\d:])")

    /** „1:02:03” / „12:34” → ms; null przy złym formacie (minuty/sekundy ≥ 60). */
    fun parseTime(s: String): Long? {
        val p = s.split(':').map { it.toIntOrNull() ?: return null }
        val (h, m, sec) = when (p.size) { 2 -> Triple(0, p[0], p[1]); 3 -> Triple(p[0], p[1], p[2]); else -> return null }
        if (sec >= 60 || (p.size == 3 && m >= 60)) return null
        return ((h * 3600L) + m * 60L + sec) * 1000L
    }

    /** Opis HTML z ekstraktora → zwykły tekst z liniami. */
    fun plainText(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Rozdziały z opisu filmu; pusta lista, gdy opis nie spełnia reguł YouTube. [durationMs] ≤ 0 – długość nieznana. */
    fun fromDescription(description: String, durationMs: Long = 0): List<YtChapter> {
        val out = mutableListOf<YtChapter>()
        for (raw in plainText(description).lines()) {
            val m = TIME.find(raw) ?: continue
            val start = parseTime(m.groupValues[1]) ?: continue
            val title = raw.removeRange(m.range).trim().trim('-', '–', '—', '|', ':', '•', '·', ')', '(', '[', ']').trim()
            out += YtChapter(start, title.ifBlank { "Rozdział ${out.size + 1}" })
        }
        return valid(out, durationMs)
    }

    /** Segmenty z ekstraktora (sekundy, tytuł). */
    fun fromSegments(segments: List<Pair<Int, String>>, durationMs: Long = 0): List<YtChapter> =
        valid(segments.map { (s, t) -> YtChapter(s * 1000L, t.trim().ifBlank { "Rozdział" }) }, durationMs, strict = false)

    /** Reguły YouTube; [strict] = false dla danych YouTube (już zweryfikowanych) – tylko porządek i długość filmu. */
    fun valid(list: List<YtChapter>, durationMs: Long, strict: Boolean = true): List<YtChapter> {
        val c = list.filter { durationMs <= 0 || it.startMs < durationMs }
        if (c.isEmpty() || c.first().startMs != 0L) return emptyList()
        if (c.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
        if (strict) {
            if (c.size < MIN_CHAPTERS) return emptyList()
            if (c.zipWithNext().any { (a, b) -> b.startMs - a.startMs < MIN_LENGTH_MS }) return emptyList()
            if (durationMs > 0 && durationMs - c.last().startMs < MIN_LENGTH_MS) return emptyList()
        } else if (c.size < 2) return emptyList()
        return c
    }

    /** Indeks rozdziału pod pozycją [posMs] (-1 = brak rozdziałów). */
    fun indexAt(chapters: List<YtChapter>, posMs: Long): Int = chapters.indexOfLast { it.startMs <= posMs }.let { if (chapters.isEmpty()) -1 else it.coerceAtLeast(0) }

    /** Cel ▼ / ►►: początek następnego rozdziału (null – ostatni). */
    fun nextStart(chapters: List<YtChapter>, posMs: Long): Long? = chapters.getOrNull(indexAt(chapters, posMs) + 1)?.startMs

    /** Cel ▲ / ◄◄: początek bieżącego rozdziału, a w pierwszych [RESTART_WINDOW_MS] – poprzedniego. */
    fun prevStart(chapters: List<YtChapter>, posMs: Long): Long? {
        val i = indexAt(chapters, posMs)
        if (i < 0) return null
        return if (posMs - chapters[i].startMs > RESTART_WINDOW_MS || i == 0) chapters[i].startMs else chapters[i - 1].startMs
    }
}

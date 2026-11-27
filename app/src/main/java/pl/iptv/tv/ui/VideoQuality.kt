package pl.iptv.tv.ui

/**
 * Jedna, wspólna nazwa jakości obrazu w całej aplikacji (pigułki jakości STube / Stremio, okno „Jakość obrazu”, pasek Live TV):
 * klasy standardowe 2160p / 1440p / 1080p / 720p / 480p / 360p / 240p / 144p liczone z szerokości **albo** wysokości obrazu.
 * Film kinowy 1920 × 804 to „1080p” (dawniej „804p”), 1920 × 1072 – też „1080p” (dawniej „1072p”), 4:3 1440 × 1080 – „1080p”.
 */
object VideoQuality {
    /** Klasy (wysokość nominalna) i odpowiadające im szerokości 16:9. */
    val CLASSES = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
    private val WIDTHS = intArrayOf(3840, 2560, 1920, 1280, 854, 640, 426, 256)
    /** Tolerancja: obraz o co najmniej 90 % szerokości / wysokości klasy należy do niej (np. 1728 px, 1012 linii). */
    private const val TOLERANCE = 0.9f

    /** Klasa obrazu [w] × [h] (0 – nieznany rozmiar). */
    fun classOf(w: Int, h: Int): Int {
        if (w <= 0 && h <= 0) return 0
        for (i in CLASSES.indices) if (w >= WIDTHS[i] * TOLERANCE || h >= CLASSES[i] * TOLERANCE) return CLASSES[i]
        return CLASSES.last()
    }

    /** Nominalna szerokość klasy [c] (do ograniczenia rozmiaru ścieżki). */
    fun widthOf(c: Int): Int = CLASSES.indexOf(c).takeIf { it >= 0 }?.let { WIDTHS[it] } ?: Int.MAX_VALUE

    /** „1080p”, „1080p60”, „4K” (2160p) – bez dopisku „Auto”. */
    fun label(c: Int, fps: Float = 0f): String = when {
        c <= 0 -> ""
        c >= 2160 -> "4K" + if (fps >= 48f) "60" else ""
        else -> "${c}p" + if (fps >= 48f) "60" else ""
    }

    /** Etykieta z rozmiaru obrazu. */
    fun label(w: Int, h: Int, fps: Float = 0f): String = label(classOf(w, h), fps)
}

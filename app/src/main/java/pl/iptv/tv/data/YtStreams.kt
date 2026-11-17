package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Strumień kandydujący (obraz lub dźwięk) – niezależny od ekstraktora, wybór testowany bez sieci. */
data class YtCandidate(
    val url: String, val height: Int = 0, val fps: Int = 0, val codec: String = "", val mime: String = "",
    val bitrate: Int = 0, val videoOnly: Boolean = false, val original: Boolean = true,
    /** Dane itag (do zbudowania manifestu DASH); null w testach / strumieniach bez itag. */
    val itag: ItagItem? = null,
    /** Ścieżka dźwięku: język (BCP 47) i nazwa (np. „polski (oryginał)”) – wybór ścieżki w odtwarzaczu. */
    val lang: String = "", val label: String = "",
) {
    val avc get() = codec.startsWith("avc")
    val aac get() = codec.startsWith("mp4a") || mime.contains("mp4")
}

/** Napisy filmu (WebVTT): adres, język, nazwa; [auto] – wygenerowane automatycznie. */
data class YtSubtitle(val url: String, val lang: String, val label: String, val auto: Boolean = false)

/** Co odtworzyć: osobny obraz + dźwięk (DASH), strumień z dźwiękiem albo HLS/DASH na żywo. */
sealed class YtPlayable {
    abstract val title: String
    abstract val durationS: Long
    /** Rozdziały filmu ([YtChapters]); puste – brak. */
    abstract val chapters: List<YtChapter>
    /** Propozycje (podobne filmy) – panel boczny odtwarzacza. */
    open val related: List<YtVideo> get() = emptyList()
    /** Kanał (autor) filmu – podpis w odtwarzaczu i powiadomieniu. */
    open val channel: String get() = ""
    /** Napisy do wyboru (menu „Napisy”). */
    open val subtitles: List<YtSubtitle> get() = emptyList()
    data class Split(override val title: String, override val durationS: Long, val video: YtCandidate, val audio: YtCandidate,
                     override val chapters: List<YtChapter> = emptyList(), override val related: List<YtVideo> = emptyList(),
                     /** Inne rozdzielczości (wybór jakości) i inne ścieżki dźwięku (języki) – osobne grupy ścieżek odtwarzacza. */
                     val videoAlts: List<YtCandidate> = emptyList(), val audioAlts: List<YtCandidate> = emptyList(),
                     override val subtitles: List<YtSubtitle> = emptyList(), override val channel: String = "") : YtPlayable()
    data class Muxed(override val title: String, override val durationS: Long, val stream: YtCandidate,
                     override val chapters: List<YtChapter> = emptyList(), override val related: List<YtVideo> = emptyList(),
                     override val subtitles: List<YtSubtitle> = emptyList(), override val channel: String = "") : YtPlayable()
    data class Live(override val title: String, val hlsUrl: String?, val dashUrl: String?) : YtPlayable() {
        override val durationS = 0L; override val chapters = emptyList<YtChapter>()
    }
}

/** Film niedostępny do odtworzenia w aplikacji – [message] gotowy do pokazania na ekranie. */
class YtStreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Adresy strumieni YouTube bez reklam (NewPipeExtractor): strumienie pobierane bezpośrednio, bez odtwarzacza YouTube,
 * więc reklamy wideo w ogóle nie występują. Wybór jakości wg urządzenia (H.264 przed VP9 – dekodowany sprzętowo wszędzie).
 */
object YtStreams {
    /** Przeglądarkowy UA dla zapytań ekstraktora (strony i API YouTube). */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    @Volatile private var initialized = false

    /** Pobieranie stron i API dla ekstraktora przez wspólny klient HTTP aplikacji. */
    class OkHttpDownloader(private val client: () -> okhttp3.OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()
            // treść tylko dla POST/PUT (OkHttp odrzuca treść w GET/HEAD); typ treści – z nagłówków ekstraktora
            val needsBody = request.httpMethod() == "POST" || request.httpMethod() == "PUT"
            val rb = okhttp3.Request.Builder().url(request.url())
                .method(request.httpMethod(), if (needsBody) (body ?: ByteArray(0)).toRequestBody() else null)
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (k, vs) -> rb.removeHeader(k); vs.forEach { v -> rb.addHeader(k, v) } }
            client().newCall(rb.build()).execute().use { r ->
                // 429 = zbyt wiele zapytań / weryfikacja „nie jestem robotem”
                if (r.code == 429) throw ReCaptchaException("reCaptcha", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader { Repository.http }, Localization("pl", "PL"), ContentCountry(Prefs.ytRegion.ifBlank { "PL" }))
            initialized = true
        }
    }

    /** Maksymalna wysokość obrazu: tryb słabszych urządzeń – z jego ustawień, inaczej 1080p (bez 4K na TV z wolnym dekoderem). */
    val maxHeight: Int get() = if (Prefs.lowPerf && Prefs.lowMaxHeight > 0) Prefs.lowMaxHeight else Prefs.ytMaxHeight.takeIf { it > 0 } ?: 1080

    /**
     * Najlepszy obraz ≤ [maxH]: najwyższa rozdzielczość; przy równej – H.264 (sprzętowy dekoder na każdym TV), potem wyższy fps,
     * potem przepływność. Ponad limitem – najniższy dostępny (lepiej coś niż nic).
     */
    fun pickVideo(list: List<YtCandidate>, maxH: Int): YtCandidate? {
        if (list.isEmpty()) return null
        val ok = list.filter { it.height in 1..maxH }
        if (ok.isEmpty()) return list.minByOrNull { it.height }
        return ok.sortedWith(compareByDescending<YtCandidate> { it.height }.thenByDescending { it.avc }
            .thenByDescending { it.fps.coerceAtMost(60) }.thenByDescending { it.bitrate }).first()
    }

    /** Dźwięk: oryginalna ścieżka (nie dubbing), AAC przed Opus (zgodność), najwyższa przepływność. */
    /** Najwyższa rozdzielczość w menu jakości: tryb słabszych urządzeń – jego limit, inaczej co najmniej 1080p (lub więcej z Ustawień). */
    val qualityCap: Int get() = if (Prefs.lowPerf && Prefs.lowMaxHeight > 0) Prefs.lowMaxHeight else maxOf(1080, Prefs.ytMaxHeight)

    /**
     * Inne rozdzielczości do wyboru jakości: po jednej na wysokość (≤ [cap]), w kolejności jak [pickVideo] (H.264 przed VP9),
     * bez wysokości już wybranej, od najwyższej; najwyżej [MAX_ALTS].
     */
    fun videoAlternatives(list: List<YtCandidate>, chosen: YtCandidate, cap: Int): List<YtCandidate> =
        list.filter { it.height in 1..cap && it.height != chosen.height }.groupBy { it.height }
            .mapNotNull { (h, l) -> pickVideo(l, h) }.sortedByDescending { it.height }.take(MAX_ALTS)

    /** Inne ścieżki dźwięku (języki, dubbing): po jednej na język, oryginał pomijany, gdy już wybrany. */
    fun audioTracks(list: List<YtCandidate>, chosen: YtCandidate): List<YtCandidate> =
        list.filter { it.lang.isNotBlank() && it.lang != chosen.lang }.groupBy { it.lang }.mapNotNull { (_, l) -> pickAudio(l) }

    const val MAX_ALTS = 5

    /** Napisy WebVTT (ręczne przed automatycznymi, po jednym na język i rodzaj). */
    private fun subtitlesOf(info: StreamInfo): List<YtSubtitle> = info.subtitles.orEmpty()
        .filter { it.isUrl && it.format == org.schabi.newpipe.extractor.MediaFormat.VTT }
        .map { YtSubtitle(it.content, it.languageTag.orEmpty(), it.displayLanguageName.orEmpty().ifBlank { it.languageTag.orEmpty() } + if (it.isAutoGenerated) " (automatyczne)" else "", it.isAutoGenerated) }
        .distinctBy { it.lang to it.auto }.sortedBy { it.auto }

    fun pickAudio(list: List<YtCandidate>): YtCandidate? =
        list.sortedWith(compareByDescending<YtCandidate> { it.original }.thenByDescending { it.aac }.thenByDescending { it.bitrate }).firstOrNull()

    private fun VideoStream.candidate() = YtCandidate(content, height, fps, codec.orEmpty(), format?.mimeType.orEmpty(), bitrate, isVideoOnly, true, itagItem)
    private fun AudioStream.candidate() = YtCandidate(content, 0, 0, codec.orEmpty(), format?.mimeType.orEmpty(), averageBitrate.takeIf { it > 0 } ?: bitrate,
        false, audioTrackType == null || audioTrackType == AudioTrackType.ORIGINAL, itagItem,
        lang = audioLocale?.toLanguageTag()?.takeIf { it != "und" }.orEmpty(),
        label = audioTrackName?.ifBlank { null } ?: audioLocale?.getDisplayLanguage(java.util.Locale("pl"))?.ifBlank { null }.orEmpty())

    /** Testy: zastępczy resolver (bez sieci). */
    @Volatile var resolverOverride: (suspend (String) -> YtPlayable)? = null

    /** Adresy strumieni filmu [videoId] (sieć – wątek IO). */
    suspend fun resolve(videoId: String): YtPlayable = withContext(Dispatchers.IO) {
        resolverOverride?.let { return@withContext it(videoId) }
        ensureInit()
        // region z Ustawień (zmiana działa bez ponownego uruchomienia aplikacji)
        NewPipe.setPreferredContentCountry(ContentCountry(Prefs.ytRegion.ifBlank { "PL" }))
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) { throw YtStreamException(message(e), e) }
        fromInfo(info)
    }

    private fun fromInfo(info: StreamInfo): YtPlayable {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            val hls = info.hlsUrl?.ifBlank { null }; val dash = info.dashMpdUrl?.ifBlank { null }
            if (hls == null && dash == null) throw YtStreamException("Transmisja na żywo niedostępna w aplikacji")
            return YtPlayable.Live(info.name, hls, dash)
        }
        val url = { s: org.schabi.newpipe.extractor.stream.Stream -> s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.itagItem != null }
        val videos = info.videoOnlyStreams.filter(url).map { it.candidate() }
        val audios = info.audioStreams.filter(url).map { it.candidate() }
        val video = pickVideo(videos, maxHeight)
        val audio = pickAudio(audios)
        val subs = runCatching { subtitlesOf(info) }.getOrDefault(emptyList())
        val channel = info.uploaderName.orEmpty()
        val chapters = chaptersOf(info)
        val related = runCatching { info.relatedItems.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().mapNotNull(YtKeyless::video).take(30) }.getOrDefault(emptyList())
        if (video != null && audio != null) return YtPlayable.Split(info.name, info.duration, video, audio, chapters, related,
            videoAlternatives(videos, video, qualityCap), audioTracks(audios, audio), subs, channel)
        // zapas: strumień z obrazem i dźwiękiem (najwyżej 360p/720p)
        val muxed = pickVideo(info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { it.candidate() }, maxHeight)
            ?: throw YtStreamException("Brak strumienia do odtworzenia")
        return YtPlayable.Muxed(info.name, info.duration, muxed, chapters, related, subs, channel)
    }

    /** Rozdziały: segmenty YouTube, a bez nich – znaczniki czasu z opisu. Błąd danych nie blokuje odtwarzania. */
    private fun chaptersOf(info: StreamInfo): List<YtChapter> = runCatching {
        val dur = info.duration * 1000L
        YtChapters.fromSegments(info.streamSegments.orEmpty().map { it.startTimeSeconds to it.title.orEmpty() }, dur)
            .ifEmpty { YtChapters.fromDescription(info.description?.content().orEmpty(), dur) }
    }.getOrDefault(emptyList())

    /** Czytelny komunikat dla błędu ekstrakcji. */
    fun message(e: Throwable): String = when (e) {
        is AgeRestrictedContentException -> "Film z ograniczeniem wiekowym – wymaga zalogowania w YouTube"
        is GeographicRestrictionException -> "Film niedostępny w tym kraju"
        is SignInConfirmNotBotException, is ReCaptchaException -> "YouTube zablokował odtwarzanie (weryfikacja „nie jestem robotem”)"
        is PaidContentException -> "Film płatny – niedostępny w aplikacji"
        is PrivateContentException -> "Film prywatny"
        // treść błędu z YouTube jest po angielsku i techniczna – na ekranie TV krótki komunikat
        is ContentNotAvailableException -> "Film niedostępny (usunięty, prywatny lub zablokowany)"
        is java.io.IOException -> "Brak połączenia z YouTube"
        else -> "Nie udało się pobrać strumienia YouTube"
    }
}

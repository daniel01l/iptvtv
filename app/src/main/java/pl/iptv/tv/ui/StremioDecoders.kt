package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Dekodowanie w odtwarzaczu Stremio – strumienie z wtyczek mają często dźwięk AC3/E-AC3/DTS/TrueHD i obraz w wysokich profilach
 * (HEVC Main10, 4K), których dekoder sprzętowy TV nie przyjmuje (`ERROR_CODE_DECODER_INIT_FAILED`).
 *
 * Dwa etapy:
 * - [Stage.NORMAL] – dekodery sprzętowe przed programowymi, zapasowy dekoder przy błędzie inicjalizacji
 *   (`setEnableDecoderFallback`), programowy dekoder dźwięku FFmpeg (moduł Jellyfin) jako zapas, passthrough dźwięku
 *   tylko dla formatów, które zgłasza system audio (HDMI / soundbar).
 * - [Stage.SAFE] – po błędzie dekodera: FFmpeg i dekodery programowe przed sprzętowymi, dźwięk zawsze dekodowany do PCM
 *   (bez passthrough – wielokanałowy PCM system miksuje do stereo).
 *
 * W obu etapach selektor ścieżek nie wybiera ścieżek, których urządzenie nie obsługuje (np. DTS bez dekodera i bez passthrough),
 * jeśli jest obsługiwana alternatywa – zamiast błędu dekodera gra np. ścieżka AC3/AAC; bez alternatywy obraz gra bez dźwięku.
 */
@OptIn(UnstableApi::class)
object StremioDecoders {
    enum class Stage { NORMAL, SAFE }

    /** Błędy, przy których pomaga inny dekoder / dekodowanie dźwięku do PCM. */
    private val DECODER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    )

    fun isDecoderError(code: Int) = code in DECODER_ERRORS

    /** Następny etap po błędzie [code]; null – nie ma już czego próbować (okno z wyborem). */
    fun nextStage(current: Stage, code: Int): Stage? = if (current == Stage.NORMAL && isDecoderError(code)) Stage.SAFE else null

    /** Dekoder FFmpeg (org.jellyfin.media3:media3-ffmpeg-decoder) w aplikacji – klasa ładowana przez Media3 refleksją. */
    val ffmpegAvailable: Boolean by lazy {
        runCatching { Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"); true }.getOrDefault(false)
    }

    /**
     * Fabryka rendererów dla etapu: kolejność dekoderów, zapasowy dekoder, rozszerzenia (ładowane przez Media3 tylko, gdy są
     * w aplikacji – bez nich ustawienie nic nie zmienia) i ujście dźwięku.
     */
    fun renderersFactory(c: Context, stage: Stage): DefaultRenderersFactory {
        val safe = stage == Stage.SAFE
        return object : DefaultRenderersFactory(c.applicationContext) {
            override fun buildAudioSink(ctx: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val b = DefaultAudioSink.Builder(ctx)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // tryb bezpieczny: tylko PCM stereo – żadnego passthrough (AC3/DTS do amplitunera), dźwięk dekodowany w TV
                @Suppress("DEPRECATION")
                if (safe) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                return b.build()
            }
        }.apply {
            setEnableDecoderFallback(true)
            setMediaCodecSelector(Playback.codecSelector(software = safe))
            setExtensionRendererMode(if (safe) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
    }

    /** Selektor ścieżek: obsługiwane ścieżki przed nieobsługiwanymi (DTS/TrueHD bez dekodera nie jest wybierany na siłę). */
    /**
     * [forceVideo] – obraz także ponad deklarowane możliwości dekodera. Telewizory często zaniżają je dla 4K HEVC 10-bit /
     * Dolby Vision / HDR, a bez tej opcji odtwarzacz wybierał wtedy SAMĄ ścieżkę dźwięku (film 4K: dźwięk jest, obrazu nie ma).
     * Obraz wymuszany jest po wykryciu takiej sytuacji ([needsForcedVideo]); gdy dekoder naprawdę nie da rady – zwykła obsługa
     * błędu dekodera (tryb programowy, inne źródło, zewnętrzny odtwarzacz).
     */
    fun trackSelector(c: Context, forceVideo: Boolean = false) = DefaultTrackSelector(c.applicationContext).apply {
        setParameters(buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(forceVideo)
            .setExceedVideoConstraintsIfNecessary(true)
            .setConstrainAudioChannelCountToDeviceCapabilities(true))
    }

    fun player(c: Context, stage: Stage, sources: MediaSource.Factory, forceVideo: Boolean = false): ExoPlayer =
        ExoPlayer.Builder(c, renderersFactory(c, stage)).setTrackSelector(trackSelector(c, forceVideo)).setMediaSourceFactory(sources)
            .setLoadControl(loadControl()).build()

    /** Plik ma obraz, ale żadna ścieżka obrazu nie została wybrana (zaniżone możliwości dekodera) – start z wymuszonym obrazem. */
    fun needsForcedVideo(tracks: androidx.media3.common.Tracks): Boolean {
        val video = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        return video.isNotEmpty() && video.none { it.isSelected }
    }

    /** Bufor filmów Stremio (pliki z serwerów / debrid o zmiennej przepustowości). */
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 120_000
    const val START_MS = 2_500
    const val REBUFFER_MS = 8_000
    const val BUFFER_BYTES = 160 * 1024 * 1024

    /**
     * Większy zapas niż domyślny (ok. 50 s): do 2 min lub 160 MB (limit pamięci na TV przy 4K), start po 2,5 s, a po przerwie
     * wznowienie dopiero przy 8 s zapasu – chwilowe spadki przepustowości źródła nie zatrzymują obrazu co chwilę
     * („czasem się zacina i za chwilę działa”). Ostatnie 10 s zostaje w pamięci (szybkie cofnięcie bez ponownego pobierania).
     */
    fun loadControl(): androidx.media3.exoplayer.DefaultLoadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_MS, REBUFFER_MS)
        .setTargetBufferBytes(BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(10_000, true)
        .build()

    /** Czytelny opis przyczyny dla okna na TV (kodek, którego nie udało się zdekodować). */
    fun describe(e: PlaybackException): String {
        val mime = (generateSequence(e.cause) { it.cause }.firstNotNullOfOrNull { (it as? MediaCodecRenderer.DecoderInitializationException)?.mimeType })
        val codec = mime?.let { codecName(it) }
        return when {
            codec != null -> "Telewizor nie potrafi zdekodować formatu $codec."
            e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "System dźwięku telewizora odrzucił ten format dźwięku."
            isDecoderError(e.errorCode) -> "Telewizor nie potrafi zdekodować tego strumienia."
            e.errorCode in 2000..2999 -> "Nie udało się pobrać strumienia (sieć lub źródło niedostępne)."
            e.errorCode in 3000..3999 -> "Nieobsługiwany lub uszkodzony format pliku."
            else -> "Błąd odtwarzania (${e.errorCodeName})."
        }
    }

    fun codecName(mime: String): String = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC3)"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus (E-AC3)"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_H264 -> "H.264"
        else -> mime
    }

    /**
     * Strumień w zewnętrznym odtwarzaczu (VLC, Just Player, MX…): ACTION_VIEW z typem wideo, tytułem, pozycją startu
     * (konwencja MX / Just Player) i nagłówkami HTTP źródła; ekran odtwarzacza otwiera go przez okno wyboru aplikacji.
     */
    fun externalView(url: String, title: String, positionMs: Long, headers: Map<String, String>): Intent {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .putExtra("position", positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .putExtra("return_result", false)
        if (headers.isNotEmpty()) view.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        return view
    }

    // ---------------- język: polski dźwięk albo polskie napisy ----------------

    /** Polska ścieżka: kod języka pl / pol / pl-PL albo opis („polski”, „lektor”, „dubbing PL”). */
    fun isPolish(language: String?, label: String?): Boolean {
        val l = language.orEmpty().lowercase()
        if (l == "pl" || l == "pol" || l.startsWith("pl-") || l.startsWith("pl_")) return true
        val t = " " + label.orEmpty().lowercase() + " "
        return listOf("polsk", "polish", "lektor", " pl ", "[pl]", "(pl)", "dubbing pl").any { it in t }
    }

    /**
     * Plan języka dla źródła ([audio] / [text] – (język, etykieta) ścieżek): jest polski dźwięk → ta ścieżka i napisy wyłączone;
     * brak → napisy polskie: wbudowana polska ścieżka napisów ([LangPlan.embeddedText]) albo napisy z wtyczek ([LangPlan.addonSubs]).
     */
    /**
     * [polishAudio] – w źródle jest polski dźwięk (wybrana ścieżka [audio] albo – gdy plik nie opisuje języków – deklaracja
     * lektora / dubbingu w opisie źródła); [noPolishAudio] – źródło deklarowało polski dźwięk, a opisane ścieżki są w innych językach.
     */
    data class LangPlan(val audio: Int?, val subsOff: Boolean, val embeddedText: Int?, val addonSubs: Boolean,
                        val polishAudio: Boolean = audio != null, val noPolishAudio: Boolean = false,
                        /** (7.8) Gdy nie ma polskiego dźwięku ani polskich napisów: angielska ścieżka dźwięku / angielskie napisy (wbudowane). */
                        val englishAudio: Int? = null, val englishText: Int? = null)

    /** (7.8) Angielska ścieżka: kod en / eng / en-US albo opis („English”, „angielski”). */
    fun isEnglish(language: String?, label: String?): Boolean {
        val l = language.orEmpty().lowercase()
        if (l == "en" || l == "eng" || l.startsWith("en-") || l.startsWith("en_")) return true
        val t = " " + label.orEmpty().lowercase() + " "
        return listOf("english", "angielsk", " eng ", "[en]", "(en)").any { it in t }
    }

    /** (7.8) Angielskie napisy z wtyczki: „en”, „eng”, „English”. */
    fun isEnglishSubtitle(lang: String): Boolean = isEnglish(lang, null) || lang.trim().lowercase() == "english"

    /** Język nieopisany w pliku (brak, „und”, „unknown”). */
    fun undefinedLanguage(language: String?) = language.isNullOrBlank() || language.lowercase() in setOf("und", "unknown", "mis", "zxx", "mul")

    fun languagePlan(audio: List<Pair<String?, String?>>, text: List<Pair<String?, String?>>, sourceClaimsPolishAudio: Boolean = false): LangPlan {
        val pl = audio.indexOfFirst { (lang, label) -> isPolish(lang, label) }
        if (pl >= 0) return LangPlan(pl, subsOff = true, embeddedText = null, addonSubs = false)
        // (7.4) jedna / nieopisane ścieżki, a źródło to „lektor PL” – to polski dźwięk: bez napisów
        if (sourceClaimsPolishAudio && audio.all { (lang, _) -> undefinedLanguage(lang) })
            return LangPlan(null, subsOff = true, embeddedText = null, addonSubs = false, polishAudio = true)
        val t = text.indexOfFirst { (lang, label) -> isPolish(lang, label) }
        // (7.8) brak polskiego dźwięku i napisów – dalej angielski (dźwięk, potem napisy); polskie napisy z wtyczek mają pierwszeństwo (sprawdzane później)
        val ea = audio.indexOfFirst { (lang, label) -> isEnglish(lang, label) }.takeIf { it >= 0 }
        val et = text.indexOfFirst { (lang, label) -> isEnglish(lang, label) }.takeIf { it >= 0 }
        return LangPlan(null, subsOff = false, embeddedText = t.takeIf { it >= 0 }, addonSubs = t < 0,
            noPolishAudio = sourceClaimsPolishAudio && audio.isNotEmpty(), englishAudio = ea, englishText = et)
    }

    /** (7.4) Polskie napisy z wtyczki: „pl”, „pol”, „pl-PL”, „Polish” – nie „por” / „pob” (portugalski, dawny błąd prefiksu „po”). */
    fun isPolishSubtitle(lang: String): Boolean = isPolish(lang, null) || lang.trim().lowercase().let { it == "polish" || it == "polski" }
}

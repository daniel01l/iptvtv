package pl.iptv.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.android.asCoroutineDispatcher
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.QueueItem
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeQueue
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreamException
import pl.iptv.tv.data.YtStreams
import pl.iptv.tv.data.YtVideo
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Silnik odtwarzania STube – jedyny właściciel ExoPlayera, działający w [YtPlaybackService] (MediaSessionService).
 * Ekrany są wyłącznie klientami (MediaController, [YtClient]): zamknięcie ekranu, WSTECZ czy HOME nie zatrzymują dźwięku.
 * Tu dzieje się wszystko, co musi działać także bez ekranu: pobieranie strumieni, SponsorBlock, zapis postępu, wznawianie,
 * ponowna ekstrakcja przy wygasłym adresie, następny film z kolejki, korektor na sesji audio.
 */
object YtEngine {
    /** Zdarzenia dla ekranów (ten sam proces). Domyślne implementacje – nic. */
    interface Listener {
        fun onLoading(v: YtVideo) {}
        fun onLoaded(p: YtPlayable) {}
        /** Film nie do odtworzenia w aplikacji – ekran pokazuje okno awaryjne; true – obsłużone. */
        fun onError(reason: String): Boolean = false
        fun onSkipped(seg: SbSegment) {}
        /** Koniec filmu bez następnego w kolejce – true: ekran pokazuje propozycje (silnik nie zatrzymuje się). */
        fun onEnded(): Boolean = false
        /** Następny w kolejce jest kanał TV – true: ekran przełącza na telewizję. */
        fun onQueueChannel(q: QueueItem): Boolean = false
        fun onStopped() {}
    }

    const val START_TIMEOUT_MS = 25_000L
    const val SAVE_EVERY_MS = 10_000L

    var player: ExoPlayer? = null; private set
    @Volatile var video: YtVideo? = null; private set
    @Volatile var playable: YtPlayable? = null; private set
    var segments: List<SbSegment> = emptyList(); private set
    var lastSkipped: SbSegment? = null; private set
    var eq: YtEqSession? = null; private set
    /** Ostatni błąd bieżącego filmu (ekran otwarty po błędzie pokazuje okno awaryjne). */
    var error: String? = null; private set
    val live get() = playable is YtPlayable.Live
    /** Czy coś jest wczytane / odtwarzane (mini-player, sterowanie z telefonu). */
    val active get() = video != null && player != null
    /** Ekran bez powierzchni wideo – ścieżka obrazu wyłączona (sam dźwięk w tle, mniej pracy dekodera). */
    var background = false; private set
    /** (7.5) Tryb samego dźwięku (telefon) – do końca filmu / sesji; ekran po powrocie nie włącza obrazu. */
    @Volatile var audioOnly = false
    /** (7.9) Powtarzanie bieżącego utworu po jego naturalnym końcu. */
    @Volatile var repeatOne = false
    /** (7.9) Losowa kolejność: następny utwór z kolejki wybierany losowo. */
    @Volatile var shuffle = false
    /** Poprzednio odtworzone filmy (przycisk „Poprzedni”), najnowszy na końcu. */
    private val played = ArrayList<YtVideo>()
    val canGoBack get() = played.isNotEmpty()
    /** Identyfikatory ostatnio odtworzonych (propozycje ich nie powtarzają). */
    val recentIds: Set<String> get() = played.mapTo(HashSet()) { it.id }

    val listeners = CopyOnWriteArraySet<Listener>()
    /** Usługa: koniec odtwarzania → zatrzymanie usługi (powiadomienie znika). */
    var onStopRequest: (() -> Unit)? = null

    private lateinit var app: Context
    private var main = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + main.asCoroutineDispatcher())
    private var loadJob: Job? = null
    private var reExtracted = false
    private var ended = false
    private var ticks = 0

    /** Odtwarzacz (tworzony raz na życie usługi; dekodery jak w odtwarzaczu kanałów). */
    fun ensurePlayer(c: Context): ExoPlayer {
        app = c.applicationContext
        // wątek główny (testy Robolectric podmieniają Looper między testami – w aplikacji bez zmian)
        if (main.looper !== Looper.getMainLooper()) { main = Handler(Looper.getMainLooper()); scope = CoroutineScope(SupervisorJob() + main.asCoroutineDispatcher()) }
        player?.let { return it }
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(app).apply {
            setMediaCodecSelector(Playback.codecSelector(software = Playback.softwareDecoding))
            setEnableDecoderFallback(Prefs.decoderFallback || Playback.softwareDecoding)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }
        val exo = ExoPlayer.Builder(app, renderers)
            // własne zarządzanie fokusem audio i pauza po odłączeniu słuchawek / głośnika BT
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        player = exo
        attachEq(exo.audioSessionId)
        return exo
    }

    /** Metadane do powiadomienia i kontrolerów (tytuł, kanał, miniatura). */
    fun metadata(): MediaMetadata {
        val v = video
        return MediaMetadata.Builder().setTitle(playable?.title?.ifBlank { null } ?: v?.title)
            .setArtist(playable?.channel?.ifBlank { null } ?: v?.channel)
            .apply { v?.thumb?.takeIf { it.isNotBlank() }?.let { setArtworkUri(android.net.Uri.parse(it)) } }.build()
    }

    /**
     * Wczytanie filmu [v] (ten sam film już grający – bez zmian). [fromStart] – bez wznawiania z historii;
     * [resumeAtMs] > 0 – od tej chwili (ponowna ekstrakcja, polecenie).
     */
    fun load(c: Context, v: YtVideo, fromStart: Boolean = false, resumeAtMs: Long = 0, force: Boolean = false) {
        val exo = ensurePlayer(c)
        if (!force && video?.id == v.id && error == null && exo.playbackState != Player.STATE_IDLE && exo.playbackState != Player.STATE_ENDED) return
        saveProgress()
        loadJob?.cancel()
        main.removeCallbacks(startTimeout); main.removeCallbacks(tick)
        if (video?.id != v.id) { reExtracted = false; if (!goingBack) video?.let { prev -> played.removeAll { it.id == prev.id }; played += prev; if (played.size > 50) played.removeAt(0) } }
        video = v; error = null; ended = false; segments = emptyList(); lastSkipped = null
        playable = null
        Playback.stop()   // kanał IPTV (podgląd) – dwa odtwarzacze naraz to brak dekoderów na wielu TV
        listeners.forEach { it.onLoading(v) }
        loadJob = scope.launch {
            val sb = if (Prefs.sbEnabled) async { SponsorBlock.segments(v.id) } else null
            val p = try { YtStreams.resolve(v.id) } catch (e: YtStreamException) { fail(e.message ?: "Film niedostępny"); return@launch }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { fail(YtStreams.message(e)); return@launch }
            if (video?.id != v.id) return@launch
            if (p !is YtPlayable.Live) segments = sb?.await().orEmpty()
            // wznowienie od miejsca przerwania (jak w YouTube); „od początku” z menu kafelka
            var at = resumeAtMs
            if (at <= 0 && !fromStart && p !is YtPlayable.Live && Prefs.stubeResume) at = StubeHistory.resumeFrom(StubeHistory.progress(v.id))
            exo.trackSelectionParameters = trackParams(exo, p)
            exo.setMediaSource(YtMedia.source(app, p))
            if (at > 0 && p !is YtPlayable.Live) exo.seekTo(at)
            exo.setPlaybackSpeed(if (p is YtPlayable.Live) 1f else speed)
            exo.playWhenReady = true
            exo.prepare()
            resumedFrom = at
            playable = p   // stan „wczytany” dopiero po pełnej konfiguracji odtwarzacza
            listeners.forEach { it.onLoaded(p) }
            main.postDelayed(startTimeout, START_TIMEOUT_MS)
            main.post(tick)
        }
    }

    /** Pozycja, od której wznowiono bieżący film (komunikat „Wznawianie od…”). */
    var resumedFrom = 0L; private set

    /** Jakość ([Prefs.ytQuality]; 0 = automatycznie do limitu z Ustawień), dźwięk oryginalny, napisy wg ostatniego wyboru. */
    fun trackParams(p: Player, what: YtPlayable?): androidx.media3.common.TrackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .clearOverrides()
        .setMaxVideoSize(Int.MAX_VALUE, Prefs.ytQuality.takeIf { it > 0 } ?: YtStreams.maxHeight)
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, background)
        .apply {
            ((what as? YtPlayable.Split)?.audio?.lang)?.takeIf { it.isNotBlank() }?.let { setPreferredAudioLanguage(it) }
            if (Prefs.ytSubLang.isNotBlank()) setPreferredTextLanguage(Prefs.ytSubLang).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            else setPreferredTextLanguage(null)
        }.build()

    val speed get() = (Prefs.ytSpeedPct.takeIf { it in SPEEDS } ?: 100) / 100f
    val SPEEDS = listOf(50, 100, 125, 150, 200)

    /** Ekran schowany (HOME, inna aplikacja, menu STube): obraz wyłączony, dźwięk gra dalej; powrót – obraz włączony. */
    fun setBackground(on: Boolean) {
        background = on
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build()
    }

    /** Koniec odtwarzania (✖ w mini-playerze, telewizja na żywo, błąd bez ekranu): pozycja do historii, usługa kończy się. */
    fun stop() {
        if (video == null && player?.playbackState == Player.STATE_IDLE) { onStopRequest?.invoke(); return }
        saveProgress()
        loadJob?.cancel()
        main.removeCallbacks(startTimeout); main.removeCallbacks(tick)
        player?.let { it.stop(); it.clearMediaItems() }
        video = null; playable = null; segments = emptyList(); error = null; background = false; audioOnly = false
        played.clear(); repeatOne = false; shuffle = false
        listeners.forEach { it.onStopped() }
        onStopRequest?.invoke()
    }

    /** Zwolnienie odtwarzacza (koniec usługi). */
    fun releasePlayer() {
        stop()
        eq?.release(); eq = null
        player?.let { it.removeListener(listener); runCatching { it.release() } }
        player = null
    }

    fun saveProgress(): pl.iptv.tv.data.WatchProgress? {
        val p = player ?: return null
        val v = video ?: return null
        if (live || p.isCurrentMediaItemLive) return null
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (p.playbackState == Player.STATE_IDLE && !ended) return null
        return StubeHistory.savePosition(v, if (ended) dur else p.currentPosition, dur)
    }

    /** Pominięcie segmentu SponsorBlock pod [posMs] (co 0,5 s, także w tle). */
    fun skipIfNeeded(posMs: Long): Boolean {
        val p = player ?: return false
        val seg = SponsorBlock.at(segments, posMs) ?: return false
        val dur = p.duration
        lastSkipped = seg
        // segment do samego końca filmu – koniec zamiast przeskoku w pustkę
        if (dur != C.TIME_UNSET && seg.endMs >= dur - 1000) { p.playWhenReady = false; finished(); return true }
        p.seekTo(seg.endMs)
        listeners.forEach { it.onSkipped(seg) }
        return true
    }

    /** Następny materiał teraz (pilot, telefon, ⏭). */
    fun next() { if (video != null) finished(natural = false) }

    private var goingBack = false

    /** „Poprzedni”: po 3 s utworu – od początku, inaczej poprzednio odtworzony film (brak – od początku). */
    fun previous(c: Context) {
        val p = player ?: return
        val back = played.lastOrNull()
        if (p.currentPosition > 3_000 || back == null) { p.seekTo(0); return }
        played.removeAt(played.size - 1)
        goingBack = true; try { load(c, back, force = true) } finally { goingBack = false }
    }

    private fun attachEq(sessionId: Int) {
        eq?.release()
        eq = YtEqSession.create(sessionId).also { it.apply() }
    }

    private fun fail(reason: String) {
        error = reason
        main.removeCallbacks(startTimeout)
        player?.stop()
        // ekran otwarty – okno awaryjne (SmartTube / YouTube / Zamknij); bez ekranu – koniec gry w tle
        if (listeners.reversed().none { it.onError(reason) }) stop()
    }

    /** Koniec filmu: następny z kolejki (film – tutaj, kanał TV – ekran), inaczej propozycje na ekranie albo zatrzymanie. */
    private fun finished(natural: Boolean = true) {
        // powtarzanie utworu: naturalny koniec → od początku (ręczne „Następny” przechodzi dalej)
        if (natural && repeatOne && !live) { player?.let { it.seekTo(0); it.playWhenReady = true; it.play() }; return }
        ended = true; saveProgress()
        val cur = video?.id
        scope.launch {
            val next = if (Prefs.queueAutoplay) StubeQueue.pop(skipRef = cur, random = shuffle) else null
            when {
                next?.isVideo == true -> load(app, next.toVideo(), force = true)
                next != null -> { if (listeners.reversed().none { it.onQueueChannel(next) }) stop() }
                listeners.reversed().any { it.onEnded() } -> {}
                // bez ekranu (gra w tle): autoodtwarzanie – następna propozycja YouTube (jak w aplikacji YouTube)
                nextSuggestion(cur) != null && Prefs.ytAutoplay -> load(app, nextSuggestion(cur)!!, force = true)
                else -> stop()
            }
        }
    }

    /** Następna propozycja (bez składanek, bez ostatnio odtworzonych, wykonawcy przeplatani) – autoodtwarzanie. */
    fun nextSuggestion(cur: String?): YtVideo? {
        val p = playable ?: return null
        val v = video
        val recent = played.mapTo(HashSet()) { it.id }
        return pl.iptv.tv.data.StubeMixes.suggest(p.related, cur.orEmpty(), p.title.ifBlank { v?.title.orEmpty() }, p.channel.ifBlank { v?.channel.orEmpty() }, recent).firstOrNull()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { main.removeCallbacks(startTimeout); error = null }
            if (state == Player.STATE_ENDED && video != null && !ended) finished(natural = true)
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEq(audioSessionId) }
        override fun onPlayerError(e: PlaybackException) {
            android.util.Log.w("YtEngine", "błąd ${e.errorCodeName}", e)
            val v = video ?: return
            val http = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            // adres strumienia wygasł lub został odrzucony (403/410) – raz ponowna ekstrakcja od tej samej chwili
            if (!reExtracted && (http == 403 || http == 410 || e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
                reExtracted = true
                load(app, v, resumeAtMs = player?.currentPosition ?: 0, force = true); return
            }
            fail(errorMessage(e, http))
        }
    }

    private val startTimeout = Runnable {
        if (player?.playbackState != Player.STATE_READY && video != null && error == null)
            fail("Strumień nie odpowiada (YouTube mógł zablokować odtwarzanie w tej aplikacji)")
    }

    /** Co 0,5 s: SponsorBlock; co [SAVE_EVERY_MS]: zapis postępu. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (video == null) return
            if (p.isPlaying || p.playbackState == Player.STATE_BUFFERING) skipIfNeeded(p.currentPosition)
            if (p.isPlaying && ++ticks % (SAVE_EVERY_MS / 500).toInt() == 0) saveProgress()
            main.postDelayed(this, 500)
        }
    }

    fun errorMessage(e: PlaybackException, http: Int = 0): String = when {
        http == 403 || http == 410 -> "YouTube odrzucił strumień (HTTP $http)"
        http == 429 -> "YouTube ograniczył odtwarzanie (zbyt wiele zapytań)"
        e.errorCode in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) ->
            "Urządzenie nie obsługuje formatu tego filmu"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Brak połączenia z serwerem wideo YouTube"
        else -> "Błąd odtwarzania (${e.errorCodeName})"
    }
}

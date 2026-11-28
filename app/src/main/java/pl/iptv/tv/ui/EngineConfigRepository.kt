package pl.iptv.tv.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.iptv.tv.data.ChannelEngine
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs

/**
 * (7.22) Maksymalna rozdzielczość obrazu Live TV ([Prefs.maxVideoRes]): limit wyboru ścieżki obrazu w strumieniach
 * wielojakościowych (`TrackSelectionParameters.setMaxVideoSize`). [AUTO] – bez limitu (`clearVideoSizeConstraints`), jak dotąd.
 */
enum class VideoResolutionLimit(val label: String, val description: String, val maxWidth: Int, val maxHeight: Int) {
    AUTO("Automatyczna", "Najlepsza jakość, jaką obsługuje urządzenie – jak dotąd", Int.MAX_VALUE, Int.MAX_VALUE),
    UHD_4K("4K (2160p)", "Do 3840×2160", 3840, 2160),
    FHD_1080P("Full HD (1080p)", "Do 1920×1080 – bez 4K", 1920, 1080),
    HD_720P("HD (720p)", "Do 1280×720 – mniej danych, lżejszy dekoder", 1280, 720),
    SD_480P("SD (480p)", "Do 854×480 – słabe łącze", 854, 480);

    val limited: Boolean get() = this != AUTO

    companion object {
        fun of(i: Int): VideoResolutionLimit = entries.getOrElse(i) { AUTO }
    }
}

/** (7.20) Skąd pochodzi wartość ustawienia silnika: ustawienie globalne albo zapisane tylko dla bieżącego kanału. */
enum class ConfigSource(val label: String) { GLOBAL("Globalne"), CHANNEL("Dla tego kanału") }

/**
 * (7.20) Ustawienia silnika odtwarzania Live TV w jednym miejscu: globalne (Ustawienia → Odtwarzanie → Silnik odtwarzania
 * i akceleracja) + zapisany profil bieżącego kanału ([channel] – „Zapamiętane silniki kanałów”). Pola „efektywne” mówią,
 * czego odtwarzacz faktycznie użyje, a `*Source` – czy wynika to z ustawienia globalnego, czy z profilu kanału.
 */
data class PlayerEngineConfig(
    val engine: Int, val hwAccel: Boolean, val asyncDecoder: Int, val surfaceMode: Int, val autoFrameRate: Boolean,
    val bufMinS: Int, val lowLatency: Boolean, val loudness: Boolean, val lowPerf: Boolean, val decoderFallback: Boolean,
    /** (7.22) „Automatyczna korekta przy zacinaniu obrazu” – strażnik klatkowania; false – tylko ręczne ustawienia. */
    val autoStutterCorrectionEnabled: Boolean = true,
    val channelKey: String? = null, val channel: ChannelEngine? = null,
    /** (7.22) Limit rozdzielczości obrazu (ustawiany w locie na odtwarzaczu – bez przebudowy, poza [playerKey]). */
    val maxVideoResolution: VideoResolutionLimit = VideoResolutionLimit.AUTO,
    /** (7.28) Ręczne ustawienia silnika bieżącego kanału (okno „Silnik kanału”); pola wyżej są już efektywne (nadpisanie albo globalne). */
    val channelOverride: pl.iptv.tv.data.ChannelEngineConfig? = null,
    /** (7.28) Same ustawienia globalne, gdy kanał ma nadpisanie ([global]). */
    val globalBase: PlayerEngineConfig? = null,
) {
    /** Profil kanału (null – brak albo kanał oznaczony jako niedziałający). */
    val channelProfile: EngineProfile? get() = channel?.profile
    private val globalSoftware get() = engine == Playback.ENGINE_SOFTWARE || !hwAccel

    /** Dekoder obrazu programowy (globalnie albo z profilu kanału). */
    val software: Boolean get() = globalSoftware || channelProfile?.software == true
    val softwareSource: ConfigSource get() = if (!globalSoftware && channelProfile?.software == true) ConfigSource.CHANNEL else ConfigSource.GLOBAL
    /** Tunelowanie – tylko bez profilu kanału (profil wyłącza tunelowanie). */
    val tunneling: Boolean get() = engine == Playback.ENGINE_TUNNELING && hwAccel && channelProfile == null
    /** Efektywna kolejka dekodera (0 – automatycznie, 1 – asynchroniczna, 2 – synchroniczna). */
    val effectiveAsync: Int get() = channelProfile?.async?.takeIf { it != 0 } ?: asyncDecoder
    val asyncSource: ConfigSource get() = if (channelProfile?.async?.let { it != 0 } == true) ConfigSource.CHANNEL else ConfigSource.GLOBAL
    val surface: RenderSurfaceMode get() = RenderSurfaceMode.entries.getOrElse(surfaceMode) { RenderSurfaceMode.AUTO }
    /** Obraz na TextureView (wg [surface] i profilu kanału). */
    val textureView: Boolean get() = surface.texture(channelProfile?.surface == 1)
    /** (7.22) Powierzchnia zawsze z ustawienia globalnego (AUTO = SurfaceView). */
    val surfaceSource: ConfigSource get() = ConfigSource.GLOBAL
    /** Duży bufor z profilu kanału (start 3 s / po przycięciu 8 s) zamiast „Bufor – minimum”. */
    val bufferSource: ConfigSource get() = if (channelProfile?.buffer == 1) ConfigSource.CHANNEL else ConfigSource.GLOBAL
    val ffmpegAudio: Boolean get() = channelProfile?.ffmpeg == true

    /** Kanał ma zapisane własne ustawienia silnika. */
    val hasChannelOverride: Boolean get() = channel != null

    /** Same ustawienia globalne (Ustawienia obserwują tylko je – zmiana kanału w tle nie przebudowuje ekranu). */
    val global: PlayerEngineConfig get() = (globalBase ?: this).copy(channelKey = null, channel = null, channelOverride = null, globalBase = null)

    /**
     * Parametry, z których powstaje odtwarzacz (dekodery, renderery, kolejka, bufor, tunelowanie) – ich zmiana wymaga nowego
     * ExoPlayera. Bez powierzchni obrazu (podmiana widoku), AFR (strategia ustawiana w locie) i głośności (efekt na sesji).
     */
    val playerKey: List<Any?> get() = listOf(engine, hwAccel, asyncDecoder, bufMinS, lowLatency, lowPerf, decoderFallback,
        // sam rodzaj strumienia / powierzchnia = brak profilu (jak [Playback.sameEngine])
        channelProfile?.copy(container = 0, surface = 0)?.takeIf { it != EngineProfile() })
}

/**
 * (7.20) Jedno źródło prawdy ustawień silnika Live TV. Zapis wartości idzie do [Prefs] (trwałość), a każdy zapis kluczy
 * [Prefs.ENGINE_KEYS] – także z innych miejsc (profile wydajności, testy) – i każda zmiana [ChannelEngines] odświeża
 * [effectiveConfig]. Obserwują go: ekran Ustawień, okno silnika w odtwarzaczu ([EngineSheet]) i [Playback] (przebudowa
 * odtwarzacza w locie – [Playback.reloadIfNeeded]). Obiekt aplikacji (singleton).
 */
object EngineConfigRepository {
    private val state = MutableStateFlow(read(null))
    /** Ustawienia globalne połączone z profilem bieżącego kanału ([bindChannel]). */
    val effectiveConfig: StateFlow<PlayerEngineConfig> = state.asStateFlow()
    val current: PlayerEngineConfig get() = state.value

    // (7.22) zadeklarowane przed `init`: migracja w init wywołuje [refresh] (przez ChannelEngines.onChanged) – dawniej lista
    // obserwatorów była jeszcze null (NullPointerException połykany przez runCatching, migracja przerywana w połowie)
    @Volatile var channelKey: String? = null; private set
    private val observers = java.util.concurrent.CopyOnWriteArrayList<(PlayerEngineConfig) -> Unit>()

    init {
        Prefs.onEngineChanged = { refresh() }
        ChannelEngines.onChanged = { refresh() }
        pl.iptv.tv.data.ChannelEngineOverrides.onChanged = { refresh() }      // (7.28) zapis / usunięcie ustawień kanału – zastosowanie w locie
        // (7.22) przy starcie: kanały HD bez automatycznie zapisanego dekodera programowego (pełna płynność sprzętowa).
        // Odczyt Prefs + zapis bazy poza wątkiem UI (Dispatchers.IO, try-catch) – błąd I/O nie blokuje ani nie kończy startu;
        // w Robolectric od razu (deterministyczne testy)
        if (android.os.Build.FINGERPRINT == "robolectric") runCatching { ChannelEngines.clearSoftwareForHd() }
        else runCatching {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try { ChannelEngines.clearSoftwareForHd() } catch (t: Throwable) {
                    android.util.Log.w("EngineConfig", "migracja profili HD: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun read(key: String?) = runCatching {
        val base = PlayerEngineConfig(Prefs.playerEngine.coerceIn(0, 2), Prefs.hwAccel, Prefs.asyncDecoder.coerceIn(0, 2), Prefs.surfaceMode.coerceIn(0, 2),
            Prefs.autoFrameRate, Prefs.bufMinS, Prefs.lowLatency, Prefs.loudnessLeveling, Prefs.lowPerf, Prefs.decoderFallback, Prefs.dropWatchdog,
            key, key?.let { ChannelEngines.get(it) }, VideoResolutionLimit.of(Prefs.maxVideoRes))
        // (7.28) priorytet: ręczne ustawienia kanału ([pl.iptv.tv.data.ChannelEngineOverrides]) → globalne → automatyczny dobór
        // (profil [ChannelEngines] dalej działa w obrębie silnika); AFR zawsze globalny (bez zmiany trybu HDMI przy zmianie kanału)
        val o = if (key != null) pl.iptv.tv.data.ChannelEngineOverrides.active else null
        if (o == null) base else base.copy(engine = o.engine, hwAccel = o.hwAccel, asyncDecoder = o.asyncDecoder, surfaceMode = o.surfaceMode,
            bufMinS = o.bufMinS, lowLatency = o.lowLatency, loudness = o.loudness, decoderFallback = o.decoderFallback,
            autoStutterCorrectionEnabled = o.autoStutter, maxVideoResolution = VideoResolutionLimit.of(o.maxVideoRes),
            channelOverride = o, globalBase = base)
    }.getOrElse { PlayerEngineConfig(0, true, 0, 0, false, 0, false, false, false, true, true, key, null) }   // Prefs przed init (testy JVM)

    /** Ponowny odczyt (po zapisie ustawień lub profilu kanału). */
    fun refresh() {
        val old = state.value; val now = read(channelKey)
        state.value = now
        if (now != old) observers.forEach { runCatching { it(now) } }
    }

    /**
     * Obserwacja [effectiveConfig] bez korutyny (silnik odtwarzania – obiekt na cały czas działania aplikacji): każda nowa
     * wartość wywołuje [o] od razu w wątku zapisu. Ekrany zbierają [effectiveConfig] w swoim cyklu życia.
     */
    fun observe(o: (PlayerEngineConfig) -> Unit) { observers += o }

    /** Bieżący kanał odtwarzacza ([Playback.play]) – jego profil wchodzi do [effectiveConfig]. */
    fun bindChannel(key: String?) { if (key != channelKey || state.value.channelKey != key) { channelKey = key; refresh() } }

    // ---- ustawienia globalne (te same metody dla Ustawień i okna w odtwarzaczu) ----

    fun setEngine(e: Int) {
        Prefs.playerEngine = e.coerceIn(0, 2)
        // tunelowanie wymaga dekodera sprzętowego i asynchronicznej kolejki
        if (e == Playback.ENGINE_TUNNELING) { Prefs.hwAccel = true; Prefs.asyncDecoder = 1 }
    }
    fun cycleEngine() = setEngine((current.engine + 1) % Playback.ENGINES.size)
    fun setHwAccel(on: Boolean) {
        Prefs.hwAccel = on
        if (!on && Prefs.playerEngine == Playback.ENGINE_TUNNELING) Prefs.playerEngine = Playback.ENGINE_HARDWARE
    }
    fun setAsyncDecoder(v: Int) {
        Prefs.asyncDecoder = v.coerceIn(0, 2)
        if (v != 1 && Prefs.playerEngine == Playback.ENGINE_TUNNELING) Prefs.playerEngine = Playback.ENGINE_HARDWARE
    }
    fun setSurfaceMode(m: RenderSurfaceMode) { Prefs.surfaceMode = m.ordinal }
    fun setAutoFrameRate(on: Boolean) { Prefs.autoFrameRate = on }
    fun setBufMin(s: Int) { Prefs.bufMinS = s }
    fun setLowLatency(on: Boolean) { Prefs.lowLatency = on }
    fun setLoudness(on: Boolean) { Prefs.loudnessLeveling = on }
    fun setDecoderFallback(on: Boolean) { Prefs.decoderFallback = on }
    /** (7.22) Strażnik klatkowania włączony / wyłączony (Ustawienia i okno silnika w odtwarzaczu). */
    fun setAutoStutterCorrection(on: Boolean) { Prefs.dropWatchdog = on }
    /** (7.22) Maksymalna rozdzielczość obrazu (Ustawienia i okno silnika w odtwarzaczu) – odtwarzacz stosuje ją w locie. */
    fun setMaxVideoResolution(v: VideoResolutionLimit) { Prefs.maxVideoRes = v.ordinal }
    fun cycleMaxVideoResolution() = setMaxVideoResolution(VideoResolutionLimit.entries[(current.maxVideoResolution.ordinal + 1) % VideoResolutionLimit.entries.size])

    // ---- nadpisania bieżącego kanału ----

    /** Zapis profilu kanału („Zapamiętane silniki kanałów”); null – reset kanału do ustawień globalnych. */
    fun setChannelProfile(key: String, url: String, p: EngineProfile?) {
        if (p == null || p == EngineProfile()) resetChannel(key, url) else ChannelEngines.save(key, url, p)
    }

    fun resetChannel(key: String, url: String) = ChannelEngineOptimizer.resetChannel(key, url)

    /** Tryb zgodności kanału (dekoder programowy + TextureView, bez tunelowania) – włączenie / wyłączenie. */
    fun setChannelCompatibility(key: String, url: String, on: Boolean) {
        val base = ChannelEngines.get(key)?.profile ?: EngineProfile()
        setChannelProfile(key, url, if (on) base.copy(software = true, surface = 1) else base.copy(software = false, surface = 0))
    }

    /**
     * „Zastosuj globalnie”: ustawienia kanału przeniesione do ustawień globalnych (dekoder programowy → silnik programowy,
     * kolejka, TextureView → powierzchnia TextureView, duży bufor → bufor 3 s), profil kanału usunięty. Dźwięk FFmpeg i rodzaj
     * strumienia nie mają odpowiednika globalnego – zostają tylko w profilu i po przeniesieniu znikają.
     */
    fun applyChannelGlobally(key: String, url: String) {
        val p = ChannelEngines.get(key)?.profile ?: return
        if (p.software) setEngine(Playback.ENGINE_SOFTWARE)
        if (p.async != 0) setAsyncDecoder(p.async)
        if (p.surface == 1) setSurfaceMode(RenderSurfaceMode.TEXTURE_VIEW)
        if (p.buffer == 1) setBufMin(3)
        resetChannel(key, url)
    }
}

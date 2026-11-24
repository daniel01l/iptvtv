package pl.iptv.tv.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.iptv.tv.data.ChannelEngine
import pl.iptv.tv.data.ChannelEngines
import pl.iptv.tv.data.EngineProfile
import pl.iptv.tv.data.Prefs

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
    val channelKey: String? = null, val channel: ChannelEngine? = null,
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
    val surfaceSource: ConfigSource get() = if (surface == RenderSurfaceMode.AUTO && channelProfile?.surface == 1) ConfigSource.CHANNEL else ConfigSource.GLOBAL
    /** Duży bufor z profilu kanału (start 3 s / po przycięciu 8 s) zamiast „Bufor – minimum”. */
    val bufferSource: ConfigSource get() = if (channelProfile?.buffer == 1) ConfigSource.CHANNEL else ConfigSource.GLOBAL
    val ffmpegAudio: Boolean get() = channelProfile?.ffmpeg == true

    /** Kanał ma zapisane własne ustawienia silnika. */
    val hasChannelOverride: Boolean get() = channel != null

    /** Same ustawienia globalne (Ustawienia obserwują tylko je – zmiana kanału w tle nie przebudowuje ekranu). */
    val global: PlayerEngineConfig get() = copy(channelKey = null, channel = null)

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

    init {
        Prefs.onEngineChanged = { refresh() }
        ChannelEngines.onChanged = { refresh() }
    }

    @Volatile var channelKey: String? = null; private set

    private fun read(key: String?) = runCatching {
        PlayerEngineConfig(Prefs.playerEngine.coerceIn(0, 2), Prefs.hwAccel, Prefs.asyncDecoder.coerceIn(0, 2), Prefs.surfaceMode.coerceIn(0, 2),
            Prefs.autoFrameRate, Prefs.bufMinS, Prefs.lowLatency, Prefs.loudnessLeveling, Prefs.lowPerf, Prefs.decoderFallback,
            key, key?.let { ChannelEngines.get(it) })
    }.getOrElse { PlayerEngineConfig(0, true, 0, 0, false, 0, false, false, false, true, key, null) }   // Prefs przed init (testy JVM)

    /** Ponowny odczyt (po zapisie ustawień lub profilu kanału). */
    fun refresh() {
        val old = state.value; val now = read(channelKey)
        state.value = now
        if (now != old) observers.forEach { runCatching { it(now) } }
    }

    private val observers = java.util.concurrent.CopyOnWriteArrayList<(PlayerEngineConfig) -> Unit>()
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

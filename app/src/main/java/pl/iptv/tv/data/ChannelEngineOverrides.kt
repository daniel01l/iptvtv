package pl.iptv.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * (7.28) Ręczne ustawienia silnika i akceleracji dla jednego kanału – te same pola co Ustawienia → Odtwarzanie → „Silnik
 * odtwarzania i akceleracja” (bez AFR: zmiana trybu HDMI zostaje wyłącznie globalna – bez wygaszania ekranu przy zmianie kanału).
 */
data class ChannelEngineConfig(
    val engine: Int, val hwAccel: Boolean, val asyncDecoder: Int, val surfaceMode: Int, val bufMinS: Int,
    val lowLatency: Boolean, val loudness: Boolean, val decoderFallback: Boolean, val autoStutter: Boolean, val maxVideoRes: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("e", engine).put("hw", hwAccel).put("a", asyncDecoder).put("s", surfaceMode).put("b", bufMinS)
        .put("ll", lowLatency).put("ld", loudness).put("df", decoderFallback).put("st", autoStutter).put("mr", maxVideoRes)

    companion object {
        fun fromJson(o: JSONObject) = ChannelEngineConfig(o.optInt("e").coerceIn(0, 2), o.optBoolean("hw", true), o.optInt("a").coerceIn(0, 2),
            o.optInt("s").coerceIn(0, 2), o.optInt("b"), o.optBoolean("ll"), o.optBoolean("ld"), o.optBoolean("df", true), o.optBoolean("st", true),
            o.optInt("mr").coerceAtLeast(0))

        /** Bieżące ustawienia globalne (punkt wyjścia okna „Silnik kanału”, gdy kanał nie ma własnych). */
        fun global() = ChannelEngineConfig(Prefs.playerEngine.coerceIn(0, 2), Prefs.hwAccel, Prefs.asyncDecoder.coerceIn(0, 2),
            Prefs.surfaceMode.coerceIn(0, 2), Prefs.bufMinS, Prefs.lowLatency, Prefs.loudnessLeveling, Prefs.decoderFallback,
            Prefs.dropWatchdog, Prefs.maxVideoRes)
    }
}

/**
 * (7.28) Magazyn nadpisań silnika per kanał: mapa {klucz kanału → [ChannelEngineConfig]} w `Prefs.channelEngineOverrides` (JSON),
 * wczytywana raz do pamięci, zapis w tle (Dispatchers.IO). Klucz ([keyFor]): `tvg-id` (gdy niepusty), inaczej znormalizowany
 * adres strumienia, inaczej nazwa – zapis dla tego samego kanału zawsze zastępuje poprzedni (bez duplikatów).
 *
 * Kolejność w odtwarzaczu: 1) ręczne ustawienie kanału ([active]) → 2) Ustawienia globalne → 3) automatyczny dobór
 * ([ChannelEngines] – profil kanału nadal działa w obrębie wybranego silnika). Zmiana kanału kosztuje jedno wyszukanie w mapie;
 * odtwarzacz jest przebudowywany tylko, gdy efektywne parametry budowy się różnią ([pl.iptv.tv.ui.PlayerEngineConfig.playerKey]).
 */
object ChannelEngineOverrides {
    private val map = ConcurrentHashMap<String, ChannelEngineConfig>()
    @Volatile private var loaded = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Nadpisanie kanału, który właśnie gra ([bind] z `Playback.play`); null – ustawienia globalne. */
    @Volatile var active: ChannelEngineConfig? = null; private set
    @Volatile var activeKey: String? = null; private set
    /** Po zapisie / usunięciu (odświeżenie [pl.iptv.tv.ui.EngineConfigRepository]). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun keyFor(ch: Channel): String {
        ch.tvgId.trim().takeIf { it.isNotEmpty() }?.let { return "id:" + it.lowercase() }
        val u = ch.url.trim().lowercase().substringBefore('#').removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (u.isNotEmpty()) return "url:$u"
        return "name:" + ch.name.trim().lowercase()
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val s = Prefs.channelEngineOverrides
                if (s.isNotBlank()) { val o = JSONObject(s); o.keys().forEach { k -> o.optJSONObject(k)?.let { map[k] = ChannelEngineConfig.fromJson(it) } } }
            }
            loaded = true
        }
    }

    fun get(ch: Channel): ChannelEngineConfig? { load(); return map[keyFor(ch)] }
    fun has(ch: Channel) = get(ch) != null

    /** Kanał odtwarzacza (wywoływane przy każdym starcie / przełączeniu – tylko odczyt z pamięci). */
    fun bind(ch: Channel?) {
        load()
        val k = ch?.let(::keyFor)
        activeKey = k; active = k?.let { map[it] }
    }

    /** Zapis dla kanału – zastępuje poprzedni wpis tego kanału. */
    fun put(ch: Channel, cfg: ChannelEngineConfig) {
        load(); val k = keyFor(ch); map[k] = cfg
        if (k == activeKey) active = cfg
        persist(); onChanged?.invoke()
    }

    /** „Przywróć domyślne” – usunięcie nadpisania kanału (wracają ustawienia globalne). */
    fun remove(ch: Channel) {
        load(); val k = keyFor(ch)
        if (map.remove(k) == null) return
        if (k == activeKey) active = null
        persist(); onChanged?.invoke()
    }

    private fun persist() {
        val snapshot = HashMap(map)
        val write = { runCatching { Prefs.channelEngineOverrides = JSONObject().apply { snapshot.forEach { (k, v) -> put(k, v.toJson()) } }.toString() } }
        if (android.os.Build.FINGERPRINT == "robolectric") write() else io.launch { write() }
    }

    fun resetForTest() { map.clear(); loaded = false; active = null; activeKey = null }
}

/**
 * (7.28) Efektywne ustawienia silnika Live TV dla budowy odtwarzacza ([pl.iptv.tv.ui.Playback]): nadpisanie bieżącego kanału
 * ([ChannelEngineOverrides.active]) albo wartość globalna z [Prefs]. Te same nazwy co w [Prefs] (podmiana `val P = Prefs`).
 * AFR i parametry trybu słabszych urządzeń – zawsze globalne.
 */
object EngineEff {
    private val o get() = ChannelEngineOverrides.active
    val playerEngine: Int get() = o?.engine ?: Prefs.playerEngine
    val hwAccel: Boolean get() = o?.hwAccel ?: Prefs.hwAccel
    val asyncDecoder: Int get() = o?.asyncDecoder ?: Prefs.asyncDecoder
    val surfaceMode: Int get() = o?.surfaceMode ?: Prefs.surfaceMode
    val bufMinS: Int get() = o?.bufMinS ?: Prefs.bufMinS
    val lowLatency: Boolean get() = o?.lowLatency ?: Prefs.lowLatency
    val loudnessLeveling: Boolean get() = o?.loudness ?: Prefs.loudnessLeveling
    val decoderFallback: Boolean get() = o?.decoderFallback ?: Prefs.decoderFallback
    val dropWatchdog: Boolean get() = o?.autoStutter ?: Prefs.dropWatchdog
    val maxVideoRes: Int get() = o?.maxVideoRes ?: Prefs.maxVideoRes
    // zawsze globalne
    val autoFrameRate: Boolean get() = Prefs.autoFrameRate
    val lowPerf: Boolean get() = Prefs.lowPerf
    val lowBufMinS: Int get() = Prefs.lowBufMinS
    val lowBufMaxS: Int get() = Prefs.lowBufMaxS
    val lowBufMb: Int get() = Prefs.lowBufMb
    val lowMaxHeight: Int get() = Prefs.lowMaxHeight
}

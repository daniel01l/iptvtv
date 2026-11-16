package pl.iptv.tv.ui

import android.media.audiofx.Equalizer
import pl.iptv.tv.data.Prefs

/**
 * Korektor dźwięku odtwarzacza STube (android.media.audiofx.Equalizer na sesji audio ExoPlayera).
 * Presety są opisane krzywą wzmocnienia w funkcji częstotliwości – działają z każdą liczbą pasm urządzenia
 * (zwykle 5: 60 Hz, 230 Hz, 910 Hz, 3,6 kHz, 14 kHz). „Własny” – poziomy pasm ustawione ◄► w panelu ([Prefs.ytEqCustom]).
 */
object YtEq {
    const val FLAT = 0; const val MOVIE = 1; const val BASS = 2; const val MUSIC = 3; const val NIGHT = 4; const val CUSTOM = 5
    val PRESETS = listOf("Płaski", "Film / Dialogi", "Bass Boost", "Muzyka", "Tryb nocny", "Własny")
    /** Krok regulacji pasma pilotem: 1 dB. */
    const val STEP_MB = 100

    /**
     * Wzmocnienie presetu dla pasma o środku [hz] (dB). Film / Dialogi – wyraźniejsze głosy (1–5 kHz), mniej dudnienia;
     * Bass Boost – niskie tony; Muzyka – „V” (bas i góra); Tryb nocny – ciche wybuchy, czytelne dialogi.
     */
    fun presetDb(preset: Int, hz: Int): Int = when (preset) {
        MOVIE -> when { hz < 100 -> -3; hz < 250 -> -1; hz < 800 -> 0; hz < 1500 -> 2; hz < 5000 -> 4; hz < 9000 -> 2; else -> 0 }
        BASS -> when { hz < 80 -> 7; hz < 160 -> 6; hz < 320 -> 4; hz < 640 -> 1; else -> 0 }
        MUSIC -> when { hz < 120 -> 4; hz < 500 -> 1; hz < 2000 -> -1; hz < 6000 -> 2; else -> 4 }
        NIGHT -> when { hz < 120 -> -5; hz < 300 -> -2; hz < 1000 -> 1; hz < 5000 -> 3; else -> 1 }
        else -> 0
    }

    /** Poziomy pasm (mB) dla presetu w zakresie urządzenia [min]..[max]; „Własny” – zapisane wartości (brakujące = 0). */
    fun levels(preset: Int, centersHz: List<Int>, min: Int, max: Int, custom: List<Int> = Prefs.ytEqCustom): List<Int> =
        centersHz.mapIndexed { i, hz ->
            val mb = if (preset == CUSTOM) custom.getOrElse(i) { 0 } else presetDb(preset, hz) * 100
            mb.coerceIn(min, max)
        }

    /** „60 Hz”, „3,6 kHz”, „14 kHz”. */
    fun hzLabel(hz: Int): String = when {
        hz < 1000 -> "$hz Hz"
        hz < 10_000 && hz % 1000 != 0 -> "%.1f kHz".format(java.util.Locale("pl", "PL"), hz / 1000f)
        else -> "${hz / 1000} kHz"
    }

    /** „+3 dB”, „0 dB”, „−2 dB”. */
    fun dbLabel(mb: Int): String = (mb / 100).let { if (it > 0) "+$it dB" else if (it < 0) "−${-it} dB" else "0 dB" }
}

/**
 * Korektor przypięty do sesji audio odtwarzacza. Urządzenie bez efektu audio (część TV/emulatorów) – [available] = false,
 * odtwarzanie działa normalnie. Zwolnienie razem z odtwarzaczem ([release]).
 */
class YtEqSession(private val eq: Device?) {
    /** Efekt korektora urządzenia (android.media.audiofx.Equalizer; w testach – atrapa). */
    interface Device {
        val bands: Int
        /** Zakres poziomu pasma (mB). */
        val range: IntRange
        fun centerHz(band: Int): Int
        fun level(band: Int): Int
        fun setLevel(band: Int, mb: Int)
        var enabled: Boolean
        fun release()
    }

    private class AndroidDevice(private val e: Equalizer) : Device {
        override val bands = e.numberOfBands.toInt()
        override val range = e.bandLevelRange.let { it[0].toInt()..it[1].toInt() }
        override fun centerHz(band: Int) = e.getCenterFreq(band.toShort()) / 1000
        override fun level(band: Int) = e.getBandLevel(band.toShort()).toInt()
        override fun setLevel(band: Int, mb: Int) { e.setBandLevel(band.toShort(), mb.toShort()) }
        override var enabled: Boolean get() = e.enabled; set(v) { e.enabled = v }
        override fun release() = e.release()
    }

    /** Korektor na sesji audio [sessionId]; brak efektu na urządzeniu – sesja bez korektora. */
    constructor(sessionId: Int) : this(if (sessionId <= 0) null else runCatching<Device> { AndroidDevice(Equalizer(0, sessionId)) }.getOrNull())

    val available get() = eq != null && bands > 0
    val bands: Int = runCatching { eq?.bands }.getOrNull() ?: 0
    val min: Int = runCatching { eq?.range?.first }.getOrNull() ?: -1500
    val max: Int = runCatching { eq?.range?.last }.getOrNull() ?: 1500
    /** Środki pasm w Hz. */
    val centersHz: List<Int> = (0 until bands).map { b -> runCatching { eq!!.centerHz(b) }.getOrDefault(0) }
    val enabled get() = runCatching { eq?.enabled }.getOrNull() ?: false

    /** Bieżące poziomy pasm (mB). */
    fun current(): List<Int> = (0 until bands).map { b -> runCatching { eq!!.level(b) }.getOrDefault(0) }

    /** Preset z Ustawień; „Płaski” – efekt wyłączony (zero kosztu przetwarzania). */
    fun apply(preset: Int = Prefs.ytEqPreset) {
        val e = eq ?: return
        runCatching {
            YtEq.levels(preset, centersHz, min, max).forEachIndexed { b, mb -> e.setLevel(b, mb) }
            e.enabled = preset != YtEq.FLAT
        }
    }

    /** Ręczna zmiana pasma – preset „Własny” z bieżącymi poziomami pozostałych pasm. */
    fun setBand(band: Int, mb: Int): List<Int> {
        val e = eq ?: return emptyList()
        val lv = current().toMutableList().also { if (band in it.indices) it[band] = mb.coerceIn(min, max) }
        runCatching { e.setLevel(band, lv[band]); e.enabled = true }
        Prefs.ytEqCustom = lv; Prefs.ytEqPreset = YtEq.CUSTOM
        return lv
    }

    fun release() { runCatching { eq?.release() } }

    companion object {
        /** Testy: fabryka korektora zamiast efektu urządzenia. */
        @Volatile var factory: ((Int) -> YtEqSession)? = null
        fun create(sessionId: Int): YtEqSession = factory?.invoke(sessionId) ?: YtEqSession(sessionId)
    }
}

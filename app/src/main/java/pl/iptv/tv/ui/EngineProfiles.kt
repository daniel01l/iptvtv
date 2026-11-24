package pl.iptv.tv.ui

import pl.iptv.tv.data.Prefs

/**
 * (7.17) Szybkie profile wydajności Live TV (pigułki na górze sekcji „Silnik odtwarzania i akceleracja”): jedno kliknięcie
 * ustawia parametry silnika; aktywny profil jest wykrywany z bieżących ustawień ([active]) – ręczna zmiana któregokolwiek
 * parametru daje [CUSTOM] („Własny (Zmodyfikowany)”).
 */
object EngineProfiles {
    enum class Profile(val label: String) {
        AUTO("Automatyczny (Zalecany)"),
        FAST("Szybkie przełączanie (Sport / Live TV)"),
        STABLE("Maksymalna stabilność"),
        LOW("Tryb dla słabszych urządzeń"),
    }
    const val CUSTOM = "Własny (Zmodyfikowany)"

    /** Parametry profilu (te same pola porównuje [active]). */
    data class Values(
        val lowPerf: Boolean, val bufMinS: Int, val cleanDecoder: Boolean, val zapDelayMs: Int,
        val asyncDecoder: Int, val decoderFallback: Boolean, val engine: Int = 0, val hwAccel: Boolean = true,
        val lowMaxHeight: Int = 1080, val lowBufMb: Int = 24,
    )

    fun values(p: Profile): Values = when (p) {
        Profile.AUTO -> Values(lowPerf = false, bufMinS = 0, cleanDecoder = false, zapDelayMs = 250, asyncDecoder = 0, decoderFallback = true)
        // bufor 2 s z bezpieczną korekcją (LiveBuffer.safe), świeży dekoder dla każdego kanału, start 200 ms po ▲▼
        Profile.FAST -> Values(lowPerf = false, bufMinS = 2, cleanDecoder = true, zapDelayMs = 200, asyncDecoder = 0, decoderFallback = true)
        Profile.STABLE -> Values(lowPerf = false, bufMinS = 6, cleanDecoder = false, zapDelayMs = 250, asyncDecoder = 1, decoderFallback = true)
        Profile.LOW -> Values(lowPerf = true, bufMinS = 0, cleanDecoder = true, zapDelayMs = 450, asyncDecoder = 0, decoderFallback = true, lowMaxHeight = 1080, lowBufMb = 16)
    }

    fun apply(p: Profile) {
        val v = values(p)
        Prefs.lowPerf = v.lowPerf; Prefs.bufMinS = v.bufMinS; Prefs.cleanDecoderOnZap = v.cleanDecoder; Prefs.zapDelayMs = v.zapDelayMs
        Prefs.asyncDecoder = v.asyncDecoder; Prefs.decoderFallback = v.decoderFallback; Prefs.playerEngine = v.engine; Prefs.hwAccel = v.hwAccel
        if (v.lowPerf) { Prefs.lowMaxHeight = v.lowMaxHeight; Prefs.lowBufMb = v.lowBufMb }
        // (7.20) bez zatrzymania: zapis ustawień odświeża EngineConfigRepository, a Playback przebudowuje odtwarzacz w locie
        EngineConfigRepository.refresh()
    }

    private fun current() = Values(Prefs.lowPerf, Prefs.bufMinS, Prefs.cleanDecoderOnZap, Prefs.zapDelayMs, Prefs.asyncDecoder, Prefs.decoderFallback,
        Prefs.playerEngine, Prefs.hwAccel, if (Prefs.lowPerf) Prefs.lowMaxHeight else 1080, if (Prefs.lowPerf) Prefs.lowBufMb else 24)

    /** Profil zgodny z ustawieniami albo null (= [CUSTOM]). */
    fun active(): Profile? {
        val c = current()
        return Profile.entries.firstOrNull { p -> values(p).let { v -> if (v.lowPerf) v == c else v == c.copy(lowMaxHeight = 1080, lowBufMb = 24) } }
    }

    fun activeLabel(): String = active()?.label ?: CUSTOM

    /** Etykieta „Bufor – minimum” ze stanem ochrony (np. „2 s (tryb szybki)”, „+1,5 s – ochrona po przycięciach”). */
    fun bufferLabel(s: Int = Prefs.bufMinS, boostMs: Int = Playback.sessionRebufferBoostMs): String {
        val base = when {
            s <= 0 -> "Automatycznie (start 1 s)"
            s <= 3 -> "$s s (tryb szybki)"
            s <= 5 -> "$s s (zrównoważony)"
            else -> "$s s (stabilny)"
        }
        return if (boostMs > 0) base + " · +" + "%.1f".format(java.util.Locale("pl"), boostMs / 1000f) + " s ochrony" else base
    }
    val BUFFER_VALUES = listOf(0, 2, 3, 4, 6, 8, 10, 15, 30)
}

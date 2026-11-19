package pl.iptv.tv.ui

import androidx.media3.common.PlaybackException

/**
 * (7.5) Architektura silników odtwarzania kanałów – szkielet pod decyzję o silniku dodatkowym (LibVLC).
 *
 * Stan obecny: jedyny silnik to Media3 ExoPlayer ([Playback]) z rozszerzeniem FFmpeg (dźwięk AC-3 / E-AC-3 / DTS / MP2 / TrueHD
 * w trybie ON / PREFER), wariantami ustawień na kanał ([pl.iptv.tv.data.EngineProfile]: dekoder sprzętowy / programowy,
 * kolejka dekodera, rodzaj strumienia, bufor) i doborem automatycznym / ręcznym ([ChannelEngineOptimizer]).
 *
 * Podejście „w stylu TiviMate” (pipeline IPTV na Media3) – zrealizowane w [Playback]: jeden odtwarzacz na wiele przełączeń
 * (bez przebudowy przy zmianie kanału), start po 2 s zapasu, ponowienia z rosnącą przerwą, osobna grupa połączeń na strumień
 * (przerwanie starych żądań przy zmianie kanału), strażnik zawieszeń i zamrożeń obrazu, rozpoznawanie HLS bez rozszerzenia.
 *
 * LibVLC jako silnik zapasowy – analiza (bez dołączania biblioteki, decyzja użytkownika):
 * - zalety: własne demuksery (nietypowe kontenery, uszkodzone TS), dekodery programowe libavcodec dla wszystkich kodeków
 *   (AC-3 / E-AC-3 / DTS / TrueHD, MPEG-2, VC-1), praca przy `ERROR_CODE_DECODER_INIT_FAILED` dekodera sprzętowego;
 * - koszt APK: `org.videolan.android:libvlc-all` ≈ +25–35 MB na ABI (arm64-v8a, armeabi-v7a, x86_64 – razem ~80–100 MB
 *   w uniwersalnym APK). Rozsądnie: podział APK na ABI (`splits { abi { } }`) albo osobny moduł dynamiczny / wtyczka;
 * - pamięć: LibVLC + własny bufor sieci (`--network-caching`) ≈ 60–120 MB dodatkowo; na słabszych TV (1–1,5 GB RAM)
 *   nie może działać równolegle z ExoPlayerem – przełączenie zawsze przez pełne `release()` jednego przed utworzeniem drugiego;
 * - cykl życia: `LibVLC` / `MediaPlayer` / `IVLCVout` muszą być zwalniane na wątku głównym w kolejności (detachViews → stop →
 *   release media → release player → release LibVLC); wyciek = natywna pamięć poza GC;
 * - zgodność: [ChannelEngineOptimizer] mógłby dodać LibVLC jako ostatni etap doboru (po wariantach Media3); SponsorBlock,
 *   PiP i tryb samego dźwięku STube działają na Media3 / MediaSession i zostają bez zmian (LibVLC tylko dla kanałów IPTV);
 *   MediaSession dla LibVLC wymagałby własnego adaptera `SimpleBasePlayer`;
 * - licencja: LibVLC – LGPL 2.1+ (dynamiczne łączenie: dozwolone); część modułów (np. z libvlc-all) – GPL.
 * Najbezpieczniej: LibVLC jako opcjonalny silnik zapasowy tylko dla kanałów, które nie ruszają na żadnym wariancie Media3,
 * w osobnym module, po decyzji – [PlaybackEngines.fallbackFor] wskazuje już przypadki, w których byłby użyty.
 */
enum class EngineKind(val id: String, val label: String) {
    MEDIA3("media3", "Media3 ExoPlayer (+ FFmpeg)"),
    LIBVLC("libvlc", "VLC (LibVLC)"),
}

/** Silnik odtwarzania kanałów: rodzaj i dostępność w bieżącej kompilacji. */
interface ChannelPlaybackEngine {
    val kind: EngineKind
    /** Biblioteka silnika jest w APK. */
    val available: Boolean
}

object PlaybackEngines {
    val media3 = object : ChannelPlaybackEngine {
        override val kind = EngineKind.MEDIA3
        override val available = true
    }

    /** LibVLC – dostępny tylko, gdy biblioteka zostanie dołączona (obecnie nie jest). */
    val libVlc = object : ChannelPlaybackEngine {
        override val kind = EngineKind.LIBVLC
        override val available: Boolean by lazy { runCatching { Class.forName("org.videolan.libvlc.LibVLC") }.isSuccess }
    }

    val all: List<ChannelPlaybackEngine> get() = listOf(media3, libVlc)

    /** Błędy, przy których Media3 nie pomoże innym wariantem, a silnik z własnymi dekoderami / demukserami – może. */
    val VLC_CASES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )

    /**
     * Silnik zapasowy dla kanału po błędzie [errorCode], gdy warianty Media3 zawiodły ([media3Exhausted]); null – zostaje Media3
     * (zwykłe ponowienia). Bez dołączonej biblioteki LibVLC zawsze null.
     */
    fun fallbackFor(errorCode: Int, media3Exhausted: Boolean, engines: List<ChannelPlaybackEngine> = all): ChannelPlaybackEngine? {
        if (!media3Exhausted || errorCode !in VLC_CASES) return null
        return engines.firstOrNull { it.kind == EngineKind.LIBVLC && it.available }
    }
}

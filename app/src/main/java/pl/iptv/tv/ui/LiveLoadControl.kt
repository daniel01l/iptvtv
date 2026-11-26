package pl.iptv.tv.ui

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * (7.17) Bufor Live TV w bezpiecznych proporcjach. `DefaultLoadControl` wymaga, by zapas przed startem i po przycięciu nie był
 * większy od minimum bufora – ustawienie „Bufor – minimum = 2 s” (przy stałym progu po przycięciu 5 s) kończyło się błędem
 * budowy odtwarzacza i wyłączeniem odtwarzania. Tu progi wynikają z minimum (45 % / 75 %), minimum ma dolną granicę
 * [MIN_FLOOR_MS], a maksimum jest co najmniej o [MAX_MARGIN_MS] większe.
 */
object LiveBuffer {
    const val MIN_FLOOR_MS = 1_500
    const val MAX_MARGIN_MS = 5_000
    /** Adaptacja: [BOOST_TRIGGER] przycięcia w [BOOST_WINDOW_MS] przy niskim progu (≤ [LOW_REBUFFER_MS]) → próg +[BOOST_MS]. */
    const val BOOST_MS = 1_500
    const val BOOST_WINDOW_MS = 25_000L
    const val BOOST_TRIGGER = 2
    const val LOW_REBUFFER_MS = 3_000
    const val BOOST_CAP_MS = 6_000

    data class Durations(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int)

    fun safe(userMinMs: Int, userMaxMs: Int): Durations {
        val min = userMinMs.coerceAtLeast(MIN_FLOOR_MS)
        val play = (min * 0.45f).toInt().coerceIn(500, 1_500)
        val rebuffer = (min * 0.75f).toInt().coerceIn(800, 2_500)
        val max = userMaxMs.coerceAtLeast(min + MAX_MARGIN_MS)
        // gwarancja wymagań DefaultLoadControl (start / po przycięciu ≤ minimum ≤ maksimum)
        return Durations(maxOf(min, play, rebuffer), max, minOf(play, min), minOf(rebuffer, min))
    }
}

/**
 * (7.17) Kontrola ładowania Live TV z adaptacją w trakcie sesji: zwykle działa jak [delegate] (`DefaultLoadControl`), a po
 * [LiveBuffer.BOOST_TRIGGER] przycięciach w [LiveBuffer.BOOST_WINDOW_MS] przy niskim progu ([baseRebufferMs] + [boostMs]
 * ≤ [LiveBuffer.LOW_REBUFFER_MS]) podnosi próg wznowienia po przycięciu o [LiveBuffer.BOOST_MS] – odtwarzanie wznawia się
 * z większym zapasem zamiast co chwilę stawać albo kończyć się błędem. Ładowanie bez zmian (Default ładuje do maksimum).
 */
class AdaptiveLiveLoadControl(private val delegate: DefaultLoadControl, val baseRebufferMs: Int, initialBoostMs: Int = 0) : LoadControl {
    @Volatile var boostMs = initialBoostMs; private set
    private val events = ArrayDeque<Long>()
    val rebufferMs get() = baseRebufferMs + boostMs

    /**
     * (7.24) Nowy kanał – próg od podstawy. Dawniej podniesiony próg (do +3 s w trybie szybkim) zostawał na wszystkie kolejne
     * kanały do końca sesji: po dłuższym oglądaniu każde przycięcie na dowolnym kanale czekało na większy zapas.
     */
    @Synchronized fun reset() { boostMs = 0; events.clear() }

    /** Przycięcie (READY → BUFFERING) w chwili [nowMs]; true – próg podniesiony. */
    @Synchronized fun onRebuffer(nowMs: Long): Boolean {
        events.addLast(nowMs)
        while (events.isNotEmpty() && nowMs - events.first() > LiveBuffer.BOOST_WINDOW_MS) events.removeFirst()
        if (events.size < LiveBuffer.BOOST_TRIGGER || rebufferMs > LiveBuffer.LOW_REBUFFER_MS || boostMs >= LiveBuffer.BOOST_CAP_MS) return false
        boostMs = (boostMs + LiveBuffer.BOOST_MS).coerceAtMost(LiveBuffer.BOOST_CAP_MS); events.clear()
        return true
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!delegate.shouldStartPlayback(parameters)) return false
        if (!parameters.rebuffering || boostMs == 0) return true
        val need = rebufferMs * 1000L
        // transmisja z docelowym opóźnieniem krótszym niż wymagany zapas – jak Default (połowa opóźnienia wystarcza)
        val live = parameters.targetLiveOffsetUs.takeIf { it != C.TIME_UNSET }?.let { it / 2 } ?: Long.MAX_VALUE
        return parameters.bufferedDurationUs >= minOf(need, live)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(): Allocator = delegate.allocator
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(timeline: androidx.media3.common.Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)
}

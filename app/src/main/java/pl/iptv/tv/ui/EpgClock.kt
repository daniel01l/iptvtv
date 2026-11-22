package pl.iptv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * (7.17) Jeden zegar postępu programów dla całej aplikacji: [ticks] tyka co [TICK_MS] (wyrównane do pełnych 30 s).
 * Konsumowany lokalnie przez widoczne paski ([ProgView], [EpgProgressFill] w Compose) – zmiana czasu odświeża tylko pasek,
 * nie całą listę kanałów (bez przebudowy rzędów / adapterów). Zegar działa tylko, gdy ktoś go słucha.
 */
object EpgClock {
    const val TICK_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val ticks: StateFlow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(TICK_MS - now % TICK_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), System.currentTimeMillis())

    /** Postęp programu [start]–[end] w chwili [now] (0–1); -1, gdy czasów brak. */
    fun fraction(start: Long, end: Long, now: Long): Float =
        if (end <= start) -1f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/**
 * Wypełnienie paska postępu programu [start]–[end] w Compose. Czas z [EpgClock.ticks] czytany dopiero w fazie rysowania
 * (`drawBehind`) – tyknięcie zegara tylko przerysowuje pasek, bez rekompozycji kafelka i rzędu.
 */
@Composable
fun EpgProgressFill(start: Long, end: Long, color: Color, modifier: Modifier) {
    val now = EpgClock.ticks.collectAsState()
    Box(modifier.drawBehind {
        now.value                                   // odczyt stanu w fazie rysowania – tyknięcie przerysowuje tylko ten pasek
        val f = EpgClock.fraction(start, end, System.currentTimeMillis())
        if (f > 0f) drawRect(color, size = Size(size.width * f, size.height))
    })
}

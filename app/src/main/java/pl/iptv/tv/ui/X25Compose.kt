package pl.iptv.tv.ui

import android.view.KeyEvent as NativeKey
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.iptv.tv.data.Prefs

/*
 * (7.27) Motyw X25 usunięty (kopia: docs/archiwum_motywy/). Zostały wspólne elementy Compose używane przez ekran główny
 * Nowoczesny, OSD odtwarzacza, wyszukiwarkę STube i bibliotekę aplikacji: [x25Clickable] (OK / przytrzymanie OK).
 */

private val OK_KEYS = setOf(NativeKey.KEYCODE_DPAD_CENTER, NativeKey.KEYCODE_ENTER, NativeKey.KEYCODE_NUMPAD_ENTER, NativeKey.KEYCODE_BUTTON_A)

private class PressState { var down = false; var long = false }

/** Czas przytrzymania OK, po którym działa „długie naciśnięcie”. */
const val X25_LONG_MS = 500L

/**
 * OK / dotknięcie = [onClick], przytrzymanie OK (≥ 0,5 s) / długie dotknięcie = [onLong]; [onKey] – pozostałe klawisze.
 * Zwolnienie OK bez wcześniejszego wciśnięcia na tym elemencie jest pomijane (OK przytrzymane na kafelku nie „klika”
 * w pierwszą pozycję otwartego właśnie panelu).
 */
@Composable
fun Modifier.x25Clickable(onClick: () -> Unit, onLong: (() -> Unit)? = null, onKey: ((KeyEvent) -> Boolean)? = null): Modifier {
    val st = remember { PressState() }
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    val key by rememberUpdatedState(onKey)
    return this
        .onPreviewKeyEvent { e ->
            if (key?.invoke(e) == true) return@onPreviewKeyEvent true
            val ne = e.nativeKeyEvent
            if (ne.keyCode !in OK_KEYS) return@onPreviewKeyEvent false
            when (ne.action) {
                NativeKey.ACTION_DOWN -> {
                    if (ne.repeatCount == 0) { st.down = true; st.long = false }
                    else if (st.down && !st.long && long != null && (ne.isLongPress || ne.eventTime - ne.downTime >= X25_LONG_MS)) { st.long = true; long?.invoke() }
                }
                NativeKey.ACTION_UP -> { if (st.down && !st.long) click(); st.down = false }
            }
            true
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { click() }, onLongPress = { long?.invoke() }) }
}


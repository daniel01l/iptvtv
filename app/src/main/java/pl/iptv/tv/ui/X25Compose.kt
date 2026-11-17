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

/** Kolory X25 dla Compose – z [Ui] (preset X25 + ewentualne kolory własne z Ustawień → Wygląd). */
@Immutable
data class X25Colors(
    val bg: Color, val panel: Color, val accent: Color, val focus: Color, val text: Color, val text2: Color, val ring: Color,
    val lowPerf: Boolean,
) {
    companion object {
        fun fromUi() = X25Colors(Color(Ui.bg), Color(Ui.panel), Color(Ui.accent), Color(Ui.focus), Color(Ui.text), Color(Ui.text2),
            Color(X25Theme.RING), runCatching { Prefs.lowPerf }.getOrDefault(false))
    }
}

val LocalX25 = staticCompositionLocalOf {
    X25Colors(Color(0xFF050806), Color(0xFF121815), Color(0xFF3CF26A), Color(0xFF3CF26A), Color.White, Color(0xFF9DB3A3), Color(0xFFD4FF3A), false)
}

/**
 * Dostawca motywu X25 dla ekranów w Compose. Kolory czytane raz na wersję wyglądu ([Ui.version]) –
 * zmiana w Ustawieniach odświeża pulpit bez ponownego uruchamiania.
 */
@Composable
fun X25ThemeProvider(version: Int = Ui.version, content: @Composable () -> Unit) {
    val colors = remember(version) { X25Colors.fromUi() }
    CompositionLocalProvider(LocalX25 provides colors, content = content)
}

object X25Type {
    val hero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 12.sp)
}

/**
 * Ramka zaznaczenia X25: pogrubiona, zaokrąglona linia w neonowej zieleni + dwie półprzezroczyste obwódki (poświata)
 * rysowane poza kafelkiem – bez rozmycia i cieni (tanie na słabszych urządzeniach; tam sama ramka).
 */
fun Modifier.x25Frame(on: Boolean, radius: Dp, color: Color, glow: Boolean = true): Modifier =
    // stały węzeł (łańcuch modyfikatorów nie zmienia się przy fokusie); obiekty rysowania tworzone raz na rozmiar i stan
    drawWithCache {
        val r = radius.toPx(); val sw = 3.dp.toPx(); val gw = Stroke(2.5.dp.toPx()); val core = Stroke(sw)
        val d1 = 6.dp.toPx(); val d2 = 3.dp.toPx()
        val c1 = color.copy(alpha = 0.16f); val c2 = color.copy(alpha = 0.32f)
        onDrawWithContent {
            drawContent()
            if (!on) return@onDrawWithContent
            if (glow) {
                drawRoundRect(c1, Offset(-d1, -d1), Size(size.width + 2 * d1, size.height + 2 * d1), CornerRadius(r + d1), style = gw)
                drawRoundRect(c2, Offset(-d2, -d2), Size(size.width + 2 * d2, size.height + 2 * d2), CornerRadius(r + d2), style = gw)
            }
            drawRoundRect(color, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r), style = core)
        }
    }

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

/** Pozycja panelu (Flyout / Guide). */
class FlyItem(val label: String, val sub: String = "", val icon: String? = null, val action: () -> Unit)

/** Strona panelu; [loading] – pozycje dopiero się wczytują. */
data class FlyPage(val title: String, val items: List<FlyItem>, val loading: Boolean = false, val subtitle: String = "")

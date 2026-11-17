package pl.iptv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.iptv.tv.data.Prefs

/** Kolory PS5 dla Compose – z [Ui] (preset PS5 + ewentualne kolory własne z Ustawień → Wygląd). */
@Immutable
data class Ps5Colors(
    val bg: Color, val panel: Color, val accent: Color, val ring: Color, val glow: Color, val text: Color, val text2: Color,
    /** Karta (widget) na tle blueprint: półprzezroczysty granat. */
    val card: Color,
    val lowPerf: Boolean,
) {
    companion object {
        // poświata: błękit PS5, przy innych motywach (ekran główny trybu launchera, biblioteka aplikacji) – akcent motywu
        fun fromUi() = Ps5Colors(Color(Ui.bg), Color(Ui.panel), Color(Ui.accent), Color(Ui.focus), Color(if (Ps5Theme.active) Ps5Theme.GLOW else Ui.accent), Color(Ui.text), Color(Ui.text2),
            Color(Ui.panel).copy(alpha = 0.78f), runCatching { Prefs.lowPerf }.getOrDefault(false))
    }
}

val LocalPs5 = staticCompositionLocalOf {
    Ps5Colors(Color(0xFF050A1E), Color(0xFF0E1736), Color(0xFF3D8BFF), Color(0xFFEAF4FF), Color(0xFF3D8BFF), Color.White, Color(0xFFA9B8DA),
        Color(0xC70E1736), false)
}

/**
 * Dostawca motywu PS5 dla ekranów w Compose (niezależny od [X25ThemeProvider]). Kolory czytane raz na wersję wyglądu
 * ([Ui.version]) – zmiana w Ustawieniach odświeża ekran bez ponownego uruchamiania.
 */
@Composable
fun PS5ThemeProvider(version: Int = Ui.version, content: @Composable () -> Unit) {
    val colors = remember(version) { Ps5Colors.fromUi() }
    CompositionLocalProvider(LocalPs5 provides colors, content = content)
}

/** Typografia PS5: lekkie, szerokie nagłówki, spokojny tekst (systemowa czcionka „light”). */
object Ps5Type {
    private val light = FontFamily.SansSerif
    val display = TextStyle(fontFamily = light, fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White, letterSpacing = 0.3.sp)
    val tab = TextStyle(fontFamily = light, fontSize = 22.sp, fontWeight = FontWeight.Normal, color = Color.White)
    val title = TextStyle(fontFamily = light, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val body = TextStyle(fontFamily = light, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color.White)
    val small = TextStyle(fontFamily = light, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Color.White)
    val number = TextStyle(fontFamily = light, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

/**
 * Obwódka zaznaczenia PS5: czysta biało-błękitna linia + błękitna poświata (dwa półprzezroczyste pierścienie poza elementem,
 * bez rozmycia). Stały węzeł modyfikatora – zmiana fokusu tylko przerysowuje (bez nowego układu), bez opóźnień.
 */
fun Modifier.ps5Frame(on: Boolean, radius: Dp, ring: Color, glow: Color, halo: Boolean = true): Modifier =
    drawWithCache {
        val r = radius.toPx(); val sw = 2.5.dp.toPx(); val core = Stroke(sw); val gw = Stroke(2.dp.toPx())
        val d1 = 6.dp.toPx(); val d2 = 3.dp.toPx()
        val c1 = glow.copy(alpha = 0.22f); val c2 = glow.copy(alpha = 0.55f)
        onDrawWithContent {
            drawContent()
            if (!on) return@onDrawWithContent
            if (halo) {
                drawRoundRect(c1, Offset(-d1, -d1), Size(size.width + 2 * d1, size.height + 2 * d1), CornerRadius(r + d1), style = gw)
                drawRoundRect(c2, Offset(-d2, -d2), Size(size.width + 2 * d2, size.height + 2 * d2), CornerRadius(r + d2), style = gw)
            }
            drawRoundRect(ring, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r), style = core)
        }
    }

/** Przełącznik „switch” w Compose (jak [Ps5Switch]). */
fun DrawScope.ps5Switch(on: Boolean, accent: Color) {
    val h = size.height; val rad = h / 2
    drawRoundRect(if (on) accent else Color.White.copy(alpha = 0.22f), cornerRadius = CornerRadius(rad))
    drawCircle(if (on) Color.White else Color.White.copy(alpha = 0.78f), rad - 3.dp.toPx(), Offset(if (on) size.width - rad else rad, h / 2))
}

/** Pozycja panelu opcji PS5 ([switch] = przełącznik po prawej, null – zwykła pozycja). */
class Ps5Option(val label: String, val sub: String = "", val switch: Boolean? = null, val action: () -> Unit)

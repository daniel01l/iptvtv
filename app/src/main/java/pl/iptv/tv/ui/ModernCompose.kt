package pl.iptv.tv.ui

import pl.iptv.tv.ui.theme.gradientLayer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.iptv.tv.data.Prefs

/** Kolory motywu Nowoczesny dla Compose – z [Ui] (preset + ewentualne kolory własne z Ustawień → Wygląd). */
@Immutable
data class ModernColors(
    val bg: Color, val panel: Color, val accent: Color, val ring: Color, val live: Color, val text: Color, val text2: Color,
    /** Szara kapsuła (przyciski drugorzędne, pozycje panelu). */
    val pill: Color,
    val lowPerf: Boolean,
) {
    companion object {
        fun fromUi() = ModernColors(Color(Ui.bg), Color(Ui.panel), Color(Ui.accent), Color(ModernTheme.RING), Color(ModernTheme.LIVE),
            Color(Ui.text), Color(Ui.text2), Color.White.copy(alpha = 0.16f), runCatching { Prefs.lowPerf }.getOrDefault(false))
    }
}

val LocalModern = staticCompositionLocalOf {
    ModernColors(Color(0xFF07080C), Color(0xFF14171F), Color(0xFF2F6BFF), Color(0xFFD8E8FF), Color(0xFFE5233A), Color.White,
        Color(0xFF9EA6B4), Color(0x29FFFFFF), false)
}

/** Dostawca motywu Nowoczesny; kolory czytane raz na wersję wyglądu ([Ui.version]). */
@Composable
fun ModernThemeProvider(version: Int = Ui.version, content: @Composable () -> Unit) {
    val colors = remember(version) { ModernColors.fromUi() }
    CompositionLocalProvider(LocalModern provides colors, content = content)
}

/** Typografia: wyraźne, pogrubione tytuły (czytelne z kanapy), spokojny opis. */
object ModernType {
    private val sans = FontFamily.SansSerif
    val section = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.6.sp)
    val hero = TextStyle(fontFamily = sans, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val meta = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val body = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color.White, lineHeight = 20.sp)
    val row = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.5.sp)
    val card = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val small = TextStyle(fontFamily = sans, fontSize = 11.sp, fontWeight = FontWeight.Normal, color = Color.White)
    val button = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.5.sp)
    val tile = TextStyle(fontFamily = sans, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
}

/**
 * Obwódka zaznaczenia: jasnoniebieska linia tuż poza kafelkiem i delikatna poświata. Stały węzeł modyfikatora
 * (zmiana fokusu tylko przerysowuje – bez nowego układu); tryb słabszych urządzeń – sama linia.
 */
fun Modifier.modernFrame(on: Boolean, radius: Dp, ring: Color, halo: Boolean = true): Modifier =
    drawWithCache {
        val r = radius.toPx(); val sw = 3.dp.toPx(); val gap = 2.dp.toPx(); val core = Stroke(sw)
        val o = gap + sw / 2; val g = gap + sw + 2.dp.toPx(); val glow = Stroke(3.dp.toPx())
        val gc = ring.copy(alpha = 0.25f)
        onDrawWithContent {
            drawContent()
            if (!on) return@onDrawWithContent
            if (halo) drawRoundRect(gc, Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r + g), style = glow)
            drawRoundRect(ring, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(r + o), style = core)
        }
    }

/** Wygaszenie tła hero: w lewo (pod tekstem) i w dół (pod rzędami) – gradienty rysowane raz na rozmiar. */
fun Modifier.modernScrim(bg: Color): Modifier = drawWithCache {
    val left = Brush.horizontalGradient(0f to bg, 0.38f to bg.copy(alpha = 0.85f), 0.62f to bg.copy(alpha = 0.15f), 1f to Color.Transparent)
    val bottom = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 0.8f to bg.copy(alpha = 0.9f), 1f to bg)
    val top = Brush.verticalGradient(0f to bg.copy(alpha = 0.55f), 0.18f to Color.Transparent)
    onDrawWithContent {
        drawContent()
        drawRect(left); drawRect(bottom); drawRect(top)
    }
}

/**
 * Tło ekranu głównego (7.1): czerń z niebieską poświatą u góry, chłodnym blaskiem przy dolnym rogu i winietą – jak
 * [ModernBackdrop] na widokach. Pędzle tworzone raz na rozmiar (drawWithCache), rysowane jako jedna warstwa.
 */
fun Modifier.modernBackdrop(bg: Color, accent: Color): Modifier = drawWithCache {
    // (7.17) cztery pełnoekranowe gradienty wyrenderowane raz do bitmapy w połowie rozdzielczości (jedna tekstura na klatkę)
    val img = gradientLayer {
        val w = size.width; val h = size.height; val r = maxOf(w, h)
        drawRect(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(bg, accent, 0.08f), bg, androidx.compose.ui.graphics.lerp(bg, Color.Black, 0.55f))))
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.19f), accent.copy(alpha = 0.05f), Color.Transparent), Offset(w * 0.85f, h * 0.05f), r * 0.6f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF5B3BD6).copy(alpha = 0.11f), Color.Transparent), Offset(w * 0.05f, h * 0.95f), r * 0.45f))
        drawRect(Brush.radialGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.38f), center = Offset(w / 2, h / 2), radius = r * 0.75f))
    }
    onDrawBehind { img() }
}

/**
 * Faktura kafelka: delikatne światło u góry (jasna krawędź) i przyciemnienie dołu – kafelki wyglądają na „wypukłe” na czarnym
 * tle. Gradienty rysowane raz na rozmiar, bez dodatkowego układu.
 */
fun Modifier.modernTileSheen(radius: Dp): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx())
    val top = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.Transparent)
    val bottom = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))
    val edge = Stroke(1.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawRoundRect(bottom, cornerRadius = r)
        drawRoundRect(top, cornerRadius = r)
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = r, style = edge)
    }
}

/** Pozycja panelu opcji ([switch] = przełącznik po prawej, null – zwykła pozycja). */
@androidx.compose.runtime.Immutable
class ModernOption(val label: String, val sub: String = "", val switch: Boolean? = null, val action: () -> Unit)

/** Ikony paska nawigacji (własne rysunki: ekran główny, wyjście); pozostałe – [X25Icons]. */
object ModernIcons {
    const val HOME = "m:home"; const val EXIT = "m:exit"; const val PLAYLIST = "m:playlist"; const val INFO = "m:info"

    fun draw(s: DrawScope, kind: String, c: Color) = with(s) {
        val m = minOf(size.width, size.height); val ox = (size.width - m) / 2; val oy = (size.height - m) / 2
        val st = Stroke(m * 0.08f, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(ox + x * m, oy + y * m)
        when (kind) {
            HOME -> {
                drawPath(Path().apply { moveTo(p(0.12f, 0.5f).x, p(0.12f, 0.5f).y); lineTo(p(0.5f, 0.14f).x, p(0.5f, 0.14f).y); lineTo(p(0.88f, 0.5f).x, p(0.88f, 0.5f).y) }, c, style = st)
                drawPath(Path().apply { moveTo(p(0.24f, 0.42f).x, p(0.24f, 0.42f).y); lineTo(p(0.24f, 0.86f).x, p(0.24f, 0.86f).y)
                    lineTo(p(0.76f, 0.86f).x, p(0.76f, 0.86f).y); lineTo(p(0.76f, 0.42f).x, p(0.76f, 0.42f).y) }, c, style = st)
                drawRect(c, p(0.42f, 0.6f), Size(0.16f * m, 0.26f * m))
            }
            EXIT -> {
                drawArc(c, -60f, 300f, false, p(0.16f, 0.2f), Size(0.68f * m, 0.68f * m), style = st)
                drawLine(c, p(0.5f, 0.1f), p(0.5f, 0.48f), st.width, StrokeCap.Round)
            }
            PLAYLIST -> {
                for (i in 0..2) drawLine(c, p(0.14f, 0.24f + i * 0.2f), p(0.66f, 0.24f + i * 0.2f), st.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(p(0.66f, 0.62f).x, p(0.66f, 0.62f).y); lineTo(p(0.66f, 0.9f).x, p(0.66f, 0.9f).y); lineTo(p(0.9f, 0.76f).x, p(0.9f, 0.76f).y); close() }, c)
            }
            INFO -> {
                drawCircle(c, 0.4f * m, p(0.5f, 0.5f), style = st)
                drawLine(c, p(0.5f, 0.46f), p(0.5f, 0.72f), st.width, StrokeCap.Round)
                drawCircle(c, 0.05f * m, p(0.5f, 0.31f))
            }
            else -> X25Icons.draw(this, kind, c)
        }
    }

    /** Trójkąt „odtwórz” w przycisku. */
    fun play(s: DrawScope, c: Color) = with(s) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * 0.2f, h * 0.12f); lineTo(w * 0.2f, h * 0.88f); lineTo(w * 0.9f, h * 0.5f); close() }, c)
    }
}

/** Kolor kafelka kanału bez logo (stały dla nazwy): ciemne, nasycone gradienty jak na kafelkach kanałów serwisów VOD. */
fun channelGradient(name: String): List<Color> {
    val pal = listOf(
        Color(0xFF0E5A8A) to Color(0xFF0A2C4A), Color(0xFFB4561E) to Color(0xFF5A2410), Color(0xFF8E1F3C) to Color(0xFF3E0C1C),
        Color(0xFFB01E2A) to Color(0xFF560C14), Color(0xFF9C2A8C) to Color(0xFF461040), Color(0xFF1F7A54) to Color(0xFF0C3A26),
        Color(0xFF3E3FA8) to Color(0xFF181A50), Color(0xFF6B5A1E) to Color(0xFF30280C),
    )
    val (a, b) = pal[Math.floorMod(name.lowercase().hashCode(), pal.size)]
    return listOf(a, b)
}

package pl.iptv.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * System designu „Obsidian Stream” (Compose, Android TV). Zbudowany na `compose.foundation` (bez Material 3 / tv-material –
 * projekt ich nie używa): kolory [ObsidianColorScheme], typografia [ObsidianTypography], dostęp przez [ObsidianTheme].
 */
@Immutable
data class ObsidianColorScheme(
    val backgroundPrimary: Color = ObsidianTokens.BackgroundPrimary,
    val surfaceGlass: Color = ObsidianTokens.SurfaceGlass,
    val surfaceElevated: Color = ObsidianTokens.SurfaceElevated,
    val accentPrimary: Color = ObsidianTokens.AccentPrimary,
    val accentGlow: Color = ObsidianTokens.AccentGlow,
    val badgeLive: Color = ObsidianTokens.BadgeLive,
    val textPrimary: Color = ObsidianTokens.TextPrimary,
    val textSecondary: Color = ObsidianTokens.TextSecondary,
)

/** Tokeny kolorów (stałe – także poza kompozycją, np. w widokach). */
object ObsidianTokens {
    val BackgroundPrimary = Color(0xFF080A0F)
    val SurfaceGlass = Color(0xD9121722)
    val SurfaceElevated = Color(0xEB1A2233)
    val AccentPrimary = Color(0xFF3B82F6)
    val AccentGlow = Color(0x5960A5FA)
    val BadgeLive = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)

    /** Krawędź „specular”: jasna u góry z lewej, zanikająca ku dołowi z prawej. */
    val SpecularStart = Color.White.copy(alpha = 0.16f)
    val SpecularEnd = Color.White.copy(alpha = 0.02f)
}

/** Typografia pod ekran TV oglądany z ~3 m: duże, pogrubione nagłówki, czytelne tytuły kart, kontrastowe opisy EPG. */
@Immutable
data class ObsidianTypography(
    val heroLarge: TextStyle = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = ObsidianTokens.TextPrimary),
    val hero: TextStyle = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = ObsidianTokens.TextPrimary),
    val sectionTitle: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = ObsidianTokens.TextPrimary),
    val cardTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = ObsidianTokens.TextPrimary),
    /** Opis programu EPG – jasny tekst (wysoki kontrast na ciemnym szkle), większa interlinia. */
    val epgDescription: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, color = ObsidianTokens.TextPrimary),
    val meta: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = ObsidianTokens.TextSecondary),
    val badge: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = ObsidianTokens.TextPrimary),
)

val LocalObsidianColors = staticCompositionLocalOf { ObsidianColorScheme() }
val LocalObsidianTypography = staticCompositionLocalOf { ObsidianTypography() }

/** Dostęp do motywu w kompozycji: `ObsidianTheme.colors.accentPrimary`, `ObsidianTheme.typography.hero`. */
object ObsidianTheme {
    val colors: ObsidianColorScheme @Composable get() = LocalObsidianColors.current
    val typography: ObsidianTypography @Composable get() = LocalObsidianTypography.current
}

@Composable
fun ObsidianTheme(
    colors: ObsidianColorScheme = ObsidianColorScheme(),
    typography: ObsidianTypography = ObsidianTypography(),
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalObsidianColors provides colors, LocalObsidianTypography provides typography, content = content)

/**
 * Karta „szklana” bez rozmycia (bez `RenderEffect` – tanie dla GPU słabszych TV): pionowy gradient powierzchni
 * (`SurfaceGlass` → `SurfaceElevated`), krawędź specular 1 dp (gradient bieli 16 % → 2 %), a przy fokusie obwódka 2 dp
 * w [accentColor] i miękka poświata na zewnątrz karty.
 *
 * Wszystkie pędzle, obrysy, położenia i kolory liczone raz w bloku pamięci `drawWithCache` (ponownie tylko przy zmianie
 * rozmiaru lub parametrów); faza rysowania klatki niczego nie alokuje. Treść karty nie jest przycinana (poświata
 * wychodzi poza kartę) – do przycięcia obrazu dodaj `clip(RoundedCornerShape(cornerRadius))` na samej treści.
 */
fun Modifier.obsidianGlassCard(
    isFocused: Boolean,
    cornerRadius: Dp = 16.dp,
    accentColor: Color = ObsidianTokens.AccentPrimary,
): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val r = cornerRadius.toPx()
    val radius = CornerRadius(r, r)

    // tło: pionowy gradient powierzchni
    val surface = Brush.verticalGradient(listOf(ObsidianTokens.SurfaceGlass, ObsidianTokens.SurfaceElevated), startY = 0f, endY = h)

    // krawędź specular 1 dp (obrys w środku krawędzi – bez wychodzenia poza kartę)
    val specW = 1.dp.toPx()
    val specular = Brush.linearGradient(listOf(ObsidianTokens.SpecularStart, ObsidianTokens.SpecularEnd), start = Offset.Zero, end = Offset(w, h))
    val specStroke = Stroke(width = specW)
    val specTopLeft = Offset(specW / 2, specW / 2)
    val specSize = Size((w - specW).coerceAtLeast(0f), (h - specW).coerceAtLeast(0f))
    val specRadius = CornerRadius((r - specW / 2).coerceAtLeast(0f))

    // obwódka fokusu 2 dp
    val focusW = 2.dp.toPx()
    val focusStroke = Stroke(width = focusW)
    val focusTopLeft = Offset(focusW / 2, focusW / 2)
    val focusSize = Size((w - focusW).coerceAtLeast(0f), (h - focusW).coerceAtLeast(0f))
    val focusRadius = CornerRadius((r - focusW / 2).coerceAtLeast(0f))

    // poświata: GLOW_RINGS pierścieni na zewnątrz karty, coraz szerszych i słabszych (kolor z akcentu)
    val glowBase = accentColor.copy(alpha = ObsidianTokens.AccentGlow.alpha)
    val ringStep = 3.dp.toPx()
    val glowTopLeft = arrayOfNulls<Offset>(GLOW_RINGS)
    val glowSize = arrayOfNulls<Size>(GLOW_RINGS)
    val glowRadius = arrayOfNulls<CornerRadius>(GLOW_RINGS)
    val glowColor = arrayOfNulls<Color>(GLOW_RINGS)
    val glowStroke = Stroke(width = ringStep)
    for (i in 0 until GLOW_RINGS) {
        val out = ringStep * (i + 0.5f)                              // środek pierścienia poza krawędzią karty
        glowTopLeft[i] = Offset(-out, -out)
        glowSize[i] = Size(w + 2 * out, h + 2 * out)
        glowRadius[i] = CornerRadius(r + out)
        glowColor[i] = glowBase.copy(alpha = glowBase.alpha * (1f - i / GLOW_RINGS.toFloat()) * 0.7f)
    }

    onDrawWithContent {
        if (isFocused) for (i in 0 until GLOW_RINGS)
            drawRoundRect(glowColor[i]!!, glowTopLeft[i]!!, glowSize[i]!!, glowRadius[i]!!, glowStroke)
        drawRoundRect(surface, cornerRadius = radius)
        drawContent()
        drawRoundRect(specular, specTopLeft, specSize, specRadius, style = specStroke)
        if (isFocused) drawRoundRect(accentColor, focusTopLeft, focusSize, focusRadius, style = focusStroke)
    }
}

private const val GLOW_RINGS = 4

/**
 * (7.17) Warstwa gradientów wyrenderowana raz do bitmapy w [scale] rozdzielczości (domyślnie połowa) i rysowana jako jeden
 * obraz: kilka pełnoekranowych gradientów (każdy liczony dla każdego piksela w każdej klatce animacji) zastępuje jedno
 * skalowane próbkowanie tekstury. Bitmapa powstaje ponownie tylko przy zmianie rozmiaru lub parametrów modyfikatora.
 */
fun androidx.compose.ui.draw.CacheDrawScope.gradientLayer(scale: Float = 0.5f, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit {
    val w = (size.width * scale).toInt().coerceAtLeast(1); val h = (size.height * scale).toInt().coerceAtLeast(1)
    // bitmapy nie da się utworzyć (brak pamięci, środowisko testowe) – gradienty rysowane bezpośrednio, jak dawniej
    val img = runCatching {
        androidx.compose.ui.graphics.ImageBitmap(w, h).also { b ->
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(b), Size(w.toFloat(), h.toFloat()), block)
        }
    }.getOrNull() ?: return block
    return { drawLayer(img) }
}

/** Rysowanie bitmapy warstwy na cały obszar (filtrowanie dwuliniowe – gładkie gradienty). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLayer(img: androidx.compose.ui.graphics.ImageBitmap) =
    drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)

/**
 * Winieta hero na pełną szerokość: od lewej `#080A0F` 95 % → 0 % (tekst hero zawsze czytelny), od dołu do pełnego tła
 * (rzędy pod spodem) i lekko od góry (zegar, nagłówek). Jedna bitmapa na rozmiar ([gradientLayer]).
 */
fun Modifier.obsidianHeroVignette(bg: Color = ObsidianTokens.BackgroundPrimary): Modifier = drawWithCache {
    val img = gradientLayer {
        drawRect(Brush.horizontalGradient(0f to bg.copy(alpha = 0.95f), 0.32f to bg.copy(alpha = 0.78f), 0.62f to bg.copy(alpha = 0.18f), 1f to bg.copy(alpha = 0f)))
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 0.82f to bg.copy(alpha = 0.88f), 1f to bg))
        drawRect(Brush.verticalGradient(0f to bg.copy(alpha = 0.5f), 0.16f to Color.Transparent))
    }
    onDrawWithContent { drawContent(); img() }
}

/**
 * Poświata ambient w kolorze zaznaczonego materiału ([glow]) – miękka plama światła za hero (prawy górny obszar) i słabsza
 * przy lewej dolnej krawędzi. Jedna bitmapa, odtwarzana tylko przy zmianie koloru lub rozmiaru.
 */
fun Modifier.obsidianAmbientGlow(glow: Color): Modifier = drawWithCache {
    val img = gradientLayer(scale = 0.25f) {           // rozmyta poświata – ćwierć rozdzielczości wystarcza
        val w = size.width; val h = size.height; val r = maxOf(w, h)
        drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), glow.copy(alpha = 0.08f), Color.Transparent), Offset(w * 0.72f, h * 0.18f), r * 0.55f))
        drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.12f), Color.Transparent), Offset(w * 0.12f, h * 0.9f), r * 0.4f))
    }
    onDrawBehind { img() }
}

/** Domyślna skala fokusu i sprężyna animacji fokusu D-pad. */
const val TV_FOCUS_SCALE = 1.06f
const val TV_FOCUS_DAMPING = androidx.compose.animation.core.Spring.DampingRatioNoBouncy   // (7.17) bez odbicia – jak [pl.iptv.tv.ui.focusSpring]
const val TV_FOCUS_STIFFNESS = pl.iptv.tv.ui.FOCUS_STIFFNESS

/**
 * Skala fokusu D-pad na warstwie GPU: wartość z `animateFloatAsState` (sprężyna 0,78 / 420) czytana wyłącznie w bloku
 * `graphicsLayer` – klatki animacji tylko aktualizują warstwę (bez pomiaru, układu i rekompozycji dzieci).
 */
@Composable
fun Modifier.tvFocusScale(isFocused: Boolean, targetScale: Float = TV_FOCUS_SCALE): Modifier {
    // (7.28) tryb szybki: bez animacji, warstwa GPU tylko na jednym (zaznaczonym) elemencie
    if (pl.iptv.tv.ui.DevicePerformanceProfile.lite) return if (isFocused) this.graphicsLayer { scaleX = targetScale; scaleY = targetScale } else this
    val scale by animateFloatAsState(
        targetValue = if (isFocused) targetScale else 1f,
        animationSpec = pl.iptv.tv.ui.focusSpring(),   // (7.28) tween 90 ms zamiast sprężyny
        label = "tvFocusScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

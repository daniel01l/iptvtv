package pl.iptv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
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

/**
 * Obraz (logo kanału, miniatura, plakat) z zastępczym tekstem: inicjały / nazwa tylko, gdy adresu brak albo obrazu nie udało się
 * wczytać – nie pod spodem (wcześniej prześwitywały przez przezroczyste logo). Dekodowanie w rozmiarze kafelka ([sizePx]),
 * bez przenikania – mniej pracy przy przewijaniu rzędów.
 */
@Composable
fun TileImage(url: String, fallback: String, modifier: Modifier, contentScale: androidx.compose.ui.layout.ContentScale,
              fallbackStyle: TextStyle, sizePx: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var failed by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(url.isBlank()) }
    // (7.12) tekstura nigdy nie wychodzi poza kafelek (clip w warstwie – także przy skali fokusu i przejściach)
    androidx.compose.foundation.layout.Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (failed) androidx.compose.foundation.text.BasicText(fallback, style = fallbackStyle, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        else {
            val req = androidx.compose.runtime.remember(url, sizePx) {
                coil.request.ImageRequest.Builder(ctx).data(url).crossfade(false)
                    // (7.12) stały rozmiar docelowy + stały klucz w pamięci: bez ponownego dekodowania i migotania przy animacjach
                    .precision(coil.size.Precision.INEXACT).memoryCacheKey("$url@$sizePx")
                    .apply { if (sizePx > 0) size(sizePx) }.build()
            }
            coil.compose.AsyncImage(req, null, androidx.compose.ui.Modifier.matchParentSize(), contentScale = contentScale, onError = { failed = true })
        }
    }
}

/**
 * Płynne przewijanie list na pulpitach (X25, PS5, biblioteka aplikacji). Domyślnie na TV Compose trzyma zaznaczony element
 * w ~30% szerokości – każdy ruch pilotem przesuwał cały rząd („przeskakiwanie”). Tu lista przesuwa się tylko o tyle,
 * ile element wystaje poza widok (z marginesem na obwódkę), łagodną animacją.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
object SmoothScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        // (7.3) sprężyna bez odbicia: kolejny krok przejmuje prędkość poprzedniego – ciągły, płynny ruch przy przytrzymaniu strzałki
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = SCROLL_STIFFNESS)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = Ui.dp(28).toFloat()
        val end = offset + size
        return when {
            offset >= margin && end <= containerSize - margin -> 0f
            size + 2 * margin >= containerSize || offset < margin -> offset - margin
            else -> end - containerSize + margin
        }
    }
}

/** Pulpit z płynnym przewijaniem list ([SmoothScroll]). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SmoothScrolling(content: @Composable () -> Unit) =
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SmoothScroll, content = content)

/** Inicjały nazwy (np. kanału) – zastępstwo logo. */
fun initialsOf(s: String) = s.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** Pozycja panelu opcji PS5 ([switch] = przełącznik po prawej, null – zwykła pozycja). */
class Ps5Option(val label: String, val sub: String = "", val switch: Boolean? = null, val action: () -> Unit)

/** (7.3) Sztywność sprężyny przewijania list na pulpitach (ok. 250–300 ms do zatrzymania, bez odbicia). */
const val SCROLL_STIFFNESS = 450f

/**
 * (7.12) Wspólna sprężyna fokusu (skala, obwódka) na wszystkich pulpitach: przejmuje prędkość przerwanej animacji, więc przy
 * szybkim wciskaniu strzałek kursor płynie od elementu do elementu zamiast zaczynać każdą animację od zera (tween).
 */
fun <T> focusSpring() = androidx.compose.animation.core.spring<T>(dampingRatio = 0.86f, stiffness = 650f)

/** Animowana skala elementu z fokusem (odczyt tylko w warstwie rysowania, np. `graphicsLayer { scaleX = scale }`). */
@Composable
fun focusScale(focused: Boolean, to: Float, label: String = "fs"): androidx.compose.runtime.State<Float> =
    androidx.compose.animation.core.animateFloatAsState(if (focused) to else 1f, focusSpring(), label = label)

/**
 * (7.6) Wejście pulpitu (X25, PS5): treść przenika z lekkim uniesieniem (260 ms) – tylko w warstwie rysowania, bez ponownego
 * składania; w trybie słabszych urządzeń od razu.
 */
@Composable
fun Modifier.dashboardEnter(): Modifier {
    val low = runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (low) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        a.animateTo(1f, androidx.compose.animation.core.tween(DASHBOARD_ENTER_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return this.then(Modifier.graphicsLayer {
        alpha = a.value
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha     // (7.7) bez bufora pośredniego
        translationY = (1f - a.value) * 16.dp.toPx()
    })
}

const val DASHBOARD_ENTER_MS = 260

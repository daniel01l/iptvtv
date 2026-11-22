package pl.iptv.tv.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import pl.iptv.tv.ui.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * (7.17) Wspólny pasek nawigacji „Obsidian Stream” (Start, STube, Kanały): zwinięty [COLLAPSED] (ikony w pigułkach),
 * z fokusem rozwija się płynnie ([ANIM_MS], FastOutSlowIn) do [EXPANDED] z podpisami. Pasek leży NAD treścią (nie przesuwa
 * jej), a pod nim [AppNavigationScrim] przyciemnia treść – podpisy menu nigdy nie zlewają się z tekstem hero.
 * Odpowiednik dla ekranów na widokach: [AppNavigationRailViews].
 */
object AppNavRail {
    val COLLAPSED = 72.dp
    val EXPANDED = 240.dp
    const val ANIM_MS = 220
    /** Pigułka pozycji: wysokość, ikona i margines paska (ikona na środku zwiniętego paska). */
    val ITEM_H = 48.dp
    val ICON = 24.dp
    val PAD = 12.dp
    /** Scrim pod rozwiniętym paskiem: `#080A0F` 78 %. */
    val SCRIM = Color(0xFF080A0F).copy(alpha = 0.78f)
    const val SCRIM_ARGB = 0xC7080A0F.toInt()
}

/** Szerokość paska animowana sprężyście w czasie [AppNavRail.ANIM_MS] (słabsze urządzenia – od razu). */
@Composable
fun AppNavigationRail(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    collapsedWidth: Dp = AppNavRail.COLLAPSED,
    expandedWidth: Dp = AppNavRail.EXPANDED,
    lowPerf: Boolean = false,
    padding: Dp = AppNavRail.PAD,
    spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val w by animateDpAsState(if (expanded) expandedWidth else collapsedWidth,
        if (lowPerf) snap() else tween(AppNavRail.ANIM_MS, easing = FastOutSlowInEasing), label = "appNavRail")
    // tło: zwinięty – półprzezroczysty gradient od lewej krawędzi; rozwinięty – prawie pełna powierzchnia (pędzle w pamięci rysowania)
    Column(modifier.fillMaxHeight().width(w).clipToBounds().drawWithCache {
        val closed = Brush.horizontalGradient(0f to ObsidianTokens.BackgroundPrimary.copy(alpha = 0.86f), 1f to ObsidianTokens.BackgroundPrimary.copy(alpha = 0.40f))
        val open = Brush.horizontalGradient(0f to ObsidianTokens.BackgroundPrimary.copy(alpha = 0.98f), 1f to ObsidianTokens.SurfaceGlass)
        val edge = ObsidianTokens.SpecularStart
        val edgeW = 1.dp.toPx()
        onDrawBehind {
            drawRect(if (expanded) open else closed)
            drawRect(edge, topLeft = androidx.compose.ui.geometry.Offset(size.width - edgeW, 0f), size = androidx.compose.ui.geometry.Size(edgeW, size.height))
        }
    }.padding(vertical = 18.dp, horizontal = padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/**
 * Pozycja paska: pigułka [AppNavRail.ITEM_H] na całą szerokość – zaznaczona: wypełnienie akcentem, bieżąca sekcja: delikatny
 * akcent i kropka. Ikona zawsze w tym samym miejscu (środek zwiniętego paska), podpis tylko w rozwiniętym. [focusModifier] –
 * fokus i kliknięcie z ekranu (klucze, prośby o fokus).
 */
@Composable
fun AppNavigationRailItem(
    label: String,
    expanded: Boolean,
    focused: Boolean,
    selected: Boolean = false,
    focusModifier: Modifier,
    accent: Color = ObsidianTokens.AccentPrimary,
    live: Color = ObsidianTokens.BadgeLive,
    itemHeight: Dp = AppNavRail.ITEM_H,
    iconSize: Dp = AppNavRail.ICON,
    railWidth: Dp = AppNavRail.COLLAPSED,
    railPadding: Dp = AppNavRail.PAD,
    avatar: Boolean = false,
    icon: DrawScope.(Color) -> Unit,
) {
    val shape = RoundedCornerShape(itemHeight / 2)
    val tint = if (focused || selected) ObsidianTokens.TextPrimary else ObsidianTokens.TextSecondary
    val start = ((railWidth - railPadding * 2 - iconSize) / 2).coerceAtLeast(2.dp)
    Row(Modifier.fillMaxWidth().height(itemHeight).then(focusModifier).clip(shape)
        .background(when { focused -> accent; selected -> accent.copy(alpha = 0.18f); else -> Color.Transparent })
        .padding(start = start, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(iconSize).then(if (avatar) Modifier.clip(CircleShape).background(Brush.linearGradient(listOf(accent, live))).padding(4.dp) else Modifier)) {
            Canvas(Modifier.fillMaxSize()) { icon(if (avatar) Color.White else tint) }
        }
        if (expanded) BasicText(label, Modifier.padding(start = 16.dp).weight(1f),
            style = ObsidianTypography().cardTitle.copy(color = tint, fontSize = 15.sp, fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        // bieżąca sekcja przy zwiniętym pasku – kropka przy ikonie
        if (selected && !focused && !expanded) Box(Modifier.padding(start = 4.dp).size(5.dp).clip(CircleShape).background(live))
    }
}

/**
 * Przyciemnienie treści pod rozwiniętym paskiem ([AppNavRail.SCRIM]). Przezroczystość animowana w warstwie GPU
 * (`graphicsLayer`) – bez rekompozycji ekranu; nie przechwytuje fokusu ani dotyku.
 */
@Composable
fun AppNavigationScrim(visible: Boolean, lowPerf: Boolean = false, modifier: Modifier = Modifier) {
    val a = animateFloatAsState(if (visible) 1f else 0f, if (lowPerf) snap() else tween(AppNavRail.ANIM_MS, easing = FastOutSlowInEasing), label = "appNavScrim")
    Box(modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(AppNavRail.SCRIM))
}

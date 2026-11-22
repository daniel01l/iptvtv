package pl.iptv.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.iptv.tv.ui.theme.ObsidianTokens
import pl.iptv.tv.ui.theme.ObsidianTypography
import pl.iptv.tv.ui.theme.obsidianGlassCard
import pl.iptv.tv.ui.theme.tvFocusScale

/**
 * (7.17) OSD pełnoekranowego odtwarzacza w stylu „Obsidian Stream”: dolna winieta, pływający szklany panel (logo, nazwa,
 * NA ŻYWO, audycja, postęp 4 dp z godzinami / czasem trwania, pigułki akcji) i boczny panel wyboru ścieżek z prawej.
 *
 * Bez narzutu podczas oglądania: pozycja / postęp odczytywane ([OsdState.progress]) tylko w pętli działającej, gdy OSD
 * jest widoczny (co [PROGRESS_TICK_MS]); ukryty OSD nie wykonuje żadnych odczytów. Auto-ukrywanie po [AUTO_HIDE_MS]
 * bezczynności, licznik zerowany każdym klawiszem pilota ([OsdState.touch], `onPreviewKeyEvent`).
 */
object PlayerOsd {
    /**
     * OSD włączony (motyw Nowoczesny). W testach Robolectric domyślnie wyłączony – ekran odtwarzacza z warstwą Compose,
     * którego test nie zamyka, zostawiał zaplanowaną klatkę Compose, a czyszczenie pętli Robolectric między testami
     * blokowało kompozycję w kolejnych klasach (jak w IptvApp: elementy w tle pomijane w Robolectric). Testy OSD włączają go same.
     */
    @Volatile var enabled = android.os.Build.FINGERPRINT != "robolectric"
    const val AUTO_HIDE_MS = 5_000L
    const val PROGRESS_TICK_MS = 500L
    /** Czasy używane przez nakładkę (domyślnie [AUTO_HIDE_MS] / [PROGRESS_TICK_MS]; krótsze tylko w testach). */
    @Volatile var autoHideMs = AUTO_HIDE_MS
    @Volatile var progressTickMs = PROGRESS_TICK_MS
    /** Panel boczny: `#0F141F` 92 %. */
    val PANEL = Color(0xEB0F141F)
    val VIGNETTE = Color(0xE6080A0F)
    val TRACK = Color.White.copy(alpha = 0.18f)
}

/** Treść panelu: kanał na żywo ([live], godziny audycji) albo materiał z czasem trwania (VOD / STube / Stremio). */
@Immutable
data class OsdInfo(
    val name: String = "", val number: String = "", val logo: String = "", val live: Boolean = true,
    val title: String = "", val next: String = "", val badge: String = "",
)

/** Postęp: 0–1 (albo -1 – brak), napis z lewej (start / pozycja) i z prawej (koniec / czas trwania). */
@Immutable
data class OsdProgress(val fraction: Float = -1f, val left: String = "", val right: String = "")

@Immutable
data class OsdOption(val label: String, val selected: Boolean, val onClick: () -> Unit)
@Immutable
data class OsdSection(val header: String, val options: List<OsdOption>)
@Immutable
data class OsdPanel(val title: String, val sections: List<OsdSection>)

@Immutable
data class OsdAction(val key: String, val label: String, val icon: String, val onClick: () -> Unit)

/**
 * Stan nakładki (właściciel – ekran odtwarzacza; widoki wywołują [show] / [hide] / [onKey], Compose czyta stan).
 *
 * Zaznaczenie pigułek i pozycji panelu ścieżek to stan ([pillIndex], [panelIndex]) prowadzony klawiszami pilota przez
 * ekran odtwarzacza – bez fokusu Compose (fokus widoków odtwarzacza i fokus wewnątrz ComposeView nie rywalizują).
 */
class OsdState {
    var visible by mutableStateOf(false); private set
    /** Pigułki akcji z zaznaczeniem (OK w odtwarzaczu); bez – sam podgląd informacji (zmiana kanału). */
    var interactive by mutableStateOf(false); private set
    var info by mutableStateOf(OsdInfo())
    var panel by mutableStateOf<OsdPanel?>(null); private set
    var actions by mutableStateOf<List<OsdAction>>(emptyList())
    /** Zaznaczona pigułka (◄ ►) i pozycja panelu ścieżek (▲ ▼). */
    var pillIndex by mutableIntStateOf(0); private set
    var panelIndex by mutableIntStateOf(0); private set
    /** Źródło postępu – wywoływane tylko przy widocznym OSD. */
    var progress: () -> OsdProgress = { OsdProgress() }
    /** Ostatni klawisz / pokazanie (licznik auto-ukrywania). */
    var lastInput by mutableLongStateOf(0L); private set
    /** Liczba odczytów postępu (diagnostyka i testy – przy ukrytym OSD nie rośnie). */
    var progressReads = 0; private set
    var onHidden: (() -> Unit)? = null
    /** Bieżący postęp (aktualizowany pętlą tylko przy widocznym OSD). */
    /**
     * Bieżący postęp (pętla tylko przy widocznym OSD): ułamek osobno – czytany dopiero przy rysowaniu paska (tyknięcie tylko
     * przerysowuje pasek, bez rekompozycji), napisy (godziny audycji / pozycja) zmieniane tylko, gdy się różnią.
     */
    internal val progressFraction = androidx.compose.runtime.mutableFloatStateOf(-1f)
    var progressLabels by mutableStateOf("" to ""); private set
    /** Ostatni odczyt postępu (testy i diagnostyka). */
    val progressNow: OsdProgress get() = OsdProgress(progressFraction.floatValue, progressLabels.first, progressLabels.second)

    /**
     * Zasięg pętli OSD – ekran odtwarzacza (`lifecycleScope`, główny wątek): pętle kończą się z ukryciem OSD
     * i z zamknięciem ekranu; bez zasięgu – sam stan, bez pętli.
     */
    var scope: kotlinx.coroutines.CoroutineScope? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var hideJob: kotlinx.coroutines.Job? = null

    fun show(interactive: Boolean) {
        if (interactive && !this.interactive) pillIndex = 0
        this.interactive = interactive || (visible && this.interactive)
        visible = true; touch(); startLoops()
    }
    fun hide() {
        progressJob?.cancel(); hideJob?.cancel(); progressJob = null; hideJob = null
        if (!visible && panel == null) return
        visible = false; interactive = false; panel = null; onHidden?.invoke()
    }
    fun touch() { lastInput = android.os.SystemClock.uptimeMillis() }
    fun openPanel(p: OsdPanel) {
        panelIndex = p.sections.flatMap { it.options }.indexOfFirst { it.selected }.coerceAtLeast(0)
        panel = p; visible = true; touch(); startLoops()
    }
    fun closePanel() { if (panel != null) { panel = null; touch() } }

    /**
     * Klawisz pilota przy OSD z pigułkami / panelem ([KeyEvent.ACTION_DOWN]): panel – ▲▼ pozycja, OK wybór (i zamknięcie),
     * ◄► bez zmian; pigułki – ◄► zaznaczenie, OK akcja. Zwraca true, gdy klawisz obsłużono (każdy zeruje auto-ukrywanie).
     */
    fun onKey(code: Int): Boolean {
        touch()
        val p = panel
        if (p != null) {
            val opts = p.sections.flatMap { it.options }
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> panelIndex = (panelIndex - 1).coerceAtLeast(0)
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> panelIndex = (panelIndex + 1).coerceAtMost(opts.size - 1)
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> opts.getOrNull(panelIndex)?.let { it.onClick(); closePanel() }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {}
                else -> return false
            }
            return true
        }
        if (!interactive) return false
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> pillIndex = (pillIndex - 1).coerceAtLeast(0)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> pillIndex = (pillIndex + 1).coerceAtMost((actions.size - 1).coerceAtLeast(0))
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> actions.getOrNull(pillIndex)?.onClick?.invoke()
            else -> return false
        }
        return true
    }

    /**
     * Pętle tylko przy widocznym OSD: postęp `while (visible) { odczyt; delay(500) }` (ukryty OSD – zero odczytów pozycji)
     * i auto-ukrywanie po 5 s od ostatniego klawisza (otwarty panel ścieżek czeka na wybór; odliczanie od nowa po zamknięciu).
     */
    private fun startLoops() {
        val sc = scope ?: return
        if (progressJob?.isActive != true) progressJob = sc.launch {
            while (visible) {
                val p = readProgress()
                progressFraction.floatValue = p.fraction
                (p.left to p.right).let { if (it != progressLabels) progressLabels = it }
                kotlinx.coroutines.delay(PlayerOsd.progressTickMs)
            }
        }
        if (hideJob?.isActive != true) hideJob = sc.launch {
            while (visible) {
                val left = lastInput + PlayerOsd.autoHideMs - android.os.SystemClock.uptimeMillis()
                if (left <= 0) { if (panel == null) { hide(); break }; touch() }
                kotlinx.coroutines.delay(left.coerceIn(50L, PlayerOsd.autoHideMs))
            }
        }
    }
    internal fun readProgress(): OsdProgress { progressReads++; return progress() }
}

@Composable
fun PlayerOsdOverlay(state: OsdState, lowPerf: Boolean = false) {
    val anim = if (lowPerf) 0 else 180
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { state.touch(); false }) {
        AnimatedVisibility(state.visible, Modifier.fillMaxSize(), enter = fadeIn(tween(anim)), exit = fadeOut(tween(anim))) {
            Box(Modifier.fillMaxSize()) {
                // dolna winieta: obraz przechodzi w ciemność pod panelem (tekst czytelny na każdym obrazie)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PlayerOsd.VIGNETTE))))
                OsdCard(state, Modifier.align(Alignment.BottomCenter))
            }
        }
        val p = state.panel
        AnimatedVisibility(p != null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.36f),
            enter = slideInHorizontally(tween(if (lowPerf) 0 else 220)) { it } + fadeIn(tween(anim)),
            exit = slideOutHorizontally(tween(if (lowPerf) 0 else 200)) { it } + fadeOut(tween(anim))) {
            val last = remember { mutableStateOf(p) }
            if (p != null) last.value = p
            last.value?.let { TrackPanel(it, state) }
        }
    }
}

@Composable
private fun OsdCard(state: OsdState, modifier: Modifier) {
    val t = ObsidianTypography()
    val info = state.info
    // postęp z pętli [OsdState] (działa tylko przy widocznym OSD); ułamek – tylko w fazie rysowania paska
    val labels = state.progressLabels
    val fraction = state.progressFraction
    Column(modifier.padding(32.dp).fillMaxWidth().obsidianGlassCard(false, 16.dp).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OsdLogo(info)
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText((if (info.number.isNotEmpty()) info.number + "  " else "") + info.name, Modifier.weight(1f, fill = false),
                        style = t.sectionTitle.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (info.live) BasicText("NA ŻYWO", Modifier.padding(start = 12.dp).clip(RoundedCornerShape(10.dp)).background(ObsidianTokens.BadgeLive)
                        .padding(horizontal = 9.dp, vertical = 3.dp), style = t.badge)
                    if (info.badge.isNotEmpty()) BasicText(info.badge, Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp))
                        .background(ObsidianTokens.AccentPrimary).padding(horizontal = 9.dp, vertical = 3.dp), style = t.badge)
                }
                BasicText(info.title.ifEmpty { "Brak informacji o programie" }, Modifier.padding(top = 4.dp),
                    style = t.cardTitle.copy(fontSize = 18.sp, color = ObsidianTokens.TextPrimary.copy(alpha = 0.92f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.next.isNotEmpty()) BasicText(info.next, Modifier.padding(top = 2.dp), style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // postęp 4 dp: aktywny – akcent, tło – biel 18 %
        if (labels.first.isNotEmpty() || labels.second.isNotEmpty()) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).drawBehind {
                val f = fraction.floatValue.coerceIn(0f, 1f)
                drawRect(PlayerOsd.TRACK)
                drawRect(ObsidianTokens.AccentPrimary, size = Size(size.width * f, size.height))
            })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                BasicText(labels.first, style = t.meta)
                Spacer(Modifier.weight(1f))
                BasicText(labels.second, style = t.meta)
            }
        }
        if (state.actions.isNotEmpty()) OsdActions(state)
    }
}

@Composable
private fun OsdLogo(info: OsdInfo) {
    val ctx = LocalContext.current
    var failed by remember(info.logo) { mutableStateOf(info.logo.isBlank()) }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(112.dp, 64.dp).clip(shape).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
        if (failed) BasicText(initialsOf(info.name), style = ObsidianTypography().sectionTitle.copy(color = ObsidianTokens.TextSecondary))
        else {
            // rozmiar dekodowania = rozmiar pola logo (bez pełnej rozdzielczości w pamięci)
            val req = remember(info.logo) {
                coil.request.ImageRequest.Builder(ctx).data(info.logo).size(Ui.dp(112), Ui.dp(64)).scale(coil.size.Scale.FIT)
                    .precision(coil.size.Precision.INEXACT).crossfade(false).build()
            }
            coil.compose.AsyncImage(req, info.name, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit, onError = { failed = true })
        }
    }
}

@Composable
private fun OsdActions(state: OsdState) {
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        state.actions.forEachIndexed { i, a -> OsdPill(a, focused = state.interactive && state.panel == null && state.pillIndex == i) }
    }
}

/** Pigułka akcji: zaznaczona – biała z ciemnym tekstem, obwódka i poświata, skala na warstwie GPU ([tvFocusScale]). */
@Composable
private fun OsdPill(a: OsdAction, focused: Boolean) {
    val h = 44.dp
    val ink = if (focused) ObsidianTokens.BackgroundPrimary else ObsidianTokens.TextPrimary
    Row(Modifier.height(h).tvFocusScale(focused)
        .x25Clickable(a.onClick)
        .obsidianGlassCard(focused, h / 2)
        .clip(RoundedCornerShape(h / 2))
        .background(if (focused) Color.White else Color.Transparent)
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { ModernIcons.draw(this, a.icon, ink) }
        BasicText(a.label.uppercase(), Modifier.padding(start = 10.dp), style = ObsidianTypography().badge.copy(fontSize = 14.sp, color = ink),
            maxLines = 1, softWrap = false)
    }
}

/** Panel ścieżek z prawej (`#0F141F` 92 %): obraz na środku ekranu pozostaje odsłonięty; zaznaczona pozycja zawsze widoczna. */
@Composable
private fun TrackPanel(p: OsdPanel, state: OsdState) {
    val t = ObsidianTypography()
    // wiersze listy: nagłówki sekcji i pozycje (indeks pozycji → indeks wiersza do przewinięcia)
    val rows = remember(p) {
        buildList<Pair<String?, Int>> {
            var n = 0
            p.sections.forEach { s -> if (s.header.isNotEmpty()) add(s.header to -1); s.options.forEach { _ -> add(null to n++) } }
        }
    }
    val options = remember(p) { p.sections.flatMap { it.options } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize().background(PlayerOsd.PANEL).padding(horizontal = 28.dp, vertical = 32.dp)) {
        BasicText(p.title, style = t.sectionTitle.copy(fontSize = 22.sp))
        LazyColumn(Modifier.padding(top = 12.dp).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows.size, key = { i -> rows[i].first?.let { "h:$it" } ?: "o:${rows[i].second}" }, contentType = { i -> if (rows[i].first != null) "header" else "option" }) { i ->
                val (header, idx) = rows[i]
                if (header != null) BasicText(header.uppercase(), Modifier.padding(top = 14.dp, bottom = 4.dp),
                    style = t.meta.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Bold))
                else TrackRow(options[idx], focused = state.panelIndex == idx) { options[idx].onClick(); state.closePanel() }
            }
        }
    }
    LaunchedEffect(state.panelIndex) {
        val row = rows.indexOfFirst { it.second == state.panelIndex }
        if (row >= 0) runCatching { list.animateScrollToItem((row - 2).coerceAtLeast(0)) }
    }
}

@Composable
private fun TrackRow(o: OsdOption, focused: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().height(48.dp).x25Clickable(onClick)
        .clip(shape).background(when { focused -> ObsidianTokens.AccentPrimary; o.selected -> ObsidianTokens.AccentPrimary.copy(alpha = 0.16f); else -> Color.Transparent })
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(o.label, Modifier.weight(1f), style = ObsidianTypography().cardTitle.copy(fontSize = 16.sp,
            color = if (focused || o.selected) ObsidianTokens.TextPrimary else ObsidianTokens.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (o.selected) BasicText("✓", style = ObsidianTypography().cardTitle.copy(color = ObsidianTokens.TextPrimary))
    }
}

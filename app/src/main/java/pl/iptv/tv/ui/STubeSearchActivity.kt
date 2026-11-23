package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.StubeSearch
import pl.iptv.tv.ui.theme.ObsidianTokens
import pl.iptv.tv.ui.theme.obsidianGlassCard
import pl.iptv.tv.ui.theme.tvFocusScale

/**
 * (7.17) Panel wyszukiwania STube na pilota (pasek „Szukaj w STube” i lupa w pasku nawigacji STube):
 * - lewa kolumna ([LEFT_FRACTION], szkło 16 dp): pole zapytania z migającym kursorem, wyczyszczeniem i mikrofonem
 *   (`RecognizerIntent`), pigułki filtrów ([StubeSearch.Filter]), klawiatura ekranowa D-pad ([LETTERS] / [SYMBOLS], spacja,
 *   usuń, wyczyść, przełącznik „123/#+=”) z fokusem startowym, historia wyszukiwania (OK – wpisuje, przytrzymanie – usuwa);
 * - prawa kolumna: wyniki na żywo ([STubeSearchViewModel]) w siatce 2 kolumn, pusta fraza – ostatnio oglądane.
 * Systemowa klawiatura się nie pokazuje (brak pola edycji, `SOFT_INPUT_STATE_ALWAYS_HIDDEN`, kontroler IME schowany);
 * fizyczna klawiatura i cyfry pilota też piszą.
 */
class STubeSearchActivity : BaseActivity() {
    override val pausesLiveTv = true
    val vm: STubeSearchViewModel by viewModels()

    /** Tryb klawiatury: litery / znaki (testy). */
    var symbols by mutableStateOf(false); private set

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> vm.setQuery(t); vm.remember(t) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { vm.setQuery(it) }
        setContent { Screen() }
    }

    override fun onResume() { super.onResume(); vm.refreshHistory() }

    /** Fizyczna klawiatura / cyfry pilota: znaki dopisywane do zapytania, Backspace usuwa (strzałki, OK i WSTECZ – jak zwykle). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> { vm.backspace(); return true }
                KeyEvent.KEYCODE_SEARCH -> { startVoice(); return true }
            }
            val c = event.unicodeChar
            if (c != 0 && !Character.isISOControl(c) && event.keyCode !in NAV_KEYS) { vm.type(String(Character.toChars(c))); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startVoice() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL").putExtra(RecognizerIntent.EXTRA_PROMPT, "Powiedz, czego szukasz w STube")
        runCatching { voice.launch(speech) }.onFailure { I18n.makeToast(this, "Wyszukiwanie głosowe jest niedostępne na tym urządzeniu", android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** Otwarcie wyniku: film – odtwarzacz; kanał / playlista – STube z jej filmami. Hasło trafia do historii. */
    fun open(item: StubeSearch.Item) {
        vm.remember()
        when (item) {
            is StubeSearch.Item.Video -> startActivity(YtPlayerActivity.intent(this, item.video))
            is StubeSearch.Item.Channel -> { startActivity(stube(STubeScreen.PL_CHANNEL + item.id, item.name)); finish() }
            is StubeSearch.Item.Playlist -> { startActivity(stube(item.playlist.id, item.playlist.title)); finish() }
        }
    }

    private fun stube(id: String, title: String) = Intent(this, STubeActivity::class.java)
        .putExtra(STubeActivity.EXTRA_PLAYLIST, id).putExtra(STubeActivity.EXTRA_PLAYLIST_TITLE, title)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    // ================= UI =================

    private val accent: Color get() = if (pl.iptv.tv.ui.theme.ObsidianViews.active) ObsidianTokens.AccentPrimary else Color(Ui.accent)

    @Composable
    private fun Screen() {
        val keyboardStart = remember { FocusRequester() }
        val ime = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { ime?.hide(); withFrameNanos { }; runCatching { keyboardStart.requestFocus() } }
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Column(Modifier.weight(LEFT_FRACTION).fillMaxHeight().obsidianGlassCard(false, 16.dp, accent)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
                QueryField()
                Filters()
                Keyboard(keyboardStart)
                History()
            }
            Spacer(Modifier.width(20.dp))
            Box(Modifier.weight(1f - LEFT_FRACTION).fillMaxHeight()) { Results() }
        }
    }

    @Composable
    private fun QueryField() {
        val q by vm.searchQuery.collectAsState()
        // migający kursor: przełącznik co CURSOR_MS (dwie klatki na cykl zamiast ciągłej animacji); w Robolectric bez migania
        val cursorOn = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        if (BLINK) LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(CURSOR_MS); cursorOn.floatValue = 1f - cursorOn.floatValue } }
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(ObsidianTokens.SurfaceElevated)
            .padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(20.dp)) { X25Icons.draw(this, X25Icons.SEARCH, ObsidianTokens.TextSecondary) }
            Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(q.ifEmpty { "Szukaj w STube" }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 18.sp, color = if (q.isEmpty()) ObsidianTokens.TextSecondary else ObsidianTokens.TextPrimary, fontWeight = FontWeight.Medium))
                // migający kursor (tylko przezroczystość w warstwie – bez rekompozycji)
                Box(Modifier.padding(start = 2.dp).width(2.dp).height(22.dp).graphicsLayer { alpha = cursorOn.floatValue }.background(accent))
            }
            if (q.isNotEmpty()) RoundButton("Wyczyść", PlayerIcon.CLOSE) { vm.clear() }
            RoundButton("Wyszukiwanie głosowe", PlayerIcon.MIC) { startVoice() }
        }
    }

    @Composable
    private fun RoundButton(desc: String, icon: String, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val d = remember(icon) { PlayerIcon(icon) }
        Box(Modifier.padding(start = 4.dp).size(40.dp).raiseOnFocus().tvFocusScale(focused, 1.1f)
            .onFocusChanged { focused = it.isFocused }.x25Clickable(onClick).focusable()
            .clip(CircleShape).background(if (focused) accent else Color.White.copy(alpha = 0.08f))
            .semanticsLabel(desc), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) { drawIntoCanvas { c -> d.setBounds(0, 0, size.width.toInt(), size.height.toInt()); d.draw(c.nativeCanvas) } }
        }
    }

    @Composable
    private fun Filters() {
        val sel by vm.selectedFilter.collectAsState()
        LazyRow(Modifier.fillMaxWidth().padding(top = 6.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StubeSearch.Filter.entries, key = { it.name }, contentType = { "filter" }) { f -> Pill(f.label, selected = f == sel) { vm.setFilter(f) } }
        }
    }

    @Composable
    private fun Pill(label: String, selected: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val h = 34.dp
        Box(Modifier.height(h).raiseOnFocus().tvFocusScale(focused).onFocusChanged { focused = it.isFocused }
            .x25Clickable(onClick, onLong).focusable()
            .obsidianGlassCard(focused, h / 2, accent).clip(RoundedCornerShape(h / 2))
            .background(when { focused -> Color.White; selected -> accent; else -> Color.Transparent })
            .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            BasicText(label, maxLines = 1, softWrap = false, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (focused) ObsidianTokens.BackgroundPrimary else ObsidianTokens.TextPrimary))
        }
    }

    @Composable
    private fun Keyboard(start: FocusRequester) {
        val rows = if (symbols) SYMBOLS else LETTERS
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    row.forEachIndexed { i, ch ->
                        val s = ch.toString()
                        Key(s, if (r == 1 && i == 0) start else null) { vm.type(s) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                Key(if (symbols) "ABC" else "123/#+=", weight = 1.6f, small = true) { symbols = !symbols }
                Key("SPACJA", weight = 2.6f, small = true) { vm.type(" ") }
                Key("USUŃ", weight = 1.3f, small = true) { vm.backspace() }
                Key("WYCZYŚĆ", weight = 1.5f, small = true) { vm.clear() }
            }
        }
    }

    @Composable
    private fun RowScope.Key(label: String, req: FocusRequester? = null, weight: Float = 1f, small: Boolean = false, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(10.dp)
        Box(Modifier.weight(weight).height(KEY_H).raiseOnFocus().tvFocusScale(focused, 1.12f)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .onFocusChanged { focused = it.isFocused }.x25Clickable(onClick).focusable()
            .obsidianGlassCard(focused, 10.dp, accent).clip(shape).background(if (focused) accent else Color.White.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center) {
            BasicText(label, maxLines = 1, softWrap = false, style = TextStyle(fontSize = if (small) 11.sp else 16.sp, fontWeight = FontWeight.SemiBold,
                color = ObsidianTokens.TextPrimary, textAlign = TextAlign.Center))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun History() {
        val hist by vm.history.collectAsState()
        if (hist.isEmpty()) return
        BasicText("Historia wyszukiwania", Modifier.padding(top = 18.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = ObsidianTokens.TextSecondary, letterSpacing = 0.6.sp))
        BasicText("OK – szukaj · przytrzymaj OK – usuń", Modifier.padding(bottom = 6.dp), style = TextStyle(fontSize = 11.sp, color = ObsidianTokens.TextSecondary.copy(alpha = 0.7f)))
        FlowRow(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hist.forEach { h -> androidx.compose.runtime.key(h) { Pill(h, selected = false, onLong = { vm.forget(h) }) { vm.setQuery(h) } } }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Results() {
        val state by vm.results.collectAsState()
        var last by remember { mutableStateOf<List<StubeSearch.Item>>(emptyList()) }
        var watched by remember { mutableStateOf<List<StubeSearch.Item>>(emptyList()) }
        LaunchedEffect(Unit) { watched = runCatching { StubeHistory.watched().take(RECENT_MAX).map { StubeSearch.Item.Video(it.toVideo()) } }.getOrDefault(emptyList()) }
        val s = state
        if (s is StubeSearchState.Results) last = s.items
        val (header, items) = when (s) {
            StubeSearchState.Idle -> (if (watched.isEmpty()) "Zacznij pisać – wyniki pojawią się od razu" else "Ostatnio oglądane w STube") to watched
            is StubeSearchState.Loading -> "Szukam: ${s.query}…" to last
            is StubeSearchState.Results -> (if (s.items.isEmpty()) "Brak wyników: ${s.query}" else "Wyniki: ${s.query}") to s.items
            is StubeSearchState.Error -> s.message to emptyList()
        }
        Column(Modifier.fillMaxSize()) {
            BasicText(header, Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = ObsidianTokens.TextPrimary))
            SmoothScrolling {
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(GRID_PAD),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(items, key = { it.key }, contentType = { it::class.simpleName }) { ResultCard(it) }
                }
            }
        }
    }

    @Composable
    private fun ResultCard(item: StubeSearch.Item) {
        var focused by remember { mutableStateOf(false) }
        val r = 16.dp
        Column(Modifier.fillMaxWidth().zIndexIf(focused)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).tvFocusScale(focused).onFocusChanged { focused = it.isFocused }
                .x25Clickable({ open(item) }).focusable().obsidianGlassCard(focused, r, accent).clip(RoundedCornerShape(r))) {
                val channel = item is StubeSearch.Item.Channel
                TileImage(item.thumb, initialsOf(item.title), if (channel) Modifier.align(Alignment.Center).fillMaxHeight(0.78f).aspectRatio(1f).clip(CircleShape) else Modifier.fillMaxSize(),
                    ContentScale.Crop, TextStyle(fontSize = 22.sp, color = ObsidianTokens.TextSecondary, fontWeight = FontWeight.Bold), sizePx = Ui.dp(320), heightPx = Ui.dp(180))
                val badge = when (item) {
                    is StubeSearch.Item.Video -> if (item.live) "NA ŻYWO" else STubeScreen.duration(item.video.duration)
                    is StubeSearch.Item.Playlist -> "PLAYLISTA"
                    is StubeSearch.Item.Channel -> ""
                }
                if (badge.isNotEmpty()) BasicText(badge, Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (badge == "NA ŻYWO") ObsidianTokens.BadgeLive else Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
            BasicText(item.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color.White else ObsidianTokens.TextPrimary.copy(alpha = 0.9f)))
            BasicText(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 12.sp, color = ObsidianTokens.TextSecondary))
        }
    }

    private fun Modifier.zIndexIf(on: Boolean) = this.then(androidx.compose.ui.Modifier.zIndex(if (on) 1f else 0f))
    private fun Modifier.semanticsLabel(d: String) = this.then(androidx.compose.ui.Modifier.semantics { contentDescription = d })

    companion object {
        const val EXTRA_QUERY = "query"
        const val LEFT_FRACTION = 0.38f
        val KEY_H: Dp = 38.dp
        val KEY_GAP: Dp = 5.dp
        val GRID_PAD: Dp = 20.dp
        const val RECENT_MAX = 20
        const val CURSOR_MS = 530L
        /** Miganie kursora (w testach Robolectric wyłączone – ciągłe klatki animacji blokowały kolejne testy). */
        @Volatile var BLINK = android.os.Build.FINGERPRINT != "robolectric"
        /** Klawiatura QWERTY z cyframi i polskimi literami; tryb znaków – „123/#+=”. */
        val LETTERS = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm-.,", "ąćęłńóśźż")
        val SYMBOLS = listOf("1234567890", "!@#$%&*()_", "+=/\\:;\"?<>", "[]{}~^|`€£", "§°«»¿¡•…·")
        private val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK)

        fun intent(c: Context, query: String = "") = Intent(c, STubeSearchActivity::class.java).putExtra(EXTRA_QUERY, query)
    }
}

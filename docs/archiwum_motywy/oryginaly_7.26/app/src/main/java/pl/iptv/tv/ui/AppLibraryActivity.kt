package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as NativeKey
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.X25Action

/**
 * Biblioteka aplikacji (tryb launchera i pulpity X25 / PS5): wszystkie aplikacje zainstalowane na urządzeniu w siatce z ikonami
 * – jak „Biblioteka gier” na konsoli. Pierwszy kafelek – Ustawienia systemu. OK – uruchom; MENU lub przytrzymanie OK – opcje:
 * dodaj / usuń z ekranu głównego PS5, informacje o aplikacji (stamtąd odinstalowanie). Lista odświeżana przy każdym powrocie
 * (zainstalowane / usunięte aplikacje). Kolory z motywu ([PS5ThemeProvider] – wartości z [Ui]).
 */
class AppLibraryActivity : BaseActivity() {
    override val videoBackground = false

    /** Aplikacja w siatce; [intent] = null – Ustawienia systemu. */
    @androidx.compose.runtime.Immutable
    data class Entry(val id: String, val label: String, val icon: ImageBitmap?, val intent: Intent)

    private var apps by mutableStateOf<List<Entry>>(emptyList())
    private var loaded by mutableStateOf(false)
    private var clock by mutableStateOf("")
    private var focused: Entry? = null
    private val first = FocusRequester()

    // ---- testy ----
    val labels get() = apps.map { it.label }
    val focusedLabel get() = focused?.label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvResolution1080pProvider { PS5ThemeProvider { SmoothScrolling { Library() } } } }
    }

    override fun onResume() {
        super.onResume()
        clock = Fmt.time(System.currentTimeMillis())
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val list = withContext(Dispatchers.IO) {
            val pm = packageManager; val px = Ui.dp(112)
            fun icon(i: Intent) = runCatching { (i.component?.let { pm.getActivityIcon(it) } ?: pm.getDefaultActivityIcon()).toBitmap(px, px).asImageBitmap() }.getOrNull()
            val settings = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            listOf(Entry(SETTINGS_ID, "Ustawienia systemu", null, settings)) +
                SystemTools.installedApps(this@AppLibraryActivity).map { e -> Entry(e.intent.component?.flattenToString() ?: e.label, e.label, icon(e.intent), e.intent) }
        }
        if (list.map { it.id } != apps.map { it.id }) apps = list
        loaded = true
    }

    fun launch(e: Entry) {
        X25Theme.selectSound(this)
        if (runCatching { startActivity(e.intent) }.isFailure) X25Actions.toast(this, "Nie można uruchomić: ${e.label}")
    }

    /** Testy: uruchomienie po nazwie. */
    fun launch(label: String) { apps.firstOrNull { it.label == label }?.let { launch(it) } }

    /** Opcje aplikacji: otwórz, ekran główny PS5 (dodaj / usuń), informacje o aplikacji. */
    fun options(e: Entry) {
        if (e.id == SETTINGS_ID) { launch(e); return }
        val onHome = e.id in Prefs.ps5Apps
        val items = buildList<Pair<String, () -> Unit>> {
            add("▶ Otwórz" to { launch(e) })
            // ekran główny PS5 (także pulpit trybu launchera przy motywach bez własnego pulpitu)
            if (!X25Theme.active) add(if (onHome) "✖ Usuń z ekranu głównego" to { Prefs.ps5Apps = Prefs.ps5Apps - e.id; X25Actions.toast(this@AppLibraryActivity, "Usunięto z ekranu głównego") }
                else "＋ Dodaj do ekranu głównego" to { Prefs.ps5Apps = Prefs.ps5Apps + e.id; X25Actions.toast(this@AppLibraryActivity, "Dodano: ${e.label}") })
            add("ℹ Informacje o aplikacji" to {
                val pkg = e.id.substringBefore('/')
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))) }
            })
        }
        Dialogs.menu(this, e.label, items)
    }

    override fun dispatchKeyEvent(event: NativeKey): Boolean {
        if (event.keyCode == NativeKey.KEYCODE_MENU) { if (event.action == NativeKey.ACTION_UP) focused?.let { options(it) }; return true }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Library() {
        val c = LocalPs5.current
        val compact = LocalConfiguration.current.screenHeightDp < 520
        val side = if (LocalConfiguration.current.screenWidthDp < 600) 16.dp else 56.dp
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = if (compact) 10.dp else 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Aplikacje", style = Ps5Type.display.copy(fontSize = if (compact) 24.sp else 32.sp))
                    if (loaded) BasicText("${apps.size - 1}", Modifier.padding(start = 14.dp), style = Ps5Type.title.copy(color = c.text2))
                    Spacer(Modifier.weight(1f))
                    BasicText(clock, style = Ps5Type.tab.copy(fontSize = if (compact) 17.sp else 21.sp))
                }
                LazyVerticalGrid(GridCells.Adaptive(if (compact) 110.dp else 150.dp), Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = side, end = side, top = 18.dp, bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(apps, key = { _, e -> e.id }, contentType = { _, _ -> "app" }) { i, e -> AppItem(e, if (i == 0) first else null, compact) }
                }
            }
            AndroidView({ Ps5Hints(it, LIBRARY_HINTS) }, Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = 8.dp))
        }
        LaunchedEffect(loaded) { if (loaded) { delay(50); runCatching { first.requestFocus() } } }
    }

    @Composable
    private fun AppItem(e: Entry, req: FocusRequester?, compact: Boolean) {
        val c = LocalPs5.current
        var on by remember { mutableStateOf(false) }
        val scale by focusScale(on, 1.1f, "lib")
        val box = if (compact) 72.dp else 104.dp
        Column(Modifier.zIndex(if (on) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(box)
                .then(if (req != null) Modifier.focusRequester(req) else Modifier)
                .onFocusChanged { if (it.isFocused != on) { on = it.isFocused; if (it.isFocused) { focused = e; X25Theme.navSound(this@AppLibraryActivity) } } }
                .x25Clickable(onClick = { launch(e) }, onLong = { options(e) })
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .ps5Frame(on, 20.dp, c.ring, c.glow, halo = !c.lowPerf)
                .clip(RoundedCornerShape(20.dp))
                .background(if (e.id == SETTINGS_ID) c.glow.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f))
                .padding(box * 0.16f)) {
                if (e.icon != null) Image(e.icon, e.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Canvas(Modifier.fillMaxSize()) { X25Icons.draw(this, X25Action.SETTINGS, Color.White) }
            }
            BasicText(e.label, Modifier.width(box + 30.dp).padding(top = 8.dp), style = Ps5Type.body.copy(color = if (on) Color.White else c.text2, textAlign = TextAlign.Center),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    companion object {
        const val SETTINGS_ID = "system:settings"
        val LIBRARY_HINTS = listOf(Ps5Hints.X to "Uruchom", Ps5Hints.O to "Wstecz", Ps5Hints.MENU to "Opcje")
    }
}

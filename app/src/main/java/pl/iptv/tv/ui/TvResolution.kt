package pl.iptv.tv.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * (7.20) Stała siatka projektowa interfejsu na telewizorach: cały ekran = **960 × 540 dp** (1920 × 1080 px przy gęstości 2,0),
 * niezależnie od DPI zgłaszanego przez system TV (np. 213 / 240 / 320 / 640 dpi na TV HD, Full HD i 4K). Na TV gęstość
 * liczona jest z rzeczywistego rozmiaru okna ([densityFor]: szerokość / 960, wysokość / 540 – mniejsza z dwóch, żeby siatka
 * zawsze mieściła się w całości), czcionki w skali 1,0 – każdy ekran, okno, siatka plakatów, OSD i pasek boczny ma te same
 * proporcje na każdym telewizorze.
 *
 * Gdzie działa: widoki – przez konfigurację kontekstu każdej aktywności ([wrap] w `attachBaseContext`) i [Ui.dp]; Compose –
 * ta sama konfiguracja oraz [TvResolution1080pProvider] na szczycie każdego drzewa (LocalDensity). Telefony i tablety – bez
 * zmian (systemowa gęstość, ergonomia dotyku). Obraz wideo nie jest skalowany: powierzchnia odtwarzacza dostaje pełny
 * bufor rozdzielczości materiału ([nativeVideoBuffer]) – 4K na matrycy 4K przy interfejsie w siatce 1080p.
 *
 * (7.22) Każda ścieżka jest odporna na zimny start telefonu: wykrywanie TV w [DeviceDisplayProfile.detectTv] (try-catch),
 * okno 0 × 0 nigdy nie daje gęstości 0 / NaN, telefon (także pionowo 9:16) zawsze w natywnej gęstości, wyjątek przy
 * konfiguracji kontekstu – kontekst bez zmian zamiast zamknięcia aplikacji.
 */
object TvResolution {
    const val DESIGN_W_DP = 960f
    const val DESIGN_H_DP = 540f
    /** Wzorcowa rozdzielczość projektowa (px przy gęstości 2,0). */
    const val DESIGN_W_PX = 1920
    const val DESIGN_H_PX = 1080
    private const val TAG = "TvResolution"

    /** Gęstość siatki TV (null – telefon / tablet albo jeszcze nie wyliczona: gęstość systemu). */
    @Volatile var density: Float? = null; private set
    /** Testy: wymuszenie trybu TV (true / false) zamiast wykrywania. */
    @Volatile var forceTv: Boolean? = null

    /** Android TV / Google TV – (7.22) wspólne, bezpieczne wykrywanie [DeviceDisplayProfile.detectTv]. */
    fun isTv(c: Context): Boolean = DeviceDisplayProfile.detectTv(c)

    /** Gęstość, przy której okno [widthPx] × [heightPx] to dokładnie siatka 960 × 540 dp (dłuższy bok = 960 dp). */
    fun densityFor(widthPx: Int, heightPx: Int, gridW: Float = DESIGN_W_DP, gridH: Float = DESIGN_H_DP): Float {
        // (7.22) zimny start: okno potrafi mieć jeszcze 0 × 0 – nigdy gęstość 0 / NaN / ∞ (wyjątki układu i Compose)
        if (widthPx <= 0 || heightPx <= 0 || gridW <= 0f || gridH <= 0f) return DESIGN_W_PX / DESIGN_W_DP
        val long = maxOf(widthPx, heightPx).toFloat(); val short = minOf(widthPx, heightPx).toFloat()
        val d = minOf(long / gridW, short / gridH)
        return if (d.isNaN() || d.isInfinite()) DESIGN_W_PX / DESIGN_W_DP else d.coerceIn(0.5f, 8f)
    }

    /** densityDpi dla [density] (160 dpi = 1,0). */
    fun densityDpiFor(d: Float): Int = Math.round(d * android.util.DisplayMetrics.DENSITY_DEFAULT)

    /** Rzeczywisty rozmiar okna aplikacji w pikselach (pełny ekran TV); (7.22) zawsze dodatni – inaczej wymiary wzorcowe. */
    private fun windowPx(c: Context): Pair<Int, Int> {
        val (w, h) = try { rawWindowPx(c) } catch (_: Throwable) { DESIGN_W_PX to DESIGN_H_PX }
        return if (w > 0 && h > 0) w to h else DESIGN_W_PX to DESIGN_H_PX
    }

    private fun rawWindowPx(c: Context): Pair<Int, Int> {
        // rozmiar okna aplikacji z zasobów (ten sam układ pikseli, w którym rysuje się interfejs; na TV bez pasków = cały ekran)
        c.resources.displayMetrics.let { r -> if (r.widthPixels > 0 && r.heightPixels > 0) return r.widthPixels to r.heightPixels }
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (wm != null && android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") runCatching { wm?.defaultDisplay?.getRealMetrics(dm) }
        if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm.widthPixels to dm.heightPixels
        val r = c.resources.displayMetrics
        return r.widthPixels to r.heightPixels
    }

    /** Wyliczenie gęstości siatki (start aplikacji i każda aktywność – zmiana trybu ekranu TV). */
    fun init(c: Context): Float? {
        // (7.22) siatka wg „Rozdzielczości interfejsu” (AUTO: TV – 960 × 540 dp, telefon – null); telefon pionowo – zawsze null
        val d = try { gridFor(DeviceDisplayProfile.of(c))?.let { (gw, gh) -> windowPx(c).let { (w, h) -> densityFor(w, h, gw, gh) } } } catch (_: Throwable) { null }
        density = d
        return d
    }

    /** (7.22) Siatka interfejsu w dp dla profilu ekranu i [UiResolutionMode.current]; null – gęstość systemu. */
    fun gridFor(p: DeviceDisplayProfile, mode: UiResolutionMode = UiResolutionMode.current()): Pair<Float, Float>? =
        if (p.isPortraitPhone) null else mode.grid(p.isTvDevice)

    /**
     * (7.22) Zmiana „Rozdzielczości interfejsu” w działającej aplikacji: nowa gęstość, metryki kontekstu aplikacji (także powrót
     * do gęstości systemu przy „Natywnej”) i przebudowa ekranów (jak po zmianie języka).
     */
    fun modeChanged(c: Context) {
        init(c.applicationContext)
        applyMetrics(c.applicationContext.resources)
        Ui.languageChanged()
    }

    /**
     * Kontekst aktywności z konfiguracją siatki TV: densityDpi z [densityFor], skala czcionek 1,0, rozmiar ekranu w dp
     * 960 × 540. Telefon / tablet – kontekst bez zmian; (7.22) błąd – kontekst bez zmian (bez zamknięcia aplikacji).
     */
    fun wrap(base: Context): Context = try { wrapTv(base) } catch (t: Throwable) { warn("siatka TV pominięta", t); base }

    private fun wrapTv(base: Context): Context {
        val d = init(base) ?: return base
        val cur = base.resources.configuration
        val dpi = densityDpiFor(d)
        if (cur.densityDpi == dpi && cur.fontScale == 1f) return base
        val cfg = Configuration(cur).apply {
            densityDpi = dpi; fontScale = 1f
            val (w, h) = windowPx(base)
            screenWidthDp = Math.round(w / d); screenHeightDp = Math.round(h / d)
            // smallestScreenWidthDp bez zmian (aplikacja nie ma zasobów „sw…dp”; jego zmiana w Robolectric przywracała gęstość z kwalifikatorów)
        }
        return base.createConfigurationContext(cfg)
    }

    /**
     * Konfiguracja nakładki (AppCompat buduje ją sam w `attachBaseContext` i podaje do `applyOverrideConfiguration` – wtedy
     * zmiana z [wrap] by przepadła): gęstość siatki TV, skala czcionek 1,0, rozmiar ekranu 960 × 540 dp. Telefon – bez zmian.
     */
    fun adjust(c: Context, o: Configuration?): Configuration? {
        val d = density ?: return o
        return try {
            // (7.22) wywołanie przed przypięciem kontekstu bazowego aktywności – windowPx daje wymiary wzorcowe zamiast wyjątku
            val (w, h) = windowPx(c)
            Configuration(o ?: Configuration()).apply {
                densityDpi = densityDpiFor(d); fontScale = 1f
                screenWidthDp = Math.round(w / d); screenHeightDp = Math.round(h / d)
            }
        } catch (t: Throwable) { warn("adjust", t); o }
    }

    /**
     * Gęstość siatki ustawiona bezpośrednio w `DisplayMetrics` zasobów (aktywność, aplikacja): widoki z XML, wymiary w kodzie
     * i Compose (`Density(context)` czyta `displayMetrics.density`) w tej samej siatce, także gdy system lub AppCompat pominie
     * konfigurację z [wrap]. Wywoływane w `onCreate` (przed `setContentView`) i `onResume` (system potrafi przywrócić metryki).
     */
    fun applyMetrics(res: android.content.res.Resources) {
        val d = density
        if (d == null) { if (res in mutated) try { restoreSystemMetrics(res) } catch (t: Throwable) { warn("metryki systemu", t) }; return }
        try { applyMetricsTv(res, d); mutated += res } catch (t: Throwable) { warn("metryki", t) }
    }

    /** Zasoby, którym ustawiono gęstość siatki (powrót do gęstości systemu po zmianie na „Natywną”). */
    private val mutated: MutableSet<android.content.res.Resources> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

    private fun restoreSystemMetrics(res: android.content.res.Resources) {
        val sys = android.content.res.Resources.getSystem()
        val s = sys.displayMetrics; val dm = res.displayMetrics
        dm.density = s.density; dm.densityDpi = s.densityDpi; @Suppress("DEPRECATION") run { dm.scaledDensity = s.scaledDensity }
        dm.xdpi = s.xdpi; dm.ydpi = s.ydpi
        runCatching {
            res.configuration.apply {
                densityDpi = s.densityDpi; fontScale = sys.configuration.fontScale
                screenWidthDp = Math.round(dm.widthPixels / s.density); screenHeightDp = Math.round(dm.heightPixels / s.density)
            }
        }
        mutated -= res
    }

    private fun applyMetricsTv(res: android.content.res.Resources, d: Float) {
        val dm = res.displayMetrics
        val dpi = densityDpiFor(d)
        if (dm.density != d || dm.densityDpi != dpi) {
            dm.density = d; dm.densityDpi = dpi; @Suppress("DEPRECATION") run { dm.scaledDensity = d }
            dm.xdpi = dpi.toFloat(); dm.ydpi = dpi.toFloat()
        }
        // rozmiar ekranu w dp w konfiguracji zasobów (ekrany wybierają układ z screenWidthDp / screenHeightDp, Compose – LocalConfiguration)
        runCatching {
            res.configuration.apply {
                densityDpi = dpi; fontScale = 1f
                screenWidthDp = Math.round(dm.widthPixels / d); screenHeightDp = Math.round(dm.heightPixels / d)
            }
        }
    }

    /** Gęstość Compose dla siatki TV ([density]); null – systemowa. */
    fun composeDensity(): Density? = density?.takeIf { it > 0f && !it.isNaN() && !it.isInfinite() }?.let { Density(it, fontScale = 1f) }

    /**
     * (7.22) Gęstość Compose dla profilu [p]: telefon / tablet (zwłaszcza pionowo) – zawsze null, czyli natywna
     * `LocalDensity.current`; TV – [density], a gdy jeszcze nie wyliczona (zimny start) – z bezpiecznego rozmiaru okna
     * (`safeWidthPx` = [widthPx] albo szerokość z metryk zasobów, co najmniej 1 px).
     */
    fun composeDensityFor(p: DeviceDisplayProfile, widthPx: Int, heightPx: Int, res: android.content.res.Resources?,
                          mode: UiResolutionMode = UiResolutionMode.current()): Density? {
        // telefon pionowo – zawsze natywna; „Natywna” albo AUTO na telefonie – też (siatka z [UiResolutionMode.grid])
        val (gw, gh) = gridFor(p, mode) ?: return null
        composeDensity()?.let { return it }
        val dm = try { res?.displayMetrics } catch (_: Throwable) { null }
        val safeWidthPx = widthPx.takeIf { it > 0 } ?: (dm?.widthPixels ?: 0).coerceAtLeast(1)
        val safeHeightPx = heightPx.takeIf { it > 0 } ?: (dm?.heightPixels ?: 0).coerceAtLeast(1)
        // brak jakiegokolwiek wymiaru (1 px) – gęstość systemu zamiast zgadywania
        if (safeWidthPx <= 1 || safeHeightPx <= 1) return null
        return Density(densityFor(safeWidthPx, safeHeightPx, gw, gh), fontScale = 1f)
    }

    /**
     * Bufor powierzchni wideo w pełnej rozdzielczości materiału, gdy jest większa niż powierzchnia w pikselach interfejsu
     * (np. 3840 × 2160 na matrycy 4K przy oknie 1920 × 1080) i matryca ma taki tryb – kompozytor skaluje obraz do widoku
     * bez utraty szczegółów; inaczej rozmiar z układu. Tryb słabszych urządzeń z limitem rozdzielczości – zawsze z układu.
     */
    fun nativeVideoBuffer(sv: android.view.SurfaceView?, videoW: Int, videoH: Int, displayH: Int, limited: Boolean): Boolean {
        sv ?: return false
        val big = !limited && videoW > 0 && videoH > 0 && videoH > sv.height && displayH >= videoH && sv.height > 0
        runCatching { if (big) sv.holder.setFixedSize(videoW, videoH) else sv.holder.setSizeFromLayout() }
        return big
    }

    /** Fizyczna wysokość matrycy (aktywny tryb wyświetlacza). */
    fun displayHeightPx(a: android.app.Activity): Int = runCatching {
        val d = (if (android.os.Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay) ?: return 0
        if (android.os.Build.VERSION.SDK_INT >= 23) minOf(d.mode.physicalWidth, d.mode.physicalHeight).coerceAtLeast(0) else 0
    }.getOrDefault(0)

    /**
     * TV: okno na pełnym ekranie bez pasków systemowych (treść także pod nimi – siatka 960 × 540 dp to cały ekran) i bez
     * wygaszania ekranu przez system w trakcie używania ([keepScreenOn]). (7.22) Każde wywołanie okna osobno w try-catch.
     */
    fun fullscreen(a: android.app.Activity, keepScreenOn: Boolean) {
        if (!isTv(a)) return
        val w = try { a.window } catch (_: Throwable) { null } ?: return
        try { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false) } catch (t: Throwable) { warn("decorFits", t) }
        try {
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (t: Throwable) { warn("insetsController", t) }
        if (keepScreenOn) try { w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (t: Throwable) { warn("keepScreenOn", t) }
    }

    private fun warn(what: String, t: Throwable) {
        runCatching { android.util.Log.w(TAG, "$what: ${t.javaClass.simpleName}: ${t.message}") }
    }

    /** Testy. */
    fun resetForTest() { density = null; forceTv = null; DeviceDisplayProfile.forceOrientation = null; UiResolutionMode.forced = null }
}

/**
 * (7.20) Siatka 1920 × 1080 (960 × 540 dp) dla Compose na TV: `LocalDensity` z [TvResolution.density] na szczycie drzewa
 * (wokół motywów i ekranów); telefon / tablet – gęstość bez zmian. (7.22) Także [LocalDeviceDisplayProfile] dla całego
 * drzewa; telefon pionowo (9:16) – zawsze natywne `LocalDensity.current`, nigdy wirtualne 960 dp.
 */
@Composable
fun TvResolution1080pProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val profile = remember(cfg.orientation, cfg.uiMode) { DeviceDisplayProfile.of(context, cfg) }
    val mode = UiResolutionMode.current()
    val d = remember(profile, mode, cfg.screenWidthDp, cfg.screenHeightDp, TvResolution.density) {
        val res = try { context.resources } catch (_: Throwable) { null }
        val dm = try { res?.displayMetrics } catch (_: Throwable) { null }
        TvResolution.composeDensityFor(profile, dm?.widthPixels ?: 0, dm?.heightPixels ?: 0, res)
    }
    if (d == null) CompositionLocalProvider(LocalDeviceDisplayProfile provides profile) { pl.iptv.tv.ui.theme.ObsidianGlassProvider(content) }   // (v2) stan motywu na szczycie drzewa
    else CompositionLocalProvider(LocalDensity provides d, LocalDeviceDisplayProfile provides profile) { pl.iptv.tv.ui.theme.ObsidianGlassProvider(content) }
}

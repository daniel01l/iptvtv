package pl.iptv.tv.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 */
object TvResolution {
    const val DESIGN_W_DP = 960f
    const val DESIGN_H_DP = 540f
    /** Wzorcowa rozdzielczość projektowa (px przy gęstości 2,0). */
    const val DESIGN_W_PX = 1920
    const val DESIGN_H_PX = 1080

    /** Gęstość siatki TV (null – telefon / tablet albo jeszcze nie wyliczona: gęstość systemu). */
    @Volatile var density: Float? = null; private set
    /** Testy: wymuszenie trybu TV (true / false) zamiast wykrywania. */
    @Volatile var forceTv: Boolean? = null

    /** Android TV / Google TV: tryb interfejsu „telewizja” albo urządzenie Leanback bez ekranu dotykowego. */
    fun isTv(c: Context): Boolean {
        forceTv?.let { return it }
        if ((c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val mode = (c.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = c.packageManager
        return runCatching { pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) && !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) }.getOrDefault(false)
    }

    /** Gęstość, przy której okno [widthPx] × [heightPx] to dokładnie siatka 960 × 540 dp (dłuższy bok = 960 dp). */
    fun densityFor(widthPx: Int, heightPx: Int): Float {
        val long = maxOf(widthPx, heightPx).toFloat(); val short = minOf(widthPx, heightPx).toFloat()
        return minOf(long / DESIGN_W_DP, short / DESIGN_H_DP).coerceAtLeast(0.5f)
    }

    /** densityDpi dla [density] (160 dpi = 1,0). */
    fun densityDpiFor(d: Float): Int = Math.round(d * android.util.DisplayMetrics.DENSITY_DEFAULT)

    /** Rzeczywisty rozmiar okna aplikacji w pikselach (pełny ekran TV). */
    private fun windowPx(c: Context): Pair<Int, Int> {
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
        val d = if (isTv(c)) windowPx(c).let { (w, h) -> densityFor(w, h) } else null
        density = d
        return d
    }

    /**
     * Kontekst aktywności z konfiguracją siatki TV: densityDpi z [densityFor], skala czcionek 1,0, rozmiar ekranu w dp
     * 960 × 540. Telefon / tablet – kontekst bez zmian.
     */
    fun wrap(base: Context): Context {
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
        val (w, h) = windowPx(c)
        return Configuration(o ?: Configuration()).apply {
            densityDpi = densityDpiFor(d); fontScale = 1f
            screenWidthDp = Math.round(w / d); screenHeightDp = Math.round(h / d)
        }
    }

    /**
     * Gęstość siatki ustawiona bezpośrednio w `DisplayMetrics` zasobów (aktywność, aplikacja): widoki z XML, wymiary w kodzie
     * i Compose (`Density(context)` czyta `displayMetrics.density`) w tej samej siatce, także gdy system lub AppCompat pominie
     * konfigurację z [wrap]. Wywoływane w `onCreate` (przed `setContentView`) i `onResume` (system potrafi przywrócić metryki).
     */
    fun applyMetrics(res: android.content.res.Resources) {
        val d = density ?: return
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
    fun composeDensity(): Density? = density?.let { Density(it, fontScale = 1f) }

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
        if (android.os.Build.VERSION.SDK_INT >= 23) minOf(d.mode.physicalWidth, d.mode.physicalHeight) else 0
    }.getOrDefault(0)

    /**
     * TV: okno na pełnym ekranie bez pasków systemowych (treść także pod nimi – siatka 960 × 540 dp to cały ekran) i bez
     * wygaszania ekranu przez system w trakcie używania ([keepScreenOn]).
     */
    fun fullscreen(a: android.app.Activity, keepScreenOn: Boolean) {
        if (!isTv(a)) return
        val w = a.window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
        androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (keepScreenOn) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Testy. */
    fun resetForTest() { density = null; forceTv = null }
}

/**
 * (7.20) Siatka 1920 × 1080 (960 × 540 dp) dla Compose na TV: `LocalDensity` z [TvResolution.density] na szczycie drzewa
 * (wokół motywów i ekranów); telefon / tablet – gęstość bez zmian.
 */
@Composable
fun TvResolution1080pProvider(content: @Composable () -> Unit) {
    val d = TvResolution.composeDensity()
    if (d == null) content() else CompositionLocalProvider(LocalDensity provides d) { content() }
}

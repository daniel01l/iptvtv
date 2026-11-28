package pl.iptv.tv.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * (7.28) Profil wydajności interfejsu – liczony RAZ (pierwsze [init], `IptvApp`), bez zapytań w trakcie rysowania klatek
 * i bez tworzenia kontekstu OpenGL (renderer GPU czytany tylko, gdy kontekst GL już istnieje w bieżącym wątku).
 *
 * [Profile.ULTRA_LITE] (TV / box / projektor / słabszy telefon), gdy spełniony jest którykolwiek warunek: tryb TV, RAM < 3,5 GB,
 * proces 32-bitowy, układ z listy [CHIPS]. Wybór użytkownika (Ustawienia → Wygląd → „Tryb wydajności interfejsu”,
 * `Prefs.uiPerfMode`): 0 – automatyczny, 1 – tryb szybki, 2 – pełne efekty.
 *
 * Tryb szybki zmienia tylko warstwę rysowania: bez poświat poza elementem (zostaje ramka #38BDF8 / #3B82F6), fokus natychmiast
 * (bez animacji skali), tło v2 jednym gradientem pionowym (bez fal i poświat), karty v2 kryjące jednym przebiegiem, obrazy Coil
 * bez przenikania. Nie dotyka trybu wyświetlania (HDMI) ani silnika odtwarzania (`Prefs.lowPerf` – osobno).
 */
object DevicePerformanceProfile {
    enum class Profile { FULL, ULTRA_LITE }

    const val MODE_AUTO = 0
    const val MODE_FAST = 1
    const val MODE_FULL = 2
    val MODE_LABELS = listOf("Automatyczny (Zalecany)", "Tryb szybki (TV i Boxy)", "Pełne efekty")
    const val TITLE = "Tryb wydajności interfejsu"
    const val NOTE = "Tryb szybki: natychmiastowy fokus, bez poświat i fal w tle, kryjące kafelki – płynniej na telewizorach, boxach i projektorach."

    /** Próg RAM (bajty) – poniżej profil [Profile.ULTRA_LITE]. */
    const val RAM_LIMIT_BYTES = 3_500L * 1024 * 1024

    /** Fragmenty nazw układów TV / boxów / projektorów i GPU o ograniczonej przepustowości (małe litery). */
    val CHIPS = listOf(
        // Amlogic
        "s905", "s922", "s805", "t962", "t972", "amlogic", "mesong",
        // Realtek
        "rtd1325", "rtd1319", "rtd1315", "rtd1295", "rtd2851", "realtek",
        // MediaTek
        "mt9615", "mt9617", "mt9638", "mt9602", "mt9950", "mt5895", "mt5599", "mt8696", "mt58", "mt96", "mediatek",
        // Allwinner
        "h713", "h616", "h618", "h313", "t950", "sun50i", "allwinner",
        // Rockchip
        "rk3528", "rk3566", "rk3318", "rk3328", "rk3229", "rk3588", "rockchip",
        // HiSilicon / Novatek / Broadcom
        "hi3798", "nt726", "bcm72", "hisilicon", "novatek",
        // GPU
        "mali-g31", "mali-g52", "mali-g57 mc1", "mali-450", "mali-400", "powervr ge8300", "powervr rogue", "adreno 308", "adreno 505", "adreno 506",
    )

    /** „h6” – tylko jako całe słowo (inaczej pasowałoby do „h616”, „h618” i przypadkowych nazw). */
    private val H6 = Regex("(^|[^a-z0-9])h6([^a-z0-9]|$)")

    @Volatile private var detected: Profile? = null
    /** Testy: wymuszony profil wykrycia (null – rzeczywisty). */
    @Volatile var forced: Profile? = null

    /** Wykrycie raz na proces (kolejne wywołania nic nie robią). */
    fun init(c: Context) {
        if (detected != null) return
        detected = try { detect(c.applicationContext ?: c) } catch (_: Throwable) { Profile.FULL }
    }

    /** Profil sprzętu (bez wyboru użytkownika). */
    val hardware: Profile get() = forced ?: detected ?: Profile.FULL

    /** Profil obowiązujący w interfejsie – po uwzględnieniu wyboru w Ustawieniach. */
    val current: Profile get() = when (runCatching { pl.iptv.tv.data.Prefs.uiPerfMode }.getOrDefault(MODE_AUTO)) {
        MODE_FAST -> Profile.ULTRA_LITE
        MODE_FULL -> Profile.FULL
        else -> hardware
    }

    /** Tryb szybki interfejsu. */
    val lite: Boolean get() = current == Profile.ULTRA_LITE

    /** Uproszczone rysowanie: tryb szybki albo „Tryb dla słabszych urządzeń”. */
    val uiLow: Boolean get() = lite || runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)

    fun matchesChip(text: String?): Boolean {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return false
        return CHIPS.any { t.contains(it) } || H6.containsMatchIn(t)
    }

    private fun detect(c: Context): Profile {
        // Robolectric – pełne efekty (testy wyglądu), wybór „Tryb szybki” nadal działa
        if (Build.FINGERPRINT == "robolectric") return Profile.FULL
        val tv = runCatching {
            (c.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)
        if (tv) return Profile.ULTRA_LITE
        val ram = runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mi); mi.totalMem
        }.getOrDefault(0L)
        if (ram in 1 until RAM_LIMIT_BYTES) return Profile.ULTRA_LITE
        if (Build.VERSION.SDK_INT >= 23 && runCatching { !android.os.Process.is64Bit() }.getOrDefault(false)) return Profile.ULTRA_LITE
        val soc = if (Build.VERSION.SDK_INT >= 31) runCatching { Build.SOC_MODEL }.getOrNull() else null
        if (listOf(Build.HARDWARE, Build.BOARD, soc, glRenderer()).any { matchesChip(it) }) return Profile.ULTRA_LITE
        return Profile.FULL
    }

    /** Renderer GPU – wyłącznie gdy kontekst GL już istnieje w tym wątku (nie tworzymy własnego). */
    private fun glRenderer(): String? = try {
        if (android.opengl.EGL14.eglGetCurrentContext() == android.opengl.EGL14.EGL_NO_CONTEXT) null
        else android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
    } catch (_: Throwable) { null }

    fun resetForTest() { detected = null; forced = null }
}

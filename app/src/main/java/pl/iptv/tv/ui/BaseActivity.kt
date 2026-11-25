package pl.iptv.tv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.request.CachePolicy
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Wspólna baza: tło (kolor lub zdjęcie), styl motywu, animacje przejść. */
open class BaseActivity : AppCompatActivity() {
    /** (7.20) Siatka interfejsu TV 960 × 540 dp (1920 × 1080) niezależnie od DPI systemu – [pl.iptv.tv.ui.TvResolution]. */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(pl.iptv.tv.ui.TvResolution.wrap(newBase)) }
    override fun applyOverrideConfiguration(o: android.content.res.Configuration?) { super.applyOverrideConfiguration(pl.iptv.tv.ui.TvResolution.adjust(this, o)) }

    protected lateinit var rootFrame: FrameLayout
    private lateinit var bgImage: ImageView
    private lateinit var dim: View
    protected lateinit var content: View
    /** Obraz odtwarzanego kanału w tle (ustawienia, edytor, przewodnik…). */
    protected open val videoBackground = true
    /** Pasek „Teraz odtwarzane” (film STube w tle) na dole ekranu. */
    protected open val miniPlayer = false
    protected var miniBar: MiniPlayerBar? = null; private set
    private var videoView: androidx.media3.ui.PlayerView? = null
    private var appliedVersion = -1

    /** Odtwarzacz obraca się zawsze, gdy włączono tryb pionowy/automatyczny. */
    protected open val isPlayerScreen = false

    /** Orientacja ekranu wg Ustawienia → Wygląd (telefon). */
    private fun applyOrientation() {
        val o = when (Prefs.orientation) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            2 -> if (isPlayerScreen) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != o) requestedOrientation = o
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pl.iptv.tv.ui.TvResolution.applyMetrics(resources); pl.iptv.tv.ui.TvResolution.applyMetrics(applicationContext.resources)   // (7.20) siatka TV 960 × 540 dp
        applyOrientation(); super.onCreate(savedInstanceState)
        // (7.20) TV: pełny ekran bez pasków systemowych – siatka 960 × 540 dp to cały ekran (MainActivity – także bez wygaszania)
        TvResolution.fullscreen(this, keepScreenOn = this is MainActivity)
        // tło okna od razu w kolorze motywu: zanim ekran się narysuje, widać tło motywu, nie stały ciemny kolor (mignięcie „czarnego ekranu”)
        if (!isPlayerScreen) window.setBackgroundDrawable(Ui.bgDrawable())
        // X25: dźwięk przy przejściu fokusu pilotem (motyw sprawdzany przy każdym zdarzeniu – działa też po zmianie motywu)
        if (!isPlayerScreen) X25Theme.attachSounds(this)
        // ekrany główne: WSTECZ (wyjście) – przy pulpicie X25 prosto na pulpit; obsługa ekranu (własne wywołania zwrotne) ma pierwszeństwo
        if (x25Root) onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveScreen()
        })
        createdEpoch = Ui.themeEpoch
    }

    /** Ekran w stylu ustawień konsoli: przy motywie PS5 pasek podpowiedzi przycisków (✕ Wybierz · ○ Wstecz · ▲▼ Nawiguj). */
    protected open val ps5Hints = false

    /** Ekran główny otwierany z pulpitu X25 (odtwarzacz, STube, Stremio, przewodnik, wyszukiwarka). */
    protected open val x25Root = false

    /** Wyjście z ekranu: przy pulpicie X25 – na pulpit (nie na listę kanałów pod spodem). */
    fun leaveScreen() {
        if (x25Root && X25Theme.dashboardOn) X25Theme.openDashboard(this)
        finish()
    }

    /** Zmiana motywu od zbudowania ekranu: domyślnie przebudowa (pulpit X25 może się zamiast tego zamknąć). */
    protected open fun onThemeEpochChanged() = rebuildForTheme()


    /** Motyw, z którym zbudowano ekran; inny = ekran budowany od nowa (układ, kafelki, ramki nowego motywu). */
    private var createdEpoch = 0

    /** Nowy motyw: ekrany menu budowane od nowa (odtwarzacz tylko odświeża styl – obraz gra dalej bez przerwy). */
    protected fun themeChangedSinceCreate() = createdEpoch != Ui.themeEpoch

    /** Przebudowa ekranu po zmianie motywu – bez animacji przejścia. */
    protected fun rebuildForTheme() {
        createdEpoch = Ui.themeEpoch
        recreate()
    }

    override fun onStart() { super.onStart(); miniBar?.let { b -> b.start(); if (b.shown) if (::content.isInitialized) content.let { insetScrollers(it, Ui.dp(MiniPlayerBar.CONTENT_INSET_DP)) } } }
    override fun onStop() { miniBar?.stop(); EngineStatusPill.hide(this); super.onStop() }

    /** Obraz kanału w tle tego ekranu odpięty od wspólnego odtwarzacza (singleton nie trzyma zniszczonego ekranu). */
    override fun onDestroy() { videoView?.let { Playback.detach(it) }; super.onDestroy() }

    /**
     * (7.22) Obrót bez odtworzenia ekranu (odtwarzacz – `configChanges`): siatka interfejsu wg profilu ekranu na nowo – telefon
     * pionowo zawsze w gęstości systemu, poziomo wg „Rozdzielczości interfejsu” (AUTO na telefonie – bez zmian).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching {
            val before = TvResolution.density
            if (TvResolution.init(this) != before) { TvResolution.applyMetrics(resources); TvResolution.applyMetrics(applicationContext.resources) }
        }
    }

    /** ▼ z najniższego elementu – pasek „Teraz odtwarzane”. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // PS5: podpowiedzi przycisków wg ostatnio użytego urządzenia (pilot / pad)
        Ps5Input.onKey(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in NAV_KEYS) KeyActivity.lastUserKeyAt = event.eventTime
        if (throttledRepeat(event)) return true
        if (miniBar?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * (7.3) Pulpity Compose (Nowoczesny, PS5, X25): przytrzymana strzałka przesuwa fokus w równym tempie [navRepeatMs]
     * zamiast tempa powtórzeń pilota (często 20–50 ms) – przewijanie nadąża płynnie, bez szarpania i gubienia klatek.
     */
    protected open val navRepeatMs = 0L
    private var lastNavRepeat = 0L

    /** Pominięte powtórzenia strzałek (testy). */
    internal var navRepeatsDropped = 0; private set

    private fun throttledRepeat(e: android.view.KeyEvent): Boolean {
        if (navRepeatMs <= 0 || e.action != android.view.KeyEvent.ACTION_DOWN || e.keyCode !in NAV_KEYS) return false
        val now = e.eventTime
        if (e.repeatCount == 0) { lastNavRepeat = now; return false }        // pierwsze naciśnięcie – zawsze od razu
        if (now - lastNavRepeat < navRepeatMs) { navRepeatsDropped++; return true }
        lastNavRepeat = now
        return false
    }

    /** Dotknięcie ekranu (nie mysz) – podpowiedzi przycisków PS5 ukryte. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ev.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) Ps5Input.onTouch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        TvResolution.applyMetrics(resources)          // (7.20) system potrafi przywrócić metryki po powrocie na ekran
        X25Theme.refreshLauncher(this)
        super.onResume()
        applyOrientation()
        // powrót do aplikacji z okienkiem PiP: okienko się zamyka, obraz przechodzi na ten ekran
        if (this !is PlayerActivity) PlayerActivity.closePip()
        gateActivation()
        if (!isPlayerScreen && themeChangedSinceCreate()) { onThemeEpochChanged(); return }
        // STube / Stremio: telewizja wstrzymana; pozostałe ekrany menu: wstrzymany kanał gra dalej (odtwarzacz TV uruchamia własny)
        when {
            pausesLiveTv -> Playback.suspendForApp()
            isPlayerScreen -> Playback.forgetSuspended()
            else -> Playback.resumeAfterApp(this)
        }
        if (::rootFrame.isInitialized && appliedVersion != Ui.version) applyTheme()
        // (7.20) zmiana silnika weszła w życie – pigułka statusu na ekranie, który jest na wierzchu
        val self = java.lang.ref.WeakReference(this)
        Playback.onEngineApplied = { text -> self.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { EngineStatusPill.show(it, text) } }
        updateVideo()
    }

    /** Ekran aplikacji, przy którym telewizja na żywo nie gra (STube, Stremio) – nie obciąża słabszych urządzeń. */
    protected open val pausesLiveTv = false

    /** Wersja zabezpieczona: bez aktywacji każdy ekran ustępuje ekranowi blokady. */
    private fun gateActivation() {
        val A = pl.iptv.tv.data.Activation
        if (!A.enabled) return
        if (A.state() != pl.iptv.tv.data.Activation.State.ACTIVE) { Playback.stop(); startActivity(Intent(this, LockActivity::class.java)); return }
        if (A.needsCheck) lifecycleScope.launch {
            if (A.check() != pl.iptv.tv.data.Activation.State.ACTIVE) { Playback.stop(); startActivity(Intent(this@BaseActivity, LockActivity::class.java)) }
        }
    }

    /** Pokazuje odtwarzany kanał w tle (przyciemniony), jeśli coś gra. */
    private fun updateVideo() {
        val v = videoView ?: return
        if (Playback.active) {
            // w pionie obraz w tle w całości (bez powiększania i przycinania), poziomo – wypełnia ekran
            v.resizeMode = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            v.visibility = View.VISIBLE; Playback.attach(v)
            // (7.17) „Obsidian Stream”: kanał w tle przyciemniony w 65 % – opcje nad nim zawsze czytelne
            dim.visibility = View.VISIBLE
            dim.setBackgroundColor(if (pl.iptv.tv.ui.theme.ObsidianViews.active) pl.iptv.tv.ui.theme.ObsidianViews.VIDEO_DIM_65 else Color.argb(150, 0, 0, 0))
        } else v.visibility = View.GONE
    }

    /** (7.22) Zakładka dolnego paska telefonu w pionie ([MobilePortraitScaffold]); null – ekran bez paska. */
    protected open val portraitTab: MobilePortraitScaffold.Tab? = null
    /** Dolny pasek nawigacji (tylko telefon pionowo). */
    var portraitNav: LinearLayout? = null; private set
    /** Układ 9:16 telefonu (wyłącznie [DeviceDisplayProfile.isPortraitPhone]). */
    val portraitPhone: Boolean get() = MobilePortraitScaffold.active(this)
    /** Ponowny wybór zakładki bieżącego ekranu (np. Stremio ↔ Do obejrzenia). */
    open fun onPortraitTab(t: MobilePortraitScaffold.Tab) {}

    protected fun setThemedContent(layout: Int) =
        setThemedContent(layoutInflater.inflate(layout, FrameLayout(this), false))

    protected fun setThemedContent(view: View) {
        rootFrame = FrameLayout(this)
        bgImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        dim = View(this)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        rootFrame.addView(bgImage, FrameLayout.LayoutParams(mp, mp))
        if (videoBackground) {
            videoView = androidx.media3.ui.PlayerView(this).apply {
                useController = false; isFocusable = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.TRANSPARENT); setKeepContentOnPlayerReset(true)
                visibility = View.GONE
            }
            rootFrame.addView(videoView, FrameLayout.LayoutParams(mp, mp))
        }
        rootFrame.addView(dim, FrameLayout.LayoutParams(mp, mp))
        rootFrame.addView(view, FrameLayout.LayoutParams(mp, mp))
        content = view
        if (miniPlayer) miniBar = MiniPlayerBar(this).also { bar ->
            bar.attachTo(rootFrame)
            // (7.17) kapsuła pływa nad treścią – ekran się nie zmniejsza; tylko przewijane listy dostają dolny odstęp,
            // żeby ostatni rząd dało się przewinąć ponad kapsułę
            bar.onVisibilityChanged = { on -> insetScrollers(view, if (on) Ui.dp(MiniPlayerBar.CONTENT_INSET_DP) else 0) }
        }
        // (7.22) telefon pionowo: dolny pasek nawigacji zamiast bocznych pasków TV (TV i poziomo – bez zmian)
        val tab = portraitTab
        if (tab != null && MobilePortraitScaffold.active(this)) portraitNav = MobilePortraitScaffold.install(this, rootFrame, view, tab) { h ->
            // mini-odtwarzacz nad paskiem
            miniBar?.let { b -> (b.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val m = h + Ui.dp(MiniPlayerBar.MARGIN_DP); if (lp.bottomMargin != m) { lp.bottomMargin = m; b.layoutParams = lp } } }
        }
        setContentView(rootFrame)
        applyTheme()
    }

    /**
     * (7.17) Dolny odstęp [inset] (0 – zdjęty) na przewijanych w pionie listach ekranu pod pływającym mini-odtwarzaczem:
     * treść przewija się ponad kapsułę zamiast się kurczyć. Pierwotny odstęp listy zapamiętany w tagu (bez narastania);
     * zagnieżdżone listy (rzędy w rzędzie) bez zmian.
     */
    fun insetScrollers(root: View, inset: Int) {
        val vertical = when (root) {
            is android.widget.ScrollView, is androidx.core.widget.NestedScrollView -> true
            is androidx.recyclerview.widget.RecyclerView -> root.layoutManager?.canScrollVertically() == true
            else -> false
        }
        if (vertical) {
            val base = (root.getTag(pl.iptv.tv.R.id.mini_inset) as? Int) ?: root.paddingBottom.also { root.setTag(pl.iptv.tv.R.id.mini_inset, it) }
            if (root.paddingBottom != base + inset) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, base + inset)
            (root as ViewGroup).clipToPadding = false
            return
        }
        if (root is ViewGroup && root !is MiniPlayerBar) for (i in 0 until root.childCount) insetScrollers(root.getChildAt(i), inset)
    }

    /** Ponownie nakłada motyw na cały ekran (np. po zmianie ustawień). */
    open fun applyTheme() {
        appliedVersion = Ui.version
        // tło rysuje okno (jedna warstwa zamiast dwóch – mniej przerysowań całego ekranu na słabszych urządzeniach)
        // odtwarzacz: obraz zasłania tło – X25 bez rysowanego tła (pełnoekranowa grafika pod wideo w każdej klatce = przycięcia)
        // (7.1) każdy motyw: jednolity kolor – tło Nowoczesnego (cieniowania na cały ekran) pod obrazem też zacinało kanał
        if (isPlayerScreen) rootFrame.background = android.graphics.drawable.ColorDrawable(Ui.bg)
        else { rootFrame.background = null; window.setBackgroundDrawable(Ui.bgDrawable()) }
        // PS5: podpowiedzi przycisków na dole ekranów w stylu ustawień konsoli (inny motyw – pasek usuwany)
        if (ps5Hints) Ps5Theme.attachScreenHints(rootFrame)
        window.statusBarColor = Ui.bg
        window.navigationBarColor = Ui.bg
        val f = Repository.bgImageFile
        if (Prefs.bgImageOn && f.exists()) {
            bgImage.visibility = View.VISIBLE
            // obraz w pamięci (klucz = data zmiany pliku): kolejne ekrany pokazują tło od razu, bez ciemnej klatki przed wczytaniem
            val key = "bg:${f.lastModified()}:${f.length()}"
            if (bgImage.getTag(pl.iptv.tv.R.id.ui_style) != key) {
                bgImage.setTag(pl.iptv.tv.R.id.ui_style, key)
                bgImage.load(f) { memoryCacheKey(key); diskCachePolicy(CachePolicy.DISABLED); crossfade(false); backdropSize(this@BaseActivity).let { (w, h) -> size(w, h) } }
            }
            dim.setBackgroundColor(Color.argb(Ui.DIMS[Ui.dimIndex.coerceIn(Ui.DIMS.indices)] * 255 / 100, 0, 0, 0))
            dim.visibility = View.VISIBLE
        } else {
            bgImage.setImageDrawable(null); bgImage.setTag(pl.iptv.tv.R.id.ui_style, null); bgImage.visibility = View.GONE; dim.visibility = View.GONE
        }
        Ui.style(content)
        if (::dim.isInitialized) updateVideo()
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        super.startActivity(intent, options); Ui.openTransition(this)
    }

    override fun finish() { super.finish(); Ui.closeTransition(this) }
}

/** Strzałki nawigacji (tempo przytrzymania – [BaseActivity.navRepeatMs]). */
internal val NAV_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN)

/** (7.3) Tempo przytrzymanej strzałki na pulpitach Compose (ms na krok). */
const val COMPOSE_NAV_REPEAT_MS = 85L

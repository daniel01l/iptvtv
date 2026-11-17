package pl.iptv.tv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.request.CachePolicy
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Wspólna baza: tło (kolor lub zdjęcie), styl motywu, animacje przejść. */
open class BaseActivity : AppCompatActivity() {
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
        applyOrientation(); super.onCreate(savedInstanceState)
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

    override fun onStart() { super.onStart(); miniBar?.start() }
    override fun onStop() { miniBar?.stop(); super.onStop() }

    /** ▼ z najniższego elementu – pasek „Teraz odtwarzane”. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // PS5: podpowiedzi przycisków wg ostatnio użytego urządzenia (pilot / pad)
        Ps5Input.onKey(event)
        if (miniBar?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /** Dotknięcie ekranu (nie mysz) – podpowiedzi przycisków PS5 ukryte. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ev.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) Ps5Input.onTouch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
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
            dim.visibility = View.VISIBLE; dim.setBackgroundColor(Color.argb(150, 0, 0, 0))
        } else v.visibility = View.GONE
    }

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
            val base = view.paddingBottom
            // miejsce pod treścią na pasek – nic nie chowa się pod nim
            bar.onVisibilityChanged = { on -> view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, base + if (on) Ui.dp(84) else 0) }
        }
        setContentView(rootFrame)
        applyTheme()
    }

    /** Ponownie nakłada motyw na cały ekran (np. po zmianie ustawień). */
    open fun applyTheme() {
        appliedVersion = Ui.version
        // tło rysuje okno (jedna warstwa zamiast dwóch – mniej przerysowań całego ekranu na słabszych urządzeniach)
        // odtwarzacz: obraz zasłania tło – X25 bez rysowanego tła (pełnoekranowa grafika pod wideo w każdej klatce = przycięcia)
        if (isPlayerScreen) rootFrame.background = if (X25Theme.active || Ps5Theme.active) android.graphics.drawable.ColorDrawable(Ui.bg) else Ui.bgDrawable()
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
                bgImage.load(f) { memoryCacheKey(key); diskCachePolicy(CachePolicy.DISABLED); crossfade(false) }
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

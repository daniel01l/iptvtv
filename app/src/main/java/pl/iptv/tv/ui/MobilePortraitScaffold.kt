package pl.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (7.22) Nawigacja telefonu trzymanego pionowo (9:16): dolny, szklany pasek w strefie kciuka z zakładkami **Live TV**,
 * **Stremio**, **STube**, **Do obejrzenia** i **Więcej** (Ustawienia) – zamiast bocznych pasków TV.
 *
 * Ścisła izolacja: działa WYŁĄCZNIE, gdy [DeviceDisplayProfile.isPortraitPhone] (telefon / tablet pionowo). Android TV
 * i każde urządzenie poziomo – dotychczasowy układ bez zmian ([active] = false, nic nie jest dodawane).
 *
 * Kolory wyłącznie z tokenów aktywnego motywu ([CardSheet.palette]): tło – panel motywu (szkło 94 %) z krawędzią specular,
 * zakładka wybrana – akcent na pigułce `chosenFill`, pozostałe – tekst drugorzędny. Dolny odstęp systemowego paska
 * nawigacji (`navigationBars`) doliczany do wysokości paska; treść ekranu kończy się nad paskiem.
 */
object MobilePortraitScaffold {
    enum class Tab(val label: String, val icon: String) {
        LIVE("Live TV", PlayerIcon.TVSET),
        STREMIO("Stremio", PlayerIcon.FILM),
        STUBE("STube", PlayerIcon.VIDEO),
        WATCHLIST("Do obejrzenia", PlayerIcon.BOOKMARK_ADD),
        MORE("Więcej", PlayerIcon.INFO),
    }

    /** Wysokość paska bez odstępu systemowego (dp). */
    const val BAR_DP = 62
    const val ICON_DP = 24
    /** Tag paska i zakładek: „portrait_nav”, „portrait_tab:<NAZWA>”. */
    const val TAG = "portrait_nav"
    const val TAB_TAG = "portrait_tab:"

    /** Testy: wymuszony stan (null – z profilu ekranu). */
    @Volatile var forced: Boolean? = null

    /** Układ pionowy telefonu (jedyny warunek wszystkich zmian 9:16). */
    fun active(a: android.content.Context): Boolean = forced ?: runCatching { DeviceDisplayProfile.of(a).isPortraitPhone }.getOrDefault(false)

    /**
     * Dolny pasek w [root] (nad treścią [content]); treść dostaje dolny margines = wysokość paska (z odstępem systemowym).
     * [onInset] – wysokość paska w px po każdym pomiarze (np. mini-odtwarzacz nad paskiem).
     */
    fun install(a: Activity, root: FrameLayout, content: View, current: Tab, onInset: (Int) -> Unit = {}): LinearLayout {
        val pal = CardSheet.palette()
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = TAG
            minimumHeight = Ui.dp(BAR_DP); setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4))
            // szkło panelu motywu + krawędź specular (boki i dół poza ekranem – widoczna linia u góry); minSdk 21 – bez LayerDrawable z grawitacją
            background = GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.panel, 0xF0)); setStroke(Ui.dp(1), pal.specular) }
            elevation = Ui.dp(8).toFloat(); isClickable = true
        }
        Tab.entries.forEach { t -> bar.addView(tabView(a, t, t == current, pal) { open(a, t) }, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // odstęp systemowego paska nawigacji (gesty / przyciski) – pasek w strefie kciuka, ponad nim
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { v, ins ->
            val b = runCatching { ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom }.getOrDefault(0)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, Ui.dp(4) + b); ins
        }
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            (content.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> if (lp.bottomMargin != h) { lp.bottomMargin = h; content.layoutParams = lp } }
            onInset(h)
        }
        runCatching { androidx.core.view.ViewCompat.requestApplyInsets(bar) }
        return bar
    }

    private fun tabView(a: Activity, t: Tab, selected: Boolean, pal: CardSheet.Palette, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        tag = TAB_TAG + t.name; contentDescription = t.label; isSelected = selected
        setPadding(Ui.dp(2), Ui.dp(5), Ui.dp(2), Ui.dp(5))
        val tint = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(pal.focusText, pal.accent or 0xFF000000.toInt(), pal.text2))
        background = StateListDrawable().apply {
            fun r(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = Ui.dp(16).toFloat() }
            addState(intArrayOf(android.R.attr.state_focused), r(pal.focusFill))
            addState(intArrayOf(android.R.attr.state_selected), r(pal.chosenFill))
            addState(intArrayOf(), r(0))
        }
        addView(android.widget.ImageView(a).apply { setImageDrawable(PlayerIcon(t.icon, tint)); isDuplicateParentStateEnabled = true },
            LinearLayout.LayoutParams(Ui.dp(ICON_DP), Ui.dp(ICON_DP)))
        addView(TextView(a).apply {
            text = t.label; textSize = 11f; tag = "keepcolor"; maxLines = 1; gravity = Gravity.CENTER; isDuplicateParentStateEnabled = true
            setTextColor(tint); if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(2) })
        setOnClickListener { onClick() }
    }

    /** Ekran zakładki. */
    fun intentFor(a: Activity, t: Tab): Intent = when (t) {
        Tab.LIVE -> Intent(a, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_DASHBOARD, true).putExtra(MainActivity.EXTRA_SKIP_START, true)
        Tab.STREMIO -> Intent(a, StremioActivity::class.java).putExtra(StremioActivity.EXTRA_FILTER, StremioActivity.FILTER_ALL)
        Tab.STUBE -> Intent(a, STubeActivity::class.java)
        Tab.WATCHLIST -> Intent(a, StremioActivity::class.java).putExtra(StremioActivity.EXTRA_FILTER, StremioActivity.FILTER_LIBRARY)
        Tab.MORE -> Intent(a, SettingsActivity::class.java)
    }

    /**
     * Przejście na zakładkę: ten sam ekran – [BaseActivity.onPortraitTab] (np. Stremio → Do obejrzenia bez nowego ekranu);
     * inny – ekran przeniesiony na wierzch (bez mnożenia kopii), bieżący zamknięty (lista kanałów zostaje u podstawy).
     */
    fun open(a: Activity, t: Tab) {
        val i = intentFor(a, t)
        if (i.component?.className == a.javaClass.name && a is BaseActivity) { a.onPortraitTab(t); return }
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        @Suppress("DEPRECATION") runCatching { a.overridePendingTransition(0, 0) }
        if (a !is MainActivity) a.finish()
    }

    /**
     * Pigułka filtru / kategorii w kolorach motywu (wybrana – akcent na `chosenFill`, fokus – wypełnienie fokusu), wspólna dla
     * Live TV, Stremio i STube w pionie.
     */
    fun chip(a: android.content.Context, label: String, selected: Boolean, onClick: () -> Unit) = TextView(a).apply {
        val pal = CardSheet.palette()
        text = label; textSize = 14f; tag = "keepcolor nozoom"; isFocusable = true; isClickable = true; gravity = Gravity.CENTER; maxLines = 1
        setTypeface(typeface, android.graphics.Typeface.BOLD); isSelected = selected
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(pal.focusText, pal.accent or 0xFF000000.toInt(), pal.text)))
        background = StateListDrawable().apply {
            fun r(c: Int, s: Int) = GradientDrawable().apply { setColor(c); cornerRadius = Ui.dp(20).toFloat(); if (s != 0) setStroke(Ui.dp(1), s) }
            addState(intArrayOf(android.R.attr.state_focused), r(pal.focusFill, pal.focusRing))
            addState(intArrayOf(android.R.attr.state_selected), r(pal.chosenFill, pal.accent or 0xFF000000.toInt()))
            addState(intArrayOf(), r(pal.rest, pal.specular))
        }
        setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8))
        setOnClickListener { onClick() }
    }
}

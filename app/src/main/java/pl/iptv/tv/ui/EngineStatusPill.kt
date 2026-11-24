package pl.iptv.tv.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * (7.20) Nienachalna pigułka statusu w prawym górnym rogu ekranu (np. „Zastosowano: Dekoder programowy (c2.android.avc)”)
 * po przebudowie silnika w locie – [SHOW_MS] 2,5 s, wejście / wyjście przenikaniem. Kolory z motywu ([CardSheet.palette]):
 * „Obsidian Stream” – panel `#0F141F` 96 % z krawędzią specular, inne motywy – panel i akcent motywu. Nie przejmuje fokusu.
 */
object EngineStatusPill {
    const val SHOW_MS = 2_500L
    const val FADE_MS = 180L
    const val TAG = "engine_status_pill"

    fun show(a: Activity, text: String) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        hide(a, animate = false)
        val pal = CardSheet.palette()
        val pill = TextView(a).apply {
            tag = TAG; this.text = text; textSize = 14f; isFocusable = false; isClickable = false
            setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pal.panel); cornerRadius = Ui.dp(40).toFloat(); setStroke(Ui.dp(1), pal.specular)
            }
            // kropka w akcencie – stan „zastosowano”
            val dot = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pal.accent); setSize(Ui.dp(8), Ui.dp(8)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null); compoundDrawablePadding = Ui.dp(10)
            setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(18), Ui.dp(10))
            elevation = Ui.dp(6).toFloat(); alpha = 0f
        }
        decor.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = Ui.dp(24); marginEnd = Ui.dp(32) })
        I18n.attach(a.window)
        pill.animate().alpha(1f).setDuration(FADE_MS).start()
        pill.postDelayed({ if (pill.parent != null) pill.animate().alpha(0f).setDuration(FADE_MS).withEndAction { (pill.parent as? ViewGroup)?.removeView(pill) }.start() }, SHOW_MS)
    }

    /** Widoczna pigułka (testy). */
    fun current(a: Activity): TextView? = (a.window?.decorView as? ViewGroup)?.findViewWithTag(TAG)

    fun hide(a: Activity, animate: Boolean = false) {
        val p = current(a) ?: return
        p.animate().cancel()
        if (animate) p.animate().alpha(0f).setDuration(FADE_MS).withEndAction { (p.parent as? ViewGroup)?.removeView(p) }.start()
        else (p.parent as? ViewGroup)?.removeView(p)
    }
}

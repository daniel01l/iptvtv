package pl.iptv.tv.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * (7.16) Pasek ikon STube: przewijanie za fokusem łagodną animacją (zaznaczona ikona jest utrzymywana w środku widoku, a nie przy
 * samej krawędzi, jak w domyślnym ScrollView), z zanikaniem ikon przy krawędziach – granica widocznych ikon jest miękka.
 */
class RailScrollView(c: Context, a: android.util.AttributeSet? = null) : ScrollView(c, a) {
    private var anim: ObjectAnimator? = null

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Ui.dp(44))
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val content = getChildAt(0) ?: return false
        val maxY = (content.height - height + paddingTop + paddingBottom).coerceAtLeast(0)
        if (maxY == 0) return false
        // prostokąt we współrzędnych dziecka (rail) – środek ikony ma trafić w środek widoku
        val target = (child.top + rectangle.centerY() - height / 2).coerceIn(0, maxY)
        if (target != scrollY) scrollSmooth(target)
        return true
    }

    private fun scrollSmooth(y: Int) {
        anim?.cancel()
        anim = ObjectAnimator.ofInt(this, "scrollY", scrollY, y).apply {
            duration = if (pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) 0 else 240; interpolator = DecelerateInterpolator(1.6f); start()
        }
    }
}

/** Poziomy odpowiednik [RailScrollView] (pasek ikon na wąskim ekranie). */
class RailHorizontalScrollView(c: Context) : HorizontalScrollView(c) {
    private var anim: ObjectAnimator? = null

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(Ui.dp(36))
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val content = getChildAt(0) ?: return false
        val maxX = (content.width - width + paddingLeft + paddingRight).coerceAtLeast(0)
        if (maxX == 0) return false
        val target = (child.left + rectangle.centerX() - width / 2).coerceIn(0, maxX)
        if (target != scrollX) {
            anim?.cancel()
            anim = ObjectAnimator.ofInt(this, "scrollX", scrollX, target).apply {
                duration = if (pl.iptv.tv.ui.DevicePerformanceProfile.uiLow) 0 else 240; interpolator = DecelerateInterpolator(1.6f); start()
            }
        }
        return true
    }
}

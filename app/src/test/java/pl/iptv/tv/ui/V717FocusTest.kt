package pl.iptv.tv.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import pl.iptv.tv.ui.theme.TV_FOCUS_DAMPING
import pl.iptv.tv.ui.theme.TV_FOCUS_STIFFNESS

/** (7.17) Zaznaczenie bez ucinania i płynne przechodzenie fokusu – wspólne dla widoków i Compose, wszystkie motywy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class V717FocusTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun reset() { Ui.applyPreset("modern") }

    @Test fun pivotZoneKeepsFocusBetweenQuarterAndThreeQuarters() {
        // w strefie – bez ruchu
        assertEquals(0f, pivotScrollDistance(300f, 100f, 1000f, 10f), 0f)
        // poniżej 75 % – do granicy strefy (najmniejszy ruch)
        assertEquals(100f, pivotScrollDistance(750f, 100f, 1000f, 10f), 0f)
        // powyżej 25 % – w drugą stronę
        assertEquals(-150f, pivotScrollDistance(100f, 100f, 1000f, 10f), 0f)
        // element większy niż strefa – na środek; większy niż lista – od początku
        assertEquals(-50f, pivotScrollDistance(100f, 700f, 1000f, 10f), 0f)
        assertEquals(40f, pivotScrollDistance(40f, 1200f, 1000f, 10f), 0f)
        // ta sama sprężyna fokusu wszędzie: bez odbicia, 500
        assertEquals(1f, TV_FOCUS_DAMPING); assertEquals(500f, TV_FOCUS_STIFFNESS); assertEquals(500f, FOCUS_STIFFNESS)
        assertEquals(140L, Ui.FOCUS_ANIM_MS)
    }

    @Test fun navigationRailItemsAreCenteredSquaresThatGrowWithRail() {
        val a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()
        // jak w STube: przewijany pasek → kontener z własnym odstępem → pozycje
        val inner = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(Ui.dp(12), Ui.dp(16), Ui.dp(12), Ui.dp(16)) }
        val items = (0 until 3).map { i -> TextView(a).apply { text = "Pozycja $i"; isFocusable = true; contentDescription = "Pozycja $i"; isSelected = i == 1 }
            .also { inner.addView(it, android.widget.LinearLayout.LayoutParams(-1, -2)) } }
        val rail = FrameLayout(a).apply { addView(inner, FrameLayout.LayoutParams(-1, -2)) }
        val root = FrameLayout(a).apply { addView(rail, FrameLayout.LayoutParams(Ui.dp(72), -1)) }
        a.setContentView(root)
        val nav = pl.iptv.tv.ui.theme.AppNavigationRailViews(rail, View(a).also { root.addView(it) }, { items }, { it.contentDescription.toString() })
        nav.attach()
        fun layout(w: Int) {
            rail.layoutParams.width = w; rail.requestLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(960), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Ui.dp(540), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, Ui.dp(960), Ui.dp(540))
        }
        layout(Ui.dp(72))
        for (b in items) {
            // zwinięty pasek: kwadrat 44 dp, 14 dp z lewej i z prawej (równo), ikona na środku
            assertEquals(Ui.dp(14), b.left + inner.left); assertEquals(Ui.dp(44), b.width); assertEquals(Ui.dp(44), b.height)
            assertEquals(Ui.dp(72) - Ui.dp(14), b.right + inner.left)
        }
        // wybrana sekcja: wypełnienie 22 % i obwódka (rysowana wewnątrz granic)
        val sel = items[1].background.current as android.graphics.drawable.GradientDrawable
        assertEquals((0.22f * 255).roundToInt(), android.graphics.Color.alpha(sel.color!!.defaultColor))
        // rozwinięty: ten sam kontener na całą szerokość pozycji (te same marginesy, bez przeskoku)
        layout(Ui.dp(240))
        assertEquals(Ui.dp(14), items[0].left + inner.left); assertEquals(Ui.dp(240) - Ui.dp(28), items[0].width)
        nav.detach(); a.finish()
    }

    @Test fun listsLeaveRoomForZoomAndDrawFocusedItemLast() {
        for (theme in listOf("modern", "classic", "xbox", "x25", "ps5dash")) {
            Ui.applyPreset(theme)
            val a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()
            a.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            val rv = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 40
                    override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(TextView(a).apply {
                        tag = "item"; isFocusable = true; layoutParams = RecyclerView.LayoutParams(-1, Ui.dp(48)) }) {}
                    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) { (h.itemView as TextView).text = "Kanał $i" }
                }
            }
            a.setContentView(FrameLayout(a).apply { addView(rv, FrameLayout.LayoutParams(Ui.dp(400), Ui.dp(480))) })
            Ui.style(rv)
            rv.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(400), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Ui.dp(480), View.MeasureSpec.EXACTLY))
            rv.layout(0, 0, Ui.dp(400), Ui.dp(480))
            // odstęp listy mieści największy przyrost powiększenia na stronę
            assertTrue(theme, rv.paddingLeft >= Ui.dp(Ui.ZOOM_ROOM_X_DP) && rv.paddingTop >= Ui.dp(Ui.ZOOM_ROOM_Y_DP))
            assertFalse(theme, rv.clipToPadding)
            val item = rv.getChildAt(2).also { Ui.style(it) }
            item.measure(View.MeasureSpec.makeMeasureSpec(rv.width - rv.paddingLeft - rv.paddingRight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Ui.dp(48), View.MeasureSpec.EXACTLY))
            item.layout(item.left, item.top, item.left + item.measuredWidth, item.top + Ui.dp(48))
            item.stateListAnimator?.let { sla ->
                // powiększenie: przyrost na stronę ≤ odstępu listy (nic nie wychodzi poza listę)
                item.isFocusable = true; item.requestFocus(); sla.jumpToCurrentState()
                val grow = (item.width * (item.scaleX - 1f)) / 2
                assertTrue("$theme $grow", grow <= Ui.dp(Ui.ZOOM_ROOM_X_DP) + 0.5f)
            }
            item.requestFocus()
            assertSame(theme, item, rv.focusedChild)
            // zaznaczony rysowany na końcu (nad sąsiadami)
            val m = ViewGroup::class.java.getDeclaredMethod("getChildDrawingOrder", Int::class.java, Int::class.java).apply { isAccessible = true }
            val idx = rv.indexOfChild(item)
            assertEquals(theme, idx, m.invoke(rv, rv.childCount, rv.childCount - 1))
            assertEquals(theme, idx + 1, m.invoke(rv, rv.childCount, idx))
            a.finish()
        }
    }
}

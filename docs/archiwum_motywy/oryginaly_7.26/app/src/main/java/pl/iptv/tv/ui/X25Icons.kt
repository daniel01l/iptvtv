package pl.iptv.tv.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import pl.iptv.tv.data.X25Action
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ikony pulpitu X25 rysowane w [DrawScope] (kilka linii i kształtów) – bez bitmap i plików wektorowych,
 * skalują się do dowolnego rozmiaru i koloru motywu.
 */
object X25Icons {
    const val SEARCH = "i:search"; const val PROFILE = "i:profile"; const val WIFI = "i:wifi"; const val ETHERNET = "i:eth"
    const val OFFLINE = "i:off"; const val MORE = "i:more"; const val PIN = "i:pin"; const val PLAY = "i:play"
    const val MOVE = "i:move"; const val RENAME = "i:rename"; const val RESIZE = "i:resize"; const val HIDE = "i:hide"; const val SHOW = "i:show"
    const val DELETE = "i:delete"; const val RESET = "i:reset"; const val POWER = "i:power"; const val EDIT = "i:edit"
    /** Wszystkie ikony własne (testy: każda ma rysunek, nie zapasowy kwadrat). */
    val ALL = listOf(SEARCH, PROFILE, WIFI, ETHERNET, OFFLINE, MORE, PIN, PLAY, MOVE, RENAME, RESIZE, HIDE, SHOW, DELETE, RESET, POWER, EDIT)

    /** Czy [kind] ma własny rysunek (inaczej – zapasowy kwadrat). */
    fun known(kind: String) = kind in ALL || kind in X25Action.DEFAULT_TITLES

    fun draw(s: DrawScope, kind: String, c: Color) = with(s) {
        val w = size.width; val h = size.height; val m = minOf(w, h)
        val st = Stroke(width = m * 0.075f, cap = StrokeCap.Round)
        val ox = (w - m) / 2; val oy = (h - m) / 2
        fun p(x: Float, y: Float) = Offset(ox + x * m, oy + y * m)
        fun rr(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f, fill: Boolean = false) =
            drawRoundRect(c, p(x, y), Size(rw * m, rh * m), CornerRadius(r * m), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
        fun tri(x: Float, y: Float, sz: Float, col: Color = c) = drawPath(Path().apply {
            moveTo(p(x, y).x, p(x, y).y); lineTo(p(x, y + sz).x, p(x, y + sz).y); lineTo(p(x + sz * 0.87f, y + sz / 2).x, p(x + sz * 0.87f, y + sz / 2).y); close()
        }, col)
        when (kind) {
            X25Action.LIVE, X25Action.CHANNEL -> {
                rr(0.1f, 0.2f, 0.8f, 0.55f); line(0.35f, 0.88f, 0.65f, 0.88f); line(0.5f, 0.75f, 0.5f, 0.88f)
                if (kind == X25Action.CHANNEL) { line(0.5f, 0.2f, 0.36f, 0.06f); line(0.5f, 0.2f, 0.64f, 0.06f) }
                else drawCircle(c, m * 0.06f, p(0.76f, 0.33f))
            }
            X25Action.STUBE, X25Action.STUBE_ROW, PLAY -> { rr(0.08f, 0.2f, 0.84f, 0.6f, 0.16f); tri(0.4f, 0.34f, 0.32f) }
            X25Action.STREMIO -> {
                drawPath(Path().apply { moveTo(p(0.5f, 0.06f).x, p(0.5f, 0.06f).y); lineTo(p(0.94f, 0.5f).x, p(0.94f, 0.5f).y); lineTo(p(0.5f, 0.94f).x, p(0.5f, 0.94f).y); lineTo(p(0.06f, 0.5f).x, p(0.06f, 0.5f).y); close() }, c, style = st)
                tri(0.42f, 0.36f, 0.28f)
            }
            X25Action.PLAYLISTS, X25Action.STUBE_PLAYLIST -> {
                line(0.12f, 0.25f, 0.62f, 0.25f); line(0.12f, 0.45f, 0.62f, 0.45f); line(0.12f, 0.65f, 0.45f, 0.65f); tri(0.58f, 0.55f, 0.3f)
            }
            X25Action.SUBS -> { rr(0.1f, 0.3f, 0.8f, 0.55f, 0.1f); line(0.2f, 0.17f, 0.8f, 0.17f); tri(0.42f, 0.44f, 0.26f) }
            X25Action.HISTORY -> { drawCircle(c, m * 0.38f, p(0.5f, 0.5f), style = st); line(0.5f, 0.5f, 0.5f, 0.28f); line(0.5f, 0.5f, 0.66f, 0.6f) }
            X25Action.FAVORITES -> drawPath(Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.42f else 0.18f; val a = Math.toRadians(-90.0 + i * 36.0)
                    val q = p(0.5f + r * cos(a).toFloat(), 0.53f + r * sin(a).toFloat())
                    if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                }; close()
            }, c, style = st)
            X25Action.GUIDE -> { for (r in 0..1) for (col in 0..2) rr(0.08f + col * 0.29f, 0.22f + r * 0.3f, 0.24f, 0.24f, 0.05f) }
            X25Action.EPG_SEARCH, SEARCH -> { drawCircle(c, m * 0.25f, p(0.43f, 0.43f), style = st); line(0.62f, 0.62f, 0.85f, 0.85f) }
            X25Action.SETTINGS -> {
                val ctr = p(0.5f, 0.5f)
                for (i in 0 until 8) rotate(i * 45f, ctr) { drawRoundRect(c, Offset(ctr.x - m * 0.07f, ctr.y - m * 0.42f), Size(m * 0.14f, m * 0.18f), CornerRadius(m * 0.03f)) }
                drawCircle(c, m * 0.27f, ctr, style = st); drawCircle(c, m * 0.09f, ctr, style = st)
            }
            X25Action.APPS -> { for (r in 0..1) for (col in 0..1) rr(0.14f + col * 0.4f, 0.14f + r * 0.4f, 0.3f, 0.3f, 0.07f) }
            X25Action.MIX -> {
                drawCircle(c, m * 0.12f, p(0.32f, 0.72f)); drawCircle(c, m * 0.12f, p(0.72f, 0.62f))
                line(0.43f, 0.72f, 0.43f, 0.18f); line(0.83f, 0.62f, 0.83f, 0.1f); line(0.43f, 0.18f, 0.83f, 0.1f)
            }
            PROFILE -> { drawCircle(c, m * 0.19f, p(0.5f, 0.36f)); drawArc(c, 180f, 180f, true, p(0.18f, 0.62f), Size(m * 0.64f, m * 0.6f)) }
            WIFI -> {
                for (i in 1..3) { val r = 0.13f * i + 0.04f
                    drawArc(c, 225f, 90f, false, p(0.5f - r, 0.78f - r), Size(2 * r * m, 2 * r * m), style = st) }
                drawCircle(c, m * 0.07f, p(0.5f, 0.78f))
            }
            ETHERNET -> { rr(0.38f, 0.1f, 0.24f, 0.2f, 0.03f); rr(0.08f, 0.66f, 0.24f, 0.2f, 0.03f); rr(0.68f, 0.66f, 0.24f, 0.2f, 0.03f)
                line(0.5f, 0.3f, 0.5f, 0.5f); line(0.2f, 0.5f, 0.8f, 0.5f); line(0.2f, 0.5f, 0.2f, 0.66f); line(0.8f, 0.5f, 0.8f, 0.66f) }
            OFFLINE -> { drawArc(c, 225f, 90f, false, p(0.1f, 0.38f), Size(0.8f * m, 0.8f * m), style = st); line(0.15f, 0.15f, 0.85f, 0.85f) }
            MORE -> { for (i in 0..2) drawCircle(c, m * 0.08f, p(0.22f + i * 0.28f, 0.5f)) }
            PIN -> { drawCircle(c, m * 0.2f, p(0.5f, 0.32f)); line(0.5f, 0.5f, 0.5f, 0.9f) }
            // przesuwanie: strzałki ◄ ►
            MOVE -> {
                line(0.12f, 0.5f, 0.88f, 0.5f)
                line(0.12f, 0.5f, 0.3f, 0.32f); line(0.12f, 0.5f, 0.3f, 0.68f); line(0.88f, 0.5f, 0.7f, 0.32f); line(0.88f, 0.5f, 0.7f, 0.68f)
            }
            // nazwa: pole tekstowe z kursorem
            RENAME -> { rr(0.08f, 0.28f, 0.84f, 0.44f, 0.08f); line(0.24f, 0.5f, 0.52f, 0.5f); line(0.66f, 0.2f, 0.66f, 0.8f) }
            // rozmiar: mały i duży prostokąt ze strzałką
            RESIZE -> { rr(0.08f, 0.5f, 0.36f, 0.36f, 0.05f); rr(0.08f, 0.14f, 0.78f, 0.72f, 0.06f); line(0.5f, 0.5f, 0.74f, 0.26f); line(0.74f, 0.26f, 0.56f, 0.26f); line(0.74f, 0.26f, 0.74f, 0.44f) }
            // oko (pokaż) / przekreślone oko (ukryj)
            SHOW, HIDE -> {
                drawOval(c, p(0.08f, 0.28f), Size(0.84f * m, 0.44f * m), style = st); drawCircle(c, m * 0.12f, p(0.5f, 0.5f))
                if (kind == HIDE) line(0.14f, 0.86f, 0.86f, 0.14f)
            }
            // kosz
            DELETE -> { line(0.16f, 0.24f, 0.84f, 0.24f); line(0.4f, 0.24f, 0.42f, 0.12f); line(0.6f, 0.24f, 0.58f, 0.12f); line(0.42f, 0.12f, 0.58f, 0.12f)
                rr(0.24f, 0.24f, 0.52f, 0.64f, 0.06f); line(0.42f, 0.4f, 0.42f, 0.74f); line(0.58f, 0.4f, 0.58f, 0.74f) }
            // przywróć: strzałka w kółku
            RESET -> { drawArc(c, -60f, 300f, false, p(0.16f, 0.16f), Size(0.68f * m, 0.68f * m), style = st); line(0.67f, 0.2f, 0.82f, 0.2f); line(0.67f, 0.2f, 0.69f, 0.06f) }
            // wyłącz: symbol zasilania
            POWER -> { drawArc(c, -50f, 280f, false, p(0.16f, 0.18f), Size(0.68f * m, 0.68f * m), style = st); line(0.5f, 0.08f, 0.5f, 0.46f) }
            // edycja układu: ołówek
            EDIT -> { line(0.2f, 0.8f, 0.72f, 0.28f); line(0.32f, 0.88f, 0.84f, 0.36f); line(0.72f, 0.28f, 0.84f, 0.36f); line(0.2f, 0.8f, 0.14f, 0.92f); line(0.14f, 0.92f, 0.32f, 0.88f) }
            else -> rr(0.15f, 0.15f, 0.7f, 0.7f)
        }
    }
}

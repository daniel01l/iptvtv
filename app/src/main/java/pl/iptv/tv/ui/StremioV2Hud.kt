package pl.iptv.tv.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.CheckedTextView
import androidx.media3.common.C
import androidx.media3.common.Format
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.ui.theme.ObsidianGlass

/**
 * (v2) Odtwarzacz Stremio w motywie Nowoczesny v2: pigułka samej jakości obrazu ([quality]) i wygląd okien wyboru otwieranych
 * w odtwarzaczu ([decorate] – `V2GlassDialog` już z [Ui.dialogBg]; tu: pozycje list jako karty, aktywna z ramką `#38BDF8`
 * i znacznikiem ✓ po prawej). Podpinane tylko przez odtwarzacz Stremio ([Ui.dialogHook] w onResume / onPause).
 */
object StremioV2Hud {
    const val ROW_TAG = "stremio_v2_row"

    /** Jakość obrazu: „4K HDR”, „1080p”, „720p HDR”…; brak obrazu – pusto (bez nazwy silnika / dekodera). */
    fun quality(f: Format?): String {
        val h = f?.height?.takeIf { it > 0 } ?: return ""
        val w = f.width
        val res = VideoQuality.label(w, h)      // wspólne klasy jakości (film kinowy 1920 × 804 – „1080p”)
        val t = f.colorInfo?.colorTransfer
        val hdr = t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG
        return res + if (hdr) " HDR" else ""
    }

    /** Okno z listą wyboru: wiersze – karty `V2OptionRow` (16 dp, odstęp), aktywny – ramka 1,5 dp `#38BDF8` i ✓ po prawej. */
    fun decorate(d: androidx.appcompat.app.AlertDialog) {
        if (!ModernV2.active) return
        val lv = d.listView ?: return
        if (lv.getTag(pl.iptv.tv.R.id.row_key) == ROW_TAG) return
        lv.setTag(pl.iptv.tv.R.id.row_key, ROW_TAG)
        lv.divider = ColorDrawable(0); lv.dividerHeight = Ui.dp(6); lv.selector = ColorDrawable(0)
        lv.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(8)); lv.clipToPadding = false
        lv.isVerticalScrollBarEnabled = false; lv.itemsCanFocus = false
        // kursor listy (pozycja zaznaczona pilotem) – od razu widoczny na pierwszej / wybranej pozycji
        lv.post { if (lv.selectedItemPosition < 0 && lv.count > 0) { lv.requestFocus(); lv.setSelection(0) } }
        lv.viewTreeObserver.addOnPreDrawListener {
            var changed = false
            for (i in 0 until lv.childCount) (lv.getChildAt(i) as? android.widget.TextView)?.let { row ->
                if (row.getTag(pl.iptv.tv.R.id.ui_dup) != ROW_TAG) {
                    row.setTag(pl.iptv.tv.R.id.ui_dup, ROW_TAG)
                    row.background = rowCard()
                    row.minHeight = Ui.dp(48); row.textSize = 16f; row.maxLines = 2; row.ellipsize = android.text.TextUtils.TruncateAt.END
                    row.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(ObsidianGlass.TEXT, ObsidianGlass.TEXT, ObsidianGlass.TEXT2)))
                    row.tag = "keepcolor"
                    row.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(14), Ui.dp(10))
                    if (row is CheckedTextView) row.checkMarkDrawable = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), CheckMark())
                        addState(intArrayOf(), ColorDrawable(0).apply { setBounds(0, 0, Ui.dp(22), Ui.dp(22)) })
                    }
                }
                // zwykła lista (Dialogs.menu): „✔ ” na początku = bieżący wybór → ✓ po prawej i ramka „wybrana”
                if (row !is CheckedTextView) {
                    val t = row.text?.toString().orEmpty()
                    if (t.startsWith("✔")) { row.text = t.removePrefix("✔").trimStart(); row.isActivated = true
                        row.setCompoundDrawablesRelative(null, null, CheckMark().apply { setBounds(0, 0, Ui.dp(22), Ui.dp(22)) }, null); row.compoundDrawablePadding = Ui.dp(10); changed = true }
                    else if (row.getTag(pl.iptv.tv.R.id.row_key) != t && row.isActivated && !t.startsWith("✔") && row.getTag(pl.iptv.tv.R.id.row_key) != null) {
                        row.isActivated = false; row.setCompoundDrawablesRelative(null, null, null, null) }
                    row.setTag(pl.iptv.tv.R.id.row_key, row.text?.toString())
                }
            }
            !changed
        }
    }

    /** Karta wiersza: kursor pilota (zaznaczenie listy / fokus) – tint + ramka `#38BDF8`, bieżący wybór – cienka ramka, spoczynek – ciemne szkło. */
    private fun rowCard(): Drawable = StateListDrawable().apply {
        val on = ObsidianGlass.tintRing(16, pl.iptv.tv.ui.DevicePerformanceProfile.uiLow)
        addState(intArrayOf(android.R.attr.state_selected), on)
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(android.R.attr.state_pressed), ObsidianGlass.tintRing(16, true))
        val r = Ui.dp(16).toFloat()
        val chosen = android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(Ui.withAlpha(ObsidianGlass.FOCUS, 0x1F)); setStroke(Math.round(1.5f * Ui.density).coerceAtLeast(1), ObsidianGlass.FOCUS_RING) }
        addState(intArrayOf(android.R.attr.state_activated), chosen)
        addState(intArrayOf(android.R.attr.state_checked), chosen)
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(ObsidianGlass.PILL_REST); setStroke(Ui.dp(1), ObsidianGlass.PILL_BORDER) })
    }

    /** Znacznik ✓ `#38BDF8` (22 dp). */
    class CheckMark : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF()
        override fun getIntrinsicWidth() = Ui.dp(22)
        override fun getIntrinsicHeight() = Ui.dp(22)
        override fun draw(c: Canvas) { r.set(bounds); ObsidianGlass.drawCheck(c, r, p) }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

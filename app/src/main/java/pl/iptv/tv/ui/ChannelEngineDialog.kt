package pl.iptv.tv.ui

import android.app.Activity
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.ChannelEngineConfig
import pl.iptv.tv.data.ChannelEngineOverrides

/**
 * (7.28) Okno „Silnik kanału”: ręczne ustawienia silnika i akceleracji dla bieżącego kanału – te same opcje co Ustawienia →
 * Odtwarzanie → „Silnik odtwarzania i akceleracja” (bez AFR – tryb HDMI zostaje globalny), wypełnione ustawieniami obowiązującymi
 * dla kanału (nadpisanie albo globalne). Karty i przyciski w palecie motywu ([CardSheet] – v2: szkło V2GlassDialog, kapsuły,
 * fokus #38BDF8; v1 / klasyczne – karty motywu), bez cieni i rozmyć. OK na karcie – kolejna wartość / przełącznik (brudnopis);
 * „Zapisz” – [ChannelEngineOverrides.put] (zastępuje poprzedni wpis kanału, odtwarzacz przebudowany w tle bez zmiany trybu
 * HDMI), „Przywróć domyślne” – usunięcie nadpisania; po każdym zamknięciu (także WSTECZ) – [onClose] (powrót do menu).
 */
object ChannelEngineDialog {
    const val TITLE = "Silnik kanału"
    const val CUSTOM = "Własny"
    const val SAVE = "Zapisz"
    const val RESET = "Przywróć domyślne"
    const val CANCEL = "Anuluj"
    const val NOTE = "Ustawienia tylko dla tego kanału (pierwszeństwo przed Ustawieniami globalnymi). Dopasowanie odświeżania (AFR) zostaje globalne."
    private val QUEUE = listOf("Automatycznie", "Asynchroniczna (szybsza)", "Synchroniczna (gdy obraz zamarza)")

    @Volatile var shown: android.app.Dialog? = null; private set

    fun show(a: Activity, ch: Channel, onClose: () -> Unit): android.app.Dialog {
        val pal = CardSheet.palette()
        var draft: ChannelEngineConfig = ChannelEngineOverrides.get(ch) ?: ChannelEngineConfig.global()
        val custom = ChannelEngineOverrides.has(ch)
        val d = android.app.Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = CardSheet.panelBg(pal); setPadding(Ui.dp(22), Ui.dp(20), Ui.dp(22), Ui.dp(18)) }
        root.addView(TextView(a).apply {
            text = "$TITLE – ${ch.name}" + if (custom) "  •  $CUSTOM" else ""
            textSize = 20f; setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(4), 0, 0, Ui.dp(4))
        })
        root.addView(TextView(a).apply { text = NOTE; textSize = 13f; setTextColor(pal.text2); setPadding(Ui.dp(4), 0, 0, Ui.dp(10)) })
        val list = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; clipToPadding = false; clipChildren = false
            setPadding(Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(CardSheet.ROOM_Y_DP), Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(CardSheet.ROOM_Y_DP))
        }
        root.addView(android.widget.ScrollView(a).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        var saved = false
        val save = CardSheet.pill(a, SAVE, pal) { saved = true; ChannelEngineOverrides.put(ch, draft); d.dismiss() }
        val reset = CardSheet.pill(a, RESET, pal) { ChannelEngineOverrides.remove(ch); d.dismiss() }
        val cancel = CardSheet.pill(a, CANCEL, pal) { d.dismiss() }
        listOf(save, reset, cancel).forEach { it.id = View.generateViewId() }
        root.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            addView(cancel); addView(reset, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) }); addView(save, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(14) })

        fun card(key: String, title: String, value: String?, switch: Boolean?, onClick: () -> Unit): View {
            val c = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                id = View.generateViewId(); tag = "chengine:$key"; contentDescription = title
                background = CardSheet.cardBg(pal); setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11)); setOnClickListener { onClick() }
            }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
            col.addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            if (value != null) col.addView(TextView(a).apply { text = value; textSize = 13f; setTextColor(CardSheet.textColors(pal, secondary = true)); isDuplicateParentStateEnabled = true; setPadding(0, Ui.dp(2), 0, 0) })
            c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (switch != null) c.addView(android.widget.ImageView(a).apply { setImageDrawable(CardSheet.Switch(switch, pal)); isDuplicateParentStateEnabled = true },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(12) })
            CardSheet.focusScale(c)
            return c
        }

        fun render(focusKey: String?) {
            list.removeAllViews()
            val v = draft
            val bufs = EngineProfiles.BUFFER_VALUES
            val surfaces = RenderSurfaceMode.entries
            val limits = VideoResolutionLimit.entries
            val cards = listOf(
                card("engine", "Silnik odtwarzania", Playback.ENGINES.getOrNull(v.engine), null) { draft = v.copy(engine = (v.engine + 1) % Playback.ENGINES.size); render("engine") },
                card("hw", "Akceleracja sprzętowa obrazu", null, v.hwAccel) { draft = v.copy(hwAccel = !v.hwAccel); render("hw") },
                card("async", "Kolejka dekodera", QUEUE[v.asyncDecoder.coerceIn(0, 2)], null) { draft = v.copy(asyncDecoder = (v.asyncDecoder + 1) % 3); render("async") },
                card("fallback", "Zapasowy dekoder przy błędzie", null, v.decoderFallback) { draft = v.copy(decoderFallback = !v.decoderFallback); render("fallback") },
                card("stutter", SettingsActivity.AUTO_STUTTER_TITLE, null, v.autoStutter) { draft = v.copy(autoStutter = !v.autoStutter); render("stutter") },
                card("surface", "Powierzchnia obrazu", surfaces.getOrElse(v.surfaceMode) { RenderSurfaceMode.AUTO }.label, null) {
                    draft = v.copy(surfaceMode = (v.surfaceMode + 1) % surfaces.size); render("surface")
                },
                card("maxres", SettingsActivity.MAX_VIDEO_TITLE, VideoResolutionLimit.of(v.maxVideoRes).label, null) {
                    draft = v.copy(maxVideoRes = (v.maxVideoRes + 1) % limits.size); render("maxres")
                },
                card("buffer", "Bufor – minimum", EngineProfiles.bufferLabel(v.bufMinS), null) {
                    draft = v.copy(bufMinS = bufs[(bufs.indexOf(v.bufMinS).coerceAtLeast(0) + 1) % bufs.size]); render("buffer")
                },
                card("loudness", "Wyrównywanie głośności kanałów", null, v.loudness) { draft = v.copy(loudness = !v.loudness); render("loudness") },
                card("lowlat", "Niskie opóźnienie na żywo", null, v.lowLatency) { draft = v.copy(lowLatency = !v.lowLatency); render("lowlat") },
            )
            cards.forEachIndexed { i, c -> list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { if (i < cards.size - 1) bottomMargin = Ui.dp(8) }) }
            // D-pad: ▲ z pierwszej zostaje, ▼ z ostatniej – „Zapisz”; przyciski ◄ ► między sobą, ▲ – ostatnia karta
            cards.first().nextFocusUpId = cards.first().id
            cards.last().nextFocusDownId = save.id
            listOf(save, reset, cancel).forEach { it.nextFocusUpId = cards.last().id; it.nextFocusDownId = it.id }
            (cards.firstOrNull { it.tag == "chengine:$focusKey" } ?: cards.first()).let { if (d.isShowing) it.requestFocus() }
            I18n.attach(d.window)
        }

        render(null)
        d.setContentView(root)
        CardSheet.window(d, a, maxWidthDp = 600)
        d.setOnKeyListener { dlg, code, e -> if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { dlg.dismiss(); true } else false }
        d.setOnShowListener { I18n.attach(d.window); list.getChildAt(0)?.requestFocus() }
        d.setOnDismissListener { if (shown === d) shown = null; onClose() }
        shown = d
        d.show()
        return d
    }
}

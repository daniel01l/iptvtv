package pl.iptv.tv.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Channel

/**
 * (7.20) Okno silnika w odtwarzaczu (przytrzymanie OK / kafelek „Silnik i akceleracja” w menu ikon). Te same karty,
 * przełączniki i kolory motywu co okna Ustawień ([CardSheet]); na górze na żywo kodeki obrazu i dźwięku
 * ([Playback.codecSummary]). Wartości z [EngineConfigRepository.effectiveConfig] (obserwowane, póki okno jest otwarte –
 * zmiana z Ustawień, strażnika czy doboru widoczna od razu), zapis tymi samymi metodami repozytorium co Ustawienia;
 * plakietka „Globalne” / „Dla tego kanału” przy każdej wartości, dla nadpisania kanału – „Zastosuj globalnie” i
 * „Resetuj dla kanału”. Zmiana wchodzi w życie od razu ([Playback.reloadIfNeeded] – bez przełączania kanału).
 */
object EngineSheet {
    /** Odświeżanie linii kodeków (ms). */
    const val CODEC_TICK_MS = 1_000L
    const val TITLE = "Silnik odtwarzania"

    /** Otwarte okno (testy). */
    @Volatile var shown: android.app.Dialog? = null; private set

    /** [extras] – dodatkowe akcje na końcu (np. ręczne dopasowanie silnika, szybkie menu ikon); zamykają okno. */
    fun show(a: AppCompatActivity, channel: Channel?, streamUrl: () -> String?, extras: List<Pair<String, () -> Unit>> = emptyList(),
             onDismiss: (() -> Unit)? = null): android.app.Dialog {
        val pal = CardSheet.palette()
        val repo = EngineConfigRepository
        val d = android.app.Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; background = CardSheet.panelBg(pal)
            setPadding(Ui.dp(22), Ui.dp(20), Ui.dp(22), Ui.dp(18))
        }
        root.addView(TextView(a).apply { text = TITLE; textSize = 20f; setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(4), 0, 0, Ui.dp(4)) })
        val codecs = TextView(a).apply { tag = "engine_codecs"; textSize = 13f; setTextColor(pal.text2); setPadding(Ui.dp(4), 0, 0, Ui.dp(10)); text = Playback.codecSummary() }
        root.addView(codecs)
        val list = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; clipToPadding = false; clipChildren = false
            setPadding(Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(CardSheet.ROOM_Y_DP), Ui.dp(CardSheet.ROOM_X_DP), Ui.dp(CardSheet.ROOM_Y_DP))
        }
        val scroll = android.widget.ScrollView(a).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        val done = CardSheet.pill(a, "Gotowe", pal) { d.dismiss() }
        root.addView(done, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.END; topMargin = Ui.dp(14) })

        fun card(key: String, title: String, value: String?, source: ConfigSource?, switch: Boolean? = null, onClick: () -> Unit): View {
            val c = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; id = View.generateViewId(); tag = "engine:$key"
                contentDescription = title + (switch?.let { if (it) ": włączone" else ": wyłączone" } ?: "")
                background = CardSheet.cardBg(pal); setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11))
                setOnClickListener { onClick() }
            }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
            col.addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            if (value != null) col.addView(TextView(a).apply { text = value; tag = "value"; textSize = 13f; setTextColor(CardSheet.textColors(pal, secondary = true)); isDuplicateParentStateEnabled = true; setPadding(0, Ui.dp(2), 0, 0) })
            c.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            if (source != null) c.addView(CardSheet.sourceChip(a, source, pal), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
            if (switch != null) c.addView(android.widget.ImageView(a).apply { setImageDrawable(CardSheet.Switch(switch, pal)); tag = "switch"; isDuplicateParentStateEnabled = true },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(12) })
            CardSheet.focusScale(c)
            return c
        }

        fun render(cfg: PlayerEngineConfig) {
            val focusedKey = (list.findFocus()?.let { f -> generateSequence(f) { it.parent as? View }.firstOrNull { (it.tag as? String)?.startsWith("engine:") == true } })?.tag
            list.removeAllViews()
            val ch = channel
            val decoder = when { cfg.tunneling -> "Tunelowanie (Android TV)"; cfg.software -> "Programowy (c2.android / OMX.google)"; else -> "Sprzętowy" }
            val cards = mutableListOf<View>()
            cards += card("engine", "Dekoder obrazu", decoder, cfg.softwareSource) { repo.cycleEngine() }
            cards += card("hw", "Akceleracja sprzętowa", null, ConfigSource.GLOBAL, cfg.hwAccel) { repo.setHwAccel(!cfg.hwAccel) }
            cards += card("async", "Kolejka dekodera", listOf("Automatycznie", "Asynchroniczna", "Synchroniczna")[cfg.effectiveAsync.coerceIn(0, 2)], cfg.asyncSource) {
                repo.setAsyncDecoder((cfg.asyncDecoder + 1) % 3)
            }
            cards += card("surface", "Powierzchnia obrazu", cfg.surface.label + " → " + (if (cfg.textureView) "TextureView" else "SurfaceView"), cfg.surfaceSource) {
                repo.setSurfaceMode(RenderSurfaceMode.entries[(cfg.surface.ordinal + 1) % RenderSurfaceMode.entries.size])
            }
            val bufs = EngineProfiles.BUFFER_VALUES
            cards += card("buffer", "Bufor – minimum", if (cfg.bufferSource == ConfigSource.CHANNEL) "Duży bufor (start 3 s, po przycięciu 8 s)" else EngineProfiles.bufferLabel(cfg.bufMinS), cfg.bufferSource) {
                repo.setBufMin(bufs[(bufs.indexOf(cfg.bufMinS).coerceAtLeast(0) + 1) % bufs.size])
            }
            cards += card("afr", "Dopasowanie odświeżania (AFR)", null, ConfigSource.GLOBAL, cfg.autoFrameRate) { repo.setAutoFrameRate(!cfg.autoFrameRate) }
            cards += card("loudness", "Wyrównywanie głośności kanałów", null, ConfigSource.GLOBAL, cfg.loudness) { repo.setLoudness(!cfg.loudness) }
            // (7.22) strażnik klatkowania – to samo źródło co Ustawienia
            cards += card("stutter", SettingsActivity.AUTO_STUTTER_TITLE, null, ConfigSource.GLOBAL, cfg.autoStutterCorrectionEnabled) { repo.setAutoStutterCorrection(!cfg.autoStutterCorrectionEnabled) }
            if (ch != null) {
                val compat = cfg.channelProfile?.let { it.software && it.surface == 1 } == true
                cards += card("compat", "Tryb zgodności dla tego kanału", "Dekoder programowy + TextureView, bez tunelowania", ConfigSource.CHANNEL, compat) {
                    repo.setChannelCompatibility(ch.key, streamUrl() ?: ch.url, !compat)
                }
                if (cfg.hasChannelOverride) {
                    val e = cfg.channel!!
                    val what = if (e.testedFailed) "Kanał nie otworzył się na żadnym silniku" else "Kanał: " + (e.profile?.label ?: "ustawienia domyślne")
                    cards += card("apply_global", "Zastosuj globalnie", what, null) { repo.applyChannelGlobally(ch.key, streamUrl() ?: ch.url) }
                    cards += card("reset_channel", "Resetuj dla kanału", "Ustawienia globalne dla ${ch.name}", null) { repo.resetChannel(ch.key, streamUrl() ?: ch.url) }
                }
            }
            extras.forEachIndexed { i, (label, action) -> cards += card("extra$i", label, null, null) { d.dismiss(); action() } }
            cards.forEachIndexed { i, v -> list.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i < cards.size - 1) bottomMargin = Ui.dp(8) }) }
            // ▼ z ostatniej karty – „Gotowe”; pierwsza nie ucieka w górę
            cards.lastOrNull()?.let { last -> last.nextFocusDownId = done.id; done.nextFocusUpId = last.id; done.nextFocusLeftId = done.id; done.nextFocusRightId = done.id }
            cards.firstOrNull()?.let { it.nextFocusUpId = it.id }
            if (d.isShowing) (cards.firstOrNull { it.tag == focusedKey } ?: if (done.isFocused) null else cards.firstOrNull())?.requestFocus()
            codecs.text = Playback.codecSummary()
            I18n.attach(d.window)
        }

        render(repo.current)
        d.setContentView(root)
        CardSheet.window(d, a, maxWidthDp = 600)
        val tick = object : Runnable {
            override fun run() { if (!d.isShowing) return; codecs.text = Playback.codecSummary(); codecs.postDelayed(this, CODEC_TICK_MS) }
        }
        val job = a.lifecycleScope.launch {
            a.repeatOnLifecycle(Lifecycle.State.STARTED) { repo.effectiveConfig.collect { if (d.isShowing) render(it) } }
        }
        d.setOnShowListener {
            I18n.attach(d.window)
            list.getChildAt(0)?.requestFocus()
            codecs.postDelayed(tick, CODEC_TICK_MS)
        }
        d.setOnDismissListener { job.cancel(); codecs.removeCallbacks(tick); if (shown === d) shown = null; onDismiss?.invoke() }
        shown = d
        d.show()
        return d
    }
}

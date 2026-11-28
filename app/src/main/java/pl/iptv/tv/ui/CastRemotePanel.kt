package pl.iptv.tv.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.lang.ref.WeakReference
import pl.iptv.tv.R

/**
 * (7.28) Pilot Cast / DLNA na telefonie (i TV, z którego przesyłamy): pływający panel w kolorach aktywnego motywu ([CardSheet.palette] –
 * Nowoczesny v2: szkło okien, v1 / inne: panel motywu). Rozwinięty: tytuł i urządzenie, pasek postępu (VOD – przeciąganie = skok),
 * ⏮ −15 s ⏯ +30 s ⏭, głośność − / wycisz / +, Audio, Napisy, lista (kanały / odcinki / kolejka z [CastHub.Local.items]),
 * zwiń, zakończ. Zwinięty: kapsuła z tytułem i ⏯ – na każdym ekranie aplikacji w trakcie sesji ([CastHub.onActivityResumed]).
 * Odświeżanie co [TICK_MS] tylko gdy panel jest na ekranie; polecenia idą do backendu (sieć w jego wątku), wątek główny nie czeka.
 */
object CastRemotePanel {
    const val TAG = "cast_remote"
    const val TICK_MS = 1_000L
    const val BACK_MS = 15_000L
    const val FWD_MS = 30_000L
    const val VOLUME_STEP = 0.1f
    const val MAX_WIDTH_DP = 560
    const val MINIMIZE = "Zwiń"
    const val EXPAND = "Pilot"
    const val AUDIO = "Audio"
    const val SUBS = "Napisy"
    const val SUBS_OFF = "Wyłączone"
    /** (7.28) Wiersz podpowiedzi „Przełącz na tryb DLNA” ([CastHub.dlnaAlternative]). */
    const val DLNA_TAG = "cast_dlna_hint"
    const val DLNA_SWITCH = "Tryb DLNA"
    /** Sesja, dla której podpowiedź DLNA została już zamknięta / użyta (jednorazowo na sesję). */
    private var dlnaHintFor: CastHub.Session? = null

    private val shown = ArrayList<WeakReference<Activity>>()

    fun isShown(a: Activity) = a.findViewById<FrameLayout>(android.R.id.content)?.findViewWithTag<View>(TAG) != null

    /** Panel w [a]; istniejący zostaje (rozwijany, gdy [expanded]). */
    fun show(a: Activity, expanded: Boolean) {
        if (CastHub.session == null) return
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        val old = content.findViewWithTag<View>(TAG)
        if (old != null) { if (expanded && old.getTag(R.id.ui_dup) != true) { content.removeView(old) } else return }
        val v = build(a, expanded)
        content.addView(v, FrameLayout.LayoutParams(minOf(Ui.dp(MAX_WIDTH_DP), a.resources.displayMetrics.widthPixels - Ui.dp(24)), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(24) })
        shown.removeAll { it.get() == null || it.get() === a }; shown += WeakReference(a)
    }

    fun dismissAll() {
        shown.forEach { r -> r.get()?.findViewById<FrameLayout>(android.R.id.content)?.let { c -> c.findViewWithTag<View>(TAG)?.let { c.removeView(it) } } }
        shown.clear()
    }

    private fun build(a: Activity, expanded: Boolean): View {
        val pal = CardSheet.palette()
        val root = LinearLayout(a).apply {
            tag = TAG; orientation = LinearLayout.VERTICAL; isClickable = true
            setTag(R.id.ui_dup, expanded)
            setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12))
            background = if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.glassDialog(24)
                else GradientDrawable().apply { cornerRadius = Ui.dp(20).toFloat(); setColor(pal.panel); setStroke(Ui.dp(1), 0x33FFFFFF) }
            elevation = 0f
        }
        val title = TextView(a).apply { setTextColor(pal.text); textSize = 15f; maxLines = 2; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val sub = TextView(a).apply { setTextColor(pal.text2); textSize = 12f; maxLines = 1 }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(a).apply { setImageDrawable(PlayerIcon(PlayerIcon.CAST_ON)) }, LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)).apply { marginEnd = Ui.dp(10) })
        head.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub) }, LinearLayout.LayoutParams(0, -2, 1f))
        val playBtn = icon(a, pal, PlayerIcon.PAUSE) { toggle() }
        if (!expanded) {
            head.addView(playBtn)
            head.addView(text(a, pal, EXPAND) { show(a, true) })
            root.addView(head)
            tick(root, title, sub, playBtn, null, null)
            root.setOnClickListener { show(a, true) }
            return root
        }
        head.addView(text(a, pal, MINIMIZE) { (root.parent as? ViewGroup)?.removeView(root); show(a, false) })
        head.addView(icon(a, pal, PlayerIcon.CLOSE) { CastHub.stopCasting() })
        root.addView(head)
        // pasek postępu (VOD) – przeciągnięcie = skok do czasu
        val time = TextView(a).apply { setTextColor(pal.text2); textSize = 12f }
        val bar = SeekBar(a).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent); thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) { s.setTag(R.id.ui_dup, true) }
                override fun onStopTrackingTouch(s: SeekBar) {
                    s.setTag(R.id.ui_dup, false)
                    val d = CastHub.control?.durationMs() ?: 0L
                    if (d > 0) CastHub.control?.seekTo(d * s.progress / 1000)
                }
            })
        }
        root.addView(LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f)); addView(time) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        // transport
        val local = { CastHub.sessionLocal }
        root.addView(row(a,
            icon(a, pal, PlayerIcon.PREV) { local()?.previous() },
            text(a, pal, "−15 s") { seekBy(-BACK_MS) },
            playBtn,
            text(a, pal, "+30 s") { seekBy(FWD_MS) },
            icon(a, pal, PlayerIcon.NEXT) { local()?.next() }))
        // głośność, ścieżki, lista
        val muteBtn = icon(a, pal, PlayerIcon.MUTE) { CastHub.control?.let { c -> c.setMuted(!c.isMuted()) } }
        root.addView(row(a,
            text(a, pal, "−") { CastHub.control?.let { c -> c.setVolume((c.volume() - VOLUME_STEP).coerceIn(0f, 1f)) } },
            muteBtn,
            text(a, pal, "+") { CastHub.control?.let { c -> c.setVolume((c.volume() + VOLUME_STEP).coerceIn(0f, 1f)) } },
            icon(a, pal, PlayerIcon.AUDIO) { pickTrack(a, CastHub.TRACK_AUDIO) },
            icon(a, pal, PlayerIcon.CC) { pickTrack(a, CastHub.TRACK_TEXT) },
            icon(a, pal, PlayerIcon.QUEUE) { pickItem(a) }))
        // (7.28) Live TV, surowy .ts przez Google Cast, a ten sam TV jest też odbiornikiem DLNA – jednorazowa podpowiedź z przełączeniem
        val dlnaRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; tag = DLNA_TAG }
        dlnaRow.addView(TextView(a).apply { text = CastHub.DLNA_HINT; setTextColor(pal.text2); textSize = 13f; maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        dlnaRow.addView(text(a, pal, DLNA_SWITCH) { CastHub.dlnaAlternative()?.let { d -> dlnaHintFor = CastHub.session; CastHub.moveTo(d) } })
        dlnaRow.addView(icon(a, pal, PlayerIcon.CLOSE) { dlnaHintFor = CastHub.session; dlnaRow.visibility = View.GONE })
        root.addView(dlnaRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
        tick(root, title, sub, playBtn, bar, time, dlnaRow)
        return root
    }

    private fun seekBy(d: Long) {
        val c = CastHub.control ?: return
        if (CastHub.session?.media?.live == true) return
        val dur = c.durationMs()
        c.seekTo((c.positionMs() + d).coerceAtLeast(0).let { if (dur > 0) it.coerceAtMost(dur) else it })
    }

    private fun toggle() { val c = CastHub.control ?: return; if (c.isPaused()) c.play() else c.pause() }

    private fun pickTrack(a: Activity, type: Int) {
        val c = CastHub.control ?: return
        val t = c.tracks(type)
        if (t.isEmpty()) { I18n.makeToast(a, "Odbiornik nie zgłasza ścieżek", android.widget.Toast.LENGTH_SHORT).show(); return }
        val names = (if (type == CastHub.TRACK_TEXT) listOf(SUBS_OFF) else emptyList()) + t.map { it.name }
        val off = if (type == CastHub.TRACK_TEXT) 1 else 0
        val cur = t.indexOfFirst { it.active }.let { if (it < 0) 0 else it + off }
        Dialogs.choice(a, if (type == CastHub.TRACK_AUDIO) AUDIO else SUBS, names, cur) { i -> c.selectTrack(type, if (i < off) null else t[i - off].id) }
    }

    private fun pickItem(a: Activity) {
        val l = CastHub.sessionLocal ?: return
        val items = l.items()
        if (items.isEmpty()) { I18n.makeToast(a, "Brak listy dla tego materiału", android.widget.Toast.LENGTH_SHORT).show(); return }
        Dialogs.choice(a, l.itemsTitle, items.map { it.label }, items.indexOfFirst { it.current }.coerceAtLeast(0)) { i -> items.getOrNull(i)?.pick?.invoke() }
    }

    private fun tick(root: View, title: TextView, sub: TextView, play: TextView, bar: SeekBar?, time: TextView?, dlnaRow: View? = null) {
        val r = object : Runnable {
            override fun run() {
                val s = CastHub.session
                if (s == null) { (root.parent as? ViewGroup)?.removeView(root); return }
                if (!root.isAttachedToWindow) return
                title.text = s.media.title
                sub.text = "„${s.device.name}”" + (if (s.media.subtitle.isNotBlank()) " • ${s.media.subtitle}" else "")
                val c = CastHub.control
                play.setCompoundDrawablesRelative(PlayerIcon(if (c?.isPaused() == true) PlayerIcon.PLAY else PlayerIcon.PAUSE, play.textColors).apply { setBounds(0, 0, Ui.dp(22), Ui.dp(22)) }, null, null, null)
                // podpowiedź DLNA – raz na sesję (zamknięta albo użyta – nie wraca dla tej sesji)
                dlnaRow?.let { r -> val want = if (dlnaHintFor !== s && CastHub.dlnaAlternative() != null) View.VISIBLE else View.GONE; if (r.visibility != want) r.visibility = want }
                if (bar != null && time != null) {
                    val d = c?.durationMs() ?: 0L; val p = c?.positionMs() ?: 0L
                    val vod = !s.media.live && d > 0
                    bar.visibility = if (vod) View.VISIBLE else View.GONE
                    if (vod && bar.getTag(R.id.ui_dup) != true) bar.progress = (p * 1000 / d).toInt().coerceIn(0, 1000)
                    time.text = if (vod) "${Fmt.duration(p)} / ${Fmt.duration(d)}" else "NA ŻYWO"
                }
                root.postDelayed(this, TICK_MS)
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.removeCallbacks(r); v.post(r) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(r) }
        })
    }

    private fun row(a: Activity, vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        views.forEach { addView(it, LinearLayout.LayoutParams(0, Ui.dp(44), 1f).apply { setMargins(Ui.dp(3), Ui.dp(6), Ui.dp(3), 0) }) }
    }

    private fun base(a: Activity, pal: CardSheet.Palette, onClick: () -> Unit) = TextView(a).apply {
        gravity = Gravity.CENTER; isFocusable = true; isClickable = true; textSize = 14f; minWidth = Ui.dp(44); minHeight = Ui.dp(40)
        setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6))
        background = if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.pillTab(40, DevicePerformanceProfile.uiLow) else CardSheet.cardBg(pal)
        setTextColor(CardSheet.textColors(pal))
        setOnClickListener { onClick() }
    }

    private fun text(a: Activity, pal: CardSheet.Palette, label: String, onClick: () -> Unit) = base(a, pal, onClick).apply { text = label }

    private fun icon(a: Activity, pal: CardSheet.Palette, kind: String, onClick: () -> Unit) = base(a, pal, onClick).apply {
        contentDescription = kind
        setCompoundDrawablesRelative(PlayerIcon(kind, textColors).apply { setBounds(0, 0, Ui.dp(22), Ui.dp(22)) }, null, null, null)
    }
}

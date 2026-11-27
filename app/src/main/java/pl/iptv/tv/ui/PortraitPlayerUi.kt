package pl.iptv.tv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * (7.22) Wspólne sterowanie odtwarzaczy (Live TV, Stremio, STube) na telefonie trzymanym pionowo:
 * - [bar] – jednolity pasek szybkich ikon w strefie kciuka: pole dotyku co najmniej [MIN_TOUCH_DP] × [MIN_TOUCH_DP] dp,
 *   ikona + krótki podpis pod nią (jedna linia, bez ucinania ikon przy 360 dp szerokości – pozycje równej szerokości),
 *   pod obrazem (bez nakładania się na wideo), nad systemowym paskiem nawigacji;
 * - [PortraitSheet] – dolny arkusz (odpowiednik ModalBottomSheet na widokach – projekt nie ma Material): uchwyt u góry
 *   (przeciągnięcie w dół zamyka), górne rogi [SHEET_RADIUS_DP] dp, tło panelu aktywnego motywu, odstęp na pasek nawigacji
 *   i klawiaturę (navigationBars / ime), przewijana treść do 80 % wysokości ekranu.
 *
 * Ścisła izolacja: używane WYŁĄCZNIE, gdy [active] ([DeviceDisplayProfile.isPortraitPhone]); TV i poziom – dawne panele OSD,
 * okna i skróty pilota bez zmian. Kolory wyłącznie z tokenów motywu ([CardSheet.palette]).
 */
object PortraitPlayerUi {
    const val MIN_TOUCH_DP = 48
    const val ICON_DP = 24
    const val SHEET_RADIUS_DP = 24
    const val BAR_TAG = "portrait_player_bar"
    const val ICON_TAG = "pp_icon:"
    const val SHEET_TAG = "portrait_sheet"
    /** (v2) Odsunięcie kapsuły paska od bocznych krawędzi ekranu (dp). */
    const val BAR_INSET_DP = 12
    /** Najwyższa część ekranu zajmowana przez arkusz. */
    const val SHEET_MAX_FRACTION = 0.8f

    data class Icon(val key: String, val icon: String, val label: String, val onClick: () -> Unit)

    fun active(a: android.content.Context) = MobilePortraitScaffold.active(a)

    /** Pasek ikon: [icons] równej szerokości, każda co najmniej 48 × 48 dp. */
    fun bar(a: Activity, icons: List<Icon>): LinearLayout {
        val pal = CardSheet.palette()
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; tag = BAR_TAG; isClickable = true
            setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
            background = if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.glassOverlay(0) else GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.panel, 0xF0)); setStroke(Ui.dp(1), pal.specular) }   // (v2) szkło 72 %
        }
        val v2 = ModernV2.active
        val G = pl.iptv.tv.ui.theme.ObsidianGlass
        // (v2) pływająca szklana kapsuła (24 dp, odsunięta od krawędzi) – jak dolny pasek nawigacji telefonu w motywie v2
        if (v2) { bar.background = LiveV2.NoPadInset(G.glassDialog(24, G.OVERLAY_FILL), Ui.dp(BAR_INSET_DP), Ui.dp(6), Ui.dp(BAR_INSET_DP), Ui.dp(6))
            bar.setPadding(Ui.dp(BAR_INSET_DP + 6), Ui.dp(12), Ui.dp(BAR_INSET_DP + 6), Ui.dp(12)) }
        val tint = if (v2) android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(android.graphics.Color.WHITE, G.FOCUS_RING, G.TEXT2))
        else android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(pal.focusText, pal.accent or 0xFF000000.toInt(), pal.text))
        icons.forEach { ic ->
            val item = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                tag = ICON_TAG + ic.key; contentDescription = ic.label
                minimumWidth = Ui.dp(MIN_TOUCH_DP); minimumHeight = Ui.dp(MIN_TOUCH_DP)
                setPadding(Ui.dp(2), Ui.dp(4), Ui.dp(2), Ui.dp(4))
                background = if (v2) android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), G.tintRing(16, true))
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(G.withAlpha(G.FOCUS, 0x38)); cornerRadius = Ui.dp(16).toFloat() })
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
                } else android.graphics.drawable.StateListDrawable().apply {
                    fun r(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = Ui.dp(14).toFloat() }
                    addState(intArrayOf(android.R.attr.state_focused), r(pal.focusFill))
                    addState(intArrayOf(android.R.attr.state_pressed), r(pal.chosenFill))
                    addState(intArrayOf(), r(0))
                }
                setOnClickListener { ic.onClick() }
            }
            item.addView(ImageView(a).apply { setImageDrawable(PlayerIcon(ic.icon, tint)); isDuplicateParentStateEnabled = true }, LinearLayout.LayoutParams(Ui.dp(ICON_DP), Ui.dp(ICON_DP)))
            item.addView(TextView(a).apply {
                text = ic.label; textSize = 10f; tag = "keepcolor"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setTextColor(tint); isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(2) })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(1); marginEnd = Ui.dp(1) })
        }
        return bar
    }

    /** (7.25) Ikona „cast” paska – widoczna tylko przy urządzeniach w pobliżu ([CastHub.visible]). */
    fun bindCast(bar: android.view.View?) { bar?.findViewWithTag<android.view.View>(ICON_TAG + "cast")?.let { CastHub.bind(it) } }

    /** Pasek przy dolnej krawędzi [root] z odstępem systemowego paska nawigacji; [onHeight] – wysokość po pomiarze. */
    fun attachBar(a: Activity, root: FrameLayout, icons: List<Icon>, onHeight: (Int) -> Unit = {}): LinearLayout {
        val b = bar(a, icons)
        root.addView(b, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b) { v, ins ->
            val n = runCatching { ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom }.getOrDefault(0)
            if (ModernV2.active) {      // (v2) kapsuła nad paskiem nawigacji systemu
                val G = pl.iptv.tv.ui.theme.ObsidianGlass
                v.background = LiveV2.NoPadInset(G.glassDialog(24, G.OVERLAY_FILL), Ui.dp(BAR_INSET_DP), Ui.dp(6), Ui.dp(BAR_INSET_DP), Ui.dp(6) + n)
                v.setPadding(v.paddingLeft, Ui.dp(12), v.paddingRight, Ui.dp(12) + n)
            } else v.setPadding(v.paddingLeft, Ui.dp(4), v.paddingRight, Ui.dp(4) + n)
            ins
        }
        b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> onHeight(v.height) }
        runCatching { androidx.core.view.ViewCompat.requestApplyInsets(b) }
        return b
    }

    /**
     * „Obróć do pełnego ekranu”: odtwarzacz poziomo (czujnik), WSTECZ – powrót do pionu (poprzednia orientacja), bez zamykania
     * odtwarzacza i bez ponownego startu strumienia (ekrany mają `configChanges`).
     */
    fun rotateToLandscape(a: androidx.activity.ComponentActivity) {
        if (OrientationPolicy.isForced(a)) return
        // (7.23) przez [OrientationPolicy]: po WSTECZ albo wyjściu z ekranu – z powrotem orientacja z Ustawień (nie „zapamiętany” poziom)
        OrientationPolicy.forceLandscape(a)
        a.onBackPressedDispatcher.addCallback(a, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { isEnabled = false; remove(); if (OrientationPolicy.isForced(a)) OrientationPolicy.release(a) else a.onBackPressedDispatcher.onBackPressed() }
        })
    }

    /** Diagnostyka obrazu: kodek, rozdzielczość, klatki (format bieżącej ścieżki obrazu odtwarzacza). */
    fun diagnostics(p: androidx.media3.common.Player?): String {
        val f = (p as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat
        if (f == null) return "Brak informacji o obrazie (jeszcze się nie wczytał)"
        return listOfNotNull(
            "Kodek: " + (f.codecs?.ifBlank { null } ?: f.sampleMimeType?.substringAfter('/') ?: "?"),
            if (f.width > 0 && f.height > 0) "Rozdzielczość: ${f.width}×${f.height}" else null,
            if (f.frameRate > 0) "Klatki: " + String.format(java.util.Locale.US, "%.2f", f.frameRate).trimEnd('0').trimEnd('.') + " kl/s" else null,
            if (f.bitrate > 0) "Przepływność: ${f.bitrate / 1000} kb/s" else null,
        ).joinToString("\n")
    }

    // ---------------- wspólne arkusze ----------------

    /** Proporcje obrazu w arkuszu (każdy odtwarzacz mapuje na swoje ustawienie). */
    enum class Aspect(val label: String) { FIT("Dopasuj"), FILL("Wypełnij"), STRETCH("Rozciągnij"), R16_9("16:9"), R4_3("4:3") }
    val SPEEDS = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) "${s.toInt()}.0" else s.toString()).replace(".", ",") + "x"

    /**
     * Arkusz Silnika i Jakości Wideo (wspólne ustawienia silnika – [EngineConfigRepository], zmiana działa od razu):
     * silnik (Media3 HW / SW / Tunelowanie), maks. rozdzielczość, powierzchnia, korekta zacinania, wyrównywanie głośności,
     * [extra] (np. jakość STube / Stremio) i diagnostyka ([diagnostics]).
     */
    fun engineSheet(a: Activity, diagnostics: () -> String, extra: (PortraitSheet.() -> Unit)? = null): PortraitSheet {
        val repo = EngineConfigRepository
        val s = PortraitSheet(a, "Silnik i jakość obrazu")
        fun build() {
            val c = repo.current
            s.clear()
            extra?.invoke(s)
            s.section("Silnik odtwarzania")
            s.choices(ENGINE_LABELS, c.engine.coerceIn(0, 2)) { repo.setEngine(it); build() }
            s.section(SettingsActivity.MAX_VIDEO_TITLE)
            s.tiles(VideoResolutionLimit.entries.map { it.label }, c.maxVideoResolution.ordinal) { repo.setMaxVideoResolution(VideoResolutionLimit.entries[it]); build() }
            s.section("Powierzchnia obrazu")
            s.tiles(listOf("Automatyczny", "SurfaceView", "TextureView"), c.surface.ordinal) { repo.setSurfaceMode(RenderSurfaceMode.entries[it]); build() }
            s.toggle(SettingsActivity.AUTO_STUTTER_TITLE, c.autoStutterCorrectionEnabled) { repo.setAutoStutterCorrection(it) }
            s.toggle("Wyrównywanie głośności kanałów", c.loudness) { repo.setLoudness(it) }
            s.section("Diagnostyka")
            s.info(diagnostics())
        }
        build(); s.show(); return s
    }
    val ENGINE_LABELS = listOf("Media3 HW (sprzętowy)", "Media3 SW (programowy)", "Tunelowanie (Android TV)")

    /** Wybór ścieżki [type] w odtwarzaczu Media3 (null – wyłączenie). */
    fun selectTrack(p: androidx.media3.common.Player, type: Int, g: androidx.media3.common.Tracks.Group?) {
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (g == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0))
        p.trackSelectionParameters = b.build()
    }

    fun trackName(f: androidx.media3.common.Format, i: Int): String = listOfNotNull(
        f.label?.ifBlank { null } ?: f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale("pl")) } ?: "Ścieżka ${i + 1}",
        f.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "stereo" else "$it kan." },
    ).joinToString(" · ")

    /** Pozycja dodatkowa (np. napisy zewnętrzne Stremio). */
    data class Choice(val label: String, val selected: Boolean, val run: () -> Unit)
    /** Suwak opóźnienia napisów (ms; zakres ±5 s). */
    class Delay(val getMs: () -> Int, val setMs: (Int) -> Unit)
    const val DELAY_RANGE_MS = 5_000

    /**
     * Arkusz Ścieżek Audio i Napisów: karty ścieżek dźwięku, napisy (wyłączone / wbudowane / [external]) i suwak
     * synchronizacji napisów −5,0 s … +5,0 s ([delay]; tylko gdy odtwarzacz ją obsługuje).
     */
    fun tracksSheet(a: Activity, p: androidx.media3.common.Player?, external: () -> List<Choice> = { emptyList() }, subsOff: () -> Boolean = { false },
                    onSubsOff: (() -> Unit)? = null, delay: Delay? = null): PortraitSheet {
        val s = PortraitSheet(a, "Ścieżka audio i napisy")
        fun build() {
            s.clear()
            val tracks = p?.currentTracks?.groups.orEmpty()
            val audio = tracks.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            s.section("Ścieżka dźwięku")
            if (audio.isEmpty()) s.info("Brak informacji o ścieżkach dźwięku")
            else s.choices(audio.mapIndexed { i, g -> trackName(g.mediaTrackGroup.getFormat(0), i) }, audio.indexOfFirst { it.isSelected }) { i -> p?.let { selectTrack(it, androidx.media3.common.C.TRACK_TYPE_AUDIO, audio[i]) }; build() }
            val text = tracks.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            val ext = external()
            s.section("Napisy")
            val off = subsOff() || (ext.none { it.selected } && text.none { it.isSelected })
            val labels = listOf("Wyłączone") + text.mapIndexed { i, g -> "Wbudowane: " + trackName(g.mediaTrackGroup.getFormat(0), i) } + ext.map { it.label }
            val sel = if (off) 0 else text.indexOfFirst { it.isSelected }.takeIf { it >= 0 && ext.none { e -> e.selected } }?.plus(1) ?: ext.indexOfFirst { it.selected }.takeIf { it >= 0 }?.plus(1 + text.size) ?: 0
            s.choices(labels, sel) { i ->
                when {
                    i == 0 -> { onSubsOff?.invoke() ?: p?.let { selectTrack(it, androidx.media3.common.C.TRACK_TYPE_TEXT, null) } }
                    i <= text.size -> p?.let { selectTrack(it, androidx.media3.common.C.TRACK_TYPE_TEXT, text[i - 1]) }
                    else -> ext[i - 1 - text.size].run()
                }
                s.root.postDelayed({ if (s.isShowing) build() }, 300)
            }
            if (delay != null) {
                s.section("Synchronizacja napisów")
                s.slider(-DELAY_RANGE_MS, DELAY_RANGE_MS, 100, delay.getMs().coerceIn(-DELAY_RANGE_MS, DELAY_RANGE_MS),
                    { v -> String.format(java.util.Locale.US, "%+.1f s", v / 1000f).replace('.', ',') }) { delay.setMs(it) }
            }
        }
        build(); s.show(); return s
    }

    /** Arkusz Proporcji Obrazu i Prędkości: kafelki proporcji i (gdy [speed] != null) prędkości odtwarzania. */
    fun aspectSpeedSheet(a: Activity, aspect: Aspect, setAspect: (Aspect) -> Unit, speed: Float? = null, setSpeed: ((Float) -> Unit)? = null,
                         extra: (PortraitSheet.() -> Unit)? = null): PortraitSheet {
        val s = PortraitSheet(a, if (speed != null) "Proporcje i prędkość" else "Proporcje obrazu")
        var cur = aspect; var sp = speed
        fun build() {
            s.clear()
            s.section("Proporcje obrazu")
            s.tiles(Aspect.entries.map { it.label }, cur.ordinal) { cur = Aspect.entries[it]; setAspect(cur); build() }
            if (sp != null && setSpeed != null) {
                s.section("Prędkość odtwarzania")
                s.tiles(SPEEDS.map { speedLabel(it) }, SPEEDS.indexOfFirst { kotlin.math.abs(it - sp!!) < 0.01f }) { sp = SPEEDS[it]; setSpeed(SPEEDS[it]); build() }
            }
            extra?.invoke(s)
        }
        build(); s.show(); return s
    }

    /** Pozycja listy (kanał, odcinek, źródło); [current] – wyraźnie oznaczona („▶ teraz”), lista przewinięta do niej. */
    data class Entry(val title: String, val subtitle: String = "", val current: Boolean = false, val thumb: String = "", val run: () -> Unit)

    /** Arkusz listy: przewijana lista, bieżąca pozycja oznaczona, wybór zamyka arkusz. */
    fun listSheet(a: Activity, title: String, entries: List<Entry>, empty: String = "Brak pozycji"): PortraitSheet {
        val s = PortraitSheet(a, title, list = true)
        s.list(entries, empty)
        s.show(); return s
    }
}

/**
 * (7.22) Dolny arkusz akcji (telefon pionowo) – odpowiednik ModalBottomSheet: uchwyt (przeciągnięcie w dół zamyka), górne
 * rogi 24 dp, tło panelu motywu, treść przewijana (lista – RecyclerView o wysokości do 80 % ekranu), odstępy navigationBars / ime.
 * Elementy: [section], [choices] (karty z zaznaczeniem), [tiles] (szybkie kafelki), [toggle], [slider], [info], [list], [action].
 */
@SuppressLint("ClickableViewAccessibility")
class PortraitSheet(private val a: Activity, val title: String, private val list: Boolean = false) {
    private val pal = CardSheet.palette()
    private val dialog = Dialog(a)
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; tag = PortraitPlayerUi.SHEET_TAG
        val r = Ui.dp(PortraitPlayerUi.SHEET_RADIUS_DP).toFloat()
        background = if (ModernV2.active) pl.iptv.tv.ui.theme.ObsidianGlass.glassDialog()      // (v2) V2GlassDialog (dolne rogi poza ekranem)
            else GradientDrawable().apply { setColor(pal.panel or 0xFF000000.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setStroke(Ui.dp(1), pal.specular) }
        setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(12))
        isClickable = true
    }
    private val handle = View(a).apply { tag = "sheet_handle"; background = GradientDrawable().apply { setColor(CardSheet.withAlpha(pal.text2, 0x80)); cornerRadius = Ui.dp(2).toFloat() } }
    private val maxH = (a.resources.displayMetrics.heightPixels * PortraitPlayerUi.SHEET_MAX_FRACTION).toInt()
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, 0, 0, Ui.dp(8)) }
    private val scroll = object : ScrollView(a) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH - Ui.dp(72), View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(body) }
    /** Lista arkusza ([list]) – testy. */
    var recycler: RecyclerView? = null; private set
    val isShowing get() = dialog.isShowing

    init {
        root.addView(handle, LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(10) })
        root.addView(TextView(a).apply { text = title; textSize = 18f; tag = "keepcolor"; setTextColor(pal.text); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(4), 0, 0, Ui.dp(8)) })
        if (!list) root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        // przeciągnięcie uchwytu / nagłówka w dół – zamknięcie (powyżej 25 % wysokości arkusza), inaczej powrót
        var downY = 0f
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { root.translationY = (e.rawY - downY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (root.translationY > root.height * 0.25f) dismiss() else root.animate().translationY(0f).setDuration(150).start(); true
                }
                else -> false
            }
        }
        handle.setOnTouchListener(drag); root.getChildAt(1).setOnTouchListener(drag)
        // pasek nawigacji i klawiatura – odstęp wewnątrz arkusza
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->

            val b = runCatching { maxOf(ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom, ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom) }.getOrDefault(0)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, Ui.dp(12) + b); ins
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
    }

    fun clear() { body.removeAllViews() }

    fun section(t: String) {
        body.addView(TextView(a).apply { text = t; textSize = 13f; tag = "keepcolor"; setTextColor(pal.accent or 0xFF000000.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(Ui.dp(4), Ui.dp(12), 0, Ui.dp(6)) })
    }

    private fun card(label: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; isSelected = selected
        tag = "sheet_choice"; contentDescription = label + if (selected) ": wybrane" else ""
        minimumHeight = Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP)
        background = CardSheet.cardBg(pal); setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10))
        addView(TextView(a).apply { text = label; textSize = 15f; tag = "keepcolor"; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) addView(TextView(a).apply { text = "✓"; textSize = 16f; tag = "keepcolor"; setTextColor(pal.accent or 0xFF000000.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        setOnClickListener { onClick() }
    }

    /** Karty wyboru (jedna zaznaczona). */
    fun choices(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        options.forEachIndexed { i, o -> body.addView(card(o, i == selected) { onPick(i) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(6) }) }
    }

    /** Szybkie kafelki (po 3 w rzędzie, minimum 48 dp wysokości). */
    fun tiles(options: List<String>, selected: Int, perRow: Int = 3, onPick: (Int) -> Unit) {
        options.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { k, o ->
                val i = r * perRow + k
                row.addView(MobilePortraitScaffold.chip(a, o, i == selected) { onPick(i) }.apply { tag = "keepcolor nozoom sheet_tile"; minHeight = Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (k < perRow - 1) Ui.dp(8) else 0 })
            }
            repeat(perRow - chunk.size) { row.addView(View(a), LinearLayout.LayoutParams(0, 0, 1f)) }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(8) })
        }
    }

    fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        var state = on
        val sw = ImageView(a).apply { setImageDrawable(CardSheet.Switch(state, pal)); isDuplicateParentStateEnabled = true }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; tag = "sheet_toggle"
            contentDescription = label + if (state) ": włączone" else ": wyłączone"
            minimumHeight = Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP); background = CardSheet.cardBg(pal); setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
        }
        row.addView(TextView(a).apply { text = label; textSize = 15f; tag = "keepcolor"; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(10) })
        row.setOnClickListener {
            state = !state; (sw.drawable as CardSheet.Switch).on = state; sw.invalidate(); sw.drawable.invalidateSelf()
            row.contentDescription = label + if (state) ": włączone" else ": wyłączone"
            onChange(state)
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
    }

    /** Suwak [min]…[max] co [step]; [format] – podpis wartości. */
    fun slider(min: Int, max: Int, step: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit): SeekBar {
        val label = TextView(a).apply { text = format(value); textSize = 15f; tag = "keepcolor sheet_slider_value"; setTextColor(pal.text); gravity = Gravity.CENTER }
        val bar = SeekBar(a).apply {
            this.max = (max - min) / step; progress = (value - min) / step; tag = "sheet_slider"; minimumHeight = Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP)
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent or 0xFF000000.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(pal.accent or 0xFF000000.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(CardSheet.withAlpha(pal.text, 0x40))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { val v = min + p * step; label.text = format(v); if (fromUser) onChange(v) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        body.addView(label, LinearLayout.LayoutParams(-1, -2))
        body.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP)))
        return bar
    }

    fun info(text: String) {
        body.addView(TextView(a).apply { this.text = text; textSize = 13f; tag = "keepcolor sheet_info"; setTextColor(pal.text2); setPadding(Ui.dp(4), 0, Ui.dp(4), 0); setLineSpacing(0f, 1.2f) })
    }

    /** Przycisk akcji (karta bez zaznaczenia); [close] – zamyka arkusz. */
    fun action(label: String, close: Boolean = true, run: () -> Unit) {
        body.addView(card(label, false) { if (close) dismiss(); run() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(6) })
    }

    /** Lista pozycji (RecyclerView do 80 % wysokości): bieżąca oznaczona i widoczna, wybór zamyka arkusz. */
    fun list(entries: List<PortraitPlayerUi.Entry>, empty: String) {
        if (entries.isEmpty()) { root.addView(scroll, LinearLayout.LayoutParams(-1, -2)); info(empty); return }
        val rv = object : RecyclerView(a) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH - Ui.dp(72), View.MeasureSpec.AT_MOST))
        }.apply {
            layoutManager = LinearLayoutManager(a); itemAnimator = null; clipToPadding = false; tag = "sheet_list"
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; minimumHeight = Ui.dp(PortraitPlayerUi.MIN_TOUCH_DP)
                        background = CardSheet.cardBg(pal); setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
                        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(6) }
                    }
                    row.addView(TextView(a).apply { textSize = 15f; tag = "keepcolor"; setTextColor(CardSheet.textColors(pal)); isDuplicateParentStateEnabled = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(TextView(a).apply { textSize = 12f; tag = "keepcolor"; setTextColor(CardSheet.textColors(pal, secondary = true)); isDuplicateParentStateEnabled = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                    val e = entries[position]; val row = h.itemView as LinearLayout
                    row.isSelected = e.current; row.tag = if (e.current) "sheet_current" else "sheet_entry"
                    (row.getChildAt(0) as TextView).apply { text = e.title; setTypeface(null, if (e.current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
                    (row.getChildAt(1) as TextView).apply { text = listOfNotNull(if (e.current) "▶ teraz" else null, e.subtitle.ifBlank { null }).joinToString(" · "); visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE }
                    row.contentDescription = e.title + if (e.current) ": teraz" else ""
                    row.setOnClickListener { dismiss(); e.run() }
                }
            }
        }
        recycler = rv
        root.addView(rv, LinearLayout.LayoutParams(-1, -2))
        entries.indexOfFirst { it.current }.takeIf { it > 0 }?.let { (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(it, Ui.dp(48)) }
    }

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // pełna szerokość, rysowanie pod paskiem nawigacji (odstęp – wstawki w arkuszu)
            runCatching { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false) }
            decorView.setPadding(0, 0, 0, 0)
        }
        root.translationY = Ui.dp(400).toFloat()
        root.animate().translationY(0f).setDuration(if (runCatching { pl.iptv.tv.data.Prefs.lowPerf }.getOrDefault(false)) 0 else 200).start()
    }

    fun dismiss() { runCatching { dialog.dismiss() } }
    fun setOnDismiss(r: () -> Unit) = dialog.setOnDismissListener { r() }
}

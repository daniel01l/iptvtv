package pl.iptv.tv.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * (7.20) Wspólne elementy okien z kartami (okna wyboru Ustawień [Dialogs.choiceCards], okno silnika w odtwarzaczu
 * [EngineSheet], lista „Zapamiętanych silników”): panel 20 dp z krawędzią specular, karty 14 dp bez linii oddzielających,
 * przełącznik, pigułki i plakietki źródła. Kolory zawsze z tokenów aktywnego motywu ([palette]): „Obsidian Stream”
 * (motyw Nowoczesny) – panel `#0F141F` 96 %, akcent `AccentPrimary`; pozostałe motywy – panel, tekst, akcent i zaznaczenie
 * z [Ui] (Klasyczny, Xbox, X25, PS5 – każdy w swoich kolorach).
 */
object CardSheet {
    data class Palette(
        val panel: Int, val text: Int, val text2: Int, val accent: Int, val focusFill: Int, val focusText: Int,
        val rest: Int, val specular: Int, val focusRing: Int,
    ) {
        /** Wybrana opcja (bez fokusu): akcent 18 %. */
        val chosenFill: Int get() = withAlpha(accent, 0x2E)
    }

    /** Panel „Obsidian Stream”: `#0F141F`, krycie 96 %. */
    const val OBSIDIAN_PANEL = 0xF50F141F.toInt()
    const val PANEL_ALPHA = 0xF5
    const val PANEL_RADIUS_DP = 20
    const val CARD_RADIUS_DP = 14

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** Odstęp listy kart na powiększenie i obwódkę fokusu (nic nie jest ucinane). */
    const val ROOM_X_DP = 10
    const val ROOM_Y_DP = 6
    /** Największe powiększenie karty przy fokusie – przyrost na stronę ≤ [ROOM_X_DP] / [ROOM_Y_DP] przy kartach do ~560 dp. */
    const val FOCUS_SCALE = 1.03f

    /**
     * Powiększenie przy fokusie (ta sama krzywa i czas co w całej aplikacji – [Ui.focusZoom], skala na warstwie widoku,
     * bez przebudowy układu); skala przycięta tak, by karta nie wyszła poza odstęp [ROOM_X_DP] / [ROOM_Y_DP].
     */
    fun focusScale(v: android.view.View) {
        v.setOnFocusChangeListener { x, has ->
            val w = maxOf(x.width, 1); val h = maxOf(x.height, 1)
            val s = minOf(FOCUS_SCALE, 1f + 2f * Ui.dp(ROOM_X_DP) / w, 1f + 2f * Ui.dp(ROOM_Y_DP) / h)
            Ui.focusZoom(x, has, s)
        }
    }

    /** Kolory okna z aktywnego motywu (wywoływane przy każdym otwarciu – zmiana motywu działa od razu). */
    fun palette(): Palette {
        val O = pl.iptv.tv.ui.theme.ObsidianViews
        return if (O.active) Palette(OBSIDIAN_PANEL, O.TEXT, O.TEXT2, O.ACCENT, O.ACCENT, android.graphics.Color.WHITE,
            0x0FFFFFFF, O.SPECULAR, 0x66FFFFFF)
        else Palette(withAlpha(Ui.panel, PANEL_ALPHA), Ui.text, Ui.text2, Ui.accent, Ui.focus, Ui.focusText,
            withAlpha(Ui.text, 0x0F), withAlpha(Ui.text, 0x29), withAlpha(Ui.focusText, 0x66))
    }

    fun panelBg(p: Palette): Drawable = GradientDrawable().apply {
        setColor(p.panel); cornerRadius = Ui.dp(PANEL_RADIUS_DP).toFloat(); setStroke(Ui.dp(1), p.specular)
    }

    private fun round(fill: Int, radiusDp: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = Ui.dp(radiusDp).toFloat(); if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Tło karty: fokus – wypełnienie zaznaczenia motywu + obwódka; wybrana – akcent 18 % z obwódką; spoczynek – 6 %. */
    fun cardBg(p: Palette): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(p.focusFill, CARD_RADIUS_DP, Ui.dp(2), p.focusRing))
        addState(intArrayOf(android.R.attr.state_pressed), round(p.focusFill, CARD_RADIUS_DP))
        addState(intArrayOf(android.R.attr.state_activated), round(p.chosenFill, CARD_RADIUS_DP, maxOf(2, Ui.dp(2) * 3 / 4), p.accent))
        addState(intArrayOf(), round(p.rest, CARD_RADIUS_DP))
    }

    /** Kolor tekstu w karcie: przy fokusie – tekst zaznaczenia motywu. */
    fun textColors(p: Palette, secondary: Boolean = false): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(if (secondary) withAlpha(p.focusText, 0xE6) else p.focusText, p.focusText, if (secondary) p.text2 else p.text))

    /** Pigułka-przycisk (Gotowe, Zastosuj globalnie…): fokus – wypełnienie zaznaczenia, spoczynek – 12 % tekstu. */
    fun pill(a: android.content.Context, label: String, p: Palette, onClick: () -> Unit) = android.widget.TextView(a).apply {
        text = label; textSize = 15f; gravity = android.view.Gravity.CENTER; isFocusable = true; isClickable = true
        id = android.view.View.generateViewId(); contentDescription = label
        setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(textColors(p))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(p.focusFill, 40, Ui.dp(2), p.focusRing))
            addState(intArrayOf(android.R.attr.state_pressed), round(p.focusFill, 40))
            addState(intArrayOf(), round(withAlpha(p.text, 0x1F), 40))
        }
        setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10))
        setOnClickListener { onClick() }
    }

    /** Plakietka źródła wartości: „Globalne” (szara) / „Dla tego kanału” (akcent). */
    fun sourceChip(a: android.content.Context, source: ConfigSource, p: Palette) = android.widget.TextView(a).apply {
        text = source.label; textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        tag = "source:${source.name}"
        val channel = source == ConfigSource.CHANNEL
        setTextColor(if (channel) p.accent else p.text2)
        background = round(if (channel) withAlpha(p.accent, 0x2E) else withAlpha(p.text, 0x14), 40, if (channel) Ui.dp(1) else 0, p.accent)
        setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2))
    }

    /** Przełącznik w kolorach motywu (włączony – tor w akcencie, gałka po prawej). */
    class Switch(var on: Boolean, private val p: Palette) : Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        override fun getIntrinsicWidth() = Ui.dp(44)
        override fun getIntrinsicHeight() = Ui.dp(24)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val h = b.height().toFloat(); val rad = h / 2
            r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            paint.color = if (on) p.accent else withAlpha(p.text2, 0x33); c.drawRoundRect(r, rad, rad, paint)
            paint.color = if (on) android.graphics.Color.WHITE else withAlpha(p.text, 0xCC)
            c.drawCircle(if (on) b.right - rad else b.left + rad, b.top + rad, rad - Ui.dp(3), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Pozycja menu kart: etykieta, opcjonalny opis i ikona ([PlayerIcon]); akcja zamyka okno. */
    data class Action(val label: String, val icon: String? = null, val description: String = "", val key: String = label, val run: () -> Unit)

    /** Otwarte menu kart (testy). */
    @Volatile var shownActions: android.app.Dialog? = null; private set

    /**
     * (7.20) Menu kontekstowe z kartami (np. przytrzymanie OK na plakacie Stremio): ten sam panel, karty, fokus i kolory motywu
     * co okna wyboru Ustawień; fokus na pierwszej pozycji, WSTECZ zamyka. Karty mają tag `action:<key>`.
     */
    fun actions(a: android.app.Activity, title: String, items: List<Action>, subtitle: String = ""): android.app.Dialog {
        val p = palette()
        val d = android.app.Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL; background = panelBg(p); setPadding(Ui.dp(22), Ui.dp(20), Ui.dp(22), Ui.dp(18))
        }
        root.addView(android.widget.TextView(a).apply { text = title; textSize = 20f; setTextColor(p.text); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(Ui.dp(4), 0, 0, Ui.dp(if (subtitle.isBlank()) 12 else 2)) })
        if (subtitle.isNotBlank()) root.addView(android.widget.TextView(a).apply { text = subtitle; textSize = 13f; setTextColor(p.text2); setPadding(Ui.dp(4), 0, 0, Ui.dp(10)) })
        val list = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL; clipToPadding = false; clipChildren = false
            setPadding(Ui.dp(ROOM_X_DP), Ui.dp(ROOM_Y_DP), Ui.dp(ROOM_X_DP), Ui.dp(ROOM_Y_DP))
        }
        val cards = items.mapIndexed { i, it ->
            android.widget.LinearLayout(a).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; id = android.view.View.generateViewId(); tag = "action:${it.key}"; contentDescription = it.label
                background = cardBg(p); setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12))
                it.icon?.let { ic -> addView(android.widget.ImageView(a).apply { setImageDrawable(PlayerIcon(ic, textColors(p))); isDuplicateParentStateEnabled = true },
                    android.widget.LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { marginEnd = Ui.dp(14) }) }
                val col = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
                col.addView(android.widget.TextView(a).apply { text = it.label; textSize = 16f; setTextColor(textColors(p)); isDuplicateParentStateEnabled = true; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                if (it.description.isNotBlank()) col.addView(android.widget.TextView(a).apply { text = it.description; textSize = 13f; setTextColor(textColors(p, secondary = true)); isDuplicateParentStateEnabled = true })
                addView(col, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                val action = it.run
                setOnClickListener { d.dismiss(); action() }
                focusScale(this)
            }.also { c -> list.addView(c, android.widget.LinearLayout.LayoutParams(-1, -2).apply { if (i < items.size - 1) bottomMargin = Ui.dp(8) }) }
        }
        cards.firstOrNull()?.let { it.nextFocusUpId = it.id }
        cards.lastOrNull()?.let { it.nextFocusDownId = it.id }
        root.addView(android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }, android.widget.LinearLayout.LayoutParams(-1, -2, 1f))
        d.setContentView(root)
        window(d, a, maxWidthDp = 480)
        d.setOnShowListener { I18n.attach(d.window); cards.firstOrNull()?.requestFocus() }
        d.setOnDismissListener { if (shownActions === d) shownActions = null }
        shownActions = d
        d.show()
        return d
    }

    /** Okno: przezroczyste tło, przyciemnienie [Dialogs.CHOICE_DIM], szerokość do [maxWidthDp] (90 % ekranu). */
    fun window(d: android.app.Dialog, a: android.app.Activity, maxWidthDp: Int = 560) {
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(Dialogs.CHOICE_DIM)
            val dm = a.resources.displayMetrics
            w.setLayout(minOf(Ui.dp(maxWidthDp), (dm.widthPixels * 0.9f).toInt()), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

package pl.iptv.tv.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import pl.iptv.tv.data.Prefs

/**
 * (7.8) Język interfejsu: polski (źródłowy, w kodzie) albo angielski – tłumaczenie na warstwie wyświetlania.
 *
 * Napisy aplikacji zostają w kodzie po polsku (logika – rozpoznawanie lektora, klucze, wyszukiwanie – bez zmian); słownik
 * `assets/i18n/en.json` (polski → angielski, szablony z „{}” w miejscu wstawek) jest stosowany do tego, co widzi użytkownik:
 * teksty i podpowiedzi widoków (przed narysowaniem klatki – [attach]), okna ([Ui.dialog]), komunikaty ([makeToast]) i teksty
 * Compose ([BasicText] w tym pakiecie). Pola wpisywania (EditText) nie są tłumaczone. Przy języku polskim – bez żadnego narzutu.
 */
object I18n {
    const val PL = "pl"
    const val EN = "en"
    /** Języki do wyboru (kod → nazwa we własnym języku). */
    val LANGS = listOf(PL to "Polski", EN to "English")

    @Volatile var lang: String = PL; private set
    val active: Boolean get() = lang != PL

    private var exact: Map<String, String> = emptyMap()
    /** Napisy pisane w kodzie wielkimi literami (np. nazwy sekcji „NA ŻYWO”) – klucz i tłumaczenie wielkimi literami. */
    private var upper: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private var phrases: List<Pair<Regex, String>> = emptyList()
    /** Znaczki / symbole na początku i końcu napisu (bez liter i cyfr) – wokół tłumaczonego środka. */
    private val SYMBOLS = Regex("^([^\\p{L}\\p{N}(\"„]*)(.*?)([^\\p{L}\\p{N})\"”…]*)$", RegexOption.DOT_MATCHES_ALL)
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 4000
    }

    /** Start aplikacji: język z ustawień, słownik wczytany tylko dla języka innego niż polski. */
    fun init(c: Context) {
        lang = runCatching { Prefs.language }.getOrDefault(PL).takeIf { l -> LANGS.any { it.first == l } } ?: PL
        if (active) load(c)
    }

    /** Zmiana języka (Ustawienia, kreator): zapis, słownik, ekrany przebudowywane jak po zmianie motywu. */
    fun setLanguage(c: Context, l: String) {
        if (l == lang) return
        Prefs.language = l
        lang = l
        synchronized(cache) { cache.clear() }
        if (active) load(c)
        Ui.languageChanged()
    }

    fun load(c: Context) {
        val json = runCatching { c.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        loadJson(JSONObject(json))
    }

    /** Słownik z JSON (testy: bez zasobów). */
    fun loadJson(o: JSONObject) {
        val ex = HashMap<String, String>(o.length() * 2)
        val pat = ArrayList<Pair<Regex, String>>()
        val phr = ArrayList<Pair<Regex, String>>()
        for (k in o.keys()) {
            val v = o.optString(k)
            if (v.isEmpty() || v == k) continue
            if ("{}" in k) {
                val outside = k.replace("{}", "").count { it.isLetter() }
                if (outside >= 3 && k.split("{}").size - 1 == v.split("{}").size - 1) {
                    val rx = k.split("{}").joinToString("(.+?)") { Regex.escape(it) }
                    pat += Regex("^$rx$", RegexOption.DOT_MATCHES_ALL) to v
                }
            } else {
                ex[k] = v
                // fragmenty dłuższych napisów składanych w kodzie („Silnik: ” + nazwa) – tylko wielowyrazowe, całe wyrazy
                if (' ' in k.trim() && k.trim().length >= 8) phr += Regex("(?<![\\p{L}])" + Regex.escape(k.trim()) + "(?![\\p{L}])") to v.trim()
            }
        }
        exact = ex
        upper = ex.entries.associate { (k, v) -> k.uppercase() to v.uppercase() }
        patterns = pat.sortedByDescending { it.first.pattern.length }
        phrases = phr.sortedByDescending { it.first.pattern.length }
        synchronized(cache) { cache.clear() }
    }

    /** Tłumaczenie tekstu do wyświetlenia (bez zmian przy języku polskim albo braku w słowniku). */
    fun ts(s: String): String {
        if (!active || s.isBlank()) return s
        synchronized(cache) { cache[s] }?.let { return it }
        val r = translate(s, 0)
        synchronized(cache) { cache[s] = r }
        return r
    }

    fun t(s: CharSequence?): CharSequence? {
        if (s == null || !active) return s
        if (s is android.text.Spanned && s !is String) return exact[s.toString()] ?: s       // tekst z formatowaniem – tylko całe dopasowanie
        return ts(s.toString())
    }

    private fun translate(s: String, depth: Int): String {
        exact[s]?.let { return it }
        // zachowanie odstępów na brzegach
        val lead = s.takeWhile { it.isWhitespace() }; val trail = s.takeLastWhile { it.isWhitespace() }
        val core = s.trim()
        if (core != s) exact[core]?.let { return lead + it + trail }
        if (core.any { it.isLetter() } && core == core.uppercase()) upper[core]?.let { return lead + it + trail }
        // znaczki na brzegach („▸  Motyw”, „★ Ulubione”, „✔ Włączone”) – środek tłumaczony w całości, znaczki zostają
        if (depth < 3) SYMBOLS.matchEntire(core)?.let { m ->
            val pre = m.groupValues[1]; val inner = m.groupValues[2]; val post = m.groupValues[3]
            if ((pre.isNotEmpty() || post.isNotEmpty()) && inner.isNotEmpty()) {
                val ti = translate(inner, depth + 1)
                if (ti != inner) return lead + pre + ti + post + trail
            }
        }
        // wiele wierszy – każdy osobno
        if ('\n' in core && depth < 2) {
            val lines = core.split('\n')
            val tl = lines.map { if (it.isBlank()) it else translate(it, depth + 1) }
            if (tl != lines) return lead + tl.joinToString("\n") + trail
        }
        // szablony z wstawkami: „Kanał {}: …” – wstawki też tłumaczone (całe dopasowanie)
        for ((rx, en) in patterns) {
            val m = rx.matchEntire(core) ?: continue
            val groups = m.groupValues.drop(1)
            var i = 0
            val out = StringBuilder()
            var rest = en
            while (true) {
                val p = rest.indexOf("{}")
                if (p < 0) { out.append(rest); break }
                out.append(rest, 0, p)
                val g = groups.getOrElse(i++) { "" }
                out.append(if (depth < 2) exact[g.trim()]?.let { g.replace(g.trim(), it) } ?: g else g)
                rest = rest.substring(p + 2)
            }
            return lead + out + trail
        }
        // fragmenty (napisy sklejane w kodzie)
        var r = core
        for ((rx, en) in phrases) if (rx.containsMatchIn(r)) r = rx.replace(r, Regex.escapeReplacement(en))
        return if (r != core) lead + r + trail else s
    }

    // ---------------- widoki ----------------

    /**
     * Tłumaczenie drzewa widoków okna tuż przed narysowaniem klatki: teksty i podpowiedzi TextView (poza polami wpisywania).
     * Widok zapamiętuje tekst po tłumaczeniu – kolejne klatki bez zmian tekstu kosztują tylko porównanie referencji.
     */
    fun attach(window: android.view.Window?) {
        if (!active || window == null) return
        val root = window.decorView
        if (root.getTag(pl.iptv.tv.R.id.i18n_attached) == true) return
        root.setTag(pl.iptv.tv.R.id.i18n_attached, true)
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean = !active || !translateTree(root)
        })
    }

    /** Zwraca true, gdy coś zmieniono (klatka rysowana od nowa – bez mignięcia polskiego tekstu). */
    fun translateTree(v: View): Boolean {
        var changed = false
        if (v is TextView && v !is EditText) {
            val txt = v.text
            if (txt != null && txt.isNotEmpty() && v.getTag(pl.iptv.tv.R.id.i18n_done) !== txt) {
                val tr = t(txt)
                if (tr !== txt && tr.toString() != txt.toString()) { v.text = tr; changed = true }
                v.setTag(pl.iptv.tv.R.id.i18n_done, v.text)
            }
            val hint = v.hint
            if (hint != null && v.getTag(pl.iptv.tv.R.id.i18n_hint) !== hint) {
                val th = t(hint)
                if (th.toString() != hint.toString()) v.hint = th
                v.setTag(pl.iptv.tv.R.id.i18n_hint, v.hint)
            }
        } else if (v is EditText) {
            val hint = v.hint
            if (hint != null && v.getTag(pl.iptv.tv.R.id.i18n_hint) !== hint) {
                val th = t(hint); if (th.toString() != hint.toString()) v.hint = th
                v.setTag(pl.iptv.tv.R.id.i18n_hint, v.hint)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (translateTree(v.getChildAt(i))) changed = true
        return changed
    }

    /** Komunikat z tłumaczeniem (zamiennik `Toast.makeText`). */
    @JvmStatic fun makeToast(c: Context, text: CharSequence, duration: Int): android.widget.Toast =
        android.widget.Toast.makeText(c, t(text) ?: text, duration)
    @JvmStatic fun makeToast(c: Context, resId: Int, duration: Int): android.widget.Toast =
        android.widget.Toast.makeText(c, t(c.getText(resId)) ?: c.getText(resId), duration)
}

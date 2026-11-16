package pl.iptv.tv.ui

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Integracja ze SmartTube (zewnętrzny klient YouTube na Android TV).
 * Wykrywanie (wersja stabilna, potem beta), wyszukiwanie i odtwarzanie linków.
 * Każde uruchomienie jest bezpieczne: brak aplikacji / aktywności → okno z alternatywą, nigdy wyjątek.
 * Widoczność pakietów (Android 11+): blok `<queries>` w AndroidManifest.xml.
 */
object SmartTubeIntegrationHelper {

    const val PKG_STABLE = "com.liskovsoft.smarttubetv"
    const val PKG_BETA = "com.liskovsoft.smarttubetv.beta"
    /** Kolejność preferencji: stabilna przed beta. */
    val PACKAGES = listOf(PKG_STABLE, PKG_BETA)

    const val ACTION_SEARCH = "com.liskovsoft.smarttubetv.action.SEARCH"
    const val HOME_PAGE = "https://smarttubeapp.github.io"
    private const val YT_WATCH = "https://www.youtube.com/watch?v="
    private const val YT_RESULTS = "https://www.youtube.com/results?search_query="

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YT_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    // ---------------- wykrywanie ----------------

    /** Zainstalowany i włączony pakiet SmartTube (stabilny ma pierwszeństwo) albo null. */
    fun installedPackage(c: Context): String? = PACKAGES.firstOrNull { isEnabled(c.packageManager, it) }

    fun isInstalled(c: Context): Boolean = installedPackage(c) != null

    /** Wersja zainstalowanego SmartTube (do opisu w Ustawieniach) albo null. */
    fun installedVersion(c: Context): String? = installedPackage(c)?.let { p ->
        runCatching { packageInfo(c.packageManager, p).versionName }.getOrNull()
    }

    private fun isEnabled(pm: PackageManager, pkg: String): Boolean = try {
        packageInfo(pm, pkg).applicationInfo?.enabled == true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: SecurityException) { false }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, pkg: String) =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) else pm.getPackageInfo(pkg, 0)

    // ---------------- linki ----------------

    /**
     * Normalizuje link / identyfikator filmu do postaci `https://www.youtube.com/watch?v=ID`
     * (youtu.be, /shorts/, /live/, /embed/, ?v=, sam 11-znakowy ID). Playlisty i kanały YouTube
     * zostają bez zmian (https). Null = to nie jest link YouTube.
     */
    fun normalizeUrl(link: String?): String? {
        val s = link?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (VIDEO_ID.matches(s)) return YT_WATCH + s
        val u = runCatching { Uri.parse(if ("://" in s) s else "https://$s") }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (host !in YT_HOSTS) return null
        val seg = u.pathSegments.orEmpty()
        val id = when {
            host.endsWith("youtu.be") -> seg.firstOrNull()
            seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed", "v") -> seg[1]
            else -> runCatching { u.getQueryParameter("v") }.getOrNull()
        }
        if (id != null && VIDEO_ID.matches(id)) return YT_WATCH + id
        return if (seg.isNotEmpty() || u.query != null) u.buildUpon().scheme("https").build().toString() else null
    }

    fun searchUrl(query: String): String = YT_RESULTS + Uri.encode(query.trim())

    // ---------------- intencje ----------------

    fun searchIntent(pkg: String, query: String): Intent = Intent(ACTION_SEARCH).setPackage(pkg)
        .putExtra(SearchManager.QUERY, query.trim())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun viewIntent(pkg: String?, url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .apply { if (pkg != null) setPackage(pkg) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------- akcje ----------------

    /**
     * Wyszukiwanie w SmartTube. Kolejno: akcja SEARCH SmartTube → systemowe ACTION_SEARCH w pakiecie →
     * strona wyników YouTube otwarta w SmartTube. Brak SmartTube → okno z alternatywą.
     */
    fun search(a: Activity, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) { toast(a, "Wpisz frazę do wyszukania"); return false }
        val pkg = installedPackage(a) ?: run { notInstalled(a, searchUrl(q)); return false }
        if (tryStart(a, searchIntent(pkg, q)) ||
            tryStart(a, Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
            tryStart(a, viewIntent(pkg, searchUrl(q)))) return true
        cannotOpen(a, searchUrl(q))
        return false
    }

    /** Odtwarzanie linku YouTube w SmartTube (ACTION_VIEW). Brak SmartTube → okno z alternatywą. */
    /** [onClosed] – po zamknięciu okna zastępczego (np. ekran odtwarzacza może się wtedy zamknąć). */
    fun play(a: Activity, link: String, onClosed: (() -> Unit)? = null): Boolean {
        val url = normalizeUrl(link) ?: run { toast(a, "To nie jest link YouTube"); onClosed?.invoke(); return false }
        val pkg = installedPackage(a) ?: run { notInstalled(a, url, onClosed); return false }
        if (tryStart(a, viewIntent(pkg, url))) return true
        cannotOpen(a, url, onClosed)
        return false
    }

    // ---------------- oficjalna aplikacja YouTube ----------------

    const val PKG_YT_TV = "com.google.android.youtube.tv"
    const val PKG_YT = "com.google.android.youtube"

    /** Film w aplikacji YouTube (wersja TV, potem mobilna), a bez niej – w przeglądarce. */
    fun playInYouTube(a: Activity, link: String): Boolean {
        val url = normalizeUrl(link) ?: return false
        for (pkg in listOf(PKG_YT_TV, PKG_YT)) if (tryStart(a, viewIntent(pkg, url))) return true
        if (tryStart(a, viewIntent(null, url))) return true
        toast(a, "Brak aplikacji YouTube i przeglądarki")
        return false
    }

    /** Uruchamia sam SmartTube (ekran główny aplikacji). */
    fun launch(a: Activity): Boolean {
        val pkg = installedPackage(a) ?: run { notInstalled(a, null); return false }
        val pm = a.packageManager
        val i = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null && tryStart(a, i)) return true
        cannotOpen(a, null)
        return false
    }

    // ---------------- fallbacki ----------------

    private fun notInstalled(a: Activity, url: String?, onClosed: (() -> Unit)? = null) = fallback(a, "SmartTube",
        "Aplikacja SmartTube nie jest zainstalowana.\n\nMożna ją pobrać ze strony $HOME_PAGE" +
            (if (url != null) " albo otworzyć film w innej aplikacji." else "."), url, onClosed)

    private fun cannotOpen(a: Activity, url: String?, onClosed: (() -> Unit)? = null) = fallback(a, "SmartTube",
        "SmartTube nie przyjął polecenia (starsza lub zablokowana wersja)." +
            (if (url != null) "\n\nMożna otworzyć film w innej aplikacji." else ""), url, onClosed)

    private fun fallback(a: Activity, title: String, msg: String, url: String?, onClosed: (() -> Unit)? = null) {
        if (a.isFinishing || a.isDestroyed) { onClosed?.invoke(); return }
        val b = Ui.dialog(a).setTitle(title).setMessage(msg)
            .setNeutralButton("Strona SmartTube") { _, _ ->
                if (!tryStart(a, viewIntent(null, HOME_PAGE))) toast(a, "Brak przeglądarki – $HOME_PAGE")
            }
            .setNegativeButton("Anuluj", null)
        if (url != null) b.setPositiveButton("Inna aplikacja") { _, _ ->
            if (!tryStart(a, viewIntent(null, url))) toast(a, "Brak aplikacji do otwarcia linku")
        }
        val d = b.show()
        if (onClosed != null) d.setOnDismissListener { onClosed() }
    }

    private fun tryStart(a: Activity, i: Intent): Boolean = try {
        if (i.resolveActivity(a.packageManager) == null) false else { a.startActivity(i); true }
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
}

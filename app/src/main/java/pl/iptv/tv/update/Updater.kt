package pl.iptv.tv.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Repository
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Aktualizacja aplikacji: z adresu (JSON/APK), z pliku lub odebrana z telefonu.
 * Przed instalacją: ta sama nazwa pakietu, ten sam podpis, wyższa wersja.
 */
object Updater {
    data class Info(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

    private val main = Handler(Looper.getMainLooper())

    fun currentCode(c: Context): Long = PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))
    fun currentName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Serwer GitHub czasem chwilowo odpowiada błędem 5xx (np. 504) – ponowienia, potem obejście przez API GitHub. */
    private val GH = Regex("^https?://[^/]+/([^/]+)/([^/]+)/releases/(?:latest/download|download/[^/]+)/([^/?]+)")
    var retryDelaysMs = listOf(2_000L, 5_000L)

    fun fetch(url: String): ByteArray {
        var last: Exception? = null
        for (attempt in 0..retryDelaysMs.size) {
            try {
                Repository.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) return r.body!!.bytes()
                    if (r.code < 500) throw IOException("HTTP ${r.code}")       // 4xx – ponawianie nic nie da
                    last = IOException("HTTP ${r.code}")
                }
            } catch (e: IOException) { if (e.message?.startsWith("HTTP 4") == true) throw e; last = e }
            if (attempt < retryDelaysMs.size) Thread.sleep(retryDelaysMs[attempt])
        }
        // obejście: plik z najnowszego wydania przez API (inny serwer niż strona github.com)
        GH.find(url)?.let { m -> runCatching { return viaApi(m.groupValues[1], m.groupValues[2], m.groupValues[3]) } }
        throw IOException("Serwer aktualizacji chwilowo nie odpowiada (${last?.message}). Spróbuj ponownie za kilka minut.")
    }

    var apiBase = "https://api.github.com"

    private fun viaApi(owner: String, repo: String, name: String): ByteArray {
        val rel = Repository.http.newCall(Request.Builder().url("$apiBase/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("API HTTP ${r.code}"); JSONObject(r.body!!.string())
        }
        val assets = rel.getJSONArray("assets")
        val a = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name") == name }
        return Repository.http.newCall(Request.Builder().url(a.getString("url")).header("Accept", "application/octet-stream").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("API HTTP ${r.code}"); r.body!!.bytes()
        }
    }

    /** Manifest: {"versionCode":15,"versionName":"2.4","apk":"IptvTv.apk","notes":"…"}; adres .apk = bez manifestu. */
    fun parseManifest(base: String, body: String): Info {
        val o = JSONObject(body)
        val apk = o.optString("apk").ifBlank { o.optString("url") }
        require(apk.isNotBlank()) { "Manifest bez adresu pliku apk" }
        return Info(o.optLong("versionCode"), o.optString("versionName"), if (apk.startsWith("http")) apk else URI(base).resolve(apk).toString(), o.optString("notes"))
    }

    /** Sprawdza adres aktualizacji; null = brak nowszej wersji. Poza wątkiem głównym. */
    suspend fun check(c: Context, url: String = Prefs.updateUrl.trim()): Info? = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "Brak adresu aktualizacji" }
        Prefs.updateLastCheck = System.currentTimeMillis()
        if (url.substringBefore('?').endsWith(".apk", true)) return@withContext Info(-1, "", url, "")
        val body = String(fetch(url))
        parseManifest(url, body).takeIf { it.versionCode > currentCode(c) }
    }

    suspend fun download(c: Context, url: String): File = withContext(Dispatchers.IO) {
        val f = File(c.cacheDir, "update.apk")
        f.writeBytes(fetch(url))
        f
    }

    /** Odciski SHA-256 certyfikatów (bieżące i z historii rotacji klucza). */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signatures(pi: PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs = ArrayList<android.content.pm.Signature>()
        if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.let { si ->
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.let { sigs += it }
        }
        pi.signatures?.let { sigs += it }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun archiveSignatures(pm: PackageManager, path: String): Set<String> {
        // część urządzeń (zwłaszcza Android TV) nie zwraca podpisu pliku przy jednej z flag – próbujemy obu
        val flags = if (Build.VERSION.SDK_INT >= 28) listOf(PackageManager.GET_SIGNING_CERTIFICATES, PackageManager.GET_SIGNATURES) else listOf(PackageManager.GET_SIGNATURES)
        for (f in flags) {
            val pi = runCatching { pm.getPackageArchiveInfo(path, f) }.getOrNull() ?: continue
            pi.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path }
            signatures(pi).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptySet()
    }

    @Suppress("DEPRECATION")
    private val sigFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Zwraca opis błędu lub null, gdy plik jest poprawną nowszą wersją tej aplikacji. */
    fun verify(c: Context, f: File): String? {
        val pm = c.packageManager
        val pi = pm.getPackageArchiveInfo(f.path, 0) ?: return "To nie jest poprawny plik APK"
        if (pi.packageName != c.packageName) return "To inna aplikacja (${pi.packageName})"
        val mine = signatures(pm.getPackageInfo(c.packageName, sigFlag))
        val theirs = archiveSignatures(pm, f.path)
        // podpis pliku nieczytelny na tym urządzeniu: sprawdzi go system przy instalacji (inny podpis zostanie odrzucony)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) return SIG_MISMATCH
        if (PackageInfoCompat.getLongVersionCode(pi) <= currentCode(c)) return "To nie jest nowsza wersja (${pi.versionName})"
        return null
    }

    const val SIG_MISMATCH = "Zainstalowana aplikacja ma inny podpis niż aktualizacja (pochodzi z innej kompilacji). " +
        "Zrób kopię zapasową ustawień (Funkcje → Kopia zapasowa), odinstaluj aplikację i zainstaluj nowy plik ręcznie – kolejne aktualizacje będą już automatyczne."

    /** Instalacja przez PackageInstaller (Android 12+: bez pytania, gdy to możliwe). */
    fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags).intentSender)
        }
    }

    private fun toast(c: Context, s: String) = main.post { Toast.makeText(c.applicationContext, s, Toast.LENGTH_LONG).show() }

    /** Plik odebrany z telefonu / pobrany: sprawdź i zainstaluj. Zwraca (sukces, komunikat). */
    fun handleIncoming(c: Context, f: File): Pair<Boolean, String> {
        verify(c, f)?.let { toast(c, "Aktualizacja odrzucona: $it"); return false to it }
        if (!canInstall(c)) {
            val m = "Brak zgody na instalowanie aktualizacji – nadaj ją w Ustawienia → Aktualizacja"
            toast(c, m); return false to m
        }
        toast(c, "Instalowanie aktualizacji…")
        main.post { runCatching { install(c, f) }.onFailure { toast(c, "Błąd instalacji: ${it.message}") } }
        return true to "Aktualizacja wysłana – trwa instalacja na urządzeniu"
    }

    /** Pełny proces: sprawdź → pobierz → zainstaluj. */
    suspend fun updateFromUrl(c: Context): String {
        val info = check(c) ?: return "Masz najnowszą wersję (${currentName(c)})"
        val f = download(c, info.apkUrl)
        val (ok, msg) = handleIncoming(c, f)
        return if (ok) "Aktualizacja do ${info.versionName.ifBlank { "nowej wersji" }}…" else msg
    }

    /** Automatyczne sprawdzenie (co 6 godzin, przy starcie aplikacji). */
    fun autoCheck(c: Context) {
        if (!Prefs.updateAuto || Prefs.updateUrl.isBlank()) return
        if (System.currentTimeMillis() - Prefs.updateLastCheck < 6 * 3_600_000L) return
        Thread({
            runCatching { kotlinx.coroutines.runBlocking { check(c) } }.getOrNull()?.let { info ->
                toast(c, "Pobieranie aktualizacji ${info.versionName}…")
                runCatching { kotlinx.coroutines.runBlocking { download(c, info.apkUrl) } }.getOrNull()?.let { handleIncoming(c, it) }
            }
        }, "UpdateCheck").start()
    }
}

/** Wynik instalacji; prośba o potwierdzenie otwiera systemowe okno. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(c, "Aktualizacja nieudana: ${Updater.SIG_MISMATCH}", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(c, "Aktualizacja nieudana: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Po aktualizacji uruchom aplikację ponownie. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { c.startActivity(Intent(c, pl.iptv.tv.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

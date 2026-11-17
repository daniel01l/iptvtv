package pl.iptv.tv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import pl.iptv.tv.HomeActivity
import pl.iptv.tv.data.Prefs

/** Oszczędzanie energii, tryb launchera, lista aplikacji systemu. */
object SystemTools {

    // ---------------- oszczędzanie energii ----------------

    fun isBatteryOptimizationOff(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /**
     * Otwiera systemowe okno „Zezwolić na działanie w tle?” (1 kliknięcie).
     * Android nie pozwala wyłączyć optymalizacji bez zgody użytkownika.
     */
    /** Otwiera ekran zgody; false gdy urządzenie go nie ma. */
    @SuppressLint("BatteryLife")
    fun requestBatteryOptimizationScreen(a: Activity): Boolean =
        tryStart(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) ||
            tryStart(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    @SuppressLint("BatteryLife")
    fun requestBatteryOptimizationOff(a: Activity) {
        if (isBatteryOptimizationOff(a)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (tryStart(a, direct) || tryStart(a, list)) return
        Ui.dialog(a).setTitle("Oszczędzanie energii")
            .setMessage("To urządzenie nie ma ekranu ustawień optymalizacji baterii.\n\n" +
                "Można ją wyłączyć z komputera:\nadb shell dumpsys deviceidle whitelist +${a.packageName}")
            .setPositiveButton("OK", null).show()
    }

    // ---------------- tryb launchera ----------------

    private fun homeComponent(c: Context) = ComponentName(c, HomeActivity::class.java)

    fun isLauncherEnabled(c: Context) =
        c.packageManager.getComponentEnabledSetting(homeComponent(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setLauncherEnabled(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(
            homeComponent(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Czy aplikacja jest aktualnie domyślnym ekranem głównym. */
    fun isDefaultHome(c: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == c.packageName
    }

    /** Pakiety innych launcherów (np. systemowego) – do instrukcji adb. */
    fun otherLaunchers(c: Context): List<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }.filter { it != c.packageName && it != "com.android.settings" }.distinct()
    }

    /** Prosi system o wybór domyślnego ekranu głównego. */
    fun chooseDefaultHome(a: Activity) {
        if (isDefaultHome(a)) return
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = a.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                runCatching { a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 77) }.isSuccess
            ) return
        }
        if (tryStart(a, Intent(Settings.ACTION_HOME_SETTINGS))) return
        // ostatnia próba: systemowy wybór „Otwórz za pomocą… / Zawsze”
        if (tryStart(a, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        showAdbHelp(a)
    }

    fun showAdbHelp(a: Activity) {
        val others = otherLaunchers(a)
        val sb = StringBuilder()
        sb.append("Ten telewizor nie pozwala wybrać innego ekranu głównego z poziomu menu. ")
        sb.append("Można wyłączyć systemowy launcher z komputera (adb):\n\n")
        if (others.isEmpty()) sb.append("adb shell pm disable-user --user 0 <pakiet launchera>\n")
        others.forEach { sb.append("adb shell pm disable-user --user 0 $it\n") }
        sb.append("\nPrzywrócenie (zrób to PRZED wyłączeniem trybu launchera w tej aplikacji!):\n")
        others.ifEmpty { listOf("<pakiet launchera>") }.forEach { sb.append("adb shell pm enable $it\n") }
        Ui.dialog(a).setTitle("Ustawienie jako launcher").setMessage(sb.toString())
            .setPositiveButton("OK", null).show()
    }

    // ---------------- aplikacje i ustawienia systemu ----------------

    data class AppEntry(val label: String, val intent: Intent)

    fun installedApps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val result = LinkedHashMap<String, AppEntry>()
        val cats = buildList { if (Build.VERSION.SDK_INT >= 21) add(Intent.CATEGORY_LEANBACK_LAUNCHER); add(Intent.CATEGORY_LAUNCHER) }
        for (cat in cats) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == c.packageName || result.containsKey(pkg)) return@forEach
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                result[pkg] = AppEntry(ri.loadLabel(pm).toString(), launch)
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    /** Biblioteka aplikacji (siatka z ikonami, ustawienia systemu) – launcher, pulpity, menu odtwarzacza. */
    fun showApps(a: Activity) { a.startActivity(Intent(a, AppLibraryActivity::class.java)) }

    // ---------------- aplikacje w menu odtwarzacza ----------------

    class QuickApp(val id: String, val label: String, val icon: android.graphics.drawable.Drawable?, val intent: Intent)

    /** Zapisane skróty; pomija odinstalowane aplikacje. */
    fun quickApps(c: Context): List<QuickApp> {
        val pm = c.packageManager
        return Prefs.quickApps.mapNotNull { id ->
            val cn = ComponentName.unflattenFromString(id) ?: return@mapNotNull null
            val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull() ?: return@mapNotNull null
            val icon = runCatching { info.loadBanner(pm) ?: info.loadIcon(pm) }.getOrNull()
            QuickApp(id, info.loadLabel(pm).toString(), icon,
                Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun launch(a: Activity, app: QuickApp) { if (!tryStart(a, app.intent)) toast(a, "Nie można uruchomić: ${app.label}") }

    /** Wybór aplikacji do dodania do menu. */
    fun addQuickApp(a: Activity, onChanged: () -> Unit) {
        val have = Prefs.quickApps.toSet()
        val apps = installedApps(a).filter { it.intent.component?.flattenToString() !in have }
        if (apps.isEmpty()) { toast(a, "Brak innych aplikacji do dodania"); return }
        Dialogs.menu(a, "Dodaj aplikację do menu", apps.map { e ->
            e.label to {
                Prefs.quickApps = Prefs.quickApps + e.intent.component!!.flattenToString()
                toast(a, "Dodano: ${e.label}"); onChanged()
            }
        })
    }

    fun removeQuickApp(a: Activity, app: QuickApp, onChanged: () -> Unit) {
        Dialogs.confirm(a, app.label, "Usunąć aplikację z menu?") {
            Prefs.quickApps = Prefs.quickApps - app.id; onChanged()
        }
    }

    /** Zarządzanie listą (Ustawienia). */
    fun manageQuickApps(a: Activity, onChanged: () -> Unit) {
        val apps = quickApps(a)
        val items = mutableListOf<Pair<String, () -> Unit>>("➕ Dodaj aplikację…" to { addQuickApp(a, onChanged) })
        apps.forEach { app -> items += "✕ Usuń: ${app.label}" to { removeQuickApp(a, app, onChanged) } }
        Dialogs.menu(a, "Aplikacje w menu odtwarzacza", items)
    }

    fun openSystemSettings(a: Activity) { if (!tryStart(a, Intent(Settings.ACTION_SETTINGS))) toast(a, "Brak ustawień systemu") }

    private fun tryStart(a: Activity, i: Intent): Boolean = try {
        if (i.resolveActivity(a.packageManager) == null) false else { a.startActivity(i); true }
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
}

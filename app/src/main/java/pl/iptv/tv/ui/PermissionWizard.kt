package pl.iptv.tv.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Kreator uprawnień: lista → po kolei każde uprawnienie z wyjaśnieniem (Zezwól / Pomiń).
 * Uprawnienia wymagające ekranu ustawień systemu wznawiają kreator w onResume aktywności.
 */
class PermissionWizard(private val a: Activity, private val onDone: () -> Unit = {}) {

    class Step(
        val title: String,
        val why: String,
        val granted: (Context) -> Boolean,
        /** Uruchamia prośbę; false = urządzenie nie ma odpowiedniego ekranu. */
        val request: (Activity) -> Boolean,
        val adb: String? = null,
    )

    companion object {
        const val REQ = 501

        fun steps(c: Context): List<Step> = buildList {
            val pkg = c.packageName
            if (Build.VERSION.SDK_INT >= 30) add(Step(
                "📂 Dostęp do plików",
                "Pozwala wczytać listę .m3u, EPG i zdjęcie tła z pamięci urządzenia lub pendrive'a (np. z folderu Pobrane).",
                { runCatching { Environment.isExternalStorageManager() }.getOrDefault(false) },
                { act -> start(act, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))) ||
                    start(act, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) },
                "adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
            )) else if (Build.VERSION.SDK_INT >= 23) add(Step(
                "📂 Dostęp do plików",
                "Pozwala wczytać listę .m3u, EPG i zdjęcie tła z pamięci urządzenia lub pendrive'a.",
                { it.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED },
                { act -> act.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ); true },
            ))
            if (Build.VERSION.SDK_INT >= 33) add(Step(
                "🔔 Powiadomienia",
                "Przypomnienia o programach i serialach jako powiadomienia (głównie na telefonie).",
                { it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { act -> act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ); true },
            ))
            if (Build.VERSION.SDK_INT >= 23) add(Step(
                "🔋 Wyłączenie oszczędzania energii",
                "Dzięki temu system nie zamyka aplikacji w tle, a start po włączeniu i przypomnienia działają niezawodnie. W następnym oknie wybierz „Zezwól”.",
                { SystemTools.isBatteryOptimizationOff(it) },
                { act -> SystemTools.requestBatteryOptimizationScreen(act) },
                "adb shell dumpsys deviceidle whitelist +$pkg",
            ))
            if (Build.VERSION.SDK_INT >= 23) add(Step(
                "🪟 Wyświetlanie nad innymi aplikacjami",
                "Potrzebne, aby aplikacja mogła sama otworzyć się po włączeniu urządzenia i pokazać przypomnienie, gdy jest w tle.",
                { Settings.canDrawOverlays(it) },
                { act -> start(act, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))) ||
                    start(act, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) },
                "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
            ))
            if (Build.VERSION.SDK_INT >= 26) add(Step(
                "⬆ Instalowanie aktualizacji",
                "Pozwala aktualizować aplikację z ustawień, automatycznie lub zdalnie z telefonu – bez ręcznego pobierania pliku.",
                { it.packageManager.canRequestPackageInstalls() },
                { act -> start(act, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg"))) },
                "adb shell appops set $pkg REQUEST_INSTALL_PACKAGES allow",
            ))
            if (Build.VERSION.SDK_INT >= 31) add(Step(
                "⏰ Alarmy i przypomnienia",
                "Przypomnienie pojawi się dokładnie o czasie, a nie z kilkuminutowym opóźnieniem.",
                { (it.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() },
                { act -> start(act, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))) },
                "adb shell appops set $pkg SCHEDULE_EXACT_ALARM allow",
            ))
        }

        fun missing(c: Context) = steps(c).filter { !it.granted(c) }
        fun summary(c: Context): String { val s = steps(c); return "${s.count { it.granted(c) }}/${s.size} nadane" }

        private fun start(a: Activity, i: Intent): Boolean = try {
            if (i.resolveActivity(a.packageManager) == null) false else { a.startActivity(i); true }
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Step>()
    private var waiting = false
    private var total = 0
    var running = false; private set

    /** Uruchamia kreator dla brakujących uprawnień. */
    fun start() {
        queue.clear(); queue.addAll(missing(a)); total = queue.size
        if (queue.isEmpty()) { onDone(); return }
        running = true
        val list = queue.joinToString("\n") { "• ${it.title}" }
        Ui.dialog(a).setTitle("Uprawnienia aplikacji")
            .setMessage("Aby wszystkie funkcje działały, aplikacja poprosi o:\n\n$list\n\nKażde uprawnienie możesz pominąć i nadać później w Ustawienia → Funkcje.")
            .setPositiveButton("Dalej") { _, _ -> next() }
            .setNegativeButton("Pomiń wszystkie") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun next() {
        val s = queue.removeFirstOrNull() ?: return finish()
        if (s.granted(a)) return next()
        val nr = total - queue.size
        Ui.dialog(a).setTitle("$nr/$total  ${s.title}").setMessage(s.why)
            .setPositiveButton("Zezwól") { _, _ ->
                if (s.request(a)) waiting = true
                else Ui.dialog(a).setTitle(s.title)
                    .setMessage("To urządzenie nie ma ekranu ustawień dla tego uprawnienia." +
                        (s.adb?.let { "\n\nMożna je nadać z komputera:\n$it" } ?: ""))
                    .setPositiveButton("OK") { _, _ -> next() }.setCancelable(false).show()
            }
            .setNegativeButton("Pomiń") { _, _ -> next() }
            .setCancelable(false).show()
    }

    /** Wywołać z onResume aktywności – powrót z ekranu ustawień systemu. */
    fun onResume() { if (waiting) { waiting = false; handler.postDelayed({ next() }, 400) } }

    /** Wywołać z onRequestPermissionsResult (także gdy system odmówił bez pokazania okna). */
    fun onPermissionResult(requestCode: Int) {
        if (requestCode == REQ && waiting) { waiting = false; handler.postDelayed({ next() }, 200) }
    }

    private fun finish() {
        running = false
        val granted = steps(a).count { it.granted(a) }
        android.widget.Toast.makeText(a, "Uprawnienia: $granted/${steps(a).size} nadane", android.widget.Toast.LENGTH_SHORT).show()
        onDone()
    }
}

package pl.iptv.tv

import android.content.Context
import java.io.File

/**
 * (7.22) Lekki zapis ostatniej awarii: `UncaughtExceptionHandler` zapisuje stacktrace do `filesDir/crash_last.txt`
 * (czas, wersja, urządzenie, wątek, pełny ślad z przyczynami), a potem przekazuje wyjątek do poprzedniego (domyślnego)
 * handlera systemu – zachowanie aplikacji przy awarii się nie zmienia, ale przyczyna zostaje do odczytu także bez adb
 * (`run-as pl.iptv.tv cat files/crash_last.txt`).
 *
 * Pomocniczo [safe] – krok startu aplikacji w try-catch: błąd jednego kroku (I/O, usługa systemu) jest logowany i
 * dopisywany do `startup_errors.txt`, a start idzie dalej.
 */
object CrashLog {
    const val FILE = "crash_last.txt"
    const val STARTUP_FILE = "startup_errors.txt"
    private const val TAG = "CrashLog"
    /** Limit pliku błędów startu (bajty) – starszy plik jest zastępowany. */
    private const val STARTUP_MAX = 32 * 1024L

    @Volatile private var dir: File? = null
    @Volatile var installed = false; private set

    /** Instalacja raz na proces (powtórne wywołanie – bez łańcucha handlerów). */
    @Synchronized fun install(c: Context) {
        dir = runCatching { c.filesDir }.getOrNull() ?: dir
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(t, e) } catch (_: Throwable) {}
            // Android zawsze ma domyślny handler (zamknięcie aplikacji); bez niego (JVM, testy) – jak w JVM: ślad na stderr, proces działa dalej
            if (previous != null) previous.uncaughtException(t, e)
            else runCatching { System.err.print("Exception in thread \"${t.name}\" "); e.printStackTrace() }
        }
        installed = true
    }

    /** Treść raportu (testy). */
    fun report(t: Thread, e: Throwable): String {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val time = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date()) }.getOrDefault("")
        return buildString {
            append("czas: ").append(time).append('\n')
            append("wersja: ").append(runCatching { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }.getOrDefault("?")).append('\n')
            append("urządzenie: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("wątek: ").append(t.name).append('\n')
            append(sw.toString())
        }
    }

    private fun write(t: Thread, e: Throwable) {
        val d = dir ?: return
        val f = File(d, FILE)
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(report(t, e))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Ostatnia zapisana awaria (null – brak). */
    fun last(c: Context): String? = runCatching { File(c.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Krok startu w try-catch: błąd logowany (i dopisywany do [STARTUP_FILE]), start idzie dalej. Zwraca false przy błędzie. */
    inline fun safe(step: String, block: () -> Unit): Boolean = try { block(); true } catch (t: Throwable) { failed(step, t); false }

    fun failed(step: String, t: Throwable) {
        runCatching { android.util.Log.e(TAG, "start: krok „$step” pominięty", t) }
        val d = dir ?: return
        runCatching {
            val f = File(d, STARTUP_FILE)
            if (f.length() > STARTUP_MAX) f.delete()
            f.appendText("${System.currentTimeMillis()} $step: ${t.javaClass.name}: ${t.message}\n")
        }
    }

    /** Testy. */
    fun resetForTest() { installed = false; dir = null }
}

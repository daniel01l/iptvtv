package pl.iptv.tv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * (7.17) Profil bazowy D-iptv Player: ścieżki kompilowane z wyprzedzeniem (AOT) i kolejność klas zimnego startu.
 *
 * Scenariusz jak na telewizorze (pilot, D-pad):
 * 1. zimny start i ekran Start (hero + karuzela „Ostatnio oglądane” przewijana ◄ ►),
 * 2. pasek nawigacji (AppNavigationRail) – otwarcie, ruch, zamknięcie,
 * 3. Kanały / EPG – szybkie przewijanie listy kanałów w dół i w górę,
 * 4. STube, Stremio i Ustawienia – wejście, krótki ruch, powrót.
 * Każdy krok odporny na brak danych (np. pusta lista, inny motyw) – generowanie zbiera to, co dało się uruchomić.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PKG, includeInStartupProfile = true, maxIterations = 3, stableIterations = 2) {
        pressHome()
        startActivityAndWait()
        settle()
        step { startScreen() }
        step { navigationRail() }
        step { channelsAndEpg() }
        step { openFromRail("STube", "stRows") { keys(KeyEvent.KEYCODE_DPAD_DOWN, 3); keys(KeyEvent.KEYCODE_DPAD_RIGHT, 4); keys(KeyEvent.KEYCODE_DPAD_UP, 3) } }
        step { openFromRail("Stremio", null) { keys(KeyEvent.KEYCODE_DPAD_DOWN, 2); keys(KeyEvent.KEYCODE_DPAD_RIGHT, 4); keys(KeyEvent.KEYCODE_DPAD_LEFT, 4) } }
        step { openFromRail("Ustawienia|Settings", null) { keys(KeyEvent.KEYCODE_DPAD_DOWN, 8); keys(KeyEvent.KEYCODE_DPAD_UP, 8) } }
    }

    companion object { const val PKG = "pl.iptv.tv"; const val WAIT_MS = 5_000L }
}

private fun MacrobenchmarkScope.step(block: MacrobenchmarkScope.() -> Unit) { runCatching { block() }; settle() }

private fun MacrobenchmarkScope.settle() { device.waitForIdle(); Thread.sleep(400) }

private fun MacrobenchmarkScope.keys(code: Int, n: Int, pauseMs: Long = 120) =
    repeat(n) { device.pressKeyCode(code); Thread.sleep(pauseMs) }

/** Start: hero, ▼ do rzędów, karuzela ◄ ► (ładowanie obrazów, skala fokusu, przewijanie rzędów), powrót na górę. */
private fun MacrobenchmarkScope.startScreen() {
    keys(KeyEvent.KEYCODE_DPAD_DOWN, 1); settle()
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, 6, 160); keys(KeyEvent.KEYCODE_DPAD_LEFT, 6, 160)
    keys(KeyEvent.KEYCODE_DPAD_DOWN, 2, 200); keys(KeyEvent.KEYCODE_DPAD_RIGHT, 4, 160)
    keys(KeyEvent.KEYCODE_DPAD_UP, 3, 200)
}

/** Pasek nawigacji: ◄ – rozwinięcie ze scrimem, ▼▲ po pozycjach, ► – zwinięcie. */
private fun MacrobenchmarkScope.navigationRail() {
    keys(KeyEvent.KEYCODE_DPAD_LEFT, 3, 200); settle()
    keys(KeyEvent.KEYCODE_DPAD_DOWN, 4, 180); keys(KeyEvent.KEYCODE_DPAD_UP, 4, 180)
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, 1); settle()
}

/** Aktywność na wierzchu (nazwa klasy z `dumpsys`). */
private fun MacrobenchmarkScope.topActivity(): String = device.executeShellCommand("dumpsys activity activities").lineSequence()
    .firstOrNull { "topResumedActivity" in it || "mResumedActivity" in it }.orEmpty()

/** Powrót na ekran Start (Nowoczesny) – WSTECZ, aż Start będzie na wierzchu (ekrany z polem wyszukiwania / panelami potrzebują kilku). */
private fun MacrobenchmarkScope.backToStart() {
    repeat(5) {
        if ("ModernHomeActivity" in topActivity()) return
        device.pressBack(); settle()
    }
}

/** Pozycja paska nawigacji ekranu Start po podpisie (pierwsza od lewej – nie tekst w treści); [screenId] – widok nowego ekranu. */
private fun MacrobenchmarkScope.openFromRail(label: String, screenId: String?, inside: MacrobenchmarkScope.() -> Unit) {
    backToStart()
    keys(KeyEvent.KEYCODE_DPAD_LEFT, 3, 200)
    // podpis po polsku albo po angielsku (język interfejsu z ustawień aplikacji): „A|B”
    val pattern = java.util.regex.Pattern.compile(label)
    device.wait(Until.hasObject(By.text(pattern)), BaselineProfileGenerator.WAIT_MS)
    val item = device.findObjects(By.text(pattern)).minByOrNull { it.visibleBounds.left } ?: return
    item.click()
    if (screenId != null) device.wait(Until.hasObject(By.res(BaselineProfileGenerator.PKG, screenId)), BaselineProfileGenerator.WAIT_MS)
    else Thread.sleep(2_000)
    settle()
    inside()
    backToStart()
}

/** Kanały / EPG: wejście z paska („Na żywo”), szybkie przewijanie listy (rzuty i przytrzymane ▼▲), podgląd programu, powrót. */
private fun MacrobenchmarkScope.channelsAndEpg() {
    keys(KeyEvent.KEYCODE_DPAD_LEFT, 3, 200)
    val live = java.util.regex.Pattern.compile("Na żywo|Live")
    device.wait(Until.hasObject(By.text(live)), BaselineProfileGenerator.WAIT_MS)
    device.findObjects(By.text(live)).minByOrNull { it.visibleBounds.left }?.click()
    // motyw bez ekranu Start – lista kanałów jest ekranem startowym
    val list = device.wait(Until.findObject(By.res(BaselineProfileGenerator.PKG, "channels")), BaselineProfileGenerator.WAIT_MS) ?: return
    settle()
    keys(KeyEvent.KEYCODE_DPAD_DOWN, 25, 60); keys(KeyEvent.KEYCODE_DPAD_UP, 25, 60)
    runCatching { list.fling(Direction.DOWN); settle(); list.fling(Direction.UP) }
    settle()
    // ► program kanału, ◄ z powrotem, ◄ kategorie
    keys(KeyEvent.KEYCODE_DPAD_RIGHT, 1); keys(KeyEvent.KEYCODE_DPAD_DOWN, 4); keys(KeyEvent.KEYCODE_DPAD_LEFT, 2)
    backToStart()
}

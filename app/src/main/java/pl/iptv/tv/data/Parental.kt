package pl.iptv.tv.data

import java.security.MessageDigest

/** Kontrola rodzicielska: PIN, blokada grup i kanałów. */
object Parental {
    /** Odblokowane do zamknięcia aplikacji (po podaniu PIN-u). */
    @Volatile var unlocked = false

    val enabled get() = Prefs.parentalOn && Prefs.parentalPin.isNotBlank()

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("iptvtv:$pin").toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(pin: String) { Prefs.parentalPin = hash(pin) }
    fun check(pin: String) = Prefs.parentalPin.isNotBlank() && hash(pin) == Prefs.parentalPin

    fun isLocked(ch: Channel) = enabled && (ch.key in Prefs.lockedChannels || ch.group in Prefs.lockedGroups)
    fun needsPin(ch: Channel) = isLocked(ch) && !unlocked
    fun hideFromLists(ch: Channel) = enabled && Prefs.parentalHide && !unlocked && isLocked(ch)
}

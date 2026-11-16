package pl.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tokeny konta YouTube (OAuth 2.0) w EncryptedSharedPreferences (AES-256, klucz w Android Keystore).
 * Osobny plik – nie trafia do kopii zapasowej ustawień ([Backup] czyta tylko „iptv”).
 * Gdy Keystore jest niedostępny/uszkodzony: tokeny tylko w pamięci (do zamknięcia aplikacji), nigdy jawnym tekstem na dysku.
 */
object YouTubeTokens {
    private const val FILE = "yt_account"
    private const val K_ACCESS = "access"; private const val K_REFRESH = "refresh"; private const val K_EXPIRES = "expires"
    private const val K_ACCOUNT = "account"; private const val K_SCOPE = "scope"

    private var app: Context? = null
    @Volatile private var opened = false
    @Volatile private var sp: SharedPreferences? = null
    private val mem = HashMap<String, Any>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Testy: własny magazyn (null = tylko pamięć). */
    fun useStorage(s: SharedPreferences?) { synchronized(this) { sp = s; opened = true; mem.clear() } }

    /** Czy tokeny przetrwają ponowne uruchomienie (szyfrowany plik dostępny). */
    val persistent: Boolean get() = store() != null

    // otwierane przy pierwszym użyciu (operacje Keystore nie spowalniają startu aplikacji)
    private fun store(): SharedPreferences? {
        if (opened) return sp
        synchronized(this) {
            if (opened) return sp
            val c = app
            sp = if (c == null) null else runCatching { open(c) }.recoverCatching {
                // uszkodzony zestaw kluczy (np. po przywróceniu systemu) – czyścimy i tworzymy od nowa
                c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
                open(c)
            }.onFailure { android.util.Log.w("YouTubeTokens", "Brak szyfrowanego magazynu – tokeny tylko w pamięci", it) }.getOrNull()
            opened = true
            return sp
        }
    }

    private fun open(c: Context): SharedPreferences {
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(c, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    // odczyt z pliku, a gdy zapis do niego się nie udał – z pamięci
    private fun str(k: String): String = store()?.let { runCatching { it.getString(k, "") }.getOrNull() }?.takeIf { it.isNotEmpty() }
        ?: (synchronized(this) { mem[k] as? String }) ?: ""
    private fun long(k: String): Long = store()?.let { runCatching { it.getLong(k, 0L) }.getOrNull() }?.takeIf { it != 0L }
        ?: (synchronized(this) { mem[k] as? Long }) ?: 0L

    val accessToken: String get() = str(K_ACCESS)
    val refreshToken: String get() = str(K_REFRESH)
    /** Wygaśnięcie tokenu dostępu (ms, czas systemowy). */
    val expiresAt: Long get() = long(K_EXPIRES)
    val accountName: String get() = str(K_ACCOUNT)
    val scope: String get() = str(K_SCOPE)

    /** Zalogowany: jest token odświeżania albo ważny token dostępu. */
    val loggedIn: Boolean get() = refreshToken.isNotBlank() || (accessToken.isNotBlank() && !expired())

    /** Token dostępu wygasa w ciągu [marginMs] (domyślnie minuta zapasu). */
    fun expired(now: Long = System.currentTimeMillis(), marginMs: Long = 60_000L) = expiresAt - marginMs <= now

    /** Zapis po logowaniu / odświeżeniu; pusty [refresh] zachowuje poprzedni (Google nie zawsze go zwraca). */
    fun save(access: String, refresh: String?, expiresInS: Long, scope: String? = null, now: Long = System.currentTimeMillis()) {
        val values = buildMap<String, Any> {
            put(K_ACCESS, access); put(K_EXPIRES, now + expiresInS * 1000L)
            if (!refresh.isNullOrBlank()) put(K_REFRESH, refresh)
            if (!scope.isNullOrBlank()) put(K_SCOPE, scope)
        }
        write(values)
    }

    fun saveAccount(name: String) = write(mapOf(K_ACCOUNT to name))

    /** Wylogowanie: usuwa wszystkie dane konta. */
    fun clear() {
        store()?.let { s -> runCatching { s.edit().clear().commit() } }
        synchronized(this) { mem.clear() }
    }

    private fun write(values: Map<String, Any>) {
        val s = store()
        if (s != null && runCatching {
                s.edit().apply { values.forEach { (k, v) -> if (v is Long) putLong(k, v) else putString(k, v as String) } }.commit()
            }.getOrDefault(false)) return
        synchronized(this) { mem.putAll(values) }
    }
}

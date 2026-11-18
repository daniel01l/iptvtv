package pl.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import pl.iptv.tv.BuildConfig
import kotlin.coroutines.coroutineContext

/**
 * Logowanie do konta YouTube: OAuth 2.0 Device Authorization Grant (RFC 8628) – bez klawiatury na TV.
 * 1. [requestDeviceCode] → kod do wpisania na google.com/device,
 * 2. [awaitAuthorization] – pętla odpytywania (korutyna, anulowana razem z oknem),
 * 3. tokeny w [YouTubeTokens] (szyfrowane); [accessToken] odświeża je automatycznie.
 */
object YouTubeAuth {
    var deviceCodeUrl = "https://oauth2.googleapis.com/device/code"
    var tokenUrl = "https://oauth2.googleapis.com/token"
    var revokeUrl = "https://oauth2.googleapis.com/revoke"
    /** Tylko odczyt (subskrypcje, polubione) – zakres dozwolony dla klientów TV. */
    const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    private const val GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"
    /** Krok wydłużenia odpytywania po „slow_down” (RFC 8628: +5 s); testy – krócej. */
    var slowDownStepMs = 5_000L

    val clientId: String get() = Prefs.ytClientId.trim().ifBlank { BuildConfig.YT_CLIENT_ID }
    val clientSecret: String get() = Prefs.ytClientSecret.trim().ifBlank { BuildConfig.YT_CLIENT_SECRET }
    /** Czy jest klient OAuth (wbudowany lub wpisany w Ustawieniach). */
    val configured: Boolean get() = clientId.isNotBlank()

    data class DeviceCode(
        val deviceCode: String, val userCode: String, val verificationUrl: String,
        /** Wygaśnięcie kodu (ms, czas systemowy). */
        val expiresAt: Long, val intervalMs: Long,
    ) {
        fun remainingS(now: Long = System.currentTimeMillis()) = ((expiresAt - now) / 1000L).coerceAtLeast(0L)
    }

    /** Błąd logowania; [code] – kod OAuth (access_denied, expired_token, invalid_client, …). */
    class AuthException(val code: String, message: String) : Exception(message)

    private val refreshLock = Mutex()

    private fun post(url: String, form: Map<String, String>): Pair<Int, JSONObject> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return Repository.http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            r.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        }
    }

    private fun errorOf(j: JSONObject, http: Int) = AuthException(j.optString("error", "http_$http"), message(j.optString("error", ""), j.optString("error_description"), http))

    fun message(code: String, desc: String = "", http: Int = 0): String = when (code) {
        "access_denied" -> "Odmówiono dostępu do konta"
        "expired_token" -> "Kod wygasł – pobierz nowy"
        "invalid_client", "unauthorized_client" -> "Nieprawidłowy identyfikator klienta OAuth (wymagany typ „TV i urządzenia z ograniczonym wejściem”)"
        "invalid_grant" -> "Sesja wygasła – zaloguj się ponownie"
        "invalid_scope" -> "Klient nie ma dostępu do YouTube Data API v3"
        else -> desc.ifBlank { if (http > 0) "Błąd serwera Google (HTTP $http)" else "Błąd logowania" }
    }

    /** Krok 1: kod urządzenia i adres do wpisania kodu. */
    suspend fun requestDeviceCode(now: Long = System.currentTimeMillis()): DeviceCode = withContext(Dispatchers.IO) {
        if (!configured) throw AuthException("no_client", "Brak identyfikatora klienta OAuth (Ustawienia → Konto YouTube → Zaawansowane)")
        val (code, j) = post(deviceCodeUrl, mapOf("client_id" to clientId, "scope" to SCOPE))
        if (code !in 200..299 || !j.has("device_code")) throw errorOf(j, code)
        DeviceCode(
            deviceCode = j.getString("device_code"), userCode = j.getString("user_code"),
            verificationUrl = j.optString("verification_url").ifBlank { j.optString("verification_uri", "https://www.google.com/device") },
            expiresAt = now + j.optLong("expires_in", 1800L) * 1000L,
            intervalMs = j.optLong("interval", 5L).coerceAtLeast(1L) * 1000L,
        )
    }

    /**
     * Krok 2: odpytuje serwer co [DeviceCode.intervalMs], aż użytkownik potwierdzi (zapis tokenów, true).
     * authorization_pending → dalej; slow_down → dłuższy odstęp; access_denied / expired_token → [AuthException].
     * Przerywana anulowaniem korutyny (zamknięcie okna). Chwilowe błędy sieci nie przerywają oczekiwania.
     */
    suspend fun awaitAuthorization(dc: DeviceCode, clock: () -> Long = System::currentTimeMillis): Boolean {
        var interval = dc.intervalMs
        while (true) {
            delay(interval)
            coroutineContext.ensureActive()
            if (clock() >= dc.expiresAt) throw AuthException("expired_token", message("expired_token"))
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    post(tokenUrl, mapOf("client_id" to clientId, "client_secret" to clientSecret,
                        "device_code" to dc.deviceCode, "grant_type" to GRANT_DEVICE))
                }.getOrNull()
            } ?: continue   // brak sieci – spróbuj w kolejnym kroku
            val (code, j) = res
            if (code in 200..299 && j.has("access_token")) {
                YouTubeTokens.clear()
                YouTubeTokens.save(j.getString("access_token"), j.optString("refresh_token"), j.optLong("expires_in", 3600L), j.optString("scope"))
                return true
            }
            when (j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += slowDownStepMs
                else -> throw errorOf(j, code)
            }
        }
    }

    /** Ważny token dostępu (odświeżany, gdy wygasa) albo null – niezalogowany / sesja odwołana. */
    suspend fun accessToken(): String? {
        if (!YouTubeTokens.loggedIn) return null
        if (!YouTubeTokens.expired()) return YouTubeTokens.accessToken
        return if (refresh()) YouTubeTokens.accessToken else null
    }

    /**
     * Odświeża token dostępu. invalid_grant (odwołany dostęp, zmiana hasła) → wylogowanie.
     * [force] – także gdy bieżący token jeszcze nie wygasł (odpowiedź 401 z API).
     */
    suspend fun refresh(force: Boolean = false): Boolean = refreshLock.withLock {
        if (!force && YouTubeTokens.accessToken.isNotBlank() && !YouTubeTokens.expired()) return@withLock true
        val rt = YouTubeTokens.refreshToken
        if (rt.isBlank()) { YouTubeTokens.clear(); return@withLock false }
        val res = withContext(Dispatchers.IO) {
            runCatching { post(tokenUrl, mapOf("client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to rt, "grant_type" to "refresh_token")) }.getOrNull()
        } ?: return@withLock false   // brak sieci – bez wylogowania
        val (code, j) = res
        if (code in 200..299 && j.has("access_token")) {
            YouTubeTokens.save(j.getString("access_token"), j.optString("refresh_token"), j.optLong("expires_in", 3600L), j.optString("scope"))
            return@withLock true
        }
        if (j.optString("error") in setOf("invalid_grant", "unauthorized_client")) YouTubeTokens.clear()
        false
    }

    /**
     * Logowanie tokenem odświeżania (refresh token wygenerowany dla tego samego klienta OAuth, np. przeniesiony z innego
     * urządzenia): zapis i od razu próba odświeżenia – nieważny token jest usuwany ([AuthException]).
     */
    suspend fun loginWithRefreshToken(rt: String) {
        val t = rt.trim()
        if (t.isEmpty()) throw AuthException("invalid_grant", "Wpisz token odświeżania")
        YouTubeTokens.clear()
        YouTubeTokens.save("", t, 0)
        if (!refresh(force = true)) { YouTubeTokens.clear(); throw AuthException("invalid_grant", "Token nieprawidłowy, odwołany albo wydany dla innego klienta OAuth") }
    }

    /** Wylogowanie: odwołanie tokenu u Google (najlepiej jak się da) i usunięcie danych lokalnie. */
    suspend fun logout() {
        val t = YouTubeTokens.refreshToken.ifBlank { YouTubeTokens.accessToken }
        YouTubeTokens.clear()
        if (t.isNotBlank()) withContext(Dispatchers.IO) { runCatching { post(revokeUrl, mapOf("token" to t)) } }
    }
}

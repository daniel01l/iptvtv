package pl.iptv.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.iptv.shared.ActivationFormat
import pl.iptv.tv.BuildConfig

/**
 * Wersja zabezpieczona: aplikacja działa tylko na urządzeniach aktywowanych w D-iptv Admin.
 * Lista aktywacji (podpisana) jest pobierana z repozytorium; bez internetu aplikacja działa jeszcze [GRACE_MS] od ostatniego sprawdzenia.
 */
object Activation {
    enum class State { ACTIVE, PENDING, BLOCKED, OFFLINE, NO_CONSENT }

    /** Bez sprawdzenia aktywacji: wersja testowa 3 dni, pełna 30 dni. */
    val graceDays get() = if (fullEdition) 30 else 3
    val GRACE_MS get() = graceDays * 86_400_000L
    const val CHECK_EVERY_MS = 15 * 60_000L
    var listUrl = BuildConfig.ACT_URL
    var pubKey = BuildConfig.ACT_PUBKEY
    var relay = "https://ntfy.sh"
    /** W testach automatycznych wyłączone (chyba że test włączy je jawnie). */
    val enabled get() = (BuildConfig.ACTIVATION && android.os.Build.FINGERPRINT != "robolectric") || forceForTest
    /** Pełna wersja czyta pole „f” listy, testowa – „s”. */
    var fullEdition = BuildConfig.FULL_EDITION

    /** Tylko testy: włącza aktywację w wersji pełnej. */
    var forceForTest = false

    private lateinit var sp: SharedPreferences
    private lateinit var app: Context
    lateinit var code: String; private set
    private lateinit var model: String
    private lateinit var version: String

    @SuppressLint("HardwareIds")
    fun init(c: Context) {
        // osobne miejsce (nie w kopii ustawień): stan aktywacji nie przenosi się z kopią zapasową
        sp = c.getSharedPreferences("activation", Context.MODE_PRIVATE)
        app = c.applicationContext
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifBlank { "brak" }
        code = ActivationFormat.codeFromId(id)
        model = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
        version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    var consent: Boolean get() = sp.getBoolean("consent", false); set(v) = sp.edit().putBoolean("consent", v).apply()
    private var lastOk: Long get() = sp.getLong("last_ok", 0); set(v) = sp.edit().putLong("last_ok", v).apply()
    private var lastTs: Long get() = sp.getLong("last_ts", 0); set(v) = sp.edit().putLong("last_ts", v).apply()
    private var status: String get() = sp.getString("status", "").orEmpty(); set(v) = sp.edit().putString("status", v).apply()
    /** Losowy numer tej instalacji – administrator widzi, gdy jeden kod zgłasza się z dwóch instalacji. */
    private val install: String get() = sp.getString("install", null) ?: java.util.UUID.randomUUID().toString().take(8).also { sp.edit().putString("install", it).apply() }
    var lastError: String = ""; private set

    /** Klucz urządzenia (prywatny zostaje na urządzeniu) – administrator szyfruje nim swój kod przy „Dodaj do moich”. */
    private fun deviceKey(): Pair<String, String> {
        val priv = sp.getString("dpriv", null); val pub = sp.getString("dpub", null)
        if (priv != null && pub != null) return priv to pub
        return ActivationFormat.newKeyPair().also { sp.edit().putString("dpriv", it.first).putString("dpub", it.second).apply() }
    }

    /** „Moje urządzenie” w Adminie: kod administratora odszyfrowany z listy → raporty bez wpisywania kodu. */
    private fun applyAdminLink(entry: ActivationFormat.Entry?) {
        if (!fullEdition || BuildConfig.SECURE) return
        val sealed = entry?.admin.orEmpty()
        if (sealed.isEmpty()) {
            if (sp.getBoolean("adm_auto", false)) { Prefs.adminCode = ""; sp.edit().putBoolean("adm_auto", false).apply() }
            return
        }
        val code = runCatching { ActivationFormat.open(sealed, ActivationFormat.sharedKey(deviceKey().first, pubKey)) }.getOrNull() ?: return
        if (Prefs.adminCode != code) {
            Prefs.adminCode = code; Prefs.relayOn = true; Prefs.adminLastReport = 0
            sp.edit().putBoolean("adm_auto", true).apply()
            pl.iptv.tv.remote.RemoteRelay.start(app)
        }
    }

    /** Stan bez sieci (na podstawie ostatniego sprawdzenia). */
    fun state(now: Long = System.currentTimeMillis()): State = when {
        !enabled -> State.ACTIVE
        !consent -> State.NO_CONSENT
        status == ActivationFormat.BLOCKED -> State.BLOCKED
        status == ActivationFormat.ACTIVE && now - lastOk <= GRACE_MS -> State.ACTIVE
        status == ActivationFormat.ACTIVE -> State.OFFLINE
        else -> State.PENDING
    }

    val needsCheck get() = enabled && consent && System.currentTimeMillis() - sp.getLong("last_try", 0) > CHECK_EVERY_MS

    /**
     * (7.25) Ekran aktywacji odpytuje co [POLL_MS] – po zatwierdzeniu w D-iptv Admin aplikacja rusza po kilku sekundach
     * (dawniej co 30 s, a adres listy zmieniał się raz na minutę – CDN mógł oddawać starą kopię przez kilka minut).
     */
    const val POLL_MS = 2_500L
    /** (7.25) Zgłoszenie do administratora przy odpytywaniu – najwyżej co tyle (zmiana stanu – od razu). */
    const val POLL_CHECKIN_MS = 60_000L

    /** (7.25) Jedno sprawdzenie naraz (ekran aktywacji, pętla aplikacji, ekrany – dawniej równoległe zapisy stanu). */
    private val checkLock = kotlinx.coroutines.sync.Mutex()
    private val nonce = java.util.concurrent.atomic.AtomicLong()
    private var lastCheckinAt = 0L
    private var lastCheckinStatus: String? = null
    /** Liczba pobrań listy (testy). */
    @Volatile var fetches = 0; private set
    /** Ostatni pobrany adres listy (testy – unikalny, bez pamięci podręcznej). */
    @Volatile var lastUrl = ""; private set
    /** Testy: odpytywanie od nowa (zgłoszenie przy pierwszym sprawdzeniu). */
    fun resetPollForTest() { lastCheckinAt = 0; lastCheckinStatus = null; fetches = 0 }

    /** Adres listy unikalny przy każdym sprawdzeniu – omija pamięć podręczną CDN / pośredników. */
    fun freshUrl(now: Long = System.currentTimeMillis()): String =
        listUrl + (if ('?' in listUrl) "&" else "?") + "t=" + now + "-" + nonce.incrementAndGet()

    /** (7.25) Wpis dla dokładnie tego kodu, który pokazuje ekran ([code]) – klucz listy porównywany po [ActivationFormat.normalize]. */
    fun entryFor(entries: Map<String, ActivationFormat.Entry>, code: String = this.code): ActivationFormat.Entry? =
        entries[code] ?: ActivationFormat.normalize(code).let { c -> entries.entries.firstOrNull { ActivationFormat.normalize(it.key) == c }?.value }

    /**
     * Pobiera i sprawdza podpisaną listę; zgłasza się administratorowi (kod, wersja, model). [poll] – odpytywanie z ekranu
     * aktywacji: zgłoszenie najwyżej co [POLL_CHECKIN_MS] (albo przy zmianie stanu). Wynik zapisany trwale (`commit`) przed
     * zwróceniem – zamknięcie aplikacji tuż po aktywacji jej nie cofa.
     */
    suspend fun check(poll: Boolean = false): State = withContext(Dispatchers.IO) {
        if (!enabled || !consent) return@withContext state()
        checkLock.withLock {
            val now = System.currentTimeMillis()
            sp.edit().putLong("last_try", now).apply()
            runCatching {
                val url = freshUrl(now); lastUrl = url; fetches++
                val req = Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-cache, no-store, max-age=0").header("Pragma", "no-cache").build()
                val body = Repository.http.newCall(req).execute().use { r ->
                    if (r.code == 404) "" else { if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
                }
                if (body.isBlank()) { save("", lastTs); lastError = ""; return@runCatching }
                val (payload, sig) = ActivationFormat.unwrap(body)
                if (pubKey.isBlank() || !ActivationFormat.verify(payload, sig, pubKey)) error("Nieprawidłowy podpis listy aktywacji")
                val ts = ActivationFormat.timestamp(payload)
                if (ts < lastTs) error("Nieaktualna lista aktywacji")            // starsza lista nie może cofnąć blokady
                val entry = entryFor(ActivationFormat.entries(payload))
                val st = entry?.forEdition(fullEdition).orEmpty()
                save(st, ts)
                if (st == ActivationFormat.ACTIVE) applyAdminLink(entry)
                lastError = ""
            }.onFailure { lastError = if (it is java.io.IOException) "brak połączenia z internetem" else it.message ?: "błąd" }
            val st = status
            if (!poll || st != lastCheckinStatus || now - lastCheckinAt >= POLL_CHECKIN_MS) {
                lastCheckinAt = now; lastCheckinStatus = st
                runCatching { checkin() }
            }
            state()
        }
    }

    /** Stan z listy zapisany od razu na dysk (jedna transakcja). */
    private fun save(st: String, ts: Long) {
        sp.edit().putString("status", st).putLong("last_ts", ts).putLong("last_ok", System.currentTimeMillis()).commit()
    }

    /** Zgłoszenie do administratora: tylko kod, numer instalacji, wersja, model i czas. */
    private fun checkin() {
        if (pubKey.isBlank()) return
        val msg = JSONObject().put("code", code).put("inst", install).put("ver", version).put("model", model)
            .put("st", status).put("ed", if (fullEdition) "full" else "test").put("ts", System.currentTimeMillis())
            .apply { if (fullEdition && !BuildConfig.SECURE) put("dk", deviceKey().second) }.toString()
        val req = Request.Builder().url("$relay/${ActivationFormat.checkinTopic(pubKey)}").post(msg.toRequestBody("text/plain".toMediaType())).build()
        Repository.http.newCall(req).execute().close()
    }

    /** Co dokładnie wysyła aplikacja (Ustawienia → Prywatność). */
    fun describeSent(): String = "Kod urządzenia: $code\nNumer instalacji: $install\nWersja: $version\nModel: $model\nCzas sprawdzenia"
}

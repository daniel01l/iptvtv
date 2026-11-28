package pl.iptv.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import pl.iptv.tv.data.Prefs
import java.lang.ref.WeakReference

/**
 * (7.25) Przesyłanie na urządzenia w pobliżu (Chromecast / Android TV / Google TV z Google Cast) z odtwarzaczy Live TV, STube
 * i Stremio. Logika (widoczność przycisku, wybór urządzenia, przekazanie, błędy, powrót do odtwarzania lokalnego) jest tutaj,
 * a sama komunikacja z odbiornikiem – w [Backend] ([GoogleCastBackend]; testy podmieniają [backendFactory]).
 *
 * - Przycisk widoczny tylko, gdy w sieci jest co najmniej jedno urządzenie (albo trwa przesyłanie) i gdy pozwala na to
 *   ustawienie [Prefs.castButton] (Ustawienia → Odbieranie i przesyłanie). Bez Usług Google Play backend się nie uruchamia –
 *   przycisk nigdy się nie pokazuje, aplikacja działa jak dawniej.
 * - Odtwarzanie lokalne gra dalej aż do potwierdzenia startu na odbiorniku ([Result.Playing]) – dopiero wtedy jest
 *   wstrzymywane ([Local.pause]), więc dźwięk nie dubluje się, a przy błędzie nic nie trzeba wznawiać.
 * - Błąd odbiornika (nieobsługiwany format, brak dostępu do adresu), brak odpowiedzi w [CONNECT_TIMEOUT_MS], rozłączenie
 *   urządzenia – czytelny komunikat i powrót do odtwarzania na tym urządzeniu ([Local.resume], od pozycji z odbiornika).
 * - Live TV: przełączenie kanału w trakcie przesyłania wysyła nowy kanał na odbiornik ([switchMedia]).
 * Wszystkie wywołania – na głównym wątku (wymóg Cast SDK).
 */
object CastHub {
    enum class Kind { LIVE, STUBE, STREMIO }

    data class Device(val id: String, val name: String, val description: String = "")

    /** Co przesłać: [mime] null – z rozszerzenia adresu ([castMime]); [positionMs] – start (VOD). */
    data class Media(
        val url: String, val title: String, val subtitle: String = "", val mime: String? = null, val live: Boolean = false,
        val image: String = "", val positionMs: Long = 0, val headers: Map<String, String> = emptyMap(),
    )

    /** Odtwarzanie lokalne ekranu, z którego przesyłamy. */
    interface Local {
        fun pause()
        /** [positionMs] – pozycja z odbiornika (VOD; dla Live TV bez znaczenia). */
        fun resume(positionMs: Long)
        /** (7.28) Pilot Cast: następny / poprzedni (kanał Live TV, odcinek Stremio, pozycja kolejki STube); false – brak. */
        fun next(): Boolean = false
        fun previous(): Boolean = false
        /** (7.28) Lista do wyboru w pilocie (kanały, odcinki sezonu, kolejka) – wybór podmienia materiał w tej samej sesji. */
        fun items(): List<Item> = emptyList()
        val itemsTitle: String get() = "Lista"
    }

    /** (7.28) Pozycja listy pilota Cast. */
    class Item(val label: String, val current: Boolean = false, val pick: () -> Unit)

    /** (7.28) Ścieżka audio / napisów na odbiorniku ([Backend.tracks]). */
    data class Track(val id: Long, val name: String, val active: Boolean)
    const val TRACK_AUDIO = 1
    const val TRACK_TEXT = 2

    sealed class Result {
        data class Playing(val device: Device) : Result()
        data class Failed(val message: String) : Result()
        /** Sesja zakończona (rozłączenie, zatrzymanie z powiadomienia / innego telefonu, koniec materiału). */
        data class Ended(val positionMs: Long, val finished: Boolean) : Result()
    }

    interface Backend {
        /** false – przesyłanie niedostępne (brak Usług Google Play / SDK). */
        fun init(c: Context): Boolean
        fun startDiscovery(onDevices: (List<Device>) -> Unit)
        fun stopDiscovery()
        /** Połączenie (jeśli trzeba) i wczytanie [media]; wyniki – [cb] (także później: błąd odtwarzania, koniec sesji). */
        fun cast(device: Device, media: Media, mime: String, cb: (Result) -> Unit)
        fun stop()
        fun positionMs(): Long
        // (7.28) pilot Cast – domyślnie brak (backend bez sterowania)
        fun durationMs(): Long = 0L
        fun isPaused(): Boolean = false
        fun play() {}
        fun pause() {}
        fun seekTo(ms: Long) {}
        fun volume(): Float = 0.5f
        fun setVolume(v: Float) {}
        fun isMuted(): Boolean = false
        fun setMuted(m: Boolean) {}
        fun tracks(type: Int): List<Track> = emptyList()
        /** [id] null – wyłączone (napisy). */
        fun selectTrack(type: Int, id: Long?) {}
    }

    /** Materiał, którego nie da się przesłać (np. film YouTube tylko z osobnym obrazem i dźwiękiem) – [message] dla użytkownika. */
    class Unsupported(message: String) : Exception(message)

    const val CONNECT_TIMEOUT_MS = 20_000L
    const val BUTTON_TAG = "cast_button"
    const val BANNER_TAG = "cast_banner"
    const val PICKER_TITLE = "Przesyłaj na urządzenie"
    const val MENU_ENTRY = "Przesyłaj na urządzenie w pobliżu (Cast)"
    const val NO_DEVICES = "Brak urządzeń w pobliżu – telewizor (Chromecast / Google TV) musi być włączony i w tej samej sieci Wi-Fi"
    const val NOTHING_PLAYING = "Nic nie jest teraz odtwarzane"
    const val STOP_LABEL = "Zakończ przesyłanie (odtwarzaj tutaj)"
    const val BACK_LOCAL = "odtwarzanie wraca na to urządzenie"

    /** Testy podmieniają; domyślnie Google Cast (w Robolectric – brak). */
    @Volatile var backendFactory: () -> Backend? = {
        // (7.28) Google Cast i DLNA / UPnP razem (MultiCastBackend)
        if (android.os.Build.FINGERPRINT == "robolectric") null
        else MultiCastBackend(listOfNotNull(runCatching { GoogleCastBackend() }.getOrNull(), DlnaBackend()))
    }

    /** (7.28) Backend bieżącej sesji – pilot Cast ([CastRemotePanel]). */
    val control: Backend? get() = backend?.takeIf { session != null }
    /** (7.28) Odtwarzanie lokalne bieżącej sesji (następny / poprzedni / lista). */
    val sessionLocal: Local? get() = session?.local?.get()

    private val main = Handler(Looper.getMainLooper())
    private var backend: Backend? = null
    private var initDone = false
    /** Przesyłanie dostępne na tym urządzeniu (backend uruchomiony). */
    var ready = false; private set
    var devices: List<Device> = emptyList(); private set
    private var watchers = 0

    class Session(val kind: Kind, val device: Device, var media: Media, var local: WeakReference<Local>, var activity: WeakReference<Activity>) {
        var playing = false
        var localPaused = false
    }
    var session: Session? = null; private set
    /** Oczekujące połączenie (przed [Result.Playing]). */
    var pending: Session? = null; private set
    private var token = 0

    private val buttons = ArrayList<WeakReference<View>>()
    /** Ostatni komunikat (testy). */
    var lastMessage: String? = null; private set

    /** Przycisk widoczny: ustawienie włączone i są urządzenia w pobliżu (albo trwa przesyłanie – żeby dało się je zakończyć). */
    val visible: Boolean get() = Prefs.castButton && ready && (devices.isNotEmpty() || session != null)

    fun isCasting(kind: Kind) = session?.kind == kind
    val casting get() = session != null

    private fun ensureBackend(c: Context): Backend? {
        if (!initDone) {
            initDone = true
            backend = runCatching { backendFactory() }.getOrNull()
            ready = backend?.let { b -> runCatching { b.init(c.applicationContext) }.getOrDefault(false) } == true
            if (!ready) backend = null
        }
        return backend
    }

    /** Ekran odtwarzacza na pierwszym planie: wyszukiwanie urządzeń; trwające przesyłanie z tego rodzaju ekranu – podpięcie [local]. */
    fun attach(a: Activity, kind: Kind, local: Local) {
        val b = ensureBackend(a)
        if (b != null && Prefs.castButton) {
            if (watchers++ == 0) runCatching { b.startDiscovery { list -> main.post { onDevices(list) } } }
        }
        session?.takeIf { it.kind == kind }?.let { s ->
            s.local = WeakReference(local); s.activity = WeakReference(a)
            if (s.playing) { if (!s.localPaused) { runCatching { local.pause() } }; s.localPaused = true; showBanner(a, s) }
        }
        refresh()
    }

    fun detach(a: Activity) {
        if (watchers > 0 && --watchers == 0) runCatching { backend?.stopDiscovery() }
        removeBanner(a)
        buttons.removeAll { it.get().let { v -> v == null || v.context === a } }
    }

    private fun onDevices(list: List<Device>) {
        if (list == devices) return
        devices = list
        refresh()
    }

    /** Widok przycisku – widoczność pilnowana przez hub (zmiana listy urządzeń, ustawienia, przesyłania). */
    fun bind(v: View): View {
        buttons.removeAll { it.get() == null }
        if (buttons.none { it.get() === v }) buttons += WeakReference(v)
        v.visibility = if (visible) View.VISIBLE else View.GONE
        return v
    }

    fun refresh() {
        val show = visible
        buttons.removeAll { it.get() == null }
        buttons.forEach { it.get()?.let { v -> val want = if (show) View.VISIBLE else View.GONE; if (v.visibility != want) v.visibility = want } }
    }

    /** Ustawienie zmienione – wyszukiwanie wł./wył., przyciski odświeżone. */
    fun settingChanged(c: Context) {
        val b = ensureBackend(c)
        if (b != null && !Prefs.castButton && watchers > 0) { runCatching { b.stopDiscovery() }; watchers = 0; devices = emptyList() }
        refresh()
    }

    // ---------------- zgodność strumienia ----------------

    /** Powód, dla którego odbiornik na pewno nie odtworzy strumienia; null – można próbować. */
    fun unsupported(m: Media): String? {
        val u = m.url.trim()
        val scheme = u.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "Ten strumień (${scheme.ifBlank { "plik" }}) nie może być przesłany – odbiorniki Cast odtwarzają tylko adresy http(s)"
        val host = runCatching { android.net.Uri.parse(u).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]" || host == "0.0.0.0")
            return "Strumień pochodzi z serwera na tym urządzeniu – telewizor nie ma do niego dostępu"
        return null
    }

    /** Typ MIME dla odbiornika (Default Media Receiver). */
    fun castMime(m: Media): String {
        m.mime?.takeIf { it.isNotBlank() }?.let { return it }
        val lower = m.url.lowercase().substringBefore('?').substringBefore('#')
        val ext = lower.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            ext == "m3u8" || ext == "m3u" || lower.contains("/hls/") || m.url.lowercase().contains("output=m3u8") || m.url.lowercase().contains("output=hls") -> "application/x-mpegURL"
            ext == "mpd" -> "application/dash+xml"
            ext == "mp4" || ext == "m4v" || ext == "mov" -> "video/mp4"
            ext == "webm" -> "video/webm"
            ext == "mkv" -> "video/x-matroska"
            ext == "mp3" -> "audio/mpeg"
            ext == "aac" -> "audio/aac"
            ext == "m4a" -> "audio/mp4"
            ext == "ts" || m.live -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /** Wskazówka do komunikatu o błędzie odbiornika. */
    fun failureHint(m: Media): String {
        val mime = castMime(m)
        return when {
            mime == "video/mp2t" -> " Strumień MPEG-TS – odbiorniki Cast odtwarzają HLS (.m3u8), DASH i MP4."
            mime == "video/x-matroska" -> " Plik MKV – część odbiorników go nie obsługuje; wybierz źródło MP4."
            m.headers.isNotEmpty() -> " Strumień wymaga własnych nagłówków (User-Agent / Referer), których odbiornik nie wysyła."
            else -> ""
        }
    }

    // ---------------- przycisk ----------------

    /** Kliknięcie przycisku: wybór urządzenia albo okno trwającego przesyłania. [media] – bieżący materiał (null – nic nie gra). */
    fun click(a: Activity, kind: Kind, local: Local, media: () -> Media?) {
        val s = session
        if (s != null) {
            CardSheet.actions(a, "Przesyłanie na „${s.device.name}”", listOf(
                CardSheet.Action(STOP_LABEL, PlayerIcon.CAST, key = "cast:stop") { stopCasting() },
                CardSheet.Action("Zamknij", PlayerIcon.CLOSE, key = "cast:close") {},
            ), s.media.title)
            return
        }
        val b = ensureBackend(a)
        if (b == null) { message(a, "Przesyłanie niedostępne – urządzenie nie ma Usług Google Play"); return }
        if (devices.isEmpty()) { message(a, NO_DEVICES); return }
        val m = try { media() } catch (e: Unsupported) { message(a, e.message.orEmpty()); return } catch (e: Exception) { null }
        if (m == null) { message(a, NOTHING_PLAYING); return }
        unsupported(m)?.let { message(a, it); return }
        CardSheet.actions(a, PICKER_TITLE, devices.map { d ->
            CardSheet.Action(d.name, PlayerIcon.CAST, d.description, key = "cast:${d.id}") { start(a, kind, d, m, local) }
        }, m.title)
    }

    /** Przekazanie [m] na [d]; odtwarzanie lokalne gra do potwierdzenia startu na odbiorniku. */
    fun start(a: Activity, kind: Kind, d: Device, m: Media, local: Local) {
        val b = backend ?: run { message(a, "Przesyłanie niedostępne"); return }
        unsupported(m)?.let { message(a, it); return }
        val s = Session(kind, d, m, WeakReference(local), WeakReference(a))
        pending = s
        val my = ++token
        message(a, "Łączenie z „${d.name}”…")
        main.postDelayed({ if (token == my && pending === s) { runCatching { b.stop() }; fail(s, "Urządzenie „${d.name}” nie odpowiada") } }, CONNECT_TIMEOUT_MS)
        try {
            b.cast(d, CastProxy.wrap(m), castMime(m)) { r -> main.post { if (token == my) onResult(s, r) } }
        } catch (e: Exception) {
            fail(s, "Nie udało się połączyć z „${d.name}”")
        }
    }

    /** Live TV: przełączenie kanału w trakcie przesyłania – nowy kanał na odbiorniku. */
    fun switchMedia(m: Media) {
        val s = session ?: return
        if (s.media.url == m.url) return
        val b = backend ?: return
        val a = s.activity.get()
        unsupported(m)?.let { r -> a?.let { message(it, r) }; return }
        s.media = m
        val my = ++token
        try { b.cast(s.device, CastProxy.wrap(m), castMime(m)) { r -> main.post { if (token == my) onResult(s, r) } } }
        catch (e: Exception) { fail(s, "Nie udało się przesłać kanału") }
    }

    // ---------------- (7.28) surowy .ts przez Google Cast → DLNA tego samego TV ----------------

    /** Podpowiedź pilota: „Brak dźwięku na kanale TV? Przełącz na tryb DLNA”. */
    const val DLNA_HINT = "Brak dźwięku na kanale TV? Przełącz na tryb DLNA"
    private const val DLNA_PREFIX = "dlna:"

    private fun norm(n: String) = n.lowercase().replace("[tv]", "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * Odbiornik DLNA tego samego telewizora dla bieżącej sesji – tylko Live TV, Google Cast i surowy MPEG-TS (bez manifestu HLS
     * z wyborem ścieżki AAC – E-AC3 / MP2 bywają bez dźwięku w Default Media Receiver). Dopasowanie po nazwie (równa / zawarta);
     * jedyny odbiornik DLNA przy jedynym urządzeniu Cast – ten sam TV. null – brak.
     */
    fun dlnaAlternative(): Device? {
        val s = session ?: return null
        if (s.kind != Kind.LIVE || s.device.id.startsWith(DLNA_PREFIX) || castMime(s.media) != "video/mp2t") return null
        val dlna = devices.filter { it.id.startsWith(DLNA_PREFIX) }
        if (dlna.isEmpty()) return null
        val cn = norm(s.device.name)
        dlna.firstOrNull { d -> val n = norm(d.name); n.isNotEmpty() && cn.isNotEmpty() && (n == cn || n.contains(cn) || cn.contains(n)) }?.let { return it }
        val casts = devices.count { !it.id.startsWith(DLNA_PREFIX) }
        return if (dlna.size == 1 && casts <= 1) dlna.first() else null
    }

    /**
     * Przeniesienie aktywnej sesji na [d] (DLNA) z tym samym kanałem – bez wznawiania odtwarzania lokalnego: sesja Google Cast
     * zamykana po cichu (spóźnione wywołania odrzucane przez [token]), nowa zaczyna się od razu; niepowodzenie – jak zwykły błąd
     * (odtwarzanie wraca na to urządzenie).
     */
    fun moveTo(d: Device) {
        val s = session ?: return
        val a = s.activity.get() ?: return
        val l = s.local.get() ?: return
        token++
        runCatching { backend?.stop() }
        session = null
        start(a, s.kind, d, s.media, l)
        pending?.localPaused = s.localPaused
    }

    private fun onResult(s: Session, r: Result) {
        when (r) {
            is Result.Playing -> {
                if (pending === s) pending = null
                session = s; s.playing = true
                if (!s.localPaused) { s.local.get()?.let { l -> runCatching { l.pause() } }; s.localPaused = true }
                s.activity.get()?.let { a -> showBanner(a, s); message(a, "▶ Przesyłanie na „${r.device.name}”"); CastProxyService.start(a) }
                refresh()
            }
            is Result.Failed -> fail(s, r.message + failureHint(s.media))
            is Result.Ended -> {
                if (session !== s && pending !== s) return
                // (7.28) koniec materiału na odbiorniku: STube – następne wideo z kolejki, Stremio – następny odcinek, w tej samej sesji
                if (r.finished && session === s && s.kind != Kind.LIVE && runCatching { s.local.get()?.next() == true }.getOrDefault(false)) return
                finish(s, r.positionMs, if (r.finished) "Przesyłanie zakończone" else "Przesyłanie przerwane – $BACK_LOCAL")
            }
        }
    }

    private fun fail(s: Session, msg: String) {
        if (session !== s && pending !== s) return
        finish(s, runCatching { backend?.positionMs() ?: 0L }.getOrDefault(0L).takeIf { it > 0 } ?: s.media.positionMs, "✖ $msg – $BACK_LOCAL")
    }

    /** Koniec sesji: odtwarzanie lokalne wznowione (gdy było wstrzymane), komunikat, przyciski / baner odświeżone. */
    private fun finish(s: Session, positionMs: Long, msg: String) {
        token++
        if (pending === s) pending = null
        if (session === s) session = null
        val a = s.activity.get()
        a?.let { removeBanner(it) }
        CastRemotePanel.dismissAll(); CastProxyService.stop(); CastProxy.stop()   // (7.28) usługa, proxy i pilot – tylko na czas sesji
        if (s.localPaused) { s.localPaused = false; s.local.get()?.let { l -> runCatching { l.resume(if (s.media.live) 0 else positionMs) } } }
        if (a != null && !a.isFinishing) message(a, msg) else lastMessage = msg
        refresh()
    }

    /** Zakończenie przesyłania przez użytkownika – odbiornik zatrzymany, odtwarzanie wraca tutaj od tej samej pozycji. */
    fun stopCasting() {
        val s = session ?: pending ?: return
        val pos = runCatching { backend?.positionMs() ?: 0L }.getOrDefault(0L)
        runCatching { backend?.stop() }
        finish(s, if (pos > 0) pos else s.media.positionMs, "Przesyłanie zakończone – $BACK_LOCAL")
    }

    private fun message(a: Activity, msg: String) {
        lastMessage = msg
        runCatching { I18n.makeToast(a, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------------- baner „Przesyłanie na …” ----------------

    private fun showBanner(a: Activity, s: Session) {
        // (7.28) pilot Cast (sterowanie, lista, ścieżki); w testach Robolectric – dawny baner
        if (CastProxy.enabled && !TvResolution.isTv(a)) { CastRemotePanel.show(a, expanded = true); return }
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<TextView>(BANNER_TAG)
        val tv = existing ?: TextView(a).apply {
            tag = BANNER_TAG; textSize = 15f; setTextColor(Color.WHITE); isFocusable = true; isClickable = true; maxLines = 2
            gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dp(10)
            setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(18), Ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = Ui.dp(22).toFloat(); setColor(0xE6101420.toInt()); setStroke(Ui.dp(1), 0x55FFFFFF) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(PlayerIcon(PlayerIcon.CAST_ON), null, null, null)
            setOnClickListener { stopCasting() }
            content.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(28) })
        }
        tv.text = "Przesyłanie na „${s.device.name}”: ${s.media.title}\nDotknij / OK – $STOP_LABEL"
    }

    private fun removeBanner(a: Activity) {
        val content = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        content.findViewWithTag<View>(BANNER_TAG)?.let { content.removeView(it) }
    }

    /** Baner widoczny na ekranie (testy). */
    fun bannerOn(a: Activity) = a.findViewById<FrameLayout>(android.R.id.content)?.findViewWithTag<View>(BANNER_TAG) != null

    /** (7.28) Każdy ekran aplikacji na pierwszym planie w trakcie sesji: zminimalizowany pilot Cast (można chodzić po aplikacji). */
    fun onActivityResumed(a: Activity) {
        if (session == null || !CastProxy.enabled) return
        if (a.isFinishing || TvResolution.isTv(a)) return          // TV: dawny baner (pilot – na telefonie)
        CastRemotePanel.show(a, expanded = false)
    }

    /** Testy: stan od nowa. */
    fun resetForTest() {
        token++; session = null; pending = null; devices = emptyList(); watchers = 0; buttons.clear()
        backend = null; initDone = false; ready = false; lastMessage = null
    }
}

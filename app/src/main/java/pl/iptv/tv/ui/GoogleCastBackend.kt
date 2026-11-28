package pl.iptv.tv.ui

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage

/**
 * (7.25) Konfiguracja Google Cast: odbiornik domyślny (Default Media Receiver – bez własnej aplikacji odbiornika), powiadomienie
 * z przyciskiem zatrzymania otwiera aplikację. Wskazany w manifeście (`OPTIONS_PROVIDER_CLASS_NAME`).
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(c: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(CastMediaOptions.Builder()
            .setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName(pl.iptv.tv.ui.MainActivity::class.java.name).build())
            .build())
        .build()

    override fun getAdditionalSessionProviders(c: Context): List<SessionProvider>? = null
}

/**
 * (7.25) [CastHub.Backend] na Google Cast SDK: urządzenia z MediaRouter (kategoria odbiornika domyślnego), wybór trasy uruchamia
 * sesję (SessionManager), potem `RemoteMediaClient.load`. Stan odbiornika (błąd / koniec / rozłączenie) wraca do [CastHub].
 */
class GoogleCastBackend : CastHub.Backend {
    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var onDevices: ((List<CastHub.Device>) -> Unit)? = null
    private var cb: ((CastHub.Result) -> Unit)? = null
    private var device: CastHub.Device? = null
    /** Wczytanie czeka na sesję. */
    private var pendingLoad: (() -> Unit)? = null
    private var client: RemoteMediaClient? = null
    private var lastPos = 0L
    private var loading = false

    override fun init(c: Context): Boolean {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) != ConnectionResult.SUCCESS) return false
        @Suppress("DEPRECATION") val ctx = CastContext.getSharedInstance(c)
        cast = ctx
        router = MediaRouter.getInstance(c)
        selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()
        ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        return true
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }

    private fun castRoutes(): List<MediaRouter.RouteInfo> {
        val r = router ?: return emptyList(); val s = selector ?: return emptyList()
        return r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(s) && !it.isBluetooth }
    }

    private fun publish() {
        onDevices?.invoke(castRoutes().map { CastHub.Device(it.id, it.name, it.description.orEmpty()) })
    }

    override fun startDiscovery(onDevices: (List<CastHub.Device>) -> Unit) {
        this.onDevices = onDevices
        val r = router ?: return; val s = selector ?: return
        r.addCallback(s, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        publish()
    }

    override fun stopDiscovery() {
        router?.removeCallback(routes)
        onDevices = null
    }

    override fun cast(device: CastHub.Device, media: CastHub.Media, mime: String, cb: (CastHub.Result) -> Unit) {
        this.cb = cb
        val ctx = cast ?: run { cb(CastHub.Result.Failed("Przesyłanie niedostępne")); return }
        val current = ctx.sessionManager.currentCastSession
        val load = { load(device, media, mime) }
        if (current != null && current.isConnected && this.device?.id == device.id) { load(); return }
        val route = castRoutes().firstOrNull { it.id == device.id }
            ?: run { cb(CastHub.Result.Failed("Urządzenie „${device.name}” jest już niedostępne")); return }
        this.device = device
        pendingLoad = load
        router?.selectRoute(route)
    }

    private fun load(device: CastHub.Device, media: CastHub.Media, mime: String) {
        val s = cast?.sessionManager?.currentCastSession
        val c = s?.remoteMediaClient ?: run { cb?.invoke(CastHub.Result.Failed("Nie udało się połączyć z „${device.name}”")); return }
        if (client !== c) { client?.unregisterCallback(status); c.registerCallback(status); client = c }
        val meta = MediaMetadata(if (media.live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            if (media.subtitle.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, media.subtitle)
            if (media.image.startsWith("http")) addImage(WebImage(Uri.parse(media.image)))
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(if (media.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime).setMetadata(meta).build()
        val req = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .apply { if (!media.live && media.positionMs > 0) setCurrentTime(media.positionMs) }.build()
        loading = true
        c.load(req).setResultCallback { r ->
            loading = false
            if (r.status.isSuccess) { pickAudio = true; activateDefaultAudio(); cb?.invoke(CastHub.Result.Playing(device)) }
            else cb?.invoke(CastHub.Result.Failed("„${device.name}” nie może odtworzyć tego strumienia"))
        }
    }

    private val status = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val c = client ?: return
            if (pickAudio) activateDefaultAudio()
            if (c.playerState == MediaStatus.PLAYER_STATE_PLAYING || c.playerState == MediaStatus.PLAYER_STATE_PAUSED) lastPos = c.approximateStreamPosition
            if (c.playerState != MediaStatus.PLAYER_STATE_IDLE || loading) return
            when (c.idleReason) {
                MediaStatus.IDLE_REASON_ERROR -> cb?.invoke(CastHub.Result.Failed("„${device?.name.orEmpty()}” nie może odtworzyć tego strumienia"))
                MediaStatus.IDLE_REASON_FINISHED -> cb?.invoke(CastHub.Result.Ended(lastPos, finished = true))
                else -> {}   // INTERRUPTED (nowy kanał) / CANCELED (zatrzymanie) – obsłużone osobno
            }
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { pendingLoad?.invoke(); pendingLoad = null }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { pendingLoad?.invoke(); pendingLoad = null }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            if (pendingLoad != null) { pendingLoad = null; cb?.invoke(CastHub.Result.Failed("Nie udało się połączyć z „${device?.name.orEmpty()}”")) }
        }
        override fun onSessionEnded(s: CastSession, error: Int) {
            client?.unregisterCallback(status); client = null
            cb?.invoke(CastHub.Result.Ended(lastPos, finished = false))
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionEnding(s: CastSession) { lastPos = client?.approximateStreamPosition ?: lastPos }
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
    }

    override fun stop() {
        lastPos = client?.approximateStreamPosition ?: lastPos
        pendingLoad = null
        cast?.sessionManager?.endCurrentSession(true)
        router?.let { r -> runCatching { r.unselect(MediaRouter.UNSELECT_REASON_STOPPED) } }
    }

    override fun positionMs(): Long = client?.approximateStreamPosition?.takeIf { it > 0 } ?: lastPos

    // ---------------- (7.28) pilot Cast ----------------

    /** Po wczytaniu: jawnie aktywna ścieżka audio (AAC, jeśli jest – AC3 / E-AC3 / MP2 często bez dźwięku na odbiorniku); raz na materiał. */
    @Volatile private var pickAudio = false

    private fun activateDefaultAudio() {
        val c = client ?: return
        val tracks = c.mediaInfo?.mediaTracks?.filter { it.type == com.google.android.gms.cast.MediaTrack.TYPE_AUDIO }.orEmpty()
        if (tracks.isEmpty()) return
        pickAudio = false
        val active = c.mediaStatus?.activeTrackIds?.toSet().orEmpty()
        if (tracks.any { it.id in active }) return
        val pick = tracks.firstOrNull { (it.contentType.orEmpty() + it.name.orEmpty()).contains("mp4a", true) || it.name.orEmpty().contains("aac", true) } ?: tracks.first()
        val texts = c.mediaStatus?.activeTrackIds?.filter { id -> tracks.none { it.id == id } }.orEmpty()
        runCatching { c.setActiveMediaTracks((texts + pick.id).toLongArray()) }
    }

    override fun durationMs(): Long = client?.streamDuration?.takeIf { it > 0 } ?: 0L
    override fun isPaused(): Boolean = client?.isPaused == true
    override fun play() { runCatching { client?.play() } }
    override fun pause() { runCatching { client?.pause() } }
    override fun seekTo(ms: Long) {
        runCatching { client?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(ms).build()) }
    }
    private val castSession get() = cast?.sessionManager?.currentCastSession
    override fun volume(): Float = runCatching { castSession?.volume?.toFloat() }.getOrNull() ?: 0.5f
    override fun setVolume(v: Float) { runCatching { castSession?.volume = v.toDouble().coerceIn(0.0, 1.0) } }
    override fun isMuted(): Boolean = runCatching { castSession?.isMute }.getOrNull() == true
    override fun setMuted(m: Boolean) { runCatching { castSession?.isMute = m } }

    override fun tracks(type: Int): List<CastHub.Track> {
        val c = client ?: return emptyList()
        val t = if (type == CastHub.TRACK_AUDIO) com.google.android.gms.cast.MediaTrack.TYPE_AUDIO else com.google.android.gms.cast.MediaTrack.TYPE_TEXT
        val active = c.mediaStatus?.activeTrackIds?.toSet().orEmpty()
        return c.mediaInfo?.mediaTracks?.filter { it.type == t }.orEmpty().mapIndexed { i, m ->
            CastHub.Track(m.id, m.name?.takeIf { it.isNotBlank() } ?: m.language?.takeIf { it.isNotBlank() } ?: "Ścieżka ${i + 1}", m.id in active)
        }
    }

    override fun selectTrack(type: Int, id: Long?) {
        val c = client ?: return
        val audio = com.google.android.gms.cast.MediaTrack.TYPE_AUDIO
        val all = c.mediaInfo?.mediaTracks.orEmpty()
        val wantType = if (type == CastHub.TRACK_AUDIO) audio else com.google.android.gms.cast.MediaTrack.TYPE_TEXT
        val keep = c.mediaStatus?.activeTrackIds?.filter { a -> all.firstOrNull { it.id == a }?.type != wantType }.orEmpty()
        runCatching { c.setActiveMediaTracks((keep + listOfNotNull(id)).toLongArray()) }
    }
}

package pl.iptv.tv.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.StubeHistory
import pl.iptv.tv.data.YtVideo

/**
 * Odtwarzanie STube w tle: odtwarzacz przekazany z [YtPlayerActivity] (HOME / inna aplikacja) gra dalej w usłudze pierwszoplanowej
 * z sesją multimediów (powiadomienie z przyciskami, sterowanie pilotem / Asystentem). Powrót do odtwarzacza odbiera odtwarzacz
 * ([take]) bez przerwy w dźwięku. W tle działa też SponsorBlock i zapis postępu.
 */
object YtBackground {
    @Volatile var player: ExoPlayer? = null; private set
    @Volatile var video: YtVideo? = null; private set
    var eq: YtEqSession? = null; private set
    private var segments: List<SbSegment> = emptyList()
    private var playable: pl.iptv.tv.data.YtPlayable? = null

    /** Odtwarzacz odebrany przez ekran: z korektorem, danymi filmu (rozdziały, propozycje) i segmentami SponsorBlock. */
    class Taken(val player: ExoPlayer, val eq: YtEqSession?, val playable: pl.iptv.tv.data.YtPlayable?, val segments: List<SbSegment>)
    private val main = Handler(Looper.getMainLooper())
    val active get() = player != null

    fun holds(videoId: String) = player != null && video?.id == videoId

    /** Czy odtwarzacz w tym stanie może grać w tle (gra lub buforuje – usługa od razu pokazuje powiadomienie). */
    fun canHandOff(p: Player) = p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)

    /** Przejęcie odtwarzacza (wątek główny) i start usługi pierwszoplanowej. */
    fun handOff(c: Context, p: ExoPlayer, v: YtVideo, eqSession: YtEqSession?, sb: List<SbSegment>, what: pl.iptv.tv.data.YtPlayable? = null) {
        stop()
        player = p; video = v; eq = eqSession; segments = sb; playable = what
        p.addListener(listener)
        main.post(tick)
        ContextCompat.startForegroundService(c, Intent(c, YtPlaybackService::class.java))
    }

    /** Odbiór odtwarzacza przez ekran (powrót); usługa kończy się, odtwarzacz gra dalej. */
    fun take(c: Context): Taken? {
        val p = player ?: return null
        val t = Taken(p, eq, playable, segments)
        detach()
        c.stopService(Intent(c, YtPlaybackService::class.java))
        return t
    }

    /** Zatrzymanie gry w tle: pozycja do historii, odtwarzacz i korektor zwolnione. */
    fun stop(c: Context? = null) {
        val p = player ?: return
        save(p)
        val e = eq
        detach()
        runCatching { p.release() }; e?.release()
        c?.stopService(Intent(c, YtPlaybackService::class.java))
    }

    private fun detach() {
        player?.removeListener(listener)
        main.removeCallbacks(tick)
        player = null; eq = null; segments = emptyList(); playable = null
    }

    private fun save(p: Player) {
        val v = video ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (p.isCurrentMediaItemLive) return
        StubeHistory.savePosition(v, if (p.playbackState == Player.STATE_ENDED) dur else p.currentPosition, dur)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) player?.let { save(it) } }
    }

    /** Co 0,5 s: SponsorBlock; co ~10 s: zapis postępu. */
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                SponsorBlock.at(segments, p.currentPosition)?.let { seg -> if (p.duration == C.TIME_UNSET || seg.endMs < p.duration - 1000) p.seekTo(seg.endMs) }
                if (++ticks % 20 == 0) save(p)
            }
            main.postDelayed(this, 500)
        }
    }
}

class YtPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = YtBackground.player ?: run { stopSelf(); return }
        val v = YtBackground.video
        // tytuł, kanał i miniatura w powiadomieniu (źródło strumienia nie niesie metadanych)
        val meta = MediaMetadata.Builder().setTitle(v?.title).setArtist(v?.channel)
            .apply { v?.thumb?.takeIf { it.isNotBlank() }?.let { setArtworkUri(android.net.Uri.parse(it)) } }.build()
        val wrapped = object : ForwardingPlayer(p) { override fun getMediaMetadata() = meta }
        val open = PendingIntent.getActivity(this, 0,
            (v?.let { YtPlayerActivity.intent(this, it) } ?: Intent(this, STubeActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, wrapped).setSessionActivity(open).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Aplikacja usunięta z ostatnich – koniec gry w tle. */
    override fun onTaskRemoved(rootIntent: Intent?) { YtBackground.stop(); stopSelf() }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }
}

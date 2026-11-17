package pl.iptv.tv.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import pl.iptv.tv.data.YtVideo

/**
 * Usługa odtwarzania STube (MediaSessionService): trzyma odtwarzacz [YtEngine] i sesję multimediów – powiadomienie z przyciskami,
 * sterowanie z systemu / Asystenta, gra w tle bez żadnego ekranu. Usługa sama przechodzi na pierwszy plan, gdy film gra.
 * Polecenia własne (wczytanie filmu, tło, stop) – tylko dla kontrolerów tej aplikacji.
 */
class YtPlaybackService : MediaSessionService() {
    companion object {
        const val CMD_LOAD = "pl.iptv.stube.LOAD"
        const val CMD_BACKGROUND = "pl.iptv.stube.BACKGROUND"
        const val CMD_STOP = "pl.iptv.stube.STOP"
        const val ARG_ID = "id"; const val ARG_TITLE = "title"; const val ARG_CHANNEL = "channel"; const val ARG_THUMB = "thumb"
        const val ARG_FROM_START = "from_start"; const val ARG_ON = "on"

        fun loadArgs(v: YtVideo, fromStart: Boolean) = Bundle().apply {
            putString(ARG_ID, v.id); putString(ARG_TITLE, v.title); putString(ARG_CHANNEL, v.channel); putString(ARG_THUMB, v.thumb)
            putBoolean(ARG_FROM_START, fromStart)
        }
        fun videoOf(b: Bundle) = YtVideo(b.getString(ARG_ID).orEmpty(), b.getString(ARG_TITLE).orEmpty(), b.getString(ARG_CHANNEL).orEmpty(), b.getString(ARG_THUMB).orEmpty())
        private val COMMANDS = listOf(CMD_LOAD, CMD_BACKGROUND, CMD_STOP)
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = YtEngine.ensurePlayer(this)
        // tytuł, kanał i miniatura w powiadomieniu / kontrolerach (źródło strumienia nie niesie metadanych)
        val forwarding = object : ForwardingPlayer(exo) { override fun getMediaMetadata() = YtEngine.metadata() }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, YtPlayerActivity::class.java).putExtra(YtPlayerActivity.EXTRA_RESUME_CURRENT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, forwarding).setSessionActivity(open).setCallback(callback).build()
        YtEngine.onStopRequest = { stopSelf() }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // polecenia własne wyłącznie dla tej aplikacji (usługa jest eksportowana dla kontrolerów systemu)
            if (controller.packageName != packageName) return super.onConnect(session, controller)
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .apply { COMMANDS.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LOAD -> YtEngine.load(this@YtPlaybackService, videoOf(args), fromStart = args.getBoolean(ARG_FROM_START))
                CMD_BACKGROUND -> YtEngine.setBackground(args.getBoolean(ARG_ON))
                CMD_STOP -> YtEngine.stop()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Aplikacja usunięta z ostatnich, a nic nie gra – koniec usługi (grający film zostaje, jak w YouTube). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (YtEngine.player?.playWhenReady != true) { YtEngine.stop(); stopSelf() }
    }

    override fun onDestroy() {
        YtEngine.onStopRequest = null
        session?.release(); session = null
        YtEngine.releasePlayer()
        super.onDestroy()
    }
}

/**
 * Klient ekranu: odtwarzacz to [MediaController] połączony z [YtPlaybackService] – ekran nigdy nie posiada ExoPlayera,
 * więc zwolnienie klienta (WSTECZ, HOME) nie zatrzymuje dźwięku.
 */
interface YtClient {
    val player: Player
    fun load(v: YtVideo, fromStart: Boolean)
    fun setBackground(on: Boolean)
    fun stop()
    /** Odłączenie ekranu (kontroler zwolniony, odtwarzanie trwa). */
    fun release()
}

object YtClients {
    /**
     * Testy Robolectric (bez wiązania usług): klient bezpośrednio na [YtEngine]. W aplikacji – zawsze MediaController.
     */
    @Volatile var direct: Boolean = runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess

    fun connect(c: Context, onReady: (YtClient) -> Unit): ListenableFuture<MediaController>? {
        if (direct) { onReady(DirectClient(c.applicationContext)); return null }
        val token = SessionToken(c, ComponentName(c, YtPlaybackService::class.java))
        val f = MediaController.Builder(c, token).buildAsync()
        f.addListener({ runCatching { f.get() }.getOrNull()?.let { onReady(ControllerClient(it)) } }, ContextCompat.getMainExecutor(c))
        return f
    }

    private class ControllerClient(private val c: MediaController) : YtClient {
        override val player: Player get() = c
        override fun load(v: YtVideo, fromStart: Boolean) { c.sendCustomCommand(SessionCommand(YtPlaybackService.CMD_LOAD, Bundle.EMPTY), YtPlaybackService.loadArgs(v, fromStart)) }
        override fun setBackground(on: Boolean) { c.sendCustomCommand(SessionCommand(YtPlaybackService.CMD_BACKGROUND, Bundle.EMPTY), Bundle().apply { putBoolean(YtPlaybackService.ARG_ON, on) }) }
        override fun stop() { c.sendCustomCommand(SessionCommand(YtPlaybackService.CMD_STOP, Bundle.EMPTY), Bundle.EMPTY) }
        override fun release() = c.release()
    }

    private class DirectClient(private val ctx: Context) : YtClient {
        override val player: Player get() = YtEngine.ensurePlayer(ctx)
        override fun load(v: YtVideo, fromStart: Boolean) = YtEngine.load(ctx, v, fromStart)
        override fun setBackground(on: Boolean) = YtEngine.setBackground(on)
        override fun stop() = YtEngine.stop()
        override fun release() {}
    }
}

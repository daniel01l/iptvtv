package pl.iptv.tv.ui

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** Odtwarzanie nagrania z pliku (przewijanie paskiem sterowania). */
class RecordingPlayerActivity : AppCompatActivity() {
    companion object { const val EXTRA_FILE = "file" }
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(PlayerView(this).also { it.id = android.R.id.content + 1 })
    }

    override fun onStart() {
        super.onStart()
        Playback.player?.pause()          // nie odtwarzaj równocześnie kanału
        val f = File(intent.getStringExtra(EXTRA_FILE) ?: return finish())
        val pv = findViewById<PlayerView>(android.R.id.content + 1)
        player = ExoPlayer.Builder(this).build().also { p ->
            pv.player = p; p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); p.prepare(); p.playWhenReady = true
        }
        pv.showController()
    }

    /** Pilot: strzałki/OK/klawisze multimedialne trafiają do paska odtwarzacza. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        findViewById<PlayerView>(android.R.id.content + 1)?.dispatchKeyEvent(event) == true || super.dispatchKeyEvent(event)

    override fun onStop() { super.onStop(); player?.release(); player = null }
}

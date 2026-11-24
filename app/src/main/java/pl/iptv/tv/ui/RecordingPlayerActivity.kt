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
    /** (7.20) Siatka interfejsu TV 960 × 540 dp (1920 × 1080) niezależnie od DPI systemu – [pl.iptv.tv.ui.TvResolution]. */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(pl.iptv.tv.ui.TvResolution.wrap(newBase)) }
    override fun applyOverrideConfiguration(o: android.content.res.Configuration?) { super.applyOverrideConfiguration(pl.iptv.tv.ui.TvResolution.adjust(this, o)) }

    companion object { const val EXTRA_FILE = "file" }
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        pl.iptv.tv.ui.TvResolution.applyMetrics(resources); pl.iptv.tv.ui.TvResolution.applyMetrics(applicationContext.resources)   // (7.20) siatka TV 960 × 540 dp
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

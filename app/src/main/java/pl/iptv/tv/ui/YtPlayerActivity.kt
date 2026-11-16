package pl.iptv.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.SbSegment
import pl.iptv.tv.data.SponsorBlock
import pl.iptv.tv.data.YtPlayable
import pl.iptv.tv.data.YtStreamException
import pl.iptv.tv.data.YtStreams

/**
 * Wbudowany odtwarzacz STube (Media3): strumienie YouTube bez reklam ([YtStreams]), automatyczne pomijanie
 * segmentów SponsorBlock, sterowanie pilotem. Gdy film nie da się odtworzyć (blokada, błąd strumienia, brak odpowiedzi) –
 * od razu okno: „Odtwórz w SmartTube”, „Odtwórz w YouTube”, „Zamknij”.
 */
class YtPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "id"; const val EXTRA_TITLE = "title"
        /** Bez gotowości odtwarzania po tylu ms od startu – strumień uznany za zablokowany. */
        const val START_TIMEOUT_MS = 25_000L
        const val SEEK_STEP_MS = 10_000L

        fun intent(c: Context, id: String, title: String = "") =
            Intent(c, YtPlayerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)
    }

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var skipLabel: TextView
    var player: ExoPlayer? = null; private set
    /** Okno awaryjne (testy). */
    var fallbackDialog: AlertDialog? = null; private set
    var segments: List<SbSegment> = emptyList(); private set
    /** Ostatnio pominięty segment (testy, komunikat). */
    var lastSkipped: SbSegment? = null; private set

    private val videoId get() = intent.getStringExtra(EXTRA_ID).orEmpty()
    private val videoUrl get() = "https://www.youtube.com/watch?v=$videoId"
    private var loadJob: Job? = null
    private var reExtracted = false
    private var resumeAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // kanał IPTV (podgląd / tło menu) zatrzymany – dwa odtwarzacze naraz to brak dekoderów na wielu TV
        Playback.stop()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 4000; setShowNextButton(false); setShowPreviousButton(false)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(16))
            text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { "$it\n\nWczytywanie…" } ?: "Wczytywanie…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // komunikat SponsorBlock – duży, w rogu, czytelny z kanapy
        skipLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE; setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10))
            background = GradientDrawable().apply { setColor(0xD0101010.toInt()); cornerRadius = Ui.dp(22).toFloat(); setStroke(Ui.dp(2), Ui.accent) }
        }
        root.addView(skipLabel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, Ui.dp(40), Ui.dp(96)) })
        setContentView(root)
        resumeAt = savedInstanceState?.getLong("pos") ?: 0L
        if (videoId.isBlank()) { finish(); return }
    }

    override fun onStart() { super.onStart(); if (player == null && fallbackDialog == null) start() }

    override fun onStop() {
        super.onStop()
        // TV: HOME / inna aplikacja – odtwarzacz i dekodery zwolnione (powrót wznawia od tej samej chwili)
        player?.let { resumeAt = it.currentPosition }
        release()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putLong("pos", player?.currentPosition ?: resumeAt) }

    private fun start() {
        loadJob?.cancel()
        status.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            val sb = if (Prefs.sbEnabled) async { SponsorBlock.segments(videoId) } else null
            val playable = try { YtStreams.resolve(videoId) } catch (e: YtStreamException) { showFallback(e.message ?: "Film niedostępny"); return@launch }
            catch (e: Exception) { showFallback(YtStreams.message(e)); return@launch }
            if (isFinishing || isDestroyed) return@launch
            if (playable !is YtPlayable.Live) segments = sb?.await().orEmpty()
            play(playable)
        }
    }

    private fun play(p: YtPlayable) {
        // ekran zakryty w trakcie pobierania adresów (HOME) – bez dźwięku w tle; onStart uruchomi film od nowa
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        release()
        val exo = ExoPlayer.Builder(this, renderers()).build()
        player = exo
        playerView.player = exo
        exo.addListener(listener)
        exo.setMediaSource(YtMedia.source(this, p))
        if (resumeAt > 0 && p !is YtPlayable.Live) exo.seekTo(resumeAt)
        exo.playWhenReady = true
        exo.prepare()
        status.text = p.title.ifBlank { "Wczytywanie…" } + "\n\nWczytywanie…"
        handler.removeCallbacks(startTimeout); handler.postDelayed(startTimeout, START_TIMEOUT_MS)
        handler.removeCallbacks(sbTick); handler.postDelayed(sbTick, 500)
    }

    /** Dekodery jak w odtwarzaczu kanałów (ustawienia silnika, zapasowy dekoder, tryb zgodności). */
    private fun renderers() = androidx.media3.exoplayer.DefaultRenderersFactory(applicationContext).apply {
        setMediaCodecSelector(Playback.codecSelector(software = Playback.softwareDecoding))
        setEnableDecoderFallback(Prefs.decoderFallback || Playback.softwareDecoding)
        setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { handler.removeCallbacks(startTimeout); status.visibility = View.GONE }
            if (state == Player.STATE_ENDED) finish()
        }
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("YtPlayer", "błąd ${error.errorCodeName}", error)
            val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            // adres strumienia wygasł lub został odrzucony (403/410) – raz ponowna ekstrakcja od tej samej chwili
            if (!reExtracted && (http == 403 || http == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
                reExtracted = true; resumeAt = player?.currentPosition ?: resumeAt
                release(); start(); return
            }
            showFallback(errorMessage(error, http))
        }
    }

    private val startTimeout = Runnable {
        if (player?.playbackState != Player.STATE_READY) showFallback("Strumień nie odpowiada (YouTube mógł zablokować odtwarzanie w tej aplikacji)")
    }

    /** SponsorBlock: segment pod bieżącą pozycją → przeskok na jego koniec i komunikat. */
    private val sbTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying || p.playbackState == Player.STATE_BUFFERING) skipIfNeeded(p.currentPosition)
            handler.postDelayed(this, 500)
        }
    }

    /** Pominięcie segmentu w [posMs] (wywoływane co 0,5 s i w testach). */
    fun skipIfNeeded(posMs: Long): Boolean {
        val p = player ?: return false
        val seg = SponsorBlock.at(segments, posMs) ?: return false
        val dur = p.duration
        // segment do samego końca filmu – koniec odtwarzania zamiast przeskoku w pustkę
        if (dur != C.TIME_UNSET && seg.endMs >= dur - 1000) { lastSkipped = seg; finish(); return true }
        p.seekTo(seg.endMs)
        lastSkipped = seg
        skipLabel.text = "⏭  Pominięto: ${SponsorBlock.label(seg.category)}"
        skipLabel.visibility = View.VISIBLE
        handler.removeCallbacks(hideSkip); handler.postDelayed(hideSkip, 3000)
        return true
    }
    private val hideSkip = Runnable { skipLabel.visibility = View.GONE }

    fun errorMessage(e: PlaybackException, http: Int = 0): String = when {
        http == 403 || http == 410 -> "YouTube odrzucił strumień (HTTP $http)"
        http == 429 -> "YouTube ograniczył odtwarzanie (zbyt wiele zapytań)"
        e.errorCode in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) ->
            "Urządzenie nie obsługuje formatu tego filmu"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Brak połączenia z serwerem wideo YouTube"
        else -> "Błąd odtwarzania (${e.errorCodeName})"
    }

    /** Okno awaryjne – czytelne na TV, fokus od razu na „Odtwórz w SmartTube”. */
    fun showFallback(reason: String) {
        if (isFinishing || isDestroyed || fallbackDialog?.isShowing == true) return
        handler.removeCallbacks(startTimeout)
        resumeAt = player?.currentPosition?.takeIf { it > 0 } ?: resumeAt
        release()
        status.visibility = View.GONE
        // kolejność przycisków na TV (układ pionowy): SmartTube → YouTube → Zamknij
        val d = Ui.dialog(this).setTitle("Nie można odtworzyć filmu")
            .setMessage("$reason\n\nWybierz inny sposób odtwarzania:")
            .setPositiveButton("Odtwórz w SmartTube") { _, _ ->
                if (SmartTubeIntegrationHelper.play(this, videoUrl) { finish() }) finish()
            }
            .setNegativeButton("Odtwórz w YouTube") { _, _ -> SmartTubeIntegrationHelper.playInYouTube(this, videoUrl); finish() }
            .setNeutralButton("Zamknij") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        fallbackDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------------- pilot ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (event.action == KeyEvent.ACTION_DOWN && p != null && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                // pasek ukryty: ◄► przewija o 10 s (jak w aplikacjach YouTube na TV), OK – pauza/wznowienie
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!p.isCurrentMediaItemLive) p.seekTo(p.currentPosition + SEEK_STEP_MS); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { p.playWhenReady = !p.playWhenReady; playerView.showController(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible && p != null) {
            playerView.hideController(); return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun release() {
        handler.removeCallbacks(startTimeout); handler.removeCallbacks(sbTick)
        player?.let { it.removeListener(listener); runCatching { it.release() } }
        player = null
        playerView.player = null
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); loadJob?.cancel() }
}

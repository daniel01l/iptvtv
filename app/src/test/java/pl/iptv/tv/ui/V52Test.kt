package pl.iptv.tv.ui

import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import pl.iptv.tv.data.Channel

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class V52Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun ch(n: Int) = Channel(key = "k$n", name = "K$n", url = "http://127.0.0.1:9/$n.ts", group = "G")

    @Test fun failingChannelDoesNotHurtNextOne() {
        Playback.stop()
        var fatal = 0
        Playback.onFatal = { fatal++ }
        Playback.play(ctx, ch(1), ch(1).url)
        // błąd dostawcy: ponowienie zaplanowane
        Playback.simulateError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertTrue(Playback.retryPending)
        // przełączenie kanału anuluje ponowienie poprzedniego (nie „wskoczy” w nowy kanał)
        Playback.play(ctx, ch(2), ch(2).url)
        assertFalse(Playback.retryPending)
        // błąd dostawcy: 2 próby, potem komunikat i zwolnienie dekoderów
        repeat(3) { Playback.simulateError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) }
        assertEquals(1, fatal)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, Playback.player!!.playbackState)
        // ten sam adres po błędzie uruchamia się od nowa (nie zostaje „martwy”)
        assertTrue(Playback.play(ctx, ch(2), ch(2).url))
        // błąd dekodera – od razu (bez ponowień, które na słabym sprzęcie blokują dekoder)
        Playback.simulateError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(2, fatal); assertFalse(Playback.retryPending)
        Playback.onFatal = null; Playback.stop()
    }

    @Test fun fastZapStartsOnlyLastChannel() {
        pl.iptv.tv.data.Prefs.permissionsAsked = true; pl.iptv.tv.data.Prefs.setupDone = true; pl.iptv.tv.data.Prefs.keyMapJson = ""
        val f = java.io.File(ctx.filesDir, "v52.m3u").apply { writeText((1..10).joinToString("", "#EXTM3U\n") { "#EXTINF:-1,K$it\nhttp://v52/$it\n" }) }
        pl.iptv.tv.data.Repository.resetAllEdits(); kotlinx.coroutines.runBlocking { pl.iptv.tv.data.Repository.loadPlaylist(f.absolutePath) }
        val chs = pl.iptv.tv.data.Repository.channels
        val c = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java, android.content.Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, chs[5].key).putExtra(PlayerActivity.EXTRA_GROUP, pl.iptv.tv.data.Repository.ALL)).setup()
        val l = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()); l.idleFor(java.time.Duration.ofMillis(300))
        val a = c.get()
        fun up() { a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP)); a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP)); l.idleFor(java.time.Duration.ofMillis(100)) }
        up(); up(); up()
        assertEquals(chs[5].key, Playback.channelKey)                    // kanały pośrednie nie są uruchamiane
        l.idleFor(java.time.Duration.ofMillis(600))
        assertEquals(chs[4].key == Playback.channelKey || chs[6].key == Playback.channelKey, false)
        assertTrue(Playback.channelKey == chs[2].key || Playback.channelKey == chs[8].key)   // trzy kroki od K6
        c.pause().stop().destroy(); Playback.stop()
    }
}

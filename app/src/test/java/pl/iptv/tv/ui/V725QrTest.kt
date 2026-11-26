package pl.iptv.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pl.iptv.tv.remote.RemoteServer

/**
 * 7.25 – kod QR (odbiór list, klucz TMDB, sterowanie STube) prowadzi do adresu w sieci Wi-Fi / Ethernet, a nie do adresu
 * sieci komórkowej / VPN / hotspotu (dawniej pierwszy adres z systemu); kod z marginesem wg normy odczytuje się do tego
 * samego adresu, który jest wypisany pod nim.
 */
@RunWith(AndroidJUnit4::class)
class V725QrTest {
    @Test fun wifiAddressGoesFirst() {
        assertEquals("192.168.1.20", RemoteServer.rankIps(listOf("rmnet_data0" to "10.84.12.7", "tun0" to "10.8.0.2", "wlan0" to "192.168.1.20")).first())
        assertEquals("192.168.0.5", RemoteServer.rankIps(listOf("ccmni1" to "100.70.1.2", "ap0" to "192.168.43.1", "eth0" to "192.168.0.5")).first())
        assertEquals("10.0.0.9", RemoteServer.rankIps(listOf("wlan0" to "169.254.3.3", "wlan1" to "10.0.0.9")).first())
        assertEquals(listOf("192.168.1.20", "10.84.12.7"), RemoteServer.rankIps(listOf("rmnet0" to "10.84.12.7", "wlan0" to "192.168.1.20", "wlan0" to "192.168.1.20")))
    }

    @Test fun qrDecodesToExactUrlWithStandardQuietZone() {
        val url = "http://192.168.1.20:${RemoteServer.PORT}/tmdb"
        val b = QrCode.bitmap(url, 280)
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val src = com.google.zxing.RGBLuminanceSource(w, h, px)
        val r = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(src)))
        assertEquals(url, r.text)
        // margines: 4 moduły bieli dookoła
        val m = QrCode.matrix(url)
        for (i in 0 until QrCode.QUIET_ZONE) for (j in 0 until m.width) { assertFalse(m.get(i, j)); assertFalse(m.get(j, i)) }
        val img = QrCode.image(androidx.test.core.app.ApplicationProvider.getApplicationContext(), url, 140)
        assertFalse((img.drawable as android.graphics.drawable.BitmapDrawable).paint.isFilterBitmap)
    }
}

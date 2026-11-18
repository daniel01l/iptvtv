package pl.iptv.tv.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Kod QR generowany lokalnie (ZXing core – bez sieci i bez zewnętrznych usług): czarne moduły na białym tle,
 * margines 2 moduły (czytelny z ekranu TV także w ciemnym pokoju). Bitmapa w rozmiarze widoku – bez skalowania przy rysowaniu.
 */
object QrCode {
    fun matrix(text: String): com.google.zxing.common.BitMatrix =
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))

    fun bitmap(text: String, sizePx: Int): Bitmap {
        val m = matrix(text)
        val n = m.width
        val scale = maxOf(1, sizePx / n)
        val px = IntArray(n * n) { i -> if (m.get(i % n, i / n)) Color.BLACK else Color.WHITE }
        val small = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        // całkowita wielokrotność modułu – ostre krawędzie (bez filtrowania)
        return Bitmap.createScaledBitmap(small, n * scale, n * scale, false).also { if (it !== small) small.recycle() }
    }
}

package pl.iptv.tv.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Kod QR generowany lokalnie (ZXing core – bez sieci i bez zewnętrznych usług): czarne moduły na białym tle,
 * margines 4 moduły – [QUIET_ZONE] (czytelny z ekranu TV także w ciemnym pokoju). Bitmapa w rozmiarze widoku – bez skalowania przy rysowaniu.
 */
object QrCode {
    /** (7.25) Margines wg normy QR (4 moduły) – przy 2 modułach na ciemnym panelu część aparatów telefonów nie odczytywała kodu. */
    const val QUIET_ZONE = 4

    fun matrix(text: String): com.google.zxing.common.BitMatrix =
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to QUIET_ZONE, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))

    fun bitmap(text: String, sizePx: Int): Bitmap {
        val m = matrix(text)
        val n = m.width
        val scale = maxOf(1, sizePx / n)
        val px = IntArray(n * n) { i -> if (m.get(i % n, i / n)) Color.BLACK else Color.WHITE }
        val small = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        // całkowita wielokrotność modułu – ostre krawędzie (bez filtrowania)
        return Bitmap.createScaledBitmap(small, n * scale, n * scale, false).also { if (it !== small) small.recycle() }
    }

    /** (7.21) Kod QR do zeskanowania telefonem (np. adres strony aplikacji w sieci lokalnej) – kwadrat [sizeDp], zaokrąglony. */
    fun image(c: android.content.Context, text: String, sizeDp: Int = 150) = android.widget.ImageView(c).apply {
        tag = "keepcolor nozoom"; contentDescription = "Kod QR: $text"; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        // ostre moduły także przy skalowaniu widoku (bez wygładzania krawędzi)
        runCatching { setImageDrawable(android.graphics.drawable.BitmapDrawable(c.resources, bitmap(text, Ui.dp(sizeDp))).apply { isFilterBitmap = false; setAntiAlias(false) }) }
        layoutParams = android.view.ViewGroup.LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp))
    }
}

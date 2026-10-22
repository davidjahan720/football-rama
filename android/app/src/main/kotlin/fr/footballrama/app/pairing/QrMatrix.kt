package fr.footballrama.app.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Modules d'un QR code (true = noir), zone de silence de 4 modules incluse. Pur JVM (ZXing core). */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    /** Pixels ARGB (noir sur blanc, 1 pixel par module) pour Bitmap.createBitmap. */
    fun toArgb(): IntArray = IntArray(size * size) { if (modules[it]) BLACK else WHITE }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        fun encode(text: String): QrMatrix {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            )
            // Largeur/hauteur 0 : taille minimale, 1 pixel par module.
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            require(m.width == m.height)
            val size = m.width
            return QrMatrix(size, BooleanArray(size * size) { m.get(it % size, it / size) })
        }
    }
}

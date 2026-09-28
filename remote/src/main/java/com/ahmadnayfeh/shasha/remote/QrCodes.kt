package com.ahmadnayfeh.shasha.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
    /**
     * QR code as rows of cells (`matrix[y][x]`, true = dark), square, with a 2-cell quiet zone.
     * [sizeHint] 0 gives one cell per module, the best input for drawing in Compose; a larger value
     * asks zxing to scale the code up to at least that many cells.
     */
    fun matrix(text: String, sizeHint: Int = 0): Array<BooleanArray> {
        require(text.isNotEmpty()) { "Nothing to encode" }
        val size = sizeHint.coerceAtLeast(0)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
    }
}

package tv.frostline.feature.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR modules for [text]; true = dark module. No quiet zone (the caller draws it). */
fun qrModules(text: String): Array<BooleanArray> {
    val hints = mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
}

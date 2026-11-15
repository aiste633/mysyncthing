package eu.stedb.mysyncthing.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

fun qrCode(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else -1 }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The text of the QR code in a greyscale picture, or null when none can be read. [luma] holds
 * one byte per pixel in rows of [rowStride] bytes, as the Y plane of a camera picture does; it
 * may be longer than the picture.
 */
fun readQrCode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? = try {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)))).text
} catch (_: Exception) {
    // A ReaderException: no code, or one that does not check out. Anything else is the reader
    // stumbling over a picture, and a camera picture must never crash the app: the next one is tried.
    null
}

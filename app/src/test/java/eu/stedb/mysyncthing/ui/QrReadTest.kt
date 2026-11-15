package eu.stedb.mysyncthing.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [readQrCode], the scanner's reader, on pictures as a camera hands them over: brightness
 * bytes in rows that may be longer than the picture is wide. The codes come from the writer
 * behind Show ID. What the camera adds (blur, tilt, light) is the manual scan's part.
 */
class QrReadTest {
    /** The one device ID tests may hold (scripts/check-leaks.sh). */
    private val id = Fixtures.laptop

    private class Picture(val luma: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** [text] as a QR code, [scale] pixels per module, each row followed by [padding] bytes that are no picture. */
    private fun picture(text: String, scale: Int = 4, padding: Int = 0): Picture {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val side = code.width * scale
        val stride = side + padding
        val luma = ByteArray(stride * side) { i ->
            val x = i % stride
            if (x >= side) 0x55 else if (code[x / scale, i / stride / scale]) 0 else -1
        }
        return Picture(luma, side, side, stride)
    }

    private fun read(p: Picture) = readQrCode(p.luma, p.width, p.height, p.rowStride)

    @Test
    fun readsADeviceId() = assertEquals(id, read(picture(id)))

    @Test
    fun rowsLongerThanThePictureAreSkippedOver() = assertEquals(id, read(picture(id, padding = 13)))

    @Test
    fun aBufferLongerThanThePictureIsFine() {
        val p = picture(id)
        assertEquals(id, readQrCode(p.luma + ByteArray(999), p.width, p.height, p.rowStride))
    }

    @Test
    fun readsWhatShowIdWrites() = forAll({ chars("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567- abcxyz019:/.?=", 1, 300) to nextInt(1, 4) }, runs = 300) { (text, scale) ->
        assertEquals(text, read(picture(text, scale)))
    }

    @Test
    fun noiseIsNoCodeAndNeverThrows() = forAll({ nextInt(1, 97).let { side -> Picture(nextBytes(side * side), side, side, side) } }, runs = 2_000) { p ->
        assertNull(read(p))
    }

    @Test
    fun aPictureCutShortIsNoCodeAndNeverThrows() {
        val p = picture(id)
        for (height in listOf(0, 1, p.height / 2)) assertNull(readQrCode(p.luma, p.width, height, p.rowStride))
        assertNull(readQrCode(ByteArray(0), 0, 0))
    }
}

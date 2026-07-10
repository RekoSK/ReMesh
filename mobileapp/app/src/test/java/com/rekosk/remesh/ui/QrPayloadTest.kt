package com.rekosk.remesh.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.rekosk.remesh.data.MeshRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share screen turns a `meshcore://contact/add` link into a QR code. If the
 * code does not scan back to exactly that link, the feature is silently useless,
 * so encode it and read it back rather than trusting a screenshot.
 */
class QrPayloadTest {

    /** Renders a BitMatrix as greyscale so the reader can consume it. */
    private class MatrixLuminanceSource(private val matrix: BitMatrix) :
        LuminanceSource(matrix.width, matrix.height) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (matrix[x, y]) 0 else 0xFF.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[y * width + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
                }
            }
            return out
        }
    }

    private fun roundTrip(text: String): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 720, 720, hints)
        val bitmap = BinaryBitmap(HybridBinarizer(MatrixLuminanceSource(matrix)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `contact uri survives a qr encode and decode`() {
        val key = ByteArray(32) { it.toByte() }
        val uri = MeshRepository.contactUri("Reko", key, advType = 1)
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun `a long percent-encoded name still fits and round trips`() {
        val key = ByteArray(32) { (255 - it).toByte() }
        val uri = MeshRepository.contactUri("Reko DIY ESP32C6 #2 & friends", key, advType = 2)
        assertEquals(uri, roundTrip(uri))
    }
}

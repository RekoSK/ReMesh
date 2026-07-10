package com.rekosk.remesh.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val QR_SIZE_PX = 720

/**
 * Renders [text] as a QR bitmap, or null if it will not fit in one.
 *
 * Medium error correction and a one-module quiet zone: enough redundancy to survive
 * being photographed off a screen, without inflating a `meshcore://` link into an
 * unreadably dense grid.
 */
fun encodeQr(text: String): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}.getOrNull()

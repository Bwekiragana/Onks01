package com.kuc.onks.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder

object QrHelper {
    fun generate(content: String, size: Int = 480): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        return BarcodeEncoder().createBitmap(matrix)
    }
}

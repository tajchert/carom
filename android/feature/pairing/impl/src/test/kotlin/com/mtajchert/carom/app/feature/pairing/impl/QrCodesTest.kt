package com.mtajchert.carom.app.feature.pairing.impl

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrCodesTest {
    private val uri = "carom://pair?v=1&h=https%3A%2F%2Fhub.example.com&c=AAAAAAAAAAAAAAAAAAAAAA&s=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun `the bitmap scales every module and reads back as the same text`() {
        val matrix = QrCodes.encode(uri)
        val bitmap = QrCodes.toBitmap(matrix, scale = 4)
        assertEquals(matrix.width * 4, bitmap.width)
        // Finder pattern corner is dark, and the bitmap is dark-on-white.
        assertEquals(Color.BLACK, bitmap.getPixel(0, 0))

        // Add a white quiet zone like the composable does, then decode.
        val quiet = 16
        val size = bitmap.width + quiet * 2
        val pixels = IntArray(size * size) { Color.WHITE }
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) pixels[(y + quiet) * size + x + quiet] = bitmap.getPixel(x, y)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
        assertEquals(uri, decoded.text)
    }
}

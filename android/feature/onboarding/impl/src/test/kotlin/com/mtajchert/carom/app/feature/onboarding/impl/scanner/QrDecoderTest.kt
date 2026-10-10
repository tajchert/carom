package com.mtajchert.carom.app.feature.onboarding.impl.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val uri = "carom://setup?v=1&h=https%3A%2F%2Fcarom-hub.example.workers.dev&t=0123456789abcdef0123456789abcdef"

    /** A camera-like Y plane: the code in the middle of a grey frame with padded rows. */
    private fun frame(text: String, width: Int = 640, height: Int = 480, rowStride: Int = 704): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300, mapOf(EncodeHintType.MARGIN to 2))
        val y = ByteArray(rowStride * height) { 0x80.toByte() }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (row in 0 until matrix.height) for (col in 0 until matrix.width) {
            y[(top + row) * rowStride + left + col] = if (matrix[col, row]) 0x10 else 0xF0.toByte()
        }
        return y
    }

    @Test
    fun `reads a code from a frame with padded rows`() {
        assertEquals(uri, QrDecoder().decode(frame(uri), rowStride = 704, width = 640, height = 480))
    }

    @Test
    fun `a frame without a code yields nothing, and the decoder keeps working`() {
        val decoder = QrDecoder()
        assertNull(decoder.decode(ByteArray(704 * 480) { 0x80.toByte() }, 704, 640, 480))
        assertEquals(uri, decoder.decode(frame(uri), 704, 640, 480))
    }
}

package com.mtajchert.carom.app.feature.pairing.impl

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** zxing core → bitmap. One QR module is [PIXELS_PER_MODULE] pixels so scaling stays sharp. */
internal object QrCodes {
    const val PIXELS_PER_MODULE = 8

    /** The smallest matrix for [text], without a quiet zone (the composable draws one). */
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /** Dark modules on white, always: scanners expect dark-on-light even in dark theme. */
    fun toBitmap(matrix: BitMatrix, scale: Int = PIXELS_PER_MODULE): Bitmap {
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val my = y / scale
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x / scale, my]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}

/** A QR code for [content] on a white tile with a quiet zone. Never log [content]. */
@Composable
internal fun QrCodeImage(
    content: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { QrCodes.toBitmap(QrCodes.encode(content)).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(20.dp),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

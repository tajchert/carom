package com.mtajchert.carom.app.feature.notifications.impl.media

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Scales album art to the large-icon cap (192 px) and encodes it as WebP. */
object ArtEncoder {
    const val MAX_SIDE = 192
    private const val QUALITY = 80

    /** The size that fits [width] x [height] into [max] on the longest side. Never upsizes. */
    fun fit(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val scale = max.toDouble() / longest
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** Null when the bitmap is empty or the platform could not encode it. */
    fun encode(src: Bitmap): EncodedArt? {
        val (w, h) = fit(src.width, src.height)
        if (w == 0 || h == 0) return null
        val software = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            src
        }
        val scaled = if (w == software.width && h == software.height) software else Bitmap.createScaledBitmap(software, w, h, true)
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val ok = scaled.compress(format, QUALITY, out)
        if (scaled !== software) scaled.recycle()
        return if (ok && out.size() > 0) EncodedArt(out.toByteArray(), w, h) else null
    }
}

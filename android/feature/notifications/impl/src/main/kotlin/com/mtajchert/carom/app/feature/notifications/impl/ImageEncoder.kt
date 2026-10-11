package com.mtajchert.carom.app.feature.notifications.impl

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.mtajchert.carom.app.core.common.di.CaromDispatchers
import com.mtajchert.carom.app.core.common.di.Dispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns the images a notification references into encoded WebP, right away (content-URI grants end with the notification). */
fun interface ImageLoader {
    suspend fun load(sources: Map<ImageSlot, ImageSource>): Map<ImageSlot, EncodedImage>

    companion object {
        val None: ImageLoader = ImageLoader { emptyMap() }
    }
}

fun interface WebpCompressor {
    /** WebP bytes of [bitmap], or null when the platform refuses. [lossless] keeps exact alpha (masks). */
    fun compress(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray?
}

/** Size limits per image kind (V2 spec §5.1). [maxEdge] bounds the width when [byWidth], else the longer side. */
internal data class ImageSpec(val maxEdge: Int, val byWidth: Boolean, val maxBytes: Int, val mask: Boolean)

internal fun ImageSlot.spec(): ImageSpec = when (this) {
    ImageSlot.AppIcon -> ImageSpec(128, false, 64 * 1024, false)
    ImageSlot.SmallIcon -> ImageSpec(96, false, 32 * 1024, true)
    ImageSlot.LargeIcon, ImageSlot.ConversationIcon, ImageSlot.CallPersonIcon, is ImageSlot.SenderIcon ->
        ImageSpec(192, false, 128 * 1024, false)
    ImageSlot.TrackerIcon, ImageSlot.StartIcon, ImageSlot.EndIcon -> ImageSpec(96, false, 64 * 1024, false)
    ImageSlot.Picture -> ImageSpec(1080, true, 400 * 1024, false)
    is ImageSlot.MessageImage -> ImageSpec(540, true, 150 * 1024, false)
}

@Singleton
class ImageEncoder internal constructor(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val compressor: WebpCompressor,
    private val clock: () -> Long,
) : ImageLoader {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Dispatcher(CaromDispatchers.IO) io: CoroutineDispatcher,
    ) : this(context, io, AndroidWebpCompressor, System::currentTimeMillis)

    private class Cached(val image: EncodedImage, val at: Long)

    // App and small icons repeat on every notification of an app; decoding and encoding them each time would be wasted work.
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_MAX
    }

    override suspend fun load(sources: Map<ImageSlot, ImageSource>): Map<ImageSlot, EncodedImage> =
        withContext(io) {
            val out = LinkedHashMap<ImageSlot, EncodedImage>()
            for ((slot, source) in sources) {
                ensureActive()
                encode(slot, source)?.let { out[slot] = it }
            }
            out
        }

    /** Null when the image cannot be loaded or never fits its cap. Never throws for a bad image. */
    internal fun encode(slot: ImageSlot, source: ImageSource): EncodedImage? {
        val key = cacheKey(slot, source)
        val now = clock()
        if (key != null) {
            synchronized(cache) { cache[key] }?.takeIf { now - it.at in 0L until CACHE_TTL_MS }?.let { return it.image }
        }
        val spec = slot.spec()
        val bitmap = try {
            render(source, spec)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        val image = fit(bitmap, spec) ?: return null
        if (key != null) synchronized(cache) { cache[key] = Cached(image, now) }
        return image
    }

    internal fun cacheKey(slot: ImageSlot, source: ImageSource): String? = when {
        slot == ImageSlot.AppIcon && source is ImageSource.FromApp -> "app:${source.packageName}"
        slot == ImageSlot.SmallIcon && source is ImageSource.FromIcon && source.icon.type == Icon.TYPE_RESOURCE ->
            "small:${source.icon.resPackage}:${source.icon.resId}"
        else -> null
    }

    private fun render(source: ImageSource, spec: ImageSpec): Bitmap? {
        val raw: Bitmap? = when (source) {
            is ImageSource.FromBitmap -> software(source.bitmap)?.let { scaled(it, spec) }
            is ImageSource.FromIcon -> fromDrawable(source.icon.loadDrawable(context), spec)
            is ImageSource.FromUri -> decodeUri(source.uri, spec)
            is ImageSource.FromApp -> fromDrawable(appDrawable(source), spec)
        }
        return if (raw != null && spec.mask) toMask(raw) else raw
    }

    private fun appDrawable(source: ImageSource.FromApp): Drawable? {
        val pm = context.packageManager
        return try {
            pm.getApplicationIcon(source.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // The extras' ApplicationInfo, or our own when the lookup is for this app (not always resolvable by name).
            (source.info ?: context.applicationInfo.takeIf { it.packageName == source.packageName })?.loadIcon(pm)
        }
    }

    private fun fromDrawable(drawable: Drawable?, spec: ImageSpec): Bitmap? {
        drawable ?: return null
        if (drawable is BitmapDrawable) return software(drawable.bitmap)?.let { scaled(it, spec) }
        val (w, h) = targetSize(drawable.intrinsicWidth, drawable.intrinsicHeight, spec)
        return drawable.toBitmap(w, h)
    }

    /** Decodes straight to the target size, so a 12 MP picture never exists in memory at full size. */
    private fun decodeUri(uri: Uri, spec: ImageSpec): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val (w, h) = targetSize(info.size.width, info.size.height, spec)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** A hardware bitmap cannot be drawn or scaled on the CPU; copy it first. */
    private fun software(b: Bitmap): Bitmap? =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    private fun scaled(b: Bitmap, spec: ImageSpec): Bitmap {
        val (w, h) = targetSize(b.width, b.height, spec)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** Fits [w] x [h] under the cap, keeping the aspect ratio and never upscaling. */
    private fun targetSize(w: Int, h: Int, spec: ImageSpec): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return spec.maxEdge to spec.maxEdge // a drawable without an intrinsic size
        val bound = if (spec.byWidth) w else max(w, h)
        val scale = min(1f, spec.maxEdge.toFloat() / bound)
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    /** White where the icon has alpha: the Mac tints it with the notification colour. */
    private fun toMask(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /** Steps the quality down, then shrinks by a quarter and repeats, until the bytes fit [ImageSpec.maxBytes]. */
    private fun fit(source: Bitmap, spec: ImageSpec): EncodedImage? {
        var bitmap = source
        val qualities = if (spec.mask) MASK_QUALITIES else QUALITIES
        repeat(MAX_SHRINKS + 1) {
            for (quality in qualities) {
                val bytes = compressor.compress(bitmap, quality, lossless = spec.mask) ?: continue
                if (bytes.size <= spec.maxBytes) return EncodedImage(bytes, bitmap.width, bitmap.height)
            }
            val w = (bitmap.width * SHRINK).roundToInt()
            val h = (bitmap.height * SHRINK).roundToInt()
            if (min(w, h) < MIN_EDGE) return null
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        return null
    }

    private companion object {
        val QUALITIES = intArrayOf(85, 70, 55, 40, 25, 10)
        val MASK_QUALITIES = intArrayOf(100)
        const val SHRINK = 0.75f
        const val MAX_SHRINKS = 6
        const val MIN_EDGE = 32
        const val CACHE_MAX = 64
        const val CACHE_TTL_MS = 60 * 60 * 1000L
    }
}

internal object AndroidWebpCompressor : WebpCompressor {
    @Suppress("DEPRECATION")
    override fun compress(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray? {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, if (lossless) 100 else quality, out)) out.toByteArray() else null
    }
}

package com.mtajchert.carom.app.feature.notifications.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Needs real bitmaps and the native WebP encoder, hence native graphics. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEncoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var now = 0L

    private fun encoder(compressor: WebpCompressor = AndroidWebpCompressor) =
        ImageEncoder(context, Dispatchers.Unconfined, compressor) { now }

    private fun bitmap(w: Int, h: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun isWebp(bytes: ByteArray) =
        bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    private class SizedCompressor(private val sizeOf: (Bitmap, Int) -> Int) : WebpCompressor {
        val qualities = mutableListOf<Int>()

        override fun compress(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray {
            qualities += quality
            return ByteArray(sizeOf(bitmap, quality))
        }
    }

    @Test
    fun `icons are downscaled to their cap and encoded as WebP, never upscaled`() {
        val e = encoder()
        val app = e.encode(ImageSlot.AppIcon, ImageSource.FromBitmap(bitmap(512, 256)))!!
        assertEquals(128 to 64, app.w to app.h)
        assertTrue(isWebp(app.bytes))
        assertEquals("image/webp", app.mime)

        val large = e.encode(ImageSlot.LargeIcon, ImageSource.FromBitmap(bitmap(400, 400)))!!
        assertEquals(192 to 192, large.w to large.h)
        val avatar = e.encode(ImageSlot.SenderIcon(0), ImageSource.FromBitmap(bitmap(1000, 500)))!!
        assertEquals(192 to 96, avatar.w to avatar.h)
        val tiny = e.encode(ImageSlot.LargeIcon, ImageSource.FromBitmap(bitmap(50, 50)))!!
        assertEquals(50 to 50, tiny.w to tiny.h)
        val tracker = e.encode(ImageSlot.TrackerIcon, ImageSource.FromBitmap(bitmap(300, 300)))!!
        assertEquals(96 to 96, tracker.w to tracker.h)
    }

    @Test
    fun `the small icon is a 96 px white alpha mask`() {
        val e = encoder()
        val solid = e.encode(ImageSlot.SmallIcon, ImageSource.FromBitmap(bitmap(200, 200, Color.RED)))!!
        assertEquals(96 to 96, solid.w to solid.h)
        val px = BitmapFactory.decodeByteArray(solid.bytes, 0, solid.bytes.size).getPixel(48, 48)
        assertEquals(255, Color.alpha(px))
        assertEquals(255, Color.red(px))
        assertEquals(255, Color.green(px))
        assertEquals(255, Color.blue(px))

        val clear = e.encode(ImageSlot.SmallIcon, ImageSource.FromBitmap(bitmap(20, 20, Color.TRANSPARENT)))!!
        assertEquals(0, Color.alpha(BitmapFactory.decodeByteArray(clear.bytes, 0, clear.bytes.size).getPixel(10, 10)))
    }

    @Test
    fun `a 12 MP picture is cut to 1080 px wide and 400 KB`() {
        val picture = encoder().encode(ImageSlot.Picture, ImageSource.FromBitmap(bitmap(4000, 3000, Color.BLUE)))!!
        assertEquals(1080 to 810, picture.w to picture.h)
        assertTrue(isWebp(picture.bytes))
        assertTrue("${picture.bytes.size} bytes", picture.bytes.size <= 400 * 1024)

        val small = encoder().encode(ImageSlot.Picture, ImageSource.FromBitmap(bitmap(600, 400)))!!
        assertEquals(600 to 400, small.w to small.h)
    }

    @Test
    fun `quality is stepped down until the picture fits`() {
        val compressor = SizedCompressor { b, q -> b.width * b.height * q / 100 }
        val picture = encoder(compressor).encode(ImageSlot.Picture, ImageSource.FromBitmap(bitmap(2160, 1620)))!!
        assertEquals(listOf(85, 70, 55, 40), compressor.qualities)
        assertEquals(1080 to 810, picture.w to picture.h)
        assertEquals(1080 * 810 * 40 / 100, picture.bytes.size)
    }

    @Test
    fun `the picture shrinks by a quarter when no quality fits`() {
        val compressor = SizedCompressor { b, _ -> b.width * b.height }
        val picture = encoder(compressor).encode(ImageSlot.Picture, ImageSource.FromBitmap(bitmap(2160, 1620)))!!
        assertEquals(608 to 456, picture.w to picture.h)
        assertEquals(6 + 6 + 1, compressor.qualities.size)
        assertTrue(picture.bytes.size <= 400 * 1024)
    }

    @Test
    fun `a picture that never fits is skipped`() {
        val compressor = SizedCompressor { _, _ -> 10 * 1024 * 1024 }
        assertNull(encoder(compressor).encode(ImageSlot.Picture, ImageSource.FromBitmap(bitmap(2160, 1620))))
    }

    @Test
    fun `sources that fail to load are skipped and the others still encode`() = runTest {
        val sources = linkedMapOf<ImageSlot, ImageSource>(
            ImageSlot.Picture to ImageSource.FromUri(Uri.parse("content://nobody/1")),
            ImageSlot.AppIcon to ImageSource.FromApp("com.unknown.app", null),
            ImageSlot.LargeIcon to ImageSource.FromBitmap(bitmap(64, 64)),
            ImageSlot.CallPersonIcon to ImageSource.FromIcon(Icon.createWithBitmap(bitmap(32, 32))),
        )
        val encoded = encoder().load(sources)
        assertEquals(setOf(ImageSlot.LargeIcon, ImageSlot.CallPersonIcon), encoded.keys)
    }

    @Test
    fun `the app's own icon encodes`() {
        val image = encoder().encode(ImageSlot.AppIcon, ImageSource.FromApp(context.packageName, null))
        assertNotNull(image)
        assertTrue(image!!.w <= 128 && image.h <= 128)
    }

    @Test
    fun `app icons are encoded once per hour and package`() {
        val compressor = SizedCompressor { _, _ -> 100 }
        val e = encoder(compressor)
        val source = ImageSource.FromApp(context.packageName, null)
        e.encode(ImageSlot.AppIcon, source)
        e.encode(ImageSlot.AppIcon, source)
        assertEquals(1, compressor.qualities.size)
        now += 2 * 60 * 60 * 1000L
        e.encode(ImageSlot.AppIcon, source)
        assertEquals(2, compressor.qualities.size)
    }

    @Test
    fun `only app icons and resource small icons are cached`() {
        val e = encoder()
        assertEquals("app:com.chat", e.cacheKey(ImageSlot.AppIcon, ImageSource.FromApp("com.chat", null)))
        assertEquals(
            "small:com.chat:123",
            e.cacheKey(ImageSlot.SmallIcon, ImageSource.FromIcon(Icon.createWithResource("com.chat", 123))),
        )
        assertNull(e.cacheKey(ImageSlot.SmallIcon, ImageSource.FromIcon(Icon.createWithBitmap(bitmap(4, 4)))))
        assertNull(e.cacheKey(ImageSlot.LargeIcon, ImageSource.FromApp("com.chat", null)))
    }
}

package com.mtajchert.carom.app.feature.notifications.impl.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtEncoderTest {
    @Test
    fun `fit keeps the aspect ratio and never upsizes`() {
        assertEquals(192 to 96, ArtEncoder.fit(400, 200))
        assertEquals(96 to 192, ArtEncoder.fit(200, 400))
        assertEquals(100 to 100, ArtEncoder.fit(100, 100))
        assertEquals(192 to 1, ArtEncoder.fit(1000, 3))
        assertEquals(0 to 0, ArtEncoder.fit(0, 50))
    }

    @Test
    fun `a large bitmap is scaled to the cap and encoded`() {
        val art = ArtEncoder.encode(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888))
        assertNotNull(art)
        assertEquals(192, art!!.width)
        assertEquals(96, art.height)
        assertTrue(art.bytes.isNotEmpty())
        assertEquals("image/webp", EncodedArt.MIME)
    }

    @Test
    fun `a small bitmap keeps its size`() {
        val art = ArtEncoder.encode(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))!!
        assertEquals(64, art.width)
        assertEquals(64, art.height)
    }
}

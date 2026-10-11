package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.protocol.Blobs
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlobUploaderTest {
    private var key: CurrentKey? = CurrentKey(kv = 5, key = ByteArray(32) { 1 })
    private var now = 1_000_000L
    private val icons = FakeIconCache()
    private val sync = UploadingSync { key?.key ?: ByteArray(32) }
    private val uploader = DefaultBlobUploader(sync, icons, GroupKeySource { key }) { now }

    private fun image(seed: Int) = EncodedImage(ByteArray(64) { (it + seed).toByte() }, w = 8, h = 8)

    @Test
    fun `identical bytes in several slots upload once`() = runTest {
        val a = image(1)
        val refs = uploader.upload(
            mapOf(ImageSlot.LargeIcon to a, ImageSlot.SenderIcon(0) to a, ImageSlot.SenderIcon(1) to image(2)),
        )
        assertEquals(2, sync.uploads.size)
        assertEquals(setOf(ImageSlot.LargeIcon, ImageSlot.SenderIcon(0), ImageSlot.SenderIcon(1)), refs.keys)
        assertEquals(refs[ImageSlot.LargeIcon], refs[ImageSlot.SenderIcon(0)])
        assertNotEquals(refs[ImageSlot.LargeIcon], refs[ImageSlot.SenderIcon(1)])
        assertTrue(sync.uploads.all { it.ttlS == DefaultBlobUploader.MESSAGE_TTL_S && !it.convergent })
    }

    @Test
    fun `at most three uploads run at once`() = runTest {
        sync.delayMs = 100
        val refs = uploader.upload((0 until 8).associate { ImageSlot.SenderIcon(it) to image(it) })
        assertEquals(8, refs.size)
        assertEquals(3, sync.maxActive)
    }

    @Test
    fun `a failed upload drops only that image`() = runTest {
        sync.failBytes = image(2).bytes
        val refs = uploader.upload(mapOf(ImageSlot.LargeIcon to image(1), ImageSlot.Picture to image(2)))
        assertEquals(setOf(ImageSlot.LargeIcon), refs.keys)
    }

    @Test
    fun `an upload that throws is treated as a failure`() = runTest {
        sync.throwOnUpload = true
        assertTrue(uploader.upload(mapOf(ImageSlot.LargeIcon to image(1))).isEmpty())
    }

    @Test
    fun `a convergent icon is uploaded once per key version and refreshed after three days`() = runTest {
        val icon = image(7)
        val first = uploader.upload(mapOf(ImageSlot.AppIcon to icon))
        assertEquals(1, sync.uploads.size)
        assertTrue(sync.uploads[0].convergent)
        assertEquals(DefaultBlobUploader.ICON_TTL_S, sync.uploads[0].ttlS)

        val again = uploader.upload(mapOf(ImageSlot.AppIcon to icon))
        assertEquals("fresh in the cache: no network", 1, sync.uploads.size)
        assertEquals(first[ImageSlot.AppIcon], again[ImageSlot.AppIcon])

        now += IconCache.FRESH_MS + 1
        uploader.upload(mapOf(ImageSlot.AppIcon to icon))
        assertEquals("stale: uploadBlob HEADs and touches", 2, sync.uploads.size)
    }

    @Test
    fun `a rekey uploads icons again`() = runTest {
        val icon = image(7)
        val before = uploader.upload(mapOf(ImageSlot.SmallIcon to icon))[ImageSlot.SmallIcon]!!
        key = CurrentKey(kv = 6, key = ByteArray(32) { 2 })
        val after = uploader.upload(mapOf(ImageSlot.SmallIcon to icon))[ImageSlot.SmallIcon]!!
        assertEquals(2, sync.uploads.size)
        assertNotEquals("the id depends on the group key", before.id, after.id)
        assertEquals(setOf(5L, 6L), icons.rows.values.map { it.first }.toSet())
    }

    @Test
    fun `an icon and a picture with the same bytes do not share a blob`() = runTest {
        val a = image(3)
        uploader.upload(mapOf(ImageSlot.SmallIcon to a, ImageSlot.LargeIcon to a))
        assertEquals(listOf(true, false), sync.uploads.map { it.convergent }.sortedDescending())
    }

    @Test
    fun `a random-key image is reused for an hour`() = runTest {
        val a = image(3)
        val first = uploader.upload(mapOf(ImageSlot.LargeIcon to a))[ImageSlot.LargeIcon]
        val second = uploader.upload(mapOf(ImageSlot.LargeIcon to a))[ImageSlot.LargeIcon]
        assertEquals(1, sync.uploads.size)
        assertEquals(first, second)
        now += DefaultBlobUploader.REUSE_MS + 1
        uploader.upload(mapOf(ImageSlot.LargeIcon to a))
        assertEquals(2, sync.uploads.size)
    }

    @Test
    fun `a broken icon cache never loses the icon`() = runTest {
        icons.broken = true
        val refs = uploader.upload(mapOf(ImageSlot.AppIcon to image(4)))
        assertEquals(setOf(ImageSlot.AppIcon), refs.keys)
        assertEquals(1, sync.uploads.size)
    }

    @Test
    fun `without a group key icons still upload`() = runTest {
        key = null
        uploader.upload(mapOf(ImageSlot.AppIcon to image(4)))
        uploader.upload(mapOf(ImageSlot.AppIcon to image(4)))
        assertEquals(2, sync.uploads.size)
        assertTrue(icons.rows.isEmpty())
    }

    @Test
    fun `nothing to upload is no work`() = runTest {
        assertTrue(uploader.upload(emptyMap()).isEmpty())
        assertEquals(0, sync.uploads.size)
    }

    private class FakeIconCache : IconCache {
        val rows = linkedMapOf<String, Pair<Long, Long>>()
        var broken = false

        override suspend fun isFresh(id: String, now: Long): Boolean {
            check(!broken) { "database is broken" }
            return rows[id]?.let { now - it.second in 0L until IconCache.FRESH_MS } ?: false
        }

        override suspend fun record(id: String, kv: Long, now: Long) {
            check(!broken) { "database is broken" }
            rows[id] = kv to now
        }

        override suspend fun clear() = rows.clear()
    }

    /** Seals like the real engine (random or convergent key) so ids behave, and records what was uploaded. */
    private class UploadingSync(
        private val inner: FakeCaromSync = FakeCaromSync(),
        private val groupKey: () -> ByteArray,
    ) : CaromSync by inner {
        class Upload(val convergent: Boolean, val ttlS: Long)

        val uploads = mutableListOf<Upload>()
        var delayMs = 0L
        var failBytes: ByteArray? = null
        var throwOnUpload = false
        var maxActive = 0
        private var active = 0

        override suspend fun uploadBlob(
            plain: ByteArray,
            mime: String,
            w: Int?,
            h: Int?,
            convergent: Boolean,
            ttlS: Long,
            msgId: String?,
        ): MessageBodies.BlobRef? {
            uploads += Upload(convergent, ttlS)
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (delayMs > 0) delay(delayMs)
            } finally {
                active--
            }
            if (throwOnUpload) throw IllegalStateException("boom")
            if (failBytes?.contentEquals(plain) == true) return null
            val key = if (convergent) Blobs.convergentKey(groupKey(), plain) else Blobs.randomKey()
            return Blobs.seal(plain, mime, key, w, h).ref
        }
    }
}

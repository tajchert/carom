package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.protocol.Blobs
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Seals and uploads the images of one notification (V2 spec §5.1). Any failure drops that image from the message. */
fun interface BlobUploader {
    suspend fun upload(images: Map<ImageSlot, EncodedImage>): Map<ImageSlot, MessageBodies.BlobRef>

    companion object {
        val None: BlobUploader = BlobUploader { emptyMap() }
    }
}

/** The group key new messages are sealed with. [toString] never prints the key. */
class CurrentKey(val kv: Long, val key: ByteArray) {
    override fun toString() = "CurrentKey(kv=$kv)"
}

fun interface GroupKeySource {
    suspend fun current(): CurrentKey?
}

class VaultGroupKeySource @Inject constructor(
    private val vault: SecretVault,
) : GroupKeySource {
    override suspend fun current(): CurrentKey? =
        (vault.contents.value ?: vault.load()).currentKey?.let { CurrentKey(it.kv, it.key) }
}

/**
 * Uploads through [CaromSync.uploadBlob], at most [MAX_PARALLEL] at a time across the process.
 *
 * - Identical bytes within one notification upload once (the same avatar on six messages).
 * - Convergent icons: the id is computed locally (same key derivation as the upload), and when
 *   [IconCache] says it is fresh nothing touches the network. Otherwise `uploadBlob` HEADs with
 *   touch and uploads if needed, and the id is recorded.
 * - Random-key images are reused for [REUSE_MS], so a Live Update that reposts every second does
 *   not upload its large icon every second.
 * - Blobs are uploaded without a `msg_id`: it is only assigned at send time. The Hub removes them
 *   by TTL.
 */
@Singleton
class DefaultBlobUploader internal constructor(
    private val sync: CaromSync,
    private val icons: IconCache,
    private val keys: GroupKeySource,
    private val clock: () -> Long,
) : BlobUploader {

    @Inject
    constructor(sync: CaromSync, icons: IconCache, keys: GroupKeySource) :
        this(sync, icons, keys, System::currentTimeMillis)

    private class Recent(val ref: MessageBodies.BlobRef, val at: Long)

    private val gate = Semaphore(MAX_PARALLEL)
    private val recent = object : LinkedHashMap<String, Recent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Recent>?) = size > RECENT_MAX
    }

    override suspend fun upload(images: Map<ImageSlot, EncodedImage>): Map<ImageSlot, MessageBodies.BlobRef> {
        if (images.isEmpty()) return emptyMap()
        val key = keys.current()
        val unique = images.entries
            .groupBy({ it.value.digest to it.key.convergent }, { it.value })
            .mapValues { it.value.first() }
        val refs = coroutineScope {
            unique.map { (id, image) ->
                async { gate.withPermit { id to uploadOne(image, convergent = id.second, key = key) } }
            }.awaitAll()
        }.toMap()
        return buildMap {
            for ((slot, image) in images) refs[image.digest to slot.convergent]?.let { put(slot, it) }
        }
    }

    private suspend fun uploadOne(image: EncodedImage, convergent: Boolean, key: CurrentKey?): MessageBodies.BlobRef? = try {
        if (convergent) uploadConvergent(image, key) else uploadRandom(image)
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        null // one broken image must not lose the others
    }

    private suspend fun uploadConvergent(image: EncodedImage, key: CurrentKey?): MessageBodies.BlobRef? {
        val now = clock()
        if (key != null) {
            val local = Blobs.seal(image.bytes, image.mime, Blobs.convergentKey(key.key, image.bytes), image.w, image.h).ref
            if (cacheOp(false) { icons.isFresh(local.id, now) }) return local
        }
        val ref = sync.uploadBlob(image.bytes, image.mime, image.w, image.h, convergent = true, ttlS = ICON_TTL_S) ?: return null
        if (key != null) cacheOp(Unit) { icons.record(ref.id, key.kv, now) }
        return ref
    }

    private suspend fun uploadRandom(image: EncodedImage): MessageBodies.BlobRef? {
        val now = clock()
        synchronized(recent) { recent[image.digest] }
            ?.takeIf { now - it.at in 0L until REUSE_MS }
            ?.let { return it.ref }
        val ref = sync.uploadBlob(image.bytes, image.mime, image.w, image.h, convergent = false, ttlS = MESSAGE_TTL_S) ?: return null
        synchronized(recent) { recent[image.digest] = Recent(ref, now) }
        return ref
    }

    /** A failing cache is a miss, never a lost icon. */
    private suspend fun <T> cacheOp(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        fallback
    }

    companion object {
        const val MAX_PARALLEL = 3

        /** `notification.posted@1` lives 2 days; the blob outlives it by an hour. */
        const val MESSAGE_TTL_S = 2 * 86_400L + 3_600L

        /** The Hub's maximum (`ttl_s` is clamped to 604 800). */
        const val ICON_TTL_S = 604_800L

        /** A random-key blob uploaded less than this long ago still has at least a message lifetime left. */
        const val REUSE_MS = 60 * 60 * 1000L
        private const val RECENT_MAX = 64
    }
}

package com.mtajchert.carom.app.feature.notifications.impl.media

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.feature.notifications.impl.DefaultBlobUploader
import javax.inject.Inject

/** Seals and uploads one album art image. A seam so the bridge is tested without a Hub. */
fun interface MediaArtUploader {
    /** The blob reference to put in `media.state@1`, or null when the upload failed (the card shows no art). */
    suspend fun upload(art: EncodedArt): MessageBodies.BlobRef?
}

/**
 * Album art is a message image: random key, uploaded with the message-image TTL
 * ([DefaultBlobUploader.MESSAGE_TTL_S]) so it outlives every `media.state@1` that points at it
 * while [MediaBridge] reuses it ([MediaBridge.ART_REUSE_MS]).
 */
class SyncMediaArtUploader @Inject constructor(
    private val sync: CaromSync,
) : MediaArtUploader {
    override suspend fun upload(art: EncodedArt): MessageBodies.BlobRef? = sync.uploadBlob(
        plain = art.bytes,
        mime = EncodedArt.MIME,
        w = art.width,
        h = art.height,
        convergent = false,
        ttlS = DefaultBlobUploader.MESSAGE_TTL_S,
        msgId = null,
    )
}

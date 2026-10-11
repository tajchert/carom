package com.mtajchert.carom.app.feature.notifications.impl.media

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.protocol.MessageBodies
import javax.inject.Inject

/** Seals and uploads one album art image. A seam so the bridge is tested without a Hub. */
fun interface MediaArtUploader {
    /** The blob reference to put in `media.state@1`, or null when the upload failed (the card shows no art). */
    suspend fun upload(art: EncodedArt): MessageBodies.BlobRef?
}

/** Album art is a message image: random key, same lifetime as `media.state@1` (spec §3.2). */
class SyncMediaArtUploader @Inject constructor(
    private val sync: CaromSync,
) : MediaArtUploader {
    override suspend fun upload(art: EncodedArt): MessageBodies.BlobRef? = sync.uploadBlob(
        plain = art.bytes,
        mime = EncodedArt.MIME,
        w = art.width,
        h = art.height,
        convergent = false,
        ttlS = TTL_S,
        msgId = null,
    )

    private companion object {
        const val TTL_S = 86_400L
    }
}

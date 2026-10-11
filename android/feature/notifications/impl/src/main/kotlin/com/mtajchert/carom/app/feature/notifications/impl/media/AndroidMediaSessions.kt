package com.mtajchert.carom.app.feature.notifications.impl.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import com.mtajchert.carom.app.feature.notifications.impl.AppLabelResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Builds a [MediaController] from the `EXTRA_MEDIA_SESSION` token of a media notification. */
class AndroidMediaSessionSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val labels: AppLabelResolver,
) : MediaSessionSource {
    override fun open(sbn: StatusBarNotification): MediaSessionHandle? {
        val n = sbn.notification
        val extras = n.extras ?: return null
        val token = BundleCompat.getParcelable(extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            ?: return null
        val controller = try {
            MediaController(context, token)
        } catch (_: RuntimeException) {
            return null // the session died or the token is malformed
        }
        return AndroidMediaSessionHandle(context, controller, labels.label(sbn.packageName, extras), n.getLargeIcon())
    }
}

internal class AndroidMediaSessionHandle(
    private val context: Context,
    private val controller: MediaController,
    override val appLabel: String,
    private val largeIcon: Icon?,
) : MediaSessionHandle {
    override val packageName: String = controller.packageName
    /** `MediaController.getTag` exists from API 30; on 29 the session is identified by package alone. */
    override val tag: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            controller.tag
        } catch (_: RuntimeException) {
            null
        }
    } else {
        null
    }

    override fun snapshot(): SessionSnapshot = MediaStateMapper.snapshot(
        metadata = controller.metadata,
        playback = controller.playbackState,
        wallNowMs = System.currentTimeMillis(),
        elapsedNowMs = SystemClock.elapsedRealtime(),
    )

    override suspend fun loadArt(): EncodedArt? = withContext(Dispatchers.IO) {
        val bitmap = metadataBitmap() ?: metadataUriBitmap() ?: largeIconBitmap() ?: return@withContext null
        ArtEncoder.encode(bitmap)
    }

    override fun registerCallback(onChange: () -> Unit): AutoCloseable {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
            override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
            override fun onSessionDestroyed() = onChange()
        }
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        return AutoCloseable {
            try {
                controller.unregisterCallback(callback)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
    }

    override fun perform(command: String, positionMs: Long?): Boolean = try {
        val controls = controller.transportControls
        when (command) {
            MediaControls.PLAY -> controls.play()
            MediaControls.PAUSE -> controls.pause()
            MediaControls.NEXT -> controls.skipToNext()
            MediaControls.PREVIOUS -> controls.skipToPrevious()
            MediaControls.SEEK -> controls.seekTo(positionMs ?: return false)
            else -> return false
        }
        true
    } catch (_: RuntimeException) {
        false // the session went away between the check and the call
    }

    private fun metadataBitmap(): Bitmap? {
        val md = controller.metadata ?: return null
        return listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).firstNotNullOfOrNull { md.getBitmap(it) }
    }

    /** Only `content:` URIs: art is never fetched from the network. Grants may be gone; that is a miss, not an error. */
    private fun metadataUriBitmap(): Bitmap? {
        val md = controller.metadata ?: return null
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { md.getString(it)?.takeIf(String::isNotBlank) }?.toUri() ?: return null
        if (uri.scheme != "content") return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = ArtEncoder.fit(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun largeIconBitmap(): Bitmap? {
        val icon = largeIcon ?: return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            val (w, h) = ArtEncoder.fit(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
            drawable.toBitmap(w, h)
        } catch (_: RuntimeException) {
            null
        }
    }
}

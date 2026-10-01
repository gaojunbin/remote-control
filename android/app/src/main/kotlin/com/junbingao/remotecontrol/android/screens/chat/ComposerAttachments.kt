package com.junbingao.remotecontrol.android.screens.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.junbingao.remotecontrol.android.attachments.AttachmentNaming
import com.junbingao.remotecontrol.android.attachments.PhotoPreparation
import com.junbingao.remotecontrol.android.attachments.PhotoPreparationError
import com.junbingao.remotecontrol.android.attachments.PickedFile
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.RequestLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The attachment flows the composer drives, on the phone's photo picker, camera and document
 * picker. What comes back is read at once, off the main thread, and checked against the limits
 * before it costs the draft anything.
 */
internal object ComposerAttachments {
    /**
     * The picker hands over content addresses, which name nothing the agent could use, so
     * `AttachmentNaming` owns the names: each photo is numbered by the order it was attached in.
     */
    suspend fun ingestPhotos(state: ComposerState, context: Context, uris: List<Uri>) {
        for (uri in uris) {
            val data = read(context, uri) ?: continue
            state.photosAttached += 1
            addPhoto(state, data, AttachmentNaming.libraryPhoto(state.photosAttached))
        }
    }

    /**
     * Every photo goes through the same downscale: a full-resolution camera JPEG would be rejected
     * at the 6 MB limit with no way to shrink it.
     */
    suspend fun addPhoto(state: ComposerState, data: ByteArray, name: String) {
        val prepared = try {
            withContext(Dispatchers.Default) { PhotoPreparation.jpeg(from = data) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoPreparationError) {
            state.attachmentError = error.message
            return
        } catch (_: Exception) {
            state.attachmentError = L10n.string("That image could not be attached.")
            return
        }
        state.add(data = prepared, name = name, mime = "image/jpeg")
    }

    suspend fun ingestFiles(state: ComposerState, context: Context, uris: List<Uri>) {
        for (uri in uris) {
            val name = PickedFile.name(context, uri) ?: uri.lastPathSegment ?: "file"
            // Check the size before reading: a large video would spike memory long before a check
            // after the read ever ran.
            if (PickedFile.size(context, uri) > RequestLimits.maxAttachmentBytes) {
                state.attachmentError = L10n.string("%@ is larger than 6 MB.", name)
                continue
            }
            val data = read(context, uri) ?: continue
            state.add(data = data, name = name, mime = mime(context, uri, name))
        }
    }

    /** Eagerly and whole: the grant a picked address carries lasts no longer than the activity that received it. */
    private suspend fun read(context: Context, uri: Uri): ByteArray? = try {
        withContext(Dispatchers.IO) { PickedFile.read(context, uri) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** The type the file's extension names, as the iPhone reads it, then what the provider says, then plain bytes. */
    fun mime(context: Context, uri: Uri, name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val named = extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return named ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
    }
}

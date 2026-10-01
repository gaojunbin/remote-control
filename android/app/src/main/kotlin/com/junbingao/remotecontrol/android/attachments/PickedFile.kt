package com.junbingao.remotecontrol.android.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** A file the person picked from the document picker or the photo picker. */
object PickedFile {
    /**
     * Read it, eagerly and whole. The grant a picked URI carries lasts only as long as the
     * activity that received it, and the bytes are not touched again until the message is
     * encoded on its way out, so they are copied now; the 6 MB cap [size] checks first is what
     * makes the copy cheap.
     */
    fun read(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException(uri.toString())

    /**
     * How big it is, before any of it is read: a large video would spike memory long before a
     * size check after the read ever ran. Zero when the provider does not say.
     */
    fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) return cursor.getLong(column)
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0
        } catch (_: FileNotFoundException) {
            0
        }
    }

    /** The name the provider gives it, which is what the attachment is called on the device. */
    fun name(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) return cursor.getString(column)
        }
        return uri.lastPathSegment
    }
}

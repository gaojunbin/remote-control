package com.junbingao.remotecontrol.android.attachments

/**
 * What an attachment is called when it reaches the device.
 *
 * `docs/DESIGN.md` § "The composer" → **Attachments are named for what they are**: "A photo from
 * the library is `photo-1.jpg`, `photo-2.jpg`, … in the order attached, with the extension of what
 * is actually sent, never the library's own identifier; a camera shot is `photo.jpg`."
 *
 * The picker hands over a content URI, which names nothing the agent could use, and every photo
 * leaves here re-encoded as JPEG by [PhotoPreparation], so every photo is named as one.
 */
object AttachmentNaming {
    /** The extension for the only format a photo is ever sent in. */
    const val photoExtension = "jpg"

    /** A shot taken here and now. There is only ever one of it. */
    const val cameraPhoto = "photo.$photoExtension"

    /** A photo from the library, numbered by the order it was attached in. */
    fun libraryPhoto(index: Int): String = "photo-$index.$photoExtension"
}

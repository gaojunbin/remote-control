package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.RequestLimits

/**
 * `web/src/features/chat/attachments.ts`: the attachment limits of `session.send` (PROTOCOL §5),
 * which are the core's `RequestLimits`, and the one check the composer makes before sending.
 */
object AttachmentLimits {
    const val maxAttachments = RequestLimits.maxAttachments
    const val maxAttachmentBytes = RequestLimits.maxAttachmentBytes
    const val maxTextBytes = RequestLimits.maxTextBytes

    /** Measured in UTF-8 bytes, as the wire measures it. */
    fun textTooLong(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > maxTextBytes
}

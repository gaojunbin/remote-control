package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode

// From RCCore's `ChatStore.swift`, split for the store's length.

/** A `session.send` the app issued and what it knows about its fate. */
data class PendingSend(
    val id: String,
    val text: String,
    /** The bytes are kept until the send is accepted or dismissed, so a retry sends the same message rather than the text without its attachments. */
    val attachments: List<OutboundAttachment>,
    val mode: SendMode,
    /** Amendment A43: the place an edited queued message goes back to, kept so a Retry puts it there too rather than at the end of the line. */
    val queueTs: Long? = null,
    val status: Status,
) {
    sealed interface Status {
        data object Sending : Status
        data class Accepted(val acceptance: SendAcceptance) : Status
        data object Uncertain : Status
    }

    val attachmentInfo: List<AttachmentInfo> get() = attachments.map { it.info }

    val isUnconfirmed: Boolean get() = status == Status.Uncertain
}

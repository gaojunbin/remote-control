package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Mac's `ComposerModel+Attach.swift`: the files a message carries.

/**
 * Whether files are taken at all: not where the attachment button is not drawn (A11), not on a
 * composer that cannot send, and not while an edited message is on its way back (A43).
 */
val ComposerModel.acceptsFiles: Boolean get() = gates.showAttach && !gates.disabled && !returning

/**
 * Two attach operations can run at once — a paste, then the file dialog before the paste has been
 * read — and each reads the count it started with. The cap is enforced where the list is written,
 * and what the second has to say is added to what the first said rather than replacing it.
 */
fun ComposerModel.attach(sources: List<AttachmentSource>) {
    if (!acceptsFiles || sources.isEmpty()) return
    val key = key
    val drafts = host.drafts
    val already = drafts.attachments(key).size
    host.tasks.launch {
        val read = withContext(reading) { AttachmentRead.read(sources, alreadyAttached = already) }
        val dropped = drafts.add(read.attachments, to = key)
        val tooMany = S.composer.attachTooMany(AttachmentLimits.maxAttachments)
        val messages = if (dropped > 0 && tooMany !in read.errors) read.errors + tooMany else read.errors.toList()
        if (messages.isEmpty()) return@launch
        errors = (errors + messages).distinct()
    }
}

fun ComposerModel.removeAttachment(at: Int) {
    host.drafts.remove(at = at, from = key)
}

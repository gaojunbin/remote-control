package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

// The Mac's `ComposerModel+Queue.swift` — A43: a queued message taken back into the field and put
// back again (`useQueuedEdit.ts`). The edit itself is the `ChatStore`'s (`queuedEdit`, the words
// aside); the files aside are the drafts store's.

/**
 * Whether a row of Up next can be taken back into the field: not one that carries files, and not
 * while the composer cannot send or already holds another one — the store's own rule, so no row
 * looks live and does nothing.
 */
fun ComposerModel.canEdit(entry: QueuedMessage): Boolean = !gates.disabled && chat.canEdit(entry)

/**
 * The message leaves the line before the field takes it, so the device cannot deliver words that
 * are still changing. `not_found` means the device took it first: nothing opens, and the line under
 * the field says so. The page's banner is cleared first, as the web's `onTakeQueued` does.
 */
fun ComposerModel.edit(entry: QueuedMessage) {
    if (!canEdit(entry) || takingOut) return
    takingOut = true
    chat.clearError()
    host.tasks.launch {
        try {
            chat.beginEdit(entry)
        } finally {
            takingOut = false
        }
        if (chat.queuedEdit == null) {
            takeError(fallback = S.errors.queueRemoveFailed)
            return@launch
        }
        // The field lets go of a dictation and of a polish note when it takes one, and its files
        // wait aside with its words.
        takeFieldBack()
        dropPolish()
        errors = emptyList()
        host.drafts.setAside(key)
        highlight = 0
        panelDismissed = false
        requestFocus()
    }
}

/** × takes a message out of the line for good. A refusal is the page's banner, as it is on the web. */
fun ComposerModel.remove(entry: QueuedMessage) {
    chat.removeQueued(entry.id)
}

/**
 * The words go back under the entry's `ts` — or, from the ⋯ menu, as an interrupt, which keeps no
 * place in the line. Accepted or uncertain, the edit ends and the files aside come back; refused,
 * the words stay, the edit goes on, and the reason is this line's.
 */
fun ComposerModel.putBack(interrupt: Boolean) {
    if (editing == null || returning) return
    errors = emptyList()
    val files = attachments.map { it.outbound }
    host.tasks.launch {
        val outcome = chat.send(mode = if (interrupt) SendMode.interrupt else SendMode.auto, attachments = files)
        settleEdit(outcome == ChatStore.SendOutcome.refused)
    }
}

/** Cancel puts the words back as they were queued, and nothing else. */
fun ComposerModel.cancelEdit() {
    takeFieldBack()
    dropPolish()
    if (!chat.canCancelEdit) return
    errors = emptyList()
    host.tasks.launch {
        chat.cancelEdit()
        settleEdit(chat.queuedEdit != null)
    }
}

private fun ComposerModel.settleEdit(refused: Boolean) {
    if (refused) {
        takeError(fallback = S.composer.sendFailed)
    } else if (chat.queuedEdit == null) {
        host.drafts.endEdit(key)
    }
}

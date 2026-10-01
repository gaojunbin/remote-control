package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.L10n
import com.junbingao.remotecontrol.core.state.PendingSend
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import com.junbingao.remotecontrol.win.shared.SlashCommands
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.PrimarySlot
import kotlinx.coroutines.launch

// The Mac's `ComposerModel+Send.swift`: what Send sends, and what comes back.

/**
 * Enter is Send, so it waits with Send: while the slot is a spinner there is nothing to press, and
 * a keystroke that sent anyway would be the button in another guise (`docs/DESIGN.md` § "The
 * composer").
 */
fun ComposerModel.primarySubmit() {
    if (slot != PrimarySlot.send) return
    if (answering) submitAnswer() else submit(SendMode.auto)
}

/**
 * PROTOCOL §5: `auto` is "send now if idle; if running, steer or queue", and the device decides.
 * The ⋯ menu's Interrupt & send is the one other mode. A12: the field is cleared and the message is
 * in the timeline in this turn, before the request leaves; only a refusal the gateway is certain
 * about comes back, and it hands the draft back if nothing was typed since.
 */
fun ComposerModel.submit(mode: SendMode) {
    val value = text.trimmed
    val files = attachments
    if (gates.disabled || (value.isEmpty() && files.isEmpty())) return
    // A29: the "Polished · Undo" note belongs to the draft and leaves with it.
    dropPolish()
    if (AttachmentLimits.textTooLong(value)) {
        errors = listOf(S.composer.textTooLong)
        return
    }
    // A43: a queued message being edited goes back into the line, even behind a steering agent,
    // unless the ⋯ menu interrupts with it.
    if (editing != null) {
        putBack(interrupt = mode == SendMode.interrupt)
        return
    }
    // A27: a first word the session offers is a command, not a message.
    if (commandable) {
        val match = SlashCommands.match(commands, draft = value)
        if (match != null) {
            run(match)
            return
        }
    }
    send(value, files, mode)
}

private fun ComposerModel.send(value: String, files: List<ComposerAttachment>, mode: SendMode) {
    host.drafts.clear(key)
    errors = emptyList()
    val outbound = files.map { it.outbound }
    host.tasks.launch {
        val outcome = if (value.isEmpty()) {
            // The contract takes a message of files alone; the store's `send` reads its words from
            // the draft and refuses an empty one, and `retry` is its door to the same delivery with
            // the words given — a fresh request id, so nothing is resent.
            chat.draft = ""
            chat.retry(PendingSend(id = GatewayRequest.newRequestID(), text = "", attachments = outbound, mode = mode,
                                   status = PendingSend.Status.Sending))
        } else {
            chat.send(mode = mode, attachments = outbound)
        }
        if (outcome != ChatStore.SendOutcome.refused) return@launch
        // The store has put the words back if nothing was typed since; the files come back the same
        // way, and the reason is this line's.
        host.drafts.restore(files, to = key)
        takeError(fallback = S.composer.sendFailed)
    }
}

/**
 * A27: the device refuses a command mid-turn with `conflict`, so the composer says so itself rather
 * than spending a round trip on it. The store runs what its draft names, so the draft is written as
 * the line the web's rules resolved before it goes.
 */
private fun ComposerModel.run(match: SlashCommands.Match) {
    if (gates.running) {
        errors = listOf(S.commands.whileRunning)
        return
    }
    errors = emptyList()
    chat.clearError()
    chat.draft = match.command.line(argument = match.argument)
    host.tasks.launch {
        chat.runCommand()
        takeError(fallback = S.commands.failed)
    }
}

/**
 * A20: the draft is the free-text answer of the first question with no option chosen, beside
 * whatever the card holds. There is no optimistic row: an answer is not a message, and the card
 * resolving is the receipt. The page's banner reports a failure, as the web's does.
 */
fun ComposerModel.submitAnswer() {
    val question = question ?: return
    val answers = answer ?: return
    dropPolish()
    val value = text
    setDraft("")
    errors = emptyList()
    chat.clearError()
    host.tasks.launch {
        chat.answer(requestID = question.requestID, answers = answers)
        // A newer draft wins, as it does for a send.
        if (chat.errorMessage != null && text.isEmpty()) setDraft(value)
    }
}

/**
 * What a request of the composer's own was refused with goes on the composer's line, not in the
 * page's banner: the store reports every refusal in one place, and this one is read off it at once,
 * in the same turn, before anything draws it there.
 */
fun ComposerModel.takeError(fallback: String) {
    val message = chat.errorMessage ?: return
    chat.clearError()
    errors = listOf(composerWording(message, fallback))
}

/** The core words a few refusals itself, in English; the sentence the web shows for them comes from the app's own table. */
fun composerWording(message: String, fallback: String): String {
    if (message == L10n.string("That message has already been sent.")) return S.composer.alreadySent
    return message.ifEmpty { fallback }
}

package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.win.shared.SlashCommands
import kotlinx.coroutines.launch

// The Mac's `ComposerModel+Input.swift`: what the field's keys and edits do.

/**
 * Every write of the field goes through here. A changed draft is a changed command list, so the
 * highlight goes back to the first match and a panel that was dismissed is open again (A27).
 */
fun ComposerModel.setDraft(value: String) {
    chat.draft = value
    highlight = 0
    panelDismissed = false
}

/**
 * The person typed. Reaching for the field takes it back from dictation; an edit drops the polish
 * note and any answer still in flight (A29); and the keystroke that opens the panel asks for the
 * list again, so it is current the moment it is on screen (A27).
 */
fun ComposerModel.userTyped(value: String) {
    takeFieldBack()
    dropPolish()
    if (commandable && SlashCommands.query(value) != null && SlashCommands.query(text) == null &&
        agent?.supports(AgentCapability.commands) == true
    ) {
        host.tasks.launch { chat.refreshCommands() }
    }
    setDraft(value)
}

/** A pointer down on the field is the other way of reaching for it: the person stops the dictation to read back what was said. */
fun ComposerModel.pointerDownInField() = takeFieldBack()

/** One key press in the field, answered with whether the composer used it. */
fun ComposerModel.handle(key: ComposerKey, shift: Boolean, hasMarkedText: Boolean): Boolean {
    val panel = if (panelOpen) {
        ComposerKeys.Panel(
            rows = panelRows.size,
            highlight = highlightIndex,
            takingChangesField = highlighted?.let { SlashCommands.completion(it) != text } ?: false,
        )
    } else {
        null
    }
    when (val action = ComposerKeys.action(key, shift = shift, hasMarkedText = hasMarkedText, panel = panel)) {
        ComposerKeyAction.Pass -> return false
        is ComposerKeyAction.Highlight -> highlight = action.index
        ComposerKeyAction.DismissPanel -> panelDismissed = true
        ComposerKeyAction.TakeRow -> highlighted?.let { take(it) }
        ComposerKeyAction.Submit -> primarySubmit()
    }
    return true
}

/** Take a row: `/name ` when it takes an argument, `/name` when it does not. */
fun ComposerModel.take(command: Command) {
    setDraft(SlashCommands.completion(command))
    requestFocus()
}

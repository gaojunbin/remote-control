package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.GrowingTextField
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scaledMetric
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.screens.chat.voice.InlineVoiceDraftSession
import com.junbingao.remotecontrol.android.screens.chat.voice.VoiceStatusLine
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.PolishPhase
import kotlinx.coroutines.delay

/**
 * One line above the field, and only when something is happening to it: an attachment problem,
 * what dictation is doing, or where the argument of a command already named goes. An attached
 * session says so in the header and prints nothing here.
 */
@Composable
internal fun NoticeLine(chat: ChatStore, state: ComposerState, voice: InlineVoiceDraftSession, usesGateway: Boolean) {
    val error = state.attachmentError
    val failed = voice.voice.failure != null
    val command = chat.commandHint
    when {
        error != null -> Text(error, Modifier.fillMaxWidth(), style = SystemFont.caption, color = Theme.danger)
        voice.voice.phase.isBusy || failed -> {
            VoiceStatusLine(voice, usesGateway)
            // The message clears itself after six seconds; the words it is about are in the draft.
            LaunchedEffect(failed) {
                if (!failed) return@LaunchedEffect
                delay(6_000)
                voice.voice.dismissFailure()
            }
        }
        // Something that is happening outranks something that is merely true, so the hint waits
        // for dictation to finish before it takes the line.
        command != null -> CommandHintLine(chat, command)
    }
}

/** Amendment A43: over the field while it holds a queued message taken out of the line, with the way to put the original words back. */
@Composable
internal fun EditingStrip(chat: ChatStore, state: ComposerState) {
    if (chat.queuedEdit == null) return
    Foreground(Theme.inkSecondary, SystemFont.caption) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.string("Editing a queued message"), Modifier.weight(1f).testTag("composer.editingQueued"))
            Button(
                onClick = { state.cancelEdit() },
                modifier = Modifier.disabledLook(chat.canCancelEdit).testTag("composer.cancelEdit"),
                enabled = chat.canCancelEdit,
            ) {
                Text(L10n.string("Cancel"), color = Theme.ink)
            }
        }
    }
}

/**
 * The field takes the row to itself and grows with the draft up to `ComposerLayout.maximumLines`,
 * then scrolls inside itself. Without its own height the bar would squeeze it back to one line;
 * the transcript is the view that gives way, not the thing being written.
 *
 * While dictation runs the field shows the transcript arriving. Reaching for it is a request to
 * take over rather than a dead tap, so it ends the dictation, keeps every word and puts the cursor
 * in the field.
 */
@Composable
internal fun PromptField(chat: ChatStore, state: ComposerState, placeholder: String, followsTail: Boolean, dictating: Boolean, takeOver: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Theme.surface, ContinuousShape(Theme.Radius.control))) {
        GrowingTextField(
            placeholder,
            chat.draft,
            { chat.draft = it },
            Modifier.padding(Theme.Space.small),
            isFocused = state.isWriting,
            onFocusChange = { state.isWriting = it },
            identifier = "composer.prompt",
            followsTail = followsTail,
            // Amendment A43: words on their way back into the line hold still until the device has them.
            enabled = !(chat.isReadOnly || chat.isReturningEdit),
        )
        if (dictating) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = takeOver)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Amendment A29: the small line under the field once a dictation has been polished, and the one
 * line a failed polish gets. Both stand until the next edit or send; the failure also goes by
 * itself after a few seconds, because nothing is wrong with the draft it is talking about.
 */
@Composable
internal fun PolishNote(chat: ChatStore) {
    when (chat.polishPhase) {
        is PolishPhase.Polished -> Foreground(Theme.inkSecondary, SystemFont.caption) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                // The identifiers go on the two elements themselves: one on the row would cover both.
                Text(L10n.string("Polished"), Modifier.testTag("composer.polished"))
                Text("·", Modifier.clearAndSetSemantics { })
                Button(onClick = { chat.undoPolish() }, modifier = Modifier.testTag("composer.polishUndo")) {
                    Text(L10n.string("Undo"), color = Theme.ink)
                }
            }
        }
        PolishPhase.Failed -> {
            Text(
                L10n.string("Polishing failed, your words are unchanged"),
                Modifier.fillMaxWidth().testTag("composer.polishFailed"),
                style = SystemFont.caption,
                color = Theme.inkSecondary,
            )
            LaunchedEffect(Unit) {
                delay(6_000)
                chat.clearPolishNote()
            }
        }
        PolishPhase.Idle, PolishPhase.Polishing -> Unit
    }
}

/** The files going with the next message, each with its own way out. */
@Composable
internal fun AttachmentStrip(state: ComposerState) {
    // The pill grows with the type, as the command panel's rows do: a fixed frame around a label
    // clips well before the largest text size.
    val pill = scaledMetric(30.dp, SystemFont.caption)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        for (attachment in state.attachments) {
            key(attachment.id) {
                Foreground(Theme.inkSecondary) {
                    Row(
                        Modifier
                            .height(pill)
                            .background(Theme.surfaceSunken, CapsuleShape)
                            .padding(horizontal = Theme.Space.small),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Sf.paperclip, font = SystemFont.caption2)
                        Text(attachment.name, style = SystemFont.caption, lineLimit = 1)
                        Button(
                            onClick = { state.remove(attachment) },
                            modifier = Modifier.semantics { contentDescription = L10n.string("Remove %@", attachment.name) },
                        ) {
                            Icon(Sf.xmarkCircleFill, font = SystemFont.caption)
                        }
                    }
                }
            }
        }
    }
}

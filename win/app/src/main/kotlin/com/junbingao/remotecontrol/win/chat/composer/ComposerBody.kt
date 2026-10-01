package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.ZLayer
import com.junbingao.remotecontrol.win.shared.Attach
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.DictationPolishState
import com.junbingao.remotecontrol.win.voice.VoiceState
import kotlinx.coroutines.launch

/**
 * `.composer-wrap`: everything of the composer, top to bottom — the files, the errors, the takeover
 * bar, the dictation's line, the edit's strip, the box, the polish note and the control row — at
 * most 800 px wide in the middle of the chat column, 20 px in from its sides (16 below 1024).
 */
@Composable
fun ComposerBody(composer: ComposerModel, stage: ComposerStage?) {
    val layout = LocalLayoutClass.current
    val gates = composer.gates
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        VStack(
            Modifier
                .widthIn(max = 800.dp)
                .fillMaxWidth()
                .padding(start = side(layout.maxWidth1023), end = side(layout.maxWidth1023), bottom = Space.sp4),
            spacing = 0.dp,
            alignment = Alignment.Start,
        ) {
            if (composer.attachments.isNotEmpty()) {
                AttachmentChips(composer.attachments, Modifier.padding(bottom = Space.sp2)) { composer.removeAttachment(at = it) }
            }
            if (composer.errors.isNotEmpty()) {
                ComposerErrors(composer.errors, Modifier.padding(bottom = Space.sp2))
            }
            val voiceError = composer.voice.error
            if (composer.voice.state == VoiceState.error && voiceError != null) {
                VoiceErrorLine(voiceError, Modifier.padding(bottom = Space.sp2)) { composer.voice.dismissError() }
            }
            if (gates.terminalControlled) {
                TakeoverBar(Attach.attachHint(composer.agent), canTakeover = gates.canTakeover, Modifier.padding(bottom = Space.sp2)) {
                    composer.host.tasks.launch { composer.chat.takeover() }
                }
            }
            statusLine(composer)?.let { VoiceStatusLine(it, Modifier.padding(bottom = Space.sp2)) }
            if (composer.editing != null) {
                EditingStrip(canCancel = composer.chat.canCancelEdit, Modifier.padding(bottom = Space.sp2)) { composer.cancelEdit() }
            }
            ComposerBox(composer, sendMenuOpen = stage?.opening == ComposerStage.Opening.sendMenu, Modifier.zIndex(ZLayer.sticky))
            when (composer.polish) {
                is DictationPolishState.Polished, DictationPolishState.Failed ->
                    PolishNote(composer.polish, Modifier.padding(top = Space.sp2)) { composer.undoPolish() }
                DictationPolishState.Idle, DictationPolishState.Polishing -> {}
            }
            ComposerControlRow(composer, opening = stage?.opening, Modifier.padding(top = Space.sp3))
        }
    }
}

private fun side(narrow: Boolean) = if (narrow) Space.sp4 else Space.sp5

/** The one quiet line above the field while the words are on their way. */
private fun statusLine(composer: ComposerModel): String? = when (composer.voice.state) {
    VoiceState.starting -> S.voice.connecting
    VoiceState.finishing -> S.voice.finishing
    VoiceState.listening -> S.voice.transcribing
    VoiceState.idle, VoiceState.error -> if (composer.polish == DictationPolishState.Polishing) S.voice.polishing else null
}

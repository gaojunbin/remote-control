package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.DictationPolishState

/** `.composer-errors`: why a send, an attach or a command failed, in the danger ink, one line each. */
@Composable
fun ComposerErrors(errors: List<String>, modifier: Modifier = Modifier) {
    VStack(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, spacing = 0.dp, alignment = Alignment.Start) {
        for (message in errors) Text(message, css(FontSize.fs13), color = Palette.danger)
    }
}

/** `.composer-errors.voice-error`: a dictation that failed, and Dismiss. */
@Composable
fun VoiceErrorLine(message: String, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    HStack(modifier.fillMaxWidth(), spacing = Space.sp3, alignment = Alignment.FirstTextBaseline) {
        Text(message, css(FontSize.fs13), Modifier.weight(1f), color = Palette.danger)
        LinkButton(S.common.dismiss, FontSize.fs13, color = Palette.danger, action = onDismiss)
    }
}

/** `.voice-status`: the one quiet line above the field while dictation runs, and while the model is writing the words back. */
@Composable
fun VoiceStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(text, css(FontSize.fs13), modifier.fillMaxWidth().padding(start = Space.sp1), color = Palette.inkSecondary)
}

/**
 * A29 — `.polish-note`: what the polish model did, under the field. "Polished · Undo" until the
 * next edit or send, or the one line that says a failed request changed nothing.
 */
@Composable
fun PolishNote(state: DictationPolishState, modifier: Modifier = Modifier, onUndo: () -> Unit) {
    WithForeground(Palette.inkSecondary) {
        if (state == DictationPolishState.Failed) {
            Text(S.voice.polishFailed, css(FontSize.fs12), modifier.fillMaxWidth().padding(start = Space.sp1))
        } else {
            HStack(modifier.fillMaxWidth().padding(start = Space.sp1), spacing = Space.sp2, alignment = Alignment.FirstTextBaseline) {
                Text(S.voice.polished, css(FontSize.fs12), softWrap = false)
                Text("·", css(FontSize.fs12), Modifier.clearAndSetSemantics {}, softWrap = false)
                LinkButton(S.voice.undo, FontSize.fs12, action = onUndo)
            }
        }
    }
}

/**
 * A10 — `.takeover-bar`: the session is the terminal's, and, for an agent that could have been
 * attached, the one quiet line on why it is not. Take over only where the agent has it.
 */
@Composable
fun TakeoverBar(hint: String?, canTakeover: Boolean, modifier: Modifier = Modifier, onTakeover: () -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    WithForeground(Palette.inkSecondary) {
        HStack(
            modifier
                .fillMaxWidth()
                .background(Palette.surfaceSunken, shape)
                .border(1.dp, Palette.line, shape)
                .padding(vertical = Space.sp2 + 1.dp, horizontal = Space.sp3 + 1.dp),
            spacing = Space.sp3,
        ) {
            VStack(Modifier.weight(1f), spacing = 2.dp, alignment = Alignment.Start) {
                Text(S.status.terminalControlled, css(FontSize.fs13))
                if (hint != null) Text(hint, css(FontSize.fs12), color = Palette.inkTertiary)
            }
            if (canTakeover) Btn(S.chat.takeOver, size = ButtonSize.small, action = onTakeover)
        }
    }
}

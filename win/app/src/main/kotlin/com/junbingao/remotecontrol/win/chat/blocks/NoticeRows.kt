package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.win.chat.resume.ResumeWords
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.hex
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/features/chat/blocks/NoticeRow.tsx`: one line in the notice voice — an icon and a
 * sentence, quiet unless it is a warning or an error.
 */
@Composable
fun NoticeLine(icon: LucideIcon, text: String, tone: NoticeLine.Tone = NoticeLine.Tone.plain, code: String? = null) {
    WithForeground(tone.ink) {
        HStack(Modifier.fillMaxWidth(), spacing = 6.dp) {
            Icon(icon, size = 13.dp)
            // The sentence wraps in the room the icon and the code leave it.
            ChatText(text, css(FontSize.fs13), Modifier.weight(1f, fill = false))
            if (code != null) Text(code, css(FontSize.fs11, mono = true), color = Palette.inkTertiary, softWrap = false)
        }
    }
}

object NoticeLine {
    enum class Tone {
        plain, warn, error;

        val ink: Color
            get() = when (this) {
                plain -> Palette.inkSecondary
                warn -> Color.hex(0x8A5312)
                error -> Palette.danger
            }
    }
}

/** `NoticeRow`: the device's own notice, at its level. */
@Composable
fun NoticeRow(notice: NoticePayload) {
    when (notice.level) {
        NoticeLevel.error -> NoticeLine(LucideIcon.circleAlert, notice.text, NoticeLine.Tone.error)
        NoticeLevel.warn -> NoticeLine(LucideIcon.alertTriangle, notice.text, NoticeLine.Tone.warn)
        else -> NoticeLine(LucideIcon.info, notice.text)
    }
}

/** `ErrorRow`: red, with the device's code after the sentence. */
@Composable
fun ErrorRow(error: ErrorPayload) {
    NoticeLine(LucideIcon.circleAlert, error.message, NoticeLine.Tone.error, code = error.code?.takeIf { it.isNotEmpty() })
}

/**
 * `TurnEndRow`: a turn that stopped or failed says so. A35: a turn the vendor's usage limit ended
 * is not a failure the reader can act on, so it reads as a notice and says when the limit resets.
 */
@Composable
fun TurnEndRow(turn: TurnCompletedPayload) {
    val limit = turn.limit
    when {
        limit != null -> NoticeLine(LucideIcon.clock, ResumeWords.limitEndText(limit))
        turn.stopReason == StopReason.interrupted -> NoticeLine(LucideIcon.alertTriangle, S.chat.turnInterrupted)
        else -> NoticeLine(LucideIcon.alertTriangle, S.chat.turnFailed, NoticeLine.Tone.error)
    }
}

/** The web's `ResumeRow` — A35 (5.15): what the device did about the resume, in the notice voice. The `fired` step draws nothing. */
@Composable
fun ResumeStepRow(resume: ResumePayload) {
    ResumeWords.rowText(resume)?.let { NoticeLine(LucideIcon.clock, it) }
}

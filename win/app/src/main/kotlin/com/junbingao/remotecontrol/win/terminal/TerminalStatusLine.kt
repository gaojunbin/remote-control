package com.junbingao.remotecontrol.win.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.settings.SettingsFlexWrap
import com.junbingao.remotecontrol.win.strings.S

/**
 * `StatusLine` in `TerminalPage.tsx`: Connecting · Connected · Disconnected with a Reconnect, and
 * the shell's own end with a New shell — one thin line holding the state, why it is that state,
 * and the one action it earns. A gap in `seq` is said rather than guessed at: bytes were lost, and
 * the screen below is missing them.
 */
@Composable
fun TerminalStatusLine(
    status: TerminalScreen.Status,
    exitCode: Int?,
    reason: String?,
    missedOutput: Boolean,
    onReconnect: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WithForeground(Palette.inkSecondary) {
        SettingsFlexWrap(modifier, spacing = Space.sp2, lineSpacing = 0.dp) {
            Text(word(status, exitCode), statusStyle, color = tint(status))
            if (reason != null) TerminalReason(reason)
            if (missedOutput) TerminalReason(S.terminal.gap)
            if (status == TerminalScreen.Status.disconnected) TerminalAction(S.terminal.reconnect, onReconnect)
            if (status == TerminalScreen.Status.exited) TerminalAction(S.terminal.newShell, onRestart)
        }
    }
}

private val statusStyle = css(FontSize.fs12, lineHeight = 1.45f)

private fun word(status: TerminalScreen.Status, exitCode: Int?): String = when (status) {
    TerminalScreen.Status.exited -> exitCode?.let { S.terminal.exitedCode(it) } ?: S.terminal.exited
    TerminalScreen.Status.connected -> S.terminal.connected
    TerminalScreen.Status.disconnected -> S.terminal.disconnected
    TerminalScreen.Status.connecting -> S.terminal.connecting
}

private fun tint(status: TerminalScreen.Status): Color = when (status) {
    TerminalScreen.Status.connected -> Palette.running
    TerminalScreen.Status.disconnected -> Palette.attention
    TerminalScreen.Status.connecting, TerminalScreen.Status.exited -> Palette.inkSecondary
}

/** `.terminal-reason`: what the state has to say, after a faint dot. */
@Composable
private fun TerminalReason(text: String) {
    val line = buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.lineStrong)) { append("· ") }
        append(text)
    }
    Text(line, statusStyle)
}

/** `.link-btn.terminal-action`: an underlined word in the ink, the underline two px below the baseline as `text-underline-offset: 2px` puts it. */
@Composable
private fun TerminalAction(title: String, action: () -> Unit) {
    Button(action) {
        Text(
            title,
            statusStyle,
            Modifier.drawBehind {
                drawRect(Palette.ink, topLeft = Offset(0f, (statusStyle.baseline + 2).dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            },
            color = Palette.ink,
        )
    }
}

package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.screens.chat.markdown.PatchView
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.ApprovalDecision
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.TimelineEntry

/**
 * A tool the agent wants to run, and the exact choices the device offered.
 *
 * The app never invents an option id and never assumes what "allow" is called: the primary and
 * danger styles decide placement, the ids go back verbatim. The destructive choice is deliberately
 * kept away from the primary one, so a one-handed tap cannot land on it by accident.
 */
@Composable
internal fun ApprovalCard(entry: TimelineEntry, payload: ApprovalPayload, chat: ChatStore) {
    val model = LocalAppModel.current
    var isSending by remember { mutableStateOf(false) }
    val actionable = payload.status.isActionable
    val isActive = actionable && !chat.isReadOnly
    val send = { optionID: String ->
        if (!isSending) {
            isSending = true
            model.perform {
                chat.approve(requestID = payload.requestID, optionID = optionID)
                isSending = false
            }
        }
    }
    Column(
        Modifier.cardEdge(
            stroke = if (actionable) Theme.attention.copy(alpha = 0.5f) else Theme.border,
            width = if (actionable) 1.dp else 0.5.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.small),
    ) {
        Foreground(if (actionable) Theme.attention else Theme.inkSecondary) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sf.handRaised, font = SystemFont.footnote)
                Text(
                    if (actionable) L10n.string("Approval needed") else L10n.string(if (payload.status == RequestStatus.expired) "This request expired" else "Answered"),
                    Modifier.weight(1f).testTag("chat.approval"),
                    style = SystemFont.footnote.weight(FontWeight.Medium),
                )
                Text(payload.tool, style = SystemFont.caption, color = Theme.inkSecondary)
            }
        }
        SelectionContainer {
            Text(payload.title, style = Theme.monoBody, color = Theme.ink)
        }
        payload.input?.get("cwd")?.stringValue?.let { CodeText(it) }
        payload.diff?.let { diff ->
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                CodeText(diff.path, Modifier.weight(1f, fill = false), color = Theme.ink)
                Text("+${diff.additions}", style = SystemFont.caption, color = Theme.added)
                Text("−${diff.deletions}", style = SystemFont.caption, color = Theme.removed)
            }
            diff.patch?.let { PatchView(it, foldedLineLimit = 12) }
        }
        if (actionable) {
            Options(payload, enabled = !isSending && isActive, send)
        } else {
            payload.decision?.let { decision ->
                Text(
                    ApprovalWords.resolution(payload, decision),
                    Modifier.testTag("approval.resolution"),
                    style = SystemFont.footnote,
                    color = Theme.inkSecondary,
                )
            }
        }
    }
}

/** Primary first, then the middle options, then the danger option at the far end of the card. */
@Composable
private fun Options(payload: ApprovalPayload, enabled: Boolean, send: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        payload.primaryOption?.let { primary ->
            Button({ send(primary.id) }, Modifier.fillMaxWidth().testTag("approval.primary"), enabled = enabled, style = PrimaryButtonStyle()) {
                Text(primary.label)
            }
        }
        for (option in payload.otherOptions) {
            Box(Modifier.fillMaxWidth().heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.Center) {
                Button({ send(option.id) }, enabled = enabled, style = ChipButtonStyle) { Text(option.label) }
            }
        }
        payload.dangerOption?.let { danger ->
            Button(
                { send(danger.id) },
                Modifier
                    .padding(top = Theme.Space.tight)
                    .fillMaxWidth()
                    .heightIn(min = Theme.Touch.primary)
                    .background(Theme.danger.copy(alpha = 0.08f), CapsuleShape)
                    .disabledLook(enabled)
                    .testTag("approval.danger"),
                enabled = enabled,
            ) {
                Text(danger.label, style = SystemFont.body.weight(FontWeight.Medium), color = Theme.danger)
            }
        }
    }
}

/** What a resolved card says. */
internal object ApprovalWords {
    /** Amendment A11: a request the device did not answer resolves with an option id it was never offered, so the line is the source alone. */
    fun resolution(payload: ApprovalPayload, decision: ApprovalDecision): String {
        val chosen = payload.resolvedOptionLabel ?: return source(decision.by)
        return "$chosen · ${source(decision.by)}"
    }

    fun source(by: EventSource): String = when (by) {
        EventSource.terminal -> L10n.string("answered in the terminal")
        EventSource.policy -> L10n.string("answered by a rule")
        else -> L10n.string("answered here")
    }
}

/**
 * `.card()` with the stroke the iPhone lays over it: a white card with its hairline border, and a
 * second edge in [stroke] drawn over that one — amber while the card waits on the reader. A border
 * modifier draws after what it wraps, so the overlay is the outer of the two.
 */
@Composable
internal fun Modifier.cardEdge(stroke: Color, width: Dp, padding: Dp = Theme.Space.medium): Modifier {
    val shape = ContinuousShape(Theme.Radius.card)
    return fillMaxWidth()
        .background(Theme.surface, shape)
        .border(width, stroke, shape)
        .border(0.5.dp, Theme.border, shape)
        .padding(padding)
}

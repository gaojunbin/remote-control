package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.Truncation
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.screens.chat.markdown.OutputBlock
import com.junbingao.remotecontrol.android.screens.chat.markdown.PatchView
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.IndicatorSize
import com.junbingao.remotecontrol.core.protocol.DiffPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.RelativeTime
import com.junbingao.remotecontrol.core.state.TimelineEntry

/** A single line that expands to the tool's input and output. */
@Composable
internal fun ToolCallRow(entry: TimelineEntry, payload: ToolCallPayload, chat: ChatStore, nested: Boolean = false) {
    val model = LocalAppModel.current
    val expanded = chat.isExpanded(entry.id)
    val children = chat.timeline.children(of = entry.id, at = chat.detail)
    val rule = Theme.border
    Column(
        Modifier
            .then(
                if (nested) {
                    Modifier.drawBehind {
                        val inset = 2.dp.toPx()
                        drawLine(rule, Offset(0.5.dp.toPx(), inset), Offset(0.5.dp.toPx(), size.height - inset), strokeWidth = 1.dp.toPx())
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = if (nested) Theme.Space.medium else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        Button(
            onClick = { chat.toggleExpanded(entry.id) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Theme.Touch.minimum)
                .semantics { contentDescription = "${payload.tool}, ${payload.title}, ${payload.status.rawValue}" }
                .testTag("chat.tool.${entry.id}"),
        ) {
            PriorityRow(Modifier.fillMaxWidth(), spacing = Theme.Space.tight) {
                Icon(if (expanded) Sf.chevronDown else Sf.chevronRight, font = SystemFont.caption2, tint = Theme.inkSecondary)
                Text(payload.tool, Modifier.flexible(), style = SystemFont.footnote.weight(FontWeight.Medium), color = Theme.ink, lineLimit = 1)
                Text(
                    payload.title,
                    Modifier.flexible().hugsLines(payload.title, Theme.mono, lineLimit = 1, truncation = Truncation.middle),
                    style = Theme.mono,
                    color = Theme.inkSecondary,
                    lineLimit = 1,
                    truncation = Truncation.middle,
                )
                RowSpacer(Theme.Space.tight)
                Trailing(payload)
            }
        }
        payload.diff?.let { DiffSummary(it) }
        if (expanded) Detail(entry, payload) { model.perform { chat.loadFullBlock(entry.id) } }
        if (children.isNotEmpty()) {
            Column(Modifier.padding(start = Theme.Space.medium), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
                for (child in children) key(child.id) { TimelineRow(child, chat, nested = true) }
            }
        }
        val output = payload.output
        if (payload.status == ToolStatus.running && !output.isNullOrEmpty() && !expanded) {
            OutputBlock(output, foldedLineLimit = 6)
        }
    }
}

/** The result chip, then the spinner while it runs or how long it took. */
@Composable
private fun Trailing(payload: ToolCallPayload) {
    Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
        val summary = payload.summary
        if (!summary.isNullOrEmpty()) {
            val failed = payload.status == ToolStatus.failed
            Text(
                summary,
                Modifier
                    .background(if (failed) Theme.danger.copy(alpha = 0.1f) else Theme.surfaceSunken, CapsuleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = SystemFont.caption,
                color = if (failed) Theme.danger else Theme.inkSecondary,
                lineLimit = 1,
            )
        }
        val duration = payload.durationMS
        if (payload.status == ToolStatus.running) {
            ActivityIndicator(size = IndicatorSize.mini)
        } else if (duration != null) {
            Text(RelativeTime.duration(milliseconds = duration), style = SystemFont.caption, color = Theme.inkSecondary, lineLimit = 1)
        }
    }
}

@Composable
private fun Detail(entry: TimelineEntry, payload: ToolCallPayload, openFull: () -> Unit) {
    Column(Modifier.padding(start = Theme.Space.medium), verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
        payload.input?.let { input ->
            Text(L10n.string("Input"), style = SystemFont.caption2.weight(FontWeight.SemiBold), color = Theme.inkSecondary)
            OutputBlock(ToolInputText.render(input), foldedLineLimit = 12, isTruncated = payload.inputTruncated, onOpenFull = openFull)
        }
        val output = payload.output
        if (!output.isNullOrEmpty()) {
            Text(L10n.string("Output"), style = SystemFont.caption2.weight(FontWeight.SemiBold), color = Theme.inkSecondary)
            OutputBlock(output, isTruncated = payload.outputTruncated, onOpenFull = openFull)
        }
        payload.diff?.patch?.let { PatchView(it) }
    }
}

@Composable
private fun DiffSummary(diff: DiffPayload) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Theme.Space.medium)
            .semantics(mergeDescendants = true) {
                contentDescription = L10n.string("%@, %lld added, %lld removed", diff.path, diff.additions, diff.deletions)
            },
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodeText(diff.path, Modifier.weight(1f, fill = false), color = Theme.ink)
        Text("+${diff.additions}", style = SystemFont.caption, color = Theme.added)
        Text("−${diff.deletions}", style = SystemFont.caption, color = Theme.removed)
    }
}

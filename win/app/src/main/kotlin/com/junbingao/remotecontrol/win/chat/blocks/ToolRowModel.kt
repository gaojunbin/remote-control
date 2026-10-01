package com.junbingao.remotecontrol.win.chat.blocks

import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.max

/**
 * What `web/src/features/chat/blocks/ToolRow.tsx` decides before it draws a tool call: whether
 * there is anything to open, what opening shows, and the word at the row's trailing edge.
 */
data class ToolRowModel(
    val running: Boolean,
    val failed: Boolean,
    val hasDetail: Boolean,
    /** A running tool shows its live output; everything else only once opened. */
    val expanded: Boolean,
    val showsInput: Boolean,
    val showsOutput: Boolean,
    val showsDiff: Boolean,
    /**
     * A27: a slash command's block is titled with the command itself, so the name and the title
     * are one and the same word, printed once.
     */
    val showsTitle: Boolean,
) {
    /** "running 3.2s", the duration, Expand or Collapse, or nothing. */
    fun trailing(tool: ToolCallPayload, now: Long = Format.nowMillis): String {
        if (running) {
            val elapsed = tool.startedAt?.let { Format.duration(max(0, now - it).toDouble()) } ?: ""
            return "${S.chat.running} $elapsed"
        }
        tool.durationMS?.let { return Format.duration(it.toDouble()) }
        if (!hasDetail) return ""
        return if (expanded) S.common.collapse else S.common.expand
    }

    companion object {
        operator fun invoke(tool: ToolCallPayload, open: Boolean): ToolRowModel {
            val running = tool.status == ToolStatus.running
            return ToolRowModel(
                running = running,
                failed = tool.status == ToolStatus.failed,
                hasDetail = tool.input != null || tool.output != null || tool.diff?.patch != null,
                expanded = open || running,
                showsInput = open && tool.input != null,
                showsOutput = if (open) tool.output != null else running && !tool.output.isNullOrEmpty(),
                showsDiff = open && !tool.diff?.patch.isNullOrEmpty(),
                showsTitle = tool.title != tool.tool,
            )
        }
    }
}

/** `toolIcons.tsx`: one lucide icon per `tool_kind`, the wrench for the rest. */
object ToolIcon {
    fun icon(kind: ToolKind): LucideIcon = when (kind) {
        ToolKind.shell -> LucideIcon.terminal
        ToolKind.read -> LucideIcon.fileText
        ToolKind.edit -> LucideIcon.fileEdit
        ToolKind.write -> LucideIcon.filePlus
        ToolKind.search -> LucideIcon.search
        ToolKind.web -> LucideIcon.globe
        ToolKind.mcp -> LucideIcon.plug
        ToolKind.subagent -> LucideIcon.bot
        ToolKind.todo -> LucideIcon.listChecks
        else -> LucideIcon.wrench
    }
}

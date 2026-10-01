package com.junbingao.remotecontrol.win.chat.header

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.shared.Attach
import com.junbingao.remotecontrol.win.shared.Format

/**
 * What `web/src/features/chat/ChatHeader.tsx` works out before it draws: the Stop button, the todo
 * chip's counts and the usage chip's words.
 */
data class ChatHeaderModel(
    /** Stop is offered: a turn this app may end is running. */
    val offersStop: Boolean,
    /** "Todos 1/4", or null when the chip is not drawn. */
    val todos: TodoCounts?,
    /** "48.2k · 1m 12s", or null when neither half has anything to say. */
    val usage: String?,
) {
    companion object {
        operator fun invoke(
            session: Session,
            agent: AgentInfo?,
            todos: List<TodoItem>,
            detail: TimelineDetail,
            now: Long = Format.nowMillis,
        ): ChatHeaderModel {
            // A7: a terminal-driven turn also reports `running`, but only the terminal can stop
            // it — the user has to take over first. A10: an attached session can be stopped only
            // when the device says the attachment carries an interrupt.
            val stoppable = if (session.control == SessionControl.shared) Attach.canInterruptShared(agent) else session.control != SessionControl.terminal
            val offersStop = (session.state == SessionState.running || session.state == SessionState.starting) && stoppable

            // A checklist is part of the agent's workings: Simple does not draw it.
            val doneCount = todos.count { it.status == TodoStatus.completed }
            val total = session.todos?.total ?: todos.size
            val done = if (todos.isEmpty()) session.todos?.done ?: 0 else doneCount
            val counts = if (detail == TimelineDetail.detailed && total > 0) TodoCounts(total = total, done = done) else null

            val tokens = session.usage?.totalTokens ?: 0
            val elapsed = session.turn?.let { Format.duration((now - it.startedAt).toDouble()) } ?: ""
            val usage = when {
                tokens > 0 && elapsed.isNotEmpty() -> "${Format.compactNumber(tokens.toDouble())} · $elapsed"
                tokens > 0 -> Format.compactNumber(tokens.toDouble())
                elapsed.isNotEmpty() -> elapsed
                else -> null
            }
            return ChatHeaderModel(offersStop = offersStop, todos = counts, usage = usage)
        }
    }
}

package com.junbingao.remotecontrol.win.chat.header

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.FittingScroll
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S

/**
 * The header's todo chip, "Todos 1/4", and the checklist behind it. A checklist that reprinted
 * itself in the transcript would be noise, so it lives here (`docs/DESIGN.md` § "The timeline").
 */
@Composable
fun TodosPopover(counts: TodoCounts, todos: List<TodoItem>) {
    val stage = LocalPreviewStage.current
    Popover(
        align = PopoverAlign.end,
        ariaLabel = S.chat.todosTitle,
        initiallyOpen = stage == "chat.todos",
        label = {
            HStack(spacing = 6.dp) {
                Icon(LucideIcon.checkSquare, size = 13.dp)
                Text(S.chat.todos(counts.done, counts.total))
            }
        },
    ) { TodoList(todos) }
}

/**
 * `.todo-list`: one row per item, the pending ones in the secondary ink, the one in progress in the
 * ink, the finished ones struck through and quiet.
 */
@Composable
private fun TodoList(todos: List<TodoItem>) {
    FittingScroll(Modifier.heightIn(max = 320.dp)) {
        VStack(Modifier.padding(Space.sp1), spacing = 0.dp, alignment = Alignment.Start) {
            for (todo in todos) {
                val completed = todo.status == TodoStatus.completed
                WithForeground(ink(todo.status)) {
                    HStack(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = Space.sp2),
                        spacing = Space.sp2,
                        alignment = Alignment.Top,
                    ) {
                        Icon(if (completed) LucideIcon.checkSquare else LucideIcon.square, size = 13.dp, modifier = Modifier.padding(top = 2.dp))
                        Text(
                            if (completed) AnnotatedString(todo.text, listOf(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.LineThrough), 0, todo.text.length))) else AnnotatedString(todo.text),
                            css(FontSize.fs13),
                            Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

private fun ink(status: TodoStatus): Color = when (status) {
    TodoStatus.inProgress -> Palette.ink
    TodoStatus.completed -> Palette.inkTertiary
    else -> Palette.inkSecondary
}

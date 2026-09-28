import RCCore
import SwiftUI

/// The header's todo chip, "Todos 1/4", and the checklist behind it. A
/// checklist that reprinted itself in the transcript would be noise, so it
/// lives here (`docs/DESIGN.md` § "The timeline").
struct TodosPopover: View {
    let counts: TodoCounts
    let todos: [TodoItem]
    @Environment(\.previewStage) private var stage

    var body: some View {
        Popover(align: .end, ariaLabel: S.chat.todosTitle, initiallyOpen: stage == "chat.todos") {
            HStack(spacing: 6) {
                Icon(.checkSquare, size: 13)
                Text(S.chat.todos(counts.done, counts.total))
            }
        } content: { _ in
            TodoList(todos: todos)
        }
    }
}

/// `.todo-list`: one row per item, the pending ones in the secondary ink, the
/// one in progress in the ink, the finished ones struck through and quiet.
private struct TodoList: View {
    let todos: [TodoItem]

    var body: some View {
        FittingScroll {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(todos) { todo in
                    HStack(alignment: .top, spacing: Space.sp2) {
                        Icon(todo.status == .completed ? .checkSquare : .square, size: 13)
                            .padding(.top, 2)
                        Text(verbatim: todo.text)
                            .css(FontSize.fs13)
                            .strikethrough(todo.status == .completed)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(ink(todo.status))
                    .padding(.vertical, 6)
                    .padding(.horizontal, Space.sp2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(Space.sp1)
        }
        .frame(maxHeight: 320)
    }

    private func ink(_ status: TodoStatus) -> Color {
        switch status {
        case .inProgress: Palette.ink
        case .completed: Palette.inkTertiary
        default: Palette.inkSecondary
        }
    }
}

import SwiftUI
import RCCore

/// Messages waiting behind the current turn, in the order they will go.
///
/// `docs/DESIGN.md` § "The composer" → **Up next** (A43): a row is taken out
/// of the line for good with a swipe, or with Remove in its context menu, and
/// nothing asks first. Tapping it takes the message back into the composer to
/// be edited: the list closes, the device lets go of the message, and the words
/// come into the field. A message that carries files is removed and never
/// edited, since its files are on the device and nothing brings them back; and
/// while the composer cannot send, or already holds a message being edited,
/// every row offers Remove and nothing else.
struct QueueSheet: View {
    let chat: ChatStore
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(chat.timeline.queue) { message in
                    QueueRow(message: message, canEdit: chat.canEdit(message),
                             edit: { edit(message) },
                             remove: { chat.removeQueued(message.id) })
                }
                if chat.timeline.queue.isEmpty {
                    Text("Nothing is queued.").font(.footnote).foregroundStyle(Theme.inkSecondary)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle("Up next")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }

    /// The list closes at once and the request to let go of the message goes
    /// with it; the field takes the words once the device has. They are out of
    /// the line from then on, so the draft that now holds them is saved at once.
    private func edit(_ message: QueuedMessage) {
        dismiss()
        Task {
            await chat.beginEdit(message)
            await model.saveDraft()
        }
    }
}

/// One queued message: two lines of its words at most, how long it has waited,
/// and, for a message that carries files, a paperclip and how many.
private struct QueueRow: View {
    let message: QueuedMessage
    let canEdit: Bool
    let edit: () -> Void
    let remove: () -> Void

    var body: some View {
        entry
            .swipeActions {
                Button("Remove", role: .destructive, action: remove)
            }
            .contextMenu {
                if canEdit { Button("Edit", systemImage: "pencil", action: edit) }
                Button("Remove", systemImage: "trash", role: .destructive, action: remove)
            }
            .accessibilityIdentifier("queue.entry")
    }

    /// A row that can be edited is a button, and one that cannot is only read,
    /// so a tap on it does nothing at all.
    @ViewBuilder
    private var entry: some View {
        if canEdit {
            Button(action: edit) { content }
                .buttonStyle(.plain)
        } else {
            content.accessibilityElement(children: .combine)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(message.text)
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: Theme.Space.tight) {
                Text(RelativeTime.short(since: message.ts))
                if message.carriesFiles, let files = message.attachments {
                    HStack(spacing: 2) {
                        Image(systemName: "paperclip")
                        Text(verbatim: "\(files)")
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L10n.string(files == 1 ? "%lld file" : "%lld files", files))
                }
            }
            .font(.caption)
            .foregroundStyle(Theme.inkSecondary)
        }
        .contentShape(Rectangle())
    }
}

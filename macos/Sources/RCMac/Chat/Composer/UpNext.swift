import RCCore
import SwiftUI

/// A43 — the queue as one control (`web/src/features/chat/UpNext.tsx`). The
/// chip that leads the control row says how many messages wait behind the
/// turn, and only while one does; the list it opens has them in the order they
/// will go, one line each. × takes a message out of the line for good, and a
/// click on one edits it. A message that carries files has only the ×: its
/// files are on the device, and nothing can bring them back into the field.
struct UpNext: View {
    let composer: ComposerModel
    let initiallyOpen: Bool

    var body: some View {
        let queue = composer.chat.timeline.queue
        // The last one removed takes the chip with it, and the open list goes too.
        if !queue.isEmpty {
            Popover(align: .start, side: .top, chevron: false, triggerStyle: ComposerChipStyle(),
                    initiallyOpen: initiallyOpen) {
                Text(S.composer.upNextCount(queue.count)).css(FontSize.fs12)
            } content: { close in
                UpNextList(composer: composer, close: close)
            }
        }
    }
}

/// `.up-next`: the title and the rows, at least 240 points wide.
private struct UpNextList: View {
    let composer: ComposerModel
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(S.composer.upNext)
                .css(FontSize.fs12, weight: .medium)
                .foregroundStyle(Palette.inkSecondary)
                .padding(EdgeInsets(top: 6, leading: 10, bottom: 2, trailing: 10))
            CappedScroll(maximum: 320) {
                VStack(spacing: 0) {
                    ForEach(composer.chat.timeline.queue) { entry in
                        UpNextRow(entry: entry, editable: composer.canEdit(entry),
                                  onEdit: {
                                      // The list closes as the words go into the field.
                                      close()
                                      composer.edit(entry)
                                  },
                                  onRemove: { composer.remove(entry) })
                    }
                }
            }
        }
        .frame(minWidth: 240, alignment: .leading)
    }
}

/// One waiting message: a button that edits it, or plain text where it
/// cannot, and its ×.
private struct UpNextRow: View {
    let entry: QueuedMessage
    let editable: Bool
    let onEdit: () -> Void
    let onRemove: () -> Void
    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 2) {
            words
                .padding(.vertical, 7)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular)
                    .fill(editable && isHovered ? Palette.surfaceHover : Color.clear))
                .contentShape(Rectangle())
                .onHover { isHovered = $0 }
                .onTapGesture { if editable { onEdit() } }
                .pointerStyle(editable ? .link : nil)
                .help(entry.text)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(editable ? .isButton : [])
                .accessibilityAction { if editable { onEdit() } }
            IconBtn(.x, size: 14, label: S.chat.queuedRemove, action: onRemove)
        }
    }

    private var words: some View {
        let files = entry.attachments ?? 0
        return HStack(spacing: Space.sp2) {
            // `white-space: nowrap` runs the lines of a message into one.
            Text(entry.text.split(whereSeparator: \.isWhitespace).joined(separator: " "))
                .css(FontSize.fs14)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if files > 0 {
                HStack(spacing: 3) {
                    Icon(.paperclip, size: 12)
                    Text("\(files)").css(FontSize.fs12)
                }
                .foregroundStyle(Palette.inkSecondary)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(S.chat.attachments(files))
            }
        }
    }
}

/// A43: what sits over the field while it holds a queued message. Cancel puts
/// the words back as they were queued, in the place they left; while words
/// are on their way back there is nothing left to cancel.
struct EditingStrip: View {
    let canCancel: Bool
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: Space.sp3) {
            HStack(spacing: 6) {
                Icon(.pencil, size: 13)
                Text(S.composer.editingQueued).css(FontSize.fs13)
            }
            .foregroundStyle(Palette.inkSecondary)
            Spacer(minLength: 0)
            LinkButton(title: S.common.cancel, size: FontSize.fs13, action: onCancel)
                .disabled(!canCancel)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, Space.sp3)
        .background(RoundedRectangle(cornerRadius: Radius.md, style: .circular).fill(Palette.surfaceMuted))
    }
}

/// A list no taller than `maximum`, which scrolls past it: `max-height` with
/// `overflow-y: auto`, in a panel that is otherwise as tall as what it holds.
struct CappedScroll<Content: View>: View {
    let maximum: CGFloat
    @ViewBuilder let content: () -> Content
    @State private var height: CGFloat = 0

    var body: some View {
        ScrollView {
            content().onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(height: min(max(height, 1), maximum))
    }
}

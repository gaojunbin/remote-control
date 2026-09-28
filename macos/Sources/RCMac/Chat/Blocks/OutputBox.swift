import SwiftUI

/// `web/src/features/chat/blocks/OutputBox.tsx`: a monospace output pane,
/// folded beyond 20 lines, with "Show more" and — where the device withheld
/// part of it — "Open full output". A live pane shows everything and folds
/// nothing, because the tool is still writing to it.
struct OutputBox: View {
    let text: String
    var truncated = false
    var live = false
    var onOpenFull: (() async -> Void)?
    @State private var expanded = false
    @State private var loadingFull = false

    static let foldLines = 20

    var body: some View {
        let fold = Format.foldLines(text, maxLines: Self.foldLines)
        let shown = expanded || live ? text : fold.head
        VStack(spacing: 0) {
            ChatBoundedScroll(maxHeight: 380) {
                Text(verbatim: ChatPreText.display(shown))
                    .css(FontSize.fs12, lineHeight: 1.65, mono: true)
                    .foregroundStyle(Palette.ink)
                    .fixedSize()
                    .textSelection(.enabled)
                    .padding(.vertical, 10)
                    .padding(.horizontal, Space.sp3)
            }
            if (fold.folded && !live) || truncated {
                actions(fold: fold)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .chatBox(radius: Radius.sm, background: Palette.surface)
    }

    private func actions(fold: (head: String, folded: Bool, total: Int)) -> some View {
        HStack(spacing: Space.sp3) {
            if fold.folded && !live {
                Button { expanded.toggle() } label: {
                    ChatLinkLabel(expanded ? S.common.showLess : "\(S.common.showMore) (\(fold.total) lines)")
                }
                .buttonStyle(.chatLink)
            }
            if truncated {
                Button {
                    guard let onOpenFull else { return }
                    loadingFull = true
                    Task {
                        await onOpenFull()
                        loadingFull = false
                    }
                } label: {
                    ChatLinkLabel(loadingFull ? S.common.loading : S.chat.openFullOutput)
                }
                .buttonStyle(.chatLink)
                .disabled(loadingFull || onOpenFull == nil)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, Space.sp3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceSunken)
        // `border-top`, which takes a point of its own above the padding.
        .padding(.top, 1)
        .background(alignment: .top) { Rectangle().fill(Palette.line).frame(height: 1) }
    }
}

/// The words of a `.link-btn` at the 12-point size of the row it sits in.
struct ChatLinkLabel: View {
    let text: String
    var size: CGFloat = FontSize.fs12

    init(_ text: String, size: CGFloat = FontSize.fs12) {
        self.text = text
        self.size = size
    }

    var body: some View {
        ChatUnderlinedText(text: text, style: TextStyle(size: size))
    }
}

/// Text laid out as `white-space: pre` or `pre-wrap` lays it out. A line feed
/// that ends the text ends its last line and starts no new one.
enum ChatPreText {
    static func display(_ text: String) -> String {
        text.hasSuffix("\n") ? String(text.dropLast()) : text
    }
}

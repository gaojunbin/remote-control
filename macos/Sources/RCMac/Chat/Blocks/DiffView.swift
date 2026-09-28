import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/DiffView.tsx`: a unified patch, one line per
/// row, additions and deletions on their tints, hunk and file headers quiet.
struct DiffView: View {
    let diff: DiffPayload
    @State private var width: CGFloat = 0

    var body: some View {
        if let patch = diff.patch, !patch.isEmpty {
            VStack(spacing: 0) {
                ChatBoundedScroll(maxHeight: 420) {
                    ChatEqualWidthColumn(minWidth: width) {
                        ForEach(Array(patch.split(separator: "\n", omittingEmptySubsequences: false).enumerated()),
                                id: \.offset) { _, line in
                            DiffLine(line: line)
                        }
                    }
                    .padding(.vertical, Space.sp2)
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                if diff.patchTruncated {
                    Text(S.chat.patchTruncated)
                        .css(FontSize.fs12)
                        .foregroundStyle(Palette.inkSecondary)
                        .padding(.top, 4)
                        .padding(.bottom, 6)
                        .padding(.horizontal, Space.sp3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 1)
                        .background(alignment: .top) { Rectangle().fill(Palette.line).frame(height: 1) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .chatBox(radius: Radius.sm)
        }
    }
}

/// `.diff-line`: a block the width of the pane, so its tint runs edge to edge.
private struct DiffLine: View {
    let line: Substring

    var body: some View {
        let kind = DiffLineKind(line)
        Text(verbatim: line.isEmpty ? " " : String(line))
            .css(FontSize.fs12, lineHeight: 1.6, mono: true)
            .fixedSize()
            .foregroundStyle(ink(kind))
            .textSelection(.enabled)
            .padding(.horizontal, Space.sp3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint(kind))
    }

    private func ink(_ kind: DiffLineKind) -> Color {
        switch kind {
        case .add: Palette.diffAdd
        case .del: Palette.diffDel
        case .hunk, .meta: Palette.inkTertiary
        case .context: Palette.ink
        }
    }

    private func tint(_ kind: DiffLineKind) -> Color {
        switch kind {
        case .add: Palette.diffAddBg
        case .del: Palette.diffDelBg
        default: .clear
        }
    }
}

/// `DiffStat`: "+12 −4" in the diff colours, monospace.
struct DiffStat: View {
    let diff: DiffPayload

    var body: some View {
        Text("\(Text(verbatim: "+\(diff.additions)").foregroundStyle(Palette.diffAdd)) \(Text(verbatim: "\u{2212}\(diff.deletions)").foregroundStyle(Palette.diffDel))")
            .css(FontSize.fs12, mono: true)
            .fixedSize()
    }
}

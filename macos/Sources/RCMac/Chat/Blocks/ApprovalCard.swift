import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/ApprovalCard.tsx`: a bordered card with the
/// agent's own options as buttons, accept first and reject last (`docs/
/// DESIGN.md` § "Approvals and questions"). Once resolved or expired it goes
/// quiet and says who decided and what.
struct ApprovalCard: View {
    let approval: ApprovalPayload
    let onDecide: (_ requestID: String, _ optionID: String) async -> Void
    @State private var busy: String?

    var body: some View {
        let pending = approval.status == .pending
        VStack(alignment: .leading, spacing: Space.sp3) {
            head
            if let diff = approval.diff, !(diff.patch ?? "").isEmpty { DiffView(diff: diff) }
            if let input = approval.input { OutputBox(text: JSONText.stringify(input)) }
            if pending {
                CardActions {
                    ForEach(CardRules.ordered(approval.options)) { option in
                        Button(option.label) { decide(option) }
                            .buttonStyle(.btn(variant(option.style), size: .small))
                            .disabled(busy != nil)
                    }
                }
            } else {
                Text(CardRules.approvalResult(approval))
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkSecondary)
            }
        }
        .cardFrame(resolved: !pending)
    }

    /// `.approval-head`: the tool's icon, its name and the monospace title.
    private var head: some View {
        HStack(spacing: Space.sp2) {
            Icon(ToolIcon.icon(approval.kind), size: 14)
            Text(verbatim: approval.tool)
                .css(FontSize.fs13, weight: .medium)
                .foregroundStyle(Palette.ink)
                .fixedSize()
            Text(verbatim: approval.title)
                .css(FontSize.fs12, mono: true)
                .foregroundStyle(Palette.inkSecondary)
                .lineLimit(1)
                .truncationMode(.tail)
                .textSelection(.enabled)
        }
        .foregroundStyle(Palette.inkTertiary)
    }

    private func variant(_ style: OptionStyle) -> ButtonVariant {
        switch style {
        case .primary: .primary
        case .danger: .danger
        default: .standard
        }
    }

    private func decide(_ option: ApprovalOption) {
        busy = option.id
        Task {
            await onDecide(approval.requestID, option.id)
            busy = nil
        }
    }
}

/// `.approval-actions`: the buttons in a row that wraps, 8 points apart.
struct CardActions<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) { self.content = content() }

    var body: some View {
        ChatWrapRow(spacing: Space.sp2) { content }
    }
}

extension View {
    /// `.approval` and `.question`: a 12-point card with a strong edge and a
    /// soft lift while it waits, a plain edge and three-quarter ink once it
    /// has been answered.
    func cardFrame(resolved: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        // The 16 points of padding sit inside the one-point edge.
        return padding(Space.sp4 + 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(Palette.surface))
            .chatBorder(ChatBorder(width: 1, radius: Radius.md), color: resolved ? Palette.line : Palette.lineStrong)
            .boxShadow(resolved ? BoxShadow(layers: []) : Shadow.one, in: shape)
            .opacity(resolved ? 0.75 : 1)
    }
}

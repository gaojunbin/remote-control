import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/ToolRow.tsx`: one line per tool call — its
/// icon, name, monospace title, diff counts, result chip and duration — that
/// opens to the input, the diff and the output. A running tool shows its live
/// output without being opened, and the rows a sub-agent produced hang under
/// it whether it is open or not.
struct ToolRow<Nested: View>: View {
    let blockID: String
    let tool: ToolCallPayload
    /// `.tool + .tool`: a row right after another tool row draws no top rule.
    let followsTool: Bool
    let onOpenFull: (String) async -> Void
    let nested: Nested
    let hasNested: Bool
    /// Nil until the row is first clicked.
    @State private var toggled: Bool?
    @Environment(\.previewStage) private var stage

    init(blockID: String, tool: ToolCallPayload, followsTool: Bool, hasNested: Bool,
         onOpenFull: @escaping (String) async -> Void, @ViewBuilder nested: () -> Nested) {
        self.blockID = blockID
        self.tool = tool
        self.followsTool = followsTool
        self.hasNested = hasNested
        self.onOpenFull = onOpenFull
        self.nested = nested()
    }

    /// A render opens every row there is something to open in, as clicks
    /// would, from the row's first layout on.
    private var open: Binding<Bool> {
        Binding(get: { toggled ?? (stage == "chat.tools.open" && ToolRowModel(tool, open: false).hasDetail) },
                set: { toggled = $0 })
    }

    var body: some View {
        let model = ToolRowModel(tool, open: open.wrappedValue)
        VStack(alignment: .leading, spacing: 0) {
            ToolHead(tool: tool, model: model, open: open)
            if model.expanded {
                ToolBody(blockID: blockID, tool: tool, model: model, hasNested: hasNested,
                         onOpenFull: onOpenFull) { nested }
            } else if hasNested {
                ToolChildren(margin: Space.sp5) { nested }
                    .padding(.bottom, Space.sp2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // The rules take their point: top and bottom always, the sides while
        // the tool runs.
        .padding(.horizontal, model.running ? 1 : 0)
        .padding(.vertical, 1)
        .background { background(model) }
        .chatBorder(border(model), color: Palette.line)
        // `margin: -1px 0`: neighbouring rules overlap the gap by a point.
        .padding(.vertical, -1)
    }

    /// `.tool.running` and `.tool:has(.tool-body)` round the rules; a row
    /// right after another draws its top rule transparent, except a running
    /// one, whose own `border` comes later in the sheet.
    private func border(_ model: ToolRowModel) -> ChatBorder {
        let radius: CGFloat = model.running || model.expanded ? Radius.md : 0
        if model.running { return ChatBorder(width: 1, radius: radius) }
        return ChatBorder(top: followsTool ? 0 : 1, bottom: 1, radius: radius)
    }

    @ViewBuilder private func background(_ model: ToolRowModel) -> some View {
        if model.running {
            RoundedRectangle(cornerRadius: Radius.md, style: .circular).fill(Palette.surfaceSunken)
        }
    }
}

/// `.tool-head`: the row's button, lit on hover while there is something to
/// open, and tinted red on a failed call.
private struct ToolHead: View {
    let tool: ToolCallPayload
    let model: ToolRowModel
    @Binding var open: Bool
    @State private var hovered = false

    var body: some View {
        Button { open.toggle() } label: {
            HStack(spacing: Space.sp2) {
                marker
                Text(verbatim: tool.tool)
                    .css(FontSize.fs13, weight: .medium)
                    .fixedSize()
                if model.showsTitle {
                    Text(verbatim: tool.title)
                        .css(FontSize.fs12, mono: true)
                        .foregroundStyle(Palette.inkSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .layoutPriority(-1)
                }
                if let diff = tool.diff { DiffStat(diff: diff) }
                if let summary = tool.summary { Badge(summary, tone: model.failed ? .error : .neutral) }
                if !model.showsTitle { Spacer(minLength: 0) }
                TrailingLabel(tool: tool, model: model)
            }
            .padding(.vertical, 9)
            .padding(.horizontal, Space.sp3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular).fill(fill))
            .contentShape(Rectangle())
        }
        .buttonStyle(.chatBare)
        .disabled(!model.hasDetail)
        .onHover { hovered = $0 }
        .pointerStyle(model.hasDetail ? .link : nil)
        .accessibilityLabel(tool.tool)
    }

    private var fill: Color {
        if hovered && model.hasDetail { return Palette.surfaceHover }
        return model.failed ? Palette.dangerSoft : .clear
    }

    /// `.tool-marker`: a pulsing green dot while the tool runs, its icon after.
    private var marker: some View {
        Group {
            if model.running {
                Dot(.tone(.working), pulses: true)
            } else {
                Icon(ToolIcon.icon(tool.kind), size: 13)
            }
        }
        .frame(width: 16)
        .foregroundStyle(Palette.inkTertiary)
    }
}

/// `.tool-right`: "running 3.2s" while it runs, ticking once a second.
private struct TrailingLabel: View {
    let tool: ToolCallPayload
    let model: ToolRowModel

    var body: some View {
        if model.running {
            TimelineView(.periodic(from: .now, by: 1)) { _ in label }
        } else {
            label
        }
    }

    private var label: some View {
        Text(verbatim: model.trailing(tool))
            .css(FontSize.fs12)
            .foregroundStyle(Palette.inkTertiary)
            .fixedSize()
    }
}

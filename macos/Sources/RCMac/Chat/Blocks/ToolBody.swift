import RCCore
import SwiftUI

/// `.tool-body`: what an open tool row shows — the input, the diff, the
/// output — and the rows nested under it, 8 points apart. A running tool that
/// was not opened shows its live output alone.
struct ToolBody<Nested: View>: View {
    let blockID: String
    let tool: ToolCallPayload
    let model: ToolRowModel
    let hasNested: Bool
    let onOpenFull: (String) async -> Void
    let nested: Nested

    init(blockID: String, tool: ToolCallPayload, model: ToolRowModel, hasNested: Bool,
         onOpenFull: @escaping (String) async -> Void, @ViewBuilder nested: () -> Nested) {
        self.blockID = blockID
        self.tool = tool
        self.model = model
        self.hasNested = hasNested
        self.onOpenFull = onOpenFull
        self.nested = nested()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp2) {
            if model.showsInput, let input = tool.input {
                ToolSection(label: S.chat.input) {
                    OutputBox(text: JSONText.stringify(input), truncated: tool.inputTruncated,
                              onOpenFull: { await onOpenFull(blockID) })
                }
            }
            if model.showsDiff, let diff = tool.diff { DiffView(diff: diff) }
            if model.showsOutput {
                ToolSection(label: model.showsInput ? S.chat.output : nil) {
                    OutputBox(text: tool.output ?? "", truncated: tool.outputTruncated, live: model.running,
                              onOpenFull: { await onOpenFull(blockID) })
                }
            }
            if hasNested { ToolChildren { nested } }
        }
        .padding(.leading, Space.sp5)
        .padding(.trailing, Space.sp3)
        .padding(.bottom, Space.sp3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// `.tool-section`: an optional 11-point caption over its pane.
private struct ToolSection<Content: View>: View {
    let label: String?
    let content: Content

    init(label: String?, @ViewBuilder content: () -> Content) {
        self.label = label
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let label {
                Text(label)
                    .css(FontSize.fs11)
                    .foregroundStyle(Palette.inkTertiary)
                    .padding(.bottom, 4)
            }
            content
        }
    }
}

/// `.tool-children`: a sub-agent's rows, on a rule 12 points in from the
/// body — or, under a closed row, 20 in from the row (`.collapsed-children`).
struct ToolChildren<Content: View>: View {
    let margin: CGFloat
    let content: Content

    init(margin: CGFloat = Space.sp3, @ViewBuilder content: () -> Content) {
        self.margin = margin
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp2) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, Space.sp3 + 1)
            .background(alignment: .leading) { Rectangle().fill(Palette.line).frame(width: 1) }
            .padding(.leading, margin)
    }
}

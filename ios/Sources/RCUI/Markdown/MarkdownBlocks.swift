import SwiftUI
import RCCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct MarkdownBlockView: View {
    let block: MarkdownBlock
    var body: some View {
        switch block.content {
        case .paragraph(let text): MarkdownInlineView(text: text)
        case .heading(let level, let text):
            MarkdownInlineView(text: text)
                .font(level == 1 ? .title2.weight(.bold) : level == 2 ? .title3.weight(.semibold) : .headline)
                .padding(.top, level <= 2 ? 5 : 2).accessibilityAddTraits(.isHeader)
        case .code(let language, let source, let closed): MarkdownCodeCard(language: language, source: source, closed: closed)
        case .quote(let blocks):
            HStack(alignment: .top, spacing: 12) {
                RoundedRectangle(cornerRadius: 2).fill(Theme.accent.opacity(0.55)).frame(width: 3)
                AnyView(VStack(alignment: .leading, spacing: 10) { ForEach(blocks) { child in MarkdownBlockView(block: child) } })
                    .foregroundStyle(.secondary)
            }.fixedSize(horizontal: false, vertical: true).padding(.vertical, 4)
                .accessibilityElement(children: .contain).accessibilityLabel("Quotation")
        case .list(let ordered, let items):
            VStack(alignment: .leading, spacing: 10) {
                ForEach(items) { item in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        if let checked = item.checked {
                            Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(checked ? Theme.accent : Color.secondary)
                                .accessibilityLabel(checked ? "Completed" : "Not completed")
                        } else {
                            Text(ordered ? "\(item.number ?? 1)." : "•")
                                .monospacedDigit().foregroundStyle(.secondary)
                                .frame(minWidth: ordered ? 22 : 10, alignment: .trailing).accessibilityHidden(true)
                        }
                        AnyView(VStack(alignment: .leading, spacing: 8) { ForEach(item.blocks) { child in MarkdownBlockView(block: child) } })
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .table(let table): MarkdownTableView(table: table)
        case .thematicBreak: Divider().padding(.vertical, 5)
        case .math(let source): MarkdownCodeCard(language: "math", source: source, closed: true)
        }
    }
}

struct MarkdownInlineView: View {
    let text: String
    private var hasMath: Bool { MarkdownMath.spans(in: text).contains { $0.isMath } }
    var body: some View {
        Group {
            if hasMath { MarkdownVisualView(kind: .inlineMath, source: text) }
            else { Text(markdownAttributed(text)).lineSpacing(5).fixedSize(horizontal: false, vertical: true) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

func markdownAttributed(_ source: String) -> AttributedString {
    (try? AttributedString(markdown: source, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(source)
}

private struct MarkdownTableView: View {
    let table: MarkdownTable
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ScrollView(.horizontal) {
                Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                    GridRow {
                        ForEach(table.headers.indices, id: \.self) { index in
                            cell(table.headers[index], column: index, header: true)
                        }
                    }.background(Theme.accent.opacity(0.10))
                    ForEach(table.rows.indices, id: \.self) { row in
                        GridRow {
                            ForEach(table.headers.indices, id: \.self) { column in
                                cell(table.rows[row][column], column: column, header: false)
                                    .accessibilityLabel("\(table.headers[column]): \(table.rows[row][column])")
                            }
                        }.background(row.isMultiple(of: 2) ? Color.primary.opacity(0.025) : .clear)
                    }
                }.fixedSize(horizontal: true, vertical: false)
            }.background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.primary.opacity(0.09)))
            Label("Scroll sideways to read the table", systemImage: "arrow.left.and.right")
                .font(.caption2).foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }
    private func cell(_ value: String, column: Int, header: Bool) -> some View {
        Text(markdownAttributed(value)).font(header ? .subheadline.weight(.semibold) : .subheadline)
            .textSelection(.enabled).lineSpacing(3)
            .frame(minWidth: 108, idealWidth: 170, maxWidth: 230, alignment: alignment(column))
            .padding(.horizontal, 12).padding(.vertical, 11)
            .overlay(alignment: .bottom) { Rectangle().fill(.primary.opacity(0.07)).frame(height: 0.5) }
    }
    private func alignment(_ index: Int) -> Alignment {
        switch table.alignments[index] { case .leading: .leading; case .center: .center; case .trailing: .trailing }
    }
}

struct MarkdownCodeCard: View {
    let language: String
    let source: String
    let closed: Bool
    @State private var sourceVisible = false
    @State private var copied = false
    @State private var expanded = false
    private var kind: MarkdownVisualKind? {
        if language == "mermaid" { return .diagram }
        if ["math", "latex", "tex"].contains(language) { return .math }
        return nil
    }
    private var rendered: Bool { kind != nil && closed && !sourceVisible }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 3) {
                Text(kind == .diagram ? "Diagram" : kind == .math ? "Formula" : language.isEmpty ? "Code" : language)
                    .font(.caption.weight(.medium)).foregroundStyle(.secondary)
                if !closed { Text("Still writing").font(.caption2).foregroundStyle(.secondary) }
                Spacer(minLength: 0)
                if kind != nil && closed {
                    Button { sourceVisible.toggle() } label: { Image(systemName: sourceVisible ? "chart.xyaxis.line" : "chevron.left.forwardslash.chevron.right").frame(width: 40, height: 44) }
                        .accessibilityLabel(sourceVisible ? "Show the rendered version" : "Show the source")
                    Button { expanded = true } label: { Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 40, height: 44) }.accessibilityLabel("Open full screen")
                }
                Button { copy() } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc").frame(width: 40, height: 44) }
                    .accessibilityLabel(copied ? "Copied" : "Copy the whole block")
                ShareLink(item: source) { Image(systemName: "square.and.arrow.up").frame(width: 40, height: 44) }.accessibilityLabel("Share this block")
            }.buttonStyle(.plain).foregroundStyle(Theme.accent)
            if rendered, let kind { MarkdownVisualView(kind: kind, source: source) }
            else { codeText }
        }.padding(.horizontal, 12).padding(.bottom, 12)
            .background(.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
            .sheet(isPresented: $expanded) {
                NavigationStack {
                    ScrollView { VStack(alignment: .leading, spacing: 20) {
                        if let kind { MarkdownVisualView(kind: kind, source: source, maximumHeight: 1600) }
                        codeText
                    }.padding(20) }
                    .navigationTitle(kind == .diagram ? "Diagram" : "Formula")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { expanded = false } } }
                }.sheetSize()
            }
    }
    private var codeText: some View {
        ScrollView(.horizontal) {
            Text(source.isEmpty ? " " : source).font(.system(.callout, design: .monospaced))
                .textSelection(.enabled).fixedSize(horizontal: true, vertical: false)
                .lineSpacing(4).padding(.vertical, 5)
        }.accessibilityLabel("Code").accessibilityValue(source)
    }
    private func copy() {
        #if os(iOS)
        UIPasteboard.general.string = source
        #else
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(source, forType: .string)
        #endif
        copied = true
        Task { try? await Task.sleep(for: .seconds(2)); copied = false }
    }
}

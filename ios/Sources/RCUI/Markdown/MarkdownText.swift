import SwiftUI
import RCCore

/// Renders agent Markdown natively: a bounded block parser, SwiftUI views for
/// every block, and a sandboxed web view only for formulas and diagrams.
///
/// Streaming text keeps one parser per visible view and reparses at most ten
/// times a second, so a long answer does not stall the timeline.
public struct MarkdownText: View {
    let text: String
    var onFileLink: ((String) -> Void)?
    @StateObject private var parser: MarkdownParseModel

    public init(_ text: String, onFileLink: ((String) -> Void)? = nil) {
        self.text = text
        self.onFileLink = onFileLink
        // The autoclosure seeds only a newly mounted view identity; streaming
        // updates continue through the existing coalescing worker.
        _parser = StateObject(wrappedValue: MarkdownParseModel(initialSource: text))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small + 2) {
            ForEach(parser.document.blocks) { block in MarkdownBlockView(block: block) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .font(.body)
        .foregroundStyle(Theme.ink)
        .textSelection(.enabled)
        .environment(\.openURL, OpenURLAction { url in
            let scheme = url.scheme?.lowercased()
            if scheme == "https" || scheme == "http" || scheme == "mailto" { return .systemAction }
            if let path = MarkdownLink.filePath(for: url) {
                guard let onFileLink else { return .discarded }
                onFileLink(path)
                return .handled
            }
            return .discarded
        })
        .onAppear { parser.submit(text) }
        .onChange(of: text) { _, latest in parser.submit(latest) }
        .onDisappear { parser.cancel() }
    }
}

/// A unified patch with coloured additions and removals.
public struct PatchView: View {
    let patch: String
    var foldedLineLimit = 20
    @State private var expanded = false

    public init(patch: String, foldedLineLimit: Int = 20) {
        self.patch = patch
        self.foldedLineLimit = foldedLineLimit
    }

    private var lines: [String] { patch.components(separatedBy: "\n") }
    private var visible: [String] { expanded ? lines : Array(lines.prefix(foldedLineLimit)) }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.offset) { _, line in
                        Text(line.isEmpty ? " " : line)
                            .font(Theme.mono)
                            .foregroundStyle(color(for: line))
                            .textSelection(.enabled)
                            .padding(.horizontal, Theme.Space.small)
                            .padding(.vertical, 1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(background(for: line))
                    }
                }
                .fixedSize(horizontal: true, vertical: false)
            }
            if lines.count > foldedLineLimit {
                Button(expanded ? L10n.string("Show less")
                                : L10n.string("Show all %lld lines", lines.count)) { expanded.toggle() }
                    .font(.footnote)
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.inkSecondary)
                    .frame(maxWidth: .infinity, minHeight: Theme.Touch.minimum)
            }
        }
        .background(Theme.surfaceSunken, in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
    }

    private func color(for line: String) -> Color {
        if line.hasPrefix("+") && !line.hasPrefix("+++") { return Theme.added }
        if line.hasPrefix("-") && !line.hasPrefix("---") { return Theme.removed }
        return Theme.inkSecondary
    }

    private func background(for line: String) -> Color {
        if line.hasPrefix("+") && !line.hasPrefix("+++") { return Theme.added.opacity(0.08) }
        if line.hasPrefix("-") && !line.hasPrefix("---") { return Theme.removed.opacity(0.08) }
        return .clear
    }
}

/// Fixed-width output with a fold beyond roughly twenty lines.
public struct OutputBlock: View {
    let text: String
    var foldedLineLimit = 20
    var onOpenFull: (() -> Void)?
    var isTruncated = false
    @State private var expanded = false

    public init(text: String, foldedLineLimit: Int = 20, isTruncated: Bool = false,
                onOpenFull: (() -> Void)? = nil) {
        self.text = text
        self.foldedLineLimit = foldedLineLimit
        self.isTruncated = isTruncated
        self.onOpenFull = onOpenFull
    }

    private var lines: [String] { text.components(separatedBy: "\n") }

    public var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            ScrollView(.horizontal) {
                Text(expanded ? text : lines.prefix(foldedLineLimit).joined(separator: "\n"))
                    .font(Theme.mono)
                    .foregroundStyle(Theme.ink)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(Theme.Space.small)
            }
            HStack(spacing: Theme.Space.medium) {
                if lines.count > foldedLineLimit {
                    Button(expanded ? L10n.string("Fold")
                                    : L10n.string("Show all %lld lines", lines.count)) { expanded.toggle() }
                }
                if isTruncated, let onOpenFull {
                    Button("Open full output", action: onOpenFull)
                }
            }
            .font(.footnote)
            .buttonStyle(.plain)
            .foregroundStyle(Theme.inkSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.Space.small)
            .padding(.bottom, lines.count > foldedLineLimit || isTruncated ? Theme.Space.small : 0)
        }
        .background(Theme.surfaceSunken, in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
    }
}

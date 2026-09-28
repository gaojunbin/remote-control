import Foundation

/// A fenced block as the web draws it: `<pre><code>`, highlighted by
/// rehype-highlight when its language is one of highlight.js's common ones.
/// The theme's colours are worked out span by span here, once.
enum MDCodeBuilder {
    static func code(_ pre: HastElement) -> MDCode {
        let code = pre.elements.first { $0.tag == "code" }
        let classes = code?.properties.className ?? []
        let highlighted = classes.contains("hljs")
        // Unhighlighted code keeps the ink of the page; `.hljs` sets its own.
        let base = highlighted ? HighlightTheme.base : HighlightStyle(color: 0x111111)
        var runs: [MDCodeRun] = []
        walk(code?.children ?? pre.children, style: base, ancestors: [], into: &runs)
        return MDCode(highlighted: highlighted, lines: lines(runs), source: code?.textContent ?? pre.textContent)
    }

    private static func walk(_ nodes: [HastNode], style: HighlightStyle, ancestors: [[String]],
                             into runs: inout [MDCodeRun]) {
        for node in nodes {
            switch node {
            case .text(let value):
                runs.append(MDCodeRun(text: value, style: style))
            case .element(let element):
                let classes = element.properties.className
                let own = HighlightTheme.style(classes: classes, ancestors: ancestors, inherited: style)
                walk(element.children, style: own, ancestors: ancestors + [classes], into: &runs)
            }
        }
    }

    /// The runs cut at every line feed. The line feed that ends the block ends
    /// its last line and starts no other, as in a `<pre>`.
    static func lines(_ runs: [MDCodeRun]) -> [[MDCodeRun]] {
        var lines: [[MDCodeRun]] = [[]]
        for run in runs {
            let pieces = run.text.split(separator: "\n", omittingEmptySubsequences: false)
            for (index, piece) in pieces.enumerated() {
                if index > 0 { lines.append([]) }
                if !piece.isEmpty { lines[lines.count - 1].append(MDCodeRun(text: String(piece), style: run.style)) }
            }
        }
        if lines.count > 1, lines.last?.isEmpty == true { lines.removeLast() }
        return lines
    }
}

/// A GFM table: its header row, its body rows and the alignment each cell
/// takes from its column (`align`, which React writes as `text-align`).
enum MDTableBuilder {
    static func table(_ table: HastElement) -> MDTable {
        var header: [MDCell] = []
        var rows: [[MDCell]] = []
        for section in table.elements {
            for row in section.elements where row.tag == "tr" {
                let cells = row.elements.filter { $0.tag == "th" || $0.tag == "td" }.map(cell)
                if section.tag == "thead" { header = cells } else { rows.append(cells) }
            }
        }
        let columns = ([header.count] + rows.map(\.count)).max() ?? 0
        return MDTable(header: header, rows: rows, columns: columns)
    }

    private static func cell(_ element: HastElement) -> MDCell {
        let header = element.tag == "th"
        let align: MDCell.Align = switch element.properties.align {
        case "center": .center
        case "right": .trailing
        default: .leading
        }
        return MDCell(inlines: MDInlines.build(element.children, marks: MDMarks(), base: header ? .medium : .regular),
                      align: align, header: header)
    }
}

import Foundation

/// Bounded, deterministic block parser for the Markdown emitted by coding agents.
/// It leaves inline styling to Foundation and never evaluates HTML or loads URLs.
public struct MarkdownDocument: Equatable, Sendable {
    public var blocks: [MarkdownBlock]
    public init(_ source: String) {
        let normalized = source.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let lines = normalized.components(separatedBy: "\n").enumerated().map { MarkdownLine(text: $0.element, number: $0.offset) }
        blocks = MarkdownParser.parse(lines, scope: "md", depth: 0)
    }
}

public struct MarkdownBlock: Identifiable, Equatable, Sendable {
    public let id: String
    public let sourceLine: Int
    public let content: Content
    public indirect enum Content: Equatable, Sendable {
        case paragraph(String)
        case heading(level: Int, text: String)
        case code(language: String, source: String, closed: Bool)
        case quote([MarkdownBlock])
        case list(ordered: Bool, items: [MarkdownListItem])
        case table(MarkdownTable)
        case thematicBreak
        case math(String)
    }
}
public struct MarkdownListItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let number: Int?
    public let checked: Bool?
    public let blocks: [MarkdownBlock]
}
public struct MarkdownTable: Equatable, Sendable {
    public let headers: [String]
    public let alignments: [Alignment]
    public let rows: [[String]]
    public enum Alignment: String, Equatable, Sendable { case leading, center, trailing }
}
public struct MarkdownMathSpan: Identifiable, Equatable, Sendable {
    public var id: Int
    public var text: String
    public var isMath: Bool
    public var display: Bool
}

public enum MarkdownLink {
    /// Remote file annotations use editor line suffixes; they are not part of the path.
    public static func filePath(for url: URL) -> String? {
        guard url.scheme == nil || url.scheme?.lowercased() == "file" else { return nil }
        let path = url.path
        guard !path.isEmpty else { return nil }
        return path.replacingOccurrences(of: ":[0-9]+(?::[0-9]+)?$", with: "", options: .regularExpression)
    }
}

/// Complete inline math only. Escaped dollar signs, code spans and currency stay text.
public enum MarkdownMath {
    public static func spans(in source: String) -> [MarkdownMathSpan] {
        let chars = Array(source)
        var result: [MarkdownMathSpan] = [], buffer = "", index = 0, codeTicks = 0
        func flush() { if !buffer.isEmpty { result.append(.init(id: result.count, text: buffer, isMath: false, display: false)); buffer = "" } }
        while index < chars.count {
            if chars[index] == "`" {
                var end = index
                while end < chars.count && chars[end] == "`" { end += 1 }
                let run = end - index
                if codeTicks == 0 { codeTicks = run } else if run == codeTicks { codeTicks = 0 }
                buffer += String(chars[index..<end]); index = end; continue
            }
            if codeTicks == 0 {
                var opener = 0, closer = "", display = false
                if chars[index] == "\\", index + 1 < chars.count, chars[index + 1] == "(" || chars[index + 1] == "[" {
                    opener = 2; display = chars[index + 1] == "["; closer = display ? "\\]" : "\\)"
                } else if chars[index] == "$", index + 1 < chars.count, !chars[index + 1].isWhitespace {
                    display = chars[index + 1] == "$"; opener = display ? 2 : 1; closer = display ? "$$" : "$"
                }
                if opener > 0 {
                    let closing = Array(closer)
                    var end = index + opener
                    while end + closing.count <= chars.count {
                        if chars[end] == "\\", closing.first != "\\" { end += 2; continue }
                        if Array(chars[end..<end + closing.count]) == closing {
                            let body = String(chars[index + opener..<end])
                            let currency = !display && !body.isEmpty && body.allSatisfy { $0.isNumber || $0 == "." || $0 == "," }
                            let trailingDigit = end + closing.count < chars.count && chars[end + closing.count].isNumber
                            if !body.isEmpty && (body.last?.isWhitespace != true || opener == 2) && !currency && !trailingDigit {
                                flush(); result.append(.init(id: result.count, text: body, isMath: true, display: display))
                                index = end + closing.count; break
                            }
                        }
                        end += 1
                    }
                    if index == end + closing.count { continue }
                }
            }
            if chars[index] == "\\", index + 1 < chars.count {
                buffer.append(chars[index]); buffer.append(chars[index + 1]); index += 2
            } else { buffer.append(chars[index]); index += 1 }
        }
        flush()
        return result
    }
}

private struct MarkdownLine { var text: String; var number: Int }
private enum MarkdownParser {
    struct Fence { var marker: Character; var count: Int; var language: String }
    struct Marker { var indent: Int; var contentIndent: Int; var number: Int?; var body: String; var checked: Bool? }

    static func parse(_ lines: [MarkdownLine], scope: String, depth: Int) -> [MarkdownBlock] {
        guard !Task.isCancelled else { return [] }
        guard depth < 12 else { return lines.first.map { [MarkdownBlock(id: "\(scope)-\($0.number)", sourceLine: $0.number, content: .paragraph(lines.map(\.text).joined(separator: "\n")))] } ?? [] }
        var blocks: [MarkdownBlock] = [], i = 0
        func add(_ content: MarkdownBlock.Content, at index: Int) { blocks.append(.init(id: "\(scope)-\(lines[index].number)", sourceLine: lines[index].number, content: content)) }
        while i < lines.count {
            if Task.isCancelled { break }
            let line = lines[i].text, trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { i += 1; continue }
            let start = i
            if let fence = fence(line) {
                i += 1; var source: [String] = []; var closed = false
                while i < lines.count {
                    if closes(lines[i].text, fence) { closed = true; i += 1; break }
                    source.append(lines[i].text); i += 1
                }
                add(.code(language: fence.language, source: source.joined(separator: "\n"), closed: closed), at: start); continue
            }
            if trimmed.hasPrefix("$$") || trimmed.hasPrefix("\\[") {
                let opener = trimmed.hasPrefix("$$") ? "$$" : "\\[", closer = trimmed.hasPrefix("$$") ? "$$" : "\\]"
                var content = String(trimmed.dropFirst(opener.count))
                if content.hasSuffix(closer) { content = String(content.dropLast(closer.count)); i += 1; add(.math(content.trimmingCharacters(in: .whitespacesAndNewlines)), at: start); continue }
                var end = i + 1
                while end < lines.count && !lines[end].text.trimmingCharacters(in: .whitespaces).hasSuffix(closer) { end += 1 }
                if end < lines.count {
                    var parts = [content]; parts += lines[(i + 1)..<end].map(\.text)
                    parts.append(String(lines[end].text.trimmingCharacters(in: .whitespaces).dropLast(closer.count)))
                    i = end + 1; add(.math(parts.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)), at: start); continue
                }
            }
            if let heading = heading(line) { add(.heading(level: heading.0, text: heading.1), at: start); i += 1; continue }
            if !rule(line), marker(line) == nil, quote(line) == nil, i + 1 < lines.count, let level = setext(lines[i + 1].text) {
                add(.heading(level: level, text: trimmed), at: start); i += 2; continue
            }
            if rule(line) { add(.thematicBreak, at: start); i += 1; continue }
            if quote(line) != nil {
                var quoted: [MarkdownLine] = []
                while i < lines.count, let value = quote(lines[i].text) { quoted.append(.init(text: value, number: lines[i].number)); i += 1 }
                add(.quote(parse(quoted, scope: "\(scope)-q\(lines[start].number)", depth: depth + 1)), at: start); continue
            }
            if let first = marker(line), first.indent <= 3 {
                var items: [MarkdownListItem] = []
                let ordered = first.number != nil
                while i < lines.count, let item = marker(lines[i].text), item.indent == first.indent, (item.number != nil) == ordered {
                    let itemLine = lines[i].number
                    var children = [MarkdownLine(text: item.body, number: itemLine)]; i += 1
                    var previousBlank = false
                    while i < lines.count {
                        let next = lines[i].text
                        if let sibling = marker(next), sibling.indent == first.indent { break }
                        if next.trimmingCharacters(in: .whitespaces).isEmpty {
                            if i + 1 < lines.count, let sibling = marker(lines[i + 1].text), sibling.indent == first.indent { i += 1; break }
                            children.append(lines[i]); i += 1; previousBlank = true; continue
                        }
                        let indent = indentation(next)
                        if indent < item.contentIndent {
                            if previousBlank || startsBlock(lines, i) { break }
                            children.append(lines[i]); i += 1
                        } else { children.append(.init(text: removingIndent(next, columns: item.contentIndent), number: lines[i].number)); i += 1 }
                        previousBlank = false
                    }
                    items.append(.init(id: "\(scope)-item\(itemLine)", number: item.number, checked: item.checked, blocks: parse(children, scope: "\(scope)-li\(itemLine)", depth: depth + 1)))
                }
                add(.list(ordered: ordered, items: items), at: start); continue
            }
            if i + 1 < lines.count, let table = tableHeader(line, lines[i + 1].text) {
                i += 2; var rows: [[String]] = []
                while i < lines.count && !lines[i].text.trimmingCharacters(in: .whitespaces).isEmpty && containsTablePipe(lines[i].text) {
                    var row = tableCells(lines[i].text)
                    if row.count < table.0.count { row += Array(repeating: "", count: table.0.count - row.count) }
                    rows.append(Array(row.prefix(table.0.count))); i += 1
                }
                add(.table(.init(headers: table.0, alignments: table.1, rows: rows)), at: start); continue
            }
            if indentation(line) >= 4 {
                var source: [String] = []
                while i < lines.count && (indentation(lines[i].text) >= 4 || lines[i].text.trimmingCharacters(in: .whitespaces).isEmpty) {
                    source.append(removingIndent(lines[i].text, columns: 4)); i += 1
                }
                while source.last == "" { source.removeLast() }
                add(.code(language: "", source: source.joined(separator: "\n"), closed: true), at: start); continue
            }
            var paragraph = [line]; i += 1
            while i < lines.count && !lines[i].text.trimmingCharacters(in: .whitespaces).isEmpty && !startsBlock(lines, i) {
                paragraph.append(lines[i].text); i += 1
            }
            add(.paragraph(paragraph.joined(separator: "\n")), at: start)
        }
        return blocks
    }
    static func startsBlock(_ lines: [MarkdownLine], _ index: Int) -> Bool {
        let line = lines[index].text
        return fence(line) != nil || heading(line) != nil || rule(line) || quote(line) != nil
            || marker(line).map { $0.indent <= 3 } == true
            || (index + 1 < lines.count && (setext(lines[index + 1].text) != nil || tableHeader(line, lines[index + 1].text) != nil))
            || line.trimmingCharacters(in: .whitespaces).hasPrefix("$$")
            || line.trimmingCharacters(in: .whitespaces).hasPrefix("\\[")
    }
    static func indentation(_ value: String) -> Int { value.prefix { $0 == " " || $0 == "\t" }.reduce(0) { $0 + ($1 == "\t" ? 4 : 1) } }
    static func removingIndent(_ value: String, columns: Int) -> String {
        var remaining = columns, index = value.startIndex
        while index < value.endIndex && remaining > 0 && (value[index] == " " || value[index] == "\t") { remaining -= value[index] == "\t" ? 4 : 1; index = value.index(after: index) }
        return String(value[index...])
    }
    static func fence(_ value: String) -> Fence? {
        guard indentation(value) <= 3 else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        guard let marker = trimmed.first, marker == "`" || marker == "~" else { return nil }
        let count = trimmed.prefix { $0 == marker }.count
        guard count >= 3 else { return nil }
        let info = String(trimmed.dropFirst(count)).trimmingCharacters(in: .whitespaces)
        guard marker != "`" || !info.contains("`") else { return nil }
        return .init(marker: marker, count: count, language: String(info.split(whereSeparator: \.isWhitespace).first ?? "").lowercased())
    }
    static func closes(_ value: String, _ open: Fence) -> Bool {
        guard let close = fence(value), close.marker == open.marker, close.count >= open.count else { return false }
        return value.trimmingCharacters(in: .whitespaces).dropFirst(close.count).trimmingCharacters(in: .whitespaces).isEmpty
    }
    static func heading(_ value: String) -> (Int, String)? {
        guard indentation(value) <= 3 else { return nil }
        let text = value.trimmingCharacters(in: .whitespaces), count = text.prefix { $0 == "#" }.count
        guard (1...6).contains(count), text.count == count || text.dropFirst(count).first?.isWhitespace == true else { return nil }
        let content = String(text.dropFirst(count)).trimmingCharacters(in: .whitespaces)
        let cleaned = content.replacingOccurrences(of: "[ \\t]+#+[ \\t]*$", with: "", options: .regularExpression)
        return (count, cleaned)
    }
    static func setext(_ value: String) -> Int? {
        let text = value.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, indentation(value) <= 3 else { return nil }
        if text.allSatisfy({ $0 == "=" }) { return 1 }
        if text.allSatisfy({ $0 == "-" }) { return 2 }
        return nil
    }
    static func rule(_ value: String) -> Bool {
        guard indentation(value) <= 3 else { return false }
        let text = value.filter { !$0.isWhitespace }
        guard text.count >= 3, let first = text.first, ["-", "*", "_"].contains(first) else { return false }
        return text.allSatisfy { $0 == first }
    }
    static func quote(_ value: String) -> String? {
        guard indentation(value) <= 3 else { return nil }
        let text = value.drop(while: { $0 == " " })
        guard text.first == ">" else { return nil }
        var rest = text.dropFirst(); if rest.first == " " { rest = rest.dropFirst() }
        return String(rest)
    }
    static func marker(_ value: String) -> Marker? {
        let indent = indentation(value), chars = Array(value.drop(while: { $0 == " " || $0 == "\t" }))
        guard !chars.isEmpty else { return nil }
        var end = 0, number: Int?
        if ["-", "*", "+"].contains(chars[0]) { end = 1 }
        else {
            while end < chars.count && chars[end].isASCII && chars[end].isNumber { end += 1 }
            guard (1...9).contains(end), end < chars.count, chars[end] == "." || chars[end] == ")" else { return nil }
            number = Int(String(chars[0..<end])); end += 1
        }
        guard end == chars.count || chars[end].isWhitespace else { return nil }
        let markerEnd = end
        while end < chars.count && chars[end].isWhitespace { end += 1 }
        let contentIndent = indent + markerEnd + max(1, min(4, end - markerEnd))
        var body = String(chars[end...]), checked: Bool?
        if body.hasPrefix("[ ] ") || body.hasPrefix("[x] ") || body.hasPrefix("[X] ") { checked = !body.hasPrefix("[ ]"); body = String(body.dropFirst(4)) }
        return .init(indent: indent, contentIndent: contentIndent, number: number, body: body, checked: checked)
    }
    static func containsTablePipe(_ value: String) -> Bool { tableCells(value).count > 1 || value.trimmingCharacters(in: .whitespaces).hasPrefix("|") }
    static func tableHeader(_ line: String, _ delimiter: String) -> ([String], [MarkdownTable.Alignment])? {
        guard containsTablePipe(line) else { return nil }
        let headers = tableCells(line), cells = tableCells(delimiter)
        guard !headers.isEmpty, headers.count == cells.count else { return nil }
        var alignments: [MarkdownTable.Alignment] = []
        for cell in cells {
            let trimmed = cell.trimmingCharacters(in: .whitespaces)
            guard trimmed.range(of: "^:?-{3,}:?$", options: .regularExpression) != nil else { return nil }
            alignments.append(trimmed.hasPrefix(":") && trimmed.hasSuffix(":") ? .center : trimmed.hasSuffix(":") ? .trailing : .leading)
        }
        return (headers, alignments)
    }
    static func tableCells(_ value: String) -> [String] {
        let chars = Array(value.trimmingCharacters(in: .whitespaces))
        var cells: [String] = [], buffer = "", index = 0, ticks = 0
        while index < chars.count {
            if chars[index] == "\\", index + 1 < chars.count { buffer += String(chars[index...index + 1]); index += 2; continue }
            if chars[index] == "`" {
                var end = index; while end < chars.count && chars[end] == "`" { end += 1 }
                let run = end - index
                if ticks == 0 { ticks = run } else if ticks == run { ticks = 0 }
                buffer += String(chars[index..<end]); index = end; continue
            }
            if chars[index] == "|" && ticks == 0 { cells.append(buffer.trimmingCharacters(in: .whitespaces)); buffer = "" }
            else { buffer.append(chars[index]) }
            index += 1
        }
        cells.append(buffer.trimmingCharacters(in: .whitespaces))
        if chars.first == "|", cells.first == "" { cells.removeFirst() }
        if chars.last == "|", cells.last == "" { cells.removeLast() }
        return cells
    }
}

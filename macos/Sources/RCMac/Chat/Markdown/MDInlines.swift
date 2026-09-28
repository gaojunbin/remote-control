import Foundation

/// Inline content, as the browser lays it out under `white-space: normal`:
/// line feeds and tabs are spaces, a run of spaces is one — across the edges
/// of `<strong>`, `<a>` and the rest — and no line starts or ends with one.
enum MDInlines {
    static func build(_ nodes: [HastNode], marks: MDMarks, base: MDWeight) -> [MDInline] {
        var raw: [MDInline] = []
        collect(nodes, marks: marks, base: base, into: &raw)
        return collapse(raw)
    }

    private static func collect(_ nodes: [HastNode], marks: MDMarks, base: MDWeight, into out: inout [MDInline]) {
        for node in nodes {
            switch node {
            case .text(let value):
                out.append(.text(value, marks))
            case .element(let element):
                collect(element, marks: marks, base: base, into: &out)
            }
        }
    }

    private static func collect(_ element: HastElement, marks: MDMarks, base: MDWeight, into out: inout [MDInline]) {
        var inner = marks
        switch element.tag {
        case "strong", "b":
            inner.weight = (marks.weight ?? base).bolder
        case "em", "i":
            inner.italic = true
        case "del", "s":
            inner.strike = true
        case "a":
            inner.link = element.properties.href ?? ""
        case "sup":
            inner.superscript = true
        case "code":
            out.append(.code(element.textContent, marks))
            return
        case "br":
            out.append(.lineBreak)
            return
        case "input":
            out.append(.checkbox(checked: element.properties.checked ?? false))
            return
        case "img":
            out.append(.image(alt: element.properties.alt ?? ""))
            return
        default:
            break
        }
        collect(element.children, marks: inner, base: base, into: &out)
    }

    /// One space for every run of white space, none at the start or end of a
    /// line, and adjoining runs with the same marks joined.
    static func collapse(_ inlines: [MDInline]) -> [MDInline] {
        var out: [MDInline] = []
        // Whether the last thing laid out ends in a space, or is the start of
        // a line, where a space collapses away.
        var atSpace = true
        for inline in inlines {
            switch inline {
            case .text(let value, let marks):
                let text = squeeze(value, dropLeading: atSpace)
                guard !text.isEmpty else { continue }
                atSpace = text.hasSuffix(" ")
                append(.text(text, marks), to: &out)
            case .code(let value, let marks):
                let text = squeeze(value, dropLeading: false)
                guard !text.isEmpty else { continue }
                atSpace = false
                out.append(.code(text, marks))
            case .lineBreak:
                trimTrailingSpace(&out)
                out.append(.lineBreak)
                atSpace = true
            case .checkbox, .image:
                out.append(inline)
                atSpace = false
            }
        }
        trimTrailingSpace(&out)
        return out
    }

    private static func squeeze(_ value: String, dropLeading: Bool) -> String {
        var result = ""
        var lastWasSpace = dropLeading
        for character in value {
            if character == " " || character == "\n" || character == "\t" || character == "\r" {
                if !lastWasSpace { result.append(" ") }
                lastWasSpace = true
            } else {
                result.append(character)
                lastWasSpace = false
            }
        }
        return result
    }

    private static func append(_ inline: MDInline, to out: inout [MDInline]) {
        if case .text(let value, let marks) = inline, case .text(let previous, let previousMarks)? = out.last,
           marks == previousMarks {
            out[out.count - 1] = .text(previous + value, marks)
        } else {
            out.append(inline)
        }
    }

    private static func trimTrailingSpace(_ out: inout [MDInline]) {
        guard case .text(let value, let marks)? = out.last, value.hasSuffix(" ") else { return }
        let trimmed = String(value.dropLast())
        if trimmed.isEmpty { out.removeLast() } else { out[out.count - 1] = .text(trimmed, marks) }
    }
}

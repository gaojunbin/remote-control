import Foundation

/// How one run of highlighted code is drawn: an sRGB ink, bold or italic, and
/// a tint behind it.
struct HighlightStyle: Equatable, Sendable {
    var color: UInt32
    var bold = false
    var italic = false
    var background: UInt32?
}

/// `highlight.js/styles/github.css`, the theme `Markdown.tsx` imports, as
/// rules over the `hljs-*` classes rehype-highlight puts on its spans. Each
/// property a span does not get from a rule of its own it inherits from the
/// span around it, and among its own rules the one of higher specificity wins,
/// the later one on a tie, as CSS decides. A tint is not inherited, but the
/// span that has one paints it behind everything inside, so a run keeps the
/// nearest one around it.
enum HighlightTheme {
    /// `.hljs { color: #24292e }`: the block's own ink.
    static let base = HighlightStyle(color: 0x24292E)

    private struct Rule: Sendable {
        /// Every class the span itself must carry.
        let classes: [String]
        /// A class some enclosing span must carry (`.hljs-meta .hljs-keyword`).
        let ancestor: String?
        var color: UInt32?
        var bold: Bool?
        var italic: Bool?
        var background: UInt32?

        var specificity: Int { classes.count + (ancestor == nil ? 0 : 1) }
    }

    /// In the stylesheet's order.
    private static let rules: [Rule] = {
        var rules: [Rule] = []
        func add(_ selectors: [String], color: UInt32, bold: Bool? = nil, italic: Bool? = nil,
                 background: UInt32? = nil) {
            for selector in selectors {
                let parts = selector.split(separator: " ").map(String.init)
                let own = parts.last.map { $0.split(separator: ".").map(String.init) } ?? []
                rules.append(Rule(classes: own, ancestor: parts.count > 1 ? parts.first : nil,
                                  color: color, bold: bold, italic: italic, background: background))
            }
        }
        add(["hljs-doctag", "hljs-keyword", "hljs-meta hljs-keyword", "hljs-template-tag",
             "hljs-template-variable", "hljs-type", "hljs-variable.language_"], color: 0xD73A49)
        add(["hljs-title", "hljs-title.class_", "hljs-title.class_.inherited__", "hljs-title.function_"],
            color: 0x6F42C1)
        add(["hljs-attr", "hljs-attribute", "hljs-literal", "hljs-meta", "hljs-number", "hljs-operator",
             "hljs-variable", "hljs-selector-attr", "hljs-selector-class", "hljs-selector-id"], color: 0x005CC5)
        add(["hljs-regexp", "hljs-string", "hljs-meta hljs-string"], color: 0x032F62)
        add(["hljs-built_in", "hljs-symbol"], color: 0xE36209)
        add(["hljs-comment", "hljs-code", "hljs-formula"], color: 0x6A737D)
        add(["hljs-name", "hljs-quote", "hljs-selector-tag", "hljs-selector-pseudo"], color: 0x22863A)
        add(["hljs-subst"], color: 0x24292E)
        add(["hljs-section"], color: 0x005CC5, bold: true)
        add(["hljs-bullet"], color: 0x735C0F)
        add(["hljs-emphasis"], color: 0x24292E, italic: true)
        add(["hljs-strong"], color: 0x24292E, bold: true)
        add(["hljs-addition"], color: 0x22863A, background: 0xF0FFF4)
        add(["hljs-deletion"], color: 0xB31D28, background: 0xFFEEF0)
        return rules
    }()

    /// The style of a span with `classes`, inside spans whose classes are
    /// `ancestors`, whose own style is `inherited`.
    static func style(classes: [String], ancestors: [[String]], inherited: HighlightStyle) -> HighlightStyle {
        let enclosing = Set(ancestors.joined())
        let matching = rules.enumerated().filter { _, rule in
            rule.classes.allSatisfy(classes.contains) && (rule.ancestor.map(enclosing.contains) ?? true)
        }
        .sorted { ($0.element.specificity, $0.offset) < ($1.element.specificity, $1.offset) }
        var style = inherited
        for (_, rule) in matching {
            if let color = rule.color { style.color = color }
            if let bold = rule.bold { style.bold = bold }
            if let italic = rule.italic { style.italic = italic }
            if let background = rule.background { style.background = background }
        }
        return style
    }
}

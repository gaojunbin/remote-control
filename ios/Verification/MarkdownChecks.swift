import Foundation
import RCCore

enum MarkdownChecks {
    static func run() -> CheckResult {
        let checks = CheckRunner(group: "markdown")
        blocks(checks)
        code(checks)
        tables(checks)
        lists(checks)
        math(checks)
        links(checks)
        return checks.result()
    }

    private static func blocks(_ checks: CheckRunner) {
        let document = MarkdownDocument("""
        # Heading

        A paragraph that
        wraps.

        > quoted

        ---
        """)
        checks.equal(document.blocks.count, 4, "heading, paragraph, quote and rule parse")
        if case .heading(let level, let text) = document.blocks[0].content {
            checks.equal(level, 1, "an ATX heading keeps its level")
            checks.equal(text, "Heading", "an ATX heading keeps its text")
        } else {
            checks.expect(false, "the first block is a heading")
        }
        if case .paragraph(let text) = document.blocks[1].content {
            checks.expect(text.contains("\n"), "a soft-wrapped paragraph keeps its line break")
        } else {
            checks.expect(false, "the second block is a paragraph")
        }
        if case .quote = document.blocks[2].content { checks.expect(true, "quote") }
        else { checks.expect(false, "the third block is a quote") }
        if case .thematicBreak = document.blocks[3].content { checks.expect(true, "rule") }
        else { checks.expect(false, "the fourth block is a thematic break") }

        checks.equal(MarkdownDocument("").blocks.count, 0, "an empty document has no blocks")
        checks.equal(MarkdownDocument("a\r\nb").blocks.count, 1, "CRLF normalises to one paragraph")
    }

    private static func code(_ checks: CheckRunner) {
        let closed = MarkdownDocument("```swift\nlet x = 1\n```")
        if case .code(let language, let source, let isClosed) = closed.blocks.first?.content {
            checks.equal(language, "swift", "the fence language is captured")
            checks.equal(source, "let x = 1", "the fence body is captured")
            checks.expect(isClosed, "a closed fence is marked closed")
        } else {
            checks.expect(false, "a fenced block parses as code")
        }
        let streaming = MarkdownDocument("```python\nprint(1)")
        if case .code(_, _, let isClosed) = streaming.blocks.first?.content {
            checks.expect(!isClosed, "an unterminated fence is marked open, for a streaming answer")
        } else {
            checks.expect(false, "an unterminated fence still parses as code")
        }
        let indented = MarkdownDocument("    indented code\n")
        if case .code = indented.blocks.first?.content { checks.expect(true, "indented code") }
        else { checks.expect(false, "an indented block parses as code") }
    }

    private static func tables(_ checks: CheckRunner) {
        let document = MarkdownDocument("""
        | Name | Count |
        | :--- | ----: |
        | a | 1 |
        | b | 2 |
        """)
        if case .table(let table) = document.blocks.first?.content {
            checks.equal(table.headers, ["Name", "Count"], "table headers parse")
            checks.equal(table.alignments, [.leading, .trailing], "column alignment parses")
            checks.equal(table.rows.count, 2, "table rows parse")
        } else {
            checks.expect(false, "a GFM table parses")
        }
    }

    private static func lists(_ checks: CheckRunner) {
        let document = MarkdownDocument("""
        - [x] done
        - [ ] pending
          - nested
        """)
        if case .list(let ordered, let items) = document.blocks.first?.content {
            checks.expect(!ordered, "a bullet list is unordered")
            checks.equal(items.count, 2, "task list items parse")
            checks.equal(items[0].checked, true, "a checked task parses")
            checks.equal(items[1].checked, false, "an unchecked task parses")
        } else {
            checks.expect(false, "a task list parses")
        }
        let numbered = MarkdownDocument("1. first\n2. second")
        if case .list(let ordered, let items) = numbered.blocks.first?.content {
            checks.expect(ordered, "a numbered list is ordered")
            checks.equal(items.count, 2, "numbered items parse")
        } else {
            checks.expect(false, "a numbered list parses")
        }
    }

    private static func math(_ checks: CheckRunner) {
        let currency = MarkdownMath.spans(in: "the total is $12.50 today")
        checks.equal(currency.filter(\.isMath).count, 0, "a currency amount is not treated as math")
        let spans = MarkdownMath.spans(in: "the value $x + y$ matters")
        checks.equal(spans.filter(\.isMath).count, 1, "one math span is found")
        checks.equal(spans.first(where: \.isMath)?.text, "x + y", "the math span is extracted")
        let inCode = MarkdownMath.spans(in: "`$not math$`")
        checks.equal(inCode.filter(\.isMath).count, 0, "a code span is never math")
        let display = MarkdownMath.spans(in: "$$E = mc^2$$")
        checks.expect(display.first(where: \.isMath)?.display == true, "display math is recognised")
    }

    private static func links(_ checks: CheckRunner) {
        checks.equal(MarkdownLink.filePath(for: URL(string: "src/main.swift:42")!), "src/main.swift",
                     "an editor line suffix is not part of the path")
        checks.expect(MarkdownLink.filePath(for: URL(string: "https://example.com")!) == nil,
                      "a web URL is not a file path")
    }
}

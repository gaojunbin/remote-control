import CoreGraphics
import Foundation
import Testing
@testable import RCMac

/// The web's own pipeline, run from the bundle, and what the chat makes of the
/// tree it returns: the blocks `.md` lays out and the gaps its margins
/// collapse to (`chat.css` § markdown), and github.css's colours.
@Suite("Chat Markdown") @MainActor
struct ChatMarkdownTests {
    private func blocks(_ text: String) -> MDStack? {
        guard case .blocks(let stack) = MarkdownEngine.shared.document(text) else { return nil }
        return stack
    }

    private func text(_ block: MDBlock?) -> MDText? {
        guard case .text(let text)? = block?.kind else { return nil }
        return text
    }

    private func code(_ markdown: String) -> MDCode? {
        guard case .code(let code)? = blocks(markdown)?.blocks.first?.kind else { return nil }
        return code
    }

    @Test func theBundleLoadsAndParses() {
        #expect(blocks("hello") != nil)
    }

    @Test func aHeadingIsSetInItsOwnTypeAndMeetsTheParagraphAtItsBottomMargin() {
        let stack = blocks("## Plan\n\nThe **lock** holds.")
        #expect(stack?.blocks.count == 2)
        #expect(text(stack?.blocks.first)?.size == 15)
        #expect(text(stack?.blocks.first)?.weight == .semibold)
        #expect(stack?.gaps == [0, 8])
        #expect(text(stack?.blocks.last)?.inlines == [
            .text("The ", MDMarks()), .text("lock", MDMarks(weight: .bold)), .text(" holds.", MDMarks())
        ])
    }

    @Test func aRuleKeepsSixteenEitherSide() {
        let stack = blocks("a\n\n---\n\nb")
        #expect(stack?.gaps == [0, 16, 16])
        #expect(stack?.top == 0)
        #expect(stack?.bottom == 0)
    }

    @Test func aNestedListIsItsItemsSecondBlock() {
        guard case .list(let list)? = blocks("1. a\n2. b\n   - c")?.blocks.first?.kind else {
            Issue.record("no list")
            return
        }
        #expect(list.ordered)
        #expect(list.start == 1)
        #expect(list.depth == 0)
        #expect(list.items.count == 2)
        // The nested list's 12 below collapses through the item's own 4, as
        // nothing on the `li` stops it.
        #expect(list.items.map(\.bottom) == [4, 12])
        guard case .list(let nested)? = list.items[1].blocks.last?.kind else {
            Issue.record("no nested list")
            return
        }
        #expect(!nested.ordered)
        #expect(nested.depth == 1)
        #expect(list.items[1].gaps == [0, 0])
    }

    @Test func anOrderedListStartsWhereItsFirstNumberSays() {
        guard case .list(let list)? = blocks("3. x\n4. y")?.blocks.first?.kind else {
            Issue.record("no list")
            return
        }
        #expect(list.start == 3)
    }

    @Test func aTaskListItemStartsWithItsCheckbox() {
        guard case .list(let list)? = blocks("- [x] done\n- [ ] todo")?.blocks.first?.kind else {
            Issue.record("no list")
            return
        }
        #expect(text(list.items.first?.blocks.first)?.inlines == [.checkbox(checked: true), .text(" done", MDMarks())])
        #expect(text(list.items.last?.blocks.first)?.inlines == [.checkbox(checked: false), .text(" todo", MDMarks())])
    }

    @Test func aTablesCellsTakeTheirColumnsAlignment() {
        guard case .table(let table)? = blocks("| a | b | c |\n| --- | ---: | :---: |\n| 1 | 2 | 3 |")?
            .blocks.first?.kind else {
            Issue.record("no table")
            return
        }
        #expect(table.columns == 3)
        #expect(table.header.map(\.align) == [.leading, .trailing, .center])
        #expect(table.header.map(\.header) == [true, true, true])
        #expect(table.rows.count == 1)
        #expect(table.rows.first?.map(\.inlines) == [[.text("1", MDMarks())], [.text("2", MDMarks())],
                                                     [.text("3", MDMarks())]])
    }

    @Test func strikethroughAndBareLinksAreMarked() {
        let inlines = text(blocks("x ~~y~~ https://example.com/runs/42")?.blocks.first)?.inlines
        #expect(inlines == [
            .text("x ", MDMarks()), .text("y", MDMarks(strike: true)), .text(" ", MDMarks()),
            .text("https://example.com/runs/42", MDMarks(link: "https://example.com/runs/42"))
        ])
    }

    @Test func aHighlightedBlockIsColouredAsGithubCSSColoursIt() {
        let block = code("```python\ndef refresh(self, token: str) -> Token:\n"
            + "    with self._lock:  # one refresh at a time\n        return self._rotate(token)\n```")
        #expect(block?.highlighted == true)
        #expect(block?.lines.count == 3)
        func color(_ word: String) -> UInt32? {
            block?.lines.joined().first { $0.text == word }?.style.color
        }
        #expect(color("def") == 0xD73A49)
        #expect(color("refresh") == 0x6F42C1)
        #expect(color("str") == 0xE36209)
        // `.hljs-variable.language_` outranks `.hljs-variable` though it comes first.
        #expect(color("self") == 0xD73A49)
        #expect(color("# one refresh at a time") == 0x6A737D)
        #expect(color("._rotate(token)") == 0x24292E)
        #expect(block?.source.hasSuffix("return self._rotate(token)\n") == true)
    }

    @Test func aLanguageHighlightJSDoesNotKnowIsStillAnHljsBlock() {
        let block = code("```nosuchlang\nx = 1\n```")
        #expect(block?.highlighted == true)
        #expect(block?.lines == [[MDCodeRun(text: "x = 1", style: HighlightTheme.base)]])
    }

    @Test func aBlockWithNoLanguageKeepsThePagesInk() {
        let block = code("```\nplain\n```")
        #expect(block?.highlighted == false)
        #expect(block?.lines.first?.first?.style.color == 0x111111)
    }

    @Test func aDiffBlockTintsItsLines() {
        let block = code("```diff\n+new\n-old\n```")
        #expect(block?.lines.map { $0.first?.style.background } == [0xF0FFF4, 0xFFEEF0])
    }
}

/// `white-space: normal` over inline content, and CSS's `bolder`.
@Suite("Chat Markdown inlines")
struct ChatMarkdownInlineTests {
    private let plain = MDMarks()
    private let bold = MDMarks(weight: .bold)

    @Test func aRunOfWhiteSpaceIsOneSpaceAndNoneAtTheEnds() {
        #expect(MDInlines.collapse([.text("  a \n\t b  ", plain)]) == [.text("a b", plain)])
    }

    @Test func aSpaceCollapsesAcrossTheEdgeOfAnElement() {
        #expect(MDInlines.collapse([.text("a ", plain), .text(" b", bold)]) == [.text("a ", plain), .text("b", bold)])
    }

    @Test func aLineBreakTakesTheSpacesEitherSideOfIt() {
        #expect(MDInlines.collapse([.text("a ", plain), .lineBreak, .text(" b", plain)])
                == [.text("a", plain), .lineBreak, .text("b", plain)])
    }

    @Test func adjoiningRunsWithTheSameMarksAreOne() {
        #expect(MDInlines.collapse([.text("a", plain), .text("b", plain)]) == [.text("ab", plain)])
    }

    @Test func bolderIsCSSBolder() {
        #expect(MDWeight.regular.bolder == .bold)
        #expect(MDWeight.medium.bolder == .bold)
        #expect(MDWeight.semibold.bolder == .black)
    }

    @Test func aSpanWithoutARuleInheritsAndATintIsKept() {
        let inherited = HighlightStyle(color: 0x6F42C1)
        #expect(HighlightTheme.style(classes: ["hljs-params"], ancestors: [["hljs-function"]], inherited: inherited)
                == inherited)
        let tinted = HighlightTheme.style(classes: ["hljs-addition"], ancestors: [], inherited: HighlightTheme.base)
        #expect(tinted == HighlightStyle(color: 0x22863A, background: 0xF0FFF4))
        #expect(HighlightTheme.style(classes: ["hljs-keyword"], ancestors: [["hljs-meta"]],
                                     inherited: HighlightStyle(color: 0x005CC5)).color == 0xD73A49)
        #expect(HighlightTheme.style(classes: ["hljs-section"], ancestors: [], inherited: HighlightTheme.base).bold)
    }
}

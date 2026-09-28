import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/ApprovalCard.test.tsx` and the words a resolved question
    /// card says (`QuestionCard.tsx`).
    @Suite("Chat cards") @MainActor
    struct ChatCardRulesTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private let options = [
            ApprovalOption(id: "allow", label: "Allow once", style: .primary),
            ApprovalOption(id: "allow_session", label: "Allow for session", style: .secondary),
            ApprovalOption(id: "deny", label: "Deny", style: .danger)
        ]

        private func approval(status: RequestStatus = .pending, decision: ApprovalDecision? = nil) -> ApprovalPayload {
            ApprovalPayload(requestID: "req-1", tool: "Bash", kind: .shell,
                            title: "git commit -am \"fix: isolate the auth clock\"",
                            options: options, status: status, decision: decision)
        }

        @Test func theOptionsAreTheAgentsAcceptFirstAndRejectLast() {
            #expect(CardRules.ordered(options).map(\.label) == ["Allow once", "Allow for session", "Deny"])
            let shuffled = [options[2], options[1], options[0]]
            #expect(CardRules.ordered(shuffled).map(\.id) == ["allow", "allow_session", "deny"])
        }

        @Test func theOthersKeepTheOrderTheyCameIn() {
            let many = [ApprovalOption(id: "b", label: "B", style: .secondary), options[2],
                        ApprovalOption(id: "a", label: "A", style: .secondary), options[0]]
            #expect(CardRules.ordered(many).map(\.id) == ["allow", "b", "a", "deny"])
        }

        @Test func aResolvedRequestSaysWhoDecided() {
            let result = CardRules.approvalResult(approval(status: .resolved,
                                                           decision: ApprovalDecision(optionID: "deny", by: .terminal)))
            #expect(result == "Deny · decided by terminal")
        }

        @Test func anOptionItNeverOfferedIsNamedVerbatim() {
            let result = CardRules.approvalResult(approval(status: .resolved,
                                                           decision: ApprovalDecision(optionID: "later", by: .remote)))
            #expect(result == "later · decided by remote")
        }

        @Test func aRequestAnsweredInTheTerminalSaysSo() {
            let decision = ApprovalDecision(optionID: ApprovalPayload.elsewhereOptionID, by: .terminal)
            #expect(CardRules.approvalResult(approval(status: .resolved, decision: decision))
                    == "Answered in the terminal")
        }

        @Test func anExpiredRequestIsMarked() {
            #expect(CardRules.approvalResult(approval(status: .expired)) == "This request expired.")
        }

        @Test func aResolvedQuestionSaysWhoAnswered() {
            let item = QuestionItem(id: "q1", prompt: "Which database?")
            #expect(CardRules.questionResult(QuestionPayload(requestID: "r", questions: [item], status: .resolved,
                                                             by: .terminal)) == "Answered in the terminal")
            #expect(CardRules.questionResult(QuestionPayload(requestID: "r", questions: [item], status: .resolved))
                    == "Answered")
            #expect(CardRules.questionResult(QuestionPayload(requestID: "r", questions: [item], status: .expired))
                    == "This question expired.")
        }

        @Test func aSecretAnswerIsNeverRedrawn() {
            #expect(CardRules.freeText(.text("hunter2"), secret: true) == "")
            #expect(CardRules.freeText(.text("Postgres"), secret: false) == "Postgres")
            #expect(CardRules.freeText(.options(["pg"]), secret: false) == "")
            #expect(CardRules.answerIncludes(.options(["pg", "redis"]), "redis"))
            #expect(!CardRules.answerIncludes(.text("redis"), "redis"))
        }
    }

    /// `ToolRow.tsx`: what opens, what a running row shows, and the word at its
    /// trailing edge.
    @Suite("Chat tool rows") @MainActor
    struct ChatToolRowTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func tool(status: ToolStatus = .succeeded, output: String? = nil, input: JSONValue? = nil,
                          patch: String? = nil, durationMS: Int? = nil, title: String = "pytest -q") -> ToolCallPayload {
            ToolCallPayload(tool: "Bash", kind: .shell, title: title, status: status, input: input, output: output,
                            diff: patch.map { DiffPayload(path: "a.py", additions: 1, deletions: 0, patch: $0) },
                            startedAt: 1_000, durationMS: durationMS)
        }

        @Test func aRunningToolShowsOnlyItsLiveOutput() {
            let model = ToolRowModel(tool(status: .running, output: "collected 12 items", input: .object([:])),
                                     open: false)
            #expect(model.expanded)
            #expect(model.showsOutput)
            #expect(!model.showsInput)
            #expect(!ToolRowModel(tool(status: .running, output: ""), open: false).showsOutput)
        }

        @Test func openingShowsTheInputTheOutputAndTheDiff() {
            let model = ToolRowModel(tool(output: "", input: .object(["command": .string("ls")]), patch: "+x"),
                                     open: true)
            #expect(model.showsInput)
            #expect(model.showsOutput)
            #expect(model.showsDiff)
        }

        @Test func aRowWithNothingToShowHasNothingToOpen() {
            let model = ToolRowModel(tool(), open: false)
            #expect(!model.hasDetail)
            #expect(model.trailing(tool()) == "")
        }

        @Test func theTrailingWordIsTheTimeOrExpandOrCollapse() {
            let running = tool(status: .running)
            #expect(ToolRowModel(running, open: false).trailing(running, now: 4_200) == "running 3.2s")
            let timed = tool(output: "ok", durationMS: 6_400)
            #expect(ToolRowModel(timed, open: false).trailing(timed) == "6.4s")
            let untimed = tool(output: "ok")
            #expect(ToolRowModel(untimed, open: false).trailing(untimed) == "expand")
            #expect(ToolRowModel(untimed, open: true).trailing(untimed) == "collapse")
        }

        @Test func aSlashCommandsNameIsPrintedOnce() {
            #expect(!ToolRowModel(tool(title: "Bash"), open: false).showsTitle)
            #expect(ToolRowModel(tool(), open: false).showsTitle)
            #expect(ToolRowModel(tool(status: .failed), open: false).failed)
        }

        @Test func everyKindHasItsIconAndTheRestTheWrench() {
            #expect(ToolIcon.icon(.shell) == .terminal)
            #expect(ToolIcon.icon(.subagent) == .bot)
            #expect(ToolIcon.icon(.todo) == .listChecks)
            #expect(ToolIcon.icon(.other) == .wrench)
        }
    }

    /// The sentences RCCore writes for its own failures, in the web's words.
    @Suite("Chat error words") @MainActor
    struct ChatErrorWordsTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func rcCoresSentencesBecomeTheWebsWords() {
            #expect(ChatErrorWords.web("That message has already been sent.") == S.composer.alreadySent)
            #expect(ChatErrorWords.web("The gateway did not answer in time.") == S.errors.timeout)
            #expect(ChatErrorWords.web("That device or session no longer exists.") == S.errors.notFound)
            #expect(ChatErrorWords.web("Not connected to the gateway.") == S.errors.generic)
        }

        @Test func aDevicesOwnSentenceIsShownAsItArrived() {
            #expect(ChatErrorWords.web("the CLI exited with 1") == "the CLI exited with 1")
            #expect(ChatErrorWords.web("") == nil)
            #expect(ChatErrorWords.web(nil) == nil)
        }
    }
}

/// `DiffView.tsx`'s line classes.
@Suite("Chat diff lines")
struct ChatDiffLineTests {
    @Test func everyLineIsColouredByItsFirstCharacters() {
        let patch = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n context\n-old\n+new"
        let kinds = patch.split(separator: "\n", omittingEmptySubsequences: false).map(DiffLineKind.init)
        #expect(kinds == [.meta, .meta, .meta, .hunk, .context, .del, .add])
    }
}

/// `JSON.stringify(value, null, 2)`, which prints a tool's input on the web.
@Suite("Chat JSON text")
struct ChatJSONTextTests {
    @Test func objectsAndArraysAreIndentedByTwo() {
        let value = JSONValue.object([
            "command": .string("pytest -q"),
            "args": .array([.integer(1), .bool(true), .null]),
            "env": .object([:]),
            "files": .array([])
        ])
        #expect(JSONText.stringify(value) == """
            {
              "args": [
                1,
                true,
                null
              ],
              "command": "pytest -q",
              "env": {},
              "files": []
            }
            """)
    }

    @Test func integerKeysComeFirstAsAJavaScriptObjectOrdersThem() {
        #expect(JSONText.orderedKeys(["b", "10", "a", "2"]) == ["2", "10", "a", "b"])
        #expect(JSONText.orderedKeys(["01", "1"]) == ["1", "01"])
    }

    @Test func numbersAreWrittenAsJavaScriptWritesThem() {
        #expect(JSONText.numberText(100) == "100")
        #expect(JSONText.numberText(123.456) == "123.456")
        #expect(JSONText.numberText(-0.5) == "-0.5")
        #expect(JSONText.numberText(0.000001) == "0.000001")
        #expect(JSONText.numberText(1.5e-7) == "1.5e-7")
        #expect(JSONText.numberText(1e20) == "100000000000000000000")
        #expect(JSONText.numberText(1e21) == "1e+21")
        #expect(JSONText.numberText(0.1 + 0.2) == "0.30000000000000004")
        #expect(JSONText.numberText(.infinity) == "null")
    }

    @Test func stringsTakeJavaScriptsEscapes() {
        #expect(JSONText.quoted("say \"hi\"\n\tand \\ go") == #""say \"hi\"\n\tand \\ go""#)
        #expect(JSONText.quoted("\u{01}é") == #""\u0001é""#)
    }

    @Test func aPreBlockEndsAtItsLastLine() {
        #expect(ChatPreText.display("a\nb\n") == "a\nb")
        #expect(ChatPreText.display("a\n\n") == "a\n")
        #expect(ChatPreText.display("a") == "a")
    }
}

import RCCore
import Testing
@testable import RCMac

@Suite("Identity")
struct IdentityTests {
    @Test func initialsAsTheTopbarAndSettingsDrawThem() {
        #expect(Identity.initials("admin") == "AD")
        #expect(Identity.initials("j.gao") == "JG")
        #expect(Identity.initials("李雷") == "李")
        #expect(Identity.initials("") == "?")
        #expect(Identity.initials("élodie") == "é")
    }

    @Test func theGatewayHostDropsTheSchemeAndThePath() {
        #expect(Identity.gatewayHost("https://rc.example.com") == "rc.example.com")
        #expect(Identity.gatewayHost("http://localhost:8787/") == "localhost:8787")
        #expect(Identity.gatewayHost("rc.example.com:8443") == "rc.example.com:8443")
    }
}

/// `web/tests/commands.test.ts`, on the mock's own command lists.
@Suite("Slash commands")
struct SlashCommandTests {
    private let codex = ["compact", "review", "init", "status", "usage", "skills", "hooks", "mcp"].map {
        Command(name: $0, description: "", argument: $0 == "review" ? "instructions" : nil)
    }
    private let pi = [
        Command(name: "compact", description: "", group: "Built-in"),
        Command(name: "release-notes", description: "", group: "Prompts"),
        Command(name: "refactor-plan", description: "", group: "Prompts"),
        Command(name: "skill:pdf-tables", description: "", group: "Skills"),
        Command(name: "skill:sql-review", description: "", group: "Skills"),
        Command(name: "rc-status", description: "", group: "Extensions")
    ]

    @Test func theQueryIsThePartialNameWhileItIsTyped() {
        #expect(SlashCommands.query("/") == "")
        #expect(SlashCommands.query("/com") == "com")
        #expect(SlashCommands.query("/skill:pdf") == "skill:pdf")
        #expect(SlashCommands.query("/review ") == nil)
        #expect(SlashCommands.query("/review the diff") == nil)
        #expect(SlashCommands.query("") == nil)
        #expect(SlashCommands.query("look at src/lib/ws.ts") == nil)
        #expect(SlashCommands.query("\n/compact") == nil)
    }

    @Test func aTypedCommandSplitsTheNameFromTheArgument() {
        #expect(SlashCommands.typed("/review focus on the retry logic")
                == .init(name: "review", argument: "focus on the retry logic"))
        #expect(SlashCommands.typed("/compact") == .init(name: "compact", argument: nil))
        #expect(SlashCommands.typed("/compact   ") == .init(name: "compact", argument: nil))
    }

    @Test func onlyWhatTheSessionOffersMatches() {
        #expect(SlashCommands.match(codex, draft: "/compact")?.command.name == "compact")
        #expect(SlashCommands.match(codex, draft: "/nonesuch") == nil)
        #expect(SlashCommands.match(codex, draft: "compact") == nil)
        let match = SlashCommands.match(codex, draft: "/review gateway/link.py only")
        #expect(match?.command.name == "review" && match?.argument == "gateway/link.py only")
    }

    @Test func filteringIsAPrefixInTheDevicesOrder() {
        #expect(SlashCommands.filter(codex, query: "").map(\.name) == codex.map(\.name))
        #expect(SlashCommands.filter(codex, query: "s").map(\.name) == ["status", "skills"])
        #expect(SlashCommands.filter(pi, query: "skill:").map(\.name) == ["skill:pdf-tables", "skill:sql-review"])
    }

    @Test func sectionsOnlyWhenThereIsMoreThanOneGroup() {
        #expect(SlashCommands.sections(codex) == [.init(group: nil, items: codex)])
        #expect(SlashCommands.sections(pi).map(\.group) == ["Built-in", "Prompts", "Skills", "Extensions"])
        let one = [Command(name: "compact", description: "x", group: "Built-in")]
        #expect(SlashCommands.sections(one) == [.init(group: nil, items: one)])
    }

    @Test func aCompletionLeavesASpaceOnlyWhereAnArgumentGoes() {
        #expect(SlashCommands.completion(for: Command(name: "compact", description: "")) == "/compact")
        #expect(SlashCommands.completion(for: Command(name: "review", description: "", argument: "x")) == "/review ")
    }
}

@Suite("Answering")
struct AnsweringTests {
    private let questions = [
        QuestionItem(id: "q1", prompt: "Which?", options: [QuestionOption(id: "a", label: "A")], allowText: true),
        QuestionItem(id: "q2", prompt: "Why?", allowText: true)
    ]

    @Test func theComposerAnswersTheFirstQuestionWithNoSelection() {
        var draft = QuestionDraft(requestID: "r")
        draft.setText("typed on the card", for: "q1")
        let answers = Answering.composeAnswer(questions, draft: draft, text: " from the composer ")
        #expect(answers == ["q1": .text("from the composer")])
    }

    @Test func aSelectionIsKeptAndTheNextQuestionTakesTheSentence() {
        var draft = QuestionDraft(requestID: "r")
        draft.toggle("a", of: questions[0])
        #expect(Answering.composeAnswer(questions, draft: draft, text: "because") ==
                ["q1": .options(["a"]), "q2": .text("because")])
        #expect(!Answering.cardComplete(questions, draft: draft))
        draft.setText("reasons", for: "q2")
        #expect(Answering.cardComplete(questions, draft: draft))
        #expect(Answering.cardAnswers(questions, draft: draft) == ["q1": .options(["a"]), "q2": .text("reasons")])
    }

    @Test func aDraftWithNowhereToGoStaysPut() {
        let optionsOnly = [QuestionItem(id: "q", prompt: "?", options: [QuestionOption(id: "a", label: "A")])]
        #expect(Answering.composeAnswer(optionsOnly, draft: QuestionDraft(requestID: "r"), text: "x") == nil)
        #expect(Answering.composeAnswer(questions, draft: QuestionDraft(requestID: "r"), text: "  ") == nil)
    }
}

extension LanguageSensitive {
    @Suite("Attach") @MainActor
    struct AttachTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func agent(attach: String?, ready: Bool = false, interrupt: Bool = false, settings: Bool = false,
                           keys: [String]? = nil, capabilities: [AgentCapability] = [.interrupt]) -> AgentInfo {
            AgentInfo(agent: "claude", available: true, capabilities: capabilities,
                      attach: attach.map(AgentAttach.init(rawValue:)), attachReady: ready, sharedInterrupt: interrupt,
                      sharedSettings: settings, sharedSettingsKeys: keys)
        }

        @Test func stopNeedsTheCapabilityAndTheSharedInterrupt() {
            #expect(Attach.canInterruptShared(agent(attach: "channel", interrupt: true)))
            #expect(!Attach.canInterruptShared(agent(attach: "channel", interrupt: true, capabilities: [])))
            #expect(!Attach.canInterruptShared(agent(attach: "channel")))
            #expect(!Attach.canInterruptShared(nil))
        }

        @Test func aSettingIsSharedOnlyWhereTheKeysSaySo() {
            #expect(Attach.canSetShared(agent(attach: "daemon", settings: true), .permissionMode))
            #expect(Attach.canSetShared(agent(attach: "channel", settings: true, keys: ["model", "effort"]), .model))
            #expect(!Attach.canSetShared(agent(attach: "channel", settings: true, keys: ["model", "effort"]),
                                         .permissionMode))
            #expect(!Attach.canSetShared(agent(attach: "channel"), .model))
        }

        @Test func theHintNamesTheSetupEachAttachmentNeeds() {
            #expect(Attach.attachHint(agent(attach: nil)) == nil)
            #expect(Attach.attachHint(agent(attach: "daemon", ready: true)) == S.chat.attachHintRestart)
            #expect(Attach.attachHint(agent(attach: "daemon")) == S.chat.attachHintDaemon)
            #expect(Attach.attachHint(agent(attach: "extension")) == S.chat.attachHintExtension)
            #expect(Attach.attachHint(agent(attach: "leader")) == S.chat.attachHintLeader)
            #expect(Attach.attachHint(agent(attach: "channel")) == S.chat.attachHintChannel)
            #expect(Attach.deliveryLabel("absorbed") == "will be re-sent")
            #expect(Attach.deliveryLabel("delivered") == nil)
        }

        @Test func optionsApplyOnlyWhatTheyCarry() {
            let session = Session(sessionID: "s", deviceID: "d", agent: "codex", title: "", cwd: "/", model: "a",
                                  speed: "priority")
            #expect(SessionOptions(model: "b").applied(to: session).speed == "priority")
            #expect(SessionOptions(speed: .some(nil)).applied(to: session).speed == nil)
            #expect(SessionOptions(model: "b").applied(to: session).model == "b")
            #expect(AttachmentLimits.textTooLong(String(repeating: "界", count: 22_000)))
            #expect(!AttachmentLimits.textTooLong("short"))
        }

        @Test func labelPairsCoverWhateverTheAgentLists() {
            let models = [AgentOption(id: "s", label: "Sonnet"), AgentOption(id: "o", label: "Opus")]
            let efforts = [AgentOption(id: "h", label: "High")]
            #expect(LabelPair.pairs(models: models, efforts: efforts).map(\.key) == ["Sonnet High", "Opus High"])
            #expect(LabelPair.pairs(models: models, efforts: []).map(\.effort) == [nil, nil])
            #expect(LabelPair.pairs(models: [], efforts: []).isEmpty)
        }
    }
}

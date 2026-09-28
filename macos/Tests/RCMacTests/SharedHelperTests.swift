import Foundation
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

/// `web/tests/commands.test.ts`, case for case on the mock gateway's own lists
/// (`commandsFor` in `web/mock/fixtures.ts`), and the A27 cases of
/// `web/tests/protocol-fixtures.test.ts` on the protocol's worked list.
@Suite("Slash commands")
struct SlashCommandTests {
    private let codex = [
        Command(name: "compact", description: "Summarise the conversation to free up context"),
        Command(name: "review", description: "Review the working tree's changes and report issues",
                argument: "instructions"),
        Command(name: "init", description: "Write an AGENTS.md for this repository"),
        Command(name: "status", description: "Show the session's model, settings and token use"),
        Command(name: "usage", description: "Show account usage and when the limits reset"),
        Command(name: "skills", description: "List the skills this session can use"),
        Command(name: "hooks", description: "List the lifecycle hooks that are installed"),
        Command(name: "mcp", description: "List the MCP servers and the tools they bring")
    ]
    private let pi = [
        Command(name: "compact", description: "Summarise the conversation to free up context",
                argument: "instructions", group: "Built-in"),
        Command(name: "release-notes", description: "Draft release notes from the commits since the last tag",
                argument: "tag", group: "Prompts"),
        Command(name: "refactor-plan", description: "Plan a refactor before touching the code",
                argument: "area", group: "Prompts"),
        Command(name: "skill:pdf-tables", description: "Extract tables from a PDF into CSV", group: "Skills"),
        Command(name: "skill:sql-review", description: "Review a migration for locks and index use",
                group: "Skills"),
        Command(name: "rc-status", description: "Show what the remote-control extension is attached to",
                group: "Extensions"),
        Command(name: "web-search", description: "Search the web and summarise the results",
                argument: "query", group: "Extensions")
    ]

    @Test func theQueryOpensOnTheSlashAloneWithTheWholeListBehindIt() {
        #expect(SlashCommands.query("/") == "")
    }

    @Test func theQueryIsThePartialNameWhileItIsBeingTyped() {
        #expect(SlashCommands.query("/com") == "com")
        #expect(SlashCommands.query("/skill:pdf") == "skill:pdf")
    }

    @Test func theQueryClosesTheMomentTheNameIsFollowedByAnything() {
        #expect(SlashCommands.query("/review ") == nil)
        #expect(SlashCommands.query("/review the diff") == nil)
    }

    @Test func ordinaryTextIsNoQueryIncludingASlashInsideIt() {
        #expect(SlashCommands.query("") == nil)
        #expect(SlashCommands.query("run the tests") == nil)
        #expect(SlashCommands.query("look at src/lib/ws.ts") == nil)
        #expect(SlashCommands.query("\n/compact") == nil)
    }

    @Test func aTypedCommandSplitsTheFirstWordFromTheArgument() {
        #expect(SlashCommands.typed("/review focus on the retry logic")
                == .init(name: "review", argument: "focus on the retry logic"))
    }

    @Test func theArgumentIsLeftOutWhenNothingButSpaceFollowed() {
        #expect(SlashCommands.typed("/compact") == .init(name: "compact", argument: nil))
        #expect(SlashCommands.typed("/compact   ") == .init(name: "compact", argument: nil))
    }

    @Test func onlyWhatTheSessionOffersMatches() {
        #expect(SlashCommands.match(codex, draft: "/compact")?.command.name == "compact")
        // A terminal treats an unknown slash as text, and so do we.
        #expect(SlashCommands.match(codex, draft: "/nonesuch") == nil)
        #expect(SlashCommands.match(codex, draft: "compact") == nil)
    }

    @Test func aMatchCarriesWhateverWasTypedAfterTheName() {
        let match = SlashCommands.match(codex, draft: "/review gateway/link.py only")
        #expect(match?.command.name == "review")
        #expect(match?.argument == "gateway/link.py only")
    }

    @Test func filteringIsAPrefixOfTheNameInTheDevicesOwnOrder() {
        #expect(SlashCommands.filter(codex, query: "").map(\.name) == codex.map(\.name))
        #expect(SlashCommands.filter(codex, query: "s").map(\.name) == ["status", "skills"])
        #expect(SlashCommands.filter(codex, query: "ski").map(\.name) == ["skills"])
        #expect(SlashCommands.filter(codex, query: "zz").isEmpty)
    }

    @Test func aNamespacedNameMatchesByItsPrefix() {
        #expect(SlashCommands.filter(pi, query: "skill:").map(\.name) == ["skill:pdf-tables", "skill:sql-review"])
    }

    @Test func noHeaderWhenTheAgentDistinguishesOneGroupOrNone() {
        #expect(SlashCommands.sections(codex) == [.init(group: nil, items: codex)])
    }

    @Test func sectionsKeepTheOrderTheGroupsFirstAppearIn() {
        let sections = SlashCommands.sections(pi)
        #expect(sections.map(\.group) == ["Built-in", "Prompts", "Skills", "Extensions"])
        #expect(sections.flatMap(\.items).count == pi.count)
    }

    @Test func aOneGroupListStaysFlat() {
        let one = [Command(name: "compact", description: "x", group: "Built-in")]
        #expect(SlashCommands.sections(one) == [.init(group: nil, items: one)])
    }

    @Test func aCompletionLeavesASpaceOnlyWhereAnArgumentGoes() {
        #expect(SlashCommands.completion(for: Command(name: "compact", description: "")) == "/compact")
        #expect(SlashCommands.completion(for: Command(name: "review", description: "", argument: "instructions"))
                == "/review ")
    }

    @Test func theProtocolsWorkedListReadsTheSameWay() throws {
        let reply = try WebFixtures.object("app/reply.session.commands.json")
        let commands = try WebFixtures.decode([Command].self, from: (reply["result"] as? [String: Any])?["commands"])
        // The worked list carries more than one group, which is what sections it.
        #expect(SlashCommands.sections(commands).count > 1)
        #expect(SlashCommands.filter(commands, query: "comp").map(\.name) == ["compact"])

        let run = try WebFixtures.object("app/session.command.json")
        let name = try #require(run["name"] as? String)
        let argument = run["argument"] as? String
        let match = SlashCommands.match(commands, draft: "/\(name) \(argument ?? "")")
        #expect(match?.command.name == name)
        #expect(match?.argument == argument)
    }
}

/// A20. The last case is the web's own (`web/tests/shared-control.test.tsx`,
/// "sends the card selections alongside the draft") on the protocol's pending
/// question; the others pin the web's rule where RCCore's `QuestionDraft`
/// reads the draft another way.
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

    @Test func theCardsSelectionsGoAlongsideTheDraft() throws {
        let pending = try #require(try WebFixtures.pendingQuestions().first)
        let two = [pending, QuestionItem(id: "q2", prompt: "Anything else?", options: [], multi: false, allowText: true)]
        var draft = QuestionDraft(requestID: "r")
        draft.toggle("widen", of: pending)
        #expect(Answering.composeAnswer(two, draft: draft, text: "and raise the timeout")
                == ["q1": .options(["widen"]), "q2": .text("and raise the timeout")])
        // Nothing selected anywhere: the draft answers the first question.
        #expect(Answering.composeAnswer(two, draft: QuestionDraft(requestID: "r"), text: "clamp it")
                == ["q1": .text("clamp it")])
    }
}

/// `sessionOptions.ts` has no web test of its own: the rule is its comment's.
@Suite("Session options")
struct SessionOptionsTests {
    @Test func aPatchChangesOnlyWhatItCarriesAndANullSpeedIsTheStandardTier() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "codex", title: "", cwd: "/", model: "a",
                              speed: "priority")
        #expect(SessionOptions(model: "b").applied(to: session).speed == "priority")
        #expect(SessionOptions(speed: .some(nil)).applied(to: session).speed == nil)
        #expect(SessionOptions(model: "b").applied(to: session).model == "b")
    }
}

/// `modelLabels.ts` has no web test of its own: the rule is its comment's.
@Suite("Model labels")
struct ModelLabelTests {
    @Test func labelPairsCoverWhateverTheAgentLists() {
        let models = [AgentOption(id: "s", label: "Sonnet"), AgentOption(id: "o", label: "Opus")]
        let efforts = [AgentOption(id: "h", label: "High")]
        #expect(LabelPair.pairs(models: models, efforts: efforts).map(\.key) == ["Sonnet High", "Opus High"])
        #expect(LabelPair.pairs(models: models, efforts: []).map(\.effort) == [nil, nil])
        #expect(LabelPair.pairs(models: [], efforts: []).isEmpty)
    }
}

/// `attachments.ts`: the limits `web/tests/attachment-cap.test.tsx` counts
/// against, which are the wire's, and the one check on the text.
@Suite("Attachment limits")
struct AttachmentLimitTests {
    @Test func theLimitsAreTheWebs() {
        #expect(AttachmentLimits.maxAttachments == 8)
        #expect(AttachmentLimits.maxAttachmentBytes == 6 * 1024 * 1024)
        #expect(AttachmentLimits.maxTextBytes == 64 * 1024)
    }

    @Test func textIsMeasuredInUTF8Bytes() {
        #expect(AttachmentLimits.textTooLong(String(repeating: "界", count: 22_000)))
        #expect(!AttachmentLimits.textTooLong("short"))
    }
}

extension LanguageSensitive {
    /// `web/tests/shared-control.test.tsx`, case for case: the protocol's
    /// attach fixtures and the mock gateway's agents, each variation spread
    /// over its fixture as the web test spreads it. One web case has no Swift
    /// form — `{ ...claudeAgent, attach_ready: undefined }` has no hint — because
    /// RCCore's `AgentInfo` reads an absent `attach_ready` as false, which is
    /// "not set up yet" and has one.
    @Suite("Attach") @MainActor
    struct AttachTests {
        private let restartHint =
            "This terminal session was started without the attachment; restart it to control it from here"
        private let leaderHint = "Run rc-client grok setup on the device, then restart Grok to attach its sessions"
        private let daemonHint = "Run rc-client codex setup on the device to attach its Codex sessions"

        init() { InterfaceLanguageSource.shared.current = .en }

        private func attachAgent(_ change: (inout [String: Any]) -> Void = { _ in }) throws -> AgentInfo {
            try WebFixtures.agent(WebFixtures.object("objects/agent.claude-attach.json"), change)
        }

        private func daemonAgent(_ change: (inout [String: Any]) -> Void = { _ in }) throws -> AgentInfo {
            try WebFixtures.agent(WebFixtures.object("objects/agent.codex-daemon.json"), change)
        }

        private func mock(_ agent: [String: Any], _ change: (inout [String: Any]) -> Void = { _ in }) throws
            -> AgentInfo {
            try WebFixtures.agent(agent, change)
        }

        // MARK: A10

        @Test func aReadyDeviceWithAnUnattachedCLIAsksForARestart() throws {
            let attach = try attachAgent()
            #expect(Attach.attachHint(attach) == restartHint)
        }

        @Test func theHintFollowsTheAttachmentKindAndReadiness() throws {
            #expect(Attach.attachHint(nil) == nil)
            let claude = try mock(MockAgents.claude)
            let noShim = try mock(MockAgents.claudeNoShim)
            let noDaemon = try mock(MockAgents.codexNoDaemon)
            #expect(Attach.attachHint(claude)?.contains("restart it") == true)
            #expect(Attach.attachHint(noShim)?.contains("Remote Control shim") == true)
            #expect(Attach.attachHint(noDaemon)?.contains("rc-client codex setup") == true)
            // No `attach` means there is nothing to hint at.
            let unattached = try mock(MockAgents.codex) { $0["attach"] = NSNull() }
            #expect(Attach.attachHint(unattached) == nil)
        }

        @Test func stopIsHiddenWhereTheAttachmentCannotInterrupt() throws {
            let cannot = try attachAgent { $0["shared_interrupt"] = false }
            let can = try attachAgent()
            #expect(!Attach.canInterruptShared(cannot))
            #expect(Attach.canInterruptShared(can))
        }

        @Test func stopNeedsTheInterruptCapabilityAsWellAsTheFlag() throws {
            let agent = try attachAgent { agent in
                agent["shared_interrupt"] = true
                agent["capabilities"] = (agent["capabilities"] as? [String] ?? []).filter { $0 != "interrupt" }
            }
            #expect(!Attach.canInterruptShared(agent))
        }

        // MARK: A11

        @Test func theTwoBooleansAreReadOffTheAgent() throws {
            // The daemon names no subset, so every setting is the device's.
            let daemon = try daemonAgent()
            for key in SharedSetting.allCases { #expect(Attach.canSetShared(daemon, key), "\(key)") }
            #expect(Attach.canAttachShared(daemon))
            let attach = try attachAgent()
            #expect(!Attach.canAttachShared(attach))
            // Both default to false when the device says nothing.
            let noDaemon = try mock(MockAgents.codexNoDaemon)
            let unsaid = try mock(MockAgents.claude) { $0["shared_attachments"] = nil }
            #expect(!Attach.canSetShared(noDaemon, .model))
            #expect(!Attach.canAttachShared(unsaid))
            #expect(!Attach.canSetShared(nil, .model))
        }

        @Test func eachHalfFollowsItsOwnFlag() throws {
            let settingsOnly = try daemonAgent { $0["shared_attachments"] = false }
            #expect(Attach.canSetShared(settingsOnly, .model))
            #expect(!Attach.canAttachShared(settingsOnly))
            let attachmentsOnly = try daemonAgent { $0["shared_settings"] = false }
            #expect(!Attach.canSetShared(attachmentsOnly, .model))
            #expect(Attach.canAttachShared(attachmentsOnly))
        }

        @Test func stopAndInterruptAndSendFollowTheInterruptFlagAlone() throws {
            let daemon = try daemonAgent()
            #expect(Attach.canInterruptShared(daemon))
            // Settings and attachments say nothing about interrupting.
            let noInterrupt = try daemonAgent { $0["shared_interrupt"] = false }
            #expect(!Attach.canInterruptShared(noInterrupt))
            #expect(Attach.canSetShared(noInterrupt, .model))
        }

        @Test func aTerminalCodexSessionHintsForTheDaemon() throws {
            let noDaemon = try mock(MockAgents.codexNoDaemon)
            #expect(Attach.attachHint(noDaemon) == daemonHint)
        }

        // MARK: A28

        @Test func grokAsksForTheSetupCommandWhileTheLeaderIsOff() throws {
            let noLeader = try mock(MockAgents.grokNoLeader)
            #expect(Attach.attachHint(noLeader) == leaderHint)
        }

        @Test func grokAsksForARestartOnceTheDeviceIsReady() throws {
            let grok = try mock(MockAgents.grok)
            #expect(Attach.attachHint(grok) == restartHint)
        }

        @Test func aSharedGrokSessionKeepsBothPickers() throws {
            let grok = try mock(MockAgents.grok)
            #expect(Attach.canSetShared(grok, .model))
            #expect(Attach.canSetShared(grok, .permissionMode))
        }

        @Test func theLeaderTakesNoImages() throws {
            let grok = try mock(MockAgents.grok)
            #expect(!Attach.canAttachShared(grok))
        }

        @Test func grokShowsStopWhileTheTerminalsTurnRuns() throws {
            let grok = try mock(MockAgents.grok)
            #expect(Attach.canInterruptShared(grok))
        }

        // MARK: A40

        @Test func settingsAnswerPerKeyAndFallBackToAllFourWithoutAList() throws {
            let attach = try attachAgent()
            #expect(Attach.canSetShared(attach, .model))
            #expect(Attach.canSetShared(attach, .effort))
            #expect(!Attach.canSetShared(attach, .permissionMode))
            #expect(!Attach.canSetShared(attach, .speed))
            let noList = try attachAgent { $0["shared_settings_keys"] = nil }
            for key in SharedSetting.allCases { #expect(Attach.canSetShared(noList, key), "\(key)") }
            // The boolean still decides first: a list without it carries nothing.
            let off = try attachAgent { $0["shared_settings"] = false }
            for key in SharedSetting.allCases { #expect(!Attach.canSetShared(off, key), "\(key)") }
        }

        // MARK: A42

        @Test func theInterruptIsReadOffTheChannelAsOffADaemon() throws {
            let attach = try attachAgent()
            let daemon = try daemonAgent()
            let noShim = try mock(MockAgents.claudeNoShim)
            #expect(Attach.canInterruptShared(attach))
            #expect(Attach.canInterruptShared(daemon))
            // The shim is what provides the terminal, so a device without it
            // carries no interrupt either.
            #expect(!Attach.canInterruptShared(noShim))
        }

        @Test func onlyAMessageTheCLIAbsorbedCarriesAChip() {
            #expect(Attach.deliveryLabel("absorbed") == "will be re-sent")
            #expect(Attach.deliveryLabel("delivered") == nil)
            #expect(Attach.deliveryLabel(nil) == nil)
        }
    }

    /// `web/tests/language.test.tsx`: a relative time's words follow the
    /// interface language, and its numbers stay as they are.
    @Suite("Relative time in both languages") @MainActor
    struct RelativeTimeLanguageTests {
        /// `Date.UTC(2026, 8, 13, 12, 0, 0)`.
        private let now: Int64 = 1_789_300_800_000

        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func theWordsAreTranslatedAndTheNumbersLeftAlone() {
            #expect(Format.relativeTime(now - 3 * 60_000, now: now) == "3m")
            #expect(Format.relativeAgo(now - 3 * 60_000, now: now) == "3m ago")
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            #expect(Format.relativeTime(now - 3 * 60_000, now: now) == "3 分钟")
            #expect(Format.relativeAgo(now - 3 * 60_000, now: now) == "3 分钟前")
            #expect(Format.relativeAgo(now - 36 * 3_600_000, now: now) == "昨天")
        }
    }
}

/// The fixtures the web tests read, read from where they read them: the
/// protocol's worked examples, as JSON so a variation can be spread over one
/// the way a web test spreads it, and decoded the way a device's reply is.
enum WebFixtures {
    struct NotAnObject: Error {
        let path: String
    }

    /// `protocol/fixtures/<path>`.
    static func object(_ path: String) throws -> [String: Any] {
        let url = URL(filePath: #filePath)
            .deletingLastPathComponent()   // macos/Tests/RCMacTests
            .deletingLastPathComponent()   // macos/Tests
            .deletingLastPathComponent()   // macos
            .deletingLastPathComponent()   // repository root
            .appending(path: "protocol/fixtures/\(path)")
        guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else {
            throw NotAnObject(path: path)
        }
        return object
    }

    static func decode<Value: Decodable>(_ type: Value.Type, from json: Any?) throws -> Value {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: json ?? NSNull(),
                                                                    options: .fragmentsAllowed))
    }

    /// An agent as a device reports it, changed first the way a web test
    /// spreads a fixture: a key set to nil is left out, `NSNull()` is `null`.
    static func agent(_ json: [String: Any], _ change: (inout [String: Any]) -> Void = { _ in }) throws
        -> AgentInfo {
        var json = json
        change(&json)
        return try decode(AgentInfo.self, from: json)
    }

    /// The questions of `events/question.pending.json`.
    static func pendingQuestions() throws -> [QuestionItem] {
        try decode([QuestionItem].self, from: object("events/question.pending.json")["questions"])
    }
}

/// The mock gateway's agents (`web/mock/fixtures.ts`), with the fields the
/// attachment rules read.
enum MockAgents {
    static var claude: [String: Any] {
        ["agent": "claude", "available": true,
         "capabilities": ["worktree", "takeover", "interrupt", "queue", "attachments", "effort", "history", "commands"],
         "attach": "channel", "attach_ready": true, "shared_interrupt": true, "shared_settings": true,
         "shared_settings_keys": ["model", "effort"], "shared_attachments": false]
    }

    static var claudeNoShim: [String: Any] {
        var agent = claude
        agent["attach_ready"] = false
        agent["capabilities"] = ["worktree", "takeover", "interrupt", "queue", "attachments", "effort", "history"]
        agent["shared_interrupt"] = false
        agent["shared_settings"] = false
        agent["shared_settings_keys"] = nil
        return agent
    }

    static var codex: [String: Any] {
        ["agent": "codex", "available": true,
         "capabilities": ["worktree", "interrupt", "queue", "steer", "attachments", "effort", "history", "commands"],
         "attach": "daemon", "attach_ready": true, "shared_interrupt": true, "shared_settings": true,
         "shared_attachments": true]
    }

    static var codexNoDaemon: [String: Any] {
        var agent = codex
        agent["attach_ready"] = false
        agent["shared_interrupt"] = false
        agent["shared_settings"] = false
        agent["shared_attachments"] = false
        return agent
    }

    static var grok: [String: Any] {
        ["agent": "grok", "available": true,
         "capabilities": ["worktree", "interrupt", "queue", "effort", "history", "commands"],
         "attach": "leader", "attach_ready": true, "shared_interrupt": true, "shared_settings": true,
         "shared_attachments": false]
    }

    static var grokNoLeader: [String: Any] {
        var agent = grok
        agent["attach_ready"] = false
        agent["shared_interrupt"] = false
        agent["shared_settings"] = false
        return agent
    }
}

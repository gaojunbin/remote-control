import Testing
import Foundation
@testable import RCCore

/// Amendment A27: the terminal's `/` menu, on the phone. What is checked here
/// is the rule the panel, the hint line and Send all read — whether a draft is
/// a command at all, which rows it leaves on screen, and what the store does
/// with it — plus the frames that carry it.
@Suite("Slash commands (A27)")
struct SlashCommandTests {
    // MARK: - What a draft means

    @Test("The slash has to be the first character, or the draft is prose")
    func onlyALeadingSlash() {
        #expect(SlashDraft.parse("look in /etc/hosts") == nil)
        #expect(SlashDraft.parse(" /compact") == nil)
        #expect(SlashDraft.parse("") == nil)
        #expect(SlashDraft.parse("/") != nil)
    }

    @Test("A bare slash opens the whole list; letters after it are the filter")
    func namePartsOfADraft() throws {
        let bare = try #require(SlashDraft.parse("/"))
        #expect(bare.name.isEmpty)
        #expect(!bare.isComplete)
        #expect(bare.argument == nil)

        let typing = try #require(SlashDraft.parse("/rev"))
        #expect(typing.name == "rev")
        #expect(!typing.isComplete)
    }

    @Test("A space finishes the name, and everything after it is the argument")
    func argumentOfADraft() throws {
        let empty = try #require(SlashDraft.parse("/review "))
        #expect(empty.name == "review")
        #expect(empty.isComplete)
        #expect(empty.argument == nil, "a trailing space is where the argument goes, not an argument")

        let written = try #require(SlashDraft.parse("/review  focus on the retry logic "))
        #expect(written.name == "review")
        #expect(written.isComplete)
        #expect(written.argument == "focus on the retry logic")
    }

    // MARK: - What the panel shows

    private var pi: [Command] { DemoFixtures.piCommands }

    @Test("Rows are filtered by prefix of the name, whatever the phone capitalised")
    func filtering() {
        #expect(SlashDraft.filter(pi, query: "").count == pi.count)
        #expect(SlashDraft.filter(pi, query: "skill:").map(\.name)
                == ["skill:pdf-tables", "skill:web-research", "skill:screenshot"])
        #expect(SlashDraft.filter(pi, query: "Comp").map(\.name) == ["compact"])
        #expect(SlashDraft.filter(pi, query: "notes").isEmpty,
                "a prefix, not a search: the middle of a name matches nothing")
    }

    @Test("A name is matched whole, and case is not what tells two commands apart")
    func matching() {
        #expect(SlashDraft.match(pi, name: "compact")?.name == "compact")
        #expect(SlashDraft.match(pi, name: "Compact")?.name == "compact")
        #expect(SlashDraft.match(pi, name: "comp") == nil)
    }

    @Test("Headers are drawn only where there is more than one group")
    func sectioning() {
        let grouped = CommandSection.build(pi)
        #expect(grouped.map(\.title) == ["Prompts", "Skills", "Extensions", "Built-in"])
        #expect(grouped.reduce(0) { $0 + $1.commands.count } == pi.count)

        let oneSource = CommandSection.build(DemoFixtures.codexCommands)
        #expect(oneSource.count == 1)
        #expect(oneSource.first?.title == nil, "one source names nothing the list does not say")
        #expect(CommandSection.build([]).isEmpty)
    }

    // MARK: - The object on the wire

    @Test("A command decodes from the protocol's own worked list")
    func replyDecodes() throws {
        let url = Self.fixtures.appending(path: "app/reply.session.commands.json")
        let reply = try JSONDecoder().decode(JSONValue.self, from: try Data(contentsOf: url))
        let result = try #require(reply["result"]).decode(CommandsResult.self)
        #expect(result.commands.map(\.name)
                == ["compact", "review", "init", "status", "release-notes", "skill:pdf-tables"])
        let review = try #require(result.commands.first { $0.name == "review" })
        #expect(review.argument == "instructions")
        #expect(review.takesArgument)
        #expect(review.group == "Built-in")
        #expect(review.slash == "/review")
        #expect(review.line(argument: "the retry logic") == "/review the retry logic")
        #expect(review.line(argument: nil) == "/review")

        let compact = try #require(result.commands.first)
        #expect(!compact.takesArgument, "a command that takes nothing shows no placeholder")
    }

    @Test("An empty placeholder or group is a device saying nothing")
    func emptyStringsDecodeAsAbsent() throws {
        let json: JSONValue = ["name": "compact", "description": "Summarise", "argument": "", "group": ""]
        let command = try json.decode(Command.self)
        #expect(command.argument == nil)
        #expect(command.group == nil)
        #expect(!command.takesArgument)
    }

    @Test("The two requests carry exactly what the fixtures do")
    func requestsMatchTheFixtures() throws {
        let list = try Self.fixture("app/session.commands.json")
        let listed = GatewayRequest.commands(sessionID: list["session_id"]?.stringValue ?? "")
        #expect(listed.json["type"]?.stringValue == "session.commands")
        #expect(listed.json["session_id"] == list["session_id"])

        let run = try Self.fixture("app/session.command.json")
        let request = GatewayRequest.command(sessionID: run["session_id"]?.stringValue ?? "",
                                             name: run["name"]?.stringValue ?? "",
                                             argument: run["argument"]?.stringValue)
        #expect(request.json["type"]?.stringValue == "session.command")
        #expect(request.json["name"] == run["name"])
        #expect(request.json["argument"] == run["argument"])
        #expect(GatewayRequest.command(sessionID: "s", name: "compact").json["argument"] == nil,
                "a command that takes nothing sends no argument at all")
    }

    // MARK: - The store

    @Test("A pi session offers its groups, and the panel closes on a finished name")
    @MainActor
    func piSessionOffersItsCommands() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.piSessionID, agent: DemoFixtures.pi, gateway: gateway)
        #expect(chat.offersCommands)
        await chat.loadCommands()
        #expect(chat.commands.count == DemoFixtures.piCommands.count)

        chat.draft = "/"
        #expect(chat.commandRows.count == DemoFixtures.piCommands.count)
        #expect(chat.commandSections.map(\.title) == ["Prompts", "Skills", "Extensions", "Built-in"])

        chat.draft = "/rel"
        #expect(chat.commandRows.map(\.name) == ["release-notes"])
        #expect(chat.draftCommand == nil, "a half-typed name runs nothing")

        let row = try #require(chat.commandRows.first)
        chat.take(row)
        #expect(chat.draft == "/release-notes ",
                "a command that takes an argument is written with the space that shows where it goes")
        #expect(chat.commandRows.isEmpty, "which closes the panel")
        #expect(chat.commandHint?.name == "release-notes", "and hands over to the hint line")
        #expect(chat.draftCommand?.name == "release-notes")

        chat.take(try #require(SlashDraft.match(chat.commands, name: "changelog")))
        #expect(chat.draft == "/changelog", "one that takes nothing is left ready to run")
    }

    /// Amendment A40: Claude's panel holds exactly the one command the device
    /// can type into the terminal the shim gives it.
    @Test("Claude offers /compact and nothing else")
    @MainActor
    func claudeOffersOneCommand() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.liveSessionID, agent: DemoFixtures.claude, gateway: gateway)
        #expect(chat.offersCommands)
        await chat.loadCommands()
        #expect(chat.commands.map(\.name) == ["compact"])
        #expect(chat.commands.first?.takesArgument == false, "and it takes no argument")

        chat.draft = "/comp"
        #expect(chat.commandRows.map(\.name) == ["compact"], "which the panel names")
        chat.draft = "/compact"
        #expect(chat.draftCommand?.name == "compact", "and Send runs")
    }

    /// The capability is the shim's, not the agent's: a machine without it has
    /// no terminal of the device's own to type into (A40).
    @Test("The same agent without the shim draws no panel at all")
    @MainActor
    func claudeWithoutShimOffersNothing() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.liveSessionID,
                                 agent: DemoFixtures.claudeWithoutShim, gateway: gateway)
        #expect(!chat.offersCommands)
        await chat.loadCommands()
        #expect(chat.commands.isEmpty, "the app never even asks")

        chat.draft = "/compact"
        #expect(chat.commandDraft == nil)
        #expect(chat.commandRows.isEmpty)
        #expect(chat.draftCommand == nil)
    }

    @Test("A session the terminal holds draws no panel either")
    @MainActor
    func terminalSessionDrawsNoPanel() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.grokSessionID, agent: DemoFixtures.grok, gateway: gateway)
        await chat.loadCommands()
        #expect(chat.commands.count == DemoFixtures.grokCommands.count,
                "the device still says what the session offers")
        chat.draft = "/hooks"
        #expect(chat.commandDraft == nil, "but nothing here can type into it")
        #expect(chat.commandRows.isEmpty)

        await #expect(throws: GatewayErrorBody.self) {
            _ = try await gateway.request(.command(sessionID: DemoFixtures.grokSessionID,
                                                   name: "hooks-list"))
        }
    }

    @Test("The row is in the transcript before the device has answered")
    @MainActor
    func theRowIsDrawnAtOnce() async throws {
        // No frame handler here, so nothing the device sends is applied and the
        // optimistic row is all there is to look at.
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.piSessionID, agent: DemoFixtures.pi, gateway: gateway)
        await chat.loadCommands()

        chat.draft = "/compact keep the decisions"
        #expect(chat.draftCommand?.name == "compact")
        #expect(chat.canSend)
        await chat.runCommand()
        #expect(chat.draft.isEmpty, "the field is cleared the way sending clears it")
        #expect(chat.errorMessage == nil)
        #expect(chat.timeline.roots.last?.pending?.text == "/compact keep the decisions")
    }

    @Test("Running one echoes it once and reports what it did")
    @MainActor
    func runningACommand() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.piSessionID, agent: DemoFixtures.pi, gateway: gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }
        await chat.loadCommands()

        chat.draft = "/compact keep the decisions"
        await chat.runCommand()
        try await Self.settle { chat.timeline.optimistic.isEmpty }
        #expect(chat.timeline.roots.filter { $0.userMessage?.text == "/compact keep the decisions" }
                    .count == 1, "the device's echo replaces the row under the same id")
        try await Self.settle { chat.timeline.roots.contains { $0.notice != nil } }
        #expect(chat.timeline.roots.contains { $0.notice?.text.contains("compacted") == true },
                "and a state change reads as a notice rather than as a message")
    }

    @Test("A turn has to finish first, and nothing leaves the field until it has")
    @MainActor
    func aCommandWaitsForTheTurn() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.codexSharedSessionID, agent: DemoFixtures.codex,
                                 gateway: gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }
        await chat.loadCommands()
        #expect(chat.commands.map(\.name) == DemoFixtures.codexCommands.map(\.name))

        chat.draft = "/"
        #expect(chat.commandSections.count == 1, "Codex has one source, so the list has no headers")
        #expect(chat.commandSections.first?.title == nil)

        chat.draft = "/usage"
        #expect(chat.isRunning)
        #expect(chat.commandsWaitForTurn, "the rows are dimmed and the card says why")
        #expect(!chat.commandRows.isEmpty, "but the list is still readable")
        #expect(!chat.canSend, "and Send does not act")
        await chat.runCommand()
        #expect(chat.draft == "/usage", "nothing left the field")
        #expect(chat.timeline.optimistic.isEmpty, "and nothing was drawn in the transcript")

        // The turn ends, and the same draft runs.
        await chat.stop()
        try await Self.settle { !chat.isRunning }
        #expect(chat.canSend)
        await chat.runCommand()
        #expect(chat.errorMessage == nil)
        #expect(chat.draft.isEmpty)
    }

    @Test("Information a terminal would have printed arrives as a tool call")
    @MainActor
    func informationReadsAsAToolCall() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.codexSharedSessionID, agent: DemoFixtures.codex,
                                 gateway: gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }
        await chat.loadCommands()
        await chat.stop()
        try await Self.settle { !chat.isRunning }

        chat.draft = "/usage"
        await chat.runCommand()
        try await Self.settle { chat.timeline.roots.contains { $0.toolCall != nil } }
        let call = try #require(chat.timeline.roots.compactMap(\.toolCall).last)
        #expect(call.tool == "/usage")
        #expect(call.title == "/usage")
        #expect(call.kind == .other)
        #expect(call.status == .succeeded)
        #expect(call.output?.contains("5-hour window") == true)
    }

    @Test("A command's own output is drawn at Simple, and the agent's working is not")
    func commandOutputSurvivesTheSimpleLevel() throws {
        func entry(tool: String) -> TimelineEntry {
            var timeline = Timeline()
            _ = timeline.apply(SessionEvent(
                seq: 1, ts: 0, kind: SessionEvent.toolCallKind, blockID: "b",
                body: .toolCall(ToolCallPayload(tool: tool, kind: .other, title: tool,
                                                status: .succeeded, output: "…"))))
            return timeline.entries[0]
        }
        #expect(entry(tool: "/usage").isDrawn(at: .simple),
                "the reader asked for this by name, so Simple keeps it")
        #expect(entry(tool: "/usage").isDrawn(at: .detailed))
        #expect(!entry(tool: "Bash").isDrawn(at: .simple),
                "an ordinary tool call is still the agent's working")
    }

    @Test("A name the session does not offer is refused rather than sent as text")
    @MainActor
    func unknownNameIsRefused() async throws {
        let gateway = DemoGateway()
        await #expect(throws: GatewayErrorBody.self) {
            _ = try await gateway.request(.command(sessionID: DemoFixtures.piSessionID,
                                                   name: "definitely-not-a-command"))
        }
    }

    @Test("The list is asked for again only once it has gone stale")
    @MainActor
    func refreshRule() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(DemoFixtures.piSessionID, agent: DemoFixtures.pi, gateway: gateway)
        await chat.loadCommands()
        let first = chat.commands

        await chat.refreshCommands()
        #expect(chat.commands.count == first.count, "a fresh answer stands")
        await chat.refreshCommands(now: Date().addingTimeInterval(ChatStore.commandsStaleAfter + 1))
        #expect(chat.commands.count == first.count, "and a stale one is replaced by the next")
    }

    // MARK: - Helpers

    private static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent()   // ios/Tests/RCCoreTests
        .deletingLastPathComponent()   // ios/Tests
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
        .appending(path: "protocol/fixtures")

    private static func fixture(_ name: String) throws -> [String: JSONValue] {
        let data = try Data(contentsOf: fixtures.appending(path: name))
        return try JSONDecoder().decode(JSONValue.self, from: data).objectValue ?? [:]
    }

    @MainActor
    private static func chat(_ sessionID: String, agent: AgentInfo,
                             gateway: DemoGateway) throws -> ChatStore {
        let session = try #require(DemoFixtures.sessions.first { $0.sessionID == sessionID })
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = agent
        return chat
    }

    @MainActor
    private static func pump(_ gateway: DemoGateway, into chat: ChatStore) -> Task<Void, Never> {
        let events = gateway.events
        return Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
    }

    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

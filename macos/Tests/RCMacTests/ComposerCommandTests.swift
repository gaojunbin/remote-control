import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/CommandMenu.test.tsx` — A27, the terminal's `/` menu in the
    /// composer, on Codex's list.
    @Suite("Composer commands", .serialized) @MainActor
    struct ComposerCommandTests {
        nonisolated private static let list = [
            Command(name: "compact", description: "Summarise the conversation"),
            Command(name: "review", description: "Review the changes", argument: "instructions"),
            Command(name: "init", description: "Write an AGENTS.md"),
            Command(name: "status", description: "Show the session's model")
        ]

        private func harness(state: SessionState = .idle) async -> ComposerHarness {
            let harness = ComposerHarness(session: ComposerFixture.session(agent: "codex", state: state),
                                          agent: DemoFixtures.codex)
            await harness.channel.answer("session.commands") { _ in
                try JSONValue.encode(CommandsResult(commands: Self.list))
            }
            await harness.chat.loadCommands()
            return harness
        }

        @Test func aSlashOpensTheWholeList() async {
            let harness = await harness()
            harness.composer.userTyped("/")
            #expect(harness.composer.panelOpen)
            #expect(harness.composer.panelRows.map(\.name) == Self.list.map(\.name))
            #expect(harness.composer.highlighted?.name == "compact")
        }

        @Test func anAgentWithoutTheCapabilityDrawsNothing() {
            let harness = ComposerHarness()
            harness.composer.userTyped("/")
            #expect(!harness.composer.panelOpen)
        }

        @Test func moreLettersNarrowTheListAndASpaceGivesWayToTheHint() async {
            let harness = await harness()
            harness.composer.userTyped("/re")
            #expect(harness.composer.panelRows.map(\.name) == ["review"])
            harness.composer.userTyped("/review ")
            #expect(!harness.composer.panelOpen)
            #expect(harness.composer.commandMatch?.command.name == "review")
            harness.composer.userTyped("/nothing")
            #expect(!harness.composer.panelOpen)
        }

        @Test func ordinaryTextWithASlashInsideIsNotACommand() async {
            let harness = await harness()
            harness.composer.userTyped("look at src/lib/ws.ts")
            #expect(!harness.composer.panelOpen && harness.composer.commandMatch == nil)
        }

        @Test func theArrowsMoveTheHighlightAndEscPutsThePanelAway() async {
            let harness = await harness()
            harness.composer.userTyped("/")
            #expect(harness.composer.handle(.down, shift: false, hasMarkedText: false))
            #expect(harness.composer.highlighted?.name == "review")
            #expect(harness.composer.handle(.escape, shift: false, hasMarkedText: false))
            #expect(!harness.composer.panelOpen)
            #expect(harness.chat.draft == "/")
            // A changed draft is a changed list: the panel is back.
            harness.composer.userTyped("/s")
            #expect(harness.composer.panelOpen)
        }

        @Test func tabTakesTheRowLeavingASpaceForItsArgument() async {
            let harness = await harness()
            harness.composer.userTyped("/")
            _ = harness.composer.handle(.down, shift: false, hasMarkedText: false)
            _ = harness.composer.handle(.tab, shift: false, hasMarkedText: false)
            #expect(harness.chat.draft == "/review ")
        }

        @Test func enterTakesTheRowAndTheSecondEnterRunsIt() async {
            let harness = await harness()
            harness.composer.userTyped("/comp")
            _ = harness.composer.handle(.enter, shift: false, hasMarkedText: false)
            #expect(harness.chat.draft == "/compact")
            _ = harness.composer.handle(.enter, shift: false, hasMarkedText: false)
            let ran = await harness.channel.waitFor("session.command")
            #expect(ran.first?["name"]?.stringValue == "compact")
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        @Test func aListedCommandRunsWithItsArgument() async {
            let harness = await harness()
            harness.composer.setDraft("/review focus on the retry logic")
            harness.composer.submit(.auto)
            let ran = await harness.channel.waitFor("session.command")
            #expect(ran.first?["name"]?.stringValue == "review")
            #expect(ran.first?["argument"]?.stringValue == "focus on the retry logic")
            #expect(await composerEventually { harness.chat.draft.isEmpty })
        }

        @Test func anUnknownSlashIsSentAsText() async {
            let harness = await harness()
            harness.composer.setDraft("/nonesuch do the thing")
            harness.composer.submit(.auto)
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["text"]?.stringValue == "/nonesuch do the thing")
        }

        @Test func aRefusalIsReportedUnderTheFieldInTheDevicesWords() async {
            let harness = await harness()
            await harness.channel.answer("session.command") { _ in
                throw ComposerFixture.refusal(.conflict, "the terminal is busy; try again in a moment")
            }
            harness.composer.setDraft("/compact")
            harness.composer.submit(.auto)
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.composer.errors == ["the terminal is busy; try again in a moment"])
            #expect(harness.chat.draft == "/compact")
            #expect(harness.chat.errorMessage == nil)
        }

        @Test func aCommandWaitsForTheTurnInTheFootersOwnWords() async throws {
            let harness = await harness(state: .running)
            harness.composer.setDraft("/compact")
            harness.composer.submit(.auto)
            #expect(harness.composer.errors == [S.commands.whileRunning])
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.command").isEmpty)
        }
    }
}

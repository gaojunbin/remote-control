import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/up-next.test.tsx` and `queued-edit.test.tsx` — A43: Up next,
    /// and a queued message taken back into the field and put back again.
    @Suite("Composer Up next", .serialized) @MainActor
    struct ComposerQueueTests {
        private static let first = QueuedMessage(id: "q-1", text: "Then run the full test suite.", ts: 5)
        private static let withFiles = QueuedMessage(id: "q-2", text: "These two screenshots", ts: 6, attachments: 2)
        private static let last = QueuedMessage(id: "q-3", text: "After that, bump Vite.", ts: 7)

        private func harness(state: SessionState = .running, agent: String = "claude",
                             info: AgentInfo? = DemoFixtures.claude) -> ComposerHarness {
            let harness = ComposerHarness(session: ComposerFixture.session(agent: agent, state: state), agent: info)
            ComposerFixture.queue([Self.first, Self.withFiles, Self.last], into: harness.chat)
            return harness
        }

        /// The field held words and a file when the edit began.
        private func editing(_ harness: ComposerHarness) async -> ComposerAttachment {
            let file = ComposerAttachment(name: "draft.png", mime: "image/png", data: Data(count: 3))
            harness.composer.setDraft("a draft I was typing")
            harness.host.drafts.add([file], to: harness.composer.key)
            harness.composer.edit(Self.first)
            _ = await composerEventually { harness.chat.queuedEdit != nil }
            return file
        }

        @Test func aMessageWithFilesCanBeRemovedButNotEdited() {
            let harness = harness()
            #expect(harness.composer.canEdit(Self.first))
            #expect(!harness.composer.canEdit(Self.withFiles))
        }

        @Test func theEntryLeavesTheLineFirstThenTheDraftGoesAside() async {
            let harness = harness()
            let focus = harness.composer.focusRequest
            _ = await editing(harness)
            let removed = await harness.channel.sent("session.queue_remove")
            #expect(removed.first?["queued_id"]?.stringValue == "q-1")
            #expect(harness.chat.draft == Self.first.text)
            #expect(harness.composer.attachments.isEmpty)
            #expect(harness.composer.focusRequest > focus)
        }

        @Test func nothingOpensAndOneLineSaysSoWhenTheDeviceHadSentIt() async {
            let harness = harness()
            await harness.channel.answer("session.queue_remove") { _ in
                throw ComposerFixture.refusal(.notFound, "no such entry")
            }
            harness.composer.edit(Self.first)
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.composer.errors == [S.composer.alreadySent])
            #expect(harness.chat.queuedEdit == nil)
            #expect(harness.chat.errorMessage == nil)
        }

        @Test func theEditedWordsGoBackUnderTheEntrysTsAsQueueEvenBehindASteeringAgent() async {
            let harness = harness(agent: "codex", info: DemoFixtures.codex)
            _ = await editing(harness)
            #expect(harness.composer.primaryLabel == S.composer.queue)
            #expect(harness.composer.placeholder == S.composer.placeholderQueued)
            harness.composer.userTyped("Then run the full test suite twice.")
            harness.composer.submit(.auto)
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["mode"]?.stringValue == "queue")
            #expect(sent.first?["queue_ts"]?.intValue == 5)
            #expect(sent.first?["text"]?.stringValue == "Then run the full test suite twice.")
        }

        @Test func theFieldGetsBackWhatItHeldOnceTheWordsAreBackInTheLine() async {
            let harness = harness()
            let file = await editing(harness)
            harness.composer.submit(.auto)
            #expect(await composerEventually { harness.chat.queuedEdit == nil })
            #expect(harness.chat.draft == "a draft I was typing")
            #expect(harness.composer.attachments == [file])
        }

        @Test func cancelPutsTheOriginalWordsBack() async {
            let harness = harness()
            _ = await editing(harness)
            harness.composer.userTyped("changed my mind")
            harness.composer.cancelEdit()
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["text"]?.stringValue == Self.first.text)
            #expect(sent.first?["queue_ts"]?.intValue == 5)
            #expect(await composerEventually { harness.chat.queuedEdit == nil })
            #expect(harness.chat.draft == "a draft I was typing")
        }

        @Test func aRefusedPutBackKeepsEditingWithTheDraftStillAside() async {
            let harness = harness()
            _ = await editing(harness)
            await harness.channel.answer("session.send") { _ in
                throw ComposerFixture.refusal(.conflict, "the queue is full")
            }
            harness.composer.submit(.auto)
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.composer.errors == ["the queue is full"])
            #expect(harness.chat.queuedEdit != nil)
            #expect(harness.chat.draft == Self.first.text)
            #expect(harness.composer.attachments.isEmpty)
        }

        @Test func theFieldHoldsStillWhileTheWordsAreOnTheirWay() async {
            let harness = harness()
            _ = await editing(harness)
            let gate = ComposerGate()
            await harness.channel.answer("session.send") { _ in
                await gate.wait()
                return try JSONValue.encode(SendResult(accepted: .queued))
            }
            harness.composer.submit(.auto)
            #expect(await composerEventually { harness.composer.returning })
            #expect(harness.composer.slot == .working)
            #expect(!harness.composer.acceptsFiles)
            harness.composer.primarySubmit()
            harness.composer.submit(.auto)
            await gate.release()
            #expect(await composerEventually { harness.chat.queuedEdit == nil })
            #expect(await harness.channel.sent("session.send").count == 1)
        }

        @Test func aTurnThatEndedMeanwhileTakesTheWordsAtOnceAndTheButtonSaysSend() async {
            let harness = harness(state: .idle)
            _ = await editing(harness)
            #expect(harness.composer.primaryLabel == S.composer.send)
            harness.composer.primarySubmit()
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["mode"]?.stringValue == "queue")
            #expect(sent.first?["queue_ts"]?.intValue == 5)
        }

        @Test func interruptAndSendKeepsNoPlaceInTheLine() async {
            let harness = harness()
            _ = await editing(harness)
            harness.composer.submit(.interrupt)
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["mode"]?.stringValue == "interrupt")
            #expect(sent.first?["queue_ts"] == nil)
        }

        @Test func whileEditingTheFieldIsAMessageAndNothingElse() async {
            let harness = harness(agent: "codex", info: DemoFixtures.codex)
            _ = await editing(harness)
            harness.composer.userTyped("/")
            #expect(!harness.composer.panelOpen)
            #expect(!harness.composer.canEdit(Self.last))
        }

        @Test func aQuestionWaitsForTheFieldWhileItIsEditing() async {
            let harness = harness(state: .needsInput)
            _ = await editing(harness)
            ComposerFixture.question([QuestionItem(id: "q", prompt: "Which?", allowText: true)],
                                     into: harness.chat, seq: 2)
            #expect(!harness.composer.answering)
            harness.composer.cancelEdit()
            #expect(await composerEventually { harness.chat.queuedEdit == nil })
            #expect(harness.composer.answering)
        }

        @Test func removeTakesAMessageOutOfTheLineAndTheBannerSaysANotFound() async {
            let harness = harness()
            await harness.channel.answer("session.queue_remove") { _ in
                throw ComposerFixture.refusal(.notFound, "gone")
            }
            harness.composer.remove(Self.last)
            #expect(await harness.channel.waitFor("session.queue_remove").first?["queued_id"]?.stringValue == "q-3")
            // A Remove's refusal is the page's banner, as on the web.
            #expect(await composerEventually { harness.chat.errorMessage != nil })
            #expect(harness.composer.errors.isEmpty)
        }
    }
}

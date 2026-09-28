import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/Composer.test.tsx`, `composer-errors.test.tsx`,
    /// `optimistic-send.test.tsx`: what Send sends, and what comes back.
    @Suite("Composer send", .serialized) @MainActor
    struct ComposerSendTests {
        @Test func anIdleSessionIsSentAuto() async {
            let harness = ComposerHarness()
            harness.composer.setDraft("run the suite")
            #expect(harness.composer.primaryLabel == S.composer.send)
            harness.composer.submit(.auto)
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["mode"]?.stringValue == "auto")
            #expect(sent.first?["text"]?.stringValue == "run the suite")
        }

        @Test func aRunningTurnStillSendsAutoAndTheButtonSaysQueue() async {
            let harness = ComposerHarness(session: ComposerFixture.session(state: .running))
            harness.composer.setDraft("and then this")
            #expect(harness.composer.primaryLabel == S.composer.queue)
            #expect(harness.composer.placeholder == S.composer.placeholderQueued)
            harness.composer.submit(.auto)
            #expect(await harness.channel.waitFor("session.send").first?["mode"]?.stringValue == "auto")
        }

        @Test func aSteeringAgentsButtonStaysSend() {
            let harness = ComposerHarness(session: ComposerFixture.session(agent: "codex", state: .running),
                                          agent: DemoFixtures.codex)
            #expect(harness.composer.primaryLabel == S.composer.send)
            #expect(harness.composer.placeholder == S.composer.placeholderSteer)
        }

        @Test func interruptAndSendIsOfferedOnlyWhileATurnRuns() async {
            #expect(!ComposerHarness().composer.showsSendMenu)
            let harness = ComposerHarness(session: ComposerFixture.session(state: .running))
            #expect(harness.composer.showsSendMenu)
            harness.composer.setDraft("stop and do this")
            harness.composer.submit(.interrupt)
            #expect(await harness.channel.waitFor("session.send").first?["mode"]?.stringValue == "interrupt")
        }

        @Test func anEmptyMessageIsNotSent() async throws {
            let harness = ComposerHarness()
            harness.composer.setDraft("   \n ")
            #expect(harness.composer.primaryDisabled)
            harness.composer.submit(.auto)
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        /// A12: the field is empty and the message on its way before the reply.
        @Test func theFieldIsClearedBeforeTheGatewayAnswers() async {
            let harness = ComposerHarness()
            let gate = ComposerGate()
            await harness.channel.answer("session.send") { _ in
                await gate.wait()
                return try JSONValue.encode(SendResult(accepted: .sent))
            }
            harness.composer.setDraft("quick one")
            harness.composer.submit(.auto)
            #expect(await composerEventually { harness.chat.draft.isEmpty })
            #expect(harness.chat.timeline.optimistic.map(\.text) == ["quick one"])
            await gate.release()
        }

        @Test func aRefusedSendHandsTheWordsAndFilesBackAndSaysWhyUnderTheField() async {
            let harness = ComposerHarness()
            await harness.channel.answer("session.send") { _ in
                throw ComposerFixture.refusal(.badRequest, "the message was refused")
            }
            let file = ComposerAttachment(name: "notes.txt", mime: "text/plain", data: Data("hi".utf8))
            harness.host.drafts.add([file], to: harness.composer.key)
            harness.composer.setDraft("try this")
            harness.composer.submit(.auto)
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.composer.errors == ["the message was refused"])
            #expect(harness.chat.draft == "try this")
            #expect(harness.composer.attachments == [file])
            // The line under the field says it; the page's banner does not.
            #expect(harness.chat.errorMessage == nil)
        }

        @Test func aNewerDraftWinsOverAnOlderRefusal() async {
            let harness = ComposerHarness()
            let gate = ComposerGate()
            await harness.channel.answer("session.send") { _ in
                await gate.wait()
                throw ComposerFixture.refusal(.conflict, "busy")
            }
            harness.composer.setDraft("first")
            harness.composer.submit(.auto)
            #expect(await composerEventually { harness.chat.draft.isEmpty })
            harness.composer.userTyped("second")
            await gate.release()
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.chat.draft == "second")
        }

        @Test func aMessagePastTheLimitIsRefusedHere() async throws {
            let harness = ComposerHarness()
            harness.composer.setDraft(String(repeating: "x", count: AttachmentLimits.maxTextBytes + 1))
            harness.composer.submit(.auto)
            #expect(harness.composer.errors == [S.composer.textTooLong])
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        @Test func filesGoAloneWhenThereAreNoWords() async {
            let harness = ComposerHarness()
            harness.host.drafts.add([ComposerAttachment(name: "a.png", mime: "image/png", data: Data(count: 4))],
                                    to: harness.composer.key)
            #expect(!harness.composer.primaryDisabled)
            harness.composer.submit(.auto)
            let sent = await harness.channel.waitFor("session.send")
            #expect(sent.first?["text"]?.stringValue == "")
            #expect(sent.first?["attachments"]?.arrayValue?.count == 1)
            #expect(harness.composer.attachments.isEmpty)
        }

        @Test func aTerminalSessionTakesNothingAndSaysWhoHoldsIt() async throws {
            let harness = ComposerHarness(session: ComposerFixture.session(state: .running, control: .terminal))
            #expect(harness.composer.gates.disabled)
            #expect(harness.composer.placeholder == S.composer.placeholderTerminal)
            harness.composer.setDraft("hello")
            #expect(harness.composer.primaryDisabled)
            harness.composer.submit(.auto)
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        @Test func anOfflineDeviceDisablesTheComposerAndTheMic() {
            let harness = ComposerHarness()
            harness.host.online = false
            #expect(harness.composer.placeholder == S.composer.placeholderOffline)
            #expect(!harness.composer.voiceEnabled)
            harness.host.online = true
            harness.host.sttEnabled = false
            #expect(!harness.composer.voiceEnabled)
        }

        /// A20: while a question waits the field is its free-text answer.
        @Test func aPendingQuestionIsAnsweredFromTheField() async {
            let harness = ComposerHarness(session: ComposerFixture.session(state: .needsInput))
            ComposerFixture.question([QuestionItem(id: "q1", prompt: "How should it split?", allowText: true)],
                                     into: harness.chat)
            #expect(harness.composer.answering)
            #expect(harness.composer.primaryLabel == S.composer.answer)
            #expect(harness.composer.placeholder == S.composer.placeholderAnswer)
            #expect(harness.composer.primaryDisabled)
            harness.composer.setDraft("by tenant")
            harness.composer.primarySubmit()
            let answered = await harness.channel.waitFor("session.answer")
            #expect(answered.first?["request_id"]?.stringValue == "req-q")
            #expect(answered.first?["answers"]?.objectValue?["q1"] != nil)
            #expect(harness.chat.draft.isEmpty)
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        @Test func aQuestionThatTakesNoWordsLeavesAnswerDisabled() {
            let harness = ComposerHarness(session: ComposerFixture.session(state: .needsInput))
            ComposerFixture.question([QuestionItem(id: "q1", prompt: "Pick one",
                                                   options: [QuestionOption(id: "a", label: "A")])],
                                     into: harness.chat)
            harness.composer.setDraft("words")
            #expect(harness.composer.answer == nil)
            #expect(harness.composer.primaryDisabled)
        }

        /// A21/A40: the card's changes go through the store's `session.set`.
        @Test func aChangeFromTheCardIsDrawnAtOnceAndGoesAsASessionSet() async {
            let harness = ComposerHarness()
            let gate = ComposerGate()
            let session = harness.chat.session
            await harness.channel.answer("session.set") { _ in
                await gate.wait()
                return try JSONValue.encode(SessionResult(session: session))
            }
            harness.composer.setOption(SessionOptions(effort: "low"))
            // Drawn before the device answers, as `applyOptions` draws it.
            #expect(await composerEventually { harness.chat.session.effort == "low" })
            await gate.release()
            let set = await harness.channel.waitFor("session.set")
            #expect(set.first?["effort"]?.stringValue == "low")
            harness.composer.setOption(SessionOptions(speed: .some(nil)))
            let standard = await harness.channel.waitFor("session.set", count: 2)
            #expect(standard.last?["speed"] == .null)
        }
    }
}

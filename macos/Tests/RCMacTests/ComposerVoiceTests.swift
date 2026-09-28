import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/voice-composer.test.tsx` and `polish-composer.test.tsx`:
    /// dictation into the composer's own field, and polish of what it left.
    @Suite("Composer dictation", .serialized) @MainActor
    struct ComposerVoiceTests {
        private func listening(_ harness: ComposerHarness) async -> FakeSpeech.Socket? {
            harness.composer.startVoice()
            guard await composerEventually({ harness.composer.voice.state == .listening }) else { return nil }
            return harness.speech.sockets.last
        }

        /// Dictate, click Done, and let the gateway's final transcript land.
        private func dictate(_ harness: ComposerHarness, _ final: String) async {
            guard let socket = await listening(harness) else { return }
            socket.deliver(.partial(final))
            harness.composer.voice.done()
            _ = await composerEventually { socket.toldToTranscribe }
            socket.deliver(.final(final))
        }

        @Test func theTranscriptIsAppendedToTheDraftTheMicWasPressedOn() async throws {
            let harness = ComposerHarness()
            harness.composer.setDraft("after lunch")
            let socket = try #require(await listening(harness))
            socket.deliver(.partial("run the suite"))
            #expect(harness.chat.draft == "after lunch run the suite")
            socket.deliver(.partial("run the whole suite"))
            #expect(harness.chat.draft == "after lunch run the whole suite")
        }

        @Test func doneLeavesTheTranscriptInTheFieldAndSendsNothing() async throws {
            let harness = ComposerHarness()
            await dictate(harness, "run the auth suite")
            #expect(await composerEventually { harness.composer.voice.state == .idle })
            #expect(harness.chat.draft == "run the auth suite")
            #expect(harness.composer.slot == .send)
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.send").isEmpty)
        }

        @Test func theClickOnDoneIsAnsweredWithTheSpinner() async throws {
            let harness = ComposerHarness()
            let socket = try #require(await listening(harness))
            #expect(harness.composer.slot == .done)
            harness.composer.voice.done()
            #expect(harness.composer.slot == .working)
            socket.deliver(.final("words"))
            #expect(harness.composer.slot == .send)
        }

        @Test func aKeystrokeTakesTheFieldBackAndKeepsTheWords() async throws {
            let harness = ComposerHarness()
            let socket = try #require(await listening(harness))
            socket.deliver(.partial("half a"))
            harness.composer.userTyped("half a sentence")
            #expect(harness.composer.voice.state == .idle)
            #expect(socket.toldToDrop)
            socket.deliver(.partial("late words"))
            #expect(harness.chat.draft == "half a sentence")
        }

        @Test func aPressOnTheFieldTakesItBackSoTheWordsCanBeReadBack() async throws {
            let harness = ComposerHarness()
            let socket = try #require(await listening(harness))
            socket.deliver(.partial("read me back"))
            harness.composer.pointerDownInField()
            #expect(harness.composer.voice.state == .idle)
            #expect(harness.chat.draft == "read me back")
        }

        @Test func dictatedWritesFollowTheTailAndTypingDoesNot() async throws {
            let harness = ComposerHarness()
            let socket = try #require(await listening(harness))
            let before = harness.composer.tailRequest
            socket.deliver(.partial("a line"))
            #expect(harness.composer.tailRequest == before + 1)
            socket.deliver(.partial("a line"))
            #expect(harness.composer.tailRequest == before + 1)
            harness.composer.userTyped("typed")
            #expect(harness.composer.tailRequest == before + 1)
        }

        // MARK: A29 — polish

        private func polishing() -> (ComposerHarness, ComposerGate) {
            let harness = ComposerHarness()
            let gate = ComposerGate()
            harness.host.polishChoice = PolishChoice(model: "gpt-4.1-mini", strength: .strong)
            harness.host.polishAnswer = { _ in
                await gate.wait()
                return "  Run the auth suite.\n"
            }
            return (harness, gate)
        }

        @Test func theDictatedWordsTheChoicesAndTheConversationGoToTheModel() async {
            let (harness, gate) = polishing()
            harness.composer.setDraft("note:")
            await dictate(harness, "um run the the auth suite")
            #expect(await composerEventually { harness.composer.polish == .polishing })
            #expect(await composerEventually { !harness.host.polishRequests.isEmpty })
            let request = harness.host.polishRequests.first
            #expect(request?.text == "um run the the auth suite")
            #expect(request?.model == "gpt-4.1-mini" && request?.strength == .strong)
            #expect(request?.language == "auto")
            #expect(harness.composer.slot == .working)
            await gate.release()
            #expect(await composerEventually { harness.chat.draft == "note: Run the auth suite." })
            #expect(harness.composer.polish == .polished(span: PolishSpan(base: "note:", dictated: "um run the the auth suite"),
                                                         text: "Run the auth suite."))
            #expect(harness.composer.slot == .send)
        }

        @Test func undoGivesTheDictatedWordsBack() async {
            let (harness, gate) = polishing()
            await dictate(harness, "um run it")
            await gate.release()
            #expect(await composerEventually { harness.chat.draft == "Run the auth suite." })
            harness.composer.undoPolish()
            #expect(harness.chat.draft == "um run it")
            #expect(harness.composer.polish == .idle)
        }

        @Test func theNextEditTakesTheNoteAway() async {
            let (harness, gate) = polishing()
            await dictate(harness, "um run it")
            await gate.release()
            #expect(await composerEventually { harness.chat.draft == "Run the auth suite." })
            harness.composer.userTyped("Run the auth suite. Now.")
            #expect(harness.composer.polish == .idle)
        }

        @Test func aFailureLeavesTheWordsAsDictatedAndSaysSo() async {
            let harness = ComposerHarness()
            harness.host.polishChoice = PolishChoice(model: "m", strength: .moderate)
            harness.host.polishAnswer = { _ in throw TransportError.requestTimedOut }
            await dictate(harness, "um run it")
            #expect(await composerEventually { harness.composer.polish == .failed })
            #expect(harness.chat.draft == "um run it")
            #expect(harness.composer.slot == .send)
        }

        @Test func enterWaitsWithSendWhileTheModelIsWriting() async throws {
            let (harness, gate) = polishing()
            await dictate(harness, "um run it")
            #expect(await composerEventually { harness.composer.polish == .polishing })
            #expect(harness.composer.handle(.enter, shift: false, hasMarkedText: false))
            #expect(!harness.composer.showsSendMenu)
            try await Task.sleep(for: .milliseconds(50))
            #expect(await harness.channel.sent("session.send").isEmpty)
            await gate.release()
        }

        @Test func typingOverTheWaitGivesSendBackAndTheLateAnswerIsDropped() async throws {
            let (harness, gate) = polishing()
            await dictate(harness, "um run it")
            #expect(await composerEventually { harness.composer.polish == .polishing })
            harness.composer.userTyped("my own words")
            #expect(harness.composer.polish == .idle)
            #expect(harness.composer.slot == .send)
            await gate.release()
            try await Task.sleep(for: .milliseconds(50))
            #expect(harness.chat.draft == "my own words")
        }

        @Test func nothingIsPolishedWithoutAModelChosenOnAGatewayThatHasOne() async throws {
            let harness = ComposerHarness()
            await dictate(harness, "um run it")
            #expect(await composerEventually { harness.composer.voice.state == .idle })
            try await Task.sleep(for: .milliseconds(30))
            #expect(harness.composer.polish == .idle)
            #expect(harness.host.polishRequests.isEmpty)
        }
    }
}

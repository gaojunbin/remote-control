import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/voice.test.ts`: the dictation state machine — the mic button
    /// starts it, only Done and a reach for the field end it, nothing is ever
    /// sent, and a dictation longer than one utterance is chained across
    /// sockets without losing the seam. On a clock a hundred times shorter.
    @Suite("Voice controller", .serialized) @MainActor
    struct VoiceControllerTests {
        private let speech = FakeSpeech()
        private var transcripts: [(text: String, isFinal: Bool)] { log.entries }
        private let log = VoiceTranscriptLog()

        private func controller() -> VoiceController {
            let voice = VoiceController(services: speech.services, timing: .fast)
            let log = log
            voice.onTranscript = { text, isFinal in log.entries.append((text, isFinal)) }
            return voice
        }

        private func listening(_ voice: VoiceController) async -> Bool {
            voice.start()
            return await composerEventually { voice.state == .listening }
        }

        @Test func itListensFromTheMomentTheMicIsPressed() async {
            let voice = controller()
            #expect(await listening(voice))
            #expect(speech.sockets.count == 1)
        }

        @Test func doneKeepsTheTranscriptAndNothingIsSent() async throws {
            let voice = controller()
            #expect(await listening(voice))
            let socket = try #require(speech.sockets.first)
            socket.deliver(.partial("run the auth suite"))
            #expect(transcripts.last?.text == "run the auth suite" && transcripts.last?.isFinal == false)

            voice.done()
            #expect(voice.state == .finishing)
            #expect(await composerEventually { socket.toldToTranscribe })
            socket.deliver(.final("run the auth suite on CI"))
            #expect(transcripts.last?.text == "run the auth suite on CI" && transcripts.last?.isFinal == true)
            #expect(voice.state == .idle)
        }

        @Test func cancelPublishesNothingAndDropsTheUtterance() async throws {
            let voice = controller()
            #expect(await listening(voice))
            let socket = try #require(speech.sockets.first)
            socket.deliver(.partial("forget this"))
            voice.cancel()
            #expect(voice.state == .idle)
            #expect(socket.toldToDrop)
            #expect(!transcripts.contains { $0.isFinal })
        }

        @Test func thereIsNoTimeLimit() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.speak(at: 0.5)
            try await Task.sleep(for: .milliseconds(1_500))
            #expect(voice.state == .listening)
            #expect(!transcripts.contains { $0.isFinal })
            voice.cancel()
        }

        @Test func segmentsChainAcrossSocketsAndJoinInSpokenOrder() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.sockets[0].deliver(.partial("first half"))
            // The replacement is taking audio before the outgoing one transcribes.
            #expect(await composerEventually { speech.sockets.count == 2 })
            #expect(speech.sockets[0].toldToTranscribe)

            speech.sockets[1].deliver(.partial("second half"))
            speech.sockets[0].deliver(.final("first half exactly"))
            #expect(transcripts.last?.text == "first half exactly second half" && transcripts.last?.isFinal == false)

            voice.done()
            #expect(await composerEventually { speech.sockets[1].toldToTranscribe })
            speech.sockets[1].deliver(.final("second half exactly"))
            #expect(transcripts.last?.text == "first half exactly second half exactly")
            #expect(transcripts.last?.isFinal == true)
        }

        @Test func aCutWaitsForThePause() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.speak(at: 0.6)
            try await Task.sleep(for: .milliseconds(500))
            #expect(speech.sockets.count == 1)
            speech.speak(at: 0)
            #expect(await composerEventually { speech.sockets.count == 2 })
            #expect(voice.state == .listening)
            voice.cancel()
        }

        @Test func aGatewayFailureKeepsWhatWasRecognised() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.sockets[0].deliver(.partial("half a sentence"))
            speech.sockets[0].deliver(.failed("backend unavailable"))
            #expect(voice.state == .error)
            #expect(voice.error == "backend unavailable")
            #expect(transcripts.last?.text == "half a sentence" && transcripts.last?.isFinal == true)
        }

        @Test func aFailureWithoutWordsOfItsOwnSaysTranscriptionFailed() async {
            let voice = controller()
            #expect(await listening(voice))
            speech.sockets[0].deliver(.failed(nil))
            #expect(voice.error == S.voice.failed)
        }

        @Test func aSocketThatCannotConnectFailsTheDictation() async {
            speech.socketsRefuse = true
            let voice = controller()
            voice.start()
            #expect(await composerEventually { voice.state == .error })
            #expect(voice.error == S.voice.failed)
        }

        @Test func aRefusedMicrophoneSaysSo() async throws {
            let voice = controller()
            voice.start()
            let recorder = try #require(speech.recorders.first)
            recorder.handlers.onError(.denied)
            #expect(voice.state == .error)
            #expect(voice.error == S.voice.denied)
        }

        @Test func aGatewayThatNeverAnswersDoneKeepsTheWords() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.sockets[0].deliver(.partial("the words so far"))
            voice.done()
            #expect(await composerEventually { voice.state == .idle })
            #expect(transcripts.last?.text == "the words so far" && transcripts.last?.isFinal == true)
            #expect(voice.error == nil)
        }

        /// The composer taken away mid-dictation — the device offline, the
        /// terminal back in charge — ends the run and keeps the words.
        @Test func aComposerThatStopsTakingDictationEndsTheRun() async throws {
            let voice = controller()
            #expect(await listening(voice))
            speech.sockets[0].deliver(.partial("half a sentence"))
            voice.enabled = false
            #expect(voice.state == .idle)
            #expect(speech.sockets[0].toldToDrop)
            #expect(transcripts.last?.text == "half a sentence" && transcripts.last?.isFinal == false)
        }

        @Test func anIdleControllerIsLeftAlone() {
            let voice = controller()
            voice.enabled = false
            #expect(voice.state == .idle)
            #expect(speech.sockets.isEmpty)
            voice.start()
            #expect(voice.state == .idle)
        }

        @Test func theElapsedClockRunsWhileListeningAndStopsAtDone() async throws {
            let voice = controller()
            #expect(await listening(voice))
            #expect(await composerEventually { voice.elapsedMs > 20 })
            voice.done()
            let stopped = voice.elapsedMs
            try await Task.sleep(for: .milliseconds(60))
            #expect(voice.elapsedMs == stopped)
        }
    }
}

/// What a controller published, kept by reference so the test reads it after.
@MainActor
final class VoiceTranscriptLog {
    var entries: [(text: String, isFinal: Bool)] = []
}

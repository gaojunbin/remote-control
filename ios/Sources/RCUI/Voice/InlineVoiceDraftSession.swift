import Foundation
import Observation
import RCCore

@MainActor @Observable public final class InlineVoiceDraftSession {
    public let voice: VoiceInputController
    public let isPreview: Bool
    @ObservationIgnored private var target: VoiceDraftTarget?
    @ObservationIgnored private var originalDraft = ""
    @ObservationIgnored private var appliedDraft = ""

    public init(platform: any SpeechInputPlatform, isPreview: Bool = false) {
        self.isPreview = isPreview
        voice = VoiceInputController(platform: platform)
    }

    public func start(draft: String, target: VoiceDraftTarget) {
        guard !voice.phase.isBusy else { return }
        self.target = target
        originalDraft = draft
        appliedDraft = draft
        voice.start()
    }

    public func updateDraft(currentDraft: String, currentTarget: VoiceDraftTarget) -> String? {
        guard let target else { return nil }
        guard target.matches(currentTarget), currentDraft == appliedDraft else {
            reset()
            return nil
        }
        let next = target.inserting(voice.transcript, into: originalDraft, currentTarget: currentTarget) ?? originalDraft
        guard next != appliedDraft else { return nil }
        appliedDraft = next
        return next
    }

    public func finish() { voice.finish() }

    public func cancel(currentDraft: String, currentTarget: VoiceDraftTarget) -> String? {
        let restored = target?.matches(currentTarget) == true && currentDraft == appliedDraft ? originalDraft : nil
        reset()
        return restored
    }

    public func reset() {
        target = nil
        originalDraft = ""
        appliedDraft = ""
        voice.cancel()
    }
}

/// A scripted platform for previews and the UI test. It never touches the
/// microphone and is only reachable behind an explicit launch argument.
@MainActor public final class ScriptedSpeechInput: SpeechInputPlatform {
    private var onEvent: (@Sendable (SpeechInputEvent) -> Void)?
    private let transcript: String

    public init(transcript: String = "Re-run the auth suite on the CI runner too.") {
        self.transcript = transcript
    }

    public func requestPermission() async throws {}
    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        self.onEvent = onEvent
        onEvent(.transcript(transcript, isFinal: false))
        onEvent(.level(0.65))
    }
    public func finish() { onEvent?(.transcript(transcript, isFinal: true)) }
    public func cancel() { onEvent = nil }
}

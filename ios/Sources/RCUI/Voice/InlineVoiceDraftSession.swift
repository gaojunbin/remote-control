import Foundation
import Observation
import RCCore

@MainActor @Observable public final class InlineVoiceDraftSession {
    public let voice: VoiceInputController
    public let isPreview: Bool
    /// Amendment A29: the span a finished dictation left in the field — the
    /// draft it started from and the words it added — handed over exactly once.
    /// The composer sets this, because it is what knows whether polishing is on
    /// and which model does it.
    public var onDictationFinished: (@MainActor (DictationSpan) -> Void)?
    @ObservationIgnored private var target: VoiceDraftTarget?
    @ObservationIgnored private var originalDraft = ""
    @ObservationIgnored private var appliedDraft = ""
    @ObservationIgnored private var hasReported = false

    public init(platform: any SpeechInputPlatform, isPreview: Bool = false) {
        self.isPreview = isPreview
        voice = VoiceInputController(platform: platform)
    }

    public func start(draft: String, target: VoiceDraftTarget) {
        guard !voice.phase.isBusy else { return }
        self.target = target
        originalDraft = draft
        appliedDraft = draft
        hasReported = false
        voice.start()
    }

    /// Amendment A29: called once the draft is up to date. A dictation that has
    /// stopped — the final transcript, a failure, the scene leaving — hands its
    /// span over, and only the first of those moments does.
    public func reportFinishedDictation() {
        guard !hasReported, target != nil, !voice.phase.isBusy else { return }
        let dictated = voice.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !dictated.isEmpty else { return }
        hasReported = true
        onDictationFinished?(DictationSpan(base: originalDraft, dictated: dictated))
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

    public func reset() {
        target = nil
        originalDraft = ""
        appliedDraft = ""
        hasReported = false
        voice.cancel()
    }
}

/// A scripted platform for previews and the UI test. It never touches the
/// microphone and is only reachable behind an explicit launch argument.
@MainActor public final class ScriptedSpeechInput: SpeechInputPlatform {
    private var onEvent: (@Sendable (SpeechInputEvent) -> Void)?
    private let transcript: String

    /// Real speech, fillers and all: the default reads as something said rather
    /// than typed, which is what dictation polish (A29) is there to clean up.
    public init(transcript: String = "um re-run the the auth suite on the CI runner too.") {
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

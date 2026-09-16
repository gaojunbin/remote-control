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
    private var delivery: Task<Void, Never>?
    private let transcript: String
    private let level: Double
    private let partials: Int

    /// The level a scripted dictation holds. Ordinary speech sits near the
    /// default; `--voice-level=` pins it so the listening glow can be looked at
    /// at rest and at full voice without speaking into a simulator.
    public static let defaultLevel = 0.65

    /// Dictation long enough to outrun the field: about a minute of speech,
    /// which wraps to well past the eight lines the composer grows to, so a UI
    /// test can watch the field follow the words (`docs/DESIGN.md` §
    /// "The composer" → **While dictation runs, the field follows the words**).
    public static let longTranscript = """
        okay so here is the whole thing i want you to pick up after lunch, first re-run the auth \
        suite on the CI runner and keep the flaky login test quarantined for now, then work out \
        why the token refresh path retries twice on a cold start, i think the client is racing \
        the keychain read there, after that go through the gateway logs from this morning around \
        nine fifteen and pull out every request that took longer than two seconds, group them by \
        route, and if the slow ones are all on the upload path then check whether the disk on the \
        box is full again, and if any of it looks like the flake we chased last week then say so \
        in the notes rather than fixing it, i would rather the two of us looked at it together \
        first, and write the whole thing up in the round notes so i can read it on the train \
        tomorrow morning
        """

    /// Real speech, fillers and all: the default reads as something said rather
    /// than typed, which is what dictation polish (A29) is there to clean up.
    /// `partials` is how many times it arrives before it is final — one, as
    /// a short sentence does, or several, the way a long one really lands.
    public init(transcript: String = "um re-run the the auth suite on the CI runner too.",
                level: Double = ScriptedSpeechInput.defaultLevel,
                partials: Int = 1) {
        self.transcript = transcript
        self.level = level
        self.partials = max(1, partials)
    }

    public func requestPermission() async throws {}

    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        self.onEvent = onEvent
        let steps = Self.steps(of: transcript, count: partials)
        onEvent(.transcript(steps[0], isFinal: false))
        onEvent(.level(level))
        guard steps.count > 1 else { return }
        delivery = Task { [weak self] in
            for step in steps.dropFirst() {
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled, let send = self?.onEvent else { return }
                send(.transcript(step, isFinal: false))
            }
        }
    }

    public func finish() {
        delivery?.cancel()
        delivery = nil
        onEvent?(.transcript(transcript, isFinal: true))
    }

    public func cancel() {
        delivery?.cancel()
        delivery = nil
        onEvent = nil
    }

    /// The transcript as it arrives: growing prefixes cut at word boundaries,
    /// the last of which is the whole of it.
    private static func steps(of transcript: String, count: Int) -> [String] {
        let words = transcript.split(separator: " ")
        guard count > 1, words.count >= count else { return [transcript] }
        return (1...count).map { step in
            words.prefix(words.count * step / count).joined(separator: " ")
        }
    }
}

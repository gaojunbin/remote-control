import Foundation

/// What one utterance's socket reports (`SttEvent` in `sttSocket.ts`). `failed`
/// carries the gateway's own sentence when it sent one.
enum SpeechEvent: Equatable, Sendable {
    case partial(String)
    case final(String)
    case failed(String?)
    case closed
}

/// One utterance over `WS /ws/stt`: 16 kHz PCM16LE mono frames out, partial
/// transcripts back, one final transcript after `stop()`.
@MainActor
protocol SpeechStream: AnyObject {
    /// Connect; throws when the gateway takes no audio.
    func start() async throws
    func append(_ frame: Data)
    /// Transcribe everything sent so far and wait for the final transcript.
    func stop()
    /// Drop the utterance: no final transcript is wanted, and nothing more is reported.
    func cancel()
}

/// Why the microphone could not be used.
enum RecorderError: Sendable {
    case denied
    case unsupported
    case failed
}

/// What a recorder hands on, on the main actor.
struct RecorderHandlers {
    let onFrame: @MainActor (Data) -> Void
    let onLevel: @MainActor (Double) -> Void
    let onError: @MainActor (RecorderError) -> Void
}

/// The microphone, as `useVoice` uses it (`VoiceRecorder` in `useVoice.ts`).
@MainActor
protocol VoiceRecorder: AnyObject {
    /// Start capturing; false when it could not, after telling `onError` why.
    func start() async -> Bool
    /// Stop, handing on the tail of the audio first.
    func stop() async
}

/// Where a dictation's microphone and sockets come from: the Mac's own
/// microphone and the gateway, or — for the offline demo and for a preview —
/// a script that speaks without either.
struct SpeechServices {
    let recorder: @MainActor (RecorderHandlers) -> any VoiceRecorder
    let socket: @MainActor (@escaping @MainActor (SpeechEvent) -> Void) -> any SpeechStream
}

/// `useVoice`'s timings. A test runs the same machine on a shorter clock.
struct VoiceTiming: Sendable {
    /// Well inside the gateway's 120 s and 4 MiB budget for one utterance.
    var segment: Duration = .seconds(30)
    /// How long a cut may wait for a pause in the speech before it is taken anyway.
    var segmentLimit: Duration = .seconds(45)
    /// How long the gateway gets to answer Done before the draft is kept as it is.
    var finalTimeout: Duration = .seconds(30)
    /// How often the elapsed clock ticks and a waiting cut looks again.
    var poll: Duration = .milliseconds(200)
    /// Input level under which the speaker counts as between words.
    var silenceLevel = 0.12

    static let standard = VoiceTiming()
}

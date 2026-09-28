import Foundation

/// Where dictation has got to (`VoiceState` in `web/src/features/voice/useVoice.ts`).
enum VoiceState: CaseIterable, Sendable {
    case idle, starting, listening, finishing, error

    /// The microphone or its last transcript still has the field.
    var isBusy: Bool { self == .starting || self == .listening || self == .finishing }
}

/// `web/src/features/voice/primarySlot.ts`: what the composer's one primary
/// slot holds, from the two things that can claim it — where dictation has got
/// to, and whether the polish model is still writing the words back.
///
/// `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner
/// becomes Send**: Done while the microphone is live, a spinner for as long as
/// the words are still on their way, and Send the moment the field holds what
/// will be sent.
enum PrimarySlot: Sendable {
    case done
    case working
    case send

    static func of(voice: VoiceState, polish: PolishProgress) -> PrimarySlot {
        switch voice {
        // A live dictation owns the slot whatever else is out: the words are
        // still being spoken, so the only thing to offer is the way out.
        case .starting, .listening: .done
        // Done was clicked and the backend's final transcript is not here yet.
        case .finishing: .working
        // The field holds the dictated words already; only an answer still on
        // its way stands between them and Send.
        case .idle, .error: polish == .polishing ? .working : .send
        }
    }
}

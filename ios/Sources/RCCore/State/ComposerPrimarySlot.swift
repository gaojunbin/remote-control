import Foundation

/// What the one primary slot of the composer's control row holds, derived from
/// the two things that can claim it: where dictation has got to, and whether
/// the model is still writing the words back.
///
/// `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the
/// spinner becomes Send**. The rule is one sentence long: Done while the
/// microphone is live, a spinner for as long as the words are still on their
/// way, and Send the moment the field holds what will be sent. It lives here,
/// away from any view, because the interesting part is the pairs — a phase of
/// dictation against a phase of polish — and every one of them can be checked
/// without a screen.
public enum ComposerPrimarySlot: String, Sendable, Equatable, CaseIterable {
    /// The one way out of a running dictation.
    case done
    /// Nothing in the slot can be tapped: the backend's final transcript, or
    /// the model's answer, is still on its way.
    case working
    /// The field holds what will be sent.
    case send

    public static func of(voice: VoiceInputPhase, polish: PolishPhase) -> ComposerPrimarySlot {
        switch voice {
        // A live dictation owns the row whatever else is out. It cannot in
        // fact be polishing — starting a dictation drops the last answer — but
        // the rule reads the same either way: the words are still being spoken,
        // so the only thing to offer is the way out of speaking them.
        case .listening, .requestingPermission:
            return .done
        // Done was tapped and the transcript is not final yet.
        case .finishing:
            return .working
        // Dictation is over: the field holds the dictated words already, and
        // only a polish still out stands between them and Send.
        case .idle, .review, .failed:
            return polish == .polishing ? .working : .send
        }
    }
}

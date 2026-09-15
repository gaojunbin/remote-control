import Testing
@testable import RCCore

/// `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the
/// spinner becomes Send**: the one slot against the trailing edge, for every
/// pair of phases that can claim it.
@Suite("The composer's primary slot")
struct ComposerPrimarySlotTests {
    private static let span = DictationSpan(base: "", dictated: "um fix the the dot")
    private static let answered = PolishPhase.polished(span, "Fix the dot.")

    /// Six phases of dictation against four of polish. The table is the ruling
    /// written out: Done only while the microphone has the row, the spinner for
    /// as long as the words are on their way, and Send the moment the field
    /// holds what will be sent.
    @Test("Every pair of phases, and the one thing the slot holds for it")
    func everyPair() {
        let answered = Self.answered
        let table: [(VoiceInputPhase, PolishPhase, ComposerPrimarySlot)] = [
            (.requestingPermission, .idle, .done), (.requestingPermission, .polishing, .done),
            (.requestingPermission, answered, .done), (.requestingPermission, .failed, .done),

            (.listening, .idle, .done), (.listening, .polishing, .done),
            (.listening, answered, .done), (.listening, .failed, .done),

            (.finishing, .idle, .working), (.finishing, .polishing, .working),
            (.finishing, answered, .working), (.finishing, .failed, .working),

            (.review, .idle, .send), (.review, .polishing, .working),
            (.review, answered, .send), (.review, .failed, .send),

            (.idle, .idle, .send), (.idle, .polishing, .working),
            (.idle, answered, .send), (.idle, .failed, .send),

            (.failed, .idle, .send), (.failed, .polishing, .working),
            (.failed, answered, .send), (.failed, .failed, .send)
        ]
        #expect(table.count == 24, "six phases of dictation against four of polish")
        for (voice, polish, expected) in table {
            #expect(ComposerPrimarySlot.of(voice: voice, polish: polish) == expected,
                    "\(voice.rawValue) with \(polish)")
        }
    }

    /// The two sentences the ruling turns on, said again as rules rather than as
    /// a table: a dead Done and a Send that would send half a thought are the
    /// two things the slot must never be.
    @Test("Done goes with the microphone, and Send is never offered while polishing")
    func theTwoThingsTheRowMustNotShow() {
        for polish in [PolishPhase.idle, .polishing, Self.answered, .failed] {
            #expect(ComposerPrimarySlot.of(voice: .finishing, polish: polish) != .done,
                    "Done is not drawn once the microphone is off")
        }
        for voice in [VoiceInputPhase.idle, .review, .failed] {
            #expect(ComposerPrimarySlot.of(voice: voice, polish: .polishing) != .send,
                    "Send is not drawn while the model is still writing")
        }
    }
}

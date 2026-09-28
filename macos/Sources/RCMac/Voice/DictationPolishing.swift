import Foundation
import RCCore

/// A29 — what a dictation left in the field: the draft the mic button was
/// pressed on, and the words the recogniser produced. The answer replaces the
/// second and never touches the first: text the person typed is theirs.
struct PolishSpan: Equatable, Sendable {
    let base: String
    let dictated: String

    /// The draft as the recogniser left it.
    var dictatedDraft: String { DictationDraft.merge(base, dictated) }

    /// The same draft with the dictated words replaced by the model's.
    func polishedDraft(_ polished: String) -> String { DictationDraft.merge(base, polished) }
}

/// `web/src/features/voice/polish.ts`, the pure half of dictation polish: what
/// the model is told, and how its answer meets the draft.
///
/// The iPhone's `DictationPolish` joins a dictation to its draft on a new line;
/// the web joins it after a space (`draft.ts`), and this app writes the field
/// the web's way, so the answer has to be matched the same way. The rules the
/// contract fixes — which words go, twenty messages of conversation, four
/// thousand characters each — are RCCore's (`DictationPolish.canPolish`,
/// `DictationPolish.context`), which say what the web's do.
enum DictationPolishing {
    /// The body of `POST /api/polish` for one dictation. A44: the gateway
    /// transcribed the words and detected their language, so the model's hint
    /// is `auto`.
    static func request(span: PolishSpan, model: String, strength: PolishStrength,
                        context: [PolishContextItem]) -> PolishRequest {
        PolishRequest(text: span.dictated, model: model, strength: strength, language: "auto", context: context)
    }

    /// The draft the answer produces, or nil when the field has moved on — the
    /// person typed, sent, or dictated again, and their words win. An answer
    /// that is empty is no answer; one that changes nothing says nothing.
    static func applyPolished(current: String, span: PolishSpan, polished: String) -> String? {
        let text = polished.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, current == span.dictatedDraft else { return nil }
        let next = span.polishedDraft(text)
        return next == current ? nil : next
    }

    /// Undo: the words as they were dictated, or nil when the field no longer
    /// holds what the model wrote and there is nothing to put back.
    static func undoPolished(current: String, span: PolishSpan, polished: String) -> String? {
        current == span.polishedDraft(polished) ? span.dictatedDraft : nil
    }
}

/// A29 — where a dictation is between the recogniser and the model: nowhere,
/// waiting for the answer, holding one that can still be undone, or told that
/// the request failed and the words were left alone.
enum DictationPolishState: Equatable, Sendable {
    case idle
    case polishing
    case polished(span: PolishSpan, text: String)
    case failed

    var progress: PolishProgress {
        switch self {
        case .idle: .idle
        case .polishing: .polishing
        case .polished: .polished
        case .failed: .failed
        }
    }
}

/// The four phases of `DictationPolishState`, without what they carry: the
/// half of the primary slot's rule that polish decides.
enum PolishProgress: CaseIterable, Sendable {
    case idle, polishing, polished, failed
}

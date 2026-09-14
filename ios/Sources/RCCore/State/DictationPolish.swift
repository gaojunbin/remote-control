import Foundation

/// Amendment A29 — what a dictation left in the composer: the draft the
/// microphone was tapped on, and the words the recogniser produced.
///
/// Polishing replaces the second and never touches the first, which is why both
/// halves are kept rather than the finished draft: text the person typed is
/// theirs, and no model answer is allowed to land on it.
public struct DictationSpan: Equatable, Sendable, Hashable {
    /// The draft as it was before dictation started. Never touched.
    public let base: String
    /// The words the recogniser produced, which are in the field now.
    public let dictated: String

    public init(base: String, dictated: String) {
        self.base = base
        self.dictated = dictated
    }

    /// How a dictation joins the draft it started from. One rule in one place,
    /// so a span can rebuild both drafts exactly as the composer wrote them.
    public static func merge(_ base: String, _ text: String) -> String {
        guard !base.isEmpty else { return text }
        return base + (base.last?.isWhitespace == true ? "" : "\n") + text
    }

    /// The draft as the recogniser left it.
    public var dictatedDraft: String { Self.merge(base, dictated) }

    /// The same draft with the dictated words replaced by the model's answer.
    public func polishedDraft(_ polished: String) -> String { Self.merge(base, polished) }
}

/// The pure half of dictation polish: what the model is told, and how its
/// answer meets the draft.
///
/// Everything here is a value in and a value out, so the rules the contract
/// fixes — twenty messages, oldest first, four thousand characters each, and a
/// replacement that touches the dictated words alone — are testable without a
/// field, a socket or a gateway.
public enum DictationPolish {
    /// `PolishRequest.context` takes at most this many messages (schema `maxItems`).
    public static let contextLimit = 20
    /// Each context message is trimmed by the app to this length (schema `maxLength`).
    public static let contextTextLimit = 4000
    /// `PolishRequest.text` (schema `maxLength`). A longer dictation is not polished.
    public static let textLimit = 8192

    /// Whether these words can be sent at all: an empty dictation has nothing
    /// to polish, and one past the contract's limit would only earn a
    /// `bad_request`.
    public static func canPolish(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && text.unicodeScalars.count <= textLimit
    }

    /// The conversation the model is given: the messages the app already shows,
    /// user and assistant alike, oldest first, the last twenty of them, each
    /// trimmed. Unconfirmed sends (A12) count — they are on screen, and the
    /// newest of them is usually what the dictation is answering.
    public static func context(_ timeline: Timeline) -> [PolishContextItem] {
        var items: [PolishContextItem] = []
        for entry in timeline.entries {
            switch entry.body {
            case .userMessage(let payload): items.append(PolishContextItem(role: .user, text: payload.text))
            case .assistantText: items.append(PolishContextItem(role: .assistant, text: entry.text))
            default: continue
            }
        }
        for message in timeline.optimistic {
            items.append(PolishContextItem(role: .user, text: message.text))
        }
        let trimmed = items
            .map { PolishContextItem(role: $0.role, text: trim($0.text)) }
            .filter { !$0.text.isEmpty }
        return Array(trimmed.suffix(contextLimit))
    }

    /// The body of `POST /api/polish` for one dictation.
    public static func request(span: DictationSpan, model: String, strength: PolishStrength,
                               language: String, context: [PolishContextItem]) -> PolishRequest {
        // "auto" is the app's own word for "let the recogniser decide"; the
        // contract's language field is a hint, so it is simply left out.
        let hint = (language.isEmpty || language == "auto") ? nil : language
        return PolishRequest(text: span.dictated, model: model, strength: strength,
                             language: hint, context: context)
    }

    /// The draft the answer produces, or nil when the field has moved on: the
    /// person typed, sent, or started dictating again, and their words win. An
    /// answer that is empty is no answer, and reads as a failure.
    public static func applyPolished(current: String, span: DictationSpan,
                                     polished: String) -> String? {
        let text = polished.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, current == span.dictatedDraft else { return nil }
        let next = span.polishedDraft(text)
        return next == current ? nil : next
    }

    /// Undo: the words as they were dictated, or nil when the field no longer
    /// holds what the model wrote, in which case there is nothing to put back.
    public static func undoPolished(current: String, span: DictationSpan,
                                    polished: String) -> String? {
        guard current == span.polishedDraft(polished) else { return nil }
        return span.dictatedDraft
    }

    private static func trim(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.unicodeScalars.count > contextTextLimit else { return trimmed }
        return String(String.UnicodeScalarView(trimmed.unicodeScalars.prefix(contextTextLimit)))
    }
}

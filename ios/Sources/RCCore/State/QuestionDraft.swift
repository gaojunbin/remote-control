import Foundation

/// What a pending question card is holding before it is submitted: the options
/// chosen on the card, and the free text typed into a question's own field.
///
/// Amendment A20 gives the composer a second way to answer the same question —
/// the draft in the message field is the free-text answer to the first question
/// still waiting for one — so the card and the composer have to be looking at
/// one copy of this rather than at two that can disagree.
public struct QuestionDraft: Sendable, Equatable {
    public let requestID: String
    private var selections: [String: Set<String>] = [:]
    private var typed: [String: String] = [:]

    public init(requestID: String) { self.requestID = requestID }

    public func isChosen(_ optionID: String, for questionID: String) -> Bool {
        selections[questionID]?.contains(optionID) ?? false
    }

    public func text(for questionID: String) -> String { typed[questionID] ?? "" }

    /// A single-choice question keeps at most one option, and choosing the
    /// option it already has clears it: nothing is answered by accident.
    public mutating func toggle(_ optionID: String, of question: QuestionItem) {
        var chosen = selections[question.id] ?? []
        if question.multi {
            if chosen.contains(optionID) { chosen.remove(optionID) } else { chosen.insert(optionID) }
        } else {
            chosen = chosen.contains(optionID) ? [] : [optionID]
        }
        selections[question.id] = chosen
    }

    public mutating func setText(_ value: String, for questionID: String) {
        typed[questionID] = value
    }

    public func hasSelection(for questionID: String) -> Bool {
        !(selections[questionID]?.isEmpty ?? true)
    }

    public func hasAnswer(for questionID: String) -> Bool {
        hasSelection(for: questionID) || !text(for: questionID).trimmed.isEmpty
    }

    /// True when the card can be submitted from the card itself.
    public func answersEveryQuestion(in questions: [QuestionItem]) -> Bool {
        questions.allSatisfy { hasAnswer(for: $0.id) }
    }

    /// Where the composer's draft goes: the first question nothing has been
    /// chosen or typed for. Nil when the card already answers them all, which is
    /// the case the card's own Submit is for.
    public func firstUnanswered(in questions: [QuestionItem]) -> QuestionItem? {
        questions.first { !hasAnswer(for: $0.id) }
    }

    /// What the card would send: chosen ids in the order the question offered
    /// them, or the text typed for a question nothing was chosen for.
    public func answers(for questions: [QuestionItem]) -> [String: QuestionAnswer] {
        var answers: [String: QuestionAnswer] = [:]
        for question in questions {
            if hasSelection(for: question.id) {
                answers[question.id] = .options(question.options.map(\.id)
                    .filter { isChosen($0, for: question.id) })
            } else {
                let value = text(for: question.id).trimmed
                if !value.isEmpty { answers[question.id] = .text(value) }
            }
        }
        return answers
    }

    /// What the composer sends: everything the card holds, plus this draft as
    /// the free-text answer to the first question still waiting for one.
    ///
    /// Nil means the draft has nowhere to go — every question is answered
    /// already, or the one waiting takes options and no words — and the message
    /// field keeps what was typed rather than losing it to a request nobody can
    /// accept.
    ///
    /// A secret question is nil too, however it is configured. The composer's
    /// draft is written to disk as a draft and restored on the next launch, and
    /// a value the card promises is neither stored nor logged cannot be typed
    /// there. Its own masked field on the card is the only way in.
    public func answers(for questions: [QuestionItem], composing draft: String) -> [String: QuestionAnswer]? {
        let text = draft.trimmed
        guard !text.isEmpty, let unanswered = firstUnanswered(in: questions),
              unanswered.allowText, !unanswered.secret else { return nil }
        var answers = answers(for: questions)
        answers[unanswered.id] = .text(text)
        return answers
    }
}

import Foundation
import RCCore

/// `web/src/features/chat/answering.ts` — amendment A20: how a pending question
/// turns into a `session.answer`.
///
/// A question is answered where you are. The card takes options and its own
/// free-text fields; the composer takes a sentence. Both read one draft — the
/// chat store's `QuestionDraft` — and these rules say how it is submitted, pure,
/// so the card, the composer and the tests read one description of it.
public enum Answering {
    /// What one question already holds: the options picked, else the text typed.
    static func answer(for question: QuestionItem, in draft: QuestionDraft) -> QuestionAnswer? {
        if draft.hasSelection(for: question.id) {
            return .options(question.options.map(\.id).filter { draft.isChosen($0, for: question.id) })
        }
        let typed = draft.text(for: question.id).trimmingCharacters(in: .whitespacesAndNewlines)
        return typed.isEmpty ? nil : .text(typed)
    }

    /// The answers a card submits on its own, with nothing left unanswered.
    public static func cardAnswers(_ questions: [QuestionItem], draft: QuestionDraft) -> [String: QuestionAnswer] {
        var answers: [String: QuestionAnswer] = [:]
        for question in questions {
            if let answer = answer(for: question, in: draft) { answers[question.id] = answer }
        }
        return answers
    }

    /// True once every question on the card carries an answer of its own.
    public static func cardComplete(_ questions: [QuestionItem], draft: QuestionDraft) -> Bool {
        questions.allSatisfy { answer(for: $0, in: draft) != nil }
    }

    /// The submission for a composer draft, or nil when the draft has nowhere
    /// to go: every question already carries a selection, or the first one
    /// without a selection refuses free text. The draft is then left where it is.
    ///
    /// The draft answers the first question with no option selected. Text typed
    /// on the card does not take that slot, so the composer's sentence replaces
    /// it rather than being ignored, and the other questions keep what they hold.
    public static func composeAnswer(_ questions: [QuestionItem], draft: QuestionDraft,
                                     text: String) -> [String: QuestionAnswer]? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty,
              let target = questions.first(where: { !draft.hasSelection(for: $0.id) }),
              target.allowText else { return nil }
        var answers = cardAnswers(questions, draft: draft)
        answers[target.id] = .text(value)
        return answers
    }
}

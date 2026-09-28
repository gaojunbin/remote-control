import Foundation
import RCCore

/// The words and orders `ApprovalCard.tsx` and `QuestionCard.tsx` work out.
public enum CardRules {
    /// The agent's own options, accept first and reject last, the rest in the
    /// order given between them. Nothing assumes any id exists.
    public static func ordered(_ options: [ApprovalOption]) -> [ApprovalOption] {
        func rank(_ style: OptionStyle) -> Int {
            switch style {
            case .primary: 0
            case .danger: 2
            default: 1
            }
        }
        return options.enumerated()
            .sorted { (rank($0.element.style), $0.offset) < (rank($1.element.style), $1.offset) }
            .map(\.element)
    }

    /// What a resolved or expired approval says instead of its buttons. A11
    /// §5.7: a request answered in the terminal resolves with the reserved
    /// `elsewhere` id, which matches none of the options on purpose.
    public static func approvalResult(_ approval: ApprovalPayload) -> String {
        if approval.status == .expired { return S.chat.approvalExpired }
        guard let decision = approval.decision else { return S.chat.questionResolved }
        if decision.optionID == ApprovalPayload.elsewhereOptionID { return S.chat.approvalElsewhere }
        let chosen = approval.options.first { $0.id == decision.optionID }?.label ?? decision.optionID
        return S.chat.approvalResolved(decision.by.rawValue, chosen)
    }

    /// A20: a resolved question says who answered it, the way an approval
    /// answered in the terminal does. A device that does not report `by` says
    /// only that the question was answered.
    public static func questionResult(_ question: QuestionPayload) -> String {
        if question.status == .expired { return S.chat.questionExpired }
        if question.by == .terminal { return S.chat.questionAnsweredInTerminal }
        return S.chat.questionResolved
    }

    /// Whether a resolved question's answer picked this option.
    public static func answerIncludes(_ answer: QuestionAnswer?, _ optionID: String) -> Bool {
        if case .options(let ids) = answer { return ids.contains(optionID) }
        return false
    }

    /// What a resolved card shows in its free-text field: the answer that was
    /// given, typed here or in the composer. A secret answer is never echoed
    /// back — the card said it is not stored, and neither is it redrawn.
    public static func freeText(_ answer: QuestionAnswer?, secret: Bool) -> String {
        guard !secret, case .text(let value) = answer else { return "" }
        return value
    }
}

/// `DiffView.tsx`: how one line of a unified patch is coloured.
public enum DiffLineKind: Sendable, Equatable {
    case meta, hunk, add, del, context

    public init(_ line: Substring) {
        if line.hasPrefix("+++") || line.hasPrefix("---") || line.hasPrefix("diff ") { self = .meta }
        else if line.hasPrefix("@@") { self = .hunk }
        else if line.hasPrefix("+") { self = .add }
        else if line.hasPrefix("-") { self = .del }
        else { self = .context }
    }
}

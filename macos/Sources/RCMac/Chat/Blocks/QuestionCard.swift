import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/QuestionCard.tsx`: one card for one or
/// several questions — options, free text, secret fields — answerable wherever
/// the composer is (A20). What the card holds lives in the conversation's
/// `QuestionDraft`, so the composer's Answer submits exactly what is on screen.
struct QuestionCard: View {
    let question: QuestionPayload
    let chat: ChatStore
    let onAnswer: (_ requestID: String, _ answers: [String: QuestionAnswer]) async -> Bool
    @State private var busy = false

    var body: some View {
        let pending = question.status == .pending
        let draft = chat.draft(for: question)
        VStack(alignment: .leading, spacing: Space.sp3) {
            ForEach(question.questions) { item in
                QuestionItemView(item: item, question: question, chat: chat, draft: draft, pending: pending)
            }
            if pending {
                CardActions {
                    Button(S.chat.submitAnswer) { submit(draft) }
                        .buttonStyle(.btn(.primary, size: .small))
                        .disabled(!Answering.cardComplete(question.questions, draft: draft) || busy)
                }
            } else {
                Text(CardRules.questionResult(question))
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkSecondary)
            }
        }
        .cardFrame(resolved: !pending)
        // A question that stops being pending takes its working state with
        // it, however it ended — answered here, in the terminal, or expired.
        // A secret field said the value is not stored, so nothing may be left
        // holding one.
        .onChange(of: pending, initial: true) { _, isPending in
            guard !isPending else { return }
            for item in question.questions where !chat.draft(for: question).text(for: item.id).isEmpty {
                chat.write("", for: item.id, in: question)
            }
        }
    }

    /// A failed answer keeps everything that was filled in: the page's banner
    /// says what went wrong and the card is ready to be submitted again.
    private func submit(_ draft: QuestionDraft) {
        busy = true
        Task {
            _ = await onAnswer(question.requestID, Answering.cardAnswers(question.questions, draft: draft))
            busy = false
        }
    }
}

/// `.question-item`: the prompt, its options and its free-text field.
private struct QuestionItemView: View {
    let item: QuestionItem
    let question: QuestionPayload
    let chat: ChatStore
    let draft: QuestionDraft
    let pending: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp2) {
            Text(verbatim: item.prompt)
                .css(FontSize.fs14)
                .textSelection(.enabled)
            if !item.options.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(item.options) { option in
                        QuestionOptionButton(option: option, selected: isSelected(option), pending: pending) {
                            choose(option)
                        }
                    }
                }
            }
            if item.allowText {
                QuestionField(item: item, question: question, chat: chat, draft: draft, pending: pending)
            }
        }
    }

    private func isSelected(_ option: QuestionOption) -> Bool {
        pending ? draft.isChosen(option.id, for: item.id)
                : CardRules.answerIncludes(question.answers?[item.id], option.id)
    }

    /// The web picks an option of a single-choice question and keeps it
    /// picked on a second press; only a `multi` question takes it back.
    private func choose(_ option: QuestionOption) {
        if !item.multi && draft.isChosen(option.id, for: item.id) { return }
        chat.choose(option.id, of: item, in: question)
    }
}

/// `.question-option`: a bordered choice, its label and an optional hint.
private struct QuestionOptionButton: View {
    let option: QuestionOption
    let selected: Bool
    let pending: Bool
    let action: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: option.label).css(FontSize.fs13)
                if let description = option.description {
                    Text(verbatim: description)
                        .css(FontSize.fs12)
                        .foregroundStyle(Palette.inkSecondary)
                }
            }
            .multilineTextAlignment(.leading)
            .padding(.vertical, Space.sp2)
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(1)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular)
                .fill(selected ? Palette.surfaceMuted : Palette.surface))
            .chatBorder(ChatBorder(width: 1, radius: Radius.sm), color: edge)
            .contentShape(Rectangle())
        }
        .buttonStyle(.chatBare)
        .disabled(!pending)
        .onHover { hovered = $0 }
        .pointerStyle(pending ? .link : nil)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var edge: Color {
        if selected { return Palette.ink }
        return hovered && pending ? Palette.lineStrong : Palette.line
    }
}

/// The question's own free-text field: a `.field`, masked for a secret, and
/// showing what was answered once the question is resolved — never a secret.
private struct QuestionField: View {
    let item: QuestionItem
    let question: QuestionPayload
    let chat: ChatStore
    let draft: QuestionDraft
    let pending: Bool

    var body: some View {
        let binding = Binding<String>(
            get: { pending ? draft.text(for: item.id)
                           : CardRules.freeText(question.answers?[item.id], secret: item.secret) },
            set: { chat.write($0, for: item.id, in: question) })
        WebField(text: binding,
                 placeholder: item.secret ? S.chat.secretPlaceholder : S.chat.freeTextPlaceholder,
                 secure: item.secret)
            .disabled(!pending)
            .accessibilityLabel(item.prompt)
    }
}

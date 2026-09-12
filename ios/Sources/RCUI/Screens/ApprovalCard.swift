import SwiftUI
import RCCore

/// A tool the agent wants to run, and the exact choices the device offered.
///
/// The app never invents an option id and never assumes what "allow" is called:
/// the primary and danger styles decide placement, the ids go back verbatim.
/// The destructive choice is deliberately kept away from the primary one, so a
/// one-handed tap cannot land on it by accident.
struct ApprovalCard: View {
    let entry: TimelineEntry
    let payload: ApprovalPayload
    let chat: ChatStore
    @State private var isSending = false

    private var isActive: Bool { payload.status.isActionable && !chat.isReadOnly }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            HStack(spacing: Theme.Space.tight) {
                Image(systemName: "hand.raised").font(.footnote)
                Text(payload.status.isActionable ? L10n.string("Approval needed") : statusText)
                    .font(.footnote.weight(.medium))
                    .accessibilityIdentifier("chat.approval")
                Spacer()
                Text(payload.tool).font(.caption).foregroundStyle(Theme.inkSecondary)
            }
            .foregroundStyle(payload.status.isActionable ? Theme.attention : Theme.inkSecondary)

            Text(payload.title)
                .font(Theme.monoBody)
                .foregroundStyle(Theme.ink)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)

            if let cwd = payload.input?["cwd"]?.stringValue {
                CodeText(cwd)
            }
            if let diff = payload.diff {
                HStack(spacing: Theme.Space.tight) {
                    CodeText(diff.path, color: Theme.ink)
                    Text("+\(diff.additions)").font(.caption).foregroundStyle(Theme.added)
                    Text("−\(diff.deletions)").font(.caption).foregroundStyle(Theme.removed)
                }
                if let patch = diff.patch { PatchView(patch: patch, foldedLineLimit: 12) }
            }

            if payload.status.isActionable {
                options
            } else if let decision = payload.decision {
                resolution(decision)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("approval.resolution")
            }
        }
        .card()
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous)
            .strokeBorder(payload.status.isActionable ? Theme.attention.opacity(0.5) : Theme.border,
                          lineWidth: payload.status.isActionable ? 1 : 0.5))
    }

    /// Primary first, then the middle options, then the danger option at the
    /// far end of the card.
    private var options: some View {
        VStack(spacing: Theme.Space.small) {
            if let primary = payload.primaryOption {
                Button(primary.label) { send(primary.id) }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(isSending || !isActive)
                    .accessibilityIdentifier("approval.primary")
            }
            ForEach(payload.otherOptions) { option in
                Button(option.label) { send(option.id) }
                    .buttonStyle(ChipButtonStyle())
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: Theme.Touch.minimum)
                    .disabled(isSending || !isActive)
            }
            if let danger = payload.dangerOption {
                Button(danger.label) { send(danger.id) }
                    .font(.body.weight(.medium))
                    .foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, minHeight: Theme.Touch.primary)
                    .background(Theme.danger.opacity(0.08), in: Capsule())
                    .buttonStyle(.plain)
                    .disabled(isSending || !isActive)
                    .padding(.top, Theme.Space.tight)
                    .accessibilityIdentifier("approval.danger")
            }
        }
    }

    private var statusText: String {
        L10n.string(payload.status == .expired ? "This request expired" : "Answered")
    }

    /// Amendment A11: a request the device did not answer resolves with an
    /// option id it was never offered, so the line is the source alone.
    private func resolution(_ decision: ApprovalDecision) -> Text {
        guard let chosen = payload.resolvedOptionLabel else { return source(decision.by) }
        return Text(verbatim: "\(chosen) · ") + source(decision.by)
    }

    private func source(_ by: EventSource) -> Text {
        switch by {
        case .terminal: Text(L10n.string("answered in the terminal"))
        case .policy: Text(L10n.string("answered by a rule"))
        default: Text(L10n.string("answered here"))
        }
    }

    private func send(_ optionID: String) {
        guard !isSending else { return }
        isSending = true
        Task {
            await chat.approve(requestID: payload.requestID, optionID: optionID)
            isSending = false
        }
    }
}

/// One or more questions from the agent. Nothing is sent until Submit, a
/// multi-select needs an explicit choice, and a secret answer uses a secure
/// field and never enters the ordinary draft.
///
/// Amendment A20: on an attached session the terminal shows its own dialog at
/// the same moment and whichever is answered first wins, so the card is live
/// here too and a resolved one says where the answer came from. What is chosen
/// on the card lives in the store rather than in this view, because the
/// composer submits the same answers with the draft added to them.
struct QuestionCard: View {
    let payload: QuestionPayload
    let chat: ChatStore
    @State private var isSending = false

    private var isActive: Bool { payload.status.isActionable && chat.allowsAnswers }
    private var draft: QuestionDraft { chat.draft(for: payload) }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.medium) {
            HStack(spacing: Theme.Space.tight) {
                Image(systemName: "questionmark.circle").font(.footnote)
                Text(L10n.string(payload.status.isActionable ? "The agent has a question" : "Answered"))
                    .font(.footnote.weight(.medium))
                    .accessibilityIdentifier("chat.question")
                Spacer()
            }
            .foregroundStyle(payload.status.isActionable ? Theme.attention : Theme.inkSecondary)

            ForEach(payload.questions) { question in
                VStack(alignment: .leading, spacing: Theme.Space.small) {
                    Text(question.prompt)
                        .font(.subheadline)
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(question.options) { option in
                        Button {
                            toggle(question: question, option: option.id)
                        } label: {
                            HStack(spacing: Theme.Space.small) {
                                Image(systemName: symbol(question: question, option: option.id))
                                    .foregroundStyle(isChosen(question.id, option.id) ? Theme.ink : Theme.resting)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(option.label).foregroundStyle(Theme.ink)
                                    if let description = option.description {
                                        Text(description).font(.caption).foregroundStyle(Theme.inkSecondary)
                                    }
                                }
                                Spacer()
                            }
                            .frame(minHeight: Theme.Touch.minimum)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!isActive)
                    }
                    if question.allowText {
                        if question.secret {
                            SecureField("Your answer", text: binding(question.id))
                                .privacySensitive()
                                .frame(minHeight: Theme.Touch.minimum)
                                .disabled(!isActive)
                        } else {
                            GrowingTextField("Your answer", text: binding(question.id))
                                .frame(minHeight: Theme.Touch.minimum)
                                .disabled(!isActive)
                        }
                    }
                }
            }

            if payload.status.isActionable {
                Button("Submit") { submit() }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(isSending || !isActive || !draft.answersEveryQuestion(in: payload.questions))
                    .accessibilityIdentifier("question.submit")
            } else if payload.status == .expired {
                Text("This question expired before it was answered.")
                    .font(.footnote).foregroundStyle(Theme.inkSecondary)
            } else if let by = payload.by {
                source(by)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("question.resolution")
            }
        }
        .card()
    }

    /// Amendment A20: the same wording the approval card uses, because it is the
    /// same fact — somebody else got there first.
    private func source(_ by: EventSource) -> Text {
        switch by {
        case .terminal: Text(L10n.string("answered in the terminal"))
        default: Text(L10n.string("answered here"))
        }
    }

    private func isChosen(_ questionID: String, _ optionID: String) -> Bool {
        draft.isChosen(optionID, for: questionID)
    }

    private func symbol(question: QuestionItem, option: String) -> String {
        if question.multi { return isChosen(question.id, option) ? "checkmark.square.fill" : "square" }
        return isChosen(question.id, option) ? "largecircle.fill.circle" : "circle"
    }

    private func toggle(question: QuestionItem, option: String) {
        chat.choose(option, of: question, in: payload)
    }

    private func binding(_ questionID: String) -> Binding<String> {
        Binding(get: { draft.text(for: questionID) },
                set: { chat.write($0, for: questionID, in: payload) })
    }

    private func submit() {
        guard !isSending else { return }
        isSending = true
        Task {
            await chat.submitAnswer(for: payload)
            isSending = false
        }
    }
}

#Preview("Approval card") {
    DemoPreview {
        NavigationStack { ChatView(sessionKey: demoSession(DemoFixtures.approvalSessionID).id) }
    }
}

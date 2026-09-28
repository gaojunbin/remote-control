import Foundation
import RCCore

extension ComposerModel {
    /// Enter is Send, so it waits with Send: while the slot is a spinner there
    /// is nothing to press, and a keystroke that sent anyway would be the
    /// button in another guise (`docs/DESIGN.md` § "The composer").
    func primarySubmit() {
        guard slot == .send else { return }
        if answering { submitAnswer() } else { submit(.auto) }
    }

    /// PROTOCOL §5: `auto` is "send now if idle; if running, steer or queue",
    /// and the device decides. The ⋯ menu's Interrupt & send is the one other
    /// mode. A12: the field is cleared and the message is in the timeline in
    /// this turn, before the request leaves; only a refusal the gateway is
    /// certain about comes back, and it hands the draft back if nothing was
    /// typed since.
    func submit(_ mode: SendMode) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let files = attachments
        guard !gates.disabled, !(value.isEmpty && files.isEmpty) else { return }
        // A29: the "Polished · Undo" note belongs to the draft and leaves with it.
        dropPolish()
        if AttachmentLimits.textTooLong(value) {
            errors = [S.composer.textTooLong]
            return
        }
        // A43: a queued message being edited goes back into the line, even
        // behind a steering agent, unless the ⋯ menu interrupts with it.
        if editing != nil {
            putBack(interrupt: mode == .interrupt)
            return
        }
        // A27: a first word the session offers is a command, not a message.
        if commandable, let match = SlashCommands.match(commands, draft: value) {
            run(match)
            return
        }
        send(value, files: files, mode: mode)
    }

    private func send(_ value: String, files: [ComposerAttachment], mode: SendMode) {
        host.drafts.clear(key)
        errors = []
        let outbound = files.map(\.outbound)
        Task {
            let outcome: ChatStore.SendOutcome
            if value.isEmpty {
                // The contract takes a message of files alone; the store's
                // `send` reads its words from the draft and refuses an empty
                // one, and `retry` is its door to the same delivery with the
                // words given — a fresh request id, so nothing is resent.
                chat.draft = ""
                outcome = await chat.retry(PendingSend(id: UUID().uuidString, text: "",
                                                       attachments: outbound, mode: mode, status: .sending))
            } else {
                outcome = await chat.send(mode: mode, attachments: outbound)
            }
            guard outcome == .refused else { return }
            // The store has put the words back if nothing was typed since; the
            // files come back the same way, and the reason is this line's.
            host.drafts.restore(files, to: key)
            takeError(fallback: S.composer.sendFailed)
        }
    }

    /// A27: the device refuses a command mid-turn with `conflict`, so the
    /// composer says so itself rather than spending a round trip on it. The
    /// store runs what its draft names, so the draft is written as the line the
    /// web's rules resolved before it goes.
    private func run(_ match: SlashCommands.Match) {
        if gates.running {
            errors = [S.commands.whileRunning]
            return
        }
        errors = []
        chat.clearError()
        chat.draft = match.command.line(argument: match.argument)
        Task {
            await chat.runCommand()
            takeError(fallback: S.commands.failed)
        }
    }

    /// A20: the draft is the free-text answer of the first question with no
    /// option chosen, beside whatever the card holds. There is no optimistic
    /// row: an answer is not a message, and the card resolving is the receipt.
    /// The page's banner reports a failure, as the web's does.
    func submitAnswer() {
        guard let question, let answers = answer else { return }
        dropPolish()
        let value = text
        setDraft("")
        errors = []
        chat.clearError()
        Task {
            await chat.answer(requestID: question.requestID, answers: answers)
            // A newer draft wins, as it does for a send.
            if chat.errorMessage != nil, text.isEmpty { setDraft(value) }
        }
    }

    /// What a request of the composer's own was refused with goes on the
    /// composer's line, not in the page's banner: the store reports every
    /// refusal in one place, and this one is read off it at once, in the same
    /// turn, before anything draws it there.
    func takeError(fallback: String) {
        guard let message = chat.errorMessage else { return }
        chat.clearError()
        errors = [Self.composerWording(message, fallback: fallback)]
    }

    /// RCCore words a few refusals itself, in English; the sentence the web
    /// shows for them comes from the app's own table.
    static func composerWording(_ message: String, fallback: String) -> String {
        if message == L10n.string("That message has already been sent.") { return S.composer.alreadySent }
        return message.isEmpty ? fallback : message
    }
}

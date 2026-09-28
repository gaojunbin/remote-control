import Foundation
import RCCore

/// A43 — a queued message taken back into the field and put back again
/// (`useQueuedEdit.ts`). The edit itself is the `ChatStore`'s (`queuedEdit`,
/// the words aside); the files aside are the drafts store's.
extension ComposerModel {
    /// Whether a row of Up next can be taken back into the field: not one that
    /// carries files, and not while the composer cannot send or already holds
    /// another one — the store's own rule, so no row looks live and does nothing.
    func canEdit(_ entry: QueuedMessage) -> Bool {
        !gates.disabled && chat.canEdit(entry)
    }

    /// The message leaves the line before the field takes it, so the device
    /// cannot deliver words that are still changing. `not_found` means the
    /// device took it first: nothing opens, and the line under the field says
    /// so. The page's banner is cleared first, as the web's `onTakeQueued` does.
    func edit(_ entry: QueuedMessage) {
        guard canEdit(entry), !takingOut else { return }
        takingOut = true
        chat.clearError()
        Task {
            await chat.beginEdit(entry)
            takingOut = false
            guard chat.queuedEdit != nil else {
                takeError(fallback: S.errors.queueRemoveFailed)
                return
            }
            // The field lets go of a dictation and of a polish note when it
            // takes one, and its files wait aside with its words.
            takeFieldBack()
            dropPolish()
            errors = []
            host.drafts.setAside(key)
            highlight = 0
            panelDismissed = false
            requestFocus()
        }
    }

    /// × takes a message out of the line for good. A refusal is the page's
    /// banner, as it is on the web.
    func remove(_ entry: QueuedMessage) {
        chat.removeQueued(entry.id)
    }

    /// The words go back under the entry's `ts` — or, from the ⋯ menu, as an
    /// interrupt, which keeps no place in the line. Accepted or uncertain, the
    /// edit ends and the files aside come back; refused, the words stay, the
    /// edit goes on, and the reason is this line's.
    func putBack(interrupt: Bool) {
        guard editing != nil, !returning else { return }
        errors = []
        let files = attachments.map(\.outbound)
        Task {
            let outcome = await chat.send(mode: interrupt ? .interrupt : .auto, attachments: files)
            settleEdit(outcome == .refused)
        }
    }

    /// Cancel puts the words back as they were queued, and nothing else.
    func cancelEdit() {
        takeFieldBack()
        dropPolish()
        guard chat.canCancelEdit else { return }
        errors = []
        Task {
            await chat.cancelEdit()
            settleEdit(chat.queuedEdit != nil)
        }
    }

    private func settleEdit(_ refused: Bool) {
        if refused {
            takeError(fallback: S.composer.sendFailed)
        } else if chat.queuedEdit == nil {
            host.drafts.endEdit(key)
        }
    }
}

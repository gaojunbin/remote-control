import Foundation
import RCCore

extension ComposerModel {
    /// Every write of the field goes through here. A changed draft is a
    /// changed command list, so the highlight goes back to the first match and
    /// a panel that was dismissed is open again (A27).
    func setDraft(_ value: String) {
        chat.draft = value
        highlight = 0
        panelDismissed = false
    }

    /// The person typed. Reaching for the field takes it back from dictation;
    /// an edit drops the polish note and any answer still in flight (A29); and
    /// the keystroke that opens the panel asks for the list again, so it is
    /// current the moment it is on screen (A27).
    func userTyped(_ value: String) {
        takeFieldBack()
        dropPolish()
        if commandable, SlashCommands.query(value) != nil, SlashCommands.query(text) == nil,
           agent?.supports(.commands) == true {
            Task { await chat.refreshCommands() }
        }
        setDraft(value)
    }

    /// A pointer down on the field is the other way of reaching for it: the
    /// person stops the dictation to read back what was said.
    func pointerDownInField() { takeFieldBack() }

    /// One key press in the field, answered with whether the composer used it.
    func handle(_ key: ComposerKey, shift: Bool, hasMarkedText: Bool) -> Bool {
        let panel = panelOpen ? ComposerKeys.Panel(
            rows: panelRows.count, highlight: highlightIndex,
            takingChangesField: highlighted.map { SlashCommands.completion(for: $0) != text } ?? false) : nil
        switch ComposerKeys.action(for: key, shift: shift, hasMarkedText: hasMarkedText, panel: panel) {
        case .pass: return false
        case .highlight(let index): highlight = index
        case .dismissPanel: panelDismissed = true
        case .takeRow: if let highlighted { take(highlighted) }
        case .submit: primarySubmit()
        }
        return true
    }

    /// Take a row: `/name ` when it takes an argument, `/name` when it does not.
    func take(_ command: Command) {
        setDraft(SlashCommands.completion(for: command))
        requestFocus()
    }
}

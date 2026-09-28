import Foundation

extension Optional {
    /// The dialogs of Settings and Users are each made fresh for an opening — a
    /// form object holding what was typed — so a modal is open exactly while
    /// its optional holds one, and closing it lets the form go:
    /// `isPresented: $form.settingsModalOpen`. The form itself is handed to the
    /// modal as a value the presenting view reads, never read back through the
    /// binding: a view that does not read its state is not drawn again when the
    /// state changes, and the modal would be drawn from the form it had before.
    var settingsModalOpen: Bool {
        get { self != nil }
        set { if !newValue { self = nil } }
    }
}

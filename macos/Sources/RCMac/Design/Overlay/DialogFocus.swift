import AppKit
import SwiftUI

/// `useOverlay` in `Modal.tsx`: as a dialog or the drawer opens, focus moves to
/// the first focusable element of its panel — `input, textarea, select,
/// button:not([disabled]), [tabindex]` in document order — and the page gives up
/// the keyboard. A field takes it with its caret after the text, and with the
/// ring a focused field always shows. A button takes it with no ring, since a
/// focus a script moves after a click never shows one, which on the Mac is the
/// same as nothing in the panel focused.
///
/// The head comes first in document order: the drawer's close button, and a
/// modal's when it shows one, so those panels start in no field. Otherwise the
/// first field is found under the panel, top to bottom, once it is laid out.
/// SwiftUI's buttons are not views AppKit can see, so a body with a button above
/// its first field would still start in the field; no dialog of the web's has one.
struct DialogFocus: NSViewRepresentable {
    /// False when a close button heads the panel.
    let startsInField: Bool

    func makeNSView(context: Context) -> NSView { DialogFocusProbe(startsInField: startsInField) }
    func updateNSView(_ view: NSView, context: Context) {}
}

private final class DialogFocusProbe: NSView {
    private let startsInField: Bool
    private var done = false

    init(startsInField: Bool) {
        self.startsInField = startsInField
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard window != nil, !done else { return }
        // After the panel's content has been laid out and hosted.
        DispatchQueue.main.async { [weak self] in self?.moveFocus() }
    }

    private func moveFocus() {
        guard let window, let root = window.contentView, !done else { return }
        done = true
        guard startsInField, let field = firstField(under: root) else {
            window.makeFirstResponder(nil)
            return
        }
        guard window.makeFirstResponder(field) else { return }
        // `focus()` leaves the caret after the text; AppKit selects all of it.
        if let editor = (field as? NSTextField)?.currentEditor() {
            editor.selectedRange = NSRange(location: (editor.string as NSString).length, length: 0)
        }
    }

    /// The panel's highest field, the leftmost of a row: window coordinates run upwards.
    private func firstField(under root: NSView) -> NSView? {
        let area = convert(bounds, to: nil)
        let fields = Self.editableFields(in: root).filter { area.intersects($0.convert($0.bounds, to: nil)) }
        return fields.min { lhs, rhs in
            let a = lhs.convert(lhs.bounds, to: nil), b = rhs.convert(rhs.bounds, to: nil)
            return a.maxY == b.maxY ? a.minX < b.minX : a.maxY > b.maxY
        }
    }

    private static func editableFields(in view: NSView) -> [NSView] {
        var found: [NSView] = []
        for sub in view.subviews where !sub.isHidden {
            if let field = sub as? NSTextField, field.isEditable, field.isEnabled {
                found.append(field)
            } else if let text = sub as? NSTextView, text.isEditable, !(text.superview is NSClipView && text.isFieldEditor) {
                found.append(text)
            } else {
                found += editableFields(in: sub)
            }
        }
        return found
    }
}

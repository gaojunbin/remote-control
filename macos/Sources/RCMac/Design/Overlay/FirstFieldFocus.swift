import AppKit
import SwiftUI

/// `useOverlay` in `Modal.tsx` focuses the first field of a dialog or the
/// drawer as it opens, so typing starts there. SwiftUI has no reach into the
/// fields a feature puts in an overlay, so this looks for the first editable
/// field under the panel, top to bottom, once it is laid out.
struct FirstFieldFocus: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { FirstFieldProbe() }
    func updateNSView(_ view: NSView, context: Context) {}
}

private final class FirstFieldProbe: NSView {
    private var done = false

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard window != nil, !done else { return }
        // After the panel's content has been laid out and hosted.
        DispatchQueue.main.async { [weak self] in self?.focusFirstField() }
    }

    private func focusFirstField() {
        guard let window, let root = window.contentView, !done else { return }
        done = true
        let area = convert(bounds, to: nil)
        let fields = Self.editableFields(in: root).filter { area.intersects($0.convert($0.bounds, to: nil)) }
        // Window coordinates run upwards: the first field is the highest one.
        let first = fields.min { lhs, rhs in
            let a = lhs.convert(lhs.bounds, to: nil), b = rhs.convert(rhs.bounds, to: nil)
            return a.maxY == b.maxY ? a.minX < b.minX : a.maxY > b.maxY
        }
        guard let first, window.makeFirstResponder(first) else { return }
        // `focus()` leaves the caret after the text; AppKit selects all of it.
        if let editor = (first as? NSTextField)?.currentEditor() {
            editor.selectedRange = NSRange(location: (editor.string as NSString).length, length: 0)
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

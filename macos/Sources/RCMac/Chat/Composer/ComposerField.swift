import AppKit
import SwiftUI

/// The composer's `<textarea>`: an `NSTextView` in a scroll view, because only
/// AppKit tells whether an input method holds a composition. It grows with its
/// words from one line to 220 points and scrolls inside after that, keeps the
/// undo of what was typed, and writes every edit straight to the draft.
struct ComposerField: NSViewRepresentable {
    let composer: ComposerModel
    /// The words, passed in so a change re-measures the field.
    let text: String
    /// A terminal holds the session, or the device is offline: nothing to type into.
    let disabled: Bool
    /// A43: an edited message is on its way back, so the field holds still.
    let readOnly: Bool
    let onFocusChange: (Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSScrollView {
        let coordinator = context.coordinator
        let storage = NSTextStorage()
        let layout = NSLayoutManager()
        layout.delegate = coordinator.boxes
        storage.addLayoutManager(layout)
        let container = NSTextContainer(size: NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        container.lineFragmentPadding = 0
        layout.addTextContainer(container)
        let field = ComposerTextView(frame: .zero, textContainer: container)
        Self.configure(field)
        field.delegate = coordinator
        coordinator.field = field

        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = false
        scroll.autohidesScrollers = true
        scroll.documentView = field
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.composer = composer
        coordinator.onFocusChange = onFocusChange
        guard let field = coordinator.field else { return }
        field.onKey = { [weak composer] key, shift, marked in
            composer?.handle(key, shift: shift, hasMarkedText: marked) ?? false
        }
        field.onPointerDown = { [weak composer] in composer?.pointerDownInField() }
        field.onFiles = { [weak composer] sources in composer?.attach(sources) }
        field.acceptsFiles = { [weak composer] in composer?.acceptsFiles ?? false }
        field.onFocusChange = { [weak coordinator] focused in
            DispatchQueue.main.async { coordinator?.onFocusChange?(focused) }
        }
        // A write that is not a keystroke — a dictation, a polished answer, a
        // command row, an edit coming in — replaces the words and leaves the
        // caret after them. An input method's composition is never written over.
        if field.string != text, !field.hasMarkedText() {
            field.string = text
            field.setSelectedRange(NSRange(location: (text as NSString).length, length: 0))
            coordinator.undo.removeAllActions()
        }
        field.isEditable = !disabled && !readOnly
        field.isSelectable = !disabled
        if disabled, field.window?.firstResponder === field { field.window?.makeFirstResponder(nil) }
        field.setAccessibilityLabel(S.composer.placeholder)
        coordinator.follow(composer)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSScrollView, context: Context) -> CGSize? {
        let width = proposal.width.flatMap { $0.isFinite ? $0 : nil } ?? 300
        let words = context.coordinator.field?.string ?? text
        let content = context.coordinator.measure.contentHeight(of: words, width: width)
        return CGSize(width: width, height: ComposerFieldText.height(ofContent: content))
    }

    private static func configure(_ field: ComposerTextView) {
        field.isRichText = false
        field.importsGraphics = false
        field.allowsUndo = true
        field.drawsBackground = false
        field.font = ComposerFieldText.style.nsFont
        field.textColor = NSColor(Palette.ink)
        field.defaultParagraphStyle = ComposerFieldText.paragraph
        field.typingAttributes = ComposerFieldText.attributes
        field.textContainerInset = NSSize(width: 0, height: ComposerFieldText.padding)
        field.isVerticallyResizable = true
        field.isHorizontallyResizable = false
        field.autoresizingMask = [.width]
        field.minSize = .zero
        field.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude)
        field.insertionPointColor = NSColor(Palette.ink)
        field.selectedTextAttributes = [.backgroundColor: NSColor(Selection.background)]
        // A browser's textarea underlines a misspelling and changes nothing
        // the person typed.
        field.isContinuousSpellCheckingEnabled = true
        field.isGrammarCheckingEnabled = false
        field.isAutomaticSpellingCorrectionEnabled = false
        field.isAutomaticQuoteSubstitutionEnabled = false
        field.isAutomaticDashSubstitutionEnabled = false
        field.isAutomaticTextReplacementEnabled = false
        field.isAutomaticLinkDetectionEnabled = false
        field.isAutomaticDataDetectionEnabled = false
        field.smartInsertDeleteEnabled = false
    }

    @MainActor
    final class Coordinator: NSObject, NSTextViewDelegate {
        weak var field: ComposerTextView?
        var composer: ComposerModel?
        var onFocusChange: ((Bool) -> Void)?
        let boxes = ComposerLineBoxes.field()
        /// The field's own history, so a write that is not a keystroke can
        /// forget it without touching anything else in the window.
        let undo = UndoManager()
        let measure = ComposerFieldMeasure()
        private var focusHandled = 0
        private var tailHandled = 0

        func textDidChange(_ notification: Notification) {
            guard let field else { return }
            composer?.userTyped(field.string)
        }

        func undoManager(for view: NSTextView) -> UndoManager? { undo }

        /// The focus and the tail, each asked for by bumping a counter. Both
        /// wait for the layout that the new words bring.
        func follow(_ composer: ComposerModel) {
            if composer.focusRequest != focusHandled {
                focusHandled = composer.focusRequest
                // `focus()` with the caret after the words, which a browser
                // scrolls into view.
                DispatchQueue.main.async { [weak self] in
                    guard let field = self?.field, let window = field.window else { return }
                    window.makeFirstResponder(field)
                    field.setSelectedRange(NSRange(location: (field.string as NSString).length, length: 0))
                    if let container = field.textContainer { field.layoutManager?.ensureLayout(for: container) }
                    field.scrollToEndOfDocument(nil)
                }
            }
            if composer.tailRequest != tailHandled {
                tailHandled = composer.tailRequest
                // `scrollTop = scrollHeight`, never animated: the end of the
                // words stays in view while dictation writes them.
                DispatchQueue.main.async { [weak self] in
                    guard let field = self?.field, let container = field.textContainer else { return }
                    field.layoutManager?.ensureLayout(for: container)
                    field.scrollToEndOfDocument(nil)
                }
            }
        }
    }
}

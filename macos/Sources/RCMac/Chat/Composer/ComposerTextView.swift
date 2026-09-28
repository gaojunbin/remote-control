import AppKit

/// The composer's text view: the web's `<textarea class="composer-input">`,
/// with the one thing only AppKit can tell — whether an input method holds a
/// composition — asked before any key of the composer's is acted on.
final class ComposerTextView: NSTextView {
    /// A key the composer may use; true when it did, and the text view then
    /// does nothing with it.
    var onKey: ((ComposerKey, _ shift: Bool, _ hasMarkedText: Bool) -> Bool)?
    var onPointerDown: (() -> Void)?
    var onFocusChange: ((Bool) -> Void)?
    /// Files pasted or dropped, where the composer takes files at all.
    var onFiles: (([AttachmentSource]) -> Void)?
    var acceptsFiles: (() -> Bool)?

    /// As the web's `keydown` handler, only Shift changes what a key does
    /// here: Enter with any other modifier is Enter.
    override func keyDown(with event: NSEvent) {
        if let key = ComposerKey(keyCode: event.keyCode),
           onKey?(key, event.modifierFlags.contains(.shift), hasMarkedText()) == true {
            return
        }
        super.keyDown(with: event)
    }

    override func mouseDown(with event: NSEvent) {
        onPointerDown?()
        super.mouseDown(with: event)
    }

    override func becomeFirstResponder() -> Bool {
        let became = super.becomeFirstResponder()
        if became { onFocusChange?(true) }
        return became
    }

    override func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned { onFocusChange?(false) }
        return resigned
    }

    /// Tab leaves a `<textarea>` for the next control rather than typing a tab.
    override func insertTab(_ sender: Any?) { window?.selectNextKeyView(nil) }

    override func insertBacktab(_ sender: Any?) { window?.selectPreviousKeyView(nil) }

    /// Escape is not a completion request here; it goes on to whatever is
    /// listening above the field.
    override func cancelOperation(_ sender: Any?) {}

    override func complete(_ sender: Any?) {}

    /// The caret is as tall as the words, as a browser draws it, rather than
    /// as tall as the line box they sit in.
    override func drawInsertionPoint(in rect: NSRect, color: NSColor, turnedOn flag: Bool) {
        guard let font else { return super.drawInsertionPoint(in: rect, color: color, turnedOn: flag) }
        let height = (font.ascender - font.descender).rounded()
        guard rect.height > height else { return super.drawInsertionPoint(in: rect, color: color, turnedOn: flag) }
        let top = rect.minY + (ComposerFieldText.style.baseline - font.ascender).rounded()
        super.drawInsertionPoint(in: NSRect(x: rect.minX, y: top, width: rect.width, height: height),
                                 color: color, turnedOn: flag)
    }

    // MARK: - Pasting and dropping files

    override var acceptableDragTypes: [NSPasteboard.PasteboardType] {
        super.acceptableDragTypes + [.fileURL, .png, .tiff]
    }

    /// A paste that holds files attaches them; one that cannot be attached
    /// here pastes as words, the way a browser's `textarea` does.
    override func paste(_ sender: Any?) {
        if acceptsFiles?() == true, let sources = Self.files(on: .general) {
            onFiles?(sources)
            return
        }
        pasteAsPlainText(sender)
    }

    /// Files dropped on the field attach, or are refused whole: a path is
    /// never typed in their place.
    override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        guard Self.files(on: sender.draggingPasteboard) != nil else { return super.draggingEntered(sender) }
        return acceptsFiles?() == true ? .copy : []
    }

    override func draggingUpdated(_ sender: any NSDraggingInfo) -> NSDragOperation {
        guard Self.files(on: sender.draggingPasteboard) != nil else { return super.draggingUpdated(sender) }
        return acceptsFiles?() == true ? .copy : []
    }

    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        guard let sources = Self.files(on: sender.draggingPasteboard) else {
            return super.performDragOperation(sender)
        }
        guard acceptsFiles?() == true else { return false }
        onFiles?(sources)
        return true
    }

    /// What a pasteboard holds as files: the files themselves, or a picture
    /// with no file behind it, which a browser hands a page as `image.png`.
    static func files(on pasteboard: NSPasteboard) -> [AttachmentSource]? {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL], !urls.isEmpty {
            return urls.map(AttachmentSource.file)
        }
        if let png = pasteboard.data(forType: .png) {
            return [.data(name: "image.png", mime: "image/png", png)]
        }
        if let tiff = pasteboard.data(forType: .tiff),
           let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            return [.data(name: "image.png", mime: "image/png", png)]
        }
        return nil
    }
}

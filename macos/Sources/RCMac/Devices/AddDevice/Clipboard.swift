import AppKit

/// `navigator.clipboard.writeText`, on the general pasteboard.
@MainActor
enum Clipboard {
    static func write(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }
}

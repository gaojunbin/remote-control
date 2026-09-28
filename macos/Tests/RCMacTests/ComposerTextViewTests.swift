import AppKit
import Testing
@testable import RCMac

/// The field itself: a key the composer uses never reaches the text, and the
/// key that confirms an input method's composition is always reported as the
/// input method's (`hasMarkedText()`), which is the rule `useImeGuard.ts`
/// exists for on the web.
@Suite("Composer text view") @MainActor
struct ComposerTextViewTests {
    private func keyDown(_ keyCode: UInt16, _ characters: String, shift: Bool = false) -> NSEvent {
        NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: shift ? [.shift] : [], timestamp: 0,
                         windowNumber: 0, context: nil, characters: characters,
                         charactersIgnoringModifiers: characters, isARepeat: false, keyCode: keyCode)!
    }

    private func field() -> (ComposerTextView, ComposerKeyLog) {
        let view = ComposerTextView(frame: NSRect(x: 0, y: 0, width: 300, height: 40))
        let log = ComposerKeyLog()
        view.onKey = { key, shift, marked in
            log.keys.append((key, shift, marked))
            return ComposerKeys.action(for: key, shift: shift, hasMarkedText: marked, panel: nil) != .pass
        }
        return (view, log)
    }

    @Test func enterIsTheComposersAndTypesNothing() {
        let (view, log) = field()
        view.string = "send this"
        view.keyDown(with: keyDown(36, "\r"))
        #expect(log.keys.count == 1)
        #expect(log.keys.first?.key == .enter && log.keys.first?.marked == false)
        #expect(view.string == "send this")
    }

    @Test func theEnterThatConfirmsACompositionIsReportedAsTheInputMethods() {
        let (view, log) = field()
        view.setMarkedText("zhong", selectedRange: NSRange(location: 5, length: 0),
                           replacementRange: NSRange(location: NSNotFound, length: 0))
        #expect(view.hasMarkedText())
        view.keyDown(with: keyDown(36, "\r"))
        #expect(log.keys.first?.marked == true)
    }

    @Test func shiftIsReadOffTheEvent() {
        let (view, log) = field()
        view.keyDown(with: keyDown(36, "\r", shift: true))
        #expect(log.keys.first?.shift == true)
    }

    @Test func aPictureOnThePasteboardIsAFileNamedAsABrowserNamesIt() throws {
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("composer-tests-\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        pasteboard.clearContents()
        let image = NSImage(size: NSSize(width: 2, height: 2), flipped: false) { rect in
            NSColor.black.setFill()
            rect.fill()
            return true
        }
        pasteboard.writeObjects([image])
        let sources = try #require(ComposerTextView.files(on: pasteboard))
        guard case .data(let name, let mime, let data) = try #require(sources.first) else {
            Issue.record("expected image data")
            return
        }
        #expect(name == "image.png" && mime == "image/png" && !data.isEmpty)
    }

    @Test func wordsOnThePasteboardAreNotFiles() {
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("composer-tests-\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        pasteboard.clearContents()
        pasteboard.setString("just words", forType: .string)
        #expect(ComposerTextView.files(on: pasteboard) == nil)
    }
}

/// The keys a field reported, kept by reference.
@MainActor
final class ComposerKeyLog {
    var keys: [(key: ComposerKey, shift: Bool, marked: Bool)] = []
}

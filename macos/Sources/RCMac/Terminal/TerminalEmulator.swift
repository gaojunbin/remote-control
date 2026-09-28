import AppKit
import RCCore
import SwiftTerm
import SwiftUI

/// The one handle the page holds on the emulator: bytes go in, and nothing
/// comes back out through it. The emulator is an AppKit view the
/// representable owns, so a SwiftUI update cannot carry a byte stream; this is
/// the wire between them, and the emulator installs its end.
@MainActor
final class TerminalFeed {
    var writer: ((Data) -> Void)?

    func write(_ bytes: Data) {
        guard !bytes.isEmpty else { return }
        writer?(bytes)
    }

    /// `term.reset()`: a full reset, asked for the way a shell asks for one.
    func reset() { write(Self.fullReset) }

    /// RIS, `ESC c`.
    static let fullReset = Data([0x1B, 0x63])
}

/// SwiftTerm's macOS view, filling the page's body (`docs/DESIGN.md` § "The
/// terminal"): it renders, selects, scrolls and turns keys into the bytes a
/// terminal expects; copy and paste are its own, on ⌘C and ⌘V through the Edit
/// menu. Everything else — what to do with the bytes, what the status line
/// says — belongs to the page.
struct TerminalEmulator: NSViewRepresentable {
    let feed: TerminalFeed
    /// The emulator was laid out at this many columns and rows.
    let onSize: @MainActor (TerminalSize) -> Void
    /// Bytes the person typed or pasted, as the emulator encodes them.
    let onInput: @MainActor (Data) -> Void

    func makeNSView(context: Context) -> MeasuredTerminalView {
        let view = MeasuredTerminalView(frame: .zero)
        TerminalTheme.dress(view)
        view.terminalDelegate = context.coordinator
        view.onResize = { [weak view, weak coordinator = context.coordinator] in
            guard let view else { return }
            coordinator?.report(cols: view.getTerminal().cols, rows: view.getTerminal().rows)
        }
        feed.writer = { [weak view] bytes in view?.feed(byteArray: [UInt8](bytes)[...]) }
        return view
    }

    func updateNSView(_ view: MeasuredTerminalView, context: Context) {
        context.coordinator.onSize = onSize
        context.coordinator.onInput = onInput
    }

    func makeCoordinator() -> Coordinator { Coordinator(onSize: onSize, onInput: onInput) }

    /// The delegate SwiftTerm calls, all of it on the main thread. The
    /// conformance is `@preconcurrency` because the library predates Swift 6
    /// isolation; the runtime check that inserts is what proves the calls do
    /// arrive on the main actor.
    @MainActor
    final class Coordinator: NSObject, @preconcurrency TerminalViewDelegate {
        var onSize: @MainActor (TerminalSize) -> Void
        var onInput: @MainActor (Data) -> Void
        private var reported: TerminalSize?

        init(onSize: @escaping @MainActor (TerminalSize) -> Void, onInput: @escaping @MainActor (Data) -> Void) {
            self.onSize = onSize
            self.onInput = onInput
        }

        /// One report per size, whether the library's callback or the layout
        /// pass got there first. A collapsed view fits to nothing, and nothing
        /// is not a size.
        func report(cols: Int, rows: Int) {
            guard cols > 0, rows > 0 else { return }
            let size = TerminalSize(cols: cols, rows: rows)
            guard size != reported else { return }
            reported = size
            onSize(size)
        }

        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            report(cols: newCols, rows: newRows)
        }

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            guard !data.isEmpty else { return }
            onInput(Data(data))
        }

        // xterm.js, as the web opens it, answers none of these: no title, no
        // working directory, no bell, no link a shell names, no clipboard a
        // shell writes to. The person's own ⌘C is the one way out.
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {}
        func bell(source: TerminalView) {}
        func clipboardCopy(source: TerminalView, content: Data) {}
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
    }
}

/// A terminal view that says when its frame changed. SwiftTerm reports a size
/// only when the column or row count moves, and the first frame after a zero
/// one does not always move it; the page needs a size before it can open
/// anything, so the frame itself is the signal.
final class MeasuredTerminalView: TerminalView {
    var onResize: (() -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        // The web's emulator draws no bar at rest; SwiftTerm's is a legacy bar
        // whose track is always drawn. The wheel and the trackpad scroll the
        // scrollback without it, and the room both keep for a bar is the same.
        for case let scroller as NSScroller in subviews { scroller.isHidden = true }
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        onResize?()
    }
}

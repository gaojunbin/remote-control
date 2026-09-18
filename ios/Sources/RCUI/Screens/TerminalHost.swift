import SwiftUI
import RCCore
#if os(iOS)
import UIKit
import SwiftTerm
#endif

/// The one handle the terminal screen holds on the emulator: bytes go in, and
/// nothing comes back out through it.
///
/// The emulator is a UIKit view a `UIViewRepresentable` owns, so the screen
/// cannot address it directly and a SwiftUI update cannot carry a byte stream.
/// This is the wire between them, and it is deliberately one-way.
@MainActor
final class TerminalFeed {
    fileprivate var writer: ((Data) -> Void)?
    /// What the emulator was last drawn at, which is what a fresh `open` asks
    /// the device for.
    fileprivate(set) var size: TerminalSize?

    func write(_ bytes: Data) { writer?(bytes) }
}

#if os(iOS)

/// SwiftTerm, wrapped (amendment A38, `docs/DESIGN.md` § "The terminal": the
/// emulator fills the screen and follows the visible area).
///
/// It renders, selects, scrolls and produces key sequences; everything else —
/// what to do with those bytes, what the status line says — belongs to the
/// screen around it.
struct TerminalHost: UIViewRepresentable {
    let feed: TerminalFeed
    /// The type size, remembered between terminals (`SettingsStore`).
    let fontSize: Double
    /// The emulator was laid out at this many columns and rows.
    let onSize: (TerminalSize) -> Void
    /// Bytes the person typed, as the emulator encodes them.
    let onInput: (Data) -> Void
    /// A pinch landed on a new type size.
    let onFontSize: (Double) -> Void

    func makeUIView(context: Context) -> LaidOutTerminalView {
        let view = LaidOutTerminalView(frame: .zero)
        view.terminalDelegate = context.coordinator
        view.font = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        // The app's own ink on the app's own surface. SwiftTerm's defaults are
        // a mid grey on white, which reads as disabled text rather than as a
        // shell; the screen paints the background behind a clear view so it
        // follows the appearance the rest of the app follows.
        view.backgroundColor = .clear
        view.nativeForegroundColor = UIColor(Theme.ink)
        // The bar below carries Esc, Tab and the arrows, so SwiftTerm's own
        // accessory would be a second row saying the same thing.
        view.inputAccessoryView = nil
        view.onLayout = { [weak view] in
            guard let view else { return }
            context.coordinator.report(cols: view.getTerminal().cols, rows: view.getTerminal().rows)
        }
        context.coordinator.attach(to: view, feed: feed)
        view.addGestureRecognizer(UIPinchGestureRecognizer(
            target: context.coordinator, action: #selector(Coordinator.pinched(_:))))
        view.accessibilityIdentifier = "terminal.emulator"
        return view
    }

    func updateUIView(_ view: LaidOutTerminalView, context: Context) {
        context.coordinator.onSize = onSize
        context.coordinator.onInput = onInput
        context.coordinator.onFontSize = onFontSize
        let wanted = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        if view.font.pointSize != wanted.pointSize { view.font = wanted }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onSize: onSize, onInput: onInput, onFontSize: onFontSize)
    }

    /// The delegate SwiftTerm calls, all of it on the main thread. The
    /// conformance is marked `@preconcurrency` because the library predates
    /// Swift 6 isolation and declares the protocol without it; the calls really
    /// do arrive on the main actor, and the runtime check that marking inserts
    /// is what proves it rather than a comment.
    @MainActor
    final class Coordinator: NSObject, @preconcurrency TerminalViewDelegate {
        var onSize: (TerminalSize) -> Void
        var onInput: (Data) -> Void
        var onFontSize: (Double) -> Void
        private weak var feed: TerminalFeed?
        private var reported: TerminalSize?
        private var pinchBase: Double?

        init(onSize: @escaping (TerminalSize) -> Void,
             onInput: @escaping (Data) -> Void,
             onFontSize: @escaping (Double) -> Void) {
            self.onSize = onSize
            self.onInput = onInput
            self.onFontSize = onFontSize
        }

        func attach(to view: LaidOutTerminalView, feed: TerminalFeed) {
            self.feed = feed
            feed.writer = { [weak view] bytes in
                guard let view, !bytes.isEmpty else { return }
                view.feed(byteArray: [UInt8](bytes)[...])
            }
        }

        /// One report per size, whether the library's own callback or the
        /// layout pass got there first.
        func report(cols: Int, rows: Int) {
            guard cols > 0, rows > 0 else { return }
            let size = TerminalSize(cols: cols, rows: rows)
            guard size != reported else { return }
            reported = size
            feed?.size = size
            onSize(size)
        }

        @objc func pinched(_ gesture: UIPinchGestureRecognizer) {
            guard let view = gesture.view as? TerminalView else { return }
            switch gesture.state {
            case .began:
                pinchBase = Double(view.font.pointSize)
            case .changed, .ended:
                guard let base = pinchBase else { return }
                let size = TerminalTypeSize.scaled(base, by: Double(gesture.scale))
                guard size != Double(view.font.pointSize) else { return }
                view.font = UIFont.monospacedSystemFont(ofSize: size, weight: .regular)
                onFontSize(size)
            default:
                pinchBase = nil
            }
        }

        // MARK: - TerminalViewDelegate

        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            report(cols: newCols, rows: newRows)
        }

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            guard !data.isEmpty else { return }
            onInput(Data(data))
        }

        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {}
        func bell(source: TerminalView) {}
        /// The one thing that leaves the terminal: what the person selected and
        /// asked the system menu to copy.
        func clipboardCopy(source: TerminalView, content: Data) {
            UIPasteboard.general.string = String(data: content, encoding: .utf8)
        }
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
    }
}

/// A terminal view that says when it has been laid out.
///
/// SwiftTerm reports a size change only when the column or row count moves, and
/// the first layout after a zero frame does not always move it. The screen needs
/// a size before it can open anything, so the layout pass itself is the signal.
final class LaidOutTerminalView: TerminalView {
    var onLayout: (() -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

#else

/// The macOS stand-in. The app ships for iOS; this exists so the screen, its
/// status line and its key bar compile and can be checked on the host, as
/// `Theme.swift` explains for every other iOS-only piece.
struct TerminalHost: View {
    let feed: TerminalFeed
    let fontSize: Double
    let onSize: (TerminalSize) -> Void
    let onInput: (Data) -> Void
    let onFontSize: (Double) -> Void

    var body: some View {
        Color.black
            .onAppear { onSize(TerminalSize(cols: 80, rows: 24)) }
    }
}

#endif

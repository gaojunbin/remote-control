import AppKit
import RCMac
import SwiftUI

/// A window of the app's own shape — full-size content under a transparent
/// title bar, the traffic lights in its corner — that is never on a screen: it
/// sits far outside every display, and AppKit is not allowed to pull it back.
final class OffscreenWindow: NSWindow {
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect { frameRect }
}

@MainActor
enum PreviewWindow {
    static func make<Content: View>(width: CGFloat, height: CGFloat, content: Content) -> NSWindow {
        let window = OffscreenWindow(contentRect: NSRect(x: 0, y: 0, width: width, height: height),
                                     styleMask: [.titled, .closable, .miniaturizable, .resizable,
                                                 .fullSizeContentView],
                                     backing: .buffered, defer: false)
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isReleasedWhenClosed = false
        window.appearance = NSAppearance(named: .aqua)
        let host = NSHostingView(rootView: content)
        host.sizingOptions = []
        window.contentView = host
        window.setContentSize(NSSize(width: width, height: height))
        window.setFrameOrigin(NSPoint(x: -40_000, y: -40_000))
        window.orderFrontRegardless()
        return window
    }

    /// What is captured: the content, which fills the whole window. The
    /// traffic lights are drawn by the window's frame, whose layers the
    /// renderer cannot reach, so a render shows the room kept for them but
    /// not the buttons.
    static func frameView(of window: NSWindow) -> NSView? { window.contentView }
}

import AppKit
import Observation
import SwiftUI

/// The main window as `docs/DESIGN.md` § "The Mac app" draws it: no title bar,
/// the content under the whole window, the traffic lights set into the
/// leading edge of the page's top strip and centred in it, always light, and
/// never narrower than 480 × 560.
@MainActor
@Observable
final class WindowChrome {
    /// How far the traffic lights reach into the strip, for its content to start after.
    private(set) var inset: CGFloat = 0
    /// The strip the traffic lights are centred in, which the page on screen declares.
    var stripHeight: CGFloat = LayoutSize.headerH {
        didSet { if stripHeight != oldValue { placeTrafficLights() } }
    }

    @ObservationIgnored private weak var window: NSWindow?
    @ObservationIgnored private weak var model: MacAppModel?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    /// AppKit puts the title bar back to its own 28 points whenever it lays the
    /// window out, which would leave the buttons above the window's top edge;
    /// every time it does, the strip's height is put back.
    @ObservationIgnored private var containerFrame: NSKeyValueObservation?
    @ObservationIgnored private weak var observedContainer: NSView?

    /// The gap between the zoom button and the first thing in the strip.
    private static let gap: CGFloat = Space.sp4
    /// Where the close button starts: a strip this tall sits the buttons as a
    /// unified toolbar does, not in the corner a 28-point title bar keeps.
    private static let leading: CGFloat = 20

    func attach(_ window: NSWindow, model: MacAppModel) {
        guard self.window !== window else { return }
        self.window = window
        self.model = model
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.styleMask.insert(.fullSizeContentView)
        window.appearance = NSAppearance(named: .aqua)
        window.backgroundColor = NSColor(Palette.canvas)
        window.minSize = NSSize(width: 480, height: 560)
        if model.options.ephemeral {
            // An ephemeral run leaves nothing behind, the window's place included.
            window.setFrameAutosaveName("")
            window.isRestorable = false
        }
        // `::selection`: selected text in every field takes the web's tint.
        if let editor = window.fieldEditor(true, for: nil) as? NSTextView {
            editor.selectedTextAttributes = [.backgroundColor: NSColor(Selection.background)]
        }
        observe(window)
        placeTrafficLights()
        noteActivity()
    }

    private func observe(_ window: NSWindow) {
        let center = NotificationCenter.default
        let placing: [Notification.Name] = [NSWindow.didResizeNotification, NSWindow.didEndLiveResizeNotification,
                                           NSWindow.didExitFullScreenNotification,
                                           NSWindow.didEnterFullScreenNotification]
        let activity: [Notification.Name] = [NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification,
                                            NSWindow.didMiniaturizeNotification,
                                            NSWindow.didDeminiaturizeNotification,
                                            NSWindow.didChangeOcclusionStateNotification,
                                            NSWindow.willCloseNotification]
        for name in placing {
            observers.append(center.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.placeTrafficLights() }
            })
        }
        for name in activity {
            let closing = name == NSWindow.willCloseNotification
            observers.append(center.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.noteActivity(closing: closing)
                    self?.placeTrafficLights()
                }
            })
        }
        for name in [NSApplication.didBecomeActiveNotification, NSApplication.didResignActiveNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.noteActivity() }
            })
        }
    }

    /// Whether a conversation shown in this window is in front of the person.
    private func noteActivity(closing: Bool = false) {
        guard let window, let model else { return }
        model.isWindowActive = !closing && NSApp.isActive && window.isKeyWindow && window.isVisible
            && !window.isMiniaturized && window.occlusionState.contains(.visible)
    }

    /// Centre the three buttons in the strip and keep AppKit's own horizontal
    /// places for them; in full screen they live in the menu bar and take no room.
    private func placeTrafficLights() {
        guard let window, let close = window.standardWindowButton(.closeButton),
              let zoom = window.standardWindowButton(.zoomButton),
              let titlebar = close.superview, let container = titlebar.superview else { return }
        guard !window.styleMask.contains(.fullScreen) else {
            inset = 0
            return
        }
        watch(container)
        let height = stripHeight
        let wanted = NSRect(x: container.frame.minX, y: window.frame.height - height,
                            width: container.frame.width, height: height)
        if container.frame != wanted { container.frame = wanted }
        if titlebar.frame != container.bounds { titlebar.frame = container.bounds }
        // AppKit's own spacing between the buttons, from wherever it put them.
        let pitch = (window.standardWindowButton(.miniaturizeButton)?.frame.minX ?? close.frame.minX + 20)
            - close.frame.minX
        for (index, kind) in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton].enumerated() {
            guard let button = window.standardWindowButton(kind) else { continue }
            let origin = NSPoint(x: Self.leading + CGFloat(index) * pitch,
                                 y: ((height - button.frame.height) / 2).rounded())
            if button.frame.origin != origin { button.setFrameOrigin(origin) }
        }
        let reach = zoom.convert(zoom.bounds, to: nil).maxX + Self.gap
        if inset != reach { inset = reach }
    }

    private func watch(_ container: NSView) {
        guard observedContainer !== container else { return }
        observedContainer = container
        containerFrame = container.observe(\.frame, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.placeTrafficLights() }
        }
    }
}

/// Hands the window a view is in to `found`, once it is in one.
struct WindowAccessor: NSViewRepresentable {
    let found: @MainActor (NSWindow) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = WindowProbeView()
        view.found = found
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        if let window = view.window { found(window) }
    }
}

private final class WindowProbeView: NSView {
    var found: (@MainActor (NSWindow) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let window { found?(window) }
    }
}

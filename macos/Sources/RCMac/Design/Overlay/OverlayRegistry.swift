import AppKit
import SwiftUI

/// The overlays drawn right now, innermost last, which is what Escape and a
/// click outside act on. The web keeps the same stack (`overlayStack` in
/// `Modal.tsx`) because every handler sits on the document and the newest has
/// to be the one that closes.
@MainActor
final class OverlayRegistry {
    struct Record {
        let openedAt: Int
        let isPopover: Bool
        let dismiss: @MainActor () -> Void
    }

    /// A popover's panel and its trigger, in window coordinates: a press inside
    /// either leaves the popover open.
    struct Frames {
        let panel: CGRect
        let trigger: CGRect
    }

    private var records: [UUID: Record] = [:]
    /// Kept apart from the records, because a panel may be measured before the
    /// layer has registered it.
    private var frames: [UUID: Frames] = [:]

    var isEmpty: Bool { records.isEmpty }

    func register(_ id: UUID, openedAt: Int, isPopover: Bool, dismiss: @escaping @MainActor () -> Void) {
        records[id] = Record(openedAt: openedAt, isPopover: isPopover, dismiss: dismiss)
    }

    func update(_ id: UUID, panel: CGRect, trigger: CGRect) {
        frames[id] = Frames(panel: panel, trigger: trigger)
    }

    func unregister(_ id: UUID) {
        records.removeValue(forKey: id)
        frames.removeValue(forKey: id)
    }

    /// Escape: the newest overlay closes, and only that one.
    @discardableResult
    func dismissNewest() -> Bool {
        guard let newest = records.max(by: { $0.value.openedAt < $1.value.openedAt }) else { return false }
        newest.value.dismiss()
        return true
    }

    /// A press anywhere closes every popover it is not inside, and goes on to
    /// whatever is under it, as a `mousedown` on the document does on the web.
    func pointerDown(at point: CGPoint) {
        for (id, record) in records where record.isPopover {
            // Not measured yet means not on screen yet: nothing to have missed.
            guard let frame = frames[id] else { continue }
            if !frame.panel.contains(point) && !frame.trigger.contains(point) { record.dismiss() }
        }
    }
}

extension EnvironmentValues {
    @Entry var overlayRegistry: OverlayRegistry?
}

/// The key and mouse events the overlay layer answers, read from the event
/// stream before any view sees them.
@MainActor
final class OverlayEvents {
    private var monitor: Any?

    func start(_ registry: OverlayRegistry) {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .leftMouseDown, .rightMouseDown]) { event in
            MainActor.assumeIsolated { Self.handle(event, registry) } ? nil : event
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    /// True when the event was used up.
    private static func handle(_ event: NSEvent, _ registry: OverlayRegistry) -> Bool {
        guard !registry.isEmpty else { return false }
        switch event.type {
        case .keyDown:
            guard event.keyCode == 53, !isComposing(in: event.window) else { return false }
            return registry.dismissNewest()
        case .leftMouseDown, .rightMouseDown:
            guard let window = event.window, let content = window.contentView else { return false }
            let location = event.locationInWindow
            registry.pointerDown(at: CGPoint(x: location.x, y: content.frame.height - location.y))
            return false
        default:
            return false
        }
    }

    /// Escape that cancels an input method's composition belongs to the input method.
    private static func isComposing(in window: NSWindow?) -> Bool {
        (window?.firstResponder as? NSTextView)?.hasMarkedText() == true
    }
}

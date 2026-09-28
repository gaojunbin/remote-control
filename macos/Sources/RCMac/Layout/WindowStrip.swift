import AppKit
import SwiftUI

/// The empty part of a page's top strip, which moves the window as a title
/// bar does and zooms it on a double click, as the person's System Settings
/// ask (`AppleActionOnDoubleClick`).
public struct WindowStrip: View {
    public init() {}

    public var body: some View {
        Color.clear
            .contentShape(Rectangle())
            .gesture(WindowDragGesture())
            .simultaneousGesture(TapGesture(count: 2).onEnded { Self.doubleClick() })
            .allowsWindowActivationEvents(true)
    }

    private static func doubleClick() {
        guard let window = NSApp.keyWindow else { return }
        switch UserDefaults.standard.string(forKey: "AppleActionOnDoubleClick") {
        case "Minimize": window.miniaturize(nil)
        case "None": break
        default: window.zoom(nil)
        }
    }
}

extension EnvironmentValues {
    /// How far into the window's top-leading corner the traffic lights reach,
    /// in points: a page's top strip starts its content after them. Zero in a
    /// window without them.
    @Entry public var trafficLightInset: CGFloat = 0
}

/// The height of the strip across the top of the page on screen, which the
/// traffic lights are centred in: the topbar's 60 points, the chat header's own.
struct WindowStripHeightKey: PreferenceKey {
    static var defaultValue: CGFloat? { nil }

    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) {
        value = nextValue() ?? value
    }
}

extension View {
    /// Declares this view as the page's top strip, `height` points tall.
    public func windowStripHeight(_ height: CGFloat) -> some View {
        preference(key: WindowStripHeightKey.self, value: height)
    }
}

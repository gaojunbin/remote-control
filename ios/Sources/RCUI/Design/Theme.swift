import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The product palette from the design brief: a light, quiet page with white
/// surfaces, hairline borders and black primary actions.
///
/// Every colour is defined for both appearances so the app stays legible when
/// the system is dark. Version 1 is designed light; the dark values keep
/// contrast rather than inverting the design.
public enum Theme {
    public static let canvas = dynamic(light: 0xF5F5F4, dark: 0x121211)
    public static let surface = dynamic(light: 0xFFFFFF, dark: 0x1C1C1B)
    public static let surfaceSunken = dynamic(light: 0xF0EFED, dark: 0x232322)
    public static let border = dynamic(light: 0xE6E5E1, dark: 0x33332F)
    public static let ink = dynamic(light: 0x111111, dark: 0xF2F2F0)
    public static let inkSecondary = dynamic(light: 0x6B6B6B, dark: 0xA0A09B)
    public static let accent = dynamic(light: 0x111111, dark: 0xF2F2F0)
    public static let onAccent = dynamic(light: 0xFFFFFF, dark: 0x111111)

    public static let running = dynamic(light: 0x22A06B, dark: 0x36BE85)
    public static let attention = dynamic(light: 0xE0862B, dark: 0xF0A050)
    public static let resting = dynamic(light: 0xB5B5B0, dark: 0x6E6E69)
    public static let danger = dynamic(light: 0xD23F31, dark: 0xE8695C)

    public static let added = dynamic(light: 0x1F7A4D, dark: 0x49B57F)
    public static let removed = dynamic(light: 0xB03227, dark: 0xE0736A)

    /// Corner radii: cards and sheets, then pills.
    public enum Radius {
        public static let card: CGFloat = 14
        public static let sheet: CGFloat = 16
        public static let control: CGFloat = 12
        public static let pill: CGFloat = 999
    }

    /// A 4-point spacing scale.
    public enum Space {
        public static let hair: CGFloat = 2
        public static let tight: CGFloat = 6
        public static let small: CGFloat = 10
        public static let medium: CGFloat = 16
        public static let large: CGFloat = 24
        public static let page: CGFloat = 20
    }

    /// The minimum comfortable target, and the primary thumb target.
    public enum Touch {
        public static let minimum: CGFloat = 44
        public static let primary: CGFloat = 48
    }

    public static let mono = Font.system(.footnote, design: .monospaced)
    public static let monoBody = Font.system(.callout, design: .monospaced)

    /// The colour a session's status dot uses.
    public static func statusColor(_ state: SessionStateToken) -> Color {
        switch state {
        case .running: running
        case .attention: attention
        case .error: danger
        case .resting: resting
        }
    }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        #if os(iOS)
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
        #else
        Color(rgb: light)
        #endif
    }
}

/// The four visual states a session dot can take. Shape and text always carry
/// the same information, so colour is never the only signal.
public enum SessionStateToken: Sendable {
    case running, attention, error, resting
}

extension Color {
    init(rgb: UInt32) {
        self.init(.sRGB,
                  red: Double((rgb >> 16) & 0xFF) / 255,
                  green: Double((rgb >> 8) & 0xFF) / 255,
                  blue: Double(rgb & 0xFF) / 255,
                  opacity: 1)
    }
}

#if os(iOS)
extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}
#endif

extension View {
    /// A white card with a hairline border and the lightest possible shadow.
    public func card(padding: CGFloat = Theme.Space.medium,
                     radius: CGFloat = Theme.Radius.card) -> some View {
        self.padding(padding)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous)
                .strokeBorder(Theme.border, lineWidth: 0.5))
            .shadow(color: .black.opacity(0.06), radius: 1, x: 0, y: 1)
    }

    public func pageBackground() -> some View {
        self.background(Theme.canvas.ignoresSafeArea())
    }

    /// Text fields that hold addresses, paths and commands.
    public func plainTextEntry() -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        self.autocorrectionDisabled()
        #endif
    }

    public func inlineNavigationTitle() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// macOS previews need an explicit size; on iOS the sheet sizes itself.
    public func sheetSize() -> some View {
        #if os(macOS)
        self.frame(minWidth: 380, idealWidth: 420, minHeight: 620, idealHeight: 760)
        #else
        self
        #endif
    }
}

/// Platform shims. The app ships for iOS; the macOS host exists so the SwiftUI
/// layer can be compiled and checked without booting a simulator, so anything
/// iOS-only is expressed once here instead of scattering `#if` through screens.
extension View {
    public func groupedList() -> some View {
        #if os(iOS)
        return self.listStyle(.insetGrouped)
        #else
        return self.listStyle(.inset)
        #endif
    }

    /// A conversation takes the whole screen, tab bar included.
    public func hideTabBar() -> some View {
        #if os(iOS)
        return self.toolbar(.hidden, for: .tabBar)
        #else
        return self
        #endif
    }

    /// A translucent strip behind the composer and the footers.
    public func barBackground() -> some View {
        #if os(iOS)
        return self.background(.bar)
        #else
        return self.background(Theme.surface)
        #endif
    }
}

extension ToolbarItemPlacement {
    public static var trailingBar: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .automatic
        #endif
    }
}

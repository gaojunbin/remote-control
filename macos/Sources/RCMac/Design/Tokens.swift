import SwiftUI

/// `web/src/styles/tokens.css`, one constant per custom property and named after
/// it (`--surface-sunken` is `Palette.surfaceSunken`, `--fs-13` is
/// `FontSize.fs13`). `docs/DESIGN.md` § "Palette and type" and § "Surfaces,
/// rows and controls" are the rules they encode: one canvas, soft surfaces
/// instead of bordered boxes, spacing rather than lines between rows. A visual
/// change starts here and in the web's file, never in a view. 1 CSS px is 1 pt.
public enum Palette {
    // surfaces
    public static let canvas = Color(hex: 0xF5F5F4)
    public static let surface = Color(hex: 0xFFFFFF)
    public static let surfaceSunken = Color(hex: 0xFAFAF9)
    public static let surfaceMuted = Color(hex: 0xF1F1EF)
    public static let surfaceHover = Color(hex: 0xF5F5F4)
    public static let surfaceActive = Color(hex: 0xECECEA)
    public static let overlay = Color(rgb: (24, 24, 22), opacity: 0.28)

    // lines
    public static let line = Color(hex: 0xE6E5E1)
    public static let lineStrong = Color(hex: 0xD6D5D0)
    /// A bar's edge — the chat header, a page's section break — and never a line
    /// between two rows: every list, Settings included, is parted by spacing.
    public static let hairline = Color(rgb: (17, 17, 17), opacity: 0.08)

    /// A list row under the pointer, and the stronger step the selected row holds.
    public static let hover = Color(rgb: (17, 17, 17), opacity: 0.04)
    public static let hoverSelected = Color(rgb: (17, 17, 17), opacity: 0.07)

    // ink
    public static let ink = Color(hex: 0x111111)
    public static let inkSecondary = Color(hex: 0x6B6B6B)
    public static let inkTertiary = Color(hex: 0x767570)
    public static let inkInverse = Color(hex: 0xFFFFFF)

    // status
    public static let running = Color(hex: 0x22A06B)
    public static let runningSoft = Color(hex: 0xE7F4EE)
    /// Amber, not orange: a blocked session reads as yellow beside the greens,
    /// and stays above 3:1 on every row background (3.67:1 on `surface`).
    public static let attention = Color(hex: 0xB07C00)
    public static let attentionSoft = Color(hex: 0xFBF3E0)
    public static let idle = Color(hex: 0xB5B5B0)
    public static let danger = Color(hex: 0xD23F31)
    public static let dangerSoft = Color(hex: 0xFDECEB)
    public static let accent = Color(hex: 0x111111)

    // diff
    public static let diffAdd = Color(hex: 0x1F7A4D)
    public static let diffAddBg = Color(hex: 0xEEFAF3)
    public static let diffDel = Color(hex: 0xC23A2C)
    public static let diffDelBg = Color(hex: 0xFDEEEC)
}

/// `--fs-*`: the type scale.
public enum FontSize {
    public static let fs11: CGFloat = 11
    public static let fs12: CGFloat = 12
    public static let fs13: CGFloat = 13
    public static let fs14: CGFloat = 14
    public static let fs15: CGFloat = 15
    public static let fs16: CGFloat = 16
    public static let fs17: CGFloat = 17
    public static let fs22: CGFloat = 22
    public static let fs30: CGFloat = 30
}

/// `--sp-*`: the spacing scale.
public enum Space {
    public static let sp1: CGFloat = 4
    public static let sp2: CGFloat = 8
    public static let sp3: CGFloat = 12
    public static let sp4: CGFloat = 16
    public static let sp5: CGFloat = 20
    public static let sp6: CGFloat = 24
    public static let sp8: CGFloat = 32
    public static let sp10: CGFloat = 40
}

/// `--r-*`: corner radii. A pill is a capsule at any height.
public enum Radius {
    public static let sm: CGFloat = 8
    public static let md: CGFloat = 12
    public static let lg: CGFloat = 16
    public static let pill: CGFloat = 999
}

/// `--row-h*`: every device and session row is this tall, whichever list it sits
/// in. A narrow screen stacks the row's last line, and a phone stacks the device
/// meta as well, so each stacked form has a height of its own.
public enum RowHeight {
    public static let rowH: CGFloat = 64
    public static let rowHStacked: CGFloat = 96
    public static let rowHStackedTall: CGFloat = 108
    /// Three lines of text — a title and two meta lines — with the same breathing room.
    public static let rowHThree: CGFloat = 84
    /// A settings row is a floor, not a clip: a sentence that wraps makes its row
    /// taller and moves nothing at the widths where it does not wrap.
    public static let rowHSetting: CGFloat = 56
}

/// `--sidebar-w`, `--header-h`, `--content-max`.
public enum LayoutSize {
    public static let sidebarW: CGFloat = 264
    public static let headerH: CGFloat = 60
    public static let contentMax: CGFloat = 1080
}

/// `--z-*`. A popover is always drawn above every overlay, because the thing
/// that opened it may itself be inside a modal or the drawer.
public enum ZLayer {
    public static let sticky: Double = 20
    public static let overlay: Double = 60
    public static let popover: Double = 100
}

/// `--ease`, `--dur-fast`, `--dur`: one easing curve, two durations, and none at
/// all under Reduce Motion (the web's `prefers-reduced-motion` rule).
public enum Motion {
    public static let durFast: Double = 0.12
    public static let dur: Double = 0.2

    /// `cubic-bezier(0.22, 0.61, 0.36, 1)` for `duration` seconds, or nothing
    /// when the reader asked for reduced motion.
    public static func ease(_ duration: Double, reduceMotion: Bool = false) -> Animation? {
        reduceMotion ? nil : .timingCurve(0.22, 0.61, 0.36, 1, duration: duration)
    }
}

extension Color {
    /// An sRGB colour written the way the stylesheet writes it: `0xF5F5F4`.
    public init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }

    /// `rgba(r, g, b, a)`.
    public init(rgb: (Int, Int, Int), opacity: Double) {
        self.init(.sRGB, red: Double(rgb.0) / 255, green: Double(rgb.1) / 255,
                  blue: Double(rgb.2) / 255, opacity: opacity)
    }
}

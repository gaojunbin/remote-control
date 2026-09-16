import CoreGraphics
import Foundation

/// How far a `GrowingTextField` is scrolled, for a UI test to read.
///
/// A test cannot ask a text view where it is scrolled to: `value` on one
/// reports the whole draft whether the field is showing its first line or its
/// last. So behind `--field-scroll-probe` the field carries one more element,
/// `<identifier>.scroll`, whose label is "<offset>/<end>" in whole points —
/// how far the text has moved inside the field, and how far it could move.
/// Equal numbers mean the last line is the one in view.
///
/// Nothing but a UI test passes the argument, and a release build ignores it,
/// so the element exists only where a test put it.
enum FieldScrollProbe {
    static let isOn: Bool = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("--field-scroll-probe")
        #else
        return false
        #endif
    }()

    static func report(offset: CGFloat, end: CGFloat) -> String {
        "\(Int(offset.rounded()))/\(Int(max(0, end).rounded()))"
    }
}

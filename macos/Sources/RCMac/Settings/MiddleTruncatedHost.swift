import SwiftUI

/// `MiddleTruncated` in `IdentityHeader.tsx`: a host that does not fit loses
/// its middle, not its end — `rc.example…:8443` still says which port. Two
/// pieces and no measuring: the tail is fixed and the head is what the line
/// can spare.
struct MiddleTruncatedHost: View {
    let text: String

    /// How much of a long host is kept at its end, so the port always survives.
    static let tail = 8

    var body: some View {
        let parts = Self.split(text)
        SettingsShrinkRow(spacing: 0, shrinking: 0) {
            Text(parts.head).lineLimit(1).truncationMode(.tail).css(FontSize.fs13, lineHeight: 1.4)
            Text(parts.tail).lineLimit(1).css(FontSize.fs13, lineHeight: 1.4)
        }
        .help(text)
    }

    static func split(_ text: String) -> (head: String, tail: String) {
        let cut = max(text.count - tail, 0)
        return (String(text.prefix(cut)), String(text.dropFirst(cut)))
    }
}

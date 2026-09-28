import RCCore
import SwiftUI

/// The working directory: its label with Browse… at the trailing edge, the path
/// in a tall mono field that says whether it exists, and up to four recent
/// directories under it.
struct DirectoryField: View {
    let form: NewSessionForm
    let device: Device?
    let browse: () -> Void

    var body: some View {
        let probe = form.probe.status(deviceID: form.deviceID, path: form.cwd)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: Space.sp3) {
                FieldLabel(S.newSession.workingDirectory)
                BrowseLink(action: browse).disabled(form.deviceID == nil)
            }
            SizedField(text: Binding(get: { form.cwd }, set: { form.setPath($0) }), mono: true,
                       fontSize: FontSize.fs15, height: 46, trailingPadding: 74)
                .overlay(alignment: .trailing) {
                    if let word = Self.word(probe.status) {
                        Text(word)
                            .css(FontSize.fs12)
                            .foregroundStyle(probe.status == .missing ? Palette.danger : Palette.inkSecondary)
                            .padding(.trailing, Space.sp4)
                            .allowsHitTesting(false)
                    }
                }
                .accessibilityLabel(S.newSession.workingDirectory)
            if !form.recent.isEmpty {
                VStack(spacing: 0) {
                    ForEach(form.recent.prefix(4)) { entry in
                        RecentRow(entry: entry) { form.setPath(entry.path) }
                    }
                }
                .padding(.top, Space.sp2)
            }
        }
    }

    static func word(_ status: DirectoryProbe.Status) -> String? {
        switch status {
        case .exists: S.newSession.dirExists
        case .missing: S.newSession.dirMissing
        case .checking: S.newSession.dirChecking
        case .idle: nil
        }
    }
}

/// `.label-row .link-btn`: Browse…, in the secondary ink, underlined in the ink
/// under the pointer.
private struct BrowseLink: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(S.newSession.browse).css(FontSize.fs13)
        }
        .buttonStyle(BrowseLinkStyle())
    }
}

private struct BrowseLinkStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { BrowseLinkBody(configuration: configuration) }
}

private struct BrowseLinkBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && isHovered
        configuration.label
            .underline(lit)
            .foregroundStyle(lit ? Palette.ink : Palette.inkSecondary)
            .contentShape(Rectangle())
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
    }
}

/// One recent directory: the path in mono and when it was last used, a tint
/// under the pointer.
private struct RecentRow: View {
    let entry: RecentDirectory
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sp3) {
                Text(Format.tildePath(entry.path))
                    .lineLimit(1)
                    .css(FontSize.fs13, mono: true)
                Spacer(minLength: 0)
                Text(Format.relativeAgo(entry.lastUsed))
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkSecondary)
                    .fixedSize()
            }
            .padding(.vertical, 9)
            .padding(.horizontal, Space.sp2)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular)
                .fill(isHovered ? Palette.surfaceHover : Color.clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .pointerStyle(.link)
    }
}

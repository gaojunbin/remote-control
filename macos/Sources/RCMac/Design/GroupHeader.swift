import SwiftUI

/// `.group-head`: a group caption that doubles as its disclosure control. The
/// chevron takes the gutter, so the label keeps the indent a plain caption
/// has; the pointer turns it the secondary ink.
struct GroupHeadStyle: ButtonStyle {
    let leading: CGFloat
    let spacing: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        GroupHeadBody(configuration: configuration, leading: leading, spacing: spacing)
    }
}

private struct GroupHeadBody: View {
    let configuration: ButtonStyleConfiguration
    let leading: CGFloat
    let spacing: CGFloat
    @State private var isHovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: spacing) { configuration.label }
            .padding(.vertical, Space.sp1)
            .padding(.leading, leading)
            .padding(.trailing, Space.sp2)
            .contentShape(Rectangle())
            .modifier(HoverInk(isHovered: isHovered))
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
    }
}

/// The header's own ink until the pointer is over it.
private struct HoverInk: ViewModifier {
    let isHovered: Bool

    func body(content: Content) -> some View {
        if isHovered { content.foregroundStyle(Palette.inkSecondary) } else { content }
    }
}

/// `.group-chevron`: a right chevron that turns a quarter when the group opens.
struct GroupChevron: View {
    let size: CGFloat
    let expanded: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Icon(.chevronRight, size: size)
            .rotationEffect(.degrees(expanded ? 90 : 0))
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: expanded)
    }
}

/// `web/src/components/GroupHeader.tsx` `DeviceGroupHeader`: the header of one
/// device's group, which the Sessions page and the chat sidebar share so the
/// two lists read the same way. The name is printed exactly as the device
/// reports it — never re-cased — at 14 points, 600, in ink.
public struct DeviceGroupHeader: View {
    let name: String
    let online: Bool
    let expanded: Bool
    let leading: CGFloat
    let onToggle: () -> Void

    /// `leading` is the header's left padding: none in `.group-head`, which the
    /// chat sidebar raises to 8.
    public init(name: String, online: Bool, expanded: Bool, leading: CGFloat = 0,
                onToggle: @escaping () -> Void) {
        self.name = name
        self.online = online
        self.expanded = expanded
        self.leading = leading
        self.onToggle = onToggle
    }

    public var body: some View {
        Button(action: onToggle) {
            GroupChevron(size: 13, expanded: expanded)
            Text(name)
                .lineLimit(1)
                .truncationMode(.tail)
                .css(FontSize.fs14, weight: .semibold, tracking: -0.01)
            OnlineDot(online: online)
        }
        .buttonStyle(GroupHeadStyle(leading: leading, spacing: Space.sp2))
        .foregroundStyle(Palette.ink)
    }
}

/// `ArchiveGroupHeader`: one device's Archive, folded shut under its active
/// rows. It takes the ink of wherever it is placed, as the web's button
/// inherits it; `leading` is 12 in `.archive-head`.
public struct ArchiveGroupHeader: View {
    let count: Int
    let expanded: Bool
    let leading: CGFloat
    let fontSize: CGFloat
    let onToggle: () -> Void

    /// `fontSize` is the size of the type around it, which the web's button
    /// inherits: 14 on the page body.
    public init(count: Int, expanded: Bool, leading: CGFloat = Space.sp3, fontSize: CGFloat = FontSize.fs14,
                onToggle: @escaping () -> Void) {
        self.count = count
        self.expanded = expanded
        self.leading = leading
        self.fontSize = fontSize
        self.onToggle = onToggle
    }

    public var body: some View {
        Button(action: onToggle) {
            GroupChevron(size: 12, expanded: expanded)
            Text(S.sessions.archiveGroup(count)).css(fontSize)
        }
        .buttonStyle(GroupHeadStyle(leading: leading, spacing: Space.sp1))
    }
}

import RCCore
import SwiftUI

/// One device's group in the chat sidebar: the same header as the Sessions
/// page, raised 8 points into the list, its rows, and its Archive indented
/// under them.
struct SidebarGroup: View {
    let group: DeviceGroup
    let activeKey: String
    @Environment(MacAppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DeviceGroupHeader(name: group.name, online: group.online, expanded: !group.collapsed,
                              leading: Space.sp2) {
                model.sessions.toggleCollapsed(group.id)
            }
            // Its margin collapses into the gap between groups when nothing follows.
            .padding(.bottom, group.collapsed ? 0 : Space.sp1)
            if !group.collapsed {
                ForEach(group.active) { session in item(session) }
                if !group.archive.isEmpty {
                    // 8 above it, which the header's 4 collapses into when no
                    // row stands between them.
                    ArchiveGroupHeader(count: group.archive.count, expanded: group.archiveExpanded,
                                       leading: Space.sp4) {
                        model.sessions.toggleArchive(group.id)
                    }
                    .padding(.top, group.active.isEmpty ? Space.sp1 : Space.sp2)
                    .padding(.bottom, group.archiveExpanded ? Space.sp1 : 0)
                    if group.archiveExpanded {
                        ForEach(group.archive) { session in item(session) }
                    }
                }
            }
        }
    }

    private func item(_ session: Session) -> some View {
        SidebarItem(session: session, online: group.online, active: session.id == activeKey,
                    unseen: model.showsUnseenDot(session)) {
            model.router.go(.chat(deviceId: session.deviceID, sessionId: session.sessionID))
        }
    }
}

/// `.sidebar-item`: the status dot, the title, and under it the folder and the
/// time, whatever the session is doing — the dot carries the state
/// (`docs/DESIGN.md` § "The session row says where it came from"). The row the
/// conversation shows is one step further into the ink. Its leading padding is
/// the gutter a red dot sits in (A47), kept by every row so the dot moves
/// nothing when it comes or goes.
struct SidebarItem: View {
    let session: Session
    let online: Bool
    let active: Bool
    /// A47: whether the row draws the red dot.
    let unseen: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    /// From the row's edge to the title: the gutter, the status dot and the
    /// gap after it.
    private static let titleReach = Space.sp5 + 7 + Space.sp2

    var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sp2) {
                StatusDot(state: session.state, control: session.control, online: online)
                VStack(alignment: .leading, spacing: 1) {
                    Text(S.sessionTitle(session))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .css(FontSize.fs14, weight: .semibold, lineHeight: 1.4, tracking: -0.01)
                        .unseenTitle(unseen, reach: Self.titleReach, gutter: Space.sp5)
                    Text("\(Format.baseName(session.cwd)) · \(Format.relativeTime(session.updatedAt))")
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .css(FontSize.fs12, lineHeight: 1.45)
                        .foregroundStyle(Palette.inkSecondary)
                }
            }
            .padding(.leading, Space.sp5)
            .padding(.trailing, Space.sp2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: 48)
            .background(RoundedRectangle(cornerRadius: 10, style: .circular).fill(fill))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .pointerStyle(.link)
        .accessibilityValue(unseen ? S.sessions.unseen : "")
        .onHover { isHovered = $0 }
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
    }

    private var fill: Color {
        if active { return Palette.hoverSelected }
        return isHovered ? Palette.hover : Color.clear
    }
}

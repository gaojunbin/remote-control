import RCCore
import SwiftUI

/// The conversation page's session list — `web/src/features/chat/Sidebar.tsx`:
/// the brand with New session beside it, a search of its own, the sessions
/// grouped by device with each device's Archive folded under it, and a footer
/// saying how many devices there are and how many sessions wait on the reader.
/// The groups follow the one rule every list follows, with the agent filter
/// and the folds the Sessions page shares; the search is this list's alone.
/// New session here starts on the conversation's own device.
public struct SessionSidebar: View {
    let deviceId: String
    let sessionId: String
    @Environment(MacAppModel.self) private var model
    @State private var query = ""
    @State private var creating: NewSessionForm?

    public init(deviceId: String, sessionId: String) {
        self.deviceId = deviceId
        self.sessionId = sessionId
    }

    public var body: some View {
        let sessions = model.connection.sessions
        let groups = SessionLayout.build(sessions: sessions, devices: model.connection.devices,
                                         agentFilter: model.sessions.agentFilter, query: query,
                                         collapsedDevices: model.sessions.collapsedDevices,
                                         archiveExpanded: model.sessions.expandedArchives)
        VStack(spacing: 0) {
            SidebarHead(onNewSession: openNewSession)
            ListSearchField(text: $query, placeholder: S.chat.searchPlaceholder, iconSize: 14, height: 32,
                            maxWidth: nil)
                .accessibilityLabel(S.chat.searchPlaceholder)
                .padding(.horizontal, Space.sp3)
                .padding(.bottom, Space.sp3)
            ThinScrollView {
                VStack(alignment: .leading, spacing: Space.sp5) {
                    ForEach(groups) { group in
                        SidebarGroup(group: group, activeKey: "\(deviceId)/\(sessionId)")
                    }
                }
                .padding(.top, Space.sp1)
                .padding(.horizontal, Space.sp2)
                .padding(.bottom, Space.sp3)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)
            .accessibilityLabel(S.nav.sessions)
            Text(S.chat.sidebarFooter(model.connection.devices.count,
                                      SessionLayout.countWaiting(SessionLayout.unarchived(sessions))))
                .css(FontSize.fs12)
                .foregroundStyle(Palette.inkTertiary)
                .padding(.vertical, Space.sp3)
                .padding(.horizontal, Space.sp4)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: LayoutSize.sidebarW)
        .frame(maxHeight: .infinity)
        .background(Palette.canvas)
        .modifier(NewSessionDrawer(form: $creating))
    }

    /// The web presets the drawer to the conversation's device only when that
    /// conversation is there to have one.
    private func openNewSession() {
        let preset = model.connection.session(deviceID: deviceId, sessionID: sessionId) == nil ? nil : deviceId
        creating = NewSessionForm(devices: DeviceOrder.online(model.connection.devices), preset: preset)
    }
}

/// `.sidebar-head`: the brand, which goes to the Sessions page, and New
/// session. On the Mac this is the window's top strip where the sidebar stands,
/// so it drags the window and starts after the traffic lights; the wordmark
/// gives way before the mark does when there is no room for both.
private struct SidebarHead: View {
    let onNewSession: () -> Void
    @Environment(MacAppModel.self) private var model
    @Environment(\.trafficLightInset) private var trafficLightInset

    var body: some View {
        HStack(spacing: Space.sp2) {
            Button { model.router.go(.sessions) } label: {
                ViewThatFits(in: .horizontal) {
                    brand(wordmark: true)
                    brand(wordmark: false)
                }
            }
            .buttonStyle(.plain)
            .pointerStyle(.link)
            .accessibilityLabel(S.productName)
            Spacer(minLength: 0)
            IconBtn(.plus, size: 16, label: S.chat.newSession, action: onNewSession)
        }
        .padding(.top, Space.sp4)
        .padding(.trailing, Space.sp3)
        .padding(.bottom, Space.sp3)
        .padding(.leading, max(Space.sp4, trafficLightInset))
        .background { WindowStrip() }
    }

    private func brand(wordmark: Bool) -> some View {
        HStack(spacing: Space.sp2) {
            Mark(size: 18)
            if wordmark {
                Text(S.productName)
                    .lineLimit(1)
                    .css(FontSize.fs15, weight: .semibold, tracking: -0.01)
                    .fixedSize()
            }
        }
    }
}

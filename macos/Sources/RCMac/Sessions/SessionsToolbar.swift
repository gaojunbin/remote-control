import RCCore
import SwiftUI

/// `.sessions-toolbar`: the search, then the agent filter — only when the list
/// holds more than one agent — and the device filter. At 640 and narrower the
/// search takes a line of its own and the two filters wrap under it.
struct SessionsToolbar: View {
    @Binding var query: String
    @Binding var agentFilter: String?
    @Binding var deviceFilter: String?
    let agents: [String]
    /// In name order, as the web's device store keeps them.
    let devices: [Device]
    var openFilter: Filter?
    @Environment(\.layoutClass) private var layout

    /// Which filter a preview stage draws open.
    enum Filter {
        case agent
        case device
    }

    /// The row id the device filter uses for "no filter".
    private static let all = "__all__"

    var body: some View {
        if layout.maxWidth640 {
            VStack(alignment: .leading, spacing: Space.sp2) {
                search(maxWidth: nil)
                HStack(spacing: Space.sp2) { filters }
            }
        } else {
            HStack(spacing: Space.sp2) {
                search(maxWidth: 320)
                filters
                Spacer(minLength: 0)
            }
        }
    }

    private func search(maxWidth: CGFloat?) -> some View {
        ListSearchField(text: $query, placeholder: S.sessions.searchPlaceholder, maxWidth: maxWidth)
            .accessibilityLabel(S.sessions.searchPlaceholder)
    }

    @ViewBuilder private var filters: some View {
        if agents.count > 1 {
            AgentFilterMenu(agents: agents, selection: $agentFilter, initiallyOpen: openFilter == .agent)
        }
        // The web marks no row while nothing is filtered: its value is null,
        // which no option carries.
        SelectMenu(options: [MenuOption(id: Self.all, label: S.sessions.allDevices)]
                    + devices.map { MenuOption(id: $0.deviceID, label: $0.name) },
                   value: deviceFilter, ariaLabel: S.sessions.allDevices, align: .end,
                   initiallyOpen: openFilter == .device,
                   onSelect: { deviceFilter = $0 == Self.all ? nil : $0 }) {
            Text(deviceFilter.map { id in devices.first { $0.deviceID == id }?.name ?? id } ?? S.sessions.allDevices)
                .css(FontSize.fs13)
        }
    }
}

import SwiftUI

/// `AgentOption.tsx`: one agent in a list that has room for its name — the
/// logo, then the name (`docs/DESIGN.md` § "Agents"). The logo is one em of the
/// line it stands in.
struct AgentOptionLabel: View {
    let agent: String
    let size: CGFloat

    var body: some View {
        HStack(spacing: Space.sp2) {
            AgentLogo(agent: agent, size: size)
            Text(S.agentLabel(agent)).css(size)
        }
    }
}

/// The Sessions page's agent filter: "All agents" and the agents actually
/// present, each with its logo and its name. The choice lives in the store both
/// lists read, so the page and the chat sidebar never disagree.
struct AgentFilterMenu: View {
    let agents: [String]
    @Binding var selection: String?
    var initiallyOpen = false

    var body: some View {
        Popover(align: .end, ariaLabel: S.sessions.agentFilter, initiallyOpen: initiallyOpen) {
            if let selection {
                AgentOptionLabel(agent: selection, size: FontSize.fs13)
            } else {
                Text(S.sessions.allAgents).css(FontSize.fs13)
            }
        } content: { close in
            MenuList {
                row(nil, close: close) { Text(S.sessions.allAgents).css(FontSize.fs14) }
                ForEach(agents, id: \.self) { agent in
                    // The browser sets the logo and the name as an inline box
                    // on the label's baseline, which deepens its line by a
                    // point and a half.
                    row(agent, close: close) {
                        AgentOptionLabel(agent: agent, size: FontSize.fs14).padding(.bottom, 1.5)
                    }
                }
            }
            .accessibilityLabel(S.sessions.agentFilter)
        }
    }

    /// No filter is the "All agents" row, which the menu then marks.
    private func row<Label: View>(_ agent: String?, close: @escaping () -> Void,
                                  @ViewBuilder label: () -> Label) -> some View {
        Button {
            selection = agent
            close()
        } label: {
            label()
        }
        .buttonStyle(MenuItemStyle(selected: selection == agent))
    }
}

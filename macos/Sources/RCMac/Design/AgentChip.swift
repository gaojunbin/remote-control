import SwiftUI

/// `.agent-chip`: which agent a session runs. One quiet tint for every agent,
/// no border, one per row: the logo and the name are what tell them apart,
/// never a colour per vendor.
public struct AgentChip: View {
    let agent: String

    public init(agent: String) { self.agent = agent }

    public var body: some View {
        HStack(spacing: 5) {
            AgentLogo(agent: agent, size: FontSize.fs11)
            Text(S.agentLabel(agent)).lineLimit(1).css(FontSize.fs11, weight: .medium)
        }
        .fixedSize()
        .padding(.horizontal, 7)
        .frame(height: 18)
        .foregroundStyle(Palette.inkSecondary)
        .background(Capsule().fill(Palette.surfaceMuted))
    }
}

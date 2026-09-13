import SwiftUI
import RCCore

/// The agent's own logo, monochrome, in the square the words beside it stand in.
///
/// `docs/DESIGN.md` § "Agents": the shape tells the agents apart, never a colour
/// per vendor, so every mark is drawn in the ink colour on the same quiet tint
/// the chips already share. The vectors are the vendors' published marks, kept
/// as template images in `Resources/Agents.xcassets`, so one asset serves both
/// appearances and every size. An agent this build carries no vector for is
/// marked by the first letter of its id in the same box.
public struct AgentLogo: View {
    private let agent: String
    private let size: CGFloat
    private let tint: Color?

    /// Dynamic Type carries the box along with the line it matches: the logo
    /// grows exactly as the footnote beside it does.
    @ScaledMetric(relativeTo: .footnote) private var scale: CGFloat = 1

    /// - Parameter tint: the colour to draw in, or nil to inherit whatever the
    ///   caller has already set — a selected segment, a menu row, a chip.
    public init(agent: String, size: CGFloat = Theme.Mark.inline, tint: Color? = Theme.ink) {
        self.agent = agent
        self.size = size
        self.tint = tint
    }

    public var body: some View {
        mark
            .frame(width: side, height: side)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var mark: some View {
        let shape = Group {
            if let image = AgentLogo.image(agent) {
                image.renderingMode(.template).resizable().scaledToFit()
            } else {
                Text(AgentLabel.initial(agent))
                    .font(.system(size: side, weight: .semibold))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
        }
        if let tint {
            shape.foregroundStyle(tint)
        } else {
            shape
        }
    }

    private var side: CGFloat { size * scale }

    /// The vector alone, for the two controls UIKit draws rather than SwiftUI —
    /// a segmented `Picker` and a `Menu` row — which take an `Image` and a
    /// `Text` and drop every other view. Nil for an agent with no vector.
    public static func image(_ agent: String) -> Image? {
        guard ["claude", "codex", "grok", "pi"].contains(agent) else { return nil }
        return Image("agent-\(agent)", bundle: .module)
    }
}

#Preview("Agent logos") {
    VStack(alignment: .leading, spacing: Theme.Space.medium) {
        ForEach(["claude", "codex", "grok", "pi", "aider"], id: \.self) { agent in
            HStack(spacing: Theme.Space.tight) {
                AgentLogo(agent: agent)
                Text(AgentLabel.name(agent)).font(Theme.Text.meta)
            }
        }
        HStack(spacing: Theme.Space.medium) {
            ForEach(["claude", "codex", "grok", "pi"], id: \.self) { agent in
                AgentLogo(agent: agent, size: Theme.Mark.control)
            }
        }
    }
    .padding()
}

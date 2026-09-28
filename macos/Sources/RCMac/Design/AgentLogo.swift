import SwiftUI

/// `web/src/components/AgentLogo.tsx`: the mark of an agent, its own logo
/// drawn monochrome in the ink of the text beside it. The shape is what tells
/// the agents apart, never a colour per vendor (`docs/DESIGN.md` § "Agents").
///
/// The box is one em square — `size` is the font size of the line it stands
/// in — so a logo reads as the same size as the name it stands beside or
/// replaces. An agent nobody knows has no vector: it is marked by the first
/// letter of its id, in the same box.
public struct AgentLogo: View {
    let agent: String
    let size: CGFloat

    public init(agent: String, size: CGFloat) {
        self.agent = agent
        self.size = size
    }

    public var body: some View {
        Group {
            if let logo = AgentLogoArt.logos[agent] {
                ZStack {
                    ForEach(logo.paths.indices, id: \.self) { index in
                        let path = logo.paths[index]
                        ViewBoxShape(path: path.path, viewBox: logo.viewBox)
                            .fill(style: FillStyle(eoFill: path.evenOdd))
                    }
                }
            } else {
                Text(agent.prefix(1).uppercased())
                    .font(.web(size: size, weight: .semibold))
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A path drawn in a viewBox, scaled uniformly into whatever square it is given.
struct ViewBoxShape: Shape {
    let path: Path
    let viewBox: CGRect

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width / viewBox.width, rect.height / viewBox.height)
        let dx = rect.minX + (rect.width - viewBox.width * scale) / 2 - viewBox.minX * scale
        let dy = rect.minY + (rect.height - viewBox.height * scale) / 2 - viewBox.minY * scale
        return path.applying(CGAffineTransform(a: scale, b: 0, c: 0, d: scale, tx: dx, ty: dy))
    }
}

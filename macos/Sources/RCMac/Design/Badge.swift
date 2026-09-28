import SwiftUI

/// `.badge`, `.badge.warn` and `.badge.error`: a 20-point tinted pill of
/// 12-point text. Nothing is outlined (`docs/DESIGN.md` § "Chips and badges").
public struct Badge: View {
    public enum Tone: Sendable { case neutral, warn, error }

    let text: String
    let tone: Tone

    public init(_ text: String, tone: Tone = .neutral) {
        self.text = text
        self.tone = tone
    }

    public var body: some View {
        Text(text)
            .lineLimit(1)
            .css(FontSize.fs12)
            .fixedSize()
            .padding(.horizontal, 8)
            .frame(height: 20)
            .foregroundStyle(foreground)
            .background(Capsule().fill(background))
    }

    private var foreground: Color {
        switch tone {
        case .neutral: Palette.inkSecondary
        case .warn: Color(hex: 0x775707)
        case .error: Color(hex: 0x9C2C21)
        }
    }

    private var background: Color {
        switch tone {
        case .neutral: Palette.surfaceMuted
        case .warn: Palette.attentionSoft
        case .error: Palette.dangerSoft
        }
    }
}

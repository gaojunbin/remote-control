import SwiftUI

extension View {
    /// `web/src/styles/base.css`: the canvas behind everything, ink text in the
    /// system face at 14 px, and ink as the tint every control inherits.
    public func webBase() -> some View {
        foregroundStyle(Palette.ink)
            .font(.web(size: FontSize.fs14))
            .tint(Palette.ink)
            .background(Palette.canvas)
    }

    /// `:focus-visible`: a 2 px ink outline 2 px outside the control, following
    /// the 4 px radius the rule gives it, drawn only while `isVisible`.
    public func focusOutline(_ isVisible: Bool, cornerRadius: CGFloat = 4) -> some View {
        overlay {
            if isVisible {
                RoundedRectangle(cornerRadius: cornerRadius + 4, style: .circular)
                    .strokeBorder(Palette.ink, lineWidth: 2)
                    .padding(-4)
                    .allowsHitTesting(false)
            }
        }
    }
}

/// `::selection`: the tint text selection takes wherever the app draws its own.
public enum Selection {
    public static let background = Color(rgb: (17, 17, 17), opacity: 0.12)
}

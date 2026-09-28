import SwiftUI

extension View {
    /// `.surface`: a list of rows on one soft surface — white, a 16-point
    /// radius, lifted off the canvas by `--shadow-soft` alone and clipping what
    /// it holds. Rows inside it are parted by spacing, never a hairline.
    public func surface(cornerRadius: CGFloat = Radius.lg) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .circular)
        return background(Palette.surface)
            .clipShape(shape)
            .boxShadow(Shadow.soft, in: shape)
    }

    /// `.card`: the same surface without the clip, for a block that is not a list.
    public func card(cornerRadius: CGFloat = Radius.lg, shadow: BoxShadow = Shadow.soft) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .circular)
        return background(shape.fill(Palette.surface))
            .boxShadow(shadow, in: shape)
    }
}

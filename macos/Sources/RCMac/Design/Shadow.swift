import SwiftUI

/// A CSS `box-shadow`: one or more layers, each drawn on its own, as the browser
/// draws them. `--shadow-*` in `tokens.css`.
public struct BoxShadow: Sendable {
    public struct Layer: Sendable {
        public let color: Color
        public let x: CGFloat
        public let y: CGFloat
        /// The CSS blur radius. Core Animation's shadow radius is half of it:
        /// measured against Chrome, `radius: blur / 2` draws the same falloff.
        public let blur: CGFloat
    }

    public let layers: [Layer]
}

public enum Shadow {
    /// `--shadow-1`
    public static let one = BoxShadow(layers: [
        .init(color: Color(rgb: (0, 0, 0), opacity: 0.06), x: 0, y: 1, blur: 2)
    ])
    /// `--shadow-soft`: a group surface is lifted off the canvas by this alone — never a border.
    public static let soft = BoxShadow(layers: [
        .init(color: Color(rgb: (24, 24, 22), opacity: 0.04), x: 0, y: 1, blur: 2),
        .init(color: Color(rgb: (24, 24, 22), opacity: 0.06), x: 0, y: 0, blur: 1)
    ])
    /// `--shadow-pop`: a popover panel.
    public static let pop = BoxShadow(layers: [
        .init(color: Color(rgb: (0, 0, 0), opacity: 0.1), x: 0, y: 8, blur: 24)
    ])
    /// `--shadow-modal`: a modal and the drawer.
    public static let modal = BoxShadow(layers: [
        .init(color: Color(rgb: (0, 0, 0), opacity: 0.12), x: 0, y: 24, blur: 60)
    ])
}

extension View {
    /// The shadow of `shape` drawn under this view, one layer at a time. Each
    /// layer is its own copy of the shape, so two layers never shadow each other
    /// the way two stacked `.shadow` modifiers would.
    public func boxShadow<S: Shape>(_ shadow: BoxShadow, in shape: S) -> some View {
        background {
            ZStack {
                ForEach(shadow.layers.indices, id: \.self) { index in
                    let layer = shadow.layers[index]
                    shape.fill(Color.white)
                        .shadow(color: layer.color, radius: layer.blur / 2, x: layer.x, y: layer.y)
                }
            }
        }
    }
}

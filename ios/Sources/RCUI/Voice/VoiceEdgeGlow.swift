import SwiftUI

/// The light that runs around the edge of the display while dictation listens.
///
/// It is a stroke, not a fill: a soft multi-colour gradient following the
/// screen's own rounded corners, breathing with the measured input level. On a
/// light page a low-alpha, heavily blurred stroke reads as light spilling in
/// from outside the screen rather than as a coloured border.
struct VoiceEdgeGlow: View {
    let active: Bool
    let level: Double
    let reduceMotion: Bool
    /// The display's corner radius, so the light follows the glass.
    var cornerRadius: CGFloat = DisplayCorner.fallbackRadius
    @State private var energy = 0.0

    var body: some View {
        Group {
            if active {
                VoiceGlowField(energy: energy, reduceMotion: reduceMotion, cornerRadius: cornerRadius)
            }
        }
        .onChange(of: level, initial: true) { _, value in
            let next = value.isFinite ? min(1, max(0, value)) : 0
            guard !reduceMotion else { energy = next; return }
            // Rising fast and falling slow is what makes it read as breathing
            // rather than as a meter.
            let duration = next > energy ? 0.14 : 0.32
            withAnimation(.easeOut(duration: duration)) { energy = next }
        }
    }
}

struct VoiceGlowField: View {
    let energy: Double
    let reduceMotion: Bool
    let cornerRadius: CGFloat
    @State private var pulse = false

    private let spectrum = Gradient(stops: [
        .init(color: Color(red: 1.0, green: 0.25, blue: 0.48), location: 0),
        .init(color: Color(red: 1.0, green: 0.18, blue: 0.42), location: 0.12),
        .init(color: Color(red: 0.84, green: 0.16, blue: 0.90), location: 0.28),
        .init(color: Color(red: 0.40, green: 0.36, blue: 1.0), location: 0.46),
        .init(color: Color(red: 0.30, green: 0.48, blue: 1.0), location: 0.60),
        .init(color: Color(red: 0.70, green: 0.24, blue: 0.98), location: 0.76),
        .init(color: Color(red: 1.0, green: 0.65, blue: 0.24), location: 0.94),
        .init(color: Color(red: 1.0, green: 0.25, blue: 0.48), location: 1)
    ])

    var body: some View {
        let input = energy.isFinite ? min(1, max(0, energy)) : 0
        // Reduce Motion gets a ring that never moves with the voice: one fixed
        // width, and a slow opacity pulse so it is still clearly listening.
        let expansion = reduceMotion ? 0.3 : input
        let emphasis = reduceMotion ? 0.0 : input
        let gradient = AngularGradient(gradient: spectrum, center: .center, angle: .degrees(-90))
        GeometryReader { geometry in
            // Three strokes on the same path: a thin bright rim, a soft skirt
            // and a wide low halo. The page has to stay readable underneath, so
            // the halo is the only wide one and it is the faintest by far.
            ZStack {
                lightSource(gradient, width: 30 + expansion * 16, blur: 20 + expansion * 6)
                    .opacity(0.13 + emphasis * 0.09)
                lightSource(gradient, width: 11 + expansion * 6, blur: 7 + expansion * 2)
                    .opacity(0.26 + emphasis * 0.12)
                lightSource(gradient, width: 3.5 + expansion * 2, blur: 2.5)
                    .opacity(0.44 + emphasis * 0.16)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .scaleEffect(x: 1 - expansion * 0.010, y: 1 - expansion * 0.004)
            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
        .clipped()
        .opacity(reduceMotion ? (pulse ? 0.9 : 0.55) : 1)
        .animation(reduceMotion ? .easeInOut(duration: 2.4).repeatForever(autoreverses: true) : nil,
                   value: pulse)
        .onAppear { if reduceMotion { pulse = true } }
    }

    private func lightSource(_ gradient: AngularGradient, width: Double, blur: Double) -> some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .stroke(gradient, lineWidth: width)
            .blur(radius: blur)
    }
}

/// How round the display's own corners are.
///
/// The exact figure is private to UIKit. A device with a home indicator has a
/// rounded display and a radius close to this; anything squarer reads correctly
/// with a small one, and the light is blurred far past the error either way.
public enum DisplayCorner {
    public static let fallbackRadius: CGFloat = 55
    public static let squareRadius: CGFloat = 14

    public static func radius(bottomSafeArea: CGFloat) -> CGFloat {
        bottomSafeArea > 0 ? fallbackRadius : squareRadius
    }
}

import SwiftUI

struct VoiceEdgeGlow: View {
    let active: Bool
    let level: Double
    let reduceMotion: Bool
    @State private var energy = 0.0

    var body: some View {
        Group {
            if active { VoiceGlowField(energy: energy, reduceMotion: reduceMotion) }
        }
        .onChange(of: level, initial: true) { _, value in
            let next = value.isFinite ? min(1, max(0, value)) : 0
            let duration = next > energy ? 0.14 : 0.32
            withAnimation(.easeOut(duration: duration)) { energy = next }
        }
    }
}

struct VoiceGlowField: View {
    let energy: Double
    let reduceMotion: Bool
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
        let expansion = reduceMotion ? 0.25 : input
        let emphasis = reduceMotion ? input * 0.3 : input
        let gradient = AngularGradient(gradient: spectrum, center: .center, angle: .degrees(-90))
        GeometryReader { geometry in
            ZStack {
                lightSource(gradient, width: 40 + expansion * 24, blur: 28 + expansion * 6)
                    .opacity(0.30 + emphasis * 0.18)
                lightSource(gradient, width: 24 + expansion * 16, blur: 14 + expansion * 4)
                    .opacity(0.55 + emphasis * 0.20)
                lightSource(gradient, width: 12 + expansion * 8, blur: 8 + expansion * 3)
                    .opacity(0.65 + emphasis * 0.15)
            }
            .frame(width: geometry.size.width + 12, height: geometry.size.height + 12)
            .scaleEffect(x: 1 - expansion * 0.018, y: 1 - expansion * 0.008)
            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
        .clipped()
    }

    private func lightSource(_ gradient: AngularGradient, width: Double, blur: Double) -> some View {
        RoundedRectangle(cornerRadius: 56, style: .continuous)
            .stroke(gradient, lineWidth: width)
            .blur(radius: blur)
    }
}

import SwiftUI

/// `web/src/features/voice/VoiceControls.tsx`: what the composer's box holds
/// while dictation runs — how loud it is, how long it has been listening, and
/// the one way out. Done stands where Send stands, at Send's size, because it
/// is the one primary action while listening; there is no Cancel and no "stop
/// and send". The click on Done is answered in that same slot: the capsule
/// gives way to the spinner until the backend's final transcript lands.
struct VoiceControls: View {
    let voice: VoiceController
    /// `done` while listening, `working` once Done was clicked.
    let slot: PrimarySlot
    let onDone: () -> Void

    /// Fixed per-bar weights: a level of 1 lights the middle bars tallest.
    private static let weights: [Double] = [0.35, 0.55, 0.8, 1, 0.7, 0.95, 0.6, 0.85, 0.5, 0.7, 0.4]

    var body: some View {
        let elapsed = Format.clock(voice.elapsedMs)
        HStack(spacing: Space.sp2) {
            HStack(spacing: 2) {
                ForEach(Self.weights.indices, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1, style: .circular)
                        .fill(Palette.ink)
                        .frame(width: 2, height: (4 + Self.weights[index] * voice.level * 20).rounded())
                }
            }
            .frame(height: 24)
            .animation(.linear(duration: 0.09), value: voice.level)
            .accessibilityHidden(true)
            Text(elapsed)
                .css(FontSize.fs13, mono: true)
                .foregroundStyle(Palette.inkSecondary)
                .accessibilityLabel(S.voice.listeningFor(elapsed))
            // Everything after the timer is pushed to the trailing edge.
            Spacer(minLength: 0)
            if slot == .done {
                Button(action: onDone) { Text(S.voice.done).css(FontSize.fs13, weight: .medium) }
                    .buttonStyle(.btn(.primary, size: .small))
                    .disabled(voice.state != .listening)
            } else {
                WorkingPill(label: S.voice.finishing)
            }
        }
        .padding(.top, Space.sp2)
        .padding(.bottom, 2)
    }
}

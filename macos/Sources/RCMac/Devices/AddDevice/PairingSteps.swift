import RCCore
import SwiftUI

/// `PairingProgress.tsx`: the handshake the code walks through, filled in as
/// `pairing.progress` arrives — a title with its dot and how long the modal has
/// been listening, a hairline of progress, and the three steps.
struct PairingSteps: View {
    let pairing: AddDevicePairing
    /// Milliseconds since the modal started listening.
    let elapsed: Int64

    var body: some View {
        let live = pairing.live
        let marks = PairingChecklist.marks(live?.step)
        let agents = PairingChecklist.agents(live)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp3) {
                HStack(spacing: Space.sp2) {
                    Dot(pairing.connected ? .online : .idle, pulses: !pairing.connected)
                    Text(pairing.connected && live?.device != nil
                         ? S.pairing.connected(live?.device?.name ?? "") : S.pairing.waiting)
                        .css(FontSize.fs14, weight: .medium)
                }
                Spacer(minLength: 0)
                Text(S.pairing.listening(Format.clock(Double(max(0, elapsed)))))
                    .css(FontSize.fs12, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
            }
            PairingProgressLine(percent: PairingChecklist.progress(live?.step))
                .padding(.top, Space.sp3)
                .padding(.bottom, Space.sp4)
            VStack(alignment: .leading, spacing: Space.sp3) {
                PairingStepRow(label: S.pairing.stepGateway, mark: marks[0])
                PairingStepRow(label: S.pairing.stepHandshake, mark: marks[1])
                PairingStepRow(label: S.pairing.stepAgents, mark: marks[2], trailing: agents)
            }
        }
        .padding(Space.sp4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .pairingBox(fill: Palette.surface)
    }
}

/// `.pair-progress`: one `--line` hairline, filled in the ink to the step
/// reached, easing over 400 ms.
private struct PairingProgressLine: View {
    let percent: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Rectangle().fill(Palette.line)
                Rectangle().fill(Palette.ink).frame(width: proxy.size.width * percent / 100)
            }
        }
        .frame(height: 1)
        .animation(Motion.ease(0.4, reduceMotion: reduceMotion), value: percent)
        .accessibilityHidden(true)
    }
}

/// One step: a 16-point ring — filled with a check once done, pulsing while it
/// is the one under way — and its label in the ink the step has reached.
private struct PairingStepRow: View {
    let label: String
    let mark: PairingChecklist.Mark
    var trailing = ""

    var body: some View {
        HStack(spacing: Space.sp3) {
            PairingStepMark(mark: mark)
            Text(label).css(FontSize.fs14)
            if !trailing.isEmpty {
                Text(trailing)
                    .css(FontSize.fs12, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
            }
        }
        .foregroundStyle(ink)
    }

    private var ink: Color {
        switch mark {
        case .done: Palette.ink
        case .active: Palette.inkSecondary
        case .idle: Palette.inkTertiary
        }
    }
}

private struct PairingStepMark: View {
    let mark: PairingChecklist.Mark
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    var body: some View {
        ZStack {
            if mark == .done {
                Circle().fill(Palette.ink)
                Icon(.check, size: 11, strokeWidth: 3).foregroundStyle(Color.white)
            } else {
                Circle().strokeBorder(mark == .active ? Palette.inkSecondary : Palette.lineStrong, lineWidth: 1.5)
            }
        }
        .frame(width: 16, height: 16)
        .opacity(mark == .active && dimmed ? 0.35 : 1)
        .onAppear(perform: pulse)
        .onChange(of: mark) { pulse() }
        .accessibilityHidden(true)
    }

    /// `rc-pulse` over 1.6 s while the step is under way.
    private func pulse() {
        guard mark == .active, !reduceMotion else {
            dimmed = false
            return
        }
        withAnimation(.timingCurve(0.22, 0.61, 0.36, 1, duration: 0.8).repeatForever(autoreverses: true)) {
            dimmed = true
        }
    }
}

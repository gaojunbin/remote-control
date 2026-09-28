import RCCore
import SwiftUI

/// `.dot` and its tones: a 7-point circle, one colour per line of the table in
/// `docs/DESIGN.md` § "The status dot". Green means working — leave it; amber
/// means there is something for you, a finished turn to read or a question to
/// answer. Only `waiting` moves, so the one state that needs the person is the
/// one that asks for a glance.
public enum DotStyle: Sendable, Hashable {
    /// A session's tone, as `DotTone` picks it (`web/src/components/dotTone.ts`).
    case tone(DotTone)
    /// `.dot.running`: a device that is online.
    case online
    /// `.dot.offline`: an empty ring of the idle grey.
    case offline
    /// `.dot` alone: the idle grey.
    case idle
    /// `.dot.attention` and `.dot.error`.
    case attention
    case error

    var fill: Color? {
        switch self {
        case .tone(.working), .online: Palette.running
        case .tone(.waiting), .tone(.live), .attention: Palette.attention
        case .tone(.failed), .error: Palette.danger
        case .offline: nil
        default: Palette.idle
        }
    }
}

/// One dot. `pulses` is `.dot.pulse`; a waiting session pulses of its own.
public struct Dot: View {
    let style: DotStyle
    let pulses: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    public init(_ style: DotStyle, pulses: Bool = false) {
        self.style = style
        self.pulses = pulses
    }

    private var isPulsing: Bool { !reduceMotion && (pulses || style == .tone(.waiting)) }

    public var body: some View {
        ZStack {
            if let fill = style.fill {
                Circle().fill(fill)
            } else {
                Circle().inset(by: 0.75).stroke(Palette.idle, lineWidth: 1.5)
            }
        }
        .frame(width: 7, height: 7)
        .opacity(isPulsing && dimmed ? 0.35 : 1)
        .onAppear { startPulse() }
        .onChange(of: isPulsing) { startPulse() }
    }

    /// `rc-pulse`: full, a third, full again over 1.8 s on the one easing curve.
    private func startPulse() {
        guard isPulsing else {
            dimmed = false
            return
        }
        withAnimation(.timingCurve(0.22, 0.61, 0.36, 1, duration: 0.9).repeatForever(autoreverses: true)) {
            dimmed = true
        }
    }
}

/// `web/src/components/StatusDot.tsx`: a session's dot. The tone comes from
/// `DotTone`, which reads the state, the control owner and the device
/// together; the accessibility label stays the raw state and the tooltip names
/// the tone.
public struct StatusDot: View {
    let state: SessionState
    let control: SessionControl
    let online: Bool

    public init(state: SessionState, control: SessionControl, online: Bool = true) {
        self.state = state
        self.control = control
        self.online = online
    }

    public var body: some View {
        let tone = DotTone.of(state: state, control: control, online: online)
        Dot(.tone(tone))
            .help(S.dotToneLabel(tone.rawValue))
            .accessibilityElement()
            .accessibilityLabel(S.stateLabel(state.rawValue))
    }
}

/// A device's own dot. Online or not, and pulsing while it updates itself (A22).
public struct OnlineDot: View {
    let online: Bool
    let pulses: Bool

    public init(online: Bool, pulses: Bool = false) {
        self.online = online
        self.pulses = pulses
    }

    public var body: some View {
        Dot(online ? .online : .offline, pulses: pulses)
            .accessibilityElement()
            .accessibilityLabel(online ? "online" : "offline")
    }
}

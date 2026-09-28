import SwiftUI

/// `web/src/components/Switch.tsx` / `.switch`: a 40 × 24 pill in
/// `--line-strong`, ink when on, with an 18-point white knob that slides 16
/// points over `--dur`. Disabled, it looks exactly as it does enabled and keeps
/// the pointer: `ui.css` has no rule for `.switch:disabled`, so only the press
/// stops working.
public struct Switch: View {
    let isOn: Bool
    let label: String
    let onChange: (Bool) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(isOn: Bool, label: String, onChange: @escaping (Bool) -> Void) {
        self.isOn = isOn
        self.label = label
        self.onChange = onChange
    }

    public var body: some View {
        Button { onChange(!isOn) } label: {
            ZStack(alignment: .leading) {
                Capsule().fill(isOn ? Palette.ink : Palette.lineStrong)
                Circle()
                    .fill(Color.white)
                    .frame(width: 18, height: 18)
                    .boxShadow(Shadow.one, in: Circle())
                    .offset(x: isOn ? 19 : 3)
            }
            .frame(width: 40, height: 24)
            .animation(Motion.ease(Motion.dur, reduceMotion: reduceMotion), value: isOn)
            .contentShape(Capsule())
        }
        .buttonStyle(SwitchStyle())
        .pointerStyle(.link)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "1" : "0")
        .accessibilityAddTraits(.isToggle)
    }
}

/// The label as it is, pressed or not, enabled or not: the plain style would
/// dim it when disabled, which the web's switch never is.
private struct SwitchStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

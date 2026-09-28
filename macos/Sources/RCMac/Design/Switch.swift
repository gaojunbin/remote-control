import SwiftUI

/// `web/src/components/Switch.tsx` / `.switch`: a 40 × 24 pill in
/// `--line-strong`, ink when on, with an 18-point white knob that slides 16
/// points over `--dur`.
public struct Switch: View {
    let isOn: Bool
    let label: String
    let onChange: (Bool) -> Void
    @Environment(\.isEnabled) private var isEnabled
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
        .buttonStyle(.plain)
        .pointerStyle(isEnabled ? .link : nil)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "1" : "0")
        .accessibilityAddTraits(.isToggle)
    }
}

import SwiftUI

/// One segment of a `Segmented` control.
public struct SegmentOption<Value: Hashable>: Identifiable {
    public let value: Value
    public let label: AnyView
    public let disabled: Bool
    /// What this segment is, in words, for a label that is a glyph or a mark.
    /// It names the button for assistive technology and on hover.
    public let name: String?

    public var id: Value { value }

    @MainActor
    public init(value: Value, label: String, disabled: Bool = false, name: String? = nil) {
        self.init(value: value, disabled: disabled, name: name) { Text(label).css(FontSize.fs14) }
    }

    @MainActor
    public init<Label: View>(value: Value, disabled: Bool = false, name: String? = nil,
                             @ViewBuilder label: () -> Label) {
        self.value = value
        self.label = AnyView(label())
        self.disabled = disabled
        self.name = name
    }
}

/// `web/src/components/Segmented.tsx` / `.segmented`: a muted pill holding
/// equal segments, 3 points apart and inset; the chosen one is a white pill
/// with `--shadow-1` and ink text, the others the secondary ink.
public struct Segmented<Value: Hashable>: View {
    let value: Value
    let options: [SegmentOption<Value>]
    let ariaLabel: String
    let onChange: (Value) -> Void

    public init(value: Value, options: [SegmentOption<Value>], ariaLabel: String,
                onChange: @escaping (Value) -> Void) {
        self.value = value
        self.options = options
        self.ariaLabel = ariaLabel
        self.onChange = onChange
    }

    public var body: some View {
        HStack(spacing: 3) {
            ForEach(options) { option in
                Button { onChange(option.value) } label: { option.label }
                    .buttonStyle(SegmentStyle(pressed: option.value == value))
                    .disabled(option.disabled)
                    .help(option.name ?? "")
                    .modifier(AccessibleName(option.name))
            }
        }
        .padding(3)
        .background(Capsule().fill(Palette.surfaceMuted))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(ariaLabel)
    }
}

private struct SegmentStyle: ButtonStyle {
    let pressed: Bool

    func makeBody(configuration: Configuration) -> some View {
        SegmentBody(configuration: configuration, pressed: pressed)
    }
}

private struct SegmentBody: View {
    let configuration: ButtonStyleConfiguration
    let pressed: Bool
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: Space.sp2) { configuration.label }
            .font(.system(size: FontSize.fs14))
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(height: 32)
            .foregroundStyle(pressed ? Palette.ink : Palette.inkSecondary)
            .background {
                if pressed {
                    Capsule().fill(Palette.surface).boxShadow(Shadow.one, in: Capsule())
                }
            }
            .contentShape(Capsule())
            .opacity(isEnabled ? 1 : 0.4)
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: pressed)
    }
}

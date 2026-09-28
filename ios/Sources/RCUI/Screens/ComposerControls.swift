import SwiftUI
import RCCore

// Amendment A44: on the phone the composer's control row is icons, because
// four words do not fit beside Send (`docs/DESIGN.md` § "The control row").
// Each is a 44-point target that opens exactly what its words opened, and each
// accessible name carries the value the icon draws. The order is
// `ComposerControl`'s; the model card's gauge is `ModelCardChip`.

/// One glyph of the row, centred on its 44-point target.
struct ControlGlyph<Glyph: View>: View {
    @ViewBuilder let glyph: Glyph

    var body: some View {
        glyph
            .frame(minWidth: Theme.Touch.minimum, minHeight: Theme.Touch.minimum)
            .contentShape(Rectangle())
    }
}

/// Amendment A43: what waits behind the turn — a notepad with the count in a
/// small dark badge at its top-right corner. It opens the Up next sheet.
struct UpNextControl: View {
    let count: Int
    let open: () -> Void
    @ScaledMetric(relativeTo: .caption2) private var badge: CGFloat = 15
    /// The clear ring cut around the badge, as a symbol's own badge has, so
    /// the digits never run into the notepad under them.
    private static let gap: CGFloat = 1.5

    var body: some View {
        Button(action: open) {
            ControlGlyph {
                Image(systemName: "note.text")
                    .mask {
                        Rectangle()
                            .overlay(alignment: .topTrailing) { badgeCutout }
                            .compositingGroup()
                    }
                    .overlay(alignment: .topTrailing) { countBadge }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.ink)
        .accessibilityLabel("Up next")
        .accessibilityValue(ComposerControl.upNextValue(count))
        .accessibilityIdentifier("composer.queue")
    }

    /// The digits at the size of the badge, before it is coloured and placed.
    private var badgeBody: some View {
        Text(verbatim: "\(count)")
            .font(.caption2.weight(.semibold))
            .monospacedDigit()
            .padding(.horizontal, 4)
            .frame(minWidth: badge, minHeight: badge)
            .fixedSize()
    }

    /// Centred on the notepad's top-right corner.
    private var countBadge: some View {
        badgeBody
            .foregroundStyle(Theme.onAccent)
            .background(Theme.ink, in: Capsule())
            .offset(x: badge * 0.55, y: -badge * 0.5)
    }

    /// The badge grown by the gap, cut out of the notepad: the same centre as
    /// the badge, so the ring is even all round.
    private var badgeCutout: some View {
        badgeBody
            .padding(Self.gap)
            .background(Capsule())
            .offset(x: badge * 0.55 + Self.gap, y: -badge * 0.5 - Self.gap)
            .blendMode(.destinationOut)
    }
}

/// Amendment A44: the language the phone's own recogniser listens for, which
/// the composer draws only while the phone is the one listening. The list is
/// the recogniser's, with no Automatic: it cannot detect a language.
struct DictationLanguageControl: View {
    let settings: SettingsStore

    var body: some View {
        Menu {
            Picker("Dictation language", selection: selection) {
                ForEach(DictationLanguage.codes, id: \.self) { code in
                    Text(DictationLanguage.name(of: code, in: settings.language)).tag(code)
                }
            }
        } label: {
            ControlGlyph { Image(systemName: "translate") }
        }
        .foregroundStyle(Theme.ink)
        .accessibilityLabel("Dictation language")
        .accessibilityValue(DictationLanguage.name(of: settings.dictationLanguage, in: settings.language))
        .accessibilityIdentifier("composer.language")
    }

    /// The language read as the recogniser hears it — a legacy `auto` shows as
    /// Chinese — and written only when one is picked.
    private var selection: Binding<String> {
        Binding(get: { settings.dictationLanguage }, set: { settings.voiceLanguage = $0 })
    }
}

/// What the session may do: a plain list of the agent's own modes with the
/// current one marked and nothing else on it, behind the shield.
struct PermissionControl: View {
    let chat: ChatStore
    let agent: AgentInfo?

    var body: some View {
        Menu {
            Picker("Permissions", selection: selection) {
                ForEach(agent?.permissionModes ?? []) { option in Text(option.label).tag(option.id) }
            }
        } label: {
            ControlGlyph { PromptShield() }
        }
        .foregroundStyle(Theme.ink)
        .accessibilityLabel("Permissions")
        .accessibilityValue(agent?.permissionModeLabel(chat.session.permissionMode)
                            ?? chat.session.permissionMode ?? "")
        .accessibilityIdentifier("composer.permissions")
    }

    private var selection: Binding<String> {
        Binding(get: { chat.session.permissionMode ?? agent?.defaultPermissionMode ?? "" },
                set: { value in Task { await chat.set(permissionMode: value) } })
    }
}

/// Amendment A17: what the terminal chose, behind the same icon its control
/// would have. A tap shows the value in a menu with nothing to choose, so the
/// reader still learns what the terminal set and nobody reaches for a control
/// that cannot move.
struct TerminalValueControl<Glyph: View>: View {
    let setting: TerminalSetting
    @ViewBuilder let glyph: Glyph

    var body: some View {
        Menu {
            Section("Set in the terminal") {
                Button(setting.text) {}.disabled(true)
                // Amendment A21: the tier it runs at, which the bolt only draws.
                if let tier = setting.speed {
                    Button(tier, systemImage: "bolt.fill") {}.disabled(true)
                }
            }
        } label: {
            ControlGlyph { glyph }
        }
        .foregroundStyle(Theme.ink)
        .accessibilityLabel(setting.field.label)
        .accessibilityValue(setting.spokenValue)
        .accessibilityHint("Set in the terminal")
        .accessibilityIdentifier("composer.readonly.\(setting.field.rawValue)")
    }
}

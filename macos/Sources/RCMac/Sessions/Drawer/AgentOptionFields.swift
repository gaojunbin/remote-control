import RCCore
import SwiftUI

/// What the agent advertises, in the order the drawer asks for it — Model,
/// Effort, Permissions, then the speed tier (A21) — each starting at the agent's
/// own default, and nothing drawn for a list the agent does not have.
struct AgentOptionFields: View {
    let form: NewSessionForm
    let agent: AgentInfo?

    var body: some View {
        let models = agent?.models ?? []
        let efforts = agent?.efforts ?? []
        let modes = agent?.permissionModes ?? []
        let speeds = agent?.speeds ?? []
        if !models.isEmpty {
            OptionList(title: S.newSession.model, choices: models, value: form.model(of: agent)) {
                form.options.model = $0
            }
        }
        if !efforts.isEmpty {
            OptionList(title: S.newSession.effort, choices: efforts, value: form.effort(of: agent)) {
                form.options.effort = $0
            }
        }
        if !modes.isEmpty {
            OptionList(title: S.newSession.permissions, choices: modes, value: form.permissionMode(of: agent)) {
                form.options.permissionMode = $0
            }
        }
        if !speeds.isEmpty {
            HStack(spacing: Space.sp3) {
                Text(S.newSession.speed)
                    .css(FontSize.fs13, weight: .medium, lineHeight: 1.4)
                    .foregroundStyle(Palette.inkSecondary)
                Spacer(minLength: 0)
                SpeedField(speeds: speeds, value: form.speed) { form.options.speed = .some($0) }
            }
        }
    }
}

/// One of the agent's lists: its label, and a menu showing the chosen label.
private struct OptionList: View {
    let title: String
    let choices: [AgentOption]
    let value: String?
    let onSelect: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(title)
            DrawerSelect(options: choices.map { MenuOption(id: $0.id, label: $0.label) }, value: value,
                         ariaLabel: title, onSelect: onSelect) {
                Text(choices.first { $0.id == value }?.label ?? "")
                    .lineLimit(1)
                    .css(FontSize.fs13)
            }
        }
    }
}

/// A21: the speed tier a new session starts at. One tier is a switch — on is
/// that tier, off is the standard speed; an agent that ever lists several gets
/// a list with the standard speed first.
private struct SpeedField: View {
    let speeds: [AgentOption]
    let value: String?
    let onChange: (String?) -> Void

    /// The row id the list uses for "no tier", which the wire spells as null.
    private static let standard = "standard"

    var body: some View {
        if speeds.count == 1, let only = speeds.first {
            Switch(isOn: value == only.id, label: S.newSession.speed) { onChange($0 ? only.id : nil) }
        } else {
            SelectMenu(options: [MenuOption(id: Self.standard, label: S.composer.speedStandard)]
                        + speeds.map { MenuOption(id: $0.id, label: $0.label) },
                       value: value, ariaLabel: S.newSession.speed, align: .end,
                       onSelect: { onChange($0 == Self.standard ? nil : $0) }) {
                Text(speeds.first { $0.id == value }?.label ?? S.composer.speedStandard).css(FontSize.fs13)
            }
        }
    }
}

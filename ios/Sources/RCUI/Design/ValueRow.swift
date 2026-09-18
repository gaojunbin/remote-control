import SwiftUI
import RCCore

/// A label on the left and a value on the right, one line each.
///
/// Settings itself no longer reads like this — its rows are two lines and a
/// control (`docs/DESIGN.md` § "The Settings screen") — but a screen that has
/// nothing to offer and only something to state still does: the blocking
/// update screen says which build this is and which one the gateway asks for.
struct ValueRow: View {
    let label: LocalizedStringKey
    let value: String
    var mono = false

    init(_ label: LocalizedStringKey, value: String, mono: Bool = false) {
        self.label = label
        self.value = value
        self.mono = mono
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.medium) {
            Text(label).font(Theme.Text.label).foregroundStyle(Theme.ink)
            Spacer(minLength: Theme.Space.small)
            Text(value)
                .font(mono ? Theme.Text.metaMono : Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
                .lineLimit(1)
                .truncationMode(mono ? .middle : .tail)
        }
        .settingsRowLayout()
        .accessibilityElement(children: .combine)
    }
}

/// The sentence under a group. Caption weight, secondary, never a box.
///
/// Settings has none left — a state speaks in the row it belongs to — but the
/// accounts screen still captions its one switch, and the resume notice its
/// picker.
struct SettingsFooter: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .padding(.top, Theme.Space.hair)
    }
}

extension View {
    /// The inset every settings row shares, so titles, toggles and pickers line
    /// up and the surface has room around them. No separator: the rows of a
    /// group sit on one surface with spacing between the groups instead
    /// (`docs/DESIGN.md` § "Surfaces, rows and controls").
    func settingsRowLayout() -> some View {
        listRowBackground(Theme.surface)
            .listRowInsets(EdgeInsets(top: 12, leading: Theme.Space.medium,
                                      bottom: 12, trailing: Theme.Space.medium))
            .listRowSeparator(.hidden)
            .frame(minHeight: 28)
    }
}

import SwiftUI
import RCCore

/// The two lines every settings row is built from: the title in the label
/// weight, and one sentence under it in the secondary ink.
///
/// `docs/DESIGN.md` § "The Settings screen": the sentence belongs to the row,
/// not to a footnote under the group, and a row whose state has something to
/// say — a blocked permission, a gateway with no model, a refused write — says
/// it here in place of the sentence.
///
/// The sentence is a `String` rather than a `LocalizedStringKey` because most
/// of them are built outside a view, by a store or by `ResumeText`, and one
/// rule for all of them is the only rule worth having. A string `L10n.string`
/// produced goes on saying what it said in the language it was built in, so
/// every group holds the interface language and is rebuilt when it changes.
struct SettingsLabel: View {
    let title: LocalizedStringKey
    let sentence: String
    /// The one row that is not the ink: signing out is the only thing here a
    /// person can regret, and the colour says so before the sentence does.
    var tint: Color = Theme.ink

    init(_ title: LocalizedStringKey, sentence: String, tint: Color = Theme.ink) {
        self.title = title
        self.sentence = sentence
        self.tint = tint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(Theme.Text.label)
                .foregroundStyle(tint)
            Text(sentence)
                .font(Theme.Text.caption)
                .foregroundStyle(Theme.inkSecondary)
                // The row's height is a floor and not a clip: a sentence that
                // wraps makes its own row taller and moves nothing else.
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A settings row: the two lines on the left, a control at the trailing edge,
/// centred on them.
///
/// A `Toggle`, a `Button` and a `NavigationLink` take a `SettingsLabel` as
/// their own label instead, which keeps the system's behaviour for the whole
/// row. Everything else is laid out here, because a control that lays itself
/// out beside a label — a segmented picker, a menu picker — drops its own
/// choice onto a second line as soon as the sentence beside it wraps.
struct SettingsRow<Control: View>: View {
    let title: LocalizedStringKey
    let sentence: String
    @ViewBuilder let control: Control

    init(_ title: LocalizedStringKey, sentence: String,
         @ViewBuilder control: () -> Control) {
        self.title = title
        self.sentence = sentence
        self.control = control()
    }

    var body: some View {
        HStack(alignment: .center, spacing: Theme.Space.medium) {
            SettingsLabel(title, sentence: sentence)
            control
        }
        .settingsRowPadding()
    }
}

/// The label of a row that is itself the action — a button or a link: the two
/// lines, and a chevron where the row leads somewhere. It carries the row's
/// padding, so the whole row is the button's target.
struct SettingsActionLabel: View {
    let title: LocalizedStringKey
    let sentence: String
    var tint: Color = Theme.ink
    /// Whether the row opens another screen or a sheet, which the chevron says.
    var leadsOn = false

    init(_ title: LocalizedStringKey, sentence: String, tint: Color = Theme.ink, leadsOn: Bool = false) {
        self.title = title
        self.sentence = sentence
        self.tint = tint
        self.leadsOn = leadsOn
    }

    var body: some View {
        HStack(alignment: .center, spacing: Theme.Space.medium) {
            SettingsLabel(title, sentence: sentence, tint: tint)
            if leadsOn {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.inkTertiary)
            }
        }
        .settingsRowPadding()
        .contentShape(Rectangle())
    }
}

/// A row whose control is a menu of more than two choices: the chosen word and
/// a chevron at the trailing edge, and the whole row opens the menu
/// (`docs/DESIGN.md` § "The Settings screen", "Controls by the shape of the
/// choice"). A `Menu` holding a `Picker` rather than a `Picker` in the `.menu`
/// style, because the menu's label is the row, and the row is the target.
struct SettingsMenuRow<Selection: Hashable, Options: View>: View {
    let title: LocalizedStringKey
    let sentence: String
    @Binding var selection: Selection
    /// The word for what is chosen, drawn at the trailing edge.
    let chosen: String
    @ViewBuilder let options: () -> Options

    init(_ title: LocalizedStringKey, sentence: String, selection: Binding<Selection>,
         chosen: String, @ViewBuilder options: @escaping () -> Options) {
        self.title = title
        self.sentence = sentence
        self._selection = selection
        self.chosen = chosen
        self.options = options
    }

    var body: some View {
        Menu {
            Picker(title, selection: $selection) { options() }
        } label: {
            HStack(alignment: .center, spacing: Theme.Space.medium) {
                SettingsLabel(title, sentence: sentence)
                HStack(spacing: Theme.Space.hair) {
                    Text(chosen).lineLimit(1)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote.weight(.semibold))
                }
                .font(Theme.Text.label)
                .foregroundStyle(Theme.ink)
                .layoutPriority(1)
            }
            .settingsRowPadding()
            // The row is the target, not the words in it.
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
    }
}

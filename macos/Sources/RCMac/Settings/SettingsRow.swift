import SwiftUI

/// `SettingsRow.tsx`: one settings row — a title, one sentence under it, and
/// the control at the trailing edge (`docs/DESIGN.md` § "The Settings
/// screen"). No rule parts two rows and nothing is drawn beside a row for a
/// state: a row whose state has something to say says it in place of its
/// sentence.
struct SettingsRow<Control: View>: View {
    let title: String
    /// The row's own sentence, or what its state has to say instead.
    let sentence: String
    /// A row a click anywhere on reaches: the ruling gives this to a menu and
    /// to a switch, never to a segmented control, where there is no one
    /// control to reach. The control keeps its own click.
    let target: Bool
    /// What a click on the row does for its control, or nil while the control
    /// has nothing to do, which leaves the row inert.
    let reach: (() -> Void)?
    let control: Control
    @State private var isHovered = false
    @Environment(\.layoutClass) private var layout
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(title: String, sentence: String, target: Bool = false, reach: (() -> Void)? = nil,
         @ViewBuilder control: () -> Control) {
        self.title = title
        self.sentence = sentence
        self.target = target
        self.reach = reach
        self.control = control()
    }

    var body: some View {
        HStack(spacing: Space.sp4) {
            SettingsRowText(title: title, sentence: sentence)
                .frame(maxWidth: .infinity, alignment: .leading)
            control.fixedSize()
        }
        .settingsRowBox(compact: layout.maxWidth640)
        .background(target && isHovered ? Palette.hover : Color.clear)
        .contentShape(Rectangle())
        .onHover { isHovered = $0 }
        .onTapGesture { if target { reach?() } }
        .pointerStyle(target ? .link : nil)
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
        .accessibilityElement(children: .contain)
    }
}

/// `SettingsActionRow`: a row that is the action itself — the whole row is the
/// button, with a chevron where it leads somewhere and none for Sign out,
/// which takes something away rather than leading anywhere.
struct SettingsActionRow: View {
    let title: String
    let sentence: String
    var danger = false
    let onPress: () -> Void

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: Space.sp4) {
                SettingsRowText(title: title, sentence: sentence, danger: danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if !danger {
                    Icon(.chevronRight, size: 16).foregroundStyle(Palette.inkTertiary)
                }
            }
        }
        .buttonStyle(SettingsActionRowStyle())
        .accessibilityLabel(title)
        .accessibilityHint(sentence)
    }
}

private struct SettingsActionRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        SettingsActionRowBody(configuration: configuration)
    }
}

private struct SettingsActionRowBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovered = false
    @Environment(\.layoutClass) private var layout
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .settingsRowBox(compact: layout.maxWidth640)
            .background(isHovered ? Palette.hover : Color.clear)
            .contentShape(Rectangle())
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
    }
}

/// `.settings-row-text`: the title in the label weight over its sentence.
struct SettingsRowText: View {
    let title: String
    let sentence: String
    var danger = false

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .css(FontSize.fs15, weight: .semibold, lineHeight: 1.35)
                .foregroundStyle(danger ? Palette.danger : Palette.ink)
            Text(sentence)
                .css(FontSize.fs13, lineHeight: 1.45)
                .foregroundStyle(Palette.inkSecondary)
        }
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
    }
}

extension View {
    /// `.settings-row`'s box: 12 above and below, 20 at the sides (16 at 640
    /// and narrower), and 56 high at least — a floor, never a clip. The row
    /// is as tall as it needs whatever height it is offered: a button offers
    /// its label less, and the text would spill over the padding.
    fileprivate func settingsRowBox(compact: Bool) -> some View {
        padding(.vertical, Space.sp3)
            .padding(.horizontal, compact ? Space.sp4 : Space.sp5)
            .frame(maxWidth: .infinity, minHeight: RowHeight.rowHSetting, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

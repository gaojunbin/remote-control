import SwiftUI
import RCCore

/// The terminal's `/` menu, on the phone (amendment A27).
///
/// A card over the keyboard, anchored above the message field: one row per
/// command, `/name` in the monospace face at the leading edge, its description
/// after it and the argument placeholder in the tertiary colour where the
/// command takes one. It is filtered by prefix as more letters are typed,
/// sectioned by group only where there is more than one to tell apart, and
/// never taller than eight rows before it scrolls.
///
/// `docs/DESIGN.md` § "The composer" holds the rule; `ChatStore` decides what
/// is in it, so the web app and this one draw the same list.
struct CommandPanel: View {
    let chat: ChatStore
    /// Called after a row is taken. The screen's background tap puts the
    /// keyboard away whenever a touch lands outside the message field, and a
    /// row is outside it, so the field asks for the keyboard back rather than
    /// leaving the argument to be typed after a second tap.
    let taking: () -> Void
    /// Bumped on every taken row, which is what the selection haptic fires on.
    @State private var taken = 0
    @ScaledMetric(relativeTo: .callout) private var rowHeight: CGFloat = 44
    @ScaledMetric(relativeTo: .caption) private var headerHeight: CGFloat = 26

    private var sections: [CommandSection] { chat.commandSections }
    private var showsHeaders: Bool { sections.count > 1 }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(sections) { section in
                        if showsHeaders, let title = section.title {
                            SectionHeader(title: title).frame(height: headerHeight)
                        }
                        ForEach(section.commands) { command in
                            row(command, isFirst: isFirst(command))
                        }
                    }
                }
            }
            // A maximum rather than a height: the card is as tall as its rows
            // up to the cap, and gives way when the keyboard leaves less than
            // that, so the control row under the field is never pushed off the
            // screen by a long list.
            .frame(maxHeight: height)
            .scrollBounceBehavior(.basedOnSize)
            if chat.commandsWaitForTurn { footer }
        }
        // A row scrolling past the top or the bottom stops at the card's own
        // corners rather than at the square edge of its content.
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .card(padding: 0)
        // A row's whole business is what it writes into the field, so the tap
        // gives the same confirmation a picker does: one selection haptic.
        .sensoryFeedback(.selection, trigger: taken)
        .accessibilityIdentifier("composer.commands")
    }

    private func row(_ command: Command, isFirst: Bool) -> some View {
        Button {
            taken += 1
            chat.take(command)
            // After the tap, not inside it: the background gesture that lowers
            // the keyboard runs on the same touch.
            Task { @MainActor in taking() }
        } label: {
            CommandLabel(command: command)
                .padding(.horizontal, Theme.Space.medium)
                .frame(height: rowHeight)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(chat.commandsWaitForTurn)
        .opacity(chat.commandsWaitForTurn ? 0.4 : 1)
        .overlay(alignment: .top) {
            if !isFirst { Rectangle().fill(Theme.hairline).frame(height: 0.5) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(label(for: command))
        .accessibilityIdentifier("command.\(command.name)")
    }

    /// A turn is running, so nothing here can be run yet and the card says so
    /// once, under the rows, rather than on every one of them.
    private var footer: some View {
        Text("Available when the turn finishes")
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .padding(.horizontal, Theme.Space.medium)
            .padding(.vertical, Theme.Space.small)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .top) { Rectangle().fill(Theme.hairline).frame(height: 0.5) }
            .accessibilityIdentifier("composer.commands.footer")
    }

    /// The card stops growing at eight rows and scrolls inside itself, which is
    /// measured rather than guessed so a header counts towards the cap too.
    private var height: CGFloat {
        let rows = CGFloat(sections.reduce(0) { $0 + $1.commands.count })
        let headers = showsHeaders ? CGFloat(sections.count) : 0
        return min(rows * rowHeight + headers * headerHeight,
                   CGFloat(SlashDraft.visibleRows) * rowHeight)
    }

    private func isFirst(_ command: Command) -> Bool {
        sections.first?.commands.first?.id == command.id
    }

    private func label(for command: Command) -> String {
        guard let argument = command.argument else {
            return L10n.string("Command, %@, %@", command.slash, command.description)
        }
        return L10n.string("Command, %@, %@, takes %@", command.slash, command.description, argument)
    }
}

/// Where a group of commands came from: the device's own word, never
/// translated, because it names a directory on that machine.
private struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(verbatim: title)
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .padding(.horizontal, Theme.Space.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            // A header, so a screen reader can jump between the sources rather
            // than read every row to find out where the next one came from.
            .accessibilityAddTraits(.isHeader)
    }
}

/// One command as it reads on a row and on the hint line under the card: the
/// name in the monospace face, what it does after it, and where the argument
/// goes at the trailing edge. The description is what gives way when the line
/// is tight, because the name and the placeholder are what is acted on.
struct CommandLabel: View {
    let command: Command

    var body: some View {
        HStack(spacing: Theme.Space.small) {
            Text(verbatim: command.slash)
                .font(Theme.mono)
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .layoutPriority(2)
            Text(verbatim: command.description)
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
            if let argument = command.argument {
                Text(verbatim: argument)
                    .font(Theme.Text.metaMono)
                    .foregroundStyle(Theme.inkTertiary)
                    .lineLimit(1)
                    .layoutPriority(1)
            }
        }
    }
}

/// The line the panel hands over to: the first word is a complete command, so
/// the card has closed and what is left to say is where the argument goes — or,
/// while a turn runs, that the command has to wait for it.
struct CommandHintLine: View {
    let chat: ChatStore
    let command: Command

    var body: some View {
        Group {
            if chat.commandsWaitForTurn {
                Text("Available when the turn finishes")
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                CommandLabel(command: command)
            }
        }
        .accessibilityIdentifier("composer.commandHint")
    }
}

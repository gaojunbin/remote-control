import RCCore
import SwiftUI

/// A27 — the terminal's `/` menu, above the composer (`CommandMenu.tsx`). One
/// row per command: `/name` in the monospaced face, the description after it,
/// the argument placeholder at the trailing edge when the command takes one;
/// group headers only when the agent distinguishes more than one group. It is
/// not a popover — nothing opened it, a keystroke did — so it is drawn in the
/// composer's own flow, over the field, and the field keeps the focus: typing
/// goes on filtering, and the keys reach the rows through the field.
struct CommandPanel: View {
    let rows: [Command]
    let highlight: Int
    /// A27: a command waits for the turn, so the rows dim and the footer says so.
    let running: Bool
    let onHighlight: (Int) -> Void
    let onTake: (Command) -> Void

    /// Eight rows, then it scrolls — the list is read, not scrolled through.
    private static let rowHeight: CGFloat = 34
    private static let visibleRows = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(SlashCommands.sections(rows), id: \.group) { section in
                            if let group = section.group {
                                Text(group.uppercased())
                                    .css(FontSize.fs11, weight: .medium, tracking: 0.04)
                                    .foregroundStyle(Palette.inkTertiary)
                                    .padding(EdgeInsets(top: Space.sp2, leading: 10, bottom: 4, trailing: 10))
                                    .accessibilityHidden(true)
                            }
                            ForEach(section.items) { command in row(command) }
                        }
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: min(contentHeight, Self.rowHeight * CGFloat(Self.visibleRows)))
                // Arrowing past the eighth row brings the row into view.
                .onChange(of: highlight) { _, index in
                    guard rows.indices.contains(index) else { return }
                    reader.scrollTo(rows[index].name)
                }
            }
            if running {
                Text(S.commands.whileRunning)
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkTertiary)
                    .padding(EdgeInsets(top: 6, leading: 10, bottom: 4, trailing: 10))
            }
        }
        .padding(Space.sp1)
        .modifier(CommandSurface())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(S.commands.menu)
    }

    /// The list's own height: the rows and whatever group headers it draws.
    private var contentHeight: CGFloat {
        let sections = SlashCommands.sections(rows)
        let headers = sections.filter { $0.group != nil }.count
        return Self.rowHeight * CGFloat(rows.count) + CGFloat(headers) * Self.headerHeight
    }

    /// `.command-group-name`: 11-point type on a 16.5-point line, 8 above and 4 below.
    private static let headerHeight: CGFloat = 16.5 + Space.sp2 + 4

    private func row(_ command: Command) -> some View {
        let index = rows.firstIndex(of: command) ?? 0
        return CommandRowText(command: command)
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: Self.rowHeight, maxHeight: Self.rowHeight, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular)
                .fill(index == highlight ? Palette.surfaceHover : Color.clear))
            .opacity(running ? 0.45 : 1)
            .contentShape(Rectangle())
            .onHover { if $0 { onHighlight(index) } }
            .onTapGesture { onTake(command) }
            .pointerStyle(.link)
            .id(command.name)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(S.commands.rowLabel(command.name, command.description))
            .accessibilityAddTraits(index == highlight ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction { onTake(command) }
    }
}

/// A27 — what a complete first word says about the rest of the line: the
/// command and where its argument goes. It stands where the panel stood, so
/// the field does not jump when the panel closes on the space after the name.
struct CommandHint: View {
    let command: Command

    var body: some View {
        CommandRowText(command: command)
            .padding(.vertical, Space.sp2)
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(CommandSurface())
            .accessibilityElement(children: .combine)
    }
}

/// `/name`, the description, the argument: set on one baseline, and at the
/// top of the row the way a flex row aligned on baselines sets them.
private struct CommandRowText: View {
    let command: Command

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.sp2) {
            Text("/\(command.name)")
                .css(FontSize.fs13, mono: true)
                .foregroundStyle(Palette.ink)
                .fixedSize()
            Text(command.description)
                .css(FontSize.fs13)
                .foregroundStyle(Palette.inkSecondary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let argument = command.argument, !argument.isEmpty {
                Text(argument)
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.inkTertiary)
                    .fixedSize()
            }
        }
    }
}

/// The panel's surface: the popover's white, radius and shadow, rising three
/// points into place as it appears (`rc-command-pop`).
private struct CommandSurface: ViewModifier {
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        content
            .background(shape.fill(Palette.surface))
            .boxShadow(Shadow.pop, in: shape)
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 3)
            .onAppear { withAnimation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion)) { shown = true } }
    }
}

import SwiftUI
import RCCore

/// Amendment A34: words another agent put into the conversation — a teammate
/// session's report, a background task's notification.
///
/// Claude Code files them as user turns, but nobody typed them, so they are not
/// the person's side of the conversation: they sit on the left with the agent's
/// own output, as a muted block on the quiet surface captioned "from another
/// agent", at the width assistant text uses and in no bubble at all
/// (`docs/DESIGN.md` § "The timeline"). The gray bubble on the right holds the
/// person's own words and nothing else.
///
/// The device has already reduced the text to who reported and what they said,
/// with the envelope and every `<system-reminder>` removed (A30), so the row
/// prints exactly what it was given.
struct AgentMessageRow: View {
    let payload: UserMessagePayload

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.hair) {
            // Where a terminal message says where it was typed, this says that
            // no one typed it.
            Text("from another agent")
                .font(Theme.Text.caption)
                .foregroundStyle(Theme.inkSecondary)
            Text(payload.text)
                .font(Theme.Text.label)
                .foregroundStyle(Theme.inkSecondary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Theme.Space.medium)
        .padding(.vertical, Theme.Space.small)
        .background(Theme.surfaceSunken,
                    in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
            .strokeBorder(Theme.border, lineWidth: 0.5))
        .accessibilityElement(children: .combine)
        // Nobody said this, so VoiceOver is not told the person did.
        .accessibilityLabel(Text(L10n.string("From another agent: %@", payload.text)))
        .accessibilityIdentifier("chat.message.agent")
    }
}

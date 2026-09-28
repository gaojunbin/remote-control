import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/AgentMessageRow.tsx` — A34: words another
/// agent put into the conversation, a teammate's report or a background
/// task's notification. Nobody typed them, so they sit on the left with the
/// agent's own output, as a muted block on the quiet surface captioned "from
/// another agent" (`docs/DESIGN.md` § "The timeline"). The device has already
/// reduced the text to who reported and what they said (A30), so the row
/// prints exactly what it was given.
struct AgentMessageRow: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(S.chat.fromAgent)
                .css(FontSize.fs11, lineHeight: 1.55)
                .foregroundStyle(Palette.inkTertiary)
                .padding(.bottom, 2)
            Text(verbatim: ChatPreText.display(text))
                .css(FontSize.fs14, lineHeight: 1.55)
                .foregroundStyle(Palette.inkSecondary)
                .textSelection(.enabled)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, Space.sp4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .chatBox(radius: Radius.md, background: Palette.surfaceSunken, clips: false)
    }
}

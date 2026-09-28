import RCCore
import SwiftUI

/// `web/src/features/sessions/SessionRow.tsx`: three lines, as `docs/DESIGN.md`
/// § "The session row" rules — the title with the time at the trailing edge;
/// the agent at the leading edge with the dot and the session's origin at the
/// trailing edge; the working directory alone after a folder, truncated from
/// the head so the folder it ends in survives. The state is the dot's colour
/// alone. Every row reserves the gutter the close action sits in (A39).
struct SessionRow: View {
    let session: Session
    let online: Bool
    /// A preview stage's: the close question drawn open.
    var asksToClose = false
    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let narrow = layout.maxWidth640
        Button {
            model.router.go(.chat(deviceId: session.deviceID, sessionId: session.sessionID))
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                titleLine
                agentLine
                pathLine
            }
            .padding(.horizontal, narrow ? Space.sp4 : Space.sp5)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .pointerStyle(.link)
        .accessibilityLabel(S.sessions.open)
        .padding(.trailing, 40)
        .frame(height: RowHeight.rowHThree)
        .overlay(alignment: .trailing) {
            // A39: only a session the device drives can be closed. A terminal
            // holds its own row until it exits, and a row in the Archive comes
            // back by being written to, so neither offers anything.
            if SessionListLayout.offersClose(session) {
                SessionCloseButton(session: session, online: online, asksOnAppear: asksToClose)
                    .opacity(isHovered || narrow ? 1 : 0)
                    .padding(.trailing, Space.sp3)
            }
        }
        // The tint eases in; the close action does not, as the web's opacity
        // has no transition.
        .background {
            Rectangle()
                .fill(isHovered ? Palette.hover : Color.clear)
                .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
        }
        .onHover { isHovered = $0 }
    }

    private var titleLine: some View {
        HStack(spacing: Space.sp3) {
            Text(S.sessionTitle(session))
                .lineLimit(1)
                .truncationMode(.tail)
                .css(FontSize.fs15, weight: .semibold, lineHeight: 1.4, tracking: -0.01)
            Spacer(minLength: 0)
            Text(Format.relativeTime(session.updatedAt))
                .lineLimit(1)
                .css(FontSize.fs12, lineHeight: 1.45)
                .foregroundStyle(Palette.inkTertiary)
                .fixedSize()
        }
    }

    /// Where the session came from, whatever it is doing (`docs/DESIGN.md`
    /// § "The session row says where it came from"); a hand-archived row says
    /// so before its origin.
    private var agentLine: some View {
        let origin = S.sessionOriginLabel(session)
        return HStack(spacing: Space.sp3) {
            AgentChip(agent: session.agent)
            Spacer(minLength: 0)
            HStack(spacing: Space.sp2) {
                StatusDot(state: session.state, control: session.control, online: online)
                Text(session.archived ? "\(S.sessions.archived) · \(origin)" : origin)
                    .lineLimit(1)
                    .css(FontSize.fs13, lineHeight: 1.45)
                    .foregroundStyle(Palette.inkSecondary)
            }
            .fixedSize()
        }
    }

    /// A folder drawn to the same rule as the laptop before a device, then the
    /// path in mono, cut at its head.
    private var pathLine: some View {
        HStack(spacing: 5) {
            Icon(.folder, size: 14, strokeWidth: 1.5)
                .foregroundStyle(Palette.ink)
            Text(Format.tildePath(session.cwd))
                .lineLimit(1)
                .truncationMode(.head)
                .css(FontSize.fs12, lineHeight: 1.45, mono: true)
                .foregroundStyle(Palette.inkSecondary)
        }
    }
}

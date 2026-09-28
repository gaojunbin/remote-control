import RCCore
import SwiftUI

/// `web/src/features/chat/ChatHeader.tsx`: the conversation's title and where
/// it runs, then the todo chip, the usage chip and Stop — on a hairline, 16 by
/// 20 points of padding. It is the window's top strip: its empty part moves
/// the window, and where the sidebar is hidden it starts after the traffic
/// lights, with the way back to Sessions first.
struct ChatHeader: View {
    let session: Session
    let agent: AgentInfo?
    let deviceName: String
    let todos: [TodoItem]
    let stopping: Bool
    let onStop: () -> Void
    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @Environment(\.trafficLightInset) private var trafficLights
    @State private var height: CGFloat = 0

    var body: some View {
        let header = ChatHeaderModel(session: session, agent: agent, todos: todos,
                                     detail: model.settings.timelineDetail)
        VStack(spacing: 0) {
            HStack(spacing: Space.sp3) {
                if layout.maxWidth1023 {
                    IconBtn(.arrowLeft, size: 17, label: S.nav.backToSessions) { model.router.go(.sessions) }
                }
                heading
                HStack(spacing: Space.sp2) {
                    if let counts = header.todos { TodosPopover(counts: counts, todos: todos) }
                    if header.usage != nil { UsageChip(session: session) }
                    if header.offersStop {
                        Button(stopping ? S.chat.stopping : S.chat.stop, action: onStop)
                            .buttonStyle(.btn(.standard, size: .small))
                            .disabled(stopping)
                    }
                }
                .fixedSize()
            }
            .padding(.vertical, Space.sp4)
            .padding(.leading, layout.maxWidth1023 ? max(Space.sp5, trafficLights) : Space.sp5)
            .padding(.trailing, Space.sp5)
            Rectangle().fill(Palette.hairline).frame(height: 1)
        }
        .background { WindowStrip() }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        // Where the sidebar is hidden the header is the window's top strip,
        // and the traffic lights are centred in it; above 1024 the sidebar's
        // head is. Declared as a value rather than a modifier put on or taken
        // off, so crossing the breakpoint keeps the header — and an open
        // popover in it — as it is.
        .preference(key: WindowStripHeightKey.self, value: layout.maxWidth1023 && height > 0 ? height : nil)
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: S.sessionTitle(session))
                .css(FontSize.fs17, weight: .semibold, lineHeight: 1.4, tracking: -0.015)
                .lineLimit(1)
                .truncationMode(.tail)
                .accessibilityAddTraits(.isHeader)
            Text(verbatim: subline)
                .css(FontSize.fs12, lineHeight: 1.45, mono: true)
                .foregroundStyle(Palette.inkTertiary)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// `device:~/path · branch`, as the template literal writes it.
    private var subline: String {
        let path = "\(deviceName):\(Format.tildePath(session.cwd))"
        guard let git = session.git else { return path }
        return "\(path) · \(git.branch ?? "null")"
    }
}

/// `.pill.quiet.usage-chip`: the tokens the session has spent and how long
/// its turn has run, ticking once a second while it runs.
private struct UsageChip: View {
    let session: Session

    var body: some View {
        TimelineView(.periodic(from: .now, by: session.turn == nil ? 3600 : 1)) { context in
            let usage = ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple,
                                        now: Int64(context.date.timeIntervalSince1970 * 1000)).usage
            if let usage {
                Text(verbatim: usage)
                    .css(FontSize.fs12, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
                    .fixedSize()
                    .padding(.horizontal, 2)
                    .frame(height: 28)
            }
        }
    }
}

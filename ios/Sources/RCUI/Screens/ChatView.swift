import SwiftUI
import Combine
import RCCore

/// One conversation: the transcript, the status line and the composer.
struct ChatView: View {
    /// The navigation path carries the session key; the session itself is
    /// resolved from the live list on every render.
    let sessionKey: String
    @Environment(AppModel.self) private var model
    @State private var showsTodos = false
    @State private var showsQueue = false
    @State private var showsSettings = false
    @State private var elapsed = ""

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Group {
            if let chat = model.chat, chat.key == sessionKey {
                content(chat)
            } else if session == nil {
                EmptyStateView(symbol: "bubble.left.and.exclamationmark.bubble.right",
                               title: "This session is gone",
                               message: "The gateway no longer lists it. It may have been deleted on the device.")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .pageBackground()
        .navigationTitle(title)
        .inlineNavigationTitle()
        .toolbar { toolbar }
        .hideTabBar()
        // Reading is the usual reason to touch this screen, so a tap
        // anywhere off the message field puts the keyboard away.
        .dismissesKeyboardOnBackgroundTap()
        .task {
            guard model.chat?.key != sessionKey, let session else { return }
            await model.open(session)
        }
        .onDisappear { Task { await model.closeChat() } }
        .onReceive(tick) { _ in updateElapsed() }
    }

    private var session: Session? { model.session(key: sessionKey) }

    private var title: String {
        let name = model.chat?.session.title ?? session?.title ?? ""
        return name.isEmpty ? "Session" : name
    }

    private func content(_ chat: ChatStore) -> some View {
        VStack(spacing: 0) {
            SubtitleBar(session: chat.session, device: model.device(for: chat.session),
                        elapsed: elapsed, todos: chat.timeline.todos, showsTodos: $showsTodos)
            if let pending = chat.unconfirmedSend {
                NoticeBanner(text: "Delivery unconfirmed. Nothing was resent automatically.",
                             tint: Theme.attention,
                             actionTitle: "Retry",
                             action: { Task { await chat.retry(pending) } },
                             dismiss: { chat.dismiss(pending) })
            }
            if let error = chat.errorMessage {
                NoticeBanner(text: error, dismiss: { chat.clearError() })
            }
            Transcript(chat: chat)
            StatusLine(chat: chat)
            Composer(chat: chat, showsQueue: $showsQueue, showsSettings: $showsSettings)
        }
        .sheet(isPresented: $showsQueue) { QueueSheet(chat: chat) }
        .sheet(isPresented: $showsSettings) {
            SessionSettingsSheet(chat: chat, agent: model.agent(for: chat.session))
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .trailingBar) {
            if let chat = model.chat, chat.key == sessionKey, chat.canStop {
                Button { Task { await chat.stop() } } label: {
                    Label("Stop", systemImage: "stop.circle").font(.footnote)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.ink)
                .accessibilityIdentifier("chat.stop")
            }
        }
    }

    private func updateElapsed() {
        guard let seconds = model.chat?.elapsedSinceTurnStart else { elapsed = ""; return }
        elapsed = RelativeTime.duration(milliseconds: Int(seconds * 1000))
    }
}

/// `device · working directory · branch`, plus the todo and usage chips. They
/// live here rather than in the navigation bar so neither ever truncates.
private struct SubtitleBar: View {
    let session: Session
    let device: Device?
    let elapsed: String
    let todos: [TodoItem]
    @Binding var showsTodos: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            HStack(spacing: Theme.Space.tight) {
                StatusLabel(state: session.state, text: session.statusLabel)
                    .layoutPriority(2)
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                Text(device?.name ?? session.deviceID)
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .lineLimit(1)
                    .layoutPriority(1)
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                CodeText(session.cwd, font: Theme.Text.metaMono)
                if let branch = session.git?.branch {
                    Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                    Text(branch)
                        .font(Theme.Text.metaMono)
                        .foregroundStyle(Theme.inkSecondary)
                        .lineLimit(1)
                        .layoutPriority(1)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)

            if session.todos != nil || session.usage != nil {
                HStack(spacing: Theme.Space.tight) {
                    if let counts = session.todos, counts.total > 0 {
                        Button { showsTodos = true } label: {
                            Label("Todos \(counts.done)/\(counts.total)", systemImage: "checklist")
                                .font(.caption)
                        }
                        .buttonStyle(ChipButtonStyle())
                        .popover(isPresented: $showsTodos) {
                            TodoPopover(items: todos).presentationCompactAdaptation(.popover)
                        }
                        .accessibilityIdentifier("chat.todos")
                    }
                    if let usage = session.usage, usage.totalTokens > 0 {
                        Text(metrics(usage))
                            .font(Theme.Text.caption)
                            .foregroundStyle(Theme.inkSecondary)
                            .accessibilityLabel("\(usage.totalTokens) tokens used")
                            .accessibilityIdentifier("chat.usage")
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(.horizontal, Theme.Space.page)
        .padding(.bottom, Theme.Space.small)
        .background(Theme.canvas)
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.hairline).frame(height: 0.5) }
    }

    private func metrics(_ usage: SessionUsage) -> String {
        let tokens = RelativeTime.compactCount(usage.totalTokens)
        return elapsed.isEmpty ? tokens : "\(tokens) · \(elapsed)"
    }
}

/// The scrolling transcript. It follows the newest content only while the
/// reader is at the foot of it, and offers a way back down whenever they are
/// not. Paging history keeps the row the reader was looking at in place.
private struct Transcript: View {
    let chat: ChatStore
    @State private var anchor: String?
    /// Until when the geometry the scroll view reports belongs to an animation
    /// this view started rather than to the reader. Without it a long row
    /// arriving at the tail would flash the jump button on its way down. It is
    /// a deadline rather than a flag so a scroll that lands a point short can
    /// never pin the button away for good.
    @State private var settlesAt = Date.distantPast

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Theme.Space.medium) {
                    if chat.timeline.hasMoreHistory {
                        Button {
                            anchor = chat.timeline.renderable.first?.id
                            Task {
                                await chat.loadHistory()
                                if let anchor { proxy.scrollTo(anchor, anchor: .top) }
                            }
                        } label: {
                            HStack {
                                Spacer()
                                if chat.isLoadingHistory {
                                    ProgressView().controlSize(.small)
                                } else {
                                    Text("Load earlier messages").font(.footnote)
                                }
                                Spacer()
                            }
                            .frame(minHeight: Theme.Touch.minimum)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Theme.inkSecondary)
                        .accessibilityIdentifier("chat.loadOlder")
                    }
                    ForEach(chat.timeline.roots) { entry in
                        TimelineRow(entry: entry, chat: chat)
                            .id(entry.id)
                    }
                    Color.clear.frame(height: 1).id(Self.tailID)
                }
                .padding(.horizontal, Theme.Space.page)
                .padding(.vertical, Theme.Space.medium)
            }
            .scrollDismissesKeyboard(.interactively)
            .accessibilityIdentifier("chat.transcript")
            // A conversation opens at its newest message, cached or streamed.
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            .onScrollGeometryChange(for: TailGeometry.self) { geometry in
                TailGeometry(geometry)
            } action: { previous, current in
                // Content that grew, or a container the keyboard shrank, is not
                // the reader moving. Someone at the foot of the transcript
                // stays there; someone reading history is left where they are.
                guard current.maximumOffset == previous.maximumOffset else {
                    // Someone at the foot of the transcript stays there, and a
                    // range that shrank until there is nothing left to scroll
                    // puts a reader who was away back at the bottom.
                    if chat.isFollowingTail { scrollToTail(proxy) }
                    else if current.isAtBottom { chat.isFollowingTail = true }
                    return
                }
                // While a scroll this view started is still running, the only
                // thing its geometry can say is that it arrived.
                guard settlesAt < Date.now else {
                    if current.isAtBottom { chat.isFollowingTail = true }
                    return
                }
                chat.isFollowingTail = current.isAtBottom
            }
            .onChange(of: chat.timeline.lastSeq) { _, _ in
                guard chat.isFollowingTail else { return }
                scrollToTail(proxy)
            }
            .overlay(alignment: .bottomTrailing) {
                Group {
                    if !chat.isFollowingTail {
                        JumpToLatestButton(chat: chat) { scrollToTail(proxy) }
                            .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    }
                }
                .animation(.easeInOut(duration: 0.18), value: chat.isFollowingTail)
                .padding(.trailing, Theme.Space.page)
                .padding(.bottom, Theme.Space.small)
            }
        }
    }

    private func scrollToTail(_ proxy: ScrollViewProxy) {
        settlesAt = Date.now.addingTimeInterval(0.45)
        chat.isFollowingTail = true
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.tailID, anchor: .bottom) }
    }

    private static let tailID = "chat.tail"
}

/// The three numbers the tail rule needs, pulled out of `ScrollGeometry` so the
/// geometry callback compares values it can equate.
private struct TailGeometry: Equatable {
    var contentHeight: Double
    var containerHeight: Double
    var offset: Double

    init(_ geometry: ScrollGeometry) {
        contentHeight = geometry.contentSize.height
            + geometry.contentInsets.top + geometry.contentInsets.bottom
        containerHeight = geometry.containerSize.height
        offset = geometry.contentOffset.y + geometry.contentInsets.top
    }

    /// How far the content can be scrolled. It changes when rows arrive and
    /// when the keyboard resizes the container, and at no other time.
    var maximumOffset: Double { max(0, contentHeight - containerHeight) }

    var isAtBottom: Bool {
        ScrollTail.isAtBottom(contentHeight: contentHeight,
                              containerHeight: containerHeight, offset: offset)
    }
}

/// The way back down, in the corner of the timeline above the message field.
/// It is on screen whenever the reader is not at the bottom, and carries what
/// arrived while they were away.
private struct JumpToLatestButton: View {
    let chat: ChatStore
    let action: () -> Void

    private var badge: String? { ScrollTail.badge(updates: chat.updatesWhileAway) }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Theme.Space.tight) {
                Image(systemName: "arrow.down").font(.footnote.weight(.semibold))
                if let badge {
                    Text(badge).font(.footnote.weight(.medium)).monospacedDigit()
                }
            }
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, badge == nil ? 0 : Theme.Space.small)
            .frame(minWidth: Theme.Touch.minimum, minHeight: Theme.Touch.minimum)
            .background(Theme.surface, in: Capsule())
            // A floating control has to lift off the transcript, and the design
            // spends that budget on a soft shadow rather than on an edge.
            .shadow(color: Theme.ink.opacity(0.12), radius: 6, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Jump to latest")
        .accessibilityValue(ScrollTail.spokenBadge(updates: chat.updatesWhileAway) ?? "")
        .accessibilityIdentifier("chat.jumpToLatest")
    }
}

/// "Claude Code is working · your message will be queued" and its siblings.
///
/// Amendment A10: takeover is offered only on a `terminal` session whose agent
/// advertises the capability, and a terminal session the device could attach to
/// says how to make the next run controllable from here.
private struct StatusLine: View {
    let chat: ChatStore

    var body: some View {
        if chat.statusLine != nil || chat.attachHint != nil {
            VStack(alignment: .leading, spacing: Theme.Space.tight) {
                if let text = chat.statusLine {
                    HStack(spacing: Theme.Space.tight) {
                        StatusDot(state: chat.session.state, size: 6)
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSecondary)
                            .accessibilityIdentifier("chat.status")
                        Spacer(minLength: 0)
                        if chat.canTakeover {
                            Button("Take over") { Task { await chat.takeover() } }
                                .buttonStyle(ChipButtonStyle())
                                .accessibilityIdentifier("chat.takeover")
                        }
                    }
                }
                if let hint = chat.attachHint {
                    AttachHintLine(hint: hint)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.Space.page)
            .padding(.vertical, Theme.Space.tight)
        }
    }
}

/// Amendment A10: what a terminal session would need before this app could
/// control it. One line, under the takeover bar, never a call to action.
private struct AttachHintLine: View {
    let hint: ChatStore.AttachHint

    var body: some View {
        label
            .font(.caption)
            .foregroundStyle(Theme.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("chat.attachHint")
    }

    private var label: Text {
        switch hint {
        case .installShim:
            Text("Start claude through the remote-control shim to control it from here")
        case .startDaemon:
            Text("Start the Codex app-server daemon on this device to control it from here")
        case .restartSession:
            Text("This terminal session was started without the attachment; restart it to control it from here")
        }
    }
}

private struct TodoPopover: View {
    let items: [TodoItem]

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            ForEach(items) { item in
                HStack(alignment: .top, spacing: Theme.Space.small) {
                    Image(systemName: symbol(item.status))
                        .foregroundStyle(item.status == .completed ? Theme.ink : Theme.resting)
                    Text(item.text)
                        .font(.subheadline)
                        .foregroundStyle(item.status == .completed ? Theme.inkSecondary : Theme.ink)
                        .strikethrough(item.status == .completed)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(Theme.Space.medium)
        .frame(minWidth: 260, alignment: .leading)
        .accessibilityIdentifier("chat.todoList")
    }

    private func symbol(_ status: TodoStatus) -> String {
        switch status {
        case .completed: "checkmark.circle.fill"
        case .inProgress: "circle.lefthalf.filled"
        default: "circle"
        }
    }
}

/// Messages waiting behind the current turn.
struct QueueSheet: View {
    let chat: ChatStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(chat.timeline.queue) { message in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(message.text)
                            .font(.subheadline)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(RelativeTime.short(since: message.ts))
                            .font(.caption)
                            .foregroundStyle(Theme.inkSecondary)
                    }
                    .swipeActions {
                        Button("Remove", role: .destructive) {
                            Task { await chat.removeQueued(message.id) }
                        }
                    }
                }
                if chat.timeline.queue.isEmpty {
                    Text("Nothing is queued.").font(.footnote).foregroundStyle(Theme.inkSecondary)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle("Up next")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }
}

#Preview("Chat") {
    DemoPreview {
        NavigationStack { ChatView(sessionKey: demoSession().id) }
    }
}

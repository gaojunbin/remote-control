import Testing
import Foundation
@testable import RCCore

/// The reading-position rule: the timeline follows the newest content only
/// while the reader is at the foot of it, and counts what arrives while they
/// are not.
@Suite("Reading position")
struct ScrollTailTests {
    @Test("At the bottom is the last screenful, not the last point")
    func atBottom() {
        // Scrolled all the way down.
        #expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_400))
        // A row that settled a few points short is still at the bottom.
        #expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_365))
        // Past the threshold the reader has gone looking at history.
        #expect(!ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_359))
        #expect(!ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 0))
    }

    @Test("A transcript shorter than its container has no bottom to leave")
    func shortTranscript() {
        #expect(ScrollTail.isAtBottom(contentHeight: 200, containerHeight: 600, offset: 0))
        // Rubber-banding past the end counts as the bottom, not as leaving it.
        #expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600, offset: 1_480))
    }

    @Test("The threshold is a parameter, so a caller can tighten it")
    func customThreshold() {
        #expect(ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600,
                                      offset: 1_390, threshold: 20))
        #expect(!ScrollTail.isAtBottom(contentHeight: 2_000, containerHeight: 600,
                                       offset: 1_370, threshold: 20))
    }

    @Test("The badge counts updates and stops at 99")
    func badge() {
        #expect(ScrollTail.badge(updates: 0) == nil)
        #expect(ScrollTail.badge(updates: 1) == "1")
        #expect(ScrollTail.badge(updates: 3) == "3")
        #expect(ScrollTail.badge(updates: 99) == "99")
        #expect(ScrollTail.badge(updates: 100) == "99+")
        #expect(ScrollTail.spokenBadge(updates: 0) == nil)
        #expect(ScrollTail.spokenBadge(updates: 1) == "1 new update")
        #expect(ScrollTail.spokenBadge(updates: 4) == "4 new updates")
    }

    // MARK: - What the store counts

    @MainActor
    @Test("Blocks that arrive while the reader is away are counted, and cleared on return")
    func updatesWhileAway() {
        let chat = ChatStore(session: session(), channel: DemoGateway())
        chat.receive(frame(text(seq: 1, blockID: "a-1", "first")))
        #expect(chat.updatesWhileAway == 0)

        chat.isFollowingTail = false
        chat.receive(frame(text(seq: 2, blockID: "a-2", "second")))
        chat.receive(frame(text(seq: 3, blockID: "a-3", "third")))
        #expect(chat.updatesWhileAway == 2)

        // Streaming into a block that is already there is the same update.
        chat.receive(frame(delta(seq: 4, blockID: "a-3", " and more")))
        #expect(chat.updatesWhileAway == 2)

        chat.isFollowingTail = true
        #expect(chat.updatesWhileAway == 0)
    }

    @MainActor
    @Test("A burst of tool calls is nothing at all at Simple, and one per block at Detailed")
    func updatesFollowTheDetailLevel() {
        let settings = SettingsStore(defaults: UserDefaults(suiteName: "rc-away-\(UUID().uuidString)")!)
        let chat = ChatStore(session: session(), channel: DemoGateway())
        chat.detailSource = { settings.timelineDetail }
        chat.isFollowingTail = false

        for seq in 1...4 { chat.receive(frame(tool(seq: seq, blockID: "tool-\(seq)"))) }
        #expect(chat.updatesWhileAway == 0, "the reader chose not to see these rows")
        chat.receive(frame(thinking(seq: 5, blockID: "th-1")))
        #expect(chat.updatesWhileAway == 0, "nor this one")
        chat.receive(frame(text(seq: 6, blockID: "a-1", "and here is the answer")))
        #expect(chat.updatesWhileAway == 1, "what is written to them is counted")

        settings.timelineDetail = .detailed
        for seq in 7...10 { chat.receive(frame(tool(seq: seq, blockID: "tool-\(seq)"))) }
        #expect(chat.updatesWhileAway == 5, "at Detailed the same burst is one per block")
    }

    @MainActor
    @Test("Sending returns the transcript to the tail")
    func sendingFollowsTheTail() async {
        let gateway = DemoGateway()
        guard let live = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.liveSessionID
        }) else {
            Issue.record("the demo live session exists")
            return
        }
        let chat = ChatStore(session: live, channel: gateway)
        chat.isFollowingTail = false
        chat.draft = "back to the bottom"
        await chat.send()
        #expect(chat.isFollowingTail)
        #expect(chat.updatesWhileAway == 0)
    }

    // MARK: - Fixtures

    private func session() -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                cwd: "/tmp", state: .running)
    }

    private func frame(_ event: SessionEvent) -> AppFrame {
        .sessionEvent(sessionID: "s", deviceID: "d", event: event)
    }

    private func text(seq: Int, blockID: String, _ body: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(1_788_944_400_000 + seq),
                     kind: SessionEvent.assistantTextKind, blockID: blockID,
                     body: .assistantText(StreamTextPayload(text: body, done: true)))
    }

    private func tool(seq: Int, blockID: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(1_788_944_400_000 + seq),
                     kind: SessionEvent.toolCallKind, blockID: blockID,
                     body: .toolCall(ToolCallPayload(tool: "Read", kind: .read,
                                                     title: "one file", status: .succeeded)))
    }

    private func thinking(seq: Int, blockID: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(1_788_944_400_000 + seq),
                     kind: SessionEvent.thinkingKind, blockID: blockID,
                     body: .thinking(StreamTextPayload(text: "a shared clock", done: true)))
    }

    private func delta(seq: Int, blockID: String, _ body: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(1_788_944_400_000 + seq),
                     kind: SessionEvent.assistantTextKind, blockID: blockID,
                     body: .assistantText(StreamTextPayload(delta: body, done: false)))
    }
}

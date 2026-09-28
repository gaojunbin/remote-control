import Foundation
import RCCore
import Testing
@testable import RCMac

/// Events as a device sends them, for the transcript suites.
enum ChatEvents {
    static func user(_ seq: Int, _ id: String, _ text: String, source: EventSource = .remote) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.userMessageKind, blockID: id,
                     body: .userMessage(UserMessagePayload(text: text, source: source)))
    }

    static func text(_ seq: Int, _ id: String, _ text: String, parent: String? = nil) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.assistantTextKind, blockID: id, parentBlockID: parent,
                     body: .assistantText(StreamTextPayload(text: text, done: true)))
    }

    static func thinking(_ seq: Int, _ id: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.thinkingKind, blockID: id,
                     body: .thinking(StreamTextPayload(text: "weighing it up", done: true)))
    }

    static func tool(_ seq: Int, _ id: String, tool: String = "Task", kind: ToolKind = .subagent,
                     status: ToolStatus = .running) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.toolCallKind, blockID: id,
                     body: .toolCall(ToolCallPayload(tool: tool, kind: kind, title: tool == "Task" ? "Audit clocks" : tool,
                                                     status: status, startedAt: Int64(seq))))
    }

    static func approval(_ seq: Int, _ id: String, parent: String? = nil) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.approvalKind, blockID: id, parentBlockID: parent,
                     body: .approval(ApprovalPayload(requestID: "req-a", tool: "Bash", kind: .shell, title: "git commit",
                                                     options: [ApprovalOption(id: "allow", label: "Allow once",
                                                                              style: .primary)])))
    }

    static func notice(_ seq: Int) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.noticeKind,
                     body: .notice(NoticePayload(level: .info, text: "the device reconnected")))
    }

    static func turnEnded(_ seq: Int, _ reason: StopReason) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: SessionEvent.turnCompletedKind,
                     body: .turnCompleted(TurnCompletedPayload(turnID: "t\(seq)", stopReason: reason, durationMS: 1)))
    }

    static func state(_ seq: Int, _ body: SessionEventBody, kind: String) -> SessionEvent {
        SessionEvent(seq: seq, ts: Int64(seq), kind: kind, body: body)
    }

    static func timeline(_ events: [SessionEvent]) -> Timeline {
        var timeline = Timeline()
        for event in events { timeline.apply(event) }
        return timeline
    }
}

/// `web/tests/timeline.test.ts` § "detail levels" and the reducer's rules the
/// selection carries over RCCore's transcript.
@Suite("Chat transcript selection") @MainActor
struct ChatTranscriptSelectionTests {
    /// The web's `conversation()` fixture.
    private var conversation: Timeline {
        ChatEvents.timeline([
            ChatEvents.user(1, "u1", "fix it"),
            ChatEvents.thinking(2, "th1"),
            ChatEvents.tool(3, "task1"),
            ChatEvents.text(4, "sub1", "the sub-agent reported back", parent: "task1"),
            ChatEvents.approval(5, "ap1", parent: "task1"),
            ChatEvents.text(6, "a1", "done"),
            ChatEvents.notice(7)
        ])
    }

    private func roots(_ selection: TranscriptSelection) -> [String] { selection.roots.map(\.id) }

    @Test func detailedDrawsTheWholeTranscript() {
        let selection = TranscriptSelection(timeline: conversation, detail: .detailed)
        #expect(roots(selection) == ["u1", "th1", "task1", "a1", "seq:7"])
        #expect(selection.children["task1"]?.map(\.id) == ["sub1", "ap1"])
    }

    @Test func simpleDropsTheWorkingsInPlaceNotCollapsed() {
        let selection = TranscriptSelection(timeline: conversation, detail: .simple)
        #expect(roots(selection) == ["u1", "ap1", "a1", "seq:7"])
        #expect(selection.children.isEmpty)
    }

    @Test func aCardWaitingOnAnAnswerComesUpOutOfAHiddenToolCall() {
        let ids = roots(TranscriptSelection(timeline: conversation, detail: .simple))
        #expect(ids.contains("ap1"))
        #expect(!ids.contains("sub1"))
    }

    @Test func aSlashCommandsOwnOutputIsDrawnAtBothLevels() {
        var timeline = conversation
        timeline.apply(ChatEvents.tool(8, "cmd1", tool: "/usage", kind: .other, status: .succeeded))
        for detail in [TimelineDetail.simple, .detailed] {
            #expect(roots(TranscriptSelection(timeline: timeline, detail: detail)).contains("cmd1"))
        }
    }

    @Test func anUnconfirmedSendIsDrawnLastAtBothLevels() {
        var timeline = conversation
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "and again"))
        for detail in [TimelineDetail.simple, .detailed] {
            #expect(TranscriptSelection(timeline: timeline, detail: detail).roots.last?.id == "req-1")
        }
    }

    @Test func anInterruptedTurnIsARowAndACleanOneIsNot() {
        var timeline = conversation
        timeline.apply(ChatEvents.turnEnded(8, .completed))
        timeline.apply(ChatEvents.turnEnded(9, .interrupted))
        for detail in [TimelineDetail.simple, .detailed] {
            let turns = TranscriptSelection(timeline: timeline, detail: detail).roots.filter {
                if case .entry(let entry) = $0, entry.turnCompleted != nil { return true }
                return false
            }
            #expect(turns.map(\.id) == ["seq:9"])
        }
    }

    @Test func stateOnlyKindsAreNoRows() {
        let timeline = ChatEvents.timeline([
            ChatEvents.state(1, .status(StatusPayload(state: .running)), kind: SessionEvent.statusKind),
            ChatEvents.state(2, .meta(MetaPayload(title: "Renamed")), kind: SessionEvent.metaKind),
            ChatEvents.state(3, .queue(QueuePayload(pending: [])), kind: SessionEvent.queueKind),
            ChatEvents.state(4, .todos(TodosPayload(items: [])), kind: SessionEvent.todosKind),
            ChatEvents.state(5, .turnStarted(TurnStartedPayload(turnID: "x", trigger: .remote)),
                             kind: SessionEvent.turnStartedKind),
            ChatEvents.turnEnded(6, .completed)
        ])
        let selection = TranscriptSelection(timeline: timeline, detail: .detailed)
        #expect(selection.roots.isEmpty)
        #expect(selection.rowCount == 0)
        #expect(selection.newestSeq == 6)
    }

    @Test func anotherAgentsWordsAreItsWorkings() {
        let timeline = ChatEvents.timeline([
            ChatEvents.user(1, "u1", "fix it"),
            ChatEvents.user(2, "u2", "recon-ios: Recon complete.", source: .agent)
        ])
        #expect(roots(TranscriptSelection(timeline: timeline, detail: .detailed)) == ["u1", "u2"])
        #expect(roots(TranscriptSelection(timeline: timeline, detail: .simple)) == ["u1"])
    }

    @Test func theMomentOfResumingIsNoRow() {
        let timeline = ChatEvents.timeline([
            ChatEvents.state(1, .resume(ResumePayload(status: .scheduled, at: 10)), kind: SessionEvent.resumeKind),
            ChatEvents.state(2, .resume(ResumePayload(status: .fired)), kind: SessionEvent.resumeKind)
        ])
        #expect(roots(TranscriptSelection(timeline: timeline, detail: .simple)) == ["seq:1"])
    }

    @Test func theKeysAreTheFirstAndTheLastDrawnRow() {
        let selection = TranscriptSelection(timeline: conversation, detail: .simple)
        #expect(selection.firstKey == "u1")
        #expect(selection.lastKey == "seq:7")
    }

    /// A row the web renders `null` for takes no gap in the column.
    @Test func anAnswerWithNoTextYetDrawsNothing() {
        let empty = SessionEvent(seq: 1, ts: 1, kind: SessionEvent.assistantTextKind, blockID: "a0",
                                 body: .assistantText(StreamTextPayload(delta: "", done: false)))
        let timeline = ChatEvents.timeline([empty])
        let selection = TranscriptSelection(timeline: timeline, detail: .detailed)
        #expect(selection.roots.filter(TranscriptRow.draws).isEmpty)
    }
}

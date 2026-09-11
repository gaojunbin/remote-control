import Testing
import Foundation
@testable import RCCore

@Suite("Timeline reducer")
struct TimelineTests {
    private func event(_ seq: Int, _ kind: String, _ fields: [String: JSONValue]) throws -> SessionEvent {
        var object = fields
        object["seq"] = .integer(Int64(seq))
        object["ts"] = .integer(Int64(1_788_944_400_000 + seq))
        object["kind"] = .string(kind)
        return try JSONValue.object(object).decode(SessionEvent.self)
    }

    @Test("Streaming deltas accumulate into one block")
    func streamingAppends() throws {
        var timeline = Timeline()
        timeline.apply(try event(1, "assistant_text", ["block_id": "a", "delta": "Hello", "done": false]))
        timeline.apply(try event(2, "assistant_text", ["block_id": "a", "delta": " there", "done": false]))
        #expect(timeline.entries.count == 1)
        #expect(timeline.entry(id: "a")?.text == "Hello there")
        #expect(timeline.entry(id: "a")?.isStreaming == true)

        timeline.apply(try event(3, "assistant_text", ["block_id": "a", "text": "Hello there.", "done": true]))
        #expect(timeline.entry(id: "a")?.text == "Hello there.")
        #expect(timeline.entry(id: "a")?.isStreaming == false)
    }

    @Test("A later event for a block replaces it and keeps its position")
    func replacementKeepsPosition() throws {
        var timeline = Timeline()
        timeline.apply(try event(1, "tool_call", ["block_id": "b", "tool": "Bash", "tool_kind": "shell",
                                                  "title": "pytest", "status": "running"]))
        timeline.apply(try event(2, "notice", ["level": "info", "text": "still running"]))
        timeline.apply(try event(3, "tool_call", ["block_id": "b", "tool": "Bash", "tool_kind": "shell",
                                                  "title": "pytest", "status": "succeeded"]))
        #expect(timeline.entries.count == 2)
        #expect(timeline.entries.first?.id == "b")
        #expect(timeline.entry(id: "b")?.toolCall?.status == .succeeded)
    }

    @Test("Late and duplicate frames are dropped", arguments: [3, 5])
    func staleFramesDropped(seq: Int) throws {
        var timeline = Timeline()
        timeline.apply(try event(5, "notice", ["level": "info", "text": "first"]))
        #expect(!timeline.apply(try event(seq, "notice", ["level": "info", "text": "late"])))
        #expect(timeline.entries.count == 1)
        #expect(timeline.lastSeq == 5)
    }

    @Test("History prepends without moving the live cursor or clobbering newer blocks")
    func historyMerge() throws {
        var timeline = Timeline()
        timeline.apply(try event(20, "assistant_text", ["block_id": "a", "text": "newest", "done": true]))
        timeline.prependHistory([
            try event(10, "user_message", ["block_id": "u", "text": "older"]),
            try event(12, "assistant_text", ["block_id": "a", "text": "stale", "done": true])
        ], hasMore: true)
        #expect(timeline.entries.first?.id == "u")
        #expect(timeline.entry(id: "a")?.text == "newest")
        #expect(timeline.lastSeq == 20)
        #expect(timeline.oldestSeq == 10)
    }

    @Test("Todos and queue are snapshots, not rows")
    func snapshotsAreNotRows() throws {
        var timeline = Timeline()
        timeline.apply(try event(1, "todos", ["items": [["id": "1", "text": "a", "status": "pending"]]]))
        timeline.apply(try event(2, "queue", ["pending": [["id": "q", "text": "later", "ts": 1]]]))
        #expect(timeline.entries.isEmpty)
        #expect(timeline.todos.count == 1)
        #expect(timeline.queue.count == 1)
        #expect(timeline.lastSeq == 2)
    }

    @Test("A resolved approval stops being actionable")
    func approvalLifecycle() throws {
        var timeline = Timeline()
        let options: JSONValue = [["id": "allow", "label": "Allow", "style": "primary"],
                                  ["id": "deny", "label": "Deny", "style": "danger"]]
        timeline.apply(try event(1, "approval", ["block_id": "ap", "request_id": "r", "tool": "Bash",
                                                 "tool_kind": "shell", "title": "rm -rf",
                                                 "options": options, "status": "pending"]))
        #expect(timeline.pendingRequest?.id == "ap")
        timeline.apply(try event(2, "approval", ["block_id": "ap", "request_id": "r", "tool": "Bash",
                                                 "tool_kind": "shell", "title": "rm -rf",
                                                 "options": options, "status": "resolved",
                                                 "decision": ["option_id": "allow", "by": "remote"]]))
        #expect(timeline.pendingRequest == nil)
    }

    @Test("Sub-agent rows hang under their parent tool call")
    func nestedRows() throws {
        var timeline = Timeline()
        timeline.apply(try event(1, "tool_call", ["block_id": "task", "tool": "Task",
                                                  "tool_kind": "subagent", "title": "Audit", "status": "running"]))
        timeline.apply(try event(2, "assistant_text", ["block_id": "sub", "parent_block_id": "task",
                                                       "text": "found it", "done": true]))
        #expect(timeline.roots.count == 1)
        #expect(timeline.children(of: "task").count == 1)
    }

    // MARK: - Two levels of detail

    /// A transcript with one of everything the two levels disagree about.
    private func mixedTimeline() throws -> Timeline {
        let options: JSONValue = [["id": "allow", "label": "Allow", "style": "primary"],
                                  ["id": "deny", "label": "Deny", "style": "danger"]]
        var timeline = Timeline()
        timeline.apply(try event(1, "turn_started", ["turn_id": "t", "trigger": "remote"]))
        timeline.apply(try event(2, "user_message", ["block_id": "u", "text": "fix the flake"]))
        timeline.apply(try event(3, "thinking", ["block_id": "th", "text": "a shared clock", "done": true]))
        timeline.apply(try event(4, "assistant_text", ["block_id": "a", "text": "Reproducing.", "done": true]))
        timeline.apply(try event(5, "tool_call", ["block_id": "task", "tool": "Task", "tool_kind": "subagent",
                                                  "title": "Audit", "status": "running"]))
        timeline.apply(try event(6, "assistant_text", ["block_id": "sub", "parent_block_id": "task",
                                                       "text": "found it", "done": true]))
        timeline.apply(try event(7, "approval", ["block_id": "ap", "parent_block_id": "task",
                                                 "request_id": "r", "tool": "Bash", "tool_kind": "shell",
                                                 "title": "rm -rf build", "options": options,
                                                 "status": "pending"]))
        timeline.apply(try event(8, "todos", ["items": [["id": "1", "text": "a", "status": "pending"]]]))
        timeline.apply(try event(9, "notice", ["level": "warn", "text": "the model was switched"]))
        timeline.apply(try event(10, "error", ["message": "the device went away"]))
        timeline.apply(try event(11, "turn_completed", ["turn_id": "t", "stop_reason": "completed",
                                                        "duration_ms": 4_000]))
        timeline.apply(try event(12, "turn_completed", ["turn_id": "t2", "stop_reason": "interrupted",
                                                        "duration_ms": 900]))
        return timeline
    }

    @Test("Detailed is the whole transcript")
    func detailedDrawsEverything() throws {
        let timeline = try mixedTimeline()
        #expect(timeline.roots(at: .detailed).map(\.id) == timeline.roots.map(\.id))
        #expect(timeline.roots(at: .detailed).map(\.id)
                == ["seq:1", "u", "th", "a", "task", "seq:9", "seq:10", "seq:11", "seq:12"])
        #expect(timeline.children(of: "task", at: .detailed).map(\.id) == ["sub", "ap"])
    }

    @Test("Simple draws only what is written to the reader")
    func simpleDropsTheAgentsOwnWork() throws {
        let timeline = try mixedTimeline()
        let rows = timeline.roots(at: .simple).map(\.id)
        // The message, the prose, the approval, the notice, the error and the
        // turn that was stopped. Not thinking, not the tool call, not the
        // sub-agent's prose under it, not a turn that simply finished.
        #expect(rows == ["u", "a", "ap", "seq:9", "seq:10", "seq:12"])
        #expect(timeline.children(of: "task", at: .simple).isEmpty)
        // Nothing was dropped from the store, so the other level still has it.
        #expect(timeline.entries.count == 11)
        #expect(timeline.todos.count == 1)
    }

    @Test("A message this app has sent is drawn at either level")
    func pendingRowsSurviveTheFilter() throws {
        var timeline = try mixedTimeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "and the CI runner"))
        #expect(timeline.roots(at: .simple).last?.pending?.id == "req-1")
        #expect(timeline.roots(at: .detailed).last?.pending?.id == "req-1")
    }

    @MainActor
    @Test("The level is a preference of this device, and Simple is the default")
    func detailPreference() {
        let defaults = UserDefaults(suiteName: "rc-detail-\(UUID().uuidString)")!
        let settings = SettingsStore(defaults: defaults)
        #expect(settings.timelineDetail == .simple)
        settings.timelineDetail = .detailed
        #expect(SettingsStore(defaults: defaults).timelineDetail == .detailed)
        #expect(TimelineDetail.allCases.map(\.title) == ["Simple", "Detailed"])
        #expect(TimelineDetail.footnote == "Simple shows only what is written to you. "
                + "Detailed adds thinking, tool calls and the task list.")
    }
}

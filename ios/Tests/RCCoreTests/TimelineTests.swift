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
}

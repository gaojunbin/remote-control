import Testing
import Foundation
@testable import RCCore

@Suite("Review fixes")
struct ReviewFixTests {
    private func event(_ seq: Int, _ kind: String, _ fields: [String: JSONValue] = [:]) throws -> SessionEvent {
        var object = fields
        object["seq"] = .integer(Int64(seq))
        object["ts"] = .integer(Int64(seq))
        object["kind"] = .string(kind)
        if kind == SessionEvent.noticeKind {
            object["level"] = "info"
            object["text"] = "x"
        }
        return try JSONValue.object(object).decode(SessionEvent.self)
    }

    @Test("A skipped seq is detected, and a refill clears it")
    func gapDetection() throws {
        var timeline = Timeline()
        timeline.apply(try event(41, "notice"))
        #expect(!timeline.hasGap)
        timeline.apply(try event(42, "notice"))
        #expect(!timeline.hasGap)
        timeline.apply(try event(44, "notice"))
        #expect(timeline.hasGap)
        timeline.clearGap()
        #expect(!timeline.hasGap)
    }

    @Test("The first event of a session is not mistaken for a gap")
    func noGapOnFirstEvent() throws {
        var timeline = Timeline()
        timeline.apply(try event(500, "notice"))
        #expect(!timeline.hasGap)
    }

    @Test("A cached transcript can hand its cursor to a warm open")
    func cursorAdoption() throws {
        var timeline = Timeline()
        timeline.prependHistory([try event(10, "notice"), try event(12, "notice")], hasMore: true)
        #expect(timeline.lastSeq == 0)
        timeline.adoptCursor(12)
        #expect(timeline.lastSeq == 12)
        timeline.adoptCursor(5)
        #expect(timeline.lastSeq == 12)
    }

    @Test("Sub-agent rows resolve through the child index, in order")
    func childIndex() throws {
        var timeline = Timeline()
        timeline.apply(try event(1, "tool_call", ["block_id": "task", "tool": "Task",
                                                  "tool_kind": "subagent", "title": "Audit",
                                                  "status": "running"]))
        for (index, seq) in [2, 3, 4].enumerated() {
            let fields: [String: JSONValue] = [
                "block_id": .string("sub\(index)"), "parent_block_id": "task",
                "text": .string("line \(index)"), "done": true
            ]
            timeline.apply(try event(seq, "assistant_text", fields))
        }
        #expect(timeline.children(of: "task").count == 3)
        #expect(timeline.children(of: "task").map(\.text) == ["line 0", "line 1", "line 2"])
        #expect(timeline.roots.count == 1)

        // A history page rebuilds the index without losing a child.
        timeline.prependHistory([try event(0, "user_message", ["block_id": "u", "text": "go"])],
                                hasMore: false)
        #expect(timeline.children(of: "task").count == 3)
    }

    @Test("A send that cannot succeed says why, before the draft is cleared")
    @MainActor
    func sendBlockReasons() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T", cwd: "/tmp")
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.draft = "hello"
        #expect(chat.sendBlockReason == nil)
        #expect(chat.canSend)

        chat.connectionReady = false
        #expect(chat.sendBlockReason == "Offline · your draft is saved")
        #expect(!chat.canSend)
        chat.connectionReady = true

        chat.deviceOnline = false
        #expect(chat.sendBlockReason == "That device is offline")
        chat.deviceOnline = true

        var terminal = session
        terminal.control = .terminal
        let locked = ChatStore(session: terminal, channel: DemoGateway())
        locked.draft = "hello"
        #expect(locked.sendBlockReason == "Controlled by the terminal")
    }

    @Test("A message longer than the protocol limit is refused before it is sent")
    func textLimit() {
        let oversized = String(repeating: "a", count: RequestLimits.maxTextBytes + 1)
        #expect(throws: AttachmentError.self) {
            _ = try GatewayRequest.send(sessionID: "s", text: oversized)
        }
        #expect(throws: Never.self) {
            _ = try GatewayRequest.send(sessionID: "s",
                                        text: String(repeating: "a", count: RequestLimits.maxTextBytes))
        }
    }

    @Test("Two attachments with the same name stay distinguishable")
    func attachmentIdentity() {
        let first = OutboundAttachment(name: "notes.png", mime: "image/png", data: Data([1]))
        let second = OutboundAttachment(name: "notes.png", mime: "image/png", data: Data([2]))
        #expect(first.id != second.id)
        var attachments = [first, second]
        attachments.removeAll { $0.id == first.id }
        #expect(attachments.count == 1)
        #expect(attachments.first?.data == Data([2]))
    }

    @Test("Cache file names cannot collide across similar origins", arguments: [
        ("https://a.com", "https://a-com"),
        ("https://rc.example.com", "https://rc.example.org"),
        (String(repeating: "https://very-long-host.example.com/", count: 3) + "a",
         String(repeating: "https://very-long-host.example.com/", count: 3) + "b")
    ])
    func slugCollisions(first: String, second: String) {
        #expect(LocalCache.slug(first) != LocalCache.slug(second))
    }

    @Test("An endpoint placeholder exists so nothing force-unwraps one")
    func endpointPlaceholder() {
        #expect(GatewayEndpoint.placeholder.isSecure)
        #expect(!GatewayEndpoint.placeholder.origin.isEmpty)
    }
}

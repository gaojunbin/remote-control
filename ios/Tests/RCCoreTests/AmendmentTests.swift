import Testing
import Foundation
@testable import RCCore

@Suite("Protocol amendments")
struct AmendmentTests {
    // MARK: - A4, WebSocket close codes

    @Test("A close code decides whether reconnecting makes sense", arguments: [
        (4401, SocketCloseReason.unauthorized, false),
        (4403, SocketCloseReason.forbidden, false),
        (4001, SocketCloseReason.replaced, false),
        (1000, SocketCloseReason.transient, true),
        (1006, SocketCloseReason.transient, true)
    ])
    func closeCodes(code: Int, reason: SocketCloseReason, reconnects: Bool) {
        #expect(SocketCloseReason(code: code) == reason)
        #expect(SocketCloseReason(code: code).shouldReconnect == reconnects)
    }

    @Test("A close with no code is treated as transient")
    func missingCloseCode() {
        #expect(SocketCloseReason(code: nil) == .transient)
        #expect(SocketCloseReason(code: nil).shouldReconnect)
    }

    // MARK: - A5, device_id on session frames

    @Test("session.event carries an optional device id")
    func sessionEventDeviceID() throws {
        let withDevice: JSONValue = [
            "type": "session.event", "session_id": "s", "device_id": "d",
            "event": ["seq": 1, "ts": 1, "kind": "notice", "level": "info", "text": "x"]
        ]
        guard case .sessionEvent(let sessionID, let deviceID, let event) = try AppFrame(json: withDevice) else {
            Issue.record("expected a session event")
            return
        }
        #expect(sessionID == "s")
        #expect(deviceID == "d")
        #expect(event.seq == 1)

        let withoutDevice: JSONValue = [
            "type": "session.event", "session_id": "s",
            "event": ["seq": 1, "ts": 1, "kind": "notice", "level": "info", "text": "x"]
        ]
        guard case .sessionEvent(_, let missing, _) = try AppFrame(json: withoutDevice) else {
            Issue.record("expected a session event")
            return
        }
        #expect(missing == nil)
    }

    @Test("session.removed keys on the session id, with or without a device id")
    func sessionRemovedDeviceID() throws {
        guard case .sessionRemoved(let sessionID, let deviceID) =
                try AppFrame(json: ["type": "session.removed", "session_id": "s"]) else {
            Issue.record("expected a removal")
            return
        }
        #expect(sessionID == "s")
        #expect(deviceID == nil)

        guard case .sessionRemoved(_, let named) =
                try AppFrame(json: ["type": "session.removed", "session_id": "s", "device_id": "d"]) else {
            Issue.record("expected a removal")
            return
        }
        #expect(named == "d")
    }

    // MARK: - A6, snapshots outside the live stream

    @Test("The subscribe reply's queue is optional and decodes when present")
    func subscribeQueue() throws {
        let session: JSONValue = ["session_id": "s", "device_id": "d"]
        let withQueue: JSONValue = [
            "session": session, "events": [], "resync": false,
            "queue": ["pending": [["id": "q1", "text": "later", "ts": 1]]]
        ]
        #expect(try withQueue.decode(SubscribeResult.self).queue?.pending.count == 1)
        #expect(try JSONValue.object(["session": session]).decode(SubscribeResult.self).queue == nil)
    }

    @Test("An older snapshot from a history page never overwrites a newer one")
    func snapshotOrdering() throws {
        func event(_ seq: Int, _ kind: String, _ fields: [String: JSONValue]) throws -> SessionEvent {
            var object = fields
            object["seq"] = .integer(Int64(seq))
            object["ts"] = .integer(Int64(seq))
            object["kind"] = .string(kind)
            return try JSONValue.object(object).decode(SessionEvent.self)
        }

        var timeline = Timeline()
        timeline.apply(try event(10, "todos", ["items": [["id": "1", "text": "new", "status": "pending"]]]))
        timeline.prependHistory([
            try event(2, "todos", ["items": [["id": "a", "text": "old", "status": "pending"],
                                             ["id": "b", "text": "old", "status": "pending"]]])
        ], hasMore: false)
        #expect(timeline.todos.count == 1)
        #expect(timeline.todos.first?.text == "new")

        // A replayed snapshot newer than the cursor still applies.
        timeline.apply(try event(11, "queue", ["pending": [["id": "q", "text": "later", "ts": 1]]]))
        #expect(timeline.queue.count == 1)

        // The subscribe reply describes the session at the current cursor.
        timeline.applySubscribedQueue([])
        #expect(timeline.queue.isEmpty)
    }
}

@Suite("Amendments A7 and A8")
struct TerminalAndOrderingTests {
    // MARK: - A7, control decides who may type

    @Test("A terminal session is read-only whether it is running or idle",
          arguments: [SessionState.running, .readonly, .needsApproval, .idle])
    @MainActor
    func terminalControlLocksTheComposer(state: SessionState) {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: state, control: .terminal)
        let chat = ChatStore(session: session, channel: DemoGateway())
        // Amendment A10: takeover is offered only when the agent advertises it.
        chat.agent = DemoFixtures.claude
        chat.draft = "hello"
        #expect(chat.isReadOnly)
        #expect(!chat.canSend)
        #expect(!chat.canStop)
        #expect(chat.sendBlockReason == "Controlled by the terminal")
        #expect(chat.statusLine == "Controlled by the terminal · Take over to send")
    }

    @Test("A terminal-driven turn still reads as running")
    @MainActor
    func terminalRunningState() {
        let running = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: .running, control: .terminal)
        let idle = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                           cwd: "/tmp", state: .readonly, control: .terminal)
        #expect(ChatStore(session: running, channel: DemoGateway()).isRunning)
        #expect(!ChatStore(session: idle, channel: DemoGateway()).isRunning)
        #expect(running.state.isWorking)
        #expect(!idle.state.isWorking)
    }

    @Test("A session the app controls stays writable and stoppable")
    @MainActor
    func remoteControlStaysWritable() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: .running, control: .remote)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.draft = "hello"
        #expect(!chat.isReadOnly)
        #expect(chat.canSend)
        #expect(chat.canStop)
    }

    // MARK: - A8, a block holds its first position

    private func streaming(_ seq: Int, block: String, firstSeq: Int?, text: String) throws -> SessionEvent {
        var fields: [String: JSONValue] = [
            "seq": .integer(Int64(seq)), "ts": .integer(Int64(seq)),
            "kind": .string(SessionEvent.assistantTextKind),
            "block_id": .string(block), "delta": .string(text), "done": false
        ]
        if let firstSeq { fields["first_seq"] = .integer(Int64(firstSeq)) }
        return try JSONValue.object(fields).decode(SessionEvent.self)
    }

    private func notice(_ seq: Int) throws -> SessionEvent {
        try JSONValue.object(["seq": .integer(Int64(seq)), "ts": .integer(Int64(seq)),
                              "kind": .string(SessionEvent.noticeKind),
                              "level": "info", "text": "x"]).decode(SessionEvent.self)
    }

    @Test("first_seq decodes, defaults to seq, and survives a round trip")
    func firstSeqDecoding() throws {
        let withFirst = try streaming(12, block: "a", firstSeq: 10, text: "x")
        #expect(withFirst.firstSeq == 10)
        #expect(withFirst.orderSeq == 10)
        #expect(try JSONValue.encode(withFirst)["first_seq"]?.intValue == 10)

        let without = try streaming(12, block: "a", firstSeq: nil, text: "x")
        #expect(without.firstSeq == nil)
        #expect(without.orderSeq == 12)
        #expect(try JSONValue.encode(without)["first_seq"] == nil)
    }

    @Test("A streaming block keeps its place while its seq rises")
    func streamingKeepsPosition() throws {
        var timeline = Timeline()
        timeline.apply(try streaming(10, block: "a", firstSeq: 10, text: "one"))
        timeline.apply(try notice(11))
        timeline.apply(try streaming(12, block: "a", firstSeq: 10, text: " two"))
        #expect(timeline.entries.map(\.id) == ["a", "seq:11"])
        #expect(timeline.entry(id: "a")?.seq == 10)
        #expect(timeline.entry(id: "a")?.latestSeq == 12)
        #expect(timeline.entry(id: "a")?.text == "one two")
    }

    @Test("A block that began before the cursor sorts by where it began")
    func lateArrivalSortsByOrigin() throws {
        var timeline = Timeline()
        timeline.apply(try notice(30))
        timeline.apply(try streaming(31, block: "b", firstSeq: 20, text: "earlier"))
        #expect(timeline.entries.map(\.id) == ["b", "seq:30"])
        // The history cursor is a real event seq, never a block position.
        #expect(timeline.oldestSeq == 30)
    }

    @Test("History pages honour the same ordering")
    func historyOrdering() throws {
        var timeline = Timeline()
        timeline.apply(try streaming(50, block: "c", firstSeq: 40, text: "late"))
        timeline.prependHistory([try notice(45)], hasMore: false)
        #expect(timeline.entries.map(\.id) == ["c", "seq:45"])
        #expect(timeline.oldestSeq == 45)
    }
}

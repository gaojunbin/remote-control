import Testing
import Foundation
@testable import RCCore

/// Amendment A43 on the demo device: every queue in `ts` order with no two
/// entries under one `ts`, `queue_ts` honoured, `queue_remove` answering
/// `not_found` for what it no longer holds, and an edit going back to its place.
@Suite("Amendment A43, the demo device's queue")
struct DemoQueueTests {
    private func item(_ id: String, ts: Int64) -> DemoQueue.Item {
        DemoQueue.Item(id: id, text: id, ts: ts)
    }

    private func order(_ queue: DemoQueue) -> [String] { queue.pending.map(\.id) }

    // MARK: - The rule

    @Test("A message sent back with queue_ts goes in front of the first entry with a later ts")
    func insertsInTsOrder() {
        var queue = DemoQueue([item("a", ts: 10), item("b", ts: 20), item("c", ts: 30)])
        queue.insert(item("front", ts: 5))
        #expect(order(queue) == ["front", "a", "b", "c"])
        queue.insert(item("middle", ts: 25))
        #expect(order(queue) == ["front", "a", "b", "middle", "c"])
        queue.insert(item("end", ts: 40))
        #expect(order(queue) == ["front", "a", "b", "middle", "c", "end"])
        queue.insert(item("tie", ts: 20))
        #expect(order(queue) == ["front", "a", "b", "tie", "middle", "c", "end"],
                "after an entry with the same ts, not before it")

        var empty = DemoQueue()
        empty.insert(item("only", ts: 7))
        #expect(order(empty) == ["only"])
    }

    @Test("Anything else joins the end under a ts no entry has")
    func appendsUnderAFreshTs() {
        var queue = DemoQueue([item("a", ts: 100)])
        queue.append(item("behind the clock", ts: 50))
        #expect(queue.pending.map(\.ts) == [100, 101], "one past the last entry when that is later")
        queue.append(item("now", ts: 500))
        queue.append(item("the same instant", ts: 500))
        #expect(queue.pending.map(\.ts) == [100, 101, 500, 501])

        var empty = DemoQueue()
        empty.append(item("first", ts: 42))
        #expect(empty.pending.map(\.ts) == [42], "the current time when nothing is ahead of it")
    }

    @Test("Removing takes out the one message named, and only once")
    func removesByID() {
        var queue = DemoQueue([item("a", ts: 1), item("b", ts: 2)])
        #expect(queue.remove(id: "a")?.message.id == "a")
        #expect(queue.remove(id: "a") == nil, "the second time there is nothing to take")
        #expect(order(queue) == ["b"])
        #expect(queue.next()?.message.id == "b")
        #expect(queue.isEmpty)
    }

    @Test("The snapshot counts a message's files and carries none of them")
    func countsFiles() {
        let file = AttachmentInfo(name: "shot.png", mime: "image/png", size: 4)
        #expect(DemoQueue.Item(id: "f", text: "t", ts: 1, files: [file, file]).message.attachments == 2)
        #expect(DemoQueue.Item(id: "g", text: "t", ts: 1).message.attachments == nil)
    }

    @Test("The seeded line has three messages under three different ts, the last with files")
    func seededLine() {
        let seeded = DemoFixtures.heldMessages(base: 1_000_000)
        #expect(Set(seeded.map(\.message.ts)).count == 3)
        #expect(seeded.map(\.message.ts) == seeded.map(\.message.ts).sorted())
        #expect(seeded.map(\.message.carriesFiles) == [false, false, true])
    }

    // MARK: - Through the demo gateway

    @Test("queue_remove answers not_found for a message it does not hold")
    func removeUnknownIsNotFound() async throws {
        let gateway = DemoGateway(resumeDelay: nil, holdsQueue: true)
        let unknown = GatewayRequest.queueRemove(sessionID: DemoFixtures.liveSessionID, queuedID: "gone")
        do {
            try await gateway.request(unknown)
            Issue.record("an unknown id was removed")
        } catch let refusal as GatewayErrorBody {
            #expect(refusal.code == .notFound)
        }
        try await gateway.request(.queueRemove(sessionID: DemoFixtures.liveSessionID,
                                               queuedID: "demo-queued-suite"))
        do {
            try await gateway.request(.queueRemove(sessionID: DemoFixtures.liveSessionID,
                                                   queuedID: "demo-queued-suite"))
            Issue.record("a message was removed twice")
        } catch let refusal as GatewayErrorBody {
            #expect(refusal.code == .notFound, "and so does a second removal of the same one")
        }
    }

    @Test("A queue_ts that is not a non-negative integer is refused")
    func badQueueTsIsRefused() async throws {
        let gateway = DemoGateway(resumeDelay: nil, holdsQueue: true)
        for bad: JSONValue in [.integer(-1), .string("12"), .number(1.5), .bool(true)] {
            var body = try GatewayRequest.send(sessionID: DemoFixtures.liveSessionID, text: "again",
                                               mode: .queue).body
            body["queue_ts"] = bad
            do {
                try await gateway.request(GatewayRequest(type: "session.send", body: body))
                Issue.record("queue_ts \(bad) was taken")
            } catch let refusal as GatewayErrorBody {
                #expect(refusal.code == .badRequest)
            }
        }
    }

    @Test("An edited message goes back to its place in the demo's line")
    @MainActor
    func editRoundTrip() async throws {
        let gateway = DemoGateway(echoDelay: .zero, resumeDelay: nil, holdsQueue: true)
        let session = try #require(DemoFixtures.sessions.first {
            $0.sessionID == DemoFixtures.liveSessionID
        })
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.claude
        let events = gateway.events
        let pump = Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
        defer { pump.cancel() }

        await chat.open()
        #expect(chat.timeline.queue.map(\.id)
                == ["demo-queued-suite", "demo-queued-regression", "demo-queued-evidence"],
                "the subscribe reply carries the line (A6)")
        #expect(chat.timeline.queue.last?.attachments == 2)
        #expect(chat.session.queued == 3)

        let middle = chat.timeline.queue[1]
        chat.draft = "a note of my own"
        await chat.beginEdit(middle)
        try await settle { chat.timeline.queue.count == 2 }
        #expect(chat.timeline.queue.map(\.id) == ["demo-queued-suite", "demo-queued-evidence"])
        #expect(chat.draft == middle.text)

        chat.draft = "Add a regression test for the refresh race, and one for logout."
        #expect(await chat.send() == .accepted)
        try await settle { chat.timeline.queue.count == 3 }
        #expect(chat.timeline.queue.map(\.text) == ["Then run the full test suite.",
                                                    "Add a regression test for the refresh race, and one for logout.",
                                                    "Here are the CI log and a screenshot of the failing run."])
        #expect(chat.timeline.queue[1].ts == middle.ts, "under the ts it left")
        #expect(chat.draft == "a note of my own")

        let removing = chat.removeQueued(chat.timeline.queue[0].id)
        #expect(chat.timeline.queue.count == 2, "a removed row leaves the list before the reply")
        #expect(chat.session.queued == 2, "and the chip counts one fewer")
        await removing.value
        #expect(chat.errorMessage == nil, "the device let go of it")
        try await settle { chat.timeline.queue.first?.ts == middle.ts }
        #expect(chat.timeline.queue.map(\.id).count == 2, "and its snapshot agrees")
        #expect(chat.timeline.queue.first?.ts == middle.ts)
    }

    @MainActor
    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

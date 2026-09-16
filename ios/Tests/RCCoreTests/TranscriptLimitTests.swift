import Testing
import Foundation
@testable import RCCore

/// What an open transcript costs, and what "Open full output" may do to it.
@Suite("Transcript limits")
struct TranscriptLimitTests {
    private func event(_ seq: Int, kind: String,
                       _ fields: [String: JSONValue] = [:]) throws -> SessionEvent {
        var object = fields
        object["seq"] = .integer(Int64(seq))
        object["ts"] = .integer(Int64(seq))
        object["kind"] = .string(kind)
        if kind == SessionEvent.noticeKind {
            object["level"] = "info"
            object["text"] = .string("line \(seq)")
        }
        return try JSONValue.object(object).decode(SessionEvent.self)
    }

    private func toolCall(_ seq: Int, firstSeq: Int? = nil, output: String,
                          truncated: Bool = false) throws -> SessionEvent {
        var fields: [String: JSONValue] = [
            "block_id": "tool", "tool": "Bash", "tool_kind": "shell",
            "title": "pytest", "status": "succeeded", "output": .string(output)
        ]
        if truncated { fields["output_truncated"] = true }
        if let firstSeq { fields["first_seq"] = .integer(Int64(firstSeq)) }
        return try event(seq, kind: "tool_call", fields)
    }

    // MARK: - The transcript has a ceiling

    @Test("A transcript left open stops growing, and says history can be paged again")
    func liveEntriesAreCapped() throws {
        var timeline = Timeline()
        timeline.markHistoryExhausted()
        #expect(!timeline.hasMoreHistory)

        for seq in 1...(Timeline.entryLimit + 10) {
            timeline.apply(try event(seq, kind: "notice"))
        }

        #expect(timeline.entries.count == Timeline.entryLimit)
        #expect(timeline.entry(id: "seq:1") == nil, "the oldest rows are the ones dropped")
        #expect(timeline.entry(id: "seq:\(Timeline.entryLimit + 10)") != nil, "the newest are kept")
        #expect(timeline.hasMoreHistory, "so scrolling back pages them from the gateway again")
        #expect(timeline.oldestSeq == 11, "and the history cursor follows the rows that are left")
    }

    @Test("Paging history back does not trim away what the reader scrolled up to see")
    func historyIsNotTrimmed() throws {
        var timeline = Timeline()
        let events = try (1...(Timeline.entryLimit + 10)).map { try event($0, kind: "notice") }
        timeline.prependHistory(events, hasMore: false)
        #expect(timeline.entries.count == Timeline.entryLimit + 10)
        #expect(timeline.entry(id: "seq:1") != nil)
    }

    // MARK: - The rows a render reads

    @Test("A redraw does not filter the transcript again")
    @MainActor
    func rowsAreKeptBetweenRenders() throws {
        let chat = ChatStore(session: Session(sessionID: "s", deviceID: "d", agent: "claude",
                                              title: "T", cwd: "/tmp", state: .idle,
                                              control: .remote),
                             channel: MuteChannel())
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: try event(1, kind: "notice")))
        #expect(chat.rows.count == 1)
        let built = chat.rowsBuilt
        _ = chat.rows
        _ = chat.rows
        #expect(chat.rowsBuilt == built, "three renders, one filter")

        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: try event(2, kind: "notice")))
        #expect(chat.rows.count == 2)
        #expect(chat.rowsBuilt == built + 1, "a new event is what makes it filter again")
    }

    // MARK: - Open full output

    @Test("A block that streamed on is not reverted by the copy that was asked for earlier")
    func staleFullOutputIsRefused() throws {
        var timeline = Timeline()
        timeline.apply(try toolCall(10, output: "first", truncated: true))
        timeline.apply(try toolCall(20, firstSeq: 10, output: "second", truncated: true))
        #expect(timeline.entry(id: "tool")?.toolCall?.output == "second")

        // The reply to the request that left while the block was at seq 10.
        timeline.replaceBlock(with: try toolCall(10, firstSeq: 10, output: "first in full"))
        #expect(timeline.entry(id: "tool")?.toolCall?.output == "second",
                "an older copy never writes over a newer one")

        // The reply for the version on screen is the point of the request.
        timeline.replaceBlock(with: try toolCall(20, firstSeq: 10, output: "second in full"))
        #expect(timeline.entry(id: "tool")?.toolCall?.output == "second in full")
        #expect(timeline.entry(id: "tool")?.toolCall?.outputTruncated != true)
    }

    @Test("A block the reply moves back into place is re-sorted with the rows around it")
    func fullOutputResortsWhenItMoves() throws {
        var timeline = Timeline()
        timeline.apply(try event(10, kind: "notice"))
        // A replacement that arrived without `first_seq`, so the row sits at 30.
        timeline.apply(try toolCall(30, output: "late", truncated: true))
        #expect(timeline.roots.map(\.id) == ["seq:10", "tool"])

        // The reply carries `first_seq`, which is where the block belongs.
        timeline.replaceBlock(with: try toolCall(30, firstSeq: 5, output: "late in full"))
        #expect(timeline.entry(id: "tool")?.seq == 5)
        #expect(timeline.roots.map(\.id) == ["tool", "seq:10"],
                "the row is put back in order rather than left where it was")
        #expect(timeline.entry(id: "seq:10") != nil, "and the index still resolves every row")
    }
}

/// A channel that answers nothing: these tests drive the store with frames.
private final class MuteChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>

    init() { events = AsyncStream<GatewayEvent>.makeStream().stream }

    func connect() async {}
    func disconnect() async {}
    func request(_ request: GatewayRequest) async throws -> JSONValue { throw TransportError.notConnected }
}

import Foundation
import RCCore

/// The reducer rules from PROTOCOL-FROZEN.md sections 4, 7 and 8.
enum TimelineChecks {
    @MainActor
    static func run() -> CheckResult {
        let checks = CheckRunner(group: "timeline")
        streaming(checks)
        replacement(checks)
        lateFrames(checks)
        history(checks)
        snapshots(checks)
        nesting(checks)
        endToEnd(checks)
        terminalControl(checks)
        blockOrdering(checks)
        return checks.result()
    }

    /// Amendment A7: `control` decides who may type; `state` only says what is
    /// happening. A terminal session runs and goes idle like any other.
    @MainActor
    private static func terminalControl(_ checks: CheckRunner) {
        func store(state: SessionState, control: SessionControl,
                   agent: AgentInfo? = DemoFixtures.claude) -> ChatStore {
            let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                                  cwd: "/tmp", state: state, control: control)
            let chat = ChatStore(session: session, channel: DemoGateway())
            chat.agent = agent
            chat.draft = "hello"
            return chat
        }

        let running = store(state: .running, control: .terminal)
        checks.expect(running.isReadOnly, "a terminal session is read-only while its turn runs")
        checks.expect(running.isRunning, "a terminal-driven turn still counts as running")
        checks.expect(!running.canStop, "the app does not stop a turn the terminal owns")
        checks.expect(!running.canSend, "the composer is disabled while the terminal has control")
        checks.equal(running.sendBlockReason, "Controlled by the terminal", "and it says why")
        checks.equal(running.statusLine, "Controlled by the terminal · Take over to send",
                     "the same line while the terminal turn runs")

        let idle = store(state: .readonly, control: .terminal)
        checks.expect(idle.isReadOnly, "an idle terminal session is still read-only")
        checks.expect(!idle.isRunning, "readonly means the terminal turn has finished")
        checks.equal(idle.statusLine, "Controlled by the terminal · Take over to send",
                     "the same line when the terminal session is idle")

        let ours = store(state: .running, control: .remote)
        checks.expect(!ours.isReadOnly, "a remote-controlled session is writable")
        checks.expect(ours.canStop, "we may stop a turn we own")

        let resumable = store(state: .idle, control: .none)
        checks.expect(!resumable.isReadOnly, "a session nobody holds can be resumed from here")
        checks.expect(resumable.canSend, "and typed into")

        // Amendment A10: takeover is an affordance, not an assumption.
        let noTakeover = store(state: .readonly, control: .terminal,
                               agent: AgentInfo(agent: "claude", available: true))
        checks.expect(!noTakeover.canTakeover, "takeover needs the capability")
        checks.equal(noTakeover.statusLine, "Controlled by the terminal",
                     "and the status line does not promise one")

        // Amendment A10: an attached session behaves like a remote one.
        let attachedIdle = store(state: .idle, control: .shared)
        checks.expect(!attachedIdle.isReadOnly, "an attached session is never read-only")
        checks.expect(attachedIdle.isAttached, "and knows it is attached")
        checks.expect(attachedIdle.canSend, "so the composer is enabled")
        checks.expect(!attachedIdle.canTakeover, "takeover is never offered while attached")
        checks.expect(attachedIdle.attachHint == nil, "and the terminal hint belongs to terminal sessions")
        checks.equal(attachedIdle.statusLine, "terminal · attached", "the status names the terminal")
        checks.expect(!attachedIdle.allowsSettingsChanges,
                      "model, permission mode and effort stay in the terminal")
        checks.expect(!attachedIdle.allowsAttachments, "and attachments cannot be relayed")
        checks.expect(!attachedIdle.allowsAnswers, "and a question the CLI asked is answered there")

        let attachedRunning = store(state: .running, control: .shared)
        checks.expect(attachedRunning.isRunning, "an attached turn runs like any other")
        checks.expect(!attachedRunning.canStop, "a channel cannot interrupt the turn it rides on")
        checks.equal(attachedRunning.statusLine, "terminal · attached · working",
                     "and the status says the terminal is busy")

        let interruptible = store(state: .running, control: .shared,
                                  agent: AgentInfo(agent: "codex", available: true,
                                                   capabilities: [.interrupt],
                                                   attach: .daemon, attachReady: true,
                                                   sharedInterrupt: true))
        checks.expect(interruptible.canStop, "an attachment that can interrupt offers Stop")

        let interruptWithoutCapability = store(state: .running, control: .shared,
                                               agent: AgentInfo(agent: "codex", available: true,
                                                                attach: .daemon, attachReady: true,
                                                                sharedInterrupt: true))
        checks.expect(!interruptWithoutCapability.canStop,
                      "and only when the agent lists interrupt as well")

        // Amendment A10: hints on a terminal session the device could attach.
        let shimMissing = store(state: .readonly, control: .terminal,
                                agent: DemoFixtures.claudeWithoutShim)
        checks.equal(shimMissing.attachHint, .installShim,
                     "an unprepared Claude device says how to prepare it")

        let shimReady = store(state: .readonly, control: .terminal)
        checks.equal(shimReady.attachHint, .restartSession,
                     "a prepared device blames the running process instead")

        let daemonMissing = store(state: .readonly, control: .terminal,
                                  agent: AgentInfo(agent: "codex", available: true,
                                                   capabilities: [.takeover], attach: .daemon))
        checks.equal(daemonMissing.attachHint, .startDaemon, "and Codex names its daemon")

        let noAttach = store(state: .readonly, control: .terminal,
                             agent: AgentInfo(agent: "claude", available: true, capabilities: [.takeover]))
        checks.expect(noAttach.attachHint == nil, "an agent that cannot be attached says nothing")
    }

    /// Amendment A8: a block holds the position of its first appearance, live,
    /// on replay and through history.
    private static func blockOrdering(_ checks: CheckRunner) {
        func streamed(_ seq: Int, block: String, firstSeq: Int?, text: String) -> SessionEvent {
            var fields: [String: JSONValue] = ["block_id": .string(block), "delta": .string(text),
                                               "done": false]
            if let firstSeq { fields["first_seq"] = .integer(Int64(firstSeq)) }
            return event(seq, "assistant_text", fields)
        }

        // A block that keeps streaming does not jump below rows that arrived
        // while it was still writing.
        var timeline = Timeline()
        timeline.apply(streamed(10, block: "a", firstSeq: 10, text: "one"))
        timeline.apply(event(11, "tool_call", ["block_id": "t", "tool": "Bash", "tool_kind": "shell",
                                               "title": "pytest", "status": "running",
                                               "first_seq": 11]))
        timeline.apply(streamed(12, block: "a", firstSeq: 10, text: " two"))
        checks.equal(timeline.entries.map(\.id), ["a", "t"],
                     "a streaming block keeps its place while its seq rises")
        checks.equal(timeline.entry(id: "a")?.text, "one two", "and still accumulates its text")
        checks.equal(timeline.entry(id: "a")?.seq, 10, "its position is its first appearance")
        checks.equal(timeline.entry(id: "a")?.latestSeq, 12, "while its cursor follows the newest event")

        // A block first seen mid-stream sorts by where it began, not where the
        // app happened to join.
        var joined = Timeline()
        joined.apply(event(30, "notice", ["level": "info", "text": "reconnected"]))
        joined.apply(streamed(31, block: "b", firstSeq: 20, text: "started earlier"))
        checks.equal(joined.entries.map(\.id), ["b", "seq:30"],
                     "a block that began before the cursor sorts ahead of it")

        // History pages agree with the same rule.
        var paged = Timeline()
        paged.apply(streamed(50, block: "c", firstSeq: 40, text: "late"))
        paged.prependHistory([event(45, "notice", ["level": "info", "text": "between"])], hasMore: false)
        checks.equal(paged.entries.map(\.id), ["c", "seq:45"],
                     "history respects a block's first appearance")
        checks.equal(paged.oldestSeq, 45,
                     "the paging cursor is a real event seq, not a block position")

        // Without first_seq the ordering is unchanged.
        var plain = Timeline()
        plain.apply(streamed(1, block: "d", firstSeq: nil, text: "x"))
        plain.apply(event(2, "notice", ["level": "info", "text": "y"]))
        plain.apply(streamed(3, block: "d", firstSeq: nil, text: "z"))
        checks.equal(plain.entries.map(\.id), ["d", "seq:2"],
                     "a block with no first_seq keeps the seq it was created at")
    }

    /// Replay both recorded sessions frame by frame and compare the result with
    /// the session summary the contract says the app must end up with.
    private static func endToEnd(_ checks: CheckRunner) {
        for file in FixtureSource.files(in: FixtureSource.fixtures.appending(path: "timelines")) {
            let name = FixtureSource.label(file)
            guard let data = try? Data(contentsOf: file),
                  let json = try? JSONDecoder().decode(JSONValue.self, from: data),
                  let frames = json["frames"]?.arrayValue,
                  var session = try? (json["session"] ?? .object([:])).decode(Session.self),
                  let expected = try? (json["session_final"] ?? .object([:])).decode(Session.self) else {
                checks.expect(false, "\(name) has a session, a final session and frames")
                continue
            }
            var timeline = Timeline()
            var applied = 0
            for value in frames {
                guard let frame = try? AppFrame(json: value) else {
                    checks.expect(false, "\(name) frame decodes")
                    continue
                }
                switch frame {
                case .sessionEvent(_, _, let event):
                    if timeline.apply(event) { applied += 1 }
                    fold(event, into: &session)
                case .sessionUpdated(let updated):
                    session = updated
                default:
                    break
                }
            }
            checks.equal(applied, frames.count, "\(name): every frame applies once")
            checks.equal(timeline.lastSeq, expected.lastSeq, "\(name): the cursor ends at last_seq")
            checks.equal(session.state, expected.state, "\(name): the final state matches")
            checks.equal(session.control, expected.control, "\(name): the final control owner matches")
            checks.equal(session.title, expected.title, "\(name): the final title matches")
            checks.equal(session.todos, expected.todos, "\(name): the final todo counts match")
            checks.equal(session.usage?.totalTokens, expected.usage?.totalTokens,
                         "\(name): the final token total matches")
            checks.expect(session.turn == nil, "\(name): the turn is closed at the end")
            checks.equal(timeline.todos.count, expected.todos?.total ?? 0,
                         "\(name): the todo snapshot matches the summary")
            checks.expect(timeline.roots.count > 3, "\(name): the transcript has renderable rows")
            checks.expect(timeline.pendingRequest == nil,
                          "\(name): nothing is left waiting on the user")

            // Replaying the same frames a second time must change nothing.
            let before = timeline
            for value in frames {
                if case .sessionEvent(_, _, let event)? = try? AppFrame(json: value) { timeline.apply(event) }
            }
            checks.expect(timeline == before, "\(name): replaying the same frames is a no-op")
        }
    }

    /// The session-summary rules from section 7: only status, turn and meta
    /// events move the summary, never silence.
    private static func fold(_ event: SessionEvent, into session: inout Session) {
        switch event.body {
        case .status(let payload):
            session.state = payload.state
            session.stateDetail = payload.detail
        case .turnStarted(let payload):
            session.turn = TurnMarker(turnID: payload.turnID, startedAt: event.ts)
        case .turnCompleted(let payload):
            session.turn = nil
            if let usage = payload.usage { session.usage = usage }
        case .todos(let payload):
            session.todos = payload.counts
        case .queue(let payload):
            session.queued = payload.pending.count
        case .meta(let payload):
            if let title = payload.title { session.title = title }
            if let model = payload.model { session.model = model }
            if let mode = payload.permissionMode { session.permissionMode = mode }
            if let effort = payload.effort { session.effort = effort }
            if let cwd = payload.cwd { session.cwd = cwd }
            if let git = payload.git { session.git = git }
            if let control = payload.control { session.control = control }
        default:
            break
        }
    }

    private static func event(_ seq: Int, _ kind: String, _ fields: [String: JSONValue]) -> SessionEvent {
        var object = fields
        object["seq"] = .integer(Int64(seq))
        object["ts"] = .integer(Int64(1_710_000_000_000 + seq))
        object["kind"] = .string(kind)
        // Fixture construction failing here would be a bug in the check itself.
        return (try? JSONValue.object(object).decode(SessionEvent.self))
            ?? SessionEvent(seq: seq, ts: 0, kind: kind, body: .unknown(kind: kind, raw: .object(object)))
    }

    /// Deltas append; the final event carries the whole text.
    private static func streaming(_ checks: CheckRunner) {
        var timeline = Timeline()
        timeline.apply(event(1, "assistant_text", ["block_id": "a", "delta": "Hello", "done": false]))
        timeline.apply(event(2, "assistant_text", ["block_id": "a", "delta": " world", "done": false]))
        checks.equal(timeline.entry(id: "a")?.text, "Hello world", "deltas append into one block")
        checks.equal(timeline.entries.count, 1, "streaming does not create extra rows")
        timeline.apply(event(3, "assistant_text", ["block_id": "a", "text": "Hello world.", "done": true]))
        checks.equal(timeline.entry(id: "a")?.text, "Hello world.", "the final event replaces the text")
        checks.expect(timeline.entry(id: "a")?.isStreaming == false, "a done block stops streaming")

        var thinking = Timeline()
        thinking.apply(event(1, "thinking", ["block_id": "t", "delta": "step", "done": false]))
        thinking.apply(event(2, "thinking", ["block_id": "t", "text": "step one", "done": true,
                                             "duration_ms": 12000]))
        checks.equal(thinking.entry(id: "t")?.thinking?.durationMS, 12000, "thinking keeps its duration")
    }

    /// A later event for the same block replaces the earlier one wholesale.
    private static func replacement(_ checks: CheckRunner) {
        var timeline = Timeline()
        timeline.apply(event(1, "tool_call", ["block_id": "b", "tool": "Bash", "title": "pytest",
                                              "status": "running"]))
        timeline.apply(event(2, "tool_call", ["block_id": "b", "tool": "Bash", "title": "pytest",
                                              "status": "failed", "output": "2 failed", "duration_ms": 6400]))
        checks.equal(timeline.entries.count, 1, "replacement keeps one row")
        checks.equal(timeline.entry(id: "b")?.toolCall?.status, .failed, "the newest tool status wins")
        checks.equal(timeline.entry(id: "b")?.seq, 1, "a replaced block keeps its original position")

        var approvals = Timeline()
        approvals.apply(event(1, "approval", ["block_id": "ap", "request_id": "r", "tool": "Bash",
                                              "title": "rm -rf", "status": "pending",
                                              "options": [["id": "allow", "label": "Allow", "style": "primary"]]]))
        checks.expect(approvals.pendingRequest?.id == "ap", "a pending approval is surfaced")
        approvals.apply(event(2, "approval", ["block_id": "ap", "request_id": "r", "tool": "Bash",
                                              "title": "rm -rf", "status": "resolved",
                                              "options": [["id": "allow", "label": "Allow", "style": "primary"]],
                                              "decision": ["option_id": "allow", "by": "remote"]]))
        checks.expect(approvals.pendingRequest == nil, "a resolved approval stops being actionable")
        checks.equal(approvals.entry(id: "ap")?.approval?.decision?.optionID, "allow", "the decision is recorded")
    }

    /// Late or duplicate frames at or below the applied cursor are dropped.
    private static func lateFrames(_ checks: CheckRunner) {
        var timeline = Timeline()
        checks.expect(timeline.apply(event(5, "notice", ["level": "info", "text": "one"])), "seq 5 applies")
        checks.expect(!timeline.apply(event(5, "notice", ["level": "info", "text": "duplicate"])),
                      "a duplicate seq is dropped")
        checks.expect(!timeline.apply(event(3, "notice", ["level": "info", "text": "older"])),
                      "an older seq is dropped")
        checks.equal(timeline.entries.count, 1, "dropped frames add no rows")
        checks.equal(timeline.lastSeq, 5, "the cursor does not move backwards")
    }

    /// History pages prepend, never advance the live cursor, and never override
    /// a newer copy of the same block.
    private static func history(_ checks: CheckRunner) {
        var timeline = Timeline()
        timeline.apply(event(20, "assistant_text", ["block_id": "a", "text": "newest", "done": true]))
        timeline.prependHistory([
            event(10, "user_message", ["block_id": "u", "text": "older question", "source": "remote"]),
            event(12, "assistant_text", ["block_id": "a", "text": "stale copy", "done": true])
        ], hasMore: true)
        checks.equal(timeline.entries.first?.id, "u", "older history sorts to the front")
        checks.equal(timeline.entry(id: "a")?.text, "newest", "history does not overwrite a newer block")
        checks.equal(timeline.lastSeq, 20, "history does not move the live cursor")
        checks.equal(timeline.oldestSeq, 10, "the paging cursor is the oldest seq held")
        checks.expect(timeline.hasMoreHistory, "has_more is carried through")
        checks.expect(timeline.historyLoaded, "a page marks history as loaded")

        timeline.prependHistory([], hasMore: false)
        checks.expect(!timeline.hasMoreHistory, "an exhausted history stops paging")

        timeline.reset()
        checks.equal(timeline.entries.count, 0, "resync clears the transcript")
        checks.equal(timeline.lastSeq, 0, "resync clears the cursor")
    }

    /// `todos` and `queue` are snapshots, not rows.
    private static func snapshots(_ checks: CheckRunner) {
        var timeline = Timeline()
        timeline.apply(event(1, "todos", ["items": [
            ["id": "1", "text": "a", "status": "completed"],
            ["id": "2", "text": "b", "status": "pending"]
        ]]))
        checks.equal(timeline.todos.count, 2, "todos are held as a snapshot")
        checks.equal(timeline.entries.count, 0, "todos add no timeline row")
        timeline.apply(event(2, "todos", ["items": [["id": "1", "text": "a", "status": "completed"]]]))
        checks.equal(timeline.todos.count, 1, "a todos snapshot replaces the previous list")

        timeline.apply(event(3, "queue", ["pending": [["id": "q1", "text": "later", "ts": 1]]]))
        checks.equal(timeline.queue.count, 1, "queued messages are held as a snapshot")
        checks.equal(timeline.entries.count, 0, "queue adds no timeline row")


        timeline.apply(event(4, "status", ["state": "running"]))
        timeline.apply(event(5, "meta", ["title": "New title"]))
        checks.equal(timeline.entries.count, 0, "status and meta add no timeline rows")
        checks.equal(timeline.lastSeq, 5, "state-only events still advance the cursor")

        // Amendment A6: a snapshot found while paging history is older than the
        // live one and must not overwrite it.
        timeline.prependHistory([
            event(1, "todos", ["items": [["id": "a", "text": "old", "status": "pending"],
                                         ["id": "b", "text": "old", "status": "pending"],
                                         ["id": "c", "text": "old", "status": "pending"]]]),
            event(2, "queue", ["pending": [["id": "old1", "text": "stale", "ts": 1],
                                           ["id": "old2", "text": "stale", "ts": 1]]])
        ], hasMore: false)
        checks.equal(timeline.todos.count, 1, "an older todos snapshot from history is ignored")
        checks.equal(timeline.queue.first?.id, "q1", "an older queue snapshot from history is ignored")

        // A snapshot replayed from the subscribe buffer still applies.
        timeline.apply(event(6, "todos", ["items": [["id": "1", "text": "a", "status": "completed"],
                                                    ["id": "2", "text": "b", "status": "pending"]]]))
        checks.equal(timeline.todos.count, 2, "a newer todos snapshot applies")

        // The subscribe reply's queue describes the session at the cursor.
        timeline.applySubscribedQueue([QueuedMessage(id: "sub", text: "from the reply", ts: 2)])
        checks.equal(timeline.queue.first?.id, "sub", "the subscribe reply's queue applies")
        timeline.apply(event(7, "queue", ["pending": []]))
        checks.equal(timeline.queue.count, 0, "a later queue event still wins over the reply")
    }

    /// Sub-agent output hangs under the tool call that produced it.
    private static func nesting(_ checks: CheckRunner) {
        var timeline = Timeline()
        timeline.apply(event(1, "tool_call", ["block_id": "task", "tool": "Task",
                                              "title": "Audit the lock", "status": "running"]))
        timeline.apply(event(2, "assistant_text", ["block_id": "sub", "parent_block_id": "task",
                                                   "text": "found it", "done": true]))
        checks.equal(timeline.roots.count, 1, "a nested row does not appear at the top level")
        checks.equal(timeline.children(of: "task").count, 1, "the nested row hangs under its parent")
        checks.equal(timeline.children(of: "task").first?.text, "found it", "nested text is preserved")
    }

}

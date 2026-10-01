package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnMarker
import com.junbingao.remotecontrol.core.protocol.arrayValue
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.jsonOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/** `ios/Verification/TimelineChecks.swift`: the reducer rules from PROTOCOL-FROZEN.md sections 4, 7 and 8. */
class TimelineChecks {
    private fun event(seq: Int, kind: String, fields: Map<String, Any?>): SessionEvent {
        val members = LinkedHashMap(fields)
        members["seq"] = seq
        members["ts"] = 1_710_000_000_000 + seq
        members["kind"] = kind
        val json = jsonOf(members)
        // Fixture construction failing here would be a bug in the check itself.
        return runCatching { json.decode<SessionEvent>() }.getOrNull()
            ?: SessionEvent(seq = seq, ts = 0, kind = kind, body = SessionEventBody.Unknown(kind = kind, raw = json))
    }

    /** Amendment A7: `control` decides who may type; `state` only says what is happening. A terminal session runs and goes idle like any other. */
    @Test
    fun terminalControl() = runTest {
        val checks = CheckRunner("timeline")
        fun store(state: SessionState, control: SessionControl, agent: AgentInfo? = DemoFixtures.claude): ChatStore {
            val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp", state = state,
                                  control = control)
            val chat = ChatStore(session = session, channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                                 tasks = backgroundScope)
            chat.agent = agent
            chat.draft = "hello"
            return chat
        }

        val running = store(state = SessionState.running, control = SessionControl.terminal)
        checks.expect(running.isReadOnly, "a terminal session is read-only while its turn runs")
        checks.expect(running.isRunning, "a terminal-driven turn still counts as running")
        checks.expect(!running.canStop, "the app does not stop a turn the terminal owns")
        checks.expect(!running.canSend, "the composer is disabled while the terminal has control")
        checks.equal(running.sendBlockReason, "Controlled by the terminal",
                     "and the field says why, without repeating the clause above it")
        checks.equal(running.statusLine, "Controlled by the terminal · take over to send",
                     "the same line while the terminal turn runs")

        val idle = store(state = SessionState.readonly, control = SessionControl.terminal)
        checks.expect(idle.isReadOnly, "an idle terminal session is still read-only")
        checks.expect(!idle.isRunning, "readonly means the terminal turn has finished")
        checks.equal(idle.statusLine, "Controlled by the terminal · take over to send",
                     "the same line when the terminal session is idle")

        val ours = store(state = SessionState.running, control = SessionControl.remote)
        checks.expect(!ours.isReadOnly, "a remote-controlled session is writable")
        checks.expect(ours.canStop, "we may stop a turn we own")

        val resumable = store(state = SessionState.idle, control = SessionControl.none)
        checks.expect(!resumable.isReadOnly, "a session nobody holds can be resumed from here")
        checks.expect(resumable.canSend, "and typed into")

        // Amendment A10: takeover is an affordance, not an assumption.
        val noTakeover = store(state = SessionState.readonly, control = SessionControl.terminal,
                               agent = AgentInfo(agent = "claude", available = true))
        checks.expect(!noTakeover.canTakeover, "takeover needs the capability")
        checks.equal(noTakeover.statusLine, "Controlled by the terminal", "and the status line does not promise one")
        checks.equal(noTakeover.sendBlockReason, "Controlled by the terminal",
                     "and the field reads the short sentence, as it does for every agent")

        // Amendment A10: an attached session behaves like a remote one.
        val attachedIdle = store(state = SessionState.idle, control = SessionControl.shared)
        checks.expect(!attachedIdle.isReadOnly, "an attached session is never read-only")
        checks.expect(attachedIdle.isAttached, "and knows it is attached")
        checks.expect(attachedIdle.canSend, "so the composer is enabled")
        checks.expect(!attachedIdle.canTakeover, "takeover is never offered while attached")
        checks.expect(attachedIdle.attachHint == null, "and the terminal hint belongs to terminal sessions")
        checks.equal(attachedIdle.statusLine, null, "and the composer says nothing the header has already said")
        // Amendment A40: the shim types `/model` and `/effort` into the terminal it owns, and has no
        // command for the permission mode.
        checks.expect(attachedIdle.allowsModelCardChanges, "the model card is typed into the terminal, so it is a control")
        checks.expect(!attachedIdle.allowsSettingsChanges(SharedSetting.permissionMode),
                      "and the permission mode stays what the terminal set")
        checks.expect(!attachedIdle.allowsAttachments, "and attachments cannot be relayed")
        // Amendment A20: a question is answered where you are. The device raises the block from a
        // hook beside the CLI's own dialog and takes whichever answer arrives first, so the card
        // here is live.
        checks.expect(attachedIdle.allowsAnswers, "and a question the CLI asked is answerable here")
        checks.expect(!store(state = SessionState.needsInput, control = SessionControl.terminal).allowsAnswers,
                      "while a session the terminal holds outright takes nothing from this app")

        val attachedRunning = store(state = SessionState.running, control = SessionControl.shared)
        checks.expect(attachedRunning.isRunning, "an attached turn runs like any other")
        checks.expect(attachedRunning.canStop, "the pseudo-terminal's Escape stops the turn it rides on (A42)")
        checks.equal(attachedRunning.statusLine, "Working · your message will be queued",
                     "and the status says only what becomes of a message typed into it")

        val interruptible = store(state = SessionState.running, control = SessionControl.shared,
                                  agent = AgentInfo(agent = "codex", available = true,
                                                    capabilities = listOf(AgentCapability.interrupt),
                                                    attach = AgentAttach.daemon, attachReady = true, sharedInterrupt = true))
        checks.expect(interruptible.canStop, "an attachment that can interrupt offers Stop")

        val interruptWithoutCapability = store(state = SessionState.running, control = SessionControl.shared,
                                               agent = AgentInfo(agent = "codex", available = true, attach = AgentAttach.daemon,
                                                                 attachReady = true, sharedInterrupt = true))
        checks.expect(!interruptWithoutCapability.canStop, "and only when the agent lists interrupt as well")

        // Amendment A10: hints on a terminal session the device could attach.
        val shimMissing = store(state = SessionState.readonly, control = SessionControl.terminal,
                                agent = DemoFixtures.claudeWithoutShim)
        checks.equal(shimMissing.attachHint, ChatStore.AttachHint.installShim, "an unprepared Claude device says how to prepare it")

        val shimReady = store(state = SessionState.readonly, control = SessionControl.terminal)
        checks.equal(shimReady.attachHint, ChatStore.AttachHint.restartSession, "a prepared device blames the running process instead")

        val daemonMissing = store(state = SessionState.readonly, control = SessionControl.terminal,
                                  agent = AgentInfo(agent = "codex", available = true,
                                                    capabilities = listOf(AgentCapability.takeover), attach = AgentAttach.daemon))
        checks.equal(daemonMissing.attachHint, ChatStore.AttachHint.startDaemon, "and Codex names its daemon")

        // Amendment A28: a Grok whose machine leaves the leader off, and the same agent on a machine
        // that is ready, where the running process is what was started outside it.
        val leaderOff = store(state = SessionState.readonly, control = SessionControl.terminal,
                              agent = DemoFixtures.grokWithoutLeader)
        checks.equal(leaderOff.attachHint, ChatStore.AttachHint.enableLeader,
                     "a Grok device that is not in the leader says how to put it there")

        val leaderReady = store(state = SessionState.readonly, control = SessionControl.terminal, agent = DemoFixtures.grok)
        checks.equal(leaderReady.attachHint, ChatStore.AttachHint.restartSession, "and a prepared one blames this `grok` instead")

        val noAttach = store(state = SessionState.readonly, control = SessionControl.terminal,
                             agent = AgentInfo(agent = "claude", available = true, capabilities = listOf(AgentCapability.takeover)))
        checks.expect(noAttach.attachHint == null, "an agent that cannot be attached says nothing")
        checks.assertAll()
    }

    /** Amendment A8: a block holds the position of its first appearance, live, on replay and through history. */
    @Test
    fun blockOrdering() {
        val checks = CheckRunner("timeline")
        fun streamed(seq: Int, block: String, firstSeq: Int?, text: String): SessionEvent {
            val fields = linkedMapOf<String, Any?>("block_id" to block, "delta" to text, "done" to false)
            if (firstSeq != null) fields["first_seq"] = firstSeq
            return event(seq, "assistant_text", fields)
        }

        // A block that keeps streaming does not jump below rows that arrived while it was still
        // writing.
        val timeline = Timeline()
        timeline.apply(streamed(10, block = "a", firstSeq = 10, text = "one"))
        timeline.apply(event(11, "tool_call", mapOf("block_id" to "t", "tool" to "Bash", "tool_kind" to "shell",
                                                    "title" to "pytest", "status" to "running", "first_seq" to 11)))
        timeline.apply(streamed(12, block = "a", firstSeq = 10, text = " two"))
        checks.equal(timeline.entries.map { it.id }, listOf("a", "t"), "a streaming block keeps its place while its seq rises")
        checks.equal(timeline.entry(id = "a")?.text, "one two", "and still accumulates its text")
        checks.equal(timeline.entry(id = "a")?.seq, 10, "its position is its first appearance")
        checks.equal(timeline.entry(id = "a")?.latestSeq, 12, "while its cursor follows the newest event")

        // A block first seen mid-stream sorts by where it began, not where the app happened to join.
        val joined = Timeline()
        joined.apply(event(30, "notice", mapOf("level" to "info", "text" to "reconnected")))
        joined.apply(streamed(31, block = "b", firstSeq = 20, text = "started earlier"))
        checks.equal(joined.entries.map { it.id }, listOf("b", "seq:30"), "a block that began before the cursor sorts ahead of it")

        // History pages agree with the same rule.
        val paged = Timeline()
        paged.apply(streamed(50, block = "c", firstSeq = 40, text = "late"))
        paged.prependHistory(listOf(event(45, "notice", mapOf("level" to "info", "text" to "between"))), hasMore = false)
        checks.equal(paged.entries.map { it.id }, listOf("c", "seq:45"), "history respects a block's first appearance")
        checks.equal(paged.oldestSeq, 45, "the paging cursor is a real event seq, not a block position")

        // Without first_seq the ordering is unchanged.
        val plain = Timeline()
        plain.apply(streamed(1, block = "d", firstSeq = null, text = "x"))
        plain.apply(event(2, "notice", mapOf("level" to "info", "text" to "y")))
        plain.apply(streamed(3, block = "d", firstSeq = null, text = "z"))
        checks.equal(plain.entries.map { it.id }, listOf("d", "seq:2"), "a block with no first_seq keeps the seq it was created at")
        checks.assertAll()
    }

    /** Replay both recorded sessions frame by frame and compare the result with the session summary the contract says the app must end up with. */
    @Test
    fun endToEnd() {
        val checks = CheckRunner("timeline")
        val files = FixtureSource.files(File(FixtureSource.fixtures, "timelines"))
        checks.expect(files.isNotEmpty(), "the recorded sessions are there")
        for (file in files) {
            val name = FixtureSource.label(file)
            val json = runCatching { JSONValue.parse(file.readBytes()) }.getOrNull()
            val frames = json?.get("frames")?.arrayValue
            val initial = runCatching { (json?.get("session") ?: JSONValue.emptyObject).decode<Session>() }.getOrNull()
            val expected = runCatching { (json?.get("session_final") ?: JSONValue.emptyObject).decode<Session>() }.getOrNull()
            if (frames == null || initial == null || expected == null) {
                checks.expect(false, "$name has a session, a final session and frames")
                continue
            }
            var session: Session = initial
            val timeline = Timeline()
            var applied = 0
            for (value in frames) {
                val frame = runCatching { AppFrame(json = value) }.getOrNull()
                if (frame == null) {
                    checks.expect(false, "$name frame decodes")
                    continue
                }
                when (frame) {
                    is AppFrame.SessionEvent -> {
                        if (timeline.apply(frame.event)) applied += 1
                        session = fold(frame.event, session)
                    }
                    is AppFrame.SessionUpdated -> session = frame.session
                    else -> Unit
                }
            }
            val final = session
            checks.equal(applied, frames.size, "$name: every frame applies once")
            checks.equal(timeline.lastSeq, expected.lastSeq, "$name: the cursor ends at last_seq")
            checks.equal(final.state, expected.state, "$name: the final state matches")
            checks.equal(final.control, expected.control, "$name: the final control owner matches")
            checks.equal(final.title, expected.title, "$name: the final title matches")
            checks.equal(final.todos, expected.todos, "$name: the final todo counts match")
            checks.equal(final.usage?.totalTokens, expected.usage?.totalTokens, "$name: the final token total matches")
            checks.expect(final.turn == null, "$name: the turn is closed at the end")
            checks.equal(timeline.todos.size, expected.todos?.total ?: 0, "$name: the todo snapshot matches the summary")
            checks.expect(timeline.roots.size > 3, "$name: the transcript has renderable rows")
            checks.expect(timeline.pendingRequest == null, "$name: nothing is left waiting on the user")

            // Replaying the same frames a second time must change nothing.
            val before = timeline.copy()
            for (value in frames) {
                val frame = runCatching { AppFrame(json = value) }.getOrNull()
                if (frame is AppFrame.SessionEvent) timeline.apply(frame.event)
            }
            checks.expect(timeline == before, "$name: replaying the same frames is a no-op")
        }
        checks.assertAll()
    }

    /** The session-summary rules from section 7: only status, turn and meta events move the summary, never silence. */
    private fun fold(event: SessionEvent, session: Session): Session = when (val body = event.body) {
        is SessionEventBody.Status -> session.copy(state = body.payload.state, stateDetail = body.payload.detail)
        is SessionEventBody.TurnStarted -> session.copy(turn = TurnMarker(turnID = body.payload.turnID, startedAt = event.ts))
        is SessionEventBody.TurnCompleted -> session.copy(turn = null, usage = body.payload.usage ?: session.usage)
        is SessionEventBody.Todos -> session.copy(todos = body.payload.counts)
        is SessionEventBody.Queue -> session.copy(queued = body.payload.pending.size)
        is SessionEventBody.Meta -> {
            val payload = body.payload
            session.copy(
                title = payload.title ?: session.title,
                model = payload.model ?: session.model,
                permissionMode = payload.permissionMode ?: session.permissionMode,
                effort = payload.effort ?: session.effort,
                cwd = payload.cwd ?: session.cwd,
                git = payload.git ?: session.git,
                control = payload.control ?: session.control,
            )
        }
        else -> session
    }

    /** Deltas append; the final event carries the whole text. */
    @Test
    fun streaming() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        timeline.apply(event(1, "assistant_text", mapOf("block_id" to "a", "delta" to "Hello", "done" to false)))
        timeline.apply(event(2, "assistant_text", mapOf("block_id" to "a", "delta" to " world", "done" to false)))
        checks.equal(timeline.entry(id = "a")?.text, "Hello world", "deltas append into one block")
        checks.equal(timeline.entries.size, 1, "streaming does not create extra rows")
        timeline.apply(event(3, "assistant_text", mapOf("block_id" to "a", "text" to "Hello world.", "done" to true)))
        checks.equal(timeline.entry(id = "a")?.text, "Hello world.", "the final event replaces the text")
        checks.expect(timeline.entry(id = "a")?.isStreaming == false, "a done block stops streaming")

        val thinking = Timeline()
        thinking.apply(event(1, "thinking", mapOf("block_id" to "t", "delta" to "step", "done" to false)))
        thinking.apply(event(2, "thinking", mapOf("block_id" to "t", "text" to "step one", "done" to true,
                                                  "duration_ms" to 12000)))
        checks.equal(thinking.entry(id = "t")?.thinking?.durationMS, 12000, "thinking keeps its duration")
        checks.assertAll()
    }

    /** A later event for the same block replaces the earlier one wholesale. */
    @Test
    fun replacement() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        timeline.apply(event(1, "tool_call", mapOf("block_id" to "b", "tool" to "Bash", "title" to "pytest",
                                                   "status" to "running")))
        timeline.apply(event(2, "tool_call", mapOf("block_id" to "b", "tool" to "Bash", "title" to "pytest",
                                                   "status" to "failed", "output" to "2 failed", "duration_ms" to 6400)))
        checks.equal(timeline.entries.size, 1, "replacement keeps one row")
        checks.equal(timeline.entry(id = "b")?.toolCall?.status, ToolStatus.failed, "the newest tool status wins")
        checks.equal(timeline.entry(id = "b")?.seq, 1, "a replaced block keeps its original position")

        val options = listOf(mapOf("id" to "allow", "label" to "Allow", "style" to "primary"))
        val approvals = Timeline()
        approvals.apply(event(1, "approval", mapOf("block_id" to "ap", "request_id" to "r", "tool" to "Bash",
                                                   "title" to "rm -rf", "status" to "pending", "options" to options)))
        checks.expect(approvals.pendingRequest?.id == "ap", "a pending approval is surfaced")
        approvals.apply(event(2, "approval", mapOf("block_id" to "ap", "request_id" to "r", "tool" to "Bash",
                                                   "title" to "rm -rf", "status" to "resolved", "options" to options,
                                                   "decision" to mapOf("option_id" to "allow", "by" to "remote"))))
        checks.expect(approvals.pendingRequest == null, "a resolved approval stops being actionable")
        checks.equal(approvals.entry(id = "ap")?.approval?.decision?.optionID, "allow", "the decision is recorded")
        checks.assertAll()
    }

    /** Late or duplicate frames at or below the applied cursor are dropped. */
    @Test
    fun lateFrames() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        checks.expect(timeline.apply(event(5, "notice", mapOf("level" to "info", "text" to "one"))), "seq 5 applies")
        checks.expect(!timeline.apply(event(5, "notice", mapOf("level" to "info", "text" to "duplicate"))),
                      "a duplicate seq is dropped")
        checks.expect(!timeline.apply(event(3, "notice", mapOf("level" to "info", "text" to "older"))),
                      "an older seq is dropped")
        checks.equal(timeline.entries.size, 1, "dropped frames add no rows")
        checks.equal(timeline.lastSeq, 5, "the cursor does not move backwards")
        checks.assertAll()
    }

    /** History pages prepend, never advance the live cursor, and never override a newer copy of the same block. */
    @Test
    fun history() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        timeline.apply(event(20, "assistant_text", mapOf("block_id" to "a", "text" to "newest", "done" to true)))
        timeline.prependHistory(listOf(
            event(10, "user_message", mapOf("block_id" to "u", "text" to "older question", "source" to "remote")),
            event(12, "assistant_text", mapOf("block_id" to "a", "text" to "stale copy", "done" to true)),
        ), hasMore = true)
        checks.equal(timeline.entries.firstOrNull()?.id, "u", "older history sorts to the front")
        checks.equal(timeline.entry(id = "a")?.text, "newest", "history does not overwrite a newer block")
        checks.equal(timeline.lastSeq, 20, "history does not move the live cursor")
        checks.equal(timeline.oldestSeq, 10, "the paging cursor is the oldest seq held")
        checks.expect(timeline.hasMoreHistory, "has_more is carried through")
        checks.expect(timeline.historyLoaded, "a page marks history as loaded")

        timeline.prependHistory(emptyList(), hasMore = false)
        checks.expect(!timeline.hasMoreHistory, "an exhausted history stops paging")

        timeline.reset()
        checks.equal(timeline.entries.size, 0, "resync clears the transcript")
        checks.equal(timeline.lastSeq, 0, "resync clears the cursor")
        checks.assertAll()
    }

    /** `todos` and `queue` are snapshots, not rows. */
    @Test
    fun snapshots() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        timeline.apply(event(1, "todos", mapOf("items" to listOf(
            mapOf("id" to "1", "text" to "a", "status" to "completed"),
            mapOf("id" to "2", "text" to "b", "status" to "pending"),
        ))))
        checks.equal(timeline.todos.size, 2, "todos are held as a snapshot")
        checks.equal(timeline.entries.size, 0, "todos add no timeline row")
        timeline.apply(event(2, "todos", mapOf("items" to listOf(mapOf("id" to "1", "text" to "a", "status" to "completed")))))
        checks.equal(timeline.todos.size, 1, "a todos snapshot replaces the previous list")

        timeline.apply(event(3, "queue", mapOf("pending" to listOf(mapOf("id" to "q1", "text" to "later", "ts" to 1)))))
        checks.equal(timeline.queue.size, 1, "queued messages are held as a snapshot")
        checks.equal(timeline.entries.size, 0, "queue adds no timeline row")

        timeline.apply(event(4, "status", mapOf("state" to "running")))
        timeline.apply(event(5, "meta", mapOf("title" to "New title")))
        checks.equal(timeline.entries.size, 0, "status and meta add no timeline rows")
        checks.equal(timeline.lastSeq, 5, "state-only events still advance the cursor")

        // Amendment A6: a snapshot found while paging history is older than the live one and must not
        // overwrite it.
        timeline.prependHistory(listOf(
            event(1, "todos", mapOf("items" to listOf(mapOf("id" to "a", "text" to "old", "status" to "pending"),
                                                      mapOf("id" to "b", "text" to "old", "status" to "pending"),
                                                      mapOf("id" to "c", "text" to "old", "status" to "pending")))),
            event(2, "queue", mapOf("pending" to listOf(mapOf("id" to "old1", "text" to "stale", "ts" to 1),
                                                        mapOf("id" to "old2", "text" to "stale", "ts" to 1)))),
        ), hasMore = false)
        checks.equal(timeline.todos.size, 1, "an older todos snapshot from history is ignored")
        checks.equal(timeline.queue.firstOrNull()?.id, "q1", "an older queue snapshot from history is ignored")

        // A snapshot replayed from the subscribe buffer still applies.
        timeline.apply(event(6, "todos", mapOf("items" to listOf(mapOf("id" to "1", "text" to "a", "status" to "completed"),
                                                                 mapOf("id" to "2", "text" to "b", "status" to "pending")))))
        checks.equal(timeline.todos.size, 2, "a newer todos snapshot applies")

        // The subscribe reply's queue describes the session at the cursor.
        timeline.applySubscribedQueue(listOf(QueuedMessage(id = "sub", text = "from the reply", ts = 2)))
        checks.equal(timeline.queue.firstOrNull()?.id, "sub", "the subscribe reply's queue applies")
        timeline.apply(event(7, "queue", mapOf("pending" to emptyList<Any>())))
        checks.equal(timeline.queue.size, 0, "a later queue event still wins over the reply")
        checks.assertAll()
    }

    /** Sub-agent output hangs under the tool call that produced it. */
    @Test
    fun nesting() {
        val checks = CheckRunner("timeline")
        val timeline = Timeline()
        timeline.apply(event(1, "tool_call", mapOf("block_id" to "task", "tool" to "Task",
                                                   "title" to "Audit the lock", "status" to "running")))
        timeline.apply(event(2, "assistant_text", mapOf("block_id" to "sub", "parent_block_id" to "task",
                                                        "text" to "found it", "done" to true)))
        checks.equal(timeline.roots.size, 1, "a nested row does not appear at the top level")
        checks.equal(timeline.children(of = "task").size, 1, "the nested row hangs under its parent")
        checks.equal(timeline.children(of = "task").firstOrNull()?.text, "found it", "nested text is preserved")
        checks.assertAll()
    }
}

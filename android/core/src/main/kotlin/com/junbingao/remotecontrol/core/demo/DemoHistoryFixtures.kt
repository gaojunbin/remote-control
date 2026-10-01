package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.demo.DemoFixtures.approvalSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.attachHintSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.codexSharedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.erroredSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.grokSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.grokSharedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.liveSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.now
import com.junbingao.remotecontrol.core.demo.DemoFixtures.pausedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.piSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.revivedSessionID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.sharedSessionID
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.DiffPayload
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionUsage
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TodosPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.jsonArrayOf
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf

/**
 * The transcripts the demo sessions open with, part of [DemoFixtures]: here those of the sessions
 * no terminal holds, and in [DemoTerminalHistoryFixtures] those a terminal holds or shares.
 */
sealed interface DemoHistoryFixtures {
    /** The transcript the live demo session opens with. */
    fun liveHistory(base: Long = now - 300_000): List<SessionEvent> {
        var seq = 0
        fun next(): Int {
            seq += 1
            return seq
        }
        return listOf(
            SessionEvent(seq = next(), ts = base, kind = SessionEvent.turnStartedKind,
                         body = SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "demo-turn-1",
                                                                                trigger = EventSource.remote))),
            SessionEvent(seq = next(), ts = base + 100, kind = SessionEvent.userMessageKind, blockID = "u-1",
                         body = SessionEventBody.UserMessage(UserMessagePayload(
                             text = "test_refresh_flow fails ~1 in 5 on CI, never locally. Find the race and fix it."))),
            SessionEvent(seq = next(), ts = base + 900, kind = SessionEvent.thinkingKind, blockID = "t-1",
                         body = SessionEventBody.Thinking(StreamTextPayload(
                             text = "The failure only appears under parallel execution, so shared module state " +
                                 "is the first suspect.",
                             done = true, durationMS = 12_000))),
            SessionEvent(seq = next(), ts = base + 1_400, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                         body = SessionEventBody.AssistantText(StreamTextPayload(
                             text = "Reproducing first — the refresh test shares a module-level clock, so a " +
                                 "scheduled expiry can leak between tests.",
                             done = true))),
            SessionEvent(seq = next(), ts = base + 2_000, kind = SessionEvent.toolCallKind, blockID = "tool-1",
                         body = SessionEventBody.ToolCall(ToolCallPayload(
                             tool = "Read", kind = ToolKind.read, title = "4 files in tests/ and auth/",
                             status = ToolStatus.succeeded,
                             input = jsonObjectOf("paths" to jsonArrayOf("tests/test_auth.py", "auth/session.py")),
                             output = "tests/test_auth.py (218 lines)\nauth/session.py (94 lines)",
                             startedAt = base + 2_000, endedAt = base + 3_200, durationMS = 1_200))),
            SessionEvent(seq = next(), ts = base + 3_400, kind = SessionEvent.toolCallKind, blockID = "tool-2",
                         body = SessionEventBody.ToolCall(ToolCallPayload(
                             tool = "Bash", kind = ToolKind.shell, title = "pytest -k refresh --count 20",
                             status = ToolStatus.failed,
                             input = jsonObjectOf("command" to "pytest -k refresh --count 20"),
                             output = """
                                 ============================= test session starts =============================
                                 collected 20 items

                                 tests/test_auth.py ....F..............F                                  [100%]

                                 ================================== FAILURES ===================================
                                 AssertionError: token expired earlier than scheduled
                                 2 failed, 18 passed in 6.40s
                             """.trimIndent(),
                             summary = "2 failed",
                             startedAt = base + 3_400, endedAt = base + 9_800, durationMS = 6_400))),
            SessionEvent(seq = next(), ts = base + 10_000, kind = SessionEvent.toolCallKind, blockID = "tool-3",
                         body = SessionEventBody.ToolCall(ToolCallPayload(
                             tool = "Edit", kind = ToolKind.edit, title = "auth/session.py", status = ToolStatus.succeeded,
                             diff = DiffPayload(path = "auth/session.py", additions = 12, deletions = 4, patch = """
                                 @@ -18,7 +18,15 @@ class SessionStore:
                                 -    clock = time.monotonic
                                 +    clock = _test_clock or time.monotonic
                                 +
                                 +    def _guarded_refresh(self, token):
                                 +        with self._lock:
                                 +            return self._refresh(token)
                             """.trimIndent()),
                             startedAt = base + 10_000, endedAt = base + 10_900, durationMS = 900))),
            SessionEvent(seq = next(), ts = base + 11_000, kind = SessionEvent.toolCallKind, blockID = "tool-4",
                         body = SessionEventBody.ToolCall(ToolCallPayload(
                             tool = "Edit", kind = ToolKind.edit, title = "tests/conftest.py", status = ToolStatus.succeeded,
                             diff = DiffPayload(path = "tests/conftest.py", additions = 8, deletions = 1),
                             startedAt = base + 11_000, endedAt = base + 11_400, durationMS = 400))),
            SessionEvent(seq = next(), ts = base + 12_000, kind = SessionEvent.assistantTextKind, blockID = "a-2",
                         body = SessionEventBody.AssistantText(StreamTextPayload(
                             text = "Each test now gets its own frozen clock and refresh is guarded by the session " +
                                 "lock. Re-running the suite 100× to confirm.\n\n" + """
                                 | Change | File | Effect |
                                 | --- | --- | --- |
                                 | Frozen clock | `tests/conftest.py` | no shared expiry |
                                 | Locked refresh | `auth/session.py` | one writer at a time |
                             """.trimIndent(),
                             done = true))),
            SessionEvent(seq = next(), ts = base + 12_500, kind = SessionEvent.todosKind,
                         body = SessionEventBody.Todos(TodosPayload(items = listOf(
                             TodoItem(id = "1", text = "Reproduce the flake", status = TodoStatus.completed),
                             TodoItem(id = "2", text = "Isolate the shared clock", status = TodoStatus.inProgress),
                             TodoItem(id = "3", text = "Guard refresh with the session lock", status = TodoStatus.pending),
                             TodoItem(id = "4", text = "Re-run the suite 100 times", status = TodoStatus.pending),
                         )))),
            SessionEvent(seq = next(), ts = base + 13_000, kind = SessionEvent.toolCallKind, blockID = "tool-5",
                         body = SessionEventBody.ToolCall(ToolCallPayload(
                             tool = "Bash", kind = ToolKind.shell, title = "pytest tests/test_auth.py --count 100 -q",
                             status = ToolStatus.running,
                             input = jsonObjectOf("command" to "pytest tests/test_auth.py --count 100 -q"),
                             output = "................................... 72%\n72 passed in 38.02s",
                             startedAt = base + 13_000))),
        )
    }

    /** The transcript for the session that is waiting on an approval. */
    fun approvalHistory(base: Long = now - 180_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(text = "Upgrade the web app to Vite 6."))),
        SessionEvent(seq = 2, ts = base + 800, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "The lockfile needs a clean reinstall. I need permission to remove it first.",
                         done = true))),
        SessionEvent(seq = 3, ts = base + 1_200, kind = SessionEvent.approvalKind, blockID = "ap-1",
                     body = SessionEventBody.Approval(ApprovalPayload(
                         requestID = "demo-approval-1", tool = "Bash", kind = ToolKind.shell,
                         title = "rm -rf node_modules package-lock.json && npm install",
                         input = jsonObjectOf("command" to "rm -rf node_modules package-lock.json && npm install",
                                              "cwd" to "/Users/me/dev/remote-control/web"),
                         options = listOf(
                             ApprovalOption(id = "approved", label = "Approve once", style = OptionStyle.primary),
                             ApprovalOption(id = "approved_for_session", label = "Approve for this session",
                                            style = OptionStyle.secondary),
                             ApprovalOption(id = "denied", label = "Deny", style = OptionStyle.danger),
                         ),
                         status = RequestStatus.pending))),
    )

    /**
     * Amendment A25: a pi turn. pi reports its usage and its cost at the end of a turn, and asks
     * for no approvals on the way.
     */
    fun piHistory(base: Long = now - 1_200_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Rewrite the config parser so an unknown key is an error, not a warning."))),
        SessionEvent(seq = 2, ts = base + 1_500, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Unknown keys now raise `ConfigError` and name the line they came from.",
                         done = true))),
        SessionEvent(seq = 3, ts = base + 1_600, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-pi", stopReason = StopReason.completed, durationMS = 19_000,
                         usage = SessionUsage(inputTokens = 8_100, outputTokens = 2_300,
                                              totalTokens = 10_400, costUSD = 0.06)))),
    )

    /** The transcript of the turn that ended on an error. */
    fun erroredHistory(base: Long = now - 1_200_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Move the package to the 6.3 toolchain and rebuild."))),
        SessionEvent(seq = 2, ts = base + 1_200, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Updated `swift-tools-version` and started the build.", done = true))),
        SessionEvent(seq = 3, ts = base + 44_000, kind = SessionEvent.errorKind,
                     body = SessionEventBody.Error(ErrorPayload(
                         message = "The agent exited before the build finished.",
                         code = "agent_exited"))),
        SessionEvent(seq = 4, ts = base + 44_100, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-toolchain", stopReason = StopReason.error,
                         durationMS = 44_000))),
    )

    /** The short transcript the archived session carries before it is resumed. */
    fun revivedHistory(base: Long = now - 5_400_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(text = "Draft the 0.1.0 changelog."))),
        SessionEvent(seq = 2, ts = base + 2_000, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Drafted it from the commit log. The wording still needs a pass.",
                         done = true))),
        SessionEvent(seq = 3, ts = base + 3_000, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-changelog", stopReason = StopReason.completed,
                         durationMS = 18_000))),
    )

    fun history(sessionID: String): List<SessionEvent> = when (sessionID) {
        liveSessionID -> liveHistory()
        approvalSessionID -> approvalHistory()
        sharedSessionID -> DemoFixtures.sharedHistory()
        codexSharedSessionID -> DemoFixtures.codexSharedHistory()
        attachHintSessionID -> DemoFixtures.attachHintHistory()
        erroredSessionID -> erroredHistory()
        revivedSessionID -> revivedHistory()
        grokSessionID -> DemoFixtures.grokHistory()
        grokSharedSessionID -> DemoFixtures.grokSharedHistory()
        piSessionID -> piHistory()
        pausedSessionID -> DemoFixtures.pausedHistory()
        else -> listOf(
            SessionEvent(seq = 1, ts = now - 3_600_000, kind = SessionEvent.userMessageKind, blockID = "u-1",
                         body = SessionEventBody.UserMessage(UserMessagePayload(text = "Add OTLP traces to the API."))),
            SessionEvent(seq = 2, ts = now - 3_599_000, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                         body = SessionEventBody.AssistantText(StreamTextPayload(
                             text = "Done. Spans now cover the request handler and the database calls.",
                             done = true))),
            SessionEvent(seq = 3, ts = now - 3_598_000, kind = SessionEvent.turnCompletedKind,
                         body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                             turnID = "demo-turn-otlp", stopReason = StopReason.completed,
                             durationMS = 42_000))),
        )
    }
}

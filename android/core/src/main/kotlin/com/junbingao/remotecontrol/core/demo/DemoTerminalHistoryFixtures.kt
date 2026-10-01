package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.demo.DemoFixtures.now
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.LimitStop
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf

/** The transcripts of the demo sessions a terminal holds or shares, part of [DemoFixtures]. */
sealed interface DemoTerminalHistoryFixtures {
    /** The transcript of the attached terminal session: the developer typed in the CLI, and the app is reading along. */
    fun sharedHistory(base: Long = now - 240_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Draft the release notes for 0.1.0 from the merged pull requests.",
                         source = EventSource.terminal))),
        SessionEvent(seq = 2, ts = base + 1_100, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Drafted `docs/RELEASE-NOTES.md` from the 14 merged pull requests, grouped by " +
                             "gateway, device client and apps.",
                         done = true))),
        // Amendment A20: an earlier question the person at the terminal answered in their own
        // dialog before this phone got to it.
        SessionEvent(seq = 3, ts = base + 1_200, kind = SessionEvent.questionKind, blockID = "q-shared-past",
                     body = SessionEventBody.Question(QuestionPayload(
                         requestID = "demo-question-shared-past",
                         questions = listOf(QuestionItem(
                             id = "q1",
                             prompt = "Should the notes name every contributor, or only the changes?",
                             options = listOf(QuestionOption(id = "changes", label = "Only the changes"),
                                              QuestionOption(id = "everyone", label = "Name every contributor")),
                             allowText = true)),
                         status = RequestStatus.resolved,
                         answers = mapOf("q1" to QuestionAnswer.Options(listOf("changes"))),
                         by = EventSource.terminal))),
        SessionEvent(seq = 4, ts = base + 1_400, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-shared", stopReason = StopReason.completed, durationMS = 31_000))),
        // Amendment A30: a teammate's report the Claude CLI filed as a user turn. Nobody typed it,
        // and the turn it started says so too.
        SessionEvent(seq = 5, ts = base + 60_000, kind = SessionEvent.userMessageKind, blockID = "u-agent",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "recon-ios: Recon complete. Fact sheet written to the scratchpad; " +
                             "three findings need a decision.",
                         source = EventSource.agent))),
        SessionEvent(seq = 6, ts = base + 60_100, kind = SessionEvent.turnStartedKind,
                     body = SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "demo-turn-shared-agent",
                                                                            trigger = EventSource.agent))),
        SessionEvent(seq = 7, ts = base + 62_000, kind = SessionEvent.assistantTextKind, blockID = "a-agent",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Read the fact sheet. The three open findings are listed below with " +
                             "what each one costs.",
                         done = true))),
        SessionEvent(seq = 8, ts = base + 62_400, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-shared-agent", stopReason = StopReason.completed, durationMS = 2_300))),
    )

    /**
     * Amendment A20: the question the attached Claude asks while this phone is looking at it. The
     * terminal is showing its own dialog for the same one.
     */
    val sharedQuestion: QuestionPayload
        get() = QuestionPayload(
            requestID = "demo-question-shared",
            questions = listOf(QuestionItem(
                id = "q1",
                prompt = "The 0.1.0 notes still have no headline. What should it say?",
                options = listOf(QuestionOption(id = "remote", label = "Remote control for your terminal agents"),
                                 QuestionOption(id = "phone", label = "Your coding agent, from your phone")),
                allowText = true)),
        )

    /**
     * The transcript of the shared Codex thread: typed in the terminal, and waiting on the four
     * decisions the daemon offers for a command.
     */
    fun codexSharedHistory(base: Long = now - 300_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Typecheck the web app and fix whatever the settings drawer broke.",
                         source = EventSource.terminal))),
        SessionEvent(seq = 2, ts = base + 1_300, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "`SessionSettings` lost its `effort` prop when the drawer moved. I will run " +
                             "the typecheck to see the full list first.",
                         done = true))),
        SessionEvent(seq = 3, ts = base + 1_900, kind = SessionEvent.approvalKind, blockID = "ap-codex",
                     body = SessionEventBody.Approval(ApprovalPayload(
                         requestID = "demo-approval-codex", tool = "shell", kind = ToolKind.shell,
                         title = "npm run typecheck",
                         input = jsonObjectOf("command" to "npm run typecheck",
                                              "cwd" to "/Users/me/dev/remote-control/web"),
                         options = listOf(
                             ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary),
                             ApprovalOption(id = "allow_session", label = "Allow for this session",
                                            style = OptionStyle.secondary),
                             ApprovalOption(id = "allow_always", label = "Always allow commands like this",
                                            style = OptionStyle.secondary),
                             ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger),
                         ),
                         status = RequestStatus.pending))),
    )

    /** The transcript of the terminal session the device cannot attach to. */
    fun attachHintHistory(base: Long = now - 300_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Rename the pairing flow to enrolment across the client.",
                         source = EventSource.terminal))),
        SessionEvent(seq = 2, ts = base + 900, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Renamed 23 symbols and updated the install script.", done = true))),
    )

    /**
     * Amendment A25: what the device read out of Grok Build's own update log while a person drove
     * the terminal. Every message carries the terminal as its source, because none of it came
     * from an app.
     */
    fun grokHistory(base: Long = now - 3_000_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "Squash the pending migrations into one and keep the down path working.",
                         source = EventSource.terminal))),
        SessionEvent(seq = 2, ts = base + 1_100, kind = SessionEvent.thinkingKind, blockID = "t-1",
                     body = SessionEventBody.Thinking(StreamTextPayload(
                         text = "Four migrations touch the same two tables, so the order they ran in matters.",
                         done = true, durationMS = 8_000))),
        SessionEvent(seq = 3, ts = base + 2_400, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Collapsed the four migrations into `0007_sessions.sql` and kept the reverse.",
                         done = true))),
        SessionEvent(seq = 4, ts = base + 2_500, kind = SessionEvent.turnCompletedKind,
                     body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                         turnID = "demo-turn-grok", stopReason = StopReason.completed, durationMS = 31_000))),
    )

    /**
     * Amendment A28: a turn the terminal set off inside the leader, read by the device as another
     * client of the same session. The prompt is the terminal's, and the turn is still running, so
     * the app can stop it.
     */
    fun grokSharedHistory(base: Long = now - 300_000): List<SessionEvent> = listOf(
        SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                     body = SessionEventBody.UserMessage(UserMessagePayload(
                         text = "The gateway retries a failed publish forever. Give it a budget and a ceiling.",
                         source = EventSource.terminal))),
        SessionEvent(seq = 2, ts = base + 1_200, kind = SessionEvent.thinkingKind, blockID = "t-1",
                     body = SessionEventBody.Thinking(StreamTextPayload(
                         text = "The retry loop has no ceiling, so a device that never answers holds the queue open.",
                         done = true, durationMS = 9_000))),
        SessionEvent(seq = 3, ts = base + 2_600, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                     body = SessionEventBody.AssistantText(StreamTextPayload(
                         text = "Five attempts with exponential backoff, capped at a minute. Writing it now.",
                         done = true))),
        SessionEvent(seq = 4, ts = base + 3_100, kind = SessionEvent.toolCallKind, blockID = "tool-grok-1",
                     body = SessionEventBody.ToolCall(ToolCallPayload(
                         tool = "Edit", kind = ToolKind.edit, title = "gateway/publish.py",
                         status = ToolStatus.running, startedAt = base + 3_100))),
    )

    /**
     * Amendment A35: the resume this demo's paused session is waiting on — three quarters of an
     * hour out, from a five-hour window the vendor named a reset time for, so the notice reads a
     * time rather than "about" one.
     */
    val pendingResume: SessionResume
        get() = SessionResume(at = now + 2_700_000, estimated = false, attempts = 0, windowMinutes = 300)

    /**
     * The transcript of a turn the usage limit ended: the turn closes with `limit`, the vendor's
     * own sentence goes out as an error rather than as the agent's words, and the device says what
     * it scheduled.
     */
    fun pausedHistory(base: Long = now - 1_800_000): List<SessionEvent> {
        val resume = pendingResume
        return listOf(
            SessionEvent(seq = 1, ts = base, kind = SessionEvent.userMessageKind, blockID = "u-1",
                         body = SessionEventBody.UserMessage(UserMessagePayload(
                             text = "Reindex the corpus and report what changed."))),
            SessionEvent(seq = 2, ts = base + 1_800, kind = SessionEvent.assistantTextKind, blockID = "a-1",
                         body = SessionEventBody.AssistantText(StreamTextPayload(
                             text = "Walking the corpus now. I have three subagents on the shards.",
                             done = true))),
            SessionEvent(seq = 3, ts = base + 61_000, kind = SessionEvent.errorKind,
                         body = SessionEventBody.Error(ErrorPayload(
                             message = "You've hit your session limit · resets 10:20pm (Asia/Singapore)",
                             code = "rate_limit"))),
            SessionEvent(seq = 4, ts = base + 61_100, kind = SessionEvent.turnCompletedKind,
                         body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                             turnID = "demo-turn-indexer", stopReason = StopReason.error, durationMS = 61_000,
                             limit = LimitStop(windowMinutes = 300, resetsAt = resume.at - 60_000)))),
            SessionEvent(seq = 5, ts = base + 61_200, kind = SessionEvent.resumeKind,
                         body = SessionEventBody.Resume(ResumePayload(status = ResumeStatus.scheduled, at = resume.at,
                                                                      estimated = false))),
        )
    }
}

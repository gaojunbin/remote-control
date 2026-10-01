package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SessionUsage
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TurnMarker
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.chat.header.ChatHeaderModel
import com.junbingao.remotecontrol.win.chat.timeline.StatusLineModel
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agents as the mock gateway describes them (`web/mock/fixtures.ts`): what the status line and the header read of them. */
internal object ChatAgents {
    val claude = AgentInfo(
        agent = "claude",
        available = true,
        capabilities = listOf(
            AgentCapability.worktree, AgentCapability.takeover, AgentCapability.interrupt, AgentCapability.queue,
            AgentCapability.attachments, AgentCapability.effort, AgentCapability.history, AgentCapability.commands,
        ),
        attach = AgentAttach.channel,
        attachReady = true,
        sharedInterrupt = true,
        sharedSettings = true,
        sharedSettingsKeys = listOf("model", "effort"),
    )
    val codex = AgentInfo(
        agent = "codex",
        available = true,
        capabilities = listOf(
            AgentCapability.worktree, AgentCapability.interrupt, AgentCapability.queue, AgentCapability.steer,
            AgentCapability.attachments, AgentCapability.effort, AgentCapability.history, AgentCapability.commands,
        ),
        attach = AgentAttach.daemon,
        attachReady = true,
        sharedInterrupt = true,
        sharedSettings = true,
        sharedAttachments = true,
    )

    /** A device whose shim carries no interrupt (before A42). */
    val claudeWithoutInterrupt = AgentInfo(
        agent = "claude",
        available = true,
        capabilities = listOf(AgentCapability.takeover, AgentCapability.interrupt, AgentCapability.queue, AgentCapability.history),
        attach = AgentAttach.channel,
        attachReady = true,
    )

    fun session(
        state: SessionState = SessionState.idle,
        control: SessionControl = SessionControl.remote,
        agent: String = "claude",
        stateDetail: String? = null,
    ): Session = Session(
        sessionID = "ses-1",
        deviceID = "dev-1",
        agent = agent,
        title = "Fix flaky auth test",
        cwd = "/Users/me/dev/gateway",
        state = state,
        stateDetail = stateDetail,
        control = control,
    )
}

/** `web/tests/StatusLine.test.tsx`. */
class ChatStatusLineTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun line(session: Session, agent: AgentInfo? = ChatAgents.claude, online: Boolean = true, editingQueued: Boolean = false): StatusLineModel? =
        StatusLineModel.of(session = session, agent = agent, deviceOnline = online, editingQueued = editingQueued)

    @Test
    fun theTerminalIsInControlWhileItsTurnRuns() {
        val model = line(ChatAgents.session(state = SessionState.running, control = SessionControl.terminal))
        assertEquals("Controlled by the terminal · a turn is running there · take over to send", model?.text)
        assertEquals(StatusLineModel.Tone.running, model?.tone)
        assertEquals(true, model?.offersTakeover)
    }

    @Test
    fun anIdleTerminalSaysHowToWriteToIt() {
        val model = line(ChatAgents.session(state = SessionState.readonly, control = SessionControl.terminal))
        assertEquals("Controlled by the terminal · take over to send", model?.text)
        assertEquals(StatusLineModel.Tone.muted, model?.tone)
    }

    @Test
    fun noTakeoverWhereTheAgentHasNone() {
        val model = line(ChatAgents.session(state = SessionState.readonly, control = SessionControl.terminal, agent = "codex"), ChatAgents.codex)
        assertEquals("Controlled by the terminal", model?.text)
        assertEquals(false, model?.offersTakeover)
    }

    @Test
    fun aRunningRemoteTurnQueues() {
        assertEquals("Claude Code is working · your message will be queued", line(ChatAgents.session(state = SessionState.running))?.text)
    }

    @Test
    fun aSteeringAgentSteers() {
        assertEquals(
            "Codex is working · your message will steer the turn",
            line(ChatAgents.session(state = SessionState.running, agent = "codex"), ChatAgents.codex)?.text,
        )
    }

    @Test
    fun anEditedQueuedMessageSaysQueued() {
        assertEquals(
            "Codex is working · your message will be queued",
            line(ChatAgents.session(state = SessionState.running, agent = "codex"), ChatAgents.codex, editingQueued = true)?.text,
        )
    }

    @Test
    fun approvalsQuestionsAndErrors() {
        assertEquals("Needs your approval", line(ChatAgents.session(state = SessionState.needsApproval))?.text)
        assertEquals(StatusLineModel.Tone.attention, line(ChatAgents.session(state = SessionState.needsApproval))?.tone)
        assertEquals("Waiting for your answer", line(ChatAgents.session(state = SessionState.needsInput))?.text)
        assertEquals("the CLI exited with 1", line(ChatAgents.session(state = SessionState.error, stateDetail = "the CLI exited with 1"))?.text)
        assertEquals("Errored", line(ChatAgents.session(state = SessionState.error))?.text)
        assertEquals("Stopped", line(ChatAgents.session(state = SessionState.stopped))?.text)
        assertEquals("Starting the agent…", line(ChatAgents.session(state = SessionState.starting))?.text)
    }

    @Test
    fun anOfflineDeviceComesBeforeAnythingElse() {
        assertEquals("Device offline", line(ChatAgents.session(state = SessionState.running, control = SessionControl.terminal), online = false)?.text)
    }

    @Test
    fun anIdleSessionHasNoLine() {
        assertNull(line(ChatAgents.session()))
    }
}

/** `web/src/features/chat/ChatHeader.tsx`: Stop, the todo chip and the usage chip. */
class ChatHeaderTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun stopIsOfferedOnlyWhereThisAppMayEndTheTurn() {
        val running = ChatAgents.session(state = SessionState.running)
        assertTrue(ChatHeaderModel(session = running, agent = ChatAgents.claude, todos = emptyList(), detail = TimelineDetail.simple).offersStop)
        val terminal = running.copy(control = SessionControl.terminal)
        assertFalse(ChatHeaderModel(session = terminal, agent = ChatAgents.claude, todos = emptyList(), detail = TimelineDetail.simple).offersStop)
        val shared = running.copy(control = SessionControl.shared)
        assertTrue(ChatHeaderModel(session = shared, agent = ChatAgents.claude, todos = emptyList(), detail = TimelineDetail.simple).offersStop)
        assertFalse(
            ChatHeaderModel(session = shared, agent = ChatAgents.claudeWithoutInterrupt, todos = emptyList(), detail = TimelineDetail.simple).offersStop,
        )
        val sharedCodex = shared.copy(agent = "codex")
        assertTrue(ChatHeaderModel(session = sharedCodex, agent = ChatAgents.codex, todos = emptyList(), detail = TimelineDetail.simple).offersStop)
        assertFalse(
            ChatHeaderModel(
                session = ChatAgents.session(state = SessionState.idle),
                agent = ChatAgents.claude,
                todos = emptyList(),
                detail = TimelineDetail.simple,
            ).offersStop,
        )
    }

    @Test
    fun theTodoChipIsTheAgentsWorkingsSoSimpleDrawsNone() {
        val session = ChatAgents.session(state = SessionState.running).copy(todos = TodoCounts(total = 4, done = 1))
        assertNull(ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.simple).todos)
        val counts = ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.detailed).todos
        assertEquals(TodoCounts(total = 4, done = 1), counts)
        val items = listOf(TodoItem(id = "1", text = "a", status = TodoStatus.completed), TodoItem(id = "2", text = "b", status = TodoStatus.pending))
        assertEquals(TodoCounts(total = 4, done = 1), ChatHeaderModel(session = session, agent = null, todos = items, detail = TimelineDetail.detailed).todos)
    }

    @Test
    fun theUsageChipSaysTokensAndTheTurnsTime() {
        var session = ChatAgents.session(state = SessionState.running)
        assertNull(ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.simple).usage)
        session = session.copy(usage = SessionUsage(totalTokens = 48_200))
        assertEquals("48.2k", ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.simple).usage)
        session = session.copy(turn = TurnMarker(turnID = "t", startedAt = 1_000))
        assertEquals(
            "48.2k · 1m 12s",
            ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.simple, now = 73_000).usage,
        )
        session = session.copy(usage = null)
        assertEquals("6.4s", ChatHeaderModel(session = session, agent = null, todos = emptyList(), detail = TimelineDetail.simple, now = 7_400).usage)
    }
}

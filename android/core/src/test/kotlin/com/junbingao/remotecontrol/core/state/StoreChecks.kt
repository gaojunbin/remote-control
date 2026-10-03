package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.HistoryResult
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.MetaPayload
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TodosPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.arrayValue
import com.junbingao.remotecontrol.core.protocol.intValue
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.SocketCloseReason
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `ios/Verification/StoreChecks.swift`: store behaviour that needs no screen, every check of it, on
 * the demo gateway where RCCore runs it there. Each check records every result through one
 * [CheckRunner] and ends the test with all of them, as RCCore's group reports them.
 */
class StoreChecks {
    /** One of RCCore's check functions: a body that may stop early, as a `guard` does, and then every result it recorded. */
    private fun check(body: suspend TestScope.(CheckRunner) -> Unit): TestResult = runTest {
        val checks = CheckRunner("stores")
        body(checks)
        checks.assertAll()
    }

    /** RCCore's `ConnectionStore()`, whose cache is the scratch directory the check deletes when it is done. */
    private fun TestScope.connectionStore(directory: File): ConnectionStore =
        ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { StubGateway(it) })

    /**
     * Amendment A27: the terminal's `/` menu, on the phone. One rule decides whether a draft is a
     * command, which rows are left on screen and what Send does with it, so the panel, the hint and
     * the button agree.
     */
    @Test
    fun slashCommands() = check { checks ->
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        fun chat(sessionID: String, agent: AgentInfo): ChatStore? {
            val session = DemoFixtures.sessions.firstOrNull { it.sessionID == sessionID }
            if (session == null) {
                checks.expect(false, "the demo carries $sessionID")
                return null
            }
            val store = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
            store.agent = agent
            return store
        }

        val pi = chat(DemoFixtures.piSessionID, DemoFixtures.pi) ?: return@check
        checks.expect(pi.offersCommands, "pi takes commands from an app")
        pi.loadCommands()
        checks.equal(pi.commands.size, DemoFixtures.piCommands.size, "and the device answers with what the session offers now")

        pi.draft = "/"
        checks.equal(pi.commandRows.size, pi.commands.size, "a bare slash opens the whole list")
        checks.equal(pi.commandSections.map { it.title }, listOf("Prompts", "Skills", "Extensions", "Built-in"),
                     "sectioned by where each command came from")
        pi.draft = "/skill:"
        checks.equal(pi.commandRows.size, 3, "and filtered by prefix as more is typed")
        checks.equal(pi.commandSections.size, 1, "a filter that leaves one group behind leaves its header behind too")
        checks.expect(pi.draftCommand == null, "a half-typed name runs nothing")

        pi.commandRows.firstOrNull()?.let { row ->
            pi.take(row)
            checks.equal(pi.draft, "/skill:pdf-tables", "a command that takes nothing is written ready to run")
            checks.equal(pi.commandRows.map { it.name }, listOf("skill:pdf-tables"),
                         "and the card is left showing the one row it now names")
            checks.equal(pi.draftCommand?.name, "skill:pdf-tables", "which Send runs")
        }
        pi.draft = "/release-notes "
        checks.expect(pi.commandRows.isEmpty(), "a finished name closes the panel")
        checks.equal(pi.commandHint?.argument, "tag", "and hands the placeholder to the line below")
        checks.equal(pi.draftCommand?.name, "release-notes", "which is what Send would run")
        checks.expect(pi.canSend, "on an idle session")

        // A message that mentions a path is a message.
        pi.draft = "read /etc/hosts"
        checks.expect(pi.commandDraft == null, "a slash inside a sentence is prose")
        checks.expect(pi.draftCommand == null, "and Send treats it as the message it is")

        // Amendment A40: the same typing that sets the model runs one command, so Claude carries the
        // capability and offers exactly `/compact`.
        val claude = chat(DemoFixtures.liveSessionID, DemoFixtures.claude) ?: return@check
        claude.loadCommands()
        checks.expect(claude.offersCommands, "Claude takes the one command it can be typed")
        checks.equal(claude.commands.map { it.name }, listOf("compact"), "which is /compact and nothing else")
        claude.draft = "/comp"
        checks.equal(claude.commandRows.map { it.name }, listOf("compact"), "and the panel names it")

        // A machine with no shim has no pseudo-terminal to type into, so that same agent takes no
        // commands there.
        val bare = chat(DemoFixtures.liveSessionID, DemoFixtures.claudeWithoutShim) ?: return@check
        checks.expect(!bare.offersCommands, "and without the shim it takes none")

        // A session a terminal holds takes nothing typed here, whatever it offers, so the panel stays
        // shut on it too.
        val grok = chat(DemoFixtures.grokSessionID, DemoFixtures.grok) ?: return@check
        grok.loadCommands()
        checks.equal(grok.commands.size, DemoFixtures.grokCommands.size, "Grok Build advertises its own list")
        grok.draft = "/hooks"
        checks.expect(grok.commandDraft == null, "but a terminal-held session draws no panel")

        // A turn is running: the rows are dimmed, the footer says why, and Send does not act until it
        // finishes.
        val codex = chat(DemoFixtures.codexSharedSessionID, DemoFixtures.codex) ?: return@check
        codex.loadCommands()
        codex.draft = "/usage"
        checks.expect(codex.commandsWaitForTurn, "a running turn dims the card")
        checks.expect(codex.commandRows.isNotEmpty(), "which is still readable")
        checks.expect(!codex.canSend, "and Send does not run a command inside a turn")
        codex.runCommand()
        checks.equal(codex.draft, "/usage", "so nothing leaves the field")
        checks.expect(codex.timeline.optimistic.isEmpty(), "and nothing is drawn in the transcript")
    }

    /**
     * Amendment A17: a session a terminal holds shows what the device read from the transcript where
     * the live controls would be. Nothing here can change them, so the store offers text rather than
     * an action, and a `meta` moves it. Amendment A21: model, effort and speed are one control, so
     * they are one chip, and the tier rides on it.
     */
    @Test
    fun terminalSettings() = check { checks ->
        fun store(control: SessionControl, agent: AgentInfo? = DemoFixtures.claude, model: String? = "claude-sonnet-4-5",
                  permissionMode: String? = "auto", effort: String? = "high", speed: String? = null): ChatStore {
            val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                                  state = SessionState.idle, control = control, model = model,
                                  permissionMode = permissionMode, effort = effort, speed = speed)
            val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
            chat.agent = agent
            return chat
        }
        fun shown(chat: ChatStore): List<String> = chat.terminalSettings.map { it.text }

        val terminal = store(control = SessionControl.terminal)
        checks.expect(!terminal.allowsModelCardChanges, "a terminal session offers no model card")
        checks.expect(!terminal.allowsSettingsChanges(SharedSetting.permissionMode), "and no permission picker either")
        checks.equal(terminal.terminalSettings.map { it.id }, listOf("modelCard", "permissionMode"),
                     "and the values stand in the order the live controls stand in")
        checks.equal(shown(terminal), listOf("Sonnet 4.5 High", "auto"),
                     "labelled by the agent's own lists, and by the raw id where they do not know it")

        // Amendment A40: the shim types `/model` and `/effort` into the terminal, so the card is a
        // control on a shared Claude session and only the permission mode is left standing as a value.
        val typed = store(control = SessionControl.shared)
        checks.expect(typed.allowsModelCardChanges, "what the device can type is a control again")
        checks.expect(!typed.isSettingPending, "and it waits for the device only while a change is being typed in")
        checks.expect(!typed.allowsSettingsChanges(SharedSetting.permissionMode), "and what it has no command for is not")
        checks.equal(shown(typed), listOf("auto"), "so one value stands where two did, and the card is live beside it")
        checks.expect(!store(control = SessionControl.shared, agent = DemoFixtures.claudeWithoutShim).allowsModelCardChanges,
                      "a machine with no shim has no pseudo-terminal to type into")
        checks.expect(store(control = SessionControl.shared, agent = DemoFixtures.codex).terminalSettings.isEmpty(),
                      "an attachment that carries every setting keeps its controls and shows nothing")
        checks.expect(store(control = SessionControl.remote).terminalSettings.isEmpty(), "and a session this app drives shows nothing")

        checks.equal(shown(store(control = SessionControl.terminal, effort = null)), listOf("Sonnet 4.5", "auto"),
                     "a value the device has not seen draws nothing at all")
        checks.expect(store(control = SessionControl.terminal, model = null, permissionMode = null, effort = null)
                          .terminalSettings.isEmpty(),
                      "and a session it knows nothing about draws no chips")
        checks.equal(shown(store(control = SessionControl.terminal, agent = null)), listOf("claude-sonnet-4-5 high", "auto"),
                     "an agent this build never heard of shows every id verbatim")

        // Amendment A21: a terminal-held Codex thread reports its tier like anything else, and the one
        // chip carries it.
        val fast = store(control = SessionControl.terminal, agent = DemoFixtures.codex, model = "gpt-5.4-codex",
                         permissionMode = "on-request", effort = "high", speed = "priority")
        checks.equal(fast.terminalSettings.firstOrNull()?.speed, "Fast", "a tier the terminal turned on rides on the one chip")
        checks.equal(fast.terminalSettings.firstOrNull()?.spokenValue, "GPT-5.4 Codex, effort High, Fast",
                     "and is spelled out for assistive technology, which cannot see the gauge (A44)")
        checks.equal(store(control = SessionControl.terminal, agent = DemoFixtures.codex).terminalSettings.firstOrNull()?.speed,
                     null, "the standard speed adds nothing to it")

        // The terminal switches model mid-session. The device publishes it as `meta`; the chip follows
        // without a reload and without a `session.set`.
        val live = store(control = SessionControl.terminal)
        val meta = SessionEvent(seq = 9, ts = 1, kind = SessionEvent.metaKind,
                                body = SessionEventBody.Meta(MetaPayload(model = "claude-opus-4-1")))
        live.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = meta))
        checks.equal(live.session.model, "claude-opus-4-1", "a meta with a model reaches the session")
        checks.equal(shown(live), listOf("Opus 4.1 High", "auto"), "and the chip says what the terminal chose")

        // Amendment A40: the card the app may open reads the same session the same way, so a model
        // somebody typed in the terminal reaches it too.
        val shared = store(control = SessionControl.shared)
        shared.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = meta))
        checks.equal(TerminalSetting.modelCardText(shared.session, agent = shared.agent), "Opus 4.1 High",
                     "and the live card words it identically")
    }

    /**
     * Amendments A43 and A44: the composer's row is Up next, the dictation language, the model card
     * and the permission mode, in that order; each stands only where it has something to show, and on
     * the phone each is an icon whose accessible value says what the icon draws.
     */
    @Test
    fun controlRow() = check { checks ->
        fun store(control: SessionControl = SessionControl.remote, agent: AgentInfo? = DemoFixtures.claude,
                  queued: Int = 0, effort: String? = "high"): ChatStore {
            val session = Session(sessionID = "s", deviceID = "d", agent = agent?.agent ?: "claude", title = "T",
                                  cwd = "/tmp", state = SessionState.idle, control = control,
                                  model = agent?.defaultModel ?: "claude-sonnet-4-5", permissionMode = "acceptEdits",
                                  effort = effort, queued = queued)
            val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
            chat.agent = agent
            return chat
        }

        checks.equal(store().controlRow(backend = VoiceBackend.onDevice),
                     listOf(ComposerControl.dictationLanguage, ComposerControl.modelCard, ComposerControl.permissions),
                     "the phone listening adds the language before what runs and what it may do")
        checks.equal(store().controlRow(backend = VoiceBackend.gateway),
                     listOf(ComposerControl.modelCard, ComposerControl.permissions),
                     "and the gateway, which detects the language, adds nothing")
        checks.equal(store(queued = 3).controlRow(backend = VoiceBackend.onDevice),
                     listOf(ComposerControl.upNext, ComposerControl.dictationLanguage, ComposerControl.modelCard,
                            ComposerControl.permissions),
                     "what waits behind the turn comes first, while anything does")
        checks.equal(store(queued = 2).controlRow(backend = VoiceBackend.gateway),
                     listOf(ComposerControl.upNext, ComposerControl.modelCard, ComposerControl.permissions), "on either backend")
        checks.equal(store(control = SessionControl.terminal).controlRow(backend = VoiceBackend.onDevice),
                     listOf(ComposerControl.dictationLanguage, ComposerControl.modelCard, ComposerControl.permissions),
                     "a terminal-held session keeps every slot, as values (A17)")
        val modeless = AgentInfo(agent = "pi", available = true,
                                 efforts = listOf(AgentOption(id = "low", label = "Low"), AgentOption(id = "high", label = "High")),
                                 capabilities = listOf(AgentCapability.effort))
        checks.equal(store(agent = modeless).controlRow(backend = VoiceBackend.gateway), listOf(ComposerControl.modelCard),
                     "and an agent with no permission modes draws no shield (A25)")

        checks.equal(ComposerControl.upNextValue(1), "1 message", "Up next says its count in words")
        checks.equal(ComposerControl.upNextValue(3), "3 messages", "in the plural past one")
        checks.equal(TerminalSetting.modelCardSpoken(store().session, agent = DemoFixtures.claude), "Sonnet 4.5, effort High",
                     "the gauge's value names the model and the effort")
        checks.equal(TerminalSetting.modelCardSpoken(store(agent = null, effort = null).session, agent = null), "claude-sonnet-4-5",
                     "and only the model where no effort is known")

        // The needle: the lowest level at the left end, the highest at the right, the others evenly
        // between, and upright where there is no scale.
        checks.equal(DemoFixtures.claude.effortPosition("medium"), 0.0, "Claude's lower level is the left end")
        checks.equal(DemoFixtures.claude.effortPosition("high"), 1.0, "and its higher one the right")
        checks.equal(DemoFixtures.codex.effortPosition("medium"), 0.5, "Codex's middle of three is upright")
        checks.equal(DemoFixtures.grok.effortPosition("high"), 2.0 / 3, "Grok's third of four is two thirds")
        checks.equal(DemoFixtures.claude.effortPosition("max"), null, "a level the agent does not list has none")
        checks.equal(DemoFixtures.claude.effortPosition(null), null, "and neither has no level at all")
        checks.equal(AgentInfo(agent = "x", available = true, efforts = listOf(AgentOption(id = "only", label = "Only")))
                         .effortPosition("only"),
                     null, "one level is no scale")
    }

    /**
     * Amendment A21: a speed tier beside the model and the effort. One control cycles standard through
     * every tier the agent lists and back, `session.set` carries a null to put it back, and a `meta`
     * moves it either way.
     */
    @Test
    fun speedTier() = check { checks ->
        fun store(agent: AgentInfo?, speed: String? = null): Pair<ChatStore, ScriptedChannel> {
            val session = Session(sessionID = "s", deviceID = "d", agent = agent?.agent ?: "codex", title = "T", cwd = "/tmp",
                                  state = SessionState.idle, control = SessionControl.remote, model = agent?.defaultModel,
                                  effort = agent?.defaultEffort, speed = speed)
            val channel = ScriptedChannel()
            val chat = ChatStore(session = session, channel = channel, tasks = backgroundScope)
            chat.agent = agent
            return chat to channel
        }

        val (claude, _) = store(agent = DemoFixtures.claude)
        checks.equal(claude.nextSpeed, null, "an agent with no tier offers no speed control at all")

        val (standard, channel) = store(agent = DemoFixtures.codex)
        checks.equal(standard.nextSpeed, SpeedChange.Tier("priority"), "the first tap on a standard session raises the first tier")
        standard.set(speed = SpeedChange.Tier("priority"))
        checks.equal(channel.requests(ofType = "session.set").firstOrNull()?.json?.get("speed"), JsonPrimitive("priority"),
                     "and sends the tier id the agent named")

        val (fast, back) = store(agent = DemoFixtures.codex, speed = "priority")
        checks.equal(fast.nextSpeed, SpeedChange.Standard, "the tap after the last tier goes back to the standard speed")
        fast.set(speed = SpeedChange.Standard)
        checks.equal(back.requests(ofType = "session.set").firstOrNull()?.json?.get("speed"), JsonNull,
                     "which is a null on the wire, not an absent key")

        // A tier the agent has since stopped offering still cycles forwards.
        val (stale, _) = store(agent = DemoFixtures.codex, speed = "turbo")
        checks.equal(stale.nextSpeed, SpeedChange.Tier("priority"), "an id the agent no longer lists starts the cycle again")

        // The terminal types `/fast`, then types it again. Both reach the app through `meta`, and the
        // second one has to clear what the first set.
        val (live, _) = store(agent = DemoFixtures.codex)
        fun meta(seq: Int, speed: SpeedChange): AppFrame = AppFrame.SessionEvent(
            sessionID = "s", deviceID = "d",
            event = SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.metaKind,
                                 body = SessionEventBody.Meta(MetaPayload(speed = speed))))
        live.receive(meta(1, SpeedChange.Tier("priority")))
        checks.equal(live.session.speed, "priority", "a meta raises the tier")
        live.receive(meta(2, SpeedChange.Standard))
        checks.equal(live.session.speed, null, "and a meta with a null puts it back")
    }

    /** The two detail levels draw from one transcript, and the jump-to-latest count reads the level. */
    @Test
    fun timelineDetail() = check { checks ->
        val base = 1_788_944_400_000L
        var seq = 0
        fun event(kind: String, blockID: String?, parent: String? = null, body: SessionEventBody): SessionEvent {
            seq += 1
            return SessionEvent(seq = seq, ts = base + seq, kind = kind, blockID = blockID, parentBlockID = parent, body = body)
        }
        val options = listOf(ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary),
                             ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger))

        val timeline = Timeline()
        timeline.apply(event(SessionEvent.turnStartedKind, null,
                             body = SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "t1", trigger = EventSource.remote))))
        timeline.apply(event(SessionEvent.userMessageKind, "u-1",
                             body = SessionEventBody.UserMessage(UserMessagePayload(text = "fix the flake"))))
        timeline.apply(event(SessionEvent.thinkingKind, "think-1",
                             body = SessionEventBody.Thinking(StreamTextPayload(text = "a shared clock", done = true))))
        timeline.apply(event(SessionEvent.assistantTextKind, "a-1",
                             body = SessionEventBody.AssistantText(StreamTextPayload(text = "Reproducing first.", done = true))))
        timeline.apply(event(SessionEvent.toolCallKind, "task-1",
                             body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Task", kind = ToolKind.subagent,
                                                                              title = "Audit", status = ToolStatus.running))))
        timeline.apply(event(SessionEvent.assistantTextKind, "sub-1", parent = "task-1",
                             body = SessionEventBody.AssistantText(StreamTextPayload(text = "found it", done = true))))
        timeline.apply(event(SessionEvent.approvalKind, "ap-1", parent = "task-1",
                             body = SessionEventBody.Approval(ApprovalPayload(requestID = "r-1", tool = "Bash", kind = ToolKind.shell,
                                                                              title = "rm -rf build", options = options))))
        timeline.apply(event(SessionEvent.todosKind, null,
                             body = SessionEventBody.Todos(TodosPayload(items = listOf(
                                 TodoItem(id = "1", text = "reproduce", status = TodoStatus.completed))))))
        timeline.apply(event(SessionEvent.noticeKind, null,
                             body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.warn, text = "the model was switched"))))
        timeline.apply(event(SessionEvent.errorKind, null,
                             body = SessionEventBody.Error(ErrorPayload(message = "the device went away"))))
        timeline.apply(event(SessionEvent.turnCompletedKind, null,
                             body = SessionEventBody.TurnCompleted(TurnCompletedPayload(turnID = "t1", stopReason = StopReason.completed,
                                                                                        durationMS = 4_000))))
        timeline.apply(event(SessionEvent.turnCompletedKind, null,
                             body = SessionEventBody.TurnCompleted(TurnCompletedPayload(turnID = "t2", stopReason = StopReason.interrupted,
                                                                                        durationMS = 900))))

        val detailed = timeline.roots(at = TimelineDetail.detailed).map { it.id }
        checks.equal(detailed, listOf("seq:1", "u-1", "think-1", "a-1", "task-1", "seq:9", "seq:10", "seq:11", "seq:12"),
                     "Detailed is the timeline as it was, with the sub-agent's rows under their tool call")
        checks.equal(timeline.children(of = "task-1", at = TimelineDetail.detailed).map { it.id }, listOf("sub-1", "ap-1"),
                     "and the tool call keeps its children")

        val simple = timeline.roots(at = TimelineDetail.simple).map { it.id }
        checks.equal(simple, listOf("u-1", "a-1", "ap-1", "seq:9", "seq:10", "seq:12"),
                     "Simple keeps the message, the prose, the approval, the notice, the error and the interrupted turn")
        checks.expect("think-1" !in simple, "thinking is not drawn at Simple")
        checks.expect("task-1" !in simple, "nor is a tool call")
        checks.expect("sub-1" !in simple, "nor what a sub-agent wrote under it")
        checks.expect("ap-1" in simple, "but an approval comes up to the top level rather than going with the tool row")
        checks.expect("seq:11" !in simple, "a turn that simply finished says nothing more at Simple")
        checks.equal(timeline.children(of = "task-1", at = TimelineDetail.simple).size, 0,
                     "nothing hangs under a tool call that is not drawn")
        checks.equal(timeline.entries.size, 11,
                     "and the store still holds every block, so switching back shows what was there")
        checks.equal(timeline.todos.size, 1, "including the todo snapshot the header chip is hidden from")

        // The jump-to-latest count is the rows the level draws. A burst of tool calls is nothing at
        // all to a reader who has chosen not to see them.
        val session = Session(sessionID = "detail", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running)
        val preference = SettingsStore(defaults = MemoryUserDefaults())
        val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
        chat.detailSource = { preference.timelineDetail }
        fun burst(first: Int) {
            for (offset in 0 until 3) {
                val number = first + offset
                chat.receive(AppFrame.SessionEvent(sessionID = "detail", deviceID = "d", event = SessionEvent(
                    seq = number, ts = base + number, kind = SessionEvent.toolCallKind, blockID = "tool-$number",
                    body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Read", kind = ToolKind.read, title = "one file",
                                                                     status = ToolStatus.succeeded)))))
            }
        }
        chat.isFollowingTail = false
        burst(first = 1)
        checks.equal(chat.updatesWhileAway, 0, "a burst of tool calls counts as nothing at Simple")
        preference.timelineDetail = TimelineDetail.detailed
        burst(first = 10)
        checks.equal(chat.updatesWhileAway, 3, "and as one per block at Detailed")
        checks.equal(chat.detail, TimelineDetail.detailed, "the transcript reads the level rather than holding a copy")
        checks.expect(!chat.showsTodos, "the todo chip needs a count as well as the level")
    }

    /**
     * The status dot, over the whole table in `docs/DESIGN.md`. The tone is a function of all three
     * facts, which is the point: `state` alone cannot tell a finished turn on a live session from a
     * session nothing owns.
     */
    @Test
    fun dotTones() = check { checks ->
        val owners = listOf(SessionControl.remote, SessionControl.terminal, SessionControl.shared)

        for (control in owners + SessionControl.none) {
            for (state in listOf(SessionState.starting, SessionState.running)) {
                checks.equal(DotTone.of(state = state, control = control, online = true), DotTone.working,
                             "$state under $control is a turn under way")
            }
            for (state in listOf(SessionState.needsApproval, SessionState.needsInput)) {
                checks.equal(DotTone.of(state = state, control = control, online = true), DotTone.waiting,
                             "$state under $control is blocked on the user")
            }
            checks.equal(DotTone.of(state = SessionState.error, control = control, online = true), DotTone.failed,
                         "an error under $control is red")
            checks.equal(DotTone.of(state = SessionState.stopped, control = control, online = true), DotTone.off,
                         "a stopped session under $control is grey")
        }

        for (control in owners) {
            for (state in listOf(SessionState.idle, SessionState.readonly)) {
                checks.equal(DotTone.of(state = state, control = control, online = true), DotTone.live,
                             "$state is alive and quiet while $control still holds it")
            }
        }
        for (state in listOf(SessionState.idle, SessionState.readonly)) {
            checks.equal(DotTone.of(state = state, control = SessionControl.none, online = true), DotTone.off,
                         "$state with nothing holding it is an exited session")
        }

        for (state in listOf(SessionState.starting, SessionState.running, SessionState.needsApproval, SessionState.needsInput,
                             SessionState.idle, SessionState.readonly, SessionState.stopped, SessionState.error)) {
            checks.equal(DotTone.of(state = state, control = SessionControl.remote, online = false), DotTone.off,
                         "a machine that is gone reports nothing, whatever $state said")
        }
        checks.equal(DotTone.of(state = SessionState("compacting"), control = SessionControl.remote, online = true),
                     DotTone.off, "a state this build has never heard of claims nothing")

        // The five tones the demo carries, so every colour is on screen at once. What each one looks
        // like is the app's own colour table, which its own checks read; this suite owns the rule that
        // picks the tone.
        val devices = DemoFixtures.devices.associate { it.deviceID to it.online }
        val tones = DemoFixtures.sessions.map { it.dotTone(online = devices[it.deviceID] ?: false) }
        checks.equal(tones.toSet(), DotTone.allCases.toSet(), "the demo list shows all five tones")
        checks.equal(tones.count { it == DotTone.failed }, 1, "exactly one of them failed")
    }

    /** Where the reader is, what they missed while away, and what brings them back. */
    @Test
    fun readingPosition() = check { checks ->
        checks.expect(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_400.0),
                      "the foot of the content is the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_365.0),
                      "a row that settles a few points short is still the bottom")
        checks.expect(!ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_200.0),
                      "a screenful up is not the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight = 200.0, containerHeight = 600.0, offset = 0.0),
                      "a transcript shorter than its container has no bottom to leave")
        checks.equal(ScrollTail.badge(updates = 0), null, "nothing missed carries no count")
        checks.equal(ScrollTail.badge(updates = 3), "3", "three blocks read as three")
        checks.equal(ScrollTail.badge(updates = 140), "99+", "the count stops at 99")

        val session = Session(sessionID = "reading", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running)
        val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
        fun arriving(seq: Int, blockID: String, delta: Boolean = false): AppFrame {
            val payload = if (delta) StreamTextPayload(delta = "more", done = false) else StreamTextPayload(text = "a line", done = true)
            return AppFrame.SessionEvent(sessionID = "reading", deviceID = "d", event = SessionEvent(
                seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.assistantTextKind, blockID = blockID,
                body = SessionEventBody.AssistantText(payload)))
        }
        chat.receive(arriving(1, blockID = "a-1"))
        checks.equal(chat.updatesWhileAway, 0, "nothing is counted while the reader is at the bottom")
        chat.isFollowingTail = false
        chat.receive(arriving(2, blockID = "a-2"))
        chat.receive(arriving(3, blockID = "a-3"))
        chat.receive(arriving(4, blockID = "a-3", delta = true))
        checks.equal(chat.updatesWhileAway, 2, "blocks are counted while the reader is away, streaming deltas are not")
        chat.isFollowingTail = true
        checks.equal(chat.updatesWhileAway, 0, "returning to the bottom clears the count")

        chat.isFollowingTail = false
        chat.draft = "back to the bottom"
        chat.send()
        checks.expect(chat.isFollowingTail, "sending returns the transcript to the tail")
    }

    /**
     * Review finding 1: a reconnect must re-issue `session.subscribe`, or the transcript silently
     * stops receiving events while the list keeps updating.
     */
    @Test
    fun reconnectResubscribe() = check { checks ->
        val channel = ScriptedChannel()
        val session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session = session, events = emptyList(), resync = false)
        val chat = ChatStore(session = session, channel = channel, tasks = backgroundScope)
        chat.open()
        checks.equal(channel.requests(ofType = "session.subscribe").size, 1, "opening subscribes once")

        // A live event moves the cursor, so the resubscribe must carry it.
        chat.receive(AppFrame.SessionEvent(sessionID = session.sessionID, deviceID = session.deviceID,
                                           event = SessionEvent(seq = 12, ts = 1, kind = SessionEvent.noticeKind,
                                                                body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info,
                                                                                                             text = "x")))))
        checks.equal(chat.timeline.lastSeq, 12, "a live event advances the cursor")

        chat.receive(AppFrame.Hello(HelloFrame(protocolVersion = RemoteProtocol.version, gatewayVersion = "test",
                                               user = UserIdentity(username = "admin"), devices = emptyList(),
                                               sessions = emptyList(), stt = STTConfig.disabled, serverTime = 0)))
        settle { channel.requests(ofType = "session.subscribe").size == 2 }
        val resubscribes = channel.requests(ofType = "session.subscribe")
        checks.equal(resubscribes.size, 2, "a hello resubscribes the open conversation")
        checks.equal(resubscribes.lastOrNull()?.body?.get("since_seq")?.intValue, 12,
                     "the resubscribe carries the cursor, so it takes the replay path")
    }

    /** Review finding 4: a skipped seq means an event was dropped. The transcript must refill instead of rendering a hole. */
    @Test
    fun gapRepair() = check { checks ->
        val channel = ScriptedChannel()
        val session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session = session, events = emptyList(), resync = false)
        val chat = ChatStore(session = session, channel = channel, tasks = backgroundScope)
        chat.open()
        val opening = channel.requests(ofType = "session.subscribe").size

        fun notice(seq: Int): AppFrame = AppFrame.SessionEvent(
            sessionID = session.sessionID, deviceID = session.deviceID,
            event = SessionEvent(seq = seq, ts = 1, kind = SessionEvent.noticeKind,
                                 body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info, text = "x"))))
        chat.receive(notice(41))
        checks.expect(!chat.timeline.hasGap, "consecutive events leave no gap")
        chat.receive(notice(42))
        checks.expect(!chat.timeline.hasGap, "still no gap")
        chat.receive(notice(44))
        checks.expect(chat.timeline.hasGap, "a skipped seq is detected")
        settle { channel.requests(ofType = "session.subscribe").size > opening }
        checks.expect(channel.requests(ofType = "session.subscribe").size > opening, "a detected gap refills from the gateway")
        settle { !chat.timeline.hasGap }
        checks.expect(!chat.timeline.hasGap, "a completed refill clears the gap")
    }

    /** Review finding 7: a warm open must not blank the transcript. */
    @Test
    fun warmOpen() = check { checks ->
        val channel = ScriptedChannel()
        val session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session = session, events = emptyList(), resync = false)
        val chat = ChatStore(session = session, channel = channel, tasks = backgroundScope)
        val cached = DemoFixtures.liveHistory()
        chat.open(cached = cached)
        checks.expect(chat.timeline.entries.isNotEmpty(), "the cached transcript survives the open")
        checks.equal(chat.timeline.lastSeq, cached.maxOfOrNull { it.seq }, "a warm open adopts the cached cursor")
        checks.equal(channel.requests(ofType = "session.subscribe").firstOrNull()?.body?.get("since_seq")?.intValue,
                     cached.maxOfOrNull { it.seq }, "a warm open subscribes from the cached cursor")
        checks.equal(channel.requests(ofType = "session.history").size, 0, "a warm open does not page history")

        // A gateway that reports resync does rebuild.
        val cold = ScriptedChannel()
        cold.subscribeReply = SubscribeResult(session = session, events = emptyList(), resync = true)
        val rebuilt = ChatStore(session = session, channel = cold, tasks = backgroundScope)
        rebuilt.open(cached = cached)
        checks.equal(cold.requests(ofType = "session.history").size, 1, "a resync rebuilds from history")
    }

    /** Review findings 8 and 13: a send that cannot succeed says why, and a retry carries the same bytes. */
    @Test
    fun sendability() = check { checks ->
        val channel = ScriptedChannel()
        val session = DemoFixtures.sessions[0]
        channel.subscribeReply = SubscribeResult(session = session, events = emptyList(), resync = false)
        val chat = ChatStore(session = session, channel = channel, tasks = backgroundScope)
        chat.draft = "hello"
        checks.expect(chat.canSend, "a connected session with a draft can send")

        chat.canReachGateway = false
        checks.expect(!chat.canSend, "an offline app cannot send")
        checks.equal(chat.sendBlockReason, "Offline · your draft is saved", "and it says so")
        chat.canReachGateway = true

        chat.deviceOnline = false
        checks.equal(chat.sendBlockReason, "That device is offline", "an offline device says so")
        chat.deviceOnline = true

        // A socket on its way back is not a reason to refuse: the transport holds the request until the
        // hello lands.
        checks.expect(ConnectionPhase.Reconnecting.canReachGateway, "a reconnecting socket still reaches the gateway")
        checks.expect(ConnectionPhase.Connecting.canReachGateway, "and so does one still connecting")
        checks.expect(ConnectionPhase.Syncing.canReachGateway, "and one still catching up")
        checks.expect(!ConnectionPhase.SignedOut.canReachGateway, "a signed-out app does not")
        checks.expect(!ConnectionPhase.Expired.canReachGateway, "and neither does an expired session")
        checks.expect(!ConnectionPhase.Superseded.canReachGateway, "nor a connection another device replaced")

        // The bytes ride along on a pending send, so a retry is the same message.
        val attachment = OutboundAttachment(name = "shot.png", mime = "image/png", data = byteArrayOf(1, 2, 3))
        val pending = PendingSend(id = "fixed", text = "look at this", attachments = listOf(attachment), mode = SendMode.auto,
                                  status = PendingSend.Status.Uncertain)
        checks.expect(pending.attachments.firstOrNull()?.data?.contentEquals(byteArrayOf(1, 2, 3)) == true,
                      "an unconfirmed send keeps its attachment bytes")
        checks.equal(pending.attachmentInfo.firstOrNull()?.size, 3, "and can still describe them")
        chat.retry(pending)
        val sends = channel.requests(ofType = "session.send")
        checks.equal(sends.firstOrNull()?.id, "fixed", "a retry reuses the original request id")
        checks.equal(sends.firstOrNull()?.json?.get("attachments")?.arrayValue?.size, 1,
                     "a retry carries the attachments the first attempt had")
    }

    /** Amendment A4 as the connection store sees it. */
    @Test
    fun closeHandling() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            // 4401 and 4403 end the session and return to the login screen.
            for (reason in listOf(SocketCloseReason.unauthorized, SocketCloseReason.forbidden)) {
                val channel = ScriptedChannel()
                val store = connectionStore(directory)
                store.enterDemo(api = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay), channel = channel)
                settle { store.hasSnapshot }
                channel.close(reason)
                settle { store.phase == ConnectionPhase.SignedOut }
                checks.equal(store.phase, ConnectionPhase.SignedOut, "$reason returns to the login screen")
                checks.expect(store.errorMessage?.isEmpty() == false, "$reason explains itself")
            }

            // 4001 keeps the user signed in and waits for a deliberate reconnect.
            val channel = ScriptedChannel()
            val store = connectionStore(directory)
            store.enterDemo(api = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay), channel = channel)
            settle { store.hasSnapshot }
            channel.close(SocketCloseReason.replaced)
            settle { store.phase == ConnectionPhase.Superseded }
            checks.equal(store.phase, ConnectionPhase.Superseded, "a replaced connection is its own state")
            checks.expect(store.isSignedIn, "a replaced connection does not sign the user out")
            checks.expect(store.devices.isNotEmpty(), "a replaced connection keeps the last known lists")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun connection() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
            val store = connectionStore(directory)
            store.enterDemo(api = gateway, channel = gateway)
            settle { store.hasSnapshot }
            checks.expect(store.hasSnapshot, "the demo hello arrives")
            checks.equal(store.phase, ConnectionPhase.Connected, "the store reports a connected phase")
            checks.equal(store.devices.size, 3, "hello populates the device list")
            checks.equal(store.sessions.size, 13, "hello populates the session list")
            checks.equal(store.inventorySummary, "3 devices · 1 waiting", "the inventory summary counts waiting sessions")
            checks.equal(store.onlineDevices.size, 2, "only the online devices are offered for a new session")
            checks.expect(store.device(DemoFixtures.macDeviceID)?.agent("claude")?.supports(AgentCapability.takeover) == true,
                          "capabilities survive the hello round trip")
            store.signOut()
            checks.equal(store.phase, ConnectionPhase.SignedOut, "signing out clears the phase")
            checks.equal(store.devices.size, 0, "signing out clears the device list")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun chat() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            chat(checks, directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun TestScope.chat(checks: CheckRunner, directory: File) {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val connection = connectionStore(directory)
        connection.enterDemo(api = gateway, channel = gateway)
        settle { connection.hasSnapshot }
        val live = connection.session(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.liveSessionID)
        if (live == null) {
            checks.expect(false, "the demo live session exists")
            return
        }
        val chat = ChatStore(session = live, channel = gateway, tasks = backgroundScope)
        connection.addFrameHandler("chat") { frame -> chat.receive(frame) }
        chat.open()
        settle { chat.timeline.entries.size > 5 }
        checks.expect(chat.timeline.entries.size >= 10, "history paints the transcript")
        checks.expect(chat.timeline.todos.size == 4, "the todos snapshot arrives with history")
        checks.equal(chat.session.todos?.total, 4, "a snapshot from history is mirrored into the session summary")
        checks.expect(chat.statusLine?.contains("queued") == true, "a running session offers to queue")

        // A message sent during a turn queues rather than interrupting.
        chat.draft = "also add a retry to the token refresh path"
        chat.send()
        settle { chat.lastAcceptance != null }
        checks.equal(chat.lastAcceptance, SendAcceptance.queued, "sending during a turn queues the message")
        checks.equal(chat.pendingSends.size, 0, "an accepted message leaves the pending list")
        checks.expect(chat.unconfirmedSend == null, "an accepted message is not shown as unconfirmed")
        checks.equal(chat.draft, "", "sending clears the draft")
        // The demo holds the message for a couple of seconds before injecting it, so these waits have
        // to outlast that on a loaded machine.
        settle(timeout = 10.seconds) { chat.timeline.queue.size == 1 }
        checks.equal(chat.timeline.queue.size, 1, "the queue snapshot carries the queued message")

        // Streaming from the scripted turn lands in one block.
        settle(timeout = 6.seconds) { chat.timeline.entry(id = "a-live")?.text?.contains("passed") == true }
        checks.expect(chat.timeline.entry(id = "a-live")?.text?.startsWith("All 100") == true,
                      "streamed deltas accumulate into one block")

        // A terminal-controlled session refuses a send instead of pretending.
        val readonly = connection.sessions.firstOrNull { it.control == SessionControl.terminal }
        if (readonly == null) {
            checks.expect(false, "the demo has a terminal-controlled session")
            return
        }
        val locked = ChatStore(session = readonly, channel = gateway, tasks = backgroundScope)
        locked.agent = connection.device(readonly.deviceID)?.agent(readonly.agent)
        checks.expect(locked.isReadOnly, "a terminal-controlled session is read-only")
        checks.expect(locked.statusLine?.contains("take over") == true, "the read-only status offers a takeover")
        locked.draft = "hello"
        checks.expect(!locked.canSend, "the composer is disabled while the terminal owns the session")

        sharedSession(connection = connection, gateway = gateway, checks = checks)
        codexSharedSession(connection = connection, gateway = gateway, checks = checks)
        grokSharedSession(connection = connection, gateway = gateway, checks = checks)

        // Approvals send only the option ids the device supplied.
        val waiting = connection.sessions.firstOrNull { it.state == SessionState.needsApproval }
        if (waiting == null) {
            checks.expect(false, "the demo has a session waiting for approval")
            return
        }
        val approving = ChatStore(session = waiting, channel = gateway, tasks = backgroundScope)
        connection.addFrameHandler("approving") { frame -> approving.receive(frame) }
        approving.open()
        settle { approving.timeline.pendingRequest != null }
        val approval = approving.timeline.pendingRequest?.approval
        if (approval == null) {
            checks.expect(false, "the pending approval is visible")
            return
        }
        checks.equal(approval.options.size, 3, "every server option is offered")
        checks.equal(approval.primaryOption?.id, "approved", "the primary option is the device's own id")
        approving.approve(requestID = approval.requestID, optionID = approval.primaryOption?.id ?: "")
        settle { approving.timeline.pendingRequest == null }
        checks.expect(approving.timeline.pendingRequest == null, "an answered approval stops being actionable")

        connection.removeFrameHandler("chat")
        connection.removeFrameHandler("approving")
    }

    /**
     * Amendment A10: an attached session types, queues and approves like a remote one, and the device
     * reports what became of each message.
     */
    private suspend fun TestScope.sharedSession(connection: ConnectionStore, gateway: DemoGateway, checks: CheckRunner) {
        val session = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID }
        if (session == null) {
            checks.expect(false, "the demo has an attached Claude session")
            return
        }
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = connection.device(session.deviceID)?.agent(session.agent)
        connection.addFrameHandler("shared") { frame -> chat.receive(frame) }
        try {
            chat.open()
            settle { chat.timeline.entries.size >= 3 }
            checks.expect(!chat.isReadOnly, "an attached session is not read-only")
            checks.expect(chat.isAttached, "and reports itself as attached")
            checks.expect(!chat.canTakeover, "takeover is never offered on an attached session")
            // Amendment A40: the device types the model and the effort into the terminal; the
            // permission mode has no command it could type.
            checks.expect(chat.allowsModelCardChanges, "the model card is a control again")
            checks.expect(!chat.allowsSettingsChanges(SharedSetting.permissionMode), "and the permission mode belongs to the terminal")
            checks.expect(!chat.allowsAttachments, "and attachments cannot reach a live CLI")

            // Amendment A20: an earlier question the person at the terminal answered in their own
            // dialog is in the transcript, saying so.
            val past = chat.timeline.entries.mapNotNull { it.question }.firstOrNull { it.by != null }
            if (past != null) {
                checks.equal(past.by, EventSource.terminal, "a question answered in the terminal says where")
                checks.expect(!past.status.isActionable, "and is no longer answerable from here")
            } else {
                checks.expect(false, "the attached session carries a question the terminal answered")
            }

            // Amendment A20: the attached CLI asks, the terminal shows its own dialog for the same
            // question, and this app may answer it.
            settle(timeout = 10.seconds) { chat.pendingQuestion != null }
            val question = chat.pendingQuestion
            if (question == null) {
                checks.expect(false, "the attached session raises a question this app can answer")
                return
            }
            checks.expect(chat.allowsAnswers, "an attached session takes an answer")
            checks.equal(chat.statusLine, "Waiting for your answer", "and the composer says what it is waiting for")
            chat.draft = "Remote control for your terminal agents"
            chat.answerDraft()
            settle(timeout = 10.seconds) { chat.pendingQuestion == null }
            checks.equal(chat.draft, "", "the draft went with the answer")
            checks.expect(chat.timeline.roots.all { it.pending == null },
                          "and nothing optimistic was drawn for it: an answer is not a message")
            val resolved = chat.timeline.entry(id = "q-shared")?.question
            if (resolved != null) {
                checks.equal(resolved.by, EventSource.remote, "the resolved question says the answer came from here")
                checks.equal(resolved.answers?.get("q1"), QuestionAnswer.Text("Remote control for your terminal agents"),
                             "carrying the draft as the free-text answer")
            } else {
                checks.expect(false, "the question block resolves in place")
            }
            checks.equal(question.requestID, "demo-question-shared", "under the id it was raised with")

            settle(timeout = 10.seconds) { chat.statusLine == null }
            checks.equal(chat.statusLine, null, "an idle attached session prints nothing the header has not already said")

            chat.draft = "also mention the iOS app in the notes"
            chat.send()
            // Amendment A19: while the device holds the message it is a queue entry and nothing else —
            // no bubble, optimistic or otherwise.
            settle(timeout = 10.seconds) { chat.timeline.queue.size == 1 }
            checks.equal(chat.timeline.queue.size, 1, "a held message is listed in the queue")
            checks.expect(chat.timeline.roots.all { it.pending == null },
                          "and the optimistic row moved into it rather than staying in the transcript")
            checks.expect(!chat.timeline.entries.any { it.userMessage?.source == EventSource.remote },
                          "the device published no block for a message it has not injected")
            checks.equal(chat.lastAcceptance, SendAcceptance.queued, "a held send is accepted with the ordinary queued acceptance")

            settle(timeout = 10.seconds) { chat.timeline.entries.any { it.userMessage?.delivery == MessageDelivery.delivered } }
            val landed = chat.timeline.entries.lastOrNull { it.userMessage?.delivery != null }
            if (landed == null) {
                checks.expect(false, "the injected message reaches the transcript")
                return
            }
            checks.equal(landed.userMessage?.delivery, MessageDelivery.delivered,
                         "the block appears when the CLI takes it, saying it went in")
            checks.expect(chat.timeline.queue.isEmpty(), "and leaves the queue")

            settle(timeout = 10.seconds) { chat.timeline.pendingRequest != null }
            val approval = chat.timeline.pendingRequest?.approval
            if (approval == null) {
                checks.expect(false, "the relayed approval arrives")
                return
            }
            checks.equal(approval.options.map { it.id }, listOf("allow", "deny"), "a relayed request offers exactly allow and deny")
            // Amendment A42: Stop is offered, and over a prompt the device — and the demo — refuse it in
            // their own words rather than escaping the dialog.
            checks.expect(chat.canStop, "Stop is offered on the attached Claude session")
            chat.stop()
            checks.equal(chat.errorMessage, "answer the prompt first", "and is refused in the device's words while the request is open")
            chat.clearError()
            chat.approve(requestID = approval.requestID, optionID = "allow")
            settle(timeout = 10.seconds) { chat.timeline.pendingRequest == null }
            checks.expect(chat.timeline.pendingRequest == null, "answering here resolves the relayed request")
        } finally {
            connection.removeFrameHandler("shared")
        }
    }

    /**
     * Amendment A11: the same session shape on an attachment that carries the settings, the
     * attachments and an interrupt. Nothing is dimmed, Stop is offered, and `session.set` reaches the
     * live thread.
     */
    private suspend fun TestScope.codexSharedSession(connection: ConnectionStore, gateway: DemoGateway, checks: CheckRunner) {
        val session = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.codexSharedSessionID }
        if (session == null) {
            checks.expect(false, "the demo has a shared Codex thread")
            return
        }
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = connection.device(session.deviceID)?.agent(session.agent)
        connection.addFrameHandler("codex-shared") { frame -> chat.receive(frame) }
        try {
            chat.open()
            settle { chat.timeline.entries.size >= 3 }

            checks.expect(chat.isAttached, "the daemon shares the thread with the terminal")
            checks.expect(chat.allowsModelCardChanges && chat.allowsSettingsChanges(SharedSetting.permissionMode),
                          "the pickers open because shared_settings names no subset")
            checks.expect(chat.allowsAttachments, "and the attachment button because shared_attachments is")
            checks.expect(chat.canStop, "a running shared thread offers Stop")
            checks.expect(!chat.canTakeover, "and still never a takeover")

            val approval = chat.timeline.pendingRequest?.approval
            if (approval == null) {
                checks.expect(false, "the daemon's request is in the transcript")
                return
            }
            checks.equal(approval.options.size, 4, "all four decisions are offered")
            checks.equal(approval.otherOptions.size, 2, "two of them stack between primary and danger")

            // Section 5's send modes: `auto` joins the running turn, `queue` waits.
            chat.draft = "also check the drawer's tests"
            chat.send(mode = SendMode.auto)
            checks.equal(chat.lastAcceptance, SendAcceptance.steered, "auto steers a running shared thread")

            // Amendment A14: the agent reads a steered message at its next step, so the row waits at
            // the foot of the transcript while the turn carries on, and the device's block lands after
            // the output that preceded it.
            checks.equal(chat.timeline.roots.lastOrNull()?.pending?.text, "also check the drawer's tests",
                         "a steered message waits at the foot rather than vanishing on acceptance")
            checks.expect(chat.timeline.roots.lastOrNull()?.pending?.isSteering == true,
                          "and never counts down towards an unconfirmed delivery")
            // The row is the last of these; whatever the turn says before the agent takes the message
            // pushes the block past where the row stands now.
            val rowsWhileWaiting = chat.timeline.roots.size
            settle(timeout = 4.seconds) { chat.timeline.optimistic.isEmpty() }
            checks.expect(chat.timeline.optimistic.isEmpty(), "the block arrives when the agent takes it")
            val landed = chat.timeline.roots.indexOfFirst { it.userMessage?.text == "also check the drawer's tests" }
            checks.equal(chat.timeline.roots.count { it.userMessage?.text == "also check the drawer's tests" }, 1,
                         "exactly one copy of the steered message")
            checks.expect(landed >= 0 && landed >= rowsWhileWaiting,
                          "and it sorts after the output the turn produced while it waited")

            chat.draft = "and then run the linter"
            chat.send(mode = SendMode.queue)
            checks.equal(chat.lastAcceptance, SendAcceptance.queued, "queue holds the message instead")

            // An option the block never offered is refused, `elsewhere` included.
            chat.approve(requestID = approval.requestID, optionID = ApprovalPayload.elsewhereOptionID)
            checks.expect(chat.errorMessage != null, "elsewhere is a resolution, never a choice to send")
            chat.clearError()

            chat.set(effort = "high")
            settle { chat.session.effort == "high" }
            checks.equal(chat.session.effort, "high", "session.set retunes the shared thread")

            chat.approve(requestID = approval.requestID, optionID = "allow_session")
            settle(timeout = 10.seconds) { chat.timeline.pendingRequest == null }
            checks.expect(chat.timeline.pendingRequest == null, "and the request is answered from here")

            chat.stop()
            settle(timeout = 10.seconds) { !chat.isRunning }
            checks.expect(!chat.isRunning, "Stop interrupts the turn through the daemon")
        } finally {
            connection.removeFrameHandler("codex-shared")
        }
    }

    /**
     * Amendment A28: a Grok session the terminal started inside the leader. The device is another
     * client of the same process, so the turn the TUI set off is stoppable and its settings are live
     * here — and the attachment button is gone, because a Grok prompt carries no images.
     */
    private suspend fun TestScope.grokSharedSession(connection: ConnectionStore, gateway: DemoGateway, checks: CheckRunner) {
        val session = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.grokSharedSessionID }
        if (session == null) {
            checks.expect(false, "the demo has a Grok session shared through the leader")
            return
        }
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = connection.device(session.deviceID)?.agent(session.agent)
        connection.addFrameHandler("grok-shared") { frame -> chat.receive(frame) }
        try {
            chat.open()
            settle { chat.timeline.entries.size >= 3 }

            checks.equal(chat.agent?.attach, AgentAttach.leader, "the agent attaches through the leader")
            checks.expect(chat.isAttached, "the leader shares the session with the terminal")
            checks.equal(chat.session.origin, EventSource.terminal, "which is where it was started")
            checks.expect(chat.allowsModelCardChanges && chat.allowsSettingsChanges(SharedSetting.permissionMode),
                          "the pickers open because shared_settings names no subset")
            checks.expect(!chat.allowsAttachments, "and the attachment button is gone, because it is not")
            checks.expect(chat.canStop, "session/cancel from here stops the terminal's turn")
            checks.expect(!chat.canTakeover, "and an attached session never offers a takeover")

            chat.set(effort = "low")
            settle { chat.session.effort == "low" }
            checks.equal(chat.session.effort, "low", "set_config_option retunes it for every client")

            // The prompt runs in the conversation the TUI is in, so an interrupt ends that turn and
            // starts the new one rather than holding a message for a shim to type.
            chat.draft = "cap it at thirty seconds instead"
            chat.send(mode = SendMode.interrupt)
            checks.equal(chat.lastAcceptance, SendAcceptance.sent, "a message through the leader is sent, never held")
            checks.expect(chat.timeline.roots.any { it.userMessage?.text?.contains("thirty") == true },
                          "and stands in the transcript the terminal is reading")

            chat.stop()
            settle(timeout = 10.seconds) { !chat.isRunning }
            checks.expect(!chat.isRunning, "Stop interrupts the turn through the leader")
        } finally {
            connection.removeFrameHandler("grok-shared")
        }
    }

    /**
     * Amendment A43: a queued message is taken back into the field, edited and sent back to the place
     * it left, against a demo device holding three messages behind a turn that runs on.
     */
    @Test
    fun queuedEdit() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            val gateway = demoGateway(holdsQueue = true)
            val connection = connectionStore(directory)
            connection.enterDemo(api = gateway, channel = gateway)
            settle { connection.hasSnapshot }
            val live = connection.session(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.liveSessionID)
            if (live == null) {
                checks.expect(false, "the demo live session exists")
                return@check
            }
            checks.equal(live.queued, 3, "the hello counts what the device holds behind the turn")
            val chat = ChatStore(session = live, channel = gateway, tasks = backgroundScope)
            chat.agent = connection.device(live.deviceID)?.agent(live.agent)
            connection.addFrameHandler("queued") { frame -> chat.receive(frame) }
            try {
                chat.open()
                val line = chat.timeline.queue
                checks.equal(line.map { it.attachments }, listOf(null, null, 2), "the line arrives with the subscribe reply, the files counted")
                if (line.size != 3) return@check

                chat.beginEdit(line[2])
                checks.expect(chat.queuedEdit == null, "a message that carries files is not edited")

                chat.draft = "a note of my own"
                chat.beginEdit(line[1])
                settle { chat.timeline.queue.size == 2 }
                checks.equal(chat.timeline.queue.map { it.id }, listOf(line[0].id, line[2].id), "an edited message leaves the line at once")
                checks.equal(chat.draft, line[1].text, "and its words are in the field")
                checks.equal(chat.queuedEdit?.aside, "a note of my own", "with the draft set aside")
                checks.equal(chat.editingSendLabel, "Queue", "and the primary reads Queue behind a running turn")

                val edited = "Add a regression test for the refresh race, and one for logout."
                chat.draft = edited
                chat.send()
                settle { chat.timeline.queue.size == 3 }
                checks.equal(chat.timeline.queue.map { it.text }, listOf(line[0].text, edited, line[2].text),
                             "Send puts the edited words back where they were")
                checks.equal(chat.timeline.queue.map { it.ts }, line.map { it.ts }, "under the ts the entry had")
                checks.equal(chat.draft, "a note of my own", "and the draft set aside comes back")
                checks.expect(chat.queuedEdit == null, "the edit is over")

                // The device got there first: nothing opens, and one line says so.
                val taken = chat.timeline.queue[0]
                val removing = chat.removeQueued(taken.id)
                checks.equal(chat.timeline.queue.size, 2, "a removed row leaves the list before the reply")
                removing.join()
                chat.beginEdit(taken)
                checks.equal(chat.errorMessage, "That message has already been sent.", "a message the device no longer holds cannot be edited")
                checks.expect(chat.queuedEdit == null, "and the field keeps what it had")
                chat.clearError()
                chat.removeQueued(taken.id).join()
                checks.equal(chat.errorMessage, "That message has already been sent.",
                             "and a Remove that finds it gone says the same, not the device's not_found")
                chat.clearError()
            } finally {
                connection.removeFrameHandler("queued")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sessionsList() = check { checks ->
        val defaults = MemoryUserDefaults()
        val store = SessionStore(defaults = defaults)
        val sessions = DemoFixtures.sessions
        val devices = DemoFixtures.devices

        val groups = store.groups(sessions, devices = devices)
        checks.equal(groups.map { it.name }, listOf("mac-studio-office", "macbook-air", "ci-runner-01"),
                     "a machine with live work comes first, then the rest by activity")
        checks.equal(groups.firstOrNull()?.active?.size, 9, "the busy machine holds nine live sessions")
        checks.equal(groups.firstOrNull()?.active?.firstOrNull()?.state, SessionState.needsApproval,
                     "a session waiting on the user sorts first inside its device")
        checks.expect(groups.all { !it.collapsed }, "every group starts expanded")
        checks.equal(groups.firstOrNull()?.archive?.map { it.sessionID }, listOf(DemoFixtures.revivedSessionID),
                     "and the one row it has archived is the session waiting to be resumed")

        // Close is offered on one kind of row, over the whole demo list: a session the device drives
        // that is not already archived (A39).
        val offered = sessions.filter { SessionListLayout.offersClose(it) }
        checks.expect(offered.all { it.control == SessionControl.remote && !it.archived }, "Close is offered only on a row the device is driving")
        checks.expect(offered.isNotEmpty(), "and the demo list has such a row")
        checks.expect(sessions.filter { it.control != SessionControl.remote }.all { !SessionListLayout.offersClose(it) },
                      "a row a terminal holds, or that nothing holds, offers none")
        checks.expect(sessions.filter { it.archived }.all { !SessionListLayout.offersClose(it) },
                      "and a row already in the Archive offers nothing either, not even unarchive")

        // The dialog is the working row's alone: it is the only one with an unfinished turn to throw
        // away.
        val asked = sessions.filter { SessionClose.asksFirst(it, online = true) }
        checks.equal(asked.map { it.state }.all { it == SessionState.running || it == SessionState.starting }, true,
                     "Close asks first only where a turn is under way")
        checks.expect(asked.isNotEmpty(), "and the demo list has one of those too")

        val quiet = groups.lastOrNull()
        if (quiet == null) {
            checks.expect(false, "the third machine is listed")
            return@check
        }
        checks.equal(quiet.active.size, 0, "the machine whose CLI exited holds nothing live")
        checks.equal(quiet.archive.firstOrNull()?.sessionID, DemoFixtures.doneSessionID,
                     "the session nothing owns sits in that machine's own Archive")
        checks.expect(!quiet.archiveExpanded, "which starts collapsed")

        store.toggleArchive(quiet.id)
        checks.expect(store.groups(sessions, devices = devices).lastOrNull()?.archiveExpanded == true, "one tap opens it")
        checks.expect(quiet.id in SessionStore(defaults = defaults).expandedArchives, "and the choice outlives the launch")
        store.toggleArchive(quiet.id)

        store.toggleCollapsed(quiet.id)
        checks.expect(store.groups(sessions, devices = devices).lastOrNull()?.collapsed == true, "a machine folds away on a tap")
        checks.expect(SessionStore(defaults = defaults).collapsedDevices == setOf(quiet.id), "and stays folded across a launch")
        store.toggleCollapsed(quiet.id)

        store.searchText = "vite"
        val byTitle = store.groups(sessions, devices = devices)
        checks.equal(byTitle.size, 1, "search drops a machine with no match entirely")
        checks.equal(byTitle.firstOrNull()?.active?.size, 1, "and keeps the row it matched")
        store.searchText = "/work/api"
        val byPath = store.groups(sessions, devices = devices)
        checks.equal(byPath.firstOrNull()?.archive?.size, 1, "search matches the working directory")
        checks.expect(byPath.firstOrNull()?.archiveExpanded == true,
                      "a match inside an Archive opens it, whatever the stored preference says")
        store.searchText = ""

        checks.equal(store.agentOptions(sessions), listOf("claude", "codex", "grok", "pi"),
                     "the filter offers the agents the list actually contains")
        store.agentFilter = "codex"
        val codexOnly = store.groups(sessions, devices = devices)
        checks.equal(codexOnly.map { it.name }, listOf("mac-studio-office", "ci-runner-01"),
                     "an agent filter removes a machine whose sessions all drop out")
        checks.expect(codexOnly.all { group -> (group.active + group.archive).all { it.agent == "codex" } },
                      "and leaves only that agent's sessions behind")
        store.agentFilter = null

        val archivedByHand = Session(sessionID = "s", deviceID = DemoFixtures.macDeviceID, agent = "claude", title = "Old",
                                     cwd = "/tmp", control = SessionControl.remote, updatedAt = DemoFixtures.now, archived = true)
        checks.expect(SessionListLayout.isArchived(archivedByHand), "a hand-archived session belongs to its device's Archive")
        val withArchived = store.groups(sessions + archivedByHand, devices = devices)
        checks.equal(withArchived.firstOrNull()?.archive?.map { it.sessionID }, listOf("s", DemoFixtures.revivedSessionID),
                     "it joins that machine's Archive rather than a global one")
        checks.equal(withArchived.firstOrNull()?.active?.size, 9, "and never counts as live, whatever still owns it")

        checks.equal(SessionListLayout.urgency(SessionState.needsInput), 0, "waiting on the user comes first")
        checks.equal(SessionListLayout.urgency(SessionState.starting), 1, "a starting agent counts as working")
        checks.equal(SessionListLayout.urgency(SessionState.stopped), 2, "everything at rest comes last")

        checks.equal(RelativeTime.short(since = DemoFixtures.now - 240_000), "4m", "relative minutes")
        checks.equal(RelativeTime.short(since = DemoFixtures.now - 10_800_000), "3h", "relative hours")
        checks.equal(RelativeTime.duration(milliseconds = 6_400), "6.4s", "sub-minute durations")
        checks.equal(RelativeTime.duration(milliseconds = 72_000), "1m 12s", "minute durations")
        checks.equal(RelativeTime.compactCount(48_200), "48.2k", "compact token counts")
    }

    /**
     * Amendment A15: a session the device brings back to life leaves the Archive on the
     * `session.updated` alone. Nothing is reloaded, and no stored preference pins the row to the half
     * of the list it was in.
     */
    @Test
    fun revivedSession() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            val channel = ScriptedChannel()
            val connection = connectionStore(directory)
            connection.enterDemo(api = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay), channel = channel)
            settle { connection.hasSnapshot }
            val store = SessionStore(defaults = MemoryUserDefaults())

            val dormant = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.revivedSessionID }
            if (dormant == null) {
                checks.expect(false, "the hello carries a session folded in an Archive")
                return@check
            }
            val folded = store.groups(connection.sessions, devices = connection.devices).firstOrNull { it.id == dormant.deviceID }
            if (folded == null) {
                checks.expect(false, "its machine is listed")
                return@check
            }
            checks.expect(folded.archive.any { it.sessionID == dormant.sessionID },
                          "the archived session starts in its own device's Archive")
            checks.expect(!folded.archiveExpanded, "folded away, as an Archive starts")
            val archivedBefore = folded.archive.size
            val activeBefore = folded.active.size

            val requestsBefore = channel.requests.size

            val awake = dormant.copy(archived = false, control = SessionControl.terminal, state = SessionState.running,
                                     updatedAt = DemoFixtures.now)
            channel.emit(AppFrame.SessionUpdated(awake))
            settle { connection.sessions.any { it.id == awake.id && !it.archived } }

            val live = store.groups(connection.sessions, devices = connection.devices).firstOrNull { it.id == dormant.deviceID }
            if (live == null) {
                checks.expect(false, "the machine is still listed")
                return@check
            }
            checks.expect(live.active.any { it.sessionID == dormant.sessionID },
                          "one session.updated is enough to move the row into the live rows")
            checks.expect(!live.archive.any { it.sessionID == dormant.sessionID }, "and to take it out of the Archive")
            checks.equal(live.archive.size, archivedBefore - 1, "so the Archive count drops by one")
            checks.equal(live.active.size, activeBefore + 1, "and the live rows gain exactly that row")
            checks.equal(channel.requests.size, requestsBefore, "and the move asks the gateway for nothing: no reload, no re-listing")

            // The reverse: the reader archives it again and the row folds back where it was, which is
            // the same rule read the other way.
            channel.emit(AppFrame.SessionUpdated(dormant))
            settle { connection.sessions.any { it.id == dormant.id && it.archived } }
            val refolded = store.groups(connection.sessions, devices = connection.devices).firstOrNull { it.id == dormant.deviceID }
            if (refolded == null) {
                checks.expect(false, "the machine is listed once more")
                return@check
            }
            checks.expect(refolded.archive.any { it.sessionID == dormant.sessionID }, "archiving it again folds the row straight back")
            checks.equal(refolded.archive.size, archivedBefore, "the Archive count is what it was")
            checks.equal(refolded.active.size, activeBefore, "and so is the live count")
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Amendment A39: Close ends the session on the machine and files it, and the one reply says all
     * three things at once. The row is then in the Archive with nothing owning it, so nothing about it
     * can run on.
     */
    @Test
    fun closesSession() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
            val connection = connectionStore(directory)
            connection.enterDemo(api = gateway, channel = gateway)
            settle { connection.hasSnapshot }
            val store = SessionStore(defaults = MemoryUserDefaults())

            val live = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.liveSessionID }
            if (live == null) {
                checks.expect(false, "the hello carries the running session the device drives")
                return@check
            }
            checks.expect(SessionListLayout.offersClose(live), "whose row offers Close")
            checks.expect(SessionClose.asksFirst(live, online = true), "and is asked about first")

            connection.close(session = live)
            val closed = connection.sessions.firstOrNull { it.sessionID == live.sessionID }
            if (closed == null) {
                checks.expect(false, "the closed session is still listed")
                return@check
            }
            checks.expect(closed.archived, "closing files the row")
            checks.equal(closed.control, SessionControl.none, "with nothing owning it any more")
            checks.equal(closed.state, SessionState.stopped, "and the agent stopped")
            checks.expect(closed.turn == null, "the turn it was running is over")
            checks.expect(connection.errorMessage == null, "and the close reported no failure")
            checks.expect(!SessionListLayout.offersClose(closed), "a closed row offers nothing further, not even unarchive")

            val group = store.groups(connection.sessions, devices = connection.devices).firstOrNull { it.id == closed.deviceID }
            if (group == null) {
                checks.expect(false, "its machine is listed")
                return@check
            }
            checks.expect(group.archive.any { it.sessionID == closed.sessionID }, "and the row is in that machine's Archive")
            checks.expect(!group.active.any { it.sessionID == closed.sessionID }, "out of the live rows")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun settings() = check { checks ->
        val defaults = MemoryUserDefaults()
        val store = SettingsStore(defaults = defaults)
        checks.equal(store.voiceBackend, VoiceBackend.onDevice, "voice defaults to on-device recognition")
        // Amendment A44: the phone's recogniser listens for Chinese until told otherwise, and an
        // `auto` from before the amendment reads the same without being rewritten.
        checks.equal(store.voiceLanguage, "zh", "a new install dictates in Chinese")
        checks.equal(store.speechLocaleIdentifier, "zh-CN", "which the recogniser hears as zh-CN")
        store.voiceLanguage = "auto"
        checks.equal(store.dictationLanguage, "zh", "a legacy auto is heard as Chinese")
        checks.equal(store.voiceLanguage, "auto", "and is left as it was")
        store.voiceLanguage = "en"
        checks.equal(store.speechLocaleIdentifier, "en-US", "a chosen language is its own locale")
        checks.equal(VoiceBackend.inEffect(chosen = VoiceBackend.gateway, gatewayTranscribes = false), VoiceBackend.onDevice,
                     "a gateway with no transcription service leaves the phone listening")
        store.remember(origin = "https://rc.example.com", username = "admin")
        checks.equal(store.timelineDetail, TimelineDetail.simple, "the timeline opens at Simple")
        store.timelineDetail = TimelineDetail.detailed
        val reloaded = SettingsStore(defaults = defaults)
        checks.equal(reloaded.lastOrigin, "https://rc.example.com", "the last origin is remembered")
        checks.equal(reloaded.timelineDetail, TimelineDetail.detailed, "and so is the detail level")
        checks.equal(TimelineDetail.allCases.map { it.title }, listOf("Simple", "Detailed"), "the control offers two levels, in that order")
        checks.equal(TimelineDetail.footnote,
                     "Simple shows only what is written to you. Detailed adds thinking, tool calls and the task list.",
                     "under a sentence that describes both")

        val report = store.diagnosticReport(app = InstalledApp.ios, appVersion = "0.1.0", platform = "iOS", osVersion = "18.0",
                                            phase = ConnectionPhase.Connected, deviceCount = 2, sessionCount = 4,
                                            sttEnabled = true, isDemo = false)
        checks.expect(report.contains("Protocol: v1"), "the report names the protocol version")
        checks.expect(report.contains("Cache schema: v1"), "the report names the cache schema")
        checks.expect(!report.contains("rc.example.com"), "the report never contains the gateway address")
        checks.expect(!report.contains("admin"), "the report never contains the account name")
    }

    @Test
    fun pairing() = check { checks ->
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val flow = PairingFlow(api = gateway)
        flow.begin()
        checks.equal(flow.code, "RC-7K42-QX9M", "the pairing code is shown verbatim")
        checks.expect(flow.command.contains("--pair RC-7K42-QX9M"), "the install one-liner carries the code")
        // `docs/DESIGN.md` § "Add device": nobody picks a platform, because the installer detects it.
        // The gateway still hands out both keys, and the flow reads one of them because they carry the
        // same command.
        checks.equal(flow.pairing?.install?.linux, flow.pairing?.install?.macos, "both install keys carry the one command the host runs")
        checks.equal(flow.steps.size, 4, "the checklist has four steps")
        checks.expect(!flow.steps[1].done, "later steps start incomplete")
        flow.receive(AppFrame.PairingProgress(PairingProgress(code = flow.code, step = PairingStep.online)))
        checks.expect(flow.steps[2].done, "progress lights up the matching step")
        flow.receive(AppFrame.PairingProgress(PairingProgress(code = "RC-OTHER-CODE", step = PairingStep.agents)))
        checks.expect(!flow.steps[3].done, "progress for a different code is ignored")
        checks.expect(flow.expiry().isNotEmpty(), "the code shows a countdown")
    }

    /**
     * Amendment A12: the message is in the transcript before the request has been answered, and the
     * device's echo under the same id replaces it rather than adding a second copy.
     */
    @Test
    fun optimisticSend() = check { checks ->
        val directory = scratchDirectory("stores")
        try {
            val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
            val connection = connectionStore(directory)
            connection.enterDemo(api = gateway, channel = gateway)
            settle { connection.hasSnapshot }
            val session = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.erroredSessionID }
            if (session == null) {
                checks.expect(false, "the demo has a reachable session with no turn running")
                return@check
            }
            val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
            connection.addFrameHandler("a12") { frame -> chat.receive(frame) }
            try {
                chat.open()

                chat.draft = "one more thing"
                val sending = async { chat.send() }
                settle(timeout = 2.seconds) { chat.timeline.roots.any { it.pending != null } }
                checks.equal(chat.timeline.roots.lastOrNull()?.pending?.text, "one more thing",
                             "the message is on screen before the request has been answered")
                checks.expect(chat.draft.isEmpty(), "and the field is already empty")
                sending.await()
                checks.equal(chat.lastAcceptance, SendAcceptance.sent, "the demo accepts it outright")
                checks.expect(chat.timeline.optimistic.isNotEmpty(), "and the row waits for the device's own event, not for the reply")

                settle(timeout = 4.seconds) { chat.timeline.optimistic.isEmpty() }
                checks.expect(chat.timeline.optimistic.isEmpty(), "the echo retires the row")
                checks.equal(chat.timeline.roots.count { it.userMessage?.text == "one more thing" }, 1,
                             "and the transcript holds exactly one copy of the message")
            } finally {
                connection.removeFrameHandler("a12")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A channel that serves one hello, records every request, and closes or emits frames on command. */
    internal class ScriptedChannel : GatewayChannel {
        private val stream = Channel<GatewayEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_LATEST)
        override val events: Flow<GatewayEvent> = stream.receiveAsFlow()
        private val recorded = mutableListOf<GatewayRequest>()
        var subscribeReply: SubscribeResult? = null
        var historyReply: HistoryResult? = null

        val requests: List<GatewayRequest> get() = recorded.toList()

        fun requests(ofType: String): List<GatewayRequest> = requests.filter { it.type == ofType }

        override suspend fun connect() {
            stream.trySend(GatewayEvent.State(ConnectionState.connected))
            emitHello()
        }

        fun emitHello() {
            stream.trySend(GatewayEvent.Frame(AppFrame.Hello(HelloFrame(
                protocolVersion = RemoteProtocol.version, gatewayVersion = "test", user = UserIdentity(username = "admin"),
                devices = DemoFixtures.devices, sessions = DemoFixtures.sessions, stt = STTConfig.disabled, serverTime = 0))))
        }

        fun emit(frame: AppFrame) {
            stream.trySend(GatewayEvent.Frame(frame))
        }

        override suspend fun disconnect() {
            stream.trySend(GatewayEvent.State(ConnectionState.disconnected))
        }

        fun close(reason: SocketCloseReason) {
            if (reason == SocketCloseReason.unauthorized) stream.trySend(GatewayEvent.State(ConnectionState.unauthorized))
            stream.trySend(GatewayEvent.Closed(reason))
        }

        override suspend fun request(request: GatewayRequest): JsonElement {
            recorded.add(request)
            return when (request.type) {
                "session.subscribe" -> subscribeReply?.let { JSONValue.encode(it) } ?: JSONValue.emptyObject
                "session.history" -> JSONValue.encode(historyReply ?: HistoryResult(events = emptyList(), hasMore = false))
                else -> JSONValue.emptyObject
            }
        }
    }
}

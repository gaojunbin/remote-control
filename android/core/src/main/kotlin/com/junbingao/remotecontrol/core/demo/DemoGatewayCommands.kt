package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.CommandsResult
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnMarker
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.milliseconds

// Slash commands (A27): what a session offers, and what running one does. Every function here
// runs on the gateway's isolation.

/**
 * What the session offers right now. An agent without the capability is refused rather than
 * answered with an empty list, because "none" and "not this agent" are different things and only
 * the second one is permanent.
 */
internal fun DemoGateway.listCommands(request: GatewayRequest): JsonElement {
    val session = session(requireSessionID(request))
    requireCommands(session)
    return JSONValue.encode(CommandsResult(commands = DemoFixtures.commands(agent = session.agent)))
}

/**
 * Run one. The device echoes it as a `user_message` under the request's id and reports what it
 * did as ordinary events; the result carries nothing.
 */
internal fun DemoGateway.runCommand(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val session = session(id)
    requireCommands(session)
    if (session.isControlledByTerminal) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Controlled by the terminal; take over first.")
    }
    val name = (request.body["name"]?.stringValue ?: "").lowercase()
    val command = DemoFixtures.commands(agent = session.agent).firstOrNull { it.name == name }
        ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "There is no /$name on this session.")
    // Amendment A40: a command on a shared Claude session is typed into the terminal, so a busy
    // one is refused in the device's own words rather than in the words for a turn this app is
    // waiting behind.
    if (session.state.isWorking) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict,
                               message = if (isTypedInto(session)) DemoGateway.terminalIsBusy
                                         else "Wait for the turn to finish.")
    }
    val argument = request.body["argument"]?.stringValue
    emit(sessionID = id, blockID = request.id,
         body = SessionEventBody.UserMessage(UserMessagePayload(text = command.line(argument = argument),
                                                                source = EventSource.remote)))
    play(command = command, agent = session.agent, sessionID = id)
    return JSONValue.emptyObject
}

private fun DemoGateway.requireCommands(session: Session) {
    if (agent(session = session)?.supports(AgentCapability.commands) != true) {
        throw GatewayErrorBody(code = GatewayErrorCode.unsupported,
                               message = "${session.agent} takes no commands from here.")
    }
}

/**
 * What each command does to the transcript. A state change is a `notice`, information a terminal
 * would have printed is a `tool_call` block titled with the command, and everything else is an
 * ordinary turn.
 */
private fun DemoGateway.play(command: Command, agent: String, sessionID: String) {
    if (command.name == "compact") {
        emit(sessionID = sessionID, body = SessionEventBody.Notice(NoticePayload(
            level = NoticeLevel.info, text = "Context was compacted; earlier turns are summarised.")))
        return
    }
    val output = codexOutput[command.name]
    if (agent == "codex" && output != null) {
        emit(sessionID = sessionID, blockID = "cmd-${uuidString().take(8)}",
             body = SessionEventBody.ToolCall(ToolCallPayload(tool = command.slash, kind = ToolKind.other,
                                                              title = command.slash, status = ToolStatus.succeeded,
                                                              output = output, durationMS = 180)))
        return
    }
    update(sessionID = sessionID) {
        it.copy(state = SessionState.running, turn = TurnMarker(turnID = uuidString(), startedAt = DemoFixtures.now))
    }
    commanding?.cancel()
    commanding = scope.launch { playCommandTurn(command = command, agent = agent, sessionID = sessionID) }
}

private suspend fun DemoGateway.playCommandTurn(command: Command, agent: String, sessionID: String) {
    val reviews = agent == "codex" && command.name == "review"
    if (reviews) {
        emit(sessionID = sessionID, body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info,
                                                                                 text = "Review started")))
    }
    pause(600.milliseconds)
    if (isCancelled()) return
    emit(sessionID = sessionID, blockID = "cmd-a-${uuidString().take(6)}",
         body = SessionEventBody.AssistantText(StreamTextPayload(text = answer(to = command, agent = agent),
                                                                 done = true)))
    if (reviews) {
        emit(sessionID = sessionID, body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info,
                                                                                 text = "Review finished")))
    }
    emit(sessionID = sessionID, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
        turnID = "demo-turn-command", stopReason = StopReason.completed, durationMS = 6_000)))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.idle)))
    finishTurn(sessionID = sessionID)
}

/**
 * The information commands, whose whole answer is the text a terminal would have printed.
 * `/usage` is the protocol's own worked example (`fixtures/events/tool_call.command.json`).
 */
private val codexOutput: Map<String, String> = mapOf(
    "status" to """
        Model: GPT-5.4 Codex · Medium
        Speed: standard
        Approval: Ask when needed
        Sandbox: workspace-write
        Working directory: /Users/me/dev/remote-control/web
        Tokens: 16,720 of 272,000
    """.trimIndent(),
    "usage" to """
        Plan: Pro
        5-hour window: 38% used, resets 14:20
        Weekly window: 12% used, resets Thu 09:00
    """.trimIndent(),
    "skills" to """
        pdf-tables — Extract tables from a PDF into CSV
        web-research — Search the web and summarise what it finds
    """.trimIndent(),
    "hooks" to """
        pre-commit — npm run lint
        turn-end — osascript -e 'display notification "done"'
    """.trimIndent(),
    "mcp" to """
        github — connected, 14 tools
        playwright — connected, 9 tools
    """.trimIndent(),
)

private fun answer(to: Command, agent: String): String = when {
    agent == "codex" && to.name == "review" ->
        "Reviewed 6 changed files. One finding: `SessionSettings` drops `effort` when the drawer is closed."
    agent == "codex" && to.name == "init" ->
        "Wrote `AGENTS.md` with the build, test and lint commands this repository uses."
    else -> "Ran ${to.slash} and finished."
}

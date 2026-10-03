package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.BlockResult
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.GitInfo
import com.junbingao.remotecontrol.core.protocol.HistoryResult
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.MetaPayload
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.ResumeBounds
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.protocol.boolValue
import com.junbingao.remotecontrol.core.protocol.intValue
import com.junbingao.remotecontrol.core.protocol.longValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

// The demo gateway's requests about a session's lifecycle: opening it, looking at it (A47), its
// history, resuming after a usage limit (A35), its settings, takeover, archive and create. Every
// function here runs on the gateway's isolation.

/** How long the device takes to type a command and read the answer back out of the transcript before it replies. */
private val typingDelay = 1_500.milliseconds

internal fun DemoGateway.subscribe(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val session = session(id)
    val since = request.body["since_seq"]?.intValue
    val all = transcripts[id].orEmpty()
    val events = if (since == null) emptyList() else all.filter { it.seq > since }
    if (id == DemoFixtures.liveSessionID) startLiveScript(sessionID = id)
    if (id == DemoFixtures.sharedSessionID) {
        startRetuneScript(sessionID = id)
        startQuestionScript(sessionID = id)
    }
    // Amendment A6: what the device is holding comes with the reply, so a conversation opens with
    // its queue rather than a snapshot later.
    val held = queues[id]?.let { if (it.isEmpty) null else QueuePayload(pending = it.pending) }
    return JSONValue.encode(SubscribeResult(session = session, events = events, resync = false, queue = held))
}

/**
 * Amendment A47: the person has the conversation in front of them. The gateway answers this itself,
 * takes the mark off and publishes the session only when there was a mark to take off. The device
 * reported nothing, so the session's own `updated_at` stays where it was.
 */
internal fun DemoGateway.markSeen(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    if (!session(id).unseen) return JSONValue.emptyObject
    sessionList = sessionList.map { if (it.sessionID == id) it.copy(unseen = false) else it }
    continuation.trySend(GatewayEvent.Frame(AppFrame.SessionUpdated(session(id))))
    return JSONValue.emptyObject
}

internal fun DemoGateway.history(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val before = request.body["before_seq"]?.intValue
    val all = transcripts[id].orEmpty()
    val page = if (before == null) all.toList() else all.filter { it.seq < before }
    return JSONValue.encode(HistoryResult(events = page, hasMore = false))
}

internal fun DemoGateway.fullBlock(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val history = transcripts[id].orEmpty()
    val blockID = request.body["block_id"]?.stringValue
    val event = blockID?.let { block -> history.lastOrNull { it.blockID == block } }
        ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such block")
    return JSONValue.encode(BlockResult(event = event))
}

// Resuming after a usage limit (A35)

/**
 * Schedule or move the resume. The bounds are the protocol's, and the refusals are the ones a real
 * device gives, so the picker is exercised against them rather than against nothing.
 */
internal fun DemoGateway.setResume(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val paused = session(id)
    if (paused.state.isWorking) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Wait for the turn to finish.")
    }
    if (paused.isControlledByTerminal) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Controlled by the terminal; take over first.")
    }
    val at = request.body["at"]?.longValue
        ?: throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "That resume has no time.")
    if (!ResumeBounds.allows(Instant.ofEpochMilli(at))) {
        throw GatewayErrorBody(code = GatewayErrorCode.badRequest,
                               message = "Pick a time between a minute from now and eight days away.")
    }
    val previous = paused.resume
    val resume = SessionResume(at = at, estimated = false, attempts = previous?.attempts ?: 0,
                               windowMinutes = previous?.windowMinutes)
    update(sessionID = id) { it.copy(resume = resume) }
    emit(sessionID = id, body = SessionEventBody.Resume(ResumePayload(
        status = if (previous == null) ResumeStatus.scheduled else ResumeStatus.rescheduled, at = at,
        estimated = false, attempts = previous?.attempts)))
    return JSONValue.encode(SessionResult(session = session(id)))
}

/** Idempotent: a session with nothing pending answers with itself and says nothing in the transcript. */
internal fun DemoGateway.cancelResume(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    if (session(id).resume == null) return JSONValue.encode(SessionResult(session = session(id)))
    update(sessionID = id) { it.copy(resume = null) }
    emit(sessionID = id, body = SessionEventBody.Resume(ResumePayload(status = ResumeStatus.cancelled,
                                                                      reason = "you changed your mind")))
    return JSONValue.encode(SessionResult(session = session(id)))
}

/** Turning the switch off ends every pending resume of the account, each with its own row, exactly as protocol 7.2 says. */
internal fun DemoGateway.cancelEveryResume() {
    for (pending in sessionList.filter { it.resume != null }) {
        val id = pending.sessionID
        update(sessionID = id) { it.copy(resume = null) }
        emit(sessionID = id, body = SessionEventBody.Resume(ResumePayload(status = ResumeStatus.cancelled,
                                                                          reason = "the switch was turned off")))
    }
}

// Settings, takeover, archive and create

internal suspend fun DemoGateway.applySet(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    // Amendment A10: only the title is ours to change on an attached session, unless amendment
    // A11's `shared_settings` says the attachment retunes the live thread — and amendment A40's
    // `shared_settings_keys` says which of the four it carries.
    val existing = session(id)
    val speed = request.body["speed"]
    val asked = settingsAsked(request)
    if (existing.isAttached && asked.any { agent(session = existing)?.shares(it) != true }) {
        throw GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "Change it in the terminal.")
    }
    // Amendment A21: an agent with no faster tier has nothing to set.
    if (speed != null && agent(session = existing)?.speeds?.isEmpty() != false) {
        throw GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "This model has no faster tier.")
    }
    // Amendment A40: a Claude terminal takes this as typing, so it lands only while nobody else
    // is at that keyboard, and it takes as long as typing a command and reading the answer back
    // does.
    if (asked.isNotEmpty() && isTypedInto(existing)) {
        if (existing.state.isWorking) {
            throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = DemoGateway.terminalIsBusy)
        }
        pause(typingDelay)
    }
    update(sessionID = id) {
        it.copy(model = request.body["model"]?.stringValue ?: it.model,
                permissionMode = request.body["permission_mode"]?.stringValue ?: it.permissionMode,
                effort = request.body["effort"]?.stringValue ?: it.effort,
                speed = if (speed != null) speed.stringValue else it.speed,
                title = request.body["title"]?.stringValue ?: it.title)
    }
    // A real device reports what it applied rather than leaving the app to trust its own optimism,
    // so the demo publishes the same `meta` (5.11).
    emit(sessionID = id, body = SessionEventBody.Meta(MetaPayload(
        title = request.body["title"]?.stringValue,
        model = request.body["model"]?.stringValue,
        permissionMode = request.body["permission_mode"]?.stringValue,
        effort = request.body["effort"]?.stringValue,
        speed = speed?.let { SpeedChange(id = it.stringValue) })))
    return JSONValue.encode(SessionResult(session = session(id)))
}

/** Which of the four settings one `session.set` carries. The title is not among them: it is the app's on every session, attached or not. */
private fun settingsAsked(request: GatewayRequest): List<SharedSetting> =
    SharedSetting.allCases.filter { request.body[it.rawValue] != null }

/**
 * Amendment A40: whether a change to this session is typed into a terminal rather than handed to
 * a daemon. A channel carries user text and nothing else, so the shim's pseudo-terminal is the
 * only way in.
 */
internal fun DemoGateway.isTypedInto(session: Session): Boolean =
    session.isAttached && agent(session = session)?.attach == AgentAttach.channel

internal fun DemoGateway.takeover(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    // Amendment A10: an attached session is already under joint control.
    if (session(id).isAttached) throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Already attached.")
    update(sessionID = id) { it.copy(control = SessionControl.remote, state = SessionState.idle) }
    emit(sessionID = id, body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info,
                                                                      text = "You took over from the terminal.")))
    return JSONValue.encode(SessionResult(session = session(id)))
}

/**
 * Amendment A39: `archived: true` on a session the device drives closes it. The scripted turn
 * stops the way a real interrupt stops one, what the device held is gone, and the row is published
 * once — archived, unowned and stopped together, so nothing of it can speak again and pull it back
 * out of the Archive. `archived: false` only clears the flag.
 */
internal fun DemoGateway.archive(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val archived = request.body["archived"]?.boolValue ?: true
    val closes = archived && session(id).control == SessionControl.remote
    if (closes) {
        scripted?.cancel()
        scripted = null
    }
    update(sessionID = id) {
        if (closes) {
            it.copy(archived = archived, control = SessionControl.none, state = SessionState.stopped,
                    stateDetail = null, turn = null)
        } else {
            it.copy(archived = archived)
        }
    }
    return JSONValue.encode(SessionResult(session = session(id)))
}

internal fun DemoGateway.create(request: GatewayRequest): JsonElement {
    val session = Session(
        sessionID = "demo-${uuidString().take(8)}",
        deviceID = request.body["device_id"]?.stringValue ?: DemoFixtures.macDeviceID,
        agent = request.body["agent"]?.stringValue ?: "claude",
        title = request.body["title"]?.stringValue ?: "New session",
        cwd = request.body["cwd"]?.stringValue ?: "/Users/me/dev",
        git = GitInfo(branch = "main"), state = SessionState.idle, origin = EventSource.remote,
        control = SessionControl.remote,
        model = request.body["model"]?.stringValue,
        permissionMode = request.body["permission_mode"]?.stringValue,
        effort = request.body["effort"]?.stringValue,
        speed = request.body["speed"]?.stringValue,
        createdAt = DemoFixtures.now, updatedAt = DemoFixtures.now)
    sessionList = listOf(session) + sessionList
    transcripts[session.sessionID] = mutableListOf()
    cursors[session.sessionID] = 0
    continuation.trySend(GatewayEvent.Frame(AppFrame.SessionUpdated(session)))
    return JSONValue.encode(SessionResult(session = session))
}

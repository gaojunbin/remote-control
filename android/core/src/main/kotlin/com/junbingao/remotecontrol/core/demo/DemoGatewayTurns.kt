package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.ApprovalDecision
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnMarker
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.arrayValue
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.decodeBase64
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

// The demo gateway's turns: a message sent, held behind a turn (A43) or into a terminal (A10,
// A11, A14, A19, A28), a turn stopped, and the prompts a turn raises answered. Every function here
// runs on the gateway's isolation.

internal fun DemoGateway.send(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val session = session(id)
    if (session.isControlledByTerminal) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Controlled by the terminal; take over first.")
    }
    val text = request.body["text"]?.stringValue ?: ""
    val mode = SendMode(request.body["mode"]?.stringValue ?: SendMode.auto.rawValue)
    val held = held(request, text = text)
    if (session.isAttached) return sendShared(session = session, request = held, mode = mode)
    val running = session.state.isWorking
    if (running && mode == SendMode.interrupt) {
        interruptTurn(sessionID = id)
    } else if (running || mode == SendMode.queue) {
        // Held until the turn ends, as a real device holds it. An explicit `queue` on an idle
        // session is taken at once, which is what an edit that outlived the turn it was waiting
        // for needs (A43).
        hold(held, sessionID = id)
        if (!running) deliverNext(sessionID = id)
        return JSONValue.encode(SendResult(accepted = SendAcceptance.queued, queuedID = request.id))
    }
    update(sessionID = id) {
        it.copy(state = SessionState.running, turn = TurnMarker(turnID = uuidString(), startedAt = DemoFixtures.now))
    }
    // A real device reports the message it was given before it reports what the agent said about
    // it, so the echo leads and the reply follows it.
    echoing?.cancel()
    echoing = scope.launch {
        pause(echoDelay)
        if (isCancelled()) return@launch
        echoThenReply(sessionID = id, blockID = request.id, text = text)
    }
    return JSONValue.encode(SendResult(accepted = SendAcceptance.sent))
}

private fun DemoGateway.echoThenReply(sessionID: String, blockID: String, text: String) {
    emit(sessionID = sessionID, blockID = blockID,
         body = SessionEventBody.UserMessage(UserMessagePayload(text = text, source = EventSource.remote)))
    startReplyScript(sessionID = sessionID)
}

/** Section 5's `interrupt` on a session the device drives: the running turn ends where it is, and the message starts the next one. */
private fun DemoGateway.interruptTurn(sessionID: String) {
    scripted?.cancel()
    scripted = null
    val turnID = attempt { session(sessionID) }?.turn?.turnID ?: "demo-turn"
    emit(sessionID = sessionID, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
        turnID = turnID, stopReason = StopReason.interrupted, durationMS = 4_000)))
}

// The queue (A43)

/** A message as the device holds it until it is delivered. */
private data class HeldMessage(
    val item: DemoQueue.Item,
    /** Sent back with `queue_ts`, so it goes back into its place. */
    val returnsToPlace: Boolean,
)

/** Reads a `session.send` the way the device does, and refuses a `queue_ts` that is not a non-negative integer, as it does. */
private fun held(request: GatewayRequest, text: String): HeldMessage {
    val queueTs = when (val value = request.body["queue_ts"]) {
        null, JsonNull -> null
        else -> value.integer?.takeIf { it >= 0 }
            ?: throw GatewayErrorBody(code = GatewayErrorCode.badRequest,
                                      message = "queue_ts must be a non-negative integer")
    }
    val files = (request.body["attachments"]?.arrayValue ?: emptyList()).map(::attachmentInfo)
    return HeldMessage(item = DemoQueue.Item(id = request.id, text = text, ts = queueTs ?: DemoFixtures.now,
                                             files = files),
                       returnsToPlace = queueTs != null)
}

/** RCCore's `JSONValue.integer`: a number written as a whole number, and nothing else — not `1.5`, not `"12"`, not `true`. */
private val JsonElement.integer: Long?
    get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

private fun attachmentInfo(attachment: JsonElement): AttachmentInfo {
    val bytes = attachment["data_base64"]?.stringValue?.let(::decodeBase64)
    return AttachmentInfo(name = attachment["name"]?.stringValue ?: "",
                          mime = attachment["mime"]?.stringValue ?: "application/octet-stream",
                          size = bytes?.size ?: 0)
}

/** Hold a message behind the turn: back in its place when it was sent back to one, at the end of the line otherwise. */
private fun DemoGateway.hold(message: HeldMessage, sessionID: String) {
    val queue = queues.getOrPut(sessionID) { DemoQueue() }
    if (message.returnsToPlace) queue.insert(message.item) else queue.append(message.item)
    publishQueue(sessionID = sessionID)
}

/** The snapshot always names the whole line, and the session row counts it. */
private fun DemoGateway.publishQueue(sessionID: String) {
    val pending = queues[sessionID]?.pending.orEmpty()
    emit(sessionID = sessionID, body = SessionEventBody.Queue(QueuePayload(pending = pending)))
    update(sessionID = sessionID) { it.copy(queued = pending.size) }
}

/**
 * A turn ended, so the device delivers the next message it holds, as a real one does: the block
 * arrives under the id the app sent it with, with `source: "queue"`, and a turn starts for it. A
 * terminal the device is attached to takes it the way it takes any held message, by injection.
 */
internal fun DemoGateway.deliverNext(sessionID: String) {
    if (queues[sessionID]?.isEmpty != false) return
    val session = attempt { session(sessionID) } ?: return
    if (session.isAttached) {
        scheduleInjection(sessionID = sessionID, asksForApproval = false)
        return
    }
    val item = queues[sessionID]?.next() ?: return
    publishQueue(sessionID = sessionID)
    emit(sessionID = sessionID, blockID = item.message.id,
         body = SessionEventBody.UserMessage(UserMessagePayload(text = item.message.text, attachments = item.files,
                                                                source = EventSource.queue)))
    update(sessionID = sessionID) {
        it.copy(state = SessionState.running, turn = TurnMarker(turnID = uuidString(), startedAt = DemoFixtures.now))
    }
    startReplyScript(sessionID = sessionID)
}

/**
 * `session.queue_remove`, answered as the device answers it: the message leaves the line and the
 * snapshot says so, or `not_found` when the device holds nothing by that id — it was delivered
 * already.
 */
internal fun DemoGateway.removeQueued(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    session(id)
    val queuedID = request.body["queued_id"]?.stringValue ?: ""
    if (queues[id]?.remove(id = queuedID) == null) {
        throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "that message is not queued")
    }
    publishQueue(sessionID = id)
    return JSONValue.emptyObject
}

// Attached sessions

/**
 * A message for an attached session takes the route the attachment supports. A channel can only
 * hand keystrokes to a CLI, so it holds the message and injects it (amendment A10). A daemon is a
 * real client of the agent's own server, so it starts, steers or interrupts the thread the
 * terminal is on (amendment A11), and Grok Build's leader is such a client too: the prompt runs in
 * the conversation the TUI is in (amendment A28). Only the device branches on this; the apps read
 * the acceptance and the agent's capabilities.
 */
private fun DemoGateway.sendShared(session: Session, request: HeldMessage, mode: SendMode): JsonElement {
    val agent = agent(session = session)
    if (agent?.attach != AgentAttach.daemon && agent?.attach != AgentAttach.leader) {
        return inject(request, sessionID = session.sessionID)
    }
    val id = session.sessionID
    val requestID = request.item.message.id
    val text = request.item.message.text
    if (!session.state.isWorking) {
        emit(sessionID = id, body = SessionEventBody.UserMessage(UserMessagePayload(text = text,
                                                                                    source = EventSource.remote)))
        update(sessionID = id) {
            it.copy(state = SessionState.running,
                    turn = TurnMarker(turnID = uuidString(), startedAt = DemoFixtures.now))
        }
        startReplyScript(sessionID = id)
        return JSONValue.encode(SendResult(accepted = SendAcceptance.sent))
    }
    return when {
        mode == SendMode.interrupt -> {
            emit(sessionID = id, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                turnID = session.turn?.turnID ?: "demo-turn", stopReason = StopReason.interrupted, durationMS = 4_000)))
            emit(sessionID = id, body = SessionEventBody.UserMessage(UserMessagePayload(text = text,
                                                                                        source = EventSource.remote)))
            update(sessionID = id) { it.copy(turn = TurnMarker(turnID = uuidString(), startedAt = DemoFixtures.now)) }
            startReplyScript(sessionID = id)
            JSONValue.encode(SendResult(accepted = SendAcceptance.sent))
        }
        mode == SendMode.auto && agent.supports(AgentCapability.steer) -> {
            // The message joins the turn that is already running, so it needs neither a queue
            // entry nor a delivery state. Amendment A14: the agent reads it at its next step, not
            // where it was sent, so the block waits for the step and the app's own row holds its
            // place.
            steering?.cancel()
            steering = scope.launch {
                pause(echoDelay)
                if (isCancelled()) return@launch
                takeSteeredMessage(sessionID = id, blockID = requestID, text = text)
            }
            JSONValue.encode(SendResult(accepted = SendAcceptance.steered))
        }
        else -> inject(request, sessionID = id)
    }
}

/**
 * Amendment A14: the turn finishes the sentence it was already saying, the agent reads the
 * steered message at its next step, and only then does the block for it appear — under the
 * request id, after the output above it. That is the order a terminal on the same thread draws.
 */
private fun DemoGateway.takeSteeredMessage(sessionID: String, blockID: String, text: String) {
    emit(sessionID = sessionID, blockID = "a-${uuidString().take(6)}",
         body = SessionEventBody.AssistantText(StreamTextPayload(
             text = "The typecheck is clean now that the drawer passes `effort` through again.", done = true)))
    emit(sessionID = sessionID, blockID = blockID,
         body = SessionEventBody.UserMessage(UserMessagePayload(text = text, source = EventSource.remote,
                                                                delivery = MessageDelivery.delivered)))
    startReplyScript(sessionID = sessionID)
}

/**
 * Amendment A10: a message a channel cannot deliver yet is held by the device and injected when
 * the terminal is next idle. Amendment A19: while it waits it is a queue entry and nothing else —
 * the block appears only when the CLI takes it, after the output of the turn it waited for.
 */
private fun DemoGateway.inject(message: HeldMessage, sessionID: String): JsonElement {
    hold(message, sessionID = sessionID)
    // A turn that is already running asks for its own permission; only an idle thread reaches the
    // request this script plays.
    scheduleInjection(sessionID = sessionID, asksForApproval = !session(sessionID).state.isWorking)
    return JSONValue.encode(SendResult(accepted = SendAcceptance.queued, queuedID = message.item.message.id))
}

private fun DemoGateway.scheduleInjection(sessionID: String, asksForApproval: Boolean) {
    injecting?.cancel()
    injecting = scope.launch { playInjection(sessionID = sessionID, asksForApproval = asksForApproval) }
}

/**
 * The CLI takes the first message in the line, whichever it is by now: one taken out to be edited
 * is no longer there to take (A43). The line is published before the block, as the device
 * publishes it, so nothing that has seen the block can still see the message waiting.
 */
private suspend fun DemoGateway.playInjection(sessionID: String, asksForApproval: Boolean) {
    pause(2_400.milliseconds)
    if (isCancelled()) return
    val item = queues[sessionID]?.next() ?: return
    publishQueue(sessionID = sessionID)
    emit(sessionID = sessionID, blockID = item.message.id,
         body = SessionEventBody.UserMessage(UserMessagePayload(text = item.message.text, attachments = item.files,
                                                                source = EventSource.remote,
                                                                delivery = MessageDelivery.delivered)))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.running)))
    update(sessionID = sessionID) {
        it.copy(state = SessionState.running, turn = TurnMarker(turnID = "demo-turn-shared", startedAt = DemoFixtures.now))
    }
    pause(900.milliseconds)
    if (isCancelled() || !asksForApproval) return
    emit(sessionID = sessionID, blockID = "ap-shared", body = SessionEventBody.Approval(ApprovalPayload(
        requestID = "demo-approval-shared", tool = "Bash", kind = ToolKind.shell,
        title = "git commit -am 'Draft 0.1.0 release notes'",
        input = jsonObjectOf("tool_name" to "Bash",
                             "description" to "Commit the drafted release notes",
                             "input_preview" to "git commit -am 'Draft 0.1.0 release notes'"),
        options = listOf(ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary),
                         ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger)),
        status = RequestStatus.pending)))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.needsApproval)))
    update(sessionID = sessionID) { it.copy(state = SessionState.needsApproval) }
}

// Stopping, approving and answering

internal fun DemoGateway.stop(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    // Amendment A11: the Codex daemon relays `turn/interrupt`, and says so. Amendment A42: a
    // Claude terminal takes Escape from the device's pseudo-terminal — never over a prompt, which
    // is answered from its card, and not at all when nothing is running.
    val existing = session(id)
    if (existing.isAttached && agent(session = existing)?.sharedInterrupt != true) {
        throw GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "Stop it in the terminal.")
    }
    if (isTypedInto(existing)) {
        if (existing.state == SessionState.needsApproval || existing.state == SessionState.needsInput) {
            throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "answer the prompt first")
        }
        if (!existing.state.isWorking) return JSONValue.emptyObject
        injecting?.cancel()
        injecting = null
    }
    scripted?.cancel()
    scripted = null
    emit(sessionID = id, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
        turnID = "demo-turn", stopReason = StopReason.interrupted, durationMS = 4_000)))
    emit(sessionID = id, body = SessionEventBody.Status(StatusPayload(state = SessionState.idle)))
    update(sessionID = id) { it.copy(state = SessionState.idle, turn = null) }
    // A stopped turn is an ended one: what waited behind it goes next.
    deliverNext(sessionID = id)
    return JSONValue.emptyObject
}

internal fun DemoGateway.resolveApproval(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val history = transcripts[id].orEmpty()
    val requestID = request.body["request_id"]?.stringValue
    val optionID = request.body["option_id"]?.stringValue
    val pending = requestID?.let { asked -> history.lastOrNull { it.approval?.requestID == asked } }
    val approval = pending?.approval
    if (optionID == null || pending == null || approval == null) {
        throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "That request is no longer open.")
    }
    // Amendment A11: `elsewhere` is a resolution, never a choice. Any id the block did not offer
    // is refused rather than relayed.
    if (approval.options.none { it.id == optionID }) {
        throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "That request did not offer that option.")
    }
    emit(sessionID = id, blockID = pending.blockID,
         body = SessionEventBody.Approval(ApprovalPayload(
             requestID = approval.requestID, tool = approval.tool, kind = approval.kind, title = approval.title,
             input = approval.input, diff = approval.diff, options = approval.options,
             status = RequestStatus.resolved,
             decision = ApprovalDecision(optionID = optionID, by = EventSource.remote))))
    emit(sessionID = id, body = SessionEventBody.Status(StatusPayload(state = SessionState.running)))
    update(sessionID = id) { it.copy(state = SessionState.running) }
    if (attempt { session(id) }?.isAttached == true) startReplyScript(sessionID = id)
    return JSONValue.emptyObject
}

/**
 * Amendment A20: the app got to the question before the terminal did. The device feeds the
 * answers back to the CLI as the tool's own answers and the block resolves saying where they came
 * from.
 */
internal fun DemoGateway.resolveQuestion(request: GatewayRequest): JsonElement {
    val id = requireSessionID(request)
    val history = transcripts[id].orEmpty()
    val requestID = request.body["request_id"]?.stringValue
    val pending = requestID?.let { asked -> history.lastOrNull { it.question?.requestID == asked } }
    val question = pending?.question
    if (pending == null || question == null || !question.status.isActionable) {
        throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "That question is no longer open.")
    }
    val answers = attempt { request.body["answers"]?.decode<Map<String, QuestionAnswer>>() }
    answering?.cancel()
    answering = null
    resolve(sessionID = id, block = pending.blockID, question = question, answers = answers, by = EventSource.remote)
    return JSONValue.emptyObject
}

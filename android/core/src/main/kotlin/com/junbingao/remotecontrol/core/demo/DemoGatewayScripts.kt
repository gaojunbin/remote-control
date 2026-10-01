package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.MetaPayload
import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TodosPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// What the demo device does on its own: the turns it plays, the terminal that moves under the
// app, and the pairing it walks through. Every function here runs on the gateway's isolation.

/** Amendment A17: how long after the attached session is opened its terminal switches model. Short enough to be seen without waiting for it. */
private val retuneDelay = 700.milliseconds

/** Amendment A20: how long after the attached session is opened its Claude asks the question the terminal is also showing a dialog for. */
private val questionDelay = 900.milliseconds

/** And how long the person at the terminal takes to answer it there, which is long enough that answering from here is what normally happens. */
private val terminalAnswerDelay = 30.seconds

/**
 * Amendment A15: a terminal attaches to the session the reader left in the Archive. The device
 * clears `archived`, reports the terminal as its owner and publishes the session, all in one
 * `session.updated`. The row leaves the Archive for the live rows without a reload and without a
 * turn: the transcript it already carries is a finished one, and the demo shows the attach rather
 * than inventing output nobody asked for.
 */
internal suspend fun DemoGateway.resumeArchivedSession() {
    val resumeDelay = resumeDelay ?: return
    pause(resumeDelay)
    if (isCancelled()) return
    update(sessionID = DemoFixtures.revivedSessionID) {
        it.copy(archived = false, origin = EventSource.terminal, control = SessionControl.terminal,
                state = SessionState.idle)
    }
}

/**
 * Amendment A17: somebody types `/model` in the terminal that owns the attached session. The
 * device reads the change out of the transcript and publishes it as `meta` and as a session
 * summary; the app has no picker to keep in step, only the chip that says what the terminal
 * chose, and it follows without a reload.
 */
internal fun DemoGateway.startRetuneScript(sessionID: String) {
    if (retuning != null) return
    retuning = scope.launch {
        pause(retuneDelay)
        if (isCancelled()) return@launch
        retune(sessionID = sessionID, model = "claude-opus-4-1")
    }
}

private fun DemoGateway.retune(sessionID: String, model: String) {
    emit(sessionID = sessionID, body = SessionEventBody.Meta(MetaPayload(model = model)))
    update(sessionID = sessionID) { it.copy(model = model) }
}

/** The terminal's own dialog, answered there because nobody answered here. */
internal fun DemoGateway.startQuestionScript(sessionID: String) {
    if (asking != null) return
    asking = scope.launch {
        pause(questionDelay)
        if (isCancelled()) return@launch
        ask(sessionID = sessionID)
    }
}

private fun DemoGateway.ask(sessionID: String) {
    val question = DemoFixtures.sharedQuestion
    emit(sessionID = sessionID, blockID = "q-shared", body = SessionEventBody.Question(question))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.needsInput)))
    update(sessionID = sessionID) { it.copy(state = SessionState.needsInput) }
    answering?.cancel()
    answering = scope.launch {
        pause(terminalAnswerDelay)
        if (isCancelled()) return@launch
        resolve(sessionID = sessionID, block = "q-shared", question = question,
                answers = mapOf("q1" to QuestionAnswer.Options(listOf("remote"))), by = EventSource.terminal)
    }
}

internal fun DemoGateway.resolve(sessionID: String, block: String?, question: QuestionPayload,
                                 answers: Map<String, QuestionAnswer>?, by: EventSource) {
    emit(sessionID = sessionID, blockID = block,
         body = SessionEventBody.Question(QuestionPayload(requestID = question.requestID, questions = question.questions,
                                                          status = RequestStatus.resolved, answers = answers, by = by)))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.idle)))
    update(sessionID = sessionID) { it.copy(state = SessionState.idle) }
}

/** The live session keeps producing output, so the demo shows a real turn. */
internal fun DemoGateway.startLiveScript(sessionID: String) {
    if (scripted != null) return
    scripted = scope.launch {
        pause(700.milliseconds)
        playLiveTurn(sessionID = sessionID)
    }
}

private suspend fun DemoGateway.playLiveTurn(sessionID: String) {
    val words = listOf("All", " 100", " runs", " passed.", " The", " shared", " clock", " was", " the",
                       " only", " source", " of", " the", " flake.")
    emit(sessionID = sessionID, blockID = "tool-5",
         body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Bash", kind = ToolKind.shell,
                                                          title = "pytest tests/test_auth.py --count 100 -q",
                                                          status = ToolStatus.succeeded,
                                                          output = "..................................... 100%\n100 passed in 52.4s",
                                                          durationMS = 52_400)))
    for ((index, word) in words.withIndex()) {
        if (isCancelled()) return
        pause(90.milliseconds)
        emit(sessionID = sessionID, blockID = "a-live",
             body = SessionEventBody.AssistantText(StreamTextPayload(delta = word, done = false)))
        if (index == words.size - 1) {
            emit(sessionID = sessionID, blockID = "a-live",
                 body = SessionEventBody.AssistantText(StreamTextPayload(text = words.joinToString(""), done = true)))
        }
    }
    emit(sessionID = sessionID, body = SessionEventBody.Todos(TodosPayload(items = listOf(
        TodoItem(id = "1", text = "Reproduce the flake", status = TodoStatus.completed),
        TodoItem(id = "2", text = "Isolate the shared clock", status = TodoStatus.completed),
        TodoItem(id = "3", text = "Guard refresh with the session lock", status = TodoStatus.completed),
        TodoItem(id = "4", text = "Re-run the suite 100 times", status = TodoStatus.completed),
    ))))
    // Amendment A43: under `--demo-queue` the turn runs on, so what waits behind it stays in the
    // line for as long as it is being edited.
    if (holdsQueue) return
    emit(sessionID = sessionID, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
        turnID = "demo-turn-1", stopReason = StopReason.completed, durationMS = 252_000)))
    emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.idle)))
    update(sessionID = sessionID) { it.copy(state = SessionState.idle, turn = null, todos = TodoCounts(total = 4, done = 4)) }
    deliverNext(sessionID = sessionID)
}

internal fun DemoGateway.startReplyScript(sessionID: String) {
    scripted?.cancel()
    // A terminal the device types into replies slowly enough for Stop to be tapped while it runs
    // (A42); the daemon's reply is quick as before.
    val typed = attempt { session(sessionID) }?.let { isTypedInto(it) } == true
    val pace = if (typed) 450.milliseconds else 110.milliseconds
    scripted = scope.launch {
        val blockID = "a-${uuidString().take(6)}"
        for (word in listOf("Got", " it", " —", " running", " that", " now.")) {
            if (isCancelled()) return@launch
            pause(pace)
            emit(sessionID = sessionID, blockID = blockID,
                 body = SessionEventBody.AssistantText(StreamTextPayload(delta = word, done = false)))
        }
        emit(sessionID = sessionID, blockID = blockID,
             body = SessionEventBody.AssistantText(StreamTextPayload(text = "Got it — running that now.", done = true)))
        emit(sessionID = sessionID, body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
            turnID = "demo-turn", stopReason = StopReason.completed, durationMS = 900)))
        emit(sessionID = sessionID, body = SessionEventBody.Status(StatusPayload(state = SessionState.idle)))
        finishTurn(sessionID = sessionID)
    }
}

internal fun DemoGateway.finishTurn(sessionID: String) {
    update(sessionID = sessionID) { it.copy(state = SessionState.idle, turn = null) }
    deliverNext(sessionID = sessionID)
}

internal suspend fun DemoGateway.runPairingScript(code: String) {
    for (step in listOf(PairingStep.waiting, PairingStep.enrolled, PairingStep.online, PairingStep.agents)) {
        if (isCancelled()) return
        pause(900.milliseconds)
        val device = if (step == PairingStep.agents) devices.firstOrNull() else null
        continuation.trySend(GatewayEvent.Frame(AppFrame.PairingProgress(PairingProgress(code = code, step = step,
                                                                                         device = device))))
    }
}

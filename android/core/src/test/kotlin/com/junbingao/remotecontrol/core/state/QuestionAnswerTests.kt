package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire case of RCCore's suite of this name (`attributionDecodes`) is in
// `protocol/QuestionAnswerTests.kt`, and the demo's own (`demoCarriesATerminalAnswer`) in
// `demo/QuestionAnswerTests.kt`.

/**
 * Amendment A20: a question Claude Code asks in an attached session can be answered from here. The
 * card and the composer submit one set of answers, the message field is the free-text answer to the
 * question still waiting for one, and a question answered in the terminal comes back saying so.
 */
class QuestionAnswerTests {
    private val headline = QuestionItem(
        id = "q1", prompt = "What should the headline say?",
        options = listOf(QuestionOption(id = "remote", label = "Remote control"),
                         QuestionOption(id = "phone", label = "From your phone")),
        allowText = true,
    )
    private val choices = QuestionItem(
        id = "q2", prompt = "Which sections?",
        options = listOf(QuestionOption(id = "gateway", label = "Gateway"), QuestionOption(id = "apps", label = "Apps")),
        multi = true,
    )

    private fun payload(questions: List<QuestionItem>): QuestionPayload = QuestionPayload(requestID = "req-1", questions = questions)

    private fun store(tasks: CoroutineScope, control: SessionControl = SessionControl.shared,
                      question: QuestionPayload? = null): Pair<ChatStore, AnswerChannel> {
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = if (question == null) SessionState.idle else SessionState.needsInput, control = control)
        val channel = AnswerChannel()
        val chat = ChatStore(session = session, channel = channel, tasks = tasks)
        chat.agent = DemoFixtures.claude
        if (question != null) {
            chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d",
                                               event = SessionEvent(seq = 1, ts = 1, kind = SessionEvent.questionKind,
                                                                    blockID = "q-1", body = SessionEventBody.Question(question))))
        }
        return chat to channel
    }

    // What the card holds

    /** The composer's draft answers the first question nothing was chosen for. */
    @Test
    fun draftFillsTheFirstUnansweredQuestion() {
        val questions = listOf(choices, headline)
        val draft = QuestionDraft(requestID = "req-1").toggle("gateway", of = choices)
        assertEquals("q1", draft.firstUnanswered(questions)?.id)

        val answers = draft.answers(questions, composing = "  Remote control for terminals  ")
        assertEquals(QuestionAnswer.Options(listOf("gateway")), answers?.get("q2"))
        assertEquals(QuestionAnswer.Text("Remote control for terminals"), answers?.get("q1"))
    }

    /** A draft with nowhere to go is left in the field. */
    @Test
    fun draftIsKeptWhenItCannotBeAnswered() {
        var draft = QuestionDraft(requestID = "req-1")
        // The one question waiting takes options and no words.
        assertNull(draft.answers(listOf(choices), composing = "something"))
        // Nothing typed is nothing to submit.
        assertNull(draft.answers(listOf(headline), composing = "   "))
        // Every question already answered on the card: the card's own Submit.
        draft = draft.toggle("remote", of = headline)
        assertNull(draft.answers(listOf(headline), composing = "and something else"))
    }

    /**
     * A secret question is never answered from the message field. The composer's draft is written to
     * disk and restored on the next launch, so a value the card promises is neither stored nor logged
     * cannot be typed there. Its own masked field is the only way in.
     */
    @Test
    fun secretsStayOffTheDraft() {
        val secret = QuestionItem(id = "q1", prompt = "Passphrase?", allowText = true, secret = true)
        val draft = QuestionDraft(requestID = "req-1")
        assertEquals("q1", draft.firstUnanswered(listOf(secret))?.id)
        assertNull(draft.answers(listOf(secret), composing = "hunter2"))
    }

    /** A single-choice question keeps one option and a multi-select keeps several. */
    @Test
    fun selectionRules() {
        var draft = QuestionDraft(requestID = "req-1")
        draft = draft.toggle("remote", of = headline)
        draft = draft.toggle("phone", of = headline)
        assertEquals(QuestionAnswer.Options(listOf("phone")), draft.answers(listOf(headline))["q1"])
        draft = draft.toggle("phone", of = headline)
        assertTrue(draft.answers(listOf(headline)).isEmpty(), "choosing it again clears it")

        draft = draft.toggle("apps", of = choices)
        draft = draft.toggle("gateway", of = choices)
        assertEquals(QuestionAnswer.Options(listOf("gateway", "apps")), draft.answers(listOf(choices))["q2"],
                     "in the order the question offered them, not the order they were tapped")
    }

    // The composer

    /** A pending question turns the composer into an answer. */
    @Test
    fun composerAnswersWhileAQuestionIsPending() = runTest {
        val (chat, _) = store(backgroundScope, question = payload(listOf(headline)))
        assertEquals("req-1", chat.pendingQuestion?.requestID)
        assertEquals("Waiting for your answer", chat.statusLine)
        assertTrue(chat.allowsAnswers, "an attached session takes the answer (A20)")
    }

    /** Submitting the draft answers the question and clears the field. */
    @Test
    fun submittingTheDraft() = runTest {
        val (chat, channel) = store(backgroundScope, question = payload(listOf(choices, headline)))
        chat.choose("apps", of = choices, question = payload(listOf(choices, headline)))
        chat.draft = "Remote control for your terminal agents"
        chat.answerDraft()

        assertEquals(listOf("session.answer"), channel.requests.map { it.type })
        val body = channel.requests.firstOrNull()?.body
        assertEquals("req-1", body?.get("request_id")?.stringValue)
        val answers = assertNotNull(body?.get("answers")).decodeAnswers()
        assertEquals(QuestionAnswer.Options(listOf("apps")), answers["q2"])
        assertEquals(QuestionAnswer.Text("Remote control for your terminal agents"), answers["q1"])
        assertEquals("", chat.draft)
        assertTrue(chat.timeline.roots.all { it.pending == null }, "no optimistic row: an answer is not a message")
    }

    /** A draft that answers nothing is neither sent nor lost. */
    @Test
    fun aDraftWithNowhereToGoStays() = runTest {
        val (chat, channel) = store(backgroundScope, question = payload(listOf(choices)))
        chat.draft = "the headline"
        chat.answerDraft()
        assertTrue(channel.requests.isEmpty(), "nothing is submitted")
        assertEquals("the headline", chat.draft, "and the words stay in the field")
    }

    /** With no question pending the composer is an ordinary composer. */
    @Test
    fun noQuestionNoAnswer() = runTest {
        val (chat, channel) = store(backgroundScope)
        assertNull(chat.pendingQuestion)
        assertNull(chat.statusLine)
        chat.draft = "hello"
        chat.answerDraft()
        assertTrue(channel.requests.isEmpty())
        assertEquals("hello", chat.draft)
    }

    /** A session the terminal holds outright answers nothing from here. */
    @Test
    fun terminalSessionsTakeNoAnswer() = runTest {
        val (chat, _) = store(backgroundScope, control = SessionControl.terminal, question = payload(listOf(headline)))
        assertFalse(chat.allowsAnswers)
        assertNull(chat.pendingQuestion)
    }

    /** A resolved question is no longer the pending one. */
    @Test
    fun resolvingClearsTheComposer() = runTest {
        val (chat, _) = store(backgroundScope, question = payload(listOf(headline)))
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = SessionEvent(
            seq = 2, ts = 2, kind = SessionEvent.questionKind, blockID = "q-1",
            body = SessionEventBody.Question(QuestionPayload(requestID = "req-1", questions = listOf(headline),
                                                             status = RequestStatus.resolved,
                                                             answers = mapOf("q1" to QuestionAnswer.Options(listOf("remote"))),
                                                             by = EventSource.terminal)))))
        assertNull(chat.pendingQuestion)
        assertEquals(EventSource.terminal, chat.timeline.entry(id = "q-1")?.question?.by)
        assertNull(chat.statusLine)
    }

    private fun JsonElement.decodeAnswers(): Map<String, QuestionAnswer> =
        runCatching { decode(MapSerializer(String.serializer(), QuestionAnswer.serializer())) }.getOrDefault(emptyMap())

    /** A channel that records what was asked of it and agrees to everything. */
    private class AnswerChannel : InertChannel() {
        val requests = mutableListOf<GatewayRequest>()

        override suspend fun request(request: GatewayRequest): JsonElement {
            requests.add(request)
            return JSONValue.emptyObject
        }
    }
}

package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The wire case of RCCore's suite of this name (`attributionDecodes`) is in
// `protocol/QuestionAnswerTests.kt`. The composer's cases, whose store answers for the demo's Claude,
// and `demoCarriesATerminalAnswer` arrive with the demo gateway.

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
}

package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem

/**
 * What a pending question card is holding before it is submitted: the options chosen on the card,
 * and the free text typed into a question's own field.
 *
 * Amendment A20 gives the composer a second way to answer the same question — the draft in the
 * message field is the free-text answer to the first question still waiting for one — so the card
 * and the composer have to be looking at one copy of this rather than at two that can disagree.
 *
 * A value, as RCCore's is: [toggle] and [setText] answer the draft they make and leave this one as
 * it was, so the copy a store publishes never changes under a screen that is reading it.
 */
@ConsistentCopyVisibility
data class QuestionDraft private constructor(
    val requestID: String,
    private val selections: Map<String, Set<String>>,
    private val typed: Map<String, String>,
) {
    constructor(requestID: String) : this(requestID, emptyMap(), emptyMap())

    fun isChosen(optionID: String, questionID: String): Boolean = selections[questionID]?.contains(optionID) ?: false

    fun text(questionID: String): String = typed[questionID] ?: ""

    /**
     * A single-choice question keeps at most one option, and choosing the option it already has
     * clears it: nothing is answered by accident.
     */
    fun toggle(optionID: String, of: QuestionItem): QuestionDraft {
        val chosen = selections[of.id] ?: emptySet()
        val next = if (of.multi) {
            if (optionID in chosen) chosen - optionID else chosen + optionID
        } else {
            if (optionID in chosen) emptySet() else setOf(optionID)
        }
        return copy(selections = selections + (of.id to next))
    }

    fun setText(value: String, questionID: String): QuestionDraft = copy(typed = typed + (questionID to value))

    fun hasSelection(questionID: String): Boolean = selections[questionID]?.isNotEmpty() ?: false

    fun hasAnswer(questionID: String): Boolean = hasSelection(questionID) || text(questionID).trimmed.isNotEmpty()

    /** True when the card can be submitted from the card itself. */
    fun answersEveryQuestion(questions: List<QuestionItem>): Boolean = questions.all { hasAnswer(it.id) }

    /**
     * Where the composer's draft goes: the first question nothing has been chosen or typed for.
     * Null when the card already answers them all, which is the case the card's own Submit is for.
     */
    fun firstUnanswered(questions: List<QuestionItem>): QuestionItem? = questions.firstOrNull { !hasAnswer(it.id) }

    /**
     * What the card would send: chosen ids in the order the question offered them, or the text
     * typed for a question nothing was chosen for.
     */
    fun answers(questions: List<QuestionItem>): Map<String, QuestionAnswer> {
        val answers = LinkedHashMap<String, QuestionAnswer>()
        for (question in questions) {
            if (hasSelection(question.id)) {
                answers[question.id] = QuestionAnswer.Options(question.options.map { it.id }.filter { isChosen(it, question.id) })
            } else {
                val value = text(question.id).trimmed
                if (value.isNotEmpty()) answers[question.id] = QuestionAnswer.Text(value)
            }
        }
        return answers
    }

    /**
     * What the composer sends: everything the card holds, plus this draft as the free-text answer
     * to the first question still waiting for one.
     *
     * Null means the draft has nowhere to go — every question is answered already, or the one
     * waiting takes options and no words — and the message field keeps what was typed rather than
     * losing it to a request nobody can accept.
     *
     * A secret question is null too, however it is configured. The composer's draft is written to
     * disk as a draft and restored on the next launch, and a value the card promises is neither
     * stored nor logged cannot be typed there. Its own masked field on the card is the only way in.
     */
    fun answers(questions: List<QuestionItem>, composing: String): Map<String, QuestionAnswer>? {
        val text = composing.trimmed
        val unanswered = firstUnanswered(questions)
        if (text.isEmpty() || unanswered == null || !unanswered.allowText || unanswered.secret) return null
        return answers(questions) + (unanswered.id to QuestionAnswer.Text(text))
    }
}

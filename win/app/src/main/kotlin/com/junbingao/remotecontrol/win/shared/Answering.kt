package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.state.QuestionDraft
import com.junbingao.remotecontrol.core.state.trimmed

/**
 * `web/src/features/chat/answering.ts` — amendment A20: how a pending question turns into a
 * `session.answer`.
 *
 * A question is answered where you are. The card takes options and its own free-text fields; the
 * composer takes a sentence. Both read one draft — the chat store's `QuestionDraft` — and these
 * rules say how it is submitted, pure, so the card, the composer and the tests read one
 * description of it.
 */
object Answering {
    /** What one question already holds: the options picked, else the text typed. */
    internal fun answer(question: QuestionItem, draft: QuestionDraft): QuestionAnswer? {
        if (draft.hasSelection(question.id)) {
            return QuestionAnswer.Options(question.options.map { it.id }.filter { draft.isChosen(it, question.id) })
        }
        val typed = draft.text(question.id).trimmed
        return if (typed.isEmpty()) null else QuestionAnswer.Text(typed)
    }

    /** The answers a card submits on its own, with nothing left unanswered. */
    fun cardAnswers(questions: List<QuestionItem>, draft: QuestionDraft): Map<String, QuestionAnswer> {
        val answers = LinkedHashMap<String, QuestionAnswer>()
        for (question in questions) answer(question, draft)?.let { answers[question.id] = it }
        return answers
    }

    /** True once every question on the card carries an answer of its own. */
    fun cardComplete(questions: List<QuestionItem>, draft: QuestionDraft): Boolean = questions.all { answer(it, draft) != null }

    /**
     * The submission for a composer draft, or null when the draft has nowhere to go: every question
     * already carries a selection, or the first one without a selection refuses free text. The
     * draft is then left where it is.
     *
     * The draft answers the first question with no option selected. Text typed on the card does not
     * take that slot, so the composer's sentence replaces it rather than being ignored, and the
     * other questions keep what they hold.
     */
    fun composeAnswer(questions: List<QuestionItem>, draft: QuestionDraft, text: String): Map<String, QuestionAnswer>? {
        val value = text.trimmed
        if (value.isEmpty()) return null
        val target = questions.firstOrNull { !draft.hasSelection(it.id) } ?: return null
        if (!target.allowText) return null
        val answers = LinkedHashMap(cardAnswers(questions, draft))
        answers[target.id] = QuestionAnswer.Text(value)
        return answers
    }
}

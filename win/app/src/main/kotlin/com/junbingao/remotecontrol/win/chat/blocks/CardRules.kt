package com.junbingao.remotecontrol.win.chat.blocks

import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.win.strings.S

/** The words and orders `ApprovalCard.tsx` and `QuestionCard.tsx` work out. */
object CardRules {
    /**
     * The agent's own options, accept first and reject last, the rest in the order given between
     * them. Nothing assumes any id exists.
     */
    fun ordered(options: List<ApprovalOption>): List<ApprovalOption> {
        fun rank(style: OptionStyle): Int = when (style) {
            OptionStyle.primary -> 0
            OptionStyle.danger -> 2
            else -> 1
        }
        return options.withIndex()
            .sortedWith(compareBy({ rank(it.value.style) }, { it.index }))
            .map { it.value }
    }

    /**
     * What a resolved or expired approval says instead of its buttons. A11 §5.7: a request
     * answered in the terminal resolves with the reserved `elsewhere` id, which matches none of the
     * options on purpose.
     */
    fun approvalResult(approval: ApprovalPayload): String {
        if (approval.status == RequestStatus.expired) return S.chat.approvalExpired
        val decision = approval.decision ?: return S.chat.questionResolved
        if (decision.optionID == ApprovalPayload.elsewhereOptionID) return S.chat.approvalElsewhere
        val chosen = approval.options.firstOrNull { it.id == decision.optionID }?.label ?: decision.optionID
        return S.chat.approvalResolved(decision.by.rawValue, chosen)
    }

    /**
     * A20: a resolved question says who answered it, the way an approval answered in the terminal
     * does. A device that does not report `by` says only that the question was answered.
     */
    fun questionResult(question: QuestionPayload): String {
        if (question.status == RequestStatus.expired) return S.chat.questionExpired
        if (question.by == EventSource.terminal) return S.chat.questionAnsweredInTerminal
        return S.chat.questionResolved
    }

    /** Whether a resolved question's answer picked this option. */
    fun answerIncludes(answer: QuestionAnswer?, optionID: String): Boolean =
        answer is QuestionAnswer.Options && optionID in answer.ids

    /**
     * What a resolved card shows in its free-text field: the answer that was given, typed here or
     * in the composer. A secret answer is never echoed back — the card said it is not stored, and
     * neither is it redrawn.
     */
    fun freeText(answer: QuestionAnswer?, secret: Boolean): String {
        if (secret || answer !is QuestionAnswer.Text) return ""
        return answer.value
    }
}

/** `DiffView.tsx`: how one line of a unified patch is coloured. */
enum class DiffLineKind {
    meta, hunk, add, del, context;

    companion object {
        operator fun invoke(line: String): DiffLineKind = when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") -> meta
            line.startsWith("@@") -> hunk
            line.startsWith("+") -> add
            line.startsWith("-") -> del
            else -> context
        }
    }
}

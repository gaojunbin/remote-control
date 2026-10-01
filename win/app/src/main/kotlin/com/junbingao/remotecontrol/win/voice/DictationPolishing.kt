package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.core.transport.PolishContextItem
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishStrength

/**
 * A29 — what a dictation left in the field: the draft the mic button was pressed on, and the words
 * the recogniser produced. The answer replaces the second and never touches the first: text the
 * person typed is theirs.
 */
data class PolishSpan(val base: String, val dictated: String) {
    /** The draft as the recogniser left it. */
    val dictatedDraft: String get() = DictationDraft.merge(base, dictated)

    /** The same draft with the dictated words replaced by the model's. */
    fun polishedDraft(polished: String): String = DictationDraft.merge(base, polished)
}

/**
 * `web/src/features/voice/polish.ts`, the pure half of dictation polish: what the model is told,
 * and how its answer meets the draft.
 *
 * The iPhone's `DictationPolish` joins a dictation to its draft on a new line; the web joins it
 * after a space (`draft.ts`), and this app writes the field the web's way, so the answer has to be
 * matched the same way. The rules the contract fixes — which words go, twenty messages of
 * conversation, four thousand characters each — are the core's (`DictationPolish.canPolish`,
 * `DictationPolish.context`), which say what the web's do.
 */
object DictationPolishing {
    /**
     * The body of `POST /api/polish` for one dictation. A44: the gateway transcribed the words and
     * detected their language, so the model's hint is `auto`.
     */
    fun request(span: PolishSpan, model: String, strength: PolishStrength, context: List<PolishContextItem>): PolishRequest =
        PolishRequest(text = span.dictated, model = model, strength = strength, language = "auto", context = context)

    /**
     * The draft the answer produces, or null when the field has moved on — the person typed, sent,
     * or dictated again, and their words win. An answer that is empty is no answer; one that
     * changes nothing says nothing.
     */
    fun applyPolished(current: String, span: PolishSpan, polished: String): String? {
        val text = polished.trimmed
        if (text.isEmpty() || current != span.dictatedDraft) return null
        val next = span.polishedDraft(text)
        return if (next == current) null else next
    }

    /**
     * Undo: the words as they were dictated, or null when the field no longer holds what the model
     * wrote and there is nothing to put back.
     */
    fun undoPolished(current: String, span: PolishSpan, polished: String): String? =
        if (current == span.polishedDraft(polished)) span.dictatedDraft else null
}

/**
 * A29 — where a dictation is between the recogniser and the model: nowhere, waiting for the
 * answer, holding one that can still be undone, or told that the request failed and the words were
 * left alone.
 */
sealed interface DictationPolishState {
    data object Idle : DictationPolishState

    data object Polishing : DictationPolishState

    data class Polished(val span: PolishSpan, val text: String) : DictationPolishState

    data object Failed : DictationPolishState

    val progress: PolishProgress
        get() = when (this) {
            Idle -> PolishProgress.idle
            Polishing -> PolishProgress.polishing
            is Polished -> PolishProgress.polished
            Failed -> PolishProgress.failed
        }
}

/**
 * The four phases of `DictationPolishState`, without what they carry: the half of the primary
 * slot's rule that polish decides.
 */
enum class PolishProgress { idle, polishing, polished, failed }

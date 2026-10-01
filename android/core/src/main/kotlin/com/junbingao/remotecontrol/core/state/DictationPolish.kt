package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.transport.PolishContextItem
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishRole
import com.junbingao.remotecontrol.core.transport.PolishStrength

/**
 * Amendment A29 — what a dictation left in the composer: the draft the microphone was tapped on,
 * and the words the recogniser produced.
 *
 * Polishing replaces the second and never touches the first, which is why both halves are kept
 * rather than the finished draft: text the person typed is theirs, and no model answer is allowed
 * to land on it.
 */
data class DictationSpan(
    /** The draft as it was before dictation started. Never touched. */
    val base: String,
    /** The words the recogniser produced, which are in the field now. */
    val dictated: String,
) {
    /** The draft as the recogniser left it. */
    val dictatedDraft: String get() = merge(base, dictated)

    /** The same draft with the dictated words replaced by the model's answer. */
    fun polishedDraft(polished: String): String = merge(base, polished)

    companion object {
        /** How a dictation joins the draft it started from. One rule in one place, so a span can rebuild both drafts exactly as the composer wrote them. */
        fun merge(base: String, text: String): String {
            if (base.isEmpty()) return text
            val endsInWhitespace = base.characters().lastOrNull()?.let(::isWhitespace) == true
            return base + (if (endsInWhitespace) "" else "\n") + text
        }
    }
}

/**
 * The pure half of dictation polish: what the model is told, and how its answer meets the draft.
 *
 * Everything here is a value in and a value out, so the rules the contract fixes — twenty messages,
 * oldest first, four thousand characters each, and a replacement that touches the dictated words
 * alone — are testable without a field, a socket or a gateway.
 */
object DictationPolish {
    /** `PolishRequest.context` takes at most this many messages (schema `maxItems`). */
    const val contextLimit = 20

    /** Each context message is trimmed by the app to this length (schema `maxLength`). */
    const val contextTextLimit = 4000

    /** `PolishRequest.text` (schema `maxLength`). A longer dictation is not polished. */
    const val textLimit = 8192

    /**
     * Whether these words can be sent at all: an empty dictation has nothing to polish, and one past
     * the contract's limit would only earn a `bad_request`.
     */
    fun canPolish(text: String): Boolean = text.trimmed.isNotEmpty() && text.codePointCount(0, text.length) <= textLimit

    /**
     * The conversation the model is given: the messages the app already shows, user and assistant
     * alike, oldest first, the last twenty of them, each trimmed. Unconfirmed sends (A12) count —
     * they are on screen, and the newest of them is usually what the dictation is answering.
     */
    fun context(timeline: Timeline): List<PolishContextItem> {
        val items = mutableListOf<PolishContextItem>()
        for (entry in timeline.entries) {
            when (val body = entry.body) {
                is SessionEventBody.UserMessage -> items.add(PolishContextItem(role = PolishRole.user, text = body.payload.text))
                is SessionEventBody.AssistantText -> items.add(PolishContextItem(role = PolishRole.assistant, text = entry.text))
                else -> continue
            }
        }
        for (message in timeline.optimistic) items.add(PolishContextItem(role = PolishRole.user, text = message.text))
        return items.map { PolishContextItem(role = it.role, text = trim(it.text)) }
            .filter { it.text.isNotEmpty() }
            .takeLast(contextLimit)
    }

    /**
     * The body of `POST /api/polish` for one dictation. Amendment A44: the language is the hint the
     * contract names — `auto` for words the gateway transcribed, the code the phone listened for —
     * so it is sent as given (`DictationLanguage.polishHint`).
     */
    fun request(span: DictationSpan, model: String, strength: PolishStrength, language: String,
                context: List<PolishContextItem>): PolishRequest =
        PolishRequest(text = span.dictated, model = model, strength = strength, language = language, context = context)

    /**
     * The draft the answer produces, or null when the field has moved on: the person typed, sent, or
     * started dictating again, and their words win. An answer that is empty is no answer, and reads
     * as a failure.
     */
    fun applyPolished(current: String, span: DictationSpan, polished: String): String? {
        val text = polished.trimmed
        if (text.isEmpty() || current != span.dictatedDraft) return null
        val next = span.polishedDraft(text)
        return if (next == current) null else next
    }

    /** Undo: the words as they were dictated, or null when the field no longer holds what the model wrote, in which case there is nothing to put back. */
    fun undoPolished(current: String, span: DictationSpan, polished: String): String? {
        if (current != span.polishedDraft(polished)) return null
        return span.dictatedDraft
    }

    private fun trim(text: String): String {
        val trimmed = text.trimmed
        if (trimmed.codePointCount(0, trimmed.length) <= contextTextLimit) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, contextTextLimit))
    }
}

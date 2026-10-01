package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.DictationPolish
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import com.junbingao.remotecontrol.core.state.Timeline
import com.junbingao.remotecontrol.core.transport.PolishContextItem
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishRole
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `web/tests/polish.test.ts` — A29, the pure half of dictation polish: the conversation the model is
 * given, and the replacement that touches the dictated words and nothing else.
 */
class VoicePolishTests {
    private fun timeline(bodies: List<Pair<String, SessionEventBody>>): Timeline {
        val timeline = Timeline()
        for ((index, entry) in bodies.withIndex()) {
            val (kind, body) = entry
            timeline.apply(SessionEvent(seq = index + 1, ts = (index + 1).toLong(), kind = kind, blockID = "block-${index + 1}", body = body))
        }
        return timeline
    }

    private fun said(text: String) = SessionEvent.userMessageKind to SessionEventBody.UserMessage(UserMessagePayload(text = text))

    private fun answered(text: String) = SessionEvent.assistantTextKind to SessionEventBody.AssistantText(StreamTextPayload(text = text, done = true))

    private fun thought(text: String) = SessionEvent.thinkingKind to SessionEventBody.Thinking(StreamTextPayload(text = text, done = true))

    @Test
    fun theUserAndAssistantMessagesGoOldestFirstAndNothingElse() {
        val context = DictationPolish.context(timeline(listOf(
            said("Make the session status dot pulse while a turn is running."),
            thought("The dot is drawn by StatusDot."),
            answered("Done: the dot now breathes while the state is running."),
        )))
        assertEquals(listOf(
            PolishContextItem(role = PolishRole.user, text = "Make the session status dot pulse while a turn is running."),
            PolishContextItem(role = PolishRole.assistant, text = "Done: the dot now breathes while the state is running."),
        ), context)
    }

    @Test
    fun theLastTwentyMessagesAreTheOnesNearestTheDictation() {
        val context = DictationPolish.context(timeline((0 until 15).flatMap { listOf(said("ask $it"), answered("answer $it")) }))
        assertEquals(DictationPolish.contextLimit, context.size)
        assertEquals(PolishContextItem(role = PolishRole.user, text = "ask 5"), context.first())
        assertEquals(PolishContextItem(role = PolishRole.assistant, text = "answer 14"), context.last())
    }

    @Test
    fun aLongMessageIsTrimmedAndAnEmptyOneDropped() {
        val context = DictationPolish.context(timeline(listOf(said("x".repeat(DictationPolish.contextTextLimit + 500)), answered("   "))))
        assertEquals(1, context.size)
        assertEquals(DictationPolish.contextTextLimit, context.first().text.length)
    }

    @Test
    fun aMessageStillOnItsWayCounts() {
        val timeline = timeline(listOf(answered("Which test?")))
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "The auth refresh one."))
        assertEquals(listOf(
            PolishContextItem(role = PolishRole.assistant, text = "Which test?"),
            PolishContextItem(role = PolishRole.user, text = "The auth refresh one."),
        ), DictationPolish.context(timeline))
    }

    @Test
    fun theRequestCarriesTheDictatedSpanTheChoicesAndAuto() {
        val request = DictationPolishing.request(
            PolishSpan(base = "note: ", dictated = "um the green blinking thing"),
            model = "gpt-4.1-mini", strength = PolishStrength.strong,
            context = listOf(PolishContextItem(role = PolishRole.user, text = "Make the dot pulse.")),
        )
        assertEquals(PolishRequest(text = "um the green blinking thing", model = "gpt-4.1-mini", strength = PolishStrength.strong, language = "auto",
                                   context = listOf(PolishContextItem(role = PolishRole.user, text = "Make the dot pulse."))), request)
    }

    @Test
    fun anEmptyOrOverlongDictationIsNotPolished() {
        assertFalse(DictationPolish.canPolish("   "))
        assertTrue(DictationPolish.canPolish("a".repeat(8192)))
        assertFalse(DictationPolish.canPolish("a".repeat(8193)))
    }

    private val span = PolishSpan(base = "after lunch", dictated = "um run the the auth suite")

    @Test
    fun theAnswerReplacesTheDictatedSpanAndLeavesWhatWasTyped() {
        assertEquals("after lunch um run the the auth suite", span.dictatedDraft)
        val next = DictationPolishing.applyPolished(current = span.dictatedDraft, span = span, polished = "Run the auth suite.")
        assertEquals("after lunch Run the auth suite.", next)
        assertEquals(span.polishedDraft("Run the auth suite."), next)
    }

    @Test
    fun whitespaceIsTrimmedAndAnEmptyAnswerIsNoAnswer() {
        assertEquals("after lunch Run the auth suite.",
                     DictationPolishing.applyPolished(current = span.dictatedDraft, span = span, polished = "  Run the auth suite.\n"))
        assertNull(DictationPolishing.applyPolished(current = span.dictatedDraft, span = span, polished = "   "))
    }

    @Test
    fun aFieldThePersonHasMovedOnIsLeftAlone() {
        assertNull(DictationPolishing.applyPolished(current = "something else entirely", span = span, polished = "Run the auth suite."))
        assertNull(DictationPolishing.applyPolished(current = span.dictatedDraft, span = span, polished = span.dictated))
    }

    @Test
    fun undoPutsTheDictatedWordsBackOnlyFromThePolishedDraft() {
        val polished = span.polishedDraft("Run the auth suite.")
        assertEquals(span.dictatedDraft, DictationPolishing.undoPolished(current = polished, span = span, polished = "Run the auth suite."))
        assertNull(DictationPolishing.undoPolished(current = "edited since", span = span, polished = "Run the auth suite."))
    }
}

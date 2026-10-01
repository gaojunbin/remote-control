package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.arrayValue
import com.junbingao.remotecontrol.core.protocol.objectValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import com.junbingao.remotecontrol.win.shared.SessionOptions
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/Composer.test.tsx`, `composer-errors.test.tsx`, `optimistic-send.test.tsx`: what Send sends, and what comes back. */
class ComposerSendTests {
    @Test
    fun anIdleSessionIsSentAuto() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.setDraft("run the suite")
        assertEquals(S.composer.send, harness.composer.primaryLabel)
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals("auto", sent["mode"]?.stringValue)
        assertEquals("run the suite", sent["text"]?.stringValue)
    }

    @Test
    fun aRunningTurnStillSendsAutoAndTheButtonSaysQueue() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(state = SessionState.running))
        harness.composer.setDraft("and then this")
        assertEquals(S.composer.queue, harness.composer.primaryLabel)
        assertEquals(S.composer.placeholderQueued, harness.composer.placeholder)
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        assertEquals("auto", harness.channel.sent("session.send").first()["mode"]?.stringValue)
    }

    @Test
    fun aSteeringAgentsButtonStaysSend() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(agent = "codex", state = SessionState.running), agent = DemoFixtures.codex)
        assertEquals(S.composer.send, harness.composer.primaryLabel)
        assertEquals(S.composer.placeholderSteer, harness.composer.placeholder)
    }

    @Test
    fun interruptAndSendIsOfferedOnlyWhileATurnRuns() = runTest {
        assertFalse(ComposerHarness(this).composer.showsSendMenu)
        val harness = ComposerHarness(this, session = ComposerFixture.session(state = SessionState.running))
        assertTrue(harness.composer.showsSendMenu)
        harness.composer.setDraft("stop and do this")
        harness.composer.submit(SendMode.interrupt)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        assertEquals("interrupt", harness.channel.sent("session.send").first()["mode"]?.stringValue)
    }

    @Test
    fun anEmptyMessageIsNotSent() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.setDraft("   \n ")
        assertTrue(harness.composer.primaryDisabled)
        harness.composer.submit(SendMode.auto)
        pass(50)
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    /** A12: the field is empty and the message on its way before the reply. */
    @Test
    fun theFieldIsClearedBeforeTheGatewayAnswers() = runTest {
        val harness = ComposerHarness(this)
        val gate = ComposerGate()
        harness.channel.answer("session.send") {
            gate.await()
            JSONValue.encode(SendResult(accepted = SendAcceptance.sent))
        }
        harness.composer.setDraft("quick one")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.chat.draft.isEmpty() })
        assertEquals(listOf("quick one"), harness.chat.timeline.optimistic.map { it.text })
        gate.release()
    }

    @Test
    fun aRefusedSendHandsTheWordsAndFilesBackAndSaysWhyUnderTheField() = runTest {
        val harness = ComposerHarness(this)
        harness.channel.answer("session.send") { throw ComposerFixture.refusal(GatewayErrorCode.badRequest, "the message was refused") }
        val file = ComposerAttachment(name = "notes.txt", mime = "text/plain", data = "hi".toByteArray())
        harness.host.drafts.add(listOf(file), to = harness.composer.key)
        harness.composer.setDraft("try this")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals(listOf("the message was refused"), harness.composer.errors)
        assertEquals("try this", harness.chat.draft)
        assertEquals(listOf(file), harness.composer.attachments)
        // The line under the field says it; the page's banner does not.
        assertNull(harness.chat.errorMessage)
    }

    @Test
    fun aNewerDraftWinsOverAnOlderRefusal() = runTest {
        val harness = ComposerHarness(this)
        val gate = ComposerGate()
        harness.channel.answer("session.send") {
            gate.await()
            throw ComposerFixture.refusal(GatewayErrorCode.conflict, "busy")
        }
        harness.composer.setDraft("first")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.chat.draft.isEmpty() })
        harness.composer.userTyped("second")
        gate.release()
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals("second", harness.chat.draft)
    }

    @Test
    fun aMessagePastTheLimitIsRefusedHere() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.setDraft("x".repeat(AttachmentLimits.maxTextBytes + 1))
        harness.composer.submit(SendMode.auto)
        assertEquals(listOf(S.composer.textTooLong), harness.composer.errors)
        pass(50)
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    @Test
    fun filesGoAloneWhenThereAreNoWords() = runTest {
        val harness = ComposerHarness(this)
        harness.host.drafts.add(listOf(ComposerAttachment(name = "a.png", mime = "image/png", data = ByteArray(4))), to = harness.composer.key)
        assertFalse(harness.composer.primaryDisabled)
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals("", sent["text"]?.stringValue)
        assertEquals(1, sent["attachments"]?.arrayValue?.size)
        assertTrue(harness.composer.attachments.isEmpty())
    }

    @Test
    fun aTerminalSessionTakesNothingAndSaysWhoHoldsIt() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(state = SessionState.running, control = SessionControl.terminal))
        assertTrue(harness.composer.gates.disabled)
        assertEquals(S.composer.placeholderTerminal, harness.composer.placeholder)
        harness.composer.setDraft("hello")
        assertTrue(harness.composer.primaryDisabled)
        harness.composer.submit(SendMode.auto)
        pass(50)
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    @Test
    fun anOfflineDeviceDisablesTheComposerAndTheMic() = runTest {
        val harness = ComposerHarness(this)
        harness.host.online = false
        assertEquals(S.composer.placeholderOffline, harness.composer.placeholder)
        assertFalse(harness.composer.voiceEnabled)
        harness.host.online = true
        harness.host.sttEnabled = false
        assertFalse(harness.composer.voiceEnabled)
    }

    /** A20: while a question waits the field is its free-text answer. */
    @Test
    fun aPendingQuestionIsAnsweredFromTheField() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(state = SessionState.needsInput))
        ComposerFixture.question(listOf(QuestionItem(id = "q1", prompt = "How should it split?", allowText = true)), harness.chat)
        assertTrue(harness.composer.answering)
        assertEquals(S.composer.answer, harness.composer.primaryLabel)
        assertEquals(S.composer.placeholderAnswer, harness.composer.placeholder)
        assertTrue(harness.composer.primaryDisabled)
        harness.composer.setDraft("by tenant")
        harness.composer.primarySubmit()
        assertTrue(eventually { harness.channel.sent("session.answer").isNotEmpty() })
        val answered = harness.channel.sent("session.answer").first()
        assertEquals("req-q", answered["request_id"]?.stringValue)
        assertNotNull(answered["answers"]?.objectValue?.get("q1"))
        assertTrue(harness.chat.draft.isEmpty())
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    @Test
    fun aQuestionThatTakesNoWordsLeavesAnswerDisabled() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(state = SessionState.needsInput))
        ComposerFixture.question(listOf(QuestionItem(id = "q1", prompt = "Pick one", options = listOf(QuestionOption(id = "a", label = "A")))), harness.chat)
        harness.composer.setDraft("words")
        assertNull(harness.composer.answer)
        assertTrue(harness.composer.primaryDisabled)
    }

    /** A21/A40: the card's changes go through the store's `session.set`. */
    @Test
    fun aChangeFromTheCardIsDrawnAtOnceAndGoesAsASessionSet() = runTest {
        val harness = ComposerHarness(this)
        val gate = ComposerGate()
        val session = harness.chat.session
        harness.channel.answer("session.set") {
            gate.await()
            JSONValue.encode(SessionResult(session = session))
        }
        harness.composer.setOption(SessionOptions(effort = "low"))
        // Drawn before the device answers, as `applyOptions` draws it.
        assertTrue(eventually { harness.chat.session.effort == "low" })
        gate.release()
        assertTrue(eventually { harness.channel.sent("session.set").isNotEmpty() })
        assertEquals("low", harness.channel.sent("session.set").first()["effort"]?.stringValue)
        harness.composer.setOption(SessionOptions(speed = SpeedChange.Standard))
        assertTrue(eventually { harness.channel.sent("session.set").size == 2 })
        assertEquals(JsonNull, harness.channel.sent("session.set").last()["speed"])
    }
}

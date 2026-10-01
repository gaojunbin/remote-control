package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.longValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.PrimarySlot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/up-next.test.tsx` and `queued-edit.test.tsx` — A43: Up next, and a queued message taken back into the field and put back again. */
class ComposerQueueTests {
    private val first = QueuedMessage(id = "q-1", text = "Then run the full test suite.", ts = 5)
    private val withFiles = QueuedMessage(id = "q-2", text = "These two screenshots", ts = 6, attachments = 2)
    private val last = QueuedMessage(id = "q-3", text = "After that, bump Vite.", ts = 7)

    private fun TestScope.harness(state: SessionState = SessionState.running, agent: String = "claude", info: AgentInfo? = DemoFixtures.claude): ComposerHarness {
        val harness = ComposerHarness(this, session = ComposerFixture.session(agent = agent, state = state), agent = info)
        ComposerFixture.queue(listOf(first, withFiles, last), harness.chat)
        return harness
    }

    /** The field held words and a file when the edit began. */
    private fun TestScope.editing(harness: ComposerHarness): ComposerAttachment {
        val file = ComposerAttachment(name = "draft.png", mime = "image/png", data = ByteArray(3))
        harness.composer.setDraft("a draft I was typing")
        harness.host.drafts.add(listOf(file), to = harness.composer.key)
        harness.composer.edit(first)
        eventually { harness.chat.queuedEdit != null }
        return file
    }

    @Test
    fun aMessageWithFilesCanBeRemovedButNotEdited() = runTest {
        val harness = harness()
        assertTrue(harness.composer.canEdit(first))
        assertFalse(harness.composer.canEdit(withFiles))
    }

    @Test
    fun theEntryLeavesTheLineFirstThenTheDraftGoesAside() = runTest {
        val harness = harness()
        val focus = harness.composer.focusRequest
        editing(harness)
        assertEquals("q-1", harness.channel.sent("session.queue_remove").first()["queued_id"]?.stringValue)
        assertEquals(first.text, harness.chat.draft)
        assertTrue(harness.composer.attachments.isEmpty())
        assertTrue(harness.composer.focusRequest > focus)
    }

    @Test
    fun nothingOpensAndOneLineSaysSoWhenTheDeviceHadSentIt() = runTest {
        val harness = harness()
        harness.channel.answer("session.queue_remove") { throw ComposerFixture.refusal(GatewayErrorCode.notFound, "no such entry") }
        harness.composer.edit(first)
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals(listOf(S.composer.alreadySent), harness.composer.errors)
        assertNull(harness.chat.queuedEdit)
        assertNull(harness.chat.errorMessage)
    }

    @Test
    fun theEditedWordsGoBackUnderTheEntrysTsAsQueueEvenBehindASteeringAgent() = runTest {
        val harness = harness(agent = "codex", info = DemoFixtures.codex)
        editing(harness)
        assertEquals(S.composer.queue, harness.composer.primaryLabel)
        assertEquals(S.composer.placeholderQueued, harness.composer.placeholder)
        harness.composer.userTyped("Then run the full test suite twice.")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals("queue", sent["mode"]?.stringValue)
        assertEquals(5L, sent["queue_ts"]?.longValue)
        assertEquals("Then run the full test suite twice.", sent["text"]?.stringValue)
    }

    @Test
    fun theFieldGetsBackWhatItHeldOnceTheWordsAreBackInTheLine() = runTest {
        val harness = harness()
        val file = editing(harness)
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.chat.queuedEdit == null })
        assertEquals("a draft I was typing", harness.chat.draft)
        assertEquals(listOf(file), harness.composer.attachments)
    }

    @Test
    fun cancelPutsTheOriginalWordsBack() = runTest {
        val harness = harness()
        editing(harness)
        harness.composer.userTyped("changed my mind")
        harness.composer.cancelEdit()
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals(first.text, sent["text"]?.stringValue)
        assertEquals(5L, sent["queue_ts"]?.longValue)
        assertTrue(eventually { harness.chat.queuedEdit == null })
        assertEquals("a draft I was typing", harness.chat.draft)
    }

    @Test
    fun aRefusedPutBackKeepsEditingWithTheDraftStillAside() = runTest {
        val harness = harness()
        editing(harness)
        harness.channel.answer("session.send") { throw ComposerFixture.refusal(GatewayErrorCode.conflict, "the queue is full") }
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals(listOf("the queue is full"), harness.composer.errors)
        assertNotNull(harness.chat.queuedEdit)
        assertEquals(first.text, harness.chat.draft)
        assertTrue(harness.composer.attachments.isEmpty())
    }

    @Test
    fun theFieldHoldsStillWhileTheWordsAreOnTheirWay() = runTest {
        val harness = harness()
        editing(harness)
        val gate = ComposerGate()
        harness.channel.answer("session.send") {
            gate.await()
            JSONValue.encode(SendResult(accepted = SendAcceptance.queued))
        }
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.composer.returning })
        assertEquals(PrimarySlot.working, harness.composer.slot)
        assertFalse(harness.composer.acceptsFiles)
        harness.composer.primarySubmit()
        harness.composer.submit(SendMode.auto)
        gate.release()
        assertTrue(eventually { harness.chat.queuedEdit == null })
        assertEquals(1, harness.channel.sent("session.send").size)
    }

    @Test
    fun aTurnThatEndedMeanwhileTakesTheWordsAtOnceAndTheButtonSaysSend() = runTest {
        val harness = harness(state = SessionState.idle)
        editing(harness)
        assertEquals(S.composer.send, harness.composer.primaryLabel)
        harness.composer.primarySubmit()
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals("queue", sent["mode"]?.stringValue)
        assertEquals(5L, sent["queue_ts"]?.longValue)
    }

    @Test
    fun interruptAndSendKeepsNoPlaceInTheLine() = runTest {
        val harness = harness()
        editing(harness)
        harness.composer.submit(SendMode.interrupt)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        val sent = harness.channel.sent("session.send").first()
        assertEquals("interrupt", sent["mode"]?.stringValue)
        assertNull(sent["queue_ts"])
    }

    @Test
    fun whileEditingTheFieldIsAMessageAndNothingElse() = runTest {
        val harness = harness(agent = "codex", info = DemoFixtures.codex)
        editing(harness)
        harness.composer.userTyped("/")
        assertFalse(harness.composer.panelOpen)
        assertFalse(harness.composer.canEdit(last))
    }

    @Test
    fun aQuestionWaitsForTheFieldWhileItIsEditing() = runTest {
        val harness = harness(state = SessionState.needsInput)
        editing(harness)
        ComposerFixture.question(listOf(QuestionItem(id = "q", prompt = "Which?", allowText = true)), harness.chat, seq = 2)
        assertFalse(harness.composer.answering)
        harness.composer.cancelEdit()
        assertTrue(eventually { harness.chat.queuedEdit == null })
        assertTrue(harness.composer.answering)
    }

    @Test
    fun removeTakesAMessageOutOfTheLineAndTheBannerSaysANotFound() = runTest {
        val harness = harness()
        harness.channel.answer("session.queue_remove") { throw ComposerFixture.refusal(GatewayErrorCode.notFound, "gone") }
        harness.composer.remove(last)
        assertTrue(eventually { harness.channel.sent("session.queue_remove").isNotEmpty() })
        assertEquals("q-3", harness.channel.sent("session.queue_remove").first()["queued_id"]?.stringValue)
        // A Remove's refusal is the page's banner, as on the web.
        assertTrue(eventually { harness.chat.errorMessage != null })
        assertTrue(harness.composer.errors.isEmpty())
    }
}

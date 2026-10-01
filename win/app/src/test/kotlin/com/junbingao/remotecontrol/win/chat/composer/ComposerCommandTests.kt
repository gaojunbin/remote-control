package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.CommandsResult
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/CommandMenu.test.tsx` — A27, the terminal's `/` menu in the composer, on Codex's list. */
class ComposerCommandTests {
    private val list = listOf(
        Command(name = "compact", description = "Summarise the conversation"),
        Command(name = "review", description = "Review the changes", argument = "instructions"),
        Command(name = "init", description = "Write an AGENTS.md"),
        Command(name = "status", description = "Show the session's model"),
    )

    private suspend fun TestScope.harness(state: SessionState = SessionState.idle): ComposerHarness {
        val harness = ComposerHarness(this, session = ComposerFixture.session(agent = "codex", state = state), agent = DemoFixtures.codex)
        harness.channel.answer("session.commands") { JSONValue.encode(CommandsResult(commands = list)) }
        harness.chat.loadCommands()
        return harness
    }

    @Test
    fun aSlashOpensTheWholeList() = runTest {
        val harness = harness()
        harness.composer.userTyped("/")
        assertTrue(harness.composer.panelOpen)
        assertEquals(list.map { it.name }, harness.composer.panelRows.map { it.name })
        assertEquals("compact", harness.composer.highlighted?.name)
    }

    @Test
    fun anAgentWithoutTheCapabilityDrawsNothing() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.userTyped("/")
        assertFalse(harness.composer.panelOpen)
    }

    @Test
    fun moreLettersNarrowTheListAndASpaceGivesWayToTheHint() = runTest {
        val harness = harness()
        harness.composer.userTyped("/re")
        assertEquals(listOf("review"), harness.composer.panelRows.map { it.name })
        harness.composer.userTyped("/review ")
        assertFalse(harness.composer.panelOpen)
        assertEquals("review", harness.composer.commandMatch?.command?.name)
        harness.composer.userTyped("/nothing")
        assertFalse(harness.composer.panelOpen)
    }

    @Test
    fun ordinaryTextWithASlashInsideIsNotACommand() = runTest {
        val harness = harness()
        harness.composer.userTyped("look at src/lib/ws.ts")
        assertTrue(!harness.composer.panelOpen && harness.composer.commandMatch == null)
    }

    @Test
    fun theArrowsMoveTheHighlightAndEscPutsThePanelAway() = runTest {
        val harness = harness()
        harness.composer.userTyped("/")
        assertTrue(harness.composer.handle(ComposerKey.down, shift = false, hasMarkedText = false))
        assertEquals("review", harness.composer.highlighted?.name)
        assertTrue(harness.composer.handle(ComposerKey.escape, shift = false, hasMarkedText = false))
        assertFalse(harness.composer.panelOpen)
        assertEquals("/", harness.chat.draft)
        // A changed draft is a changed list: the panel is back.
        harness.composer.userTyped("/s")
        assertTrue(harness.composer.panelOpen)
    }

    @Test
    fun tabTakesTheRowLeavingASpaceForItsArgument() = runTest {
        val harness = harness()
        harness.composer.userTyped("/")
        harness.composer.handle(ComposerKey.down, shift = false, hasMarkedText = false)
        harness.composer.handle(ComposerKey.tab, shift = false, hasMarkedText = false)
        assertEquals("/review ", harness.chat.draft)
    }

    @Test
    fun enterTakesTheRowAndTheSecondEnterRunsIt() = runTest {
        val harness = harness()
        harness.composer.userTyped("/comp")
        harness.composer.handle(ComposerKey.enter, shift = false, hasMarkedText = false)
        assertEquals("/compact", harness.chat.draft)
        harness.composer.handle(ComposerKey.enter, shift = false, hasMarkedText = false)
        assertTrue(eventually { harness.channel.sent("session.command").isNotEmpty() })
        assertEquals("compact", harness.channel.sent("session.command").first()["name"]?.stringValue)
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    @Test
    fun aListedCommandRunsWithItsArgument() = runTest {
        val harness = harness()
        harness.composer.setDraft("/review focus on the retry logic")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.command").isNotEmpty() })
        val ran = harness.channel.sent("session.command").first()
        assertEquals("review", ran["name"]?.stringValue)
        assertEquals("focus on the retry logic", ran["argument"]?.stringValue)
        assertTrue(eventually { harness.chat.draft.isEmpty() })
    }

    @Test
    fun anUnknownSlashIsSentAsText() = runTest {
        val harness = harness()
        harness.composer.setDraft("/nonesuch do the thing")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
        assertEquals("/nonesuch do the thing", harness.channel.sent("session.send").first()["text"]?.stringValue)
    }

    @Test
    fun aRefusalIsReportedUnderTheFieldInTheDevicesWords() = runTest {
        val harness = harness()
        harness.channel.answer("session.command") { throw ComposerFixture.refusal(GatewayErrorCode.conflict, "the terminal is busy; try again in a moment") }
        harness.composer.setDraft("/compact")
        harness.composer.submit(SendMode.auto)
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals(listOf("the terminal is busy; try again in a moment"), harness.composer.errors)
        assertEquals("/compact", harness.chat.draft)
        assertNull(harness.chat.errorMessage)
    }

    @Test
    fun aCommandWaitsForTheTurnInTheFootersOwnWords() = runTest {
        val harness = harness(state = SessionState.running)
        harness.composer.setDraft("/compact")
        harness.composer.submit(SendMode.auto)
        assertEquals(listOf(S.commands.whileRunning), harness.composer.errors)
        pass(50)
        assertTrue(harness.channel.sent("session.command").isEmpty())
    }
}

package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The wire cases of RCCore's suite of this name are in `protocol/TypedTerminalTests.kt`, and
// `demoMatchesTheFixture`, which reads the demo's own Claude, is in `demo/TypedTerminalTests.kt`.

/**
 * Amendment A40: the device types into an attached Claude Code terminal. The shim runs the CLI
 * inside a pseudo-terminal the device owns, so `/model`, `/effort` and `/compact` are typed in as
 * the person at the keyboard would — and nothing else is, because there is no command the device
 * could type for the permission mode.
 *
 * What this app has to get right is the reading: `shared_settings_keys` says which setting is a
 * control and which is a value (A17), a refusal comes back in the device's own words, and neither
 * is guessed from the agent id.
 */
class TypedTerminalTests {
    // What the device does with a change

    /** A key the terminal can be typed waits, then reads what it now runs. */
    @Test
    fun typedSettingLands() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(gateway)
        val before = chat.session.model

        // The demo takes as long as typing the command and reading the answer back does, so the wait
        // is a state the card really passes through.
        val setting = async { chat.set(model = "claude-opus-4-1") }
        settle { chat.isSettingPending }
        assertEquals(setOf(SharedSetting.model), chat.pendingSettings, "the card waits while the device types")
        assertEquals(before, chat.session.model, "and reads the model the terminal is still on")

        setting.await()
        assertEquals("claude-opus-4-1", chat.session.model, "the reply is what it follows")
        assertFalse(chat.isSettingPending)
        assertNull(chat.errorMessage)

        chat.set(effort = "medium")
        assertEquals("medium", chat.session.effort)
        assertNull(chat.errorMessage)
    }

    /** A key outside the list is refused, and the old value comes back with the words. */
    @Test
    fun unsharedSettingIsRefused() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(gateway)
        val before = chat.session.permissionMode
        chat.set(permissionMode = "plan")
        assertEquals(before, chat.session.permissionMode)
        assertFalse(chat.isSettingPending)
        assertEquals("Change it in the terminal.", chat.errorMessage)
    }

    /**
     * A busy terminal refuses the change in the device's own words. The device never types over
     * somebody else's keyboard: while a turn runs or a dialog is open there, the change is refused
     * and nothing is queued. The demo's terminal raises a question of its own shortly after the
     * session opens, which is that dialog.
     */
    @Test
    fun busyTerminalConflicts() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(gateway)
        pump(gateway, into = chat)
        chat.open()
        settle(timeout = 10.seconds) { chat.session.state == SessionState.needsInput }
        assertEquals(SessionState.needsInput, chat.session.state, "the terminal is asking something of its own")

        val before = chat.session.effort
        chat.set(effort = "medium")
        assertEquals(before, chat.session.effort, "nothing was drawn, so nothing moved")
        assertFalse(chat.isSettingPending, "and the control stops waiting")
        assertEquals("the terminal is busy; try again in a moment", chat.errorMessage)
    }

    // The one command

    /** The session lists /compact, and running it echoes the line and the compaction. */
    @Test
    fun compactIsTypedIn() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(gateway)
        pump(gateway, into = chat)

        chat.loadCommands()
        assertEquals(listOf("compact"), chat.commands.map { it.name })

        chat.draft = "/compact"
        chat.runCommand()
        settle { chat.timeline.optimistic.isEmpty() }
        assertEquals(1, chat.timeline.roots.count { it.userMessage?.text == "/compact" },
                     "the device's echo replaces the row the app drew under the same id")
        settle { chat.timeline.roots.any { it.notice != null } }
        assertTrue(chat.timeline.roots.any { it.notice?.text?.contains("compacted") == true },
                   "and the compaction itself reads as a notice")
    }

    /**
     * A busy terminal refuses the command in the same words. A command is typing too, so it waits for
     * the same quiet terminal. The composer has already closed the panel while the dialog is open
     * (A20), so the rule is read off the device rather than off the field.
     */
    @Test
    fun compactOnABusyTerminal() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(gateway)
        pump(gateway, into = chat)
        chat.open()
        settle(timeout = 10.seconds) { chat.session.state == SessionState.needsInput }

        val error = assertFailsWith<GatewayErrorBody>("a busy terminal took the command") {
            gateway.request(GatewayRequest.command(id = "c-1", sessionID = DemoFixtures.sharedSessionID, name = "compact",
                                                   argument = null))
        }
        assertEquals(GatewayErrorCode.conflict, error.code)
        assertEquals("the terminal is busy; try again in a moment", error.message)
    }

    // Helpers

    private fun TestScope.chat(gateway: DemoGateway): ChatStore {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.sharedSessionID })
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude
        return chat
    }

    private fun TestScope.pump(gateway: DemoGateway, into: ChatStore): Job = backgroundScope.launch {
        gateway.events.collect { event -> if (event is GatewayEvent.Frame) into.receive(event.frame) }
    }
}

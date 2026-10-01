package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.TerminalAttachResult
import com.junbingao.remotecontrol.core.protocol.TerminalExited
import com.junbingao.remotecontrol.core.protocol.TerminalOpenResult
import com.junbingao.remotecontrol.core.protocol.TerminalOutput
import com.junbingao.remotecontrol.core.protocol.intValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Amendment A38: one shell over the gateway, without an emulator under it.
 *
 * What is checked here is everything the screen cannot see by looking: that keystrokes leave in the
 * order they were made, that a gap in `seq` is noticed rather than guessed at, that a duplicate frame
 * is not printed twice, and that a lost socket ends in an `attach` whose scrollback arrives first.
 */
class TerminalSessionTests {
    private val deviceID = "d1"

    private fun session(channel: ShellChannel, tasks: CoroutineScope): Pair<TerminalSession, Collected> {
        val session = TerminalSession(deviceID = deviceID, channel = channel, tasks = tasks, resizeDelay = 1.milliseconds)
        val collected = Collected()
        session.onOutput = { collected.append(it) }
        return session to collected
    }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.encodeToByteArray())

    private fun output(terminalID: String, seq: Int, text: String): AppFrame =
        AppFrame.TerminalOutput(TerminalOutput(terminalID = terminalID, deviceID = deviceID, seq = seq, data = base64(text)))

    /** Opening asks for the size the emulator is drawn at, and gets an id. */
    @Test
    fun opens() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 100, rows = 32)

        assertEquals(TerminalSession.Status.Connected, session.status)
        assertEquals(ShellChannel.terminalID, session.terminalID)
        val open = assertNotNull(channel.first("terminal.open"))
        assertEquals(deviceID, open["device_id"]?.stringValue)
        assertEquals(100, open["cols"]?.intValue)
        assertEquals(32, open["rows"]?.intValue)
    }

    /** A machine that offers no terminal is said in the app's own words. */
    @Test
    fun unsupported() = runTest {
        val channel = ShellChannel()
        channel.refuse(GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "terminal disabled"))
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        assertEquals(TerminalSession.Status.Failed("This device does not offer a terminal."), session.status)
        assertNull(session.terminalID)
        assertTrue(TerminalStatusText.offersNewShell(session.status))
    }

    /** A fifth terminal on one machine says what to do about it. */
    @Test
    fun conflict() = runTest {
        val channel = ShellChannel()
        channel.refuse(GatewayErrorBody(code = GatewayErrorCode.conflict, message = "four already"))
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        assertEquals(TerminalSession.Status.Failed("This device already runs four terminals. Close one first."), session.status)
    }

    /** Output is fed in order, a repeat is dropped and a gap is noticed. */
    @Test
    fun outputSequencing() = runTest {
        val channel = ShellChannel()
        val (session, collected) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)
        val id = ShellChannel.terminalID

        session.receive(output(id, seq = 1, "one"))
        session.receive(output(id, seq = 2, "two"))
        assertEquals("onetwo", collected.text)
        assertFalse(session.missedOutput)

        session.receive(output(id, seq = 2, "two again"))
        assertEquals("onetwo", collected.text, "a frame already applied is not printed twice")
        assertFalse(session.missedOutput, "and is not a gap either")

        session.receive(output(id, seq = 5, "five"))
        assertEquals("onetwofive", collected.text)
        assertTrue(session.missedOutput, "three and four never arrived, and the line says so")
    }

    /** Another terminal's output, and another machine's, are not this screen's. */
    @Test
    fun ignoresOtherTerminals() = runTest {
        val channel = ShellChannel()
        val (session, collected) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        session.receive(output("someone-else", seq = 1, "no"))
        session.receive(AppFrame.TerminalOutput(TerminalOutput(terminalID = ShellChannel.terminalID,
                                                               deviceID = "another-machine", seq = 1, data = base64("no"))))
        assertTrue(collected.text.isEmpty())
    }

    /** Keystrokes leave in the order they were typed. */
    @Test
    fun inputOrder() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        for (letter in "hello") session.type(letter.toString().encodeToByteArray())
        channel.settle(untilRequests = 6)

        val typed = channel.bodies("terminal.input")
            .mapNotNull { body -> body["data"]?.stringValue?.let { Base64.getDecoder().decode(it) } }
            .joinToString("") { it.decodeToString() }
        assertEquals("hello", typed)
    }

    /** A run of size changes settles into one resize. */
    @Test
    fun resizeSettles() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        session.resize(cols = 81, rows = 24)
        session.resize(cols = 90, rows = 30)
        session.resize(cols = 100, rows = 40)
        channel.settle(untilRequests = 2)

        val resizes = channel.bodies("terminal.resize")
        assertEquals(1, resizes.size, "one request, not three")
        assertEquals(100, resizes.firstOrNull()?.get("cols")?.intValue)
        assertEquals(40, resizes.firstOrNull()?.get("rows")?.intValue)
    }

    /** A lost socket is said, and its return attaches with the scrollback first. */
    @Test
    fun reattaches() = runTest {
        val channel = ShellChannel()
        val (session, collected) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)
        session.receive(output(ShellChannel.terminalID, seq = 1, "before"))

        session.link(isUp = false)
        assertEquals(TerminalSession.Status.Disconnected, session.status)
        assertTrue(TerminalStatusText.offersReconnect(session.status))

        channel.scrollback("before the drop")
        session.attach()
        assertEquals(TerminalSession.Status.Connected, session.status)
        assertEquals("beforebefore the drop", collected.text, "the scrollback lands before anything new does")

        // Protocol 7.3: `seq` carries on from where the other connection left it, so the first frame
        // after an attach is never read as a gap.
        session.receive(output(ShellChannel.terminalID, seq = 91, "after"))
        assertFalse(session.missedOutput)
        assertTrue(collected.text.endsWith("after"))
    }

    /** A terminal the device no longer keeps offers a new shell, not a retry. */
    @Test
    fun attachAfterTheShellIsGone() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)
        session.link(isUp = false)

        channel.refuse(GatewayErrorBody(code = GatewayErrorCode.notFound, message = "gone"))
        session.attach()
        assertEquals(TerminalSession.Status.Exited(code = null), session.status)
        assertNull(session.terminalID)
        assertTrue(TerminalStatusText.offersNewShell(session.status))
    }

    /** The shell ending frees the terminal and names its code. */
    @Test
    fun exits() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        session.receive(AppFrame.TerminalExited(TerminalExited(terminalID = ShellChannel.terminalID,
                                                               deviceID = deviceID, code = 130)))
        assertEquals(TerminalSession.Status.Exited(code = 130), session.status)
        assertNull(session.terminalID)
        assertEquals("Shell exited (130)", TerminalStatusText.line(session.status))
        assertEquals("Shell exited", TerminalStatusText.exited(code = null))
    }

    /** Closing ends the shell once, and typing after it sends nothing. */
    @Test
    fun closes() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)
        session.open(cols = 80, rows = 24)

        session.close()
        session.close()
        session.type("x".encodeToByteArray())
        channel.settle(untilRequests = 2)

        assertEquals(1, channel.bodies("terminal.close").size)
        assertTrue(channel.bodies("terminal.input").isEmpty())
    }

    /** Nothing happens to a terminal that was never opened. */
    @Test
    fun inertBeforeOpen() = runTest {
        val channel = ShellChannel()
        val (session, _) = session(channel, backgroundScope)

        session.type("x".encodeToByteArray())
        session.resize(cols = 90, rows = 30)
        session.link(isUp = false)
        session.close()
        channel.settle(untilRequests = 1)

        assertTrue(channel.bodies("terminal.input").isEmpty())
        assertTrue(channel.bodies("terminal.resize").isEmpty())
        assertTrue(channel.bodies("terminal.close").isEmpty())
        assertEquals(TerminalSession.Status.Connecting, session.status)
    }

    /** What the emulator would have been fed. */
    private class Collected {
        private val bytes = ByteArrayOutputStream()
        val text: String get() = bytes.toByteArray().decodeToString()
        fun append(data: ByteArray) = bytes.write(data)
    }

    /** A gateway that answers the five terminal requests and remembers them. */
    private class ShellChannel : InertChannel() {
        private val seen = mutableListOf<Pair<String, JsonObject>>()
        private var refusal: GatewayErrorBody? = null
        private var scrollbackText = ""

        fun refuse(error: GatewayErrorBody) {
            refusal = error
        }

        fun scrollback(text: String) {
            scrollbackText = text
        }

        override suspend fun request(request: GatewayRequest): JsonElement {
            seen.add(request.type to request.body)
            refusal?.let { throw it }
            return when (request.type) {
                "terminal.open" -> JSONValue.encode(TerminalOpenResult(terminalID = terminalID))
                "terminal.attach" -> JSONValue.encode(TerminalAttachResult(
                    terminalID = terminalID, cols = 80, rows = 24,
                    scrollback = Base64.getEncoder().encodeToString(scrollbackText.encodeToByteArray())))
                else -> JSONValue.emptyObject
            }
        }

        fun bodies(type: String): List<JsonObject> = seen.filter { it.first == type }.map { it.second }

        fun first(type: String): JsonObject? = bodies(type).firstOrNull()

        /**
         * Wait for the session's own work to run. It has no timer behind it but the resize delay, so
         * a handful of steps is enough; the deadline is there so a broken expectation fails rather
         * than hangs.
         */
        suspend fun settle(untilRequests: Int) {
            withTimeoutOrNull(2.seconds) {
                while (seen.size < untilRequests) delay(5.milliseconds)
            }
            delay(20.milliseconds)
        }

        companion object {
            const val terminalID = "t-1"
        }
    }
}

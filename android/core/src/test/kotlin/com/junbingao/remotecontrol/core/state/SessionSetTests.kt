package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/DESIGN.md` § "The model card": on a session this app drives, every change made from the card
 * is drawn the moment it is made, and the device's reply either confirms it or puts the previous value
 * back with an error.
 *
 * Amendment A40: not on a `shared` session. There the change is typed into somebody else's terminal,
 * so the card waits for the reply rather than drawing ahead of it. These cover both halves.
 */
class SessionSetTests {
    private fun session(title: String = "T", effort: String? = "medium", speed: String? = null): Session =
        Session(sessionID = "s", deviceID = "d", agent = "codex", title = title, cwd = "/tmp", state = SessionState.idle,
                control = SessionControl.remote, effort = effort, speed = speed)

    /**
     * The same session a terminal owns and the device is attached to, on the agent whose shim types
     * the model and the effort in (A40).
     */
    private fun shared(title: String = "T", effort: String? = "medium"): Session =
        Session(sessionID = "s", deviceID = "d", agent = "claude", title = title, cwd = "/tmp", state = SessionState.idle,
                origin = EventSource.terminal, control = SessionControl.shared, effort = effort)

    /** A speed change is visible before the channel answers. */
    @Test
    fun drawnBeforeTheReply() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)

        val setting = async { chat.set(speed = SpeedChange.Tier("fast")) }
        channel.waitForSet()
        assertEquals("fast", chat.session.speed, "the lightning fills on the tap, not on the reply")

        channel.release(Result.success(SessionResult(session = session(effort = "high", speed = "fast"))))
        setting.await()
        assertEquals("fast", chat.session.speed)
        assertEquals("high", chat.session.effort, "and the device's own copy replaces it")
        assertNull(chat.errorMessage)
    }

    /** A refusal puts the previous value back, with the error. */
    @Test
    fun refusalRestores() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = session(effort = "medium"), channel = channel, tasks = backgroundScope)

        val setting = async { chat.set(effort = "high") }
        channel.waitForSet()
        assertEquals("high", chat.session.effort)

        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "Not on this agent.")))
        setting.await()
        assertEquals("medium", chat.session.effort, "the level the session was on comes back")
        assertEquals("Not on this agent.", chat.errorMessage)
    }

    /** A session that arrived while the request was in flight is not overwritten. */
    @Test
    fun newerSessionSurvivesARefusal() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = session(effort = "medium"), channel = channel, tasks = backgroundScope)

        val setting = async { chat.set(effort = "high") }
        channel.waitForSet()

        // The device publishes a change of its own between the tap and the refusal. Rolling back over
        // it would put a stale level on screen.
        chat.receive(AppFrame.SessionUpdated(session(title = "renamed at the terminal", effort = "low")))
        assertEquals("low", chat.session.effort)

        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "Not on this agent.")))
        setting.await()
        assertEquals("low", chat.session.effort, "the newer session stands")
        assertEquals("renamed at the terminal", chat.session.title)
        assertEquals("Not on this agent.", chat.errorMessage)
    }

    // Amendment A40, a change that is typed into a terminal

    /** On a shared session the control waits instead of drawing the change. */
    @Test
    fun sharedSettingWaitsForTheReply() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = shared(), channel = channel, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude

        val setting = async { chat.set(effort = "high") }
        channel.waitForSet()
        assertEquals("medium", chat.session.effort, "the card still reads the level the terminal is on")
        assertEquals(setOf(SharedSetting.effort), chat.pendingSettings, "and names the control that is waiting")
        assertTrue(chat.isSettingPending)

        channel.release(Result.success(SessionResult(session = shared(effort = "high"))))
        setting.await()
        assertEquals("high", chat.session.effort, "the reply is what the card follows")
        assertFalse(chat.isSettingPending, "and the wait is over with it")
        assertNull(chat.errorMessage)
    }

    /** A busy terminal leaves the value alone and says so in the device's words. */
    @Test
    fun sharedConflictChangesNothing() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = shared(), channel = channel, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude

        val setting = async { chat.set(model = "claude-opus-4-1") }
        channel.waitForSet()
        assertNull(chat.session.model, "nothing was drawn to put back")

        channel.release(Result.failure(GatewayErrorBody(code = GatewayErrorCode.conflict,
                                                        message = "the terminal is busy; try again in a moment")))
        setting.await()
        assertNull(chat.session.model, "and the refusal leaves it exactly where it was")
        assertFalse(chat.isSettingPending)
        assertEquals("the terminal is busy; try again in a moment", chat.errorMessage)
    }

    /**
     * A rename on a shared session is still drawn at once. The title is not typed into anything — it
     * is the app's on every session — so a rename is still drawn the moment it is made.
     */
    @Test
    fun sharedTitleStaysOptimistic() = runTest {
        val channel = HeldSetChannel()
        val chat = ChatStore(session = shared(), channel = channel, tasks = backgroundScope)
        chat.agent = DemoFixtures.claude

        val setting = async { chat.set(title = "Release notes") }
        channel.waitForSet()
        assertEquals("Release notes", chat.session.title)
        assertFalse(chat.isSettingPending, "nothing is being typed for a title")

        channel.release(Result.success(SessionResult(session = shared(title = "Release notes"))))
        setting.await()
        assertNull(chat.errorMessage)
    }

    /** A channel that holds one `session.set` until the test says what the device answered, so the moment between the tap and the reply can be looked at. */
    private class HeldSetChannel : InertChannel() {
        private var held: CompletableDeferred<Result<SessionResult>>? = null
        private var arrived = CompletableDeferred<Unit>()

        override suspend fun request(request: GatewayRequest): JsonElement {
            if (request.type != "session.set") return JSONValue.emptyObject
            val outcome = CompletableDeferred<Result<SessionResult>>()
            held = outcome
            arrived.complete(Unit)
            return JSONValue.encode(outcome.await().getOrThrow())
        }

        /** Returns once the request has reached the channel and is waiting there. */
        suspend fun waitForSet() {
            arrived.await()
            arrived = CompletableDeferred()
        }

        fun release(outcome: Result<SessionResult>) {
            held?.complete(outcome)
            held = null
        }
    }
}

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.persistence.CachedWorkspace
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Amendment A47 — a session that stopped working and waits for you is marked until someone looks:
 * the rule the gateway keeps the mark by (which the demo keeps it by too), the count the app icon
 * carries, the cache, and when the app tells the gateway the person has a conversation in front of
 * them. RCCore's suite of this name also holds the fixtures (`protocol/UnseenMarkTests.kt`) and the
 * demo (`demo/UnseenMarkTests.kt`).
 */
class UnseenMarkTests {
    private val directories = mutableListOf<File>()

    @AfterTest
    fun removeDirectories() {
        for (directory in directories) directory.deleteRecursively()
    }

    // The wire

    /** The mark is kept in the cache the list paints from. */
    @Test
    fun cacheKeepsTheMark() = runTest {
        val cache = LocalCache(scratchDirectory("unseen").also { directories.add(it) })
        cache.save(CachedWorkspace(sessions = listOf(session("a", unseen = true), session("b"))),
                   origin = "https://rc.example.invalid", username = "me")
        val loaded = cache.load(origin = "https://rc.example.invalid", username = "me")?.sessions.orEmpty()
        assertEquals(listOf(true, false), loaded.map { it.unseen })
    }

    // The rule

    /** A running turn that becomes waiting marks the session. */
    @Test
    fun runningToWaiting() {
        for ((state, control) in waiting) {
            val next = UnseenMark.next(previous = session("s", state = SessionState.running, control = control),
                                       current = session("s", state = state, control = control))
            assertTrue(next, "running → $state with control $control")
        }
    }

    /** A session that only started has done nothing to look at, so it brings no mark. */
    @Test
    fun startingBringsNone() {
        for ((state, control) in waiting) {
            val next = UnseenMark.next(previous = session("s", state = SessionState.starting, control = control),
                                       current = session("s", state = state, control = control))
            assertFalse(next, "starting → $state with control $control")
        }
        assertFalse(UnseenMark.works(SessionState.starting), "green, but not a turn under way")
    }

    /** Grey and red dots never bring one: an exited CLI and an error wait for nobody. */
    @Test
    fun notWaiting() {
        val running = session("s", state = SessionState.running)
        for (current in listOf(session("s", state = SessionState.idle, control = SessionControl.none),
                               session("s", state = SessionState.readonly, control = SessionControl.none),
                               session("s", state = SessionState.error), session("s", state = SessionState.stopped))) {
            assertFalse(UnseenMark.next(previous = running, current = current), "${current.state}, ${current.control}")
        }
    }

    /** Waiting to waiting keeps whatever the session carried. */
    @Test
    fun waitingToWaiting() {
        for (carried in listOf(true, false)) {
            val asking = session("s", state = SessionState.needsApproval, unseen = carried)
            assertEquals(carried, UnseenMark.next(previous = asking, current = session("s", state = SessionState.idle)))
            val idle = session("s", state = SessionState.idle, unseen = carried)
            assertEquals(carried, UnseenMark.next(previous = idle, current = session("s", state = SessionState.needsInput)))
        }
    }

    /** A turn running again clears it: someone carried on elsewhere. */
    @Test
    fun workingAgain() {
        val marked = session("s", state = SessionState.idle, unseen = true)
        assertFalse(UnseenMark.next(previous = marked, current = session("s", state = SessionState.running)))
        assertTrue(UnseenMark.next(previous = marked, current = session("s", state = SessionState.starting)),
                   "a process starting is not yet a turn, so the mark waits for one")
    }

    /** Archiving clears it, and an archived session is never marked. */
    @Test
    fun archiving() {
        val marked = session("s", state = SessionState.idle, unseen = true)
        assertFalse(UnseenMark.next(previous = marked,
                                    current = session("s", state = SessionState.stopped, control = SessionControl.none,
                                                      archived = true)))
        assertFalse(UnseenMark.next(previous = session("s", state = SessionState.running),
                                    current = session("s", state = SessionState.idle, archived = true)))
    }

    /** The states alone decide: a turn that ended offline still marks the session. */
    @Test
    fun onlineIgnored() {
        val ended = session("s", state = SessionState.idle)
        assertEquals(DotTone.off, ended.dotTone(online = false), "the dot is grey while the machine is gone")
        assertTrue(UnseenMark.next(previous = session("s", state = SessionState.running), current = ended),
                   "and the turn it finished is still one to look at")
    }

    /** The badge counts the unarchived sessions with a red dot. */
    @Test
    fun count() {
        val sessions = listOf(session("a", unseen = true), session("b", unseen = true, archived = true),
                              session("c"), session("d", state = SessionState.needsApproval, unseen = true))
        assertEquals(2, UnseenMark.count(sessions))
        assertEquals(0, UnseenMark.count(emptyList()))
        assertEquals(1, UnseenMark.count(sessions, excluding = "d/a"), "the conversation in front is being looked at, so it is not counted")
        assertEquals(2, UnseenMark.count(sessions, excluding = "d/c"), "and a quiet one changes nothing")
    }

    // Telling the gateway

    /** `session.seen` goes only while the copy carries the mark, and once for it. */
    @Test
    fun sendsOnlyWhileMarked() = runTest {
        val channel = SeenChannel(listOf(session("marked", unseen = true), session("quiet")))
        val connection = connected(channel)
        assertEquals(1, connection.unseenCount)

        connection.markSeen(deviceID = "d", sessionID = "quiet")
        assertTrue(channel.seen.isEmpty(), "a session without the mark asks for nothing")

        connection.markSeen(deviceID = "d", sessionID = "marked")
        connection.markSeen(deviceID = "d", sessionID = "marked")
        assertEquals(listOf("marked"), channel.seen, "one request for one mark, however often it is found")

        channel.emit(AppFrame.SessionUpdated(session("marked")))
        settle { connection.unseenCount == 0 }
        connection.markSeen(deviceID = "d", sessionID = "marked")
        assertEquals(listOf("marked"), channel.seen, "the gateway took it off, so there is nothing to say")

        channel.emit(AppFrame.SessionUpdated(session("marked", unseen = true)))
        settle { connection.unseenCount == 1 }
        connection.markSeen(deviceID = "d", sessionID = "marked")
        assertEquals(listOf("marked", "marked"), channel.seen, "a turn that ended again is a new mark")
    }

    /** A failed `session.seen` says nothing, and the next occasion sends it again. */
    @Test
    fun failureIsSilent() = runTest {
        val channel = SeenChannel(listOf(session("marked", unseen = true)))
        val connection = connected(channel)
        channel.failsSeen = true
        connection.markSeen(deviceID = "d", sessionID = "marked")
        assertEquals(listOf("marked"), channel.seen)
        assertNull(connection.errorMessage, "no banner for a request that is idempotent")
        channel.failsSeen = false
        connection.markSeen(deviceID = "d", sessionID = "marked")
        assertEquals(listOf("marked", "marked"), channel.seen)
    }

    /** The conversation in front with the mark on is reported at every moment it becomes so. */
    @Test
    fun reporterFollowsTheFront() = runTest {
        val channel = SeenChannel(listOf(session("a", unseen = true), session("b"), session("c")))
        val connection = connected(channel)
        val front = Front()
        val reporter = SeenReporter(connection, backgroundScope) { front.key }
        reporter.start()

        observe(100)
        assertTrue(channel.seen.isEmpty(), "nothing is in front, so nothing has been seen")

        front.key = "d/a"
        observe { channel.seen == listOf("a") }
        assertEquals(listOf("a"), channel.seen, "opening it in front of the person sends it")

        front.key = "d/b"
        channel.emit(AppFrame.SessionUpdated(session("c", unseen = true)))
        observe(150)
        assertEquals(listOf("a"), channel.seen, "a quiet conversation, and a mark on one behind it, send nothing")

        channel.emit(AppFrame.SessionUpdated(session("b", unseen = true)))
        observe { channel.seen == listOf("a", "b") }
        assertEquals(listOf("a", "b"), channel.seen, "a mark arriving on the conversation in front is cleared at once")

        front.key = null
        observe(20)
        front.key = "d/c"
        observe { channel.seen == listOf("a", "b", "c") }
        assertEquals(listOf("a", "b", "c"), channel.seen, "and so is one in front when its window comes forward")
    }

    // Helpers

    /** Every state and owner the dot draws amber: what a turn ends in, or stops to ask from. */
    private val waiting = listOf(
        SessionState.needsApproval to SessionControl.remote, SessionState.needsInput to SessionControl.shared,
        SessionState.idle to SessionControl.remote, SessionState.idle to SessionControl.shared,
        SessionState.idle to SessionControl.terminal, SessionState.readonly to SessionControl.terminal,
    )

    private fun session(id: String, state: SessionState = SessionState.idle, control: SessionControl = SessionControl.remote,
                        unseen: Boolean = false, archived: Boolean = false): Session =
        Session(sessionID = id, deviceID = "d", agent = "claude", title = id, cwd = "/src", state = state, control = control,
                archived = archived, unseen = unseen)

    private suspend fun TestScope.connected(channel: SeenChannel): ConnectionStore {
        val connection = ConnectionStore(tasks = backgroundScope, cache = LocalCache(scratchDirectory("unseen").also { directories.add(it) }),
                                         makeAPI = { StubGateway(it) })
        connection.enterDemo(api = DemoGateway(resumeDelay = null, isolation = StandardTestDispatcher(testScheduler)),
                             channel = channel)
        settle { connection.hasSnapshot }
        return connection
    }

    /**
     * Let snapshot observers hear what changed, as the app's frame clock tells them, until
     * [condition] holds; RCCore's observation needs no such word.
     */
    private fun TestScope.observe(condition: () -> Boolean) = settle {
        Snapshot.sendApplyNotifications()
        condition()
    }

    /** The same for [milliseconds] of the test's clock, where nothing is expected to happen. */
    private fun TestScope.observe(milliseconds: Long) {
        repeat((milliseconds / 10).toInt()) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(10.milliseconds)
            runCurrent()
        }
    }

    /** What a test says is in front of the person, observed as an app model is. */
    private class Front {
        var key: String? by mutableStateOf(null)
    }

    /** A channel that serves one hello of the test's sessions, emits frames on command, answers everything with `{}`, and remembers every `session.seen`. */
    private class SeenChannel(private val sessions: List<Session>) : GatewayChannel {
        private val stream = Channel<GatewayEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_LATEST)
        override val events: Flow<GatewayEvent> = stream.receiveAsFlow()
        val seen = mutableListOf<String>()
        var failsSeen = false

        override suspend fun connect() {
            stream.trySend(GatewayEvent.State(ConnectionState.connected))
            emit(AppFrame.Hello(HelloFrame(protocolVersion = RemoteProtocol.version, gatewayVersion = "test",
                                           user = UserIdentity(username = "me"), devices = emptyList(), sessions = sessions,
                                           stt = STTConfig.disabled, serverTime = 0)))
        }

        override suspend fun disconnect() {
            stream.trySend(GatewayEvent.State(ConnectionState.disconnected))
        }

        fun emit(frame: AppFrame) {
            stream.trySend(GatewayEvent.Frame(frame))
        }

        override suspend fun request(request: GatewayRequest): JsonElement {
            if (request.type != "session.seen") return JSONValue.emptyObject
            seen.add(request.body["session_id"]?.stringValue.orEmpty())
            if (failsSeen) throw TransportError.DeliveryUncertain
            return JSONValue.emptyObject
        }
    }
}

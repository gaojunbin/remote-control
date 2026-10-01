package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A subscription lives exactly as long as the conversation that opened it.
 *
 * A `hello` means the gateway has forgotten this connection's subscriptions, and a gap in the seq
 * means an event never arrived; both resubscribe. Neither may do so for a conversation that has
 * already been closed — nothing draws the result, and the gateway streams it until the next
 * reconnect.
 */
class SubscriptionLifetimeTests {
    private fun session(): Session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                                             state = SessionState.idle, control = SessionControl.remote)

    private fun hello(): AppFrame = AppFrame.Hello(HelloFrame(protocolVersion = RemoteProtocol.version, gatewayVersion = "test",
                                                              user = UserIdentity(username = "me"), devices = emptyList(),
                                                              sessions = emptyList(), stt = STTConfig.disabled, serverTime = 0))

    /** A closed conversation does not resubscribe itself when the socket comes back. */
    @Test
    fun closedChatIgnoresHello() = runTest {
        val channel = RecordingChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.subscribe()
        assertEquals(1, channel.count(of = "session.subscribe"))

        chat.close()
        chat.receive(hello())
        delay(150)

        assertEquals(1, channel.count(of = "session.subscribe"), "the conversation that said unsubscribe stays unsubscribed")
        assertEquals(1, channel.count(of = "session.unsubscribe"))
    }

    /** An open conversation still resubscribes when the socket comes back. */
    @Test
    fun openChatFollowsHello() = runTest {
        val channel = RecordingChannel()
        val chat = ChatStore(session = session(), channel = channel, tasks = backgroundScope)
        chat.subscribe()

        chat.receive(hello())
        delay(150)

        assertEquals(2, channel.count(of = "session.subscribe"))
    }

    /**
     * A channel that records what it was asked for and refuses every request, so a test can count the
     * requests a store issues without answering any of them.
     */
    private class RecordingChannel : InertChannel() {
        private val types = mutableListOf<String>()

        override suspend fun request(request: GatewayRequest): JsonElement {
            types.add(request.type)
            throw TransportError.NotConnected
        }

        fun count(of: String): Int = types.count { it == of }
    }
}

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Slash commands (A27) on the demo device. The wire cases of RCCore's suite of this name are
 * `protocol.SlashCommandTests`; the panel, the hint and the runs through `ChatStore` are
 * `core-state`'s.
 */
class SlashCommandTests {
    /** A name the session does not offer is refused rather than sent as text. */
    @Test
    fun unknownNameIsRefused() = runTest {
        val gateway = demoGateway()
        assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.command(sessionID = DemoFixtures.piSessionID, name = "definitely-not-a-command"))
        }
    }
}

package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.InstallCommands
import com.junbingao.remotecontrol.core.transport.PairingClaim
import com.junbingao.remotecontrol.core.transport.PairingGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Add device sheet's code stays on screen until the gateway has taken it back. Cancelling used
 * to blank the flow first and ask afterwards, which drew the "Requesting a code" placeholder on a
 * sheet that was closing.
 */
class PairingFlowTests {
    /** Cancel keeps the code until the gateway has taken it back. */
    @Test
    fun cancelKeepsTheCodeWhileTheGatewayIsAsked() = runTest {
        val gateway = HeldGateway()
        val flow = PairingFlow(api = gateway)
        flow.begin()
        assertEquals("RC-TEST-CODE", flow.code)

        val cancelling = async { flow.cancel() }
        gateway.waitForCancel()
        assertNotNull(flow.pairing, "the code is still shown while the request is out")
        assertEquals("RC-TEST-CODE", flow.code)

        gateway.releaseCancel()
        cancelling.await()
        assertNull(flow.pairing, "and gone once the gateway has answered")
        assertEquals(listOf("RC-TEST-CODE"), gateway.cancelledCodes)
    }

    /** A claim swaps the codes without a gap. */
    @Test
    fun claimSwapsCodesWithoutAGap() = runTest {
        val gateway = HeldGateway()
        val flow = PairingFlow(api = gateway)
        flow.begin()

        val claiming = async { flow.claim(token = "tok") }
        gateway.waitForCancel()
        assertEquals("RC-TEST-CODE", flow.code, "the old code stays up while it is given back")
        gateway.releaseCancel()
        claiming.await()
        assertEquals("RC-CLAIMED", flow.code)
        assertNull(flow.pairing?.install, "a claimed code carries no one-liner (A23)")
    }

    /** Cancel with no code asks the gateway for nothing. */
    @Test
    fun cancelWithoutACodeIsANoOp() = runTest {
        val gateway = HeldGateway()
        val flow = PairingFlow(api = gateway)
        flow.cancel()
        assertTrue(gateway.cancelledCodes.isEmpty())
    }

    /** A gateway whose `cancelPairing` waits until the test lets it answer. */
    private class HeldGateway : StubGateway(GatewayEndpoint("https://rc.example.com")) {
        val cancelledCodes = mutableListOf<String>()
        private var arrival = CompletableDeferred<Unit>()
        private var release = CompletableDeferred<Unit>()

        override suspend fun beginPairing(): PairingGrant =
            PairingGrant(code = "RC-TEST-CODE", expiresAt = System.currentTimeMillis() + 600_000,
                         install = InstallCommands(macos = "curl … --pair RC-TEST-CODE",
                                                   linux = "curl … --pair RC-TEST-CODE"))

        override suspend fun cancelPairing(code: String) {
            cancelledCodes.add(code)
            arrival.complete(Unit)
            release.await()
        }

        override suspend fun claimPairingRequest(token: String): PairingClaim =
            PairingClaim(code = "RC-CLAIMED", expiresAt = System.currentTimeMillis() + 600_000)

        /** Returns once `cancelPairing` has been called and is waiting. */
        suspend fun waitForCancel() = arrival.await()

        fun releaseCancel() {
            release.complete(Unit)
            release = CompletableDeferred()
            arrival = CompletableDeferred()
        }
    }
}

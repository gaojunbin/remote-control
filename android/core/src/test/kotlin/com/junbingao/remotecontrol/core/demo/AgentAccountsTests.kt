package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Accounts and quota (A33) on the demo machines. The wire cases of RCCore's suite of this name are
 * `protocol.AgentAccountsTests`; the lines a page writes (`AccountLine`, `QuotaWindow`) are
 * `core-state`'s.
 */
class AgentAccountsTests {
    /**
     * The demo machines cover an account, a key, a failure, nothing and nowhere. The demo holds
     * every shape the page has to draw, because that is what the previews and the UI test read.
     */
    @Test
    fun demoShapes() {
        val mac = assertNotNull(DemoFixtures.agentsWithQuota(deviceID = DemoFixtures.macDeviceID))
        val claude = assertNotNull(mac.firstOrNull { it.agent == "claude" }?.accounts?.firstOrNull())
        assertEquals(3, claude.limits?.size)
        assertEquals("Max 5x", claude.tier)
        val grok = assertNotNull(mac.firstOrNull { it.agent == "grok" }?.accounts?.firstOrNull())
        assertTrue(grok.limits == null && grok.limitsError == null)
        val pi = assertNotNull(mac.firstOrNull { it.agent == "pi" }?.accounts)
        assertEquals(2, pi.size)
        assertTrue(pi[1].method == AccountMethod.apiKey && pi[1].endpoint == "api.relay.example")

        val laptop = assertNotNull(DemoFixtures.agentsWithQuota(deviceID = DemoFixtures.laptopDeviceID))
        val expired = assertNotNull(laptop.firstOrNull { it.agent == "claude" }?.accounts?.firstOrNull())
        assertNull(expired.limits)
        assertEquals(false, expired.limitsError?.isEmpty())
        assertEquals(emptyList(), laptop.firstOrNull { it.agent == "grok" }?.accounts)

        // Nothing carrying a window ever reaches the stored device list.
        for (device in DemoFixtures.devices) {
            for (agent in device.agents) {
                for (account in agent.accounts.orEmpty()) {
                    assertNull(account.limits)
                    assertNull(account.limitsCheckedAt)
                }
            }
        }
    }
}

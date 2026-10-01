package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Amendment A33: what an agent is signed in with, and what is left of its quota — the wire half,
 * from the protocol's four worked examples. RCCore's suite of this name also holds the words the
 * page writes (`AccountLine`, `QuotaWindow`) and the demo's machines; those cases are in
 * `state/AgentAccountsTests.kt` and `demo/AgentAccountsTests.kt`.
 */
class AgentAccountsTests {
    private fun agentFixture(name: String): AgentInfo = FixtureSource.json("objects/$name").decode()

    /** Claude's account decodes with its tier and three windows, one confined to a model. */
    @Test
    fun claudeAccount() {
        val claude = agentFixture("agent.claude-attach.json")
        val account = assertNotNull(claude.accounts?.firstOrNull())
        assertEquals(1, claude.accounts.size)
        assertEquals("anthropic", account.provider)
        assertEquals(AccountMethod.account, account.method)
        assertEquals("max", account.plan)
        assertEquals("Max 5x", account.tier)
        assertEquals("me@example.com", account.email)
        assertNull(account.endpoint)
        assertNull(account.limitsError)
        assertEquals(1_789_470_000_000, account.limitsCheckedAt)

        val limits = assertNotNull(account.limits)
        assertEquals(listOf(300, 10080, 10080), limits.map { it.windowMinutes })
        assertEquals(listOf(16.0, 54.0, 64.0), limits.map { it.usedPercent })
        assertEquals(listOf(null, null, "Fable"), limits.map { it.scope })
        assertEquals(1_789_487_040_000, limits[0].resetsAt)
    }

    /** Codex's is one account on a plan with the daemon's two windows. */
    @Test
    fun codexAccount() {
        val account = assertNotNull(agentFixture("agent.codex-daemon.json").accounts?.firstOrNull())
        assertEquals("openai", account.provider)
        assertEquals("pro", account.plan)
        assertNull(account.tier)
        assertEquals(listOf(300, 10080), account.limits?.map { it.windowMinutes })
    }

    /**
     * Grok Build reports an account with no plan, no windows and no failure. The vendor exposes no
     * windows the device can read, so `limits` is absent and there is no error either: the page
     * draws the line and no meter.
     */
    @Test
    fun grokAccount() {
        val account = assertNotNull(agentFixture("agent.grok.json").accounts?.firstOrNull())
        assertEquals("xai", account.provider)
        assertEquals(AccountMethod.account, account.method)
        assertNull(account.plan)
        assertEquals("me@example.com", account.email)
        assertNull(account.limits)
        assertNull(account.limitsError)
    }

    /** pi holds one credential per provider, and a key names the host it is sent to. */
    @Test
    fun piAccounts() {
        val accounts = assertNotNull(agentFixture("agent.pi.json").accounts)
        assertEquals(2, accounts.size)
        assertEquals("anthropic", accounts[0].provider)
        assertEquals(AccountMethod.account, accounts[0].method)
        assertEquals(2, accounts[0].limits?.size)
        assertEquals("openai", accounts[1].provider)
        assertEquals(AccountMethod.apiKey, accounts[1].method)
        assertEquals("api.relay.example", accounts[1].endpoint)
        assertNull(accounts[1].limits)
    }

    /**
     * A published device carries accounts without limits. `hello` and `agents.updated` carry the
     * credentials without the windows, which is why the page asks for them itself.
     */
    @Test
    fun publishedDeviceHasNoLimits() {
        val device = assertIs<AppFrame.DeviceUpdated>(AppFrame(data = FixtureSource.data("app/device.updated.json")),
                                                      "device.updated does not decode as a device").device
        for (agent in device.agents) {
            for (account in agent.accounts.orEmpty()) {
                assertNull(account.limits)
                assertNull(account.limitsError)
                assertNull(account.limitsCheckedAt)
            }
        }
        assertEquals("Max 5x", device.agent("claude")?.accounts?.firstOrNull()?.tier)
    }

    /** And a device.agents reply carries the same accounts with them. */
    @Test
    fun replyCarriesLimits() {
        val result = assertNotNull(FixtureSource.json("app/reply.device.agents.json")["result"]).decode<AgentsResult>()
        assertEquals(2, result.agents.size)
        assertEquals(3, result.agents[0].accounts?.firstOrNull()?.limits?.size)
        assertEquals(2, result.agents[1].accounts?.firstOrNull()?.limits?.size)
    }

    /**
     * Absent, empty and populated are three different answers. An older device says nothing about
     * accounts at all, and "did not look" has to stay distinguishable from "signed in nowhere".
     */
    @Test
    fun threeAnswers() {
        val absent = JSONValue.parse("""{"agent":"claude","available":true}""".encodeToByteArray()).decode<AgentInfo>()
        assertNull(absent.accounts)

        val empty = JSONValue.parse("""{"agent":"claude","available":true,"accounts":[]}""".encodeToByteArray())
            .decode<AgentInfo>()
        assertEquals(emptyList(), empty.accounts)
    }

    /** The windows are dropped for storage, and put back for the page. */
    @Test
    fun limitsAreNotStored() {
        val claude = agentFixture("agent.claude-attach.json")
        val account = assertNotNull(claude.accounts?.firstOrNull())
        val stored = account.withoutLimits
        assertNull(stored.limits)
        assertNull(stored.limitsCheckedAt)
        assertEquals(account.tier, stored.tier)
        assertEquals(listOf(stored), claude.with(accounts = listOf(stored)).accounts)
        assertNull(claude.with(accounts = null).accounts)
    }
}

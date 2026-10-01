package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.demo.DemoFixtures.laptopDeviceID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.macDeviceID
import com.junbingao.remotecontrol.core.demo.DemoFixtures.now
import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentLimit

/** Accounts and quota (A33), part of [DemoFixtures]. */
sealed interface DemoQuotaFixtures {
    /**
     * What `device.agents` answers for one machine: the same credentials the device list already
     * carries, with the rate-limit windows the device read for them. Nothing here reaches the
     * stored device, which is the whole point of asking for them on demand.
     *
     * The four shapes a page has to draw are spread across the demo machines: an account with
     * windows, an account whose vendor exposes none (Grok Build), an account the device could not
     * read (an expired token), and a key, which never has a window to measure.
     */
    fun agentsWithQuota(deviceID: String): List<AgentInfo>? = when (deviceID) {
        macDeviceID -> listOf(
            DemoFixtures.claude.with(accounts = listOf(anthropicMax.with(limits = claudeWindows))),
            DemoFixtures.codex.with(accounts = listOf(openAIPro.with(limits = codexWindows))),
            DemoFixtures.grok,
            DemoFixtures.pi.with(accounts = listOf(piAnthropic.with(limits = piWindows), relayedKey)),
        )
        laptopDeviceID -> listOf(
            DemoFixtures.claudeWithoutShim.with(accounts = listOf(
                anthropicPro.with(limitsError = "signed-in token expired; open Claude Code once to refresh it"),
            )),
            DemoFixtures.grokWithoutLeader,
        )
        else -> null
    }
}

private val anthropicMax: AgentAccount
    get() = AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max",
                         tier = "Max 5x", email = "me@example.com")

private val anthropicPro: AgentAccount
    get() = AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "pro", email = "me@example.com")

private val openAIPro: AgentAccount
    get() = AgentAccount(provider = "openai", method = AccountMethod.account, plan = "pro", email = "me@example.com")

private val piAnthropic: AgentAccount
    get() = AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max", email = "me@example.com")

private val relayedKey: AgentAccount
    get() = AgentAccount(provider = "openai", method = AccountMethod.apiKey, endpoint = "api.relay.example")

/** A five-hour window, a week, and the week one model is confined to. */
private val claudeWindows: List<AgentLimit>
    get() = listOf(
        AgentLimit(windowMinutes = 300, usedPercent = 16.0, resetsAt = now + 7_200_000),
        AgentLimit(windowMinutes = 10080, usedPercent = 54.0, resetsAt = now + 205_200_000),
        AgentLimit(windowMinutes = 10080, scope = "Fable", usedPercent = 64.0, resetsAt = now + 205_200_000),
    )

/** The shared daemon's two, the second of them nearly spent. */
private val codexWindows: List<AgentLimit>
    get() = listOf(
        AgentLimit(windowMinutes = 300, usedPercent = 37.0, resetsAt = now + 5_400_000),
        AgentLimit(windowMinutes = 10080, usedPercent = 93.0, resetsAt = now + 291_600_000),
    )

private val piWindows: List<AgentLimit>
    get() = listOf(
        AgentLimit(windowMinutes = 300, usedPercent = 8.0, resetsAt = now + 7_200_000),
        AgentLimit(windowMinutes = 10080, usedPercent = 100.0, resetsAt = now + 205_200_000),
    )

/*
 * Amendment A33: the two shapes a `device.agents` reply adds to a credential the device list
 * already carries — the windows it read, or why it could not. They live here because only this
 * scripted device builds them; a real device sends the whole account at once.
 */

private fun AgentAccount.with(limits: List<AgentLimit>): AgentAccount =
    AgentAccount(provider = provider, method = method, plan = plan, tier = tier, email = email, endpoint = endpoint,
                 limits = limits, limitsCheckedAt = DemoFixtures.checkedNow)

private fun AgentAccount.with(limitsError: String): AgentAccount =
    AgentAccount(provider = provider, method = method, plan = plan, tier = tier, email = email, endpoint = endpoint,
                 limitsError = limitsError, limitsCheckedAt = DemoFixtures.checkedNow)

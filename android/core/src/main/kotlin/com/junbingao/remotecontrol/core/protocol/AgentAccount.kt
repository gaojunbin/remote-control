package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Amendment A33: how an agent is signed in with a vendor. */
@JvmInline
@Serializable(with = AccountMethod.Serializer::class)
value class AccountMethod(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    companion object {
        /** The vendor's own subscription account, signed in with OAuth. */
        val account = AccountMethod("account")

        /** A key, possibly pointed at a third-party endpoint. */
        val apiKey = AccountMethod("api_key")
    }

    object Serializer : WireEnumSerializer<AccountMethod>("AccountMethod", ::AccountMethod)
}

/** Amendment A33: one rate-limit window of a vendor account, as the device read it for a `device.agents` reply. */
@Serializable
data class AgentLimit(
    /** The window's length: 300 for five hours, 10080 for a week. */
    @SerialName("window_minutes") val windowMinutes: Int = 0,
    /** What the window is confined to when it is not everything, in the vendor's words — the model a weekly limit applies to. */
    val scope: String? = null,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
    @SerialName("resets_at") val resetsAt: Long? = null,
)

/**
 * Amendment A33: one credential an agent holds on a device — whose it is, how it signs in, and
 * in a `device.agents` reply what is left of its quota.
 *
 * `limits` is present only in that reply: `hello` and `agents.updated` carry accounts read from
 * local files, which change rarely. An account with neither `limits` nor `limits_error` after a
 * reply is one whose vendor exposes no windows the device can read, and the page draws no meter
 * for it at all.
 */
@Serializable
data class AgentAccount(
    /** The vendor the credential belongs to, as the agent names it: `anthropic`, `openai`, `xai`, or another id. */
    val provider: String = "",
    val method: AccountMethod = AccountMethod.account,
    /** The plan word the vendor records, lowercase as reported. Null or absent when the agent records none. */
    val plan: String? = null,
    /**
     * A finer tier when the vendor exposes one, in words the device vouches for: "Max 5x" for
     * Claude's rate-limit tier id. Shown exactly as it arrived, because the device already put it
     * into words.
     */
    val tier: String? = null,
    val email: String? = null,
    /** For an `api_key`: the host the key is sent to when it is not the vendor's own. Host only, never a path or a secret. */
    val endpoint: String? = null,
    val limits: List<AgentLimit>? = null,
    /** Why `limits` is missing after the device tried, in the device's own words, one line. */
    @SerialName("limits_error") val limitsError: String? = null,
    @SerialName("limits_checked_at") val limitsCheckedAt: Long? = null,
) {
    /**
     * The same credential as the device list stores it: what an agent holds changes rarely, and
     * the windows it is spending are a question asked on demand, so they never reach the stored
     * device.
     */
    val withoutLimits: AgentAccount
        get() = AgentAccount(provider = provider, method = method, plan = plan, tier = tier,
                             email = email, endpoint = endpoint)
}

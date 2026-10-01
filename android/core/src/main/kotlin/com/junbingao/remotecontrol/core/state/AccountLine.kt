package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount

/**
 * Amendment A33: the one line under an agent that says how it is signed in.
 *
 * `docs/DESIGN.md` § "A device has a page": the vendor's name, then the plan, the tier and the
 * email, each only when the device reported it; *Anthropic API key*, or *Anthropic API key · host*
 * when the key goes somewhere that is not the vendor; *Not signed in* when the device found
 * neither.
 *
 * Everything but the vendor's name is the device's own word: a plan, a tier, an email and a host
 * are data, never translated and never mapped through a table. The plan alone is raised at its
 * first letter, because the vendor records it lowercase and the device passes it on as it found
 * it; the tier arrives in words the device already vouches for (A33).
 */
object AccountLine {
    const val separator = " · "

    /**
     * The vendors this build has a name for. An id it does not know is printed as itself, so a
     * device that grows a fifth vendor needs no new app.
     */
    private val vendors = mapOf("anthropic" to "Anthropic", "openai" to "OpenAI", "xai" to "xAI")

    fun vendorName(provider: String): String = vendors[provider] ?: provider

    /** The line itself, for one credential. */
    fun text(account: AgentAccount): String {
        val parts = mutableListOf<String>()
        val vendor = vendorName(account.provider)
        when (account.method) {
            AccountMethod.apiKey -> {
                parts.add(if (vendor.isEmpty()) L10n.string("API key") else L10n.string("%@ API key", vendor))
                account.endpoint?.takeIf { it.isNotEmpty() }?.let(parts::add)
            }
            else -> {
                parts.add(if (vendor.isEmpty()) L10n.string("Account") else L10n.string("%@ account", vendor))
                account.plan?.takeIf { it.isNotEmpty() }?.let { parts.add(raised(it)) }
                account.tier?.takeIf { it.isNotEmpty() }?.let(parts::add)
                account.email?.takeIf { it.isNotEmpty() }?.let(parts::add)
            }
        }
        return parts.joinToString(separator)
    }

    /** The vendor's own word with its first letter raised: `max` reads as *Max* beside a name and a tier that are already written for a reader. */
    internal fun raised(word: String): String {
        val characters = word.characters()
        val first = characters.firstOrNull() ?: return word
        return first.uppercase() + characters.drop(1).joinToString("")
    }

    /** What a device says when it looked and found nothing signed in. */
    val notSignedIn: String get() = L10n.string("Not signed in")
}

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.demo.DemoFixtures.adminUsername
import com.junbingao.remotecontrol.core.demo.DemoFixtures.disabledUsername
import com.junbingao.remotecontrol.core.demo.DemoFixtures.memberUsername
import com.junbingao.remotecontrol.core.demo.DemoFixtures.now
import com.junbingao.remotecontrol.core.demo.DemoFixtures.servedBuild
import com.junbingao.remotecontrol.core.demo.DemoFixtures.servedClientVersion
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.PolishInfo
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.AppVersion
import com.junbingao.remotecontrol.core.transport.ClientBuild
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.InstallCommands
import com.junbingao.remotecontrol.core.transport.PairingClaim
import com.junbingao.remotecontrol.core.transport.PairingGrant
import com.junbingao.remotecontrol.core.transport.PolishModel
import com.junbingao.remotecontrol.core.transport.PolishModelsResponse
import com.junbingao.remotecontrol.core.transport.PushConfig

/** What the demo gateway says about itself, and the accounts it holds, part of [DemoFixtures]. */
sealed interface DemoGatewayFixtures {
    /**
     * The demo gateway transcribes, so both Transcribe choices mean what they say in it; and its
     * provider detects the language, as every gateway's has since A44.
     */
    fun config(minimumAppVersion: String = AppBuild.version): GatewayConfig =
        GatewayConfig(publicOrigin = "https://demo.remote-control.invalid",
                      stt = STTConfig(enabled = true, languages = listOf("auto")),
                      push = PushConfig(webEnabled = true, apnsEnabled = true),
                      version = "0.1.0-demo",
                      polish = PolishInfo(enabled = true),
                      apps = apps(minimumAppVersion = minimumAppVersion),
                      client = ClientBuild(version = servedClientVersion, build = servedBuild,
                                           url = "/dist/rc_client-latest.whl"))

    /**
     * Amendment A31: what the demo gateway says the oldest app it works with is. It is this build
     * by default, so the demo is never blocked; a demo asked for a higher one is how the blocking
     * screen is driven. The Mac app's entry (A45) moves with the iPhone app's, since one source
     * tree ships both, and so do the Android and Windows apps' (A46), which one round ships on the
     * same version. Those two are not on TestFlight: their builds are on the repository's releases.
     */
    fun apps(minimumAppVersion: String = AppBuild.version): AppsInfo {
        val apple = AppSupport(minimumVersion = minimumAppVersion,
                               updateURL = "https://testflight.apple.com/join/EXAMPLE")
        val released = AppSupport(minimumVersion = minimumAppVersion,
                                  updateURL = "https://github.com/gaojunbin/remote-control/releases/latest")
        return AppsInfo(ios = apple, macos = apple, android = released, windows = released)
    }

    /** One major version above this build, which is a minimum no installed app can meet. */
    val laterAppVersion: String get() = "${AppVersion(AppBuild.version).major + 1}.0.0"

    /** Amendment A29: the two models the demo's polish provider offers. */
    val polishModels: PolishModelsResponse
        get() = PolishModelsResponse(models = listOf(PolishModel(id = "gpt-4.1-mini", label = "gpt-4.1-mini"),
                                                     PolishModel(id = "gpt-4.1", label = "gpt-4.1")))

    /**
     * A stand-in for the model: the fillers go, a doubled word goes, and the first letter is
     * capitalised. Enough that the replacement can be watched happening, and it reaches nothing.
     */
    fun polished(text: String): String {
        val fillers = setOf("um", "uh", "erm", "like", "呃", "那个")
        val words = mutableListOf<String>()
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            val bare = word.trimmingPunctuation().lowercase()
            if (bare in fillers) continue
            if (words.lastOrNull()?.lowercase() == word.lowercase()) continue
            words.add(word)
        }
        val joined = words.joinToString(" ")
        if (joined.isEmpty()) return joined
        val first = Character.charCount(joined.codePointAt(0))
        return joined.substring(0, first).uppercase() + joined.substring(first)
    }

    val pairingGrant: PairingGrant
        get() = PairingGrant(code = "RC-7K42-QX9M", expiresAt = now + 600_000,
                             install = InstallCommands(
                                 macos = "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M",
                                 linux = "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M"))

    /** Amendment A23: the code a claimed host is given, and the link the host printed to ask for it. */
    val pairingClaim: PairingClaim get() = PairingClaim(code = "RC-9M27-TB4K", expiresAt = now + 600_000)

    /** Amendment A24: the gateway's accounts — its operator, a member and a disabled one. */
    val users: List<UserRecord>
        get() = listOf(
            UserRecord(username = adminUsername, role = UserRole.admin, state = UserState.active,
                       createdAt = now - 8_640_000, lastLoginAt = now - 120_000, devices = 3),
            UserRecord(username = memberUsername, role = UserRole.member, state = UserState.active,
                       createdAt = now - 4_320_000, lastLoginAt = now - 172_800_000, devices = 1),
            UserRecord(username = disabledUsername, role = UserRole.member, state = UserState.disabled,
                       createdAt = now - 2_160_000, lastLoginAt = null, devices = 0),
        )
}

/** `trimmingCharacters(in: .punctuationCharacters)`: Unicode's P categories off both ends. */
private fun String.trimmingPunctuation(): String {
    var start = 0
    var end = length
    while (start < end && isPunctuation(codePointAt(start))) start += Character.charCount(codePointAt(start))
    while (end > start && isPunctuation(codePointBefore(end))) end -= Character.charCount(codePointBefore(end))
    return substring(start, end)
}

private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt() -> true
    else -> false
}

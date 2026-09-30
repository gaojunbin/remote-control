package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.HealthResponse
import com.junbingao.remotecontrol.core.transport.PolishModelsResponse
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishResponse
import com.junbingao.remotecontrol.core.transport.PolishRole
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlin.test.Test

/**
 * `ios/Verification/PolishChecks.swift`, the contract's half: the fixtures A29 froze, A30's
 * sources and A31's comparison. The span, the context, the composer's flow, the Settings
 * preferences and the connection's rule drive `DictationPolish`, `ChatStore`, `SettingsStore` and
 * `ConnectionStore`, and are `core-state`'s to add here.
 */
class PolishChecks {
    /** A29, the request the contract fixes. */
    @Test
    fun contract() {
        val checks = CheckRunner("polish")
        // Amendment A44: words the gateway transcribed carry `auto`, and words the phone heard
        // carry the language it listened for.
        checks.equal(DictationLanguage.polishHint(backend = VoiceBackend.gateway, listening = "en"), "auto",
                     "the gateway detected the language, so the hint says auto")
        checks.equal(DictationLanguage.polishHint(backend = VoiceBackend.onDevice, listening = "ja"), "ja",
                     "the phone's recogniser was told one, so the hint names it")
        checks.equal(DictationLanguage.polishHint(backend = VoiceBackend.onDevice, listening = "auto"), "zh",
                     "and a legacy auto on the phone is the Chinese it was heard as")

        // The frozen fixtures decode into the models the app sends and reads.
        checks.noThrow("http/polish.request.json decodes as a polish request") {
            val decoded = FixtureSource.json("http/polish.request.json").decode<PolishRequest>()
            if (decoded.strength != PolishStrength.strong || decoded.context.size != 2 ||
                decoded.context.firstOrNull()?.role != PolishRole.user || decoded.context.lastOrNull()?.role != PolishRole.assistant) {
                throw ProtocolFailure.Malformed("polish request fields")
            }
        }
        checks.noThrow("http/polish.response.json decodes as a polish response") {
            if (FixtureSource.json("http/polish.response.json").decode<PolishResponse>().text.isEmpty()) {
                throw ProtocolFailure.Malformed("polish response text")
            }
        }
        checks.noThrow("http/polish.models.response.json decodes as the provider's models") {
            val models = FixtureSource.json("http/polish.models.response.json").decode<PolishModelsResponse>().models
            if (models.size != 2 || models.firstOrNull()?.id != "gpt-4.1-mini") throw ProtocolFailure.Malformed("polish models")
        }

        // A gateway older than A29 says nothing about polishing, which is no.
        checks.noThrow("a hello without polish reports it disabled") {
            val hello = jsonObjectOf("type" to "hello", "protocol" to 1, "gateway_version" to "0.0.9",
                                     "user" to mapOf("username" to "me"), "devices" to emptyList<Any>(),
                                     "sessions" to emptyList<Any>(), "stt" to mapOf("enabled" to false),
                                     "server_time" to 0).decode<HelloFrame>()
            if (hello.polish.enabled || hello.apps != null) throw ProtocolFailure.Malformed("an older gateway asks for nothing")
        }
        checks.expect(runCatching { FixtureSource.json("app/hello.json").decode<HelloFrame>() }.getOrNull()?.polish?.enabled == true,
                      "the frozen hello reports the gateway can polish")
        checks.expect(runCatching { FixtureSource.json("http/config.response.json").decode<GatewayConfig>() }.getOrNull()
                          ?.polish?.enabled == true,
                      "and so does the frozen config response")
        checks.assertAll()
    }

    /** A30, words another agent put in the conversation. */
    @Test
    fun agentMessages() {
        val checks = CheckRunner("polish")
        checks.noThrow("events/user_message.agent.json decodes as a message nobody typed") {
            val event = FixtureSource.json("events/user_message.agent.json").decode<SessionEvent>()
            val payload = (event.body as? SessionEventBody.UserMessage)?.payload
            if (payload == null || payload.source != EventSource.agent) {
                throw ProtocolFailure.Malformed("the fixture is a user message from an agent")
            }
            if (!payload.text.startsWith("recon-ios:")) throw ProtocolFailure.Malformed("the text says who reported and what they said")
        }
        checks.equal(EventSource.agent.rawValue, "agent", "the wire value is `agent`")
        checks.expect(EventSource.agent.isElsewhere, "a turn another agent started is read as a turn nobody here started")
        checks.expect(EventSource.terminal.isElsewhere, "exactly as a terminal-started one is")
        checks.expect(!EventSource.remote.isElsewhere, "and a turn this app started is not")
        checks.noThrow("a turn_started with trigger agent decodes") {
            val payload = jsonObjectOf("turn_id" to "t", "trigger" to "agent").decode<TurnStartedPayload>()
            if (payload.trigger != EventSource.agent || !payload.trigger.isElsewhere) throw ProtocolFailure.Malformed("turn_started trigger")
        }
        // A value no build has heard of must not crash an app mid-transcript.
        checks.noThrow("an unknown source decodes as itself") {
            val payload = jsonObjectOf("text" to "hi", "source" to "seance").decode<UserMessagePayload>()
            if (payload.source.rawValue != "seance") throw ProtocolFailure.Malformed("unknown source")
        }
        checks.assertAll()
    }

    /** A31, the comparison. */
    @Test
    fun versions() {
        val checks = CheckRunner("polish")
        checks.expect(AppVersion("1.2.3") < AppVersion("1.10.0"), "ten is a number, not a character")
        checks.expect(AppVersion("0.9.9") < AppVersion("1.0.0"), "a major version wins")
        checks.expect(AppVersion("1.2") == AppVersion("1.2.0"), "a missing part is zero")
        checks.expect(AppVersion("banana") == AppVersion("0.0.0"), "and so is anything unreadable")
        checks.equal(AppVersion("2.0.1").toString(), "2.0.1", "a version says itself back")

        val apps = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0", updateURL = "https://testflight.apple.com/join/X"))
        checks.expect(AppUpdateRequirement.of(apps, current = "0.9.0") != null, "a build below the minimum has to update")
        checks.equal(AppUpdateRequirement.of(apps, current = "1.0.0"), null, "a build that meets it does not")
        checks.equal(AppUpdateRequirement.of(apps, current = "1.4.0"), null, "and neither does a newer one")
        checks.equal(AppUpdateRequirement.of(null, current = "0.0.1"), null, "a gateway that states no minimum asks for nothing")
        checks.equal(AppUpdateRequirement.of(AppsInfo(ios = null), current = "0.0.1"), null, "and neither does one that names no app")
        checks.equal(AppUpdateRequirement.of(apps, current = "0.9.0")?.updateURL?.toString(),
                     "https://testflight.apple.com/join/X", "the link the operator named is kept")
        val insecure = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0", updateURL = "itms://apps"))
        checks.equal(AppUpdateRequirement.of(insecure, current = "0.9.0")?.updateURL, null, "a link that is not https is not followed")

        // Amendments A45 and A46: each app reads its own entry and never another's.
        val both = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0"), macos = AppSupport(minimumVersion = "2.0.0"))
        checks.expect(AppUpdateRequirement.of(both, app = InstalledApp.macos, current = "1.5.0") != null,
                      "the Mac app is held to the Mac app's entry")
        checks.equal(AppUpdateRequirement.of(both, app = InstalledApp.ios, current = "1.5.0"), null, "and the iPhone app is not")
        checks.equal(AppUpdateRequirement.of(AppsInfo(ios = AppSupport(minimumVersion = "9.0.0")),
                                             app = InstalledApp.macos, current = "1.0.0"), null,
                     "a gateway that names only the iPhone app asks nothing of the Mac app")
        checks.equal(AppUpdateRequirement.of(both, app = InstalledApp.android, current = "0.0.1"), null,
                     "nor of the Android app")

        checks.noThrow("http/health.response.json carries the minimum app build") {
            if (FixtureSource.json("http/health.response.json").decode<HealthResponse>().apps?.ios?.minimumVersion != "0.1.0") {
                throw ProtocolFailure.Malformed("health apps")
            }
        }
        checks.noThrow("and the Mac app's entry beside it (A45)") {
            if (FixtureSource.json("http/health.response.json").decode<HealthResponse>().apps?.macos?.minimumVersion != "1.11.0") {
                throw ProtocolFailure.Malformed("health apps.macos")
            }
        }
        checks.noThrow("and the Android and Windows apps' entries (A46)") {
            val apps = FixtureSource.json("http/health.response.json").decode<HealthResponse>().apps
            if (apps?.android?.minimumVersion != "1.12.0" || apps.windows?.minimumVersion != "1.12.0") {
                throw ProtocolFailure.Malformed("health apps.android and apps.windows")
            }
        }
        checks.noThrow("and so do the config response and the hello") {
            val config = FixtureSource.json("http/config.response.json").decode<GatewayConfig>()
            val hello = FixtureSource.json("app/hello.json").decode<HelloFrame>()
            if (config.apps?.ios == null || hello.apps?.ios == null || config.apps.android == null || hello.apps.windows == null) {
                throw ProtocolFailure.Malformed("config and hello apps")
            }
        }
        checks.assertAll()
    }
}

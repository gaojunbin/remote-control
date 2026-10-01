package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
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
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test

/**
 * `ios/Verification/PolishChecks.swift`: amendments A29, A30 and A31 — dictation polish, words
 * another agent put in the conversation, and the oldest app build a gateway will talk to. All three
 * are rules rather than screens: the span arithmetic and the context the model is given, the flow
 * the composer drives, the caption a message nobody typed carries, and the comparison that decides
 * whether the app may go on at all. The demo's own line of `agentMessages` is in
 * `demo/PolishChecks.kt`.
 */
class PolishChecks {
    /** A29, the span and its two drafts. */
    @Test
    fun span() {
        val checks = CheckRunner("polish")
        val empty = DictationSpan(base = "", dictated = "um the the green blinking thing")
        checks.equal(empty.dictatedDraft, "um the the green blinking thing", "a dictation into an empty field is the whole draft")
        checks.equal(empty.polishedDraft("The pulsing status dot."), "The pulsing status dot.",
                     "and the answer replaces the whole of it")

        val typed = DictationSpan(base = "Two things:", dictated = "um fix the dot")
        checks.equal(typed.dictatedDraft, "Two things:\num fix the dot", "a dictation after typed words goes on its own line")
        checks.equal(typed.polishedDraft("fix the dot"), "Two things:\nfix the dot", "and only the dictated half is replaced")

        val spaced = DictationSpan(base = "Two things: ", dictated = "fix the dot")
        checks.equal(spaced.dictatedDraft, "Two things: fix the dot", "a draft that already ends in whitespace takes no newline")

        // The join is the composer's own, so a span rebuilds exactly the draft dictation produced
        // rather than something close to it.
        val target = VoiceDraftTarget(account = "a", deviceID = "d", sessionID = "s")
        checks.equal(target.inserting("um fix the dot", into = "Two things:", currentTarget = target), typed.dictatedDraft,
                     "the span's join is the one dictation itself uses")

        checks.equal(DictationPolish.applyPolished(current = typed.dictatedDraft, span = typed, polished = "  fix the dot.  "),
                     "Two things:\nfix the dot.", "the answer is trimmed and lands in the dictated span")
        checks.equal(DictationPolish.applyPolished(current = "something else", span = typed, polished = "fix the dot."), null,
                     "a field that has moved on keeps what the person put in it")
        checks.equal(DictationPolish.applyPolished(current = typed.dictatedDraft, span = typed, polished = "   "), null,
                     "an empty answer is no answer")
        checks.equal(DictationPolish.undoPolished(current = "Two things:\nfix the dot.", span = typed, polished = "fix the dot."),
                     typed.dictatedDraft, "Undo puts the dictated words back")
        checks.equal(DictationPolish.undoPolished(current = "edited by hand", span = typed, polished = "fix the dot."), null,
                     "and puts nothing back once the field holds something else")

        checks.expect(DictationPolish.canPolish("um so"), "words can be polished")
        checks.expect(!DictationPolish.canPolish("   "), "whitespace cannot")
        checks.expect(!DictationPolish.canPolish("a".repeat(8193)), "and neither can a dictation past the contract's limit")
        checks.assertAll()
    }

    /** A29, what the model is told. */
    @Test
    fun context() {
        val checks = CheckRunner("polish")
        val timeline = Timeline()
        var seq = 0
        fun next(): Int {
            seq += 1
            return seq
        }
        for (index in 1..25) {
            timeline.apply(SessionEvent(seq = next(), ts = 0, kind = SessionEvent.userMessageKind, blockID = "u$index",
                                        body = SessionEventBody.UserMessage(UserMessagePayload(text = "question $index"))))
            timeline.apply(SessionEvent(seq = next(), ts = 0, kind = SessionEvent.assistantTextKind, blockID = "a$index",
                                        body = SessionEventBody.AssistantText(StreamTextPayload(text = "answer $index", done = true))))
        }
        // A tool call is not conversation, and neither is a turn marker.
        timeline.apply(SessionEvent(seq = next(), ts = 0, kind = SessionEvent.toolCallKind, blockID = "t1",
                                    body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Bash", kind = ToolKind.shell,
                                                                                     title = "make test",
                                                                                     status = ToolStatus.succeeded))))
        timeline.addOptimistic(OptimisticMessage(id = "pending", text = "and one just sent"))

        val items = DictationPolish.context(timeline)
        checks.equal(items.size, 20, "at most twenty messages go with a dictation")
        checks.equal(items.lastOrNull()?.text, "and one just sent", "the newest of them is the send the device has not echoed yet")
        checks.equal(items.lastOrNull()?.role, PolishRole.user, "which is the person's own")
        checks.equal(items.firstOrNull()?.text, "answer 16", "oldest first, counting back from the newest")
        checks.expect(items.all { it.text.isNotEmpty() }, "and nothing empty is sent")

        val long = Timeline()
        long.apply(SessionEvent(seq = 1, ts = 0, kind = SessionEvent.userMessageKind, blockID = "u",
                                body = SessionEventBody.UserMessage(UserMessagePayload(text = "x".repeat(5000)))))
        checks.equal(DictationPolish.context(long).firstOrNull()?.text?.length, 4000,
                     "each message is trimmed to what the contract takes")
        checks.assertAll()
    }

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

    /** A29, the flow the composer drives. */
    @Test
    fun flow() = runTest {
        val checks = CheckRunner("polish")
        fun store(): ChatStore {
            val chat = ChatStore(session = DemoFixtures.sessions[0], channel = AcceptingChannel(), tasks = backgroundScope)
            chat.deviceOnline = true
            return chat
        }
        val span = DictationSpan(base = "Two things:", dictated = "um fix the the dot")

        // Success: the dictated span alone is replaced, and the note offers Undo.
        val success = store()
        success.draft = span.dictatedDraft
        success.polishService = { request -> "Fix the dot. (${request.strength.rawValue}, ${request.context.size} messages)" }
        success.polish(span = span, model = "gpt-4.1-mini", strength = PolishStrength.moderate, language = "en")
        checks.equal(success.polishPhase, PolishPhase.Polishing, "the request is out")
        checks.equal(success.statusLine, "Polishing…", "and the status line says so")
        settle { success.polishPhase != PolishPhase.Polishing }
        checks.equal(success.draft, "Two things:\nFix the dot. (moderate, 0 messages)",
                     "the answer lands in the dictated span and nowhere else")
        checks.expect(success.polishPhase is PolishPhase.Polished, "the note offers Undo")
        success.undoPolish()
        checks.equal(success.draft, span.dictatedDraft, "Undo puts the dictated words back")
        checks.equal(success.polishPhase, PolishPhase.Idle, "and takes the note with them")

        // An edit ends the note; nothing else does.
        val edited = store()
        edited.draft = span.dictatedDraft
        edited.polishService = { "Fix the dot." }
        edited.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        settle { edited.polishPhase != PolishPhase.Polishing }
        edited.draft += " and the header"
        checks.equal(edited.polishPhase, PolishPhase.Idle, "typing after a polish ends the note")

        // Failure: the words are left exactly as dictated.
        val failed = store()
        failed.draft = span.dictatedDraft
        failed.polishService = { throw TransportError.NotConnected }
        failed.polish(span = span, model = "m", strength = PolishStrength.strong, language = "auto")
        settle { failed.polishPhase != PolishPhase.Polishing }
        checks.equal(failed.polishPhase, PolishPhase.Failed, "a failure says so")
        checks.equal(failed.draft, span.dictatedDraft, "and changes not one word")
        failed.clearPolishNote()
        checks.equal(failed.polishPhase, PolishPhase.Idle, "the line goes once it has been read")

        // A send while polishing sends the words as dictated and drops the answer.
        val sending = store()
        sending.draft = span.dictatedDraft
        sending.polishService = {
            delay(200)
            "Fix the dot."
        }
        sending.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        sending.send()
        checks.equal(sending.polishPhase, PolishPhase.Idle, "sending drops the request")
        checks.equal(sending.draft, "", "and the field is empty behind it")
        delay(350)
        checks.equal(sending.draft, "", "a late answer is not pasted into the next message")

        // Nothing runs without a model, and nothing runs without a service.
        val unconfigured = store()
        unconfigured.draft = span.dictatedDraft
        unconfigured.polishService = { "Fix the dot." }
        unconfigured.polish(span = span, model = "", strength = PolishStrength.moderate, language = "en")
        checks.equal(unconfigured.polishPhase, PolishPhase.Idle, "no model chosen, no request")

        val unserviced = store()
        unserviced.draft = span.dictatedDraft
        unserviced.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        checks.equal(unserviced.polishPhase, PolishPhase.Idle, "no polish service, no request")
        checks.assertAll()
    }

    /** A29, the settings that drive it. */
    @Test
    fun settings() {
        val checks = CheckRunner("polish")
        val defaults = MemoryUserDefaults()
        val store = SettingsStore(defaults = defaults)
        checks.expect(!store.polishEnabled, "polishing is off on a fresh install")
        checks.equal(store.polishModel, "", "with no model chosen")
        checks.equal(store.polishStrength, PolishStrength.moderate, "and the gentler of the two strengths")

        store.remember(origin = "https://rc.example.com", username = "ada")
        store.polishEnabled = true
        store.polishModel = "gpt-4.1-mini"
        store.polishStrength = PolishStrength.strong

        val other = SettingsStore(defaults = defaults)
        other.remember(origin = "https://rc.example.com", username = "bob")
        checks.expect(!other.polishEnabled, "another account on the same gateway starts off")

        other.adopt(origin = "https://rc.example.com", username = "ada")
        checks.expect(other.polishEnabled, "and the first account's choices are still theirs")
        checks.equal(other.polishModel, "gpt-4.1-mini", "including the model")
        checks.equal(other.polishStrength, PolishStrength.strong, "and the strength")
        checks.equal(PolishStrength.allCases.map { it.rawValue }, listOf("moderate", "strong"),
                     "the control offers two strengths, gentler first")
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

    /** A31, the rule the connection applies. */
    @Test
    fun minimumVersion() = runTest {
        val checks = CheckRunner("polish")
        val directory = scratchDirectory("polish-minimum")
        try {
            // The demo asks for exactly this build, so nothing is blocked.
            val running = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory),
                                          makeAPI = { demoGateway(resumeDelay = DemoGateway.defaultResumeDelay) },
                                          makeChannel = { demoGateway(resumeDelay = DemoGateway.defaultResumeDelay) })
            val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
            running.enterDemo(api = gateway, channel = gateway)
            settle { running.hasSnapshot }
            checks.equal(running.updateRequired, null, "a gateway this build meets blocks nothing")
            checks.expect(running.polish.enabled, "and the demo can polish a dictation")

            // A gateway that wants a newer build stops the app wherever it is.
            val demanding = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay,
                                        minimumAppVersion = DemoFixtures.laterAppVersion)
            val blocked = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { demanding },
                                          makeChannel = { demanding })
            blocked.enterDemo(api = demanding, channel = demanding)
            settle { blocked.updateRequired != null }
            checks.equal(blocked.updateRequired?.minimum, AppVersion(DemoFixtures.laterAppVersion),
                         "the gateway's minimum is what the screen shows")
            checks.equal(blocked.updateRequired?.current, AppVersion(AppBuild.version), "beside this build's own version")

            // `/api/health` answers before anyone has signed in, which is the point of it: the
            // sign-in form is blocked too.
            val unsigned = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { demanding },
                                           makeChannel = { demanding })
            unsigned.registrationOpen(origin = "https://rc.example.com")
            checks.expect(unsigned.updateRequired != null, "the public health route blocks the app before it has a credential")

            // Amendments A45 and A46: each app's store reads its own entry.
            for ((app, label) in listOf(InstalledApp.macos to "the Mac app is stopped by its own minimum",
                                        InstalledApp.android to "and so is the Android app",
                                        InstalledApp.windows to "and the Windows app")) {
                val store = ConnectionStore(tasks = backgroundScope, installedApp = app, cache = LocalCache(directory),
                                            makeAPI = { demanding }, makeChannel = { demanding })
                store.registrationOpen(origin = "https://rc.example.com")
                checks.expect(store.updateRequired != null, label)
            }

            blocked.signOut()
            checks.equal(blocked.updateRequired, null, "signing out is the way to another gateway, so it clears the screen")
        } finally {
            directory.deleteRecursively()
        }
        checks.assertAll()
    }

    /** A channel that takes every message, so a send under test ends where a send ends and not in the refusal path that puts the words back. */
    private class AcceptingChannel : InertChannel() {
        override suspend fun request(request: GatewayRequest): JsonElement =
            if (request.type == "session.send") JSONValue.encode(SendResult(accepted = SendAcceptance.sent)) else JSONValue.emptyObject
    }
}

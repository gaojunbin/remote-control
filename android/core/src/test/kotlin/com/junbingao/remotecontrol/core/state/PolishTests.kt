package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.transport.HealthResponse
import com.junbingao.remotecontrol.core.transport.PolishRole
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RCCore's `PolishTests.swift` holds four suites. `AgentMessageTests.demo`, which reads the demo's
// fixtures alone, is in `demo/AgentMessageTests.kt`.

/** Amendment A29 — the dictated span is what is polished, and nothing else. */
class DictationPolishTests {
    private val span = DictationSpan(base = "Two things:", dictated = "um fix the the dot")

    /** Only the dictated words are replaced; typed words are left alone. */
    @Test
    fun replacesTheSpan() {
        assertEquals("Two things:\num fix the the dot", span.dictatedDraft)
        assertEquals("Two things:\nFix the dot.",
                     DictationPolish.applyPolished(current = span.dictatedDraft, span = span, polished = " Fix the dot. "))
    }

    /** A field the person has moved on from keeps what they put in it. */
    @Test
    fun leavesAnEditedFieldAlone() {
        assertNull(DictationPolish.applyPolished(current = "something else", span = span, polished = "Fix the dot."))
        assertNull(DictationPolish.applyPolished(current = span.dictatedDraft, span = span, polished = "  "))
    }

    /** Undo restores the words exactly as they were dictated. */
    @Test
    fun undo() {
        val polished = span.polishedDraft("Fix the dot.")
        assertEquals(span.dictatedDraft, DictationPolish.undoPolished(current = polished, span = span, polished = "Fix the dot."))
        assertNull(DictationPolish.undoPolished(current = "typed over", span = span, polished = "Fix the dot."))
    }

    /** Nothing empty and nothing over the contract's limit is sent. */
    @Test
    fun limits() {
        assertTrue(DictationPolish.canPolish("um so"))
        assertFalse(DictationPolish.canPolish("\n  \n"))
        assertFalse(DictationPolish.canPolish("x".repeat(8193)))
    }

    /** The model is given the last twenty messages, oldest first, each trimmed. */
    @Test
    fun context() {
        val timeline = Timeline()
        var seq = 0
        for (index in 1..30) {
            seq += 1
            timeline.apply(SessionEvent(seq = seq, ts = 0, kind = SessionEvent.userMessageKind, blockID = "u$index",
                                        body = SessionEventBody.UserMessage(UserMessagePayload(text = "ask $index"))))
        }
        seq += 1
        timeline.apply(SessionEvent(seq = seq, ts = 0, kind = SessionEvent.assistantTextKind, blockID = "a",
                                    body = SessionEventBody.AssistantText(StreamTextPayload(text = "y".repeat(4500), done = true))))
        val items = DictationPolish.context(timeline)
        assertEquals(20, items.size)
        assertEquals("ask 12", items.first().text)
        assertEquals(PolishRole.assistant, items.last().role)
        assertEquals(4000, items.last().text.length)
    }

    /** The hint is auto for the gateway's words and the phone's language for its own. */
    @Test
    fun languageHint() {
        assertEquals("auto", DictationPolish.request(span = span, model = "m", strength = PolishStrength.strong,
                                                     language = "auto", context = emptyList()).language)
        assertEquals("zh", DictationPolish.request(span = span, model = "m", strength = PolishStrength.strong,
                                                   language = "zh", context = emptyList()).language)
        assertEquals("auto", DictationLanguage.polishHint(backend = VoiceBackend.gateway, listening = "zh"))
        assertEquals("de", DictationLanguage.polishHint(backend = VoiceBackend.onDevice, listening = "de"))
    }
}

/** The composer's half of A29, driven through `ChatStore` with a fake model. */
class DictationPolishFlowTests {
    private val span = DictationSpan(base = "", dictated = "um fix the the dot")

    private fun store(tasks: CoroutineScope): ChatStore {
        val chat = ChatStore(session = DemoFixtures.sessions[0], channel = AcceptingChannel(), tasks = tasks)
        chat.deviceOnline = true
        chat.draft = span.dictatedDraft
        return chat
    }

    /** The answer lands in the field and leaves Undo behind it. */
    @Test
    fun success() = runTest {
        val chat = store(backgroundScope)
        chat.polishService = { "Fix the dot." }
        chat.polish(span = span, model = "gpt-4.1-mini", strength = PolishStrength.moderate, language = "en")
        assertEquals("Polishing…", chat.statusLine)
        settle { chat.polishPhase != PolishPhase.Polishing }
        assertEquals("Fix the dot.", chat.draft)
        chat.undoPolish()
        assertEquals(span.dictatedDraft, chat.draft)
        assertEquals(PolishPhase.Idle, chat.polishPhase)
    }

    /** A failure says so in one line and changes not one word. */
    @Test
    fun failure() = runTest {
        val chat = store(backgroundScope)
        chat.polishService = { throw TransportError.NotConnected }
        chat.polish(span = span, model = "m", strength = PolishStrength.strong, language = "en")
        settle { chat.polishPhase != PolishPhase.Polishing }
        assertEquals(PolishPhase.Failed, chat.polishPhase)
        assertEquals(span.dictatedDraft, chat.draft)
        chat.clearPolishNote()
        assertEquals(PolishPhase.Idle, chat.polishPhase)
    }

    /** A send drops a request still out, and what goes is what was in the field. */
    @Test
    fun sendWins() = runTest {
        val chat = store(backgroundScope)
        chat.polishService = {
            delay(200)
            "Fix the dot."
        }
        chat.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        chat.send()
        assertEquals(PolishPhase.Idle, chat.polishPhase)
        assertTrue(chat.draft.isEmpty())
        delay(350)
        assertTrue(chat.draft.isEmpty())
    }

    /**
     * An edit while the request is out drops it, and the late answer never lands. `docs/DESIGN.md`
     * § "The composer": typing into the field while the spinner is up ends the wait. The person's
     * words win, so the request is dropped and the answer that arrives afterwards is never applied.
     */
    @Test
    fun editWhilePolishing() = runTest {
        val chat = store(backgroundScope)
        chat.polishService = {
            delay(200)
            "Fix the dot."
        }
        chat.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        assertEquals(PolishPhase.Polishing, chat.polishPhase)

        chat.draft += " and the spinner"
        val typed = chat.draft
        assertEquals(PolishPhase.Idle, chat.polishPhase, "the request is dropped the moment they type")
        delay(400)
        assertEquals(typed, chat.draft, "and the answer that was already out is never applied")
        assertEquals(PolishPhase.Idle, chat.polishPhase)
    }

    /** The note goes on the next edit. */
    @Test
    fun editEndsTheNote() = runTest {
        val chat = store(backgroundScope)
        chat.polishService = { "Fix the dot." }
        chat.polish(span = span, model = "m", strength = PolishStrength.moderate, language = "en")
        settle { chat.polishPhase != PolishPhase.Polishing }
        chat.draft += " now"
        assertEquals(PolishPhase.Idle, chat.polishPhase)
    }

    /** A channel that takes every message, so a send under test ends where a send ends rather than in the refusal path. */
    private class AcceptingChannel : InertChannel() {
        override suspend fun request(request: GatewayRequest): JsonElement =
            if (request.type == "session.send") JSONValue.encode(SendResult(accepted = SendAcceptance.sent)) else JSONValue.emptyObject
    }
}

/** Amendment A31 — the version comparison, and the rule built on it. */
class AppVersionTests {
    /** Versions compare part by part, not as text. */
    @Test
    fun ordering() {
        assertTrue(AppVersion("1.2.3") < AppVersion("1.10.0"))
        assertTrue(AppVersion("0.9.0") < AppVersion("1.0.0"))
        assertTrue(AppVersion("2.0.0") > AppVersion("1.99.99"))
        assertEquals(AppVersion("1.2.0"), AppVersion("1.2"))
        assertEquals(AppVersion("0.0.0"), AppVersion(""))
        assertEquals(AppVersion("0.0.0"), AppVersion("not a version"))
    }

    /** Only a build below the minimum has to update. */
    @Test
    fun rule() {
        val apps = AppsInfo(ios = AppSupport(minimumVersion = "1.2.0", updateURL = "https://testflight.apple.com/join/X"))
        assertNotNull(AppUpdateRequirement.of(apps, current = "1.1.9"))
        assertNull(AppUpdateRequirement.of(apps, current = "1.2.0"))
        assertNull(AppUpdateRequirement.of(apps, current = "2.0.0"))
        assertNull(AppUpdateRequirement.of(null, current = "0.0.1"))
        assertNull(AppUpdateRequirement.of(AppsInfo(ios = null), current = "0.0.1"))
    }

    /** Only an https link is offered to the person. */
    @Test
    fun link() {
        val secure = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0", updateURL = "https://apps.apple.com/app/id1"))
        assertNotNull(AppUpdateRequirement.of(secure, current = "1.0.0")?.updateURL)
        val other = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0", updateURL = "itms://apps"))
        assertNull(AppUpdateRequirement.of(other, current = "1.0.0")?.updateURL)
        val none = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(none, current = "1.0.0")?.updateURL)
    }

    /** Each app is held to its own entry (A45). */
    @Test
    fun ownEntry() {
        val both = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0"), macos = AppSupport(minimumVersion = "2.0.0"))
        assertNotNull(AppUpdateRequirement.of(both, app = InstalledApp.macos, current = "1.5.0"))
        assertNull(AppUpdateRequirement.of(both, app = InstalledApp.ios, current = "1.5.0"))
        val iosOnly = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(iosOnly, app = InstalledApp.macos, current = "1.0.0"))
    }

    /** The Mac app's entry decodes beside the iPhone app's (A45). */
    @Test
    fun decodesMacEntry() {
        val apps = jsonObjectOf("ios" to mapOf("minimum_version" to "0.1.0"),
                                "macos" to mapOf("minimum_version" to "1.11.0")).decode<AppsInfo>()
        assertEquals("0.1.0", apps.ios?.minimumVersion)
        assertEquals("1.11.0", apps.macos?.minimumVersion)
        assertEquals("1.11.0", apps.support(InstalledApp.macos)?.minimumVersion)
    }

    /** The first source to say the build is too old wins, and signing out clears it. */
    @Test
    fun connectionRule() = runTest {
        val directory = scratchDirectory("connection-rule")
        try {
            val demanding = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay,
                                        minimumAppVersion = DemoFixtures.laterAppVersion)
            val store = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { demanding },
                                        makeChannel = { demanding })
            store.enterDemo(api = demanding, channel = demanding)
            settle { store.updateRequired != null }
            assertEquals(AppVersion(DemoFixtures.laterAppVersion), store.updateRequired?.minimum)
            store.signOut()
            assertNull(store.updateRequired)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Each of the four apps is held to its own entry, and to no other (A46). */
    @Test
    fun fourEntries() {
        val apps = AppsInfo(ios = AppSupport(minimumVersion = "1.0.0"), macos = AppSupport(minimumVersion = "2.0.0"),
                            android = AppSupport(minimumVersion = "3.0.0", updateURL = "https://play.google.com/store"),
                            windows = AppSupport(minimumVersion = "4.0.0"))
        assertNotNull(AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "2.5.0"))
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "3.0.0"))
        assertEquals("https://play.google.com/store",
                     AppUpdateRequirement.of(apps, app = InstalledApp.android, current = "2.5.0")?.updateURL?.toString())
        assertNotNull(AppUpdateRequirement.of(apps, app = InstalledApp.windows, current = "3.5.0"))
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.windows, current = "4.0.1"))
        // A gateway older than A46 names neither, and asks nothing of either.
        val older = AppsInfo(ios = AppSupport(minimumVersion = "9.0.0"), macos = AppSupport(minimumVersion = "9.0.0"))
        assertNull(AppUpdateRequirement.of(older, app = InstalledApp.android, current = "1.0.0"))
        assertNull(AppUpdateRequirement.of(older, app = InstalledApp.windows, current = "1.0.0"))
    }

    /** The Android and Windows entries decode from the frozen health response (A46). */
    @Test
    fun decodesAndroidAndWindowsEntries() {
        val apps = assertNotNull(FixtureSource.json("http/health.response.json").decode<HealthResponse>().apps)
        assertEquals("1.12.0", apps.android?.minimumVersion)
        assertEquals("1.12.0", apps.windows?.minimumVersion)
        assertEquals("1.12.0", apps.support(InstalledApp.android)?.minimumVersion)
        assertEquals("1.12.0", apps.support(InstalledApp.windows)?.minimumVersion)
    }

    /** The version an app states at startup is the one the rule reads; until then it is the shipped one. */
    @Test
    fun statedVersion() {
        assertEquals(AppBuild.shipped, AppBuild.version)
        val apps = AppsInfo(android = AppSupport(minimumVersion = "1.12.0"))
        try {
            AppBuild.version = "1.11.0"
            assertEquals(AppVersion("1.11.0"), AppUpdateRequirement.of(apps, app = InstalledApp.android)?.current)
        } finally {
            AppBuild.version = AppBuild.shipped
        }
        assertNull(AppUpdateRequirement.of(apps, app = InstalledApp.android))
    }
}

/** Amendment A30 — words another agent put into a Claude conversation. */
class AgentMessageTests {
    /** `agent` decodes as itself and reads like a turn nobody here started. */
    @Test
    fun source() {
        val payload = jsonObjectOf("text" to "recon-ios: done", "source" to "agent").decode<UserMessagePayload>()
        assertEquals(EventSource.agent, payload.source)
        assertTrue(payload.source.isElsewhere)
        assertTrue(EventSource.terminal.isElsewhere)
        assertFalse(EventSource.remote.isElsewhere)
        assertFalse(EventSource.queue.isElsewhere)
    }

    /** A turn another agent's message started carries the trigger. */
    @Test
    fun trigger() {
        val payload = jsonObjectOf("turn_id" to "t", "trigger" to "agent").decode<TurnStartedPayload>()
        assertEquals(EventSource.agent, payload.trigger)
        assertTrue(payload.trigger.isElsewhere)
    }
}

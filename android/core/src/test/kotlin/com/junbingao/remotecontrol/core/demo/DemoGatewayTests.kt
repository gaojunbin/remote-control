@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.BlockResult
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HistoryResult
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.protocol.Preferences
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.AppUpdateRequirement
import com.junbingao.remotecontrol.core.state.AppVersion
import com.junbingao.remotecontrol.core.state.InstalledApp
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The demo gateway driven through `GatewayAPI` and `GatewayChannel` the way a store drives it.
 * RCCore has no suite of this name — its stores' suites reach the demo through the stores — so
 * these hold what the demo itself serves, for the stores and screens built on it to trust.
 */
class DemoGatewayTests {
    /** Sign in, `hello`, subscribe, send: the scripted turns play to their ends as a store reads them. */
    @Test
    fun storeRoundTrip() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)

        // The sign-in form: whether it may register, the account, and what the gateway serves.
        assertFalse(gateway.health().registrationOpen)
        val login = gateway.login(username = DemoFixtures.adminUsername, password = "correct horse")
        assertEquals("demo", login.token)
        assertEquals(UserRole.admin, login.user.role)
        assertEquals(DemoFixtures.adminUsername, gateway.session().user.username)
        assertEquals("demo", gateway.bearerToken())
        assertEquals(DemoFixtures.servedBuild, gateway.config().servedBuild)

        // The socket: connecting, connected, and a hello carrying everything a store lists.
        gateway.connect()
        runCurrent()
        assertEquals(listOf(ConnectionState.connecting, ConnectionState.connected), log.states)
        val hello = assertNotNull(log.hello)
        assertEquals(RemoteProtocol.version, hello.protocolVersion)
        assertEquals(DemoFixtures.adminUsername, hello.user.username)
        assertEquals(DemoFixtures.devices.map { it.deviceID }, hello.devices.map { it.deviceID })
        assertEquals(DemoFixtures.sessions.map { it.sessionID }, hello.sessions.map { it.sessionID })
        assertEquals(Preferences(resumeAfterLimit = true), hello.preferences)
        val live = hello.sessions.first { it.sessionID == DemoFixtures.liveSessionID }
        val history = DemoFixtures.liveHistory()
        assertEquals(history.size, live.lastSeq, "the cursor is the transcript's last seq")

        // Subscribing from zero brings the transcript back, and the turn it was in plays on.
        val subscribed = gateway.request(GatewayRequest.subscribe(sessionID = live.sessionID, sinceSeq = 0),
                                         SubscribeResult.serializer())
        assertEquals(history.map { it.blockID }, subscribed.events.map { it.blockID })
        assertEquals(SessionState.running, subscribed.session.state)
        assertNull(subscribed.queue)
        settle()
        val turn = log.events(live.sessionID)
        assertEquals((history.size + 1..history.size + turn.size).toList(), turn.map { it.seq },
                     "seq rises by one from the transcript's last")
        assertEquals("tool-5", turn.first().blockID)
        assertEquals(ToolStatus.succeeded, turn.first().toolCall?.status)
        val streamed = turn.filter { it.blockID == "a-live" && it.streamText?.done == false }
        val done = turn.single { it.blockID == "a-live" && it.streamText?.done == true }
        assertEquals("All 100 runs passed. The shared clock was the only source of the flake.", done.streamText?.text)
        assertEquals(done.streamText?.text, streamed.joinToString("") { it.streamText?.delta.orEmpty() })
        assertEquals(listOf(SessionEvent.todosKind, SessionEvent.turnCompletedKind, SessionEvent.statusKind),
                     turn.takeLast(3).map { it.kind })
        val ended = log.updates(live.sessionID).last()
        assertEquals(SessionState.idle, ended.state)
        assertNull(ended.turn)
        assertEquals(TodoCounts(total = 4, done = 4), ended.todos)

        // Sending: accepted at once, echoed under the request's own id a round trip later, then answered.
        log.clear()
        val send = GatewayRequest.send(sessionID = live.sessionID, text = "Now add a regression test.")
        assertEquals(SendResult(accepted = SendAcceptance.sent), gateway.request(send, SendResult.serializer()))
        runCurrent()
        assertEquals(SessionState.running, log.updates(live.sessionID).last().state)
        assertTrue(log.events(live.sessionID).isEmpty(), "nothing is echoed before the device's round trip")
        advanceTimeBy(DemoGateway.defaultEchoDelay)
        runCurrent()
        val echo = log.events(live.sessionID).single()
        assertEquals(send.id, echo.blockID)
        assertEquals("Now add a regression test.", echo.userMessage?.text)
        assertEquals(EventSource.remote, echo.userMessage?.source)
        settle()
        val reply = log.events(live.sessionID).drop(1)
        assertEquals("Got it — running that now.", reply.single { it.streamText?.done == true }.streamText?.text)
        assertEquals(listOf(SessionEvent.turnCompletedKind, SessionEvent.statusKind), reply.takeLast(2).map { it.kind })
        assertEquals(SessionState.idle, log.updates(live.sessionID).last().state)

        // The history and a full block read the transcript the events wrote.
        val page = gateway.request(GatewayRequest.history(sessionID = live.sessionID), HistoryResult.serializer())
        assertEquals((1..page.events.size).toList(), page.events.map { it.seq })
        assertEquals(echo.seq + reply.size, page.events.last().seq)
        val block = gateway.request(GatewayRequest.block(sessionID = live.sessionID, blockID = "a-live"),
                                    BlockResult.serializer())
        assertEquals(done.seq, block.event.seq)

        gateway.disconnect()
        runCurrent()
        assertEquals(ConnectionState.disconnected, log.states.last())
    }

    /** Without a test's dispatcher the gateway keeps real time on its own, as the apps run it. */
    @Test
    fun defaultIsolationRunsOnRealTime() = runBlocking {
        val gateway = DemoGateway(echoDelay = 50.milliseconds, resumeDelay = null)
        gateway.connect()
        withTimeout(5.seconds) { gateway.events.first { (it as? GatewayEvent.Frame)?.frame is AppFrame.Hello } }
        val send = GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = "hello")
        assertEquals(SendResult(accepted = SendAcceptance.sent), gateway.request(send, SendResult.serializer()))
        val echo = withTimeout(5.seconds) {
            gateway.events.first { ((it as? GatewayEvent.Frame)?.frame as? AppFrame.SessionEvent)?.event?.blockID == send.id }
        }
        assertEquals("hello", ((echo as GatewayEvent.Frame).frame as AppFrame.SessionEvent).event.userMessage?.text)
        gateway.disconnect()
    }

    /**
     * Amendments A31, A45 and A46: every app reads its own entry of `apps`, and the demo names one
     * for each — this build by default, so nothing is blocked, and a later one on request.
     */
    @Test
    fun everyAppReadsItsOwnMinimum() = runTest {
        val running = demoGateway()
        for (app in InstalledApp.entries) {
            assertEquals(AppBuild.version, running.health().apps?.support(app)?.minimumVersion, "$app")
            assertNull(AppUpdateRequirement.of(running.config().apps, app = app, current = AppBuild.version), "$app")
        }
        val demanding = demoGateway(minimumAppVersion = DemoFixtures.laterAppVersion)
        val log = EventLog(backgroundScope, demanding)
        demanding.connect()
        runCurrent()
        for (app in InstalledApp.entries) {
            val required = assertNotNull(AppUpdateRequirement.of(log.hello?.apps, app = app, current = AppBuild.version))
            assertEquals(AppVersion(DemoFixtures.laterAppVersion), required.minimum)
        }
    }

    /** Amendment A23: a pairing walks its four steps, a claimed host its own code, and a cancel stops it. */
    @Test
    fun pairingWalksItsSteps() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        val grant = gateway.beginPairing()
        settle()
        val steps = log.frames.filterIsInstance<AppFrame.PairingProgress>().map { it.progress }
        assertEquals(listOf(PairingStep.waiting, PairingStep.enrolled, PairingStep.online, PairingStep.agents),
                     steps.map { it.step })
        assertTrue(steps.all { it.code == grant.code })
        assertEquals(DemoFixtures.macDeviceID, steps.last().device?.deviceID, "the last step names the machine")

        assertEquals(GatewayErrorCode.notFound, assertFailsWith<GatewayErrorBody> {
            gateway.claimPairingRequest(token = "0000000000000000000000000A")
        }.code)
        log.clear()
        val claim = gateway.claimPairingRequest(token = DemoFixtures.claimToken)
        advanceTimeBy(1.seconds)
        gateway.cancelPairing(code = claim.code)
        settle()
        // As in RCCore, the step being waited for still goes out — a cancelled pause ends early and
        // the script looks for cancellation only before the next one — and nothing after it does.
        val claimed = log.frames.filterIsInstance<AppFrame.PairingProgress>().map { it.progress }
        assertEquals(listOf(PairingStep.waiting, PairingStep.enrolled), claimed.map { it.step })
        assertTrue(claimed.all { it.code == claim.code })
    }

    /**
     * Amendments A29 and A41: opening the Voice group is what the demo reads as Settings being on
     * screen, so the account's other device turns polish on a moment later — once — and the
     * stand-in model cleans the words it is given.
     */
    @Test
    fun anotherDeviceTurnsPolishOn() = runTest {
        val gateway = demoGateway()
        val log = EventLog(backgroundScope, gateway)
        assertEquals(listOf("gpt-4.1-mini", "gpt-4.1"), gateway.polishModels().models.map { it.id })
        gateway.polishModels()
        advanceTimeBy(DemoGateway.defaultElsewhereDelay)
        runCurrent()
        val changed = log.frames.filterIsInstance<AppFrame.PreferencesUpdated>().map { it.preferences }
        assertEquals(listOf(true), changed.map { it.polishEnabled }, "one change, made elsewhere")
        assertEquals(true, gateway.preferences().preferences.polishEnabled)

        val start = currentTime
        val polished = gateway.polish(PolishRequest(text = "um so like I think think we should uh go",
                                                    model = "gpt-4.1-mini", strength = PolishStrength.moderate))
        assertEquals("So I think we should go", polished.text)
        assertEquals(DemoGateway.defaultPolishDelay.inWholeMilliseconds, currentTime - start)

        val still = demoGateway(changesPreferencesElsewhere = false)
        val quiet = EventLog(backgroundScope, still)
        still.polishModels()
        settle()
        assertTrue(quiet.frames.none { it is AppFrame.PreferencesUpdated }, "a test reading Settings asks for nothing to move")
    }

    /**
     * Amendment A15: the session left in the Archive is resumed from its terminal a few seconds
     * after the list opens, in one `session.updated`.
     */
    @Test
    fun archivedSessionRevives() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val log = EventLog(backgroundScope, gateway)
        gateway.connect()
        advanceTimeBy(DemoGateway.defaultResumeDelay - 1.milliseconds)
        assertTrue(log.updates(DemoFixtures.revivedSessionID).isEmpty())
        settle()
        val revived = log.updates(DemoFixtures.revivedSessionID).single()
        assertFalse(revived.archived)
        assertEquals(EventSource.terminal, revived.origin)
        assertEquals(SessionControl.terminal, revived.control)
        assertEquals(SessionState.idle, revived.state)
    }
}

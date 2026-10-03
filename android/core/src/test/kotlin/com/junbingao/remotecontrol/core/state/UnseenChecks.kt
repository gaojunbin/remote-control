package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.objectValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.PushRoute
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * `ios/Verification/UnseenChecks.swift`: amendment A47's red dot, the badge that counts them, and
 * `session.seen` — the fixtures, the gateway's rule as the demo keeps it, and the store's one request
 * per mark.
 */
class UnseenChecks {
    private val directories = mutableListOf<File>()

    @AfterTest
    fun removeDirectories() {
        for (directory in directories) directory.deleteRecursively()
    }

    @Test
    fun fixtures() {
        val checks = CheckRunner("unseen")
        val sessions = runCatching { FixtureSource.json("app/hello.json")["sessions"]?.decode<List<Session>>() }.getOrNull()
        if (sessions != null && sessions.size == 2) {
            checks.expect(sessions[0].unseen, "a hello session carries the gateway's mark")
            checks.expect(!sessions[1].unseen, "and one without the field carries none")
        } else {
            checks.expect(false, "app/hello.json carries two sessions")
        }
        val expected = FixtureSource.json("app/session.seen.json").objectValue
        if (expected != null) {
            val built = GatewayRequest.seen(sessionID = expected["session_id"]?.stringValue ?: "").json
            checks.equal(built["type"], expected["type"], "session.seen is named as the fixture names it")
            checks.equal(built["session_id"], expected["session_id"], "and names the session the same way")
            checks.equal(built.keys, expected.keys, "and carries the fixture's fields and no others")
        } else {
            checks.expect(false, "app/session.seen.json exists")
        }
        val route = runCatching { FixtureSource.json("http/push.payload.badge.json")["rc"]?.decode<PushRoute>() }.getOrNull()
        if (route != null) {
            checks.equal(route.kind, PushKind.badge, "the badge-only push says what it is")
            checks.equal(route.badge, 0, "and carries the count")
        } else {
            checks.expect(false, "http/push.payload.badge.json decodes as a push route")
        }
        val alert = runCatching { FixtureSource.json("http/push.payload.json")["rc"]?.decode<PushRoute>() }.getOrNull()
        checks.equal(alert?.badge, 1, "every push carries the count")
        checks.assertAll()
    }

    @Test
    fun rule() {
        val checks = CheckRunner("unseen")
        fun session(state: SessionState, control: SessionControl = SessionControl.remote, unseen: Boolean = false,
                    archived: Boolean = false): Session =
            Session(sessionID = "s", deviceID = "d", agent = "claude", title = "t", cwd = "/", state = state,
                    control = control, archived = archived, unseen = unseen)
        val running = session(SessionState.running)
        for (waiting in listOf(session(SessionState.needsApproval), session(SessionState.needsInput), session(SessionState.idle),
                               session(SessionState.idle, SessionControl.shared),
                               session(SessionState.readonly, SessionControl.terminal))) {
            checks.expect(UnseenMark.next(previous = running, current = waiting),
                          "running → ${waiting.state} (${waiting.control}) marks the session")
        }
        checks.expect(!UnseenMark.next(previous = session(SessionState.starting), current = session(SessionState.idle)),
                      "but a session that only started has done nothing to look at")
        for (quiet in listOf(session(SessionState.idle, SessionControl.none), session(SessionState.error),
                             session(SessionState.stopped))) {
            checks.expect(!UnseenMark.next(previous = running, current = quiet),
                          "running → ${quiet.state} (${quiet.control}) waits for nobody")
        }
        checks.expect(UnseenMark.next(previous = session(SessionState.needsApproval, unseen = true), current = session(SessionState.idle)),
                      "waiting → waiting keeps the mark")
        checks.expect(!UnseenMark.next(previous = session(SessionState.needsApproval), current = session(SessionState.idle)),
                      "and keeps its absence")
        checks.expect(!UnseenMark.next(previous = session(SessionState.idle, unseen = true), current = session(SessionState.running)),
                      "working again clears it")
        checks.expect(!UnseenMark.next(previous = session(SessionState.idle, unseen = true),
                                       current = session(SessionState.stopped, SessionControl.none, archived = true)),
                      "and so does archiving")
        val marks = listOf(session(SessionState.idle, unseen = true), session(SessionState.idle, unseen = true, archived = true),
                           session(SessionState.idle))
        checks.equal(UnseenMark.count(marks), 1, "the badge counts the unarchived sessions with the mark")
        checks.equal(UnseenMark.count(marks, excluding = "d/s"), 0, "less the conversation in front of the person")
        checks.assertAll()
    }

    /** One `session.seen` per mark, and none for a session without one. */
    @Test
    fun requests() = runTest {
        val checks = CheckRunner("unseen")
        val channel = StoreChecks.ScriptedChannel()
        val connection = ConnectionStore(tasks = backgroundScope, cache = cache(), makeAPI = { StubGateway(it) })
        connection.enterDemo(api = demoGateway(), channel = channel)
        settle { connection.hasSnapshot }
        checks.equal(connection.unseenCount, 1, "the demo's hello carries one mark")
        val mac = DemoFixtures.macDeviceID

        connection.markSeen(deviceID = mac, sessionID = DemoFixtures.liveSessionID)
        checks.equal(channel.requests(ofType = "session.seen").size, 0, "a session without the mark asks for nothing")
        connection.markSeen(deviceID = mac, sessionID = DemoFixtures.approvalSessionID)
        connection.markSeen(deviceID = mac, sessionID = DemoFixtures.approvalSessionID)
        checks.equal(channel.requests(ofType = "session.seen").map { it.body["session_id"]?.stringValue },
                     listOf(DemoFixtures.approvalSessionID), "one request for one mark")

        val listed = connection.session(deviceID = mac, sessionID = DemoFixtures.approvalSessionID)
        if (listed == null) {
            checks.expect(false, "the marked session is listed")
        } else {
            val cleared = listed.copy(unseen = false)
            channel.emit(AppFrame.SessionUpdated(cleared))
            settle { connection.unseenCount == 0 }
            channel.emit(AppFrame.SessionUpdated(cleared.copy(unseen = true)))
            settle { connection.unseenCount == 1 }
            connection.markSeen(deviceID = mac, sessionID = DemoFixtures.approvalSessionID)
            checks.equal(channel.requests(ofType = "session.seen").size, 2, "a new mark is a new request")
        }
        connection.signOut()
        checks.assertAll()
    }

    /** The demo keeps the mark by the gateway's rule, so the demo shows it. */
    @Test
    fun demo() = runTest {
        val checks = CheckRunner("unseen")
        val gateway = demoGateway(echoDelay = Duration.ZERO)
        val connection = ConnectionStore(tasks = backgroundScope, cache = cache(), makeAPI = { StubGateway(it) })
        connection.enterDemo(api = gateway, channel = gateway)
        settle { connection.hasSnapshot }
        fun pi(): Session? = connection.sessions.firstOrNull { it.sessionID == DemoFixtures.piSessionID }

        runCatching { gateway.request(GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = "go")) }
        settle { pi()?.unseen == true }
        checks.expect(pi()?.unseen == true, "a demo turn that ends unwatched marks its session")
        checks.equal(connection.unseenCount, 2, "and the badge counts it with the one the demo started with")

        connection.markSeen(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.piSessionID)
        settle { pi()?.unseen == false }
        checks.expect(pi()?.unseen == false, "and session.seen takes it off")

        runCatching { gateway.request(GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = "again")) }
        settle { pi()?.state == SessionState.running }
        checks.expect(pi()?.unseen == false, "a session at work carries no mark")
        connection.signOut()
        checks.assertAll()
    }

    private fun cache(): LocalCache = LocalCache(scratchDirectory("unseen-checks").also { directories.add(it) })
}
